#!/usr/bin/env python3
"""Fail-closed GitHub capability audit for unattended engineering jobs.

The audit shells out to the GitHub CLI but never reads or prints tokens. It
checks authentication, organization repository-creation capability, repository
write capability, and existing GitHub Project V2 update capability.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass, replace
from typing import Any, Protocol


class AuditError(RuntimeError):
    """A bounded, user-safe audit failure."""


class RateLimitError(AuditError):
    """Stop further GitHub API calls on confirmed primary/secondary limiting."""


def _is_rate_limit_error(detail: str) -> bool:
    """Do not confuse a generic permissions HTTP 403 with an Actions/API budget error."""
    lowered = detail.casefold()
    return (
        any(
            marker in lowered
            for marker in (
                "secondary rate limit",
                "rate limit exceeded",
                "rate limit has been exceeded",
                "api rate limit",
                "abuse detection",
                "abuse limit",
                "too many requests",
                "rate_limited",
                "rate_limit",
            )
        )
        or bool(re.search(r"\b(?:http\s*|status(?:\s+code)?\s*[:=]?\s*|response\s+code\s*[:=]?\s*)429\b", lowered))
        or ("403" in lowered and bool(re.search(r"x-ratelimit-remaining\s*:\s*0\b", lowered)))
    )


class Runner(Protocol):
    def run(self, *args: str, input_text: str | None = None) -> str: ...


class SubprocessRunner:
    def run(self, *args: str, input_text: str | None = None) -> str:
        try:
            completed = subprocess.run(
                list(args),
                input=input_text,
                capture_output=True,
                check=False,
                text=True,
                timeout=30,
            )
        except subprocess.TimeoutExpired as exc:
            # A timeout is not evidence of either a code failure or Actions
            # budget exhaustion, and must not expose subprocess arguments.
            raise AuditError(f"{args[0]} command timed out without an API result") from exc
        except OSError as exc:
            raise AuditError(f"{args[0]} command could not be executed") from exc
        if completed.returncode != 0:
            # Inspect BOTH streams: gh may emit warnings to stderr while
            # placing the actual HTTP 429 response in stdout.
            error_output = "\n".join((completed.stderr or "", completed.stdout or ""))
            detail = _safe_error(error_output)
            if _is_rate_limit_error(error_output):
                raise RateLimitError(f"{args[0]} rate-limited: {detail}")
            raise AuditError(f"{args[0]} command failed: {detail}")
        return completed.stdout


@dataclass(frozen=True)
class RepositoryCapability:
    repository: str
    readable: bool
    contents_write: bool
    branch_write: bool
    pull_request_write: bool
    merge_permission: bool
    note: str


@dataclass(frozen=True)
class OrganizationCapability:
    organization: str
    visible: bool
    repository_create: bool
    project_update: bool | None
    project_count_sampled: int
    note: str


@dataclass(frozen=True)
class AuditReport:
    schema_version: str
    authenticated: bool
    login: str | None
    cli_available: bool
    organizations: list[OrganizationCapability]
    repositories: list[RepositoryCapability]
    ready_for_unattended_writes: bool
    blockers: list[str]


def _safe_error(value: str) -> str:
    compact = " ".join(value.strip().split())
    if not compact:
        return "unknown error"
    lowered = compact.lower()
    # Credential material is not always a GitHub PAT. HTTP clients and
    # integrations may echo generic bearer/basic headers, URL userinfo,
    # passwords, and API-key assignments in an error response.
    sensitive_markers = (
        "ghp_", "github_pat_", "gho_", "ghu_", "ghs_", "ghr_",
        "authorization:", "bearer ", "basic ", "x-api-key:",
        "api_key=", "api_key:", "apikey=", "access_token=",
        "github_token=", "gh_token=", "token=", "password=",
        "client_secret=", "secret_key=", "private_key=",
    )
    if any(marker in lowered for marker in sensitive_markers) or re.search(
        r"https?://[^\\s/@]+:[^\\s/@]+@", compact, flags=re.IGNORECASE
    ):
        return "credential-bearing error redacted"
    return compact[:300]


def _gh_json(runner: Runner, *args: str) -> Any:
    raw = runner.run("gh", *args)
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise AuditError("GitHub CLI returned invalid JSON") from exc
    if not isinstance(payload, dict):
        raise AuditError("GitHub API returned unexpected non-object JSON")
    # Treat even HTTP-200 error envelopes as errors; never silently interpret
    # an API throttle as missing repository or organization capability.
    if isinstance(payload.get("message"), str) and _is_rate_limit_error(payload["message"]):
        raise RateLimitError("GitHub API rate-limited: " + _safe_error(payload["message"]))
    if payload.get("errors"):
        detail = json.dumps(payload["errors"])
        safe_detail = _safe_error(detail)
        if _is_rate_limit_error(detail):
            raise RateLimitError(f"GitHub API rate-limited: {safe_detail}")
        raise AuditError(f"GitHub API returned errors: {safe_detail}")
    return payload


def _audit_repository(runner: Runner, repository: str) -> RepositoryCapability:
    payload = _gh_json(runner, "api", f"repos/{repository}")
    permissions = payload.get("permissions")
    if not isinstance(permissions, dict):
        raise AuditError("GitHub repository response has invalid permissions")
    actual_name = payload.get("full_name")
    readable = isinstance(actual_name, str) and actual_name.casefold() == repository.casefold()
    # GitHub returns booleans. Values such as "false", 1, or "yes" are not proof
    # of a permission; Python's bool() would otherwise grant a false positive.
    push = readable and any(
        permissions.get(key) is True for key in ("push", "maintain", "admin")
    )
    return RepositoryCapability(
        repository=repository,
        readable=readable,
        contents_write=push,
        branch_write=push,
        pull_request_write=push,
        # Repository write access never proves branch-protection/approval gates.
        merge_permission=False,
        note=(
            "Repository write permission verified; merge eligibility remains "
            "unproven pending independent reviews and protected exact-head checks."
            if push
            else (
                "Repository identity is missing or mismatched."
                if not readable
                else "Push permission is missing."
            )
        ),
    )


ORG_QUERY = """
query($login: String!) {
  organization(login: $login) {
    login
    viewerCanCreateRepositories
    projectsV2(first: 20) {
      totalCount
      nodes {
        id
        title
        viewerCanUpdate
      }
    }
  }
}
""".strip()


def _audit_organization(runner: Runner, organization: str) -> OrganizationCapability:
    payload = _gh_json(
        runner,
        "api",
        "graphql",
        "-f",
        f"query={ORG_QUERY}",
        "-F",
        f"login={organization}",
    )
    data = payload.get("data")
    if not isinstance(data, dict):
        raise AuditError("GitHub organization response has invalid data")
    org = data.get("organization")
    if org is None:
        return OrganizationCapability(
            organization=organization,
            visible=False,
            repository_create=False,
            project_update=False,
            project_count_sampled=0,
            note="Organization is not visible to the authenticated identity.",
        )

    if not isinstance(org, dict) or not isinstance(org.get("login"), str):
        raise AuditError("GitHub organization response has invalid identity")
    if org["login"].casefold() != organization.casefold():
        raise AuditError("GitHub organization response identity mismatch")
    projects = org.get("projectsV2") or {}
    if not isinstance(projects, dict):
        raise AuditError("GitHub organization response has invalid projects")
    nodes = projects.get("nodes") or []
    if not isinstance(nodes, list) or any(not isinstance(node, dict) for node in nodes):
        raise AuditError("GitHub organization response has invalid project nodes")
    count = projects.get("totalCount")
    if type(count) is not int or count < len(nodes):
        raise AuditError("GitHub organization response has invalid project count")
    if count > len(nodes):
        # GraphQL query fetches only the first 20 projects. An incomplete sample
        # cannot establish organization-wide write authority.
        raise AuditError("GitHub Projects enumeration incomplete; pagination required")
    project_update: bool | None
    if nodes:
        project_update = all(node.get("viewerCanUpdate") is True for node in nodes)
        note = (
            "All sampled organization projects are writable."
            if project_update
            else "At least one sampled organization project is read-only."
        )
    else:
        project_update = None
        note = "No existing organization project was available to prove update access."

    return OrganizationCapability(
        organization=organization,
        visible=True,
        repository_create=org.get("viewerCanCreateRepositories") is True,
        project_update=project_update,
        project_count_sampled=len(nodes),
        note=note,
    )


def build_report(
    runner: Runner,
    organizations: list[str],
    repositories: list[str],
) -> AuditReport:
    if shutil.which("gh") is None and isinstance(runner, SubprocessRunner):
        return AuditReport(
            schema_version="gha-indie-worker.github-readiness.v1",
            authenticated=False,
            login=None,
            cli_available=False,
            organizations=[],
            repositories=[],
            ready_for_unattended_writes=False,
            blockers=["GitHub CLI is not installed or not on PATH."],
        )

    blockers: list[str] = []
    login: str | None = None
    authenticated = False
    try:
        runner.run("gh", "auth", "status", "--hostname", "github.com")
        user = _gh_json(runner, "api", "user")
        login = user.get("login")
        authenticated = isinstance(login, str) and bool(
            re.fullmatch(r"[A-Za-z0-9-]{1,39}", login)
        )
        if not authenticated:
            login = None
            blockers.append("GitHub returned missing or invalid authenticated login.")
    except AuditError as exc:
        blockers.append(str(exc))

    org_results: list[OrganizationCapability] = []
    repo_results: list[RepositoryCapability] = []
    rate_limited = False
    if authenticated:
        for org in organizations:
            try:
                result = _audit_organization(runner, org)
                org_results.append(result)
                if not result.visible:
                    blockers.append(f"{org}: organization is not visible.")
                if not result.repository_create:
                    blockers.append(f"{org}: repository creation is not permitted.")
                if result.project_update is False:
                    blockers.append(f"{org}: at least one sampled GitHub Project is not writable.")
                if result.project_update is None:
                    blockers.append(f"{org}: GitHub Projects write access is unproven.")
            except RateLimitError as exc:
                blockers.append(f"{org}: API rate limit; stopped remaining scan: {exc}")
                rate_limited = True
                break
            except AuditError as exc:
                blockers.append(f"{org}: {exc}")

        for repository in ([] if rate_limited else repositories):
            try:
                result = _audit_repository(runner, repository)
                repo_results.append(result)
                if not result.readable:
                    blockers.append(f"{repository}: repository identity is missing or mismatched.")
                elif not result.contents_write:
                    blockers.append(f"{repository}: repository contents are not writable.")
            except RateLimitError as exc:
                blockers.append(f"{repository}: API rate limit; stopped remaining scan: {exc}")
                rate_limited = True
                break
            except AuditError as exc:
                blockers.append(f"{repository}: {exc}")

    return AuditReport(
        schema_version="gha-indie-worker.github-readiness.v1",
        authenticated=authenticated,
        login=login,
        cli_available=True,
        organizations=org_results,
        repositories=repo_results,
        ready_for_unattended_writes=authenticated and not blockers,
        blockers=blockers,
    )


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--org", action="append", default=[], help="Organization login to audit")
    parser.add_argument("--repo", action="append", default=[], help="owner/repository to audit")
    parser.add_argument(
        "--allow-unproven-projects",
        action="store_true",
        help="Treat organizations with no existing Projects V2 board as non-blocking.",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv or sys.argv[1:])
    if not args.org and not args.repo:
        print("At least one --org or --repo is required.", file=sys.stderr)
        return 2

    report = build_report(SubprocessRunner(), args.org, args.repo)
    if args.allow_unproven_projects:
        filtered = [
            blocker
            for blocker in report.blockers
            if not blocker.endswith("GitHub Projects write access is unproven.")
        ]
        report = replace(
            report,
            ready_for_unattended_writes=report.authenticated and not filtered,
            blockers=filtered,
        )

    print(json.dumps(asdict(report), indent=2, sort_keys=True))
    return 0 if report.ready_for_unattended_writes else 1


if __name__ == "__main__":
    raise SystemExit(main())
