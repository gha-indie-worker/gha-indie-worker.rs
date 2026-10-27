# Oreslang upstream compatibility — 2026-10-06

This repository is audited against the latest 10 PRs in `ores-truffle-oreslang/oreslang-source.java` (#405, #406, #408, #409, #410, #411, #412, #414, #415, #416).

## Upstream contract

- #405 `do select` / `do nb select` no-result syntax; legacy select remains compatible.
- #406 arm-local `return` inside `do select`, with `W-SELECT-RETURN` diagnostics.
- #408 surfaces those warnings through the CLI.
- #409 additive runtime `SelectPlan`; no source rewrite required and traditional select stays on `SelectSet`.
- #410 permits task-local data-only `Channel<T>` at async boundaries and makes explicit `rt take` consuming.
- #411 defines binding capabilities: `const` fixed/read-only, `val` = `const mut`, `let` rebindable/read-only, `let mut` rebindable/mutable; adds select-case bindings.
- #412 expands do-select control-flow/actor-domain/atomic-dispatch coverage.
- #414 hardens source continuations so selected arms/cleanup run once.
- #415 adds `define actor` parsing/isolation groundwork and host `ready`/`done`; source `spawn Worker()` lifecycle is still deferred.
- #416 shares immutable checked code images across same-process actor contexts; not cross-process or machine-code sharing.

## Repository impact

Primarily a binding/ownership audit. Do not introduce SelectPlan into examples unless repeated/dynamic select is a measured hotspot; preserve pointer-free ownership forms.

## Integration policy

The ten PRs do not form one linear compiler head. Several are stacked on separate bases, and #415/#416 are a separate actor stack. Do not repin this repository to an arbitrary draft head and describe it as the integrated language. Migrate source to the intended contract, then advance the compiler ref only when the required upstream stack has exact-head green CI.

If source-org Actions cannot allocate a runner, mirror the exact source Git tree/blob SHAs into a funded test-org branch and run Actions there without private cross-org tokens or secrets.

## Guardrails

- `do select` / `do nb select` are no-result side-effecting forms; selected-arm returns are local/discarded.
- Traditional `select` remains supported and independent of #409 SelectPlan.
- `cb select` / `nb cb select` are noncanonical.
- Preserve #411 binding capability distinctions; do not mechanically rewrite all declarations to one keyword.
- Keep Oreslang pointer-free and use runtime ownership operations rather than `&` / `*`.
- Actor-mailbox data is serialized; local task channels may carry only what their local contract permits.
- #416 is same-process immutable code-data sharing, not cross-process or JIT-machine-code sharing.
