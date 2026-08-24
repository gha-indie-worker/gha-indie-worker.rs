# GitHub Actions compatibility and security boundary

`gha-indie-worker` is a secure continuity runner for a deliberately bounded GitHub Actions workflow subset. It is **not** yet a drop-in replacement for the hosted GitHub Actions service or the official self-hosted runner.

The compatibility contract is fail-closed: unsupported YAML or execution semantics must produce an explicit planning or validation error. They must never be silently ignored, approximated, or forwarded to a shell.

## Architecture

The repository contains five distinct trust stages:

1. **Strict YAML admission and planning** — rejects ambiguous or excessive YAML, validates the job graph, deterministically expands supported static matrices, and preserves bounded whole-expression matrices for dependency-time resolution.
2. **Immutable reviewed-profile binding** — binds each concrete job to an exact repository commit, reviewed profile digest, catalog digest, plan digest, dependency set, matrix metadata, and content-derived request identity.
3. **Fixed-profile execution** — submits only operator-reviewed profiles to the existing build queue. Workflow YAML cannot select a shell command, container image, Dockerfile, deployment, namespace, executor, or credential.
4. **Explicitly trusted Linux conformance execution** — the separate `gha-linux-runner` CLI can execute the bounded `run`-step subset only when a human or higher-level policy supplies `--allow-host-execution`. It is not connected to webhook or HTTP intake and it does not weaken fixed-profile admission.
5. **Explicitly trusted Linux workflow scheduling** — the separate `gha-linux-workflow` CLI composes that same bounded executor across a validated job graph, including direct dependency outputs and output-driven matrix expansion. It creates one empty workspace per concrete job and requires the same explicit host-execution capability.

Keeping these stages separate is intentional. A feature being understood by the planner does not automatically grant it execution authority.

## Compatibility matrix

| Area | Current status | Contract |
| --- | --- | --- |
| Workflow YAML admission | Supported subset | Bounded by source bytes, lines, nesting, node count, jobs, steps, dependencies, runner labels, parameter entries, expanded jobs, copied steps, and estimated plan size. Boolean and numeric YAML scalars in `if` and `run` are normalized to their GitHub command/expression strings. |
| Ambiguous YAML | Rejected, including in production fixed-profile planning | Duplicate keys, merge keys, aliases, anchors, tags, tabs, directives, and multiple documents fail closed before execution policy evaluation. GitHub supports some YAML reuse features, but this worker intentionally rejects them until bounded expansion and differential evidence exist. |
| `jobs` and `needs` | Trusted scheduler supported subset | Static job identifiers, unknown-dependency rejection, cycle rejection, deterministic topological order, dependency-result aggregation, GitHub's implicit-success downstream skip, explicit status-function recovery, declared job outputs, and direct-only `needs.<job>.outputs` are supported. Transitive dependencies are intentionally absent from `needs`. |
| `runs-on` | Partial | Static Linux labels and Linux labels resolved from the supported `matrix`, `env`, and direct `needs` contexts are executable by the trusted CLI. Windows, macOS, runner groups, and caller-selected environments are not executable. |
| Repository revision | Supported | Execution requires an exact lowercase 40-character commit SHA and a policy-approved HTTPS GitHub repository identity. Mutable branches and tags may be planned but are not executable. |
| Steps | Production profile classification; trusted CLI partial | Production intake classifies `run` plus a small setup-action allowlist into installed profiles, then rejects shell composition, redirection, publishing/deployment commands, scripts, and any command not represented by that profile's reviewed command surface. The fixed profile remains a reviewed coverage operation rather than byte-for-byte shell execution. The explicit trusted Linux CLI executes `run` steps in separate processes with a shared workspace and fails closed on every `uses` step. |
| Action references | Partial | Recognized setup actions must be pinned to an exact commit SHA. Arbitrary JavaScript, Docker, composite, local, and marketplace actions are not executed. |
| Matrix | Trusted scheduler supported subset | Deterministic static axes plus bounded `include`/`exclude` expansion are available. A whole-expression matrix may resolve from direct dependency outputs after those jobs finish, including `fromJSON(needs.<job>.outputs.<name>)`. Expansion remains capped at 256 instances, and `max-parallel` applies to the generated group. Axis-level dynamic expressions and fixed-profile dispatch of deferred matrices remain rejected. |
| Job concurrency | Trusted scheduler supported subset | Dependency-ready jobs run concurrently up to an operator-wide ceiling, while each matrix group independently honors `strategy.max-parallel`. The fixed-profile HTTP workflow executor remains sequential. Concurrency groups and external cancellation APIs are not implemented. |
| Failure propagation | Trusted scheduler supported subset | Step and job outcome remain distinct from conclusion. Boolean or static-matrix expression-valued job `continue-on-error` tolerates a failed instance. A non-tolerated failure triggers matrix `fail-fast`, cancels queued siblings, and aborts in-progress sibling processes; a tolerated failure does not. Direct dependants receive aggregate `needs.<job>.result` and GitHub-compatible implicit-success gating. |
| Status and retention | Partial | Authenticated submit/list/get APIs, queued/running/succeeded/failed/skipped states, request deduplication, deadlines, and bounded in-memory retention are implemented. Durable workflow-run recovery is future work. |
| Expressions and contexts | Production rejected; trusted CLI typed subset | Fixed-profile execution rejects expressions. The trusted Linux CLI evaluates bounded typed expressions over explicitly supplied `matrix`, `env`, direct `needs`, and completed prior-step contexts. It supports documented literals, property/index access, logical and comparison operators, loose equality, status checks, and the versioned pure-function set. All other contexts/functions fail closed; secret taint and the broader production context model remain unimplemented. |
| Secrets and identity | Rejected for execution | `secrets`, `github.token`, OIDC request variables, secret-bearing action inputs, and caller-provided credentials are not accepted by workflow execution. |
| Workflow/job/step `env` and defaults | Production rejected; trusted CLI partial | The trusted Linux CLI supports scalar workflow/job/step environment precedence, direct dependency outputs in downstream job/step environments, step-scoped `env` in conditions and `continue-on-error`, subsequent-step `GITHUB_ENV` updates, workflow/job `defaults.run`, and explicit step overrides. Fixed-profile environment forwarding remains unsupported. |
| Conditions and timeouts | Production rejected; trusted CLI partial | The trusted Linux CLIs support typed step conditions, explicit status checks, GitHub's implicit `success()` gate, bounded step timeouts, workspace-contained working directories, default Bash, explicit Bash, and `sh`. The workflow scheduler additionally supports job conditions over direct `needs` results and status functions. Job-level timeout, broader job contexts, and fixed-profile HTTP execution remain rejected rather than ignored. |
| Reusable workflows | Planner-visible only | Job-level `uses` can be represented by the planner, but reusable workflow invocation and nested permission/secret semantics are not executable. |
| Services and containers | Rejected | Job containers, service containers, container credentials, and port/network lifecycle are outside the current trust boundary. |
| Permissions and tokens | Not implemented | Workflow/job `permissions`, `GITHUB_TOKEN` scoping, fork restrictions, and OIDC claims are not synthesized. |
| Events and filters | Admission audit only | Production fixed-profile planning accepts only unfiltered `push`, `pull_request`, and `workflow_dispatch` declarations. Unsupported or elevated-trust events, schedules, branch/path/activity filters, and manual inputs make a plan non-executable. Trigger declarations are reported in the plan but are not matched by the explicit API; an authenticated upstream dispatcher must prove event, ref, and path eligibility. The trusted CLI does not evaluate `on`. |
| Artifacts and caches | Not implemented | Upload/download artifacts, cache keys, retention, attestations, and provenance publication require a separate content-addressed service. |
| Environments and deployments | Not implemented | Environment approvals, protected deployments, concurrency locks, and deployment status APIs remain outside this worker. |
| Annotations and checks | Not implemented | Check runs, log commands, masks, problem matchers, summaries, annotations, reruns, and cancellation APIs need a GitHub lifecycle adapter. |

## Versioned trusted Linux runner evidence

### Linux runner v1

The `gha-indie-worker.linux-runner.v1` contract is intentionally smaller than GitHub Actions. Its parity claim is limited to the behavior exercised by `tests/fixtures/gha/linux-runner-parity.yml` and `.github/workflows/linux-runner-parity.yml`:

- each `run` step starts in a separate process while the workspace persists;
- workflow environment is overridden by job environment and then step environment;
- single-line and delimiter-based multiline writes to `GITHUB_ENV` affect later steps but not the writing step;
- single-line and delimiter-based multiline writes to `GITHUB_OUTPUT` become scalar `steps.<id>.outputs.*` values;
- writes to `GITHUB_PATH` prepend executable search paths for later steps;
- workflow and job `defaults.run` are merged per field and explicit step shell/working-directory values take precedence;
- an inherited `shell: sh` runs with `-e`, while a step override of `shell: bash` enables `--noprofile --norc -eo pipefail`;
- a failed `continue-on-error: true` step keeps `outcome: failure` and changes `conclusion` to `success`;
- default/success, failure, always, cancelled, and not-cancelled status conditions use the current job status;
- working directories must already exist and remain inside the canonical workspace;
- matrix, environment, and prior-step-output scalar interpolation is bounded and fail-closed;
- step output size and execution time are bounded.
- unsupported job-level conditions, timeouts, `continue-on-error`, reusable workflows, and `with` inputs on `run` steps fail closed during preflight before any step executes.

The differential workflow executes an equivalent oracle sequence directly on GitHub's official Ubuntu runner, executes the fixture through `gha-linux-runner`, compares the resulting workspace bytes, and compares each observed step outcome/conclusion. A passing differential job proves this listed slice only. It does not prove action-runtime, service-container, token, event, artifact, cache, deployment, or complete expression parity.

### Linux runner v2 expressions

The additive `gha-indie-worker.linux-runner.v2` contract is recorded in
`docs/GHA_CONFORMANCE_2026-08-14_EXPRESSION_V2.md`. Its independent
official-runner differential proves the following combined fixture surface:

- scalar YAML normalization for the observed unquoted `run: false` command;
- typed boolean, string, number, missing-property, matrix, environment, and
  prior-step-result values;
- property and bracket indexing;
- loose numerical equality/comparison and case-insensitive ASCII string
  comparison;
- short-circuit operand-valued `&&` and `||`;
- `contains`, `startsWith`, `endsWith`, `format`, `join`, and `fromJSON` in the
  observed compositions;
- expression-valued step `continue-on-error` and subsequent
  `outcome`/`conclusion` inspection;
- false typed conditions producing matching skipped states.

Local positive and negative tests additionally cover the documented v2 parser
surface, `toJSON`, hexadecimal and exponential literals, implicit `success()`
after an ordinary failure, input/resource limits, and whole-job rejection of
unavailable contexts and unsupported functions. Those local cases are not
misrepresented as independent differential evidence.

The v2 evaluator still rejects every unlisted context or function. In
particular, it has no secret context, taint propagation, `hashFiles`, object
filters, or dynamic matrices. The scheduler supplies a deliberately narrow
direct-`needs` context for job conditions; it does not make `needs` available to
step expressions or implement job outputs.

### Linux workflow scheduler v1

The additive `gha-indie-worker.linux-workflow.v1` contract is recorded in
`docs/GHA_CONFORMANCE_2026-08-14_SCHEDULER_V1.md`. Its paired fixture and
official-hosted differential cover:

- execution of all instances from a bounded static `matrix.include`;
- per-matrix `max-parallel: 2`, measured from official job timestamps and from
  the indie scheduler's own maximum-observed counter;
- expression-valued job `continue-on-error` using a static matrix boolean;
- preservation of the failed experimental instance's raw outcome and concrete
  job conclusion while its explicit effective conclusion and matrix aggregate
  remain successful;
- non-triggering of `fail-fast` by that tolerated instance;
- default execution of a job that needs the successful matrix aggregate;
- a false job condition producing `skipped`, followed by an `always()` job that
  inspects `needs.<job>.result` and recovers successfully.

Local positive and negative tests additionally prove queued and in-progress
fail-fast cancellation, operator-wide concurrency limits, ordinary dependency
failure and recovery, isolated workspaces, and whole-plan rejection before any
shell starts. Those cases are labeled local rather than official differential
evidence.

That v1 scheduler contract rejects actions, reusable workflows, job timeouts, dynamic
matrices, job outputs, step-level `needs`, services, containers, secrets, event
contexts, artifacts, caches, and persistence/recovery. Its workspaces start
empty; repository and artifact material appears only when a future explicitly
supported, immutable action supplies it.

### Linux runner v3 and workflow scheduler v2 output dataflow

The additive `gha-indie-worker.linux-runner.v3` and
`gha-indie-worker.linux-workflow.v2` contracts are recorded in
`docs/GHA_CONFORMANCE_2026-08-14_OUTPUT_DATAFLOW_V1.md`. Their paired fixture and
official-hosted differential cover:

- step outputs explicitly promoted through a job `outputs` mapping;
- downstream `needs.<job>.result` and `needs.<job>.outputs` values in runner
  selection, job and step environments, and step templates;
- GitHub's direct-dependency-only `needs` shape, including an empty lookup for
  a transitive dependency;
- a whole-expression matrix produced by
  `fromJSON(needs.<job>.outputs.<name>)` after the producer finishes;
- two concrete generated matrix jobs and per-group `max-parallel: 2`;
- matrix job-output aggregation, with duplicate keys intentionally permitted to
  follow completion order just as GitHub documents completion order as
  nondeterministic.

Local positive and negative tests additionally cover use of `needs` in job
`continue-on-error`, the 256-instance deferred matrix bound, whole-workflow
output accounting, and pre-shell rejection of secret-context job outputs. Job
outputs are estimated as UTF-16 and bounded to 1 MiB per concrete job and
50 MiB per workflow. The expression engine's 64 KiB per-value ceiling is a
deliberately stricter resource boundary, and every parsed environment, output,
or path command file is bounded by the runner output policy.

The v2 scheduler accepts only a whole-expression dynamic matrix that resolves
to an object. It does not support axis-level dynamic expressions, `secrets`,
secret taint/masking, environment files shared across jobs, artifacts, actions,
reusable workflows, job timeouts, persistence, or crash recovery. Syntax and
context availability are preflighted before any shell starts, but a deferred
matrix's resolved value and size can only be validated after its producer has
finished; a bad value fails closed before any generated consumer starts.

## Execution invariants

The following conditions are mandatory for every production fixed-profile job. The trusted conformance CLI has a separate explicit-authorization boundary described above and is not a production intake path:

- workflow input passed strict bounded YAML admission;
- the trigger declaration is inside the independent-worker policy and contains no unevaluated filters or inputs;
- repository identity and context path are canonical and traversal-free;
- revision is immutable;
- dependency graph is complete and acyclic;
- selected profile exists in the local reviewed catalog;
- profile, catalog, plan, and request digests match;
- no caller-selected command, action implementation, image, Dockerfile, build argument, deploy request, namespace, secret, or executor crosses the binding boundary;
- the normal build-server policy validates the generated request again before enqueueing it.

## Parity roadmap

### P0 — trustworthy continuity lane

- make strict YAML admission the only executable parser path;
- preserve immutable plan/profile/request digests through durable storage;
- expose GitHub check-run status, cancellation, retries, logs, and annotations;
- add crash recovery and lease-based ownership for queued/running workflow jobs;
- run the official compatibility corpus plus adversarial YAML and expression fixtures.

### P1 — useful static workflow parity

- connect the bounded static scheduler to durable leases, cancellation APIs,
  immutable source material, and the production admission boundary;
- extend the bounded typed evaluator with per-field context availability,
  secret taint propagation, masking, and safe job/step scheduler integration;
- support outputs without exposing secret-tainted values;
- implement content-addressed artifacts and caches with quotas and retention;
- support reusable workflows only after immutable resolution, recursion limits, permission narrowing, and digest binding.

### P2 — broader runner compatibility

- isolated JavaScript, Docker, composite, and local actions pinned by immutable digest;
- service/job containers with reviewed network and credential policy;
- environment protection, deployment lifecycle, concurrency groups, and event-trigger evaluation;
- Windows and macOS backends with equivalent sandboxing and lifecycle behavior;
- full differential testing against GitHub-hosted and official self-hosted runners.

## Release claim policy

Do not label a release “GitHub Actions compatible” without a versioned compatibility report. Claims must name the supported syntax and lifecycle surface, list intentional deviations, provide differential-test evidence, and state the sandbox and credential model. Until then, describe the project as a **secure bounded GitHub Actions continuity worker**.
