#!/usr/bin/env python3
"""Cross-reference invariants for restored native-fleet authority."""

from __future__ import annotations

from .native_fleet_validation import FleetError


class CheckpointReferenceValidationMixin:
    """Reject internally inconsistent checkpoint authority graphs."""

    def _validate_checkpoint_references(self) -> None:
        max_sequence = 0
        active_from_hosts: set[str] = set()
        for host in self.hosts.values():
            identity = self.identities.get(host.key_id)
            if identity is None or identity.host_id != host.host_id:
                raise FleetError(
                    "checkpoint_reference_invalid",
                    f"host {host.host_id} has no matching identity",
                )
            max_sequence = max(max_sequence, host.last_assigned_sequence)
            if host.state == "busy" and not host.active_lease_ids:
                raise FleetError(
                    "checkpoint_reference_invalid",
                    f"busy host {host.host_id} has no active lease",
                )
            if host.active_lease_ids and host.state not in {"busy", "draining"}:
                raise FleetError(
                    "checkpoint_reference_invalid",
                    f"host {host.host_id} carries active authority in state {host.state}",
                )
            if host.capability is not None and len(host.active_lease_ids) > host.capability[
                "concurrency"
            ]:
                raise FleetError(
                    "checkpoint_reference_invalid",
                    f"host {host.host_id} exceeds attested concurrency",
                )
            for lease_id in host.active_lease_ids:
                if lease_id in active_from_hosts:
                    raise FleetError(
                        "checkpoint_reference_invalid",
                        f"lease {lease_id} is active on multiple hosts",
                    )
                active_from_hosts.add(lease_id)

        if self._assignment_sequence < max_sequence:
            raise FleetError(
                "checkpoint_reference_invalid",
                "assignmentSequence is behind a host assignment sequence",
            )

        for lease in self.leases.values():
            host = self.hosts.get(lease.host_id)
            identity = self.identities.get(lease.key_id)
            if host is None or identity is None:
                raise FleetError(
                    "checkpoint_reference_invalid",
                    f"lease {lease.lease_id} references an unknown host or identity",
                )
            if host.key_id != lease.key_id or identity.host_id != lease.host_id:
                raise FleetError(
                    "checkpoint_reference_invalid",
                    f"lease {lease.lease_id} identity binding is inconsistent",
                )
            active = lease.terminal_status is None
            if active:
                if lease.lease_id not in active_from_hosts:
                    raise FleetError(
                        "checkpoint_reference_invalid",
                        f"active lease {lease.lease_id} is missing from its host",
                    )
                if host.capability_digest != lease.capability_digest:
                    raise FleetError(
                        "checkpoint_reference_invalid",
                        f"active lease {lease.lease_id} capability has drifted",
                    )
                if self.request_to_lease.get(lease.request_id) != lease.lease_id:
                    raise FleetError(
                        "checkpoint_reference_invalid",
                        f"active lease {lease.lease_id} lacks request authority mapping",
                    )
                if lease.request_id in self.receipts_by_request:
                    raise FleetError(
                        "checkpoint_reference_invalid",
                        f"active lease {lease.lease_id} already has a receipt",
                    )
            else:
                if lease.lease_id in active_from_hosts:
                    raise FleetError(
                        "checkpoint_reference_invalid",
                        f"terminal lease {lease.lease_id} remains active on a host",
                    )
                if lease.request_id in self.request_to_lease:
                    raise FleetError(
                        "checkpoint_reference_invalid",
                        f"terminal lease {lease.lease_id} retains request authority",
                    )
                receipt = self.receipts_by_request.get(lease.request_id)
                if receipt is None or receipt.lease_id != lease.lease_id:
                    raise FleetError(
                        "checkpoint_reference_invalid",
                        f"terminal lease {lease.lease_id} lacks a matching receipt",
                    )
                if receipt.status != lease.terminal_status:
                    raise FleetError(
                        "checkpoint_reference_invalid",
                        f"terminal lease {lease.lease_id} disagrees with its receipt",
                    )

        for request_id, lease_id in self.request_to_lease.items():
            lease = self.leases.get(lease_id)
            if (
                lease is None
                or lease.request_id != request_id
                or lease.terminal_status is not None
            ):
                raise FleetError(
                    "checkpoint_reference_invalid",
                    f"request mapping {request_id} is inconsistent",
                )

        for request_id, receipt in self.receipts_by_request.items():
            lease = self.leases.get(receipt.lease_id)
            if (
                lease is None
                or receipt.request_id != request_id
                or lease.request_id != request_id
                or lease.terminal_status is None
                or receipt.request_digest != lease.request_digest
                or receipt.host_id != lease.host_id
                or receipt.key_id != lease.key_id
                or receipt.profile != lease.profile
                or receipt.profile_digest != lease.profile_digest
                or receipt.capability_digest != lease.capability_digest
            ):
                raise FleetError(
                    "checkpoint_reference_invalid",
                    f"receipt {request_id} is inconsistent",
                )

