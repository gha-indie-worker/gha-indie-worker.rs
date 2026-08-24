from __future__ import annotations

import importlib.util
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "tools" / "native_worker_execution.py"
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("native_worker_execution", MODULE_PATH)
assert SPEC and SPEC.loader
execution = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(execution)

NOW = datetime(2026, 8, 8, 18, 0, tzinfo=timezone.utc)
COMMIT = "a" * 40
TREE = "b" * 40
REPOSITORY = "https://github.com/gha-indie-worker/gha-indie-worker.rs.git"
PROFILE_DIGEST = "sha256:" + "1" * 64
REQUEST_DIGEST = "sha256:" + "2" * 64


def fixtures(*, platform: str = "linux", architecture: str = "x64", context_dir: str = "."):
    capabilities = ["git"]
    if platform in {"macos", "windows"}:
        capabilities.append("native")
    capabilities.sort()
    host_id = "linux-lab-01" if platform == "linux" else f"{platform}-lab-01"
    snapshot = {
        "hostId": host_id,
        "platform": platform,
        "architecture": architecture,
        "capabilities": capabilities,
        "profiles": [{"name": "native-live", "digest": PROFILE_DIGEST}],
    }
    dispatch = {
        "schemaVersion": execution.DISPATCH_SCHEMA,
        "requestId": "gha:request:123",
        "requestDigest": REQUEST_DIGEST,
        "planDigest": "sha256:" + "3" * 64,
        "profileCatalogDigest": "sha256:" + "4" * 64,
        "repositoryUrl": REPOSITORY,
        "commitSha": COMMIT,
        "jobInstanceId": "build",
        "baseJobId": "build",
        "jobOrderIndex": 0,
        "profile": "native-live",
        "profileDigest": PROFILE_DIGEST,
        "runner": {"platform": platform, "architecture": architecture, "capabilities": capabilities},
        "contextDir": context_dir,
        "needsInstances": [],
        "matrix": {},
        "failFast": True,
        "maxParallel": 1,
    }
    lease = {
        "schemaVersion": execution.LEASE_SCHEMA,
        "leaseId": "lease_123",
        "requestId": dispatch["requestId"],
        "requestDigest": dispatch["requestDigest"],
        "hostId": host_id,
        "keyId": f"device:{host_id}:0123456789abcdef",
        "nonce": "nonce_123",
        "issuedAt": execution.format_time(NOW - timedelta(seconds=10)),
        "expiresAt": execution.format_time(NOW + timedelta(minutes=5)),
        "attempt": 1,
        "repositoryUrl": dispatch["repositoryUrl"],
        "commitSha": dispatch["commitSha"],
        "profile": dispatch["profile"],
        "profileDigest": dispatch["profileDigest"],
        "capabilityDigest": execution.sha256_digest(snapshot),
        "hostCapabilitySnapshot": snapshot,
        "cancelRequested": False,
        "terminalStatus": None,
    }
    return dispatch, lease


class FakeGit:
    def __init__(
        self,
        *,
        commit: str = COMMIT,
        remotes: str = "origin\n",
        gitlinks: str = "100644 blob " + "c" * 40 + "\tREADME.md\x00",
        sensitive_config: str = "",
        create_context: str | None = None,
    ):
        self.commit, self.remotes, self.gitlinks = commit, remotes, gitlinks
        self.sensitive_config, self.create_context = sensitive_config, create_context
        self.commands: list[list[str]] = []
        self.environments: list[dict[str, str]] = []

    def __call__(self, command, **kwargs):
        command = list(command)
        self.commands.append(command)
        self.environments.append(dict(kwargs["env"]))
        stdout, returncode = "", 0
        if "init" in command:
            workspace = Path(command[-1])
            (workspace / ".git").mkdir(parents=True, exist_ok=True)
            if self.create_context:
                (workspace / self.create_context).mkdir(parents=True, exist_ok=True)
        elif command[-3:] == ["rev-parse", "--verify", "HEAD"]:
            stdout = self.commit + "\n"
        elif command[-3:] == ["rev-parse", "--verify", "HEAD^{tree}"]:
            stdout = TREE + "\n"
        elif command[-3:] == ["symbolic-ref", "-q", "HEAD"]:
            returncode = 1
        elif command[-3:] == ["remote", "get-url", "origin"]:
            stdout = REPOSITORY + "\n"
        elif command[-1:] == ["remote"]:
            stdout = self.remotes
        elif "--get-regexp" in command:
            stdout, returncode = (self.sensitive_config, 0) if self.sensitive_config else ("", 1)
        elif command[-4:] == ["ls-tree", "-r", "-z", "HEAD"]:
            stdout = self.gitlinks
        return subprocess.CompletedProcess(command, returncode, stdout, "")
