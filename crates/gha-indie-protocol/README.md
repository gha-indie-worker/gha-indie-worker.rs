# gha-indie-protocol

This crate is the fail-closed handoff between the bounded workflow planner and independent fixed-profile workers.

It does **not** translate arbitrary GitHub Actions commands or third-party actions into worker execution. A dispatchable workflow plan must contain only graph, runner-label, and static scalar-matrix metadata. Each base job is then bound by an operator-reviewed document to:

- one HTTPS repository URL;
- one exact 40-character lowercase commit SHA;
- one installed profile from a canonical catalog;
- the exact SHA-256 digest of that catalog and profile;
- one explicit runner platform and architecture;
- a bounded set of reviewed host capabilities;
- one repository-relative context directory.

The supported platform identifiers are `linux`, `macos`, and `windows`. The supported architecture identifiers are `x64` and `arm64`. Native macOS and Windows profiles must declare the `native` capability so they cannot be confused with a container or compatibility-layer execution path.

A planned job must select `self-hosted`, `gha-indie-worker`, exactly one supported platform label, and exactly one supported architecture label. Binding fails closed when those labels do not exactly match the selected profile. Additional capabilities such as `xcode`, `ios-simulator`, `msvc`, `windows-sdk`, or `hyper-v` remain operator-reviewed profile metadata; workflow YAML cannot grant them.

The resulting dispatch batch contains deterministic plan, catalog, and per-request digests plus concrete dependency-instance identities and the exact normalized runner target. It contains no shell source, action reference, caller environment, reusable workflow, condition, timeout, secret, mutable Git reference, generic image selection, or deployment authority.

## CLI

```text
gha-bind-plan <plan.json> <profile-catalog.json> <bindings.json>
```

Input schemas:

- `gha-indie-worker.plan.v1`
- `gha-indie-worker.profile-catalog.v2`
- `gha-indie-worker.bindings.v1`

Output schemas:

- `gha-indie-worker.dispatch-batch.v2`
- `gha-indie-worker.dispatch.v2`

A profile record has this shape:

```json
{
  "name": "macos-xcode",
  "digest": "sha256:...",
  "runner": {
    "platform": "macos",
    "architecture": "arm64",
    "capabilities": ["native", "xcode", "ios-simulator"]
  }
}
```

Capability order is normalized before catalog and request digests are calculated. Duplicate, malformed, excessive, or unsupported target metadata is rejected.

The CLI limits plan input to 4 MiB and catalog/binding inputs to 512 KiB each. Validation failures are emitted as structured JSON on stderr with exit status 2.

## Compatibility boundary

This crate establishes identity, native-host routing, and policy binding. It intentionally does not choose a concrete machine, enroll a host, issue a lease, retry an ambiguous submission, evaluate expressions, resolve actions, inject workflow environment, or execute a repository command. Secure enrollment, exact capability matching, durable assignment, isolated execution, cleanup, quarantine, and exact detached-SHA checkout remain separately reviewable runtime boundaries.
