#!/usr/bin/env python3
"""Versioned, fail-closed Oreslang admission conformance runner.

No Oreslang execution is performed here: only LLVM IR emission or Java --check.
The command is an explicit argv template (never shell=True). Pending cases are
listed but never treated as passing conformance.
"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parent


def validate_suite():
    try:
        import jsonschema
    except ImportError as exc:
        raise RuntimeError("Install dependency: python3 -m pip install jsonschema") from exc
    schema = json.loads((ROOT / "schema" / "suite.schema.json").read_text())
    suite = json.loads((ROOT / "cases.json").read_text())
    jsonschema.Draft202012Validator.check_schema(schema)
    jsonschema.validate(instance=suite, schema=schema)
    seen = set()
    for case in suite["cases"]:
        cid = case["id"]
        if cid in seen:
            raise ValueError(f"duplicate contract case ID: {cid}")
        seen.add(cid)
        source = (ROOT / case["file"]).resolve()
        if not source.is_relative_to(ROOT / "fixtures"):
            raise ValueError(f"fixture escapes fixture root: {cid}")
        if not source.is_file():
            raise ValueError(f"missing fixture: {case['file']}")
        if source.suffix != ".ores" or source.stat().st_size == 0:
            raise ValueError(f"empty or incorrectly named fixture: {cid}")
        if "pending" in case["backends"].values() and not case.get("pending_reason"):
            raise ValueError(f"pending case without explanation: {cid}")
        if "pending" not in case["backends"].values() and "pending_reason" in case:
            raise ValueError(f"obsolete pending reason: {cid}")
    all_sources = {case["file"] for case in suite["cases"]}
    disk_sources = {"fixtures/" + p.name for p in (ROOT / "fixtures").glob("*.ores")}
    if disk_sources != all_sources:
        raise ValueError(f"orphan/missing fixture files: {sorted(disk_sources ^ all_sources)}")
    return suite


def run_backend(suite, backend, command, timeout):
    argv = shlex.split(command)
    if not argv or sum(token.count("{source}") for token in argv) != 1:
        raise ValueError("--command must contain exactly one {source} placeholder")
    results = []
    failures = 0
    for case in suite["cases"]:
        if case["backends"][backend] == "pending":
            results.append({"id": case["id"], "status": "pending",
                            "diagnostic": case["pending_reason"]})
            print(f"PENDING {backend}: {case['id']}: {case['pending_reason']}")
            continue
        source = str((ROOT / case["file"]).resolve())
        args = [part.replace("{source}", source) for part in argv]
        started = time.monotonic()
        try:
            proc = subprocess.run(args, capture_output=True, text=True,
                                  timeout=timeout, check=False)
            exitcode = proc.returncode
            admitted = exitcode == 0
            # Only the compiler's normal rejection status proves a negative
            # case. Signals, probe/setup errors (2), and launcher failures
            # (126/127) are infrastructure failures, never semantic rejection.
            success = exitcode == (0 if case["admission"] == "accept" else 1)
            # Rejecting LLVM must not leak partially emitted LLVM IR on stdout.
            if backend == "llvm" and not admitted and proc.stdout.strip():
                success = False
            diagnostic = (proc.stderr or proc.stdout).strip()[:700]
        except (OSError, subprocess.TimeoutExpired) as ex:
            success = False
            exitcode = -1
            diagnostic = str(ex)
        record = {"id": case["id"], "status": "pass" if success else "fail",
                  "observed_exit_code": exitcode,
                  "elapsed_ms": round((time.monotonic() - started) * 1000)}
        if diagnostic and not success:
            record["diagnostic"] = diagnostic
        results.append(record)
        print(f"{'PASS' if success else 'FAIL'} {backend}: {case['id']}"
              + (f" :: {diagnostic}" if not success else ""))
        if not success:
            failures += 1
    return results, failures


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--validate", action="store_true", help="validate contracts only")
    ap.add_argument("--backend", choices=["llvm", "graal"])
    ap.add_argument("--command", help="argv template containing exactly one {source}")
    ap.add_argument("--timeout", type=int, default=90)
    ap.add_argument("--report", type=Path)
    args = ap.parse_args()
    suite = validate_suite()
    print(f"Validated {len(suite['cases'])} conformance cases (schema {suite['schema_version']})")
    if args.validate:
        if args.backend or args.command or args.report:
            ap.error("--validate may not be combined with backend execution")
        return 0
    if not args.backend or not args.command:
        ap.error("--backend and --command are required for execution")
    if args.timeout <= 0 or args.timeout > 300:
        ap.error("--timeout must be between 1 and 300 seconds")
    results, failures = run_backend(suite, args.backend, args.command, args.timeout)
    report = {"schema_version": suite["schema_version"], "backend": args.backend,
              "results": results}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    if failures:
        print(f"FAIL: {failures} required conformance cases failed", file=sys.stderr)
        return 1
    passed = sum(1 for r in results if r["status"] == "pass")
    pending = sum(1 for r in results if r["status"] == "pending")
    print(f"RESULT: {passed} required cases passed, {pending} pending (NOT counted as pass)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, ValueError, OSError) as error:
        print(f"Conformance manifest failure: {error}", file=sys.stderr)
        sys.exit(2)
