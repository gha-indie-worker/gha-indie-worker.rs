# Architecture

## Objective

Make data science a first-class Oreslang workload instead of wrapping a Java or Python implementation.

The public API should remain concise enough for exploratory work while the storage/execution model stays friendly to AOT compilation, CPU caches, vectorization, parallel scheduling, and GPU placement.

## Principles

### Native Oreslang userland

Core algorithms and tests live in `.ores` files. Java is not the implementation language for this library. Runtime/compiler intrinsics may later optimize recognized kernels while preserving the Oreslang reference semantics.

### Pointer-free reference semantics

Oreslang userland does not expose C/C++/Rust-style pointers. Objects and collection values are reference values, and ordinary parameter passing passes that reference without copying the underlying object.

Accordingly, public Oreslang APIs must not use `&T`, `T*`, `&value`, unary `*value`, `&mut T`, or pointer arithmetic. The `*` token is still used for ordinary numeric multiplication; it is never a userland dereference operator.

Ownership and lifetime operations are explicit and semantic rather than encoded as pointer syntax:

- `rt copy value` creates an explicit copy;
- `rt borrow value` creates an explicit temporary borrow/capability when one is actually needed;
- `rt take value` explicitly transfers ownership;
- `rt share value` explicitly creates shared ownership;
- `mut` marks mutation authority for a referenced value.

A read-only parameter therefore uses a normal type such as `List<float> values`, not `&List<float> values`. A mutable destination uses a form such as `List<float> mut output`, not `&mut List<float>`. `@Structural` is a type-compatibility mechanism only; it is not an ownership or read-only marker.

The compiler, OresVM, FFI layer, SIMD backend, and GPU backend may lower safe reference/buffer operations to raw addresses, aligned loads, device pointers, and address arithmetic internally. Those capabilities stay below the Oreslang source-language boundary. This preserves contiguous SoA layouts, vectorization, cache-aware traversal, zero-copy views where safe, and CPU/GPU placement without exposing raw pointer authority to application code.

### Columnar by default

Rows are convenient at boundaries; columns are the execution representation. Nullable columns store values separately from validity metadata, so reductions scan dense numeric buffers rather than boxed row objects.

### Data-oriented layouts

Performance-sensitive multi-field data uses Struct-of-Arrays layouts. Current core `main` expresses these as data-only classes; behavior remains in free systems/functions. Once source-level structs are finalized, the container syntax can migrate without changing the DOP layout.

### Batch pipelines

Pipelines operate on typed buffers rather than boxed records. The current implementation favors in-place map and destination-buffer compaction. Later compiler/library work can fuse adjacent kernels, vectorize, choose chunk sizes, and place work on CPU/GPU.

### Explicit errors

Shape mismatches, empty reductions, invalid frames, invalid numeric domains, and undersized destination buffers return `Result<T,E>`.

## Native testing

`tests/main.ores` uses the core native testing framework, pinned under `vendor/oreslang/testing.ores`. Tests are ordinary Oreslang code and emit the stable `ORES_TEST|...` event protocol.

## Performance direction

Candidate compiler/runtime intrinsics can specialize:

- Welford reductions;
- dot/axpy/matvec;
- validity-mask scans;
- map/filter fusion;
- chunked parallel execution;
- SIMD;
- GPU task placement when data/effects make that safe.

The pure Oreslang implementation remains the semantic reference.
