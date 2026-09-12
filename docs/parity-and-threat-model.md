# GitHub Actions parity and threat model

`gha-indie-worker` is intended to become a compatible implementation of the GitHub Actions execution contract. Compatibility is an evidence claim, not a repository description. The checked-in catalog therefore starts with both `full_parity` and `production_drop_in_replacement` set to `false`, and every feature starts as `unverified` until a real-GitHub execution and an exact-head clone execution produce matching normalized traces for positive and adversarial cases.

## What parity means

A feature is `supported` only when all of the following are true:

1. The feature has a deterministic positive workflow fixture.
2. The feature has an adversarial fixture that exercises its failure or trust boundary.
3. Both fixtures ran against a recorded GitHub Actions reference revision and the exact `gha-indie-worker` revision under review.
4. The reference and clone traces match after the repository's bounded normalization rules.
5. The trace files, workflow SHA, runner version, platform, and SHA-256 evidence are checked in and accepted by the parity catalog audit.
6. Any platform-specific promise is proven separately on that platform.

A passing unit test for an internal function is useful, but it is not sufficient evidence of GitHub Actions compatibility. Likewise, accepting a workflow file is not enough if expression coercion, context timing, file commands, failure propagation, cleanup, token permissions, or result reporting differ.

## Catalog statuses

- `unverified`: no accepted runtime comparison evidence exists.
- `partial`: some evidence exists, but the required positive/adversarial pair is incomplete.
- `supported`: matched GitHub and clone evidence exists for both cases.
- `blocked`: a concrete blocker is recorded in the feature entry.

`tools/audit_gha_parity.py` contains a mandatory feature set. Removing a difficult feature from the JSON catalog does not make the parity claim easier: CI fails because the feature is missing. A full-parity claim also fails unless every required feature is `supported`.

## Reference execution model

Reference runs should occur in a dedicated, low-privilege conformance repository rather than a production repository. The harness should:

1. copy the selected fixture into `.github/workflows/` at an exact commit;
2. stage any fixture dependencies, such as the called reusable workflow, at that same commit;
3. dispatch the workflow with non-secret test inputs;
4. capture workflow, job, step, annotation, output, and cleanup events into `gha-indie-worker.trace.v1`;
5. record the GitHub workflow commit, GitHub runner version, platform, and immutable trace digest;
6. execute the same fixture against the exact clone commit and capture the same event vocabulary;
7. compare the traces with `tools/compare_gha_traces.py`;
8. promote a feature only after review of both evidence sets.

The reference repository should use `permissions: contents: read` by default. Fixtures that test elevated permissions, OIDC, environments, or fork behavior need purpose-built ephemeral repositories and credentials. They must not borrow a production token or production environment secret.

## Trace comparison

The comparator accepts a trace object, a JSON array of events, or JSON Lines. It preserves event order, conclusions, statuses, outputs, context values, and nested semantic data. It removes only known capture noise such as timestamps, transport IDs, runner names, and sequence numbers, and it normalizes explicitly supplied workspace/temp roots plus standard GitHub-hosted paths.

The comparator does not print differing values. A mismatch report contains event names, JSON-pointer paths, and SHA-256 fingerprints. This keeps CI diagnostics useful without echoing a secret, untrusted pull-request payload, command output, or generated token.

Service ports, random delimiters, temporary filenames, and other intentionally variable values should be captured as semantic assertions—for example, `reachable: true`—rather than placed in a broad ignore list. Normalization must not be expanded merely to make a failing comparison pass.

## Threat model

The clone executes repository-controlled code and must assume that workflow YAML, expressions, action metadata, archives, container images, cache entries, artifacts, log commands, and event payloads are hostile.

### Control-plane threats

- forged, replayed, duplicated, reordered, or expired runner-service messages;
- assignment races and lease loss;
- incorrect retry or acknowledgement behavior;
- unbounded payloads, logs, artifacts, caches, or expression evaluation;
- stale-job completion overwriting a newer attempt;
- mutable action references resolving differently between planning and execution.

### Execution-plane threats

- command, expression, shell, path, or environment injection;
- workflow-command injection through untrusted output;
- secret disclosure through logs, annotations, summaries, subprocess arguments, crash reports, or structured telemetry;
- workspace, process, network, credential, or filesystem leakage between jobs;
- malicious Docker entrypoints, service containers, and cleanup hooks;
- symlink, hardlink, device-file, zip-slip, and decompression-bomb attacks in actions, caches, and artifacts;
- cancellation or timeout paths that skip revocation and cleanup;
- differences in Windows, macOS, Linux, architecture, path, quoting, and shell behavior.

### Authorization threats

- granting a broader `GITHUB_TOKEN` than the event and workflow permit;
- failing to downgrade permissions for untrusted fork or pull-request contexts;
- exposing environment secrets before protection rules and approvals complete;
- issuing OIDC tokens with incorrect claims, audience, subject, lifetime, or replay controls;
- confusing repository, organization, environment, runner-group, or installation ownership.

## Initial conformance fixtures

The first fixture set covers:

- expression functions, JSON coercion, contexts, matrix include/exclude, job outputs, and `needs`;
- tolerated and hard failures plus `success()`, `failure()`, `always()`, and `cancelled()` behavior;
- `GITHUB_ENV`, `GITHUB_OUTPUT`, `GITHUB_PATH`, step summaries, annotations, masking, and stop-commands;
- job containers, service containers, health checks, service DNS, and dynamic port contexts;
- reusable-workflow inputs, optional secrets, outputs, and caller dependencies.

These fixtures create test inputs; their presence does not mark the associated catalog entries as supported.

## Priority implementation order

1. Secure runner-service protocol, idempotent assignment, cancellation, bounded input, and job isolation.
2. Workflow parsing, expression semantics, contexts, `needs`, matrix expansion, conditions, and result propagation.
3. Shell execution, file commands, annotations, masking, timeouts, and cleanup.
4. JavaScript, composite, Docker, remote, and reusable actions with immutable resolution.
5. Containers, services, caches, artifacts, environments, token permissions, and OIDC.
6. Linux, macOS, and Windows platform matrices with versioned reference evidence.

The catalog should remain conservative throughout this sequence. `GHA_REQUIRE_FULL_PARITY=true` is an explicit release gate and is expected to fail until the entire mandatory catalog is proven.
