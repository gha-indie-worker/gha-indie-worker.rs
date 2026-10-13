"""Runner control-flow tests; these do not certify either compiler backend."""
import contextlib
import importlib.util
import io
from pathlib import Path
import shlex
import sys
import unittest

spec = importlib.util.spec_from_file_location("conformance_runner", Path(__file__).with_name("run.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RunnerExitStatusTest(unittest.TestCase):
    def run_probe(self, code, admission="reject", backend="graal", pending=False):
        case = {"id": "probe.status", "file": "fixtures/probe.ores",
                "admission": admission, "backends": {backend: "pending" if pending else "required"},
                "pending_reason": "Test of pending handling"}
        command = shlex.join([sys.executable, "-c", code, "{source}"])
        with contextlib.redirect_stdout(io.StringIO()):
            return runner.run_backend({"cases": [case]}, backend, command, 2)

    def test_normal_rejection(self):
        for backend in ("graal", "llvm"):
            with self.subTest(backend=backend):
                results, failures = self.run_probe("raise SystemExit(1)", backend=backend)
                self.assertEqual(0, failures)
                self.assertEqual("pass", results[0]["status"])

    def test_infrastructure_exit_is_not_rejection(self):
        for backend in ("graal", "llvm"):
            for status in (2, 126, 127, 137, 255):
                with self.subTest(backend=backend, status=status):
                    results, failures = self.run_probe(f"raise SystemExit({status})", backend=backend)
                    self.assertEqual(1, failures)
                    self.assertEqual("fail", results[0]["status"])

    @unittest.skipIf(sys.platform == "win32", "POSIX signal exit status")
    def test_signal_is_not_rejection(self):
        for backend in ("graal", "llvm"):
            with self.subTest(backend=backend):
                results, failures = self.run_probe(
                    "import os, signal; os.kill(os.getpid(), signal.SIGTERM)", backend=backend)
                self.assertEqual(1, failures)
                self.assertLess(results[0]["observed_exit_code"], 0)

    def test_accept_requires_success(self):
        self.assertEqual(0, self.run_probe("pass", admission="accept")[1])
        self.assertEqual(1, self.run_probe("raise SystemExit(1)", admission="accept")[1])
        self.assertEqual(1, self.run_probe("pass", admission="reject")[1])

    def test_llvm_rejection_must_not_emit_partial_ir(self):
        self.assertEqual(1, self.run_probe("print('partial IR'); raise SystemExit(1)", backend="llvm")[1])

    def test_pending_does_not_execute_or_pass(self):
        results, failures = self.run_probe("raise RuntimeError('must not execute')", pending=True)
        self.assertEqual(0, failures)
        self.assertEqual("pending", results[0]["status"])
        self.assertNotIn("observed_exit_code", results[0])


if __name__ == "__main__":
    unittest.main()
