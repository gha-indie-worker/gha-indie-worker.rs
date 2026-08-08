#!/usr/bin/env python3
"""Public native fleet protocol API and canonical lab fixtures."""

from __future__ import annotations

from typing import Any, Iterable

from .native_fleet_validation import *  # noqa: F401,F403
from .native_fleet_runtime import NativeFleet

def capability_payload(
    *,
    host_id: str,
    platform: str,
    architecture: str,
    trust_tier: str,
    profiles: Iterable[tuple[str, str]],
    capabilities: Iterable[str],
    concurrency: int = 1,
    agent_version: str = "0.1.0",
) -> dict[str, Any]:
    """Construct a canonical test/lab capability payload."""

    shell_map = {
        "linux": ["bash"],
        "macos": ["bash", "zsh"],
        "windows": ["cmd", "powershell"],
    }
    return {
        "schemaVersion": HOST_CAPABILITY_SCHEMA,
        "hostId": host_id,
        "platform": platform,
        "architecture": architecture,
        "hardwareClass": {
            "linux": "linux-vm",
            "macos": "apple-silicon",
            "windows": "windows-hyper-v",
        }[platform],
        "osBuild": "lab-1",
        "agentVersion": agent_version,
        "protocolVersion": PROTOCOL_VERSION,
        "trustTier": trust_tier,
        "patchRing": "stable",
        "sandbox": "disposable-or-reimageable",
        "networkProfile": "egress-restricted",
        "shells": sorted(shell_map[platform]),
        "capabilities": sorted(set(capabilities)),
        "profiles": [
            {"name": name, "digest": digest}
            for name, digest in sorted(profiles, key=lambda item: item[0])
        ],
        "concurrency": concurrency,
    }


def dispatch_request(
    *,
    request_id: str,
    platform: str,
    architecture: str,
    profile: str,
    profile_digest: str,
    capabilities: Iterable[str],
) -> dict[str, Any]:
    """Construct a canonical dispatch-v2 fixture compatible with PR #14."""

    return {
        "schemaVersion": DISPATCH_SCHEMA,
        "requestId": request_id,
        "requestDigest": sha256_digest({"requestId": request_id}),
        "planDigest": sha256_digest({"plan": request_id}),
        "profileCatalogDigest": sha256_digest({"catalog": "v2"}),
        "repositoryUrl": "https://github.com/gha-indie-worker/example.git",
        "commitSha": "a" * 40,
        "jobInstanceId": "build",
        "baseJobId": "build",
        "jobOrderIndex": 0,
        "profile": profile,
        "profileDigest": profile_digest,
        "runner": {
            "platform": platform,
            "architecture": architecture,
            "capabilities": sorted(set(capabilities)),
        },
        "contextDir": ".",
        "needsInstances": [],
        "matrix": {},
        "failFast": True,
        "maxParallel": None,
    }
