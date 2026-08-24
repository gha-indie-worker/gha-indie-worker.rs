#!/usr/bin/env python3
"""Native fleet storage and shared fail-closed helpers."""

from __future__ import annotations

from .native_fleet_validation import *  # noqa: F401,F403


class FleetBase:
    def __init__(
        self,
        *,
        minimum_agent_version: str = "0.1.0",
        heartbeat_ttl: timedelta = timedelta(seconds=90),
        lease_ttl: timedelta = timedelta(minutes=5),
        maximum_attestation_ttl: timedelta = timedelta(minutes=10),
    ) -> None:
        version_tuple(minimum_agent_version)
        self.minimum_agent_version = minimum_agent_version
        self.heartbeat_ttl = heartbeat_ttl
        self.lease_ttl = lease_ttl
        self.maximum_attestation_ttl = maximum_attestation_ttl
        self.bootstrap_grants: dict[str, BootstrapGrant] = {}
        self.identities: dict[str, DeviceIdentity] = {}
        self.hosts: dict[str, HostRecord] = {}
        self.leases: dict[str, Lease] = {}
        self.request_to_lease: dict[str, str] = {}
        self.receipts_by_request: dict[str, TerminalReceipt] = {}
        self._assignment_sequence = 0

    def _authenticate(self, credential: DeviceCredential, now: datetime) -> DeviceIdentity:
        if not isinstance(credential, DeviceCredential):
            raise FleetError("credential_invalid", "device credential is invalid")
        identity = self.identities.get(credential.key_id)
        if identity is None or identity.host_id != credential.host_id:
            raise FleetError("identity_unknown", "device identity is unknown")
        if identity.revoked:
            raise FleetError("identity_revoked", "device identity is revoked")
        if identity.expires_at <= now or credential.expires_at <= now:
            raise FleetError("identity_expired", "device identity expired")
        if not hmac.compare_digest(identity.secret, credential.secret):
            raise FleetError("identity_authentication_failed", "device credential is invalid")
        return identity

    def _host(self, host_id: str) -> HostRecord:
        host = self.hosts.get(host_id)
        if host is None:
            raise FleetError("host_unknown", "host is unknown")
        return host

    def _active_lease(self, lease_id: str) -> Lease:
        lease = self.leases.get(lease_id)
        if lease is None:
            raise FleetError("lease_unknown", "lease is unknown")
        if lease.terminal_status is not None:
            raise FleetError("lease_terminal", "lease is already terminal")
        return lease

    def _match_reasons(
        self,
        host: HostRecord,
        request: Mapping[str, Any],
        required_trust_tier: str,
        now: datetime,
    ) -> list[str]:
        reasons: list[str] = []
        if host.state not in {"healthy", "busy"}:
            reasons.append(f"state:{host.state}")
        identity = self.identities.get(host.key_id)
        if identity is None or identity.revoked:
            reasons.append("identity:revoked-or-missing")
        elif identity.expires_at <= now:
            reasons.append("identity:expired")
        if host.last_seen_at is None or now - host.last_seen_at > self.heartbeat_ttl:
            reasons.append("heartbeat:stale")
        capability = host.capability
        if capability is None or host.capability_digest is None:
            reasons.append("capability:missing")
            return sorted(set(reasons))
        runner = request["runner"]
        if capability["platform"] != runner["platform"]:
            reasons.append("platform:mismatch")
        if capability["architecture"] != runner["architecture"]:
            reasons.append("architecture:mismatch")
        if capability["trustTier"] != required_trust_tier:
            reasons.append("trust-tier:mismatch")
        host_capabilities = set(capability["capabilities"])
        missing_capabilities = sorted(set(runner["capabilities"]) - host_capabilities)
        if missing_capabilities:
            reasons.append("capabilities:missing:" + ",".join(missing_capabilities))
        profiles = {item["name"]: item["digest"] for item in capability["profiles"]}
        if request["profile"] not in profiles:
            reasons.append("profile:missing")
        elif profiles[request["profile"]] != request["profileDigest"]:
            reasons.append("profile-digest:mismatch")
        if len(host.active_lease_ids) >= capability["concurrency"]:
            reasons.append("concurrency:exhausted")
        return sorted(set(reasons))

    def _refresh_host_state(self, host: HostRecord) -> None:
        if host.state in {"draining", "maintenance", "quarantined", "offline", "revoked", "enrolling"}:
            return
        host.state = "busy" if host.active_lease_ids else "healthy"

    def _terminalize(
        self,
        lease: Lease,
        status: str,
        now: datetime,
        run_manifest_digest: str,
    ) -> TerminalReceipt:
        existing = self.receipts_by_request.get(lease.request_id)
        if existing is not None:
            return existing
        lease.terminal_status = status
        receipt = TerminalReceipt(
            schema_version=TERMINAL_RECEIPT_SCHEMA,
            lease_id=lease.lease_id,
            request_id=lease.request_id,
            request_digest=lease.request_digest,
            host_id=lease.host_id,
            key_id=lease.key_id,
            status=status,
            completed_at=now,
            attempt=lease.attempt,
            profile=lease.profile,
            profile_digest=lease.profile_digest,
            capability_digest=lease.capability_digest,
            run_manifest_digest=run_manifest_digest,
            cancel_requested=lease.cancel_requested,
        )
        self.receipts_by_request[lease.request_id] = receipt
        self.request_to_lease.pop(lease.request_id, None)
        host = self.hosts[lease.host_id]
        host.active_lease_ids.discard(lease.lease_id)
        self._refresh_host_state(host)
        return receipt
