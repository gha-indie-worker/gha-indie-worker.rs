#!/usr/bin/env python3
"""Independent compiler admission, diagnostic class and native-result contract runner.

Semantics here belong to the suite, not either compiler implementation.
Only the LLVM backend currently has a native exit-result adapter.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent
CLASSES = {
    "llvm": {
        "unknown_name": r"unknown parameter",
        "call_arity": r"wrong call arity",
        "duplicate_declaration": r"duplicate function",
        "integer_overflow": r"overflows signed 64-bit",
        "unterminated_comment": r"unterminated block comment",
        "ownership_violation": r"(borrow|ownership|mutat)",
    },
    "graal": {
        "unknown_name": r"unknown name",
        "call_arity": r"(arity|argument|expects)",
        "duplicate_declaration": r"(duplicate|already has arity)",
        "integer_overflow": r"(overflow|out of range)",
        "unterminated_comment": r"unterminated block comment",
        "ownership_violation": r"(borrow|ownership|mutat)",
    }
}

def validate_suite():
    try:
        import jsonschema
    except ImportError as exc:
        raise ValueError("install jsonschema>=4.20,<5 to validate contracts") from exc
    schema = json.loads((ROOT / "schema.json").read_text())
    jsonschema.Draft202012Validator.check_schema(schema)
    raw = (ROOT / "cases.json").read_bytes()
    suite = json.loads(raw)
    jsonschema.validate(suite, schema)
    ids = set()
    files = set()
    for case in suite["cases"]:
        if case["id"] in ids:
            raise ValueError("duplicate case: " + case["id"])
        ids.add(case["id"])
        f = (ROOT / case["source"]).resolve()
        if not f.is_relative_to((ROOT / "fixtures").resolve()) or not f.is_file():
            raise ValueError("invalid fixture: " + case["source"])
        if f.stat().st_size == 0:
            raise ValueError("empty fixture: " + case["id"])
        files.add(case["source"])
        pending = "pending" in case["backends"].values()
        if pending != bool(case.get("pending_reason")):
            raise ValueError("pending requires reason and resolved cases cannot retain one: " + case["id"])
        if all(s == "pending" for s in case["backends"].values()):
            raise ValueError("a case cannot be pending on both backends: " + case["id"])
    on_disk = {"fixtures/" + f.name for f in (ROOT / "fixtures").glob("*.ores")}
    if files != on_disk:
        raise ValueError("orphan or missing fixtures: " + str(sorted(files ^ on_disk)))
    # Bind the suite identity to the actual programs and normative contracts.
    # A manifest-only digest permits different backends to run different source
    # bytes while claiming identical test inventories. Use length-delimited
    # records so filename/content boundaries are unambiguous.
    h = hashlib.sha256(b"oreslang-conformance-suite-v1.1\0")
    for name in ["cases.json", "schema.json", "report.schema.json", "main.tsp", *sorted(files)]:
        data = (ROOT / name).read_bytes()
        encoded = name.encode("utf-8")
        h.update(len(encoded).to_bytes(4, "big"))
        h.update(encoded)
        h.update(len(data).to_bytes(8, "big"))
        h.update(data)
    return suite, h.hexdigest()

def validate_command(command):
    argv = shlex.split(command)
    if not argv or sum(p.count("{source}") for p in argv) != 1:
        raise ValueError("compiler command must contain exactly one {source}")
    return argv

def check_diagnostic(returncode, stdout, stderr, category, backend):
    # A crash, missing compiler, signal or setup failure cannot satisfy a rejection.
    # A valid rejection must also identify the intended semantic error.
    if returncode != 1 or (backend == "llvm" and stdout.strip()):
        return False
    return bool(re.search(CLASSES[backend][category], stderr, flags=re.IGNORECASE))

def run_case(case, backend, argv, clang, timeout, run_argv=None):
    source = str((ROOT / case["source"]).resolve())
    command = [piece.replace("{source}", source) for piece in argv]
    try:
        compiled = subprocess.run(command, capture_output=True, text=True,
                                  check=False, timeout=timeout)
        if case["kind"] == "diagnostic":
            cls = case["diagnostic_class"]
            passed = check_diagnostic(compiled.returncode, compiled.stdout,
                                      compiled.stderr, cls, backend)
            return {"id": case["id"], "status": "pass" if passed else "fail",
                    "diagnostic_class": cls if passed else "unclassified",
                    "detail": "" if passed else f"code={compiled.returncode}: {compiled.stderr[:450]}"}
        if compiled.returncode != 0:
            return {"id": case["id"], "status": "fail",
                    "detail": f"compiler exit={compiled.returncode}: {compiled.stderr[:450]}"}
        if backend == "graal":
            # The Java/Graal launcher runs guest main(): int as the process exit
            # status, so no LLVM IR or Clang is involved. The compiler check
            # above is still mandatory: runtime crashes cannot satisfy a
            # negative diagnostic and a missing executable cannot be a pass.
            if run_argv is None:
                return {"id": case["id"], "status": "fail",
                        "detail": "Graal source-execution command not configured"}
            guest_command = [part.replace("{source}", source) for part in run_argv]
            guest = subprocess.run(guest_command, capture_output=True, text=True,
                                   check=False, timeout=timeout)
            expected = case["expected_exit"]
            passed = guest.returncode == expected and not guest.stdout and not guest.stderr
            return {"id": case["id"], "status": "pass" if passed else "fail",
                    "observed_exit": guest.returncode,
                    "detail": "" if passed else
                        f"expected exit={expected}; actual={guest.returncode}; "
                        f"stdout={guest.stdout[:160]!r}; stderr={guest.stderr[:160]!r}"}
        if backend != "llvm" or not compiled.stdout.startswith("; ModuleID"):
            return {"id": case["id"], "status": "fail",
                    "detail": "native result adapter unavailable or compiler did not emit LLVM IR"}
        if not clang:
            return {"id": case["id"], "status": "fail", "detail": "Clang is required for native-result tests"}
        with tempfile.TemporaryDirectory(prefix="ores-conformance-") as folder:
            exe = str(Path(folder) / "main")
            build = subprocess.run([clang, "-x", "ir", "-", "-o", exe], input=compiled.stdout,
                                   capture_output=True, text=True, check=False, timeout=timeout)
            if build.returncode:
                return {"id": case["id"], "status": "fail",
                        "detail": f"Clang failed: {build.stderr[:450]}"}
            guest = subprocess.run([exe], capture_output=True, text=True, check=False,
                                   timeout=timeout)
            expected = case["expected_exit"]
            passed = guest.returncode == expected and not guest.stdout and not guest.stderr
            return {"id": case["id"], "status": "pass" if passed else "fail",
                    "observed_exit": guest.returncode,
                    "detail": "" if passed else f"expected exit={expected}; stdout={guest.stdout[:160]!r}; stderr={guest.stderr[:160]!r}"}
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"id": case["id"], "status": "fail", "detail": f"toolchain/runtime failure: {exc}"}

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--validate", action="store_true")
    p.add_argument("--backend", choices=["llvm", "graal"])
    p.add_argument("--command", help="shell-free argv with one {source} placeholder")
    p.add_argument("--clang", default="")
    p.add_argument("--run-command", help="Graal OresMain guest process argv with one {source}")
    p.add_argument("--revision", help="40-character Git commit SHA for the compiled backend")
    p.add_argument("--timeout", type=int, default=30)
    p.add_argument("--report", type=Path)
    args = p.parse_args()
    suite, checksum = validate_suite()
    if args.validate:
        if args.backend or args.command or args.report:
            p.error("--validate cannot be combined with an execution option")
        print(f"Validated {len(suite['cases'])} semantic cases, SHA256={checksum}")
        return 0
    if not args.backend or not args.command or not args.revision:
        p.error("--backend, --command and --revision are required")
    if not re.fullmatch("[a-f0-9]{40}", args.revision):
        p.error("--revision must be a 40-character Git commit SHA")
    if not 1 <= args.timeout <= 300:
        p.error("--timeout must be 1..300 seconds")
    argv = validate_command(args.command)
    if args.run_command and args.backend != "graal":
        p.error("--run-command is only applicable to the graal backend")
    run_argv = validate_command(args.run_command) if args.run_command else None
    results = []
    for case in suite["cases"]:
        if case["backends"][args.backend] == "pending":
            result = {"id": case["id"], "status": "pending",
                      "detail": case["pending_reason"]}
        else:
            result = run_case(case, args.backend, argv, args.clang, args.timeout, run_argv)
        results.append(result)
        print(f"{result['status'].upper()} {case['id']} ({args.backend})"
              + (f": {result.get('detail','')}" if result["status"] == "fail" else ""))
    report = {"schema_version":"1.1.0", "backend":args.backend, "suite_sha256":checksum,
              "source_revision":args.revision, "results":results}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    passed = sum(x["status"] == "pass" for x in results)
    pending = sum(x["status"] == "pending" for x in results)
    failed = sum(x["status"] == "fail" for x in results)
    print(f"SUMMARY pass={passed} pending={pending} fail={failed}; pending is NOT pass")
    return 1 if failed else 0

if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, json.JSONDecodeError) as err:
        print(f"Conformance infrastructure error: {err}", file=sys.stderr)
        sys.exit(2)
