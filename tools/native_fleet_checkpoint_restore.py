#!/usr/bin/env python3
"""Fail-closed native-fleet checkpoint restoration."""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any, Mapping

from .native_fleet_validation import *  # noqa: F401,F403
from .native_fleet_validation import _require_exact_keys
from .native_fleet_checkpoint_common import (
    CHECKPOINT_STATE_SCHEMA,
    TOKEN_HASH_RE,
    _STATE_KEYS,
    _bounded_identifier,
    _nonnegative_integer,
    _positive_seconds,
    _sorted_unique_objects,
    _verify_checkpoint,
)
from .native_fleet_checkpoint_codec import _lease_from_dict, _receipt_from_dict


class CheckpointRestoreMixin:
    """Restore exact schema and authority references from a sealed checkpoint."""

    @classmethod
    def restore_checkpoint(
        cls,
        checkpoint: Mapping[str, Any],
        *,
        integrity_keys: Mapping[str, bytes],
        identity_secrets: Mapping[str, bytes],
        now: datetime,
        sweep: bool = True,
    ):
        now = require_utc(now)
        state = _verify_checkpoint(
            checkpoint,
            integrity_keys=integrity_keys,
            now=now,
        )
        _require_exact_keys(state, _STATE_KEYS, "checkpoint state")
        if state["schemaVersion"] != CHECKPOINT_STATE_SCHEMA:
            raise FleetError(
                "checkpoint_state_schema_unsupported", "unsupported checkpoint state schema"
            )
        minimum_agent_version = state["minimumAgentVersion"]
        version_tuple(minimum_agent_version)
        fleet = cls(
            minimum_agent_version=minimum_agent_version,
            heartbeat_ttl=timedelta(
                seconds=_positive_seconds(
                    state["heartbeatTtlSeconds"], "heartbeatTtlSeconds"
                )
            ),
            lease_ttl=timedelta(
                seconds=_positive_seconds(state["leaseTtlSeconds"], "leaseTtlSeconds")
            ),
            maximum_attestation_ttl=timedelta(
                seconds=_positive_seconds(
                    state["maximumAttestationTtlSeconds"],
                    "maximumAttestationTtlSeconds",
                )
            ),
        )
        fleet._assignment_sequence = _nonnegative_integer(
            state["assignmentSequence"], "assignmentSequence"
        )

        grants = _sorted_unique_objects(
            state["bootstrapGrants"],
            field_name="bootstrapGrants",
            key_name="tokenHash",
        )
        for value in grants:
            _require_exact_keys(
                value,
                {
                    "tokenHash",
                    "hostId",
                    "platform",
                    "architecture",
                    "trustTier",
                    "expiresAt",
                    "used",
                },
                "checkpoint bootstrap grant",
            )
            token_hash = value["tokenHash"]
            host_id = value["hostId"]
            if not isinstance(token_hash, str) or not TOKEN_HASH_RE.fullmatch(token_hash):
                raise FleetError(
                    "checkpoint_token_hash_invalid", "bootstrap tokenHash is invalid"
                )
            if not isinstance(host_id, str) or not HOST_ID_RE.fullmatch(host_id):
                raise FleetError(
                    "checkpoint_host_id_invalid", "bootstrap hostId is invalid"
                )
            if value["platform"] not in PLATFORMS:
                raise FleetError(
                    "checkpoint_platform_invalid", "bootstrap platform is invalid"
                )
            if value["architecture"] not in ARCHITECTURES:
                raise FleetError(
                    "checkpoint_architecture_invalid", "bootstrap architecture is invalid"
                )
            if value["trustTier"] not in TRUST_TIERS:
                raise FleetError(
                    "checkpoint_trust_invalid", "bootstrap trustTier is invalid"
                )
            if not isinstance(value["used"], bool):
                raise FleetError(
                    "checkpoint_used_invalid", "bootstrap used must be boolean"
                )
            fleet.bootstrap_grants[token_hash] = BootstrapGrant(
                token_hash=token_hash,
                host_id=host_id,
                platform=value["platform"],
                architecture=value["architecture"],
                trust_tier=value["trustTier"],
                expires_at=parse_time(value["expiresAt"], "expiresAt"),
                used=value["used"],
            )

        if not isinstance(identity_secrets, Mapping):
            raise FleetError(
                "identity_secret_resolver_invalid",
                "identity secret resolver must be a mapping",
            )
        identities = _sorted_unique_objects(
            state["identities"], field_name="identities", key_name="keyId"
        )
        known_identity_ids: set[str] = set()
        for value in identities:
            _require_exact_keys(
                value,
                {"hostId", "keyId", "expiresAt", "revoked"},
                "checkpoint identity",
            )
            host_id = value["hostId"]
            key_id = value["keyId"]
            if not isinstance(host_id, str) or not HOST_ID_RE.fullmatch(host_id):
                raise FleetError(
                    "checkpoint_host_id_invalid", "identity hostId is invalid"
                )
            if not isinstance(key_id, str) or not KEY_ID_RE.fullmatch(key_id):
                raise FleetError("checkpoint_key_id_invalid", "identity keyId is invalid")
            if not key_id.startswith(f"device:{host_id}:"):
                raise FleetError(
                    "checkpoint_identity_host_mismatch",
                    "identity keyId is not bound to hostId",
                )
            revoked = value["revoked"]
            if not isinstance(revoked, bool):
                raise FleetError(
                    "checkpoint_revoked_invalid", "identity revoked must be boolean"
                )
            secret = identity_secrets.get(key_id)
            if secret is None and not revoked:
                raise FleetError(
                    "identity_secret_missing",
                    f"external identity secret is unavailable for {key_id}",
                )
            if secret is None:
                secret = b""
            if not isinstance(secret, bytes) or (not revoked and len(secret) < 16):
                raise FleetError(
                    "identity_secret_invalid",
                    f"external identity secret is invalid for {key_id}",
                )
            known_identity_ids.add(key_id)
            fleet.identities[key_id] = DeviceIdentity(
                host_id=host_id,
                key_id=key_id,
                secret=secret,
                expires_at=parse_time(value["expiresAt"], "expiresAt"),
                revoked=revoked,
            )
        unknown_secrets = sorted(set(identity_secrets) - known_identity_ids)
        if unknown_secrets:
            raise FleetError(
                "identity_secret_unknown",
                f"identity secret resolver contains unknown keys {unknown_secrets}",
            )

        hosts = _sorted_unique_objects(
            state["hosts"], field_name="hosts", key_name="hostId"
        )
        for value in hosts:
            _require_exact_keys(
                value,
                {
                    "hostId",
                    "keyId",
                    "expectedPlatform",
                    "expectedArchitecture",
                    "expectedTrustTier",
                    "state",
                    "capability",
                    "capabilityDigest",
                    "lastSeenAt",
                    "activeLeaseIds",
                    "assignmentCount",
                    "lastAssignedSequence",
                    "recoveryGeneration",
                    "quarantineReason",
                },
                "checkpoint host",
            )
            host_id = value["hostId"]
            key_id = value["keyId"]
            if not isinstance(host_id, str) or not HOST_ID_RE.fullmatch(host_id):
                raise FleetError("checkpoint_host_id_invalid", "hostId is invalid")
            if not isinstance(key_id, str) or not KEY_ID_RE.fullmatch(key_id):
                raise FleetError("checkpoint_key_id_invalid", "host keyId is invalid")
            platform = value["expectedPlatform"]
            architecture = value["expectedArchitecture"]
            trust_tier = value["expectedTrustTier"]
            if platform not in PLATFORMS:
                raise FleetError("checkpoint_platform_invalid", "host platform is invalid")
            if architecture not in ARCHITECTURES:
                raise FleetError(
                    "checkpoint_architecture_invalid", "host architecture is invalid"
                )
            if trust_tier not in TRUST_TIERS:
                raise FleetError("checkpoint_trust_invalid", "host trust tier is invalid")
            if value["state"] not in HOST_STATES:
                raise FleetError("checkpoint_host_state_invalid", "host state is invalid")
            capability = value["capability"]
            capability_digest = value["capabilityDigest"]
            if capability is None:
                if capability_digest is not None:
                    raise FleetError(
                        "checkpoint_capability_digest_mismatch",
                        "host capabilityDigest requires capability",
                    )
                normalized_capability = None
            else:
                normalized_capability = validate_host_capability(
                    capability, minimum_agent_version
                )
                if capability_digest != sha256_digest(normalized_capability):
                    raise FleetError(
                        "checkpoint_capability_digest_mismatch",
                        "host capabilityDigest does not match capability",
                    )
                if (
                    normalized_capability["hostId"] != host_id
                    or normalized_capability["platform"] != platform
                    or normalized_capability["architecture"] != architecture
                    or normalized_capability["trustTier"] != trust_tier
                ):
                    raise FleetError(
                        "checkpoint_capability_host_mismatch",
                        "host capability differs from enrolled host binding",
                    )
            active_lease_ids = value["activeLeaseIds"]
            if (
                not isinstance(active_lease_ids, list)
                or any(not isinstance(item, str) for item in active_lease_ids)
                or active_lease_ids != sorted(active_lease_ids)
                or len(active_lease_ids) != len(set(active_lease_ids))
            ):
                raise FleetError(
                    "checkpoint_active_leases_invalid",
                    "host activeLeaseIds must be sorted and unique",
                )
            quarantine_reason = value["quarantineReason"]
            if quarantine_reason is not None and (
                not isinstance(quarantine_reason, str)
                or not quarantine_reason
                or len(quarantine_reason) > 512
            ):
                raise FleetError(
                    "checkpoint_quarantine_reason_invalid",
                    "host quarantineReason is invalid",
                )
            last_seen_at = value["lastSeenAt"]
            fleet.hosts[host_id] = HostRecord(
                host_id=host_id,
                key_id=key_id,
                expected_platform=platform,
                expected_architecture=architecture,
                expected_trust_tier=trust_tier,
                state=value["state"],
                capability=normalized_capability,
                capability_digest=capability_digest,
                last_seen_at=(
                    parse_time(last_seen_at, "lastSeenAt")
                    if last_seen_at is not None
                    else None
                ),
                active_lease_ids=set(active_lease_ids),
                assignment_count=_nonnegative_integer(
                    value["assignmentCount"], "assignmentCount"
                ),
                last_assigned_sequence=_nonnegative_integer(
                    value["lastAssignedSequence"], "lastAssignedSequence"
                ),
                recovery_generation=_nonnegative_integer(
                    value["recoveryGeneration"], "recoveryGeneration"
                ),
                quarantine_reason=quarantine_reason,
            )

        leases = _sorted_unique_objects(
            state["leases"], field_name="leases", key_name="leaseId"
        )
        for value in leases:
            lease = _lease_from_dict(value)
            fleet.leases[lease.lease_id] = lease

        mappings = _sorted_unique_objects(
            state["requestToLease"],
            field_name="requestToLease",
            key_name="requestId",
        )
        for value in mappings:
            _require_exact_keys(
                value, {"requestId", "leaseId"}, "checkpoint request-to-lease entry"
            )
            request_id = _bounded_identifier(value["requestId"], "requestId")
            lease_id = _bounded_identifier(value["leaseId"], "leaseId")
            fleet.request_to_lease[request_id] = lease_id

        receipts = _sorted_unique_objects(
            state["receipts"], field_name="receipts", key_name="requestId"
        )
        for value in receipts:
            receipt = _receipt_from_dict(value)
            fleet.receipts_by_request[receipt.request_id] = receipt

        fleet._validate_checkpoint_references()
        if sweep:
            fleet.sweep(now=now)
        return fleet

