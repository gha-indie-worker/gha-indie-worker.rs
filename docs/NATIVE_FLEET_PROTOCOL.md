# Native fleet enrollment, scheduling, and lease protocol

This slice implements the executable laboratory contract for **DEN-2583** on top of the typed `gha-indie-worker.dispatch.v2` runner target introduced by PR #14.

## Boundaries

The implementation is a dependency-free protocol simulator and validator. It proves state-machine, matching, replay, and lease semantics on Linux, Windows, and macOS reference runners. It does not claim that physical machines, production mTLS, platform secure storage, OS sandboxes, or reimage pipelines are already provisioned.

Production agents must replace the simulator HMAC with a platform-bound asymmetric device identity. The capability envelope, payload digest, expiry, identity binding, one-use bootstrap, rotation, revocation, and fail-closed verification semantics remain the same.

## Enrollment and attestation

1. An operator issues a one-use, short-lived bootstrap grant bound to one host ID, platform, architecture, and trust tier.
2. Enrollment consumes the grant and creates a short-lived device identity.
3. The host advertises a signed `gha-indie-worker.host-capability.v1` payload inside a `gha-indie-worker.capability-envelope.v1` envelope.
4. The control plane verifies identity status, timestamp bounds, payload digest, signature, platform/architecture/trust binding, agent/protocol version, profile digests, capabilities, and the absence of secret-shaped fields.
5. macOS and Windows claims require the `native` capability. Architecture names are canonical: `x64` and `arm64`; aliases such as `x86_64` fail closed.

Capability changes during an active lease quarantine the host and terminate the lease. A quarantined host requires a fresh heartbeat/attestation and explicit recovery.

## Exact scheduling

The scheduler consumes the immutable dispatch request produced by PR #14 and matches:

- platform and architecture;
- exact trust tier;
- fixed profile name and digest;
- all required attested capabilities;
- healthy identity and recent heartbeat;
- eligible host state;
- available concurrency.

There is no fallback to another operating system, architecture, trust tier, profile generation, or capability set. Every nonmatching host records stable rejection reasons. Multiple eligible hosts are ordered by utilization, prior assignment count, last assignment sequence, and host ID.

## Leases and replay

A lease binds the request/digest, repository and immutable commit, host/key identity, fixed profile digest, capability digest, full selected capability snapshot, attempt, nonce, and deadline.

- Duplicate delivery returns the existing active lease.
- Duplicate delivery after completion returns the terminal receipt.
- Renewal requires the current nonce and rotates it.
- Cancellation is idempotent and prevents renewal.
- Conflicting terminal receipts fail closed.
- Heartbeat loss, identity revocation, quarantine, and lease expiry create terminal outcomes without issuing a second authority.

## Cross-agent profile coordination

PRs #17 and #18 define macOS and Windows hardening profiles. They remain machine-operation policy documents. Before integration, their dispatch-facing projection must use the canonical PR #14 vocabulary:

- `platform`: `macos` or `windows`;
- `architecture`: `arm64` or `x64` (not `x86_64`);
- fixed profile `name` plus `sha256:` digest;
- sorted capabilities including `native`;
- canonical trust tiers from this contract.

The simulator tests use `macos-xcode`, `windows-msvc`, and `linux-rust` profile generations to exercise the bridge.
