#!/usr/bin/env python3
"""Immutable dispatch-to-lease binding for native worker execution."""

from __future__ import annotations

import copy
from datetime import datetime, timedelta
from typing import Any, Mapping

try:
    from .native_worker_common import *  # noqa: F401,F403
except ImportError:  # direct script/import from tools directory
    from native_worker_common import *  # type: ignore # noqa: F401,F403

DISPATCH_KEYS = {
    "schemaVersion", "requestId", "requestDigest", "planDigest",
    "profileCatalogDigest", "repositoryUrl", "commitSha", "jobInstanceId",
    "baseJobId", "jobOrderIndex", "profile", "profileDigest", "runner",
    "contextDir", "needsInstances", "matrix", "failFast", "maxParallel",
}
LEASE_KEYS = {
    "schemaVersion", "leaseId", "requestId", "requestDigest", "hostId",
    "keyId", "nonce", "issuedAt", "expiresAt", "attempt", "repositoryUrl",
    "commitSha", "profile", "profileDigest", "capabilityDigest",
    "hostCapabilitySnapshot", "cancelRequested", "terminalStatus",
}
HANDOFF_KEYS = {
    "schemaVersion", "handoffDigest", "requestId", "requestDigest", "leaseId",
    "leaseNonce", "hostId", "keyId", "repositoryUrl", "commitSha", "profile",
    "profileDigest", "capabilityDigest", "runner", "contextDir", "issuedAt",
    "expiresAt", "checkoutPolicy",
}
CHECKOUT_POLICY = {
    "exactShaOnly": True,
    "detachedHead": True,
    "allowFallbackRef": False,
    "allowAdditionalRemotes": False,
    "allowSubmodules": False,
    "allowGitLfsSmudge": False,
    "allowPersistedAuth": False,
    "fetchTags": False,
}


def validate_dispatch(value: Mapping[str, Any]) -> dict[str, Any]:
    dispatch = require_mapping(value, "dispatch")
    require_exact_keys(dispatch, DISPATCH_KEYS, "dispatch")
    reject_secret_fields(dispatch)
    if dispatch["schemaVersion"] != DISPATCH_SCHEMA:
        raise ExecutionError("schema_unsupported", "unsupported dispatch schema")
    for field in ("requestDigest", "planDigest", "profileCatalogDigest", "profileDigest"):
        if not isinstance(dispatch[field], str) or not SHA256_RE.fullmatch(dispatch[field]):
            raise ExecutionError("digest_invalid", f"{field} must be sha256:<64 lowercase hex>")
    bounded_identifier(dispatch["requestId"], "requestId")
    validate_repository_url(dispatch["repositoryUrl"])
    if not isinstance(dispatch["commitSha"], str) or not SHA1_RE.fullmatch(dispatch["commitSha"]):
        raise ExecutionError("commit_sha_invalid", "commitSha must be exactly 40 lowercase hexadecimal characters")
    if not isinstance(dispatch["profile"], str) or not NAME_RE.fullmatch(dispatch["profile"]):
        raise ExecutionError("profile_invalid", "profile is invalid")
    runner = validate_runner(dispatch["runner"])
    context_dir = validate_context_dir(dispatch["contextDir"])
    index = dispatch["jobOrderIndex"]
    if not isinstance(index, int) or isinstance(index, bool) or index < 0:
        raise ExecutionError("job_order_invalid", "jobOrderIndex must be a non-negative integer")
    if not isinstance(dispatch["needsInstances"], list) or len(dispatch["needsInstances"]) > 128:
        raise ExecutionError("needs_invalid", "needsInstances must be a bounded list")
    if not isinstance(dispatch["matrix"], Mapping):
        raise ExecutionError("matrix_invalid", "matrix must be an object")
    if not isinstance(dispatch["failFast"], bool):
        raise ExecutionError("fail_fast_invalid", "failFast must be boolean")
    maximum = dispatch["maxParallel"]
    if maximum is not None and (
        not isinstance(maximum, int) or isinstance(maximum, bool) or not 1 <= maximum <= 128
    ):
        raise ExecutionError("max_parallel_invalid", "maxParallel must be null or 1..128")
    normalized = copy.deepcopy(dict(dispatch))
    normalized["runner"], normalized["contextDir"] = runner, context_dir
    return normalized


def validate_lease(value: Mapping[str, Any] | Any) -> dict[str, Any]:
    if hasattr(value, "as_dict"):
        value = value.as_dict()
    lease = require_mapping(value, "lease")
    require_exact_keys(lease, LEASE_KEYS, "lease")
    reject_secret_fields(lease)
    if lease["schemaVersion"] != LEASE_SCHEMA:
        raise ExecutionError("schema_unsupported", "unsupported lease schema")
    for field in ("requestDigest", "profileDigest", "capabilityDigest"):
        if not isinstance(lease[field], str) or not SHA256_RE.fullmatch(lease[field]):
            raise ExecutionError("digest_invalid", f"{field} must be sha256:<64 lowercase hex>")
    for field in ("leaseId", "requestId", "hostId", "keyId", "nonce"):
        bounded_identifier(lease[field], field)
    validate_repository_url(lease["repositoryUrl"])
    if not isinstance(lease["commitSha"], str) or not SHA1_RE.fullmatch(lease["commitSha"]):
        raise ExecutionError("commit_sha_invalid", "lease commitSha is invalid")
    if not isinstance(lease["profile"], str) or not NAME_RE.fullmatch(lease["profile"]):
        raise ExecutionError("profile_invalid", "lease profile is invalid")
    attempt = lease["attempt"]
    if not isinstance(attempt, int) or isinstance(attempt, bool) or not 1 <= attempt <= 1000:
        raise ExecutionError("attempt_invalid", "lease attempt must be 1..1000")
    parse_time(lease["issuedAt"], "issuedAt")
    parse_time(lease["expiresAt"], "expiresAt")
    if not isinstance(lease["cancelRequested"], bool):
        raise ExecutionError("cancel_invalid", "cancelRequested must be boolean")
    if lease["terminalStatus"] is not None:
        raise ExecutionError("lease_terminal", "terminal leases cannot create execution handoffs")
    snapshot = require_mapping(lease["hostCapabilitySnapshot"], "hostCapabilitySnapshot")
    reject_secret_fields(snapshot, "$.hostCapabilitySnapshot")
    return copy.deepcopy(dict(lease))


def _validate_snapshot(snapshot: Mapping[str, Any], lease: Mapping[str, Any], dispatch: Mapping[str, Any]) -> None:
    if snapshot.get("hostId") != lease["hostId"]:
        raise ExecutionError("host_snapshot_mismatch", "snapshot belongs to another host")
    if snapshot.get("platform") != dispatch["runner"]["platform"] or snapshot.get("architecture") != dispatch["runner"]["architecture"]:
        raise ExecutionError("runner_snapshot_mismatch", "snapshot target differs from dispatch")
    capabilities = validate_string_list(snapshot.get("capabilities"), "hostCapabilitySnapshot.capabilities")
    if not set(dispatch["runner"]["capabilities"]).issubset(capabilities):
        raise ExecutionError("capability_snapshot_mismatch", "snapshot lacks a required capability")
    profiles = snapshot.get("profiles")
    expected = {"name": dispatch["profile"], "digest": dispatch["profileDigest"]}
    if not isinstance(profiles, list) or expected not in profiles:
        raise ExecutionError("profile_snapshot_mismatch", "snapshot lacks the fixed profile")
    if sha256_digest(snapshot) != lease["capabilityDigest"]:
        raise ExecutionError("capability_digest_mismatch", "snapshot digest differs from lease")


def build_execution_handoff(dispatch_value: Mapping[str, Any], lease_value: Mapping[str, Any] | Any, *, now: datetime) -> dict[str, Any]:
    now = require_utc(now)
    dispatch, lease = validate_dispatch(dispatch_value), validate_lease(lease_value)
    issued, expires = parse_time(lease["issuedAt"], "issuedAt"), parse_time(lease["expiresAt"], "expiresAt")
    if issued > now + timedelta(seconds=30):
        raise ExecutionError("lease_from_future", "lease issuedAt is too far in the future")
    if expires <= now:
        raise ExecutionError("lease_expired", "lease expired before handoff")
    if expires <= issued:
        raise ExecutionError("lease_ttl_invalid", "lease expiry must follow issuance")
    if lease["cancelRequested"]:
        raise ExecutionError("lease_cancel_pending", "canceled leases cannot create handoffs")
    for field in ("requestId", "requestDigest", "repositoryUrl", "commitSha", "profile", "profileDigest"):
        if lease[field] != dispatch[field]:
            raise ExecutionError("lease_dispatch_mismatch", f"lease {field} differs from dispatch")
    _validate_snapshot(lease["hostCapabilitySnapshot"], lease, dispatch)
    unsigned = {
        "schemaVersion": HANDOFF_SCHEMA,
        "requestId": dispatch["requestId"], "requestDigest": dispatch["requestDigest"],
        "leaseId": lease["leaseId"], "leaseNonce": lease["nonce"],
        "hostId": lease["hostId"], "keyId": lease["keyId"],
        "repositoryUrl": dispatch["repositoryUrl"], "commitSha": dispatch["commitSha"],
        "profile": dispatch["profile"], "profileDigest": dispatch["profileDigest"],
        "capabilityDigest": lease["capabilityDigest"], "runner": copy.deepcopy(dispatch["runner"]),
        "contextDir": dispatch["contextDir"], "issuedAt": format_time(now),
        "expiresAt": lease["expiresAt"], "checkoutPolicy": copy.deepcopy(CHECKOUT_POLICY),
    }
    return {**unsigned, "handoffDigest": sha256_digest(unsigned)}


def validate_execution_handoff(value: Mapping[str, Any], *, now: datetime) -> dict[str, Any]:
    now = require_utc(now)
    handoff = require_mapping(value, "execution handoff")
    require_exact_keys(handoff, HANDOFF_KEYS, "execution handoff")
    reject_secret_fields(handoff)
    if handoff["schemaVersion"] != HANDOFF_SCHEMA:
        raise ExecutionError("schema_unsupported", "unsupported handoff schema")
    unsigned = {key: copy.deepcopy(item) for key, item in handoff.items() if key != "handoffDigest"}
    if not isinstance(handoff["handoffDigest"], str) or sha256_digest(unsigned) != handoff["handoffDigest"]:
        raise ExecutionError("handoff_digest_mismatch", "handoff content does not match digest")
    if handoff["checkoutPolicy"] != CHECKOUT_POLICY:
        raise ExecutionError("checkout_policy_mismatch", "checkout policy differs from reviewed contract")
    for field in ("requestDigest", "profileDigest", "capabilityDigest"):
        if not isinstance(handoff[field], str) or not SHA256_RE.fullmatch(handoff[field]):
            raise ExecutionError("digest_invalid", f"{field} is invalid")
    for field in ("requestId", "leaseId", "leaseNonce", "hostId", "keyId"):
        bounded_identifier(handoff[field], field)
    validate_repository_url(handoff["repositoryUrl"])
    if not isinstance(handoff["commitSha"], str) or not SHA1_RE.fullmatch(handoff["commitSha"]):
        raise ExecutionError("commit_sha_invalid", "handoff commitSha is invalid")
    validate_runner(handoff["runner"])
    validate_context_dir(handoff["contextDir"])
    issued, expires = parse_time(handoff["issuedAt"], "issuedAt"), parse_time(handoff["expiresAt"], "expiresAt")
    if issued > now + timedelta(seconds=30):
        raise ExecutionError("handoff_from_future", "handoff is from the future")
    if expires <= now:
        raise ExecutionError("handoff_expired", "execution handoff expired")
    return copy.deepcopy(dict(handoff))
