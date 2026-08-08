from __future__ import annotations

from tools.native_fleet_protocol import dispatch_request
from tools.native_worker_execution import ExecutionError, build_execution_handoff

from native_fleet_test_support import MAC_DIGEST, NOW, NativeFleetTestCase


class NativeWorkerHandoffIntegrationTests(NativeFleetTestCase):
    def test_exact_scheduler_lease_becomes_one_execution_handoff(self):
        self.enroll_and_advertise(
            host_id="macos-integration-01",
            platform="macos",
            architecture="arm64",
            trust_tier="public-trusted",
            profile="macos-xcode",
            profile_digest=MAC_DIGEST,
            capabilities=["native", "xcode"],
        )
        request = dispatch_request(
            request_id="gha:integration:macos",
            platform="macos",
            architecture="arm64",
            profile="macos-xcode",
            profile_digest=MAC_DIGEST,
            capabilities=["native", "xcode"],
        )
        assignment = self.fleet.schedule(
            request,
            required_trust_tier="public-trusted",
            now=NOW,
        )
        self.assertIsNotNone(assignment.lease)
        handoff = build_execution_handoff(request, assignment.lease, now=NOW)
        self.assertEqual(handoff["leaseId"], assignment.lease.lease_id)
        self.assertEqual(handoff["leaseNonce"], assignment.lease.nonce)
        self.assertEqual(handoff["commitSha"], request["commitSha"])
        self.assertEqual(handoff["runner"], request["runner"])
        self.assertEqual(handoff["profileDigest"], MAC_DIGEST)

        duplicate = self.fleet.schedule(
            request,
            required_trust_tier="public-trusted",
            now=NOW,
        )
        duplicate_handoff = build_execution_handoff(request, duplicate.lease, now=NOW)
        self.assertEqual(duplicate_handoff["leaseId"], handoff["leaseId"])
        self.assertEqual(duplicate_handoff["handoffDigest"], handoff["handoffDigest"])

    def test_cancelled_scheduler_lease_cannot_cross_execution_boundary(self):
        self.enroll_and_advertise(
            host_id="macos-integration-02",
            platform="macos",
            architecture="arm64",
            trust_tier="public-trusted",
            profile="macos-xcode",
            profile_digest=MAC_DIGEST,
            capabilities=["native", "xcode"],
        )
        request = dispatch_request(
            request_id="gha:integration:cancel",
            platform="macos",
            architecture="arm64",
            profile="macos-xcode",
            profile_digest=MAC_DIGEST,
            capabilities=["native", "xcode"],
        )
        assignment = self.fleet.schedule(
            request,
            required_trust_tier="public-trusted",
            now=NOW,
        )
        self.fleet.cancel(request["requestId"])
        with self.assertRaisesRegex(ExecutionError, "lease_cancel_pending"):
            build_execution_handoff(request, assignment.lease, now=NOW)
