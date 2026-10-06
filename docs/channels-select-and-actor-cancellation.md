# Channels, select, actor mailboxes, and cancellation

This document is the source/runtime contract for Oreslang channel waiting,
selection, actor mailbox transport, parent/child lifetimes, and cancellation.

## One concurrency model: actors

Oreslang has one general-purpose concurrency identity: the **actor**.

An `async` callable, `Future<T>`, `Awaitable<T>`, channel wait, or select
registration is not a second goroutine/process model. It is suspension and
continuation machinery owned by an actor execution domain (or by the
runtime/root actor while bootstrapping). Code that wants an independently
concurrent job spawns an actor.

This distinction is intentional:

- actor identity owns mutable state, mailbox order, lifetime, supervision, and
  cancellation;
- a Future represents completion, not a new concurrency identity;
- `await` suspends the owning actor/task continuation;
- Future/channel completion only makes a continuation runnable;
- completion threads never execute guest continuation code inline.

The Java/Truffle reference runtime still contains transitional async plumbing.
That backend detail must not become language semantics.

## Mailbox = actor policy around Channel<Envelope>

Every actor owns exactly one inbound mailbox. The mailbox is **not** a second
queue primitive. Its transport container is an Oreslang channel:

```text
ActorMailbox<M>
  actor id / owner
  policy + quotas
  freeze/copy/sendability rules
  supervision + lifecycle metadata
  scheduling metadata
  Channel<Envelope<M>> transport
```

The mailbox layer remains responsible for:

- actor/runtime affinity;
- private-copy vs shared/frozen transport;
- heap/mailbox quota reservation;
- capability validation;
- ActorRef validation;
- shared-memory restrictions;
- fail-stop actor behavior;
- scheduling the actor when an envelope becomes ready.

The channel owns:

- buffering/rendezvous;
- read/write waiters;
- close state;
- cancellation-safe waiter removal;
- select registration and arbitration.

Runtime-only continuation envelopes use the same mailbox channel but are never
visible as guest messages. They have bounded reserved headroom so a full user
mailbox cannot silently discard a resumed `nb select` arm.

Public `Channel<T>`, `SelectCase`, and `SelectSet` values are
**execution-domain-local capabilities** in this version. They cannot be sent
through an actor mailbox or used as actor-callable parameters/results.
Actor-to-actor communication remains `ActorRef`/mailbox transport. This avoids
letting a raw channel object bypass actor isolation and share mutable guest
objects by reference. A future cross-actor channel capability would need an
explicit copy/freeze/ownership-transfer contract before it can be admitted.

## Parent and child actors

Parenthood is a **structured lifetime/supervision relation**, not a second
hidden communication transport.

Holding an `ActorRef` grants message-send authority, not arbitrary lateral
termination authority. Inside actor code, lifecycle control is limited to the
actor itself and its structured descendants. An actor cannot stop/cancel a
parent, sibling, or unrelated actor merely because it was given that actor's
reply/recipient reference. Host/supervisor code remains able to control any
actor in its runtime. Actor-initiated child stop/cancel is nonblocking so it
never parks a scheduler carrier waiting for child finalization.

When actor A spawns actor B during A's turn:

1. B is registered as A's child.
2. The spawn result gives A the typed ActorRef/recipient capability used to send
   messages to B.
3. B communicates back to A by receiving/passing an ActorRef/Recipient for A
   when its protocol needs that capability.
4. Both directions still send into the destination actor's mailbox channel.
5. Stopping/failing/cancelling A cancels its structured descendants.
6. A is not fully finalized until its child set is finalized.

There is no scheduler or full ActorMailman per actor. Schedulers/thread pools
remain shared runtime resources; a parent/child edge does not create a new
thread pool, scheduler, or queue.

A future typed parent endpoint can make step 3 more ergonomic, but it must
remain an ActorRef/Recipient capability rather than an ambient mutable parent
object.

## Channel operations

A bounded channel is created with an explicit element type:

```ores
val Channel<int> input = Channel.new<int>(64);
```

Capacity zero is a rendezvous/unbuffered channel.

### Blocking/suspending forms

```ores
val msg = readch input;
writech output, msg;
```

"Blocking" means **suspend the owning Ores continuation and release the carrier**.
It never means park a bounded actor carrier thread.

The target lowering is:

```text
readch ch
  -> ch.read_async()
  -> Future<T>
  -> suspend current actor state machine
  -> return to scheduler
  -> requeue actor continuation when Future settles

writech ch, value
  -> ch.write_async(value)
  -> Future<void>
  -> same suspension path
```

The current reference interpreter executes already-ready blocking operations
and fails closed if a pending actor operation would otherwise require parking a
carrier. Full source-frame lowering to the existing OresScheduler resumable
Task ABI is the remaining implementation step. This is deliberately safer than
sync-over-async.

### Nonblocking registration forms

```ores
val Future<int> pending_read = nb readch input;
val Future<void> pending_write = nb writech output, value;
```

`nb` means **register and return immediately**. It does not mean "probe once."

This distinction is important. A pending `nb readch` remains registered until
it completes or is cancelled.

### Immediate probe forms

```ores
val Option<int> now = try readch input;
val bool wrote = try writech output, value;
```

`try` means **succeed now or return immediately without leaving a waiter**.

Immediate probes and select registrations use the same channel arbitration, so
an immediate write can rendezvous with a pending select-read and an immediate
read can rendezvous with a pending select-write.

## Static select

Canonical static syntax:

```ores
select {
  case readch incoming: let msg {
    stdio.println("Received:", msg);
  }
  case writech outgoing, payload: {
    stdio.println("Sent payload successfully");
  }
  case readch shutdown: const signal {
    return;
  }
}
```

A read arm may bind with `let`, `val`, or `const`. `const` means the
selected runtime value is bound immutably; it does not imply the message was a
compile-time constant.

Every `case` and `default` arm requires its own `{ ... }` body, including
empty arms. Canonical source uses two spaces per indentation level and no tabs
for indentation: arms sit one level inside `select`, and their statements sit
one level inside the arm. The same rules apply to `nb select` and `try select`.
Legacy unbraced arms are rejected by the parser; `oresfmt` migrates them.

A select may include one `default: { ... }` arm.

### Deterministic selection policy

Oreslang does **not** copy Go's pseudo-random default case choice.

The default is:

```ores
select { ... }       // FAIR
```

FAIR is deterministic round-robin over a stable select site/set. If more than
one case is simultaneously ready, the next fairness cursor chooses the first
probe position. Static select sites retain a rotation ticket across repeated
executions; reusable dynamic SelectSet values retain their own cursor.

Explicit strict priority:

```ores
select first {
  case readch control: const command {
    ...
  }
  case readch data: let value {
    ...
  }
}
```

Explicit random choice, only when the program actually wants it:

```ores
select random {
  ...
}
```

Fairness policy governs the probe order among cases observed ready together.
It cannot reverse a case that has already atomically won a readiness race.

## Nonblocking static select

```ores
nb select {
  case readch incoming: let msg {
    stdio.println("Received:", msg);
  }
  case readch payload: const body {
    stdio.println("Received payload:", body);
  }
  case readch shutdown: const signal {
    return;
  }
}
```

Semantics:

1. evaluate/arm the case set;
2. register one select operation;
3. return immediately to the current actor code;
4. exactly one case wins;
5. Future completion enqueues an internal continuation envelope back to the
   owning actor;
6. only a later serialized actor mailbox turn executes the selected branch.

The branch never runs on a producer/I/O/Future callback thread and never runs
concurrently against that actor's state.

Because the enclosing stack has already continued, a nonblocking arm is a
detached `void` continuation scope:

- `return;` exits the arm itself;
- `return value;` is invalid;
- `break`/`continue` cannot escape into an enclosing loop that has already
  continued;
- Copy values and locally-copyable channel/select handles may be captured while
  the current turn continues;
- move-only owned locals referenced by any arm transfer into the armed
  selection, so the continuing outer code cannot use them afterward;
- mutable captures transfer exclusively into the continuation;
- ordinary borrowed locals and MutexGuard-bearing values cannot outlive the
  current turn through an `nb select` arm;
- actor `self` is the deliberate borrowed exception because the arm can only
  re-enter the same actor under its single-turn execution lease.

Capture transfer is conservative across the whole arm set: if any possible arm
owns a move-only value, that value belongs to the armed selection until one arm
wins or the selection is cancelled.

If the owning actor terminates before the select wins, actor teardown cancels
the pending select Future and detaches all channel registrations.

## Dynamic select

Static and dynamic select lower to the same runtime `SelectSet` primitive.

Cases can be assembled at runtime:

```ores
val Channel<int> a = Channel.new<int>(16);
val Channel<int> b = Channel.new<int>(16);

val Array<SelectCase> cases = [
  SelectCase.read(a),
  SelectCase.write(b, 42)
];

val SelectResult result = select from cases;
val Future<SelectResult> pending = nb select from cases;
val Option<SelectResult> ready = try select from cases;
```

A reusable set can retain its fairness cursor:

```ores
val set = SelectSet.new(cases);
val result = select from set;
```

Runtime list/array and map values are accepted. For maps, value iteration order
defines the case order used by `first` and the initial deterministic fair
ordering.

Dynamic policies use the same spellings:

```ores
select first from cases
select fair from cases
select random from cases
nb select first from cases
try select from cases
```

`SelectResult` exposes:

- `index`
- `operation` (`read`, `write`, or `default`)
- `value` for a read result

## Cancellation

Oreslang does not require Go-style `context.Context` propagation through every
function just to stop work.

### Structured cancellation

Ordinary actor cancellation is structured:

- mark the actor stopped immediately;
- close its mailbox channel;
- prevent new message/continuation admission;
- cancel its pending actor-owned channel/select registrations, including raw
  `nb readch`, `nb writech`, dynamic `nb select from ...`, and deferred
  static-select continuations;
- drain/release mailbox resource reservations;
- cascade cancellation to structured child actors;
- running trusted/co-resident actor code observes an uncatchable control-plane
  unwind at scheduler safepoints;
- language `finally`/`defer` cleanup may run during that controlled unwind;
- external actor finalization waits for running turns and structured children to
  leave.

Cancellation is control flow, not a normal guest exception. An Oreslang
`try/catch` cannot swallow the cancellation signal and keep the actor alive.

Carrier-thread interruption is deliberately **not** an actor cancellation
signal. Actors are multiplexed over shared carriers, so a Java/native worker
interrupt caused by executor shutdown or host machinery cannot be attributed to
the actor currently occupying that carrier. Structured cancellation is keyed by
actor/runtime state; non-cooperative untrusted termination is keyed by the
revocable isolate/domain boundary. This keeps carrier identity completely
separate from actor identity.

### Force cancellation for untrusted actors

Force cancellation is a **host/supervisor authority**, not an ordinary actor
capability. Actor turns may request normal structured cancellation, but they
cannot invoke the isolate-revocation hook themselves.

Untrusted code cannot be expected to poll, yield, honor callbacks, or run
cleanup. Therefore **untrusted actors must run inside a host-revocable execution
boundary** (for example the dedicated untrusted Graal/native isolate/domain in
the hardened OresVM stack).

`forceCancel` follows this order:

1. ask the outer isolation manager to revoke/terminate the actor's isolated
   execution domain;
2. require positive confirmation that guest execution can no longer continue;
3. only then perform logical actor/mailbox/child teardown.

If no revocable boundary is installed, force cancellation fails closed and has
no logical side effect. It must never pretend that interrupting a shared
dispatcher carrier is equivalent to killing an isolated actor.

For force-killed untrusted code, guest cleanup is **not trusted and not
required**. The outer runtime owns deterministic reclamation of:

- isolated heap/arena;
- mailbox reservations;
- host request/response capabilities;
- file/socket/native handles granted through runtime-owned capabilities;
- pending channel/select registrations;
- child execution domains where the sandbox policy permits children.

This is the Erlang-style safety property Oreslang wants: once isolation is real,
termination does not depend on cooperation from hostile guest code.

## Cancellation races

Channel and select cancellation use one atomic arbitration state.

If cancellation wins:

- the waiter/selection is detached;
- it cannot later consume a channel value;
- a later value remains available to another reader/select.

If a channel case wins first:

- cancellation returns false for that already-claimed operation;
- exactly one case completes;
- no second case may consume or publish a result.

This same rule applies to static select, dynamic select, read waiters, and write
waiters.

## Implementation status in PR #252

Implemented:

- Ores-owned bounded/rendezvous Channel;
- cancellable async read/write registrations;
- immediate probes interoperating with select;
- deterministic FAIR / explicit PRIORITY / opt-in RANDOM selection;
- dynamic SelectSet from iterable/map;
- mailbox transport backed by Channel<MessageEnvelope>;
- static/dynamic parser + AST + type/ownership rules, including complete
  closure-capture scanning for channel/select syntax and explicit deferred
  `nb select` capture transfer;
- `nb select` serialized actor continuation re-entry;
- actor-owned cleanup of pending `nb readch`, `nb writech`, dynamic
  `nb select from ...`, and deferred static-select registrations;
- structured parent/child cancellation;
- uncatchable actor cancellation safepoints;
- force-cancel isolation-revocation contract;
- bounded internal continuation headroom that is accounted separately from
  the configured user-message quota, so runtime control traffic cannot silently
  shrink `maxMailboxMessages`.

Remaining compiler/runtime integration:

- lower arbitrary pending blocking `readch`, `writech`, `select`, and
  ordinary `await` from actor source frames into the existing resumable
  OresScheduler Task/state-machine ABI;
- connect the force-cancel hook to the dedicated untrusted-isolate runtime stack
  when that stack is reconciled onto current main;
- migrate transitional free-standing async backend behavior so every source
  async continuation is explicitly owned by an actor/root-actor domain.

## Streaming writes to channels

A stream is a sequence of individual writes. Use `for ... of ...` for an array
or synchronous iterator, or `for await ... of ...` for an `AsyncIterator<T>` in
an async callable. No bulk-write syntax is needed:

```ores
fnc send_values(Channel<int> output, Array<int> values) -> void {
  for const value of values do
    writech output, value;
  done
  return;
}

async generator fnc events() -> int {
  yield 1;
  yield 2;
  return;
}

pub async routine main() -> void {
  val Channel<int> output = Channel.new<int>(2);
  val AsyncIterator<int> source = events();
  for await const value of source {
    await nb writech output, value;
  }
  stdio.println(readch output);
  stdio.println(readch output);
  return;
}
```

`writech` waits for each write to complete. `await nb writech` also waits for
each write's completion before pulling the next value, preserving order and
backpressure without accumulating pending writes. An unawaited `nb writech`
returns a `Future<void>` immediately; retain and await it when completion
matters. A full bounded channel or a zero-capacity rendezvous channel needs a
reader to make progress. The example buffers the whole two-element stream;
longer streams should have an active reader in the owning concurrency domain.
Channels cannot be passed as ordinary async-callable parameters. An async
iterator is consumed in its owning task; it is not transferred to another task.
Breaking the loop closes the iterator activation and runs its cleanup.

See `examples/channel-streaming.ores` for an executable example of both loop
forms. Streaming tests cover arrays, synchronous and asynchronous iterators,
blocking and awaited nonblocking writes, rendezvous backpressure, early iterator
cleanup, braced select dispatch, and invalid element/iterator types.
