#!/usr/bin/env python3
"""Exact detached-SHA Git checkout with bounded evidence."""

from __future__ import annotations

import copy
import os
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Callable, Mapping, Sequence

try:
    from .native_worker_common import *  # noqa: F401,F403
    from .native_worker_handoff import validate_execution_handoff
except ImportError:
    from native_worker_common import *  # type: ignore # noqa: F401,F403
    from native_worker_handoff import validate_execution_handoff  # type: ignore

MAX_GIT_OUTPUT_BYTES = 16 * 1024
DEFAULT_COMMAND_TIMEOUT_SECONDS = 120
Runner = Callable[..., subprocess.CompletedProcess[str]]


def git_environment(control_home: Path) -> dict[str, str]:
    environment = os.environ.copy()
    dangerous = {
        "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
        "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_CEILING_DIRECTORIES", "GIT_SSH",
        "GIT_SSH_COMMAND", "GIT_ASKPASS", "SSH_ASKPASS", "GIT_CONFIG_GLOBAL",
        "GIT_CONFIG_SYSTEM", "GIT_CONFIG_COUNT", "GIT_NAMESPACE", "GIT_COMMON_DIR",
    }
    for key in list(environment):
        if key in dangerous or key.startswith("GIT_CONFIG_KEY_") or key.startswith("GIT_CONFIG_VALUE_"):
            environment.pop(key, None)
    environment.update({
        "GIT_TERMINAL_PROMPT": "0", "GCM_INTERACTIVE": "Never",
        "GIT_LFS_SKIP_SMUDGE": "1", "GIT_CONFIG_NOSYSTEM": "1",
        "HOME": str(control_home), "USERPROFILE": str(control_home),
        "XDG_CONFIG_HOME": str(control_home / ".config"),
    })
    return environment


def _bounded_output(value: str) -> str:
    encoded = value.encode("utf-8", errors="replace")
    if len(encoded) <= MAX_GIT_OUTPUT_BYTES:
        return value
    return encoded[:MAX_GIT_OUTPUT_BYTES].decode("utf-8", errors="replace") + "\n[output truncated]"


def run_git(
    runner: Runner,
    command: Sequence[str],
    *,
    environment: Mapping[str, str],
    timeout_seconds: int,
    allowed_returncodes: set[int] = {0},
) -> subprocess.CompletedProcess[str]:
    try:
        completed = runner(
            list(command), env=dict(environment), text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=timeout_seconds, shell=False, check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise ExecutionError("git_invocation_failed", f"git invocation failed: {type(error).__name__}") from error
    if completed.returncode not in allowed_returncodes:
        raise ExecutionError(
            "git_command_failed",
            f"git exited {completed.returncode}; stdout={_bounded_output(completed.stdout or '')!r}; "
            f"stderr={_bounded_output(completed.stderr or '')!r}",
        )
    return completed


def ensure_workspace(workspace: Path) -> None:
    if workspace.exists():
        if workspace.is_symlink():
            raise ExecutionError("workspace_symlink_forbidden", "workspace must not be a symlink")
        if not workspace.is_dir():
            raise ExecutionError("workspace_invalid", "workspace must be a directory")
        if any(workspace.iterdir()):
            raise ExecutionError("workspace_not_empty", "workspace must be empty")
    else:
        workspace.mkdir(parents=True, mode=0o700)
    if workspace.resolve() == Path(workspace.anchor).resolve():
        raise ExecutionError("workspace_invalid", "filesystem root cannot be a workspace")


def parse_gitlinks(output: str) -> list[str]:
    gitlinks: list[str] = []
    for record in output.split("\x00"):
        if not record:
            continue
        metadata, separator, path = record.partition("\t")
        if not separator:
            raise ExecutionError("git_tree_invalid", "git ls-tree output is malformed")
        if metadata.split(" ", 1)[0] == "160000":
            gitlinks.append(path)
    return sorted(gitlinks)


def execute_exact_checkout(
    handoff_value: Mapping[str, object],
    *,
    workspace: Path,
    now: datetime,
    runner: Runner = subprocess.run,
    timeout_seconds: int = DEFAULT_COMMAND_TIMEOUT_SECONDS,
) -> dict[str, object]:
    """Checkout one immutable SHA without branch, tag, or transport fallback."""

    started_at = require_utc(now)
    handoff = validate_execution_handoff(handoff_value, now=started_at)
    if not isinstance(timeout_seconds, int) or isinstance(timeout_seconds, bool) or not 1 <= timeout_seconds <= 900:
        raise ExecutionError("timeout_invalid", "command timeout must be 1..900 seconds")
    workspace = Path(workspace)
    existed_before = workspace.exists()
    ensure_workspace(workspace)
    git_path = shutil.which("git")
    if git_path is None:
        if not existed_before:
            shutil.rmtree(workspace, ignore_errors=True)
        raise ExecutionError("git_missing", "git executable is unavailable")
    try:
        with tempfile.TemporaryDirectory(prefix="gha-indie-worker-git-home-") as home_text:
            home = Path(home_text)
            (home / ".config").mkdir(mode=0o700)
            environment, prefix = git_environment(home), [git_path, "-C", str(workspace)]
            run_git(runner, [git_path, "init", "--quiet", str(workspace)], environment=environment, timeout_seconds=timeout_seconds)
            hooks = workspace / ".git" / "gha-indie-worker-disabled-hooks"
            hooks.mkdir(mode=0o700)
            for key, value in {
                "core.hooksPath": str(hooks), "fetch.recurseSubmodules": "false",
                "submodule.recurse": "false", "protocol.file.allow": "never",
                "core.fsmonitor": "false", "gc.auto": "0",
            }.items():
                run_git(runner, [*prefix, "config", "--local", key, value], environment=environment, timeout_seconds=timeout_seconds)
            run_git(runner, [*prefix, "remote", "add", "origin", handoff["repositoryUrl"]], environment=environment, timeout_seconds=timeout_seconds)
            run_git(
                runner,
                [git_path, "-c", "protocol.version=2", "-c", "protocol.file.allow=never", "-C", str(workspace),
                 "fetch", "--no-tags", "--no-recurse-submodules", "--depth=1", "origin", handoff["commitSha"]],
                environment=environment, timeout_seconds=timeout_seconds,
            )
            run_git(runner, [*prefix, "checkout", "--detach", "--force", "FETCH_HEAD"], environment=environment, timeout_seconds=timeout_seconds)
            resolved = run_git(runner, [*prefix, "rev-parse", "--verify", "HEAD"], environment=environment, timeout_seconds=timeout_seconds).stdout.strip()
            if resolved != handoff["commitSha"]:
                raise ExecutionError("checkout_sha_mismatch", "resolved HEAD differs from requested commit")
            symbolic = run_git(runner, [*prefix, "symbolic-ref", "-q", "HEAD"], environment=environment, timeout_seconds=timeout_seconds, allowed_returncodes={0, 1})
            if symbolic.returncode == 0:
                raise ExecutionError("checkout_not_detached", "checkout retained a symbolic branch")
            origin = run_git(runner, [*prefix, "remote", "get-url", "origin"], environment=environment, timeout_seconds=timeout_seconds).stdout.strip()
            if origin != handoff["repositoryUrl"]:
                raise ExecutionError("origin_url_mismatch", "origin URL changed during checkout")
            remotes = sorted(line.strip() for line in run_git(runner, [*prefix, "remote"], environment=environment, timeout_seconds=timeout_seconds).stdout.splitlines() if line.strip())
            if remotes != ["origin"]:
                raise ExecutionError("additional_remote_forbidden", "checkout contains an unexpected remote")
            sensitive = run_git(
                runner,
                [*prefix, "config", "--local", "--name-only", "--get-regexp",
                 r"^(http\..*\.extraheader|url\..*\.insteadof|credential\..*)$"],
                environment=environment, timeout_seconds=timeout_seconds, allowed_returncodes={0, 1},
            )
            if sensitive.returncode == 0 and sensitive.stdout.strip():
                raise ExecutionError("persisted_credential_config_forbidden", "checkout contains auth or URL rewrite config")
            tree_output = run_git(runner, [*prefix, "ls-tree", "-r", "-z", "HEAD"], environment=environment, timeout_seconds=timeout_seconds).stdout
            if parse_gitlinks(tree_output):
                raise ExecutionError("submodule_gitlink_forbidden", "submodule gitlinks are unsupported")
            tree_sha = run_git(runner, [*prefix, "rev-parse", "--verify", "HEAD^{tree}"], environment=environment, timeout_seconds=timeout_seconds).stdout.strip()
            if not SHA1_RE.fullmatch(tree_sha):
                raise ExecutionError("tree_sha_invalid", "resolved tree SHA is invalid")
            status = run_git(runner, [*prefix, "status", "--porcelain=v1", "--untracked-files=no"], environment=environment, timeout_seconds=timeout_seconds).stdout
            if status:
                raise ExecutionError("checkout_dirty", "tracked checkout is not clean")
            context = workspace if handoff["contextDir"] == "." else workspace.joinpath(*PurePosixPath(handoff["contextDir"]).parts)
            if not context.exists() or not context.is_dir() or context.is_symlink():
                raise ExecutionError("context_dir_missing", "fixed contextDir is not a real directory")
            try:
                context.resolve().relative_to(workspace.resolve())
            except ValueError as error:
                raise ExecutionError("context_dir_escape", "contextDir resolves outside checkout") from error
            completed = datetime.now(timezone.utc)
            unsigned = {
                "schemaVersion": EVIDENCE_SCHEMA, "handoffDigest": handoff["handoffDigest"],
                "requestId": handoff["requestId"], "requestDigest": handoff["requestDigest"],
                "leaseId": handoff["leaseId"], "hostId": handoff["hostId"],
                "repositoryUrl": handoff["repositoryUrl"], "requestedCommitSha": handoff["commitSha"],
                "resolvedCommitSha": resolved, "treeSha": tree_sha, "originUrl": origin,
                "remotes": remotes, "detachedHead": True, "submoduleGitlinks": [],
                "contextDir": handoff["contextDir"], "profile": handoff["profile"],
                "profileDigest": handoff["profileDigest"], "runner": copy.deepcopy(handoff["runner"]),
                "startedAt": format_time(started_at), "completedAt": format_time(completed),
            }
            return {**unsigned, "evidenceDigest": sha256_digest(unsigned)}
    except Exception:
        if not existed_before:
            shutil.rmtree(workspace, ignore_errors=True)
        raise
