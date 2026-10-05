# litegraph-gpu-host

Trusted lowest-level accelerator host and device/lane authority.

LiteGraph deliberately separates host actors from accelerator execution. Actor mailboxes, supervision, networking and ordinary OS capabilities run on CPUs; kernels/models can execute on GPUs through trusted capabilities. One box may therefore have one or more CPU sockets and many GPUs, and both processor types can be active concurrently.

## This repository owns

- device enumeration and health.
- lane/stream/context/partition ownership.
- VRAM and scratch-memory accounting.
- CUDA/ROCm/Metal/Vulkan backends behind AcceleratorBackend.

## This repository does not own

- tenant-facing raw device pointers.
- cluster placement.
- control-plane CRUD.

## Runtime relationship

```text
runtime/modeld → opaque accelerator request → lane actor → backend/device → completion
```

This boundary should remain true even as CUDA, ROCm, Metal, Vulkan/WebGPU or CPU implementations evolve.

## Heterogeneous execution model

Resource requests describe CPU/RAM independently from accelerator count/VRAM/features. `cpu` requires host execution, `gpu` requires a compatible accelerator path, and `auto` permits a validated fallback/selection policy. Logical function/model identity remains stable across target variants.

Portable compute is intentionally constrained. Filesystem access, sockets, process creation and other host syscalls are host capabilities; they are not silently translated into GPU kernels.

## Safety and multi-tenancy

- Validate tenant-controlled sizes and identifiers before allocation.
- Keep native driver handles and pointers behind trusted process/capability boundaries.
- Bound queues and propagate backpressure.
- Release reservations on cancellation, timeout, process death and device reset.
- Keep immutable artifacts content-addressed and verify digests before use.
- Never include credentials in examples, manifests, logs or artifact metadata.

Where isolation is configurable, use the shared `shared`, `sandbox`, `partitioned`, and `dedicated` vocabulary.

## Shared contracts

Do not fork platform types locally. `litegraph-interfaces` owns canonical semantics and `litegraph-contracts` owns cross-language wire schemas. TypeSpec and JSON Schema Draft 2020-12 are peer authored authorities; parity failures stop promotion. Generated outputs are read-only evidence.

## Tooling and configuration

Fleet engineering policy lives in `ORESoftware/my-ai` `AGENTS.md` and `SHARED.md`. Durable scripts, code generators, validators, audits and CI gates are Rust-first; Python is not used for those responsibilities.

Any executable CLI surface uses root `.cli-flags.toml` plus the canonical `flags-2-env` argv boundary. Credentials are secret-store/environment inputs, not command-line flags.

## Testing

Pure policy and accounting logic should be deterministic and hardware-independent. Use fake backends where the test is about lifecycle/placement rather than hardware. Claims about CUDA/ROCm/Metal/Vulkan behavior require real compatible runners. Cross-service behavior belongs in `litegraph-test`; performance claims belong in `litegraph-benchmarks`.

Always distinguish source/test failure from CI admission failure: a job with zero executed steps is not a passing test.

## Related components

- `litegraph-api-server.rs`: tenant control plane.
- `litegraph-router.rs`: invocation hot path.
- `litegraph-scheduler`: placement authority.
- `litegraph-node`: per-machine capacity/health.
- `litegraph-runtime`: InvocationActor lifecycle.
- `litegraph-gpu-host`: accelerator device/lane execution.
- `litegraph-modeld`: resident ModelActors.
- `litegraph-compiler`: deterministic target variants.
- function/model registries: immutable artifact authorities.
- desktop repositories: local supervision and UX.

## Contribution rule

Preserve these ownership boundaries. If a change introduces a new cross-repository concept, establish it in the canonical interfaces/contracts first and add integration tests rather than letting two services develop competing definitions.
