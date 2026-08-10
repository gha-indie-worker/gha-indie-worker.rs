#!/usr/bin/env python3
"""Fail-closed GitHub capability audit for unattended engineering jobs.

The audit shells out to the GitHub CLI but never reads or prints tokens. It
checks authentication, organization repository-creation capability, repository
write capability, and existing GitHub Project V2 update capability.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass, replace
from typing import Any, Protocol


class AuditError(RuntimeError):
    """A bounded, user-safe audit failure."""


class Runner(Protocol):
    def run(self, *args: str, input_text: str | None = None) -> str: ...


class SubprocessRunner:
    def run(self, *args: str, input_text: str | None = None) -> str:
        completed = subprocess.run(
            list(args),
            input=input_text,
            capture_output=True,
            check=False,
            text=True,
            timeout=30,
        )
        if completed.returncode != 0:
            detail = _safe_error(completed.stderr or completed.stdout)
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
    for marker in ("ghp_", "github_pat_", "gho_", "ghu_", "ghs_", "ghr_"):
        if marker in lowered:
            return "credential-bearing error redacted"
    return compact[:300]


def _gh_json(runner: Runner, *args: str) -> Any:
    raw = runner.run("gh", *args)
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise AuditError("GitHub CLI returned invalid JSON") from exc


def _audit_repository(runner: Runner, repository: str) -> RepositoryCapability:
    payload = _gh_json(runner, "api", f"repos/{repository}")
    permissions = payload.get("permissions") or {}
    readable = bool(payload.get("full_name"))
    push = bool(permissions.get("push") or permissions.get("maintain") or permissions.get("admin"))
    return RepositoryCapability(
        repository=repository,
        readable=readable,
        contents_write=push,
        branch_write=push,
        pull_request_write=push,
        merge_permission=push,
        note=(
            "Repository permission permits eligible merges; branch protection "
            "and exact-head checks still govern each pull request."
            if push
            else "Push permission is missing."
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
    org = (payload.get("data") or {}).get("organization")
    if not org:
        return OrganizationCapability(
            organization=organization,
            visible=False,
            repository_create=False,
            project_update=False,
            project_count_sampled=0,
            note="Organization is not visible to the authenticated identity.",
        )

    projects = org.get("projectsV2") or {}
    nodes = projects.get("nodes") or []
    project_update: bool | None
    if nodes:
        project_update = all(bool(node.get("viewerCanUpdate")) for node in nodes)
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
        repository_create=bool(org.get("viewerCanCreateRepositories")),
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
        authenticated = bool(login)
    except AuditError as exc:
        blockers.append(str(exc))

    org_results: list[OrganizationCapability] = []
    repo_results: list[RepositoryCapability] = []
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
            except AuditError as exc:
                blockers.append(f"{org}: {exc}")

        for repository in repositories:
            try:
                result = _audit_repository(runner, repository)
                repo_results.append(result)
                if not result.contents_write:
                    blockers.append(f"{repository}: repository contents are not writable.")
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
