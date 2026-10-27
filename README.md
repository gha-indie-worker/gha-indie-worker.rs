# oreslang-data-science

Native Oreslang libraries for numerical computing, columnar data, data pipelines, and data-oriented programming.

The core library is intentionally implemented in **Oreslang**, not Java. The target is Python-class ergonomics with a data-oriented execution model that can grow toward NumPy/Polars-class numeric and columnar performance while preserving Oreslang ownership, AOT, actor isolation, scheduler semantics, and CPU/GPU placement.

## Native core

The current compiler-facing implementation provides:

- scalar math helpers and checked square root;
- numerically stable Welford mean/variance reductions;
- borrowed, allocation-free reductions such as `sum` and `dot`;
- owned in-place vector transforms such as `scale`, `axpy`, and L2 normalization;
- row-major `DenseMatrixF64` with caller-provided output buffers;
- nullable columnar `FloatColumn` and `FloatFrame`;
- allocation-conscious map/filter pipeline primitives;
- Struct-of-Arrays point-cloud and particle data models;
- deterministic pure-Oreslang byte-level BPE with caller-provided token/span buffers.

Current core `main` represents typed sequences as `List<T>`. Oreslang object values are references and ordinary arguments are passed by reference: passing `values` does not copy the object and does not require address-of syntax. Oreslang source does **not** expose raw pointers: `&` is not an address-of/borrow operator, unary `*` is not a dereference operator, and pointer arithmetic is not part of userland Oreslang. (`*` remains the ordinary multiplication operator.)

Ownership and access changes are explicit operations: `rt copy`, `rt borrow`, `rt take`, and `rt share`. Mutation authority is expressed with `mut`, not with `&mut` or pointer types. `@Structural` remains reserved for explicit shape-based compatibility when a callable intentionally accepts values across nominal representation categories. Mutable buffers are used for transforms so a future dedicated contiguous `Array<T>` can replace the storage primitive without changing the higher-level execution model.

## Example

```ores
import fnc mean from './src/math/reductions';
import fnc scale from './src/math/vector';

val List<float> values = arr[1.0, 2.0, 3.0, 4.0];
val List<float> doubled = scale(values, 2.0);

// Ordinary object arguments are references. No '&' or '*' pointer syntax is used.
val Result<float, String> average = mean(doubled);
stdio.stdout.println(average.expect("mean failed"));
```

## Native tests

The repository uses the Oreslang-native framework from core PR #233. A pinned copy is kept at `vendor/oreslang/testing.ores` so tests need no Java/JUnit dependency and no cross-repository token.

```sh
ORESLANG_BIN=/path/to/oreslang ./scripts/test.sh
```

The suite in `tests/main.ores` covers scalar math, stable reductions, shape errors, vector normalization, dense matvec, nullable column means, SoA particle energy, and byte-level BPE rank/span semantics. `tests/bpe_reference.rs` independently checks the intended BPE behavior while native compiler/runtime execution remains gated upstream; it is not a substitute for native Oreslang evidence.

The NLP boundary deliberately accepts UTF-8 bytes today so it does not depend on backend-specific String storage. See [NLP text and BPE contract](docs/NLP_TEXT_CONTRACT.md), [Architecture](docs/ARCHITECTURE.md), and [Roadmap](ROADMAP.md).

## Compiler PR checkpoint (2026-10-06)

A [repository-specific compiler compatibility matrix](docs/compiler-pr-sync.md) tracks the 10 newest `oreslang-source.java` PRs. They remain unmerged upstream; existing demos, build flags, and stable compiler references stay unchanged pending exact-SHA conformance testing.

## Runner CI and native-suite coverage

The `Test runner contracts` workflow checks shell syntax and uses a compiler
test double to prove repository-root execution, paths containing spaces,
explicit compiler selection, PATH fallback, exact output/exit-code propagation,
and failure when the compiler is missing. It uses read-only permissions and no
secrets. The runner now works when launched outside this repository.

This is a launcher gate, not numerical conformance evidence. A 2026-10-07 probe
against compiler `5ffc52d9297d175307094d530293c69f1cafc7cd` stopped at the
unexported `TestCase` class imported from `vendor/oreslang/testing.ores`.
Resolve exports and subsequent ownership diagnostics, then run the complete
native numerical suite before selecting a compiler pin and enabling runtime CI.
