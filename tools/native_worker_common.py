#!/usr/bin/env python3
"""Shared validation and digest helpers for the native worker boundary."""

from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import PurePosixPath
from typing import Any, Mapping
from urllib.parse import unquote, urlsplit

DISPATCH_SCHEMA = "gha-indie-worker.dispatch.v2"
LEASE_SCHEMA = "gha-indie-worker.lease.v1"
HANDOFF_SCHEMA = "gha-indie-worker.native-execution-handoff.v1"
EVIDENCE_SCHEMA = "gha-indie-worker.exact-checkout-evidence.v1"
SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
SHA256_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}$")
REPOSITORY_COMPONENT_RE = re.compile(r"^[A-Za-z0-9_.-]{1,100}$")
FORBIDDEN_FIELD_RE = re.compile(
    r"(?:secret|password|private.?key|access.?token|refresh.?token|credential)", re.I
)
PLATFORMS = {"linux", "macos", "windows"}
ARCHITECTURES = {"x64", "arm64"}


class ExecutionError(ValueError):
    """Stable, fail-closed execution-contract error."""

    def __init__(self, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def sha256_digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def require_utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() != timedelta(0):
        raise ExecutionError("timestamp_invalid", "timestamps must be timezone-aware UTC values")
    return value.astimezone(timezone.utc)


def format_time(value: datetime) -> str:
    return require_utc(value).isoformat(timespec="seconds").replace("+00:00", "Z")


def parse_time(value: Any, field_name: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ExecutionError("timestamp_invalid", f"{field_name} must be an RFC3339 UTC timestamp")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise ExecutionError("timestamp_invalid", f"{field_name} is invalid") from error
    return require_utc(parsed)


def require_mapping(value: Any, context: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ExecutionError("object_invalid", f"{context} must be an object")
    return value


def require_exact_keys(value: Mapping[str, Any], expected: set[str], context: str) -> None:
    actual = set(value)
    missing, unknown = sorted(expected - actual), sorted(actual - expected)
    if missing:
        raise ExecutionError("field_missing", f"{context} is missing {missing}")
    if unknown:
        raise ExecutionError("field_unknown", f"{context} contains unknown fields {unknown}")


def reject_secret_fields(value: Any, path: str = "$") -> None:
    if isinstance(value, Mapping):
        for key, nested in value.items():
            if FORBIDDEN_FIELD_RE.search(str(key)):
                raise ExecutionError("secret_field_forbidden", f"{path}.{key} is forbidden")
            reject_secret_fields(nested, f"{path}.{key}")
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            reject_secret_fields(nested, f"{path}[{index}]")


def bounded_identifier(value: Any, field_name: str) -> str:
    if not isinstance(value, str) or not IDENTIFIER_RE.fullmatch(value):
        raise ExecutionError("identifier_invalid", f"{field_name} is invalid")
    return value


def validate_repository_url(value: Any) -> str:
    """Accept only a credential-free HTTPS GitHub repository URL."""

    if not isinstance(value, str) or len(value) > 300 or any(ord(ch) < 32 for ch in value):
        raise ExecutionError("repository_invalid", "repositoryUrl must be a bounded string")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise ExecutionError("repository_authority_invalid", "repository URL authority is invalid") from error
    if parsed.scheme != "https" or parsed.hostname != "github.com" or port is not None:
        raise ExecutionError("repository_transport_forbidden", "only HTTPS github.com repositories are allowed")
    if parsed.username is not None or parsed.password is not None:
        raise ExecutionError("repository_credentials_forbidden", "repository URLs must not contain credentials")
    if parsed.query or parsed.fragment:
        raise ExecutionError("repository_suffix_forbidden", "repository URLs must not contain query or fragment data")
    if "%" in parsed.path or unquote(parsed.path) != parsed.path:
        raise ExecutionError("repository_encoding_forbidden", "repository path must not use percent encoding")
    if value.endswith("/") or "//" in parsed.path:
        raise ExecutionError("repository_path_invalid", "repository path must be canonical")
    parts = parsed.path.lstrip("/").split("/")
    if len(parts) != 2 or not all(parts):
        raise ExecutionError("repository_path_invalid", "repository URL must identify exactly owner/repository")
    owner, repository = parts
    repository = repository[:-4] if repository.endswith(".git") else repository
    if (
        owner in {".", ".."}
        or repository in {".", ".."}
        or not REPOSITORY_COMPONENT_RE.fullmatch(owner)
        or not REPOSITORY_COMPONENT_RE.fullmatch(repository)
    ):
        raise ExecutionError("repository_path_invalid", "repository owner or name is invalid")
    return value


def validate_context_dir(value: Any) -> str:
    if not isinstance(value, str) or not value or len(value) > 256:
        raise ExecutionError("context_dir_invalid", "contextDir must be a bounded repository-relative path")
    if "\\" in value or "\x00" in value or value.startswith("/"):
        raise ExecutionError("context_dir_invalid", "contextDir must use canonical POSIX repository-relative syntax")
    if value == ".":
        return value
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts) or path.as_posix() != value:
        raise ExecutionError("context_dir_invalid", "contextDir must already be canonical and non-traversing")
    return value


def validate_string_list(value: Any, field_name: str, *, maximum: int = 64) -> list[str]:
    if not isinstance(value, list) or len(value) > maximum:
        raise ExecutionError("list_invalid", f"{field_name} must be a list with at most {maximum} entries")
    if any(not isinstance(item, str) or not NAME_RE.fullmatch(item) for item in value):
        raise ExecutionError("identifier_invalid", f"{field_name} contains an invalid value")
    if value != sorted(value) or len(value) != len(set(value)):
        raise ExecutionError("order_invalid", f"{field_name} must be sorted and unique")
    return list(value)


def validate_runner(value: Any) -> dict[str, Any]:
    runner = require_mapping(value, "runner")
    require_exact_keys(runner, {"platform", "architecture", "capabilities"}, "runner")
    platform, architecture = runner["platform"], runner["architecture"]
    if platform not in PLATFORMS:
        raise ExecutionError("platform_unsupported", "runner platform is unsupported")
    if architecture not in ARCHITECTURES:
        raise ExecutionError("architecture_unsupported", "runner architecture is unsupported")
    capabilities = validate_string_list(runner["capabilities"], "runner.capabilities")
    if platform in {"macos", "windows"} and "native" not in capabilities:
        raise ExecutionError("native_capability_required", "native Windows/macOS execution requires native")
    return {"platform": platform, "architecture": architecture, "capabilities": capabilities}
