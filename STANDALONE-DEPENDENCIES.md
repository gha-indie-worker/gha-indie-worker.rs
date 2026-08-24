# Standalone dependency boundary

The current repository is a byte-verified extraction of:

```text
repository: ORESoftware/k8s-cluster
source commit: 42b7471fa680331fe4977db42db8497ab9549cd9
source path: remote/deployments/build-server-rs
private library gitlink: ORESoftware/k8s-libs-and-shared-defs@84d565eeb7e1e78ce0851a2e3c95966e5cc12e36
```

The original extraction declared three monorepo-relative dependencies. The standalone repository now resolves that boundary through reviewed, repository-local compatibility crates:

| Package | Reviewed standalone path |
| --- | --- |
| `dd-telemetry` | `vendor/dd-telemetry` |
| `dd-nats-subject-defs` | `vendor/dd-nats-subject-defs` |
| `dd-runtime-config-client` | `vendor/dd-runtime-config-client` |

These crates preserve only the APIs and fixed subject definitions required by the standalone worker. They add no execution authority: telemetry is an identity layer, runtime registration is inert in standalone mode, and NATS names remain fixed constants. `Cargo.lock`, source-provenance checks, strict Clippy, and the complete test suite make the repository independently compilable without selecting mutable dependency branches at build time.

## Required invariants

A standalone change must:

1. keep all three dependency paths repository-local and prevent `git`, branch, tag, or revision selectors from being introduced silently;
2. preserve the generated NATS subject contract and the no-op standalone runtime-registration boundary;
3. update and review `Cargo.lock` whenever dependency identities change;
4. run formatting, warnings-denied Clippy, every test target, and the relevant integration probes;
5. update `SOURCE_PROVENANCE.md` and its CI-pinned Git blob identities when a declared standalone extension changes;
6. compare canonical-core files against the immutable upstream source revision; and
7. never store a classic PAT, private deploy key, installation token, or other runtime credential in the repository.

The original extraction work was tracked by DEN-1633 and the parent continuity work in DEN-1550.
