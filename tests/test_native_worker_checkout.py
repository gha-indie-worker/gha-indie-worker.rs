from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path

from tests.native_worker_test_support import COMMIT, NOW, FakeGit, execution, fixtures


class NativeWorkerCheckoutTests(unittest.TestCase):
    def handoff(self, *, context_dir: str = "."):
        dispatch, lease = fixtures(context_dir=context_dir)
        return execution.build_execution_handoff(dispatch, lease, now=NOW)

    def test_one_remote_one_sha_and_detached_head(self):
        fake = FakeGit()
        with tempfile.TemporaryDirectory() as parent:
            evidence = execution.execute_exact_checkout(
                self.handoff(), workspace=Path(parent) / "checkout", now=NOW, runner=fake
            )
        self.assertEqual(evidence["requestedCommitSha"], COMMIT)
        self.assertEqual(evidence["resolvedCommitSha"], COMMIT)
        self.assertTrue(evidence["detachedHead"])
        self.assertEqual(evidence["remotes"], ["origin"])
        fetches = [command for command in fake.commands if "fetch" in command]
        self.assertEqual(len(fetches), 1)
        self.assertEqual(fetches[0][-1], COMMIT)
        flattened = "\n".join(" ".join(command) for command in fake.commands)
        for forbidden in ("refs/heads", "refs/tags", "--branch", "--recurse-submodules"):
            self.assertNotIn(forbidden, flattened)
        for environment in fake.environments:
            self.assertEqual(environment["GIT_TERMINAL_PROMPT"], "0")
            self.assertEqual(environment["GIT_LFS_SKIP_SMUDGE"], "1")
            self.assertNotIn("GIT_SSH_COMMAND", environment)
            self.assertNotIn("GIT_ASKPASS", environment)

    def test_sha_mismatch_deletes_new_workspace(self):
        with tempfile.TemporaryDirectory() as parent:
            workspace = Path(parent) / "checkout"
            with self.assertRaisesRegex(execution.ExecutionError, "checkout_sha_mismatch"):
                execution.execute_exact_checkout(
                    self.handoff(), workspace=workspace, now=NOW, runner=FakeGit(commit="b" * 40)
                )
            self.assertFalse(workspace.exists())

    def test_submodule_extra_remote_and_sensitive_config_fail(self):
        cases = [
            (FakeGit(gitlinks="160000 commit " + "c" * 40 + "\tvendor/dep\x00"), "submodule_gitlink_forbidden"),
            (FakeGit(remotes="backup\norigin\n"), "additional_remote_forbidden"),
            (FakeGit(sensitive_config="http.https://github.com/.extraheader\n"), "persisted_credential_config_forbidden"),
        ]
        for fake, code in cases:
            with self.subTest(code=code), tempfile.TemporaryDirectory() as parent:
                with self.assertRaisesRegex(execution.ExecutionError, code):
                    execution.execute_exact_checkout(
                        self.handoff(), workspace=Path(parent) / "checkout", now=NOW, runner=fake
                    )

    def test_context_directory_must_exist(self):
        handoff = self.handoff(context_dir="src")
        with tempfile.TemporaryDirectory() as parent:
            with self.assertRaisesRegex(execution.ExecutionError, "context_dir_missing"):
                execution.execute_exact_checkout(
                    handoff, workspace=Path(parent) / "checkout", now=NOW, runner=FakeGit()
                )
        with tempfile.TemporaryDirectory() as parent:
            evidence = execution.execute_exact_checkout(
                handoff, workspace=Path(parent) / "checkout", now=NOW,
                runner=FakeGit(create_context="src"),
            )
            self.assertEqual(evidence["contextDir"], "src")

    def test_nonempty_workspace_is_rejected(self):
        with tempfile.TemporaryDirectory() as parent:
            workspace = Path(parent) / "checkout"
            workspace.mkdir()
            (workspace / "unexpected.txt").write_text("x", encoding="utf-8")
            with self.assertRaisesRegex(execution.ExecutionError, "workspace_not_empty"):
                execution.execute_exact_checkout(
                    self.handoff(), workspace=workspace, now=NOW, runner=FakeGit()
                )

    def test_dangerous_git_environment_is_scrubbed(self):
        with tempfile.TemporaryDirectory() as parent:
            original = os.environ.copy()
            try:
                os.environ.update({
                    "GIT_DIR": "/attacker", "GIT_SSH_COMMAND": "echo leaked",
                    "GIT_CONFIG_KEY_0": "http.x.extraheader", "GIT_CONFIG_VALUE_0": "secret",
                })
                environment = execution.git_environment(Path(parent))
            finally:
                os.environ.clear()
                os.environ.update(original)
        for key in ("GIT_DIR", "GIT_SSH_COMMAND", "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0"):
            self.assertNotIn(key, environment)


if __name__ == "__main__":
    unittest.main()
