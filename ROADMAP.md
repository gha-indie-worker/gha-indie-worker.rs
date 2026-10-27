# Roadmap

The target is not a thin Python compatibility layer. The target is an Oreslang-native stack covering the common NumPy + pandas/Polars + SciPy + scikit-learn workflow while exploiting Oreslang's runtime model.

## P0 — native numerical/data core

- [x] scalar math helpers;
- [x] stable mean/variance reductions;
- [x] dot, scale, Hadamard, AXPY, L2 normalization;
- [x] row-major dense matrix with destination-buffer matvec;
- [x] typed nullable float columns;
- [x] eager map/filter buffer kernels;
- [x] Struct-of-Arrays point/particle examples;
- [x] native Oreslang test suite using the core test framework;
- [ ] benchmark harness against equivalent Python/NumPy/Polars operations.

## P0 — NLP text foundation

- [x] deterministic byte-level BPE core in pure Oreslang;
- [x] byte-offset token spans and caller-provided output buffers;
- [x] reference checks for ASCII, UTF-8 multibyte spans, rank ordering, and invalid input;
- [ ] cross-backend lifetime-safe `String` -> UTF-8 byte view;
- [ ] Unicode normalization and grapheme/word/sentence segmentation;
- [ ] execute the native BPE suite on Java/Graal, LLVM, .NET, WASM, and JS/transpiler backends.

## P1 — ndarray and dataframe semantics

- dedicated contiguous typed `Array<T>`;
- n-dimensional arrays with shape/stride metadata;
- zero-copy views and slicing;
- broadcasting;
- bit-packed validity masks;
- heterogeneous DataFrame schema;
- select/filter/with_column/group_by/aggregate/join/sort;
- lazy logical plans and expression trees;
- chunked execution and operator fusion.

## P2 — data interchange

- CSV and JSON readers/writers;
- Arrow-compatible column buffers;
- Parquet;
- memory mapping;
- streaming readers with backpressure;
- zero-copy handoff where ownership rules permit it.

## P3 — statistics and scientific computing

- quantiles, covariance, correlation;
- histograms;
- distributions and random sampling;
- linear algebra decompositions;
- numerical integration and root finding;
- time-series windows/rolling operations.

## P4 — machine learning

- train/test split;
- preprocessing pipelines;
- linear/logistic regression;
- trees and ensembles;
- clustering;
- metrics;
- model serialization;
- tensor/accelerator bridge.

## P5 — runtime/compiler acceleration

- recognize reduction and linear-algebra kernels;
- loop fusion;
- SIMD;
- multicore chunk scheduling;
- effect-aware parallel plans;
- Regent/Legion-inspired CPU/GPU mapping;
- explicit transfer planning for GPU-resident columns;
- cache-aware batch sizing;
- AOT specialization for known shapes and schemas.

## Competitive bar

For common exploratory workloads, Oreslang should aim for:

- Python-level ergonomics without a Python interpreter dependency;
- Polars-style columnar execution;
- NumPy-class dense numeric kernels;
- Rust-like predictable native deployment;
- Oreslang-native actor/task isolation and heterogeneous scheduling.
