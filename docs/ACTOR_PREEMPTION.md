# Actor preemption and resumable safepoints

OresVM does **not** schedule actors at source-line boundaries. A source line is
debug metadata and may lower to many runtime operations.

## Two different VM concepts

### 1. Checkpoint

`OresContext.schedulerSafepoint()` / `ActorRuntime.schedulerSafepoint()` is a
poll for runtime state:

- structured actor cancellation,
- runtime shutdown,
- untrusted fuel,
- untrusted wall-time,
- untrusted CPU-time.

A checkpoint does not capture a Java stack and does not release the actor's
logical turn. In particular, `Thread.yield()` is not an actor-preemption
primitive and must not be used to model one.

### 2. Resumable scheduling boundary

A compiler-lowered `OresScheduler.Task<T>` owns a heap-resident continuation.
When it returns `OresScheduler.cooperate()`, the current scheduler dispatch
fully unwinds before the same logical task is queued again.

The task object, not a source line, owns the resume state: its program-counter
state, captured environments/locals, and any other compiler-generated control
state. Completed Ores operations are not replayed.

The scheduler keeps a per-task execution lease. A cooperate resume is published
only after the previous carrier/Truffle turn has exited, so two physical
carriers cannot execute the same logical continuation concurrently.

## Actor single-writer invariant

An actor may occupy at most one active actor execution boundary at a time.
`ActorCell.beginTurn()` fails closed if that invariant is violated.

Suspension or carrier migration therefore must never mean:

```text
actor turn A -> suspend
actor turn B -> mutate the same actor
actor turn A -> resume
```

A future compiler pass that preempts a persistent actor turn must retain the
logical turn lease across the suspension, or otherwise prove an equivalent
non-reentrant actor protocol.

## Safe automatic preemption

Automatic quantum/budget preemption is valid only at a point whose execution
state is already continuation-lowered. The compiler may insert such points at
statement boundaries, loop back-edges, calls, allocation/runtime boundaries,
channel operations, and explicit `rt cooperate`, but only when resumption has
a heap-owned continuation.

Ordinary synchronous evaluator frames are not transparently suspendable merely
because they call `schedulerSafepoint()`. Until they are continuation-lowered,
their safepoints remain cancellation/quota checkpoints.

Untrusted actor fuel/wall/CPU accounting must also survive any future
cooperative handoff before automatic preemption is enabled for untrusted code;
a handoff must never reset a logical task's quota.

## Required invariants

1. **No source-line semantics.** Preemption occurs only at VM-defined resumable
   boundaries.
2. **No replay.** Resumption continues from the saved continuation; completed
   source operations are not repeated.
3. **No overlap.** A logical scheduler task has one execution lease and one
   actor has one active execution boundary.
4. **Carrier release first.** A cooperate resume cannot be admitted until the
   previous guest/Truffle boundary has fully unwound.
5. **Carrier identity is irrelevant.** A resumed task may run on the same or a
   different physical carrier.
6. **Fail closed.** Code without a resumable continuation may be checked or
   cancelled at a safepoint, but it must not pretend to have been suspended.
