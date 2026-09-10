#!/usr/bin/env python3
"""Strict checkpoint codecs for leases and terminal receipts."""

from __future__ import annotations

from typing import Any, Mapping

from .native_fleet_validation import *  # noqa: F401,F403
from .native_fleet_validation import _require_exact_keys
from .native_fleet_checkpoint_common import _bounded_identifier

def _lease_from_dict(value: Mapping[str, Any]) -> Lease:
    _require_exact_keys(
        value,
        {
            "schemaVersion",
            "leaseId",
            "requestId",
            "requestDigest",
            "hostId",
            "keyId",
            "nonce",
            "issuedAt",
            "expiresAt",
            "attempt",
            "repositoryUrl",
            "commitSha",
            "profile",
            "profileDigest",
            "capabilityDigest",
            "hostCapabilitySnapshot",
            "cancelRequested",
            "terminalStatus",
        },
        "checkpoint lease",
    )
    if value["schemaVersion"] != LEASE_SCHEMA:
        raise FleetError("checkpoint_lease_schema", "unsupported lease schema")
    lease_id = _bounded_identifier(value["leaseId"], "leaseId")
    request_id = _bounded_identifier(value["requestId"], "requestId")
    host_id = value["hostId"]
    key_id = value["keyId"]
    if not isinstance(host_id, str) or not HOST_ID_RE.fullmatch(host_id):
        raise FleetError("checkpoint_host_id_invalid", "lease hostId is invalid")
    if not isinstance(key_id, str) or not KEY_ID_RE.fullmatch(key_id):
        raise FleetError("checkpoint_key_id_invalid", "lease keyId is invalid")
    for field_name in ("requestDigest", "profileDigest", "capabilityDigest"):
        if not isinstance(value[field_name], str) or not SHA256_RE.fullmatch(
            value[field_name]
        ):
            raise FleetError(
                "checkpoint_digest_invalid", f"lease {field_name} is invalid"
            )
    nonce = _bounded_identifier(value["nonce"], "nonce")
    issued_at = parse_time(value["issuedAt"], "issuedAt")
    expires_at = parse_time(value["expiresAt"], "expiresAt")
    if expires_at <= issued_at:
        raise FleetError("checkpoint_lease_time_invalid", "lease expiry must follow issue")
    attempt = value["attempt"]
    if not isinstance(attempt, int) or isinstance(attempt, bool) or attempt < 1:
        raise FleetError("checkpoint_attempt_invalid", "lease attempt must be positive")
    repository_url = value["repositoryUrl"]
    if not isinstance(repository_url, str) or not repository_url.startswith(
        "https://github.com/"
    ):
        raise FleetError("checkpoint_repository_invalid", "lease repository is invalid")
    commit_sha = value["commitSha"]
    if not isinstance(commit_sha, str) or not SHA1_RE.fullmatch(commit_sha):
        raise FleetError("checkpoint_commit_invalid", "lease commit SHA is invalid")
    profile = value["profile"]
    if not isinstance(profile, str) or not NAME_RE.fullmatch(profile):
        raise FleetError("checkpoint_profile_invalid", "lease profile is invalid")
    snapshot = validate_host_capability(value["hostCapabilitySnapshot"], "0.0.0")
    if sha256_digest(snapshot) != value["capabilityDigest"]:
        raise FleetError(
            "checkpoint_capability_digest_mismatch",
            "lease capability snapshot does not match capabilityDigest",
        )
    cancel_requested = value["cancelRequested"]
    if not isinstance(cancel_requested, bool):
        raise FleetError(
            "checkpoint_cancel_invalid", "lease cancelRequested must be boolean"
        )
    terminal_status = value["terminalStatus"]
    if terminal_status is not None and terminal_status not in TERMINAL_STATES:
        raise FleetError(
            "checkpoint_terminal_status_invalid", "lease terminalStatus is invalid"
        )
    return Lease(
        schema_version=LEASE_SCHEMA,
        lease_id=lease_id,
        request_id=request_id,
        request_digest=value["requestDigest"],
        host_id=host_id,
        key_id=key_id,
        nonce=nonce,
        issued_at=issued_at,
        expires_at=expires_at,
        attempt=attempt,
        repository_url=repository_url,
        commit_sha=commit_sha,
        profile=profile,
        profile_digest=value["profileDigest"],
        capability_digest=value["capabilityDigest"],
        host_capability_snapshot=snapshot,
        cancel_requested=cancel_requested,
        terminal_status=terminal_status,
    )

def _receipt_from_dict(value: Mapping[str, Any]) -> TerminalReceipt:
    _require_exact_keys(
        value,
        {
            "schemaVersion",
            "leaseId",
            "requestId",
            "requestDigest",
            "hostId",
            "keyId",
            "status",
            "completedAt",
            "attempt",
            "profile",
            "profileDigest",
            "capabilityDigest",
            "runManifestDigest",
            "cancelRequested",
        },
        "checkpoint receipt",
    )
    if value["schemaVersion"] != TERMINAL_RECEIPT_SCHEMA:
        raise FleetError("checkpoint_receipt_schema", "unsupported receipt schema")
    lease_id = _bounded_identifier(value["leaseId"], "leaseId")
    request_id = _bounded_identifier(value["requestId"], "requestId")
    host_id = value["hostId"]
    key_id = value["keyId"]
    if not isinstance(host_id, str) or not HOST_ID_RE.fullmatch(host_id):
        raise FleetError("checkpoint_host_id_invalid", "receipt hostId is invalid")
    if not isinstance(key_id, str) or not KEY_ID_RE.fullmatch(key_id):
        raise FleetError("checkpoint_key_id_invalid", "receipt keyId is invalid")
    status = value["status"]
    if status not in TERMINAL_STATES:
        raise FleetError("checkpoint_terminal_status_invalid", "receipt status is invalid")
    for field_name in (
        "requestDigest",
        "profileDigest",
        "capabilityDigest",
        "runManifestDigest",
    ):
        if not isinstance(value[field_name], str) or not SHA256_RE.fullmatch(
            value[field_name]
        ):
            raise FleetError(
                "checkpoint_digest_invalid", f"receipt {field_name} is invalid"
            )
    profile = value["profile"]
    if not isinstance(profile, str) or not NAME_RE.fullmatch(profile):
        raise FleetError("checkpoint_profile_invalid", "receipt profile is invalid")
    attempt = value["attempt"]
    if not isinstance(attempt, int) or isinstance(attempt, bool) or attempt < 1:
        raise FleetError("checkpoint_attempt_invalid", "receipt attempt must be positive")
    cancel_requested = value["cancelRequested"]
    if not isinstance(cancel_requested, bool):
        raise FleetError(
            "checkpoint_cancel_invalid", "receipt cancelRequested must be boolean"
        )
    return TerminalReceipt(
        schema_version=TERMINAL_RECEIPT_SCHEMA,
        lease_id=lease_id,
        request_id=request_id,
        request_digest=value["requestDigest"],
        host_id=host_id,
        key_id=key_id,
        status=status,
        completed_at=parse_time(value["completedAt"], "completedAt"),
        attempt=attempt,
        profile=profile,
        profile_digest=value["profileDigest"],
        capability_digest=value["capabilityDigest"],
        run_manifest_digest=value["runManifestDigest"],
        cancel_requested=cancel_requested,
    )
