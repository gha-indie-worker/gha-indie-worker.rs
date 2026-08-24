from native_fleet_test_support import *  # noqa: F401,F403


class NativeFleetLifecycleTests(NativeFleetTestCase):
    def test_drain_quarantine_recover_and_host_loss_are_idempotent(self) -> None:
        credential, payload, capability_digest, _ = self.enroll_and_advertise(
            host_id="mac-lab-01",
            platform="macos",
            architecture="arm64",
            trust_tier="public-trusted",
            profile="macos-xcode",
            profile_digest=MAC_DIGEST,
            capabilities=["native", "xcode"],
        )
        self.assertTrue(self.fleet.drain("mac-lab-01"))
        self.assertFalse(self.fleet.drain("mac-lab-01"))
        request = dispatch_request(
            request_id="req-drained",
            platform="macos",
            architecture="arm64",
            profile="macos-xcode",
            profile_digest=MAC_DIGEST,
            capabilities=["native", "xcode"],
        )
        drained = self.fleet.schedule(
            request, required_trust_tier="public-trusted", now=NOW
        )
        self.assertIsNone(drained.lease)
        self.assertIn("state:draining", drained.rejection_reasons["mac-lab-01"])
        self.assertTrue(self.fleet.resume("mac-lab-01", now=NOW))
        self.assertFalse(self.fleet.resume("mac-lab-01", now=NOW))

        assigned = self.fleet.schedule(
            request, required_trust_tier="public-trusted", now=NOW
        )
        self.assertIsNotNone(assigned.lease)
        self.assertTrue(
            self.fleet.quarantine(
                "mac-lab-01", reason="cleanup-failure", now=NOW + timedelta(seconds=5)
            )
        )
        self.assertFalse(
            self.fleet.quarantine(
                "mac-lab-01", reason="cleanup-failure", now=NOW + timedelta(seconds=5)
            )
        )
        receipt = self.fleet.receipts_by_request["req-drained"]
        self.assertEqual(receipt.status, "quarantined")
        self.assertEqual(self.fleet.hosts["mac-lab-01"].state, "quarantined")

        self.fleet.heartbeat(
            credential,
            capability_digest=capability_digest,
            now=NOW + timedelta(seconds=10),
        )
        self.assertTrue(
            self.fleet.recover(
                "mac-lab-01",
                capability_digest=capability_digest,
                now=NOW + timedelta(seconds=10),
            )
        )
        self.assertFalse(
            self.fleet.recover(
                "mac-lab-01",
                capability_digest=capability_digest,
                now=NOW + timedelta(seconds=10),
            )
        )

        request2 = dispatch_request(
            request_id="req-host-loss",
            platform="macos",
            architecture="arm64",
            profile="macos-xcode",
            profile_digest=MAC_DIGEST,
            capabilities=["native", "xcode"],
        )
        assigned2 = self.fleet.schedule(
            request2,
            required_trust_tier="public-trusted",
            now=NOW + timedelta(seconds=10),
        )
        self.assertIsNotNone(assigned2.lease)
        self.fleet.sweep(now=NOW + timedelta(minutes=3))
        self.assertEqual(self.fleet.hosts["mac-lab-01"].state, "offline")
        self.assertEqual(self.fleet.receipts_by_request["req-host-loss"].status, "host-lost")

        fresh_envelope = sign_capability_envelope(
            credential,
            payload,
            NOW + timedelta(minutes=3),
            NOW + timedelta(minutes=8),
        )
        self.fleet.advertise(fresh_envelope, now=NOW + timedelta(minutes=3))
        self.assertEqual(self.fleet.hosts["mac-lab-01"].state, "healthy")

    def test_capability_drift_during_active_lease_quarantines_host(self) -> None:
        credential, payload, _, _ = self.enroll_and_advertise(
            host_id="win-lab-01",
            platform="windows",
            architecture="x64",
            trust_tier="public-trusted",
            profile="windows-msvc",
            profile_digest=WINDOWS_DIGEST,
            capabilities=["native", "msvc", "windows-sdk"],
        )
        request = dispatch_request(
            request_id="req-drift",
            platform="windows",
            architecture="x64",
            profile="windows-msvc",
            profile_digest=WINDOWS_DIGEST,
            capabilities=["native", "msvc"],
        )
        assigned = self.fleet.schedule(
            request, required_trust_tier="public-trusted", now=NOW
        )
        self.assertIsNotNone(assigned.lease)
        drifted = copy.deepcopy(payload)
        drifted["capabilities"].append("hyper-v")
        drifted["capabilities"].sort()
        envelope = sign_capability_envelope(
            credential,
            drifted,
            NOW + timedelta(seconds=1),
            NOW + timedelta(minutes=5),
        )
        with self.assertRaisesRegex(FleetError, "capability_drift_during_lease"):
            self.fleet.advertise(envelope, now=NOW + timedelta(seconds=1))
        self.assertEqual(self.fleet.hosts["win-lab-01"].state, "quarantined")
        self.assertEqual(self.fleet.receipts_by_request["req-drift"].status, "quarantined")
