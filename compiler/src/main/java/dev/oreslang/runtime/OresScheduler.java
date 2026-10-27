package dev.oreslang.runtime;

import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Scheduler affinity for ordinary Oreslang tasks.
 *
 * <p>An Oreslang task owns one scheduler for its complete logical lifetime.
 * Physical carrier-thread identity is intentionally not stable: after an
 * {@code await}, the continuation may resume on any carrier owned by the same
 * scheduler.</p>
 *
 * <p>Futures do not own schedulers. A suspended task records the scheduler that
 * was executing it when it reached {@code await}; Future completion only makes
 * that task runnable again. Producer, timer, I/O, JNI, and other completion
 * threads never execute the task continuation directly.</p>
 *
 * <p>The compiler lowers an async callable to {@link Task}: a small resumable
 * state machine. Returning {@link Await} suspends the task. Returning
 * {@link Done} completes it.</p>
 */
public final class OresScheduler implements AutoCloseable {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final AtomicLong NEXT_DISPATCH_ID = new AtomicLong();

    private static long allocatePositiveId(AtomicLong counter, String kind) {
        long value = counter.incrementAndGet();
        if (value <= 0) {
            throw new IllegalStateException("OresScheduler " + kind + " id space exhausted");
        }
        return value;
    }
    private static final ThreadLocal<OresScheduler> CURRENT = new ThreadLocal<>();
    /** The carrier still belongs to this scheduler during terminal callbacks. */
    private static final ThreadLocal<OresScheduler> CURRENT_CARRIER_SCHEDULER =
            new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_DISPATCH_ID = new ThreadLocal<>();
    private static final ThreadLocal<ExecutionContext> CURRENT_EXECUTION_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SCHEDULER_CARRIER = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CURRENT_CANCELLATION_CLEANUP =
            new ThreadLocal<>();
    private static final ThreadLocal<ActorTurnWatchdog.Lease> CURRENT_WATCHDOG_LEASE =
            new ThreadLocal<>();
    private static final int DEFAULT_QUEUE_CAPACITY = 65_536;
    // A timed source task owns one global reservation until its Future's
    // terminal subscriber drain completes, even if its guest resources retired.
    // At capacity we reject new timed task admission, never defer an existing
    // deadline or enqueue an unbounded number of blocked notifier threads.
    static final int MAX_ACTIVE_WALL_DEADLINE_TASKS = 256;
    private static final Semaphore WALL_DEADLINE_ADMISSIONS =
            new Semaphore(MAX_ACTIVE_WALL_DEADLINE_TASKS);

    static int availableWallDeadlineAdmissions() {
        return WALL_DEADLINE_ADMISSIONS.availablePermits();
    }

    /**
     * Host-owned wall-clock expiration. Separate from actor/scheduler carriers:
     * an unresolved await cannot consume a new 250 ms budget on resumption.
     * Timer callbacks never execute guest turns or interrupt active carriers.
     */
    private static final ScheduledThreadPoolExecutor WALL_DEADLINES = createWallDeadlineTimer();

    private static ScheduledThreadPoolExecutor createWallDeadlineTimer() {
        AtomicInteger threadId = new AtomicInteger();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(2,
                runnable -> Thread.ofPlatform().daemon(true)
                        .name("ores-source-wall-deadline-" + threadId.incrementAndGet())
                        .unstarted(runnable));
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    @FunctionalInterface
    interface TurnExecutor {
        void execute(Runnable turn);
    }

    /** One compiler-generated async state-machine turn. */
    @FunctionalInterface
    public interface Task<T> {
        Step<T> resume(Resume resume) throws Exception;
    }

    /**
     * Internal source task with heap-owned lexical unwind frames. Cancellation
     * is delivered to its owning scheduler as an authenticated terminal
     * resume, NOT as an ordinary producer failure or a foreign-thread callback.
     * It must bypass source catch and finish its defers/guards before Done.
     */
    public interface CleanupAwareTask<T> extends Task<T> { }

    /** Fatal failure of the physical cleanup witness; do not reclaim loans. */
    public static final class CleanupIncompleteError extends Error {
        public CleanupIncompleteError(String message) { super(message); }
        public CleanupIncompleteError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Result of one resumable task turn. */
    public sealed interface Step<T> permits Done, Await, Cooperate { }

    /** The async task has produced its final value. */
    public record Done<T>(T value) implements Step<T> { }

    /**
     * The async task is suspended on a Future. The scheduler, not the Future,
     * owns the continuation placement.
     */
    public record Await<T>(OresFuture<?> future) implements Step<T> {
        public Await {
            Objects.requireNonNull(future, "future");
        }
    }

    /**
     * Cooperative scheduler handoff. This produces no language value and
     * resumes the same task only after the current carrier turn has unwound.
     */
    public record Cooperate<T>() implements Step<T> { }

    /**
     * Input delivered to a compiler-generated state machine when it starts or
     * resumes after an await.
     */
    public record Resume(boolean initial, Object value, Throwable failure) {
        private static Resume initialResume() {
            return new Resume(true, null, null);
        }

        private static Resume completed(Object value, Throwable failure) {
            return new Resume(false, value, failure);
        }
    }

    // Await dependencies are VM-wide, not scheduler-local: tasks on distinct
    // schedulers may await each other. Identity is essential for Future tokens.
    // Entries exist only while a task is suspended on a pending Future.
    private static final Object AWAIT_GRAPH_LOCK = new Object();
    private static final IdentityHashMap<OresFuture<?>, OresFuture<?>> AWAIT_GRAPH =
            new IdentityHashMap<>();

    private static boolean registerAwaitDependency(
            OresFuture<?> owner, OresFuture<?> awaited) {
        synchronized (AWAIT_GRAPH_LOCK) {
            if (AWAIT_GRAPH.containsKey(owner)) {
                throw new IllegalStateException("task already has an active Future await");
            }
            OresFuture<?> cursor = awaited;
            int remaining = AWAIT_GRAPH.size() + 1;
            while (cursor != null && remaining-- > 0) {
                if (cursor == owner) return false;
                cursor = AWAIT_GRAPH.get(cursor);
            }
            if (cursor != null) {
                // A pre-existing cyclic graph is a runtime invariant violation.
                throw new IllegalStateException("Future await graph contains a cycle");
            }
            AWAIT_GRAPH.put(owner, awaited);
            return true;
        }
    }

    private static void removeAwaitDependency(OresFuture<?> owner) {
        synchronized (AWAIT_GRAPH_LOCK) {
            AWAIT_GRAPH.remove(owner);
        }
    }

    static boolean hasAwaitDependency(OresFuture<?> owner) {
        synchronized (AWAIT_GRAPH_LOCK) {
            return AWAIT_GRAPH.containsKey(owner);
        }
    }

    private final String name;
    private final long schedulerDomain;
    private final AtomicLong nextExecution = new AtomicLong();
    private final int parallelism;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final TurnExecutor turnExecutor;
    private final Set<TaskRunner<?>> tasks = ConcurrentHashMap.newKeySet();
    /** Linearizes new task admission with scheduler shutdown. */
    private final Object taskAdmissionLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Actor STOP seals task admission without closing the cleanup executor. */
    private final AtomicBoolean actorStopSealed = new AtomicBoolean();
    private final AtomicInteger retiringPhysicalCallbacks = new AtomicInteger();
    /** Tracks terminal subscriber drains even after a Task leaves the live set. */
    private final AtomicInteger pendingTerminalNotifications = new AtomicInteger();
    private final AtomicReference<Throwable> physicalRetirementFailure = new AtomicReference<>();
    private volatile Runnable actorStopQuiescenceListener;
    private final AtomicLong watchdogOverruns = new AtomicLong();

    /**
     * Create a user-owned scheduler with exactly {@code parallelism} carrier
     * threads and a bounded ready queue.
     */
    public OresScheduler(int parallelism) {
        this(parallelism, DEFAULT_QUEUE_CAPACITY, Runnable::run);
    }

    public OresScheduler(int parallelism, int queueCapacity) {
        this(parallelism, queueCapacity, Runnable::run);
    }

    private OresScheduler(
            int parallelism,
            int queueCapacity,
            TurnExecutor turnExecutor) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException("scheduler parallelism must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("scheduler queue capacity must be positive");
        }

        this.schedulerDomain = allocatePositiveId(NEXT_ID, "scheduler-domain");
        this.name = "ores-user-scheduler-" + schedulerDomain;
        this.parallelism = parallelism;
        this.turnExecutor = Objects.requireNonNull(turnExecutor, "turnExecutor");

        AtomicInteger carrierId = new AtomicInteger();
        ThreadFactory factory = task -> Thread.ofPlatform()
                .daemon(true)
                .name(name + "-carrier-" + carrierId.getAndIncrement())
                .unstarted(() -> {
                    SCHEDULER_CARRIER.set(Boolean.TRUE);
                    try {
                        task.run();
                    } finally {
                        SCHEDULER_CARRIER.remove();
                    }
                });
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        pool.prestartAllCoreThreads();
        this.executor = pool;
        this.ownedExecutor = pool;
    }

    private OresScheduler(
            String name,
            int parallelism,
            Executor executor,
            ExecutorService ownedExecutor,
            TurnExecutor turnExecutor) {
        this.name = Objects.requireNonNull(name, "name");
        this.schedulerDomain = allocatePositiveId(NEXT_ID, "scheduler-domain");
        if (parallelism <= 0) {
            throw new IllegalArgumentException("scheduler parallelism must be positive");
        }
        this.parallelism = parallelism;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = ownedExecutor;
        this.turnExecutor = Objects.requireNonNull(turnExecutor, "turnExecutor");
    }

    /**
     * Runtime-owned scheduler facade over an existing VM executor. Closing the
     * facade cancels its tasks but does not shut down the shared VM executor.
     */
    static OresScheduler runtimeOwned(
            String name,
            int parallelism,
            Executor executor) {
        return runtimeOwned(name, parallelism, executor, Runnable::run);
    }

    /**
     * Runtime-owned scheduler whose physical carrier dispatch is distinct from
     * guest-turn admission. The carrier executor owns only where the task runs;
     * the turn executor owns the entered language/context boundary.
     *
     * <p>This separation is critical for await completion publication:
     * {@link TaskRunner#afterCarrierTurn()} runs after {@code turnExecutor}
     * returns, so a terminal Future cannot become externally visible until the
     * guest turn has completely left its Truffle context.</p>
     */
    static OresScheduler runtimeOwned(
            String name,
            int parallelism,
            Executor executor,
            TurnExecutor turnExecutor) {
        return new OresScheduler(
                name,
                parallelism,
                executor,
                null,
                turnExecutor);
    }

    /**
     * Context-owned scheduler with private carriers. Each guest turn is wrapped
     * by the owning language context before scheduler binding is installed.
     */
    static OresScheduler managed(
            int parallelism,
            TurnExecutor turnExecutor) {
        return new OresScheduler(
                parallelism,
                DEFAULT_QUEUE_CAPACITY,
                turnExecutor);
    }

    /** True only on private carriers owned by user-created OresSchedulers. */
    public static boolean isSchedulerCarrierThread() {
        return Boolean.TRUE.equals(SCHEDULER_CARRIER.get());
    }

    public String name() {
        return name;
    }

    public int parallelism() {
        return parallelism;
    }

    /** Number of physical dispatch turns flagged by the independent watchdog. */
    public long watchdogOverruns() {
        return watchdogOverruns.get();
    }

    /** Poll records forward progress without claiming a physical carrier handoff. */
    static void checkpointCurrentTurn() {
        ActorTurnWatchdog.Lease lease = CURRENT_WATCHDOG_LEASE.get();
        if (lease != null) lease.checkpoint();
    }

    /** Advisory request; only a compiler-lowered continuation may actually yield. */
    public static boolean preemptionRequested() {
        ActorTurnWatchdog.Lease lease = CURRENT_WATCHDOG_LEASE.get();
        return lease != null && lease.preemptionRequested();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Scheduler currently executing this Ores task turn, if any. */
    public static OresScheduler current() {
        return CURRENT.get();
    }

    public static OresScheduler requireCurrent() {
        OresScheduler scheduler = CURRENT.get();
        if (scheduler == null) {
            throw new IllegalStateException(
                    "operation requires an executing OresScheduler task");
        }
        return scheduler;
    }

    /**
     * Stable logical execution-domain token for the currently running
     * scheduler task, or {@code null} outside a scheduler task.
     *
     * <p>The token survives await/resume carrier migration and is intentionally
     * distinct for concurrent tasks sharing the same OresScheduler.</p>
     */
    public static Object currentTaskDomain() {
        return CURRENT_EXECUTION_CONTEXT.get();
    }

    /**
     * Stable logical execution context for the current Oreslang task.
     *
     * <p>This identity survives await/cooperate carrier migration. It is
     * deliberately distinct from {@link #currentDispatchId()} and from any
     * actor heap/allocation domain.</p>
     */
    public static ExecutionContext currentExecutionContext() {
        return CURRENT_EXECUTION_CONTEXT.get();
    }

    /**
     * Identifier for the current scheduler dispatch turn, or {@code 0} outside
     * an OresScheduler dispatch. A continuation resumed after {@code await}
     * always observes a different dispatch id, even when the scheduler chooses
     * the same physical carrier thread immediately.
     */
    public static long currentDispatchId() {
        Long id = CURRENT_DISPATCH_ID.get();
        return id == null ? 0L : id;
    }

    public static <T> Step<T> done(T value) {
        return new Done<>(value);
    }

    public static <T> Step<T> await(OresFuture<?> future) {
        return new Await<>(Objects.requireNonNull(future, "future"));
    }

    /** Lowering target for source-level `rt cooperate`. */
    public static <T> Step<T> cooperate() {
        return new Cooperate<>();
    }

    /**
     * Read-only cooperative cancellation poll for a compiler-generated safe
     * point inside the CURRENT tracked Task turn. It grants no cancellation
     * authority and never interrupts a physical carrier.
     *
     * Ordinary scheduler tasks without a tracked cancellation owner report
     * false; nested inline Task turns restore the previous owner's token.
     */
    public static boolean cancellationRequested() {
        return TaskHandle.currentCancellationRequested();
    }

    /**
     * Source interpreter only: true during every scheduler-authenticated
     * terminal-cleanup turn, including subsequent awaited defer/iterator-close
     * resumptions. Cancellation is injected exactly once, but its cleanup
     * authority persists until the Task physically retires. This is NOT a
     * caller-provided CancellationException classifier or general guest right.
     */
    public static boolean cancellationCleanupTurn() {
        return Boolean.TRUE.equals(CURRENT_CANCELLATION_CLEANUP.get());
    }

    /**
     * Explicit cancellation checkpoint for compiler-lowered loops and
     * carefully selected synchronous boundaries. A pending cancellation
     * raises a private runtime marker, unwinding ordinary Java finally blocks
     * before the Task reaches its terminal quiescence barrier.
     *
     * Never lower this inside an ownership-critical or non-reentrant section;
     * do not insert it automatically into every ordinary watchdog poll.
     * Async finally/defer lowering requires a separate cleanup state machine.
     */
    public static void cancellationCheckpoint() {
        TaskHandle.checkCurrentCancellationCheckpoint();
    }

    /**
     * Start one compiler-lowered async task on this scheduler.
     *
     * <p>The returned Future represents the whole task. Awaiting it from another
     * scheduler does not move the waiter onto this scheduler.</p>
     */
    public <T> OresFuture<T> start(Task<T> task) {
        return start(task, () -> { });
    }

    /**
     * Host-runtime opt-in for a cancellable Task with a distinct physical
     * quiescence barrier. This does not change existing Future semantics.
     * Source Task syntax and structured actor scopes require separate lowering.
     */
    public <T> TaskHandle<T> startTracked(Task<T> task) {
        return TaskHandle.start(this, task);
    }

    /**
     * Create an explicit host/runtime Task scope. The caller must seal or
     * close it and await its getDone() barrier when physical quiescence matters.
     * Guest actor/tenant authority is not exposed by this staging API.
     */
    public TaskScope newTaskScope() {
        synchronized (taskAdmissionLock) {
            ensureTaskAdmissionOpen();
            return new TaskScope(this);
        }
    }

    /**
     * Trusted runtime lifecycle hook. A cancelled Future can settle before
     * the current guest carrier has physically exited; this callback fires
     * only after task execution is quiescent (or before it ever started).
     * Never use Future settlement alone to reclaim guest-owned resources.
     */
    <T> OresFuture<T> start(Task<T> task, Runnable onQuiescent) {
        return startAdmitted(task, onQuiescent, null, 0L, () -> { });
    }

    /**
     * Trusted runtime-only admission with an independently enforced remaining
     * wall budget. Used for UNTRUSTED source continuations, including children
     * sharing one logical quota. Do not expose this to guest code.
     */
    <T> OresFuture<T> startWithWallDeadline(
            Task<T> task, Runnable onQuiescent, long remainingNanos,
            Throwable deadlineFailure) {
        Objects.requireNonNull(deadlineFailure, "deadlineFailure");
        return startWithWallDeadline(task, onQuiescent, remainingNanos,
                deadlineFailure, () -> { });
    }

    /**
     * Conservative physical reclamation gate for runtime-owned actor schedulers.
     * A Task stays in this live set until its physical onQuiescent callback
     * has returned successfully. This also covers the interval when a Task
     * has already reached TERMINAL but a host/FFI retirement callback is
     * still running, and covers an ordinary Task's live carrier. A failed
     * cleanup/retirement callback remains retained indefinitely for quarantine.
     * The actor must never interpret Future.isDone() as a heap release proof.
     */
    public boolean hasIncompleteCleanup() {
        return physicalRetirementFailure.get() != null
                || !tasks.isEmpty()
                || retiringPhysicalCallbacks.get() != 0
                || pendingTerminalNotifications.get() != 0;
    }
    boolean actorStopPhysicallyQuiescent() {
        return tasks.isEmpty() && retiringPhysicalCallbacks.get() == 0
                && pendingTerminalNotifications.get() == 0;
    }

    Runnable sealActorStopAdmission(Runnable onPhysicallyQuiescent) {
        Objects.requireNonNull(onPhysicallyQuiescent, "onPhysicallyQuiescent");
        final Set<TaskRunner<?>> snapshot;
        synchronized (taskAdmissionLock) {
            if (!actorStopSealed.compareAndSet(false, true)) return () -> { };
            actorStopQuiescenceListener = onPhysicallyQuiescent;
            snapshot = Set.copyOf(tasks);
        }
        AtomicBoolean cancellationPublished = new AtomicBoolean();
        return () -> {
            if (!cancellationPublished.compareAndSet(false, true)) return;
            Throwable firstFailure = null;
            try {
                for (TaskRunner<?> runner : snapshot) {
                    try { runner.completion.cancel(false); }
                    catch (RuntimeException | Error failure) {
                        if (firstFailure == null) firstFailure = failure;
                        else if (firstFailure != failure) firstFailure.addSuppressed(failure);
                    }
                }
            } finally {
                notifyActorStopQuiescent();
            }
            if (firstFailure instanceof RuntimeException runtime) throw runtime;
            if (firstFailure instanceof Error fatal) throw fatal;
        };
    }

    private void notifyActorStopQuiescent() {
        Runnable listener = actorStopQuiescenceListener;
        if (listener != null && actorStopSealed.get() && actorStopPhysicallyQuiescent()) {
            listener.run();
        }
    }


    /** Called only after the winning Future's terminal callbacks have drained. */
    <T> OresFuture<T> startWithWallDeadline(
            Task<T> task, Runnable onQuiescent, long remainingNanos,
            Throwable deadlineFailure, Runnable onNotificationDrained) {
        Objects.requireNonNull(deadlineFailure, "deadlineFailure");
        Objects.requireNonNull(onNotificationDrained, "onNotificationDrained");
        return startAdmitted(task, onQuiescent, deadlineFailure,
                Math.max(0L, remainingNanos), onNotificationDrained);
    }

    private <T> OresFuture<T> startAdmitted(
            Task<T> task, Runnable onQuiescent,
            Throwable deadlineFailure, long remainingNanos,
            Runnable onNotificationDrained) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(onQuiescent, "onQuiescent");
        Objects.requireNonNull(onNotificationDrained, "onNotificationDrained");
        // Linearize open-check + live-set registration with close(), but do
        // NOT hold the lock while handing work to an arbitrary executor.
        // Shutdown must still cancel this task if enqueue blocks or rejects.
        TaskRunner<T> runner = new TaskRunner<>(task, onQuiescent,
                deadlineFailure != null, onNotificationDrained);
        synchronized (taskAdmissionLock) {
            ensureTaskAdmissionOpen();
            if (deadlineFailure != null) {
                if (!WALL_DEADLINE_ADMISSIONS.tryAcquire()) {
                    throw new RejectedExecutionException(
                            "global source wall-deadline admission is full");
                }
                runner.reserveDeadlineSlot();
            }
            try {
                tasks.add(runner);
                pendingTerminalNotifications.incrementAndGet();
            } catch (RuntimeException | Error failure) {
                runner.releaseUnusedDeadlineSlot();
                throw failure;
            }
        }
        try {
            if (deadlineFailure != null) {
                runner.armWallDeadline(remainingNanos, deadlineFailure);
            }
            runner.scheduleInitial();
        } catch (RuntimeException | Error failure) {
            // A concurrent close may already have cancelled and retired the
            // NEW runner. failBeforeStart is idempotent in that case.
            runner.failBeforeStart(failure);
        }
        return runner.completion;
    }

    /**
     * Runtime/host bridge for non-suspending work. Source-level synchronous
     * lambdas may lower to this path.
     */
    public <T> OresFuture<T> startSync(Callable<? extends T> task) {
        Objects.requireNonNull(task, "task");
        AtomicBoolean entered = new AtomicBoolean();
        return start(resume -> {
            if (!resume.initial() || !entered.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "synchronous scheduler task was resumed more than once");
            }
            return done(task.call());
        });
    }

    /**
     * Bind an already-admitted VM CONTROL/root turn to this scheduler without
     * hopping threads. Used by the legacy root-task bridge while source-level
     * main/async lowering moves to {@link #start(Task)}.
     */
    void runBound(Runnable turn) {
        Objects.requireNonNull(turn, "turn");
        ensureOpen();
        OresScheduler prior = CURRENT.get();
        Long priorDispatch = CURRENT_DISPATCH_ID.get();
        CURRENT.set(this);
        CURRENT_DISPATCH_ID.set(allocatePositiveId(NEXT_DISPATCH_ID, "dispatch"));
        try {
            turn.run();
        } finally {
            if (priorDispatch == null) {
                CURRENT_DISPATCH_ID.remove();
            } else {
                CURRENT_DISPATCH_ID.set(priorDispatch);
            }
            if (prior == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(prior);
            }
        }
    }

    private void executeTurn(Runnable turn, Runnable afterTurn,
            java.util.function.Consumer<Throwable> admissionFailure) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(afterTurn, "afterTurn");
        ensureOpen();
        executor.execute(() -> {
            boolean priorCarrier = Boolean.TRUE.equals(SCHEDULER_CARRIER.get());
            OresScheduler previousCarrierScheduler = CURRENT_CARRIER_SCHEDULER.get();
            SCHEDULER_CARRIER.set(Boolean.TRUE);
            CURRENT_CARRIER_SCHEDULER.set(this);
            try {
                turnExecutor.execute(() -> runBound(turn));
            } catch (RuntimeException | Error failure) {
                // Context admission can fail before runTurn starts. Settle the
                // owning task instead of leaving the host waiting forever.
                admissionFailure.accept(failure);
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                if (failure instanceof LinkageError fatal) throw fatal;
            } finally {
                try {
                    afterTurn.run();
                } finally {
                    if (previousCarrierScheduler == null) {
                        CURRENT_CARRIER_SCHEDULER.remove();
                    } else {
                        CURRENT_CARRIER_SCHEDULER.set(previousCarrierScheduler);
                    }
                    if (priorCarrier) {
                        SCHEDULER_CARRIER.set(Boolean.TRUE);
                    } else {
                        SCHEDULER_CARRIER.remove();
                    }
                }
            }
        });
    }

    private void ensureTaskAdmissionOpen() {
        ensureOpen();
        if (actorStopSealed.get()) {
            throw new RejectedExecutionException(
                    "OresScheduler " + name + " sealed for actor cleanup");
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new RejectedExecutionException(
                    "OresScheduler " + name + " is closed");
        }
    }

    @Override
    public void close() {
        if (CURRENT.get() == this) {
            throw new IllegalStateException(
                    "OresScheduler cannot be closed from one of its own task turns; "
                            + "close it from an outside/root scheduler task");
        }
        final Set<TaskRunner<?>> admitted;
        synchronized (taskAdmissionLock) {
            if (!closed.compareAndSet(false, true)) return;
            admitted = Set.copyOf(tasks);
        }

        // Revoke *all* task state before notifying any Future subscriber.
        // Future.cancel() synchronously invokes completion waiters, and an
        // arbitrary waiter may block. A blocked subscriber for task A must
        // not prevent task B's await registration from being detached, or
        // prevent B from retaining only live physical execution resources.
        for (TaskRunner<?> task : admitted) {
            task.revokeFromSchedulerClose();
        }
        for (TaskRunner<?> task : admitted) {
            task.publishCancelledFromSchedulerClose();
        }
        // Do not clear tasks here: cancelled Futures may settle while guest
        // carriers still execute. Each TaskRunner removes itself only after
        // scheduler-confirmed quiescence, including during shutdown.

        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
            // A terminal Future listener runs after CURRENT has been cleared
            // but may still be executing on this executor's physical carrier.
            // Waiting for our own thread to terminate would deadlock/time out.
            if (CURRENT_CARRIER_SCHEDULER.get() != this) {
                try {
                    if (!ownedExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "OresScheduler " + name + " carriers did not terminate");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException(
                            "interrupted while closing OresScheduler " + name);
                }
            }
        }
    }

    private final class TaskRunner<T> implements BiConsumer<Object, Throwable> {
        private static final int NEW = 0;
        private static final int QUEUED = 1;
        private static final int RUNNING = 2;
        private static final int WAITING = 3;
        private static final int TERMINAL = 4;
        private static final int MAX_CLEANUP_DISPATCHES = 4_096;

        private final Task<T> task;
        private final ExecutionContext executionContext;
        private final Runnable onQuiescent;
        // 0=unclaimed, 1=retirement callback executing, 2=retired,
        // 3=callback failed. The publication path cannot overtake state 1.
        private final AtomicInteger quiescenceState = new AtomicInteger();
        private final AtomicReference<Throwable> quiescenceFailure = new AtomicReference<>();
        private final java.util.Map<String, dev.oreslang.interop.MixedInteropBridge.Invoker>
                interopScope = dev.oreslang.interop.MixedInteropBridge.capture();
        private final AtomicInteger phase = new AtomicInteger(NEW);
        private final AtomicBoolean executing = new AtomicBoolean();
        /**
         * Guest cancellation checkpoints are observed during the turn, but
         * host Future subscribers must be notified after the guest execution
         * boundary unwinds. Only the RUNNING -> TERMINAL winner owns this.
         */
        private final AtomicBoolean checkpointCancellationPending = new AtomicBoolean();
        private final AtomicBoolean cleanupCancellationInjected = new AtomicBoolean();
        private final AtomicBoolean cleanupIncomplete = new AtomicBoolean();
        private final AtomicInteger cleanupDispatches = new AtomicInteger();
        private final AtomicReference<Resume> pendingResume =
                new AtomicReference<>(Resume.initialResume());
        private final AtomicReference<TerminalOutcome<T>> terminalOutcome =
                new AtomicReference<>();
        private final AtomicReference<OresFuture.RuntimeWaiterRegistration<?>>
                activeAwaitRegistration = new AtomicReference<>();
        private final OresFuture<T> completion;
        private final boolean timedAdmission;
        private final Runnable onNotificationDrained;
        private final AtomicBoolean deadlineSlotReserved = new AtomicBoolean();
        private final AtomicBoolean deadlineSlotReleased = new AtomicBoolean();
        private final AtomicBoolean deadlineNotificationDrained = new AtomicBoolean();
        private final AtomicBoolean terminalNotificationRetired = new AtomicBoolean();
        private final AtomicBoolean deadlineQuiescenceRetired = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> wallDeadline =
                new AtomicReference<>();
        private volatile ActorTurnWatchdog.Lease watchdogLease;

        private TaskRunner(Task<T> task, Runnable onQuiescent,
                boolean timedAdmission, Runnable onNotificationDrained) {
            this.task = task;
            long execution = allocatePositiveId(nextExecution, "execution");
            this.executionContext = ExecutionContext.task(schedulerDomain, execution);
            this.onQuiescent = onQuiescent;
            this.timedAdmission = timedAdmission;
            this.onNotificationDrained = onNotificationDrained;
            this.completion = new OresFuture<>(
                    () -> true, this::cancelFromFuture, this::notificationDrained,
                    task instanceof CleanupAwareTask<?>);
        }

        private void reserveDeadlineSlot() {
            if (!timedAdmission || !deadlineSlotReserved.compareAndSet(false, true)) {
                throw new IllegalStateException("wall deadline slot reserved twice");
            }
        }

        private void notificationDrained() {
            try {
                // Terminal is visible before synchronous callbacks finish.
                // An actor's memory must never be reclaimed while one such
                // subscriber still owns host/FFI retirement references.
                onNotificationDrained.run();
            } catch (RuntimeException | Error failure) {
                physicalRetirementFailure.compareAndSet(null, failure);
                throw failure;
            } finally {
                deadlineNotificationDrained.set(true);
                try {
                    releaseDeadlineSlotIfRetired();
                } finally {
                    if (terminalNotificationRetired.compareAndSet(false, true)) {
                        int remaining = pendingTerminalNotifications.decrementAndGet();
                        if (remaining < 0) {
                            physicalRetirementFailure.compareAndSet(null,
                                    new IllegalStateException(
                                            "scheduler terminal notification receipt underflow"));
                        }
                        notifyActorStopQuiescent();
                    }
                }
            }
        }

        private void releaseDeadlineSlotIfRetired() {
            // Both subscriber notification and physical guest quiescence must
            // have finished. Cancellation alone is not a memory-release event.
            if (deadlineSlotReserved.get() && deadlineNotificationDrained.get()
                    && deadlineQuiescenceRetired.get()
                    && deadlineSlotReleased.compareAndSet(false, true)) {
                WALL_DEADLINE_ADMISSIONS.release();
            }
        }

        private void releaseUnusedDeadlineSlot() {
            // Only for a failed admission before task publication to the
            // scheduler live set. No external Future could have been observed.
            if (deadlineSlotReserved.get()
                    && deadlineSlotReleased.compareAndSet(false, true)) {
                WALL_DEADLINE_ADMISSIONS.release();
            }
        }

        private void armWallDeadline(long delayNanos, Throwable failure) {
            ScheduledFuture<?> timer = WALL_DEADLINES.schedule(
                    () -> expireFromWallDeadline(failure),
                    delayNanos, TimeUnit.NANOSECONDS);
            if (!wallDeadline.compareAndSet(null, timer)) {
                timer.cancel(false);
                throw new IllegalStateException("task wall deadline already armed");
            }
            // A concurrent close may have retired this task while the timer
            // was being submitted. Never retain a stale queued deadline.
            if (phase.get() == TERMINAL) cancelWallDeadline();
        }

        private void cancelWallDeadline() {
            ScheduledFuture<?> timer = wallDeadline.getAndSet(null);
            if (timer != null) timer.cancel(false);
        }

        private void scheduleInitial() {
            if (!phase.compareAndSet(NEW, QUEUED)) {
                throw new IllegalStateException("scheduler task was already started");
            }
            enqueueTurn();
        }

        private void enqueueTurn() {
            // Each scheduled dispatch owns its own carrier-lease bit. A stale
            // or duplicate dispatch must never clear another running dispatch's
            // executing flag, watchdog identity or quota-retirement barrier.
            AtomicBoolean ownsExecution = new AtomicBoolean();
            try {
                executeTurn(() -> {
                    try (var scope = dev.oreslang.interop.MixedInteropBridge.open(interopScope)) {
                        runTurn(ownsExecution);
                    }
                }, () -> afterCarrierTurn(ownsExecution.get()), this::failTerminal);
            } catch (RuntimeException | Error rejected) {
                failTerminal(rejected);
                throw rejected;
            }
        }

        private void runTurn(AtomicBoolean ownsExecution) {
            // Claim the physical guest execution lease BEFORE transitioning to
            // RUNNING. Otherwise cancellation can observe RUNNING with
            // executing=false, announce quiescence and release actor memory
            // while this carrier is about to begin task.resume().
            if (!executing.compareAndSet(false, true)) {
                // A stale/duplicate dispatch lost the physical lease. It
                // does not own the current turn and MUST NOT fail, retire or
                // publish any outcome for the legitimate running dispatch.
                // Its afterTurn callback also receives ownsExecution=false.
                return;
            }
            ownsExecution.set(true);
            if (!phase.compareAndSet(QUEUED, RUNNING)) return;

            ActorTurnWatchdog.Lease lease = ActorTurnWatchdog.enter(
                    ignored -> watchdogOverruns.incrementAndGet());
            watchdogLease = lease;
            ActorTurnWatchdog.Lease previousWatchdog = CURRENT_WATCHDOG_LEASE.get();
            CURRENT_WATCHDOG_LEASE.set(lease);
            ExecutionContext priorExecutionContext = CURRENT_EXECUTION_CONTEXT.get();
            CURRENT_EXECUTION_CONTEXT.set(executionContext);
            try {
                detachActiveAwaitRegistration();
                Resume resume = pendingResume.getAndSet(null);
                if (resume == null) {
                    failTerminal(new IllegalStateException(
                            "scheduler resumed a task without an await result"));
                    return;
                }

                // Cancellation of a cleanup-aware SourceTask MUST reenter its
                // saved lexical continuation rather than clearing it. Only
                // one resume is injected; later awaits belong to shielded
                // cleanup, and must be allowed to complete normally.
                java.util.concurrent.CancellationException requested =
                        completion.deferredCancellationCause();
                if (requested != null && !cleanupCancellationInjected.get()
                        && task instanceof CleanupAwareTask<?>) {
                    if (resume.initial()) {
                        failTerminal(requested); // no guest frames to unwind
                        return;
                    }
                    cleanupCancellationInjected.set(true);
                    resume = Resume.completed(null, requested);
                }

                if (cleanupCancellationInjected.get()
                        && task instanceof CleanupAwareTask<?>
                        && cleanupDispatches.incrementAndGet()
                                > MAX_CLEANUP_DISPATCHES) {
                    cleanupIncomplete.set(true);
                    failTerminal(new CleanupIncompleteError(
                            "source cancellation cleanup exceeded dispatch budget"));
                    return;
                }

                final Step<T> step;
                Boolean priorCleanup = CURRENT_CANCELLATION_CLEANUP.get();
                // A source defer may itself suspend repeatedly. Every one of
                // those resumes is still a cleanup-only Task turn.
                if (cleanupCancellationInjected.get()) {
                    CURRENT_CANCELLATION_CLEANUP.set(true);
                } else {
                    CURRENT_CANCELLATION_CLEANUP.remove();
                }
                try {
                    step = Objects.requireNonNull(
                            task.resume(resume),
                            "OresScheduler task returned null Step");
                } catch (Throwable failure) {
                    if (TaskHandle.isAuthenticatedCheckpointCancellation(failure)) {
                        // Only the winning checkpoint may change RUNNING to
                        // TERMINAL. A wall deadline or forced cancellation
                        // that already won keeps its existing terminal cause.
                        if (phase.compareAndSet(RUNNING, TERMINAL)) {
                            checkpointCancellationPending.set(true);
                        }
                        // Never invoke arbitrary synchronous Future listeners
                        // while the guest turn/Truffle context is still bound.
                        // afterCarrierTurn() will publish cancellation.
                        return;
                    }
                    failTerminal(failure);
                    if (failure instanceof VirtualMachineError fatal) throw fatal;
                    if (failure instanceof ThreadDeath fatal) throw fatal;
                    if (failure instanceof LinkageError fatal) throw fatal;
                    return;
                } finally {
                    if (priorCleanup == null) CURRENT_CANCELLATION_CLEANUP.remove();
                    else CURRENT_CANCELLATION_CLEANUP.set(priorCleanup);
                }

                if (step instanceof Done<?> done) {
                    @SuppressWarnings("unchecked")
                    T value = (T) done.value();
                    finish(value);
                    return;
                }

                if (step instanceof Await<?> await) {
                    armAwait(await.future());
                    return;
                }

                if (step instanceof Cooperate<?>) {
                    armCooperate();
                    return;
                }

                failTerminal(new IllegalStateException(
                        "unknown OresScheduler task step " + step.getClass().getName()));
            } finally {
                if (previousWatchdog == null) {
                    CURRENT_WATCHDOG_LEASE.remove();
                } else {
                    CURRENT_WATCHDOG_LEASE.set(previousWatchdog);
                }
                if (priorExecutionContext == null) {
                    CURRENT_EXECUTION_CONTEXT.remove();
                } else {
                    CURRENT_EXECUTION_CONTEXT.set(priorExecutionContext);
                }
            }
        }

        /**
         * Runs only after the scheduler binding has been removed and the
         * carrier has completely unwound the logical guest turn. Publishing a
         * task Future earlier can let a host close its Polyglot Context while
         * this carrier is still executing guest continuation code.
         */
        private void afterCarrierTurn(boolean ownsExecution) {
            if (!ownsExecution) {
                // This carrier never owned the physical lease; another
                // dispatch may still be inside guest code. Never clear it.
                retireWhenQuiescent();
                return;
            }
            ActorTurnWatchdog.Lease lease = watchdogLease;
            watchdogLease = null;
            if (lease != null) lease.close();
            executing.set(false);
            if (task instanceof CleanupAwareTask<?>
                    && completion.deferredCancellationCause() != null
                    && !cleanupCancellationInjected.get()) {
                requestCleanupCancellation();
            }
            // After the guest turn has unwound, release its lifecycle
            // resources before notifying arbitrary Future subscribers.
            // Completion callbacks are synchronous and can block indefinitely;
            // they must not hold a retired actor quota or scheduler task live.
            try {
                retireWhenQuiescent();
            } finally {
                // runBound() and the guest turn have fully unwound. At this
                // point Future subscribers may run without observing a live
                // guest context. A competing wall deadline retains precedence
                // because only the RUNNING -> TERMINAL winner set this bit.
                if (checkpointCancellationPending.getAndSet(false)) {
                    completion.cancel(false);
                }
                publishTerminalIfReady();
                scheduleReadyResume();
            }
        }

        private void armCooperate() {
            Resume resume = Resume.completed(null, null);
            if (!pendingResume.compareAndSet(null, resume)) {
                failTerminal(new IllegalStateException(
                        "cooperate attempted to publish more than one resume"));
                return;
            }
            if (!phase.compareAndSet(RUNNING, WAITING)) {
                pendingResume.compareAndSet(resume, null);
            }
            // afterCarrierTurn() owns requeueing so this dispatch fully
            // unwinds before the continuation becomes runnable again.
        }

        private void armAwait(OresFuture<?> awaited) {
            // Awaiting the task's own completion creates an unresolvable
            // dependency (including Future.flatten() cycles). Reject before
            // publishing a waiter, rather than retaining the task forever.
            if (awaited == completion) {
                failTerminal(new IllegalStateException(
                        "scheduler task cannot await its own completion Future"));
                return;
            }
            if (!phase.compareAndSet(RUNNING, WAITING)) {
                return;
            }

            try {
                // Settled futures need no dependency edge and cannot take
                // part in a *pending* cycle. Keep their fast path lock-free.
                Object alreadyTerminal = awaited.runtimeTerminalStateOrNull();
                if (alreadyTerminal != null) {
                    deliverAwaitCompletion(
                            OresFuture.runtimeTerminalValue(alreadyTerminal),
                            OresFuture.runtimeTerminalFailure(alreadyTerminal));
                    return;
                }
                // Register before callback publication. Detect A -> B -> ... -> A
                // under one lock, including cycles crossing scheduler instances.
                // No user code executes while the graph lock is held.
                if (!registerAwaitDependency(completion, awaited)) {
                    // The producer may have settled while another task
                    // published a dependency. Prefer the terminal outcome
                    // over a now-obsolete await graph.
                    Object terminal = awaited.runtimeTerminalStateOrNull();
                    if (terminal != null) {
                        deliverAwaitCompletion(
                                OresFuture.runtimeTerminalValue(terminal),
                                OresFuture.runtimeTerminalFailure(terminal));
                        return;
                    }
                    failTerminal(new IllegalStateException(
                            "cyclic Future await dependency detected"));
                    return;
                }
                // Cancellation might have revoked this task before the graph
                // edge was installed. Recheck admission before fast-path
                // observation so terminal results cannot strand an edge.
                if (phase.get() != WAITING) {
                    removeAwaitDependency(completion);
                    return;
                }
                // Hot path: terminal Futures are immutable. Observe their
                // already-published terminal state directly and enqueue the
                // continuation for a fresh dispatch without allocating a
                // waiter, queue node, registration, or capturing callback.
                //
                // This deliberately does NOT resume inline. executing is still
                // true until afterCarrierTurn(), so deliverAwaitCompletion()
                // can only make the task ready; the scheduler re-enters it
                // through a later dispatch after the current stack unwinds.
                Object terminal = awaited.runtimeTerminalStateOrNull();
                if (terminal != null) {
                    deliverAwaitCompletion(
                            OresFuture.runtimeTerminalValue(terminal),
                            OresFuture.runtimeTerminalFailure(terminal));
                    return;
                }

                // Pending path: TaskRunner itself is the reusable callback
                // target, avoiding a new captured lambda for every await. The
                // detachable registration remains per suspension so scheduler
                // close/task cancellation can sever retention safely.
                OresFuture.RuntimeWaiterRegistration<?> registration =
                        awaited.whenCompleteRuntimeCancellable(this);

                OresFuture.RuntimeWaiterRegistration<?> previous =
                        activeAwaitRegistration.getAndSet(registration);
                if (previous != null) previous.detach();

                // Settlement may race between the terminal observation above
                // and waiter publication. whenCompleteRuntimeCancellable()
                // handles that race and may call accept(...) synchronously.
                // If it did, clear the already-claimed registration now.
                if (phase.get() != WAITING || pendingResume.get() != null) {
                    detachActiveAwaitRegistration();
                }
            } catch (RuntimeException | Error registrationFailure) {
                failTerminal(registrationFailure);
            }
        }

        @Override
        public void accept(Object value, Throwable failure) {
            deliverAwaitCompletion(value, failure);
        }

        private void deliverAwaitCompletion(Object value, Throwable failure) {
            if (phase.get() == TERMINAL
                    || (task instanceof CleanupAwareTask<?>
                        && completion.deferredCancellationCause() != null
                        && !cleanupCancellationInjected.get())) {
                removeAwaitDependency(completion);
                return;
            }

            Resume resume = Resume.completed(
                    value,
                    failure == null ? null : OresFuture.unwrap(failure));
            if (!pendingResume.compareAndSet(null, resume)) {
                failTerminal(new IllegalStateException(
                        "await delivered more than one resume to the same task"));
                return;
            }

            // The awaited Future has settled: this is no longer a pending
            // dependency, even though scheduling the continuation may lag.
            removeAwaitDependency(completion);
            if (phase.get() == TERMINAL) {
                pendingResume.compareAndSet(resume, null);
                return;
            }
            scheduleReadyResume();
        }

        /**
         * Producer completion may happen on any thread. It may only make this
         * task runnable. The continuation itself executes after the prior turn
         * released its execution lease and only through this scheduler.
         */
        private void scheduleReadyResume() {
            if (executing.get()) return;
            if (pendingResume.get() == null) return;
            if (!phase.compareAndSet(WAITING, QUEUED)) return;

            try {
                enqueueTurn();
            } catch (RejectedExecutionException rejected) {
                // enqueueTurn already failed the task.
            }
        }

        private void detachActiveAwaitRegistration() {
            removeAwaitDependency(completion);
            OresFuture.RuntimeWaiterRegistration<?> registration =
                    activeAwaitRegistration.getAndSet(null);
            if (registration != null) registration.detach();
        }

        private void finish(T value) {
            detachActiveAwaitRegistration();
            if (!phase.compareAndSet(RUNNING, TERMINAL)) {
                return;
            }
            terminalOutcome.set(new TerminalSuccess<>(value));
            // afterCarrierTurn() publishes only after the carrier has fully
            // unwound runBound(...).
        }

        /**
         * A host deadline must not depend on a runnable guest continuation.
         * Revoke task authority and retire only quiescent resources on the
         * timer worker; Future subscribers are invoked on a separate virtual
         * host notifier because they are synchronous and can block forever.
         * An executing carrier still owns terminal publication after unwind.
         */
        private void expireFromWallDeadline(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            if (task instanceof CleanupAwareTask<?>
                    && cleanupCancellationInjected.get()) {
                cleanupIncomplete.set(true);
                failure = new CleanupIncompleteError(
                        "deadline terminated in-progress source cleanup; quarantine loans",
                        failure);
            }
            detachActiveAwaitRegistration();
            int observed;
            do {
                observed = phase.get();
                if (observed == TERMINAL) return;
            } while (!phase.compareAndSet(observed, TERMINAL));
            pendingResume.set(null);
            terminalOutcome.compareAndSet(null, new TerminalFailure<>(failure));
            if (!executing.get()) {
                // Quiescence retirement can acquire an actor lifecycle lock,
                // which might be held indefinitely by a stuck host callback.
                // Keep BOTH resource retirement and Future notification off
                // the shared timer worker. Global timed admission bounds the
                // number of virtual host finalizers, including blocked ones.
                Thread.ofVirtual().name("ores-source-wall-expiry-finalize")
                        .start(() -> {
                            try {
                                retireWhenQuiescent();
                            } finally {
                                publishTerminalIfReady();
                            }
                        });
            }
        }

        private void failTerminal(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            if (task instanceof CleanupAwareTask<?>
                    && (failure instanceof CleanupIncompleteError
                        || failure instanceof ActorRuntime.UntrustedActorQuotaExceededException
                        // An untrappable panic while unwinding may leave a
                        // later defer/guard/FFI loan unexecuted. Preserve the
                        // original panic identity, but quarantine its owner.
                        || (cleanupCancellationInjected.get()
                            && failure instanceof Error)
                        || (completion.deferredCancellationCause() != null
                            && failure instanceof RejectedExecutionException))) {
                // Quota rejection can occur BEFORE a cancellation resume even
                // reaches SourceTask. Treat it as incomplete lexical cleanup
                // and never issue a false heap/guard retirement receipt.
                cleanupIncomplete.set(true);
                if (!(failure instanceof CleanupIncompleteError)
                        && !(cleanupCancellationInjected.get()
                            && failure instanceof Error)) {
                    failure = new CleanupIncompleteError(
                            "source cleanup denied or quota exhausted; quarantine loans",
                            failure);
                }
            }
            detachActiveAwaitRegistration();
            int observed;
            do {
                observed = phase.get();
                if (observed == TERMINAL) return;
            } while (!phase.compareAndSet(observed, TERMINAL));

            terminalOutcome.compareAndSet(null, new TerminalFailure<>(failure));
            if (!executing.get()) {
                try {
                    retireWhenQuiescent();
                } finally {
                    publishTerminalIfReady();
                }
            }
        }

        private void retireWhenQuiescent() {
            // Never run physical retirement while a source defer/finally/FFI
            // obligation remains incomplete, or while a guest carrier runs.
            if (cleanupIncomplete.get() || phase.get() != TERMINAL
                    || executing.get() || !quiescenceState.compareAndSet(0, 1))
                return;
            cancelWallDeadline();
            try {
                // Keep the Task registered until the callback has actually
                // finished: it may revoke actor-owned loans and can fail.
                if (cleanupIncomplete.get()) return;
                onQuiescent.run();
                if (!cleanupIncomplete.get()) tasks.remove(this);
            } catch (Throwable retirementFailure) {
                // Do not publish a preceding successful guest result when
                // physical owner/FFI loan retirement failed. The callback is
                // at-most-once; leave the Task strongly held in quarantine.
                quiescenceFailure.compareAndSet(null, retirementFailure);
                cleanupIncomplete.set(true);
                if (retirementFailure instanceof VirtualMachineError fatal) throw fatal;
                if (retirementFailure instanceof ThreadDeath fatal) throw fatal;
                if (retirementFailure instanceof LinkageError fatal) throw fatal;
            } finally {
                deadlineQuiescenceRetired.set(true);
                releaseDeadlineSlotIfRetired();
                quiescenceState.set(cleanupIncomplete.get() ? 3 : 2);
            }
        }

        private void publishTerminalIfReady() {
            if (phase.get() != TERMINAL || completion.isDone()) return;
            // Scheduler.close() can invoke publication from an external thread
            // while a cleanup Task still executes on its owning carrier. The
            // TERMINAL phase is merely logical arbitration, never proof that
            // the physical guest turn has left the actor/Truffle boundary.
            if (executing.get()) return;
            // State 1 denotes an in-flight physical release callback.
            // Scheduler.close() may race it but cannot issue a terminal Future.
            int retirement = quiescenceState.get();
            if (retirement == 1) return;
            if (!cleanupIncomplete.get()
                    && (retirement != 2 || !deadlineQuiescenceRetired.get()))
                return;
            TerminalOutcome<T> outcome = terminalOutcome.get();
            // Terminal phase CAS can precede the outcome write. Never
            // synthesize a clean cancellation receipt without an outcome.
            if (outcome == null) return;
            java.util.concurrent.CancellationException pendingCancellation =
                    completion.deferredCancellationCause();
            if (cleanupIncomplete.get() && task instanceof CleanupAwareTask<?>) {
                // A terminal outcome can race denied cleanup dispatch or a
                // fatal defer. Neither a winning Done nor logical Cancelled
                // proves physical release after quarantine has been latched.
                Throwable retirementFailure = quiescenceFailure.get();
                Throwable incomplete = outcome instanceof TerminalFailure<?> failed
                        ? failed.failure() : retirementFailure != null
                            ? retirementFailure : new CleanupIncompleteError(
                                "physical source cleanup incomplete; quarantine actor loans");
                if (!(incomplete instanceof Error)) {
                    incomplete = new CleanupIncompleteError(
                            "physical source cleanup incomplete; quarantine actor loans",
                            incomplete);
                }
                if (pendingCancellation != null) {
                    completion.failDeferredCleanupFromRuntime(incomplete);
                } else {
                    completion.failFromRuntime(incomplete);
                }
                return;
            }
            Throwable retirementFailure = quiescenceFailure.get();
            if (retirementFailure != null) {
                // Ordinary Tasks also require the physical onQuiescent lease
                // before their result is observable. Preserve the callback
                // failure identity rather than converting it into success.
                completion.failFromRuntime(retirementFailure);
                return;
            }
            if (pendingCancellation != null
                    && task instanceof CleanupAwareTask<?>
                    && !(outcome instanceof TerminalFailure<?> failure
                            && dominatesSourceCleanupCancellation(failure.failure()))) {
                completion.completeDeferredCancellationFromRuntime();
            } else if (outcome instanceof TerminalSuccess<?> success) {
                @SuppressWarnings("unchecked")
                T value = (T) success.value();
                completion.completeFromRuntime(value);
            } else if (outcome instanceof TerminalFailure<?> failure) {
                if (pendingCancellation != null && task instanceof CleanupAwareTask<?>) {
                    // Fatal cleanup error or unavailable cleanup carrier:
                    // cancellation cannot serve as a successful release proof.
                    completion.failDeferredCleanupFromRuntime(failure.failure());
                } else {
                    completion.failFromRuntime(failure.failure());
                }
            }
        }

        private void failBeforeStart(Throwable failure) {
            failTerminal(failure);
        }

        private boolean dominatesSourceCleanupCancellation(Throwable failure) {
            // Fatal panic/host failure in a cleanup frame must not be hidden
            // behind cancellation. The source compiler's typed effect system
            // will strengthen this staging classifier.
            Throwable cause = OresFuture.unwrap(failure);
            return cause instanceof Error
                    || cause instanceof UntrappableTaskFailure;
        }

        private void requestCleanupCancellation() {
            java.util.concurrent.CancellationException cause =
                    completion.deferredCancellationCause();
            if (cause == null || cleanupCancellationInjected.get()) return;
            detachActiveAwaitRegistration();
            int phaseNow = phase.get();
            if (phaseNow == TERMINAL) return;
            if (phaseNow == NEW
                    || (phaseNow == QUEUED
                            && pendingResume.get() != null
                            && pendingResume.get().initial())) {
                // A cancellation before initial execution has no guest scope.
                failTerminal(cause);
                return;
            }
            if (phaseNow == RUNNING) return; // physical turn must unwind first
            pendingResume.set(Resume.completed(null, cause));
            if (phaseNow == WAITING) scheduleReadyResume();
            // QUEUED already owns its execution slot. No duplicate enqueue.
        }

        private void cancelFromFuture() {
            if (task instanceof CleanupAwareTask<?>) {
                requestCleanupCancellation();
                return;
            }
            detachActiveAwaitRegistration();
            int observed;
            do {
                observed = phase.get();
                if (observed == TERMINAL) return;
            } while (!phase.compareAndSet(observed, TERMINAL));
            pendingResume.set(null);
            retireWhenQuiescent();
        }

        private void revokeFromSchedulerClose() {
            // Scheduler CLOSE is not a runnable cleanup lane. A queued or
            // suspended guest cleanup cannot truthfully be reported as done.
            // Fail closed; retire physical resources only once a running
            // carrier has actually exited. Do NOT publish fake cancellation
            // while lexical guards may still remain.
            if (task instanceof CleanupAwareTask<?>) {
                if (phase.get() == TERMINAL) return;
                completion.cancel(false);
                // Cancellation BEFORE initial guest execution has no lexical
                // frames; failTerminal() inside requestCleanupCancellation
                // already handled that case without a quarantine.
                if (phase.get() != TERMINAL) {
                    cleanupIncomplete.set(true);
                    failTerminal(new CleanupIncompleteError(
                            "scheduler closed before source Task unwind completed"));
                }
            } else {
                cancelFromFuture();
            }
        }

        private void publishCancelledFromSchedulerClose() {
            if (task instanceof CleanupAwareTask<?>) {
                publishTerminalIfReady();
                return;
            }
            // Ordinary tasks may publish immediate cancellation after their
            // await revokers have been detached.
            completion.cancel(false);
        }
    }

    private sealed interface TerminalOutcome<T>
            permits TerminalSuccess, TerminalFailure { }

    private record TerminalSuccess<T>(T value) implements TerminalOutcome<T> { }

    private record TerminalFailure<T>(Throwable failure)
            implements TerminalOutcome<T> {
        private TerminalFailure {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
