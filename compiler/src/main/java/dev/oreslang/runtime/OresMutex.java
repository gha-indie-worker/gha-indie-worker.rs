package dev.oreslang.runtime;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Oreslang synchronization primitives.
 *
 * <p>{@link Local} is an actor/private-domain mutex. It deliberately does not
 * use a JVM lock: the creating semantic execution domain owns it and recursive
 * acquisition is rejected. Shared actors may migrate JVM worker threads without
 * changing that domain. {@link Shared} is an explicit same-process
 * shared-memory capability backed by a JVM synchronizer with poisoning and
 * acquire/release ordering.</p>
 */
public final class OresMutex {
    private OresMutex() { }

    private static long saturatedNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public static <T> Local<T> local(T value) {
        return new Local<>(value);
    }

    public static <T> Shared<T> shared(T value) {
        if (ActorRuntime.currentActorKind() == ActorRuntime.ActorKind.PRIVATE) {
            throw new SecurityException("private actors cannot create SharedMutex<T>");
        }
        IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
        if (actorPolicy != null) {
            actorPolicy.require(
                    IsolatePolicy.Capability.SHARED_MEMORY,
                    "SharedMutex.new");
        }
        return new Shared<>(value);
    }

    /**
     * Runtime-owned future for a lexical MutexGuard.
     *
     * <p>Callers may observe or cancel it, but may not forge completion,
     * timeout-complete it, or obtrude a value. Otherwise a queued lock request
     * could be detached from the mutex's domain/permit accounting.</p>
     */
    public static final class GuardFuture<T> extends CompletableFuture<Guard<T>> {
        private GuardFuture() { }

        private boolean completeFromRuntime(Guard<T> guard) {
            return super.complete(guard);
        }

        private boolean failFromRuntime(Throwable failure) {
            return super.completeExceptionally(failure);
        }

        @Override
        public boolean complete(Guard<T> value) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public CompletableFuture<Guard<T>> completeAsync(
                java.util.function.Supplier<? extends Guard<T>> supplier) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public CompletableFuture<Guard<T>> completeAsync(
                java.util.function.Supplier<? extends Guard<T>> supplier,
                java.util.concurrent.Executor executor) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public CompletableFuture<Guard<T>> orTimeout(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("use Oreslang timed lock acquisition, not GuardFuture.orTimeout");
        }

        @Override
        public CompletableFuture<Guard<T>> completeOnTimeout(
                Guard<T> value,
                long timeout,
                TimeUnit unit) {
            throw new UnsupportedOperationException("use Oreslang timed lock acquisition, not GuardFuture.completeOnTimeout");
        }

        @Override
        public void obtrudeValue(Guard<T> value) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public void obtrudeException(Throwable ex) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }
    }

    /**
     * Runtime-owned aggregate contract used to inspect values protected by
     * SharedMutex at actor-transport boundaries. Implementations must expose
     * every transitively reachable child that can carry capabilities/state.
     */
    public interface SharedState {
        Iterable<?> sharedStateChildren();
    }

    public sealed interface Lock<T> permits Local, Shared {
        Guard<T> lock();
        Optional<Guard<T>> tryLock();
        Optional<Guard<T>> lockFor(Duration timeout);
        CompletableFuture<Guard<T>> lockAsync();
        CompletableFuture<Guard<T>> lockAsyncFor(Duration timeout);
        <R> R withLock(Function<? super T, ? extends R> body);
        boolean isPoisoned();
    }

    /**
     * Linear lock capability. Oreslang lowering owns guard release; guest code
     * may release early, but the mutex itself has no public unlock operation.
     */
    public interface Guard<T> extends AutoCloseable {
        T value();
        boolean released();
        void release();

        /**
         * Releases after an abnormal critical-section exit. Shared mutexes are
         * poisoned; actor-local mutexes simply release because their state is
         * confined to the failing actor/private domain.
         */
        void fail();

        @Override
        default void close() {
            release();
        }
    }

    public static final class RecursiveLockException extends IllegalStateException {
        public RecursiveLockException(String kind) {
            super(kind + " is non-reentrant; recursive acquisition is forbidden");
        }
    }

    /** A different actor execution segment already holds a nonparking local mutex. */
    public static final class MutexContendedException extends IllegalStateException {
        public MutexContendedException() {
            super("Mutex<T> is held by another execution segment; use try_lock() or scheduler-owned logical admission");
        }
    }

    public static final class WrongMutexDomainException extends IllegalStateException {
        public WrongMutexDomainException(String message) {
            super(message);
        }
    }

    public static final class PoisonedMutexException extends IllegalStateException {
        public PoisonedMutexException() {
            super("SharedMutex is poisoned because a previous critical section exited abnormally; call recover(...)");
        }
    }

    public static final class LockTimeoutException extends IllegalStateException {
        public LockTimeoutException(Duration timeout) {
            super("mutex acquisition timed out after " + timeout);
        }
    }

    public static final class DeadlockDetectedException extends IllegalStateException {
        public DeadlockDetectedException() {
            super("SharedMutex wait would create a cross-mutex deadlock cycle");
        }
    }

    /**
     * Actor/private-domain mutex. The stable actor domain authorizes access,
     * but each physical dispatch has a distinct execution-segment identity.
     * Atomic admission is required even for actor-local state: multiple
     * carrier threads may execute the same actor when explicitly configured.
     *
     * <p>No JVM thread parks on contention. Local lock_async() retains its
     * existing immediate-acquisition contract and fails closed when busy;
     * queued logical admission must be provided by the actor scheduler.</p>
     */
    public static final class Local<T> implements Lock<T> {
        private final T value;
        private final Object ownerDomain;
        private final AtomicReference<Object> holderSegment = new AtomicReference<>();

        private Local(T value) {
            this.value = value;
            this.ownerDomain = ActorRuntime.currentExecutionDomain();
        }

        private void requireOwnerDomain() {
            if (!Objects.equals(ActorRuntime.currentExecutionDomain(), ownerDomain)) {
                throw new WrongMutexDomainException(
                        "Mutex<T> is actor/private-domain state and cannot be accessed from another actor/execution domain; use SharedMutex<T>");
            }
        }

        @Override
        public Guard<T> lock() {
            requireOwnerDomain();
            Object segment = ActorRuntime.currentMutexExecutionSegment();
            if (!holderSegment.compareAndSet(null, segment)) {
                if (holderSegment.get() == segment) {
                    throw new RecursiveLockException("Mutex<T>");
                }
                throw new MutexContendedException();
            }
            return new LocalGuard(segment);
        }

        @Override
        public Optional<Guard<T>> tryLock() {
            requireOwnerDomain();
            Object segment = ActorRuntime.currentMutexExecutionSegment();
            if (!holderSegment.compareAndSet(null, segment)) return Optional.empty();
            return Optional.of(new LocalGuard(segment));
        }

        @Override
        public Optional<Guard<T>> lockFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            return tryLock();
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsync() {
            GuardFuture<T> future = new GuardFuture<>();
            try {
                future.completeFromRuntime(lock());
            } catch (Throwable failure) {
                future.failFromRuntime(failure);
            }
            return future;
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsyncFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            return lockAsync();
        }

        @Override
        public <R> R withLock(Function<? super T, ? extends R> body) {
            Objects.requireNonNull(body, "body");
            Guard<T> guard = lock();
            try {
                R result = body.apply(value);
                guard.release();
                return result;
            } catch (RuntimeException | Error failure) {
                guard.fail();
                throw failure;
            }
        }

        @Override
        public boolean isPoisoned() {
            return false;
        }

        private final class LocalGuard implements Guard<T> {
            private final Object owningSegment;
            private final AtomicBoolean released = new AtomicBoolean();
            private final TaskOwnedLeases.Lease taskLease;

            private LocalGuard(Object owningSegment) {
                this.owningSegment = owningSegment;
                // Registration is part of acquisition, not mutex creation.
                // If the tracked Task cannot admit the lease, roll back the
                // already acquired CAS gate before reporting the failure.
                try {
                    taskLease = TaskOwnedLeases.registerIfTracked(
                            this::releaseFromRuntime, this::releaseFromRuntime);
                } catch (RuntimeException | Error admissionFailure) {
                    releaseFromRuntime();
                    throw admissionFailure;
                }
            }

            private void requireOwningSegment() {
                requireOwnerDomain();
                if (taskLease != null) taskLease.requireCurrentOwner();
                // Identity is intentional: record value equality would merge
                // separate physical actor turns in the same execution domain.
                if (ActorRuntime.currentMutexExecutionSegment() != owningSegment) {
                    throw new WrongMutexDomainException(
                            "MutexGuard belongs to another physical actor execution segment; state borrows cannot cross a turn boundary");
                }
            }

            @Override
            public T value() {
                requireOwningSegment();
                if (released.get() || holderSegment.get() != owningSegment) {
                    throw new IllegalStateException("MutexGuard has been released");
                }
                return value;
            }

            @Override public boolean released() { return released.get(); }

            @Override
            public void release() {
                requireOwningSegment();
                if (taskLease == null) releaseFromRuntime();
                else taskLease.close();
            }

            /** Only invoked by the owner or after physical Task quiescence. */
            private void releaseFromRuntime() {
                if (!released.compareAndSet(false, true)) return;
                if (!holderSegment.compareAndSet(owningSegment, null)) {
                    throw new IllegalStateException("MutexGuard owner segment changed before release");
                }
            }

            @Override
            public void fail() {
                release();
            }
        }
    }

    /**
     * Explicit same-process shared-memory mutex. This is intentionally not a
     * distributed lock and must not be serialized across OS-process/Graal
     * isolate boundaries.
     */
    public static final class Shared<T> implements Lock<T> {
        private static final int MAX_ASYNC_WAITERS = 8_192;
        private static final int MAX_GLOBAL_ASYNC_WAITERS = 32_768;
        private static final AtomicInteger GLOBAL_ASYNC_WAITERS = new AtomicInteger();
        private static final ConcurrentHashMap<Object, Shared<?>> WAITING_ON =
                new ConcurrentHashMap<>();
        private static final ScheduledExecutorService ASYNC_TIMEOUTS =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform()
                                .daemon(true)
                                .name("ores-shared-mutex-timeouts")
                                .factory());

        private final T value;
        private final Semaphore permit = new Semaphore(1, true);
        private final AtomicBoolean poisoned = new AtomicBoolean();
        private final AtomicInteger asyncWaiters = new AtomicInteger();
        private final AtomicReference<Object> currentOwnerDomain = new AtomicReference<>();
        private final Object asyncQueueLock = new Object();
        private final ArrayDeque<AsyncWaiter> asyncQueue = new ArrayDeque<>();
        private boolean preferAsyncHandoff = true;
        /*
         * Runtime ownership is publication-aware. A send may need to reserve
         * ownership before mailbox admission so a receiver can never observe an
         * unbound SharedMutex, but a failed admission must not permanently bind
         * the handle. pendingPublications + publishedToRuntime provide that
         * two-phase contract.
         */
        private ActorRuntime owningRuntime;
        private int pendingPublications;
        private boolean publishedToRuntime;
        private final Set<Object> activeDomains = ConcurrentHashMap.newKeySet();

        private Shared(T value) {
            this.value = value;
        }

        synchronized boolean bindToRuntime(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            if (owningRuntime == null) {
                owningRuntime = runtime;
            } else if (owningRuntime != runtime) {
                return false;
            }
            publishedToRuntime = true;
            return true;
        }

        synchronized boolean reserveRuntimePublication(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            if (owningRuntime == null) {
                owningRuntime = runtime;
            } else if (owningRuntime != runtime) {
                return false;
            }
            pendingPublications++;
            return true;
        }

        synchronized void commitRuntimePublication(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            if (owningRuntime != runtime || pendingPublications <= 0) {
                throw new IllegalStateException("SharedMutex publication commit without matching reservation");
            }
            pendingPublications--;
            publishedToRuntime = true;
        }

        synchronized void abortRuntimePublication(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            if (owningRuntime != runtime || pendingPublications <= 0) {
                throw new IllegalStateException("SharedMutex publication abort without matching reservation");
            }
            pendingPublications--;
            if (pendingPublications == 0 && !publishedToRuntime) {
                owningRuntime = null;
            }
        }

        void inspectForTransport(java.util.function.Consumer<? super T> inspection) {
            Objects.requireNonNull(inspection, "inspection");
            final boolean acquired;
            try {
                acquired = permit.tryAcquire(0L, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "SharedMutex transport inspection interrupted");
            }
            if (!acquired) {
                throw new IllegalStateException(
                        "SharedMutex cannot be published while locked or contended; retry after the current critical section completes");
            }
            try {
                inspection.accept(value);
            } finally {
                releasePermitOrHandoff();
            }
        }

        private void requireActorAccess() {
            if (ActorRuntime.currentActorKind() == ActorRuntime.ActorKind.PRIVATE) {
                throw new SecurityException("private actors cannot access SharedMutex<T>");
            }
            IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
            if (actorPolicy != null) {
                actorPolicy.require(
                        IsolatePolicy.Capability.SHARED_MEMORY,
                        "SharedMutex operation");
            }

            ActorRuntime current = ActorRuntime.currentActorRuntime();
            if (current != null && !bindToRuntime(current)) {
                throw new WrongMutexDomainException(
                        "SharedMutex belongs to another ActorRuntime");
            }
        }

        private void rejectBlockingActorAcquisition() {
            requireActorAccess();
            if (ActorRuntime.inActorExecution()) {
                throw new WrongMutexDomainException(
                        "blocking SharedMutex.lock()/lock_for()/with_lock() is forbidden during actor execution; use try_lock() or await lock_async()");
            }
        }

        /**
         * Reserve the semantic execution domain before waiting. This prevents a
         * single actor from queueing a second acquisition behind itself and
         * deadlocking, even when the actor migrates JVM workers.
         */
        /**
         * Reference-identity key, not record equality. ActorExecutionContext
         * is a record: two distinct concurrent actor dispatches can compare
         * equal by value even though they own DIFFERENT physical turns.
         *
         * This host bridge will eventually become a generated/generation-
         * checked numeric owner handle in the AoS/SoA/AoSoA metadata store.
         */
        private static final class LogicalOwner {
            private final Object execution;

            private LogicalOwner(Object execution) {
                this.execution = Objects.requireNonNull(execution);
            }

            @Override public int hashCode() {
                return System.identityHashCode(execution);
            }

            @Override public boolean equals(Object other) {
                return other instanceof LogicalOwner identity
                        && execution == identity.execution;
            }
        }

        // Package-private proof seam: different value-equal actor turns must
        // never collapse into a single logical lock/deadlock owner.
        static Object logicalOwnerKey(Object execution) {
            return new LogicalOwner(execution);
        }

        private static Object currentLogicalExecutionIdentity() {
            // Actor domain != execution owner. Task identity survives await
            // and carrier migration; an untracked actor uses its physical
            // dispatch instance, and an ordinary host uses its Thread.
            Object taskDomain = OresScheduler.currentTaskDomain();
            return taskDomain != null
                    ? taskDomain : ActorRuntime.currentMutexExecutionSegment();
        }

        private static Object currentLogicalLockOwner() {
            // One allocated key per lock admission. Keep guard reads and
            // releases free of additional wrapper allocations.
            return logicalOwnerKey(currentLogicalExecutionIdentity());
        }

        private static boolean currentExecutionOwns(Object ownerKey) {
            return ownerKey instanceof LogicalOwner owner
                    && owner.execution == currentLogicalExecutionIdentity();
        }

        private Object reserveDomain(boolean tryOnly) {
            requireActorAccess();
            Object domain = currentLogicalLockOwner();
            if (activeDomains.add(domain)) return domain;
            if (tryOnly) return null;
            throw new RecursiveLockException("SharedMutex<T>");
        }

        private void releaseDomain(Object domain) {
            if (domain != null) activeDomains.remove(domain);
        }

        private void beginWait(Object domain) {
            Shared<?> existing = WAITING_ON.putIfAbsent(domain, this);
            if (existing != null) {
                if (existing == this) {
                    throw new RecursiveLockException("SharedMutex<T>");
                }
                throw new IllegalStateException(
                        "execution domain is already waiting on another SharedMutex");
            }

            if (wouldCreateDeadlock(domain)) {
                WAITING_ON.remove(domain, this);
                throw new DeadlockDetectedException();
            }
        }

        private void endWait(Object domain) {
            if (domain != null) WAITING_ON.remove(domain, this);
        }

        private boolean wouldCreateDeadlock(Object requesterDomain) {
            Object owner = currentOwnerDomain.get();
            Set<Object> seen = new HashSet<>();
            while (owner != null) {
                if (Objects.equals(owner, requesterDomain)) return true;
                if (!seen.add(owner)) return false;
                Shared<?> ownerWait = WAITING_ON.get(owner);
                if (ownerWait == null) return false;
                owner = ownerWait.currentOwnerDomain.get();
            }
            return false;
        }

        private void markOwner(Object domain) {
            if (!currentOwnerDomain.compareAndSet(null, domain)) {
                throw new IllegalStateException("SharedMutex permit acquired while another owner is recorded");
            }
        }

        private void clearOwner(Object domain) {
            if (domain == null) return;
            if (!currentOwnerDomain.compareAndSet(domain, null)) {
                Object recorded = currentOwnerDomain.get();
                if (recorded != null) {
                    throw new IllegalStateException(
                            "SharedMutex owner-domain accounting mismatch");
                }
            }
        }

        private int asyncWaiterLimit() {
            IsolatePolicy policy = ActorRuntime.currentActorPolicy();
            return policy == null
                    ? MAX_ASYNC_WAITERS
                    : Math.min(MAX_ASYNC_WAITERS, policy.maxMailboxMessages());
        }

        private static boolean reserveWaiter(AtomicInteger counter, int limit) {
            while (true) {
                int current = counter.get();
                if (current >= limit) return false;
                if (counter.compareAndSet(current, current + 1)) return true;
            }
        }

        private boolean reserveAsyncWaiter(int limit) {
            return reserveWaiter(asyncWaiters, limit);
        }

        private static void releaseWaiter(AtomicInteger counter, String scope) {
            int remaining = counter.decrementAndGet();
            if (remaining < 0) {
                counter.incrementAndGet();
                throw new IllegalStateException("SharedMutex " + scope + " async waiter accounting underflow");
            }
        }

        private void releaseAsyncWaiter() {
            releaseWaiter(asyncWaiters, "per-mutex");
        }

        private static void releaseGlobalAsyncWaiter() {
            releaseWaiter(GLOBAL_ASYNC_WAITERS, "global");
        }

        private static <T> GuardFuture<T> failedGuardFuture(Throwable failure) {
            GuardFuture<T> future = new GuardFuture<>();
            future.failFromRuntime(failure);
            return future;
        }

        /**
         * Track a queued async acquisition in the requesting Task, not in
         * the unrelated carrier which eventually grants the permit.
         * Retirement cancels pending admission or poisons/releases an
         * already-granted guard. The handshake is bounded and host-only.
         */
        private final class TaskBoundRequest {
            private GuardFuture<T> future;
            private SharedGuard guard;
            private boolean retired;
            private boolean handoffInFlight;
            // This receipt is registered while the originating Task is still
            // executing, BEFORE any waiter is placed in a public queue.
            private final CompletableFuture<Void> physicalSettlement =
                    new CompletableFuture<>();
            private final TaskOwnedLeases.Lease lease;

            private TaskBoundRequest() {
                lease = TaskOwnedLeases.registerIfTracked(
                        this::releaseExplicitly, this::retireAbandoned);
                if (lease == null) {
                    throw new IllegalStateException("async lock Task ownership was lost");
                }
                try {
                    TaskHandle.registerPhysicalSettlement(physicalSettlement);
                } catch (RuntimeException | Error registrationFailure) {
                    lease.disarmWithoutRelease();
                    throw registrationFailure;
                }
            }

            /** Claim under asyncQueueLock, before any releasing carrier can race retirement. */
            private synchronized void beginHandoff() {
                handoffInFlight = true;
            }

            /** A fair semaphore waiter takes precedence, returning this request to the queue. */
            private synchronized void deferHandoff() {
                handoffInFlight = false;
            }

            /**
             * Finalize the claim on the granting carrier. A successfully
             * published but abandoned guard is revoked only AFTER all
             * synchronous completion subscribers have returned; a cancelled
             * unpublished grant was already reclaimed by this carrier.
             */
            private void finishHandoff() {
                final SharedGuard abandoned;
                final GuardFuture<T> pending;
                synchronized (this) {
                    handoffInFlight = false;
                    abandoned = retired ? guard : null;
                    pending = future;
                }
                try {
                    if (abandoned != null) {
                        abandoned.releaseFromRuntime(isPublishedAsyncGrant(pending));
                    }
                    physicalSettlement.complete(null);
                } catch (RuntimeException | Error failure) {
                    physicalSettlement.completeExceptionally(failure);
                    throw failure;
                }
            }

            private void failHandoff(Throwable failure) {
                // If a carrier aborts during a physical grant transition,
                // success cannot be proven. Quarantine through Task cleanup.
                physicalSettlement.completeExceptionally(failure);
            }

            private synchronized void bindFuture(GuardFuture<T> future) {
                this.future = future;
            }

            private synchronized boolean bindGuard(SharedGuard next) {
                if (retired) return false;
                guard = next;
                return true;
            }

            /**
             * A failed/timed-out/cancelled request that never obtained a guard
             * owns NO permit and must not consume the Task's live lease quota.
             * This is safe on the host notification carrier: no guest callback
             * is invoked and no protected payload was ever exposed.
             */
            private void finishWithoutGrant() {
                boolean settled;
                synchronized (this) {
                    if (guard != null) return;
                    retired = true;
                    settled = !handoffInFlight;
                }
                lease.disarmWithoutRelease();
                // A Future terminal notification does not settle a dequeued
                // permit. Only its granting carrier can do that.
                if (settled) physicalSettlement.complete(null);
            }

            /** A grant lost the Future completion race; reclaim it first. */
            private void finishUndeliveredGrant(SharedGuard undelivered) {
                synchronized (this) {
                    if (guard != undelivered) return;
                    retired = true;
                    guard = null;
                }
                lease.disarmWithoutRelease();
            }

            private void releaseExplicitly() {
                SharedGuard acquired;
                synchronized (this) {
                    acquired = guard;
                }
                if (acquired == null) {
                    throw new IllegalStateException("cannot release an ungranted async mutex guard");
                }
                acquired.releaseFromRuntime(false);
            }

            private void retireAbandoned() {
                GuardFuture<T> pending;
                SharedGuard acquired;
                boolean claimed;
                synchronized (this) {
                    retired = true;
                    pending = future;
                    acquired = guard;
                    claimed = handoffInFlight;
                }
                // Binding a guard is NOT publication. The grant may still
                // lose the Future completion race to Task cancellation or
                // timeout, in which case guest state was never exposed and
                // poisoning would be a false-positive.
                //
                // Cancellation of the Future linearizes before this cleanup
                // decides whether an acquired guard was actually published.
                // A successfully completed Future cannot subsequently become
                // cancelled, even if its observers have not yet run.
                try {
                    if (pending != null && !isPublishedAsyncGrant(pending)) {
                        pending.cancel(false);
                    }
                } finally {
                    // Cancellation callbacks execute outside this monitor.
                    // If a grant was delivered, conservative poison is
                    // required; if it was not, merely reclaim the permit.
                    // The guard CAS protects exact-once release against the
                    // concurrent granting carrier.
                    if (acquired != null && !claimed) {
                        acquired.releaseFromRuntime(isPublishedAsyncGrant(pending));
                    }
                    // Claimed permits are still owned by the granting carrier.
                    // It performs the abandoned release in finishHandoff(),
                    // then resolves the physical receipt joined by getDone().
                }
            }
        }

        /**
         * The Future's successful completion is the publication point, not
         * the earlier internal guard binding. A cancelled/timed-out/failed
         * request cannot have exposed protected state to its caller.
         *
         * Keep this side-effect-free so the same predicate can be exercised
         * by deterministic regression tests without Task scheduling.
         */
        static boolean isPublishedAsyncGrant(CompletableFuture<?> future) {
            return future != null
                    && future.isDone()
                    && !future.isCompletedExceptionally();
        }

        private final class AsyncWaiter {
            private final Object ownerDomain;
            private final boolean enforceOwnerDomain;
            // Transporting a grant does not transport actor authority.
            // Capture the requesting actor domain before another carrier
            // performs the physical permit handoff.
            private final Object requestingActorDomain;
            private final GuardFuture<T> future;
            private final TaskBoundRequest taskRequest;

            private AsyncWaiter(
                    Object ownerDomain,
                    boolean enforceOwnerDomain,
                    Object requestingActorDomain,
                    GuardFuture<T> future,
                    TaskBoundRequest taskRequest) {
                this.ownerDomain = ownerDomain;
                this.enforceOwnerDomain = enforceOwnerDomain;
                this.requestingActorDomain = requestingActorDomain;
                this.future = future;
                this.taskRequest = taskRequest;
            }
        }

        /**
         * Releases the physical permit or directly transfers it to a queued
         * async waiter. Direct handoff avoids one virtual thread per waiter.
         *
         * <p>When both blocking host waiters and async waiters exist, alternate
         * preference so neither class can monopolize release handoff.</p>
         */
        // Test-only, same-package hook; invoked OUTSIDE queue and lease locks.
        // A deterministic latch lets tests halt between dequeue and publication.
        private volatile Runnable afterAsyncDequeueForTesting;

        void setAfterAsyncDequeueForTesting(Runnable hook) {
            afterAsyncDequeueForTesting = hook;
        }

        private void releasePermitOrHandoff() {
            while (true) {
                AsyncWaiter waiter = null;
                ArrayDeque<TaskBoundRequest> discarded = null;
                boolean releasedToSemaphore = false;
                synchronized (asyncQueueLock) {
                    while (!asyncQueue.isEmpty()) {
                        AsyncWaiter candidate = asyncQueue.removeFirst();
                        if (candidate.taskRequest != null) {
                            // Claim even a now-cancelled candidate: cleanup
                            // must not complete its retirement receipt before
                            // wait/domain accounting is physically removed.
                            candidate.taskRequest.beginHandoff();
                        }
                        if (!candidate.future.isDone()) {
                            waiter = candidate;
                            break;
                        }
                        endWait(candidate.ownerDomain);
                        releaseDomain(candidate.ownerDomain);
                        if (candidate.taskRequest != null) {
                            if (discarded == null) discarded = new ArrayDeque<>();
                            discarded.addLast(candidate.taskRequest);
                        }
                    }

                    if (waiter == null) {
                        permit.release();
                        releasedToSemaphore = true;
                    } else if (permit.hasQueuedThreads() && !preferAsyncHandoff) {
                        asyncQueue.addFirst(waiter);
                        if (waiter.taskRequest != null) {
                            waiter.taskRequest.deferHandoff();
                        }
                        preferAsyncHandoff = true;
                        permit.release();
                        releasedToSemaphore = true;
                    } else if (permit.hasQueuedThreads()) {
                        preferAsyncHandoff = false;
                    }
                }
                // Physical accounting and receipt completion are outside
                // asyncQueueLock. Never publish getDone under a queue monitor.
                if (discarded != null) {
                    for (TaskBoundRequest skipped : discarded) {
                        skipped.finishHandoff();
                    }
                }
                if (releasedToSemaphore) return;

                try {
                    Runnable hook = afterAsyncDequeueForTesting;
                    if (hook != null) hook.run();

                    if (poisoned.get()) {
                        endWait(waiter.ownerDomain);
                        releaseDomain(waiter.ownerDomain);
                        waiter.future.failFromRuntime(new PoisonedMutexException());
                        continue;
                    }

                    endWait(waiter.ownerDomain);
                    markOwner(waiter.ownerDomain);
                    SharedGuard guard = new SharedGuard(
                            waiter.ownerDomain,
                            waiter.enforceOwnerDomain,
                            waiter.requestingActorDomain,
                            waiter.taskRequest == null ? null : waiter.taskRequest.lease,
                            false);
                    if (waiter.taskRequest != null && !waiter.taskRequest.bindGuard(guard)) {
                        boolean reclaimed = guard.takePermitForHandoff();
                        waiter.taskRequest.finishUndeliveredGrant(guard);
                        if (reclaimed) continue;
                        return;
                    }
                    if (waiter.future.completeFromRuntime(guard)) return;

                    // Cancellation won after dequeue. Do not confuse
                    // Future completion with reclaimed permit ownership.
                    boolean reclaimed = guard.takePermitForHandoff();
                    if (waiter.taskRequest != null) {
                        waiter.taskRequest.finishUndeliveredGrant(guard);
                    }
                    if (reclaimed) continue;
                    return;
                } catch (RuntimeException | Error handoffFailure) {
                    if (waiter.taskRequest != null) {
                        waiter.taskRequest.failHandoff(handoffFailure);
                    }
                    throw handoffFailure;
                } finally {
                    if (waiter.taskRequest != null) {
                        waiter.taskRequest.finishHandoff();
                    }
                }
            }
        }

        private Guard<T> checkedGuardAfterAcquire(Object ownerDomain, boolean enforceOwnerDomain) {
            endWait(ownerDomain);
            if (poisoned.get()) {
                releaseDomain(ownerDomain);
                releasePermitOrHandoff();
                throw new PoisonedMutexException();
            }
            try {
                markOwner(ownerDomain);
            } catch (RuntimeException | Error failure) {
                releaseDomain(ownerDomain);
                releasePermitOrHandoff();
                throw failure;
            }
            try {
                return new SharedGuard(ownerDomain, enforceOwnerDomain,
                        ActorRuntime.currentActorExecutionDomain(), null, true);
            } catch (RuntimeException | Error registrationFailure) {
                clearOwner(ownerDomain);
                releaseDomain(ownerDomain);
                releasePermitOrHandoff();
                throw registrationFailure;
            }
        }

        @Override
        public Guard<T> lock() {
            rejectBlockingActorAcquisition();
            Object ownerDomain = reserveDomain(false);
            try {
                beginWait(ownerDomain);
                permit.acquire();
            } catch (InterruptedException interrupted) {
                endWait(ownerDomain);
                releaseDomain(ownerDomain);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("SharedMutex lock wait interrupted");
            } catch (RuntimeException | Error failure) {
                endWait(ownerDomain);
                releaseDomain(ownerDomain);
                throw failure;
            }
            return checkedGuardAfterAcquire(ownerDomain, true);
        }

        @Override
        public Optional<Guard<T>> tryLock() {
            Object ownerDomain = reserveDomain(true);
            if (ownerDomain == null) return Optional.empty();
            try {
                if (!permit.tryAcquire(0L, TimeUnit.NANOSECONDS)) {
                    releaseDomain(ownerDomain);
                    return Optional.empty();
                }
                return Optional.of(checkedGuardAfterAcquire(ownerDomain, true));
            } catch (InterruptedException interrupted) {
                releaseDomain(ownerDomain);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "SharedMutex try_lock interrupted");
            }
        }

        @Override
        public Optional<Guard<T>> lockFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            rejectBlockingActorAcquisition();
            if (timeout.isZero()) return tryLock();
            Object ownerDomain = reserveDomain(true);
            if (ownerDomain == null) return Optional.empty();
            try {
                beginWait(ownerDomain);
                if (!permit.tryAcquire(saturatedNanos(timeout), TimeUnit.NANOSECONDS)) {
                    endWait(ownerDomain);
                    releaseDomain(ownerDomain);
                    return Optional.empty();
                }
                return Optional.of(checkedGuardAfterAcquire(ownerDomain, true));
            } catch (InterruptedException interrupted) {
                endWait(ownerDomain);
                releaseDomain(ownerDomain);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("SharedMutex lock wait interrupted");
            } catch (RuntimeException | Error failure) {
                endWait(ownerDomain);
                releaseDomain(ownerDomain);
                throw failure;
            }
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsync() {
            // Reserve the logical owner before registering a Task lease.
            // A recursive-lock rejection must not leave an ungranted lease.
            Object ownerDomain = reserveDomain(false);
            int waiterLimit = asyncWaiterLimit();
            if (!reserveAsyncWaiter(waiterLimit)) {
                releaseDomain(ownerDomain);
                return failedGuardFuture(new IllegalStateException(
                        "SharedMutex async waiter limit exceeded: " + waiterLimit));
            }
            if (!reserveWaiter(GLOBAL_ASYNC_WAITERS, MAX_GLOBAL_ASYNC_WAITERS)) {
                releaseAsyncWaiter();
                releaseDomain(ownerDomain);
                return failedGuardFuture(new IllegalStateException(
                        "SharedMutex process-wide async waiter limit exceeded: " + MAX_GLOBAL_ASYNC_WAITERS));
            }

            // Register before the waiter is exposed to other carriers. An
            // admission failure must return both quotas and owner reservation.
            final TaskBoundRequest taskRequest;
            try {
                taskRequest = TaskHandle.isCurrentTrackedTaskTurn()
                        ? new TaskBoundRequest() : null;
            } catch (RuntimeException | Error registrationFailure) {
                releaseGlobalAsyncWaiter();
                releaseAsyncWaiter();
                releaseDomain(ownerDomain);
                throw registrationFailure;
            }

            boolean enforceOwnerDomain = ActorRuntime.inActorExecution();
            GuardFuture<T> future = new GuardFuture<>();
            if (taskRequest != null) taskRequest.bindFuture(future);
            AsyncWaiter waiter = new AsyncWaiter(
                    ownerDomain,
                    enforceOwnerDomain,
                    ActorRuntime.currentActorExecutionDomain(),
                    future,
                    taskRequest);

            future.whenComplete((ignored, failure) -> {
                boolean removed;
                synchronized (asyncQueueLock) {
                    removed = asyncQueue.remove(waiter);
                }
                if (removed) {
                    endWait(ownerDomain);
                    releaseDomain(ownerDomain);
                }
                releaseAsyncWaiter();
                releaseGlobalAsyncWaiter();
                if (taskRequest != null && failure != null) {
                    taskRequest.finishWithoutGrant();
                }
            });

            boolean acquired = false;
            boolean poisonedNow = false;
            Throwable waitFailure = null;
            synchronized (asyncQueueLock) {
                if (poisoned.get()) {
                    poisonedNow = true;
                } else {
                    boolean waitRegistered = false;
                    try {
                        // The timed zero-duration form honors a fair
                        // Semaphore's queue order. Untimed tryAcquire() is
                        // explicitly allowed to barge ahead of queued host
                        // waiters, which would violate our mixed-waiter
                        // fairness contract.
                        if (permit.tryAcquire(0L, TimeUnit.NANOSECONDS)) {
                            acquired = true;
                            if (taskRequest != null) taskRequest.beginHandoff();
                        } else {
                            beginWait(ownerDomain);
                            waitRegistered = true;
                            asyncQueue.addLast(waiter);
                        }
                    } catch (InterruptedException interrupted) {
                        if (waitRegistered) endWait(ownerDomain);
                        Thread.currentThread().interrupt();
                        waitFailure = new java.util.concurrent.CancellationException(
                                "SharedMutex async acquisition interrupted");
                    } catch (RuntimeException | Error failure) {
                        if (waitRegistered) endWait(ownerDomain);
                        waitFailure = failure;
                    }
                }
            }

            if (waitFailure != null) {
                releaseDomain(ownerDomain);
                future.failFromRuntime(waitFailure);
            } else if (poisonedNow) {
                releaseDomain(ownerDomain);
                future.failFromRuntime(new PoisonedMutexException());
            } else if (acquired) {
                try {
                    markOwner(ownerDomain);
                    SharedGuard guard = new SharedGuard(
                            ownerDomain,
                            enforceOwnerDomain,
                            ActorRuntime.currentActorExecutionDomain(),
                            taskRequest == null ? null : taskRequest.lease,
                            false);
                    if (taskRequest != null && !taskRequest.bindGuard(guard)) {
                        guard.releaseFromRuntime(false);
                        taskRequest.finishUndeliveredGrant(guard);
                        future.cancel(false);
                    } else if (!future.completeFromRuntime(guard)) {
                        guard.releaseFromRuntime(false);
                        if (taskRequest != null) {
                            taskRequest.finishUndeliveredGrant(guard);
                        }
                    }
                } catch (RuntimeException | Error failure) {
                    if (taskRequest != null) taskRequest.failHandoff(failure);
                    releaseDomain(ownerDomain);
                    releasePermitOrHandoff();
                    future.failFromRuntime(failure);
                } finally {
                    if (taskRequest != null) taskRequest.finishHandoff();
                }
            }

            return future;
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsyncFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");

            CompletableFuture<Guard<T>> pending = lockAsync();
            if (!(pending instanceof GuardFuture<?>)) return pending;

            @SuppressWarnings("unchecked")
            GuardFuture<T> future = (GuardFuture<T>) pending;
            if (future.isDone()) return future;

            long timeoutNanos = saturatedNanos(timeout);
            if (timeoutNanos == 0L) {
                future.failFromRuntime(new LockTimeoutException(timeout));
                return future;
            }

            final java.util.concurrent.ScheduledFuture<?> timeoutTask;
            try {
                timeoutTask = ASYNC_TIMEOUTS.schedule(
                        () -> future.failFromRuntime(new LockTimeoutException(timeout)),
                        timeoutNanos,
                        TimeUnit.NANOSECONDS);
            } catch (RuntimeException schedulingFailure) {
                future.failFromRuntime(schedulingFailure);
                return future;
            }
            future.whenComplete((ignored, failure) -> timeoutTask.cancel(false));
            return future;
        }

        @Override
        public <R> R withLock(Function<? super T, ? extends R> body) {
            Objects.requireNonNull(body, "body");
            Guard<T> guard = lock();
            try {
                R result = body.apply(value);
                guard.release();
                return result;
            } catch (RuntimeException | Error failure) {
                guard.fail();
                throw failure;
            }
        }

        public <R> R recover(Function<? super T, ? extends R> repair) {
            Objects.requireNonNull(repair, "repair");
            Object ownerDomain = reserveDomain(false);
            boolean acquired = false;
            boolean waiting = false;
            try {
                if (ActorRuntime.inActorExecution()) {
                    // Recovery is expected to happen after the poisoning guard
                    // released its permit. Never block an actor if another
                    // recovery attempt is already in progress.
                    acquired = permit.tryAcquire();
                    if (!acquired) {
                        throw new WrongMutexDomainException(
                                "SharedMutex recovery is busy; actor recovery never blocks, retry from a later mailbox turn");
                    }
                } else {
                    try {
                        beginWait(ownerDomain);
                        waiting = true;
                        permit.acquire();
                        acquired = true;
                        endWait(ownerDomain);
                        waiting = false;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.CancellationException(
                                "SharedMutex recovery wait interrupted");
                    }
                }

                markOwner(ownerDomain);
                if (!poisoned.get()) {
                    throw new IllegalStateException(
                            "SharedMutex is not poisoned; recover(...) is only for repairing poisoned state");
                }
                try {
                    R result = repair.apply(value);
                    poisoned.set(false);
                    return result;
                } catch (RuntimeException | Error failure) {
                    poisoned.set(true);
                    throw failure;
                }
            } finally {
                if (waiting) endWait(ownerDomain);
                if (acquired) {
                    clearOwner(ownerDomain);
                    releasePermitOrHandoff();
                }
                releaseDomain(ownerDomain);
            }
        }

        @Override
        public boolean isPoisoned() {
            requireActorAccess();
            return poisoned.get();
        }

        /** Runtime transport inspects the protected payload for explicit capability/reference validation. */
        Object transportValue() {
            return value;
        }

        private final class SharedGuard implements Guard<T> {
            private final Object ownerDomain;
            private final boolean enforceOwnerDomain;
            private final Object requestingActorDomain;
            private final AtomicBoolean released = new AtomicBoolean();
            private final TaskOwnedLeases.Lease taskLease;

            private SharedGuard(Object ownerDomain, boolean enforceOwnerDomain,
                                Object requestingActorDomain,
                                TaskOwnedLeases.Lease inheritedLease,
                                boolean registerCurrentTask) {
                this.ownerDomain = ownerDomain;
                this.enforceOwnerDomain = enforceOwnerDomain;
                this.requestingActorDomain = requestingActorDomain;
                this.taskLease = registerCurrentTask
                        ? TaskOwnedLeases.registerIfTracked(
                                () -> releaseFromRuntime(false),
                                () -> releaseFromRuntime(true))
                        : inheritedLease;
            }

            private void requireOwnerDomain() {
                if (enforceOwnerDomain) {
                    // Actor shared-memory authorization and Task ownership
                    // are distinct checks. A Task identity does not authorize
                    // entering another actor's guard domain.
                    if (!Objects.equals(
                            ActorRuntime.currentActorExecutionDomain(),
                            requestingActorDomain)) {
                        throw new WrongMutexDomainException(
                                "SharedMutex guard belongs to a different actor domain");
                    }
                    if (!currentExecutionOwns(ownerDomain)) {
                        throw new WrongMutexDomainException(
                                "SharedMutex guard belongs to another logical Task or actor turn");
                    }
                }
                if (taskLease != null) taskLease.requireCurrentOwner();
            }

            @Override
            public T value() {
                requireOwnerDomain();
                if (released()) throw new IllegalStateException("MutexGuard has been released");
                return value;
            }

            @Override public boolean released() { return released.get(); }

            @Override
            public void release() {
                requireOwnerDomain();
                if (taskLease == null) releaseFromRuntime(false);
                else taskLease.close();
            }

            /** Called only by the owner or the trusted quiescent Task cleanup. */
            private void releaseFromRuntime(boolean abandoned) {
                if (!released.compareAndSet(false, true)) return;
                if (abandoned) poisoned.set(true);
                clearOwner(ownerDomain);
                releaseDomain(ownerDomain);
                releasePermitOrHandoff();
            }

            /** Handoff loop keeps the physical permit instead of releasing it. */
            private boolean takePermitForHandoff() {
                if (!released.compareAndSet(false, true)) return false;
                clearOwner(ownerDomain);
                releaseDomain(ownerDomain);
                return true;
            }

            @Override
            public void fail() {
                requireOwnerDomain();
                if (released.get()) return;
                poisoned.set(true);
                if (taskLease == null) releaseFromRuntime(false);
                else taskLease.close();
            }
        }
    }
}
