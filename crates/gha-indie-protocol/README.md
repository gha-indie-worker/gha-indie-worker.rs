# gha-indie-protocol

This crate is the fail-closed handoff between the bounded workflow planner and the independent fixed-profile worker.

It does **not** translate arbitrary GitHub Actions commands or third-party actions into worker execution. A dispatchable workflow plan must contain only graph, runner-label, and static scalar-matrix metadata. Each base job is then bound by an operator-reviewed document to:

- one HTTPS repository URL;
- one exact 40-character lowercase commit SHA;
- one installed Linux profile from a canonical catalog;
- the exact SHA-256 digest of that catalog and profile;
- one repository-relative context directory.

The resulting dispatch batch contains deterministic plan, catalog, and per-request digests plus concrete dependency-instance identities. It contains no shell source, action reference, caller environment, reusable workflow, condition, timeout, secret, or mutable Git reference.

## CLI

```text
gha-bind-plan <plan.json> <profile-catalog.json> <bindings.json>
```

Input schemas:

- `gha-indie-worker.plan.v1`
- `gha-indie-worker.profile-catalog.v1`
- `gha-indie-worker.bindings.v1`

Output schema:

- `gha-indie-worker.dispatch-batch.v1`

The CLI limits plan input to 4 MiB and catalog/binding inputs to 512 KiB each. Validation failures are emitted as structured JSON on stderr with exit status 2.

## Compatibility boundary

This crate establishes identity and policy binding. It intentionally does not choose an executor, retry an ambiguous submission, evaluate expressions, resolve actions, inject workflow environment, or execute a repository command. Durable assignment and exact detached-SHA checkout remain separately reviewable runtime boundaries.
