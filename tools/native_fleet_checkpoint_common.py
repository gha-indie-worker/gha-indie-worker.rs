#!/usr/bin/env python3
"""Checkpoint envelope, generic validation, and secret-free integrity helpers."""

from __future__ import annotations

import base64
import copy
import hashlib
import hmac
import re
from datetime import datetime, timedelta
from typing import Any, Mapping

from .native_fleet_validation import *  # noqa: F401,F403
from .native_fleet_validation import _reject_secret_fields, _require_exact_keys

CHECKPOINT_SCHEMA = "gha-indie-worker.fleet-checkpoint.v1"
CHECKPOINT_STATE_SCHEMA = "gha-indie-worker.fleet-state.v1"
CHECKPOINT_SIGNATURE_ALGORITHM = "hmac-sha256-checkpoint-v1"

CHECKPOINT_KEY_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
TOKEN_HASH_RE = re.compile(r"^[0-9a-f]{64}$")
SIGNATURE_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")

_CHECKPOINT_KEYS = {
    "schemaVersion",
    "keyId",
    "createdAt",
    "stateDigest",
    "signatureAlgorithm",
    "signature",
    "state",
}
_STATE_KEYS = {
    "schemaVersion",
    "minimumAgentVersion",
    "heartbeatTtlSeconds",
    "leaseTtlSeconds",
    "maximumAttestationTtlSeconds",
    "assignmentSequence",
    "bootstrapGrants",
    "identities",
    "hosts",
    "leases",
    "requestToLease",
    "receipts",
}

def _integrity_key(value: Any) -> bytes:
    if not isinstance(value, bytes) or len(value) < 32:
        raise FleetError(
            "checkpoint_integrity_key_invalid",
            "checkpoint integrity keys must contain at least 32 bytes",
        )
    return value

def _bounded_identifier(value: Any, field_name: str) -> str:
    # Dispatch request IDs are opaque protocol values. Preserve every printable
    # identifier accepted by dispatch-v2 instead of silently narrowing it to a
    # checkpoint-only alphabet. Control characters and unbounded values still
    # fail closed.
    if (
        not isinstance(value, str)
        or not value
        or len(value) > 512
        or any(ord(character) < 0x20 or ord(character) == 0x7F for character in value)
    ):
        raise FleetError("checkpoint_identifier_invalid", f"{field_name} is invalid")
    return value

def _nonnegative_integer(value: Any, field_name: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise FleetError(
            "checkpoint_integer_invalid", f"{field_name} must be a non-negative integer"
        )
    return value

def _positive_seconds(value: Any, field_name: str) -> int:
    value = _nonnegative_integer(value, field_name)
    if value == 0 or value > 604_800:
        raise FleetError(
            "checkpoint_duration_invalid",
            f"{field_name} must be between 1 and 604800 seconds",
        )
    return value

def _sorted_unique_objects(
    values: Any,
    *,
    field_name: str,
    key_name: str,
    maximum: int = 10_000,
) -> list[Mapping[str, Any]]:
    if not isinstance(values, list) or len(values) > maximum:
        raise FleetError(
            "checkpoint_list_invalid",
            f"{field_name} must be a list with at most {maximum} entries",
        )
    if any(not isinstance(value, Mapping) for value in values):
        raise FleetError(
            "checkpoint_list_invalid", f"{field_name} entries must be objects"
        )
    keys = [value.get(key_name) for value in values]
    if any(not isinstance(value, str) for value in keys):
        raise FleetError(
            "checkpoint_list_invalid", f"{field_name}.{key_name} values must be strings"
        )
    if keys != sorted(keys):
        raise FleetError(
            "checkpoint_order_invalid", f"{field_name} must be sorted by {key_name}"
        )
    if len(keys) != len(set(keys)):
        raise FleetError(
            "checkpoint_duplicate", f"{field_name} contains duplicate {key_name} values"
        )
    return list(values)

def _signature_fields(
    *,
    key_id: str,
    created_at: str,
    state_digest: str,
) -> dict[str, str]:
    return {
        "schemaVersion": CHECKPOINT_SCHEMA,
        "keyId": key_id,
        "createdAt": created_at,
        "stateDigest": state_digest,
    }

def _checkpoint_signature(key: bytes, fields: Mapping[str, Any]) -> str:
    return base64.urlsafe_b64encode(
        hmac.new(key, canonical_bytes(fields), hashlib.sha256).digest()
    ).decode().rstrip("=")

def seal_checkpoint_state(
    state: Mapping[str, Any],
    *,
    integrity_key_id: str,
    integrity_key: bytes,
    created_at: datetime,
) -> dict[str, Any]:
    """Seal a secret-free state object for durable lab storage.

    This helper intentionally validates only the generic envelope boundary. Full
    state schema and cross-reference validation occurs during restore, which lets
    fault tests create correctly signed but internally inconsistent checkpoints.
    """

    created_at = require_utc(created_at)
    if not isinstance(integrity_key_id, str) or not CHECKPOINT_KEY_ID_RE.fullmatch(
        integrity_key_id
    ):
        raise FleetError("checkpoint_key_id_invalid", "checkpoint key ID is invalid")
    key = _integrity_key(integrity_key)
    if not isinstance(state, Mapping):
        raise FleetError("checkpoint_state_invalid", "checkpoint state must be an object")
    state_copy = copy.deepcopy(dict(state))
    _reject_secret_fields(state_copy)
    state_digest = sha256_digest(state_copy)
    created_at_text = format_time(created_at)
    fields = _signature_fields(
        key_id=integrity_key_id,
        created_at=created_at_text,
        state_digest=state_digest,
    )
    return {
        **fields,
        "signatureAlgorithm": CHECKPOINT_SIGNATURE_ALGORITHM,
        "signature": _checkpoint_signature(key, fields),
        "state": state_copy,
    }

def _verify_checkpoint(
    checkpoint: Mapping[str, Any],
    *,
    integrity_keys: Mapping[str, bytes],
    now: datetime,
) -> dict[str, Any]:
    now = require_utc(now)
    if not isinstance(checkpoint, Mapping):
        raise FleetError("checkpoint_invalid", "checkpoint must be an object")
    _require_exact_keys(checkpoint, _CHECKPOINT_KEYS, "checkpoint")
    if checkpoint["schemaVersion"] != CHECKPOINT_SCHEMA:
        raise FleetError("checkpoint_schema_unsupported", "unsupported checkpoint schema")
    key_id = checkpoint["keyId"]
    if not isinstance(key_id, str) or not CHECKPOINT_KEY_ID_RE.fullmatch(key_id):
        raise FleetError("checkpoint_key_id_invalid", "checkpoint key ID is invalid")
    if not isinstance(integrity_keys, Mapping):
        raise FleetError(
            "checkpoint_integrity_keys_invalid", "integrity key resolver must be a mapping"
        )
    key = integrity_keys.get(key_id)
    if key is None:
        raise FleetError(
            "checkpoint_integrity_key_unknown", "checkpoint integrity key is unavailable"
        )
    key = _integrity_key(key)
    created_at = parse_time(checkpoint["createdAt"], "createdAt")
    if created_at > now + timedelta(seconds=30):
        raise FleetError(
            "checkpoint_from_future", "checkpoint createdAt is too far in the future"
        )
    state = checkpoint["state"]
    if not isinstance(state, Mapping):
        raise FleetError("checkpoint_state_invalid", "checkpoint state must be an object")
    state_copy = copy.deepcopy(dict(state))
    _reject_secret_fields(state_copy)
    expected_digest = sha256_digest(state_copy)
    if checkpoint["stateDigest"] != expected_digest:
        raise FleetError(
            "checkpoint_digest_mismatch", "checkpoint stateDigest does not match state"
        )
    if checkpoint["signatureAlgorithm"] != CHECKPOINT_SIGNATURE_ALGORITHM:
        raise FleetError(
            "checkpoint_signature_algorithm_unsupported",
            "unsupported checkpoint signature algorithm",
        )
    signature = checkpoint["signature"]
    if not isinstance(signature, str) or not SIGNATURE_RE.fullmatch(signature):
        raise FleetError("checkpoint_signature_invalid", "checkpoint signature is invalid")
    fields = _signature_fields(
        key_id=key_id,
        created_at=checkpoint["createdAt"],
        state_digest=expected_digest,
    )
    expected_signature = _checkpoint_signature(key, fields)
    if not hmac.compare_digest(signature, expected_signature):
        raise FleetError("checkpoint_signature_invalid", "checkpoint signature is invalid")
    return state_copy
