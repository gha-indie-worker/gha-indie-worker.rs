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

### Actor-local continuation handoff

Actor-backed source schedulers use a VM-owned continuation lane distinct from
the ordinary user-message FIFO. When the currently executing actor envelope
publishes a resumable source continuation, that continuation has priority over
later user messages and the current actor batch must leave its
`TurnExecutor`/carrier boundary before the continuation is admitted.

This prevents two otherwise subtle violations:

1. `rt cooperate` cannot be implemented as "append to the mailbox and keep
   draining the same batch", because that would retain the physical carrier.
2. A later user message cannot overtake an immediately-ready preemption
   continuation and mutate the actor before the preempted logical task resumes.

A genuinely pending `await` is different: while no continuation is runnable,
the runtime may apply the language's eventual actor-reentrancy policy. Automatic
preemption itself is stricter: once its continuation is ready, it resumes ahead
of later mailbox mutations.

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
   previous guest/Truffle and actor `TurnExecutor` boundaries have fully
   unwound.
5. **Actor continuation priority.** An immediately-ready preemption
   continuation cannot be overtaken by a later user mailbox message.
6. **Carrier identity is irrelevant.** A resumed task may run on the same or a
   different physical carrier.
7. **Fail closed.** Code without a resumable continuation may be checked or
   cancelled at a safepoint, but it must not pretend to have been suspended.


## Control-lane capacity and fail-stop admission

An actor reserves **1,024 continuation slots independently** of
`IsolatePolicy.maxMailboxMessages()`. The counters for user envelopes,
source continuations, and their sum must satisfy:

```text
0 <= queuedUserMessages <= maxMailboxMessages
0 <= queuedContinuations <= 1024
queuedMessages == queuedUserMessages + queuedContinuations
```

All changes are made atomically under the accounting lock. An already-full
control lane cannot borrow an idle user-mailbox slot; conversely, 1,024
queued continuations must not consume even one user-mailbox slot. A failed
internal continuation enqueue fail-stops the actor. Dispatch, stop, cancel,
and finalization must each release every acquired reservation exactly once.

Actor execution-lease admission and retirement are also fallible
invariants. Whether `beginTurn()`, `endTurn()`, or finalization throws,
the carrier must clear `CURRENT_ACTOR_EXECUTION`, `currentActor`, and
`ACTOR_CARRIER` in nested `finally` scopes. A rejected carrier must
not retain ambient actor identity when reused.

## Cancellation is not physical carrier revocation

Structured stop immediately rejects new mailbox admissions and drains
queued work. It does **not** instantaneously release a carrier that is
already running guest code. Final actor termination must be delayed until
that carrier has left the `TurnExecutor`/Truffle boundary and the
single-writer lease is retired.

## UNTRUSTED continuation quota dependency

The runtime currently meters UNTRUSTED execution per physical actor batch;
this is not yet a sufficient quota contract for arbitrary `await` and
resumable source-task handoffs. In particular, fuel, elapsed wall time and
cross-carrier CPU time must belong to the logical task before automatic
preemption can be enabled for UNTRUSTED code (issue #402). The language
currently rejects `rt cooperate` for UNTRUSTED actors, but that guard is
**not** proof that all resumable `await` paths are quota-continuous.
