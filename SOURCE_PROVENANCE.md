# Source provenance

This repository is published from `ORESoftware/k8s-cluster` at immutable commit `42b7471fa680331fe4977db42db8497ab9549cd9`.

- Canonical source path: `remote/deployments/build-server-rs`
- Canonical feature PR: `ORESoftware/k8s-cluster#797`
- Published role: bounded GitHub Actions YAML planner and fixed-profile execution worker
- Publication date: 2026-08-04
- Source parity: `src/`, `scripts/`, `generated/`, and `readme.md` are byte-for-byte canonical
- Packaging adaptations: three monorepo path dependencies are replaced by local API-compatible crates under `vendor/`; `Dockerfile` copies those crates

The compatibility crates do not add execution authority: telemetry is an identity layer, runtime registration exposes an empty router and no-op registration in standalone mode, and NATS symbols are the canonical worker subjects, stream name, and queue group.

This is an independent continuity lane, not a claim to reproduce GitHub's proprietary Actions control plane. Native workflow semantics remain GitHub-hosted Actions and Actions Runner Controller.
