# Standalone dependency extraction boundary

The current repository is a byte-verified extraction of:

```text
repository: ORESoftware/k8s-cluster
source commit: 5cfac43c6900898f36f588d044ca34083da1c726
source path: remote/deployments/build-server-rs
private library gitlink: ORESoftware/k8s-libs-and-shared-defs@84d565eeb7e1e78ce0851a2e3c95966e5cc12e36
```

The Rust package still declares three monorepo-relative dependencies:

| Package | Current reviewed path |
| --- | --- |
| `dd-telemetry` | `../../libs/telemetry-rs` |
| `dd-nats-subject-defs` | `../../libs/nats/subject-defs/generated/rust` |
| `dd-runtime-config-client` | `../../libs/runtime-config-client-rs` |

Those paths are intentionally treated as an **unresolved standalone boundary**, not silently rewritten to mutable branches or unreviewed package sources. Until the dependencies are extracted or vendored through a separately reviewed immutable mechanism, ordinary pull-request CI validates source formatting, manifest shape, lockfile presence, provenance, and secret safety without claiming that the crate is independently compilable.

## Required migration

A complete standalone certification must:

1. choose one immutable dependency model: publish reviewed crates, vendor exact source trees, or use a repository layout with pinned gitlinks;
2. preserve the generated NATS subject contract byte-for-byte;
3. preserve telemetry and runtime-config API behavior;
4. regenerate and review `Cargo.lock` only after the dependency identities are fixed;
5. run formatting, warnings-denied Clippy, every test target, the production container build, and a real-process smoke test;
6. compare results against the canonical `k8s-cluster` build-server suite;
7. never store a classic PAT, private deploy key, or installation token in this repository.

Tracked by DEN-1633 and the parent continuity work in DEN-1550.
