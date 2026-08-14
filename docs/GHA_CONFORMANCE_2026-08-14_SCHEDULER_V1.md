# GitHub Actions conformance report: trusted Linux workflow scheduler v1

Date: 2026-08-14

Implementation schema: `gha-indie-worker.linux-workflow.v1`

Result: **PASS for the bounded scheduler slice described below**

This report does not claim complete GitHub Actions compatibility. It records a
reproducible comparison between GitHub's official Ubuntu-hosted execution and
the separate, explicitly authorized `gha-linux-workflow` CLI. Production webhook
and HTTP intake remain unable to execute workflow-controlled shell source.

## Immutable test identity

- Implementation head: [`9823fe923c72e4a177e80e6f863b81f36166aab9`](https://github.com/gha-indie-worker/gha-indie-worker.rs/commit/9823fe923c72e4a177e80e6f863b81f36166aab9)
- Implementation tree: `9dd0428e075ba181575d769f35525d9511505345`
- GitHub pull-request test merge: `52ea3b49e50072367924b6dd3d4be674a581ab95`
- Test-merge tree: `9dd0428e075ba181575d769f35525d9511505345`
- Pull request: [#31](https://github.com/gha-indie-worker/gha-indie-worker.rs/pull/31)
- Passing official workflow run: [31835619737](https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31835619737)
- Passing differential job: [94881266968](https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31835619737/job/94881266968)
- Official tolerated-failure job: [94881156171](https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31835619737/job/94881156171)

The tested pull-request merge tree is byte-identical to the implementation-head
tree. The report itself is an evidence-only follow-up and is not part of the
implementation tree identified above.

## Reference contract

The asserted behavior is derived from GitHub's current primary documentation:

- [matrix job variations, failure handling, and maximum parallelism](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/run-job-variations)
- [workflow syntax for `needs`, job conditions, strategy, and job-level `continue-on-error`](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
- [`needs` context result semantics](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#needs-context)
- [status-check functions and implicit `success()` conditions](https://docs.github.com/en/actions/reference/workflows-and-actions/expressions#status-check-functions)

The official matrix documentation states two rules central to this slice:

1. `strategy.max-parallel` limits how many matrix instances may run at once.
2. A failed matrix instance with `continue-on-error: true` does not affect its
   siblings, while a failed non-tolerated instance triggers `fail-fast`.

## Differential design

The checked-in oracle is
`.github/workflows/linux-workflow-scheduler-parity.yml`; the paired indie input
is `tests/fixtures/gha/linux-workflow-scheduler-parity.yml`.

Both sides execute three static matrix instances:

| Label | `continue-on-error` | Command result |
| --- | ---: | --- |
| `stable-one` | `false` | success |
| `experimental` | `true` | failure |
| `stable-two` | `false` | success |

Both use `fail-fast: true` and `max-parallel: 2`. The official commands sleep for
five seconds so overlap can be measured from the Actions jobs API timestamps.
The indie trace contains an independent per-group maximum-observed counter.

The graph also includes:

- a default downstream job needing the matrix aggregate;
- a job with `if: false`;
- a recovery job using
  `always() && needs.skip_job.result == 'skipped'`.

The differential job reads official results with its restricted `GITHUB_TOKEN`
(`actions: read`, `contents: read`), runs the paired fixture through the indie
scheduler, and performs executable assertions across both traces.

The immutable observation below used job-level `continue-on-error`, which made
GitHub expose the tolerated experimental concrete job as a red check even though
the matrix aggregate and workflow succeeded. The recurring pull-request gate now
places the same tolerance on the failing step: it compares GitHub's failed step
`outcome` context with the indie raw outcome, and GitHub's successful step and
job conclusions with the indie effective conclusion. This keeps every configured
gate green without erasing the historical job-level observation or weakening the
runner's job-level fixture.

## Exact passing observation

The final comparator emitted this deterministic JSON object:

```json
{"indieMaxParallel":2,"matrix":{"experimental":{"indieConclusion":"failure","indieEffectiveConclusion":"success","indieOutcome":"failure","officialJobConclusion":"failure","officialStepConclusion":"failure"},"stable-one":{"indieConclusion":"success","indieEffectiveConclusion":"success","indieOutcome":"success","officialJobConclusion":"success","officialStepConclusion":"success"},"stable-two":{"indieConclusion":"success","indieEffectiveConclusion":"success","indieOutcome":"success","officialJobConclusion":"success","officialStepConclusion":"success"}},"officialMaxParallel":2,"officialNeeds":{"after_matrix":"success","matrix_job":"success","recover_skip":"success","skip_job":"skipped"},"schemaVersion":"gha-indie-worker.linux-workflow.v1","workflowConclusion":"success"}
```

Whitespace differs from the log's `json.dumps` rendering; keys and values are
identical.

## What the official differential proves

For this exact fixture and implementation tree:

1. All three bounded static matrix instances were created and executed.
2. Official and indie stable-instance command outcomes and concrete job
   conclusions were `success`.
3. The experimental command failed on both systems.
4. GitHub reported the experimental concrete job conclusion as `failure`.
   Indie now reports the same concrete conclusion and separately reports its
   effective conclusion as `success` for dependency and workflow aggregation.
5. The tolerated experimental failure did not trigger matrix fail-fast; the
   third instance ran successfully.
6. Both systems observed a maximum of exactly two simultaneous matrix jobs.
7. The matrix aggregate exposed through official `needs` was `success`, and the
   default downstream job ran successfully. The indie group aggregate matched.
8. Both systems produced `skipped` for the false job condition.
9. Both systems ran the explicit `always()` recovery job and exposed the skipped
   dependency result correctly.
10. The final workflow conclusion was `success`.

## Oracle-driven correction

The first official calibration run
[31835336364](https://github.com/gha-indie-worker/gha-indie-worker.rs/actions/runs/31835336364)
failed its comparator because the initial indie model changed a tolerated
concrete job conclusion to `success`. GitHub's jobs API demonstrated that the
concrete conclusion remains `failure`; tolerance instead changes aggregate and
workflow behavior. Commit `9823fe9` introduced separate concrete and effective
conclusions and the second differential passed. This failed-first calibration is
retained as evidence that the oracle changed the implementation rather than
merely confirming a preselected expectation.

## Local positive and negative evidence

The final implementation head passed:

- **122** default-feature Rust tests across all targets:
  - 41 library tests, including 6 scheduler tests;
  - 75 service tests;
  - 1 single-job Linux CLI test;
  - 2 workflow scheduler CLI tests;
  - 3 planner CLI tests.
- **27** execution-free `--no-default-features` planner/library tests.
- strict Clippy with warnings denied for all targets/all features.
- strict Clippy with warnings denied for the no-default planner surface.
- **16** immutable binding protocol tests with Rust 1.97.0.
- **37** Python policy, native-fleet, and worker-handoff tests.

The scheduler-specific local suite covers behavior that is intentionally not
misrepresented as official differential evidence:

- a non-tolerated failure cancels queued matrix siblings;
- a non-tolerated failure aborts an already running sibling process;
- job processes use `kill_on_drop`, and the cancelled sibling cannot finish its
  post-sleep write;
- an operator-wide concurrency ceiling composes with per-matrix
  `max-parallel`;
- an ordinary failed dependency skips a default child while an explicit
  `failure()` handler runs;
- every concrete job receives a distinct empty workspace;
- any unsupported action in any concrete job rejects the entire plan before the
  first job's shell source starts;
- unsupported job condition contexts and job timeouts fail closed.

Every workflow triggered on implementation head `9823fe9` completed
successfully: the scheduler differential, workflow planner, standalone worker,
Linux runner differential, Linux expression differential, and full-history
secret scan.

## Supported v1 scheduler contract

The trusted scheduler currently supports:

- planner-validated, acyclic static job graphs;
- bounded static matrix axes and `include`/`exclude` expansion inherited from
  the planner;
- deterministic ready-job selection;
- isolated empty workspaces for concrete jobs;
- an explicit operator-wide maximum job count;
- per-matrix `strategy.max-parallel`;
- `strategy.fail-fast` cancellation of queued siblings and abortion of running
  siblings;
- boolean or whole-expression job `continue-on-error`, with the expression
  restricted to the static `matrix` context;
- job conditions over direct `needs` results and status-check functions;
- implicit `success()` gating when no status-check function is present;
- `needs.<job>.result` aggregation across static matrix instances;
- a deliberately empty `needs.<job>.outputs` object;
- explicit concrete outcome, concrete conclusion, effective conclusion, start
  and completion sequence, group aggregate, and observed concurrency results;
- complete-plan preflight before workflow shell execution;
- explicit `--allow-host-execution` authorization.

## Intentional differences and remaining gaps

This passing report does **not** prove or claim parity for:

- action execution of any kind, including checkout, JavaScript, Docker,
  composite, local, and marketplace actions;
- reusable workflows;
- dynamic matrices generated from expressions or prior-job outputs;
- job outputs or step-level access to `needs`;
- job-level timeouts;
- `github`, `inputs`, `vars`, `secrets`, `strategy`, `runner`, or event contexts
  at the scheduler boundary;
- job/service containers, networking, or service health checks;
- artifacts, caches, summaries, annotations, masks, problem matchers, or check
  runs;
- `GITHUB_TOKEN`, OIDC, permissions, fork policy, environments, approvals, or
  deployments;
- concurrency groups, external cancellation, retries, reruns, persistence,
  leases, or crash recovery;
- repository materialization. Each workspace starts empty because checkout is
  still an unsupported action;
- Windows or macOS execution;
- integration of arbitrary workflow shell execution into production intake.

## Reproduction

Local supported-subset execution requires an explicit trust decision:

```text
cargo run --locked --bin gha-linux-workflow -- \
  --workflow tests/fixtures/gha/linux-workflow-scheduler-parity.yml \
  --run-root /an/operator-controlled/run-root \
  --max-parallel 2 \
  --allow-host-execution
```

The exact independent comparison is reproduced by dispatching
`.github/workflows/linux-workflow-scheduler-parity.yml` at the implementation
commit. A valid result must retain the schema identifier, three label
observations, official and indie maximum parallelism of two, the exact `needs`
result map, and final success shown above. The current recurring gate retains
those assertions but uses the green-check projection described under
"Differential design".

## Claim boundary

The defensible claim is:

> `gha-indie-worker.linux-workflow.v1` matches GitHub's observed behavior for the
> versioned static-matrix/dependency fixture in this report and passes the
> listed local fail-fast, isolation, preflight, and negative tests.

It is not yet accurate to call the project a drop-in or complete GitHub Actions
replacement.
