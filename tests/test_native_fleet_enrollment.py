from native_fleet_test_support import *  # noqa: F401,F403


class NativeFleetEnrollmentTests(NativeFleetTestCase):
    def test_bootstrap_is_one_time_host_bound_and_expiring(self) -> None:
        token = self.fleet.issue_bootstrap(
            host_id="mac-lab-01",
            platform="macos",
            architecture="arm64",
            trust_tier="public-trusted",
            now=NOW,
            ttl=timedelta(minutes=1),
        )
        with self.assertRaisesRegex(FleetError, "bootstrap_host_mismatch"):
            self.fleet.enroll(token, host_id="mac-lab-02", now=NOW)
        self.fleet.enroll(token, host_id="mac-lab-01", now=NOW)
        with self.assertRaisesRegex(FleetError, "bootstrap_replayed"):
            self.fleet.enroll(token, host_id="mac-lab-01", now=NOW)

        expired = self.fleet.issue_bootstrap(
            host_id="win-lab-01",
            platform="windows",
            architecture="x64",
            trust_tier="public-trusted",
            now=NOW,
            ttl=timedelta(seconds=1),
        )
        with self.assertRaisesRegex(FleetError, "bootstrap_expired"):
            self.fleet.enroll(expired, host_id="win-lab-01", now=NOW + timedelta(seconds=2))

    def test_signed_capability_rejects_tampering_stale_agent_and_secret_fields(self) -> None:
        token = self.fleet.issue_bootstrap(
            host_id="mac-lab-01",
            platform="macos",
            architecture="arm64",
            trust_tier="public-trusted",
            now=NOW,
        )
        credential = self.fleet.enroll(token, host_id="mac-lab-01", now=NOW)
        payload = capability_payload(
            host_id="mac-lab-01",
            platform="macos",
            architecture="arm64",
            trust_tier="public-trusted",
            profiles=[("macos-xcode", MAC_DIGEST)],
            capabilities=["native", "xcode", "ios-simulator"],
        )
        envelope = sign_capability_envelope(
            credential, payload, NOW, NOW + timedelta(minutes=5)
        )
        envelope["payload"]["capabilities"].append("tampered")
        envelope["payload"]["capabilities"].sort()
        with self.assertRaisesRegex(FleetError, "capability_digest_mismatch"):
            self.fleet.advertise(envelope, now=NOW)

        stale_payload = capability_payload(
            host_id="mac-lab-01",
            platform="macos",
            architecture="arm64",
            trust_tier="public-trusted",
            profiles=[("macos-xcode", MAC_DIGEST)],
            capabilities=["native", "xcode"],
            agent_version="0.0.9",
        )
        stale_envelope = sign_capability_envelope(
            credential, stale_payload, NOW, NOW + timedelta(minutes=5)
        )
        with self.assertRaisesRegex(FleetError, "agent_version_stale"):
            self.fleet.advertise(stale_envelope, now=NOW)

        secret_payload = capability_payload(
            host_id="mac-lab-01",
            platform="macos",
            architecture="arm64",
            trust_tier="public-trusted",
            profiles=[("macos-xcode", MAC_DIGEST)],
            capabilities=["native", "xcode"],
        )
        secret_payload["accessToken"] = "must-never-be-advertised"
        secret_envelope = sign_capability_envelope(
            credential, secret_payload, NOW, NOW + timedelta(minutes=5)
        )
        with self.assertRaisesRegex(FleetError, "field_unknown|secret_field_forbidden"):
            self.fleet.advertise(secret_envelope, now=NOW)

    def test_identity_rotation_and_revocation_block_stale_credentials(self) -> None:
        credential, payload, _, _ = self.enroll_and_advertise(
            host_id="linux-lab-01",
            platform="linux",
            architecture="x64",
            trust_tier="private-build",
            profile="linux-rust",
            profile_digest=LINUX_DIGEST,
            capabilities=["cargo-cache"],
        )
        rotated = self.fleet.rotate_identity(credential, now=NOW)
        with self.assertRaisesRegex(FleetError, "identity_revoked"):
            self.fleet.heartbeat(
                credential,
                capability_digest=sha256_digest(payload),
                now=NOW,
            )
        envelope = sign_capability_envelope(
            rotated, payload, NOW, NOW + timedelta(minutes=5)
        )
        capability_digest = self.fleet.advertise(envelope, now=NOW)
        self.assertTrue(self.fleet.revoke("linux-lab-01", now=NOW))
        self.assertFalse(self.fleet.revoke("linux-lab-01", now=NOW))
        with self.assertRaisesRegex(FleetError, "identity_revoked"):
            self.fleet.heartbeat(
                rotated, capability_digest=capability_digest, now=NOW
            )
