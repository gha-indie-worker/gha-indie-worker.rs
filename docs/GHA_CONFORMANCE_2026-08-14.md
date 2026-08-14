# GitHub Actions conformance report: trusted Linux runner v1

- Report ID: `gha-indie-worker.linux-runner.v1/2026-08-14`
- Implementation commit: `2f7cc06c284b7187cdbb6c20b8521bbe841977be`
- Oracle platform: GitHub-hosted `ubuntu-24.04`
- Result: **PASS for the bounded surface below**
- Full GitHub Actions compatibility: **not claimed**

This report records differential and local evidence for the explicitly trusted
`gha-linux-runner` CLI. The CLI is not connected to webhook or HTTP intake and
requires `--allow-host-execution`, because a workflow `run` step is arbitrary
host code. The production worker continues to accept only reviewed fixed
profiles.

## Independent differential evidence

GitHub Actions run
[`31829820013`](https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31829820013),
job
[`94862579287`](https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31829820013/job/94862579287),
passed in 1 minute 32 seconds. The job ran an oracle sequence directly on the
official GitHub-hosted runner, ran the equivalent fixture through
`gha-linux-runner`, byte-compared their workspace results, and compared the
selected step outcomes and conclusions.

The final machine-readable observation was:

```json
{
  "jobConclusion": "success",
  "matchedSteps": [
    "after",
    "consume_commands",
    "default_pipe",
    "file_commands",
    "must_skip",
    "tolerated_pipe"
  ],
  "schemaVersion": "gha-indie-worker.linux-runner.v1",
  "workspaceResult": "step:step:workflow:workflow|from-env|42|path=from-path|env=env-one\nenv-two|output=output-one\noutput-two"
}
```

That observation proves parity for all of the following behaviors in the
versioned fixture:

- separate step processes with a persistent workspace;
- scalar workflow, job, and step environment precedence;
- workflow and job `defaults.run` inheritance with step overrides;
- `GITHUB_ENV` persistence, including delimiter-based multiline values;
- `GITHUB_OUTPUT` publication and `steps.<id>.outputs.*` interpolation,
  including delimiter-based multiline values;
- `GITHUB_PATH` updates becoming executable search paths in later steps;
- static scalar `matrix.*` interpolation;
- inherited `sh -e` behavior and explicit Bash pipefail behavior;
- step `continue-on-error` outcome/conclusion separation;
- default/success and failure status gating for the observed successful job;
- workspace-contained working-directory resolution.

The oracle is defined in `.github/workflows/linux-runner-parity.yml`; the indie
input is `tests/fixtures/gha/linux-runner-parity.yml`. Both are reviewed source,
not dynamically generated expectations.

## Local verification evidence

The following suites passed from the implementation worktree on 2026-08-14:

| Suite | Toolchain | Result |
| --- | --- | --- |
| Standalone worker, all Rust targets | Rust 1.90.0 | 104 passed: 25 library, 75 service binary, 1 Linux CLI, 3 planner CLI |
| Execution-free planner | Rust 1.97.0, `--no-default-features` | 20 passed: 17 library, 3 planner CLI |
| Immutable binding protocol | Rust 1.97.0 | 16 passed: 14 library, 2 CLI |
| Native fleet and exact-checkout Python suite | Python 3 | 37 passed |
| Standalone worker lint | Rust 1.90.0 Clippy, all targets, warnings denied | passed |
| Immutable protocol lint | Rust 1.97.0 Clippy, all targets, warnings denied | passed |

Reproduction commands:

```sh
rustup run 1.90.0 cargo test --locked --all-targets
rustup run 1.90.0 cargo clippy --locked --all-targets -- -D warnings
rustup run 1.97.0 cargo test --locked --no-default-features --lib --bin gha-workflow-plan
python3 -m unittest discover -s tests -p 'test_*.py'
```

The protocol crate declares Rust 1.97. Its formatting, strict Clippy, and all
targets are also enforced by `.github/workflows/indie-protocol.yml` whenever
that crate changes.

## Negative and security evidence

Unit tests additionally prove that execution fails closed before any step runs
when host execution is not explicitly authorized or a selected job contains:

- any action or reusable-workflow invocation;
- a secret or unsupported expression context;
- an unsupported job-level condition, timeout, or `continue-on-error` field;
- action-style `with` inputs on a `run` step;
- a non-Linux or ambiguous runner target;
- an unsupported shell or unsafe working directory.

The runner also bounds each supported step by configured time and captured
output limits, validates environment names, protects runner-controlled
environment names, and requires every working directory to resolve within the
canonical workspace.

## Explicitly unproven or unsupported

This report does not prove or claim parity for:

- JavaScript, Docker, composite, local, or marketplace action execution;
- complete GitHub expression syntax, coercion, functions, or contexts;
- secrets, `GITHUB_TOKEN`, permission narrowing, OIDC, or fork policy;
- job-level conditions, job timeouts, job-level `continue-on-error`, matrix
  scheduling, `fail-fast`, `max-parallel`, cancellation, or retries;
- service containers, job containers, artifacts, caches, attestations, or job
  summaries;
- events, filters, schedules, manual inputs, reusable workflows, environments,
  deployment approvals, concurrency groups, or check-run lifecycle APIs;
- Windows or macOS execution through this Linux CLI;
- runner process-tree isolation equivalent to GitHub-hosted virtual machines.

The authoritative support/deviation matrix and roadmap remain in
`docs/GHA_COMPATIBILITY.md`. A future report must use a new report ID and fresh
differential evidence when the claimed surface changes.
