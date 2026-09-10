#!/usr/bin/env python3
"""Exact-match scheduling and exclusive lease operations."""

from __future__ import annotations

from .native_fleet_validation import *  # noqa: F401,F403


class SchedulingMixin:
    def schedule(
        self,
        request: Mapping[str, Any],
        *,
        required_trust_tier: str,
        now: datetime,
    ) -> AssignmentResult:
        now = require_utc(now)
        if required_trust_tier not in TRUST_TIERS:
            raise FleetError("trust_tier_unsupported", "unsupported required trust tier")
        normalized = validate_dispatch_request(request)
        self.sweep(now=now)
        request_id = normalized["requestId"]
        receipt = self.receipts_by_request.get(request_id)
        if receipt is not None:
            return AssignmentResult(None, receipt, True, {})
        lease_id = self.request_to_lease.get(request_id)
        if lease_id is not None:
            existing = self.leases[lease_id]
            return AssignmentResult(existing, None, True, {})
        rejections: dict[str, tuple[str, ...]] = {}
        eligible: list[HostRecord] = []
        for host in self.hosts.values():
            reasons = self._match_reasons(host, normalized, required_trust_tier, now)
            if reasons:
                rejections[host.host_id] = tuple(reasons)
            else:
                eligible.append(host)
        if not eligible:
            return AssignmentResult(None, None, False, rejections)
        eligible.sort(
            key=lambda host: (
                len(host.active_lease_ids) / host.capability["concurrency"],
                host.assignment_count,
                host.last_assigned_sequence,
                host.host_id,
            )
        )
        host = eligible[0]
        self._assignment_sequence += 1
        host.assignment_count += 1
        host.last_assigned_sequence = self._assignment_sequence
        attempt = 1
        lease = Lease(
            schema_version=LEASE_SCHEMA,
            lease_id=random_id("lease"),
            request_id=request_id,
            request_digest=normalized["requestDigest"],
            host_id=host.host_id,
            key_id=host.key_id,
            nonce=random_id("nonce"),
            issued_at=now,
            expires_at=now + self.lease_ttl,
            attempt=attempt,
            repository_url=normalized["repositoryUrl"],
            commit_sha=normalized["commitSha"],
            profile=normalized["profile"],
            profile_digest=normalized["profileDigest"],
            capability_digest=host.capability_digest,
            host_capability_snapshot=copy.deepcopy(host.capability),
        )
        self.leases[lease.lease_id] = lease
        self.request_to_lease[request_id] = lease.lease_id
        host.active_lease_ids.add(lease.lease_id)
        self._refresh_host_state(host)
        return AssignmentResult(lease, None, False, rejections)

    def renew_lease(
        self,
        credential: DeviceCredential,
        *,
        lease_id: str,
        nonce: str,
        now: datetime,
    ) -> Lease:
        now = require_utc(now)
        identity = self._authenticate(credential, now)
        lease = self._active_lease(lease_id)
        if lease.host_id != identity.host_id or lease.key_id != identity.key_id:
            raise FleetError("lease_host_mismatch", "lease belongs to another identity")
        if lease.nonce != nonce:
            raise FleetError("lease_nonce_invalid", "lease nonce is stale or invalid")
        if lease.expires_at <= now:
            self._terminalize(lease, "timed-out", now, sha256_digest({"leaseId": lease_id, "expired": True}))
            raise FleetError("lease_expired", "lease expired")
        if lease.cancel_requested:
            raise FleetError("lease_cancel_pending", "canceled lease cannot be renewed")
        host = self.hosts[lease.host_id]
        if host.state in {"quarantined", "offline", "revoked", "maintenance"}:
            raise FleetError("host_unavailable", f"host is {host.state}")
        lease.expires_at = now + self.lease_ttl
        lease.nonce = random_id("nonce")
        return lease

    def cancel(self, request_id: str) -> bool:
        receipt = self.receipts_by_request.get(request_id)
        if receipt is not None:
            return False
        lease_id = self.request_to_lease.get(request_id)
        if lease_id is None:
            raise FleetError("request_unknown", "request has no active or terminal assignment")
        lease = self.leases[lease_id]
        if lease.cancel_requested:
            return False
        lease.cancel_requested = True
        return True

    def complete(
        self,
        credential: DeviceCredential,
        *,
        lease_id: str,
        nonce: str,
        status: str,
        run_manifest_digest: str,
        now: datetime,
    ) -> TerminalReceipt:
        now = require_utc(now)
        if status not in TERMINAL_STATES:
            raise FleetError("terminal_status_invalid", "unsupported terminal status")
        if not SHA256_RE.fullmatch(run_manifest_digest):
            raise FleetError("run_manifest_digest_invalid", "run manifest digest is invalid")
        existing_lease = self.leases.get(lease_id)
        if existing_lease is None:
            raise FleetError("lease_unknown", "lease is unknown")
        existing_receipt = self.receipts_by_request.get(existing_lease.request_id)
        if existing_receipt is not None:
            if existing_receipt.status == status and existing_receipt.run_manifest_digest == run_manifest_digest:
                return existing_receipt
            raise FleetError("terminal_receipt_conflict", "terminal receipt conflicts with the recorded result")
        identity = self._authenticate(credential, now)
        lease = self._active_lease(lease_id)
        if lease.host_id != identity.host_id or lease.key_id != identity.key_id:
            raise FleetError("lease_host_mismatch", "lease belongs to another identity")
        if lease.nonce != nonce:
            raise FleetError("lease_nonce_invalid", "lease nonce is stale or invalid")
        return self._terminalize(lease, status, now, run_manifest_digest)
