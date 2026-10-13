#!/usr/bin/env python3
import unittest
from pathlib import Path
import sys
from unittest.mock import patch
from subprocess import CompletedProcess

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run

class DiagnosticProof(unittest.TestCase):
    def test_only_semantic_exit_one_is_rejection(self):
        for status in (0, 2, 3, 126, 127, -9, -11):
            with self.subTest(status=status):
                self.assertFalse(run.check_diagnostic(status, "", "unknown parameter", "unknown_name", "llvm"))
        self.assertTrue(run.check_diagnostic(1, "", "unknown parameter 'x'", "unknown_name", "llvm"))

    def test_unrelated_error_cannot_pass_negative(self):
        self.assertFalse(run.check_diagnostic(1, "", "wrong call arity", "unknown_name", "llvm"))
        self.assertFalse(run.check_diagnostic(1, "", "", "unknown_name", "llvm"))
        self.assertFalse(run.check_diagnostic(1, "partial IR", "unknown parameter", "unknown_name", "llvm"))
        self.assertTrue(run.check_diagnostic(1, "", "duplicate function", "duplicate_declaration", "llvm"))

    def test_command_requires_one_placeholder(self):
        for command in ("", "clang foo", "tool {source} {source}"):
            with self.subTest(command=command):
                with self.assertRaises(ValueError):
                    run.validate_command(command)
        self.assertEqual(run.validate_command("compiler {source}"), ["compiler", "{source}"])

    def test_validates_strict_schema_fixture_inventory(self):
        suite, digest = run.validate_suite()
        self.assertGreaterEqual(len(suite["cases"]), 10)
        self.assertEqual(len(digest), 64)

    def test_mutating_fixture_or_schema_changes_suite_identity(self):
        import shutil
        import tempfile
        original_suite, original_digest = run.validate_suite()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            shutil.copytree(run.ROOT, root, dirs_exist_ok=True)
            with patch.object(run, "ROOT", root):
                _, untouched = run.validate_suite()
                self.assertEqual(original_digest, untouched)
                case = original_suite["cases"][0]
                fixture = root / case["source"]
                before = fixture.read_bytes()
                fixture.write_bytes(before + b" // unexpected backend divergence\n")
                _, changed_source = run.validate_suite()
                self.assertNotEqual(original_digest, changed_source)
                fixture.write_bytes(before)
                schema = root / "schema.json"
                schema.write_text(schema.read_text() + " ")
                _, changed_schema = run.validate_suite()
                self.assertNotEqual(original_digest, changed_schema)

    def test_compiler_crash_never_counts_as_pass(self):
        case = {"id":"diagnostic.crash","kind":"diagnostic","source":"fixtures/unknown.ores",
                "diagnostic_class":"unknown_name"}
        for exit_code in (-11, 2, 127):
            with patch.object(run.subprocess, "run", return_value=CompletedProcess(
                    args=[], returncode=exit_code, stdout="", stderr="unknown parameter")):
                result = run.run_case(case, "llvm", ["fake", "{source}"], "", 3)
                self.assertEqual("fail", result["status"])

    def test_graal_native_result_requires_real_execution_and_exact_exit(self):
        case = {"id": "native.add", "kind": "native_exit",
                "source": "fixtures/add.ores", "expected_exit": 42}
        check = CompletedProcess(args=[], returncode=0, stdout="", stderr="")
        executed = CompletedProcess(args=[], returncode=42, stdout="", stderr="")
        with patch.object(run.subprocess, "run", side_effect=[check, executed]) as call:
            result = run.run_case(case, "graal", ["check", "{source}"], "", 3,
                                  ["guest", "{source}"])
        self.assertEqual(result["status"], "pass")
        self.assertEqual(result["observed_exit"], 42)
        self.assertEqual(call.call_count, 2)

        # No guest command is not silently reported as a passing result.
        with patch.object(run.subprocess, "run", return_value=check):
            missing = run.run_case(case, "graal", ["check", "{source}"], "", 3)
        self.assertEqual(missing["status"], "fail")

        for status, stdout, stderr in [(0, "", ""), (43, "", ""),
                                       (42, "unexpected", ""), (42, "", "exception")]:
            with self.subTest(status=status, stdout=stdout, stderr=stderr):
                wrong = CompletedProcess(args=[], returncode=status,
                                         stdout=stdout, stderr=stderr)
                with patch.object(run.subprocess, "run", side_effect=[check, wrong]):
                    result = run.run_case(case, "graal", ["check", "{source}"], "", 3,
                                          ["guest", "{source}"])
                self.assertEqual(result["status"], "fail")

        # A failing compiler check must not run the guest process.
        reject = CompletedProcess(args=[], returncode=1, stdout="", stderr="type error")
        with patch.object(run.subprocess, "run", return_value=reject) as mock_run:
            result = run.run_case(case, "graal", ["check", "{source}"], "", 3,
                                  ["guest", "{source}"])
        self.assertEqual(result["status"], "fail")
        self.assertEqual(mock_run.call_count, 1)

    def test_native_test_does_not_skip_missing_clang(self):
        case = {"id":"native.add","kind":"native_exit","source":"fixtures/add.ores","expected_exit":42}
        with patch.object(run.subprocess, "run", return_value=CompletedProcess(
                args=[], returncode=0, stdout="; ModuleID = 'foo'\n", stderr="")):
            result = run.run_case(case, "llvm", ["fake", "{source}"], "", 3)
            self.assertEqual("fail", result["status"])


class ComparatorParityProof(unittest.TestCase):
    """The incremental conformance comparison must never certify pending parity."""

    def setUp(self):
        self.suite, self.digest = run.validate_suite()
        self.sha = {"llvm": "a" * 40, "graal": "b" * 40}
        self.reports = {}
        for backend in ("llvm", "graal"):
            results = []
            for case in self.suite["cases"]:
                if case["backends"][backend] == "pending":
                    record = {"id": case["id"], "status": "pending",
                              "detail": case["pending_reason"]}
                elif case["kind"] == "diagnostic":
                    record = {"id": case["id"], "status": "pass",
                              "diagnostic_class": case["diagnostic_class"], "detail": ""}
                else:
                    record = {"id": case["id"], "status": "pass",
                              "observed_exit": case["expected_exit"], "detail": ""}
                results.append(record)
            self.reports[backend] = {
                "schema_version": "1.1.0", "backend": backend,
                "suite_sha256": self.digest, "source_revision": self.sha[backend],
                "results": results,
            }

    def compare(self, *, strict=False, revisions=None, suite=None):
        from contextlib import redirect_stdout, redirect_stderr
        import io
        import compare
        selected = suite or self.suite
        ids = {case["id"] for case in selected["cases"]}
        reports = {backend: dict(report, results=[
            record for record in report["results"] if record["id"] in ids])
            for backend, report in self.reports.items()}
        with redirect_stdout(io.StringIO()) as out, redirect_stderr(io.StringIO()):
            status = compare.compare(selected, self.digest,
                                     reports["llvm"], reports["graal"],
                                     require_suite_parity=strict, expected_revisions=revisions)
        return status, out.getvalue()

    def synthetic_pending(self, backend="llvm"):
        # Regression-only coverage: a fully required real conformance suite
        # must not erase testing of the comparator's pending safety boundary.
        case = self.suite["cases"][0]
        case["backends"][backend] = "pending"
        case["pending_reason"] = "synthetic comparator regression only"
        record = self.reports[backend]["results"][0]
        record.clear()
        record.update({"id": case["id"], "status": "pending",
                       "detail": case["pending_reason"]})
        return record

    def test_incremental_mode_is_explicitly_incomplete(self):
        self.synthetic_pending()
        status, summary = self.compare()
        self.assertEqual(status, 0)
        self.assertIn("SUITE_COMPLETE=no", summary)
        self.assertIn("BACKEND_PENDING=", summary)

    def test_strict_gate_rejects_pending_cases(self):
        self.synthetic_pending()
        status, summary = self.compare(strict=True, revisions=self.sha)
        self.assertEqual(status, 1)
        self.assertIn("SUITE_COMPLETE=no", summary)

    def test_strict_gate_accepts_complete_fixture_suite_with_pinned_heads(self):
        status, summary = self.compare(strict=True, revisions=self.sha)
        self.assertEqual(status, 0)
        self.assertIn("SUITE_COMPLETE=yes", summary)
        self.assertIn("BACKEND_PENDING=0", summary)

    def test_strict_gate_demands_pinned_heads(self):
        with self.assertRaisesRegex(ValueError, "requires pinned"):
            self.compare(strict=True)

    def test_stale_source_sha_rejected(self):
        revisions = dict(self.sha, llvm="c" * 40)
        with self.assertRaisesRegex(ValueError, "stale llvm report"):
            self.compare(revisions=revisions)

    def test_pending_cannot_smuggle_runtime_or_diagnostic_evidence(self):
        record = self.synthetic_pending("graal")
        self.assertEqual(self.compare()[0], 0)
        for evidence in ({"observed_exit": 0}, {"diagnostic_class": "unknown_name"}):
            record.update(evidence)
            self.assertEqual(self.compare()[0], 1)
            for key in evidence:
                del record[key]

    def test_required_diagnostic_cannot_smuggle_native_exit(self):
        record = next(item for item in self.reports["llvm"]["results"]
                      if item.get("diagnostic_class"))
        record["observed_exit"] = 0
        self.assertEqual(self.compare()[0], 1)

    def test_required_native_cannot_smuggle_diagnostic(self):
        record = next(item for item in self.reports["llvm"]["results"]
                      if "observed_exit" in item)
        record["diagnostic_class"] = "unknown_name"
        self.assertEqual(self.compare()[0], 1)

    def test_strict_gate_can_verify_fully_required_subset_only(self):
        # A tiny suite passing its fixtures still does NOT prove language parity.
        suite = {"cases": [case for case in self.suite["cases"]
                          if all(s == "required" for s in case["backends"].values())]}
        self.assertTrue(suite["cases"])
        status, summary = self.compare(strict=True, revisions=self.sha, suite=suite)
        self.assertEqual(status, 0)
        self.assertIn("SUITE_COMPLETE=yes", summary)

if __name__ == "__main__":
    unittest.main()
