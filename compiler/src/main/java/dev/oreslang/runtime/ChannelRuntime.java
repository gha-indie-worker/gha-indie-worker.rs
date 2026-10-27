package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Oreslang-owned channel and atomic select substrate.
 *
 * <p>All pending channel operations are represented as select registrations;
 * direct read/write is simply a one-case SelectSet. One fair runtime lock owns
 * only the short admission/commit critical section. No actor carrier parks on
 * this lock waiting for channel readiness: an unready operation returns an
 * {@link OresFuture} and the owning Ores continuation suspends or keeps it
 * armed.</p>
 *
 * <p>This single commit coordinator deliberately favors semantic strength over
 * premature sharding in the reference runtime. It guarantees that multi-channel
 * selection, rendezvous, cancellation, close, and loser preservation share one
 * atomic arbitration point. A native backend may shard/lock-free this later as
 * long as it preserves the same observable contract.</p>
 */
public final class ChannelRuntime {
    private ChannelRuntime() { }

    /** A finite default prevents an idle producer from retaining unbounded select waiters. */
    public static final int DEFAULT_MAX_PENDING_REGISTRATIONS = 4096;
    /** Bound dynamic select fan-in before allocating waiter registrations. */
    public static final int MAX_SELECT_CASES = 256;

    private static final ReentrantLock COORDINATOR = new ReentrantLock(true);
    private static final Set<SelectRegistration> ACTIVE = new LinkedHashSet<>();
    /** Global cap on pending selections, independent of per-channel arm limits. */
    private static final int MAX_PENDING_SELECTS = 4_096;
    static final int MAX_PENDING_SELECT_ARM_REGISTRATIONS = 4_096;
    /** Global retained descriptor count. Only accessed under COORDINATOR. */
    private static int activeArmRegistrations;
    private static long nextTicket;

    public enum SelectPolicy {
        /** Deterministic rotating fairness on a reusable SelectSet. */
        FAIR,
        /** Strict source/list order. May intentionally starve later cases. */
        PRIORITY,
        /** Explicit opt-in randomized case order; never the language default. */
        RANDOM
    }

    public enum SelectOperation {
        READ,
        WRITE,
        FUTURE,
        DEFAULT
    }

    public record SelectResult(
            int index,
            SelectOperation operation,
            Object value) {
        public SelectResult {
            if (index < 0) {
                throw new IllegalArgumentException(
                        "select result index must be non-negative");
            }
            Objects.requireNonNull(operation, "operation");
        }
    }

    /** Admission exhaustion is distinct from closure; no waiter is retained. */
    public static final class ChannelBackpressureException extends IllegalStateException {
        public ChannelBackpressureException(String message) {
            super(message);
        }
    }

    public static final class SelectAdmissionException extends IllegalArgumentException {
        public SelectAdmissionException(String message) {
            super(message);
        }
    }

    public static final class ChannelClosedException extends IllegalStateException {
        public ChannelClosedException(String message) {
            super(message);
        }
    }

    /**
     * Bounded MPMC channel. Capacity zero is a rendezvous channel.
     *
     * <p>Null is deliberately rejected because Oreslang has no standalone null
     * value. Use Option<T> or an explicit signal/unit value instead.</p>
     */
    public static final class Channel<T> implements AutoCloseable {
        private final int capacity;
        private final int maxPendingRegistrations;
        private final java.util.ArrayDeque<T> buffer = new java.util.ArrayDeque<>();
        private final ArrayList<CaseRegistration> registrations = new ArrayList<>();
        private boolean closed;
        private Throwable closeCause;

        public Channel(int capacity) {
            this(capacity, DEFAULT_MAX_PENDING_REGISTRATIONS);
        }

        /**
         * Pending operation quota counts registered select arms, not only
         * buffered values. A multi-arm select counts once per referenced arm;
         * it is admitted atomically across every participating channel.
         */
        public Channel(int capacity, int maxPendingRegistrations) {
            if (capacity < 0) {
                throw new IllegalArgumentException(
                        "channel capacity cannot be negative");
            }
            if (maxPendingRegistrations < 1) {
                throw new IllegalArgumentException(
                        "max pending channel registrations must be positive");
            }
            this.capacity = capacity;
            this.maxPendingRegistrations = maxPendingRegistrations;
        }

        public int capacity() {
            return capacity;
        }

        public int maxPendingRegistrations() {
            return maxPendingRegistrations;
        }

        public int pendingRegistrations() {
            COORDINATOR.lock();
            try {
                return registrations.size();
            } finally {
                COORDINATOR.unlock();
            }
        }

        public int size() {
            COORDINATOR.lock();
            try {
                return buffer.size();
            } finally {
                COORDINATOR.unlock();
            }
        }

        public boolean isEmpty() {
            return size() == 0;
        }

        public boolean isClosed() {
            COORDINATOR.lock();
            try {
                return closed;
            } finally {
                COORDINATOR.unlock();
            }
        }

        /**
         * Immediate receive probe. No waiter remains registered when this
         * method returns empty.
         */
        public Optional<T> tryRead() {
            Optional<SelectResult> result =
                    SelectSet.of(read(this)).trySelect(SelectPolicy.PRIORITY);
            if (result.isEmpty()) return Optional.empty();
            @SuppressWarnings("unchecked")
            T value = (T) result.get().value();
            return Optional.of(value);
        }

        /** Runtime-only drain of committed mailbox values, including after close. */
        boolean drainOne(java.util.function.Consumer<? super T> consumer) {
            Objects.requireNonNull(consumer, "consumer");
            ArrayList<Runnable> completions = new ArrayList<>();
            T value;
            COORDINATOR.lock();
            try {
                if (buffer.isEmpty()) return false;
                value = buffer.removeFirst();
                // Draining creates a slot for a pending writer. It must run the
                // same arbitration path as ordinary channel reads; otherwise
                // admitted writers can remain stuck until unrelated channel
                // activity wakes the coordinator.
                pumpLocked(completions);
            } finally {
                COORDINATOR.unlock();
            }
            Throwable consumerFailure = null;
            try {
                // FIFO: deliver the older buffered value before publishing
                // newly admitted writes; retain a failing consumer as primary.
                consumer.accept(value);
            } catch (RuntimeException | Error failure) {
                consumerFailure = failure;
                throw failure;
            } finally {
                // Preserve the consumer's exception as the primary failure,
                // without abandoning any already-committed writer completion.
                final Throwable primary = consumerFailure;
                runAfterPreservingFailure(primary, () -> runCompletions(completions));
            }
            return true;
        }

        /**
         * Immediate send probe. No waiter remains registered when this returns
         * false.
         */
        public boolean tryWrite(T value) {
            requireValue(value);
            return SelectSet.of(write(this, value))
                    .trySelect(SelectPolicy.PRIORITY)
                    .isPresent();
        }

        /**
         * Scheduler-friendly receive registration.
         */
        public OresFuture<T> readAsync() {
            OresFuture<SelectResult> selected =
                    SelectSet.of(read(this)).selectAsync(SelectPolicy.PRIORITY);
            return mapSelection(selected, result -> {
                @SuppressWarnings("unchecked")
                T value = (T) result.value();
                return value;
            });
        }

        /**
         * Scheduler-friendly send registration.
         */
        public OresFuture<Void> writeAsync(T value) {
            requireValue(value);
            OresFuture<SelectResult> selected =
                    SelectSet.of(write(this, value))
                            .selectAsync(SelectPolicy.PRIORITY);
            return mapSelection(selected, ignored -> null);
        }

        private void requireValue(T value) {
            Objects.requireNonNull(
                    value,
                    "Oreslang channels cannot carry null; use Option<T>");
        }

        private ChannelClosedException closedFailure() {
            ChannelClosedException failure =
                    new ChannelClosedException("channel is closed");
            if (closeCause != null) failure.initCause(closeCause);
            return failure;
        }

        @Override
        public void close() {
            close(null);
        }

        /**
         * Close preserves already-buffered values for readers. Once the buffer
         * is drained, reads fail; writes fail immediately. Pending selections
         * re-arbitrate atomically across all of their cases.
         */
        public void close(Throwable cause) {
            ArrayList<Runnable> completions = new ArrayList<>();
            COORDINATOR.lock();
            try {
                if (closed) return;
                closed = true;
                closeCause = cause;
                pumpLocked(completions);
            } finally {
                COORDINATOR.unlock();
            }
            runCompletions(completions);
        }
    }

    /**
     * Runtime-side descriptor protocol for the source-level Selectable<T> trait.
     * One invocation reduces a source to one NEXT event operation. The method
     * must be side-effect-free: no subscriptions, waiting, consumption, guest
     * callbacks, or producer scheduling; only an approved SelectCase descriptor.
     *
     * <p>Java host callers are trusted; the Oreslang compiler must statically
     * enforce pure/effect-safe descriptions before opening this adapter to
     * guest-defined traits. The winner coordinator still only accepts the
     * sealed channel/Future cases below.</p>
     */
    @FunctionalInterface
    public interface Selectable<T> {
        SelectCase selectNext();
    }

    public sealed interface SelectCase permits ReadCase, WriteCase, FutureCase, DefaultCase { }

    public record ReadCase<T>(Channel<T> channel) implements SelectCase {
        public ReadCase {
            Objects.requireNonNull(channel, "channel");
        }
    }

    public record WriteCase<T>(Channel<T> channel, T value)
            implements SelectCase {
        public WriteCase {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(
                    value,
                    "Oreslang channels cannot carry null; use Option<T>");
        }
    }

    /** One immutable Future completion, not another concurrently running actor. */
    public record FutureCase<T>(OresFuture<T> future) implements SelectCase {
        public FutureCase {
            Objects.requireNonNull(future, "future");
        }
    }

    public record DefaultCase() implements SelectCase { }

    public static <T> FutureCase<T> await(OresFuture<T> future) {
        return new FutureCase<>(future);
    }

    public static <T> ReadCase<T> read(Channel<T> channel) {
        return new ReadCase<>(channel);
    }

    public static <T> WriteCase<T> write(Channel<T> channel, T value) {
        return new WriteCase<>(channel, value);
    }

    public static DefaultCase defaultCase() {
        return new DefaultCase();
    }

    /**
     * Reusable select descriptor. Static and dynamic source select lower to
     * this same primitive.
     */
    public static final class SelectSet {
        private final List<SelectCase> cases;
        private final AtomicLong fairCursor;

        public SelectSet(Collection<? extends SelectCase> cases) {
            this(cases, 0L);
        }

        /**
         * Compiler/runtime hook for static select sites that rebuild evaluated
         * case values each execution but retain one deterministic fairness
         * ticket.
         */
        public SelectSet(
                Collection<? extends SelectCase> cases,
                long initialFairCursor) {
            this(cases, new AtomicLong(initialFairCursor));
        }

        // Dynamic plan generations share a cursor only with their immutable snapshot.
        // Old completions cannot rotate a newer generation's FAIR state.
        private SelectSet(
                Collection<? extends SelectCase> cases,
                AtomicLong sharedFairCursor) {
            Objects.requireNonNull(cases, "cases");
            this.fairCursor = Objects.requireNonNull(sharedFairCursor, "sharedFairCursor");
            if (cases.isEmpty()) {
                throw new IllegalArgumentException(
                        "select requires at least one case");
            }
            if (cases.size() > MAX_SELECT_CASES) {
                throw new SelectAdmissionException(
                        "select case count exceeds maximum " + MAX_SELECT_CASES);
            }
            this.cases = List.copyOf(cases);
            long defaults = this.cases.stream()
                    .filter(DefaultCase.class::isInstance)
                    .count();
            if (defaults > 1) {
                throw new IllegalArgumentException(
                        "select permits at most one default case");
            }
        }

        public static SelectSet of(SelectCase... cases) {
            return new SelectSet(List.of(cases));
        }

        public static SelectSet from(Iterable<? extends SelectCase> cases) {
            ArrayList<SelectCase> copy = new ArrayList<>();
            for (SelectCase selectCase : cases) {
                if (copy.size() == MAX_SELECT_CASES) {
                    throw new IllegalArgumentException(
                            "select case count exceeds maximum " + MAX_SELECT_CASES);
                }
                copy.add(Objects.requireNonNull(selectCase));
            }
            return new SelectSet(copy);
        }

        /**
         * Reduce heterogeneous trusted sources to exactly one operation each
         * BEFORE acquiring the atomic coordinator. Describing a candidate
         * must never await or consume its next event. A default is a select
         * control arm, not a one-event Selectable source.
         */
        public static SelectSet fromSelectables(
                Iterable<? extends Selectable<?>> selectables) {
            Objects.requireNonNull(selectables, "selectables");
            ArrayList<SelectCase> cases = new ArrayList<>();
            for (Selectable<?> selectable : selectables) {
                if (cases.size() >= MAX_SELECT_CASES) {
                    throw new SelectAdmissionException(
                            "selectable source quota exceeded: " + MAX_SELECT_CASES);
                }
                SelectCase operation = Objects.requireNonNull(
                        Objects.requireNonNull(selectable, "selectable source").selectNext(),
                        "selectNext must produce one operation");
                if (operation instanceof DefaultCase) {
                    throw new IllegalArgumentException(
                            "Selectable<T> cannot reduce to a default arm");
                }
                cases.add(operation);
            }
            return new SelectSet(cases);
        }

        /**
         * Map insertion/value iteration order defines the case index/order.
         * The language may add keyed SelectResult metadata later without
         * changing selection arbitration.
         */
        public static SelectSet fromMap(Map<?, ? extends SelectCase> cases) {
            Objects.requireNonNull(cases, "cases");
            return new SelectSet(cases.values());
        }

        public List<SelectCase> cases() {
            return cases;
        }

        public OresFuture<SelectResult> selectAsync() {
            return selectAsync(SelectPolicy.FAIR);
        }

        public OresFuture<SelectResult> selectAsync(SelectPolicy policy) {
            SelectRegistration registration =
                    new SelectRegistration(
                            this,
                            Objects.requireNonNull(policy, "policy"));
            registration.start(false);
            return registration.future;
        }

        /**
         * Package-private deterministic race seam for runtime regression tests.
         * Observers run only in explicitly opted-in test selections; the normal
         * select path never executes injected callbacks.
         */
        OresFuture<SelectResult> selectAsyncForTesting(
                SelectPolicy policy,
                java.util.function.Consumer<OresFuture<SelectResult>> onCreated,
                Runnable afterWaiterAttached) {
            return selectAsyncForTesting(policy, onCreated, afterWaiterAttached, null);
        }

        /** Opt-in observer for the critical decision-to-late-detach window. */
        OresFuture<SelectResult> selectAsyncForTesting(
                SelectPolicy policy,
                java.util.function.Consumer<OresFuture<SelectResult>> onCreated,
                Runnable afterWaiterAttached,
                Runnable beforeLateWaiterDetached) {
            SelectRegistration registration =
                    new SelectRegistration(this,
                            Objects.requireNonNull(policy, "policy"),
                            Objects.requireNonNull(afterWaiterAttached, "afterWaiterAttached"),
                            beforeLateWaiterDetached);
            Objects.requireNonNull(onCreated, "onCreated").accept(registration.future);
            registration.start(false);
            return registration.future;
        }

        public Optional<SelectResult> trySelect() {
            return trySelect(SelectPolicy.FAIR);
        }

        public Optional<SelectResult> trySelect(SelectPolicy policy) {
            SelectRegistration registration =
                    new SelectRegistration(
                            this,
                            Objects.requireNonNull(policy, "policy"));
            return registration.tryNow();
        }

        private int[] initialOrder(SelectPolicy policy) {
            int count = cases.size();
            int[] order = new int[count];

            if (policy == SelectPolicy.PRIORITY) {
                for (int i = 0; i < count; i++) order[i] = i;
                return order;
            }

            if (policy == SelectPolicy.FAIR) {
                int start = Math.floorMod(fairCursor.get(), count);
                for (int i = 0; i < count; i++) {
                    order[i] = (start + i) % count;
                }
                return order;
            }

            for (int i = 0; i < count; i++) order[i] = i;
            for (int i = count - 1; i > 0; i--) {
                int j = ThreadLocalRandom.current().nextInt(i + 1);
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }
            return order;
        }

        private void selected(int index, SelectPolicy policy) {
            // Default did not perform I/O and must not rotate FAIR credit.
            if (policy == SelectPolicy.FAIR && !cases.isEmpty()
                    && !(cases.get(index) instanceof DefaultCase)) {
                fairCursor.set((index + 1L) % cases.size());
            }
        }
    }

    /**
     * Optional reusable execution plan for repeated or dynamically assembled
     * selections.
     *
     * <p>A SelectPlan is deliberately a layer over {@link SelectSet}, not a
     * replacement for it. Existing select lowering may continue constructing
     * and executing SelectSet directly. Plans only retain immutable case
     * descriptors plus the SelectSet fairness cursor; every invocation still
     * creates a fresh SelectRegistration and therefore preserves the existing
     * atomic arbitration, cancellation, loser-detachment, close, and Future
     * semantics.</p>
     *
     * <p>This split gives the compiler a safe optimization target for static
     * select sites and gives dynamic select code an explicit reusable value.
     * It intentionally does not keep live channel waiters armed between
     * invocations. Persistent waiter reuse is a separate optimization that
     * would require a stronger generation/winner protocol.</p>
     */
    public static final class SelectPlan {
        private final SelectSet set;

        public SelectPlan(Collection<? extends SelectCase> cases) {
            this(new SelectSet(cases));
        }

        private SelectPlan(SelectSet set) {
            this.set = Objects.requireNonNull(set, "set");
        }

        public static SelectPlan of(SelectCase... cases) {
            return new SelectPlan(SelectSet.of(cases));
        }

        public static SelectPlan from(Iterable<? extends SelectCase> cases) {
            return new SelectPlan(SelectSet.from(cases));
        }

        public static SelectPlan fromMap(Map<?, ? extends SelectCase> cases) {
            return new SelectPlan(SelectSet.fromMap(cases));
        }

        public List<SelectCase> cases() {
            return set.cases();
        }

        public OresFuture<SelectResult> selectAsync() {
            return set.selectAsync();
        }

        public OresFuture<SelectResult> selectAsync(SelectPolicy policy) {
            return set.selectAsync(policy);
        }

        public Optional<SelectResult> trySelect() {
            return set.trySelect();
        }

        public Optional<SelectResult> trySelect(SelectPolicy policy) {
            return set.trySelect(policy);
        }
    }

    /**
     * Mutation-friendly select plan retaining only reusable case descriptors,
     * never armed waiters or completion authority. Insertion order defines
     * index order; keyed selections pin that order to one immutable generation.
     *
     * <p>Each select creates a fresh, bounded SelectRegistration through the
     * underlying SelectSet. This preserves Channel, Future and cancellation
     * semantics (including late Future waiter detachment) from the current
     * runtime rather than implementing a second arbitration path.
     */
    public static final class DynamicSelectPlan<K> {
        private final LinkedHashMap<K, SelectCase> entries = new LinkedHashMap<>();
        // One cursor per structural generation: late old wins never rotate a
        // newer generation. Mutations do not acquire COORDINATOR.
        private AtomicLong fairness = new AtomicLong();
        private long version;
        // The fast path performs one volatile read, not a plan monitor entry.
        private volatile Snapshot<K> cached;

        private record Snapshot<K>(long version, List<K> keys, SelectSet set) { }

        public record PlannedResult<K>(long version, K key, SelectResult selection) {
            public PlannedResult {
                Objects.requireNonNull(key, "key");
                Objects.requireNonNull(selection, "selection");
            }
        }

        public DynamicSelectPlan() { }

        public DynamicSelectPlan(Map<? extends K, ? extends SelectCase> initial) {
            Objects.requireNonNull(initial, "initial");
            for (Map.Entry<? extends K, ? extends SelectCase> entry : initial.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
        }

        /**
         * Mutations publish a new generation. Replacements retain their key's
         * insertion position; failed duplicates and quota checks are atomic.
         */
        public synchronized void put(K key, SelectCase selectCase) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(selectCase, "selectCase");
            if (!entries.containsKey(key) && entries.size() >= MAX_SELECT_CASES) {
                throw new SelectAdmissionException(
                        "dynamic select case count exceeds maximum " + MAX_SELECT_CASES);
            }
            if (selectCase instanceof DefaultCase) {
                for (Map.Entry<K, SelectCase> entry : entries.entrySet()) {
                    if (!entry.getKey().equals(key)
                            && entry.getValue() instanceof DefaultCase) {
                        throw new IllegalArgumentException(
                                "select permits at most one default case");
                    }
                }
            }
            long nextVersion = Math.addExact(version, 1L);
            entries.put(key, selectCase);
            fairness = new AtomicLong(fairness.get());
            version = nextVersion;
            cached = null;
        }

        /** Existing in-flight selections keep their original snapshot. */
        public synchronized boolean remove(K key) {
            Objects.requireNonNull(key, "key");
            if (!entries.containsKey(key)) return false;
            long nextVersion = Math.addExact(version, 1L);
            entries.remove(key);
            fairness = new AtomicLong(fairness.get());
            version = nextVersion;
            cached = null;
            return true;
        }

        public synchronized long version() {
            return version;
        }

        public synchronized int size() {
            return entries.size();
        }

        public synchronized List<K> keys() {
            return List.copyOf(entries.keySet());
        }

        /**
         * Snapshot capture is the select's plan-version linearization point.
         * Concurrent mutations may complete after the capture but before
         * registration; the in-flight operation still owns the old generation.
         */
        private Snapshot<K> snapshot() {
            Snapshot<K> result = cached;
            if (result != null) return result;
            synchronized (this) {
                result = cached;
                if (result != null) return result;
                if (entries.isEmpty()) {
                    throw new IllegalStateException("cannot select from an empty dynamic plan");
                }
                result = new Snapshot<>(
                        version, List.copyOf(entries.keySet()),
                        new SelectSet(entries.values(), fairness));
                cached = result;
                return result;
            }
        }

        public SelectSet selectSetSnapshot() {
            return snapshot().set();
        }

        public OresFuture<SelectResult> selectAsync() {
            return selectAsync(SelectPolicy.FAIR);
        }

        public OresFuture<SelectResult> selectAsync(SelectPolicy policy) {
            return snapshot().set().selectAsync(policy);
        }

        public Optional<SelectResult> trySelect() {
            return trySelect(SelectPolicy.FAIR);
        }

        public Optional<SelectResult> trySelect(SelectPolicy policy) {
            return snapshot().set().trySelect(policy);
        }

        public OresFuture<PlannedResult<K>> selectKeyedAsync(SelectPolicy policy) {
            Snapshot<K> selected = snapshot();
            return mapSelection(
                    selected.set().selectAsync(policy),
                    result -> new PlannedResult<>(
                            selected.version(), selected.keys().get(result.index()), result));
        }

        public Optional<PlannedResult<K>> trySelectKeyed(SelectPolicy policy) {
            Snapshot<K> selected = snapshot();
            return selected.set().trySelect(policy)
                    .map(result -> new PlannedResult<>(
                            selected.version(), selected.keys().get(result.index()), result));
        }
    }

    private static final class CaseRegistration {
        private final SelectRegistration selection;
        private final int index;
        private final SelectCase selectCase;
        private final long ticket;

        private CaseRegistration(
                SelectRegistration selection,
                int index,
                SelectCase selectCase) {
            this.selection = selection;
            this.index = index;
            this.selectCase = selectCase;
            this.ticket = nextTicket++;
        }

        private Channel<?> channel() {
            if (selectCase instanceof ReadCase<?> read) return read.channel();
            if (selectCase instanceof WriteCase<?> write) return write.channel();
            throw new IllegalStateException(
                    "only channel cases have channel registrations");
        }

        private SelectOperation operation() {
            if (selectCase instanceof ReadCase<?>) return SelectOperation.READ;
            if (selectCase instanceof WriteCase<?>) return SelectOperation.WRITE;
            if (selectCase instanceof FutureCase<?>) return SelectOperation.FUTURE;
            return SelectOperation.DEFAULT;
        }

        private Object writeValue() {
            return ((WriteCase<?>) selectCase).value();
        }
    }

    private static final class SelectRegistration {
        private final SelectSet set;
        private final SelectPolicy policy;
        /** Captured under COORDINATOR at admission, after preceding FAIR commits. */
        private int[] order;
        private final ArrayList<CaseRegistration> registrations =
                new ArrayList<>();
        /**
         * Direct lookup by source arm index. Arbitration repeatedly checks
         * up to MAX_SELECT_CASES arms under the global coordinator; scanning
         * the registration list for every arm would make each probe quadratic.
         * Indexed slots are populated/cleared only under COORDINATOR.
         */
        private final CaseRegistration[] registeredByIndex;
        /** Detached on any winner/cancellation; never cancels a shared producer Future. */
        private final ArrayList<OresFuture.RuntimeWaiterRegistration<?>> futureWaiters =
                new ArrayList<>();
        private final int defaultIndex;
        private final OresFuture<SelectResult> future;
        private final Runnable afterWaiterAttachedForTesting;
        private final Runnable beforeLateWaiterDetachedForTesting;
        private boolean decided;
        private boolean registered;
        /** Registrations that can still return after a selection was committed. */
        private int attachingFutureWaiters;
        /** Completion publication is held until in-flight listener attachments detach. */
        private final ArrayList<Runnable> deferredPublication = new ArrayList<>();

        private SelectRegistration(SelectSet set, SelectPolicy policy) {
            this(set, policy, null, null);
        }

        private SelectRegistration(SelectSet set, SelectPolicy policy,
                Runnable afterWaiterAttachedForTesting,
                Runnable beforeLateWaiterDetachedForTesting) {
            this.set = set;
            this.policy = policy;
            this.afterWaiterAttachedForTesting = afterWaiterAttachedForTesting;
            this.beforeLateWaiterDetachedForTesting = beforeLateWaiterDetachedForTesting;
            this.registeredByIndex = new CaseRegistration[set.cases.size()];

            int foundDefault = -1;
            for (int i = 0; i < set.cases.size(); i++) {
                if (set.cases.get(i) instanceof DefaultCase) {
                    foundDefault = i;
                    break;
                }
            }
            this.defaultIndex = foundDefault;
            this.future = new OresFuture<>(
                    this::cancelAdmission,
                    () -> { });
        }

        private boolean cancelAdmission() {
            ArrayList<Runnable> cleanups = new ArrayList<>();
            COORDINATOR.lock();
            try {
                if (decided) return false;
                decided = true;
                unregisterLocked(this, cleanups);
                return true;
            } finally {
                COORDINATOR.unlock();
                // Producer queues may be large even when this select is bounded.
                // Never scan/remove their waiters under the channel coordinator.
                runCompletions(cleanups);
            }
        }

        private void start(boolean immediateOnly) {
            ArrayList<Runnable> completions = new ArrayList<>();
            COORDINATOR.lock();
            try {
                // Even when the retained-waiter quota is full, a ready
                // rendezvous or immediate probe must be able to make progress.
                // A transient registration is held only while under the lock.
                registerLocked(this);
                pumpLocked(completions);

                if (!decided && defaultIndex >= 0) {
                    commitSingleLocked(
                            caseOrDefault(defaultIndex),
                            new SelectResult(
                                    defaultIndex,
                                    SelectOperation.DEFAULT,
                                    null),
                            completions);
                }

                if (!decided && !immediateOnly
                        && exceedsPendingBudgetLocked(this)) {
                    decided = true;
                    unregisterLocked(this, completions);
                    completions.add(() -> future.failFromRuntime(
                            new ChannelBackpressureException(
                                    "channel pending registration budget exhausted")));
                }

                if (!decided && !immediateOnly && activeArmRegistrations > MAX_PENDING_SELECT_ARM_REGISTRATIONS) {
                    // Ready operations have already been given a chance to commit.
                    decided = true;
                    unregisterLocked(this, completions);
                    completions.add(() -> future.failFromRuntime(
                            new SelectAdmissionException(
                                    "pending select arm registration quota exceeded")));
                }

                if (!decided && !immediateOnly && ACTIVE.size() > MAX_PENDING_SELECTS) {
                    decided = true;
                    unregisterLocked(this, completions);
                    completions.add(() -> future.failFromRuntime(
                            new SelectAdmissionException(
                                    "pending select registration quota exceeded")));
                }

                if (!decided && immediateOnly) {
                    decided = true;
                    unregisterLocked(this, completions);
                }
            } finally {
                COORDINATOR.unlock();
            }
            Throwable publicationFailure = null;
            try {
                runCompletions(completions);
            } catch (RuntimeException | Error failure) {
                publicationFailure = failure;
                throw failure;
            } finally {
                // A failure in an unrelated committed completion must not
                // leave this registered selection without Future wakeups.
                if (!immediateOnly) {
                    final Throwable primary = publicationFailure;
                    runAfterPreservingFailure(primary, this::attachFutureWaiters);
                }
            }
        }

        private void attachFutureWaiters() {
            List<CaseRegistration> pending;
            COORDINATOR.lock();
            try {
                if (decided) return;
                pending = List.copyOf(registrations);
            } finally {
                COORDINATOR.unlock();
            }
            for (CaseRegistration registration : pending) {
                if (!(registration.selectCase instanceof FutureCase<?> candidate)) continue;
                // Claim the attachment lifetime before leaving the coordinator.
                // Committed results cannot be published while this claim lives.
                COORDINATOR.lock();
                try {
                    if (decided) return;
                    attachingFutureWaiters++;
                } finally {
                    COORDINATOR.unlock();
                }
                OresFuture.RuntimeWaiterRegistration<?> waiter = null;
                // Ordinary pending listeners never need a drain snapshot.
                List<Runnable> readyToPublish = null;
                try {
                    waiter = candidate.future().whenCompleteRuntimeCancellable(
                            (ignored, failure) -> futureBecameReady());
                    if (afterWaiterAttachedForTesting != null) {
                        afterWaiterAttachedForTesting.run();
                    }
                } finally {
                    boolean lost;
                    COORDINATOR.lock();
                    try {
                        lost = decided;
                        if (!lost && waiter != null) futureWaiters.add(waiter);
                    } finally {
                        COORDINATOR.unlock();
                    }
                    try {
                        // Detachment can scan a large producer waiter queue.
                        // Leave the attachment claim live until AFTER removal:
                        // a concurrent publisher must not observe count == 0
                        // while this losing registration remains linked.
                        if (lost && waiter != null) {
                            try {
                                if (beforeLateWaiterDetachedForTesting != null) {
                                    beforeLateWaiterDetachedForTesting.run();
                                }
                            } finally {
                                // Even a throwing test hook cannot retain a late handle.
                                waiter.detach();
                            }
                        }
                    } finally {
                        COORDINATOR.lock();
                        try {
                            attachingFutureWaiters--;
                            if (attachingFutureWaiters == 0
                                    && !deferredPublication.isEmpty()) {
                                readyToPublish = List.copyOf(deferredPublication);
                                deferredPublication.clear();
                            }
                        } finally {
                            COORDINATOR.unlock();
                        }
                        if (readyToPublish != null) runCompletions(readyToPublish);
                    }
                }
            }
        }

        /** Publish a selected result only after every in-flight attachment has settled. */
        private void publishAfterAttachment(Runnable action) {
            COORDINATOR.lock();
            try {
                if (attachingFutureWaiters > 0) {
                    deferredPublication.add(action);
                    return;
                }
            } finally {
                COORDINATOR.unlock();
            }
            action.run();
        }

        private void futureBecameReady() {
            ArrayList<Runnable> completions = new ArrayList<>();
            COORDINATOR.lock();
            try {
                if (!decided) pumpLocked(completions);
            } finally {
                COORDINATOR.unlock();
            }
            runCompletions(completions);
        }

        private Optional<SelectResult> tryNow() {
            start(true);
            if (!future.isDone()) return Optional.empty();
            return Optional.of(future.join());
        }

        private CaseRegistration caseOrDefault(int index) {
            for (CaseRegistration registration : registrations) {
                if (registration.index == index) return registration;
            }
            return new CaseRegistration(this, index, set.cases.get(index));
        }
    }

    private static boolean exceedsPendingBudgetLocked(
            SelectRegistration selection) {
        for (CaseRegistration registration : selection.registrations) {
            // FutureCase has no Channel and consumes no per-channel credits.
            if (registration.selectCase instanceof ReadCase<?>
                    || registration.selectCase instanceof WriteCase<?>) {
                Channel<?> channel = registration.channel();
                if (channel.registrations.size() > channel.maxPendingRegistrations) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void registerLocked(SelectRegistration selection) {
        if (selection.decided || selection.registered) return;

        // A reusable SelectSet may have had another selection commit after
        // this registration object was constructed. Capture the fairness
        // cursor at atomic admission, not at construction, so overlapping
        // invocations observe all earlier committed rotations.
        selection.order = selection.set.initialOrder(selection.policy);
        selection.registered = true;
        ACTIVE.add(selection);

        for (int index : selection.order) {
            SelectCase selectCase = selection.set.cases.get(index);
            if (selectCase instanceof DefaultCase) continue;

            CaseRegistration registration =
                    new CaseRegistration(selection, index, selectCase);
            selection.registrations.add(registration);
            selection.registeredByIndex[index] = registration;
            activeArmRegistrations++;
            if (selectCase instanceof ReadCase<?> || selectCase instanceof WriteCase<?>) {
                channelOf(selectCase).registrations.add(registration);
            }
        }
    }

    private static void unregisterLocked(
            SelectRegistration selection, List<Runnable> completions) {
        if (!selection.registered) return;
        selection.registered = false;
        ACTIVE.remove(selection);
        if (activeArmRegistrations < selection.registrations.size()) {
            throw new IllegalStateException("select arm registration accounting underflow");
        }
        activeArmRegistrations -= selection.registrations.size();
        for (CaseRegistration registration :
                List.copyOf(selection.registrations)) {
            selection.registeredByIndex[registration.index] = null;
            if (registration.selectCase instanceof ReadCase<?>
                    || registration.selectCase instanceof WriteCase<?>) {
                registration.channel().registrations.remove(registration);
            }
        }
        selection.registrations.clear();
        if (!selection.futureWaiters.isEmpty()) {
            // Once decided=true, callbacks cannot commit another arm. Queue
            // removals can be linear in shared producer waiters; schedule all
            // detaches after releasing COORDINATOR, before publishing the winner.
            List<OresFuture.RuntimeWaiterRegistration<?>> detached =
                    List.copyOf(selection.futureWaiters);
            selection.futureWaiters.clear();
            completions.add(() -> {
                for (OresFuture.RuntimeWaiterRegistration<?> waiter : detached) {
                    waiter.detach();
                }
            });
        }
    }

    /**
     * Drive all registrations until no further atomic commit is possible.
     * Completion callbacks are queued and run only after COORDINATOR is
     * released, preventing channel-lock -> actor-mailbox lock inversion.
     */
    private static void pumpLocked(List<Runnable> completions) {
        boolean progressed;
        do {
            progressed = false;

            for (SelectRegistration selection : List.copyOf(ACTIVE)) {
                if (selection.decided) continue;

                CaseRegistration chosen =
                        preferredCommittableCaseLocked(selection);
                if (chosen == null) continue;

                if (chosen.selectCase instanceof FutureCase<?> awaiting) {
                    Object settled = awaiting.future().runtimeSelectReadyStateOrNull();
                    if (settled == null) continue;
                    Throwable failure = OresFuture.runtimeTerminalFailure(settled);
                    if (failure != null) {
                        commitFailureLocked(chosen, failure, completions);
                    } else {
                        commitSingleLocked(chosen,
                                new SelectResult(chosen.index, SelectOperation.FUTURE,
                                        OresFuture.runtimeTerminalValue(settled)),
                                completions);
                    }
                    progressed = true;
                    break;
                }

                Channel<?> channel = chosen.channel();

                if (chosen.operation() == SelectOperation.READ) {
                    if (!channel.buffer.isEmpty()) {
                        Object value = channel.buffer.removeFirst();
                        commitSingleLocked(
                                chosen,
                                new SelectResult(
                                        chosen.index,
                                        SelectOperation.READ,
                                        value),
                                completions);
                        progressed = true;
                        break;
                    }

                    if (channel.closed) {
                        commitFailureLocked(
                                chosen,
                                channel.closedFailure(),
                                completions);
                        progressed = true;
                        break;
                    }

                    CaseRegistration writer =
                            findMutualPeerLocked(
                                    chosen,
                                    SelectOperation.WRITE);
                    if (writer != null) {
                        commitPairLocked(chosen, writer, completions);
                        progressed = true;
                        break;
                    }
                } else {
                    if (channel.closed) {
                        commitFailureLocked(
                                chosen,
                                channel.closedFailure(),
                                completions);
                        progressed = true;
                        break;
                    }

                    CaseRegistration reader =
                            findMutualPeerLocked(
                                    chosen,
                                    SelectOperation.READ);
                    if (reader != null) {
                        commitPairLocked(reader, chosen, completions);
                        progressed = true;
                        break;
                    }

                    if (channel.capacity > channel.buffer.size()) {
                        @SuppressWarnings("unchecked")
                        Channel<Object> writable =
                                (Channel<Object>) channel;
                        writable.buffer.addLast(chosen.writeValue());
                        commitSingleLocked(
                                chosen,
                                new SelectResult(
                                        chosen.index,
                                        SelectOperation.WRITE,
                                        null),
                                completions);
                        progressed = true;
                        break;
                    }
                }
            }
        } while (progressed);
    }

    private static CaseRegistration preferredCommittableCaseLocked(
            SelectRegistration selection) {
        if (selection.decided) return null;

        // A producer Future can settle midway through this priority scan.
        // Multiple arms awaiting that SAME Future must use one readiness
        // observation for the entire arbitration pass; otherwise an earlier
        // arm may be sampled PENDING while a later duplicate observes SUCCESS
        // and spuriously wins PRIORITY over the identical earlier source.
        // Identity comparison is intentional: distinct Futures are distinct
        // completion authorities, even when their values compare equal.
        // IdentityHashMap accepts null values so PENDING is cached too.
        java.util.IdentityHashMap<OresFuture<?>, Object> observedFutureStates = null;
        for (int index : selection.order) {
            SelectCase selectCase = selection.set.cases.get(index);
            if (selectCase instanceof DefaultCase) continue;

            CaseRegistration registration =
                    findRegistration(selection, index);
            if (registration == null) continue;

            if (registration.selectCase instanceof FutureCase<?> awaiting) {
                if (observedFutureStates == null) {
                    observedFutureStates = new java.util.IdentityHashMap<>();
                }
                OresFuture<?> producer = awaiting.future();
                Object observed;
                if (observedFutureStates.containsKey(producer)) {
                    observed = observedFutureStates.get(producer);
                } else {
                    observed = producer.runtimeSelectReadyStateOrNull();
                    observedFutureStates.put(producer, observed);
                }
                if (observed != null) return registration;
                continue;
            }
            Channel<?> channel = registration.channel();
            if (registration.operation() == SelectOperation.READ) {
                if (!channel.buffer.isEmpty() || channel.closed) {
                    return registration;
                }
                if (findMutualPeerLocked(
                        registration,
                        SelectOperation.WRITE) != null) {
                    return registration;
                }
            } else {
                if (channel.closed
                        || channel.capacity > channel.buffer.size()) {
                    return registration;
                }
                if (findMutualPeerLocked(
                        registration,
                        SelectOperation.READ) != null) {
                    return registration;
                }
            }
        }
        return null;
    }

    private static CaseRegistration preferredReadyCaseLocked(
            SelectRegistration selection) {
        if (selection.decided) return null;

        // Peer preference must make the same stable observation of repeated
        // Future arms as primary arbitration. A producer can settle outside
        // COORDINATOR while we probe aliases of that producer: without an
        // identity snapshot, an earlier alias can see PENDING while a later
        // alias sees READY in the very same PRIORITY/FAIR preference pass.
        java.util.IdentityHashMap<OresFuture<?>, Boolean> observedFutureReadiness =
                new java.util.IdentityHashMap<>();

        for (int index : selection.order) {
            SelectCase selectCase = selection.set.cases.get(index);
            if (selectCase instanceof DefaultCase) continue;

            CaseRegistration registration =
                    findRegistration(selection, index);
            if (registration == null) continue;

            if (selectCase instanceof FutureCase<?> futureCase) {
                OresFuture<?> producer = futureCase.future();
                Boolean ready = observedFutureReadiness.get(producer);
                if (ready == null) {
                    ready = producer.runtimeSelectReadyStateOrNull() != null;
                    observedFutureReadiness.put(producer, ready);
                }
                if (ready) return registration;
            } else if (basicReadyLocked(registration)) {
                return registration;
            }
        }
        return null;
    }

    private static boolean basicReadyLocked(CaseRegistration registration) {
        if (registration.selectCase instanceof FutureCase<?> awaiting) {
            return awaiting.future().runtimeSelectReadyStateOrNull() != null;
        }
        Channel<?> channel = registration.channel();

        if (registration.operation() == SelectOperation.READ) {
            if (!channel.buffer.isEmpty()) return true;
            if (channel.closed) return true;
            return hasOppositePeerLocked(
                    registration,
                    SelectOperation.WRITE);
        }

        if (channel.closed) return true;
        if (hasOppositePeerLocked(registration, SelectOperation.READ)) {
            return true;
        }
        return channel.capacity > channel.buffer.size();
    }

    private static boolean hasOppositePeerLocked(
            CaseRegistration registration,
            SelectOperation operation) {
        for (CaseRegistration candidate :
                registration.channel().registrations) {
            if (candidate.selection == registration.selection
                    || candidate.selection.decided
                    || candidate.operation() != operation) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * A rendezvous commits only when each selection currently prefers the
     * matching case under its own FAIR/PRIORITY/RANDOM order. This prevents a
     * peer's lower-priority arm from being stolen merely because it is present
     * on the same rendezvous channel.
     */
    private static CaseRegistration findMutualPeerLocked(
            CaseRegistration registration,
            SelectOperation opposite) {
        // Buffered values precede pending writers. Pairing a writer directly
        // with a reader here would let a newer value overtake committed data.
        if (!registration.channel().buffer.isEmpty()) return null;
        CaseRegistration best = null;

        for (CaseRegistration candidate :
                registration.channel().registrations) {
            if (candidate.selection == registration.selection
                    || candidate.selection.decided
                    || candidate.operation() != opposite) {
                continue;
            }

            CaseRegistration peerPreferred =
                    preferredReadyCaseLocked(candidate.selection);
            if (peerPreferred != candidate) continue;

            if (best == null || candidate.ticket < best.ticket) {
                best = candidate;
            }
        }
        return best;
    }

    private static CaseRegistration findRegistration(
            SelectRegistration selection,
            int index) {
        return selection.registeredByIndex[index];
    }

    private static void commitPairLocked(
            CaseRegistration reader,
            CaseRegistration writer,
            List<Runnable> completions) {
        if (reader.selection == writer.selection
                || reader.selection.decided
                || writer.selection.decided) {
            return;
        }

        Object value = writer.writeValue();

        reader.selection.decided = true;
        writer.selection.decided = true;
        reader.selection.set.selected(reader.index, reader.selection.policy);
        writer.selection.set.selected(writer.index, writer.selection.policy);
        unregisterLocked(reader.selection, completions);
        unregisterLocked(writer.selection, completions);

        SelectResult readResult = new SelectResult(
                reader.index,
                SelectOperation.READ,
                value);
        SelectResult writeResult = new SelectResult(
                writer.index,
                SelectOperation.WRITE,
                null);

        completions.add(() -> reader.selection.publishAfterAttachment(
                () -> reader.selection.future.completeFromRuntime(readResult)));
        completions.add(() -> writer.selection.publishAfterAttachment(
                () -> writer.selection.future.completeFromRuntime(writeResult)));
    }

    private static void commitSingleLocked(
            CaseRegistration registration,
            SelectResult result,
            List<Runnable> completions) {
        SelectRegistration selection = registration.selection;
        if (selection.decided) return;

        selection.decided = true;
        selection.set.selected(result.index(), selection.policy);
        unregisterLocked(selection, completions);
        completions.add(() -> selection.publishAfterAttachment(
                () -> selection.future.completeFromRuntime(result)));
    }

    private static void commitFailureLocked(
            CaseRegistration registration,
            Throwable failure,
            List<Runnable> completions) {
        SelectRegistration selection = registration.selection;
        if (selection.decided) return;

        // A terminally ready case still wins selection. FAIR reuse must rotate
        // past it exactly as it does after a successful read/write; otherwise a
        // permanently closed arm can monopolize a reusable SelectSet forever.
        selection.decided = true;
        selection.set.selected(registration.index, selection.policy);
        unregisterLocked(selection, completions);
        completions.add(() -> selection.publishAfterAttachment(
                () -> selection.future.failFromRuntime(failure)));
    }

    private static Channel<?> channelOf(SelectCase selectCase) {
        if (selectCase instanceof ReadCase<?> read) return read.channel();
        if (selectCase instanceof WriteCase<?> write) return write.channel();
        throw new IllegalArgumentException(
                "default select case has no channel");
    }

    static <T> OresFuture<T> mapSelection(
            OresFuture<SelectResult> source,
            Function<SelectResult, T> mapper) {
        OresFuture<T> result = new OresFuture<>(
                () -> source.cancel(false),
                () -> { });

        source.whenCompleteRuntime((selected, failure) -> {
            if (failure == null) {
                try {
                    result.completeFromRuntime(mapper.apply(selected));
                } catch (Throwable mappingFailure) {
                    result.failFromRuntime(mappingFailure);
                }
            } else if (source.isCancelled()) {
                // source is private to this mapping. Its cancellation was
                // initiated by result.cancel(); allow the outer cancellation
                // call to perform the authoritative Cancelled settlement.
                return;
            } else if (!result.isDone()) {
                result.failFromRuntime(failure);
            }
        });
        return result;
    }

    /**
     * Execute required cleanup/follow-up despite a previous failure. Preserve
     * the original exception as primary rather than masking it with a later
     * listener, cleanup, or notification failure.
     */
    private static void runAfterPreservingFailure(
            Throwable primaryFailure, Runnable requiredAction) {
        try {
            requiredAction.run();
        } catch (RuntimeException | Error laterFailure) {
            if (primaryFailure == null) throw laterFailure;
            if (primaryFailure != laterFailure) {
                primaryFailure.addSuppressed(laterFailure);
            }
        }
    }

    /**
     * Every atomic commit may publish more than one terminal Future (notably
     * rendezvous pairs), and unregistering Future arms queues loser detachments
     * ahead of publication. A failure in any one completion must not prevent
     * the remaining committed operations from being published or detached.
     *
     * <p>Do not execute this drain while holding COORDINATOR. Preserve the
     * original failure after running all remaining callbacks, rather than
     * silently treating a broken internal completion hook as success.</p>
     */
    private static void runCompletions(List<Runnable> completions) {
        Throwable firstFailure = null;
        for (Runnable completion : completions) {
            try {
                completion.run();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure instanceof RuntimeException failure) throw failure;
        if (firstFailure instanceof Error failure) throw failure;
    }
}
