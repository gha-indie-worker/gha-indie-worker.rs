#!/usr/bin/env python3
"""Fail-closed comparison of two independent backend conformance reports."""
import argparse
import json
import re
from pathlib import Path
import sys

from run import validate_suite

def compare(suite, digest, llvm, graal, *, require_suite_parity=False, expected_revisions=None):
    # Only the explicit strict gate can assert completion of the *fixture suite*.
    # It is never evidence of parity for language features absent from the suite.
    if require_suite_parity and (not expected_revisions or set(expected_revisions) != {"llvm", "graal"}):
        raise ValueError("strict suite parity requires pinned --llvm-revision and --graal-revision")
    try:
        import jsonschema
    except ImportError as exc:
        raise ValueError("jsonschema is required to compare reports") from exc
    report_schema = json.loads((Path(__file__).parent / "report.schema.json").read_text())
    records = {}
    for backend, report in (("llvm", llvm), ("graal", graal)):
        jsonschema.validate(report, report_schema)
        if report["backend"] != backend or report["suite_sha256"] != digest:
            raise ValueError(f"wrong backend or suite hash for {backend}")
        if expected_revisions is not None:
            revision = expected_revisions.get(backend)
            if revision is not None:
                if not re.fullmatch(r"[0-9a-f]{40}", revision):
                    raise ValueError(f"invalid expected {backend} revision")
                if report["source_revision"] != revision:
                    raise ValueError(f"stale {backend} report: revision does not match requested source head")
        ids = [r["id"] for r in report["results"]]
        expected = [case["id"] for case in suite["cases"]]
        if len(ids) != len(set(ids)) or set(ids) != set(expected):
            raise ValueError(f"duplicate, missing or extra test IDs for {backend}")
        records[backend] = {r["id"]: r for r in report["results"]}
    common = 0
    missing = 0
    defects = []
    for case in suite["cases"]:
        statuses = []
        for backend in ("llvm", "graal"):
            record = records[backend][case["id"]]
            status = record["status"]
            if case["backends"][backend] == "pending":
                if (status != "pending" or "observed_exit" in record
                        or "diagnostic_class" in record
                        or record.get("detail") != case["pending_reason"]):
                    defects.append(f"{case['id']} {backend}: malformed pending record ({status})")
                else:
                    missing += 1
            else:
                if status != "pass":
                    defects.append(f"{case['id']} {backend}: required test status {status}")
                elif case["kind"] == "native_exit" and (
                        record.get("observed_exit") != case["expected_exit"]
                        or "diagnostic_class" in record):
                    defects.append(f"{case['id']} {backend}: wrong native execution evidence")
                elif case["kind"] == "diagnostic" and (record.get("diagnostic_class") != case["diagnostic_class"]
                        or "observed_exit" in record):
                    defects.append(f"{case['id']} {backend}: wrong diagnostic evidence")
                elif status == "pass" and record.get("detail", ""):
                    defects.append(f"{case['id']} {backend}: passing record contains an error detail")
            statuses.append(status)
        if case["backends"]["llvm"] == "required" and case["backends"]["graal"] == "required":
            if statuses == ["pass", "pass"]:
                common += 1
    suite_complete = common == len(suite["cases"]) and missing == 0 and not defects
    if require_suite_parity and not suite_complete:
        defects.append("strict fixture parity blocked: pending, failing or unmatched suite cases remain")
    for defect in defects:
        print("FAIL:", defect, file=sys.stderr)
    print(f"COMMON_VERIFIED={common} SUITE_TOTAL={len(suite['cases'])} "
          f"BACKEND_PENDING={missing} SUITE_COMPLETE={'yes' if suite_complete else 'no'} "
          f"DEFECTS={len(defects)}")
    return 1 if defects else 0

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--llvm", required=True, type=Path)
    p.add_argument("--graal", required=True, type=Path)
    p.add_argument("--llvm-revision", help="expected exact LLVM compiler Git SHA")
    p.add_argument("--graal-revision", help="expected exact Graal compiler Git SHA")
    p.add_argument("--require-suite-parity", action="store_true",
                   help="fail if any fixture is pending; requires both exact source revisions")
    opts = p.parse_args()
    suite, digest = validate_suite()
    revisions = {"llvm": opts.llvm_revision, "graal": opts.graal_revision}
    if opts.require_suite_parity and not all(revisions.values()):
        p.error("--require-suite-parity needs --llvm-revision and --graal-revision")
    return compare(suite, digest, json.loads(opts.llvm.read_text()), json.loads(opts.graal.read_text()),
                   require_suite_parity=opts.require_suite_parity, expected_revisions=revisions)

if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError) as ex:
        print(f"Comparison infrastructure error: {ex}", file=sys.stderr)
        sys.exit(2)
