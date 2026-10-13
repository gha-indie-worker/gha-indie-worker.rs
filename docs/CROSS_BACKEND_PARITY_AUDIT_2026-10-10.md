# Cross-backend Oreslang parity audit — 2026-10-10

> **Status: NOT AT PARITY.** This records implementation and verification
> gaps observed in the two default branches, not a promise of completed
> work. Java baseline: `450d32405dd7a92146d71f264ac850041b588910`.
> LLVM baseline: `f84183bcc7d933c2a272f42c572c3836feba4859`.
> Open feature PRs are not treated as merged, integrated capabilities.

## What counts as parity

1. **Admission parity:** the same ordinary `.ores` program is admitted
   or rejected under the same language and typed capability policy.
2. **Semantic parity:** admitted programs have the same types, values,
   ownership/borrowing effects, failure classification and cleanup order.
3. **Behavioral parity:** identical actor/start/stop/failure traces,
   suspension/resumption, timeout/cancel arbitration and resource-lifetime
   outcomes under comparable injected schedules.
4. **Feature parity:** every public source construct and documented stdlib
   operation has executable semantics on both backends, or is explicitly
   excluded by a shared *target capability* contract. A host-only runtime
   helper or a parser acceptance test is not end-to-end source support.

Performance equivalence, "zero GC pauses", OS thread count and isolate
configuration are distinct performance/isolation requirements; they
must not be misreported as proof of language semantic parity.

## Blocking differences on the audited default branches

| Area | Java/GraalVM | Native LLVM | Exit criterion |
| --- | --- | --- | --- |
| Functions, arithmetic and control flow | Broad language frontend | Restricted i64 function/expression subset; provisional overflow, ABI, integer width | Same programs and result/error semantics, including boundary integers and overflow |
| Ownership and `rt copy/take/share/borrow` | Typed actor/heap and borrow rules evolving | Primitive i64 calls and scoped loans only; no general heap/projection/suspension loans | Differential compile-fail and run fixtures for aliasing, nested borrows, moves, deference and cleanup |
| `async`, `suspends`, `await` | Source-level support, with legacy unannotated suspension compatibility | No source continuation-frame lowering | Same effect-typed call graph, continuation resumes, cancellation and borrow restrictions |
| `select`, `nb select`, channels, futures | Broader runtime and source support; reusable plans in open PRs | No source select lowering; rejection fixtures | Same winner/loser, close, cancellation, timeout and subscription traces |
| Actor spawn, ActorGroup, supervision | Existing actor runtime and active lifecycle PRs | Host-only actor lifecycle primitives; source spawn rejected | Same `spawn` vs `await spawn`, managed refs, ready/done futures, stop/join and parent failure notices |
| `throw`, `raise`, `panic`, `defer`, `recover`, `trap` | Frontend/runtime support under hardening | Runtime exception-policy helpers, no general source exception lowering | Same unwind precedence, cleanup, suppressed errors, actor containment and typed control-lane events |
| Actor-owned memory | Java heap/FFM actor regions and arenas, more PRs pending | Bounded native actor heaps/arenas; not fully exposed through source actor lowering | Exact-source lifecycle tests: no cross-actor aliasing, stale handles, double-drop or early retirement |
| Untrusted actors | Shared segregated-isolate architecture under development | No matching source-backed untrusted execution model | One untrusted Graal isolate boundary and explicit native isolation policy; prevent privilege/memory leaks |
| Nonblocking I/O and `*_blocking` | Async transport migration underway | No imported source I/O registry or continuation integration | Both forms use admitted async operations, release carriers, close/cancel safely and produce same errors |
| Capability/environment admission | Opt-in import closure gates emerging | Rejection-only target fixtures | Same transitive imports, denied effects and native target ABI behavior |
| GPU/quantum source constructs | Emerging typed lowering behind PRs | Distinct experimental native kernel builders/PRs | Same typed IR validation, source execution and device/fallback policy before advertising parity |

**Do not merge broad feature stacks merely because their standalone helper
tests pass.** Reconcile parent branches, run exact combined-head toolchains,
and require source-level differential tests for each row.

## Current normative fixture coverage is very small

`contracts/v1/semantics/cases.json` contains **11 cases**. With the
signed-i64 literal overflow diagnostic implemented and tested on Graal,
**10 now require both backends** and **1** ownership/mutation diagnostic
remains LLVM-pending. The expanded inventory still does not test general
reference/actor semantics.
The comparator's default mode is therefore a progress inventory, not
a parity signoff. The new `--require-suite-parity` flag rejects pending
cases and requires exact pinned backend Git SHAs.

Even when all 11 become required and pass, this alone cannot establish
language-wide parity: no shared 11-case suite covers the lifecycle,
async/select, fault isolation and memory matrix above.

## Nonnegotiable release gates

- Run a common versioned source-fixture corpus on *both* exact compiler
  heads, with semantic acceptance, diagnostic IDs, value ABI and event traces.
- Include compile-fail probes for unsupported features. Rejection without
  emitted IR is a safety gate, **not** evidence of feature support.
- For source actors, test stop-before-ready, race between start and stop,
  throw/panic/recover, parent supervision delivery, mailbox priority under
  saturation, cancellation and quiescent heap retirement.
- Test loans across each suspension boundary: `await`, `readch`,
  `select`, `nb select`, `do nb select`, I/O and future/observable
  subscriptions. Ensure a failed branch releases all losing registrations.
- Run ASan/UBSan, native Clang/CTest, Java/GraalVM runtime tests and seeded
  race/replay tests. Verify physical lifetime, not only logical handles.
- Reject cross-actor pointers; enforce actor-generation fencing, quotas and
  single-winner terminal completion before rescheduling a continuation.
- Pin each artifact to its source Git tree SHA; require real job steps and
  executed tests. A zero-step runner failure is **unverified**.
- Before a parity release, use `compare.py --require-suite-parity` with
  `--llvm-revision` and `--graal-revision` **and** require the expanded
  end-to-end feature matrix above. Neither substitutes for the other.

## Integration order

1. Specify language-wide integer, effect and exception semantics, then add
   shared executable oracles with canonical value/error/trace output.
2. Implement LLVM typed continuation frames and structured control flow,
   including ownership-safe suspension.
3. Integrate LLVM source actors, ActorGroup, channels, select and control-lane
   supervisor failure handling with actor-owned heaps.
4. Complete target-safe async I/O and portable stdlib/capability admission.
5. Integrate isolation, memory/GC, cancellation and performance stress tests
   into exact-head cross-backend CI before merging dependent stacks.

Related work: Java [actor lifecycle PR #574](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/574),
[control lane PR #581](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/581),
[async I/O PR #584](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/584);
LLVM [ActorGroup PR #54](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/54),
[exception-policy PR #64](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/64),
and [ownership/grammar PR #72](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/72).
