# Source provenance

This repository is published from `ORESoftware/k8s-cluster` at immutable commit `42b7471fa680331fe4977db42db8497ab9549cd9` and then extended in this standalone repository through an explicit, CI-pinned compatibility layer.

- Canonical source path: `remote/deployments/build-server-rs`
- Canonical feature PR: `ORESoftware/k8s-cluster#797`
- Published role: bounded GitHub Actions YAML planner and fixed-profile execution worker
- Publication date: 2026-08-04
- Canonical core parity: every canonical file under `src/` except `src/main.rs`, plus `scripts/`, `generated/`, and `readme.md`, remains byte-for-byte identical to the immutable source commit
- Standalone entry-point integration: `src/main.rs` is pinned to Git blob `c9c6afdb499db519f3e8461e68d26d9435a5974b`; it preserves the canonical fixed-profile policy and delegates executable YAML admission to the shared library boundary while re-exporting only the YAML value/serialization surface used by that policy
- Shared strict admission: `src/lib.rs` is pinned to Git blob `7474ebbbd27f01d250b1c7614e18598a90dcb1c2`; planner and executable entry points share the bounded source guard and ambiguity-rejecting parser, while the planner additionally applies aggregate semantic expansion limits and the executable compatibility-report path preserves canonical YAML ordering and scalar behavior
- Standalone planner extensions: `src/workflow.rs`, `src/workflow_guard.rs`, `src/workflow_yaml.rs`, and the two `src/bin/gha-workflow-*` entry points are pinned by exact Git blob hashes in `.github/workflows/ci.yml`
- Immutable binding protocol: `crates/gha-indie-protocol` is independently locked and validated by `.github/workflows/indie-protocol.yml`
- Packaging adaptations: three monorepo path dependencies are replaced by local API-compatible crates under `vendor/`; `Dockerfile` copies those crates

The provenance workflow verifies the complete canonical source path set, byte-compares the unmodified canonical core, rejects unexpected standalone source paths, and verifies the exact blob identity of every declared extension. Any source change therefore requires an explicit provenance-manifest update in the same reviewed diff.

The compatibility crates do not add execution authority: telemetry is an identity layer, runtime registration exposes an empty router and no-op registration in standalone mode, and NATS symbols are the canonical worker subjects, stream name, and queue group.

This is an independent continuity lane, not a claim to reproduce GitHub's proprietary Actions control plane. Native workflow semantics remain GitHub-hosted Actions and Actions Runner Controller. The supported subset and intentional deviations are versioned in `docs/GHA_COMPATIBILITY.md`.
