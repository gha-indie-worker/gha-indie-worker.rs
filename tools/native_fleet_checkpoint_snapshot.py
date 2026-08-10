#!/usr/bin/env python3
"""Deterministic secret-free native-fleet checkpoint serialization."""

from __future__ import annotations

import copy
from datetime import datetime
from typing import Any

from .native_fleet_validation import format_time, require_utc
from .native_fleet_checkpoint_common import CHECKPOINT_STATE_SCHEMA, seal_checkpoint_state


class CheckpointSnapshotMixin:
    """Serialize current authority and fleet metadata without identity secrets."""

    def checkpoint(
        self,
        *,
        integrity_key_id: str,
        integrity_key: bytes,
        now: datetime,
        sweep: bool = True,
    ) -> dict[str, Any]:
        now = require_utc(now)
        if sweep:
            self.sweep(now=now)
        state = {
            "schemaVersion": CHECKPOINT_STATE_SCHEMA,
            "minimumAgentVersion": self.minimum_agent_version,
            "heartbeatTtlSeconds": int(self.heartbeat_ttl.total_seconds()),
            "leaseTtlSeconds": int(self.lease_ttl.total_seconds()),
            "maximumAttestationTtlSeconds": int(
                self.maximum_attestation_ttl.total_seconds()
            ),
            "assignmentSequence": self._assignment_sequence,
            "bootstrapGrants": [
                {
                    "tokenHash": grant.token_hash,
                    "hostId": grant.host_id,
                    "platform": grant.platform,
                    "architecture": grant.architecture,
                    "trustTier": grant.trust_tier,
                    "expiresAt": format_time(grant.expires_at),
                    "used": grant.used,
                }
                for grant in sorted(
                    self.bootstrap_grants.values(), key=lambda item: item.token_hash
                )
            ],
            "identities": [
                {
                    "hostId": identity.host_id,
                    "keyId": identity.key_id,
                    "expiresAt": format_time(identity.expires_at),
                    "revoked": identity.revoked,
                }
                for identity in sorted(
                    self.identities.values(), key=lambda item: item.key_id
                )
            ],
            "hosts": [
                {
                    "hostId": host.host_id,
                    "keyId": host.key_id,
                    "expectedPlatform": host.expected_platform,
                    "expectedArchitecture": host.expected_architecture,
                    "expectedTrustTier": host.expected_trust_tier,
                    "state": host.state,
                    "capability": copy.deepcopy(host.capability),
                    "capabilityDigest": host.capability_digest,
                    "lastSeenAt": (
                        format_time(host.last_seen_at)
                        if host.last_seen_at is not None
                        else None
                    ),
                    "activeLeaseIds": sorted(host.active_lease_ids),
                    "assignmentCount": host.assignment_count,
                    "lastAssignedSequence": host.last_assigned_sequence,
                    "recoveryGeneration": host.recovery_generation,
                    "quarantineReason": host.quarantine_reason,
                }
                for host in sorted(self.hosts.values(), key=lambda item: item.host_id)
            ],
            "leases": [
                lease.as_dict()
                for lease in sorted(self.leases.values(), key=lambda item: item.lease_id)
            ],
            "requestToLease": [
                {"requestId": request_id, "leaseId": lease_id}
                for request_id, lease_id in sorted(self.request_to_lease.items())
            ],
            "receipts": [
                receipt.as_dict()
                for receipt in sorted(
                    self.receipts_by_request.values(), key=lambda item: item.request_id
                )
            ],
        }
        return seal_checkpoint_state(
            state,
            integrity_key_id=integrity_key_id,
            integrity_key=integrity_key,
            created_at=now,
        )

