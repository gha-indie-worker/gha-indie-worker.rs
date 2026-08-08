#!/usr/bin/env python3
"""One-use enrollment, identity, attestation, and heartbeat operations."""

from __future__ import annotations

from .native_fleet_validation import *  # noqa: F401,F403
from .native_fleet_validation import _require_exact_keys

class EnrollmentMixin:
    def issue_bootstrap(
        self,
        *,
        host_id: str,
        platform: str,
        architecture: str,
        trust_tier: str,
        now: datetime,
        ttl: timedelta = timedelta(minutes=10),
    ) -> str:
        now = require_utc(now)
        if not HOST_ID_RE.fullmatch(host_id):
            raise FleetError("host_id_invalid", "hostId is invalid")
        if platform not in PLATFORMS:
            raise FleetError("platform_unsupported", "unsupported platform")
        if architecture not in ARCHITECTURES:
            raise FleetError("architecture_unsupported", "unsupported architecture")
        if trust_tier not in TRUST_TIERS:
            raise FleetError("trust_tier_unsupported", "unsupported trust tier")
        if ttl <= timedelta(0) or ttl > timedelta(hours=1):
            raise FleetError("bootstrap_ttl_invalid", "bootstrap TTL must be within one hour")
        if host_id in self.hosts and self.hosts[host_id].state != "revoked":
            raise FleetError("host_exists", "host already exists")
        token = secrets.token_urlsafe(32)
        token_hash = hash_token(token)
        self.bootstrap_grants[token_hash] = BootstrapGrant(
            token_hash=token_hash,
            host_id=host_id,
            platform=platform,
            architecture=architecture,
            trust_tier=trust_tier,
            expires_at=now + ttl,
        )
        return token

    def enroll(
        self,
        token: str,
        *,
        host_id: str,
        now: datetime,
        identity_ttl: timedelta = timedelta(hours=8),
    ) -> DeviceCredential:
        now = require_utc(now)
        grant = self.bootstrap_grants.get(hash_token(token))
        if grant is None:
            raise FleetError("bootstrap_invalid", "bootstrap token is unknown")
        if grant.used:
            raise FleetError("bootstrap_replayed", "bootstrap token was already used")
        if grant.expires_at <= now:
            raise FleetError("bootstrap_expired", "bootstrap token expired")
        if grant.host_id != host_id:
            raise FleetError("bootstrap_host_mismatch", "bootstrap token is bound to another host")
        if identity_ttl <= timedelta(0) or identity_ttl > timedelta(days=7):
            raise FleetError("identity_ttl_invalid", "identity TTL must be within seven days")
        grant.used = True
        key_id = f"device:{host_id}:{secrets.token_hex(8)}"
        secret = secrets.token_bytes(32)
        expires_at = now + identity_ttl
        self.identities[key_id] = DeviceIdentity(
            host_id=host_id,
            key_id=key_id,
            secret=secret,
            expires_at=expires_at,
        )
        self.hosts[host_id] = HostRecord(
            host_id=host_id,
            key_id=key_id,
            expected_platform=grant.platform,
            expected_architecture=grant.architecture,
            expected_trust_tier=grant.trust_tier,
        )
        return DeviceCredential(host_id=host_id, key_id=key_id, secret=secret, expires_at=expires_at)

    def rotate_identity(
        self,
        credential: DeviceCredential,
        *,
        now: datetime,
        identity_ttl: timedelta = timedelta(hours=8),
    ) -> DeviceCredential:
        now = require_utc(now)
        identity = self._authenticate(credential, now)
        host = self.hosts[identity.host_id]
        if host.active_lease_ids:
            raise FleetError("identity_rotation_busy", "identity cannot rotate with active leases")
        identity.revoked = True
        key_id = f"device:{host.host_id}:{secrets.token_hex(8)}"
        secret = secrets.token_bytes(32)
        expires_at = now + identity_ttl
        self.identities[key_id] = DeviceIdentity(host.host_id, key_id, secret, expires_at)
        host.key_id = key_id
        host.state = "enrolling"
        host.capability = None
        host.capability_digest = None
        host.last_seen_at = None
        return DeviceCredential(host.host_id, key_id, secret, expires_at)

    def advertise(self, envelope: Mapping[str, Any], *, now: datetime) -> str:
        now = require_utc(now)
        if not isinstance(envelope, Mapping):
            raise FleetError("attestation_invalid", "capability envelope must be an object")
        _require_exact_keys(
            envelope,
            {
                "schemaVersion",
                "keyId",
                "issuedAt",
                "expiresAt",
                "payloadDigest",
                "signatureAlgorithm",
                "signature",
                "payload",
            },
            "capability envelope",
        )
        if envelope["schemaVersion"] != CAPABILITY_ENVELOPE_SCHEMA:
            raise FleetError("schema_unsupported", "unsupported capability envelope schema")
        key_id = envelope["keyId"]
        if not isinstance(key_id, str) or not KEY_ID_RE.fullmatch(key_id):
            raise FleetError("key_id_invalid", "keyId is invalid")
        identity = self.identities.get(key_id)
        if identity is None:
            raise FleetError("identity_unknown", "device identity is unknown")
        if identity.revoked:
            raise FleetError("identity_revoked", "device identity is revoked")
        if identity.expires_at <= now:
            raise FleetError("identity_expired", "device identity expired")
        issued_at = parse_time(envelope["issuedAt"], "issuedAt")
        expires_at = parse_time(envelope["expiresAt"], "expiresAt")
        if issued_at > now + timedelta(seconds=30):
            raise FleetError("attestation_from_future", "attestation issuedAt is too far in the future")
        if expires_at <= now:
            raise FleetError("attestation_expired", "attestation expired")
        if expires_at <= issued_at or expires_at - issued_at > self.maximum_attestation_ttl:
            raise FleetError("attestation_ttl_invalid", "attestation TTL is invalid")
        if envelope["signatureAlgorithm"] != "hmac-sha256-simulator":
            raise FleetError("signature_algorithm_unsupported", "unsupported simulator signature algorithm")
        payload = validate_host_capability(envelope["payload"], self.minimum_agent_version)
        payload_digest = sha256_digest(payload)
        if envelope["payloadDigest"] != payload_digest:
            raise FleetError("capability_digest_mismatch", "payloadDigest does not match payload")
        signed_fields = {
            "keyId": key_id,
            "issuedAt": envelope["issuedAt"],
            "expiresAt": envelope["expiresAt"],
            "payloadDigest": payload_digest,
        }
        expected_signature = base64.urlsafe_b64encode(
            hmac.new(identity.secret, canonical_bytes(signed_fields), hashlib.sha256).digest()
        ).decode().rstrip("=")
        if not isinstance(envelope["signature"], str) or not hmac.compare_digest(
            envelope["signature"], expected_signature
        ):
            raise FleetError("signature_invalid", "capability signature is invalid")
        if payload["hostId"] != identity.host_id:
            raise FleetError("identity_host_mismatch", "capability hostId does not match identity")
        host = self.hosts[identity.host_id]
        if payload["platform"] != host.expected_platform:
            raise FleetError("enrollment_platform_mismatch", "capability platform differs from enrollment")
        if payload["architecture"] != host.expected_architecture:
            raise FleetError("enrollment_architecture_mismatch", "capability architecture differs from enrollment")
        if payload["trustTier"] != host.expected_trust_tier:
            raise FleetError("enrollment_trust_mismatch", "capability trust tier differs from enrollment")
        if host.active_lease_ids and host.capability_digest not in (None, payload_digest):
            self.quarantine(host.host_id, reason="capability-drift-during-lease", now=now)
            raise FleetError("capability_drift_during_lease", "capability changed during an active lease")
        host.capability = payload
        host.capability_digest = payload_digest
        host.last_seen_at = now
        if host.state in {"enrolling", "offline"}:
            host.state = "healthy"
        self._refresh_host_state(host)
        return payload_digest

    def heartbeat(
        self,
        credential: DeviceCredential,
        *,
        capability_digest: str,
        now: datetime,
    ) -> None:
        now = require_utc(now)
        identity = self._authenticate(credential, now)
        host = self.hosts[identity.host_id]
        if host.capability_digest != capability_digest:
            self.quarantine(host.host_id, reason="capability-digest-mismatch", now=now)
            raise FleetError("capability_digest_mismatch", "heartbeat capability digest is stale or invalid")
        host.last_seen_at = now
        if host.state == "offline":
            host.state = "healthy"
        self._refresh_host_state(host)
