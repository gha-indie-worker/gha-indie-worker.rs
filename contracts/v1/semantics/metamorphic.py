#!/usr/bin/env python3
"""Deterministic arithmetic metamorphic tests of the *native executable*.

This oracle is intentionally small: it proves equal observable values for
source-level identities without relying on the LLVM IR optimizer's output.
It is NOT a proof of actor, borrow, integer-overflow or JVM parity.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random
import shlex
import subprocess
import sys
import tempfile

def generate(seed, index):
    rng = random.Random(seed + 1000003 * index)
    target = rng.randrange(0, 120)
    k = rng.randrange(1, 31)
    a = rng.randrange(target + 1)
    b = target - a
    expr = [
        f"(({target} + {k}) - {k})",
        f"(({target} - {k}) + {k})",
        f"((1 * {target}) + (0 * {k}))",
        f"(-(-{target}) + 0)",
        f"(({a} + {b}) * 1)",
        f"((({a} + {b}) + {k}) - {k})",
    ]
    first, second = expr[index % len(expr)], expr[(index + 3) % len(expr)]
    assert first != second and 0 <= target <= 119
    wrap = lambda e: f"pub fnc main(): int {{ return {e}; }}\n"
    return (wrap(first), wrap(second), target)

def catalog(seed, count):
    cases = [generate(seed, i) for i in range(count)]
    hasher = hashlib.sha256(b"oreslang-metamorphic-arithmetic-v1\0")
    for left, right, result in cases:
        for data in (left.encode(), right.encode(), str(result).encode()):
            hasher.update(len(data).to_bytes(8, "big"))
            hasher.update(data)
    return cases, hasher.hexdigest()

def execute(source, argv, clang, timeout, workspace):
    file = Path(workspace) / "program.ores"
    binary = Path(workspace) / "program"
    file.write_text(source, encoding="utf-8")
    command = [part.replace("{source}", str(file)) for part in argv]
    compiled = subprocess.run(command, text=True, capture_output=True, timeout=timeout, check=False)
    if compiled.returncode != 0 or not compiled.stdout.startswith("; ModuleID"):
        raise AssertionError(f"compiler failed or omitted IR: rc={compiled.returncode}; stderr={compiled.stderr[:400]!r}")
    built = subprocess.run([clang, "-x", "ir", "-", "-o", str(binary)],
                           input=compiled.stdout, capture_output=True, text=True,
                           timeout=timeout, check=False)
    if built.returncode:
        raise AssertionError(f"native compilation failed: {built.stderr[:400]}")
    guest = subprocess.run([str(binary)], capture_output=True, text=True,
                           timeout=timeout, check=False)
    if guest.stdout or guest.stderr or guest.returncode < 0:
        raise AssertionError(f"unexpected IO or termination: rc={guest.returncode} stdout={guest.stdout!r} stderr={guest.stderr!r}")
    return guest.returncode

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--command", help="LLVM compiler argv; exactly one {source}")
    parser.add_argument("--clang", default="clang")
    parser.add_argument("--count", type=int, default=32)
    parser.add_argument("--seed", type=int, default=20261008)
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if not 1 <= args.count <= 512 or not 1 <= args.timeout <= 300:
        parser.error("count must be 1..512 and timeout must be 1..300")
    cases, digest = catalog(args.seed, args.count)
    if args.self_test:
        if catalog(args.seed, args.count) != (cases, digest):
            raise AssertionError("nondeterministic source generator")
        print(f"GENERATOR_OK pairs={len(cases)} source_sha256={digest}")
        return 0
    if not args.command:
        parser.error("--command required unless --self-test")
    argv = shlex.split(args.command)
    if not argv or sum(x.count("{source}") for x in argv) != 1:
        parser.error("--command requires exactly one {source} placeholder")
    report = {"version": 1, "seed": args.seed, "pairs": args.count,
              "source_sha256": digest, "passed": 0, "failures": []}
    with tempfile.TemporaryDirectory(prefix="oreslang-metamorphic-") as tmp:
        for i, (left, right, expected) in enumerate(cases):
            try:
                a = execute(left, argv, args.clang, args.timeout, tmp)
                b = execute(right, argv, args.clang, args.timeout, tmp)
                if (a, b) != (expected, expected):
                    raise AssertionError(f"expected {expected}, got {a} and {b}")
                report["passed"] += 1
            except (OSError, subprocess.TimeoutExpired, AssertionError) as e:
                report["failures"].append({"index": i, "error": str(e)[:500]})
                print(f"FAIL pair={i}: {e}", file=sys.stderr)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(f"METAMORPHIC passed={report['passed']}/{args.count} source_sha256={digest}")
    return 1 if report["failures"] else 0

if __name__ == "__main__":
    sys.exit(main())
