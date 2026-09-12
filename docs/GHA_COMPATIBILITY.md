# GitHub Actions compatibility and security boundary

`gha-indie-worker` is a secure continuity runner for a deliberately bounded GitHub Actions workflow subset. It is **not** yet a drop-in replacement for the hosted GitHub Actions service or the official self-hosted runner.

The compatibility contract is fail-closed: unsupported YAML or execution semantics must produce an explicit planning or validation error. They must never be silently ignored, approximated, or forwarded to a shell.

## Architecture

The repository contains three distinct trust stages:

1. **Strict YAML admission and planning** — rejects ambiguous or excessive YAML, validates the job graph, and deterministically expands supported static matrices.
2. **Immutable reviewed-profile binding** — binds each concrete job to an exact repository commit, reviewed profile digest, catalog digest, plan digest, dependency set, matrix metadata, and content-derived request identity.
3. **Fixed-profile execution** — submits only operator-reviewed profiles to the existing build queue. Workflow YAML cannot select a shell command, container image, Dockerfile, deployment, namespace, executor, or credential.

Keeping these stages separate is intentional. A feature being understood by the planner does not automatically grant it execution authority.

## Compatibility matrix

| Area | Current status | Contract |
| --- | --- | --- |
| Workflow YAML admission | Supported subset | Bounded by source bytes, lines, nesting, node count, jobs, steps, dependencies, runner labels, parameter entries, expanded jobs, copied steps, and estimated plan size. |
| Ambiguous YAML | Rejected | Duplicate keys, merge keys, aliases, anchors, tags, tabs, directives, and multiple documents fail closed before execution policy evaluation. |
| `jobs` and `needs` | Supported | Static job identifiers, unknown-dependency rejection, cycle rejection, deterministic topological order, and downstream skip after failed dependencies. |
| `runs-on` | Partial | Static Linux labels only. Windows, macOS, dynamic labels, runner groups, and caller-selected environments are not executable. |
| Repository revision | Supported | Execution requires an exact lowercase 40-character commit SHA and a policy-approved HTTPS GitHub repository identity. Mutable branches and tags may be planned but are not executable. |
| Steps | Profile classification only | `run` and a small allowlist of setup actions are inspected only to select one installed fixed profile. Caller shell text is never forwarded. |
| Action references | Partial | Recognized setup actions must be pinned to an exact commit SHA. Arbitrary JavaScript, Docker, composite, local, and marketplace actions are not executed. |
| Static matrix | Planner/protocol support | Deterministic axes plus bounded `include`/`exclude` expansion are available in the planner and binding protocol. Matrix jobs are not yet enabled in the fixed-profile HTTP execution path. |
| Job concurrency | Deviation | The current fixed-profile workflow executor runs concrete jobs sequentially in dependency order. GitHub-independent parallel-ready scheduling is future work. |
| Failure propagation | Partial | Required downstream jobs are skipped after dependency failure. `continue-on-error`, `fail-fast`, cancellation propagation, and nuanced conclusion semantics are not executable. |
| Status and retention | Partial | Authenticated submit/list/get APIs, queued/running/succeeded/failed/skipped states, request deduplication, deadlines, and bounded in-memory retention are implemented. Durable workflow-run recovery is future work. |
| Expressions and contexts | Rejected for execution | `${{ ... }}`, contexts, functions, interpolation, dynamic matrices, job outputs, and step outputs are not evaluated. |
| Secrets and identity | Rejected for execution | `secrets`, `github.token`, OIDC request variables, secret-bearing action inputs, and caller-provided credentials are not accepted by workflow execution. |
| Workflow/job/step `env` and defaults | Rejected for execution | Environment mutation and default shell/working-directory behavior are not forwarded. |
| Conditions and timeouts | Rejected for execution | Job/step `if`, custom timeouts, custom shells, working directories, and `continue-on-error` are planner-visible but cannot bind to execution. |
| Reusable workflows | Planner-visible only | Job-level `uses` can be represented by the planner, but reusable workflow invocation and nested permission/secret semantics are not executable. |
| Services and containers | Rejected | Job containers, service containers, container credentials, and port/network lifecycle are outside the current trust boundary. |
| Permissions and tokens | Not implemented | Workflow/job `permissions`, `GITHUB_TOKEN` scoping, fork restrictions, and OIDC claims are not synthesized. |
| Events and filters | Not implemented | `on`, branch/path filters, schedules, manual inputs, repository dispatch, and event payload contexts are not evaluated by the worker. |
| Artifacts and caches | Not implemented | Upload/download artifacts, cache keys, retention, attestations, and provenance publication require a separate content-addressed service. |
| Environments and deployments | Not implemented | Environment approvals, protected deployments, concurrency locks, and deployment status APIs remain outside this worker. |
| Annotations and checks | Not implemented | Check runs, log commands, masks, problem matchers, summaries, annotations, reruns, and cancellation APIs need a GitHub lifecycle adapter. |

## Execution invariants

The following conditions are mandatory for every executable job:

- workflow input passed strict bounded YAML admission;
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

- execute bounded static matrix jobs with dependency-aware parallelism, `max-parallel`, and `fail-fast`;
- support safe job/step conditions over a typed, taint-tracked expression evaluator;
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
