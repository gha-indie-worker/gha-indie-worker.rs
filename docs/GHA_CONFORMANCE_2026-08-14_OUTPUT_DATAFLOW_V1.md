# GitHub Actions output-dataflow conformance report

- Report ID: `gha-indie-worker.output-dataflow.v1/2026-08-14`
- Planner schema: `gha-indie-worker.plan.v2`
- Job runner schema: `gha-indie-worker.linux-runner.v3`
- Workflow scheduler schema: `gha-indie-worker.linux-workflow.v2`
- Implementation commit: `88411e686bfdd2656fd6a2e1e4d63d2866319404`
- Implementation tree: `5fffd317156416207fe586556532dfe9ae3e51ad`
- Parent `dev` commit: `f8a95013ea81b7e47af7f1363f8fd20caf092e57`
- Pull request: <https://github.com/gha-indie-worker/gha-indie-worker.rs/pull/32>
- Official differential run: <https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31838835400>
- Differential job: <https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31838835400/job/94891150215>
- Differential conclusion: **success**

## Claim

The trusted Linux scheduler matches the GitHub-hosted runner for the exact
job-output and dynamic-matrix behaviors exercised by the paired fixture in this
report. This is a bounded conformance claim, not a claim of complete GitHub
Actions parity.

The official jobs and the indie fixture both proved this sequence:

1. a producer writes three step outputs and explicitly promotes them to job
   outputs;
2. a direct dependent consumes those outputs in `runs-on`, job `env`, step
   `env`, and step source;
3. `fromJSON(needs.define.outputs.matrix)` expands two matrix jobs, `red` and
   `green`;
4. both matrix jobs succeed under `max-parallel: 2` and publish a job output;
5. a job that needs only the matrix group sees that direct group's outputs but
   does not see the producer as a transitive entry in `needs`;
6. the final workflow conclusion is `success` on both engines.

## Ground truth

The implementation and oracle are scoped to GitHub's published semantics:

- [job outputs are mapped from step outputs and are available to dependent jobs](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#jobsjob_idoutputs);
- [the `needs` context contains only direct dependencies, with `result` and `outputs`](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#needs-context);
- [an output may define a later matrix with `fromJSON`](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/run-job-variations#using-an-output-to-define-two-matrices);
- [context availability depends on the workflow key being evaluated](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#context-availability);
- [expression coercion and pure functions follow the documented expression language](https://docs.github.com/en/actions/reference/workflows-and-actions/expressions).

GitHub documents a 1 MiB output limit per job and a 50 MiB total per workflow,
approximated using UTF-16 encoding. It also documents that matrix job outputs
are combined, completion order is not guaranteed, and a duplicate output name
is overwritten by the last completing instance. The indie scheduler applies
the same completion-order merge rule while retaining deterministic result
presentation order.

## Paired oracle

The committed indie fixture is
`tests/fixtures/gha/linux-job-outputs-parity.yml`. The GitHub-hosted half and
the comparator are in
`.github/workflows/linux-job-outputs-parity.yml`.

The GitHub workflow does not declare expected results solely from indie data.
It executes native GitHub jobs first, passes their observed `needs` values into
the differential job, reads the official job list through GitHub's Actions API,
and then executes the paired fixture through `gha-linux-workflow`. The final
Python comparator asserts both sides independently.

### Official job evidence

| Official job | Job link | Result |
| --- | --- | --- |
| Define outputs | <https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31838835400/job/94891083168> | `success` |
| Fanout `green` | <https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31838835400/job/94891108274> | `success` |
| Fanout `red` | <https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31838835400/job/94891108287> | `success` |
| Direct-needs observation | <https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31838835400/job/94891131361> | `success` |
| Differential comparator | <https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31838835400/job/94891150215> | `success` |

### Exact machine-readable observation

The successful comparator emitted:

```json
{"indieFanoutOutput":"red/hello","indieMatrixJobs":["green","red"],"indieMaxParallel":2,"official":{"defineGreeting":"hello","defineMatrix":{"include":[{"color":"red"},{"color":"green"}]},"defineResult":"success","directOnly":"","fanoutResult":"success","fanoutSeen":"red/hello","observeResult":"success"},"officialMatrixJobs":["green","red"],"schemaVersion":"gha-indie-worker.linux-workflow.v2","workflowConclusion":"success"}
```

The output key `seen` is deliberately shared by both matrix instances. This run
observed `red/hello` last on both engines, but the comparator correctly accepts
either `red/hello` or `green/hello`; GitHub explicitly says matrix completion
order is not guaranteed.

## Comparison matrix

| Behavior | Official observation | Indie observation | Result |
| --- | --- | --- | --- |
| Producer job result | `success` | `success` | Match |
| Promoted greeting output | `hello` | `hello` | Match |
| Promoted matrix JSON | `{"include":[{"color":"red"},{"color":"green"}]}` | Same parsed object | Match |
| Output-selected Linux runner | `ubuntu-24.04` accepted | `ubuntu-24.04` accepted | Match |
| Generated matrix instances | `green`, `red` | `green`, `red` | Match |
| Generated matrix conclusions | both `success` | both `success` | Match |
| Matrix output aggregation | one completion-order `seen` value | one completion-order `seen` value | Match within documented nondeterminism |
| Direct matrix-group result | `success` | `success` | Match |
| Transitive producer in observer `needs` | absent; interpolation is empty | absent; interpolation is empty | Match |
| Observer result | `success` | `success` | Match |
| Workflow result | `success` | `success` | Match |

## Local positive and negative evidence

The implementation adds dedicated tests for:

- planner preservation of declared job outputs and a whole-expression deferred
  matrix;
- direct `needs` propagation through runner labels, job and step environments,
  step source, job outputs, and job `continue-on-error`;
- two-job deferred matrix materialization and per-group concurrency accounting;
- direct-only `needs` visibility;
- rejection of 257 generated instances before any generated consumer starts;
- rejection of manually supplied plans that exceed 1,024 jobs or contain more
  than one deferred template for a base job;
- rejection of `secrets` in a job output expression before any workflow shell
  starts;
- a 1 MiB per-job UTF-16 output estimate;
- a 50 MiB per-workflow UTF-16 output estimate;
- bounded reads of `GITHUB_ENV`, `GITHUB_OUTPUT`, and `GITHUB_PATH` command files;
- rejection of unresolved deferred matrices and shell-derived outputs at the
  separate fixed-profile dispatch boundary.

Validation run locally on the report branch:

```text
cargo test --locked --all-targets
cargo test --locked --no-default-features --lib
cargo clippy --locked --all-targets -- -D warnings
cargo clippy --locked --no-default-features --lib -- -D warnings
Rust 1.97 protocol: cargo test --locked --all-targets
Rust 1.97 protocol: cargo clippy --locked --all-targets -- -D warnings
python3 -m unittest discover -s tests -p 'test_*.py'
```

Observed counts after the report-only resource-bound test was added:

- root Rust all-target suite: 132 tests;
- no-default-feature planner/library suite: 26 tests;
- protocol suite: 16 tests;
- Python suite: 37 tests;
- strict Clippy: clean with default and no-default features, plus the protocol
  crate on Rust 1.97.

## Intentional differences and stricter boundaries

These are deliberate and must not be described as parity failures:

- only trusted, explicitly authorized host execution can enter this scheduler;
- only `run` steps execute; every action and reusable workflow fails closed;
- only a whole-expression deferred matrix is supported, and it must resolve to
  an object; axis-level dynamic expressions are rejected;
- generated matrices are capped at 256 concrete jobs;
- each expression result is capped at 64 KiB, stricter than GitHub's aggregate
  job-output limit;
- output names are restricted to the planner's compatible identifier subset;
- fixed-profile dispatch accepts only already-expanded, output-free plans;
- a resolved deferred value cannot be checked before its producer runs, so an
  invalid value fails after the producer but before any generated consumer;
- runner workspaces remain isolated and empty because checkout and artifact
  actions are not implemented.

## Remaining gaps

This evidence does **not** cover or claim parity for:

- secret contexts, secret redaction, taint tracking, or masking;
- action outputs, composite actions, JavaScript actions, Docker actions, or
  marketplace resolution;
- reusable workflows and cross-workflow outputs;
- job timeouts, concurrency groups, environments, approvals, or deployments;
- artifacts, caches, services, containers, summaries, annotations, or problem
  matchers;
- `github`, `runner`, `strategy`, `vars`, `inputs`, event payload, token, or OIDC
  contexts beyond the explicitly supported subset;
- persistence, crash recovery, leases, reruns, or external cancellation;
- complete GitHub Actions YAML, expression, or lifecycle parity.

## Reproduction

Local differential fixture:

```text
cargo run --quiet --locked --bin gha-linux-workflow -- \
  --workflow tests/fixtures/gha/linux-job-outputs-parity.yml \
  --run-root /tmp/gha-indie-output-parity \
  --max-parallel 2 \
  --allow-host-execution
```

Official evidence is reproducible with the workflow-dispatch entry point in
`.github/workflows/linux-job-outputs-parity.yml`. A future run is evidence for
its own immutable head SHA; it does not retroactively change the observation
recorded here.
