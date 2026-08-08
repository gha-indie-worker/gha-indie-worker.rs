#!/usr/bin/env python3
"""Drain, quarantine, recovery, revocation, and host-loss operations."""

from __future__ import annotations

from .native_fleet_validation import *  # noqa: F401,F403


class LifecycleMixin:
    def drain(self, host_id: str) -> bool:
        host = self._host(host_id)
        if host.state == "revoked":
            raise FleetError("host_revoked", "revoked host cannot drain")
        if host.state == "draining":
            return False
        if host.state in {"quarantined", "maintenance"}:
            raise FleetError("host_state_invalid", f"host cannot drain from {host.state}")
        host.state = "draining"
        return True

    def resume(self, host_id: str, *, now: datetime) -> bool:
        now = require_utc(now)
        host = self._host(host_id)
        if host.state != "draining":
            return False
        if host.last_seen_at is None or now - host.last_seen_at > self.heartbeat_ttl:
            host.state = "offline"
            raise FleetError("heartbeat_stale", "host heartbeat is stale")
        host.state = "healthy"
        self._refresh_host_state(host)
        return True

    def enter_maintenance(self, host_id: str) -> bool:
        host = self._host(host_id)
        if host.active_lease_ids:
            raise FleetError("host_busy", "host cannot enter maintenance with active leases")
        if host.state == "maintenance":
            return False
        if host.state in {"quarantined", "revoked"}:
            raise FleetError("host_state_invalid", f"host cannot enter maintenance from {host.state}")
        host.state = "maintenance"
        return True

    def quarantine(self, host_id: str, *, reason: str, now: datetime) -> bool:
        now = require_utc(now)
        host = self._host(host_id)
        if host.state == "revoked":
            return False
        changed = host.state != "quarantined" or host.quarantine_reason != reason
        host.state = "quarantined"
        host.quarantine_reason = reason
        for lease_id in list(host.active_lease_ids):
            lease = self.leases[lease_id]
            if lease.terminal_status is None:
                self._terminalize(
                    lease,
                    "quarantined",
                    now,
                    sha256_digest({"leaseId": lease_id, "quarantineReason": reason}),
                )
        return changed

    def recover(self, host_id: str, *, capability_digest: str, now: datetime) -> bool:
        now = require_utc(now)
        host = self._host(host_id)
        if host.state == "healthy" and host.capability_digest == capability_digest:
            return False
        if host.state != "quarantined":
            raise FleetError("host_state_invalid", "only quarantined hosts can recover")
        if host.active_lease_ids:
            raise FleetError("host_busy", "host cannot recover with active leases")
        if host.capability_digest != capability_digest:
            raise FleetError("capability_digest_mismatch", "recovery capability digest does not match")
        if host.last_seen_at is None or now - host.last_seen_at > self.heartbeat_ttl:
            host.state = "offline"
            raise FleetError("heartbeat_stale", "host must re-attest before recovery")
        host.quarantine_reason = None
        host.recovery_generation += 1
        host.state = "healthy"
        return True

    def revoke(self, host_id: str, *, now: datetime) -> bool:
        now = require_utc(now)
        host = self._host(host_id)
        if host.state == "revoked":
            return False
        identity = self.identities[host.key_id]
        identity.revoked = True
        for lease_id in list(host.active_lease_ids):
            lease = self.leases[lease_id]
            if lease.terminal_status is None:
                self._terminalize(
                    lease,
                    "host-lost",
                    now,
                    sha256_digest({"leaseId": lease_id, "identityRevoked": True}),
                )
        host.state = "revoked"
        return True

    def sweep(self, *, now: datetime) -> None:
        now = require_utc(now)
        for host in self.hosts.values():
            if host.state in {"enrolling", "maintenance", "quarantined", "revoked"}:
                continue
            if host.last_seen_at is None or now - host.last_seen_at > self.heartbeat_ttl:
                host.state = "offline"
                for lease_id in list(host.active_lease_ids):
                    lease = self.leases[lease_id]
                    if lease.terminal_status is None:
                        self._terminalize(
                            lease,
                            "host-lost",
                            now,
                            sha256_digest({"leaseId": lease_id, "heartbeatLost": True}),
                        )
        for lease in list(self.leases.values()):
            if lease.terminal_status is None and lease.expires_at <= now:
                self._terminalize(
                    lease,
                    "timed-out",
                    now,
                    sha256_digest({"leaseId": lease.lease_id, "leaseExpired": True}),
                )

    def rejection_reasons(
        self,
        request: Mapping[str, Any],
        *,
        required_trust_tier: str,
        now: datetime,
    ) -> Mapping[str, tuple[str, ...]]:
        normalized = validate_dispatch_request(request)
        self.sweep(now=now)
        return {
            host.host_id: tuple(self._match_reasons(host, normalized, required_trust_tier, now))
            for host in self.hosts.values()
        }
