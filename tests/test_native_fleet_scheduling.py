from native_fleet_test_support import *  # noqa: F401,F403


class NativeFleetSchedulingTests(NativeFleetTestCase):
    def test_mac_windows_and_linux_receive_only_exact_matching_work(self) -> None:
        mac_credential, _, mac_capability_digest, _ = self.enroll_and_advertise(
            host_id="mac-lab-01",
            platform="macos",
            architecture="arm64",
            trust_tier="public-trusted",
            profile="macos-xcode",
            profile_digest=MAC_DIGEST,
            capabilities=["native", "xcode", "ios-simulator"],
        )
        win_credential, _, win_capability_digest, _ = self.enroll_and_advertise(
            host_id="win-lab-01",
            platform="windows",
            architecture="x64",
            trust_tier="public-trusted",
            profile="windows-msvc",
            profile_digest=WINDOWS_DIGEST,
            capabilities=["native", "msvc", "powershell", "windows-sdk"],
        )
        linux_credential, _, linux_capability_digest, _ = self.enroll_and_advertise(
            host_id="linux-lab-01",
            platform="linux",
            architecture="x64",
            trust_tier="public-trusted",
            profile="linux-rust",
            profile_digest=LINUX_DIGEST,
            capabilities=["cargo-cache"],
        )

        mac_request = dispatch_request(
            request_id="req-mac",
            platform="macos",
            architecture="arm64",
            profile="macos-xcode",
            profile_digest=MAC_DIGEST,
            capabilities=["native", "xcode"],
        )
        mac_result = self.fleet.schedule(
            mac_request, required_trust_tier="public-trusted", now=NOW
        )
        self.assertEqual(mac_result.lease.host_id, "mac-lab-01")
        self.assertEqual(mac_result.lease.capability_digest, mac_capability_digest)
        self.assertEqual(
            mac_result.lease.host_capability_snapshot["platform"], "macos"
        )
        self.assertIn("platform:mismatch", mac_result.rejection_reasons["win-lab-01"])

        renewed = self.fleet.renew_lease(
            mac_credential,
            lease_id=mac_result.lease.lease_id,
            nonce=mac_result.lease.nonce,
            now=NOW + timedelta(minutes=1),
        )
        self.assertGreater(renewed.expires_at, NOW + timedelta(minutes=5))
        self.assertTrue(self.fleet.cancel("req-mac"))
        self.assertFalse(self.fleet.cancel("req-mac"))
        mac_receipt = self.fleet.complete(
            mac_credential,
            lease_id=renewed.lease_id,
            nonce=renewed.nonce,
            status="canceled",
            run_manifest_digest=MANIFEST_DIGEST,
            now=NOW + timedelta(minutes=2),
        )
        self.assertEqual(mac_receipt.status, "canceled")

        win_request = dispatch_request(
            request_id="req-win",
            platform="windows",
            architecture="x64",
            profile="windows-msvc",
            profile_digest=WINDOWS_DIGEST,
            capabilities=["native", "msvc", "windows-sdk"],
        )
        win_result = self.fleet.schedule(
            win_request, required_trust_tier="public-trusted", now=NOW
        )
        self.assertEqual(win_result.lease.host_id, "win-lab-01")
        self.assertEqual(win_result.lease.capability_digest, win_capability_digest)
        self.fleet.complete(
            win_credential,
            lease_id=win_result.lease.lease_id,
            nonce=win_result.lease.nonce,
            status="success",
            run_manifest_digest=MANIFEST_DIGEST,
            now=NOW + timedelta(minutes=1),
        )

        linux_request = dispatch_request(
            request_id="req-linux",
            platform="linux",
            architecture="x64",
            profile="linux-rust",
            profile_digest=LINUX_DIGEST,
            capabilities=["cargo-cache"],
        )
        linux_result = self.fleet.schedule(
            linux_request, required_trust_tier="public-trusted", now=NOW
        )
        self.assertEqual(linux_result.lease.host_id, "linux-lab-01")
        self.assertEqual(linux_result.lease.capability_digest, linux_capability_digest)
        self.fleet.complete(
            linux_credential,
            lease_id=linux_result.lease.lease_id,
            nonce=linux_result.lease.nonce,
            status="success",
            run_manifest_digest=MANIFEST_DIGEST,
            now=NOW + timedelta(minutes=1),
        )

    def test_no_match_bad_profile_digest_and_architecture_alias_fail_closed(self) -> None:
        self.enroll_and_advertise(
            host_id="win-lab-01",
            platform="windows",
            architecture="x64",
            trust_tier="public-trusted",
            profile="windows-msvc",
            profile_digest=WINDOWS_DIGEST,
            capabilities=["native", "msvc", "windows-sdk"],
        )
        wrong_digest = dispatch_request(
            request_id="req-bad-digest",
            platform="windows",
            architecture="x64",
            profile="windows-msvc",
            profile_digest=MAC_DIGEST,
            capabilities=["native", "msvc"],
        )
        result = self.fleet.schedule(
            wrong_digest, required_trust_tier="public-trusted", now=NOW
        )
        self.assertIsNone(result.lease)
        self.assertIn(
            "profile-digest:mismatch", result.rejection_reasons["win-lab-01"]
        )

        alias_request = copy.deepcopy(wrong_digest)
        alias_request["requestId"] = "req-alias"
        alias_request["runner"]["architecture"] = "x86_64"
        with self.assertRaisesRegex(FleetError, "architecture_unsupported"):
            self.fleet.schedule(
                alias_request, required_trust_tier="public-trusted", now=NOW
            )

    def test_duplicate_delivery_and_terminal_receipt_are_idempotent(self) -> None:
        credential, _, _, _ = self.enroll_and_advertise(
            host_id="linux-lab-01",
            platform="linux",
            architecture="x64",
            trust_tier="public-trusted",
            profile="linux-rust",
            profile_digest=LINUX_DIGEST,
            capabilities=["cargo-cache"],
        )
        request = dispatch_request(
            request_id="req-duplicate",
            platform="linux",
            architecture="x64",
            profile="linux-rust",
            profile_digest=LINUX_DIGEST,
            capabilities=["cargo-cache"],
        )
        first = self.fleet.schedule(
            request, required_trust_tier="public-trusted", now=NOW
        )
        duplicate = self.fleet.schedule(
            request, required_trust_tier="public-trusted", now=NOW
        )
        self.assertTrue(duplicate.duplicate)
        self.assertEqual(first.lease.lease_id, duplicate.lease.lease_id)

        receipt = self.fleet.complete(
            credential,
            lease_id=first.lease.lease_id,
            nonce=first.lease.nonce,
            status="success",
            run_manifest_digest=MANIFEST_DIGEST,
            now=NOW + timedelta(minutes=1),
        )
        repeated_receipt = self.fleet.complete(
            credential,
            lease_id=first.lease.lease_id,
            nonce=first.lease.nonce,
            status="success",
            run_manifest_digest=MANIFEST_DIGEST,
            now=NOW + timedelta(minutes=1),
        )
        self.assertEqual(receipt, repeated_receipt)
        terminal_duplicate = self.fleet.schedule(
            request,
            required_trust_tier="public-trusted",
            now=NOW + timedelta(minutes=1),
        )
        self.assertTrue(terminal_duplicate.duplicate)
        self.assertEqual(terminal_duplicate.terminal_receipt, receipt)
        self.assertIsNone(terminal_duplicate.lease)

        with self.assertRaisesRegex(FleetError, "terminal_receipt_conflict"):
            self.fleet.complete(
                credential,
                lease_id=first.lease.lease_id,
                nonce=first.lease.nonce,
                status="failure",
                run_manifest_digest=MANIFEST_DIGEST,
                now=NOW + timedelta(minutes=1),
            )

    def test_fair_selection_and_concurrency_limits(self) -> None:
        credential_a, _, digest_a, _ = self.enroll_and_advertise(
            host_id="linux-lab-a",
            platform="linux",
            architecture="x64",
            trust_tier="public-trusted",
            profile="linux-rust",
            profile_digest=LINUX_DIGEST,
            capabilities=["cargo-cache"],
        )
        credential_b, _, _, _ = self.enroll_and_advertise(
            host_id="linux-lab-b",
            platform="linux",
            architecture="x64",
            trust_tier="public-trusted",
            profile="linux-rust",
            profile_digest=LINUX_DIGEST,
            capabilities=["cargo-cache"],
        )
        request_a = dispatch_request(
            request_id="req-a",
            platform="linux",
            architecture="x64",
            profile="linux-rust",
            profile_digest=LINUX_DIGEST,
            capabilities=["cargo-cache"],
        )
        request_b = dispatch_request(
            request_id="req-b",
            platform="linux",
            architecture="x64",
            profile="linux-rust",
            profile_digest=LINUX_DIGEST,
            capabilities=["cargo-cache"],
        )
        first = self.fleet.schedule(
            request_a, required_trust_tier="public-trusted", now=NOW
        )
        second = self.fleet.schedule(
            request_b, required_trust_tier="public-trusted", now=NOW
        )
        self.assertEqual({first.lease.host_id, second.lease.host_id}, {"linux-lab-a", "linux-lab-b"})

        request_c = dispatch_request(
            request_id="req-c",
            platform="linux",
            architecture="x64",
            profile="linux-rust",
            profile_digest=LINUX_DIGEST,
            capabilities=["cargo-cache"],
        )
        no_capacity = self.fleet.schedule(
            request_c, required_trust_tier="public-trusted", now=NOW
        )
        self.assertIsNone(no_capacity.lease)
        self.assertTrue(
            all("concurrency:exhausted" in reasons for reasons in no_capacity.rejection_reasons.values())
        )

        lease_a = first.lease if first.lease.host_id == "linux-lab-a" else second.lease
        self.fleet.complete(
            credential_a,
            lease_id=lease_a.lease_id,
            nonce=lease_a.nonce,
            status="success",
            run_manifest_digest=MANIFEST_DIGEST,
            now=NOW + timedelta(seconds=10),
        )
        third = self.fleet.schedule(
            request_c,
            required_trust_tier="public-trusted",
            now=NOW + timedelta(seconds=10),
        )
        self.assertEqual(third.lease.host_id, "linux-lab-a")
        self.assertEqual(third.lease.capability_digest, digest_a)

        lease_b = first.lease if first.lease.host_id == "linux-lab-b" else second.lease
        self.fleet.complete(
            credential_b,
            lease_id=lease_b.lease_id,
            nonce=lease_b.nonce,
            status="success",
            run_manifest_digest=MANIFEST_DIGEST,
            now=NOW + timedelta(seconds=10),
        )
