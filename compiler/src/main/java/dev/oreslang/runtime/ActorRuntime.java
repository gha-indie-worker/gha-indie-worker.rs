package dev.oreslang.runtime;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Host-side actor substrate used by the first interpreter.
 *
 * Oreslang follows the core Akka-style execution invariant: actors are
 * multiplexed over dispatcher threads, but one actor processes its mailbox
 * serially. Carrier-thread identity is never actor identity.
 *
 * PRIVATE and SHARED actors are deliberately bulkheaded onto different
 * dispatchers. Private actors also receive a confined logical memory slice;
 * shared actors may coordinate through explicitly synchronized shared cells.
 */
public final class ActorRuntime implements AutoCloseable {
    private static final int MAX_MESSAGE_GRAPH_DEPTH = 256;
    private static final int MAX_MESSAGE_GRAPH_NODES = 100_000;
    private static final int MAX_ACTOR_GROUPS_PER_ACTOR = 64;
    private static final int MAX_ACTOR_GROUP_MEMBERSHIPS_PER_ACTOR = 64;
    private static final int MAX_EVENT_TOPICS_PER_GROUP = 1_024;
    private static final long CLOSE_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long MAILMAN_MAX_BATCH_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
    private static final int INTERNAL_CONTINUATION_SLOTS = 1_024;
    /** A single UNTRUSTED actor cannot monopolize all global timed slots. */
    private static final int UNTRUSTED_TIMED_SOURCE_ADMISSION_SLOTS = 32;
    private static final long UNTRUSTED_FUEL_PER_MILLI = 256L;
    private static final long UNTRUSTED_MIN_FUEL = 256L;
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<Boolean> ACTOR_CARRIER = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<ActorExecutionContext> CURRENT_ACTOR_EXECUTION = new ThreadLocal<>();
    /** Current actor-owned source task's logical quota, never a carrier's quota. */
    private static final ThreadLocal<UntrustedLogicalQuota> CURRENT_UNTRUSTED_QUOTA =
            new ThreadLocal<>();
    private static final ThreadLocal<ActorGroupMailmanExecution> CURRENT_ACTOR_GROUP_MAILMAN =
            new ThreadLocal<>();
    /**
     * Narrow internal escape hatch used only by ActorGroupContext.send().
     * A Mailman callback otherwise has no ambient ActorRuntime authority even
     * though it executes on a CONTROL carrier.
     */
    private static final ThreadLocal<Boolean> ACTOR_GROUP_MAILMAN_CONTEXT_SEND =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private record ActorGroupMailmanExecution(
            ActorRuntime runtime,
            ActorGroupId groupId) { }

    private record ActorExecutionContext(
            ActorRuntime runtime,
            ActorId actorId,
            ActorKind kind,
            IsolatePolicy policy,
            Object executionDomain) { }

    /**
     * Executes the actor turn synchronously on the calling scheduler worker.
     * The callback must return before execute returns; off-thread submission
     * or post-return execution is forbidden. ActorRuntime checks the physical
     * execution owner at turn entry/exit and rejects violations.
     */
    @FunctionalInterface
    public interface TurnExecutor {
        void execute(Runnable turn);

        static TurnExecutor direct() {
            return Runnable::run;
        }
    }

    /**
     * Admission boundary for runtime CONTROL work such as ActorGroup Mailmen.
     *
     * Implementations must enqueue onto a VM-owned CONTROL scheduler/pool; they
     * must not create a scheduler or carrier pool per ActorGroup.
     */
    @FunctionalInterface
    public interface ControlDispatcher {
        OresFuture<Void> execute(Runnable turn);

        static ControlDispatcher unavailable() {
            return turn -> {
                throw new RejectedExecutionException(
                        "ActorGroup Mailman CONTROL dispatcher is not configured");
            };
        }
    }

    /**
     * Host-injected VM-owned executor for potentially blocking physical child
     * STOP work. Submission must be bounded, prompt, and must not run the task
     * inline or on the zero-thread CONTROL coordinator. Rejection throws.
     *
     * A successfully submitted task must eventually execute once; runtime
     * admission and physical retirement remain separately accounted for.
     * Standalone runtimes without this capability retain synchronous STOP.
     */
    @FunctionalInterface
    public interface PhysicalStopDispatcher {
        /**
         * The executor must call neverStarted exactly once for a task accepted
         * but later removed without ever starting (e.g. shutdownNow). This is
         * a FAILURE receipt, never physical successful retirement.
         *
         * Rejected admission throws synchronously. A started task reports its
         * physical completion through the stopTask's own finally block.
         */
        void execute(Runnable stopTask, Consumer<Throwable> neverStarted);
    }

    public static boolean isActorCarrierThread() {
        return Boolean.TRUE.equals(ACTOR_CARRIER.get());
    }

    public static boolean inActorExecution() {
        return CURRENT_ACTOR_EXECUTION.get() != null;
    }

    public static boolean inActorGroupMailmanExecution() {
        return CURRENT_ACTOR_GROUP_MAILMAN.get() != null;
    }

    public static IsolatePolicy currentActorPolicy() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? null : current.policy();
    }

    public static ActorRuntime currentActorRuntime() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? null : current.runtime();
    }

    public static ActorKind currentActorKind() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? null : current.kind();
    }

    public static Optional<ActorId> currentActorId() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? Optional.empty() : Optional.of(current.actorId());
    }

    public static Object currentExecutionDomain() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? Thread.currentThread() : current.executionDomain();
    }

    /**
     * Opaque identity for the currently executing physical actor dispatch.
     * Distinct simultaneous carriers in one actor domain MUST have distinct
     * segment identities. Unlike {@link #currentExecutionDomain()}, this is
     * never an actor-wide ownership key. Host callers use their Thread.
     *
     * <p>Use identity comparison, not equals(): ActorExecutionContext is a
     * record whose value equality is intentionally not a lease identity.</p>
     */
    static Object currentMutexExecutionSegment() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? Thread.currentThread() : current;
    }

    /**
     * Stable actor execution domain for actor-local runtime services, or null
     * when the caller is not currently inside an actor mailbox turn.
     */
    public static Object currentActorExecutionDomain() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? null : current.executionDomain();
    }

    public enum ActorKind { PRIVATE, SHARED, UNTRUSTED }

    /**
     * Requested physical heap placement is a requirement, not an assertion of
     * physical isolation. VM_ISOLATE needs a verified external Graal supervisor.
     */
    public enum ActorHeapPlacement { ACTOR_ARENA, VM_ISOLATE, PROCESS_ISOLATE }

    public static ActorHeapPlacement defaultHeapPlacement(ActorKind kind) {
        return Objects.requireNonNull(kind, "kind") == ActorKind.UNTRUSTED
                ? ActorHeapPlacement.VM_ISOLATE : ActorHeapPlacement.ACTOR_ARENA;
    }


    /** Host-visible notice; it does not grant permission to interrupt a carrier. */
    public record WatchdogNotice(
            ActorId actorId,
            ActorKind kind,
            long elapsedNanos,
            long sinceCheckpointNanos) { }

    public static final class UntrustedActorQuotaExceededException extends SecurityException {
        public enum Resource { FUEL, WALL_TIME, CPU_TIME }
        private final Resource resource;
        private UntrustedActorQuotaExceededException(Resource resource, String message) {
            super(message);
            this.resource = resource;
        }
        public Resource resource() { return resource; }
    }

    private static boolean isPrivateKind(ActorKind kind) {
        return kind == ActorKind.PRIVATE || kind == ActorKind.UNTRUSTED;
    }

    /**
     * Structured cancellation requests actor shutdown and cascades through the
     * actor's child tree. FORCE_ISOLATED additionally requires a host-owned
     * isolation revoker that can make non-cooperating guest execution
     * impossible before the call returns.
     */
    public enum CancellationMode { STRUCTURED, FORCE_ISOLATED }

    public record IsolationTarget(
            ActorId actorId,
            Object executionDomain) {
        public IsolationTarget {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(executionDomain, "executionDomain");
        }
    }

    public record ForceCancellationRequest(
            ActorId rootActorId,
            List<IsolationTarget> targets) {
        public ForceCancellationRequest {
            Objects.requireNonNull(rootActorId, "rootActorId");
            targets = List.copyOf(
                    Objects.requireNonNull(targets, "targets"));
            if (targets.isEmpty()) {
                throw new IllegalArgumentException(
                        "force-cancellation target set cannot be empty");
            }
        }
    }

    @FunctionalInterface
    public interface ForceCancellationHook {
        /**
         * Returning true means every target was atomically revoked and is no
         * longer capable of executing guest code. Returning false or throwing
         * means no logical force-cancellation is published.
         */
        boolean revoke(ForceCancellationRequest request);
    }

    private final class ForceCancellationAttempt {
        private final ActorCell<?> root;
        private final List<ActorCell<?>> cells;
        private final ForceCancellationRequest request;

        private ForceCancellationAttempt(
                ActorCell<?> root,
                List<ActorCell<?>> cells,
                ForceCancellationRequest request) {
            this.root = root;
            this.cells = List.copyOf(cells);
            this.request = request;
        }
    }


    public static final class ActorCancelledException extends CancellationException {
        private final ActorId actorId;

        private ActorCancelledException(ActorId actorId, String message) {
            super(message);
            this.actorId = actorId;
        }

        public ActorId actorId() { return actorId; }
    }

    /**
     * Internal control-plane unwind used at actor scheduler safepoints.
     * It is an Error intentionally: Oreslang source catch handles ordinary
     * runtime failures, but cancellation must not be absorbable by guest code.
     * Evaluator finally/defer unwinding still runs before the actor turn exits.
     */
    public static final class ActorCancellationSignal extends Error {
        private ActorCancellationSignal(String message) {
            super(message, null, false, false);
        }
    }

    public enum CarrierBackend { NATIVE_PTHREAD, JVM_THREAD_POOL }

    private static final String CARRIER_BACKEND_PROPERTY = "ores.runtime.carriers";

    public record DispatcherConfig(
            int privateParallelism,
            int sharedParallelism,
            int throughput,
            int maxActors) {
        public DispatcherConfig {
            if (privateParallelism <= 0) throw new IllegalArgumentException("privateParallelism must be > 0");
            if (sharedParallelism <= 0) throw new IllegalArgumentException("sharedParallelism must be > 0");
            if (throughput <= 0) throw new IllegalArgumentException("throughput must be > 0");
            if (maxActors <= 0) throw new IllegalArgumentException("maxActors must be > 0");
        }

        public DispatcherConfig(int privateParallelism, int sharedParallelism, int throughput) {
            this(privateParallelism, sharedParallelism, throughput, 16_384);
        }

        public static DispatcherConfig defaults() {
            // Account for process CPU quotas once at VM construction, not on
            // every actor turn. Independently sized full-core pools multiply
            // runnable pthread carriers and can thrash cpuset-limited hosts.
            CpuCarrierBudget.PoolSizes pools =
                    CpuCarrierBudget.defaults(CpuCarrierBudget.effectiveProcessors());
            int shared = Integer.getInteger(
                    "ores.runtime.actor.shared.parallelism", pools.sharedWorkers());
            return new DispatcherConfig(pools.privateWorkers(), shared, 64, 16_384);
        }
    }

    public record ActorId(UUID value) {
        public ActorId { Objects.requireNonNull(value); }
        public static ActorId create() { return new ActorId(UUID.randomUUID()); }
    }

    public record ActorGroupId(UUID value) {
        public ActorGroupId { Objects.requireNonNull(value); }
        public static ActorGroupId create() { return new ActorGroupId(UUID.randomUUID()); }
    }

    /** Stable identity, not a bearer capability or guest supervisor authority. */
    public record SupervisorId(UUID value) {
        public SupervisorId { Objects.requireNonNull(value); }
        public static SupervisorId create() { return new SupervisorId(UUID.randomUUID()); }
    }

    /** Stable machine-readable causes for synchronous mailbox admission failure. */
    public interface SendError {
        enum Reason {
            TERMINATED, MAILBOX_FULL, INVALID_REFERENCE, SEALED,
            RESOURCE_EXHAUSTED, TRANSPORT_UNAVAILABLE
        }
        Reason reason();
    }

    public static final class ActorMailboxFullException
            extends IllegalStateException implements SendError {
        public ActorMailboxFullException(ActorId id) {
            super("actor mailbox limit exceeded for " + id);
        }
        @Override public Reason reason() { return Reason.MAILBOX_FULL; }
    }

    public static final class ActorSealedException
            extends IllegalStateException implements SendError {
        public ActorSealedException(ActorId id) {
            super("actor " + id + " is sealed against new messages");
        }
        @Override public Reason reason() { return Reason.SEALED; }
    }

    /** A caller has exceeded the bounded number of outstanding drain observers. */
    public static final class ActorResourceExhaustedException
            extends IllegalStateException implements SendError {
        public ActorResourceExhaustedException(ActorId id) {
            super("actor drain observer limit exceeded for " + id);
        }
        @Override public Reason reason() { return Reason.RESOURCE_EXHAUSTED; }
    }

    public static final class ActorInvalidReferenceException
            extends IllegalArgumentException implements SendError {
        public ActorInvalidReferenceException(String message) { super(message); }
        @Override public Reason reason() { return Reason.INVALID_REFERENCE; }
    }

    public static final class ActorTransportException
            extends IllegalStateException implements SendError {
        public ActorTransportException(String message, Throwable cause) {
            super(message, cause);
        }
        @Override public Reason reason() { return Reason.TRANSPORT_UNAVAILABLE; }
    }

    public static final class ActorTerminatedException
            extends IllegalStateException implements SendError {
        private final ActorId actorId;
        private final ActorKind actorKind;

        private ActorTerminatedException(ActorId actorId, ActorKind actorKind, Throwable cause) {
            super("actor " + actorId + " (" + actorKind + ") is terminated", cause);
            this.actorId = actorId;
            this.actorKind = actorKind;
        }

        public ActorId actorId() { return actorId; }
        public ActorKind actorKind() { return actorKind; }
        @Override public Reason reason() { return Reason.TERMINATED; }
    }

    /**
     * Deeply immutable runtime-owned shared value. The backing graph is frozen
     * once, quota-accounted once, and retained until runtime teardown.
     */
    public final class Shared<T> {
        private final AtomicBoolean sharedClosed = new AtomicBoolean();
        private T value;
        private long reservedBytes;

        private Shared(T value, long reservedBytes) {
            this.value = value;
            this.reservedBytes = reservedBytes;
        }

        public T value() {
            rejectPrivateActorSharedMemoryAccess("Shared.value");
            if (sharedClosed.get()) throw new IllegalStateException("Shared value belongs to a closed actor runtime");
            return value;
        }

        private boolean ownedBy(ActorRuntime runtime) {
            return ActorRuntime.this == runtime;
        }

        private void closeFromRuntime() {
            if (!sharedClosed.compareAndSet(false, true)) return;
            long bytes = reservedBytes;
            reservedBytes = 0L;
            value = null;
            releaseSharedRuntimeBytes(bytes);
            sharedValues.remove(this);
        }
    }

    private final UUID runtimeInstanceId = UUID.randomUUID();
    private final AtomicLong nextActorGeneration = new AtomicLong(1);
    private final Map<ActorId, ActorCell<?>> actors = new ConcurrentHashMap<>();
    private final Map<ActorGroupId, ActorGroup> actorGroups = new ConcurrentHashMap<>();
    private final ActorSupervisor rootSupervisor = new ActorSupervisor(SupervisorId.create(), null);
    private final Map<SupervisorId, ActorSupervisor> supervisors =
            new ConcurrentHashMap<>(Map.of(rootSupervisor.id(), rootSupervisor));
    /**
     * Reverse cooperation-membership index. All mutations to this map AND an
     * ActorGroup.members set are serialized on groupMembershipLock. This is
     * independent of ActorCell.managedByGroup (exclusive lifecycle ownership).
     * Lock order for join is groupMembershipLock -> group.authorityLock; group
     * close releases authorityLock before taking groupMembershipLock.
     */
    private final Object groupMembershipLock = new Object();
    private final Map<ActorId, Set<ActorGroupId>> groupMemberships = new java.util.HashMap<>();
    /** Actor-created groups remain structurally owned even after creator leaves. */
    private final Map<ActorId, Set<ActorGroupId>> createdGroupsByActor =
            new java.util.HashMap<>();
    private final AtomicInteger actorCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Distinct from admission sealing: dispatcher stop must still occur later. */
    private final AtomicBoolean dispatchersShutdown = new AtomicBoolean();
    private final AtomicLong privateMemoryBytes = new AtomicLong();
    private final AtomicLong sharedMemoryBytes = new AtomicLong();
    private final Object memoryBudgetLock = new Object();
    private final Object runtimeLifecycleLock = new Object();
    private final Set<SyncCell<?>> syncCells = ConcurrentHashMap.newKeySet();
    private final Set<Shared<?>> sharedValues = ConcurrentHashMap.newKeySet();
    /** Read-only parent-observable streams retained until drained/runtime close. */
    private final Set<ActorOutputStream<?>> actorOutputStreams =
            ConcurrentHashMap.newKeySet();
    private final IsolatePolicy policyCeiling;
    private final DispatcherConfig dispatcherConfig;
    private final TurnExecutor turnExecutor;
    private final ControlDispatcher controlDispatcher;
    private final PhysicalStopDispatcher physicalStopDispatcher;
    private volatile Consumer<Object> actorExitHook = ignored -> { };
    /** Physical native I/O operation/loan gate for the actor incarnation. */
    private volatile java.util.function.Predicate<ActorId> nativeIoLoanRetired =
            ignored -> true;

    /**
     * Host/isolate boundary hook. Returning true means the execution domain
     * rooted at the supplied actor (including descendants owned by that
     * isolation boundary) has been revoked strongly enough that guest code
     * cannot continue running. The default runtime has no such authority.
     */
    private volatile ForceCancellationHook forceCancellationHook =
            request -> false;

    private final ExecutorService privateDispatcher;
    private final ExecutorService sharedDispatcher;
    private final ExecutorService untrustedDispatcher;
    private final ActorPhysicalTurnGate physicalTurnGate;
    private final ThreadLocal<ActorCell<?>> currentActor = new ThreadLocal<>();
    private final AtomicLong watchdogOverruns = new AtomicLong();
    private final AtomicLong watchdogNoticesDropped = new AtomicLong();
    private final ArrayBlockingQueue<WatchdogNotice> watchdogNotices =
            new ArrayBlockingQueue<>(1024);
    private final ThreadLocal<SyncCell<?>> currentSyncCell = new ThreadLocal<>();
    private final ThreadLocal<Boolean> forceCancellationCallback = new ThreadLocal<>();

    public ActorRuntime() {
        this(IsolatePolicy.developer(), DispatcherConfig.defaults(), TurnExecutor.direct());
    }

    public ActorRuntime(IsolatePolicy policyCeiling) {
        this(policyCeiling, DispatcherConfig.defaults(), TurnExecutor.direct());
    }

    public ActorRuntime(IsolatePolicy policyCeiling, int maxActors) {
        this(
                policyCeiling,
                new DispatcherConfig(
                        DispatcherConfig.defaults().privateParallelism(),
                        DispatcherConfig.defaults().sharedParallelism(),
                        DispatcherConfig.defaults().throughput(),
                        maxActors),
                TurnExecutor.direct());
    }

    public ActorRuntime(IsolatePolicy policyCeiling, DispatcherConfig dispatcherConfig) {
        this(policyCeiling, dispatcherConfig, TurnExecutor.direct());
    }

    public ActorRuntime(
            IsolatePolicy policyCeiling,
            DispatcherConfig dispatcherConfig,
            TurnExecutor turnExecutor) {
        this(
                policyCeiling,
                dispatcherConfig,
                turnExecutor,
                ControlDispatcher.unavailable());
    }

    public ActorRuntime(
            IsolatePolicy policyCeiling,
            DispatcherConfig dispatcherConfig,
            TurnExecutor turnExecutor,
            ControlDispatcher controlDispatcher) {
        this(policyCeiling, dispatcherConfig, turnExecutor, controlDispatcher, null);
    }

    public ActorRuntime(
            IsolatePolicy policyCeiling,
            DispatcherConfig dispatcherConfig,
            TurnExecutor turnExecutor,
            ControlDispatcher controlDispatcher,
            PhysicalStopDispatcher physicalStopDispatcher) {
        // Standalone ActorRuntime callers must not bypass OresVM's strict-GC
        // admission gate: the underlying JVM and direct-buffer cleaner still GC.
        ActorHeapAdmission.requireSupportedJvmMode(
                System.getProperty("ores.runtime.gc.mode", "hybrid"));
        long poolsPerf = CorePerf.start();
        this.policyCeiling = Objects.requireNonNull(policyCeiling);
        this.dispatcherConfig = Objects.requireNonNull(dispatcherConfig);
        this.turnExecutor = Objects.requireNonNull(turnExecutor);
        this.controlDispatcher = Objects.requireNonNull(controlDispatcher);
        this.physicalStopDispatcher = physicalStopDispatcher;
        int physicalLimit = Integer.getInteger("ores.runtime.actor.max-active-turns", 0);
        if (physicalLimit < 0) {
            throw new IllegalArgumentException("ores.runtime.actor.max-active-turns must be >= 0");
        }
        this.physicalTurnGate = physicalLimit == 0
                ? null : new ActorPhysicalTurnGate(physicalLimit, dispatcherConfig.maxActors());
        ExecutorService privatePool = null;
        ExecutorService sharedPool = null;
        ExecutorService untrustedPool;
        try {
            privatePool = newActorDispatcher(dispatcherConfig.privateParallelism(),
                    dispatcherConfig.maxActors(), "ores-private-actor-dispatcher-");
            sharedPool = newActorDispatcher(dispatcherConfig.sharedParallelism(),
                    dispatcherConfig.maxActors(), "ores-shared-actor-dispatcher-");
            untrustedPool = newActorDispatcher(Math.max(1, dispatcherConfig.privateParallelism()),
                    dispatcherConfig.maxActors(), "ores-untrusted-actor-dispatcher-");
        } catch (RuntimeException | Error failure) {
            CoreLog.record(CoreLog.Level.ERROR, CoreLog.ACTOR_DISPATCHER_FAILED, 1L);
            if (privatePool != null) privatePool.shutdownNow();
            if (sharedPool != null) sharedPool.shutdownNow();
            throw failure;
        }
        this.privateDispatcher = privatePool;
        this.sharedDispatcher = sharedPool;
        this.untrustedDispatcher = untrustedPool;
        CorePerf.end(CorePerf.ACTOR_POOLS_STARTUP, poolsPerf);
        if (CoreDebug.enabled()) {
            CoreDebug.event(CoreDebug.ACTOR_POOLS_CREATED, dispatcherConfig.privateParallelism());
        }
        if (poolsPerf != 0L) {
            CorePerf.actorBackend(carrierBackend() == CarrierBackend.NATIVE_PTHREAD,
                    dispatcherConfig.privateParallelism(), dispatcherConfig.sharedParallelism());
        }
    }

    public IsolatePolicy policyCeiling() { return policyCeiling; }
    public DispatcherConfig dispatcherConfig() { return dispatcherConfig; }
    public int maxActors() { return dispatcherConfig.maxActors(); }

    /** Total overrun detections, including notices dropped from the bounded queue. */
    public long watchdogOverruns() {
        requireSupervisorContext("inspect actor watchdog overruns");
        return watchdogOverruns.get();
    }

    /** Number of watchdog notices dropped when the bounded host queue was full. */
    public long watchdogNoticesDropped() {
        requireSupervisorContext("inspect dropped actor watchdog notices");
        return watchdogNoticesDropped.get();
    }

    /** Number of recoverable watchdog monitor/observer failures in this JVM. */
    public long watchdogObserverFailures() {
        requireSupervisorContext("inspect watchdog observer failures");
        return ActorTurnWatchdog.observerFailures();
    }

    /** Host-only nonblocking drain: actor code cannot steal another actor's diagnostics. */
    public Optional<WatchdogNotice> pollWatchdogNotice() {
        requireSupervisorContext("drain actor watchdog notices");
        return Optional.ofNullable(watchdogNotices.poll());
    }

    /** Indicates a request, not a safe or completed suspension. */
    public boolean watchdogPreemptionRequested() {
        ActorCell<?> cell = currentActor.get();
        ActorTurnWatchdog.Lease lease = cell == null ? null : cell.watchdogLease;
        return lease != null && lease.preemptionRequested();
    }

    private void recordWatchdogOverrun(ActorCell<?> cell, ActorTurnWatchdog.Lease lease) {
        long now = System.nanoTime();
        watchdogOverruns.incrementAndGet();
        if (!watchdogNotices.offer(new WatchdogNotice(
                cell.ref.id(), cell.kind,
                lease.elapsedNanos(now), lease.sinceCheckpointNanos(now)))) {
            watchdogNoticesDropped.incrementAndGet();
        }
    }

    /**
     * Physical carrier implementation currently backing actor turns.
     *
     * <p>This is diagnostic/control-plane information only. Actor identity is
     * never carrier identity and source semantics do not depend on this value.</p>
     */
    public CarrierBackend carrierBackend() {
        return usesNativeCarriers(privateDispatcher)
                && usesNativeCarriers(sharedDispatcher)
                && usesNativeCarriers(untrustedDispatcher)
                ? CarrierBackend.NATIVE_PTHREAD
                : CarrierBackend.JVM_THREAD_POOL;
    }


    /**
     * A Java memory quota or direct ByteBuffer does NOT provide an independent
     * garbage collector or a separate Graal execution isolate.
     */
    public record ActorHeapStatus(
            ActorKind kind,
            boolean actorOwnedMemorySlice,
            boolean canAllocateOwnerCheckedDirectMemory,
            boolean independentOfParentContextHeap,
            boolean independentlyCollectable) {
        public ActorHeapStatus {
            Objects.requireNonNull(kind, "kind");
            if (independentlyCollectable && !independentOfParentContextHeap) {
                throw new IllegalArgumentException(
                        "independent actor GC requires a physically independent heap");
            }
        }
    }

    /**
     * Production admission cannot route a host ActorRuntime into an independent
     * VM/process. The older spawnUntrusted compatibility API remains available
     * for bootstrap testing but cannot satisfy this placement requirement.
     */
    public <M> ActorRef<M> spawnWithHeapPlacement(
            ActorKind kind,
            IsolatePolicy policy,
            ActorHeapPlacement placement,
            BehaviorFactory<M> factory) {
        requireSupervisorContext("admit actor heap placement");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(factory, "factory");
        if (placement != ActorHeapPlacement.ACTOR_ARENA) {
            throw new IllegalStateException(
                    "required " + placement + " backend is not attached to this "
                    + "ActorRuntime; route via a verified Graal isolate or process");
        }
        if (kind == ActorKind.UNTRUSTED) {
            throw new SecurityException(
                    "untrusted actors cannot use host ACTOR_ARENA placement; "
                    + "route to a verified sandboxed VM_ISOLATE or PROCESS_ISOLATE");
        }
        return spawn(kind, policy, factory);
    }

    /** Host-supervisor inspection of actual backing, never inferred from ActorKind. */
    public ActorHeapStatus heapStatusFor(ActorRef<?> ref) {
        requireSupervisorContext("inspect actor heap isolation");
        Objects.requireNonNull(ref, "ref");
        if (!ref.ownedBy(this)) {
            throw new IllegalArgumentException("ActorRef belongs to another runtime");
        }
        ActorCell<?> cell = actors.get(ref.id());
        if (cell == null) {
            throw new IllegalStateException("actor was not found or has finalized");
        }
        boolean ownerCheckedRegion = cell.memorySlice != null;
        return new ActorHeapStatus(
                cell.kind, ownerCheckedRegion, ownerCheckedRegion, false, false);
    }

    public enum ActorHeapBacking {
        OWNER_CHECKED_REGION,
        DETERMINISTIC_NATIVE_ARENA,
        INDEPENDENT_GRAAL_ISOLATE,
        ISOLATED_PROCESS
    }

    /**
     * Reject physical isolation requirements that this actor runtime cannot
     * prove. A future router must check the actual backend at admission.
     */
    public void requireActorHeapBacking(ActorRef<?> ref, ActorHeapBacking backing) {
        Objects.requireNonNull(backing, "backing");
        ActorHeapStatus status = heapStatusFor(ref);
        if (backing == ActorHeapBacking.OWNER_CHECKED_REGION) {
            if (!status.actorOwnedMemorySlice()) {
                throw new IllegalStateException("actor has no owner-checked region");
            }
            return;
        }
        throw new IllegalStateException("actor heap backing " + backing
                + " is not implemented by the bootstrap ActorRuntime: "
                + "native reclamation and physical isolate placement require "
                + "verified backend routing");
    }

    public void requireIndependentGcHeap(ActorRef<?> ref) {
        if (!heapStatusFor(ref).independentlyCollectable()) {
            throw new IllegalStateException(
                    "actor requires an independent physical GC heap; "
                    + "the current JVM ActorRuntime provides only actor-local "
                    + "ownership/quota boundaries, not an independent collector");
        }
    }

    /** Placement is not a separate-heap guarantee. CPU -1 means no hard binding. */
    public record ActorPlacement(int lane, int cpu, String affinity) { }

    public Optional<ActorPlacement> placementFor(ActorRef<?> ref) {
        requireSupervisorContext("inspect actor placement");
        Objects.requireNonNull(ref);
        if (!ref.ownedBy(this)) throw new IllegalArgumentException("ActorRef belongs to another runtime");
        ActorCell<?> cell = actors.get(ref.id());
        if (cell == null) return Optional.empty();
        if (dispatcherFor(cell.kind) instanceof ActorAffinityDispatcher lanes) {
            int cpu = lanes.cpuForLane(cell.dispatchLane);
            return Optional.of(new ActorPlacement(cell.dispatchLane, cpu, cpu < 0 ? "lane" : "cpu"));
        }
        return Optional.of(new ActorPlacement(-1, -1, "none"));
    }

    /**
     * Installs a host-owned hook invoked exactly once when an actor execution
     * domain is retired. Guest code cannot mutate this hook.
     */
    public void setActorExitHook(Consumer<Object> actorExitHook) {
        requireSupervisorContext("install actor exit hook");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        this.actorExitHook = Objects.requireNonNull(actorExitHook, "actorExitHook");
    }

    /**
     * Install before admitting actors. This gate executes BEFORE the actor
     * memory slice closes (unlike the best-effort actorExitHook).
     */
    public void setNativeIoLoanRetirementGate(
            java.util.function.Predicate<ActorId> gate) {
        requireSupervisorContext("install native I/O retirement gate");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        nativeIoLoanRetired = Objects.requireNonNull(gate);
    }

    /** Wake an already-stopping actor when its final native loan retires. */
    void onNativeIoLoanRetired(ActorId id) {
        ActorCell<?> cell = actors.get(Objects.requireNonNull(id));
        if (cell == null) return;
        synchronized (cell.lifecycleLock) {
            if (cell.stopped.get() && !cell.finalized) cell.finalizeStopLocked();
            cell.lifecycleLock.notifyAll();
        }
    }

    /**
     * Install force-cancellation authority from an outer isolate/sandbox
     * manager. Guest actor code cannot install or replace this hook.
     */
    public void setForceCancellationHook(
            BiPredicate<ActorId, Object> forceCancellationHook) {
        requireNoForceCancellationHookReentry("install force-cancellation authority");
        requireSupervisorContext("install force-cancellation authority");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        BiPredicate<ActorId, Object> leafHook =
                Objects.requireNonNull(
                        forceCancellationHook, "forceCancellationHook");
        this.forceCancellationHook = request -> {
            if (request.targets().size() != 1) return false;
            IsolationTarget target = request.targets().getFirst();
            return leafHook.test(
                    target.actorId(),
                    target.executionDomain());
        };
    }

    /**
     * Install host/VM authority that can revoke a complete structured actor
     * subtree atomically. A false result or exception leaves the subtree fenced
     * only temporarily; all fences are released and queued work may resume.
     */
    public void setForceCancellationTreeHook(
            ForceCancellationHook forceCancellationHook) {
        requireNoForceCancellationHookReentry(
                "install force-cancellation tree authority");
        requireSupervisorContext(
                "install force-cancellation tree authority");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        this.forceCancellationHook =
                Objects.requireNonNull(
                        forceCancellationHook,
                        "forceCancellationHook");
    }

    public int actorCount() { return actorCount.get(); }

    /**
     * Compiler/runtime hook for fair static-select sites.
     *
     * <p>The immutable select-site identity may live in the shared code image,
     * but its mutable fairness cursor is actor-owned. This prevents shared,
     * private, and untrusted actors executing the same AST from mutating one
     * hidden cross-actor scheduler counter.</p>
     */
    public long nextActorLocalSelectTicket(Object selectSite) {
        Objects.requireNonNull(selectSite, "selectSite");
        requireCallerRuntimeAffinity("advance actor-local select fairness");
        ActorCell<?> cell = currentActor.get();
        if (cell == null) {
            throw new IllegalStateException(
                    "actor-local select fairness is only available during an actor turn");
        }
        return cell.nextStaticSelectTicket(selectSite);
    }

    /**
     * Package-private proof hook for the explicitly whitelisted immutable code
     * image attached to one actor. The actor never receives ambient access to
     * the process code store; only this exact handle is exposed through its
     * ActorContext.
     */
    Optional<SharedCodeImageStore.CodeImage> codeImageFor(ActorRef<?> ref) {
        requireSupervisorContext("inspect actor code image");
        Objects.requireNonNull(ref, "ref");
        if (!ref.ownedBy(this)) {
            throw new IllegalArgumentException(
                    "ActorRef belongs to a different ActorRuntime");
        }
        ActorCell<?> cell = actors.get(ref.id());
        return cell == null ? Optional.empty() : Optional.ofNullable(cell.codeImage);
    }

    /** Package-private proof hook: mailbox transport is the runtime Channel<T>. */
    boolean mailboxUsesChannelTransport(ActorRef<?> ref) {
        requireSupervisorContext("inspect mailbox transport");
        Objects.requireNonNull(ref, "ref");
        if (!ref.ownedBy(this)) {
            throw new IllegalArgumentException(
                    "ActorRef belongs to a different ActorRuntime");
        }
        ActorCell<?> cell = actors.get(ref.id());
        return cell != null
                && cell.mailbox instanceof ChannelRuntime.Channel<?>
                && cell.continuationMailbox instanceof ChannelRuntime.Channel<?>;
    }

    public long privateMemoryBytes() { return privateMemoryBytes.get(); }
    public long sharedMemoryBytes() { return sharedMemoryBytes.get(); }
    public long actorMemoryBytes() { return privateMemoryBytes.get() + sharedMemoryBytes.get(); }

    /**
     * Logical actor-confined memory slice for one private actor.
     *
     * This is independent of carrier threads. Mailbox payloads and persistent
     * actor-state allocations share one budget. The current JVM backend uses
     * accounting plus alias isolation; a native/polyglot-isolate backend can map
     * this same contract to a physically separate heap/arena.
     */
    public final class ActorMemorySlice implements AutoCloseable {
        private final ActorId owner;
        private final long limitBytes;
        private final AtomicLong usedBytes = new AtomicLong();
        private final AtomicBoolean sliceClosed = new AtomicBoolean();
        private final Set<PrivateMemoryBlock> blocks = ConcurrentHashMap.newKeySet();

        private ActorMemorySlice(ActorId owner, long limitBytes) {
            this.owner = Objects.requireNonNull(owner);
            this.limitBytes = limitBytes;
        }

        public ActorId owner() { return owner; }
        public long limitBytes() { return limitBytes; }
        public long usedBytes() { return usedBytes.get(); }
        public long remainingBytes() { return Math.max(0L, limitBytes - usedBytes.get()); }
        public boolean closed() { return sliceClosed.get(); }

        /**
         * Reserve persistent private-actor heap. Compiler/interpreter lowering
         * should retain the reservation for as long as the state allocation is
         * live and close it when that allocation dies.
         */
        public MemoryReservation reserveHeap(long bytes) {
            requireCurrentOwner();
            return reserve(bytes, "private actor heap");
        }

        /**
         * Allocate actor-confined direct memory. The raw ByteBuffer is never
         * exposed; all reads/writes verify the owning ActorId. This gives
         * compiler-lowered private actor state a genuinely unshared backing
         * region while actors remain multiplexed over carrier threads.
         */
        public PrivateMemoryBlock allocatePrivateBytes(int bytes) {
            requireCurrentOwner();
            if (bytes < 0) throw new IllegalArgumentException("private memory block size cannot be negative");
            MemoryReservation reservation = reserve(bytes, "private actor direct heap");
            try {
                PrivateMemoryBlock block = new PrivateMemoryBlock(this, reservation, bytes);
                blocks.add(block);
                return block;
            } catch (RuntimeException | Error failure) {
                reservation.close();
                throw failure;
            }
        }

        private MemoryReservation reserveMailbox(Object isolatedMessage) {
            return reserve(estimateFrozenBytes(isolatedMessage), "private actor mailbox");
        }

        private synchronized MemoryReservation reserve(long bytes, String purpose) {
            if (bytes < 0) throw new IllegalArgumentException("memory reservation cannot be negative");
            if (sliceClosed.get()) throw new IllegalStateException("private actor memory slice is closed");
            if (bytes == 0) return new MemoryReservation(this, 0);

            long current = usedBytes.get();
            long next;
            try {
                next = Math.addExact(current, bytes);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(purpose + " accounting overflow");
            }
            if (next > limitBytes) {
                throw new IllegalStateException(purpose + " limit exceeded for " + owner
                        + ": requested=" + bytes + " used=" + current + " limit=" + limitBytes);
            }

            reservePrivateRuntimeBytes(bytes, owner, purpose);
            usedBytes.set(next);
            return new MemoryReservation(this, bytes);
        }

        private void requireCurrentOwner() {
            ActorCell<?> cell = currentActor.get();
            if (cell == null || !isPrivateKind(cell.kind) || !cell.ref.id().equals(owner)) {
                throw new IllegalStateException(
                        "private actor memory slice may only be reserved by its owning actor");
            }
        }

        private synchronized void release(long bytes) {
            if (bytes == 0 || sliceClosed.get()) return;
            long remaining = usedBytes.addAndGet(-bytes);
            if (remaining < 0) {
                usedBytes.addAndGet(bytes);
                throw new IllegalStateException("private actor memory accounting underflow for " + owner);
            }
            try {
                releasePrivateRuntimeBytes(bytes, owner);
            } catch (RuntimeException failure) {
                usedBytes.addAndGet(bytes);
                throw failure;
            }
        }

        @Override
        public synchronized void close() {
            if (!sliceClosed.compareAndSet(false, true)) return;
            for (PrivateMemoryBlock block : List.copyOf(blocks)) block.invalidateFromSlice();
            blocks.clear();
            long bytes = usedBytes.getAndSet(0);
            if (bytes != 0) releasePrivateRuntimeBytes(bytes, owner);
        }

        private void unregister(PrivateMemoryBlock block) {
            blocks.remove(block);
        }
    }

    /**
     * Owner-checked direct memory owned by exactly one private actor.
     *
     * No mutable buffer reference escapes this wrapper. Closing the block or
     * terminating the actor overwrites the entire region before invalidation.
     */
    public final class PrivateMemoryBlock implements AutoCloseable {
        private final ActorMemorySlice slice;
        private final MemoryReservation reservation;
        private final int capacity;
        private volatile ByteBuffer memory;
        private final AtomicBoolean blockClosed = new AtomicBoolean();

        private PrivateMemoryBlock(
                ActorMemorySlice slice,
                MemoryReservation reservation,
                int bytes) {
            this.slice = Objects.requireNonNull(slice);
            this.reservation = Objects.requireNonNull(reservation);
            this.capacity = bytes;
            this.memory = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }

        public int capacity() { return capacity; }
        public ActorId owner() { return slice.owner(); }
        public boolean closed() { return blockClosed.get(); }

        public byte readByte(int index) {
            return openMemory().get(index);
        }

        public void writeByte(int index, byte value) {
            openMemory().put(index, value);
        }

        public int readInt(int index) {
            return openMemory().getInt(index);
        }

        public void writeInt(int index, int value) {
            openMemory().putInt(index, value);
        }

        public long readLong(int index) {
            return openMemory().getLong(index);
        }

        public void writeLong(int index, long value) {
            openMemory().putLong(index, value);
        }

        public double readDouble(int index) {
            return openMemory().getDouble(index);
        }

        public void writeDouble(int index, double value) {
            openMemory().putDouble(index, value);
        }

        public byte[] copyOut() {
            ByteBuffer live = openMemory();
            byte[] out = new byte[capacity];
            ByteBuffer duplicate = live.duplicate();
            duplicate.clear();
            duplicate.get(out);
            return out;
        }

        public void copyIn(byte[] bytes) {
            Objects.requireNonNull(bytes);
            ByteBuffer live = openMemory();
            if (bytes.length != capacity) {
                throw new IllegalArgumentException(
                        "private memory copy size mismatch: expected " + capacity
                                + " bytes but got " + bytes.length);
            }
            ByteBuffer duplicate = live.duplicate();
            duplicate.clear();
            duplicate.put(bytes);
        }

        private ByteBuffer openMemory() {
            if (blockClosed.get() || slice.closed()) {
                throw new IllegalStateException("private actor memory block is closed");
            }
            slice.requireCurrentOwner();
            ByteBuffer live = memory;
            if (live == null) throw new IllegalStateException("private actor memory block is closed");
            return live;
        }

        private void zeroAndDetachMemory() {
            ByteBuffer live = memory;
            if (live == null) return;
            ByteBuffer duplicate = live.duplicate();
            duplicate.clear();
            while (duplicate.hasRemaining()) duplicate.put((byte) 0);
            memory = null;
        }

        private void invalidateFromSlice() {
            if (!blockClosed.compareAndSet(false, true)) return;
            zeroAndDetachMemory();
        }

        @Override
        public void close() {
            openMemory(); // owner + liveness check before invalidation
            if (!blockClosed.compareAndSet(false, true)) return;
            zeroAndDetachMemory();
            slice.unregister(this);
            reservation.close();
        }
    }

    public final class MemoryReservation implements AutoCloseable {
        private final ActorMemorySlice slice;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private MemoryReservation(ActorMemorySlice slice, long bytes) {
            this.slice = Objects.requireNonNull(slice);
            this.bytes = bytes;
        }

        public ActorId owner() { return slice.owner(); }
        public long bytes() { return bytes; }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) slice.release(bytes);
        }
    }

    /**
     * Host-only shard reservation tied to one actor's physical lifetime.
     * PRIVATE backing is the existing owner-checked direct memory block.
     * SHARED backing is an ordinary Java array charged to the aggregate
     * runtime heap limit. No raw direct buffer or cross-actor permit escapes.
     */
    final class ActorShardLease implements AutoCloseable {
        private final ActorCell<?> cell;
        private final PrivateMemoryBlock privateBlock;
        private final long sharedBytes;
        private final AtomicBoolean revoked = new AtomicBoolean();
        private ActorShardBacking attachedShard;

        private ActorShardLease(ActorCell<?> cell, PrivateMemoryBlock block, long bytes) {
            this.cell = cell;
            this.privateBlock = block;
            this.sharedBytes = block == null ? bytes : 0L;
        }
        boolean privateBacking() { return privateBlock != null; }
        void attach(ActorShardBacking shard) {
            synchronized (cell.lifecycleLock) {
                requireOwner();
                if (attachedShard != null) {
                    throw new IllegalStateException("actor shard already attached");
                }
                attachedShard = Objects.requireNonNull(shard, "shard");
            }
        }
        boolean ownedBy(ActorRuntime runtime, ActorId actor) {
            return runtime == ActorRuntime.this && cell.ref.id().equals(actor);
        }
        void requireOwner() {
            if (currentActor.get() != cell) {
                throw new SecurityException("shard accessible only during its owning actor turn");
            }
            if (revoked.get() || cell.finalized) {
                throw new IllegalStateException("shard region has been revoked");
            }
        }
        double read(int element) {
            requireOwner();
            return privateBlock.readDouble(Math.multiplyExact(element, Double.BYTES));
        }
        void write(int element, double value) {
            requireOwner();
            privateBlock.writeDouble(Math.multiplyExact(element, Double.BYTES), value);
        }
        int readInt(int element) {
            requireOwner();
            return privateBlock.readInt(Math.multiplyExact(element, Integer.BYTES));
        }
        void writeInt(int element, int value) {
            requireOwner();
            privateBlock.writeInt(Math.multiplyExact(element, Integer.BYTES), value);
        }
        private void release(boolean ownerTurn) {
            synchronized (cell.lifecycleLock) {
                // Close/stop, backing detachment, and byte refund must have
                // one lifecycle linearization point. In particular, finalizer
                // and close must not both believe they own the same quota.
                if (!revoked.compareAndSet(false, true)) return;
                ActorShardBacking shard = attachedShard;
                attachedShard = null;
                if (shard != null) shard.revokeBackingFromLease();
                cell.shardLeases.remove(this);
                final long charged = privateBlock != null
                        ? privateBlock.capacity() : sharedBytes;
                if (cell.shardReservedBytes < charged) {
                    throw new IllegalStateException("actor shard reservation underflow");
                }
                if (privateBlock != null) {
                    // On finalization, ActorMemorySlice.close() revokes all
                    // remaining blocks while still holding lifecycleLock.
                    if (ownerTurn) privateBlock.close();
                } else {
                    // Only release the shared quota after its backing array
                    // has been scrubbed and detached from the shard.
                    releaseSharedRuntimeBytes(sharedBytes);
                }
                cell.shardReservedBytes -= charged;
            }
        }
        @Override public void close() {
            if (currentActor.get() != cell) {
                throw new SecurityException("shard close requires its owning actor turn");
            }
            release(true); // idempotent; no second budget decrement
        }
        private void revokeFromActorStop() { release(false); }
    }

    ActorShardLease reserveActorShard(int bytes) {
        if (bytes < 1) throw new IllegalArgumentException("invalid shard byte count");
        ActorCell<?> cell = currentActor.get();
        if (cell == null || cell.kind == ActorKind.UNTRUSTED) {
            throw new SecurityException("actor shard allocation requires a trusted actor turn");
        }
        synchronized (cell.lifecycleLock) {
            if (cell.stopped.get() || cell.finalized || closed.get()) {
                throw terminated(cell.ref);
            }
            long total;
            try {
                total = Math.addExact(cell.shardReservedBytes, bytes);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException("actor shard reservation overflow", overflow);
            }
            if (total > cell.policy.maxHeapBytes()) {
                throw new IllegalStateException("per-actor shard heap limit exceeded");
            }
            PrivateMemoryBlock block = null;
            if (cell.memorySlice != null) {
                block = cell.memorySlice.allocatePrivateBytes(bytes);
            } else {
                reserveSharedRuntimeBytes(bytes, "shared actor shard");
            }
            ActorShardLease lease = new ActorShardLease(cell, block, bytes);
            cell.shardReservedBytes = total;
            cell.shardLeases.add(lease);
            return lease;
        }
    }

    /**
     * Opaque, detached snapshot backed by a JVM array whose full byte length
     * remains charged to its originating actor AND this runtime until closed
     * or the actor's physical finalization. Access never exposes the array.
     */
    final class ActorShardSnapshotLease implements AutoCloseable {
        private final ActorCell<?> cell;
        // Cleared and detached under lifecycleLock on revocation so a
        // retained lease handle does not pin the JVM array after refund.
        private double[] values;
        private final long bytes;
        private boolean revoked; // guarded by cell.lifecycleLock

        private ActorShardSnapshotLease(ActorCell<?> cell, double[] values, long bytes) {
            this.cell = cell;
            this.values = values;
            this.bytes = bytes;
        }

        private void checkCaller() {
            ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
            if (current != null
                    && (current.runtime() != ActorRuntime.this
                            || !current.actorId().equals(cell.ref.id()))) {
                throw new SecurityException("foreign actor cannot access shard snapshot");
            }
        }

        int length() {
            synchronized (cell.lifecycleLock) {
                checkCaller();
                if (revoked || cell.finalized) throw new IllegalStateException("shard snapshot revoked");
                return values.length;
            }
        }

        double read(int index) {
            synchronized (cell.lifecycleLock) {
                checkCaller();
                if (revoked || cell.finalized) {
                    throw new IllegalStateException("shard snapshot revoked");
                }
                return values[index];
            }
        }

        void copyInto(double[] destination, int offset) {
            Objects.requireNonNull(destination, "destination");
            synchronized (cell.lifecycleLock) {
                checkCaller();
                if (revoked || cell.finalized) {
                    throw new IllegalStateException("shard snapshot revoked");
                }
                if (offset < 0 || offset > destination.length
                        || values.length > destination.length - offset) {
                    throw new IndexOutOfBoundsException("snapshot destination span");
                }
                System.arraycopy(values, 0, destination, offset, values.length);
            }
        }

        void writeFromOwner(int index, double value) {
            synchronized (cell.lifecycleLock) {
                if (currentActor.get() != cell || revoked || cell.finalized) {
                    throw new SecurityException("snapshot initialization requires owner turn");
                }
                values[index] = value;
            }
        }

        private void revoke() {
            synchronized (cell.lifecycleLock) {
                if (revoked) return;
                revoked = true;
                double[] old = values;
                java.util.Arrays.fill(old, 0.0);
                values = null; // no reachable mutable buffer retained by this lease
                cell.shardSnapshotLeases.remove(this);
                if (cell.shardReservedBytes < bytes) {
                    throw new IllegalStateException("snapshot accounting underflow");
                }
                cell.shardReservedBytes -= bytes;
                releaseSharedRuntimeBytes(bytes);
            }
        }

        @Override public void close() {
            checkCaller();
            revoke();
        }
        private void revokeFromActorStop() { revoke(); }
    }

    ActorShardSnapshotLease reserveActorShardSnapshot(int rows) {
        if (rows <= 0) throw new IllegalArgumentException("snapshot must be nonempty");
        final long bytes = Math.multiplyExact((long) rows, Double.BYTES);
        ActorCell<?> cell = currentActor.get();
        if (cell == null || cell.kind == ActorKind.UNTRUSTED) {
            throw new SecurityException("snapshot creation requires trusted owning actor");
        }
        synchronized (cell.lifecycleLock) {
            if (cell.stopped.get() || cell.finalized || closed.get()) {
                throw terminated(cell.ref);
            }
            final long total = Math.addExact(cell.shardReservedBytes, bytes);
            if (total > cell.policy.maxHeapBytes()) {
                throw new IllegalStateException("actor snapshot quota exceeded");
            }
            reserveSharedRuntimeBytes(bytes, "actor column snapshot");
            final double[] contents;
            try {
                contents = new double[rows];
            } catch (RuntimeException | Error failure) {
                releaseSharedRuntimeBytes(bytes);
                throw failure;
            }
            ActorShardSnapshotLease lease = new ActorShardSnapshotLease(cell, contents, bytes);
            cell.shardReservedBytes = total;
            cell.shardSnapshotLeases.add(lease);
            return lease;
        }
    }

    private record MessageEnvelope(
            Object value,
            Runnable release,
            Runnable continuation,
            ActorId sender,
            long sequence) implements AutoCloseable {
        private static MessageEnvelope message(
                Object value,
                Runnable release,
                ActorId sender,
                long sequence) {
            if (sequence < 0) {
                throw new IllegalArgumentException("actor mailbox sequence cannot be negative");
            }
            return new MessageEnvelope(value, release, null, sender, sequence);
        }

        private static MessageEnvelope continuation(Runnable continuation) {
            return new MessageEnvelope(
                    null,
                    null,
                    Objects.requireNonNull(continuation, "continuation"),
                    null,
                    -1L);
        }

        private boolean isContinuation() {
            return continuation != null;
        }

        @Override
        public void close() {
            if (release != null) release.run();
        }
    }

    /**
     * Explicit synchronized shared-memory cell.
     *
     * Actor fields do not use this: a mailbox turn already provides exclusive
     * mutation of actor-owned state. SyncCell is for state intentionally shared
     * by multiple SHARED actors.
     */
    public final class SyncCell<T> implements AutoCloseable {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final AtomicBoolean cellClosed = new AtomicBoolean();
        private T value;
        private long reservedBytes;

        @SuppressWarnings("unchecked")
        private SyncCell(T initialValue) {
            Object frozen = freeze(initialValue);
            rejectSharedMutableHandles(frozen, new IdentityHashMap<>(), 0);
            long bytes = estimateFrozenBytes(frozen);
            reserveSharedRuntimeBytes(bytes, "shared SyncCell");
            this.value = (T) frozen;
            this.reservedBytes = bytes;
        }

        public boolean closed() { return cellClosed.get(); }

        private boolean ownedBy(ActorRuntime runtime) {
            return ActorRuntime.this == runtime;
        }

        public T snapshot() {
            rejectPrivateActorSharedMemoryAccess("SyncCell.snapshot");
            boolean entered = enterSyncCell(this);
            lock.lock();
            try {
                requireOpen();
                return value;
            } finally {
                lock.unlock();
                exitSyncCell(entered);
            }
        }

        public <R> R read(Function<? super T, ? extends R> reader) {
            Objects.requireNonNull(reader);
            requireSharedActorTurn();
            boolean entered = enterSyncCell(this);
            lock.lock();
            try {
                requireOpen();
                Object frozen = freeze(reader.apply(value));
                rejectSharedMutableHandles(frozen, new IdentityHashMap<>(), 0);
                @SuppressWarnings("unchecked")
                R result = (R) frozen;
                return result;
            } finally {
                lock.unlock();
                exitSyncCell(entered);
            }
        }

        @SuppressWarnings("unchecked")
        public T update(UnaryOperator<T> updater) {
            Objects.requireNonNull(updater);
            requireSharedActorTurn();
            boolean entered = enterSyncCell(this);
            lock.lock();
            try {
                requireOpen();
                Object frozen = freeze(updater.apply(value));
                rejectSharedMutableHandles(frozen, new IdentityHashMap<>(), 0);
                requireOpen();
                if (closed.get()) throw new IllegalStateException("actor runtime is closed");
                long nextBytes = estimateFrozenBytes(frozen);
                long delta = nextBytes - reservedBytes;
                if (delta > 0) reserveSharedRuntimeBytes(delta, "shared SyncCell update");
                value = (T) frozen;
                if (delta < 0) releaseSharedRuntimeBytes(-delta);
                reservedBytes = nextBytes;
                return value;
            } finally {
                lock.unlock();
                exitSyncCell(entered);
            }
        }

        private void requireOpen() {
            if (cellClosed.get()) throw new IllegalStateException("SyncCell is closed");
        }

        @Override
        public void close() {
            rejectPrivateActorSharedMemoryAccess("SyncCell.close");
            boolean entered = enterSyncCell(this);
            try {
                closeFromRuntime();
            } finally {
                exitSyncCell(entered);
            }
        }

        private void closeFromRuntime() {
            lock.lock();
            try {
                if (!cellClosed.compareAndSet(false, true)) return;
                long bytes = reservedBytes;
                reservedBytes = 0L;
                value = null;
                releaseSharedRuntimeBytes(bytes);
                syncCells.remove(this);
            } finally {
                lock.unlock();
            }
        }

        private void invalidateFromRuntime() {
            cellClosed.set(true);
            syncCells.remove(this);
        }
    }

    /**
     * Immutable metadata for one admitted actor mailbox message.
     *
     * <p>The payload has already crossed the runtime transport boundary:
     * private/untrusted actors observe an isolated copy, while shared actors
     * observe a deeply frozen/sendable graph. This envelope is the runtime
     * substrate for the future source-level ActorMail<T> type and is distinct
     * from the ActorGroup Mailman ActorMail record.</p>
     */
    public record ActorInboxMail<M>(
            ActorId recipient,
            Optional<ActorId> sender,
            long sequence,
            M value) {
        public ActorInboxMail {
            Objects.requireNonNull(recipient, "recipient");
            sender = sender == null ? Optional.empty() : sender;
            Objects.requireNonNull(sender, "sender");
            if (sequence < 0) {
                throw new IllegalArgumentException("actor mailbox sequence cannot be negative");
            }
            Objects.requireNonNull(value, "actor mailbox values cannot be null; use Option<T>");
        }
    }

    /**
     * Immutable parent-facing actor output. Output ordering is FIFO per actor;
     * no global ordering is promised across different actors.
     */
    public record ActorOutput<M>(
            ActorId actorId,
            long sequence,
            M value) {
        public ActorOutput {
            Objects.requireNonNull(actorId, "actorId");
            if (sequence < 0) {
                throw new IllegalArgumentException(
                        "actor output sequence cannot be negative");
            }
            Objects.requireNonNull(
                    value,
                    "actor output values cannot be null; use Option<T>");
        }
    }

    /**
     * Read-only bounded stream for one actor's parent-observable outputs.
     *
     * <p>The raw Channel is deliberately hidden so holders of ActorRef cannot
     * forge actor outputs. Only the executing actor may publish through
     * self.send/emitCurrentActorOutput().</p>
     */
    public final class ActorOutputStream<M> {
        private final ActorId actorId;
        private final ChannelRuntime.Channel<ActorOutputEnvelope> channel;

        private ActorOutputStream(
                ActorId actorId,
                ChannelRuntime.Channel<ActorOutputEnvelope> channel) {
            this.actorId = Objects.requireNonNull(actorId, "actorId");
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        public ActorId actorId() {
            return actorId;
        }

        public boolean isDone() {
            return channel.isClosed() && channel.isEmpty();
        }

        public OresFuture<GeneratorRuntime.Step<ActorOutput<M>>> next() {
            OresFuture<ActorOutputEnvelope> read = channel.readAsync();
            OresFuture<GeneratorRuntime.Step<ActorOutput<M>>> result =
                    new OresFuture<>(() -> read.cancel(false));
            read.whenCompleteRuntime((envelope, failure) -> {
                if (failure != null) {
                    Throwable terminal = OresFuture.unwrap(failure);
                    if (terminal instanceof ChannelRuntime.ChannelClosedException
                            && terminal.getCause() == null) {
                        result.completeFromRuntime(
                                GeneratorRuntime.Step.doneStep());
                    } else if (terminal instanceof java.util.concurrent.CancellationException) {
                        result.cancel(false);
                    } else {
                        result.failFromRuntime(
                                terminal instanceof ChannelRuntime.ChannelClosedException
                                        && terminal.getCause() != null
                                                ? terminal.getCause()
                                                : terminal);
                    }
                    if (isDone()) actorOutputStreams.remove(this);
                    return;
                }

                @SuppressWarnings("unchecked")
                ActorOutput<M> output = (ActorOutput<M>) envelope.output();
                envelope.close();
                result.completeFromRuntime(
                        GeneratorRuntime.Step.yielded(output));
                if (isDone()) actorOutputStreams.remove(this);
            });
            return result;
        }

        private void close(Throwable cause) {
            channel.close(cause);
        }

        private void closeAndDrainFromRuntime() {
            channel.close();
            while (channel.drainOne(ActorOutputEnvelope::close)) {
                // release all queued transport reservations on runtime teardown
            }
            actorOutputStreams.remove(this);
        }
    }

    private static final class ActorOutputEnvelope implements AutoCloseable {
        private final ActorOutput<Object> output;
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ActorOutputEnvelope(
                ActorOutput<Object> output,
                Runnable release) {
            this.output = Objects.requireNonNull(output, "output");
            this.release = Objects.requireNonNull(release, "release");
        }

        private ActorOutput<Object> output() {
            return output;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) release.run();
        }
    }

    @FunctionalInterface
    public interface Behavior<M> {
        void onMessage(M message, ActorContext<M> context) throws Exception;
    }

    /**
     * Compiler/interpreter callback for a one-shot source-level actor callable.
     * Guest code never receives this host callback object.
     */
    @FunctionalInterface
    public interface Invocation<M, R> {
        R run(M message, ActorContext<M> context) throws Exception;
    }

    /**
     * Compiler-facing actor constructor. The actor context is available before
     * state initialization, so private actor fields can reserve/allocate in the
     * actor's confined memory slice rather than being captured from the caller.
     */
    @FunctionalInterface
    public interface BehaviorFactory<M> {
        Behavior<M> create(ActorContext<M> context) throws Exception;
    }

    /**
     * Marker for mutable guest state created on an actor's own execution
     * domain. The runtime validates ownership before admitting it as persistent
     * source-actor state.
     */
    public interface ActorOwnedGuestState {
        ActorId ownerActorId();
    }

    /**
     * Constrained compiler/interpreter bridge for source actor classes.
     *
     * <p>The executor is shared code, not actor state. It must be bound to one
     * immutable CodeImage identity. Mutable state is created only through
     * initializeActor after the ActorContext is installed, and subsequent
     * mailbox turns receive that actor-owned state explicitly.</p>
     */
    public interface ActorCodeExecutor {
        SharedCodeImageStore.CodeImage codeImage();

        ActorOwnedGuestState initializeActor(
                String actorTypeName,
                ActorContext<Object> context) throws Exception;

        void receiveActor(
                String actorTypeName,
                ActorOwnedGuestState state,
                ActorInboxMail<Object> mail,
                ActorContext<Object> context) throws Exception;

        /**
         * Once-only, actor-affine cooperative teardown before owned state is
         * released. No suspension is admitted in this first source-level slice.
         * A default no-op preserves existing host ActorCodeExecutor bridges.
         */
        default void tearDownActor(
                String actorTypeName,
                ActorOwnedGuestState state,
                ActorContext<Object> context) throws Exception {
        }
    }

    public interface ActorContext<M> {
        ActorRef<M> self();
        ActorRuntime runtime();
        IsolatePolicy policy();
        ActorKind kind();
        Optional<ActorMemorySlice> privateMemory();

        /**
         * Current admitted mailbox envelope, present only while onMessage is
         * executing. Startup/factory code has no current mail.
         */
        default Optional<ActorInboxMail<M>> currentMail() {
            return Optional.empty();
        }

        /**
         * The one immutable code image explicitly whitelisted for this actor.
         *
         * <p>This is intentionally not a store/lookup API. Untrusted and
         * private actors cannot enumerate or resolve adjacent code units; the
         * supervisor/compiler must attach the exact image at spawn time.</p>
         */
        default Optional<SharedCodeImageStore.CodeImage> codeImage() {
            return Optional.empty();
        }

        /**
         * Resolve a group the current actor has already joined. ActorGroupId is
         * an immutable scalar and is safe to retain in private actor state.
         */
        default ActorGroup actorGroup(ActorGroupId id) {
            return runtime().actorGroup(id);
        }
    }

    /**
     * Opaque runtime handle used by Future/channel completion plumbing to
     * re-enter one actor through its serialized mailbox lane.
     *
     * <p>The handle carries no guest-callable callback surface. Completion
     * threads may only enqueue a runtime continuation; they never execute guest
     * code directly.</p>
     */
    public final class ContinuationTarget {
        private final ActorId actorId;

        private ContinuationTarget(ActorId actorId) {
            this.actorId = Objects.requireNonNull(actorId, "actorId");
        }

        public ActorId actorId() {
            return actorId;
        }

        private boolean enqueue(Runnable continuation) {
            return enqueueContinuation(actorId, continuation);
        }
    }

    /**
     * Package-private runtime proof hook, not exposed to Oreslang source:
     * actor methods, continuation callbacks and lifecycle cleanup must all
     * execute with the same temporary physical owner and active turn lease.
     */
    boolean currentThreadOwnsActorExecutionLease() {
        ActorCell<?> cell = currentActor.get();
        if (cell == null) return false;
        synchronized (cell.lifecycleLock) {
            return cell.carrierActive && cell.scheduled.get()
                    && cell.carrierOwnerThread == Thread.currentThread()
                    && cell.activeTurns == 1;
        }
    }

    public ContinuationTarget captureCurrentContinuationTarget() {
        ActorCell<?> cell = currentActor.get();
        if (cell == null) {
            throw new IllegalStateException(
                    "deferred actor continuation requires an executing actor turn");
        }
        return new ContinuationTarget(cell.ref.id());
    }

    /**
     * Bind a runtime Future to the current actor lifetime. Outside an actor
     * turn the Future remains independently owned by its caller.
     */
    public <T> OresFuture<T> ownCurrentActorFuture(OresFuture<T> future) {
        Objects.requireNonNull(future, "future");
        ActorCell<?> cell = currentActor.get();
        if (cell == null) return future;

        if (!ownFuture(cell, future, true)) {
            return future;
        }
        return future;
    }


    /**
     * Gracefully terminate the actor whose source turn is currently executing.
     * This is the lowering target for actor-local `self.end()`; it carries no
     * authority to stop siblings or unrelated actors.
     */
    public void endCurrentActor() {
        requireCallerRuntimeAffinity("end current actor");
        ActorCell<?> cell = currentActor.get();
        if (cell == null) {
            throw new IllegalStateException(
                    "self.end is only valid during an actor execution turn");
        }
        cell.requestEnd(null);
    }

    /**
     * Gracefully terminate the current actor and register one final synchronous
     * cleanup action. This is the lowering target for self.endWithCleanup(...).
     * The mailbox is sealed immediately, so no later receive turn can begin.
     * Cleanup runs exactly once after the current turn body returns, while
     * actor-local state is still alive, and before outputs close or done settles.
     */
    public void endCurrentActorWithCleanup(Runnable finalCleanup) {
        Objects.requireNonNull(finalCleanup, "finalCleanup");
        requireCallerRuntimeAffinity("end current actor with cleanup");
        ActorCell<?> cell = currentActor.get();
        if (cell == null) {
            throw new IllegalStateException(
                    "self.endWithCleanup is only valid during an actor execution turn");
        }
        cell.requestEnd(finalCleanup);
    }

    /**
     * Publish one data-only value from the currently executing actor to its
     * read-only parent-observable output stream.
     *
     * <p>This is bounded and non-blocking: a full stream fails the actor turn
     * rather than parking a carrier or growing an unbounded waiter queue.</p>
     */
    public void emitCurrentActorOutput(Object value) {
        requireCallerRuntimeAffinity("emit actor output");
        ActorCell<?> cell = currentActor.get();
        if (cell == null) {
            throw new IllegalStateException(
                    "self.send is only valid during an actor execution turn");
        }
        Objects.requireNonNull(
                value,
                "actor output values cannot be null; use Option<T>");

        validateMessageGraph(value);
        // Output streams are data-only. Do not let ActorRef/shared-memory/mutex
        // capabilities escape through a surface that looks like serialized data.
        rejectDataFreezeCapabilities(
                value,
                new IdentityHashMap<>(),
                0);
        if (cell.kind == ActorKind.UNTRUSTED) {
            rejectUntrustedInboundCapabilities(
                    value,
                    new IdentityHashMap<>(),
                    0);
        }

        Object prepared = isPrivateKind(cell.kind)
                ? isolateCopy(value)
                : freezeForTransport(value);
        long bytes = estimateFrozenBytes(
                prepared,
                new IdentityHashMap<>(),
                0);
        cell.reserveOutput(bytes);

        ActorOutputEnvelope envelope = null;
        boolean admitted = false;
        try {
            synchronized (cell.lifecycleLock) {
                if (!cell.acceptsActorOutputLocked()) {
                    throw terminated(cell.ref);
                }
                if (cell.forceKillFenced) {
                    throw new IllegalStateException(
                            "actor " + cell.ref.id()
                                    + " is fenced while force cancellation is pending");
                }

                long sequence = cell.nextOutputSequence;
                long nextSequence;
                try {
                    nextSequence = Math.addExact(sequence, 1L);
                } catch (ArithmeticException exhausted) {
                    throw new IllegalStateException(
                            "actor output sequence exhausted for "
                                    + cell.ref.id(),
                            exhausted);
                }

                envelope = new ActorOutputEnvelope(
                        new ActorOutput<>(
                                cell.ref.id(),
                                sequence,
                                prepared),
                        () -> cell.releaseOutput(bytes));
                if (!cell.outputChannel.tryWrite(envelope)) {
                    throw new IllegalStateException(
                            "actor output stream limit exceeded for "
                                    + cell.ref.id());
                }
                cell.nextOutputSequence = nextSequence;
                admitted = true;
            }
        } finally {
            if (!admitted) {
                if (envelope != null) envelope.close();
                else cell.releaseOutput(bytes);
            }
        }
    }

    /**
     * Start one stackless source continuation in the current actor's logical
     * scheduler. The scheduler's executor is mailbox-backed, so every resume
     * is serialized with ordinary actor messages and uses no second concurrency
     * identity or carrier pool.
     */
    public <T> OresFuture<T> startActorTask(OresScheduler.Task<T> task) {
        Objects.requireNonNull(task, "task");
        ActorCell<?> cell = currentActor.get();
        if (cell == null) {
            throw new IllegalStateException(
                    "source actor task requires an executing actor turn");
        }
        if (cell.kind != ActorKind.UNTRUSTED) return cell.sourceScheduler().start(task);
        // Do not mint multiple independent root budgets from one physical
        // mailbox turn. While one source-task cohort is live, its nested
        // tasks share the same quota; a second top-level task fails closed.
        // Separate actor messages cannot defeat this rule while a source task
        // is still suspended.
        UntrustedLogicalQuota inheritedQuota = CURRENT_UNTRUSTED_QUOTA.get();
        synchronized (cell.lifecycleLock) {
            // Unlike the physical-cohort count, this remains charged while a
            // terminal Future subscriber is blocked AFTER guest quiescence.
            if (cell.activeUntrustedTimedAdmissions >=
                    UNTRUSTED_TIMED_SOURCE_ADMISSION_SLOTS) {
                throw new RejectedExecutionException(
                        "untrusted actor timed source-task admission is full");
            }
            if (inheritedQuota == null) {
                if (cell.activeUntrustedSourceQuota != null) {
                    throw new SecurityException(
                            "untrusted actor already has an active logical source-task quota");
                }
                long nowCpu = currentThreadCpuNanos();
                long alreadyCpu = nowCpu >= 0 && cell.turnStartedCpuNanos >= 0
                        ? Math.max(0L, nowCpu - cell.turnStartedCpuNanos) : 0L;
                inheritedQuota = new UntrustedLogicalQuota(
                        ActorRuntime.this,
                        cell.ref.id(),
                        cell.policy.maxWallTime().toNanos(),
                        cell.untrustedFuelRemaining,
                        cell.turnStartedWallNanos,
                        alreadyCpu,
                        System::nanoTime,
                        ActorRuntime::currentThreadCpuNanos);
                cell.activeUntrustedSourceQuota = inheritedQuota;
            } else if (inheritedQuota != cell.activeUntrustedSourceQuota
                    || !inheritedQuota.ownedBy(ActorRuntime.this, cell.ref.id())) {
                throw new SecurityException(
                        "untrusted source-task quota belongs to a different actor or runtime");
            }
            // Task creation is not itself a source loop backedge. Without a
            // count cap, nested spawn can retain arbitrarily many queued
            // tasks (and eventually overflow the signed reference counter)
            // before hitting a fuel safepoint.
            if (cell.activeUntrustedSourceTaskCount >= INTERNAL_CONTINUATION_SLOTS) {
                throw new RejectedExecutionException(
                        "untrusted actor logical source-task cohort is full");
            }
            cell.activeUntrustedSourceTaskCount++;
            cell.activeUntrustedTimedAdmissions++;
        }

        UntrustedLogicalQuota quota = inheritedQuota;
        AtomicBoolean physicalQuiescenceConfirmed = new AtomicBoolean();
        AtomicBoolean terminalSubscribersDrained = new AtomicBoolean();
        AtomicBoolean releasedTimedAdmission = new AtomicBoolean();
        Runnable releaseTimedAdmissionOnce = () -> {
            if (releasedTimedAdmission.compareAndSet(false, true)) {
                synchronized (cell.lifecycleLock) {
                    if (cell.activeUntrustedTimedAdmissions <= 0) {
                        throw new IllegalStateException(
                                "untrusted timed source-task admission underflow");
                    }
                    cell.activeUntrustedTimedAdmissions--;
                }
                // Last subscriber drain can happen after the final physical
                // SourceTask receipt. Recheck STOP without a nested monitor.
                cell.sourceTaskCleanupQuiesced();
            }
        };
        Runnable releaseTimedWhenSafe = () -> {
            if (physicalQuiescenceConfirmed.get()
                    && terminalSubscribersDrained.get()) {
                releaseTimedAdmissionOnce.run();
            }
        };
        AtomicBoolean released = new AtomicBoolean();
        Runnable retireOnce = () -> {
            if (released.compareAndSet(false, true)) {
                try {
                    releaseUntrustedSourceTask(cell, quota);
                } finally {
                    // A completed Future is not proof that an untrusted
                    // physical carrier has exited the actor boundary.
                    physicalQuiescenceConfirmed.set(true);
                    releaseTimedWhenSafe.run();
                }
            }
        };
        Runnable notificationsDrained = () -> {
            terminalSubscribersDrained.set(true);
            releaseTimedWhenSafe.run();
        };
        // Preserve the source's verified terminal-unwind contract through
        // budget wrappers: a plain lambda erases CleanupAwareTask and would
        // bypass defer/finally when an untrusted actor Task is cancelled.
        OresScheduler.Task<T> quotaTurn = resume -> {
                UntrustedLogicalQuota previous = CURRENT_UNTRUSTED_QUOTA.get();
                CURRENT_UNTRUSTED_QUOTA.set(quota);
                boolean entered = false;
                try {
                    quota.enter();
                    entered = true;
                    quota.check();
                    OresScheduler.Step<T> result = task.resume(resume);
                    quota.check();
                    return result;
                } finally {
                    try {
                        if (entered) quota.leave();
                    } finally {
                        if (previous == null) CURRENT_UNTRUSTED_QUOTA.remove();
                        else CURRENT_UNTRUSTED_QUOTA.set(previous);
                    }
                }
            };
        OresScheduler.Task<T> quotaChecked =
                task instanceof OresScheduler.CleanupAwareTask<?>
                        ? (OresScheduler.CleanupAwareTask<T>) quotaTurn::resume
                        : quotaTurn;
        OresFuture<T> future;
        try {
            future = cell.sourceScheduler().startWithWallDeadline(
                    quotaChecked, retireOnce, quota.remainingWallNanos(),
                    new UntrustedActorQuotaExceededException(
                            UntrustedActorQuotaExceededException.Resource.WALL_TIME,
                            "untrusted logical task exceeded wall-time budget while suspended"),
                    notificationsDrained);
        } catch (RuntimeException | Error failure) {
            // No task was admitted. Release the pre-reserved quota slot.
            // The scheduler's own retirement callback also claims this
            // once, including a failure published before start() returns.
            try {
                retireOnce.run();
            } finally {
                releaseTimedAdmissionOnce.run();
            }
            throw failure;
        }
        // A caller may cancel the Future while the source turn is still
        // executing. The scheduler invokes retireOnce ONLY after guest
        // carrier quiescence, not merely when the Future settles.
        return future;
    }

    private void releaseUntrustedSourceTask(
            ActorCell<?> cell, UntrustedLogicalQuota quota) {
        synchronized (cell.lifecycleLock) {
            if (cell.activeUntrustedSourceQuota != quota) return;
            if (cell.activeUntrustedSourceTaskCount <= 0) {
                throw new IllegalStateException(
                        "untrusted logical source-task quota retirement underflow");
            }
            if (--cell.activeUntrustedSourceTaskCount == 0) {
                cell.activeUntrustedSourceQuota = null;
            }
        }
    }

    private boolean ownFuture(
            ActorCell<?> cell,
            OresFuture<?> future,
            boolean allowSourceCleanup) {
        // Only an executing scheduler-minted cleanup continuation may create
        // bounded post-STOP resource-close Futures. Captured generic Future
        // callbacks never gain this authority, even for the same ActorId.
        boolean cleanupTurn = allowSourceCleanup
                && currentActor.get() == cell
                && OresScheduler.cancellationCleanupTurn();
        boolean accepted = false;
        boolean cleanupOwned = false;
        synchronized (cell.lifecycleLock) {
            // Re-registering the same owned Future must not cancel it or
            // charge the bounded cleanup receipts a second time.
            if (cell.pendingOperations.contains(future)) return true;
            if (!cell.finalized && !closed.get()
                    && (!cell.stopped.get()
                            || (cleanupTurn && cell.sourceStopCleanupActive))) {
                cleanupOwned = cell.stopped.get();
                if (!cleanupOwned
                        || cell.pendingCleanupFutures < INTERNAL_CONTINUATION_SLOTS) {
                    accepted = cell.pendingOperations.add(future);
                    if (accepted && cleanupOwned) cell.pendingCleanupFutures++;
                }
            }
            if (!accepted && cleanupTurn && cell.stopped.get()) {
                // Cleanup may not silently swallow a capacity or lifecycle
                // denial and proceed to publish a false clean arena receipt.
                cell.sourceStopCleanupAborted = true;
            }
        }
        if (!accepted) {
            // Cancellation callbacks can reenter actor control: always settle
            // outside lifecycleLock, including capacity-denial failures.
            if (!future.isDone()) future.cancel(false);
            return false;
        }

        final boolean cleanupReceipt = cleanupOwned;
        // Do NOT retire actor ownership from this Future's first terminal
        // waiter. Another synchronous subscriber may still hold a reference
        // into this actor's arena, even after isDone() is already visible.
        // The Future's physical notification-drain receipt includes the
        // internal revoker and ALL terminal waiters of the winning settlement.
        future.whenNotificationsDrainedRuntime(() -> {
            boolean notify = false;
            synchronized (cell.lifecycleLock) {
                Throwable physicalFailure = future.cancellationHookFailureFromRuntime();
                if (physicalFailure != null) {
                    // A thrown native/I/O revoker cannot become a successful
                    // physical loan receipt merely because cancel() completed.
                    cell.sourceStopCleanupAborted = true;
                    if (cell.stopped.get()) {
                        cell.ref.terminationCause.compareAndSet(null,
                                new OresScheduler.CleanupIncompleteError(
                                        "actor owned Future cancellation hook failed",
                                        physicalFailure));
                    }
                }
                if (cell.pendingOperations.remove(future)) {
                    if (cleanupReceipt) {
                        if (cell.pendingCleanupFutures <= 0) {
                            cell.sourceStopCleanupAborted = true;
                            cell.ref.terminationCause.compareAndSet(null,
                                    new OresScheduler.CleanupIncompleteError(
                                            "actor cleanup Future receipt underflow"));
                        } else {
                            cell.pendingCleanupFutures--;
                        }
                    }
                    notify = cell.stopped.get();
                }
            }
            if (notify) cell.sourceTaskCleanupQuiesced();
        });
        return true;
    }

    /**
     * Register scheduler plumbing only. Future completion enqueues the supplied
     * continuation back to the captured actor; the callback body itself is not
     * run on the producer/completion thread.
     */
    public <T> void enqueueOnCompletion(
            OresFuture<T> future,
            ContinuationTarget target,
            BiConsumer<? super T, ? super Throwable> continuation) {
        Objects.requireNonNull(future, "future");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(continuation, "continuation");

        ActorCell<?> cell = actors.get(target.actorId());
        if (cell == null || !ownFuture(cell, future, false)) return;

        future.whenCompleteRuntime(
                (value, failure) ->
                        target.enqueue(() -> continuation.accept(value, failure)));
    }

    /**
     * Minted solely by this actor's runtime-owned SourceTask scheduler.
     * It is separate from the public Future continuation target: after STOP,
     * only already-admitted scheduler tasks may cross the cleanup lane.
     * The cell identity check also fences any previous actor incarnation.
     */
    private boolean enqueueSourceTaskContinuation(
            ActorCell<?> cell, Runnable continuation) {
        Objects.requireNonNull(continuation, "continuation");
        if (actors.get(cell.ref.id()) != cell || closed.get()) return false;
        if (!cell.reserveContinuationSlot()) {
            cell.fail(new IllegalStateException(
                    "actor source cleanup continuation capacity exceeded for "
                            + cell.ref.id()));
            return false;
        }
        boolean admitted = false;
        try {
            synchronized (cell.lifecycleLock) {
                if (cell.finalized || cell.forceKillFenced || closed.get()
                        || actors.get(cell.ref.id()) != cell
                        || (cell.stopped.get()
                                && !cell.sourceStopCleanupPending
                                && !cell.sourceStopCleanupActive)) return false;
                if (!cell.sourceTaskMailbox.tryWrite(
                        MessageEnvelope.continuation(continuation))) {
                    throw new IllegalStateException(
                            "actor source continuation mailbox capacity invariant violated for "
                                    + cell.ref.id());
                }
                admitted = true;
                if (currentActor.get() == cell) cell.sourceHandoffRequested = true;
            }
            cell.schedule();
            return true;
        } finally {
            if (!admitted) cell.releaseMailboxSlot(true);
        }
    }

    private boolean enqueueContinuation(ActorId actorId, Runnable continuation) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(continuation, "continuation");
        ActorCell<?> cell = actors.get(actorId);
        if (cell == null || cell.stopped.get() || closed.get()) return false;
        if (!cell.reserveContinuationSlot()) {
            cell.fail(new IllegalStateException(
                    "actor internal continuation queue overflow for " + actorId));
            return false;
        }

        boolean admitted = false;
        try {
            synchronized (cell.lifecycleLock) {
                if (cell.stopped.get() || cell.finalized || closed.get()) return false;
                if (!cell.continuationMailbox.tryWrite(
                        MessageEnvelope.continuation(continuation))) {
                    throw new IllegalStateException(
                            "actor continuation mailbox capacity invariant violated for " + actorId);
                }
                admitted = true;

                // If this continuation was published by the actor envelope that
                // is executing right now, force the enclosing actor batch to
                // leave its TurnExecutor boundary before the continuation can
                // run. That makes scheduler cooperation a real carrier handoff
                // rather than a same-batch queue rotation.
                if (currentActor.get() == cell) {
                    cell.sourceHandoffRequested = true;
                }
            }
            cell.schedule();
            return true;
        } finally {
            if (!admitted) cell.releaseMailboxSlot(true);
        }
    }

    /**
     * Keep one source receive logically active across stackless suspension.
     * Until the Future settles, later user mail remains queued and only the
     * actor's bounded runtime-continuation lane may execute.
     */
    public void suspendCurrentUserMailboxUntil(OresFuture<?> future) {
        Objects.requireNonNull(future, "future");
        requireCallerRuntimeAffinity("suspend current actor mailbox turn");
        ActorCell<?> cell = currentActor.get();
        if (cell == null) {
            throw new IllegalStateException(
                    "suspended source receive requires an executing actor turn");
        }

        synchronized (cell.lifecycleLock) {
            if (cell.stopped.get() || cell.finalized) throw terminated(cell.ref);
            if (cell.sourceReceiveSuspended) {
                throw new IllegalStateException(
                        "actor " + cell.ref.id()
                                + " already has a suspended source receive");
            }
            if (cell.activeMail == null) {
                throw new IllegalStateException(
                        "only an active user-mail receive may suspend the actor mailbox");
            }
            cell.sourceReceiveSuspended = true;
        }

        future.whenCompleteRuntime((ignored, failure) -> {
            if (!enqueueContinuation(cell.ref.id(), () -> {
                long completedSequence;
                synchronized (cell.lifecycleLock) {
                    completedSequence = cell.activeMail == null
                            ? -1L : cell.activeMail.sequence();
                    cell.sourceReceiveSuspended = false;
                    cell.activeMail = null;
                    cell.lifecycleLock.notifyAll();
                }
                if (failure != null) {
                    Throwable terminal = OresFuture.unwrap(failure);
                    if (terminal instanceof java.util.concurrent.CancellationException
                            && cell.endRequested) {
                        return;
                    }
                    if (terminal instanceof RuntimeException runtime) throw runtime;
                    if (terminal instanceof Error error) throw error;
                    throw new RuntimeException(terminal);
                }
                if (completedSequence < 0) {
                    throw new IllegalStateException("source receive lost pending mail sequence");
                }
                cell.markMessageProcessed(completedSequence);
            })) {
                boolean failActor = false;
                synchronized (cell.lifecycleLock) {
                    if (!cell.stopped.get() && !cell.finalized) {
                        cell.sourceReceiveSuspended = false;
                        cell.activeMail = null;
                        failActor = true;
                    }
                }
                // A failed Future invokes registered callbacks inline. Never
                // initiate termination while an outer lifecycle monitor is
                // still held: drain observers may re-enter the runtime.
                if (failActor) {
                    cell.fail(new IllegalStateException(
                            "actor source receive completion could not re-enter its continuation lane"));
                }
            }
        });
    }

    /** An actor handle is an opaque typed local capability, never a direct actor-state pointer. */
    public record ActorAddress(UUID runtimeId, ActorId actorId, long generation,
                               ActorKind kind, String transport) {
        public ActorAddress {
            Objects.requireNonNull(runtimeId);
            Objects.requireNonNull(actorId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(transport);
            if (generation <= 0) throw new IllegalArgumentException("generation must be positive");
        }
    }

    public final class ActorRef<M> {
        private final ActorId id;
        private final ActorKind kind;
        private final long generation;
        private final AtomicReference<Throwable> terminationCause = new AtomicReference<>();
        // Observer Futures are runtime-owned: awaiting is allowed, cancelling
        // either observer cannot cancel the actor or fabricate its lifecycle.
        private final OresFuture<Void> readyFuture =
                new OresFuture<>(() -> false, () -> { });
        // Non-cancellable observer: cancelling a waiter never cancels its actor.
        // Unlike readyFuture, this projects the successfully initialized ref.
        private final OresFuture<ActorRef<M>> readyRefFuture =
                new OresFuture<>(() -> false, () -> { });
        private final OresFuture<Void> doneFuture =
                new OresFuture<>(() -> false, () -> { });
        private volatile ActorOutputStream<Object> outputStream;

        private ActorRef(ActorId id, ActorKind kind, long generation) {
            this.id = id;
            this.kind = kind;
            this.generation = generation;
            readyFuture.whenCompleteRuntime((ignored, failure) -> {
                if (failure != null) {
                    readyRefFuture.failFromRuntime(OresFuture.unwrap(failure));
                } else {
                    readyRefFuture.completeFromRuntime(this);
                }
            });
        }

        public ActorId id() { return id; }
        public ActorKind kind() { return kind; }
        public long generation() { return generation; }
        public ActorAddress address() {
            return new ActorAddress(runtimeInstanceId, id, generation, kind, "local-process");
        }
        private boolean ownedBy(ActorRuntime runtime) { return ActorRuntime.this == runtime; }
        public boolean isAlive() { return ActorRuntime.this.isAlive(this); }
        public Optional<Throwable> failure() { return Optional.ofNullable(terminationCause.get()); }

        /**
         * Actor initialization barrier. Accessing ready starts an unstarted
         * actor on its own dispatcher, even if no first message was sent.
         * Constructor/factory failures complete this Future exceptionally.
         */
        public OresFuture<Void> ready() {
            ActorCell<?> cell = actors.get(id);
            if (cell != null && !cell.stopped.get()) cell.schedule();
            return readyFuture;
        }

        /**
         * Java host accessor corresponding to Oreslang's get_ready().
         * Kept separate from await-spawn's ref-valued readiness barrier.
         */
        public OresFuture<Void> getReady() {
            return ready();
        }

        /**
         * An actor-ref-valued initialization barrier for `await spawn Actor()`.
         * Neither awaiting nor attempting to cancel this observer owns or
         * cancels the actor. Completion means on_start succeeded, not done.
         */
        public OresFuture<ActorRef<M>> readyRef() {
            ready();
            return readyRefFuture;
        }

        /**
         * Settles only after actor finalization and the TurnExecutor exit.
         * Exceptional termination is reported as a failed Future.
         */
        public OresFuture<Void> done() {
            return doneFuture;
        }

        /** Java host accessor corresponding to Oreslang's get_done(). */
        public OresFuture<Void> getDone() {
            return done();
        }

        /** Read-only outputs emitted by actor-local self.send(...). */
        public ActorOutputStream<Object> outputs() {
            ActorOutputStream<Object> stream = outputStream;
            if (stream == null) {
                throw new IllegalStateException(
                        "actor output stream has not been attached");
            }
            return stream;
        }

        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            requireBlockingHostContext(
                    "synchronously wait for actor termination");
            Objects.requireNonNull(unit);
            if (timeout < 0) throw new IllegalArgumentException("timeout must be non-negative");
            ActorCell<?> cell = actors.get(id);
            if (cell == null) return true;
            cell.awaitFinalized(unit.toNanos(timeout));
            return cell.finalized();
        }

        public void send(M message) {
            ActorRuntime.this.send(this, message);
        }

        /** Successful completion covers messages admitted before this call.
         * Processing failures, cancellation or stop-before-processing fail it.
         * If the caller is this actor and has unprocessed accepted messages,
         * the returned Future fails instead of waiting for its own execution
         * lease to complete. Use gracefulStop() for self-shutdown.
         * No carrier thread blocks awaiting the barrier. */
        public OresFuture<Void> drain() {
            return ActorRuntime.this.drain(this);
        }

        /** Seal admission and finish previously admitted messages before stopping.
         * An actor failure still fails getDone(); this is NOT a restart policy. */
        public OresFuture<Void> gracefulStop() {
            return ActorRuntime.this.gracefulStop(this);
        }

        public void stop() {
            ActorRuntime.this.stop(this);
        }

        /**
         * Structured cancellation is non-blocking: it revokes future mailbox
         * work immediately and cascades to descendants. Running trusted actor
         * code observes cancellation at the next scheduler boundary.
         */
        public boolean cancel() {
            return ActorRuntime.this.cancel(this, CancellationMode.STRUCTURED);
        }

        /**
         * Force cancellation is valid only when the host configured an
         * isolation revoker (for example, an untrusted secondary Graal/native
         * isolate). It never relies on guest cooperation.
         */
        public boolean forceCancel() {
            return ActorRuntime.this.cancel(
                    this,
                    CancellationMode.FORCE_ISOLATED);
        }

        public boolean kill() {
            return forceCancel();
        }

        public Optional<ActorId> parentId() {
            ActorCell<?> cell = actors.get(id);
            return cell == null || cell.parent == null
                    ? Optional.empty()
                    : Optional.of(cell.parent.ref.id());
        }

        public List<ActorId> childIds() {
            ActorCell<?> cell = actors.get(id);
            if (cell == null) return List.of();
            return cell.children.stream().map(child -> child.ref.id()).toList();
        }

        @Override
        public String toString() {
            return "ActorRef[" + kind + ":" + id.value() + "]";
        }
    }

    /**
     * Parent-bound structured child capability. It can send to the child, but
     * lifecycle authority is valid only in the spawning parent actor turn.
     */
    public final class ActorHandle<M> {
        private final ActorRef<M> ref;
        private final ActorId ownerActorId;

        private ActorHandle(ActorRef<M> ref, ActorId ownerActorId) {
            this.ref = Objects.requireNonNull(ref, "ref");
            this.ownerActorId = Objects.requireNonNull(ownerActorId, "ownerActorId");
        }

        public ActorRef<M> ref() { return ref; }
        public ActorId id() { return ref.id(); }
        public ActorKind kind() { return ref.kind(); }
        public boolean isAlive() { return ref.isAlive(); }
        public Optional<Throwable> failure() { return ref.failure(); }

        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return ref.awaitTermination(timeout, unit);
        }

        public void send(M message) { ref.send(message); }

        public void stop() {
            requireOwnedChildHandle(this, "stop");
            ActorRuntime.this.stop(ref);
        }

        public boolean cancel() {
            requireOwnedChildHandle(this, "cancel");
            return ActorRuntime.this.cancel(
                    ref,
                    CancellationMode.STRUCTURED);
        }

        public boolean kill() {
            requireOwnedChildHandle(this, "kill");
            return forceCancelInternal(ref);
        }

        private boolean ownedBy(ActorRuntime runtime) {
            return ActorRuntime.this == runtime;
        }
    }

    /**
     * Host/supervisor-only lifecycle capability. It intentionally exposes no
     * message-send surface.
     */
    public final class ActorControlHandle {
        private final ActorRef<?> ref;

        private ActorControlHandle(ActorRef<?> ref) {
            this.ref = Objects.requireNonNull(ref, "ref");
        }

        public ActorId id() { return ref.id(); }
        public ActorKind kind() { return ref.kind(); }
        public boolean isAlive() { return ref.isAlive(); }
        public Optional<Throwable> failure() { return ref.failure(); }

        public Optional<ActorId> parentId() {
            ActorCell<?> cell = actors.get(ref.id());
            return cell == null || cell.parent == null
                    ? Optional.empty()
                    : Optional.of(cell.parent.ref.id());
        }

        public List<ActorId> childIds() {
            ActorCell<?> cell = actors.get(ref.id());
            return cell == null
                    ? List.of()
                    : cell.children.stream()
                            .map(child -> child.ref.id())
                            .toList();
        }

        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return ref.awaitTermination(timeout, unit);
        }

        public void stop() {
            requireSupervisorContext("stop actors through a control handle");
            ActorRuntime.this.stop(ref);
        }

        public boolean cancel() {
            requireSupervisorContext("cancel actors through a control handle");
            return ActorRuntime.this.cancel(
                    ref,
                    CancellationMode.STRUCTURED);
        }

        public boolean kill() {
            requireSupervisorContext("kill actors through a control handle");
            return forceCancelInternal(ref);
        }
    }

    public ActorControlHandle controlHandle(ActorId actorId) {
        requireSupervisorContext("acquire an actor control handle");
        Objects.requireNonNull(actorId, "actorId");
        ActorCell<?> cell = actors.get(actorId);
        if (cell == null) {
            throw new IllegalArgumentException(
                    "unknown or finalized actor " + actorId);
        }
        return new ActorControlHandle(cell.ref);
    }

    public ActorControlHandle controlHandle(ActorRef<?> ref) {
        requireSupervisorContext("acquire an actor control handle");
        Objects.requireNonNull(ref, "ref");
        if (!ref.ownedBy(this)) {
            throw new IllegalArgumentException(
                    "ActorRef belongs to a different ActorRuntime");
        }
        return new ActorControlHandle(ref);
    }

    private void requireOwnedChildHandle(
            ActorHandle<?> handle,
            String operation) {
        if (!handle.ownedBy(this)) {
            throw new IllegalArgumentException(
                    "ActorHandle belongs to a different ActorRuntime");
        }
        ActorCell<?> caller = currentActor.get();
        if (caller == null || !caller.ref.id().equals(handle.ownerActorId)) {
            throw new SecurityException(
                    "ActorHandle lifecycle authority is bound to spawning parent "
                            + handle.ownerActorId + "; cannot " + operation
                            + " from this execution context");
        }
        ActorCell<?> target = actors.get(handle.ref.id());
        if (target != null && target.parent != caller) {
            throw new SecurityException(
                    "ActorHandle no longer names a direct structured child");
        }
    }

    private void requireNoForceCancellationHookReentry(String operation) {
        if (Boolean.TRUE.equals(forceCancellationCallback.get())) {
            throw new IllegalStateException(
                    "force-cancellation revoker must not re-enter ActorRuntime while attempting to "
                            + operation);
        }
    }

    /**
     * Unforgeable join authority for one ActorGroup. Capabilities may cross
     * actor mailboxes but are runtime-affine and cannot be reconstructed from a
     * group id.
     */
    public final class ActorGroupJoinCapability {
        private final ActorGroupId groupId;
        private final UUID secret;

        private ActorGroupJoinCapability(ActorGroupId groupId, UUID secret) {
            this.groupId = Objects.requireNonNull(groupId, "groupId");
            this.secret = Objects.requireNonNull(secret, "secret");
        }

        public ActorGroupId groupId() { return groupId; }

        private boolean ownedBy(ActorRuntime runtime) {
            return ActorRuntime.this == runtime;
        }

        /**
         * Resolve this capability in its owning runtime. Possessing the
         * capability does not itself grant event access until the actor joins.
         */
        public ActorGroup group() {
            requireCallerRuntimeAffinity("resolve an ActorGroup capability");
            ActorGroup group = actorGroups.get(groupId);
            if (group == null || group.closed()) {
                throw new IllegalStateException("ActorGroup is closed: " + groupId);
            }
            group.requireCapability(this);
            return group;
        }

        /**
         * Join the current actor and return the group handle for immediate use.
         */
        public ActorGroup join() {
            ActorGroup group = group();
            group.joinCurrent(this);
            return group;
        }

        @Override
        public String toString() {
            return "ActorGroupJoinCapability[" + groupId.value() + "]";
        }
    }

    /**
     * Host/runtime registration of a supervisor's advisory scope. Registration
     * alone confers no guest actor control capabilities, and membership in an
     * advised ActorGroup never confers supervision authority.
     *
     * The source-level 'define supervisor' entity and its CONTROL handlers
     * require separate checked compiler lowering. This immutable association
     * is the substrate for that work.
     */
    public final class ActorSupervisor {
        private final SupervisorId id;
        // Immutable parent prevents cycles and prevents a hot-policy upgrade
        // from silently reparenting an existing supervisory domain.
        private final ActorSupervisor parent;
        private volatile SupervisorCodeSlot<?> codeSlot;

        private ActorSupervisor(SupervisorId id, ActorSupervisor parent) {
            this.id = Objects.requireNonNull(id, "id");
            this.parent = parent;
        }

        public SupervisorId id() { return id; }

        /**
         * Associate exactly one owner CONTROL code slot with this stable
         * supervisor. Source-level guest policy loading remains compiler-gated.
         */
        public void installPolicyCode(SupervisorCodeSlot<?> slot) {
            requireSupervisorContext("install supervisor policy code");
            requireCallerRuntimeAffinity("install supervisor policy code");
            Objects.requireNonNull(slot, "slot");
            synchronized (runtimeLifecycleLock) {
                if (closed.get()) throw new IllegalStateException("actor runtime is closed");
                if (supervisors.get(id) != this)
                    throw new IllegalArgumentException("foreign supervisor runtime");
                if (codeSlot != null)
                    throw new IllegalStateException("supervisor policy code already installed");
                slot.current();
                slot.bindToSupervisor(this);
                codeSlot = slot;
            }
        }

        /**
         * Explicit host/CONTROL bridge to start a worker owned by this
         * supervisor's existing ActorGroup. The caller receives an ordinary
         * ActorRef, not a supervisor capability. Source-level start_child,
         * transactional readiness, panic dispatch and restart policy require
         * separate compiler/runtime lowering; this method does not provide them.
         */
        public <M> ActorRef<M> startChildHost(
                ActorGroup group,
                ActorKind kind,
                IsolatePolicy policy,
                BehaviorFactory<M> behaviorFactory) {
            requireSupervisorContext("start a supervised child");
            requireCallerRuntimeAffinity("start a supervised child");
            Objects.requireNonNull(group, "group");
            if (supervisors.get(id) != this) {
                throw new IllegalArgumentException("foreign supervisor runtime");
            }
            // Closing removes a group from the live registry. Check closure
            // first so a stale same-runtime group is reported as terminated,
            // not misleadingly as an unauthorized supervisor reassignment.
            if (group.closed()) {
                throw new IllegalStateException("ActorGroup is closed");
            }
            if (actorGroups.get(group.id()) != group || group.supervisor != this) {
                throw new IllegalArgumentException(
                        "ActorGroup is not directly bound to this supervisor");
            }
            // spawnWorker retains structured lifecycle authority before B can
            // become ready; close racing this call fails admission/tears down B.
            return group.spawnWorker(kind, policy, behaviorFactory);
        }

        public Optional<SupervisorCodeSlot.Descriptor> policyRevision() {
            requireSupervisorContext("inspect supervisor policy revision");
            requireCallerRuntimeAffinity("inspect supervisor policy revision");
            SupervisorCodeSlot<?> slot = codeSlot;
            return slot == null ? Optional.empty() : Optional.of(slot.current());
        }

        private void retireCodeFromRuntime() {
            SupervisorCodeSlot<?> slot = codeSlot;
            if (slot != null) slot.close();
        }

        public Optional<SupervisorId> parentId() {
            requireSupervisorContext("inspect supervisor parent");
            requireCallerRuntimeAffinity("inspect supervisor parent");
            return Optional.ofNullable(parent).map(ActorSupervisor::id);
        }

        /** Directly supervised child supervisors. */
        public Set<SupervisorId> childSupervisorIds() {
            requireSupervisorContext("inspect child supervisors");
            requireCallerRuntimeAffinity("inspect child supervisors");
            return Set.copyOf(supervisors.values().stream()
                    .filter(candidate -> candidate.parent == this)
                    .map(ActorSupervisor::id)
                    .toList());
        }

        private boolean isDescendantOf(ActorSupervisor ancestor) {
            for (ActorSupervisor cursor = this; cursor != null; cursor = cursor.parent) {
                if (cursor == ancestor) return true;
            }
            return false;
        }

        /** Read-only snapshot; closed groups disappear from the association. */
        public Set<ActorGroupId> advisedGroupIds() {
            requireSupervisorContext("inspect supervisor ActorGroups");
            requireCallerRuntimeAffinity("inspect supervisor ActorGroups");
            return Set.copyOf(actorGroups.values().stream()
                    .filter(group -> group.supervisor == this && !group.closed())
                    .map(ActorGroup::id)
                    .toList());
        }

        /** Groups directly or transitively supervised by this node. */
        public Set<ActorGroupId> subtreeGroupIds() {
            requireSupervisorContext("inspect supervisor subtree ActorGroups");
            requireCallerRuntimeAffinity("inspect supervisor subtree ActorGroups");
            return Set.copyOf(actorGroups.values().stream()
                    .filter(group -> !group.closed()
                            && group.supervisor.isDescendantOf(this))
                    .map(ActorGroup::id)
                    .toList());
        }
    }

    /** The runtime's non-null fallback supervisor for legacy group creation. */
    public ActorSupervisor rootSupervisor() {
        requireSupervisorContext("inspect the root ActorSupervisor");
        return rootSupervisor;
    }

    /** Register a supervisor immediately below the runtime root. */
    public ActorSupervisor createSupervisor() {
        return createChildSupervisor(rootSupervisor);
    }

    /**
     * Create a supervisor whose parent is another same-runtime supervisor.
     * Parentage is fixed for the lifetime of this supervisor identity.
     * A logical supervisor will eventually execute as a CONTROL-lane actor,
     * not as a dedicated thread or mandatory OS process.
     */
    public ActorSupervisor createChildSupervisor(ActorSupervisor parent) {
        requireSupervisorContext("create a child ActorSupervisor");
        requireCallerRuntimeAffinity("create a child ActorSupervisor");
        Objects.requireNonNull(parent, "parent");
        synchronized (runtimeLifecycleLock) {
            if (closed.get()) throw new IllegalStateException("actor runtime is closed");
            if (supervisors.get(parent.id()) != parent)
                throw new IllegalArgumentException(
                        "parent ActorSupervisor belongs to another ActorRuntime");
            if (supervisors.size() >= dispatcherConfig.maxActors())
                throw new IllegalStateException(
                        "ActorSupervisor runtime limit exceeded: maximum "
                                + dispatcherConfig.maxActors());
            while (true) {
                ActorSupervisor child = new ActorSupervisor(SupervisorId.create(), parent);
                if (supervisors.putIfAbsent(child.id(), child) == null)
                    return child;
            }
        }
    }

    /**
     * Cooperation domain orthogonal to the supervision tree.
     *
     * <p>An ActorGroup owns membership/capability state and one runtime event
     * bus. It does not own a dispatcher and is not itself an actor, so group
     * communication never serializes through a central mailbox.</p>
     */
    public final class ActorGroup implements AutoCloseable {
        private final ActorGroupId id;
        private final ActorId creator;
        private final ActorSupervisor supervisor;
        private final int memberLimit;
        private final int eventTopicLimit;
        private final Set<ActorId> members = ConcurrentHashMap.newKeySet();
        // Explicitly lifecycle-managed workers, distinct from cooperating
        // members: membership never grants cancellation authority.
        private final Map<ActorId, ActorRef<?>> managedWorkers = new ConcurrentHashMap<>();
        // Guarded by authorityLock: a stop-admitted actor cannot be released
        // into a different supervisor before stop actually dispatches.
        private final Set<ActorId> stoppingWorkers = new java.util.HashSet<>();
        private final AtomicBoolean groupClosed = new AtomicBoolean();
        // Runtime-owned, non-cancellable observer; actor retirement may outlive
        // group admission revocation and clearing managed-worker references.
        private final OresFuture<Void> closedWorkersDone =
                new OresFuture<>(() -> false, () -> { });
        private final Object authorityLock = new Object();
        // Trusted runtime resource closures, not guest callbacks. Invoked
        // outside authorityLock after the group is fenced but before worker
        // cancellation and event-bus teardown.
        private final Set<Runnable> infrastructureCloseHooks = new LinkedHashSet<>();
        private final ActorEventBus eventBus;
        private final AtomicReference<GroupMailmanState<?>> mailmanState = new AtomicReference<>();
        private UUID joinSecret = UUID.randomUUID();

        private ActorGroup(
                ActorGroupId id,
                ActorId creator,
                IsolatePolicy creatorPolicy,
                ActorSupervisor supervisor) {
            this.id = Objects.requireNonNull(id, "id");
            this.creator = creator;
            this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
            Objects.requireNonNull(creatorPolicy, "creatorPolicy");
            this.memberLimit = Math.max(
                    1,
                    Math.min(
                            dispatcherConfig.maxActors(),
                            creatorPolicy.maxMailboxMessages()));
            this.eventTopicLimit = Math.max(
                    1,
                    Math.min(
                            MAX_EVENT_TOPICS_PER_GROUP,
                            creatorPolicy.maxMailboxMessages()));
            this.eventBus = new ActorEventBus(ActorRuntime.this, this);
            if (creator != null) members.add(creator);
        }

        public ActorGroupId id() { return id; }

        /** Public identity only; this does not expose the supervisor capability. */
        public SupervisorId supervisorId() {
            requireObserver("inspect ActorGroup supervisor");
            return supervisor.id();
        }

        public Optional<ActorId> creatorId() {
            requireObserver("inspect ActorGroup creator");
            return Optional.ofNullable(creator);
        }

        public boolean closed() { return groupClosed.get(); }

        void registerInfrastructureClose(Runnable hook) {
            Objects.requireNonNull(hook, "hook");
            synchronized (authorityLock) {
                requireOpen();
                infrastructureCloseHooks.add(hook);
            }
        }

        public int memberCount() {
            requireObserver("inspect ActorGroup membership");
            return members.size();
        }

        public Set<ActorId> memberIds() {
            requireObserver("inspect ActorGroup membership");
            return Set.copyOf(members);
        }

        /**
         * Opt a worker into lifecycle management without automatically joining
         * it to the group's event bus. Cooperating membership and structured
         * lifecycle ownership are separate capabilities.
         *
         * Host/supervisors may manage runtime-local actors. Inside an actor,
         * only the group's creator may register a structured descendant.
         */
        public <M> ActorRef<M> manageWorker(ActorRef<M> worker) {
            Objects.requireNonNull(worker, "worker");
            requireManager("manage an ActorGroup worker");
            if (!worker.ownedBy(ActorRuntime.this)) {
                throw new IllegalArgumentException("worker belongs to a different ActorRuntime");
            }
            requireActorLifecycleAuthority(worker, "manage an ActorGroup worker");
            synchronized (authorityLock) {
                requireOpen();
                ActorCell<?> cell = actors.get(worker.id());
                if (cell == null || cell.stopped.get() || stoppingWorkers.contains(worker.id())) {
                    throw new IllegalStateException("cannot manage a stopped, stopping or unknown worker");
                }
                if (!managedWorkers.containsKey(worker.id()) && managedWorkers.size() >= memberLimit) {
                    throw new IllegalStateException(
                            "ActorGroup managed worker limit exceeded: maximum " + memberLimit);
                }
                // A worker has exactly one structured ActorGroup owner.
                // Other cooperation groups may still admit it as a member,
                // but no second manager may acquire independent stop rights.
                ActorGroupId owningGroup = cell.managedByGroup.get();
                if (owningGroup == null) {
                    if (cell.managedByGroup.compareAndSet(null, id)) {
                        owningGroup = id;
                    } else {
                        owningGroup = cell.managedByGroup.get();
                    }
                }
                if (!id.equals(owningGroup)) {
                    throw new IllegalStateException(
                            "actor is already managed by a different ActorGroup");
                }
                managedWorkers.putIfAbsent(worker.id(), worker);
                if (cell.stopped.get()) {
                    managedWorkers.remove(worker.id(), worker);
                    throw new IllegalStateException("worker stopped during group admission");
                }
            }
            return worker;
        }

        /**
         * Spawn and install lifecycle ownership in one group admission turn.
         * The authority lock serializes with closeFromRuntime, so close never
         * snapshots managedWorkers between a spawn and its registration.
         * No user behavior factory executes during spawnInternal: actor
         * initialization is deferred until its dispatcher is scheduled.
         *
         * Invariant: successful return has an owner, or concurrent close has
         * already revoked it and requested cancellation. A failed admission
         * must cancel the unexposed actor before propagating the error.
         */
        public <M> ActorRef<M> spawnWorker(
                ActorKind kind,
                IsolatePolicy policy,
                BehaviorFactory<M> behaviorFactory) {
            requireManager("spawn an ActorGroup worker");
            synchronized (authorityLock) {
                requireOpen();
                ActorRef<M> worker = ActorRuntime.this.spawn(kind, policy, behaviorFactory);
                try {
                    return manageWorker(worker);
                } catch (RuntimeException | Error failure) {
                    try {
                        worker.cancel();
                    } catch (RuntimeException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                    throw failure;
                }
            }
        }

        /** Non-blocking readiness barrier retaining the worker reference. */
        public <M> OresFuture<ActorRef<M>> spawnReadyWorker(
                ActorKind kind,
                IsolatePolicy policy,
                BehaviorFactory<M> behaviorFactory) {
            return spawnWorker(kind, policy, behaviorFactory).readyRef();
        }

        /**
         * Transfer one live actor out of the group's lifecycle authority
         * without stopping it or changing cooperative group membership.
         * A second group can adopt only after the old cell ownership token
         * has been atomically cleared under the same admission lock.
         */
        public boolean releaseWorker(ActorRef<?> worker) {
            requireManager("release an ActorGroup worker");
            Objects.requireNonNull(worker, "worker");
            if (!worker.ownedBy(ActorRuntime.this)) {
                throw new IllegalArgumentException("worker belongs to a different ActorRuntime");
            }
            synchronized (authorityLock) {
                requireOpen();
                if (stoppingWorkers.contains(worker.id())) {
                    throw new IllegalStateException(
                            "cannot transfer worker after stop/cancellation was admitted");
                }
                if (!managedWorkers.containsKey(worker.id())) return false;
                ActorCell<?> cell = actors.get(worker.id());
                if (cell == null || cell.stopped.get()) {
                    throw new IllegalStateException("cannot transfer a stopped worker");
                }
                if (!cell.managedByGroup.compareAndSet(id, null)) {
                    throw new IllegalStateException("worker lifecycle owner changed unexpectedly");
                }
                managedWorkers.remove(worker.id(), worker);
                return true;
            }
        }

        /** Live managed workers at a point in time; never exposes cancellation authority. */
        public List<ActorRef<?>> workers() {
            requireObserver("inspect ActorGroup workers");
            return List.copyOf(managedWorkers.values());
        }

        public int workerCount() {
            requireObserver("inspect ActorGroup workers");
            return managedWorkers.size();
        }

        /** The snapshot can be awaited without blocking an OresVM carrier. */
        public OresFuture<List<Void>> workersReady() {
            requireObserver("await ActorGroup workers readiness");
            return OresFutures.all(workers().stream().map(ActorRef::ready).toList());
        }

        /** Completion snapshot; new worker admissions are not included. */
        public OresFuture<List<Void>> workersDone() {
            requireObserver("await ActorGroup workers completion");
            return OresFutures.all(workers().stream().map(ActorRef::done).toList());
        }

        /**
         * Await all workers owned at group-close admission, not merely the
         * empty managed-worker registry after close has revoked admission.
         * This observes logical actor retirement, not loan-gated physical
         * FFM/native heap release.
         */
        public OresFuture<Void> closedDone() {
            requireObserver("observe ActorGroup close completion");
            return closedWorkersDone;
        }

        /** Request group shutdown and obtain its noncancellable drain barrier. */
        public OresFuture<Void> closeAsync() {
            requireManager("close ActorGroup asynchronously");
            closeFromRuntime();
            return closedWorkersDone;
        }

        /** Manager-only graceful stop. Never acts on membership-only actors. */
        public void stopWorkers() {
            requireManager("stop ActorGroup workers");
            final List<ActorRef<?>> stopRequests;
            synchronized (authorityLock) {
                requireOpen();
                stopRequests = List.copyOf(managedWorkers.values());
                for (ActorRef<?> ref : stopRequests) stoppingWorkers.add(ref.id());
            }
            // Do not acquire actor lifecycle locks while group authority is
            // held: actor finalization takes its own lock before group cleanup.
            for (ActorRef<?> ref : stopRequests) {
                if (ref.isAlive()) ref.stop();
            }
        }

        /** Manager-only structured cancellation, not force-kill. */
        public int cancelWorkers() {
            requireManager("cancel ActorGroup workers");
            final List<ActorRef<?>> stopRequests;
            synchronized (authorityLock) {
                requireOpen();
                stopRequests = List.copyOf(managedWorkers.values());
                for (ActorRef<?> ref : stopRequests) stoppingWorkers.add(ref.id());
            }
            int cancelled = 0;
            for (ActorRef<?> ref : stopRequests) {
                if (ref.cancel()) cancelled++;
            }
            return cancelled;
        }

        int eventTopicLimit() { return eventTopicLimit; }

        public ActorEventBus events() {
            requireObserver("access ActorGroup events");
            requireOpen();
            return eventBus;
        }

        /**
         * Install the group's one logical serialized Mailman and bounded MPSC
         * outbox. The Mailman executes on the VM CONTROL scheduler supplied to
         * this ActorRuntime; it never owns a carrier thread or scheduler.
         */
        public <Out> void installMailman(
                int outboxCapacity,
                ActorMailman<Out> mailman) {
            requireManager("install an ActorGroup Mailman");
            requireOpen();
            if (outboxCapacity <= 0) {
                throw new IllegalArgumentException("ActorGroup Mailman outbox capacity must be > 0");
            }
            GroupMailmanState<Out> next =
                    new GroupMailmanState<>(this, outboxCapacity, mailman);
            installMailmanState(next);
        }

        /**
         * Install a resumable Mailman whose waits cooperatively release the
         * physical CONTROL carrier and resume through the runtime dispatcher.
         */
        public <Out> void installCooperativeMailman(
                int outboxCapacity,
                CooperativeActorMailman<Out> mailman) {
            requireManager("install a cooperative ActorGroup Mailman");
            requireOpen();
            if (outboxCapacity <= 0) {
                throw new IllegalArgumentException("ActorGroup Mailman outbox capacity must be > 0");
            }
            GroupMailmanState<Out> next =
                    new GroupMailmanState<>(this, outboxCapacity, mailman);
            installMailmanState(next);
        }

        private void installMailmanState(GroupMailmanState<?> next) {
            if (!mailmanState.compareAndSet(null, next)) {
                throw new IllegalStateException("ActorGroup already has a Mailman: " + id);
            }
            if (groupClosed.get() || closed.get()) {
                if (mailmanState.compareAndSet(next, null)) next.close();
                throw new IllegalStateException("ActorGroup closed while installing Mailman: " + id);
            }
        }

        public boolean hasMailman() {
            requireObserver("inspect ActorGroup Mailman");
            GroupMailmanState<?> state = mailmanState.get();
            return state != null && !state.stopped();
        }

        /**
         * Emit one data-only value to this group's bounded Mailman outbox.
         * Actor identity is captured as metadata; the immutable payload is
         * quota-accounted until the Mailman consumes or teardown drains it.
         */
        public void emit(Object output) {
            ActorId sender = requireCurrentMember("emit to an ActorGroup Mailman");
            GroupMailmanState<?> state = mailmanState.get();
            if (state == null) {
                throw new IllegalStateException("ActorGroup has no configured Mailman: " + id);
            }
            state.emitUntyped(sender, output);
        }

        int mailmanOutboxSize() {
            GroupMailmanState<?> state = mailmanState.get();
            return state == null ? 0 : state.outboxSize();
        }

        public ActorGroupJoinCapability joinCapability() {
            requireManager("issue ActorGroup join capabilities");
            synchronized (authorityLock) {
                requireOpen();
                return new ActorGroupJoinCapability(id, joinSecret);
            }
        }

        /**
         * Invalidates previously issued join capabilities without disturbing
         * actors that are already members. Rotation and join admission are
         * linearized on authorityLock so a stale capability cannot validate
         * immediately before rotation and join immediately after it.
         */
        public ActorGroupJoinCapability rotateJoinCapability() {
            requireManager("rotate ActorGroup join capabilities");
            synchronized (authorityLock) {
                requireOpen();
                joinSecret = UUID.randomUUID();
                return new ActorGroupJoinCapability(id, joinSecret);
            }
        }

        public void joinCurrent(ActorGroupJoinCapability capability) {
            ActorId actorId = currentActorId().orElseThrow(() ->
                    new SecurityException("joinCurrent requires an actor execution context"));
            joinId(actorId, capability);
        }

        public void join(ActorRef<?> ref, ActorGroupJoinCapability capability) {
            Objects.requireNonNull(ref, "ref");
            requireCallerRuntimeAffinity("join an ActorGroup");
            if (!ref.ownedBy(ActorRuntime.this)) {
                throw new ActorInvalidReferenceException("ActorRef belongs to a different ActorRuntime");
            }
            ActorId caller = currentActorId().orElse(null);
            if (caller != null && !caller.equals(ref.id())) {
                throw new SecurityException("actor code may only join itself to an ActorGroup");
            }
            joinId(ref.id(), capability);
        }

        private void joinId(ActorId actorId, ActorGroupJoinCapability capability) {
            // One admission boundary across ALL groups, including creator
            // auto-memberships. Never reserve a slot on duplicate join.
            synchronized (groupMembershipLock) {
                synchronized (authorityLock) {
                    requireOpen();
                    requireCapabilityLocked(capability);
                    ActorCell<?> cell = actors.get(actorId);
                    if (cell == null || cell.stopped.get()) {
                        throw new IllegalStateException("actor is not alive: " + actorId);
                    }
                    if (members.contains(actorId)) return;
                    if (members.size() >= memberLimit) {
                        throw new IllegalStateException(
                                "ActorGroup member limit exceeded: maximum " + memberLimit);
                    }
                    Set<ActorGroupId> joined = groupMemberships.computeIfAbsent(
                            actorId, ignored -> new java.util.HashSet<>());
                    int limit = Math.max(1, Math.min(
                            MAX_ACTOR_GROUP_MEMBERSHIPS_PER_ACTOR,
                            cell.policy.maxMailboxMessages()));
                    if (joined.size() >= limit) {
                        throw new IllegalStateException(
                                "actor ActorGroup membership limit exceeded: maximum " + limit);
                    }
                    members.add(actorId);
                    joined.add(id);
                    if (groupClosed.get() || cell.stopped.get()) {
                        members.remove(actorId);
                        joined.remove(id);
                        if (joined.isEmpty()) groupMemberships.remove(actorId);
                        throw new IllegalStateException("ActorGroup or actor closed during join");
                    }
                }
            }
        }

        public void leaveCurrent() {
            ActorId actorId = requireCurrentMember("leave an ActorGroup");
            leaveId(actorId);
        }

        public boolean contains(ActorRef<?> ref) {
            Objects.requireNonNull(ref, "ref");
            requireObserver("inspect ActorGroup membership");
            if (!ref.ownedBy(ActorRuntime.this)) return false;
            return members.contains(ref.id());
        }

        private void requireObserver(String operation) {
            requireCallerRuntimeAffinity(operation);
            ActorId caller = currentActorId().orElse(null);
            if (caller != null && !isMember(caller)) {
                throw new SecurityException(
                        operation + " requires ActorGroup membership");
            }
        }

        boolean isMember(ActorId actorId) {
            return !groupClosed.get() && members.contains(actorId);
        }

        ActorId requireCurrentMember(String operation) {
            requireCallerRuntimeAffinity(operation);
            ActorId actorId = currentActorId().orElseThrow(() ->
                    new SecurityException(operation + " requires an actor execution context"));
            if (!isMember(actorId)) {
                throw new SecurityException("actor " + actorId + " is not a member of ActorGroup " + id);
            }
            return actorId;
        }

        ActorId currentMemberOrSystem(String operation) {
            requireCallerRuntimeAffinity(operation);
            requireNotActorGroupMailman(operation);
            ActorId actorId = currentActorId().orElse(null);
            if (actorId != null && !isMember(actorId)) {
                throw new SecurityException("actor " + actorId + " is not a member of ActorGroup " + id);
            }
            return actorId;
        }

        void requireManager(String operation) {
            requireCallerRuntimeAffinity(operation);
            requireNotActorGroupMailman(operation);
            ActorId caller = currentActorId().orElse(null);
            if (caller != null && !Objects.equals(caller, creator)) {
                throw new SecurityException(
                        operation + " requires the ActorGroup creator or host/supervisor");
            }
        }

        void requireSupervisor(String operation) {
            requireSupervisorContext(operation);
        }

        private void requireCapability(ActorGroupJoinCapability capability) {
            synchronized (authorityLock) {
                requireCapabilityLocked(capability);
            }
        }

        private void requireCapabilityLocked(ActorGroupJoinCapability capability) {
            Objects.requireNonNull(capability, "capability");
            if (!capability.ownedBy(ActorRuntime.this)
                    || !id.equals(capability.groupId)
                    || !joinSecret.equals(capability.secret)) {
                throw new SecurityException("invalid or revoked ActorGroup join capability");
            }
        }

        private void leaveId(ActorId actorId) {
            synchronized (groupMembershipLock) {
                if (!members.remove(actorId)) return;
                Set<ActorGroupId> joined = groupMemberships.get(actorId);
                if (joined != null) {
                    joined.remove(id);
                    if (joined.isEmpty()) groupMemberships.remove(actorId);
                }
                // Rejoin must not overtake subscription draining for the old
                // membership and inadvertently inherit a previous channel.
                eventBus.removeActor(actorId);
            }
        }

        private void actorTerminated(ActorId actorId) {
            // Fenced against concurrent worker admission so stale actor refs
            // cannot be resurrected after finalization.
            synchronized (authorityLock) {
                managedWorkers.remove(actorId);
                stoppingWorkers.remove(actorId);
            }
            leaveId(actorId);
            if (Objects.equals(creator, actorId)) closeFromRuntime();
        }

        private void requireOpen() {
            if (groupClosed.get() || closed.get()) {
                throw new IllegalStateException("ActorGroup is closed: " + id);
            }
        }

        @Override
        public void close() {
            requireManager("close an ActorGroup");
            closeFromRuntime();
        }

        private void closeFromRuntime() {
            List<ActorRef<?>> owned;
            List<Runnable> infrastructureClosures;
            synchronized (authorityLock) {
                if (!groupClosed.compareAndSet(false, true)) return;
                // Revocation is part of close: even a capability held by code
                // racing teardown can no longer pass validation.
                joinSecret = UUID.randomUUID();
                owned = List.copyOf(managedWorkers.values());
                infrastructureClosures = List.copyOf(infrastructureCloseHooks);
                infrastructureCloseHooks.clear();
                managedWorkers.clear();
                stoppingWorkers.clear();
            }
            // Runtime resources are fenced immediately; this must happen
            // without authorityLock and failures must not strand other hooks.
            Throwable infrastructureFailure = null;
            for (Runnable closure : infrastructureClosures) {
                try {
                    closure.run();
                } catch (RuntimeException | Error failure) {
                    if (infrastructureFailure == null) infrastructureFailure = failure;
                    else infrastructureFailure.addSuppressed(failure);
                }
            }
            // Revoke every membership before asynchronous worker cleanup. A
            // room close frees membership quota without stopping unrelated
            // members or tearing down their other room subscriptions.
            synchronized (groupMembershipLock) {
                for (ActorId member : members) {
                    Set<ActorGroupId> joined = groupMemberships.get(member);
                    if (joined != null) {
                        joined.remove(id);
                        if (joined.isEmpty()) groupMemberships.remove(member);
                    }
                }
                members.clear();
                if (creator != null) {
                    Set<ActorGroupId> created = createdGroupsByActor.get(creator);
                    if (created != null) {
                        created.remove(id);
                        if (created.isEmpty()) createdGroupsByActor.remove(creator);
                    }
                }
            }
            // Attach completion handlers before requesting cancellation: an
            // actor might finalize synchronously during the first stop call.
            // Keep only a future, not strong actor heap/arena references.
            if (owned.isEmpty()) {
                closedWorkersDone.completeFromRuntime(null);
            } else {
                AtomicInteger pending = new AtomicInteger(owned.size());
                AtomicReference<Throwable> firstFailure = new AtomicReference<>();
                for (ActorRef<?> worker : owned) {
                    worker.done().whenCompleteRuntime((ignored, failure) -> {
                        if (failure != null) firstFailure.compareAndSet(null, failure);
                        if (pending.decrementAndGet() == 0) {
                            Throwable terminal = firstFailure.get();
                            if (terminal == null) closedWorkersDone.completeFromRuntime(null);
                            else closedWorkersDone.failFromRuntime(terminal);
                        }
                    });
                }
            }
            // Closing a cooperating group never cancels ordinary members.
            // Only explicitly managed workers are structurally cancelled.
            // Never abort event-bus and Mailman teardown because one worker's
            // cancellation hook failed. Preserve the failure for the manager.
            RuntimeException cancellationFailure = null;
            for (ActorRef<?> worker : owned) {
                try {
                    worker.cancel();
                } catch (RuntimeException failure) {
                    if (worker.isAlive()) {
                        if (cancellationFailure == null) cancellationFailure = failure;
                        else cancellationFailure.addSuppressed(failure);
                    }
                }
            }
            try {
                GroupMailmanState<?> mailman = mailmanState.getAndSet(null);
                if (mailman != null) mailman.close();
            } finally {
                try {
                    eventBus.closeFromGroup();
                } finally {
                    actorGroups.remove(id, this);
                }
            }
            if (infrastructureFailure != null) {
                if (cancellationFailure != null)
                    infrastructureFailure.addSuppressed(cancellationFailure);
                if (infrastructureFailure instanceof RuntimeException runtimeFailure)
                    throw runtimeFailure;
                throw (Error) infrastructureFailure;
            }
            if (cancellationFailure != null) throw cancellationFailure;
        }

        @Override
        public String toString() {
            return "ActorGroup[" + id.value() + ",closed=" + groupClosed.get() + "]";
        }
    }

    private static final class GroupMailmanEnvelope<Out> {
        private final ActorMail<Out> mail;
        private final ActorGroupEventReservation reservation;

        private GroupMailmanEnvelope(
                ActorMail<Out> mail,
                ActorGroupEventReservation reservation) {
            this.mail = Objects.requireNonNull(mail, "mail");
            this.reservation = Objects.requireNonNull(reservation, "reservation");
        }

        private void release() {
            reservation.close();
        }
    }

    private final class GroupMailmanState<Out> implements AutoCloseable {
        private final ActorGroup group;
        private final ActorMailman<Out> mailman;
        private final CooperativeActorMailman<Out> cooperativeMailman;
        private final ChannelRuntime.Channel<GroupMailmanEnvelope<Out>> outbox;
        private final Object admissionLock = new Object();
        private final AtomicLong nextSequence = new AtomicLong();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicReference<Thread> executionLease = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<PendingMailmanTask> suspended = new AtomicReference<>();

        private GroupMailmanState(
                ActorGroup group,
                int outboxCapacity,
                ActorMailman<Out> mailman) {
            this(group, outboxCapacity, Objects.requireNonNull(mailman, "mailman"), null);
        }

        private GroupMailmanState(
                ActorGroup group,
                int outboxCapacity,
                CooperativeActorMailman<Out> mailman) {
            this(group, outboxCapacity, null, Objects.requireNonNull(mailman, "mailman"));
        }

        private GroupMailmanState(
                ActorGroup group,
                int outboxCapacity,
                ActorMailman<Out> mailman,
                CooperativeActorMailman<Out> cooperativeMailman) {
            this.group = Objects.requireNonNull(group, "group");
            if ((mailman == null) == (cooperativeMailman == null)) {
                throw new IllegalArgumentException(
                        "exactly one Mailman implementation must be configured");
            }
            this.mailman = mailman;
            this.cooperativeMailman = cooperativeMailman;
            this.outbox = new ChannelRuntime.Channel<>(outboxCapacity);
        }

        private final class PendingMailmanTask {
            private final GroupMailmanEnvelope<Out> envelope;
            private final AtomicBoolean active;
            private final ActorGroupContext context;
            private final OresScheduler.Task<Void> task;
            private final AtomicReference<OresFuture.RuntimeWaiterRegistration<?>> waiter =
                    new AtomicReference<>();
            private final AtomicBoolean released = new AtomicBoolean();
            /**
             * Producer completion may race the tail of the current CONTROL turn.
             * Record readiness separately from dispatch so another CONTROL carrier
             * cannot resume this logical Mailman before executionLease is released.
             */
            private final AtomicReference<OresScheduler.Resume> readyResume =
                    new AtomicReference<>();
            private final AtomicBoolean resumeDispatchScheduled = new AtomicBoolean();

            private PendingMailmanTask(
                    GroupMailmanEnvelope<Out> envelope,
                    AtomicBoolean active,
                    ActorGroupContext context,
                    OresScheduler.Task<Void> task) {
                this.envelope = Objects.requireNonNull(envelope, "envelope");
                this.active = Objects.requireNonNull(active, "active");
                this.context = Objects.requireNonNull(context, "context");
                this.task = Objects.requireNonNull(task, "task");
            }

            private void detachWaiter() {
                OresFuture.RuntimeWaiterRegistration<?> registration =
                        waiter.getAndSet(null);
                if (registration != null) registration.detach();
            }

            private void release() {
                detachWaiter();
                if (released.compareAndSet(false, true)) envelope.release();
            }
        }

        private boolean stopped() {
            return stopped.get();
        }

        @SuppressWarnings("unchecked")
        private void emitUntyped(ActorId sender, Object output) {
            emit(sender, (Out) output);
        }

        private void emit(ActorId sender, Out output) {
            Objects.requireNonNull(sender, "sender");
            Throwable priorFailure = failure.get();
            if (priorFailure != null) {
                throw new IllegalStateException(
                        "ActorGroup Mailman has failed for " + group.id,
                        priorFailure);
            }
            if (stopped.get() || group.closed() || closed.get()) {
                throw new IllegalStateException("ActorGroup Mailman is stopped for " + group.id);
            }

            FrozenActorGroupEventPayload frozen = freezeActorGroupEventPayload(output);
            GroupMailmanEnvelope<Out> envelope;
            synchronized (admissionLock) {
                if (stopped.get() || group.closed() || closed.get()) {
                    frozen.reservation().close();
                    throw new IllegalStateException(
                            "ActorGroup Mailman stopped during admission for " + group.id);
                }
                long sequence = nextSequence.getAndIncrement();
                envelope =
                        new GroupMailmanEnvelope<>(
                                new ActorMail<>(
                                        sender,
                                        group.id,
                                        sequence,
                                        (Out) frozen.value()),
                                frozen.reservation());

                if (!outbox.tryWrite(envelope)) {
                    envelope.release();
                    throw new IllegalStateException(
                            "ActorGroup Mailman outbox capacity exceeded for " + group.id);
                }
            }

            try {
                schedule();
            } catch (RuntimeException | Error admissionFailure) {
                failAndDrain(admissionFailure);
                throw admissionFailure;
            }
        }

        private void schedule() {
            if (stopped.get() || suspended.get() != null || outbox.size() == 0) return;
            if (!scheduled.compareAndSet(false, true)) return;

            final OresFuture<Void> dispatch;
            try {
                dispatch = Objects.requireNonNull(
                        controlDispatcher.execute(this::runQuantum),
                        "ActorGroup Mailman CONTROL dispatcher returned null Future");
            } catch (RuntimeException | Error submissionFailure) {
                scheduled.set(false);
                throw submissionFailure;
            }

            dispatch.whenCompleteRuntime((ignored, dispatchFailure) -> {
                if (dispatchFailure != null) {
                    failAndDrain(OresFuture.unwrap(dispatchFailure));
                }
            });
        }

        private ActorGroupContext newMailmanContext(AtomicBoolean active) {
            return new ActorGroupContext() {
                private void requireActive() {
                    if (!active.get() || executionLease.get() != Thread.currentThread()) {
                        throw new SecurityException(
                                "ActorGroupContext requires an active Mailman CONTROL turn");
                    }
                    ActorGroupMailmanExecution execution = CURRENT_ACTOR_GROUP_MAILMAN.get();
                    if (execution == null || execution.runtime() != ActorRuntime.this
                            || !execution.groupId().equals(group.id)) {
                        throw new SecurityException("ActorGroupContext belongs to another execution");
                    }
                }

                @Override
                public ActorGroupId groupId() {
                    requireActive();
                    return group.id;
                }

                @Override
                public int memberCount() {
                    requireActive();
                    if (stopped.get() || group.closed() || closed.get()) {
                        throw new IllegalStateException(
                                "ActorGroup Mailman is stopped for " + group.id);
                    }
                    return group.members.size();
                }

                @Override
                public <M> void send(ActorRef<M> target, M message) {
                    requireActive();
                    Objects.requireNonNull(target, "target");
                    synchronized (admissionLock) {
                        if (stopped.get() || group.closed() || closed.get()) {
                            throw new IllegalStateException(
                                    "ActorGroup Mailman is stopped for " + group.id);
                        }
                        sendFromActorGroupMailman(group, target, message);
                    }
                }
            };
        }

        private boolean enterExecutionLease() {
            Thread carrier = Thread.currentThread();
            if (!executionLease.compareAndSet(null, carrier)) {
                throw new IllegalStateException(
                        "single-Mailman execution lease violated for ActorGroup " + group.id);
            }
            ActorGroupMailmanExecution prior = CURRENT_ACTOR_GROUP_MAILMAN.get();
            if (prior != null) {
                executionLease.compareAndSet(carrier, null);
                throw new IllegalStateException(
                        "nested ActorGroup Mailman execution is not permitted");
            }
            CURRENT_ACTOR_GROUP_MAILMAN.set(
                    new ActorGroupMailmanExecution(ActorRuntime.this, group.id));
            return true;
        }

        private void leaveExecutionLease() {
            Thread carrier = Thread.currentThread();
            CURRENT_ACTOR_GROUP_MAILMAN.remove();
            if (!executionLease.compareAndSet(carrier, null)) {
                failure.compareAndSet(
                        null,
                        new IllegalStateException(
                                "ActorGroup Mailman execution lease ownership changed for "
                                        + group.id));
                stopped.set(true);
            }
        }

        private void runQuantum() {
            if (stopped.get()) {
                scheduled.set(false);
                return;
            }

            enterExecutionLease();
            try {
                int handled = 0;
                long startedAt = System.nanoTime();
                while (!stopped.get()
                        && handled < dispatcherConfig.throughput()
                        && (handled == 0
                                || System.nanoTime() - startedAt < MAILMAN_MAX_BATCH_NANOS)) {
                    GroupMailmanEnvelope<Out> envelope;
                    try {
                        envelope = outbox.tryRead().orElse(null);
                    } catch (ChannelRuntime.ChannelClosedException closedOutbox) {
                        if (stopped.get()) break;
                        throw closedOutbox;
                    }
                    if (envelope == null) break;

                    try {
                        if (cooperativeMailman != null) {
                            if (!startCooperativeEnvelope(envelope)) {
                                break;
                            }
                        } else {
                            runSynchronousEnvelope(envelope);
                        }
                    } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
                        failure.compareAndSet(null, fatal);
                        stopped.set(true);
                        throw fatal;
                    } catch (Throwable mailmanFailure) {
                        failure.compareAndSet(null, mailmanFailure);
                        stopped.set(true);
                    }
                    handled++;
                }
            } finally {
                leaveExecutionLease();
                finishControlTurn();
            }
        }

        private void runSynchronousEnvelope(GroupMailmanEnvelope<Out> envelope)
                throws Exception {
            AtomicBoolean active = new AtomicBoolean(true);
            ActorGroupContext context = newMailmanContext(active);
            try {
                mailman.receiveMail(envelope.mail, context);
            } finally {
                active.set(false);
                envelope.release();
            }
        }

        private boolean startCooperativeEnvelope(GroupMailmanEnvelope<Out> envelope)
                throws Exception {
            AtomicBoolean active = new AtomicBoolean();
            ActorGroupContext context = newMailmanContext(active);
            OresScheduler.Task<Void> task;
            active.set(true);
            try {
                task = Objects.requireNonNull(
                        cooperativeMailman.createTask(envelope.mail, context),
                        "cooperative Mailman returned null task");
            } catch (Throwable factoryFailure) {
                // The envelope has already left the bounded outbox, so generic
                // drain logic can no longer see its reservation. Release it
                // here before propagating the factory failure.
                envelope.release();
                throw factoryFailure;
            } finally {
                active.set(false);
            }

            PendingMailmanTask pending =
                    new PendingMailmanTask(envelope, active, context, task);
            try {
                boolean done = advancePending(
                        pending,
                        new OresScheduler.Resume(true, null, null));
                if (done) pending.release();
                return done;
            } catch (Throwable failure) {
                pending.release();
                throw failure;
            }
        }

        private boolean advancePending(
                PendingMailmanTask pending,
                OresScheduler.Resume resume) throws Exception {
            if (stopped.get() || group.closed() || closed.get()) {
                throw new java.util.concurrent.CancellationException(
                        "ActorGroup Mailman stopped before cooperative resume");
            }

            final OresScheduler.Step<Void> step;
            pending.active.set(true);
            try {
                step = Objects.requireNonNull(
                        pending.task.resume(resume),
                        "cooperative Mailman task returned null Step");
            } finally {
                pending.active.set(false);
            }

            if (step instanceof OresScheduler.Done<?>) {
                return true;
            }
            if (step instanceof OresScheduler.Await<?> awaiting) {
                armPending(pending, awaiting.future());
                return false;
            }
            throw new IllegalStateException(
                    "unknown cooperative Mailman step " + step.getClass().getName());
        }

        private void armPending(
                PendingMailmanTask pending,
                OresFuture<?> awaited) {
            Objects.requireNonNull(awaited, "awaited");
            if (!suspended.compareAndSet(null, pending)) {
                throw new IllegalStateException(
                        "ActorGroup Mailman attempted overlapping cooperative suspension");
            }
            pending.readyResume.set(null);
            pending.resumeDispatchScheduled.set(false);

            Object terminal = awaited.runtimeTerminalStateOrNull();
            if (terminal != null) {
                queuePendingResume(
                        pending,
                        new OresScheduler.Resume(
                                false,
                                OresFuture.runtimeTerminalValue(terminal),
                                OresFuture.runtimeTerminalFailure(terminal)));
                return;
            }

            OresFuture.RuntimeWaiterRegistration<?> registration =
                    awaited.whenCompleteRuntimeCancellable((value, waitFailure) ->
                            queuePendingResume(
                                    pending,
                                    new OresScheduler.Resume(
                                            false,
                                            value,
                                            waitFailure == null
                                                    ? null
                                                    : OresFuture.unwrap(waitFailure))));
            pending.waiter.set(registration);

            if (stopped.get() || suspended.get() != pending) {
                pending.detachWaiter();
            }
        }

        private void queuePendingResume(
                PendingMailmanTask pending,
                OresScheduler.Resume resume) {
            if (stopped.get() || suspended.get() != pending) return;
            if (!pending.readyResume.compareAndSet(null, resume)) {
                failAndDrain(new IllegalStateException(
                        "cooperative Mailman await queued more than one resume"));
                return;
            }

            /*
             * Future completion may happen before the current CONTROL turn has
             * finished unwinding. Never submit a resumable turn while this
             * logical Mailman still owns a physical carrier: with CONTROL
             * parallelism > 1 that could violate the single-Mailman lease.
             * finishControlTurn() retries after leaveExecutionLease().
             */
            schedulePendingResumeIfReady(pending);
        }

        private void schedulePendingResumeIfReady(PendingMailmanTask pending) {
            if (stopped.get() || suspended.get() != pending) return;
            if (pending.readyResume.get() == null) return;
            if (executionLease.get() != null) return;
            if (!pending.resumeDispatchScheduled.compareAndSet(false, true)) return;

            final OresFuture<Void> dispatch;
            try {
                dispatch = Objects.requireNonNull(
                        controlDispatcher.execute(
                                () -> resumePending(pending)),
                        "ActorGroup Mailman CONTROL dispatcher returned null Future");
            } catch (RuntimeException | Error submissionFailure) {
                pending.resumeDispatchScheduled.set(false);
                failAndDrain(submissionFailure);
                return;
            }

            dispatch.whenCompleteRuntime((ignored, dispatchFailure) -> {
                if (dispatchFailure != null) {
                    failAndDrain(OresFuture.unwrap(dispatchFailure));
                }
            });
        }

        private void resumePending(PendingMailmanTask pending) {
            if (stopped.get() || suspended.get() != pending) return;

            enterExecutionLease();
            try {
                pending.detachWaiter();
                if (!suspended.compareAndSet(pending, null)) return;

                OresScheduler.Resume resume = pending.readyResume.getAndSet(null);
                pending.resumeDispatchScheduled.set(false);
                if (resume == null) {
                    pending.release();
                    failure.compareAndSet(
                            null,
                            new IllegalStateException(
                                    "cooperative Mailman resumed without an await result"));
                    stopped.set(true);
                    return;
                }

                try {
                    boolean done = advancePending(pending, resume);
                    if (done) pending.release();
                } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
                    pending.release();
                    failure.compareAndSet(null, fatal);
                    stopped.set(true);
                    throw fatal;
                } catch (Throwable mailmanFailure) {
                    pending.release();
                    failure.compareAndSet(null, mailmanFailure);
                    stopped.set(true);
                }
            } finally {
                leaveExecutionLease();
                finishControlTurn();
            }
        }

        private void finishControlTurn() {
            if (stopped.get()) {
                scheduled.set(false);
                cancelSuspended();
                drainOutbox();
                return;
            }
            PendingMailmanTask pending = suspended.get();
            if (pending != null) {
                // The logical Mailman lease remains held, but no physical
                // carrier is retained while its awaited Future is pending.
                // If completion raced the tail of the prior turn, this is the
                // first point at which a new CONTROL dispatch is permitted.
                schedulePendingResumeIfReady(pending);
                return;
            }

            scheduled.set(false);
            if (outbox.size() != 0) {
                try {
                    schedule();
                } catch (RuntimeException | Error rescheduleFailure) {
                    failAndDrain(rescheduleFailure);
                }
            }
        }

        private void cancelSuspended() {
            PendingMailmanTask pending = suspended.getAndSet(null);
            if (pending != null) pending.release();
        }

        private void failAndDrain(Throwable cause) {
            synchronized (admissionLock) {
                failure.compareAndSet(
                        null,
                        cause == null
                                ? new IllegalStateException(
                                        "ActorGroup Mailman CONTROL dispatch failed")
                                : cause);
                stopped.set(true);
                scheduled.set(false);
                cancelSuspended();
                drainOutboxLocked();
            }
        }

        private void drainOutbox() {
            synchronized (admissionLock) {
                drainOutboxLocked();
            }
        }

        private void drainOutboxLocked() {
            outbox.close();
            while (outbox.drainOne(GroupMailmanEnvelope::release)) {
                // Closing the channel preserves buffered envelopes; teardown owns their release.
            }
        }

        private int outboxSize() {
            return outbox.size();
        }

        @Override
        public void close() {
            synchronized (admissionLock) {
                stopped.set(true);
                scheduled.set(false);
                cancelSuspended();
                drainOutboxLocked();
            }
        }
    }

    /**
     * Create a cooperation domain. An actor-created group is structurally owned
     * by that actor and closes when its creator terminates. Host-created groups
     * live until explicitly closed or the ActorRuntime closes.
     */
    public int actorGroupCount() {
        return actorGroups.size();
    }

    /**
     * Resolve a live group by id. Actor callers must already be members; the
     * host/supervisor may inspect any group in its runtime.
     */
    public ActorGroup actorGroup(ActorGroupId id) {
        Objects.requireNonNull(id, "id");
        requireCallerRuntimeAffinity("access an ActorGroup");
        ActorGroup group = actorGroups.get(id);
        if (group == null || group.closed()) {
            throw new IllegalArgumentException("unknown or closed ActorGroup: " + id);
        }
        group.currentMemberOrSystem("access ActorGroup " + id);
        return group;
    }

    public ActorGroup createActorGroup() {
        return createActorGroupInternal(rootSupervisor);
    }

    /**
     * Bind an ActorGroup to an explicit registered supervisor at construction.
     * An ordinary actor may still create a group through the root-supervised
     * overload; it may not impersonate or choose a host supervisor.
     */
    public ActorGroup createActorGroup(ActorSupervisor supervisor) {
        requireSupervisorContext("assign an ActorGroup supervisor");
        Objects.requireNonNull(supervisor, "supervisor");
        if (supervisors.get(supervisor.id()) != supervisor)
            throw new IllegalArgumentException("ActorSupervisor belongs to another ActorRuntime");
        return createActorGroupInternal(supervisor);
    }

    private ActorGroup createActorGroupInternal(ActorSupervisor supervisor) {
        requireCallerRuntimeAffinity("create an ActorGroup");
        requireNotActorGroupMailman("create an ActorGroup");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");

        ActorId creator = currentActorId().orElse(null);
        IsolatePolicy creatorPolicy =
                currentActorPolicy() == null ? policyCeiling : currentActorPolicy();
        if (creator != null && !actors.containsKey(creator)) {
            throw new IllegalStateException("current actor is no longer registered");
        }

        synchronized (runtimeLifecycleLock) {
            if (closed.get()) throw new IllegalStateException("actor runtime is closed");
            if (actorGroups.size() >= dispatcherConfig.maxActors()) {
                throw new IllegalStateException(
                        "ActorGroup runtime limit exceeded: maximum "
                                + dispatcherConfig.maxActors());
            }
            if (creator != null) {
                long ownedGroups = actorGroups.values().stream()
                        .filter(group -> creator.equals(group.creator))
                        .filter(group -> !group.closed())
                        .count();
                int ownerLimit = Math.max(
                        1,
                        Math.min(
                                MAX_ACTOR_GROUPS_PER_ACTOR,
                                creatorPolicy.maxMailboxMessages()));
                if (ownedGroups >= ownerLimit) {
                    throw new IllegalStateException(
                            "actor-owned ActorGroup limit exceeded for "
                                    + creator + ": maximum " + ownerLimit);
                }
            }
            synchronized (groupMembershipLock) {
                ActorCell<?> creatorCell = creator == null ? null : actors.get(creator);
                if (creator != null && (creatorCell == null || creatorCell.stopped.get())) {
                    throw new IllegalStateException("group creator is no longer alive");
                }
                Set<ActorGroupId> joined = creator == null
                        ? null : groupMemberships.computeIfAbsent(
                                creator, ignored -> new java.util.HashSet<>());
                int limit = creatorCell == null ? 0 : Math.max(1, Math.min(
                        MAX_ACTOR_GROUP_MEMBERSHIPS_PER_ACTOR,
                        creatorCell.policy.maxMailboxMessages()));
                if (joined != null && joined.size() >= limit) {
                    throw new IllegalStateException(
                            "actor ActorGroup membership limit exceeded: maximum " + limit);
                }
                while (true) {
                    ActorGroupId id = ActorGroupId.create();
                    ActorGroup group = new ActorGroup(id, creator, creatorPolicy, supervisor);
                    if (actorGroups.putIfAbsent(id, group) == null) {
                        if (joined != null) {
                            joined.add(id);
                            createdGroupsByActor.computeIfAbsent(
                                    creator, ignored -> new java.util.HashSet<>()).add(id);
                        }
                        // Finalization calls unregisterActor() while holding
                        // ActorCell.lifecycleLock. Never acquire that lock here:
                        // instead recheck the stop flag after publication. If
                        // retirement beat group creation's registration, the
                        // group is closed before it can escape to the caller.
                        if (creatorCell != null && creatorCell.stopped.get()) {
                            group.closeFromRuntime();
                            throw new IllegalStateException("group creator stopped during creation");
                        }
                        return group;
                    }
                }
            }
        }
    }

    private <M> void sendFromActorGroupMailman(
            ActorGroup group,
            ActorRef<M> target,
            M message) {
        ActorGroupMailmanExecution mailman = CURRENT_ACTOR_GROUP_MAILMAN.get();
        if (mailman == null
                || mailman.runtime() != this
                || !mailman.groupId().equals(group.id)) {
            throw new SecurityException(
                    "ActorGroupContext.send requires the owning Mailman execution context");
        }

        boolean priorPermit =
                Boolean.TRUE.equals(ACTOR_GROUP_MAILMAN_CONTEXT_SEND.get());
        ACTOR_GROUP_MAILMAN_CONTEXT_SEND.set(Boolean.TRUE);
        try {
            send(target, message);
        } finally {
            if (priorPermit) {
                ACTOR_GROUP_MAILMAN_CONTEXT_SEND.set(Boolean.TRUE);
            } else {
                ACTOR_GROUP_MAILMAN_CONTEXT_SEND.remove();
            }
        }
    }

    private void requireCallerRuntimeAffinity(String operation) {
        requireNoForceCancellationHookReentry(operation);
        ActorRuntime caller = currentActorRuntime();
        if (caller != null && caller != this) {
            throw new SecurityException(
                    "actor cannot " + operation + " through another ActorRuntime");
        }
        ActorGroupMailmanExecution mailman = CURRENT_ACTOR_GROUP_MAILMAN.get();
        if (mailman != null) {
            if (mailman.runtime() != this) {
                throw new SecurityException(
                        "ActorGroup Mailman cannot " + operation + " through another ActorRuntime");
            }
            if (!Boolean.TRUE.equals(ACTOR_GROUP_MAILMAN_CONTEXT_SEND.get())) {
                throw new SecurityException(
                        "ActorGroup Mailman cannot " + operation
                                + " through ambient ActorRuntime authority; use ActorGroupContext");
            }
        }
    }

    private void requireNotActorGroupMailman(String operation) {
        ActorGroupMailmanExecution mailman = CURRENT_ACTOR_GROUP_MAILMAN.get();
        if (mailman != null) {
            throw new SecurityException(
                    "ActorGroup Mailman cannot " + operation
                            + "; CONTROL carrier identity does not confer host/supervisor authority");
        }
    }

    private void requireSupervisorContext(String operation) {
        requireNoForceCancellationHookReentry(operation);
        requireNotActorGroupMailman(operation);
        if (inActorExecution()) {
            throw new SecurityException(
                    "actor code cannot " + operation + "; this operation belongs to the host/supervisor");
        }
    }

    private void requireBlockingHostContext(String operation) {
        if (inActorExecution()
                || inActorGroupMailmanExecution()
                || OresScheduler.current() != null) {
            throw new SecurityException(
                    operation
                            + " is a host/embedder blocking operation; actor, Mailman CONTROL, "
                            + "and scheduler turns must use nonblocking cancellation plus "
                            + "Future/Awaitable suspension");
        }
    }

    private void requireActorLifecycleAuthority(
            ActorRef<?> target,
            String operation) {
        ActorCell<?> caller = currentActor.get();
        if (caller == null) return;

        ActorCell<?> targetCell = actors.get(target.id());
        if (targetCell == null) return;
        if (caller == targetCell) return;

        for (ActorCell<?> ancestor = targetCell.parent;
                ancestor != null;
                ancestor = ancestor.parent) {
            if (ancestor == caller) return;
        }

        throw new SecurityException(
                "actor " + caller.ref.id() + " cannot " + operation
                        + " unrelated actor " + target.id()
                        + "; actor lifecycle authority is limited to self and structured descendants");
    }

    public <M> ActorHandle<M> spawnChild(
            ActorKind kind,
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(behaviorFactory, "behaviorFactory");

        ActorCell<?> parent = currentActor.get();
        if (parent == null) {
            throw new IllegalStateException(
                    "spawnChild requires an executing parent actor");
        }

        parent.policy.require(
                IsolatePolicy.Capability.ACTOR_SPAWN,
                "child actor spawn");

        ActorRef<M> ref = spawnInternal(
                kind,
                policy,
                behaviorFactory,
                false);
        return new ActorHandle<>(ref, parent.ref.id());
    }

    public <M> ActorHandle<M> spawnChildPrivate(
            BehaviorFactory<M> behaviorFactory) {
        return spawnChild(
                ActorKind.PRIVATE,
                defaultSpawnPolicy(),
                behaviorFactory);
    }

    public <M> ActorHandle<M> spawnChildShared(
            BehaviorFactory<M> behaviorFactory) {
        return spawnChild(
                ActorKind.SHARED,
                defaultSpawnPolicy(),
                behaviorFactory);
    }

    public <M> ActorHandle<M> spawnChildUntrusted(
            BehaviorFactory<M> behaviorFactory) {
        return spawnChild(
                ActorKind.UNTRUSTED,
                IsolatePolicy.untrustedActor(),
                behaviorFactory);
    }

    private IsolatePolicy defaultSpawnPolicy() {
        IsolatePolicy caller = currentActorPolicy();
        return caller == null ? policyCeiling : caller;
    }

    private void requireWithinCallerPolicy(IsolatePolicy child) {
        IsolatePolicy caller = currentActorPolicy();
        if (caller == null) return;

        if (!caller.capabilities().containsAll(child.capabilities())) {
            java.util.Set<IsolatePolicy.Capability> excess = child.capabilities().isEmpty()
                    ? java.util.EnumSet.noneOf(IsolatePolicy.Capability.class)
                    : java.util.EnumSet.copyOf(child.capabilities());
            excess.removeAll(caller.capabilities());
            throw new SecurityException("child actor policy exceeds caller actor capabilities: " + excess);
        }
        if (child.maxHeapBytes() > caller.maxHeapBytes()) {
            throw new SecurityException("child actor maxHeapBytes exceeds caller actor policy");
        }
        if (child.maxMailboxMessages() > caller.maxMailboxMessages()) {
            throw new SecurityException("child actor mailbox limit exceeds caller actor policy");
        }
        if (child.maxWallTime().compareTo(caller.maxWallTime()) > 0) {
            throw new SecurityException("child actor wall-time limit exceeds caller actor policy");
        }
        if (caller.adversarial() && !child.adversarial()) {
            throw new SecurityException("child actor cannot weaken an adversarial caller policy");
        }
    }

    /** Backward-compatible default: an unqualified runtime actor is private. */
    public <M> ActorRef<M> spawn(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawn(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(policy, behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivate(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(policyCeiling, behaviorFactory);
    }

    /**
     * Trusted-host compatibility path. Compiler-generated actors should prefer
     * the context-aware BehaviorFactory overload.
     */
    public <M> ActorRef<M> spawnPrivate(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        requireSupervisorContext("use trusted Supplier private actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(ActorKind.PRIVATE, policy, context -> behaviorFactory.get(), true);
    }

    /**
     * Isolation-safe private actor construction. The factory itself must be
     * stateless/capture-free; mutable actor state must be created after the
     * actor context is installed and stored in actor-owned memory.
     */
    public <M> ActorRef<M> spawnPrivate(BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.PRIVATE, defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivate(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.PRIVATE, policy, behaviorFactory);
    }

    /**
     * Explicit host-only escape hatch for tests/embedding code that needs a
     * context-aware factory with captured Java objects. Never used by Oreslang
     * compiler lowering and forbidden for adversarial policies.
     */
    public <M> ActorRef<M> spawnPrivateTrusted(BehaviorFactory<M> behaviorFactory) {
        return spawnPrivateTrusted(defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivateTrusted(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        requireSupervisorContext("use trusted captured private actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(ActorKind.PRIVATE, policy, behaviorFactory, true);
    }

    public <M> ActorRef<M> spawnShared(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnShared(defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnShared(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        requireSupervisorContext("use trusted Supplier shared actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(ActorKind.SHARED, policy, context -> behaviorFactory.get(), true);
    }

    /**
     * Explicit hostile-code entrypoint. The runtime pins this actor to the
     * fixed adversarial baseline and never grants ambient capabilities.
     */
    public <M> ActorRef<M> spawnUntrusted(
            BehaviorFactory<M> behaviorFactory) {
        return spawn(
                ActorKind.UNTRUSTED,
                IsolatePolicy.untrustedActor(),
                behaviorFactory);
    }

    public <M> ActorRef<M> spawnUntrusted(
            IsolatePolicy requestedLimits,
            BehaviorFactory<M> behaviorFactory) {
        return spawn(
                ActorKind.UNTRUSTED,
                requestedLimits,
                behaviorFactory);
    }

    public <M> ActorRef<M> spawnShared(BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.SHARED, defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnShared(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.SHARED, policy, behaviorFactory);
    }

    /**
     * Explicit host-only escape hatch for context-aware shared actor factories
     * that intentionally capture host objects. Compiler lowering must never use
     * this path. Adversarial policies reject it.
     */
    public <M> ActorRef<M> spawnSharedTrusted(BehaviorFactory<M> behaviorFactory) {
        return spawnSharedTrusted(defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnSharedTrusted(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        requireSupervisorContext("use trusted captured shared actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(ActorKind.SHARED, policy, behaviorFactory, true);
    }

    /** Compatibility path for trusted host callers. */
    public <M> ActorRef<M> spawn(
            ActorKind kind,
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        requireSupervisorContext("use trusted Supplier actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(kind, policy, context -> behaviorFactory.get(), true);
    }

    private static void requireStatelessActorFactory(Object factory) {
        for (Class<?> type = factory.getClass();
             type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                boolean isStatic = java.lang.reflect.Modifier.isStatic(modifiers);
                boolean isFinal = java.lang.reflect.Modifier.isFinal(modifiers);

                if (!isStatic) {
                    throw new SecurityException(
                            "actor BehaviorFactory must be stateless; captured host state must enter through explicit actor messages/capabilities");
                }
                if (!isFinal) {
                    throw new SecurityException(
                            "actor BehaviorFactory declares mutable static JVM state '"
                                    + field.getName() + "'; actor construction cannot share static state");
                }
                if (!field.trySetAccessible()) {
                    throw new SecurityException(
                            "actor BehaviorFactory contains inaccessible static state: " + field.getName());
                }
                final Object value;
                try {
                    value = field.get(null);
                } catch (IllegalAccessException impossible) {
                    throw new SecurityException(
                            "cannot inspect actor BehaviorFactory static state: " + field.getName(),
                            impossible);
                }
                if (!isPrivateStaticConstant(value)) {
                    throw new SecurityException(
                            "actor BehaviorFactory declares shared static object '"
                                    + field.getName()
                                    + "'; only immutable scalar constants are allowed");
                }
            }
        }
    }

    private void validatePrivateBehaviorState(ActorId owner, Behavior<?> behavior) {
        for (Class<?> type = behavior.getClass();
             type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                boolean isStatic = java.lang.reflect.Modifier.isStatic(modifiers);
                boolean isFinal = java.lang.reflect.Modifier.isFinal(modifiers);

                if (isStatic) {
                    if (!isFinal) {
                        throw new SecurityException(
                                "private actor behavior class declares mutable static JVM state '"
                                        + field.getName() + "'; private actors cannot share static state");
                    }
                    if (!field.trySetAccessible()) {
                        throw new SecurityException(
                                "private actor behavior contains inaccessible static state: " + field.getName());
                    }
                    final Object staticValue;
                    try {
                        staticValue = field.get(null);
                    } catch (IllegalAccessException impossible) {
                        throw new SecurityException(
                                "cannot inspect private actor static state: " + field.getName(),
                                impossible);
                    }
                    if (!isPrivateStaticConstant(staticValue)) {
                        throw new SecurityException(
                                "private actor behavior class declares shared static object '"
                                        + field.getName()
                                        + "'; only immutable scalar constants are allowed");
                    }
                    continue;
                }

                if (!isFinal) {
                    throw new SecurityException(
                            "private actor behavior field '" + field.getName()
                                    + "' is mutable JVM state; persistent mutable state must use context.privateMemory()");
                }
                if (!field.trySetAccessible()) {
                    throw new SecurityException(
                            "private actor behavior contains inaccessible captured state: " + field.getName());
                }
                final Object value;
                try {
                    value = field.get(behavior);
                } catch (IllegalAccessException impossible) {
                    throw new SecurityException(
                            "cannot inspect private actor behavior capture: " + field.getName(),
                            impossible);
                }
                validatePrivateBehaviorCapture(owner, field.getName(), value);
            }
        }
    }

    private static boolean isPrivateStaticConstant(Object value) {
        return value == null
                || isScalar(value)
                || value instanceof Class<?>;
    }

    private void validatePrivateBehaviorCapture(ActorId owner, String fieldName, Object value) {
        if (value == null || isScalar(value) || value instanceof Class<?>) return;

        if (value instanceof ActorShardBacking shard) {
            if (!shard.ownedBy(this, owner) || !shard.isPrivateBacked()) {
                throw new SecurityException(
                        "private actor behavior cannot capture a foreign or shared shard in " + fieldName);
            }
            return;
        }
        if (value instanceof PrivateMemoryBlock block) {
            if (!block.owner().equals(owner)) {
                throw new SecurityException(
                        "private actor behavior captured another actor's memory block in " + fieldName);
            }
            return;
        }
        if (value instanceof ActorMemorySlice slice) {
            if (!slice.owner().equals(owner)) {
                throw new SecurityException(
                        "private actor behavior captured another actor's memory slice in " + fieldName);
            }
            return;
        }
        if (value instanceof MemoryReservation reservation) {
            if (!reservation.owner().equals(owner)) {
                throw new SecurityException(
                        "private actor behavior captured another actor's memory reservation in " + fieldName);
            }
            return;
        }
        if (value instanceof ActorRef<?> ref) {
            if (!ref.ownedBy(this)) {
                throw new SecurityException(
                        "private actor behavior captured an ActorRef from another runtime in " + fieldName);
            }
            return;
        }
        if (value instanceof ActorContext<?> actorContext) {
            if (!actorContext.self().id().equals(owner) || actorContext.runtime() != this) {
                throw new SecurityException(
                        "private actor behavior captured a foreign actor context in " + fieldName);
            }
            return;
        }

        throw new SecurityException(
                "private actor behavior captured mutable/non-private JVM state in "
                        + fieldName + " (" + value.getClass().getName()
                        + "); allocate persistent state through context.privateMemory()");
    }

    private void requireTrustedSupplierPolicy(IsolatePolicy policy) {
        Objects.requireNonNull(policy);
        if (policy.adversarial()) {
            throw new SecurityException(
                    "adversarial actors require the context-aware BehaviorFactory path; "
                            + "Supplier factories can capture host/shared mutable references");
        }
    }

    /**
     * Creates the actor identity and private memory slice immediately. Behavior
     * initialization later runs on that actor's dispatcher with the actor
     * context already installed.
     */
    public <M> ActorRef<M> spawn(
            ActorKind kind,
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        return spawnInternal(kind, policy, behaviorFactory, false);
    }

    /**
     * Spawn an actor with exactly one immutable process-local code image
     * whitelisted into its ActorContext. The factory remains subject to the
     * ordinary capture-free admission rules; code is supplied out-of-band
     * through the actor cell rather than captured in the factory.
     */
    public <M> ActorRef<M> spawnWithCodeImage(
            ActorKind kind,
            SharedCodeImageStore.CodeImage codeImage,
            BehaviorFactory<M> behaviorFactory) {
        return spawnWithCodeImage(
                kind,
                defaultSpawnPolicy(),
                codeImage,
                behaviorFactory);
    }

    public <M> ActorRef<M> spawnWithCodeImage(
            ActorKind kind,
            IsolatePolicy policy,
            SharedCodeImageStore.CodeImage codeImage,
            BehaviorFactory<M> behaviorFactory) {
        Objects.requireNonNull(codeImage, "codeImage");
        return spawnInternal(kind, policy, behaviorFactory, false, codeImage);
    }

    /**
     * Compiler-only source actor entry point. Unlike the trusted host factory
     * escape hatches, this does not capture arbitrary JVM state: the shared
     * executor is identity-bound to its CodeImage and the actor's mutable guest
     * state is created later on the actor dispatcher.
     */
    public ActorRef<Object> spawnCodeActor(
            ActorKind kind,
            ActorCodeExecutor executor,
            String actorTypeName) {
        return spawnCodeActor(
                kind,
                defaultSpawnPolicy(),
                executor,
                actorTypeName);
    }

    public ActorRef<Object> spawnCodeActor(
            ActorKind kind,
            IsolatePolicy policy,
            ActorCodeExecutor executor,
            String actorTypeName) {
        Objects.requireNonNull(executor, "executor");
        if (actorTypeName == null || actorTypeName.isBlank()) {
            throw new IllegalArgumentException(
                    "source actor type name cannot be blank");
        }
        SharedCodeImageStore.CodeImage image =
                Objects.requireNonNull(executor.codeImage(), "executor codeImage");
        return spawnInternal(
                kind,
                policy,
                null,
                false,
                image,
                executor,
                actorTypeName);
    }

    private <M> ActorRef<M> spawnInternal(
            ActorKind kind,
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory,
            boolean trustedFactory) {
        return spawnInternal(
                kind, policy, behaviorFactory, trustedFactory,
                null, null, null);
    }

    private <M> ActorRef<M> spawnInternal(
            ActorKind kind,
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory,
            boolean trustedFactory,
            SharedCodeImageStore.CodeImage codeImage) {
        return spawnInternal(
                kind, policy, behaviorFactory, trustedFactory,
                codeImage, null, null);
    }

    private <M> ActorRef<M> spawnInternal(
            ActorKind kind,
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory,
            boolean trustedFactory,
            SharedCodeImageStore.CodeImage codeImage,
            ActorCodeExecutor codeExecutor,
            String codeActorType) {
        requireCallerRuntimeAffinity("spawn actors");
        requireNotActorGroupMailman("spawn actors");
        Objects.requireNonNull(kind);
        Objects.requireNonNull(policy);
        if ((behaviorFactory == null) == (codeExecutor == null)) {
            throw new IllegalArgumentException(
                    "actor spawn requires exactly one behavior factory or source-code executor");
        }
        if (codeExecutor != null) {
            if (codeImage == null || codeExecutor.codeImage() != codeImage) {
                throw new SecurityException(
                        "source actor executor must be identity-bound to its exact whitelisted CodeImage");
            }
            if (codeActorType == null || codeActorType.isBlank()) {
                throw new IllegalArgumentException(
                        "source actor type name cannot be blank");
            }
        } else {
            Objects.requireNonNull(behaviorFactory, "behaviorFactory");
        }

        ActorCell<?> parentForPolicy = currentActor.get();
        if (parentForPolicy != null) {
            parentForPolicy.policy.require(
                    IsolatePolicy.Capability.ACTOR_SPAWN,
                    "actor spawn");
        }

        IsolatePolicy effectivePolicy;
        if (kind == ActorKind.UNTRUSTED) {
            IsolatePolicy baseline = IsolatePolicy.untrustedActor();
            long heap = Math.min(policy.maxHeapBytes(), baseline.maxHeapBytes());
            int mailbox = Math.min(policy.maxMailboxMessages(), baseline.maxMailboxMessages());
            java.time.Duration wall =
                    policy.maxWallTime().compareTo(baseline.maxWallTime()) < 0
                            ? policy.maxWallTime()
                            : baseline.maxWallTime();
            effectivePolicy = new IsolatePolicy(Set.of(), heap, mailbox, wall, true);
        } else if (kind == ActorKind.PRIVATE) {
            effectivePolicy = policy.withoutCapabilities(
                    IsolatePolicy.Capability.SHARED_MEMORY,
                    IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                    IsolatePolicy.Capability.JAVA_INTEROP,
                    IsolatePolicy.Capability.JAVA_SOURCE_INTEROP);
        } else {
            effectivePolicy = policy;
        }
        requireWithinCeiling(effectivePolicy);
        requireWithinCallerPolicy(effectivePolicy);
        if (kind == ActorKind.SHARED) {
            effectivePolicy.require(IsolatePolicy.Capability.SHARED_MEMORY, "shared actor spawn");
        }
        policy = effectivePolicy;
        if (behaviorFactory != null && !trustedFactory) {
            requireStatelessActorFactory(behaviorFactory);
        }

        ActorCell<?> parent = currentActor.get();
        synchronized (runtimeLifecycleLock) {
            if (closed.get()) throw new IllegalStateException("actor runtime is closed");
            reserveActorSlot();

            ActorCell<M> cell = null;
            boolean linkedToParent = false;
            try {
                // All potentially throwing incarnation construction belongs
                // inside the same rollback boundary as actor registration.
                // Otherwise generation exhaustion leaks the reserved actor slot.
                ActorId id = ActorId.create();
                long generation = nextActorGeneration.getAndIncrement();
                if (generation <= 0) {
                    throw new IllegalStateException("actor generation sequence exhausted");
                }
                ActorRef<M> ref = new ActorRef<>(id, kind, generation);
                cell = new ActorCell<>(
                        ref,
                        kind,
                        effectivePolicy,
                        behaviorFactory,
                        trustedFactory,
                        codeImage,
                        codeExecutor,
                        codeActorType,
                        parent);

                if (parent != null) {
                    synchronized (parent.lifecycleLock) {
                        if (parent.stopped.get()
                                || parent.sealing
                                || parent.finalized
                                || parent.forceKillFenced) {
                            throw new CancellationException(
                                    "cannot spawn a child from a stopping or force-fenced actor "
                                            + parent.ref.id());
                        }
                        // A nested spawn inherits its parent's structured
                        // lifecycle group. The spawn lineage is not a new
                        // supervisor or a new group. Event-bus membership
                        // remains opt-in and is never inherited here.
                        ActorGroupId inheritedId = parent.managedByGroup.get();
                        ActorGroup inherited = inheritedId == null
                                ? null : actorGroups.get(inheritedId);
                        if (inheritedId != null && inherited == null) {
                            throw new IllegalStateException(
                                    "parent's lifecycle ActorGroup no longer exists");
                        }
                        if (inherited != null) {
                            // Publish ownership, lineage and actor identity
                            // atomically against group close/worker transfer.
                            synchronized (inherited.authorityLock) {
                                inherited.requireOpen();
                                if (!inheritedId.equals(parent.managedByGroup.get())) {
                                    throw new IllegalStateException(
                                            "parent's lifecycle ActorGroup changed during spawn");
                                }
                                if (inherited.managedWorkers.size() >= inherited.memberLimit) {
                                    throw new IllegalStateException(
                                            "ActorGroup managed worker limit exceeded: maximum "
                                                    + inherited.memberLimit);
                                }
                                cell.managedByGroup.set(inheritedId);
                                inherited.managedWorkers.put(id, ref);
                                parent.children.add(cell);
                                linkedToParent = true;
                                actors.put(id, cell);
                            }
                        } else {
                            parent.children.add(cell);
                            linkedToParent = true;
                            actors.put(id, cell);
                        }
                    }
                } else {
                    actors.put(id, cell);
                }
                return ref;
            } catch (RuntimeException | Error failure) {
                // Roll back every registry when spawn admission fails. In
                // particular, never strand an inherited group ownership ref.
                if (cell != null) actors.remove(cell.ref.id(), cell);
                if (linkedToParent && cell != null) parent.children.remove(cell);
                if (cell != null) {
                    ActorGroupId inheritedId = cell.managedByGroup.get();
                    ActorGroup inherited = inheritedId == null
                            ? null : actorGroups.get(inheritedId);
                    if (inherited != null) {
                        synchronized (inherited.authorityLock) {
                            inherited.managedWorkers.remove(cell.ref.id(), cell.ref);
                        }
                    }
                }
                actorCount.decrementAndGet();
                throw failure;
            }
        }
    }

    /**
     * Compiler-only lowering target for source-level actor/isoactor callables.
     *
     * A fresh actor receives exactly one transported message, computes one
     * data-only result, then terminates. Synchronous nesting from an actor turn
     * is rejected so a bounded dispatcher cannot be starved by callers waiting
     * on actors scheduled onto the same runtime.
     */
    public <M, R> R invoke(
            ActorKind kind,
            M message,
            Invocation<M, R> invocation) {
        Objects.requireNonNull(kind, "kind");
        if (OresScheduler.current() != null) {
            @SuppressWarnings("unchecked")
            R futureSurface = (R) (Object) invokeAsync(kind, message, invocation);
            return futureSurface;
        }
        Objects.requireNonNull(invocation, "invocation");
        requireCallerRuntimeAffinity("invoke actor callables");
        if (inActorExecution()) {
            throw new IllegalStateException(
                    "synchronous actor-callable invocation from an actor turn is forbidden; "
                            + "use mailbox-oriented actor composition");
        }

        IsolatePolicy policy = defaultSpawnPolicy();
        CompletableFuture<R> completion = new CompletableFuture<>();
        ActorRef<M> ref = spawnInternal(
                kind,
                policy,
                factoryContext -> (delivered, turnContext) -> {
                    try {
                        @SuppressWarnings("unchecked")
                        R frozen = (R) freeze(invocation.run(delivered, turnContext));
                        completion.complete(frozen);
                    } catch (VirtualMachineError fatal) {
                        completion.completeExceptionally(fatal);
                        throw fatal;
                    } catch (ThreadDeath fatal) {
                        completion.completeExceptionally(fatal);
                        throw fatal;
                    } catch (LinkageError fatal) {
                        completion.completeExceptionally(fatal);
                        throw fatal;
                    } catch (Throwable failure) {
                        completion.completeExceptionally(failure);
                    } finally {
                        turnContext.self().stop();
                    }
                },
                true);

        try {
            try {
                send(ref, message);
            } catch (ActorTerminatedException terminatedBeforeDelivery) {
                Throwable startupFailure = ref.terminationCause.get();
                if (startupFailure instanceof RuntimeException runtime) throw runtime;
                if (startupFailure instanceof Error error) throw error;
                if (startupFailure != null) throw new RuntimeException(startupFailure);
                throw terminatedBeforeDelivery;
            }
            long timeoutNanos;
            try {
                timeoutNanos = policy.maxWallTime().toNanos();
            } catch (ArithmeticException overflow) {
                timeoutNanos = Long.MAX_VALUE;
            }
            if (timeoutNanos <= 0) timeoutNanos = 1;

            long startedAt = System.nanoTime();
            R result = null;
            ExecutionException executionFailure = null;
            try {
                result = completion.get(timeoutNanos, TimeUnit.NANOSECONDS);
            } catch (ExecutionException failed) {
                // The guest result/failure is not externally complete until
                // the one-shot actor has unwound and its carrier has crossed
                // back out of TurnExecutor (TruffleContext.leave in OresVM).
                executionFailure = failed;
            }

            long elapsed = Math.max(0L, System.nanoTime() - startedAt);
            long remaining = timeoutNanos == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : Math.max(0L, timeoutNanos - elapsed);
            if (!ref.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                throw new TimeoutException(
                        "actor callable completed its guest result but did not leave its carrier before the wall-time deadline");
            }

            if (executionFailure != null) throw executionFailure;
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("actor callable invocation interrupted");
        } catch (TimeoutException timedOut) {
            throw new IllegalStateException(
                    "actor callable exceeded max wall time " + policy.maxWallTime(),
                    timedOut);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        } finally {
            if (ref.isAlive()) {
                try {
                    stop(ref);
                } catch (IllegalStateException alreadyStopping) {
                    if (ref.isAlive()) throw alreadyStopping;
                }
            }
        }
    }

    /**
     * Nonblocking actor-call bridge for scheduler-owned language execution.
     * The returned Future completes from the actor mailbox continuation when a
     * suspendable source body finishes. Host callers that require synchronous
     * actor invocation should continue to use invoke(...).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <M, R> OresFuture<R> invokeAsync(
            ActorKind kind,
            M message,
            Invocation<M, R> invocation) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(invocation, "invocation");
        requireCallerRuntimeAffinity("invoke actor callables");
        if (inActorExecution()) {
            throw new IllegalStateException(
                    "actor callable invocation from an actor turn is forbidden; "
                            + "use mailbox-oriented actor composition");
        }

        IsolatePolicy policy = defaultSpawnPolicy();

        /*
         * The guest result is deliberately staged separately from the Future
         * exposed to the caller. A one-shot actor may compute its result while
         * its carrier still owns a TruffleContext. Publishing that result here
         * would let an awaiting root frame resume, return from Context.eval(),
         * and close the Polyglot Context before TurnExecutor.execute(...) has
         * crossed its leave boundary.
         *
         * ActorCell.finalizedFuture is settled only after carrierActive is
         * false, scheduled is clear, actor teardown is complete, and the
         * TurnExecutor has returned. The externally visible Future therefore
         * becomes terminal only after that barrier.
         */
        OresFuture<R> staged = new OresFuture<>();
        OresFuture<R> completion = new OresFuture<>();

        ActorRef<M> ref = spawnInternal(
                kind,
                policy,
                factoryContext -> (delivered, turnContext) -> {
                    try {
                        Object result = invocation.run(delivered, turnContext);
                        if (result instanceof OresFuture<?> future) {
                            OresFuture<?> owned =
                                    ownCurrentActorFuture((OresFuture) future);
                            ContinuationTarget target =
                                    captureCurrentContinuationTarget();

                            enqueueOnCompletion(
                                    (OresFuture) owned,
                                    target,
                                    (value, failure) -> {
                                        try {
                                            if (failure != null) {
                                                staged.failFromRuntime(
                                                        OresFuture.unwrap(failure));
                                            } else {
                                                R frozen = (R) freeze(value);
                                                staged.completeFromRuntime(frozen);
                                            }
                                        } catch (Throwable callbackFailure) {
                                            staged.failFromRuntime(callbackFailure);
                                        } finally {
                                            turnContext.self().stop();
                                        }
                                    });
                            return;
                        }

                        R frozen = (R) freeze(result);
                        staged.completeFromRuntime(frozen);
                        turnContext.self().stop();
                    } catch (VirtualMachineError fatal) {
                        staged.failFromRuntime(fatal);
                        throw fatal;
                    } catch (ThreadDeath fatal) {
                        staged.failFromRuntime(fatal);
                        throw fatal;
                    } catch (LinkageError fatal) {
                        staged.failFromRuntime(fatal);
                        throw fatal;
                    } catch (Throwable failure) {
                        staged.failFromRuntime(failure);
                        turnContext.self().stop();
                    }
                },
                true);

        @SuppressWarnings("unchecked")
        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell == null) {
            completion.failFromRuntime(
                    new IllegalStateException(
                            "one-shot actor disappeared before finalization barrier registration"));
            return completion;
        }

        cell.finalizedFuture.whenCompleteRuntime((ignored, barrierFailure) -> {
            if (completion.isDone()) return;

            Object terminal = staged.runtimeTerminalStateOrNull();
            if (terminal != null) {
                Throwable failure = OresFuture.runtimeTerminalFailure(terminal);
                if (failure == null) {
                    completion.completeFromRuntime(
                            (R) OresFuture.runtimeTerminalValue(terminal));
                } else if (failure instanceof CancellationException) {
                    completion.cancel(false);
                } else {
                    completion.failFromRuntime(failure);
                }
                return;
            }

            Throwable termination = ref.terminationCause.get();
            if (termination instanceof CancellationException) {
                completion.cancel(false);
            } else if (termination != null) {
                completion.failFromRuntime(termination);
            } else if (barrierFailure != null) {
                completion.failFromRuntime(OresFuture.unwrap(barrierFailure));
            } else {
                completion.failFromRuntime(
                        new IllegalStateException(
                                "one-shot actor finalized before producing a result"));
            }
        });

        try {
            send(ref, message);
        } catch (Throwable failure) {
            staged.failFromRuntime(failure);
            try {
                if (ref.isAlive()) stop(ref);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
        return completion;
    }

    private void reserveActorSlot() {
        while (true) {
            int current = actorCount.get();
            if (current >= dispatcherConfig.maxActors()) {
                throw new IllegalStateException(
                        "actor runtime limit exceeded: maximum " + dispatcherConfig.maxActors());
            }
            if (actorCount.compareAndSet(current, current + 1)) return;
        }
    }

    private void unregisterActor(ActorCell<?> cell) {
        // Keep the actor discoverable until all runtime-owned teardown that is
        // part of its termination contract has completed. ActorRef.awaitTermination()
        // treats absence from this map as terminal, so removing the actor first
        // would let observers race ahead of creator-owned ActorGroup cleanup.
        Throwable deregistrationFailure = null;
        Set<ActorGroupId> affected = new java.util.HashSet<>();
        synchronized (groupMembershipLock) {
            Set<ActorGroupId> memberships = groupMemberships.get(cell.ref.id());
            if (memberships != null) affected.addAll(memberships);
            Set<ActorGroupId> created = createdGroupsByActor.get(cell.ref.id());
            if (created != null) affected.addAll(created);
            // An explicitly managed worker is not necessarily a cooperating
            // member. The owner needs its independent termination callback.
            ActorGroupId managedBy = cell.managedByGroup.get();
            if (managedBy != null) affected.add(managedBy);
        }
        for (ActorGroupId groupId : affected) {
            ActorGroup group = actorGroups.get(groupId);
            try {
                if (group != null) group.actorTerminated(cell.ref.id());
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable failure) {
                // One broken ActorGroup must not prevent removal from every
                // other group, actor count retirement or done notification.
                if (deregistrationFailure == null) deregistrationFailure = failure;
                else if (deregistrationFailure != failure) {
                    deregistrationFailure.addSuppressed(failure);
                }
            }
        }
        if (actors.remove(cell.ref.id(), cell)) {
            int remaining = actorCount.decrementAndGet();
            if (remaining < 0) {
                actorCount.incrementAndGet();
                throw new IllegalStateException("actor count accounting underflow");
            }
        }
        if (deregistrationFailure != null) {
            if (deregistrationFailure instanceof RuntimeException runtime) throw runtime;
            if (deregistrationFailure instanceof Error error) throw error;
            throw new IllegalStateException(
                    "ActorGroup deregistration failed during actor retirement",
                    deregistrationFailure);
        }
    }

    public <T> SyncCell<T> syncCell(T initialValue) {
        requireCallerRuntimeAffinity("create shared SyncCell values");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        IsolatePolicy callerPolicy = currentActorPolicy();
        if (callerPolicy != null) {
            callerPolicy.require(IsolatePolicy.Capability.SHARED_MEMORY, "SyncCell");
        } else {
            policyCeiling.require(IsolatePolicy.Capability.SHARED_MEMORY, "SyncCell");
        }
        rejectPrivateActorSharedMemoryAccess("SyncCell creation");
        SyncCell<T> cell = new SyncCell<>(initialValue);
        syncCells.add(cell);
        if (closed.get()) {
            cell.close();
            throw new IllegalStateException("actor runtime is closed");
        }
        return cell;
    }

    private boolean enterSyncCell(SyncCell<?> cell) {
        SyncCell<?> held = currentSyncCell.get();
        if (held == null) {
            currentSyncCell.set(cell);
            return true;
        }
        if (held != cell) {
            throw new IllegalStateException(
                    "nested synchronization across different SyncCell values is forbidden; "
                            + "snapshot values first or use one shared cell");
        }
        return false;
    }

    private void exitSyncCell(boolean entered) {
        if (entered) currentSyncCell.remove();
    }

    private void rejectPrivateActorSharedMemoryAccess(String operation) {
        ActorCell<?> current = currentActor.get();
        if (current != null && isPrivateKind(current.kind)) {
            throw new IllegalStateException("private actors cannot access synchronized shared memory via " + operation);
        }
    }

    private void requireSharedActorTurn() {
        ActorCell<?> cell = currentActor.get();
        if (cell == null || cell.kind != ActorKind.SHARED) {
            throw new IllegalStateException("shared state mutation requires a shared actor mailbox turn");
        }
    }

    private void reservePrivateRuntimeBytes(long bytes, ActorId owner, String purpose) {
        synchronized (memoryBudgetLock) {
            long privateBytes = privateMemoryBytes.get();
            long sharedBytes = sharedMemoryBytes.get();
            long total;
            try {
                total = Math.addExact(Math.addExact(privateBytes, sharedBytes), bytes);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(purpose + " aggregate accounting overflow");
            }
            if (total > policyCeiling.maxHeapBytes()) {
                throw new IllegalStateException(purpose + " aggregate runtime limit exceeded for " + owner
                        + ": requested=" + bytes + " privateUsed=" + privateBytes
                        + " sharedUsed=" + sharedBytes + " runtimeLimit=" + policyCeiling.maxHeapBytes());
            }
            privateMemoryBytes.addAndGet(bytes);
        }
    }

    private void releasePrivateRuntimeBytes(long bytes, ActorId owner) {
        if (bytes == 0) return;
        synchronized (memoryBudgetLock) {
            long current = privateMemoryBytes.get();
            if (bytes > current) {
                throw new IllegalStateException(
                        "private actor aggregate memory accounting underflow for " + owner
                                + ": release=" + bytes + " privateUsed=" + current);
            }
            privateMemoryBytes.set(current - bytes);
        }
    }

    private void reserveSharedRuntimeBytes(long bytes, String purpose) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        if (bytes < 0) throw new IllegalArgumentException("shared memory reservation cannot be negative");
        if (bytes == 0) return;
        synchronized (memoryBudgetLock) {
            long privateBytes = privateMemoryBytes.get();
            long sharedBytes = sharedMemoryBytes.get();
            long total;
            try {
                total = Math.addExact(Math.addExact(privateBytes, sharedBytes), bytes);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(purpose + " aggregate accounting overflow");
            }
            if (total > policyCeiling.maxHeapBytes()) {
                throw new IllegalStateException(purpose + " aggregate runtime limit exceeded"
                        + ": requested=" + bytes + " privateUsed=" + privateBytes
                        + " sharedUsed=" + sharedBytes + " runtimeLimit=" + policyCeiling.maxHeapBytes());
            }
            sharedMemoryBytes.addAndGet(bytes);
        }
    }

    private void releaseSharedRuntimeBytes(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("shared memory release cannot be negative");
        if (bytes == 0 || closed.get()) return;
        synchronized (memoryBudgetLock) {
            if (closed.get()) return;
            long current = sharedMemoryBytes.get();
            if (bytes > current) {
                throw new IllegalStateException(
                        "shared actor memory accounting underflow: release=" + bytes
                                + " sharedUsed=" + current);
            }
            sharedMemoryBytes.set(current - bytes);
        }
    }

    private void requireWithinCeiling(IsolatePolicy child) {
        if (!policyCeiling.capabilities().containsAll(child.capabilities())) {
            java.util.Set<IsolatePolicy.Capability> excess = java.util.EnumSet.copyOf(child.capabilities());
            excess.removeAll(policyCeiling.capabilities());
            throw new SecurityException("child actor policy exceeds parent capabilities: " + excess);
        }
        if (child.maxHeapBytes() > policyCeiling.maxHeapBytes()) {
            throw new SecurityException("child actor maxHeapBytes exceeds parent policy");
        }
        if (child.maxMailboxMessages() > policyCeiling.maxMailboxMessages()) {
            throw new SecurityException("child actor mailbox limit exceeds parent policy");
        }
        if (child.maxWallTime().compareTo(policyCeiling.maxWallTime()) > 0) {
            throw new SecurityException("child actor wall-time limit exceeds parent policy");
        }
        if (policyCeiling.adversarial() && !child.adversarial()) {
            throw new SecurityException("child actor cannot weaken an adversarial parent policy");
        }
    }

    public boolean isAlive(ActorRef<?> ref) {
        Objects.requireNonNull(ref);
        if (!ref.ownedBy(this)) return false;
        ActorCell<?> cell = actors.get(ref.id());
        return cell != null && !cell.stopped.get();
    }

    public void stop(ActorRef<?> ref) {
        requireCallerRuntimeAffinity("stop actors");
        Objects.requireNonNull(ref);
        if (!ref.ownedBy(this)) {
            throw new IllegalArgumentException("ActorRef belongs to a different ActorRuntime");
        }
        requireActorLifecycleAuthority(ref, "stop");
        ActorCell<?> caller = currentActor.get();
        if (caller != null
                && caller.kind == ActorKind.UNTRUSTED
                && !caller.ref.id().equals(ref.id())) {
            throw new SecurityException("untrusted actors cannot stop other actors");
        }
        ActorCell<?> cell = actors.get(ref.id());
        if (cell == null) return;

        cell.stop();

        // Only a host/supervisor stop is a synchronization point. Actor turns
        // may stop self/descendants but must never park a bounded carrier while
        // waiting for another actor to finalize.
        if (currentActor.get() != null) return;

        try {
            cell.awaitFinalized(CLOSE_WAIT_NANOS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while waiting for actor " + ref.id() + " to finalize",
                    interrupted);
        }
        if (!cell.finalized()) {
            throw new IllegalStateException(
                    "actor " + ref.id()
                            + " or one of its child actors did not finalize within the stop deadline");
        }
    }

    public boolean cancel(ActorRef<?> ref) {
        return cancel(ref, CancellationMode.STRUCTURED);
    }

    public boolean cancel(ActorRef<?> ref, CancellationMode mode) {
        requireCallerRuntimeAffinity("cancel actors");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(mode, "mode");
        if (mode == CancellationMode.FORCE_ISOLATED) {
            requireSupervisorContext("force-cancel isolated actors");
        }
        if (!ref.ownedBy(this)) {
            throw new IllegalArgumentException(
                    "ActorRef belongs to a different ActorRuntime");
        }
        requireActorLifecycleAuthority(ref, "cancel");

        if (mode == CancellationMode.FORCE_ISOLATED) {
            requireSupervisorContext("force-cancel isolated actors");
            return forceCancelInternal(ref);
        }

        requireActorLifecycleAuthority(ref, "cancel");
        ActorCell<?> cell = actors.get(ref.id());
        if (cell == null || cell.finalized()) return false;

        cell.cancel(new ActorCancelledException(
                ref.id(),
                "actor " + ref.id() + " was cancelled"));
        return true;
    }

    private boolean forceCancelInternal(ActorRef<?> ref) {
        ActorCell<?> cell = actors.get(ref.id());
        if (cell == null || cell.finalized()) return false;

        ForceCancellationAttempt attempt =
                fenceForceCancellationSubtree(cell);
        if (attempt == null) return false;

        final boolean revoked;
        try {
            if (Boolean.TRUE.equals(forceCancellationCallback.get())) {
                throw new IllegalStateException(
                        "nested force-cancellation revocation is forbidden");
            }

            forceCancellationCallback.set(Boolean.TRUE);
            try {
                revoked = forceCancellationHook.revoke(attempt.request);
            } finally {
                forceCancellationCallback.remove();
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            clearForceKillFence(attempt, true);
            throw fatal;
        } catch (LinkageError fatal) {
            clearForceKillFence(attempt, true);
            throw fatal;
        } catch (RuntimeException revocationFailure) {
            clearForceKillFence(attempt, true);
            throw new IllegalStateException(
                    "force-cancellation revoker failed before logical actor teardown; "
                            + "revokers must be atomic and side-effect-free on false/throw",
                    revocationFailure);
        }

        if (!revoked) {
            clearForceKillFence(attempt, true);
            throw new IllegalStateException(
                    "force cancellation requires VM-owned atomic revocation of the complete actor subtree; "
                            + "no logical cancellation/teardown was published");
        }

        try {
            attempt.root.cancel(new ActorCancelledException(
                    ref.id(),
                    "actor " + ref.id() + " was force-cancelled"));
            return true;
        } finally {
            clearForceKillFence(attempt, false);
        }
    }

    private ForceCancellationAttempt fenceForceCancellationSubtree(
            ActorCell<?> root) {
        synchronized (runtimeLifecycleLock) {
            ActorCell<?> current = actors.get(root.ref.id());
            if (current != root || root.finalized()) return null;

            List<ActorCell<?>> cells = new ArrayList<>();
            List<ActorCell<?>> pending = new ArrayList<>();
            Set<ActorId> seen = new LinkedHashSet<>();
            pending.add(root);

            for (int i = 0; i < pending.size(); i++) {
                ActorCell<?> cell = pending.get(i);
                if (!seen.add(cell.ref.id())) continue;
                if (cell.forceKillFenced) {
                    throw new IllegalStateException(
                            "force cancellation is already in progress for actor "
                                    + cell.ref.id());
                }
                cells.add(cell);
                pending.addAll(List.copyOf(cell.children));
            }

            for (ActorCell<?> cell : cells) {
                cell.forceKillFenced = true;
            }

            List<IsolationTarget> targets = cells.stream()
                    .map(cell -> new IsolationTarget(
                            cell.ref.id(),
                            cell.executionDomain))
                    .toList();

            return new ForceCancellationAttempt(
                    root,
                    cells,
                    new ForceCancellationRequest(
                            root.ref.id(),
                            targets));
        }
    }

    private void clearForceKillFence(
            ForceCancellationAttempt attempt,
            boolean resume) {
        List<ActorCell<?>> resumeCells = new ArrayList<>();

        synchronized (runtimeLifecycleLock) {
            for (ActorCell<?> cell : attempt.cells) {
                cell.forceKillFenced = false;
                if (resume
                        && !cell.stopped.get()
                        && !cell.finalized()
                        && cell.hasQueuedWork()) {
                    resumeCells.add(cell);
                }
            }
        }

        for (ActorCell<?> cell : resumeCells) {
            cell.schedule();
        }
    }

    private ActorTerminatedException terminated(ActorRef<?> ref) {
        return new ActorTerminatedException(ref.id(), ref.kind(), ref.terminationCause.get());
    }

    /** Non-cancellable observer over all mail accepted before this call.
     * This is a processed-message barrier, not an actor termination barrier.
     * Self-drain of unprocessed mail fails closed to prevent a scheduling
     * cycle; the admission snapshot and self-caller guard are atomic. */
    public OresFuture<Void> drain(ActorRef<?> ref) {
        requireCallerRuntimeAffinity("drain actor messages");
        Objects.requireNonNull(ref, "ref");
        if (!ref.ownedBy(this)) {
            throw new ActorInvalidReferenceException(
                    "ActorRef belongs to a different ActorRuntime");
        }
        ActorCell<?> cell = actors.get(ref.id());
        if (cell == null) return OresFuture.failed(terminated(ref));
        return cell.drainAcceptedMessages();
    }

    /** Graceful stop seals new mail, drains prior admissions, then terminates.
     * Never blocks a carrier: done is resolved after physical finalization. */
    public OresFuture<Void> gracefulStop(ActorRef<?> ref) {
        requireCallerRuntimeAffinity("gracefully stop actors");
        Objects.requireNonNull(ref, "ref");
        if (!ref.ownedBy(this)) {
            throw new ActorInvalidReferenceException(
                    "ActorRef belongs to a different ActorRuntime");
        }
        requireActorLifecycleAuthority(ref, "gracefulStop");
        ActorCell<?> cell = actors.get(ref.id());
        if (cell == null) return ref.done();
        final OresFuture<Void> barrier;
        synchronized (cell.lifecycleLock) {
            if (cell.stopped.get() || cell.finalized || cell.sealing) return ref.done();
            cell.sealing = true;
            // Reserve a single shutdown waiter even when public observers hit
            // their per-actor quota. Repeated gracefulStop calls reuse ref.done().
            barrier = cell.drainAcceptedMessages(true);
        }
        barrier.whenCompleteRuntime((ignored, failure) -> {
            if (failure == null) cell.stop();
        });
        cell.schedule();
        return ref.done();
    }

    @SuppressWarnings("unchecked")
    public <M> void send(ActorRef<M> ref, M message) {
        requireCallerRuntimeAffinity("send messages");
        if (currentActorKind() == ActorKind.UNTRUSTED) {
            throw new SecurityException("untrusted actors cannot send actor messages");
        }
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        Objects.requireNonNull(ref);
        if (!ref.ownedBy(this)) {
            throw new ActorInvalidReferenceException(
                    "ActorRef belongs to a different ActorRuntime");
        }
        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell == null || cell.stopped.get()) throw terminated(ref);
        synchronized (cell.lifecycleLock) {
            if (cell.stopped.get() || cell.finalized) throw terminated(ref);
            if (cell.sealing) throw new ActorSealedException(ref.id());
            if (!cell.reserveMailboxSlot()) {
                throw new ActorMailboxFullException(ref.id());
            }
        }
        boolean mailboxSlotTransferred = false;
        try {
        validateMessageGraph(message);
        if (cell.kind == ActorKind.UNTRUSTED) {
            rejectUntrustedInboundCapabilities(message, new IdentityHashMap<>(), 0);
        }
        requireMutexTransport(cell, message, new IdentityHashMap<>(), 0);
        requireOwnedActorRefs(message, new IdentityHashMap<>(), 0);
        long runtimeRemaining = Math.max(0L, policyCeiling.maxHeapBytes() - actorMemoryBytes());
        if (cell.kind == ActorKind.SHARED) {
            requireOwnedSharedHandles(message, new IdentityHashMap<>(), 0);
            long actorRemaining = Math.max(0L, cell.policy.maxHeapBytes() - cell.sharedMailboxBytes.get());
            long allowed = Math.min(actorRemaining, runtimeRemaining);
            try {
                estimateSharedTransportBytes(message, new IdentityHashMap<>(), 0, allowed);
            } catch (IllegalStateException tooLarge) {
                throw new IllegalStateException(
                        "shared actor mailbox memory limit exceeded for " + ref.id() + ": " + tooLarge.getMessage(),
                        tooLarge);
            }
        } else {
            long actorRemaining = cell.memorySlice.remainingBytes();
            try {
                estimatePrivateTransportBytes(message, new IdentityHashMap<>(), 0, actorRemaining);
            } catch (IllegalStateException tooLarge) {
                throw new IllegalStateException(
                        "private actor mailbox limit exceeded for " + ref.id() + ": " + tooLarge.getMessage(),
                        tooLarge);
            }
            try {
                estimatePrivateTransportBytes(message, new IdentityHashMap<>(), 0, runtimeRemaining);
            } catch (IllegalStateException aggregateExceeded) {
                throw new IllegalStateException(
                        "private actor aggregate runtime limit exceeded for " + ref.id()
                                + ": " + aggregateExceeded.getMessage(),
                        aggregateExceeded);
            }
        }

        if (closed.get()) throw new IllegalStateException("actor runtime is closed");

        Object prepared = isPrivateKind(cell.kind) ? isolateCopy(message) : freezeForTransport(message);
        Runnable release;
        if (isPrivateKind(cell.kind)) {
            MemoryReservation reservation;
            try {
                reservation = cell.memorySlice.reserveMailbox(prepared);
            } catch (IllegalStateException exceeded) {
                throw new IllegalStateException(
                        "private actor mailbox limit exceeded for " + ref.id() + ": " + exceeded.getMessage(),
                        exceeded);
            }
            release = reservation::close;
        } else {
            long bytes = estimateSharedMailboxBytes(prepared, new IdentityHashMap<>(), 0);
            cell.reserveSharedMailbox(bytes);
            release = () -> cell.releaseSharedMailbox(bytes);
        }

        ActorId sender = currentActorId().orElse(null);
        MessageEnvelope envelope = null;
        List<OresMutex.Shared<?>> sharedMutexReservations = List.of();
        boolean admitted = false;
        try {
            synchronized (runtimeLifecycleLock) {
                if (closed.get()) throw new IllegalStateException("actor runtime is closed");
                if (cell.kind == ActorKind.SHARED) {
                    sharedMutexReservations = reserveSharedMutexBindings(prepared);
                }
                synchronized (cell.lifecycleLock) {
                    if (cell.stopped.get() || cell.finalized) {
                        throw terminated(ref);
                    }
                    if (cell.sealing) {
                        throw new ActorSealedException(ref.id());
                    }
                    if (cell.forceKillFenced) {
                        throw new IllegalStateException(
                                "actor " + ref.id()
                                        + " is fenced while force cancellation is pending");
                    }
                    long sequence = cell.nextUserMessageSequence;
                    long nextSequence;
                    try {
                        nextSequence = Math.addExact(sequence, 1L);
                    } catch (ArithmeticException exhausted) {
                        throw new IllegalStateException(
                                "actor mailbox sequence exhausted for " + ref.id(),
                                exhausted);
                    }
                    envelope = MessageEnvelope.message(
                            prepared, release, sender, sequence);
                    if (!cell.mailbox.tryWrite(envelope)) {
                        throw new ActorMailboxFullException(ref.id());
                    }
                    cell.nextUserMessageSequence = nextSequence;
                    mailboxSlotTransferred = true;
                    admitted = true;
                    commitSharedMutexBindings(sharedMutexReservations);
                }
            }
        } finally {
            if (!admitted) {
                abortSharedMutexBindings(sharedMutexReservations);
                if (envelope != null) envelope.close();
                else release.run();
            }
        }
        cell.schedule();
        } finally {
            if (!mailboxSlotTransferred) cell.releaseMailboxSlot(false);
        }
    }

    /**
     * Actor cancellation/quota checkpoint used at compiler-injected VM
     * safepoints.
     *
     * <p>This method deliberately does <strong>not</strong> call
     * {@link Thread#yield()} and does not claim to suspend the actor. An OS
     * thread scheduling hint cannot preserve an Ores continuation and does not
     * release the actor's logical turn. Resumable preemption is represented
     * explicitly by {@link OresScheduler.Cooperate} from a heap-owned
     * {@link OresScheduler.Task} state machine.</p>
     *
     * <p>Therefore a source line is never a preemption boundary. A caller that
     * is not continuation-lowered may only observe cancellation/quota checks at
     * this point; it must keep running until a real resumable boundary.</p>
     */
    public void schedulerSafepoint() {
        if (closed.get()) throw new ActorCancellationSignal("actor runtime is closing");
        ActorCell<?> cell = currentActor.get();
        if (cell != null && cell.watchdogLease != null) {
            cell.watchdogLease.checkpoint();
        }
        if (cell != null && cell.stopped.get() && !cell.endRequested
                && !(cell.sourceStopCleanupActive
                        && OresScheduler.cancellationCleanupTurn())) {
            // An authenticated SourceTask unwind continues to need the
            // compiler's ordinary safepoints, but STOP may never resurrect
            // user mail, fresh tasks, or an arbitrary guest continuation.
            throw new ActorCancellationSignal("actor execution stopped");
        }
        if (cell != null && cell.kind == ActorKind.UNTRUSTED) {
            UntrustedLogicalQuota logicalQuota = CURRENT_UNTRUSTED_QUOTA.get();
            if (logicalQuota != null) {
                if (!logicalQuota.ownedBy(this, cell.ref.id())) {
                    throw new SecurityException(
                            "untrusted source-task quota escaped its actor");
                }
                logicalQuota.chargeSafepoint();
                return;
            }
            // Once a logical cohort exists, an unrelated synchronous mailbox
            // turn cannot consume a fresh physical quota in parallel. The
            // compiler must run its continuation within the owning task or
            // wait until that cohort is fully retired.
            if (cell.activeUntrustedSourceQuota != null) {
                throw new SecurityException(
                        "untrusted actor safepoint outside active logical source-task quota");
            }
            cell.untrustedSafepoints++;
            if (--cell.untrustedFuelRemaining < 0) {
                throw new UntrustedActorQuotaExceededException(
                        UntrustedActorQuotaExceededException.Resource.FUEL,
                        "untrusted actor exhausted its execution fuel");
            }
            long wallBudget = cell.policy.maxWallTime().toNanos();
            if (System.nanoTime() - cell.turnStartedWallNanos > wallBudget) {
                throw new UntrustedActorQuotaExceededException(
                        UntrustedActorQuotaExceededException.Resource.WALL_TIME,
                        "untrusted actor exceeded its wall-time budget");
            }
            long cpuNow = currentThreadCpuNanos();
            if (cpuNow >= 0 && cell.turnStartedCpuNanos >= 0
                    && cpuNow - cell.turnStartedCpuNanos > wallBudget) {
                throw new UntrustedActorQuotaExceededException(
                        UntrustedActorQuotaExceededException.Resource.CPU_TIME,
                        "untrusted actor exceeded its CPU-time budget");
            }
        }

        // Carrier identity is not actor identity. Carrier interruption/yield is
        // never actor cancellation or actor preemption. Structured cancellation
        // is represented by ActorCell/runtime state above. Non-cooperative
        // untrusted termination belongs to the host-owned revocable isolate
        // boundary used by FORCE_ISOLATED.
    }

    private static long currentThreadCpuNanos() {
        if (!THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported()
                || !THREAD_MX_BEAN.isThreadCpuTimeEnabled()) {
            return -1L;
        }
        return THREAD_MX_BEAN.getCurrentThreadCpuTime();
    }

    /**
     * Resource debt belongs to one logical UNTRUSTED source task tree.
     * No physical carrier identity, await, or cooperate may reinitialize it.
     *
     * <p>All segments run under the owning actor's single-writer mailbox lease.
     * A second simultaneous segment is rejected rather than racing accounting.
     * CPU use is accumulated per carrier segment; comparing CPU readings from
     * two different carrier threads directly is invalid.</p>
     */
    static final class UntrustedLogicalQuota {
        private final ActorRuntime ownerRuntime;
        private final ActorId ownerActor;
        private final long wallBudgetNanos;
        private final long startedNanos;
        private final LongSupplier wallClock;
        private final LongSupplier cpuClock;
        private long fuelRemaining;
        private long cpuUsedNanos;
        private long segmentCpuStarted = -1L;
        private boolean segmentActive;

        // Test-only deterministic constructor; production always binds owner.
        UntrustedLogicalQuota(
                long wallBudgetNanos,
                long fuelRemaining,
                long startedNanos,
                long initialCpuNanos,
                LongSupplier wallClock,
                LongSupplier cpuClock) {
            this(null, null, wallBudgetNanos, fuelRemaining, startedNanos,
                    initialCpuNanos, wallClock, cpuClock);
        }

        UntrustedLogicalQuota(
                ActorRuntime ownerRuntime,
                ActorId ownerActor,
                long wallBudgetNanos,
                long fuelRemaining,
                long startedNanos,
                long initialCpuNanos,
                LongSupplier wallClock,
                LongSupplier cpuClock) {
            if (wallBudgetNanos <= 0 || fuelRemaining < 0 || initialCpuNanos < 0) {
                throw new IllegalArgumentException("invalid untrusted logical quota");
            }
            this.ownerRuntime = ownerRuntime;
            this.ownerActor = ownerActor;
            this.wallBudgetNanos = wallBudgetNanos;
            this.fuelRemaining = fuelRemaining;
            this.startedNanos = startedNanos;
            this.cpuUsedNanos = initialCpuNanos;
            this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
            this.cpuClock = Objects.requireNonNull(cpuClock, "cpuClock");
        }

        boolean ownedBy(ActorRuntime runtime, ActorId actor) {
            return ownerRuntime == runtime && Objects.equals(ownerActor, actor);
        }

        /** Remaining cohort wall time, including elapsed suspended time. */
        synchronized long remainingWallNanos() {
            long elapsed = wallClock.getAsLong() - startedNanos;
            // Overflow, an already-expired quota or a late child all expire
            // immediately. Never create another full budget for a child.
            if (elapsed < 0 || elapsed >= wallBudgetNanos) return 0L;
            return wallBudgetNanos - elapsed;
        }

        synchronized void enter() {
            if (ownerRuntime != null) {
                ActorExecutionContext context = CURRENT_ACTOR_EXECUTION.get();
                if (context == null || context.runtime() != ownerRuntime
                        || !context.actorId().equals(ownerActor)) {
                    throw new SecurityException(
                            "untrusted source-task quota entered outside its actor");
                }
            }
            if (segmentActive) {
                throw new IllegalStateException(
                        "untrusted task tried overlapping carrier segments");
            }
            segmentCpuStarted = cpuClock.getAsLong();
            segmentActive = true;
        }

        synchronized void chargeSafepoint() {
            requireActive();
            if (fuelRemaining == 0L) {
                throw new UntrustedActorQuotaExceededException(
                        UntrustedActorQuotaExceededException.Resource.FUEL,
                        "untrusted logical task exhausted execution fuel");
            }
            fuelRemaining--;
            check();
        }

        synchronized void check() {
            requireActive();
            long elapsed = wallClock.getAsLong() - startedNanos;
            // Fail closed on a monotonic clock overflow instead of treating
            // overflow as a new unspent deadline.
            if (elapsed < 0 || elapsed > wallBudgetNanos) {
                throw new UntrustedActorQuotaExceededException(
                        UntrustedActorQuotaExceededException.Resource.WALL_TIME,
                        "untrusted logical task exceeded wall-time budget");
            }
            long cpu = accruedCpu();
            if (cpu > wallBudgetNanos) {
                throw new UntrustedActorQuotaExceededException(
                        UntrustedActorQuotaExceededException.Resource.CPU_TIME,
                        "untrusted logical task exceeded CPU-time budget");
            }
        }

        synchronized void leave() {
            requireActive();
            try {
                long now = cpuClock.getAsLong();
                if (segmentCpuStarted >= 0 && now >= segmentCpuStarted) {
                    cpuUsedNanos = saturatingSum(cpuUsedNanos, now - segmentCpuStarted);
                }
            } finally {
                // Even an unexpected JVM CPU-counter failure must not strand
                // the quota's execution lease on the old carrier.
                segmentCpuStarted = -1L;
                segmentActive = false;
            }
        }

        private long accruedCpu() {
            long current = cpuClock.getAsLong();
            if (segmentCpuStarted < 0 || current < segmentCpuStarted) {
                return cpuUsedNanos; // CPU clock unavailable: fuel/wall still enforced.
            }
            return saturatingSum(cpuUsedNanos, current - segmentCpuStarted);
        }

        private void requireActive() {
            if (!segmentActive) {
                throw new IllegalStateException(
                        "untrusted logical quota used outside carrier segment");
            }
        }

        private static long saturatingSum(long a, long b) {
            return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
        }
    }

    private static long untrustedFuelBudget(IsolatePolicy policy) {
        long millis = Math.max(1L, policy.maxWallTime().toMillis());
        try {
            return Math.max(
                    UNTRUSTED_MIN_FUEL,
                    Math.multiplyExact(millis, UNTRUSTED_FUEL_PER_MILLI));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    static final class ActorGroupEventReservation implements AutoCloseable {
        private final AtomicReference<ActorRuntime> owner;
        private final long bytes;

        private ActorGroupEventReservation(ActorRuntime owner, long bytes) {
            this.owner = new AtomicReference<>(Objects.requireNonNull(owner, "owner"));
            if (bytes < 0) throw new IllegalArgumentException("bytes cannot be negative");
            this.bytes = bytes;
        }

        long bytes() { return bytes; }

        @Override
        public void close() {
            ActorRuntime runtime = owner.getAndSet(null);
            if (runtime != null) runtime.releaseSharedRuntimeBytes(bytes);
        }
    }

    record FrozenActorGroupEventPayload(
            Object value,
            ActorGroupEventReservation reservation) {
        FrozenActorGroupEventPayload {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(reservation, "reservation");
        }
    }

    /**
     * Freeze a data-only ActorGroup event payload once and reserve its retained
     * bus memory against the same OresVM aggregate budget used by actor
     * mailboxes/shared runtime state. A bounded preflight runs before the deep
     * copy so an oversized publication cannot allocate its full immutable copy
     * before policy admission.
     */
    FrozenActorGroupEventPayload freezeActorGroupEventPayload(Object value) {
        requireCallerRuntimeAffinity("publish ActorGroup event payloads");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        Objects.requireNonNull(value, "Oreslang events cannot carry null; use Option<T>");

        validateMessageGraph(value);
        rejectDataFreezeCapabilities(value, new IdentityHashMap<>(), 0);

        IsolatePolicy caller = currentActorPolicy();
        long senderLimit =
                caller == null ? policyCeiling.maxHeapBytes() : caller.maxHeapBytes();
        long runtimeRemaining =
                Math.max(0L, policyCeiling.maxHeapBytes() - actorMemoryBytes());
        long preflightLimit = Math.min(senderLimit, runtimeRemaining);
        long estimatedBytes = estimateFrozenBytes(value);
        if (estimatedBytes > preflightLimit) {
            throw new IllegalStateException(
                    "ActorGroup event payload exceeds available memory policy: estimated="
                            + estimatedBytes + " senderLimit=" + senderLimit
                            + " runtimeRemaining=" + runtimeRemaining);
        }

        Object frozen = freeze(value);
        long bytes = estimateFrozenBytes(frozen);
        if (bytes > senderLimit) {
            throw new IllegalStateException(
                    "ActorGroup event payload exceeds sender memory policy: bytes="
                            + bytes + " limit=" + senderLimit);
        }

        reserveSharedRuntimeBytes(bytes, "ActorGroup event payload");
        return new FrozenActorGroupEventPayload(
                frozen,
                new ActorGroupEventReservation(this, bytes));
    }

    @SuppressWarnings("unchecked")
    public <T> Shared<T> shareReadonly(T value) {
        requireCallerRuntimeAffinity("share readonly values");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        IsolatePolicy callerPolicy = currentActorPolicy();
        if (callerPolicy != null) {
            callerPolicy.require(IsolatePolicy.Capability.ACTOR_SHARE_READONLY, "shareReadonly");
        } else {
            policyCeiling.require(IsolatePolicy.Capability.ACTOR_SHARE_READONLY, "shareReadonly");
        }
        rejectPrivateActorSharedMemoryAccess("shareReadonly");
        requireOwnedSharedHandles(value, new IdentityHashMap<>(), 0);
        Object frozen = freeze(value);
        rejectSharedMutableHandles(frozen, new IdentityHashMap<>(), 0);
        long bytes = estimateFrozenBytes(frozen);
        reserveSharedRuntimeBytes(bytes, "shared readonly value");
        Shared<T> shared = new Shared<>((T) frozen, bytes);
        sharedValues.add(shared);
        if (closed.get()) {
            shared.closeFromRuntime();
            throw new IllegalStateException("actor runtime is closed");
        }
        return shared;
    }

    private void requireMutexTransport(
            ActorCell<?> target,
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)
                || value instanceof ActorRuntime.ActorRef<?>
                || value instanceof ActorRuntime.ActorGroupJoinCapability) return;
        if (value instanceof ChannelRuntime.Channel<?>
                || value instanceof ChannelRuntime.SelectCase
                || value instanceof ChannelRuntime.SelectSet) {
            throw new IllegalArgumentException(
                    "Channel/SelectSet capabilities are execution-domain local and cannot cross actor mailboxes; "
                            + "send data through ActorRef/mailbox transport instead");
        }
        if (value instanceof OresMutex.Local<?>) {
            throw new IllegalArgumentException("Mutex<T> is actor-local state and cannot cross actor mailboxes");
        }
        if (value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("MutexGuard<T> is lexical and cannot cross actor mailboxes");
        }
        if (value instanceof OresMutex.Shared<?> sharedMutex) {
            if (target.kind != ActorKind.SHARED) {
                throw new SecurityException("private actors cannot receive SharedMutex<T>");
            }
            ActorKind senderKind = currentActorKind();
            if (senderKind != null && isPrivateKind(senderKind)) {
                throw new SecurityException("private actors cannot send SharedMutex<T>");
            }
            IsolatePolicy senderPolicy = currentActorPolicy();
            if (senderPolicy != null) {
                senderPolicy.require(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex actor send");
            } else {
                policyCeiling.require(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex host send");
            }
            target.policy.require(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex actor receive");
            sharedMutex.inspectForTransport(payload ->
                    requireSharedMutexPayloadSafe(
                            payload,
                            new IdentityHashMap<>(),
                            depth + 1));
            return;
        }
        if (value instanceof Shared<?> shared) {
            requireMutexTransport(target, shared.value(), visiting, depth + 1);
            return;
        }
        if (value instanceof SyncCell<?>) return;
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) requireMutexTransport(target, item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) requireMutexTransport(target, item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    requireMutexTransport(target, entry.getKey(), visiting, depth + 1);
                    requireMutexTransport(target, entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    requireMutexTransport(target, Array.get(value, i), visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private void requireSharedMutexPayloadSafe(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)) return;

        if (value instanceof ActorRuntime.ActorRef<?> ref) {
            if (!ref.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "SharedMutex payload contains ActorRef from another ActorRuntime");
            }
            return;
        }
        if (value instanceof Shared<?> shared) {
            if (!shared.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "SharedMutex payload contains Shared value from another ActorRuntime");
            }
            requireSharedMutexPayloadSafe(shared.value(), visiting, depth + 1);
            return;
        }
        if (value instanceof OresMutex.Shared<?>) {
            throw new IllegalArgumentException(
                    "SharedMutex payload cannot contain another SharedMutex; nested shared locks are not transport-safe until recursive lock-order semantics are defined");
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException(
                    "SharedMutex payload cannot contain actor-local mutex state");
        }
        if (value instanceof SyncCell<?>) {
            throw new IllegalArgumentException(
                    "SharedMutex payload cannot contain SyncCell writable shared state");
        }
        if (value instanceof java.util.concurrent.CompletionStage<?>) {
            throw new IllegalArgumentException(
                    "SharedMutex payload cannot contain pending/asynchronous computation state");
        }

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic SharedMutex payload is not runtime-shared-safe");
        }
        try {
            if (value instanceof OresMutex.SharedState aggregate) {
                for (Object child : aggregate.sharedStateChildren()) {
                    requireSharedMutexPayloadSafe(child, visiting, depth + 1);
                }
                return;
            }
            if (value instanceof List<?> list) {
                for (Object item : list) requireSharedMutexPayloadSafe(item, visiting, depth + 1);
                return;
            }
            if (value instanceof Set<?> set) {
                for (Object item : set) requireSharedMutexPayloadSafe(item, visiting, depth + 1);
                return;
            }
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    requireSharedMutexPayloadSafe(entry.getKey(), visiting, depth + 1);
                    requireSharedMutexPayloadSafe(entry.getValue(), visiting, depth + 1);
                }
                return;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    requireSharedMutexPayloadSafe(Array.get(value, i), visiting, depth + 1);
                }
                return;
            }
            throw new IllegalArgumentException(
                    "SharedMutex payload contains opaque host value of type "
                            + value.getClass().getName());
        } finally {
            visiting.remove(value);
        }
    }

    private void requireOwnedActorRefs(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)) return;
        if (value instanceof ActorRuntime.ActorRef<?> ref) {
            if (!ref.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "ActorRef belongs to a different ActorRuntime; cross-runtime actor channels require an explicit bridge");
            }
            return;
        }
        if (value instanceof ActorRuntime.ActorGroupJoinCapability capability) {
            if (!capability.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "ActorGroup capability belongs to a different ActorRuntime");
            }
            return;
        }
        if (value instanceof Shared<?> shared) {
            requireOwnedActorRefs(shared.value(), visiting, depth + 1);
            return;
        }
        if (value instanceof SyncCell<?>) return;
        if (value instanceof OresMutex.Shared<?> sharedMutex) {
            requireOwnedActorRefs(sharedMutex.transportValue(), visiting, depth + 1);
            return;
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) requireOwnedActorRefs(item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) requireOwnedActorRefs(item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    requireOwnedActorRefs(entry.getKey(), visiting, depth + 1);
                    requireOwnedActorRefs(entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    requireOwnedActorRefs(Array.get(value, i), visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private void requireOwnedSharedHandles(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)) return;
        if (value instanceof ActorRuntime.ActorRef<?> ref) {
            if (!ref.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "ActorRef belongs to a different ActorRuntime; cross-runtime actor channels require an explicit bridge");
            }
            return;
        }
        if (value instanceof ActorRuntime.ActorGroupJoinCapability capability) {
            if (!capability.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "ActorGroup capability belongs to a different ActorRuntime");
            }
            return;
        }
        if (value instanceof Shared<?> shared) {
            if (!shared.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "Shared value belongs to a different ActorRuntime; copy/freeze it into the destination runtime");
            }
            shared.value();
            return;
        }
        if (value instanceof SyncCell<?> cell) {
            if (!cell.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "SyncCell belongs to a different ActorRuntime and cannot cross shared-memory domains");
            }
            if (cell.closed()) throw new IllegalArgumentException("SyncCell is closed");
            return;
        }
        if (value instanceof OresMutex.Shared<?>) {
            // Runtime affinity is reserved atomically immediately before mailbox admission.
            return;
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross shared-memory domains");
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) requireOwnedSharedHandles(item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) requireOwnedSharedHandles(item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    requireOwnedSharedHandles(entry.getKey(), visiting, depth + 1);
                    requireOwnedSharedHandles(entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    requireOwnedSharedHandles(Array.get(value, i), visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private List<OresMutex.Shared<?>> reserveSharedMutexBindings(Object value) {
        Set<OresMutex.Shared<?>> unique = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        collectSharedMutexes(value, unique, new IdentityHashMap<>(), 0);

        List<OresMutex.Shared<?>> reserved = new ArrayList<>(unique.size());
        try {
            for (OresMutex.Shared<?> mutex : unique) {
                if (!mutex.reserveRuntimePublication(this)) {
                    throw new IllegalArgumentException(
                            "SharedMutex may cross actor mailboxes only within its owning ActorRuntime");
                }
                reserved.add(mutex);
            }
            return List.copyOf(reserved);
        } catch (RuntimeException | Error failure) {
            abortSharedMutexBindings(reserved);
            throw failure;
        }
    }

    private static void collectSharedMutexes(
            Object value,
            Set<OresMutex.Shared<?>> out,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)
                || value instanceof ActorRuntime.ActorRef<?>
                || value instanceof SyncCell<?>) return;
        if (value instanceof OresMutex.Shared<?> sharedMutex) {
            if (!out.add(sharedMutex)) return;
            if (visiting.put(value, Boolean.TRUE) != null) return;
            try {
                collectSharedMutexes(
                        sharedMutex.transportValue(),
                        out,
                        visiting,
                        depth + 1);
            } finally {
                visiting.remove(value);
            }
            return;
        }
        if (value instanceof Shared<?> shared) {
            collectSharedMutexes(shared.value(), out, visiting, depth + 1);
            return;
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) return;
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) collectSharedMutexes(item, out, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) collectSharedMutexes(item, out, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    collectSharedMutexes(entry.getKey(), out, visiting, depth + 1);
                    collectSharedMutexes(entry.getValue(), out, visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    collectSharedMutexes(Array.get(value, i), out, visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private void commitSharedMutexBindings(List<OresMutex.Shared<?>> reservations) {
        for (OresMutex.Shared<?> mutex : reservations) {
            mutex.commitRuntimePublication(this);
        }
    }

    private void abortSharedMutexBindings(List<OresMutex.Shared<?>> reservations) {
        for (int i = reservations.size() - 1; i >= 0; i--) {
            reservations.get(i).abortRuntimePublication(this);
        }
    }

    private static void rejectUntrustedInboundCapabilities(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value instanceof OresSymbol symbol && !symbol.publicToUntrustedCoreV1()) {
            // Check every nested key and value before admitting untrusted mailbox data.
            throw new SecurityException("Symbol is not visible to untrusted actor");
        }
        if (value instanceof OresKeyedMap<?, ?>) {
            throw new SecurityException(
                    "typed Map values are mutable runtime objects and cannot cross an untrusted actor boundary");
        }
        if (value == null || isScalar(value)) return;
        if (value instanceof ActorRuntime.ActorRef<?>
                || value instanceof ActorRuntime.ActorGroupJoinCapability
                || value instanceof Shared<?>
                || value instanceof SyncCell<?>
                || value instanceof OresMutex.Lock<?>
                || value instanceof OresMutex.Guard<?>) {
            throw new SecurityException(
                    "untrusted actors accept data-only messages; live capabilities are forbidden");
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException(
                    "cyclic values cannot cross an untrusted actor boundary");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    rejectUntrustedInboundCapabilities(item, visiting, depth + 1);
                }
            } else if (value instanceof Set<?> set) {
                for (Object item : set) {
                    rejectUntrustedInboundCapabilities(item, visiting, depth + 1);
                }
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    rejectUntrustedInboundCapabilities(entry.getKey(), visiting, depth + 1);
                    rejectUntrustedInboundCapabilities(entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = java.lang.reflect.Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    rejectUntrustedInboundCapabilities(
                            java.lang.reflect.Array.get(value, i), visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private static void rejectSharedMutableHandles(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value) || value instanceof ActorRuntime.ActorRef<?>) return;
        if (value instanceof ActorRuntime.ActorHandle<?>
                || value instanceof ActorRuntime.ActorControlHandle
                || value instanceof ActorRuntime.ContinuationTarget
                || value instanceof ChannelRuntime.Channel<?>) {
            throw new SecurityException(
                    "execution-domain local capabilities cannot be wrapped as Shared");
        }
        if (value instanceof ActorRuntime.SyncCell<?>) {
            throw new IllegalArgumentException("SyncCell is mutable shared state and cannot be wrapped as Shared");
        }
        if (value instanceof OresMutex.Shared<?>) {
            throw new IllegalArgumentException("SharedMutex is mutable shared state and cannot be wrapped as Shared");
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot be wrapped as Shared");
        }
        if (value instanceof Shared<?> shared) {
            shared.value();
            return;
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot be shared read-only");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) rejectSharedMutableHandles(item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) rejectSharedMutableHandles(item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    rejectSharedMutableHandles(entry.getKey(), visiting, depth + 1);
                    rejectSharedMutableHandles(entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    rejectSharedMutableHandles(Array.get(value, i), visiting, depth + 1);
                }
            } else {
                throw new IllegalArgumentException("value of type " + value.getClass().getName()
                        + " is not a runtime-owned immutable actor value");
            }
        } finally {
            visiting.remove(value);
        }
    }

    private static void validateMessageGraph(Object value) {
        validateMessageGraph(value, new IdentityHashMap<>(), 0, new long[]{0L});
    }

    private static void validateMessageGraph(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth,
            long[] nodes) {
        requireGraphDepth(depth);
        if (++nodes[0] > MAX_MESSAGE_GRAPH_NODES) {
            throw new IllegalArgumentException(
                    "actor message graph exceeds maximum node count " + MAX_MESSAGE_GRAPH_NODES);
        }
        if (value instanceof ActorRuntime.ActorHandle<?>
                || value instanceof ActorRuntime.ActorControlHandle
                || value instanceof ActorRuntime.ContinuationTarget) {
            throw new SecurityException(
                    "actor lifecycle capabilities are execution-domain local and cannot cross actor mailboxes");
        }
        if (value instanceof ActorRuntime.ActorGroup
                || value instanceof ActorEventBus
                || value instanceof ActorEventBus.Subscription<?>
                || value instanceof ChannelRuntime.SelectSet
                || value instanceof ChannelRuntime.SelectCase) {
            throw new SecurityException(
                    "runtime coordination handles are execution-domain local and cannot cross actor mailboxes");
        }
        if (value instanceof ChannelRuntime.Channel<?>) {
            throw new IllegalArgumentException(
                    "channel capability is execution-domain local and cannot cross actor mailboxes");
        }
        if (value instanceof OresKeyedMap<?, ?>) {
            throw new IllegalArgumentException(
                    "typed Map values cannot cross actor boundaries until isolated transport is supported");
        }
        if (value instanceof HttpResponseDraft) {
            throw new SecurityException(
                    "HTTP response drafts are single-use, request-scoped capabilities; "
                    + "they cannot cross actor mailboxes");
        }
        if (value == null || isScalar(value)
                || value instanceof ActorRuntime.ActorRef<?>
                || value instanceof Shared<?>
                || value instanceof SyncCell<?>
                || value instanceof OresMutex.Shared<?>) {
            return;
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            return; // transport-specific validation produces the semantic error.
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                requireGraphNodeCapacity(nodes[0], list.size());
                for (Object item : list) validateMessageGraph(item, visiting, depth + 1, nodes);
            } else if (value instanceof Set<?> set) {
                requireGraphNodeCapacity(nodes[0], set.size());
                for (Object item : set) validateMessageGraph(item, visiting, depth + 1, nodes);
            } else if (value instanceof Map<?, ?> map) {
                requireGraphNodeCapacity(nodes[0], Math.multiplyExact((long) map.size(), 2L));
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    validateMessageGraph(entry.getKey(), visiting, depth + 1, nodes);
                    validateMessageGraph(entry.getValue(), visiting, depth + 1, nodes);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                requireGraphNodeCapacity(nodes[0], length);
                for (int i = 0; i < length; i++) {
                    validateMessageGraph(Array.get(value, i), visiting, depth + 1, nodes);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private static void requireGraphNodeCapacity(long alreadyVisited, long additionalNodes) {
        if (additionalNodes < 0
                || additionalNodes > (long) MAX_MESSAGE_GRAPH_NODES - alreadyVisited) {
            throw new IllegalArgumentException(
                    "actor message graph exceeds maximum node count " + MAX_MESSAGE_GRAPH_NODES);
        }
    }

    private static void requireGraphDepth(int depth) {
        if (depth > MAX_MESSAGE_GRAPH_DEPTH) {
            throw new IllegalArgumentException(
                    "actor message graph exceeds maximum nesting depth " + MAX_MESSAGE_GRAPH_DEPTH);
        }
    }

    /**
     * Converts supported values into a deeply immutable/sendable graph.
     * Unknown host objects are rejected instead of being passed by reference.
     */
    public static Object freeze(Object value) {
        validateMessageGraph(value);
        rejectDataFreezeCapabilities(value, new IdentityHashMap<>(), 0);
        return freeze(value, new IdentityHashMap<>(), 0);
    }

    private static Object freezeForTransport(Object value) {
        validateMessageGraph(value);
        return freeze(value, new IdentityHashMap<>(), 0);
    }

    private static void rejectDataFreezeCapabilities(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)) return;
        if (value instanceof ActorRuntime.ActorRef<?>
                || value instanceof ActorRuntime.ActorGroupJoinCapability
                || value instanceof Shared<?>
                || value instanceof SyncCell<?>
                || value instanceof OresMutex.Lock<?>
                || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException(
                    "freeze() accepts data values only; live actor/shared capabilities require explicit actor transport");
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot be frozen");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) rejectDataFreezeCapabilities(item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) rejectDataFreezeCapabilities(item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    rejectDataFreezeCapabilities(entry.getKey(), visiting, depth + 1);
                    rejectDataFreezeCapabilities(entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    rejectDataFreezeCapabilities(Array.get(value, i), visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private static Object freeze(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (isScalar(value)) return value;
        if (value instanceof Shared<?> shared) {
            shared.value();
            return shared;
        }
        if (value instanceof ActorRuntime.ActorRef<?> ref) return ref;
        if (value instanceof ActorRuntime.ActorGroupJoinCapability capability) return capability;
        if (value instanceof ActorRuntime.SyncCell<?> cell) return cell;
        if (value instanceof OresMutex.Shared<?> sharedMutex) return sharedMutex;
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                List<Object> frozen = new ArrayList<>(list.size());
                for (Object item : list) frozen.add(freeze(item, visiting, depth + 1));
                return List.copyOf(frozen);
            }
            if (value instanceof Set<?> set) {
                LinkedHashSet<Object> frozen = new LinkedHashSet<>();
                for (Object item : set) {
                    Object detached = freeze(item, visiting, depth + 1);
                    if (!frozen.add(detached)) {
                        throw new IllegalArgumentException(
                                "duplicate logical Set element during actor freeze");
                    }
                }
                return Collections.unmodifiableSet(frozen);
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> frozen = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object detachedKey = freeze(entry.getKey(), visiting, depth + 1);
                    Object detachedValue = freeze(entry.getValue(), visiting, depth + 1);
                    if (frozen.containsKey(detachedKey)) {
                        throw new IllegalArgumentException(
                                "duplicate logical Map key during actor freeze");
                    }
                    frozen.put(detachedKey, detachedValue);
                }
                return Collections.unmodifiableMap(frozen);
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> frozen = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    frozen.add(freeze(Array.get(value, i), visiting, depth + 1));
                }
                return List.copyOf(frozen);
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    /**
     * Private transport never retains a shared mutable reference. Immutable
     * shared wrappers are unwrapped and copied into the private message graph.
     */
    private static Object isolateCopy(Object value) {
        return isolateCopy(value, new IdentityHashMap<>(), 0);
    }

    private static Object isolateCopy(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (isScalar(value)) return value;
        if (value instanceof ActorRuntime.SyncCell<?>) {
            throw new IllegalArgumentException("private actors cannot receive shared SyncCell values");
        }
        if (value instanceof OresMutex.Shared<?>) {
            throw new IllegalArgumentException("private actors cannot receive SharedMutex<T>");
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }
        if (value instanceof Shared<?> shared) return isolateCopy(shared.value(), visiting, depth + 1);
        if (value instanceof ActorRuntime.ActorRef<?> ref) return ref;
        if (value instanceof ActorRuntime.ActorGroupJoinCapability capability) return capability;

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross private actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                List<Object> copy = new ArrayList<>(list.size());
                for (Object item : list) copy.add(isolateCopy(item, visiting, depth + 1));
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof Set<?> set) {
                LinkedHashSet<Object> copy = new LinkedHashSet<>();
                for (Object item : set) {
                    Object detached = isolateCopy(item, visiting, depth + 1);
                    if (!copy.add(detached)) {
                        throw new IllegalArgumentException(
                                "duplicate logical Set element during private actor copy");
                    }
                }
                return Collections.unmodifiableSet(copy);
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object detachedKey = isolateCopy(entry.getKey(), visiting, depth + 1);
                    Object detachedValue = isolateCopy(entry.getValue(), visiting, depth + 1);
                    if (copy.containsKey(detachedKey)) {
                        throw new IllegalArgumentException(
                                "duplicate logical Map key during private actor copy");
                    }
                    copy.put(detachedKey, detachedValue);
                }
                return Collections.unmodifiableMap(copy);
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> copy = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    copy.add(isolateCopy(Array.get(value, i), visiting, depth + 1));
                }
                return Collections.unmodifiableList(copy);
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    private static long estimateSharedTransportBytes(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth,
            long limit) {
        requireGraphDepth(depth);
        if (limit < 0) throw new IllegalStateException("message exceeds remaining actor memory");

        long scalar = scalarLogicalBytes(value);
        if (scalar >= 0) return requireWithinLimit(scalar, limit);
        if (value instanceof Shared<?>) return requireWithinLimit(48L, limit);
        if (value instanceof ActorRuntime.SyncCell<?>) return requireWithinLimit(64L, limit);
        if (value instanceof OresMutex.Shared<?>) return requireWithinLimit(64L, limit);
        if (value instanceof ActorRuntime.ActorGroupJoinCapability) return requireWithinLimit(48L, limit);
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }
        if (value instanceof ActorRuntime.ActorRef<?>) return requireWithinLimit(48L, limit);

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                long total = requireWithinLimit(containerBase(24L, 8L, list.size()), limit);
                for (Object item : list) {
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(item, visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value instanceof Set<?> set) {
                long total = requireWithinLimit(containerBase(24L, 16L, set.size()), limit);
                for (Object item : set) {
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(item, visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value instanceof Map<?, ?> map) {
                long total = requireWithinLimit(containerBase(24L, 32L, map.size()), limit);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(entry.getKey(), visiting, depth + 1, limit - total),
                            limit);
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(entry.getValue(), visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                long total = requireWithinLimit(containerBase(24L, 8L, length), limit);
                for (int i = 0; i < length; i++) {
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(
                                    Array.get(value, i), visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    private static long estimateSharedMailboxBytes(
            Object value,
            IdentityHashMap<Object, Boolean> seen,
            int depth) {
        requireGraphDepth(depth);
        long scalar = scalarLogicalBytes(value);
        if (scalar >= 0) return scalar;
        if (value instanceof Shared<?>) return 48L;
        if (value instanceof ActorRuntime.SyncCell<?>) return 64L;
        if (value instanceof OresMutex.Shared<?>) return 64L;
        if (value instanceof ActorRuntime.ActorGroupJoinCapability) return 48L;
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }
        if (value instanceof ActorRuntime.ActorRef<?>) return 48L;
        if (seen.put(value, Boolean.TRUE) != null) return 0L;

        long bytes = 24L;
        if (value instanceof List<?> list) {
            bytes = Math.addExact(bytes, 8L * list.size());
            for (Object item : list) {
                bytes = Math.addExact(bytes, estimateSharedMailboxBytes(item, seen, depth + 1));
            }
            return bytes;
        }
        if (value instanceof Set<?> set) {
            bytes = Math.addExact(bytes, 16L * set.size());
            for (Object item : set) {
                bytes = Math.addExact(bytes, estimateSharedMailboxBytes(item, seen, depth + 1));
            }
            return bytes;
        }
        if (value instanceof Map<?, ?> map) {
            bytes = Math.addExact(bytes, 32L * map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                bytes = Math.addExact(bytes, estimateSharedMailboxBytes(entry.getKey(), seen, depth + 1));
                bytes = Math.addExact(bytes, estimateSharedMailboxBytes(entry.getValue(), seen, depth + 1));
            }
            return bytes;
        }
        return 64L;
    }

    /**
     * Validates and estimates a private-actor message before allocating its
     * isolation copy. The walk short-circuits as soon as the destination or
     * parent-runtime budget cannot admit the logical graph.
     */
    private static long estimatePrivateTransportBytes(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth,
            long limit) {
        requireGraphDepth(depth);
        if (limit < 0) throw new IllegalStateException("message exceeds remaining actor memory");

        long scalar = scalarLogicalBytes(value);
        if (scalar >= 0) return requireWithinLimit(scalar, limit);

        if (value instanceof ActorRuntime.SyncCell<?>) {
            throw new IllegalArgumentException("private actors cannot receive shared SyncCell values");
        }
        if (value instanceof Shared<?> shared) {
            return estimatePrivateTransportBytes(shared.value(), visiting, depth + 1, limit);
        }
        if (value instanceof ActorRuntime.ActorRef<?>) return requireWithinLimit(48L, limit);
        if (value instanceof ActorRuntime.ActorGroupJoinCapability) return requireWithinLimit(48L, limit);

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross private actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                long total = requireWithinLimit(containerBase(24L, 8L, list.size()), limit);
                for (Object item : list) {
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(item, visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value instanceof Set<?> set) {
                long total = requireWithinLimit(containerBase(24L, 16L, set.size()), limit);
                for (Object item : set) {
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(item, visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value instanceof Map<?, ?> map) {
                long total = requireWithinLimit(containerBase(24L, 32L, map.size()), limit);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(entry.getKey(), visiting, depth + 1, limit - total),
                            limit);
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(entry.getValue(), visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                long total = requireWithinLimit(containerBase(24L, 8L, length), limit);
                for (int i = 0; i < length; i++) {
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(
                                    Array.get(value, i), visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    private static long scalarLogicalBytes(Object value) {
        if (value == null) return 8L;
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Character || value instanceof Integer || value instanceof Float) return 16L;
        if (value instanceof Long || value instanceof Double) return 24L;
        if (value instanceof BigInteger integer) return 32L + integer.toByteArray().length;
        if (value instanceof BigDecimal decimal) return 48L + decimal.unscaledValue().toByteArray().length;
        if (value instanceof String string) return 40L + (long) string.length() * 2L;
        if (value instanceof OresSymbol symbol) return 56L + symbol.name().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (value instanceof UUID || value instanceof ActorId || value instanceof ActorGroupId) return 40L;
        if (value instanceof Enum<?>) return 24L;
        return -1L;
    }

    private static long containerBase(long header, long perEntry, int count) {
        try {
            return Math.addExact(header, Math.multiplyExact(perEntry, (long) count));
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("actor message size accounting overflow");
        }
    }

    private static long requireWithinLimit(long bytes, long limit) {
        if (bytes > limit) {
            throw new IllegalStateException(
                    "message requires at least " + bytes + " bytes but only " + limit + " remain");
        }
        return bytes;
    }

    private static long addWithinLimit(long left, long right, long limit) {
        long total;
        try {
            total = Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("actor message size accounting overflow");
        }
        return requireWithinLimit(total, limit);
    }

    /**
     * Conservative language-level footprint estimate. This is a quota metric,
     * not a promise about HotSpot/Graal object layout.
     */
    private static long estimateFrozenBytes(Object value) {
        return estimateFrozenBytes(value, new IdentityHashMap<>(), 0);
    }

    private static long estimateFrozenBytes(
            Object value,
            IdentityHashMap<Object, Boolean> seen,
            int depth) {
        requireGraphDepth(depth);
        if (value == null) return 8L;
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Character || value instanceof Integer || value instanceof Float) return 16L;
        if (value instanceof Long || value instanceof Double) return 24L;
        if (value instanceof BigInteger integer) return 32L + integer.toByteArray().length;
        if (value instanceof BigDecimal decimal) return 48L + decimal.unscaledValue().toByteArray().length;
        if (value instanceof String string) return 40L + (long) string.length() * 2L;
        if (value instanceof OresSymbol symbol) return 56L + symbol.name().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (value instanceof UUID || value instanceof ActorId) return 40L;
        if (value instanceof Enum<?>) return 24L;
        if (value instanceof ActorRuntime.ActorRef<?>) return 48L;
        if (value instanceof ActorRuntime.ActorGroupJoinCapability) return 48L;
        if (value instanceof ActorRuntime.SyncCell<?>) return 64L;
        if (value instanceof OresMutex.Shared<?>) return 64L;
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot be frozen");
        }
        if (value instanceof Shared<?> shared) return estimateFrozenBytes(shared.value(), seen, depth + 1);

        if (seen.put(value, Boolean.TRUE) != null) return 0L;

        long bytes = 24L;
        if (value instanceof List<?> list) {
            bytes = Math.addExact(bytes, 8L * list.size());
            for (Object item : list) bytes = Math.addExact(bytes, estimateFrozenBytes(item, seen, depth + 1));
            return bytes;
        }
        if (value instanceof Set<?> set) {
            bytes = Math.addExact(bytes, 16L * set.size());
            for (Object item : set) bytes = Math.addExact(bytes, estimateFrozenBytes(item, seen, depth + 1));
            return bytes;
        }
        if (value instanceof Map<?, ?> map) {
            bytes = Math.addExact(bytes, 32L * map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                bytes = Math.addExact(bytes, estimateFrozenBytes(entry.getKey(), seen, depth + 1));
                bytes = Math.addExact(bytes, estimateFrozenBytes(entry.getValue(), seen, depth + 1));
            }
            return bytes;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            bytes = Math.addExact(bytes, 8L * length);
            for (int i = 0; i < length; i++) {
                bytes = Math.addExact(bytes, estimateFrozenBytes(Array.get(value, i), seen, depth + 1));
            }
            return bytes;
        }
        return 64L;
    }

    private static boolean isScalar(Object value) {
        return value == null || value instanceof String || value instanceof OresSymbol
                || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Enum<?> || value instanceof UUID || value instanceof ActorId
                || value instanceof ActorGroupId;
    }

    /**
     * Phase one of context shutdown. Reject actor creation, new messages and
     * child spawns without executing cancellation callbacks or waiting for
     * worker/guest cleanup. Every live ActorCell is pre-sealed before I/O
     * transport wakeup begins. Subsequent close() still owns carrier teardown.
     */
    void sealShutdownAdmission() {
        requireSupervisorContext("seal ActorRuntime admission");
        final List<ActorCell<?>> snapshot;
        synchronized (runtimeLifecycleLock) {
            closed.set(true);
            snapshot = List.copyOf(actors.values());
        }
        for (ActorCell<?> cell : snapshot) {
            synchronized (cell.lifecycleLock) {
                if (!cell.finalized) cell.sealing = true;
            }
        }
    }

    @Override
    public void close() {
        requireSupervisorContext("close an ActorRuntime");
        sealShutdownAdmission();

        final List<ActorCell<?>> snapshot = List.copyOf(actors.values());
        for (ActorCell<?> cell : snapshot) cell.stop();
        // Distinct from closed CAS: admission may have been sealed before
        // the physical STOP and dispatcher shutdown sequence began.
        final boolean firstDispatcherShutdown =
                dispatchersShutdown.compareAndSet(false, true);
        if (firstDispatcherShutdown && physicalTurnGate != null) physicalTurnGate.close();

        if (firstDispatcherShutdown) {
            // Stop executor carriers so queued work is rejected/removed and
            // blocked host-side executor operations can wake. This interrupt is
            // backend shutdown mechanics only; it is never an actor
            // cancellation identity. Every ActorCell above was already marked
            // stopped through the structured runtime lifecycle.
            privateDispatcher.shutdownNow();
            sharedDispatcher.shutdownNow();
            untrustedDispatcher.shutdownNow();

            // shutdownNow() removes queued tasks without invoking runBatch().
            // Clear those cells' scheduled bits only when no carrier actually
            // entered the TurnExecutor boundary. Active carriers retain the bit
            // until their executor finally exits.
            for (ActorCell<?> cell : snapshot) cell.cancelQueuedScheduleOnShutdown();
        }

        long deadline = System.nanoTime() + CLOSE_WAIT_NANOS;
        boolean interrupted = false;
        List<ActorId> stillRunning = new ArrayList<>();
        for (ActorCell<?> cell : snapshot) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    cell.awaitFinalized(remaining);
                } catch (InterruptedException waitInterrupted) {
                    interrupted = true;
                    break;
                }
            }
            if (!cell.finalized()) stillRunning.add(cell.ref.id());
        }

        boolean dispatchersTerminated = true;
        for (ExecutorService dispatcher : List.of(privateDispatcher, sharedDispatcher, untrustedDispatcher)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                dispatchersTerminated = false;
                break;
            }
            try {
                if (!dispatcher.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    dispatchersTerminated = false;
                    break;
                }
            } catch (InterruptedException waitInterrupted) {
                interrupted = true;
                dispatchersTerminated = false;
                break;
            }
        }

        if (interrupted) Thread.currentThread().interrupt();
        if (!stillRunning.isEmpty() || !dispatchersTerminated || interrupted) {
            if (interrupted) {
                for (ActorCell<?> cell : snapshot) {
                    if (!cell.finalized() && !stillRunning.contains(cell.ref.id())) {
                        stillRunning.add(cell.ref.id());
                    }
                }
            }
            throw new IllegalStateException(
                    "ActorRuntime close did not observe full actor termination/carrier exit: "
                            + stillRunning.size() + " actor(s) still running, dispatchersTerminated="
                            + dispatchersTerminated);
        }
        // Only after physical actor and carrier retirement may we invalidate
        // shared/actor-group registries or erase their resource ownership.
        for (ActorGroup group : List.copyOf(actorGroups.values())) group.closeFromRuntime();
        actorGroups.clear();
        // Supervisor policy images retire only AFTER physical actor/carrier exit
        // and actor-group shutdown, so outstanding CONTROL turns keep their leases.
        RuntimeException policyCleanupFailure = null;
        for (ActorSupervisor supervisor : List.copyOf(supervisors.values())) {
            try {
                supervisor.retireCodeFromRuntime();
            } catch (RuntimeException failure) {
                if (policyCleanupFailure == null) policyCleanupFailure = failure;
                else policyCleanupFailure.addSuppressed(failure);
            }
        }
        for (ActorOutputStream<?> stream : List.copyOf(actorOutputStreams)) {
            stream.closeAndDrainFromRuntime();
        }
        actorOutputStreams.clear();
        for (SyncCell<?> cell : List.copyOf(syncCells)) cell.invalidateFromRuntime();
        syncCells.clear();
        for (Shared<?> shared : List.copyOf(sharedValues)) shared.closeFromRuntime();
        sharedValues.clear();
        sharedMemoryBytes.set(0L);
        actors.clear();
        actorCount.set(0);
        if (policyCleanupFailure != null) throw policyCleanupFailure;
    }

    private ExecutorService dispatcherFor(ActorKind kind) {
        return switch (kind) {
            case PRIVATE -> privateDispatcher;
            case SHARED -> sharedDispatcher;
            case UNTRUSTED -> untrustedDispatcher;
        };
    }

    private static boolean usesNativeCarriers(ExecutorService executor) {
        return executor instanceof NativeCarrierExecutor
                || executor instanceof ActorAffinityDispatcher lanes && lanes.nativeCarriers();
    }

    private static ExecutorService newActorDispatcher(int parallelism, int capacity, String prefix) {
        String mode = System.getProperty("ores.actor.affinity", "lane").trim();
        if (mode.equals("none")) return newDispatcher(parallelism, capacity, prefix);
        if (!mode.equals("lane") && !mode.equals("cpu")) {
            throw new IllegalArgumentException("ores.actor.affinity must be lane, cpu, or none");
        }
        int[] cpus = new int[0];
        if (mode.equals("cpu")) {
            String configured = System.getProperty("ores.actor.cpus", "").trim();
            if (configured.isEmpty()) throw new IllegalArgumentException("CPU affinity requires ores.actor.cpus");
            cpus = java.util.Arrays.stream(configured.split(",", -1))
                    .map(String::trim).mapToInt(Integer::parseInt).toArray();
        }
        return new ActorAffinityDispatcher(parallelism, capacity, cpus,
                lane -> newDispatcher(1, capacity, prefix + "lane-" + lane + "-"));
    }

    private static ExecutorService newDispatcher(
            int parallelism,
            int readyQueueCapacity,
            String threadPrefix) {
        String requested = System.getProperty(CARRIER_BACKEND_PROPERTY, "auto")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (!requested.equals("auto")
                && !requested.equals("native")
                && !requested.equals("java")) {
            throw new IllegalArgumentException(
                    CARRIER_BACKEND_PROPERTY + " must be one of auto, native, java");
        }

        boolean unix = isNativeCarrierPlatform();
        if (!requested.equals("java") && (requested.equals("native") || unix)) {
            if (!unix) {
                throw new IllegalStateException(
                        "native Oreslang carriers currently require Linux or macOS");
            }
            try {
                return new NativeCarrierExecutor(
                        parallelism,
                        parallelism,
                        readyQueueCapacity,
                        threadPrefix);
            } catch (UnsatisfiedLinkError | SecurityException unavailable) {
                if (requested.equals("native")) {
                    throw new IllegalStateException(
                            "native Oreslang carrier backend was required but liboresthread could not be loaded",
                            unavailable);
                }
                // Development portability fallback only. CI and production can
                // set -Dores.runtime.carriers=native to make this fail closed.
            }
        }

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(readyQueueCapacity),
                namedFactory(threadPrefix),
                new ThreadPoolExecutor.AbortPolicy());
        // JVM workers are a portability fallback, not the preferred Oreslang
        // runtime backend. They are created lazily on first scheduled turn.
        return executor;
    }

    private static boolean isNativeCarrierPlatform() {
        String os = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT);
        return os.contains("linux") || os.contains("mac") || os.contains("darwin");
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger next = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + next.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private final class ActorCell<M> {
        private final ActorRef<M> ref;
        private final ActorKind kind;
        private final int dispatchLane;
        private final IsolatePolicy policy;
        private final BehaviorFactory<M> behaviorFactory;
        private final boolean trustedFactory;
        /** Exact immutable source image whitelisted for this actor, if any. */
        private final SharedCodeImageStore.CodeImage codeImage;
        /** Shared compiler/interpreter code executor; never exposed to guest source. */
        private final ActorCodeExecutor codeExecutor;
        /** Closed-world source actor declaration selected for this actor. */
        private final String codeActorType;
        /** Mutable source actor state, created on this actor's dispatcher. */
        private ActorOwnedGuestState codeActorState;
        private final ActorCell<?> parent;
        private final Set<ActorCell<?>> children = ConcurrentHashMap.newKeySet();
        private final Set<OresFuture<?>> pendingOperations = ConcurrentHashMap.newKeySet();
        /** Bounded async-close Future receipts admitted only by cleanup Tasks. */
        private int pendingCleanupFutures;
        /**
         * Mutable scheduler metadata belongs to this actor, never to the shared
         * AST/evaluator. Keys are immutable shared code-site identities.
         */
        private final IdentityHashMap<Object, Long> staticSelectCursors =
                new IdentityHashMap<>();
        /** Settles only after the actor has fully crossed its TurnExecutor boundary. */
        private final OresFuture<Void> finalizedFuture = new OresFuture<>();
        /** Mailbox transport for ordinary user messages. */
        private final ChannelRuntime.Channel<MessageEnvelope> mailbox;
        private final ChannelRuntime.Channel<ActorOutputEnvelope> outputChannel;
        private final ActorOutputStream<Object> outputStream;
        private final ActorMemorySlice memorySlice;
        private final Set<ActorShardLease> shardLeases = ConcurrentHashMap.newKeySet();
        private final Set<ActorShardSnapshotLease> shardSnapshotLeases = ConcurrentHashMap.newKeySet();
        /** Guarded by lifecycleLock; private and shared shard bytes both counted. */
        private long shardReservedBytes;
        private final AtomicBoolean scheduled = new AtomicBoolean();
        /** Per-dispatch identity, guarded by lifecycleLock. A stale queued
         * runnable can never consume a newer scheduled=false->true claim. */
        private Object scheduledDispatchToken;
        private final AtomicBoolean stopped = new AtomicBoolean();
        /** Set under lifecycleLock by gracefulStop; no further user admissions. */
        private boolean sealing;
        // Lifetime-affine structured owner: never transferred before physical
        // finalization, even if the original group closes during cancellation.
        private final AtomicReference<ActorGroupId> managedByGroup = new AtomicReference<>();
        private volatile boolean forceKillFenced;
        private final AtomicLong sharedMailboxBytes = new AtomicLong();
        private final AtomicLong outputBytes = new AtomicLong();
        private long nextOutputSequence;
        private final Object mailboxAccountingLock = new Object();
        private int queuedMessages;
        private int queuedUserMessages;
        /** Separate control-lane quota; never borrow user mailbox headroom. */
        private int queuedContinuations;
        private long nextUserMessageSequence;
        private long lastProcessedUserMessageSequence = -1L;
        private record DrainWaiter(long sequence, OresFuture<Void> completion) { }
        private static final int MAX_PENDING_DRAIN_WAITERS = 1_024;
        private final ArrayList<DrainWaiter> pendingDrains = new ArrayList<>();
        private ActorInboxMail<M> activeMail;
        /** Bounded control channel: completion threads enqueue, never invoke guest code. */
        private final ChannelRuntime.Channel<MessageEnvelope> continuationMailbox =
                new ChannelRuntime.Channel<>(INTERNAL_CONTINUATION_SLOTS);
        /**
         * Runtime-minted SourceTask commands, isolated from ordinary Future
         * continuations so actor STOP can discard the latter while retaining
         * the former for authenticated lexical cleanup.
         */
        private final ChannelRuntime.Channel<MessageEnvelope> sourceTaskMailbox =
                new ChannelRuntime.Channel<>(INTERNAL_CONTINUATION_SLOTS);
        /** True while one source receive owns actor state across suspension. */
        private boolean sourceReceiveSuspended;
        private final Object lifecycleLock = new Object();
        private final Object executionDomain = new Object();
        private int activeTurns;
        private boolean carrierActive;
        /** Physical worker currently owning this actor lane, guarded by lifecycleLock.
         * Not an affinity requirement: a later turn may use a different worker. */
        private Thread carrierOwnerThread;
        /**
         * Set when the currently executing actor envelope enqueues a source
         * continuation. The batch must then unwind through TurnExecutor before
         * any queued continuation is admitted, otherwise rt cooperate would
         * merely rotate work on the same physical carrier.
         */
        private boolean sourceHandoffRequested;
        private OresScheduler sourceScheduler;
        // Source-scheduler shutdown must not invoke arbitrary synchronous
        // Future/cancellation callbacks while holding lifecycleLock.
        // guarded by lifecycleLock; only one close worker per actor.
        private boolean sourceSchedulerClosing;
        private boolean sourceStopCleanupPending;
        private boolean pendingOperationRevocationPending;
        private int pendingStopFutureNotifications;
        private int pendingStopDrainNotifications;
        private int pendingChildStopDispatches;
        private boolean sourceStopCleanupActive;
        private boolean sourceStopCleanupAborted;
        private boolean sourceSchedulerCloseFinished;
        private Throwable sourceSchedulerCloseFailure;
        /** self.end()/self.endWithCleanup(...) lifecycle state; guarded by lifecycleLock. */
        private boolean endRequested;
        private Runnable finalCleanup;
        private boolean finalCleanupStarted;
        private volatile boolean finalCleanupActive;
        /** Graceful source teardown is a reserved once-only actor turn. */
        // schedule() is also called outside lifecycleLock by host stops and
        // producer threads. Its admission read must see the shutdown request.
        private volatile boolean gracefulTeardownPending;
        private boolean gracefulTeardownStarted;
        private boolean gracefulTeardownActive;
        /** Cancellation, fatal failure and runtime close revoke all further
         * guest finalization callbacks, even if teardown already began. */
        private boolean gracefulTeardownRevoked;
        private boolean finalized;
        private volatile ActorTurnWatchdog.Lease watchdogLease;
        private long turnStartedWallNanos;
        private long turnStartedCpuNanos = -1L;
        private long untrustedFuelRemaining;
        private long untrustedSafepoints;
        /** One live logical quota cohort per untrusted actor. Guarded by lifecycleLock. */
        private volatile UntrustedLogicalQuota activeUntrustedSourceQuota;
        private int activeUntrustedSourceTaskCount;
        /** Timed admission persists until both guest quiescence and terminal drain. */
        private int activeUntrustedTimedAdmissions;
        private Behavior<M> behavior;

        private ActorCell(
                ActorRef<M> ref,
                ActorKind kind,
                IsolatePolicy policy,
                BehaviorFactory<M> behaviorFactory,
                boolean trustedFactory,
                SharedCodeImageStore.CodeImage codeImage,
                ActorCodeExecutor codeExecutor,
                String codeActorType,
                ActorCell<?> parent) {
            this.ref = ref;
            this.kind = kind;
            this.dispatchLane = dispatcherFor(kind) instanceof ActorAffinityDispatcher lanes
                    ? lanes.reserveLane() : -1;
            this.policy = policy;
            this.behaviorFactory = behaviorFactory;
            this.trustedFactory = trustedFactory;
            this.codeImage = codeImage;
            this.codeExecutor = codeExecutor;
            this.codeActorType = codeActorType;
            this.parent = parent;
            int mailboxCapacity = policy.maxMailboxMessages() >
                            Integer.MAX_VALUE - INTERNAL_CONTINUATION_SLOTS
                    ? Integer.MAX_VALUE
                    : policy.maxMailboxMessages() + INTERNAL_CONTINUATION_SLOTS;
            this.mailbox = new ChannelRuntime.Channel<>(mailboxCapacity);
            this.outputChannel = new ChannelRuntime.Channel<>(
                    Math.max(1, policy.maxMailboxMessages()));
            this.outputStream = new ActorOutputStream<>(
                    ref.id(), outputChannel);
            ref.outputStream = outputStream;
            actorOutputStreams.add(outputStream);
            this.memorySlice = isPrivateKind(kind)
                    ? new ActorMemorySlice(ref.id(), policy.maxHeapBytes())
                    : null;
        }

        private long nextStaticSelectTicket(Object selectSite) {
            synchronized (lifecycleLock) {
                if (stopped.get() || finalized) throw terminated(ref);
                long ticket = staticSelectCursors.getOrDefault(selectSite, 0L);
                staticSelectCursors.put(selectSite, Math.addExact(ticket, 1L));
                return ticket;
            }
        }

        private int mailboxCapacityWithControlHeadroom() {
            return policy.maxMailboxMessages() >
                            Integer.MAX_VALUE - INTERNAL_CONTINUATION_SLOTS
                    ? Integer.MAX_VALUE
                    : policy.maxMailboxMessages() + INTERNAL_CONTINUATION_SLOTS;
        }

        /**
         * User-message capacity and runtime-control capacity are distinct.
         * Continuations may consume only the reserved control headroom; they
         * must never reduce the actor's configured user mailbox allowance.
         */
        private boolean reserveMailboxSlot() {
            synchronized (mailboxAccountingLock) {
                if (queuedUserMessages >= policy.maxMailboxMessages()) return false;
                if (queuedMessages >= mailboxCapacityWithControlHeadroom()) return false;
                queuedUserMessages++;
                queuedMessages++;
                return true;
            }
        }

        private boolean reserveContinuationSlot() {
            synchronized (mailboxAccountingLock) {
                if (queuedContinuations >= INTERNAL_CONTINUATION_SLOTS) return false;
                if (queuedMessages >= mailboxCapacityWithControlHeadroom()) return false;
                queuedContinuations++;
                queuedMessages++;
                return true;
            }
        }

        private void releaseMailboxSlot(boolean continuation) {
            synchronized (mailboxAccountingLock) {
                // Validate every counter before changing any of them: a failed
                // invariant must not partially release another lane's capacity.
                if (queuedMessages <= 0
                        || queuedMessages != queuedUserMessages + queuedContinuations) {
                    throw new IllegalStateException(
                            "actor mailbox accounting invariant violation for " + ref.id());
                }
                if (continuation && queuedContinuations <= 0) {
                    throw new IllegalStateException(
                            "actor continuation accounting underflow for " + ref.id());
                }
                if (!continuation && queuedUserMessages <= 0) {
                    throw new IllegalStateException(
                            "actor user-mailbox accounting underflow for " + ref.id());
                }
                queuedMessages--;

                if (continuation) {
                    if (queuedContinuations <= 0) {
                        queuedMessages++;
                        throw new IllegalStateException(
                                "actor continuation accounting underflow for " + ref.id());
                    }
                    queuedContinuations--;
                } else {
                    if (queuedUserMessages <= 0) {
                        queuedMessages++;
                        throw new IllegalStateException(
                                "actor user-mailbox accounting underflow for " + ref.id());
                    }
                    queuedUserMessages--;
                }
            }
        }

        private OresFuture<Void> drainAcceptedMessages() {
            return drainAcceptedMessages(false);
        }

        private OresFuture<Void> drainAcceptedMessages(boolean shutdownBarrier) {
            synchronized (lifecycleLock) {
                if (stopped.get() || finalized) return OresFuture.failed(terminated(ref));
                final long target = nextUserMessageSequence - 1L;
                if (lastProcessedUserMessageSequence >= target)
                    return OresFuture.completed(null);
                // Do this inside the admission monitor. Checking only for an
                // active mail outside this lock races concurrent senders and
                // misses factory/startup turns with accepted, unprocessed mail.
                // The actor cannot complete its own pending drain until it
                // yields the single-writer execution lease.
                if (!shutdownBarrier && currentActor.get() == this)
                    return OresFuture.failed(new IllegalStateException(
                            "an actor cannot drain its own active mailbox turn"));
                // Public observers are bounded independently of mailbox slots:
                // a stalled actor must not permit unlimited host-side Futures.
                // gracefulStop reserves at most one extra waiter and cannot be
                // starved by callers exhausting the public observer capacity.
                if (!shutdownBarrier && pendingDrains.size() >= MAX_PENDING_DRAIN_WAITERS)
                    return OresFuture.failed(new ActorResourceExhaustedException(ref.id()));
                OresFuture<Void> result = new OresFuture<>(() -> false, () -> { });
                pendingDrains.add(new DrainWaiter(target, result));
                return result;
            }
        }

        private void markMessageProcessed(long sequence) {
            List<OresFuture<Void>> completed = new ArrayList<>();
            synchronized (lifecycleLock) {
                if (sequence != lastProcessedUserMessageSequence + 1L) {
                    throw new IllegalStateException(
                            "nonsequential actor message completion for " + ref.id());
                }
                lastProcessedUserMessageSequence = sequence;
                for (int i = pendingDrains.size() - 1; i >= 0; --i) {
                    DrainWaiter waiter = pendingDrains.get(i);
                    if (waiter.sequence() <= sequence) {
                        pendingDrains.remove(i);
                        completed.add(waiter.completion());
                    }
                }
            }
            // Complete outside the lifecycle monitor: Future callbacks may
            // synchronously request actor stop or another lifecycle operation.
            for (OresFuture<Void> waiter : completed) waiter.completeFromRuntime(null);
        }

        private boolean hasQueuedWork() {
            synchronized (lifecycleLock) {
                return hasQueuedWorkLocked();
            }
        }

        private boolean hasQueuedWorkLocked() {
            return !sourceTaskMailbox.isEmpty()
                    || (!stopped.get() && (!continuationMailbox.isEmpty()
                            || (!sourceReceiveSuspended && !mailbox.isEmpty())));
        }

        private OresScheduler sourceScheduler() {
            synchronized (lifecycleLock) {
                if (sourceScheduler != null) return sourceScheduler;
                // Creation itself must linearize with STOP; otherwise an
                // already-active guest message could create a NEW, unsealed
                // source scheduler after STOP took its cancellation snapshot.
                if (stopped.get() || finalized || closed.get()) {
                    throw terminated(ref);
                }

                java.util.concurrent.Executor mailboxExecutor = command -> {
                    if (!enqueueSourceTaskContinuation(this, command)) {
                        throw new RejectedExecutionException(
                                "actor " + ref.id()
                                        + " cannot accept a source continuation");
                    }
                };

                sourceScheduler = OresScheduler.runtimeOwned(
                        "ores-actor-" + ref.id(),
                        1,
                        mailboxExecutor,
                        Runnable::run);
                return sourceScheduler;
            }
        }

        private boolean beginTurn() {
            synchronized (lifecycleLock) {
                if ((stopped.get() && !gracefulTeardownPending && !sourceStopCleanupActive)
                        || forceKillFenced || finalized) return false;
                // The full actor execution domain (including lifecycle and resumed
                // continuations) must be entered on the single admitted carrier.
                if (!carrierActive || carrierOwnerThread != Thread.currentThread()
                        || !scheduled.get()) {
                    throw new IllegalStateException(
                            "actor turn entered without owning its physical carrier for " + ref.id());
                }
                if (activeTurns != 0) {
                    throw new IllegalStateException(
                            "actor single-writer execution lease violation for " + ref.id()
                                    + ": activeTurns=" + activeTurns);
                }
                activeTurns = 1;
                return true;
            }
        }

        private void requestEnd(Runnable cleanup) {
            synchronized (lifecycleLock) {
                if (finalized) return;
                if (endRequested) {
                    if (cleanup != null && cleanup != finalCleanup) {
                        throw new IllegalStateException(
                                "actor final cleanup is already fixed for actor "
                                        + ref.id());
                    }
                    return;
                }
                endRequested = true;
                finalCleanup = cleanup;
            }
            stop();
        }

        private void runGracefulTeardownIfNeeded(ActorContext<M> context) throws Exception {
            ActorOwnedGuestState state;
            synchronized (lifecycleLock) {
                if (!gracefulTeardownPending || gracefulTeardownStarted
                        || gracefulTeardownRevoked
                        || forceKillFenced || finalized
                        || ref.terminationCause.get() != null) return;
                gracefulTeardownStarted = true;
                gracefulTeardownActive = true;
                state = codeActorState;
            }
            try {
                if (state != null && codeExecutor != null) {
                    @SuppressWarnings("unchecked")
                    ActorContext<Object> sourceContext =
                            (ActorContext<Object>) (ActorContext<?>) context;
                    codeExecutor.tearDownActor(codeActorType, state, sourceContext);
                }
            } finally {
                synchronized (lifecycleLock) {
                    gracefulTeardownActive = false;
                    gracefulTeardownPending = false;
                    lifecycleLock.notifyAll();
                }
            }
        }

        private void runFinalCleanupIfNeeded() {
            Runnable cleanup;
            synchronized (lifecycleLock) {
                if (!endRequested
                        || finalCleanupStarted
                        || finalCleanup == null
                        || gracefulTeardownRevoked
                        || (ref.terminationCause.get() != null
                                && !gracefulTeardownStarted)) return;
                finalCleanupStarted = true;
                finalCleanupActive = true;
                cleanup = finalCleanup;
            }

            try {
                cleanup.run();
            } finally {
                synchronized (lifecycleLock) {
                    finalCleanupActive = false;
                    finalCleanup = null;
                    lifecycleLock.notifyAll();
                }
            }
        }

        private void endTurn() {
            synchronized (lifecycleLock) {
                if (!carrierActive || carrierOwnerThread != Thread.currentThread()) {
                    throw new IllegalStateException(
                            "actor turn left from a non-owning physical carrier for " + ref.id());
                }
                if (activeTurns != 1) {
                    throw new IllegalStateException(
                            "actor execution lease accounting violation for " + ref.id()
                                    + ": activeTurns=" + activeTurns);
                }
                activeTurns = 0;
                if (stopped.get()) finalizeStopLocked();
                lifecycleLock.notifyAll();
            }
        }

        private void closeSourceSchedulerOutsideLock(OresScheduler scheduler) {
            Throwable failure = null;
            try {
                scheduler.close();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                failure = fatal;
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            } finally {
                synchronized (lifecycleLock) {
                    if (sourceScheduler != scheduler) {
                        failure = new IllegalStateException(
                                "actor source scheduler identity changed during close");
                    }
                    sourceSchedulerCloseFailure = failure;
                    sourceSchedulerCloseFinished = true;
                    sourceSchedulerClosing = false;
                    if (stopped.get()) finalizeStopLocked();
                    lifecycleLock.notifyAll();
                }
            }
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            if (failure instanceof LinkageError fatal) throw fatal;
        }

        private void finalizeStopLocked() {
            if (finalized
                    || sourceSchedulerClosing
                    || sourceStopCleanupPending
                    || pendingOperationRevocationPending
                    || pendingStopFutureNotifications != 0
                    || pendingStopDrainNotifications != 0
                    || pendingChildStopDispatches != 0
                    || pendingCleanupFutures != 0
                    || activeTurns != 0
                    || scheduled.get()
                    || carrierActive
                    || gracefulTeardownPending
                    || !children.isEmpty()) return;
            OresScheduler scheduler = sourceScheduler;
            if (sourceStopCleanupAborted) {
                recordFinalizationFailure(new OresScheduler.CleanupIncompleteError(
                        "actor source cleanup admission failed; quarantine arena"));
                lifecycleLock.notifyAll();
                return;
            }
            if (scheduler != null && (!scheduler.actorStopPhysicallyQuiescent()
                    || activeUntrustedSourceTaskCount != 0
                    || activeUntrustedTimedAdmissions != 0)) return;
            try {
                if (!nativeIoLoanRetired.test(ref.id())) return;
            } catch (RuntimeException | Error gateFailure) {
                sourceStopCleanupAborted = true;
                recordFinalizationFailure(new OresScheduler.CleanupIncompleteError(
                        "actor native I/O physical loan gate failed", gateFailure));
                lifecycleLock.notifyAll();
                return;
            }
            if (scheduler != null) {
                if (!sourceSchedulerCloseFinished) {
                    sourceSchedulerClosing = true;
                    try {
                        Thread.ofVirtual()
                                .name("ores-actor-" + ref.id() + "-scheduler-close")
                                .start(() -> closeSourceSchedulerOutsideLock(scheduler));
                    } catch (VirtualMachineError | ThreadDeath fatal) {
                        sourceSchedulerClosing = false;
                        throw fatal;
                    } catch (Throwable schedulingFailure) {
                        sourceSchedulerCloseFailure = schedulingFailure;
                        sourceSchedulerCloseFinished = true;
                        sourceSchedulerClosing = false;
                    }
                    if (!sourceSchedulerCloseFinished) return;
                }
                if (sourceSchedulerCloseFailure != null || scheduler.hasIncompleteCleanup()) {
                    recordFinalizationFailure(new OresScheduler.CleanupIncompleteError(
                            "actor source cleanup incomplete; quarantine arena",
                            sourceSchedulerCloseFailure));
                    lifecycleLock.notifyAll();
                    return;
                }
            }
            finalized = true;
            sourceScheduler = null;
            sourceTaskMailbox.close();
            try { drainMailboxReservations(); }
            catch (VirtualMachineError | ThreadDeath fatal) { throw fatal; }
            catch (Throwable failure) { recordFinalizationFailure(failure); }
            staticSelectCursors.clear();
            codeActorState = null;
            try { outputStream.close(ref.terminationCause.get()); }
            catch (VirtualMachineError | ThreadDeath fatal) { throw fatal; }
            catch (Throwable failure) { recordFinalizationFailure(failure); }
            for (ActorShardLease lease : List.copyOf(shardLeases)) {
                try { lease.revokeFromActorStop(); }
                catch (VirtualMachineError | ThreadDeath fatal) { throw fatal; }
                catch (Throwable failure) { recordFinalizationFailure(failure); }
            }
            shardLeases.clear();
            for (ActorShardSnapshotLease snapshot : List.copyOf(shardSnapshotLeases)) {
                try { snapshot.revokeFromActorStop(); }
                catch (VirtualMachineError | ThreadDeath fatal) { throw fatal; }
                catch (Throwable failure) { recordFinalizationFailure(failure); }
            }
            shardSnapshotLeases.clear();
            if (memorySlice != null) {
                try { memorySlice.close(); }
                catch (VirtualMachineError | ThreadDeath fatal) { throw fatal; }
                catch (Throwable failure) { recordFinalizationFailure(failure); }
            }
            try { actorExitHook.accept(executionDomain); }
            catch (VirtualMachineError | ThreadDeath fatal) { throw fatal; }
            catch (Throwable failure) { recordFinalizationFailure(failure); }
            try { unregisterActor(this); }
            catch (VirtualMachineError | ThreadDeath fatal) { throw fatal; }
            catch (Throwable failure) { recordFinalizationFailure(failure); }
            if (parent != null) {
                try { parent.childFinalized(this); }
                catch (VirtualMachineError | ThreadDeath fatal) { throw fatal; }
                catch (Throwable failure) { recordFinalizationFailure(failure); }
            }
            Throwable terminal = ref.terminationCause.get();
            if (!ref.readyFuture.isDone()) {
                ref.readyFuture.failFromRuntime(
                        terminal == null ? new ActorTerminatedException(ref.id(), kind, null) : terminal);
            }
            if (terminal == null) ref.doneFuture.completeFromRuntime(null);
            else ref.doneFuture.failFromRuntime(terminal);
            finalizedFuture.completeFromRuntime(null);
            lifecycleLock.notifyAll();
        }

        private void sourceTaskCleanupQuiesced() {
            synchronized (lifecycleLock) {
                if (stopped.get() && !sourceStopCleanupPending
                        && activeTurns == 0) finalizeStopLocked();
                lifecycleLock.notifyAll();
            }
        }

        private void childFinalized(ActorCell<?> child) {
            synchronized (lifecycleLock) {
                children.remove(child);
                if (stopped.get() && activeTurns == 0) finalizeStopLocked();
                lifecycleLock.notifyAll();
            }
        }

        private boolean finalized() {
            synchronized (lifecycleLock) {
                return finalized;
            }
        }

        private void awaitFinalized(long remainingNanos) throws InterruptedException {
            long deadline = System.nanoTime() + Math.max(0L, remainingNanos);
            synchronized (lifecycleLock) {
                while (!finalized) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return;
                    long millis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
                    lifecycleLock.wait(millis);
                }
            }
        }

        private void cancelQueuedScheduleOnShutdown() {
            synchronized (lifecycleLock) {
                if (scheduled.get() && !carrierActive) {
                    scheduledDispatchToken = null;
                    scheduled.set(false);
                    if (stopped.get() && activeTurns == 0) finalizeStopLocked();
                    lifecycleLock.notifyAll();
                }
            }
        }

        /** Caller must hold lifecycleLock. Both output reservation and
         * actual commit enforce this same revocable actor authority. */
        private boolean acceptsActorOutputLocked() {
            return !closed.get()
                    && !finalized
                    && !gracefulTeardownRevoked
                    && !forceKillFenced
                    && (!stopped.get() || endRequested
                            || gracefulTeardownActive || finalCleanupActive);
        }

        private void reserveOutput(long bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException(
                        "actor output reservation cannot be negative");
            }
            synchronized (lifecycleLock) {
                if (closed.get()) {
                    throw new IllegalStateException("actor runtime is closed");
                }
                // self.end/self.endWithCleanup seals the mailbox immediately, but
                // the current turn and its final cleanup retain the actor's output
                // capability until finalization closes the stream.
                if (!acceptsActorOutputLocked()) {
                    throw terminated(ref);
                }

                long current = outputBytes.get();
                long next;
                try {
                    next = Math.addExact(current, bytes);
                } catch (ArithmeticException overflow) {
                    throw new IllegalStateException(
                            "actor output memory accounting overflow",
                            overflow);
                }

                long actorAvailable = isPrivateKind(kind)
                        ? Math.max(0L, memorySlice.remainingBytes() - current)
                        : Math.max(
                                0L,
                                policy.maxHeapBytes()
                                        - sharedMailboxBytes.get()
                                        - current);
                if (bytes > actorAvailable) {
                    throw new IllegalStateException(
                            "actor output memory limit exceeded for "
                                    + ref.id()
                                    + ": requested=" + bytes
                                    + " outputUsed=" + current
                                    + " available=" + actorAvailable);
                }

                reserveSharedRuntimeBytes(
                        bytes,
                        "actor output stream");
                outputBytes.set(next);
            }
        }

        private void releaseOutput(long bytes) {
            if (bytes == 0) return;
            synchronized (lifecycleLock) {
                long current = outputBytes.get();
                if (bytes > current) {
                    throw new IllegalStateException(
                            "actor output memory accounting underflow for "
                                    + ref.id());
                }
                outputBytes.set(current - bytes);
                releaseSharedRuntimeBytes(bytes);
            }
        }

        private void reserveSharedMailbox(long bytes) {
            if (bytes < 0) throw new IllegalArgumentException("shared mailbox reservation cannot be negative");
            synchronized (lifecycleLock) {
                if (closed.get()) throw new IllegalStateException("actor runtime is closed");
                if (stopped.get()) throw terminated(ref);
                long current = sharedMailboxBytes.get();
                long next;
                try {
                    next = Math.addExact(current, bytes);
                } catch (ArithmeticException overflow) {
                    throw new IllegalStateException("shared actor mailbox memory accounting overflow");
                }
                if (next > policy.maxHeapBytes()) {
                    throw new IllegalStateException("shared actor mailbox memory limit exceeded for " + ref.id()
                            + ": requested=" + bytes + " used=" + current + " limit=" + policy.maxHeapBytes());
                }
                reserveSharedRuntimeBytes(bytes, "shared actor mailbox");
                sharedMailboxBytes.set(next);
            }
        }

        private void releaseSharedMailbox(long bytes) {
            if (bytes == 0) return;
            synchronized (lifecycleLock) {
                long current = sharedMailboxBytes.get();
                if (bytes > current) {
                    throw new IllegalStateException(
                            "shared actor mailbox memory accounting underflow for "
                                    + ref.id()
                                    + ": release=" + bytes + " used=" + current);
                }
                sharedMailboxBytes.set(current - bytes);
                releaseSharedRuntimeBytes(bytes);
            }
        }

        private void schedule() {
            final Object dispatchTicket;
            synchronized (lifecycleLock) {
                if ((stopped.get() && !gracefulTeardownPending) || forceKillFenced || closed.get() || scheduled.get()) return;
                // Atomically claim the actor lane and publish a fresh identity.
                dispatchTicket = new Object();
                scheduledDispatchToken = dispatchTicket;
                scheduled.set(true);
            }
            try {
                ExecutorService dispatcher = dispatcherFor(kind);
                Runnable dispatch = () -> runBatch(dispatchTicket);
                java.util.function.Consumer<Runnable> submit = runnable -> {
                    if (dispatcher instanceof ActorAffinityDispatcher lanes) {
                        lanes.executeOnLane(dispatchLane, runnable);
                    } else {
                        dispatcher.execute(runnable);
                    }
                };
                if (physicalTurnGate != null) {
                    physicalTurnGate.dispatch(dispatch, submit, failure -> {
                        synchronized (lifecycleLock) {
                            if (scheduledDispatchToken == dispatchTicket && !carrierActive) {
                                scheduledDispatchToken = null;
                                scheduled.set(false);
                            }
                        }
                        stop();
                    });
                } else {
                    submit.accept(dispatch);
                }
            } catch (RejectedExecutionException rejected) {
                synchronized (lifecycleLock) {
                    if (scheduledDispatchToken == dispatchTicket && !carrierActive) {
                        scheduledDispatchToken = null;
                        scheduled.set(false);
                    }
                }
                synchronized (lifecycleLock) {
                    gracefulTeardownPending = false;
                    gracefulTeardownRevoked = true;
                }
                fail(rejected);

                if (!closed.get()) throw rejected;
            }
        }

        private void runBatch(Object dispatchTicket) {
            // Nested or obsolete dispatch attempts must not mutate the
            // thread-local authority of an already executing actor.
            boolean previousCarrier = Boolean.TRUE.equals(ACTOR_CARRIER.get());
            boolean carrierEntered = false;
            boolean reschedule = false;
            try {
                synchronized (lifecycleLock) {
                    // Prevent obsolete queued runnables impersonating the
                    // next actor turn after scheduled was cleared and re-claimed.
                    if (scheduledDispatchToken != dispatchTicket || !scheduled.get()) return;
                    if ((stopped.get() && !gracefulTeardownPending) || closed.get() || forceKillFenced) return;
                    if (carrierActive || carrierOwnerThread != null || activeTurns != 0) {
                        throw new IllegalStateException(
                                "actor physical carrier already occupied for " + ref.id());
                    }
                    carrierOwnerThread = Thread.currentThread();
                    carrierActive = true;
                    carrierEntered = true;
                }

                // Only a successfully admitted physical actor turn can assert
                // carrier identity. Stale replay is inert even if invoked from
                // inside another legitimate actor turn on the same worker.
                ACTOR_CARRIER.set(Boolean.TRUE);

                // Only the admitted owner may have its native CPU affinity
                // changed. An obsolete queued runnable is inert, even if the
                // dispatcher supports physical CPU pinning.
                if (dispatcherFor(kind) instanceof ActorAffinityDispatcher lanes) {
                    int cpu = lanes.cpuForLane(dispatchLane);
                    if (cpu >= 0) NativeCarrierExecutor.bindCurrentThreadToCpu(cpu);
                }
                // TurnExecutor is a synchronous host context boundary, not
                // an independent scheduler. A broken host must not suppress a
                // turn, call it twice, or re-enter actor code on another worker.
                // These flags remain valid even if a foreign callback races
                // after the executor has returned.
                AtomicBoolean invoked = new AtomicBoolean();
                AtomicBoolean completed = new AtomicBoolean();
                AtomicBoolean violationObserved = new AtomicBoolean();
                Thread admittedWorker = Thread.currentThread();
                turnExecutor.execute(() -> {
                    if (!invoked.compareAndSet(false, true)) {
                        // Record the violation independently of the thrown
                        // exception: an embeddable host may catch/suppress it.
                        violationObserved.set(true);
                        throw new IllegalStateException(
                                "actor turn callback invoked more than once for " + ref.id());
                    }
                    if (Thread.currentThread() != admittedWorker) {
                        violationObserved.set(true);
                        throw new IllegalStateException(
                                "actor turn callback invoked on a foreign worker for " + ref.id());
                    }
                    synchronized (lifecycleLock) {
                        if (scheduledDispatchToken != dispatchTicket || !scheduled.get()
                                || !carrierActive || carrierOwnerThread != admittedWorker) {
                            violationObserved.set(true);
                            throw new IllegalStateException(
                                    "actor turn callback lost physical carrier lease for " + ref.id());
                        }
                    }
                    try {
                        runBatchEntered();
                    } finally {
                        completed.set(true);
                    }
                });
                if (violationObserved.get() || !completed.get()) {
                    throw new IllegalStateException(
                            "actor TurnExecutor returned after an invalid or unfinished callback for "
                                    + ref.id());
                }
            } catch (Throwable failure) {
                fail(failure);
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                if (failure instanceof LinkageError fatal) throw fatal;
            } finally {
                try {
                    synchronized (lifecycleLock) {
                        if (carrierEntered) {
                            if (carrierOwnerThread != Thread.currentThread()
                                    || activeTurns != 0) {
                                // Never release an owner that has not completely
                                // left guest execution and its Truffle boundary.
                                throw new IllegalStateException(
                                        "actor carrier release before execution quiesced for " + ref.id());
                            }
                            carrierOwnerThread = null;
                            carrierActive = false;
                        }
                        // A stale replay cannot release a later scheduling
                        // identity, even when no carrier is currently active.
                        if (scheduledDispatchToken == dispatchTicket
                                && (carrierEntered || !carrierActive)) {
                            scheduledDispatchToken = null;
                            scheduled.set(false);
                            if (stopped.get() && activeTurns == 0) finalizeStopLocked();
                            reschedule = !forceKillFenced
                                    && !closed.get()
                                    && !finalized
                                    && (stopped.get() ? gracefulTeardownPending : hasQueuedWorkLocked());
                        }
                        lifecycleLock.notifyAll();
                    }
                } finally {
                    if (carrierEntered) {
                        if (previousCarrier) ACTOR_CARRIER.set(Boolean.TRUE);
                        else ACTOR_CARRIER.remove();
                    }
                }
                if (reschedule) schedule();
            }
        }

        @SuppressWarnings("unchecked")
        private void runBatchEntered() {
            // A nested physical dispatch on this thread (including into a
            // different ActorRuntime) must not replace an executing actor's
            // ThreadLocal identity, context, watchdog or authority.
            // Synchronous actor-to-actor calls are mailbox sends, not a
            // second nested actor scheduler turn.
            if (CURRENT_ACTOR_EXECUTION.get() != null || currentActor.get() != null) {
                throw new IllegalStateException(
                        "nested actor carrier admission would overwrite the current actor context");
            }
            currentActor.set(this);
            CURRENT_ACTOR_EXECUTION.set(new ActorExecutionContext(
                    ActorRuntime.this, ref.id(), kind, policy, executionDomain));
            boolean turnActive = false;
            ActorContext<M> teardownContext = null;
            try {
                // Admission may throw on a single-writer lease violation.
                // The carrier's actor identity must still be cleared.
                turnActive = beginTurn();
                if (!turnActive) return;
                watchdogLease = ActorTurnWatchdog.enter(
                        lease -> recordWatchdogOverrun(this, lease));

                if (kind == ActorKind.UNTRUSTED) {
                    turnStartedWallNanos = System.nanoTime();
                    turnStartedCpuNanos = currentThreadCpuNanos();
                    untrustedFuelRemaining = untrustedFuelBudget(policy);
                    untrustedSafepoints = 0L;
                }

                ActorContext<M> context = new ActorContext<>() {
                    @Override public ActorRef<M> self() { return ref; }
                    @Override public ActorRuntime runtime() { return ActorRuntime.this; }
                    @Override public IsolatePolicy policy() { return policy; }
                    @Override public ActorKind kind() { return kind; }
                    @Override public Optional<ActorMemorySlice> privateMemory() {
                        return Optional.ofNullable(memorySlice);
                    }
                    @Override public Optional<ActorInboxMail<M>> currentMail() {
                        return Optional.ofNullable(activeMail);
                    }
                    @Override public Optional<SharedCodeImageStore.CodeImage> codeImage() {
                        return Optional.ofNullable(codeImage);
                    }
                };

                teardownContext = context;
                // External stop may have sealed the user mailbox before this
                // reserved lifecycle turn began. Never initialize or receive.
                if (stopped.get()) return;

                if (behavior == null && codeActorState == null) {
                    if (codeExecutor != null) {
                        @SuppressWarnings("unchecked")
                        ActorContext<Object> sourceContext =
                                (ActorContext<Object>) (ActorContext<?>) context;
                        ActorOwnedGuestState created =
                                Objects.requireNonNull(
                                        codeExecutor.initializeActor(
                                                codeActorType,
                                                sourceContext),
                                        "source actor executor returned null state");
                        if (!created.ownerActorId().equals(ref.id())) {
                            throw new SecurityException(
                                    "source actor state owner "
                                            + created.ownerActorId()
                                            + " does not match actor "
                                            + ref.id());
                        }
                        codeActorState = created;
                    } else {
                        Behavior<M> created = Objects.requireNonNull(
                                behaviorFactory.create(context),
                                "actor behaviorFactory returned null");
                        if (isPrivateKind(kind) && !trustedFactory) {
                            validatePrivateBehaviorState(ref.id(), created);
                        }
                        behavior = created;
                    }
                    synchronized (lifecycleLock) {
                        if (stopped.get() || forceKillFenced) {
                            ref.readyFuture.failFromRuntime(terminated(ref));
                        } else {
                            ref.readyFuture.completeFromRuntime(null);
                        }
                    }
                }

                int processed = 0;
                while (processed < dispatcherConfig.throughput()
                        && (!stopped.get() || sourceStopCleanupActive)) {
                    MessageEnvelope envelope;
                    boolean sourceTaskCommand;
                    synchronized (lifecycleLock) {
                        if ((stopped.get() && !sourceStopCleanupActive)
                                || finalized) break;

                        // Only scheduler-minted, already-admitted SourceTask
                        // commands may run after STOP. Never consume ordinary
                        // guest mail or generic Future continuations here.
                        envelope = sourceTaskMailbox.tryRead().orElse(null);
                        sourceTaskCommand = envelope != null;
                        if (envelope == null && !stopped.get()) {
                            envelope = continuationMailbox.tryRead().orElse(null);
                            if (envelope == null && !sourceReceiveSuspended) {
                                envelope = mailbox.tryRead().orElse(null);
                            }
                        }
                    }
                    if (envelope == null) break;
                    releaseMailboxSlot(envelope.isContinuation());
                    // The local is assigned from both continuation and user-mail
                    // queues above, so it is not effectively final. Bind the
                    // selected envelope to a final resource for deterministic
                    // close/permit release across every dispatch path.
                    try (MessageEnvelope activeEnvelope = envelope) {
                        /*
                         * Linearize queued-work start against stop/cancel.
                         * If cancellation acquired lifecycleLock first, this
                         * envelope is discarded and no new guest work begins.
                         * If this gate wins first, the envelope is already-active
                         * actor work; a later cancellation is observed at the
                         * normal scheduler safepoints.
                         */
                        synchronized (lifecycleLock) {
                            // A source command dequeued before STOP has
                            // already crossed the actor-turn admission gate.
                            // Never discard it: its scheduler may already
                            // consider the Task QUEUED and cannot replay it.
                            // STOP observes cancellation at the next source
                            // checkpoint; user/ordinary callbacks never pass.
                            if (finalized || (stopped.get()
                                    && !sourceTaskCommand)) break;
                        }

                        if (activeEnvelope.isContinuation()) {
                            activeEnvelope.continuation().run();
                        } else {
                            ActorInboxMail<M> mail = new ActorInboxMail<>(
                                    ref.id(),
                                    Optional.ofNullable(activeEnvelope.sender()),
                                    activeEnvelope.sequence(),
                                    (M) activeEnvelope.value());
                            activeMail = mail;
                            try {
                                if (codeExecutor != null) {
                                    if (codeActorState == null
                                            || !codeActorState.ownerActorId().equals(ref.id())) {
                                        throw new SecurityException(
                                                "source actor state lost actor ownership before mailbox dispatch");
                                    }
                                    @SuppressWarnings("unchecked")
                                    ActorInboxMail<Object> sourceMail =
                                            (ActorInboxMail<Object>) (ActorInboxMail<?>) mail;
                                    @SuppressWarnings("unchecked")
                                    ActorContext<Object> sourceContext =
                                            (ActorContext<Object>) (ActorContext<?>) context;
                                    codeExecutor.receiveActor(
                                            codeActorType,
                                            codeActorState,
                                            sourceMail,
                                            sourceContext);
                                } else {
                                    behavior.onMessage(mail.value(), context);
                                }
                            } finally {
                                if (!sourceReceiveSuspended) activeMail = null;
                            }
                        }
                        if (codeExecutor == null
                                && isPrivateKind(kind)
                                && !trustedFactory) {
                            // Private state that survives a mailbox turn must
                            // remain in actor-owned storage/capabilities. This
                            // catches behavior fields that were null/immutable
                            // at construction but later retain a mutable JVM
                            // object across turns.
                            validatePrivateBehaviorState(ref.id(), behavior);
                        }
                    }
                    // A source receive that suspended is not processed until
                    // its continuation completes; the continuation marks it.
                    if (!envelope.isContinuation() && !sourceReceiveSuspended) {
                        markMessageProcessed(envelope.sequence());
                    }
                    processed++;

                    synchronized (lifecycleLock) {
                        if (sourceHandoffRequested) {
                            sourceHandoffRequested = false;
                            break;
                        }
                    }
                }
            } catch (Throwable failure) {
                // Actor cancellation is a control-plane unwind, not a guest
                // failure. The actor was already marked stopped by the
                // supervisor/cancel path; finally/endTurn completes teardown.
                if (!(failure instanceof ActorCancellationSignal)) {
                    // Fail-stop supervision for ordinary actor failures. Fatal
                    // VM errors are cleaned up and then rethrown.
                    fail(failure);
                }
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                if (failure instanceof LinkageError fatal) throw fatal;
            } finally {
                if (turnActive && stopped.get()) {
                    try {
                        if (teardownContext != null) {
                            runGracefulTeardownIfNeeded(teardownContext);
                        }
                    } catch (Throwable teardownFailure) {
                        recordFinalizationFailure(teardownFailure);
                    }
                    try {
                        runFinalCleanupIfNeeded();
                    } catch (Throwable cleanupFailure) {
                        recordFinalizationFailure(cleanupFailure);
                    }
                }
                try {
                    ActorTurnWatchdog.Lease lease = watchdogLease;
                    watchdogLease = null;
                    if (lease != null) lease.close();
                    if (turnActive) endTurn();
                } finally {
                    // endTurn itself is a fail-closed invariant check and may
                    // throw; never strand actor authority on a reused carrier.
                    CURRENT_ACTOR_EXECUTION.remove();
                    currentActor.remove();
                }
                // scheduled/finalization/rescheduling belong to runBatch(),
                // after TurnExecutor.execute(...) has returned.
            }
        }

        private void drainOrdinaryMailboxReservations() {
            synchronized (lifecycleLock) {
                while (continuationMailbox.drainOne(continuation -> {
                    releaseMailboxSlot(true);
                    continuation.close();
                })) {
                    // STOP discards ordinary runtime continuations.
                }
                sourceHandoffRequested = false;
            }
            while (mailbox.drainOne(envelope -> {
                releaseMailboxSlot(false);
                envelope.close();
            })) {
                // STOP discards user mail but preserves SourceTask unwind.
            }
            sourceReceiveSuspended = false;
            activeMail = null;
        }

        private void drainMailboxReservations() {
            drainOrdinaryMailboxReservations();
            while (sourceTaskMailbox.drainOne(continuation -> {
                releaseMailboxSlot(true);
                continuation.close();
            })) {
                // Safe only after all source tasks and physical receipts retire.
            }
        }

        private void recordFinalizationFailure(Throwable failure) {
            Throwable original = ref.terminationCause.get();
            if (original == null && ref.terminationCause.compareAndSet(null, failure)) return;
            original = ref.terminationCause.get();
            if (original != null && original != failure) original.addSuppressed(failure);
        }

        private void fail(Throwable failure) {
            terminateTree(failure, true);
        }

        private void stop() {
            terminateTree(null, false);
        }

        private void cancel(ActorCancelledException cancellation) {
            terminateTree(cancellation, true);
        }

        private void presealDescendantAdmissions(List<ActorCell<?>> roots) {
            ArrayDeque<ActorCell<?>> toSeal = new ArrayDeque<>(roots);
            Set<ActorCell<?>> seen =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            while (!toSeal.isEmpty()) {
                ActorCell<?> child = toSeal.removeFirst();
                if (!seen.add(child)) continue;
                synchronized (child.lifecycleLock) {
                    if (!child.finalized) {
                        child.sealing = true;
                        toSeal.addAll(child.children);
                    }
                }
            }
        }

        private void quarantineChildStopFailure(Throwable failure) {
            synchronized (lifecycleLock) {
                sourceStopCleanupAborted = true;
                ref.terminationCause.compareAndSet(null,
                        new OresScheduler.CleanupIncompleteError(
                                "child STOP request failed; quarantine actor state",
                                failure));
                lifecycleLock.notifyAll();
            }
        }

        private void completeChildStopDispatch(Throwable failure) {
            synchronized (lifecycleLock) {
                if (failure != null) {
                    sourceStopCleanupAborted = true;
                    ref.terminationCause.compareAndSet(null,
                            new OresScheduler.CleanupIncompleteError(
                                    "asynchronous child STOP failed; quarantine actor state",
                                    failure));
                }
                if (pendingChildStopDispatches <= 0) {
                    sourceStopCleanupAborted = true;
                    ref.terminationCause.compareAndSet(null,
                            new OresScheduler.CleanupIncompleteError(
                                    "asynchronous child STOP dispatch receipt underflow"));
                } else {
                    pendingChildStopDispatches--;
                }
                if (stopped.get()) finalizeStopLocked();
                lifecycleLock.notifyAll();
            }
        }

        private void terminateTree(Throwable cause, boolean recordCause) {
            List<ActorCell<?>> descendants;
            List<OresFuture<?>> pending;
            List<DrainWaiter> failedDrainWaiters;
            OresScheduler cleanupScheduler;
            Runnable cancellationSnapshot;
            boolean scheduleTeardown;
            synchronized (lifecycleLock) {
                if (finalized) return;
                if (recordCause && cause != null) {
                    ref.terminationCause.compareAndSet(null, cause);
                }
                if (cause != null || closed.get() || forceKillFenced) {
                    gracefulTeardownPending = false;
                    gracefulTeardownRevoked = true;
                } else if (!stopped.get() && codeExecutor != null
                        && codeActorState != null && !sourceReceiveSuspended) {
                    gracefulTeardownPending = true;
                }
                // STOP is single-shot. Reentrant supervisor/host requests must
                // not re-close a channel or reset the cleanup admission fence.
                if (!stopped.compareAndSet(false, true)) return;
                sealing = true;
                cleanupScheduler = sourceScheduler;
                sourceStopCleanupPending = cleanupScheduler != null;
                pendingOperationRevocationPending = true;
                // No guest Task can enter after STOP: seal the scheduler's
                // own admission monitor while our lifecycle monitor is held.
                // This phase does not cancel Futures or invoke subscribers.
                cancellationSnapshot = cleanupScheduler == null ? null
                        : cleanupScheduler.sealActorStopAdmission(
                                this::sourceTaskCleanupQuiesced);
                // Detach pending observers under the actor monitor. Settling an
                // OresFuture invokes runtime callbacks inline, so never settle
                // one while holding lifecycleLock.
                failedDrainWaiters = List.copyOf(pendingDrains);
                pendingDrains.clear();
                pendingStopDrainNotifications = failedDrainWaiters.size();
                if (cause == null) mailbox.close();
                else mailbox.close(cause);
                continuationMailbox.close(cause);
                drainOrdinaryMailboxReservations();
                // Leave sourceTaskMailbox open: the stopped actor's scheduler
                // still owns authenticated lexical unwind continuations.
                descendants = List.copyOf(children);
                pendingChildStopDispatches =
                        physicalStopDispatcher == null ? 0 : descendants.size();
                pending = List.copyOf(pendingOperations);
                // Cancellation may already have won the terminal CAS on a
                // different thread, whose revoker or subscriber is still
                // physically running. A second cancel() cannot join it.
                // Reserve exactly one notification-drain receipt per owner.
                pendingStopFutureNotifications = pending.size();
                pendingOperations.clear();
                finalizeStopLocked();
                scheduleTeardown = gracefulTeardownPending
                        && activeTurns == 0 && !scheduled.get();
                lifecycleLock.notifyAll();
            }
            if (scheduleTeardown) schedule();

            // Physical sibling teardown may block or queue. All descendants,
            // not merely immediate children, must reject new work BEFORE
            // those asynchronous dispatches are submitted.
            presealDescendantAdmissions(descendants);

            // Propagate every sibling STOP without coupling it to another
            // sibling's blocking revoker when a VM-owned physical STOP
            // dispatcher exists. All siblings are already admission-sealed.
            // Parent DONE joins each dispatch receipt AND child finalization.
            if (physicalStopDispatcher != null) {
                for (ActorCell<?> child : descendants) {
                    // Exactly one accounting release even if a broken host
                    // dispatcher schedules a task and then throws.
                    java.util.concurrent.atomic.AtomicBoolean received =
                            new java.util.concurrent.atomic.AtomicBoolean();
                    Thread submittingThread = Thread.currentThread();
                    Runnable teardown = () -> {
                        Throwable failure = null;
                        try {
                            // Reject a host dispatcher that runs physical
                            // revokers inline. It could otherwise block the
                            // actor or the VM's CONTROL scheduler indefinitely.
                            if (Thread.currentThread() == submittingThread) {
                                throw new IllegalStateException(
                                        "physical STOP dispatcher executed teardown on submitting thread");
                            }
                            child.cancel(new ActorCancelledException(
                                    child.ref.id(),
                                    "parent actor " + ref.id() + " terminated"));
                        } catch (RuntimeException | Error stoppedFailure) {
                            failure = stoppedFailure;
                        } finally {
                            if (received.compareAndSet(false, true)) {
                                completeChildStopDispatch(failure);
                            }
                        }
                    };
                    try {
                        physicalStopDispatcher.execute(teardown, discarded -> {
                            // A queued task discarded by shutdown did not
                            // invoke child.cancel(). This receipt MUST report
                            // failure, even for a buggy dispatcher passing
                            // null; it must never manufacture a clean release.
                            Throwable rejected = discarded == null
                                    ? new IllegalStateException(
                                            "physical STOP discarded without failure reason")
                                    : discarded;
                            if (received.compareAndSet(false, true)) {
                                completeChildStopDispatch(rejected);
                            } else {
                                // A broken host may deliver duplicate or
                                // conflicting callbacks. Never treat that as
                                // a successful physical teardown.
                                quarantineChildStopFailure(
                                        new IllegalStateException(
                                                "duplicate physical STOP discard receipt",
                                                rejected));
                            }
                        });
                    } catch (RuntimeException | Error admissionFailure) {
                        // Rejecting after enqueue is a host-contract breach:
                        // quarantine even if the queued callback later runs.
                        if (received.compareAndSet(false, true)) {
                            completeChildStopDispatch(admissionFailure);
                        } else {
                            quarantineChildStopFailure(admissionFailure);
                        }
                    }
                }
            } else {
                // Standalone host runtime compatibility: no implicit new
                // pool/thread per actor and no CONTROL carrier may block here.
                // The universal sibling admission pre-fence still applies.
                for (ActorCell<?> child : descendants) {
                    try {
                        child.cancel(new ActorCancelledException(
                                child.ref.id(),
                                "parent actor " + ref.id() + " terminated"));
                    } catch (RuntimeException | Error failure) {
                        quarantineChildStopFailure(failure);
                    }
                }
            }

            // Establish receipts before requesting cancellation. A concurrent
            // cancellation may already have published isCancelled() while its
            // revocation hook or terminal subscribers are still executing;
            // only the winning settlement's notification-drain receipt joins
            // that physical work. Registration can itself immediately call
            // back when a Future has already fully settled.
            Throwable notificationRegistrationFailure = null;
            for (OresFuture<?> future : pending) {
                try {
                    future.whenNotificationsDrainedRuntime(() -> {
                        synchronized (lifecycleLock) {
                            if (pendingStopFutureNotifications <= 0) {
                                sourceStopCleanupAborted = true;
                                ref.terminationCause.compareAndSet(null,
                                        new OresScheduler.CleanupIncompleteError(
                                                "actor owned Future notification receipt underflow"));
                                lifecycleLock.notifyAll();
                                return;
                            }
                            pendingStopFutureNotifications--;
                            Throwable physicalFailure = future.cancellationHookFailureFromRuntime();
                            if (physicalFailure != null) {
                                sourceStopCleanupAborted = true;
                                ref.terminationCause.compareAndSet(null,
                                        new OresScheduler.CleanupIncompleteError(
                                                "actor STOP owned Future revocation failed",
                                                physicalFailure));
                            }
                            if (stopped.get() && !pendingOperationRevocationPending) {
                                finalizeStopLocked();
                            }
                            lifecycleLock.notifyAll();
                        }
                    });
                } catch (RuntimeException | Error failure) {
                    // A registration failure cannot be mistaken for a drain
                    // receipt. The outstanding count deliberately quarantines.
                    if (notificationRegistrationFailure == null) {
                        notificationRegistrationFailure = failure;
                    } else if (notificationRegistrationFailure != failure) {
                        notificationRegistrationFailure.addSuppressed(failure);
                    }
                }
            }
            if (notificationRegistrationFailure != null) {
                synchronized (lifecycleLock) {
                    sourceStopCleanupAborted = true;
                    ref.terminationCause.compareAndSet(null,
                            new OresScheduler.CleanupIncompleteError(
                                    "actor owned Future notification registration failed",
                                    notificationRegistrationFailure));
                    lifecycleLock.notifyAll();
                }
            }

            // The Future revocation must finish BEFORE arena reclamation,
            // not afterward. One failing revoker may not skip the others.
            // This is only the logical revoker phase: physical I/O / FFI
            // loan receipts remain a separate supervisor integration gate.
            Throwable revocationFailure = null;
            try {
                for (OresFuture<?> future : pending) {
                    try {
                        future.cancel(false);
                    } catch (RuntimeException | Error failure) {
                        if (revocationFailure == null) revocationFailure = failure;
                        else if (revocationFailure != failure) {
                            revocationFailure.addSuppressed(failure);
                        }
                    }
                }
            } finally {
                synchronized (lifecycleLock) {
                    pendingOperationRevocationPending = false;
                    if (revocationFailure != null) {
                        sourceStopCleanupAborted = true;
                        ref.terminationCause.compareAndSet(null,
                                new OresScheduler.CleanupIncompleteError(
                                        "actor owned Future revocation failed; quarantine arena",
                                        revocationFailure));
                    }
                    finalizeStopLocked();
                    lifecycleLock.notifyAll();
                }
            }

            // The snapshot and admission seal happened AT STOP under its
            // monitor. Publish cancellation only now, outside lifecycleLock:
            // synchronous Future subscribers must never re-enter a held lock.
            if (cleanupScheduler != null) {
                Throwable admissionFailure = null;
                try {
                    cancellationSnapshot.run();
                } catch (RuntimeException | Error failure) {
                    admissionFailure = failure;
                } finally {
                    synchronized (lifecycleLock) {
                        sourceStopCleanupPending = false;
                        // Cleanup-origin registration failures may race the
                        // STOP cancellation snapshot. Never erase an earlier
                        // fail-closed quarantine marker when the snapshot
                        // itself succeeds.
                        sourceStopCleanupAborted |= admissionFailure != null;
                        sourceStopCleanupActive = admissionFailure == null
                                && !forceKillFenced && !closed.get();
                        if (admissionFailure != null) {
                            ref.terminationCause.compareAndSet(null,
                                    new OresScheduler.CleanupIncompleteError(
                                            "actor source cancellation admission failed",
                                            admissionFailure));
                        }
                        finalizeStopLocked();
                        lifecycleLock.notifyAll();
                    }
                }
                if (admissionFailure == null || gracefulTeardownPending) schedule();
            }

            // Drain observer settlement is intentionally LAST. Host waiter
            // callbacks are synchronous and may block indefinitely, so they
            // cannot precede child STOP propagation, owned-Future revocation,
            // or scheduler lexical-cancellation requests. Track their physical
            // notification drains separately from guest resource receipts.
            Throwable drainFailure = cause == null ? terminated(ref) : cause;
            Throwable drainPublishFailure = null;
            for (DrainWaiter waiter : failedDrainWaiters) {
                OresFuture<Void> observer = waiter.completion();
                try {
                    observer.whenNotificationsDrainedRuntime(() -> {
                        synchronized (lifecycleLock) {
                            if (pendingStopDrainNotifications <= 0) {
                                sourceStopCleanupAborted = true;
                                ref.terminationCause.compareAndSet(null,
                                        new OresScheduler.CleanupIncompleteError(
                                                "actor STOP drain observer receipt underflow"));
                                lifecycleLock.notifyAll();
                                return;
                            }
                            pendingStopDrainNotifications--;
                            if (stopped.get() && !sourceStopCleanupPending
                                    && !pendingOperationRevocationPending) {
                                finalizeStopLocked();
                            }
                            lifecycleLock.notifyAll();
                        }
                    });
                    observer.failFromRuntime(drainFailure);
                } catch (RuntimeException | Error failure) {
                    // A failed observer registration/settlement cannot count
                    // as a completed drain. Preserve the count and quarantine,
                    // but always attempt every remaining observer.
                    if (drainPublishFailure == null) drainPublishFailure = failure;
                    else if (drainPublishFailure != failure) {
                        drainPublishFailure.addSuppressed(failure);
                    }
                }
            }
            if (drainPublishFailure != null) {
                synchronized (lifecycleLock) {
                    sourceStopCleanupAborted = true;
                    ref.terminationCause.compareAndSet(null,
                            new OresScheduler.CleanupIncompleteError(
                                    "actor STOP drain notification failed; quarantine arena",
                                    drainPublishFailure));
                    lifecycleLock.notifyAll();
                }
            }
        }
    }
}
