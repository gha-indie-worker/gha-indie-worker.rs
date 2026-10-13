# Core native-Oreslang migration audit (2026-10-09)

Status: **inventory and migration plan, not an implementation/merge approval**  
Source: `main` at `508e2d9f7dcbc3490a7a5219508114c67092e957`  
Scope: `oreslang-source.java` only; draft PR branches are examined separately, not assumed to exist on `main`.

## Executive findings

1. **No safe Java deletion identified solely by same-branch native-Oreslang duplication.** On the audited `main`, `stdlib/` contains **only `testing.ores`**. `NativeTestingFrameworkTest.java` is a JUnit integration test that *executes* this Oreslang library, not a second testing-framework implementation. Retain it.
2. User-facing standard-library policy still lives inside the 8,871-line `OresEvalRootNode.java` alongside the evaluator. In particular, string helpers (`member`, ~5911-5957), stdio facades (~5969-5990 and ~8816+), Future combinator dispatch (~6363-6404), list accessors/mutation (~6491-6527), and Option/Result combinators (~6630-6683) are Java-coded. **These are extraction candidates, not automatically deletable code.**
3. Compiler/Truffle bindings, AST/IR, scheduler admission, isolated actor transport, host capability enforcement, OS I/O, mutex/channel synchronization and collection-storage intrinsics cannot simply be replaced by guest library routines.
4. Native-first stdlib loading is not yet available on this `main`: an existing draft PR proposes it (#218) and an overlapping draft (#237) adds `StandardLibraryResolver`. Consolidate them before attempting to replace built-in names.
5. Main CI failures on the audited SHA do **not** establish implementation failures. The observed `ci`, `fast-ci`, Graal conformance and native CLI run jobs reported `failure` with **zero executed steps**; root cause/runner admission needs separate diagnosis and exact-head execution.

## Boundary classification

| Current code (main) | Classification | Intended disposition | Required proof before deletion |
| --- | --- | --- | --- |
| `stdlib/testing.ores` | Already native Oreslang | Keep | Its Java JUnit tests should continue to test the actual .ores module. |
| `NativeTestingFrameworkTest.java` | Host test, **not** duplicate library | Keep | Do not delete merely because tests are named `Native*`. |
| `OresEvalRootNode.member(String)` helpers: identifier/route-name checks, trim, split, prefix/suffix/contains | Library policy over string primitives | Extract policy into `std/string` or `std/text`; retain only irreducible immutable string operations in backend intrinsics | Exhaustive ASCII/Unicode/empty/boundary conformance, exact original semantics or versioned change. |
| `OresEvalRootNode.optionMember/resultMember` | Guest-visible convenience semantics | Move to native Oreslang methods/functions over sealed Option/Result payload/tag intrinsics | Panic-vs-Result semantics, generic return types, single evaluation, return/ownership propagation, no host identity leaks. |
| `OresEvalRootNode` `List.get/add/set/size`, Java List/Map storage | Mixed semantic API and backing storage | Move algorithms, equality, order and collection families to `std/collections`; keep opaque storage/load/store/integer-index safety as intrinsics | Bounds, aliasing, freezing, mutation borrow, iteration invalidation, identity/cycles, actor sendability, cross-backend equivalence. |
| `OresFutures`, `Future.map/compose/onSuccess` | Mixed combinators and runtime waiter ownership | Put pure composition API in Oreslang, retain scheduler-owned completion, cancellation, waiter detach and wakeup in runtime | Cancellation races, deterministic result order, no callbacks on producer I/O threads, fairness/quota accounting. |
| `stdio.stdout.log/logList`/display formatting | Formatting is library policy; stdout write is capability | Move formatting/join logic to `std/log`; keep checked output sink and bounded marshaling as intrinsics | STDOUT policy, escaping, bounded log output, cyclic objects, lazy evaluation side effects. |
| `NativeIo.java`/`AsyncNativeIo.java` | Privileged OS boundary | Keep permission checks, canonicalization, worker/admission and opaque handles in backend; move safe path/text/HTTP *library policy* to Oreslang | Same-path authorization at submission and execution, symlink/TOCTOU threat model, deadlines and close under saturation. |
| `NativeHttp.java` | Transport and security boundary | Keep socket/framing, admission, revocation, ownership and request-lifetime enforcement backend-side; extract routing, protocol convenience, response-building policy where safe | Smuggling/framing, percent decode, request limits, response ownership, actor cancellation. |
| `ActorRuntime`, `ChannelRuntime`, `OresMutex`, `OresScheduler`, `OresFuture` | Runtime concurrency primitives | Keep as backend implementation until native ABI equivalents can replace them; expose guest abstractions in .ores | Formal linearization points, cancellation, fairness, bounded queues, memory ordering, isolation, no actor-carrier blocking. |
| `Lexer/Parser/TypeChecker/OwnershipChecker`, `OresLanguage` | Compiler / Truffle backend | Keep; factor reusable typed IR and backend-independent runtime contracts | Differential conformance against canonical semantic tests and non-JVM backend. |
| `RuntimeGarbageCollector` | Host-resource cleanup support | Keep narrowly documented; do **not** claim it is an independent guest heap collector | Explicit resource lifetime / cycle and finalizer tests. |

The current `docs/NATIVE_RUNTIME_ABI.md` already explicitly favors `Oreslang -> typed IR -> intrinsics -> native runtime -> OS`. Maintain capability isolation, checked casts and Ores-owned type tags. Do not use arbitrary Java host reflection as an implementation shortcut.

## Missing pieces / follow-up backlog

The following are **not complete on audited main**, even if drafts or designs exist:

| Priority | Gap | Definition of done | Existing work |
| --- | --- | --- | --- |
| P0 | Canonical packaged `std/*` module resolver | Resolve, compile, typecheck and link versioned .ores library modules in JIT/AOT/hot-load; disallow `java:` and unsafe host interop; prevent path traversal/shadowing. | #218 and #237 overlap; restack/consolidate. |
| P0 | Typed runtime intrinsic/ABI registry | One versioned manifest for names, signatures, purity/effects, capabilities, ownership, actor sendability and backend feature flags; compiler and runtime derive surfaces from same contract. | `docs/NATIVE_RUNTIME_ABI.md` is direction, not stable ABI. |
| P0 | Native collection storage with .ores algorithms | `Array/List/Map/Set` ownership/equality/order semantics identical in Truffle and native backend, no exposing java.util. | #135, #237. |
| P0 | Runtime isolation and host resource checks | Proof/negative tests that stdlib cannot escalate capabilities or leak host handles across private/untrusted actor boundaries. | Some tests on main; broader backend contract missing. |
| P1 | Option/Result native combinators | `map/flat_map/map_err/or_else/unwrap*`, typed generic propagation, panics vs recoverable errors. | #210. |
| P1 | Native Future/Iterator/Stream API | Guest-facing APIs with scheduler-owned callbacks, backpressure, lifecycle and cancellation. Observable bridges use the same engine. | #210, #293. |
| P1 | Text/encoding/format APIs | Explicit Unicode/codepoint/grapheme semantics, UTF-8 encode/decode and allocation-aware builders; keep literal string ops separate from HTML/URL sanitization. | `docs/string-transform.md` only documents current primitive. |
| P1 | Safe file/network/HTTP library | Opaque OS handles, native async transfer, common error types; keep authorization, limits and framing out of guest policy. | #131, #221 and native transport stack. |
| P1 | Structured error/Result taxonomy | Portable filesystem/network/timeout/cancel/parse errors, with stable codes and sanitized diagnostics. | Current host exceptions leak backend-shaped classification. |
| P1 | Runtime/stdlib parity CI | Boot stdlib from packaged source, deny Java interop, execute identical suites under JIT, AOT/Native Image, strict actor and native variants. | Existing CI workflows; execution not confirmed on audited SHA. |
| P2 | Native stdlib `math/time/serde/json/regex/collections` packages | Native Oreslang policy atop narrowly typed numeric/clock/codec intrinsics; TypeSpec/JSON Schema contracts where appropriate. | Track as incremental libraries; avoid broad Java wrappers. |
| P2 | Native testing expansion | Parallel isolation, per-test monotonic timeouts, snapshots, coverage, fuzz shrinking, stable JSON reporting and Rust CLI test discovery. | Explicitly deferred in `docs/TESTING.md`. |
| P2 | Full managed guest heap / arena semantics | Concretely define cycles/weak refs/arenas/destructor behavior rather than describing `RuntimeGarbageCollector` as a tracing collector. | Draft runtime PRs and `docs/RUNTIME_BOUNDARY_AUDIT.md`. |
| P2 | Effects and `pure` enforcement | Compiler transitive closure over imports/callbacks/FFI with fail-closed effects and backend-independent verification. | `docs/PURE.md` marks itself **draft**. |

## Existing PRs: do not independently rewrite their functionality

- #218 (`207246c`): packaged native core loader; **draft, dirty**.
- #237 (`9bd4c2c`): native collections; **draft, unstable**, overlaps resolver/stdlib with #218.
- #210 (`48e67e0`): Option/Result/Future/Iterator/Stream; **draft, unstable**.
- #135 (`05faf42`): native C core + storage/net primitives; **draft**.
- #131 (`0b4e606`): native HTTP stdlib; **draft, dirty**, notes protocol migration incomplete.
- #221 (`1006b92`): native filesystem; **draft, dirty**, built on other native-network work.
- #293 (`4e17130`): rx-ores bridge; **draft, unstable**, still Java host implementation, not yet native Oreslang guest facade.
- #509: Rust formal-analysis work; keep Rust (not Python) for any new core-audit tool.

Branch mergeability is advisory and changes with the target branch. Re-evaluate on the **exact new head**, not an old green ancestor.

## Deletion gate: what qualifies as “remove Java implemented in native Oreslang”

A Java user-visible implementation can be removed **only after**:

1. The equivalent `.ores` definition is actually checked into the merged main tree and is executable via versioned stdlib import/load.
2. The Java implementation is reduced to an intrinsic, compiler backend, privileged transport primitive, or deleted if genuinely unused.
3. Compiler and runtime API contracts agree on signatures, generic constraints, error behavior, capability scope and ownership.
4. Tests execute both old and new paths against the same vectors (including faults, races, cancellation, quota exhaustion and malformed inputs).
5. AOT/Native Image resource packaging and fresh hot-load contexts can resolve and execute the library without privileged Java host access.
6. CI jobs actually run on the exact PR head SHA; zero-step/runner-admission failure is **not** a compile/test failure.
7. No duplicate exposed semantic owner remains; otherwise explicitly tag the old branch as compatibility shim with sunset gates.

The `NativeTestingFrameworkTest.java` JUnit file **must not** be removed by this rule; it is a host conformance test, not the stdlib logic.

## Suggested implementation order

1. Restack/merge the resolver foundation from #218/#237, resolving their overlap; make the source and absence of `java:` imports mechanically auditable.
2. Stabilize #135's opaque storage/intrinsics, then land #237 collection algorithms, and only then retire Java collection **policy** (not backend storage operations).
3. Add the Option/Result semantic layer from #210 and retire evaluator convenience method tables incrementally.
4. Stabilize Futures/Stream/Rx on the same scheduler/cancellation engine; keep lock-free atomic state and transport handling backend-owned.
5. Stage files/network/HTTP only with complete security/fuzz/actor lifecycle tests; no eager transport rewrite.
6. Introduce one CI conformance command using the exact-head built artifact, plus Rust tooling for any new static audit or formal-model checks.

## Current validation evidence / limitations

At audit time, `main` SHA `508e2d9...` had failed workflow runs:
- [ci](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/37890879237)
- [fast-ci](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/37890879113)
- [Graal conformance](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/37890879096)
- [Native CLI contract](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/37890879128)

The inspected job summaries have zero steps; no successful source execution is established by those failures. Investigate runner admission and validate in a funded test organization **only** if the mirror branch verifies exact Git tree/blob identities and runs with its own token. Do not treat a test of a different source tree as verification for this SHA.

This audit is based on a complete repository path inventory, sampled relevant Java/native code and docs, and available PR metadata/diffs. It is not a full call graph or a verified runtime test execution. No runtime code was removed in this audit because no same-branch Java-vs-`.ores` implementation duplicate was proven.
