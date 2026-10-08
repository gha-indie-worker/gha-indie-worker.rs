# ActorGroup Event Bus

This document defines the first OresVM runtime contract for tightly coupled actor
cooperation. It is intentionally layered on top of the actor/channel runtime:
mailboxes remain the default actor-to-actor transport, while an ActorGroup event
bus is an opt-in hot path for solver/search/graph workloads that exchange useful
state repeatedly.

## Separation of responsibilities

The supervision tree and ActorGroup membership are orthogonal.

- The supervision tree answers who owns, cancels, restarts, and observes failure.
- An ActorGroup answers which actors are cooperating on one problem.
- A Mailbox is the private inbox of one actor.
- A Channel is the bounded queue and atomic select/waiter primitive. Each event
  subscription owns a channel; Mailman uses a bounded channel as its outbox.
- An ActorEventBus is runtime infrastructure owned by an ActorGroup. It is not
  an actor and has no central mailbox/dispatcher.

An actor may therefore cooperate with cousins, nephews, or other descendants
without changing structured ownership. Membership requires an unforgeable,
runtime-affine ActorGroupJoinCapability that may be explicitly forwarded through
actor messages. Rotating the capability prevents new joins without ejecting
existing members.

Actor-created groups close automatically when their creator terminates.
Host-created groups live until explicitly closed or until ActorRuntime teardown.
Actor termination or group leave closes that actor's subscriptions, fails pending
reads/writes, and releases unread payload reservations before membership is forgotten.
Group close drains subscriptions and the Mailman outbox. A Mailman callback already
in progress may finish, but its context cannot send after group close.

## Mailbox and Mailman routing

Use ActorRef.send for addressed commands and replies. It enters the target actor's
private mailbox and preserves that actor's transport/isolation policy. Publishing
an event fans out to current subscriptions; it does not invoke actor behavior or
implicitly enqueue a mailbox message. Actors read subscription Futures explicitly.
There is no total order between mailbox arrivals and event arrivals.

An optional Mailman consumes group.emit output on CONTROL carriers in bounded
quanta, one callback at a time. Its FIFO outbox uses ChannelRuntime.tryWrite and
tryRead; a full outbox rejects immediately without registering a pending writer.
Each envelope is frozen once, holds one aggregate-memory reservation, and releases
it on callback completion, admission rejection, dispatch failure, or teardown.

ActorGroupContext.send routes through the ordinary target mailbox, including its
capacity and isolation checks. Holding an ActorRef permits routing outside the
group; membership is not a replacement for the reference capability. Every context
operation requires its owning callback and carrier. Capturing a context, calling it
from another thread, or using it in a later callback fails with SecurityException.
Mailman callback/dispatch failure stops the Mailman and drains queued output; it
has no implicit retry. Overflow rejects only that emit. A successful emit means
outbox admission, not delivery or successful callback completion.

## Delivery policies

A topic has one bounded delivery policy.

### RELIABLE

Every active subscriber is admitted before the publication starts. Per-subscriber
outstanding delivery is bounded. Once the bound is exhausted, publication fails
with EventBackpressureException instead of allocating an unbounded pending-writer
queue.

A successful publication returns a PublishReceipt whose completion Future
resolves when every reliable channel write has been admitted. Waiting for this
Future never blocks an OresVM carrier thread.

Reliable delivery is therefore backpressure, not infinite buffering. Outstanding
buffered plus waiting deliveries are bounded per subscriber by max(capacity + 1,
2 * capacity). Cancelling a publication receipt cancels its pending channel writes;
already admitted deliveries remain readable. Receipt completion acknowledges
admission, not processing, and does not provide persistence or replay.

readAsync also bounds pending reads by the subscribing actor's maxMailboxMessages
policy. Exceeding that limit rejects before installing another channel waiter.
Completion, cancellation, or subscription close releases the pending-read slot.
Cancelling an unread wait does not consume the next event.

### LATEST

LATEST topics have capacity 1. Publishing a newer unread event coalesces the
older event. This is appropriate for monotonic/incumbent-style knowledge such as
current best bounds, latest policy versions, or current consensus state.

### LOSSY

LOSSY topics never wait. A full subscriber queue drops the new event and records
the drop in publication/topic counters. This is intended for telemetry or other
information where freshness matters more than completeness.

## Runtime-owned reductions

The first collective operation is a monotonic double reduction:

- MIN
- MAX

A reduction owns its state in the runtime rather than in any actor. An improving
candidate updates the reduction and publishes a LATEST notification; a
non-improving candidate is ignored. State mutation and notification are ordered
under one reduction-local critical section so concurrent improvements cannot
publish an older bound after a newer one.

The reduction lock is local to that reduction. It is not a global event-bus lock.
Reduction notification topics are runtime-owned: publish and publishSystem reject
these names; only reduceDouble may produce notifications. An ordinary topic cannot
be converted into a reduction. State commits after the topic's close/admission fence
and before notification delivery, so a rejected admission cannot silently advance
its bound.

## Payload ownership and memory

Event values are data-only. Publishing:

1. rejects null and live capabilities such as ActorRef, Shared, SyncCell, mutex
   handles, and ActorGroup join capabilities;
2. freezes the payload once before fanout;
3. reserves the retained payload size against ActorRuntime's aggregate actor
   memory ceiling;
4. fans out the immutable value to subscriber channels;
5. releases retained payload accounting as queued deliveries are read,
   coalesced, cancelled, rejected, or torn down.

This keeps the hot path zero-copy for the current supported subscriber domain
without granting actors shared mutable state.

The accounting is intentionally conservative and represents OresVM policy, not
a claim about exact JVM object layout.

## Isolation boundary in this first implementation

The zero-copy subscriber path currently accepts SHARED actors only.

Private/isolated actors may join a group, publish data-only events, read
runtime-owned scalar reductions, and receive ActorGroup capabilities through
ordinary actor transport, but they may not subscribe to the zero-copy event bus
yet. Their existing isolation contract requires a per-subscriber isolated-copy
delivery backend with private-memory reservations; sharing the same frozen JVM
object directly would weaken that contract.

Until that backend lands, private/isolated actors should receive values through
ordinary actor mailboxes.

This restriction is fail-closed and enforced by ActorEventBus.subscribe(), not
only by source-level checking.

## Performance contract

No source-level latency number such as "10 ms" is guaranteed. OresVM should make
same-process communication much faster than that, but latency depends on carrier
placement, CPU topology, queue contention, payload size, and subscriber count.

The current ChannelRuntime implementation is the correctness substrate. It is
not the final NUMA-aware physical design. Future runtime work may replace the
backend with:

- per-worker or per-NUMA event shards;
- cache-line-aligned SPSC/MPMC queues;
- native ring buffers;
- topic IDs instead of string lookup on the hot path;
- batched fanout;
- topology-aware subscriber placement;
- isolated-copy delivery for private/untrusted actors;
- CPU/GPU buffer handles for large data instead of copying tensor contents.

Those changes must preserve the semantics in this document.

## Intended algorithm families

ActorGroup events are specifically useful for fine-grained cooperative
algorithms such as parallel branch-and-bound/branch-and-cut, SAT/CP clause
sharing, parallel best-first search, MCTS, asynchronous graph propagation,
ADMM/coordinate methods, large-neighborhood search, particle/swarm methods, and
other solvers where independently scheduled workers benefit from prompt shared
knowledge.

The group should carry small control/knowledge messages (bounds, cuts, IDs,
versions, handles, statistics), not repeatedly copy large matrices or tensors.
Large numerical state should use OresVM-owned immutable/buffer handles and
data-oriented CPU/GPU kernels.
