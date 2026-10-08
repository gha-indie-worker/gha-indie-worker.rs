# Formal verification layer

This directory documents the executable formal-methods layer for Oreslang runtime semantics.

## What is proved now

`FormalActorProtocolModelCheckTest` is a bounded explicit-state model checker for the actor protocol/Future hand-off lifecycle. It enumerates every legal transition in the small-state model rather than sampling thread schedules.

`FormalSelectModelCheckTest` separately enumerates three-case select readiness, arbitration, loser detachment, and cancellation.

`FormalChannelRendezvousModelCheckTest` models a zero-capacity channel's read/write waiter lifecycle: second-arrival atomic handoff, cancellation withdrawal, and close-time waiter failure.

`FormalBufferedChannelModelCheckTest` exhaustively explores a capacity-two buffer with three distinct messages, proving capacity, FIFO conservation, nonblocking full-buffer rejection, successful retry after a read frees capacity, one-way write fencing on close, and FIFO draining of values buffered before close.

`FormalControlPlaneLivenessModelCheckTest` models the ActorGroup Mailman/CONTROL-carrier boundary in both the known blocking design and the required cooperative design. It keeps the blocking model as an executable counterexample oracle: a callback that waits while retaining its carrier strands already-queued CONTROL work. The cooperative model proves that suspension returns the carrier and queues the Mailman continuation behind work that was already ready.

`FormalOwnershipDomainModelCheckTest` exhaustively checks the ownership/provenance algebra across root, SHARED, PRIVATE, and UNTRUSTED contexts for primitive, struct, and class values. It is a refinement target for the in-flight allocator/ownership work rather than a claim that every lowering is already on `main`.

`FormalGarbageCollectorLifecycleModelCheckTest` models explicit cleanup, ReferenceQueue notification consumption, retry after cleanup failure, actor-domain retirement, and best-effort context shutdown.

`FormalGcSweepQuantaModelCheckTest` models incremental bounded sweeping with a stable cursor and per-slot cleanup ownership. It proves one sweep quantum claims at most one retired slot, repeated quanta visit every retired slot, cleanup success is terminal, and cleanup failure returns the slot to a retryable state.

`FormalStructuredCancellationModelCheckTest` models a three-level structured actor tree and proves downward-only lifecycle authority, cancellation cascade, bottom-up termination, and atomic whole-subtree force kill.

`FormalProxyLockLivenessModelCheckTest` keeps blocking actor-side proxy lock acquisition as an executable starvation counterexample and models the required cooperative acquisition path: waiter registration releases the carrier, unlock reserves the grant before continuation re-entry, and cancellation removes pending waiters without resurrection.

`FormalForceRevocationModelCheckTest` models hard kill as a two-phase protocol: fence, external revocation, then logical termination. It also explores failed/throwing revokers, mailbox rejection while fenced, overlapping hard-kill rejection, and fence clearing after failure.

The checked invariants are:

1. **At-most-once protocol settlement.** A reply is completed or cancelled, never both and never twice.
2. **Mailbox reservation conservation.** A queued request/continuation owns exactly one bounded mailbox reservation; every terminal state owns zero.
3. **No completion-thread guest execution.** Future completion is notification-only. Guest continuation code can run only after mailbox re-entry.
4. **No resurrection after teardown.** Stop/runtime-close transitions cancel outstanding protocol work and late Future completion cannot revive it.
5. **Serialized actor execution.** A protocol request has one initial guest turn and at most one resumed continuation turn.
6. **Weak progress.** Every reachable live non-terminal actor-protocol state has a path to a terminal reply when progress actions are scheduled.
7. **Exactly-one select commit.** One select consumes at most one ready case and records the same case as its winner.
8. **Atomic loser detachment.** Once a case wins, every losing select registration is detached.
9. **Cancellation is non-consuming.** Cancelling an uncommitted select detaches every registration without consuming any case.
10. **Ownership provenance is explicit.** `rt take`, `rt borrow`, and `rt share` preserve allocation domain; storage-bearing `rt copy` creates fresh same-domain storage; primitive copy is allocation-elided.
11. **Proxy is promotion, not copying.** A shared-actor class proxy consumes the raw unique owner and promotes one authoritative target into the proxy domain; PRIVATE/UNTRUSTED/root contexts cannot acquire that authority.
12. **GC cleanup succeeds at most once.** Failed cleanup stays retryable while the registry is open, including after a queue notification was consumed; actor-domain retirement remains reclaimable even with a reachable stale owner.
13. **Context-close cleanup is best effort.** The formal contract explicitly does not mislabel a failed final host cleanup hook as success.
14. **No structured actor orphans.** Parent teardown fences/cancels descendants before parent termination, and lifecycle authority never propagates upward from child to parent.
15. **Rendezvous is exactly one handoff.** A zero-capacity channel completes read and write together; the second arrival cannot leave both waiters pending.
16. **Cancelled channel waiters are withdrawn.** Cancellation cannot consume later traffic, and channel close fails every remaining pending waiter without inventing a delivery.
17. **Hard kill publishes only after revocation.** Logical cancelled termination is unreachable until external revocation succeeds.
18. **Force-kill fences fail closed.** While external revocation is in flight, new mailbox admission and overlapping hard-kill attempts are rejected; false/throwing revokers clear the fence and restore actor progress.
19. **Buffered capacity is invariant.** A capacity-two channel never reaches three buffered values; a full nonblocking write has no state transition.
20. **Buffered FIFO is conserved.** At every reachable state, admitted order equals the delivered prefix followed by the still-buffered suffix, so modeled messages are neither reordered, duplicated, nor lost.
21. **Contended proxy acquisition is carrier-releasing.** The cooperative model never lets a waiting actor retain its physical carrier; unlock reserves the grant before scheduling the continuation, and cancellation prevents resurrection.

22. **Promoted storage outlives actor ownership.** Actor stop cannot reclaim storage rooted by a runtime-owned proxy; disposal of the final promoted root makes reclamation legal.
23. **Proxy locks cannot span suspension or compose implicitly.** Read-to-write upgrades, cross-proxy nested locking, and suspension while locked fail closed in the bounded model; disposed proxies cannot be reacquired.

`FormalActorStorageLifetimeModelCheckTest` explores actor retirement versus promoted-root lifetime. `FormalProxySynchronizationModelCheckTest` explores two proxies, lock modes, suspension and disposal. These are bounded abstract contracts; they do not substitute for concrete runtime refinement tests.

## Relationship to runtime tests

This model is deliberately smaller than `ActorRuntime`. Its purpose is to make the semantic contract reviewable and exhaustive. Integration tests must separately prove that concrete runtime traces refine these transitions.

The companion `async-testing` proof repo contains runtime refinement checks over real channels/select/actors. A runtime change is not considered fully verified merely because the model is green: both the model and implementation-level proofs should pass.

## Running

```sh
mvn -B -Dtest='Formal*ModelCheckTest' test
```

No additional model-checking dependency is required; the finite-state explorer is implemented with ordinary Java/JUnit so it runs anywhere the existing compiler/runtime test suite runs.

## Next formal targets

The next models should cover:

- Keep `BufferedChannelRefinementTest` aligned with the buffered-channel model whenever concrete close/backpressure semantics change.
- Concrete runtime refinement for cooperative proxy-lock acquisition against `FormalProxyLockLivenessModelCheckTest`.
- Concrete runtime refinement for the cooperative Mailman/CONTROL suspension model tracked in #358.
- Concrete trace refinement for allocation-domain/ownership lowering once the #309/#328/#329/#332 stack is reconciled onto current `main`.
- Concrete GC runtime refinement for the now-modeled bounded sweep cursor and cleanup-slot ownership.
- Structured actor failure propagation/restart policy beyond the already-modeled cancellation tree.

For unbounded properties we should add a second tier using an external prover/model checker once its toolchain is pinned reproducibly; this bounded executable layer remains useful as the fast CI gate.
