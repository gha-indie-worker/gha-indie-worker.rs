# GitHub Actions conformance report: trusted Linux expressions v2

- Report ID: `gha-indie-worker.linux-runner.v2/2026-08-14-expression`
- Implementation commit: `b73f8ad3892056bb3b0d768e166dd0f65b16b490`
- Oracle platform: GitHub-hosted `ubuntu-24.04`
- Result: **PASS for the bounded expression surface below**
- Full GitHub Actions expression or service compatibility: **not claimed**

This report extends, rather than replaces,
`gha-indie-worker.linux-runner.v1/2026-08-14`. The trusted
`gha-linux-runner` CLI remains disconnected from webhook and HTTP intake and
still requires `--allow-host-execution`. The production worker continues to
accept only reviewed fixed profiles.

## Independent differential evidence

GitHub Actions run
[`31832954357`](https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31832954357),
job
[`94872741810`](https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31832954357/job/94872741810),
passed on the exact implementation commit. The job evaluated an oracle sequence
directly in GitHub Actions, evaluated the equivalent fixture through
`gha-linux-runner`, byte-compared their workspaces, and compared every selected
step outcome and conclusion.

The final machine-readable observation was:

```json
{
  "jobConclusion": "success",
  "matchedSteps": [
    "after",
    "must_skip",
    "producer",
    "tolerated"
  ],
  "schemaVersion": "gha-indie-worker.linux-runner.v2",
  "workspaceResult": "a-b:yes:7:Alpha"
}
```

The differential fixture proves the following behaviors together:

- an unquoted YAML `run: false` scalar is normalized to the shell command
  `false`, matching the official runner;
- static boolean and string matrix values are available to expressions;
- environment and output values remain strings until explicitly converted;
- string comparisons and `contains`, `startsWith`, and `endsWith` are
  case-insensitive for the observed ASCII values;
- GitHub-compatible loose numerical comparison converts the string output
  `"7"` for comparison with the number `7`;
- a missing property compares equal to the empty string in the observed loose
  equality case;
- `fromJSON` converts strings to booleans, numbers, and arrays;
- bracket indexing selects array entries and can select a step by identifier;
- `join` and `format` produce the observed scalar rendering;
- `&&` and `||` return operand values, enabling GitHub's documented
  pseudo-ternary pattern;
- expression-valued step `continue-on-error` changes a failing step's
  conclusion to `success` while preserving its `failure` outcome;
- subsequent conditions can inspect `steps.<id>.outcome` and
  `steps.<id>.conclusion`;
- a false typed condition produces a skipped step with matching outcome and
  conclusion.

The official oracle lives in
`.github/workflows/linux-expression-parity.yml`; the indie input is
`tests/fixtures/gha/linux-expression-parity.yml`. Both are reviewed source and
the comparison fails on any workspace-byte or selected-status mismatch.

## Typed expression v1 contract

The reusable evaluator identifies itself as
`gha-indie-worker.expression.v1`. Within the trusted Linux v2 boundary it
supports:

- `null`, booleans, JSON decimal and exponential numbers, hexadecimal numeric
  literals, and single-quoted strings with doubled-quote escaping;
- parentheses, property dereference, bracket property/index access, unary `!`,
  `<`, `<=`, `>`, `>=`, `==`, `!=`, `&&`, and `||`;
- documented falsy values and loose numerical equality/conversion;
- case-insensitive ASCII string equality and ordering;
- short-circuit evaluation with operand-valued `&&` and `||`;
- `contains`, `startsWith`, `endsWith`, `format`, `join`, `toJSON`, and
  `fromJSON`;
- `success`, `failure`, `cancelled`, and `always` in step conditions;
- explicitly supplied `matrix`, `env`, and completed prior-step contexts,
  including step outputs, outcome, and conclusion;
- scalar template rendering and type-preserving evaluation when a field is one
  complete `${{ ... }}` expression.

Step conditions without any explicit status-check function receive GitHub's
implicit `success()` gate. Local integration tests prove that literal and
context-only true conditions are skipped after an ordinary failure while an
explicit `failure()` recovery condition runs.

## Local verification evidence

The following suites passed from the implementation worktree on 2026-08-14:

| Suite | Toolchain | Result |
| --- | --- | --- |
| Standalone worker, all Rust targets | Rust 1.90.0 | 114 passed: 35 library, 75 service binary, 1 Linux CLI, 3 planner CLI |
| Execution-free planner and expression parser | Rust 1.97.0, `--no-default-features` | 27 passed: 24 library, 3 planner CLI |
| Immutable binding protocol | Rust 1.97.0 | 16 passed: 14 library, 2 CLI |
| Native fleet and exact-checkout Python suite | Python 3 | 37 passed |
| Standalone worker lint | Rust 1.90.0 Clippy, all targets, warnings denied | passed |
| Execution-free planner lint | Rust 1.90.0 Clippy, warnings denied | passed |
| Immutable protocol lint | Rust 1.97.0 Clippy, all targets, warnings denied | passed |

Reproduction commands:

```sh
rustup run 1.90.0 cargo test --locked --all-targets
rustup run 1.90.0 cargo clippy --locked --all-targets -- -D warnings
rustup run 1.97.0 cargo test --locked --no-default-features --lib --bin gha-workflow-plan
rustup run 1.90.0 cargo clippy --locked --no-default-features --lib --bin gha-workflow-plan -- -D warnings
python3 -m unittest discover -s tests -p 'test_*.py'
```

All five PR workflows attached to the exact implementation commit passed:

- Linux expression parity;
- Linux runner parity;
- standalone GHA indie worker;
- workflow planner;
- full-history secret scan.

## Negative and resource-bound evidence

The expression evaluator bounds each untrusted input or derived structure:

| Boundary | Maximum |
| --- | ---: |
| Expression source | 4 KiB |
| Lexical tokens | 512 |
| Parse/evaluation nesting | 64 |
| Function arguments | 32 |
| Evaluated JSON or rendered value | 64 KiB |

Whole-job preflight parses and validates every expression before the first
shell process starts. Tests prove that a later `secrets.TOKEN` reference or
`hashFiles(...)` call prevents an earlier marker-producing step from running.
Unavailable root contexts, unsupported functions, malformed syntax, excessive
input, unsupported array/object string coercion, invalid `fromJSON`, and invalid
`format` placeholders produce stable fail-closed errors.

The evaluator only receives caller-constructed public context objects. It has
no ambient access to process environment variables, repository tokens, event
payloads, filesystem contents, network services, or secrets.

## Explicitly unproven or unsupported

This report does not prove or claim parity for:

- `github`, `needs`, `vars`, `inputs`, `runner`, `strategy`, `job`, `secrets`,
  or reusable-workflow `jobs` contexts;
- secret-taint propagation, masking, redaction, or use of secrets in shell
  templates;
- `hashFiles`, object filters using `*`, wildcard projection, or any unlisted
  function;
- complete Unicode case-folding behavior;
- object or array reference-identity equality;
- every numeric formatting edge case, every malformed-expression diagnostic,
  or every `format` brace edge case accepted by GitHub;
- dynamic matrices derived from expressions or prior-job outputs;
- job-level conditions, `needs` result/output expressions, job outputs, job
  timeouts, job-level `continue-on-error`, matrix scheduling, `fail-fast`, or
  `max-parallel`;
- action execution, services, containers, permissions, tokens, events,
  artifacts, caches, environments, deployments, or check-run lifecycle APIs.

The authoritative cumulative matrix and roadmap remain in
`docs/GHA_COMPATIBILITY.md`. Any future expansion of this expression surface
requires a new report ID and fresh official-runner differential evidence.
