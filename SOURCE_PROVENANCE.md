# Source provenance

This repository is published from `ORESoftware/k8s-cluster` at immutable commit `42b7471fa680331fe4977db42db8497ab9549cd9` and then extended in this standalone repository through an explicit, CI-pinned compatibility layer.

- Canonical source path: `remote/deployments/build-server-rs`
- Canonical feature PR: `ORESoftware/k8s-cluster#797`
- Published role: bounded GitHub Actions YAML planner and fixed-profile execution worker
- Publication date: 2026-08-04
- Canonical core parity: every canonical file under `src/` except the explicitly pinned standalone extensions below, plus `scripts/` and `generated/`, remains byte-for-byte identical to the immutable source commit
- Standalone entry-point integration: `src/main.rs` is pinned to Git blob `a3da1bdae02618a7382fc24aa73e3ad8edb721aa`; it preserves the canonical fixed-profile policy, compiles the independently certified admission classifier without connecting it to unreviewed evidence, and delegates executable YAML admission to the shared library boundary
- GitHub admission classification: `src/admission.rs` is pinned to Git blob `db0812072de0a3ba5d1ce5965a28dc2ff0d09f5a`; it fails closed on ambiguous zero-step evidence and cannot authorize fallback from repository-controlled log text
- Immutable checkout hardening: `src/jobs.rs` is pinned to Git blob `645078edf50bd8d7c024100bc4e1f748b483a4dd`; exact commit requests use a restricted shallow fetch followed by a detached checkout instead of treating the commit ID as a branch name
- Executable workflow admission: `src/gha_workflow.rs` is pinned to Git blob `8f42204e1e2443974121203ca683bad26c0a0d26`; it accepts only lowercase immutable commit IDs and enforces GitHub-compatible job identifier syntax before fixed-profile binding
- Fixed-profile request admission: `src/validation.rs` is pinned to Git blob `a57366881c2dcf43e119e70cccd5a82527370227`; every profile request, including direct authenticated submissions, requires the same lowercase immutable commit contract
- HTTP regression surface: `src/http.rs` is pinned to Git blob `6e7e559684ad544e2f05b542779b64c8439e5936`; it proves missing and mutable profile revisions are rejected before queue admission
- Standalone operator contract: `readme.md` is pinned to Git blob `446a84f97fcee88873e97ff3dcd3ab8bb1848ee5`; it documents immutable profile checkout separately from mutable image-job branch and tag checkout
- Shared strict admission: `src/lib.rs` is pinned to Git blob `4163d3a5bf19c26c43b9a854e55632e784a56a82`; planner and executable entry points share the bounded source guard and ambiguity-rejecting parser, while the trusted Linux executor is isolated behind the default-on `linux-runner` feature so the standalone planner harness remains execution-free
- Trusted Linux conformance executor: `src/linux_runner.rs` is pinned to Git blob `0ca7d1c3536c5f34871c8b3810697106d6b49154` and `src/bin/gha-linux-runner.rs` is pinned to Git blob `deca1126fe6bd8d03e4e3f105d2b635408a5175a`; host shell execution requires an explicit CLI capability and is not reachable from webhook or HTTP intake
- Standalone planner extensions: `src/workflow.rs`, `src/workflow_guard.rs`, `src/workflow_yaml.rs`, and the two `src/bin/gha-workflow-*` entry points are pinned by exact Git blob hashes in `.github/workflows/ci.yml`
- Immutable binding protocol: `crates/gha-indie-protocol` is independently locked and validated by `.github/workflows/indie-protocol.yml`
- Packaging adaptations: three monorepo path dependencies are replaced by local API-compatible crates under `vendor/`; `Dockerfile` copies those crates

The provenance workflow verifies the complete canonical source path set, byte-compares the unmodified canonical core, rejects unexpected standalone source paths, and verifies the exact blob identity of every declared extension. Any source change therefore requires an explicit provenance-manifest update in the same reviewed diff.

The vendored compatibility crates do not add execution authority: telemetry is an identity layer, runtime registration exposes an empty router and no-op registration in standalone mode, and NATS symbols are the canonical worker subjects, stream name, and queue group. The separate trusted Linux CLI does add explicit local execution authority and therefore requires `--allow-host-execution`; production intake never supplies that capability.

This is an independent continuity lane, not a claim to reproduce GitHub's proprietary Actions control plane. Native workflow semantics remain GitHub-hosted Actions and Actions Runner Controller. The supported subset and intentional deviations are versioned in `docs/GHA_COMPATIBILITY.md`.
