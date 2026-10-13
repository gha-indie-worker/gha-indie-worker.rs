# Oreslang / OresVM hybrid memory-management contract

Status: architecture and implementation gap audit, 2026-10-10. **Do not mistake
compile-time ownership checking for deterministic native deallocation.**

## Goal

Oreslang should reclaim the vast majority of application objects by **ownership,
moves, and deterministic drops**, not by tracing a garbage-collected heap.
Borrow checking establishes safety; lowering and runtime drop glue must actually
perform reclamation. The two obligations are independent. A fallback collector
must never become a substitute for a rejected borrow or an unimplemented drop.

## Intended allocation and lifetime classes

| Class | Default reclamation | Rules |
| --- | --- | --- |
| Scalar, stack-local, non-escaping value | register/stack lifetime | no heap or tracing |
| Owned object/container | compiler-inserted drop at last owner / scope exit | move transfers the unique owner; recursive field drops exactly once |
| Actor-owned arena | epoch/bulk release after quiescence | every handler, continuation, borrowed slice, pinned I/O buffer, and FFI lease must be dead |
| Explicit shared immutable data | counted or immutable shared backing | crossing actor boundaries requires explicit Send/Share proof |
| Explicit cyclic/shared graph (future) | opt-in tracing or cycle-aware scheme | no implicit promotion from owned references to a managed tracing heap |
| Foreign resource (fd, native buffer, response handle) | linear/RAII close | shutdown, throw, cancellation, and hot reload must all close or transfer safely |
| JVM-backed Graal interpreter objects | host JVM GC | Oreslang cannot disable or replace host JVM tracing; guest ownership restricts aliases, not Java allocation |

Rust ownership is **not** a garbage collector. It makes reclamation
compile-time-directed; exceptions such as intentional cycles in `Rc` graphs
still require explicit `Weak` edges or another reclamation strategy. An
Oreslang opt-in traced graph type must prove roots, barriers, and ownership
boundaries before being advertised as implemented.

## Java/Graal baseline and patch scope

`OwnershipChecker` enforces many lexical alias/move/suspension rules, but
normal guest execution still creates JVM objects. `RuntimeGarbageCollector`
is a **secondary host-resource cleanup registry using weak references**, not
a guest heap tracer. Its cleanup hook must not capture the weakly tracked
owner strongly. The OresVM bootstrap/dispatcher is not itself a heap GC.

This patch specifically changes fallback cleanup housekeeping:

- There is **no periodic task without a tracked foreign cleanup resource**.
- Registering the first resource arms periodic maintenance. Releasing the
  last resource cancels the timer (the shared timer cancels queued tasks).
- Each periodic/public `collectPeriodic()` pass inspects at most **256**
  entries; it makes **zero requests to `System.gc()`**.
- No full-set snapshot allocation is needed before inspecting retry entries
  or retired domains. Empty retired-domain tombstones and stale queue entries
  also consume inspection budget.
- Completed host-cleanup entries sever their actor-domain links and clear their
  weak owner reference; a queued stale notification cannot pin an old actor
  domain merely because its cleanup handle was retained.
- `actor.gc()` remains actor-domain-local and bounded. Actor termination
  eagerly retires its domain. `process.gc()` remains an explicit full sweep
  that can request throttled JVM GC.
- Cleanup callbacks are arbitrary host code: the entry-count bound is
  **not** a bounded-time / hard-realtime proof. Bad callbacks can still stall
  the shared timer. Explicitly close resources; do not rely on weak-reference
  enqueue timing or finalization for correctness.

These properties reduce work; they do **not** deliver a zero-JVM-GC Java
execution profile. Production ownership-based deallocation needs native
object layout and drop glue, or safe compiler escape/stack allocation.

## Required native lowering contract (not yet implemented)

1. Classify allocation as stack/register, unique heap, actor arena,
   explicitly shared immutable, foreign linear handle, or opt-in traceable.
2. Emit one drop path for **every** control-flow edge: return, break,
   continue, throw, failure propagation, cancellation, forced actor exit,
   supervisor restart, and hot-code generation retirement.
3. Initialize per-field drop flags for partial construction/moves. Never
   drop a moved or uninitialized field; do drop all still-owned fields.
4. Track mutable/immutable borrow lifetimes through closures, channels,
   select plans, async suspensions, resumes, and all FFI callbacks.
5. Actor message send atomically transfers ownership only on successful
   admission. Failed send preserves the sender's obligation to drop.
6. Do not reclaim an async I/O pinned buffer until completion/cancellation
   acknowledgement and generation-fenced producer shutdown.
7. Do not destroy hot-replaced code or actor epochs while any old frame,
   function pointer, callback, or pending operation holds a generation lease.
8. Reject unsupported/ambiguous LLVM source ownership constructs at
   compilation rather than silently compiling unmanaged allocations.

## Required validation before production claims

- Instrument allocation/free/drop counts per actor and process, high-water
  usage, queue depth, cleanup retries, pause latency, and leaked handles.
- Test copy/move/borrow/exclusive aliasing and partial initialization against
  success and every exceptional exit, including nested async cancellation.
- Test closure cycles, shared cycles, weak upgrades, and graph roots only
  once an opt-in cycle collection type exists.
- Stress transfer failure, abandoned mail, actor termination, hot reload,
  pinned I/O, FFI reentrancy, and cross-tenant isolation under sanitizers.
- Benchmark **allocations per request**, **bytes traced per request**,
  **p50/p99/p99.9 latency**, **pause count**, and **max live bytes**, not
  just throughput. Keep Java and LLVM measurements distinct.

**Current boundary:** Java guest values still rely on JVM GC for physical
memory. LLVM has only a constrained native POD arena and rejects Oreslang
ownership syntax. Neither implementation has proven general zero-GC guest
heap execution; only specific native profiles can eventually make that claim.
