# litegraph-runtime

Per-invocation execution runtime and capability boundary.

LiteGraph is a heterogeneous compute actor platform: CPU code owns control, networking, actor supervision and ordinary OS capabilities; suitable numerical work may be dispatched to one or more GPUs. A machine is therefore not classified as simply "CPU" or "GPU"—CPU, RAM, accelerator devices and VRAM are independently schedulable resources.

## Responsibilities

- InvocationActor lifecycle, deadlines and cancellation.
- request-local memory and output ownership.
- guest-engine adapters and opaque host capabilities.
- CPU host phases and accelerator dispatch through trusted clients.

## Explicit non-responsibilities

- cluster placement policy.
- raw GPU driver ownership.
- resident model lifecycle.

Keeping these boundaries explicit is important: moving policy into a lower-level component makes local execution harder to reason about and creates competing authorities.

## Place in the system

```text
router → node → runtime/InvocationActor → CPU host work and/or gpu-host/modeld → result
```

Shared invariants across the platform:

- invocation actors are ephemeral;
- resident artifacts and compiled variants are immutable and revisioned;
- guest/customer code receives capabilities, never raw accelerator pointers;
- mutable accelerator state belongs to trusted lane/device actors;
- CPU and GPU resources are accounted independently;
- `cpu`, `gpu`, and `auto` describe execution requirements/preferences without changing logical function identity;
- backpressure and cancellation must propagate rather than creating unbounded queues.

## Contracts and compatibility

Wire-visible names use `snake_case`. Cross-language contracts belong in `litegraph-contracts`: authored TypeSpec and JSON Schema Draft 2020-12 are peer authorities, and generated files are evidence rather than a third authored schema. Contract mismatches must fail closed before promotion.

Public/shared semantic types belong in `litegraph-interfaces` or `litegraph-pub-lib-core`; this repository should not create a subtly different copy of an existing concept.

## Security and isolation

Treat all tenant input and artifacts as untrusted. Validate sizes, identifiers and capability requests before allocating expensive resources. Never expose native accelerator pointers/driver handles across the tenant boundary, never place credentials in manifests or examples, and keep secrets in approved runtime secret channels.

Isolation policy uses the platform classes `shared`, `sandbox`, `partitioned`, and `dedicated` where applicable. Resource release on cancellation, timeout and failure is part of correctness.

## Development expectations

Follow the fleet policy in `ORESoftware/my-ai` (`AGENTS.md` plus `SHARED.md`) when changing this repository. Durable systems tooling, validators, code generation and CI helpers should be Rust-first. Do not add Python for repository scripts, validators, codegen or CI gates.

When this repository exposes an executable with command-line configuration, its public option contract belongs in root `.cli-flags.toml` and the argv boundary should use the canonical `flags-2-env` integration rather than maintaining a second independent flag schema.

Tests should cover both success and fail-closed behavior. Hardware-independent logic should run with deterministic fakes/simulators; hardware-specific certification belongs on real accelerator runners. A hosted workflow that starts zero test steps is not evidence of a passing build.

## Integration map

- `litegraph-contracts` — wire schemas.
- `litegraph-interfaces` — canonical shared semantics.
- `litegraph-scheduler` — cluster placement.
- `litegraph-node` — machine inventory and local supervision.
- `litegraph-runtime` — invocation lifecycle.
- `litegraph-gpu-host` — trusted accelerator execution.
- `litegraph-modeld` — resident model actors.
- `litegraph-compiler` — deterministic multi-target build artifacts.
- registries — immutable function/model artifact storage.
- `litegraph-router.rs` — invocation forwarding and backpressure.

## Documentation rule

Keep this README specific to this repository. Architectural decisions that affect multiple repositories should be recorded in the canonical interface/contracts layer and linked here rather than copied into divergent local specifications.
