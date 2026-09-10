#!/usr/bin/env python3
"""Canonical validation, digest, and capability-envelope helpers."""

from __future__ import annotations

from .native_fleet_models import *  # noqa: F401,F403

def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def format_time(value: datetime) -> str:
    value = require_utc(value)
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")


def parse_time(value: Any, field_name: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise FleetError("timestamp_invalid", f"{field_name} must be an RFC3339 UTC timestamp")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise FleetError("timestamp_invalid", f"{field_name} is invalid") from error
    return require_utc(parsed)


def require_utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() != timedelta(0):
        raise FleetError("timestamp_invalid", "timestamps must be timezone-aware UTC values")
    return value.astimezone(timezone.utc)


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def sha256_digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def random_id(prefix: str, byte_count: int = 16) -> str:
    return f"{prefix}_{secrets.token_hex(byte_count)}"


def version_tuple(value: str) -> tuple[int, int, int]:
    match = SEMVER_RE.fullmatch(value)
    if not match:
        raise FleetError("agent_version_invalid", "agentVersion must be strict major.minor.patch")
    return tuple(int(part) for part in match.groups())


def _require_exact_keys(value: Mapping[str, Any], expected: set[str], context: str) -> None:
    actual = set(value)
    missing = sorted(expected - actual)
    unknown = sorted(actual - expected)
    if missing:
        raise FleetError("field_missing", f"{context} is missing {missing}")
    if unknown:
        raise FleetError("field_unknown", f"{context} contains unknown fields {unknown}")


def _reject_secret_fields(value: Any, path: str = "$") -> None:
    if isinstance(value, Mapping):
        for key, nested in value.items():
            if FORBIDDEN_FIELD_RE.search(str(key)):
                raise FleetError("secret_field_forbidden", f"{path}.{key} is forbidden")
            _reject_secret_fields(nested, f"{path}.{key}")
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            _reject_secret_fields(nested, f"{path}[{index}]")


def _validate_string_list(
    values: Any,
    field_name: str,
    pattern: re.Pattern[str],
    *,
    maximum: int = 64,
) -> list[str]:
    if not isinstance(values, list) or len(values) > maximum:
        raise FleetError("list_invalid", f"{field_name} must be a list with at most {maximum} entries")
    if any(not isinstance(value, str) or not pattern.fullmatch(value) for value in values):
        raise FleetError("identifier_invalid", f"{field_name} contains an invalid identifier")
    if len(set(values)) != len(values):
        raise FleetError("duplicate_value", f"{field_name} contains duplicate entries")
    if values != sorted(values):
        raise FleetError("order_invalid", f"{field_name} must be sorted")
    return list(values)


def validate_host_capability(payload: Mapping[str, Any], minimum_agent_version: str) -> dict[str, Any]:
    if not isinstance(payload, Mapping):
        raise FleetError("capability_invalid", "capability payload must be an object")
    _require_exact_keys(
        payload,
        {
            "schemaVersion",
            "hostId",
            "platform",
            "architecture",
            "hardwareClass",
            "osBuild",
            "agentVersion",
            "protocolVersion",
            "trustTier",
            "patchRing",
            "sandbox",
            "networkProfile",
            "shells",
            "capabilities",
            "profiles",
            "concurrency",
        },
        "host capability",
    )
    _reject_secret_fields(payload)
    if payload["schemaVersion"] != HOST_CAPABILITY_SCHEMA:
        raise FleetError("schema_unsupported", "unsupported host capability schema")
    host_id = payload["hostId"]
    if not isinstance(host_id, str) or not HOST_ID_RE.fullmatch(host_id):
        raise FleetError("host_id_invalid", "hostId is invalid")
    platform = payload["platform"]
    architecture = payload["architecture"]
    if platform not in PLATFORMS:
        raise FleetError("platform_unsupported", f"unsupported platform {platform!r}")
    if architecture not in ARCHITECTURES:
        raise FleetError("architecture_unsupported", f"unsupported architecture {architecture!r}")
    if payload["trustTier"] not in TRUST_TIERS:
        raise FleetError("trust_tier_unsupported", "unsupported trustTier")
    for field_name in ("hardwareClass", "osBuild", "patchRing", "sandbox", "networkProfile"):
        value = payload[field_name]
        if not isinstance(value, str) or not value or len(value) > 128:
            raise FleetError("field_invalid", f"{field_name} must be a bounded non-empty string")
    if payload["protocolVersion"] != PROTOCOL_VERSION:
        raise FleetError("protocol_version_unsupported", "unsupported protocolVersion")
    if version_tuple(payload["agentVersion"]) < version_tuple(minimum_agent_version):
        raise FleetError("agent_version_stale", "agentVersion is below the minimum")
    shells = _validate_string_list(payload["shells"], "shells", NAME_RE, maximum=16)
    capabilities = _validate_string_list(
        payload["capabilities"], "capabilities", CAPABILITY_RE, maximum=64
    )
    if platform in {"macos", "windows"} and "native" not in capabilities:
        raise FleetError("native_capability_missing", "native macOS/Windows hosts require native capability")
    profiles = payload["profiles"]
    if not isinstance(profiles, list) or not profiles or len(profiles) > 64:
        raise FleetError("profiles_invalid", "profiles must contain 1..64 entries")
    normalized_profiles: list[dict[str, str]] = []
    seen_profiles: set[str] = set()
    for profile in profiles:
        if not isinstance(profile, Mapping):
            raise FleetError("profile_invalid", "profile entries must be objects")
        _require_exact_keys(profile, {"name", "digest"}, "profile")
        name = profile["name"]
        digest = profile["digest"]
        if not isinstance(name, str) or not NAME_RE.fullmatch(name):
            raise FleetError("profile_name_invalid", "profile name is invalid")
        if name in seen_profiles:
            raise FleetError("duplicate_profile", f"duplicate profile {name!r}")
        if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
            raise FleetError("profile_digest_invalid", "profile digest must be sha256:<64 lowercase hex>")
        seen_profiles.add(name)
        normalized_profiles.append({"name": name, "digest": digest})
    if normalized_profiles != sorted(normalized_profiles, key=lambda item: item["name"]):
        raise FleetError("order_invalid", "profiles must be sorted by name")
    concurrency = payload["concurrency"]
    if not isinstance(concurrency, int) or isinstance(concurrency, bool) or not 1 <= concurrency <= 32:
        raise FleetError("concurrency_invalid", "concurrency must be an integer from 1 through 32")
    normalized = copy.deepcopy(dict(payload))
    normalized["shells"] = shells
    normalized["capabilities"] = capabilities
    normalized["profiles"] = normalized_profiles
    return normalized


def sign_capability_envelope(
    credential: DeviceCredential,
    payload: Mapping[str, Any],
    issued_at: datetime,
    expires_at: datetime,
) -> dict[str, Any]:
    issued_at = require_utc(issued_at)
    expires_at = require_utc(expires_at)
    payload_copy = copy.deepcopy(dict(payload))
    payload_digest = sha256_digest(payload_copy)
    signed_fields = {
        "keyId": credential.key_id,
        "issuedAt": format_time(issued_at),
        "expiresAt": format_time(expires_at),
        "payloadDigest": payload_digest,
    }
    signature = base64.urlsafe_b64encode(
        hmac.new(credential.secret, canonical_bytes(signed_fields), hashlib.sha256).digest()
    ).decode().rstrip("=")
    return {
        "schemaVersion": CAPABILITY_ENVELOPE_SCHEMA,
        **signed_fields,
        "signatureAlgorithm": "hmac-sha256-simulator",
        "signature": signature,
        "payload": payload_copy,
    }


def validate_dispatch_request(request: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(request, Mapping):
        raise FleetError("dispatch_invalid", "dispatch request must be an object")
    _require_exact_keys(
        request,
        {
            "schemaVersion",
            "requestId",
            "requestDigest",
            "planDigest",
            "profileCatalogDigest",
            "repositoryUrl",
            "commitSha",
            "jobInstanceId",
            "baseJobId",
            "jobOrderIndex",
            "profile",
            "profileDigest",
            "runner",
            "contextDir",
            "needsInstances",
            "matrix",
            "failFast",
            "maxParallel",
        },
        "dispatch request",
    )
    _reject_secret_fields(request)
    if request["schemaVersion"] != DISPATCH_SCHEMA:
        raise FleetError("schema_unsupported", "unsupported dispatch schema")
    for field_name in ("requestDigest", "planDigest", "profileCatalogDigest", "profileDigest"):
        if not isinstance(request[field_name], str) or not SHA256_RE.fullmatch(request[field_name]):
            raise FleetError("digest_invalid", f"{field_name} must be a sha256 digest")
    if not isinstance(request["requestId"], str) or not request["requestId"]:
        raise FleetError("request_id_invalid", "requestId is required")
    if not isinstance(request["repositoryUrl"], str) or not request["repositoryUrl"].startswith("https://github.com/"):
        raise FleetError("repository_invalid", "repositoryUrl must be an HTTPS GitHub repository")
    if not isinstance(request["commitSha"], str) or not SHA1_RE.fullmatch(request["commitSha"]):
        raise FleetError("commit_sha_invalid", "commitSha must be 40 lowercase hexadecimal characters")
    if not isinstance(request["profile"], str) or not NAME_RE.fullmatch(request["profile"]):
        raise FleetError("profile_name_invalid", "profile is invalid")
    runner = request["runner"]
    if not isinstance(runner, Mapping):
        raise FleetError("runner_invalid", "runner must be an object")
    _require_exact_keys(runner, {"platform", "architecture", "capabilities"}, "runner")
    if runner["platform"] not in PLATFORMS:
        raise FleetError("platform_unsupported", "unsupported runner platform")
    if runner["architecture"] not in ARCHITECTURES:
        raise FleetError("architecture_unsupported", "unsupported runner architecture")
    capabilities = _validate_string_list(
        runner["capabilities"], "runner.capabilities", CAPABILITY_RE, maximum=64
    )
    if runner["platform"] in {"macos", "windows"} and "native" not in capabilities:
        raise FleetError("native_capability_missing", "native request must require native capability")
    normalized = copy.deepcopy(dict(request))
    normalized["runner"] = {
        "platform": runner["platform"],
        "architecture": runner["architecture"],
        "capabilities": capabilities,
    }
    return normalized


