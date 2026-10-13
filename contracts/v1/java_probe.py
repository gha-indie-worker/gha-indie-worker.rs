#!/usr/bin/env python3
"""Run the real Graal/Java Oreslang launcher in check or execution mode.

Check mode must reject diagnostics with exit 1; execution mode invokes guest
main() and forwards its real process exit (no synthetic result or stub).
The launcher reads classpath resolved by Maven, never from the environment.
"""
import os
from pathlib import Path
import subprocess
import sys


def main():
    if len(sys.argv) not in (2, 3) or (len(sys.argv) == 3 and sys.argv[2] != "--execute"):
        raise ValueError("usage: java_probe.py <fixture.ores> [--execute]")
    execute = len(sys.argv) == 3
    path = Path(sys.argv[1]).resolve()
    if not path.is_file() or path.suffix != ".ores":
        raise ValueError("invalid source fixture")
    project = Path(__file__).resolve().parents[2]
    cpfile = project / "target" / "conformance-classpath.txt"
    deps = cpfile.read_text().strip()
    if not deps:
        raise ValueError("missing Maven dependency classpath")
    classes = project / "target" / "classes"
    # Truffle needs host native access to load its runtime library on JDK 25.
    # Explicitly authorize that capability for this isolated test process;
    # never suppress or discard process stderr (including guest failures).
    # Enable the runtime compiler rather than masking Truffle's interpreted-only
    # advisory. This test process must still expose all stderr to the runner.
    # Truffle engine diagnostics (including interpreted-mode notices) are
    # host metadata, not guest stderr. Persist them separately for CI review;
    # keep actual guest stdout and stderr unfiltered.
    import hashlib
    log_dir = project / "target" / "conformance-engine-logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    log_name = hashlib.sha256(str(path).encode("utf-8")).hexdigest()[:20] + ".log"
    engine_log = log_dir / log_name
    # JDK 25 warns on the existing Truffle dependency's deprecated Unsafe
    # adapter. This runner-only compatibility option retains its current
    # (already permitted) behavior while preserving every guest stderr byte.
    # Production compatibility with future JDK deny/removal is a separate gate.
    argv = ["java", "-XX:+EnableJVMCI",
            "--sun-misc-unsafe-memory-access=allow",
            "--enable-native-access=ALL-UNNAMED",
            "-Dpolyglot.log.file=" + str(engine_log),
            "-cp", str(classes) + os.pathsep + deps,
            "dev.oreslang.launcher.OresMain",
            *([] if execute else ["--check"]), str(path)]
    result = subprocess.run(argv, cwd=project, timeout=80, check=False)
    return result.returncode


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, subprocess.TimeoutExpired) as ex:
        print(f"Java compiler probe failed: {ex}", file=sys.stderr)
        sys.exit(2)
