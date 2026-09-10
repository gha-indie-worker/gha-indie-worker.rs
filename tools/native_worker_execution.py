#!/usr/bin/env python3
"""Public native worker handoff and exact-checkout API."""

from __future__ import annotations

import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

try:
    from .native_worker_common import *  # noqa: F401,F403
    from .native_worker_handoff import *  # noqa: F401,F403
    from .native_worker_checkout import *  # noqa: F401,F403
except ImportError:
    from native_worker_common import *  # type: ignore # noqa: F401,F403
    from native_worker_handoff import *  # type: ignore # noqa: F401,F403
    from native_worker_checkout import *  # type: ignore # noqa: F401,F403


def _load_json(path: Path) -> Mapping[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ExecutionError("input_invalid", f"cannot read JSON input {path}") from error
    return require_mapping(value, str(path))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dispatch", type=Path, required=True)
    parser.add_argument("--lease", type=Path, required=True)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    arguments = parser.parse_args()
    now = datetime.now(timezone.utc)
    try:
        handoff = build_execution_handoff(_load_json(arguments.dispatch), _load_json(arguments.lease), now=now)
        evidence = execute_exact_checkout(handoff, workspace=arguments.workspace, now=now)
    except ExecutionError as error:
        print(json.dumps({"code": error.code, "message": error.message}, sort_keys=True), file=os.sys.stderr)
        return 2
    arguments.evidence.parent.mkdir(parents=True, exist_ok=True)
    arguments.evidence.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(evidence, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
