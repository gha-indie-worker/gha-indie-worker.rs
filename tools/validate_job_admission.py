#!/usr/bin/env python3
"""Fail-closed validation for the reviewed gha-indie-worker job envelope.

The envelope is produced after workflow admission and dispatch-v2 profile binding. It
carries the immutable request identity, canonical runner target, bounded resources,
and exclusive lease metadata that the execution boundary is allowed to consume.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any, Mapping

SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^sha256:[0-9a-f]{64}$")
ACTION = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}$")
IMAGE = re.compile(r"^docker://[^\s@]+@sha256:[0-9a-f]{64}$")
IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
PROFILE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
CAPABILITY = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
RFC3339_UTC = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")

ALLOWED_EVENTS = {"push", "pull_request", "workflow_dispatch"}
PLATFORMS = {"linux", "macos", "windows"}
ARCHITECTURES = {"x64", "arm64"}
TRUST_TIERS = {
    "public-untrusted",
    "public-trusted",
    "private-build",
    "release-signing",
}
RESOURCE_LIMITS = {
    "timeout_seconds": 3600,
    "log_bytes": 10_485_760,
    "artifact_bytes": 1_073_741_824,
    "processes": 512,
}


def _exact_keys(value: Mapping[str, Any], expected: set[str], context: str, errors: list[str]) -> None:
    actual = set(value)
    for key in sorted(expected - actual):
        errors.append(f"{context}_{key}_missing")
    for key in sorted(actual - expected):
        errors.append(f"{context}_{key}_unknown")


def _string(value: Any, pattern: re.Pattern[str] | None = None) -> bool:
    return isinstance(value, str) and bool(value) and (pattern is None or bool(pattern.fullmatch(value)))


def validate(job: Any) -> list[str]:
    errors: list[str] = []

    def need(ok: bool, code: str) -> None:
        if not ok:
            errors.append(code)

    if not isinstance(job, Mapping):
        return ["job_invalid"]

    expected_job_keys = {
        "schema",
        "request_id",
        "request_digest",
        "job_id",
        "delivery_id",
        "idempotency_key",
        "repository",
        "ref",
        "sha",
        "profile",
        "profile_digest",
        "trust_tier",
        "runner",
        "event",
        "permissions",
        "lease",
        "resources",
        "isolation",
        "steps",
    }
    _exact_keys(job, expected_job_keys, "job", errors)

    need(job.get("schema") == "gha-indie-worker.job/v1", "schema")
    for key in ("request_id", "job_id", "delivery_id", "idempotency_key"):
        need(_string(job.get(key), IDENTIFIER), f"{key}_invalid")
    need(_string(job.get("request_digest"), SHA256), "request_digest_invalid")
    need(_string(job.get("repository"), REPOSITORY), "repository_invalid")
    need(isinstance(job.get("ref"), str) and job["ref"].startswith("refs/"), "ref_invalid")
    need(_string(job.get("sha"), SHA1), "sha_invalid")
    need(_string(job.get("profile"), PROFILE), "profile_invalid")
    need(_string(job.get("profile_digest"), SHA256), "profile_digest_invalid")
    need(job.get("trust_tier") in TRUST_TIERS, "trust_tier_unsupported")
    need(job.get("event") in ALLOWED_EVENTS, "event_unsupported")

    runner = job.get("runner")
    need(isinstance(runner, Mapping), "runner_invalid")
    if isinstance(runner, Mapping):
        _exact_keys(runner, {"platform", "architecture", "capabilities"}, "runner", errors)
        platform = runner.get("platform")
        architecture = runner.get("architecture")
        capabilities = runner.get("capabilities")
        need(platform in PLATFORMS, "runner_platform_unsupported")
        need(architecture in ARCHITECTURES, "runner_architecture_unsupported")
        need(isinstance(capabilities, list) and len(capabilities) <= 64, "runner_capabilities_invalid")
        if isinstance(capabilities, list):
            need(all(_string(value, CAPABILITY) for value in capabilities), "runner_capability_invalid")
            need(capabilities == sorted(capabilities), "runner_capabilities_unsorted")
            need(len(capabilities) == len(set(capabilities)), "runner_capabilities_duplicate")
            if platform in {"macos", "windows"}:
                need("native" in capabilities, "runner_native_capability_missing")

    permissions = job.get("permissions")
    need(isinstance(permissions, Mapping), "permissions_invalid")
    if isinstance(permissions, Mapping):
        for name, value in permissions.items():
            need(_string(name, PROFILE), f"permission_{name}_name_invalid")
            need(value in ("none", "read"), f"permission_{name}_unsafe")

    lease = job.get("lease")
    need(isinstance(lease, Mapping), "lease_invalid")
    if isinstance(lease, Mapping):
        _exact_keys(lease, {"id", "expires_at", "nonce"}, "lease", errors)
        need(_string(lease.get("id"), IDENTIFIER), "lease_id_invalid")
        need(_string(lease.get("nonce"), IDENTIFIER), "lease_nonce_invalid")
        need(_string(lease.get("expires_at"), RFC3339_UTC), "lease_expires_at_invalid")

    resources = job.get("resources")
    need(isinstance(resources, Mapping), "resources_invalid")
    if isinstance(resources, Mapping):
        _exact_keys(resources, set(RESOURCE_LIMITS), "resources", errors)
        for key, maximum in RESOURCE_LIMITS.items():
            value = resources.get(key)
            need(isinstance(value, int) and not isinstance(value, bool) and 0 < value <= maximum, f"{key}_invalid")

    isolation = job.get("isolation")
    need(isinstance(isolation, Mapping), "isolation_invalid")
    if isinstance(isolation, Mapping):
        _exact_keys(isolation, {"host_socket", "privileged", "ambient_credentials", "devices"}, "isolation", errors)
        need(isolation.get("host_socket") is False, "host_socket_denied")
        need(isolation.get("privileged") is False, "privileged_denied")
        need(isolation.get("ambient_credentials") is False, "ambient_credentials_denied")
        need(isolation.get("devices") == [], "devices_denied")

    steps = job.get("steps")
    need(isinstance(steps, list) and 0 < len(steps) <= 100, "steps_invalid")
    if isinstance(steps, list):
        for index, step in enumerate(steps):
            need(isinstance(step, Mapping), f"step_{index}_invalid")
            if not isinstance(step, Mapping):
                continue
            unknown = set(step) - {"run", "uses"}
            need(not unknown, f"step_{index}_field_unknown")
            kinds = [key for key in ("run", "uses") if key in step]
            need(len(kinds) == 1, f"step_{index}_kind")
            if "run" in step:
                need(isinstance(step["run"], str) and bool(step["run"]), f"step_{index}_run_invalid")
            if "uses" in step:
                uses = str(step["uses"])
                need(bool(ACTION.fullmatch(uses)) or bool(IMAGE.fullmatch(uses)), f"step_{index}_uses_unpinned")

    return sorted(set(errors))


def fixture() -> dict[str, Any]:
    return {
        "schema": "gha-indie-worker.job/v1",
        "request_id": "req-1",
        "request_digest": "sha256:" + "1" * 64,
        "job_id": "build-windows",
        "delivery_id": "delivery-1",
        "idempotency_key": "idem-1",
        "repository": "o/r",
        "ref": "refs/heads/main",
        "sha": "a" * 40,
        "profile": "windows-msvc",
        "profile_digest": "sha256:" + "2" * 64,
        "trust_tier": "public-untrusted",
        "runner": {
            "platform": "windows",
            "architecture": "x64",
            "capabilities": ["msvc", "native", "windows-sdk"],
        },
        "event": "pull_request",
        "permissions": {"contents": "read"},
        "lease": {
            "id": "lease-1",
            "expires_at": "2026-08-08T05:00:00Z",
            "nonce": "nonce-1",
        },
        "resources": {
            "timeout_seconds": 900,
            "log_bytes": 1_048_576,
            "artifact_bytes": 1_048_576,
            "processes": 64,
        },
        "isolation": {
            "host_socket": False,
            "privileged": False,
            "ambient_credentials": False,
            "devices": [],
        },
        "steps": [
            {"uses": "actions/checkout@" + "b" * 40},
            {"run": "cargo test --locked"},
        ],
    }


def self_test() -> None:
    assert validate(fixture()) == []

    unsafe = fixture()
    unsafe["event"] = "pull_request_target"
    unsafe["permissions"] = {"contents": "write"}
    unsafe["isolation"]["privileged"] = True
    unsafe["steps"][0]["uses"] = "actions/checkout@v5"
    unsafe_errors = set(validate(unsafe))
    assert {
        "event_unsupported",
        "permission_contents_unsafe",
        "privileged_denied",
        "step_0_uses_unpinned",
    } <= unsafe_errors

    target_mismatch = fixture()
    target_mismatch["runner"] = {
        "platform": "windows",
        "architecture": "x86_64",
        "capabilities": ["msvc", "windows-sdk"],
    }
    target_errors = set(validate(target_mismatch))
    assert {
        "runner_architecture_unsupported",
        "runner_native_capability_missing",
    } <= target_errors

    malformed_binding = fixture()
    malformed_binding["request_digest"] = "not-a-digest"
    malformed_binding["profile_digest"] = "sha256:ABC"
    binding_errors = set(validate(malformed_binding))
    assert {"request_digest_invalid", "profile_digest_invalid"} <= binding_errors

    duplicate_capability = fixture()
    duplicate_capability["runner"]["capabilities"] = ["native", "native", "windows-sdk"]
    duplicate_errors = set(validate(duplicate_capability))
    assert "runner_capabilities_duplicate" in duplicate_errors

    print("job admission self-test: ok")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("file", nargs="?", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        self_test()
        return 0
    if not args.file:
        parser.error("file is required unless --self-test is used")
    errors = validate(json.loads(args.file.read_text()))
    json.dump({"ok": not errors, "errors": errors}, sys.stdout, indent=2, sort_keys=True)
    print()
    return 0 if not errors else 2


if __name__ == "__main__":
    raise SystemExit(main())
