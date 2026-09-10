from __future__ import annotations

import copy
import unittest
from datetime import timedelta

from tests.native_worker_test_support import COMMIT, NOW, execution, fixtures


class NativeWorkerHandoffTests(unittest.TestCase):
    def test_active_lease_builds_digest_bound_handoff(self):
        dispatch, lease = fixtures(platform="macos", architecture="arm64")
        handoff = execution.build_execution_handoff(dispatch, lease, now=NOW)
        self.assertEqual(handoff["commitSha"], COMMIT)
        self.assertEqual(handoff["runner"]["platform"], "macos")
        self.assertEqual(handoff["checkoutPolicy"], execution.CHECKOUT_POLICY)
        self.assertNotIn("run", handoff)
        self.assertNotIn("uses", handoff)
        execution.validate_execution_handoff(handoff, now=NOW)

    def test_tampered_handoff_fails_digest(self):
        dispatch, lease = fixtures()
        handoff = execution.build_execution_handoff(dispatch, lease, now=NOW)
        handoff["commitSha"] = "b" * 40
        with self.assertRaisesRegex(execution.ExecutionError, "handoff_digest_mismatch"):
            execution.validate_execution_handoff(handoff, now=NOW)

    def test_dispatch_lease_identity_mismatch_fails_closed(self):
        dispatch, lease = fixtures()
        lease["commitSha"] = "b" * 40
        with self.assertRaisesRegex(execution.ExecutionError, "lease_dispatch_mismatch"):
            execution.build_execution_handoff(dispatch, lease, now=NOW)

    def test_expired_canceled_and_terminal_leases_are_rejected(self):
        dispatch, lease = fixtures()
        expired = copy.deepcopy(lease)
        expired["expiresAt"] = execution.format_time(NOW)
        with self.assertRaisesRegex(execution.ExecutionError, "lease_expired"):
            execution.build_execution_handoff(dispatch, expired, now=NOW)
        canceled = copy.deepcopy(lease)
        canceled["cancelRequested"] = True
        with self.assertRaisesRegex(execution.ExecutionError, "lease_cancel_pending"):
            execution.build_execution_handoff(dispatch, canceled, now=NOW)
        terminal = copy.deepcopy(lease)
        terminal["terminalStatus"] = "success"
        with self.assertRaisesRegex(execution.ExecutionError, "lease_terminal"):
            execution.build_execution_handoff(dispatch, terminal, now=NOW)

    def test_capability_snapshot_must_match_target_and_digest(self):
        dispatch, lease = fixtures(platform="windows", architecture="x64")
        lease["hostCapabilitySnapshot"]["architecture"] = "arm64"
        lease["capabilityDigest"] = execution.sha256_digest(lease["hostCapabilitySnapshot"])
        with self.assertRaisesRegex(execution.ExecutionError, "runner_snapshot_mismatch"):
            execution.build_execution_handoff(dispatch, lease, now=NOW)

    def test_forbidden_repository_transports_and_suffixes(self):
        invalid = [
            "ssh://github.com/owner/repo.git", "git://github.com/owner/repo.git",
            "file:///tmp/repo", "https://token@github.com/owner/repo.git",
            "https://github.com.evil.example/owner/repo.git",
            "https://github.com:443/owner/repo.git",
            "https://github.com:invalid/owner/repo.git",
            "https://github.com/owner/repo.git?ref=main",
            "https://github.com/owner/repo.git#main",
            "https://github.com/owner/repo.git/",
            "https://github.com/owner/%72epo.git",
        ]
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(execution.ExecutionError):
                execution.validate_repository_url(value)

    def test_branch_tag_uppercase_and_short_sha_are_rejected(self):
        dispatch, _ = fixtures()
        for value in ("main", "refs/heads/main", "v1.2.3", "A" * 40, "a" * 39):
            candidate = copy.deepcopy(dispatch)
            candidate["commitSha"] = value
            with self.subTest(value=value), self.assertRaisesRegex(execution.ExecutionError, "commit_sha_invalid"):
                execution.validate_dispatch(candidate)

    def test_context_traversal_and_windows_syntax_are_rejected(self):
        for value in ("../src", "src/../bin", "/src", "src\\bin", "src//bin", "./src"):
            with self.subTest(value=value), self.assertRaisesRegex(execution.ExecutionError, "context_dir_invalid"):
                execution.validate_context_dir(value)

    def test_unknown_and_secret_fields_are_rejected(self):
        dispatch, lease = fixtures()
        dispatch["unknown"] = True
        with self.assertRaisesRegex(execution.ExecutionError, "field_unknown"):
            execution.build_execution_handoff(dispatch, lease, now=NOW)
        dispatch, lease = fixtures()
        lease["hostCapabilitySnapshot"]["accessToken"] = "not-a-real-token"
        lease["capabilityDigest"] = execution.sha256_digest(lease["hostCapabilitySnapshot"])
        with self.assertRaisesRegex(execution.ExecutionError, "secret_field_forbidden"):
            execution.build_execution_handoff(dispatch, lease, now=NOW)

    def test_future_handoff_is_rejected(self):
        dispatch, lease = fixtures()
        handoff = execution.build_execution_handoff(dispatch, lease, now=NOW + timedelta(seconds=31))
        with self.assertRaisesRegex(execution.ExecutionError, "handoff_from_future"):
            execution.validate_execution_handoff(handoff, now=NOW)


if __name__ == "__main__":
    unittest.main()
