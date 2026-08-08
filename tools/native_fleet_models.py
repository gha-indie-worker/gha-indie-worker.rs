#!/usr/bin/env python3
"""Fail-closed native-host enrollment, capability, scheduling, and lease simulator.

This module is deliberately dependency-free so the protocol can be exercised on
Linux, Windows, and macOS reference runners. Production transport and platform-
bound key implementations remain separate reviewable boundaries.
"""

from __future__ import annotations

import base64
import copy
import hashlib
import hmac
import json
import re
import secrets
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable, Mapping

HOST_CAPABILITY_SCHEMA = "gha-indie-worker.host-capability.v1"
CAPABILITY_ENVELOPE_SCHEMA = "gha-indie-worker.capability-envelope.v1"
DISPATCH_SCHEMA = "gha-indie-worker.dispatch.v2"
LEASE_SCHEMA = "gha-indie-worker.lease.v1"
TERMINAL_RECEIPT_SCHEMA = "gha-indie-worker.terminal-receipt.v1"
PROTOCOL_VERSION = 1

PLATFORMS = {"linux", "macos", "windows"}
ARCHITECTURES = {"x64", "arm64"}
TRUST_TIERS = {
    "public-untrusted",
    "public-trusted",
    "private-build",
    "release-signing",
}
HOST_STATES = {
    "enrolling",
    "healthy",
    "busy",
    "draining",
    "maintenance",
    "quarantined",
    "offline",
    "revoked",
}
TERMINAL_STATES = {
    "success",
    "failure",
    "canceled",
    "timed-out",
    "host-lost",
    "quarantined",
}

HOST_ID_RE = re.compile(r"^[a-z0-9][a-z0-9.-]{2,63}$")
KEY_ID_RE = re.compile(r"^device:[a-z0-9][a-z0-9.-]{2,63}:[0-9a-f]{16}$")
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
CAPABILITY_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
SHA256_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
SEMVER_RE = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
FORBIDDEN_FIELD_RE = re.compile(
    r"(?:secret|password|private.?key|access.?token|refresh.?token|credential)", re.I
)


class FleetError(ValueError):
    """A stable fail-closed protocol error."""

    def __init__(self, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


@dataclass(frozen=True)
class DeviceCredential:
    host_id: str
    key_id: str
    secret: bytes = field(repr=False)
    expires_at: datetime


@dataclass
class BootstrapGrant:
    token_hash: str
    host_id: str
    platform: str
    architecture: str
    trust_tier: str
    expires_at: datetime
    used: bool = False


@dataclass
class DeviceIdentity:
    host_id: str
    key_id: str
    secret: bytes = field(repr=False)
    expires_at: datetime
    revoked: bool = False


@dataclass
class HostRecord:
    host_id: str
    key_id: str
    expected_platform: str
    expected_architecture: str
    expected_trust_tier: str
    state: str = "enrolling"
    capability: dict[str, Any] | None = None
    capability_digest: str | None = None
    last_seen_at: datetime | None = None
    active_lease_ids: set[str] = field(default_factory=set)
    assignment_count: int = 0
    last_assigned_sequence: int = 0
    recovery_generation: int = 0
    quarantine_reason: str | None = None


@dataclass
class Lease:
    schema_version: str
    lease_id: str
    request_id: str
    request_digest: str
    host_id: str
    key_id: str
    nonce: str
    issued_at: datetime
    expires_at: datetime
    attempt: int
    repository_url: str
    commit_sha: str
    profile: str
    profile_digest: str
    capability_digest: str
    host_capability_snapshot: dict[str, Any]
    cancel_requested: bool = False
    terminal_status: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "leaseId": self.lease_id,
            "requestId": self.request_id,
            "requestDigest": self.request_digest,
            "hostId": self.host_id,
            "keyId": self.key_id,
            "nonce": self.nonce,
            "issuedAt": format_time(self.issued_at),
            "expiresAt": format_time(self.expires_at),
            "attempt": self.attempt,
            "repositoryUrl": self.repository_url,
            "commitSha": self.commit_sha,
            "profile": self.profile,
            "profileDigest": self.profile_digest,
            "capabilityDigest": self.capability_digest,
            "hostCapabilitySnapshot": copy.deepcopy(self.host_capability_snapshot),
            "cancelRequested": self.cancel_requested,
            "terminalStatus": self.terminal_status,
        }


@dataclass(frozen=True)
class TerminalReceipt:
    schema_version: str
    lease_id: str
    request_id: str
    request_digest: str
    host_id: str
    key_id: str
    status: str
    completed_at: datetime
    attempt: int
    profile: str
    profile_digest: str
    capability_digest: str
    run_manifest_digest: str
    cancel_requested: bool

    def as_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "leaseId": self.lease_id,
            "requestId": self.request_id,
            "requestDigest": self.request_digest,
            "hostId": self.host_id,
            "keyId": self.key_id,
            "status": self.status,
            "completedAt": format_time(self.completed_at),
            "attempt": self.attempt,
            "profile": self.profile,
            "profileDigest": self.profile_digest,
            "capabilityDigest": self.capability_digest,
            "runManifestDigest": self.run_manifest_digest,
            "cancelRequested": self.cancel_requested,
        }


@dataclass(frozen=True)
class AssignmentResult:
    lease: Lease | None
    terminal_receipt: TerminalReceipt | None
    duplicate: bool
    rejection_reasons: Mapping[str, tuple[str, ...]]


