# Actor class lifecycle and parent-facing output streams (design + implementation status)

**Status (2026-10-07): actor declarations, source `spawn Actor()` execution, `ActorRef<Actor>`, actor-local `on_start()`, typed mailbox dispatch, bounded parent-facing outputs, `self.send`, `self.end`/`self.endWithCleanup`, ready/done lifecycle barriers, actor-only lexical dependency boundaries, and same-OresVM immutable shared-code images are implemented and regression-tested. Stream merge/`for await` integration remains a separate acceptance gate.**

The existing `ActorRuntime` already has `ActorRef.send/stop/cancel/forceCancel`,
bounded channel-backed mailboxes, actor identity, child supervision, and
`rt cooperate` continuation support. The Java `ActorMail<Out>` record is
**already used for ActorGroup Mailman outbox messages**. Do not silently
repurpose it as a class actor's inbound message.

## Target `main.ores` (partially implemented end-to-end target)

```ores
// Actor creation, lifecycle hooks, mailbox delivery, outputs, ready/done,
// self.send and self.end are executable today. The merge/for-await portion
// remains a contract target until stream-composition lowering is complete.
define actor Worker as
  on_start(): void {
    // Actor-local startup hook; actor constructors stay forbidden.
    return;
  }

  receive(ActorMail<String> mail): void {
    if mail.value != "start"; do
      return;
    fi

    self.send("hello");
    rt cooperate;
    self.send("world");
    rt cooperate;
    self.send("!");
    self.end();
    return;
  }
end

pub async routine main(): void {
  val workers = [spawn Worker(), spawn Worker(), spawn Worker()];
  await Future.all([workers[0].ready, workers[1].ready, workers[2].ready]);

  // Creating an actor does not automatically send a receive() message.
  for const worker of workers do
    worker.send("start");
  done

  // A parent may subscribe/merge all three bounded output streams. Each
  // worker's per-stream order is hello, world, !; cross-worker order is free.
  val streams = [workers[0].outputs, workers[1].outputs, workers[2].outputs];
  for await const output of merge(streams) do
    stdio.println(output.actor_id, output.sequence, output.value);
  done

  await Future.all([workers[0].done, workers[1].done, workers[2].done]);
  return;
}
```

This illustration uses implemented `spawn`, `ActorMail`, `outputs`, `ready`
and `done` actor surfaces plus still-evolving `merge`/`for await` stream
composition. The actor lifecycle/transport subset is covered by executable
source tests; the complete illustration is not yet an acceptance fixture. In particular,
`return await w.ready` returns a readiness result, **not** `w` or a list
of output strings.

## Canonical API split

| Surface | Signature (conceptual) | Contract |
| --- | --- | --- |
| `spawn Worker()` | `ActorRef<Worker>` | Allocates actor identity; reserved on_start hook runs in actor execution context, not the caller |
| `w.id`, `w.kind` | `ActorId`, `ActorKind` | Immutable identity/diagnostics |
| `w.ready` | `Future<void>` | Completes once actor-local field initialization and reserved `on_start()` succeed; fails if startup fails/stops |
| `w.send(mail)` | `void` or explicit nonblocking variant | **Inbound**, serialized, bounded actor mailbox; reject capability/pointer/function payloads |
| `w.outputs` | `AsyncIterator<ActorOutput<T>>` or read-only `Channel<ActorOutput<T>>` | **Outbound**, bounded, async/backpressure-capable, parent-observable stream |
| `w.done` | `Future<void>` | Settles after actor finalization and isolate/carrier cleanup |
| `w.stop()`, `w.cancel()` | `void`, `bool` | Graceful stop vs structured cancellation (supervisor authority checks apply) |
| `w.kill()` | `bool` | Force-isolated cancellation only if revoker is configured; never thread interruption masquerading as isolation |
| `self.send(value)` | `Future<void>` or nonblocking admission acknowledgement | **Outbound** from actor to its output stream; NOT recursive delivery to own inbox |
| `self.end()` | `void` | Non-blocking graceful stop request; seals/drains the inbox immediately so no later `receive()` starts; current turn may finish, then finalization closes outputs after admitted values drain |
| `self.endWithCleanup(|| -> { ... })` | `void` | Same immediate inbox fence as `self.end()`, plus one synchronous zero-arg final cleanup action after the current turn unwinds and before outputs close / `done` settles |
| `receive(ActorMail<T>)` | `void` | Lifecycle handler invoked once per incoming envelope, serialized and actor-local |
| `rt cooperate` | `void` scheduling effect | Suspension/safepoint; no implicit publication or message delivery |

`self.send(value)`, `self.end()`, and `self.endWithCleanup(...)` are **non-overridable runtime actor operations**. Actor classes may not declare instance methods named `send`, `end`, or `endWithCleanup`, and source code does not use `super.send(...)` to reach transport.
This keeps quota checks, serialization, termination fences, and output-stream
ordering outside user-overridable dispatch.

`self.end()` is intentionally **not overloaded**. Cleanup uses the separate
`self.endWithCleanup(...)` name:

```ores
self.endWithCleanup(|| -> {
  // final synchronous actor-local cleanup
});
```

The end request itself does not block. As soon as either end API is called, the
actor mailbox is sealed and queued-but-not-started mail is discarded; no later
`receive()` invocation may begin. Code already executing in the current
`receive()` may continue until that turn returns. For `endWithCleanup`, the
cleanup then runs exactly once as the final guest action while actor-local state
is still available. Cleanup cannot be async/suspending; if it throws, `done`
rejects with the cleanup failure.

## Message identities and transport

- Source `ActorMail<T>` is now statically modeled with immutable
  `recipient: ActorId`, `sender: Option<ActorId>`, `sequence: int`, and
  `value: T`. Runtime delivery uses `ActorRuntime.ActorInboxMail<T>`, which
  is deliberately distinct from the existing
  `dev.oreslang.runtime.ActorMail` Group Mailman record.
- Introduce `ActorOutput<T>(actor_id, sequence, value)` for output. The
  output sender is the *emitting* actor, not its parent.
- Send values through the same strict graph walker/serializer/copy rules
  used by mailbox admission. Function closures, raw object pointers,
  borrowed/transient aliases, mutable cross-actor references, and live
  domain-local channels are not permitted on actor mailboxes/output.
- Backpressure must never block an actor carrier or retain unbounded
  `Future`/waiter queues. Define finite pending writer/read quotas,
  cancellation behavior, buffer accounting, and terminal EOF/error results.
- Output readers must not gain permission to read actor-owned heap objects.
  Freezing or copy-across-isolation is mandatory.
- Per actor/mailbox and per output stream ordering is FIFO. Ordering *between*
  workers is nondeterministic. A merged stream must preserve each producer's
  sequence, not promise a global order.
- Parent cancellation/failure should terminate structured children and close
  output streams. `done` observes the existing carrier-exit finalization
  barrier rather than just a return from `receive`.

## Lexical scope / actor isolation boundary

Ordinary modules and ordinary classes retain normal source-unit lexical
visibility. They may refer to sibling functions, modules, classes, interfaces,
and type aliases in the same file without artificial imports.

Actor classes are the exception. A define actor, shared actor, isoactor, or
untrusted actor is a hard executable dependency boundary. Actor fields,
signatures, methods, lifecycle hooks, and lambdas may resolve only:

- actor-owned fields/methods and inherited actor members;
- a directly declared `extends` parent (an explicit declaration edge, still
  subject to actor-kind/isolation compatibility checks);
- explicit imports from another source unit;
- language/compiler/runtime intrinsics admitted by the actor's capability policy;
- parameters and locals introduced inside the actor.

An actor cannot reach a sibling file-level helper, module, class, actor, interface,
or type alias merely because it appears in the same source file. Move reusable
actor dependencies to an importable source unit and import them explicitly.
Same-file import escape hatches such as @self, ., or normalized aliases that
resolve back to the current physical file are rejected.

The type checker and evaluator enforce the same boundary, and declaration origin
survives async/nlex/lambda lowering so closures cannot launder ambient file
access. Capability checks remain a separate layer: importing code never grants a
private or untrusted actor authority that its execution policy forbids.

## Implementation sequencing / acceptance gates

1. **Implemented in this branch:** parse actor declarations; preserve ordinary
   same-file module/class visibility while enforcing actor-only lexical dependency
   boundaries; reject self-import escape hatches; and enforce explicit actor
   inheritance edges without granting ambient sibling access.
2. **Implemented source execution:** `spawn Worker()` lowers to a runtime-owned
   source actor with actor-local field initialization and `on_start()`.
   Constructors and constructor-style spawn arguments remain rejected.
3. **Implemented lifecycle/transport:** `ready`/`done`, typed mailbox
   `ActorMail<T>`, `ActorRef.send`, bounded read-only `outputs`,
   `self.send`, `self.end`, and synchronous `self.endWithCleanup`.
   Mail/output graphs are copied/frozen under actor-boundary rules and live
   domain-local capabilities remain rejected.
4. **Implemented state/code separation:** linked source units publish immutable
   code-image identities once per OresVM. Shared/private/untrusted actors reuse
   those identities while actor fields, select fairness cursors, mailboxes and
   lifecycle state remain actor-owned. Explicit imports form the executable
   linked-code dependency closure; guest code cannot enumerate the code store.
5. **Still separate:** generalized parent-side stream merge/`for await`
   composition and any stronger proof of physical Graal machine-code-page
   sharing across engines or OS processes.
6. Keep exercising overflow/backpressure, cancellation, startup/cleanup
   failures, capability rejection, shared-state isolation, private/untrusted
   policy enforcement, and import isolation in exact-tree CI.
7. Only after green tests for the *exact* head SHA consider merging. If the
   source org cannot allocate a GitHub Actions runner, mirror the **exact Git
   tree** into a funded test org's temporary branch, verify blob/tree identity,
   and run Actions locally to that repo without cross-org secrets.

The source-level `async || -> { ... }` lambda spelling is the required slim-arrow
form. Parser and runtime support for async RHS lambdas and the full worker-stream
example are separate gates; do not silently substitute fat arrows.

```ores
// Canonical slim-arrow spelling; actor spawn is executable. Async RHS-lambda
// completeness remains independently gated where syntax/runtime coverage is missing.
const create = async || -> {
  const worker = spawn Worker();
  await worker.ready;
  return worker;
};
```

Returning `await worker.ready` returns the readiness result (void), not the
actor reference. The proposal fixture still contains stream-composition pieces
that are broader than the now-runnable actor spawn/lifecycle subset.

`SharedCodeImageStore` and `OresContext` now hold one immutable `Ast.Program`
reference per linked source unit for the same OresVM, and `Evaluator.link()`
publishes it once. Existing linked-import lookup returns the shared evaluator.
This is a real same-process JVM code-image reference invariant, not proof that
Graal native/JIT machine-code pages are shared among independent Truffle engines
or OS processes. Actor state and mailbox payloads stay outside the code store. Static-select
fairness is also actor-local: the shared AST supplies only the immutable select
site identity, while each ActorCell owns its mutable cursor. This is the model
to follow for any future inline cache or scheduler metadata—shared code may
reference actor-local runtime state, but must not become a writable state bag
shared by isolated actors.

Source-level `spawn Worker()`, actor startup, mailbox receive, bounded worker
output, `self.send`, `self.end`, `self.endWithCleanup`, and `ready`/`done`
are evaluator-lowered into `ActorRuntime` and covered by executable regression
tests. Do not extend that claim to generalized stream merge/`for await` or
cross-process/native machine-code sharing.
