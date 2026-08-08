#!/usr/bin/env python3
"""Credential-free live exact-SHA checkout fixture for hosted OS references."""

from __future__ import annotations

import argparse
import importlib.util
import json
import platform as host_platform
import shutil
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "tools" / "native_worker_execution.py"
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("native_worker_execution", MODULE_PATH)
assert SPEC and SPEC.loader
execution = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(execution)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-url", required=True)
    parser.add_argument("--commit-sha", required=True)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--platform", choices=sorted(execution.PLATFORMS), required=True)
    parser.add_argument("--architecture", choices=sorted(execution.ARCHITECTURES), required=True)
    args = parser.parse_args()

    now = datetime.now(timezone.utc)
    capabilities = ["git"]
    if args.platform in {"macos", "windows"}:
        capabilities.append("native")
    capabilities.sort()
    profile_digest = "sha256:" + "1" * 64
    snapshot = {
        "hostId": f"{args.platform}-hosted-reference-01",
        "platform": args.platform,
        "architecture": args.architecture,
        "capabilities": capabilities,
        "profiles": [{"name": "native-live", "digest": profile_digest}],
    }
    request_id = "gha:live:" + args.commit_sha
    request_digest = execution.sha256_digest(
        {"repositoryUrl": args.repository_url, "commitSha": args.commit_sha, "platform": args.platform}
    )
    dispatch = {
        "schemaVersion": execution.DISPATCH_SCHEMA,
        "requestId": request_id,
        "requestDigest": request_digest,
        "planDigest": execution.sha256_digest({"plan": request_id}),
        "profileCatalogDigest": execution.sha256_digest({"catalog": "native-live-v1"}),
        "repositoryUrl": args.repository_url,
        "commitSha": args.commit_sha,
        "jobInstanceId": "live-checkout",
        "baseJobId": "live-checkout",
        "jobOrderIndex": 0,
        "profile": "native-live",
        "profileDigest": profile_digest,
        "runner": {
            "platform": args.platform,
            "architecture": args.architecture,
            "capabilities": capabilities,
        },
        "contextDir": ".",
        "needsInstances": [],
        "matrix": {},
        "failFast": True,
        "maxParallel": 1,
    }
    lease = {
        "schemaVersion": execution.LEASE_SCHEMA,
        "leaseId": "lease_live_reference",
        "requestId": request_id,
        "requestDigest": request_digest,
        "hostId": snapshot["hostId"],
        "keyId": f"device:{snapshot['hostId']}:0123456789abcdef",
        "nonce": "nonce_live_reference",
        "issuedAt": execution.format_time(now - timedelta(seconds=1)),
        "expiresAt": execution.format_time(now + timedelta(minutes=10)),
        "attempt": 1,
        "repositoryUrl": args.repository_url,
        "commitSha": args.commit_sha,
        "profile": "native-live",
        "profileDigest": profile_digest,
        "capabilityDigest": execution.sha256_digest(snapshot),
        "hostCapabilitySnapshot": snapshot,
        "cancelRequested": False,
        "terminalStatus": None,
    }

    if args.workspace.exists():
        shutil.rmtree(args.workspace)
    handoff = execution.build_execution_handoff(dispatch, lease, now=now)
    evidence = execution.execute_exact_checkout(handoff, workspace=args.workspace, now=now)
    evidence["referenceHost"] = host_platform.platform()
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(evidence, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
