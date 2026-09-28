# k8s-cluster CI-family extraction — 2026-09-27

Current source reference: `ORESoftware/k8s-cluster` commit `cc675fd772d56e917d6b19e03a9c62e98d02248d`.

`gha-indie-worker.rs` was already split from `remote/deployments/build-server-rs` at `5cfac43c6900898f36f588d044ca34083da1c726`; this pass does **not** duplicate that service. Instead it brings forward the newer sibling policies that belong with the independent continuity lane.

## Capacity broker

Imported under `imports/k8s-cluster/gha-capacity-broker-rs/` for review. Preserve these invariants when integrating:

- route using **gross Actions minutes** before included-usage discounts; keep net/billable minutes separate for cost reporting;
- explicit thresholds: warn, self-hosted, hard stop;
- fail closed when billing cannot be read: validated ARC capacity or hold;
- self-hosted capacity is used only after an operator-controlled readiness/certification bit is true;
- `build-server` remains a bounded fallback for already-reviewed profiles, never arbitrary repository commands;
- organization variables are selected-repository-only and require explicit repository IDs;
- `build-server`/`hold` write a deliberately nonexistent runner label so jobs do not queue indefinitely;
- keep ARC registration, billing-read, and variable-mutation GitHub App authority separate.

## CI profile runner

Borrow from `remote/deployments/ci-profile-runner-rs`:

- exact 40-hex revision required;
- exact repository/profile allowlist binding;
- compiled profiles only (source currently has Playwright/Puppeteer);
- caller cannot choose clone URL, image, shell, command, network, mount, resource limits, container name, or containerd namespace;
- disable git `ext`, `file`, `local`, tags and submodules; verify detached HEAD equals requested SHA;
- fixed CPU/memory/PID/shm limits, no-new-privileges, cap-drop ALL;
- bounded output and unconditional workdir/container cleanup;
- keep host-containerd privilege isolated from the general build server.

## Build server

The existing split remains authoritative here. Bring forward improvements from the newer k8s copy selectively (Fiducia locking/idempotency, durable NATS intake, webhook dedupe, fixed-profile artifacts, least-privilege secret sync) through normal review instead of overwriting this repo.

The original k8s services and manifests remain untouched during this extraction.


## Executable import evidence

The staged capacity-broker import remains a review capsule, not production wiring. Its original `Cargo.toml` and `Cargo.lock` are copied from `ORESoftware/k8s-cluster@cc675fd772d56e917d6b19e03a9c62e98d02248d` so the imported policy core can be compiled and tested independently without silently changing its dependency graph.

`.github/workflows/capacity-import-proof.yml` runs exact-head formatting, warnings-denied Clippy, and locked tests for that capsule. Passing the capsule proves the extracted policy code remains executable; it does **not** claim the main GIW build server calls it or that GitHub billing/variable mutation credentials are deployed.
