package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Oreslang's runtime-owned Future primitive.
 *
 * <p>This is deliberately <em>not</em> a {@link CompletableFuture}. Oreslang
 * Future values do not expose a callback surface that can accidentally execute
 * guest code on an I/O, timer, JNI, or producer-completion thread. Runtime
 * components may register enqueue-only waiters through
 * {@link #whenCompleteRuntime(BiConsumer)}; actor/root schedulers decide when
 * the captured Oreslang continuation actually runs.</p>
 *
 * <p>Completion authority belongs to the runtime operation that created the
 * Future. Guest code may observe state, await, or request cancellation, but it
 * cannot forge a value/failure.</p>
 *
 * <p>Blocking host bridges ({@code get}, timed {@code get}, and {@code join})
 * reject pending reads inside actor execution. Settled reads and zero-timeout
 * polling remain available without parking an actor dispatcher.</p>
 */
public final class OresFuture<T> implements Future<T>, Awaitable<T> {
    private static final Object PENDING = new Object();

    /**
     * Single-shot completion capability used to adapt callback-only APIs.
     *
     * <p>Calling resolve/reject/cancel settles the target Future but never
     * resumes Oreslang guest code inline. Awaiting code is still resumed only
     * by its owning scheduler after the suspending turn has unwound.</p>
     */
    public interface Callback<T> {
        void resolve(T value);
        void reject(Throwable failure);
        void cancel();
        boolean isDone();

        default void complete(Throwable failure, T value) {
            if (failure == null) resolve(value);
            else reject(failure);
        }
    }

    public static final class AlreadySettledException extends IllegalStateException {
        public AlreadySettledException(String message) {
            super(message);
        }
    }

    private record Success<T>(T value) { }
    private record Failure(Throwable failure) { }
    private record Cancelled(CancellationException failure) { }

    /**
     * Runtime-only detachable completion waiter.
     *
     * <p>The waiter is also its own registration handle. Keeping the callback,
     * exactly-once claim bit, owner, and detach operation in one object avoids
     * allocating a second registration wrapper and captured removal lambda for
     * every genuinely suspending await.</p>
     *
     * <p>Detaching never changes the Future's producer/cancellation state. It
     * only prevents this runtime continuation waiter from retaining or being
     * invoked after its owning scheduler/task has been cancelled.</p>
     */
    static final class RuntimeWaiterRegistration<T> {
        private final OresFuture<T> owner;
        private final BiConsumer<? super T, ? super Throwable> callback;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private RuntimeWaiterRegistration(
                OresFuture<T> owner,
                BiConsumer<? super T, ? super Throwable> callback) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.callback = Objects.requireNonNull(callback, "callback");
        }

        boolean detach() {
            boolean detached = claimed.compareAndSet(false, true);
            // A concurrent settlement can have already claimed this waiter.
            // Still remove the registration from the producer queue: claiming
            // callback delivery and reclaiming queue retention are separate
            // obligations. This is idempotent if the producer already polled it.
            owner.waiters.remove(this);
            return detached;
        }
    }

    /** Released at terminal CAS so retained settled Futures cannot pin actor/channel state. */
    private volatile BooleanSupplier cancelAdmission;
    /** One-shot release after terminal subscribers complete; never retain owners afterwards. */
    private volatile Runnable onNotificationDrained;
    /**
     * Runtime subscribers may finish after the terminal state CAS. Actor arena
     * owners therefore need a receipt for the winning settlement's complete
     * notification drain, not merely isDone() or an early waiter callback.
     */
    private final Object notificationDrainLock = new Object();
    private List<Runnable> notificationDrainListeners;
    private boolean notificationDrainComplete;
    private final AtomicReference<Runnable> cancelHook;
    private final AtomicBoolean cancelHookRun = new AtomicBoolean();
    /** Runtime-owned physical resource revocation failures are never a clean release receipt. */
    private final AtomicReference<Throwable> cancelHookFailure = new AtomicReference<>();
    /**
     * A subscriber registering after the cancellation CAS must not run its
     * callback before the internal revoker. Deferring such a subscriber to
     * the winner's ordinary waiter drain also avoids reentrant deadlocks if
     * a cancellation hook registers another waiter on its own Future.
     */
    private final AtomicBoolean cancelRevocationComplete = new AtomicBoolean();
    // Only scheduler-owned cleanup-aware Task Futures use deferred cancellation.
    // An observer's cancel() requests the runtime to unwind without exposing
    // a false terminal state while guest defer/finally and arena loans remain.
    private final boolean deferredCancellation;
    // Only short terminal-admission arbitration is synchronized. Neither
    // revocation nor subscribers may execute while this lock is held.
    private final Object deferredAdmissionLock = new Object();
    private final AtomicReference<CancellationException> requestedCancellation =
            new AtomicReference<>();
    private final AtomicReference<Object> state = new AtomicReference<>(PENDING);
    private final ConcurrentLinkedQueue<RuntimeWaiterRegistration<T>> waiters = new ConcurrentLinkedQueue<>();

    public OresFuture() {
        this(() -> true, () -> { });
    }

    OresFuture(Runnable cancelHook) {
        this(() -> true, cancelHook);
    }

    /** Scheduler-owned terminal notification completion (not guest admission). */
    OresFuture(Runnable cancelHook, Runnable onNotificationDrained) {
        this(() -> true, cancelHook, onNotificationDrained);
    }

    /**
     * Runtime-only constructor for operations whose cancellation must win an
     * external arbitration before the Future transitions to cancelled.
     *
     * <p>This is used by channels/select so a cancellation racing with a ready
     * case cannot consume a channel value after cancellation has already won.
     * Guest code never receives the admission capability.</p>
     */
    OresFuture(BooleanSupplier cancelAdmission, Runnable cancelHook) {
        this(cancelAdmission, cancelHook, () -> { });
    }

    OresFuture(BooleanSupplier cancelAdmission, Runnable cancelHook,
            Runnable onNotificationDrained) {
        this(cancelAdmission, cancelHook, onNotificationDrained, false);
    }

    OresFuture(BooleanSupplier cancelAdmission, Runnable cancelHook,
            Runnable onNotificationDrained, boolean deferredCancellation) {
        this.deferredCancellation = deferredCancellation;
        this.cancelAdmission = Objects.requireNonNull(
                cancelAdmission, "cancelAdmission");
        this.cancelHook = new AtomicReference<>(
                Objects.requireNonNull(cancelHook, "cancelHook"));
        this.onNotificationDrained = Objects.requireNonNull(
                onNotificationDrained, "onNotificationDrained");
    }

    public static <T> OresFuture<T> completed(T value) {
        OresFuture<T> future = new OresFuture<>();
        future.completeFromRuntime(value);
        return future;
    }

    public static <T> OresFuture<T> failed(Throwable failure) {
        OresFuture<T> future = new OresFuture<>();
        future.failFromRuntime(Objects.requireNonNull(failure, "failure"));
        return future;
    }

    /**
     * Adapt a single-shot callback registration API into an Ores Future.
     *
     * <p>The registrar may invoke the callback synchronously. That only settles
     * the Future; it cannot re-enter an awaiting Oreslang frame. A second
     * callback settlement is rejected deterministically.</p>
     */
    public static <T> OresFuture<T> fromCallback(
            Consumer<? super Callback<T>> registrar) {
        Objects.requireNonNull(registrar, "registrar");
        OresFuture<T> future = new OresFuture<>();
        AtomicBoolean callbackClaimed = new AtomicBoolean();

        Callback<T> completion = new Callback<>() {
            private boolean claim(String operation) {
                if (!callbackClaimed.compareAndSet(false, true)) {
                    throw new AlreadySettledException(
                            "callback Future already settled; duplicate " + operation);
                }

                // Consumer cancellation may legitimately win before a foreign
                // callback arrives. The first late producer callback is then a
                // no-op rather than an exception escaping onto the producer's
                // thread. Marking the callback claimed still diagnoses any
                // subsequent duplicate callback invocation.
                return !future.isCancelled();
            }

            @Override
            public void resolve(T value) {
                if (!claim("resolve")) return;
                if (!future.completeFromRuntime(value) && !future.isCancelled()) {
                    throw new AlreadySettledException(
                            "callback Future was already settled before resolve");
                }
            }

            @Override
            public void reject(Throwable failure) {
                Objects.requireNonNull(failure, "failure");
                if (!claim("reject")) return;
                if (!future.failFromRuntime(failure) && !future.isCancelled()) {
                    throw new AlreadySettledException(
                            "callback Future was already settled before reject");
                }
            }

            @Override
            public void cancel() {
                if (!claim("cancel")) return;
                if (!future.cancel(false) && !future.isCancelled()) {
                    throw new AlreadySettledException(
                            "callback Future was already settled before cancel");
                }
            }

            @Override
            public boolean isDone() {
                return callbackClaimed.get() || future.isDone();
            }
        };

        try {
            registrar.accept(completion);
        } catch (Throwable failure) {
            // VM-fatal failures must escape regardless of whether the foreign
            // registrar has already settled the Future. Turning an unhandled
            // VM/linkage failure into an ordinary rejected Future is unsafe.
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            if (failure instanceof LinkageError fatal) throw fatal;

            // A normal registrar failure rejects only if the callback has not
            // already won the single-shot race. A late benign exception must
            // never overwrite a result the registrar already published.
            if (callbackClaimed.compareAndSet(false, true)) {
                future.failFromRuntime(failure);
            }
        }
        return future;
    }

    @Override
    public OresFuture<T> getAwaited() {
        return this;
    }

    @SuppressWarnings("unchecked")
    public static <T> OresFuture<T> from(OresFuture<? extends T> future) {
        return (OresFuture<T>) Objects.requireNonNull(future, "future");
    }

    /**
     * Host-interop adapter. The host CompletionStage may complete on any thread;
     * its callback only settles this OresFuture. It does not run guest code.
     */
    public static <T> OresFuture<T> from(CompletionStage<? extends T> stage) {
        Objects.requireNonNull(stage, "stage");
        Runnable cancelHook = stage instanceof Future<?> cancellable
                ? () -> cancellable.cancel(true)
                : () -> { };
        OresFuture<T> result = new OresFuture<>(cancelHook);
        try {
            stage.whenComplete((value, failure) -> {
                if (failure == null) {
                    result.completeFromRuntime(value);
                    return;
                }

                Throwable terminalFailure = unwrap(failure);
                final boolean cancelled;
                try {
                    // A host may convey cancellation through the terminal
                    // error even when its Future.isCancelled() flag is false.
                    // Conversely, an inconsistent host may throw from its
                    // cancellation probe. Neither may strand this adapter.
                    cancelled = terminalFailure instanceof CancellationException
                            || (stage instanceof Future<?> hostFuture
                                    && hostFuture.isCancelled());
                } catch (Throwable observationFailure) {
                    if (observationFailure instanceof VirtualMachineError fatal) throw fatal;
                    if (observationFailure instanceof ThreadDeath fatal) throw fatal;
                    if (observationFailure instanceof LinkageError fatal) throw fatal;
                    result.failFromRuntime(unwrap(observationFailure));
                    return;
                }

                if (cancelled) {
                    result.cancel(false);
                } else {
                    result.failFromRuntime(terminalFailure);
                }
            });
        } catch (Throwable failure) {
            // An untrusted host CompletionStage can fail while registering the
            // completion observer. Never strand the Ores Future in PENDING or
            // replace a result which raced registration failure. VM-fatal
            // exceptions retain their normal host failure semantics.
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            if (failure instanceof LinkageError fatal) throw fatal;
            result.failFromRuntime(unwrap(failure));
        }
        return result;
    }

    /**
     * Chain a callback-only operation after this Future on an explicit Ores
     * scheduler. Guest registrar code executes only as a scheduler turn.
     *
     * <p>If the callback fires synchronously, the dependent Future is already
     * settled when this turn returns Await, but TaskRunner still requires the
     * current turn to unwind before the continuation can be dispatched.</p>
     */
    public <U> OresFuture<U> attachCallback(
            OresScheduler scheduler,
            java.util.function.BiConsumer<? super T, ? super Callback<U>> registrar) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(registrar, "registrar");
        OresFuture<T> source = this;
        AtomicReference<OresFuture<U>> dependentRef = new AtomicReference<>();

        OresFuture<U> task = scheduler.start(new OresScheduler.Task<>() {
            private int pc;

            @Override
            public OresScheduler.Step<U> resume(OresScheduler.Resume resume) {
                if (pc == 0) {
                    if (!resume.initial()) {
                        throw new IllegalStateException(
                                "callback chain started with a non-initial resume");
                    }
                    pc = 1;
                    return OresScheduler.await(source);
                }

                if (pc == 1) {
                    if (resume.failure() != null) {
                        throw propagate(resume.failure());
                    }
                    @SuppressWarnings("unchecked")
                    T value = (T) resume.value();
                    OresFuture<U> dependent = OresFuture.fromCallback(
                            callback -> registrar.accept(value, callback));
                    dependentRef.set(dependent);
                    pc = 2;
                    return OresScheduler.await(dependent);
                }

                if (pc == 2) {
                    if (resume.failure() != null) {
                        throw propagate(resume.failure());
                    }
                    @SuppressWarnings("unchecked")
                    U value = (U) resume.value();
                    pc = 3;
                    return OresScheduler.done(value);
                }

                throw new IllegalStateException(
                        "callback Future chain resumed after completion");
            }

            private RuntimeException propagate(Throwable failure) {
                Throwable unwrapped = OresFuture.unwrap(failure);
                if (unwrapped instanceof RuntimeException runtime) return runtime;
                if (unwrapped instanceof Error error) throw error;
                return new RuntimeException(unwrapped);
            }
        });

        AtomicReference<RuntimeWaiterRegistration<U>> taskWaiter =
                new AtomicReference<>();

        Runnable detach = () -> {
            RuntimeWaiterRegistration<U> registration =
                    taskWaiter.getAndSet(null);
            if (registration != null) registration.detach();
        };

        OresFuture<U> exposed = new OresFuture<>(() -> {
            detach.run();
            task.cancel(true);

            // The callback-produced Future belongs to this chain. Marking it
            // cancelled prevents a late foreign callback from reviving work;
            // OresFuture.fromCallback safely drops the first such late callback.
            OresFuture<U> dependent = dependentRef.getAndSet(null);
            if (dependent != null) dependent.cancel(false);

            // The source may be shared by other consumers, so chain
            // cancellation deliberately never cancels it.
        });

        RuntimeWaiterRegistration<U> registration =
                task.whenCompleteRuntimeCancellable((value, failure) -> {
                    try {
                        if (exposed.isDone()) return;

                        OresFuture<U> dependent = dependentRef.get();
                        if (failure == null) {
                            exposed.completeFromRuntime(value);
                        } else if (task.isCancelled()
                                || source.isCancelled()
                                || (dependent != null && dependent.isCancelled())) {
                            exposed.cancel(false);
                        } else {
                            exposed.failFromRuntime(OresFuture.unwrap(failure));
                        }
                    } finally {
                        detach.run();
                        dependentRef.set(null);
                    }
                });

        taskWaiter.set(registration);
        if (exposed.isDone()) {
            detach.run();
            dependentRef.set(null);
        }

        return exposed;
    }

    boolean completeFromRuntime(T value) {
        return settle(new Success<>(value));
    }

    boolean failFromRuntime(Throwable failure) {
        return settle(new Failure(Objects.requireNonNull(failure, "failure")));
    }

    /**
     * Runtime-only completion subscription.
     *
     * <p>Callbacks registered here must be scheduler plumbing only: transition a
     * dependent Future, enqueue a continuation, or release runtime accounting.
     * They must never execute Oreslang guest code directly. A terminal
     * Future may notify a newly registered runtime waiter on this very call
     * stack (including an actor or producer thread). Therefore, this hook
     * must remain strictly host-only: source continuations must be queued
     * through an owning scheduler/actor mailbox, never invoked here.</p>
     */
    void whenCompleteRuntime(BiConsumer<? super T, ? super Throwable> callback) {
        whenCompleteRuntimeCancellable(callback);
    }

    RuntimeWaiterRegistration<T> whenCompleteRuntimeCancellable(
            BiConsumer<? super T, ? super Throwable> callback) {
        Objects.requireNonNull(callback, "callback");
        RuntimeWaiterRegistration<T> waiter =
                new RuntimeWaiterRegistration<>(this, callback);
        waiters.add(waiter);

        Object observed = state.get();
        if (observed != PENDING
                && (!(observed instanceof Cancelled)
                        || cancelRevocationComplete.get())) {
            // Cancellation state is visible immediately at CAS, but ordinary
            // runtime subscribers must not run until internal revocation.
            // If revocation is still underway, leave this registration in
            // the queue: the cancelling thread will drain it afterwards.
            // Otherwise remove/claim atomically against the settlement drain
            // so the callback runs no more than once.
            waiters.remove(waiter);
            notifyWaiter(waiter, observed);
        }
        return waiter;
    }

    /**
     * Host/runtime-only, exactly once after the winning terminal settlement
     * has drained its registered callbacks and its own drain hook. Registration
     * linearizes with draining, even if terminal state is already visible.
     * This is a physical host-notification receipt, not a guest continuation.
     */
    void whenNotificationsDrainedRuntime(Runnable receipt) {
        Objects.requireNonNull(receipt, "receipt");
        boolean alreadyDrained;
        synchronized (notificationDrainLock) {
            alreadyDrained = notificationDrainComplete;
            if (!alreadyDrained) {
                if (notificationDrainListeners == null) {
                    notificationDrainListeners = new ArrayList<>();
                }
                notificationDrainListeners.add(receipt);
            }
        }
        if (alreadyDrained) receipt.run();
    }

    int pendingRuntimeWaiterCount() {
        return waiters.size();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (state.get() != PENDING) return false;
        // Capture the admission capability locally: a concurrent terminal
        // transition may clear the field, but settlement CAS remains final.
        BooleanSupplier admission = cancelAdmission;
        if (admission == null || !admission.getAsBoolean()) return false;

        CancellationException cancelled =
                new CancellationException("OresFuture was cancelled");
        if (deferredCancellation) {
            // This request is not terminal. The owner must dispatch its
            // authenticated unwind continuation before publication.
            synchronized (deferredAdmissionLock) {
                if (state.get() != PENDING || requestedCancellation.get() != null) {
                    return false;
                }
                requestedCancellation.set(cancelled);
            }
            invokeCancelHook();
            return true;
        }
        // Revoke the owning operation BEFORE any synchronous subscriber can
        // block terminal notification. isCancelled() becomes visible at the
        // successful state CAS; guest/producer authority must then be removed
        // before ordinary host completion callbacks are entered.
        return settle(new Cancelled(cancelled), this::invokeCancelHook);
    }

    /** Trusted scheduler-only observation of a pending terminal-unwind request. */
    CancellationException deferredCancellationCause() {
        return requestedCancellation.get();
    }

    /** Settle cancelled ONLY after the cleanup continuation physically exits. */
    boolean completeDeferredCancellationFromRuntime() {
        CancellationException cause = requestedCancellation.get();
        if (!deferredCancellation || cause == null) {
            throw new IllegalStateException("no deferred cancellation to complete");
        }
        return settle(new Cancelled(cause), () -> { }, true);
    }

    /** Cleanup lane unavailable: terminal error is not a safe cleanup receipt. */
    boolean failDeferredCleanupFromRuntime(Throwable failure) {
        if (!deferredCancellation || requestedCancellation.get() == null) {
            throw new IllegalStateException("no deferred cancellation to fail");
        }
        return settle(new Failure(Objects.requireNonNull(failure, "failure")),
                () -> { }, true);
    }

    @Override
    public boolean isCancelled() {
        return state.get() instanceof Cancelled;
    }

    @Override
    public boolean isDone() {
        return state.get() != PENDING;
    }

    /**
     * Allocation-free runtime observation used by scheduler await fast paths.
     *
     * <p>A non-null value is the Future's immutable terminal state object. A
     * null result means settlement or cancellation revocation is still pending.
     * Callers must register normally so cancellation cannot resume a guest
     * continuation before its owning operation has been revoked.</p>
     */
    Object runtimeTerminalStateOrNull() {
        Object observed = state.get();
        return observed == PENDING
                || (observed instanceof Cancelled && !cancelRevocationComplete.get())
                ? null : observed;
    }

    /**
     * Select readiness must respect cancellation's revocation barrier, not
     * merely the early state CAS. A cancelled producer is not selectable until
     * its internal cancel hook has finished; subscribed wakeups are delivered
     * after that barrier by the normal runtime waiter drain.
     */
    Object runtimeSelectReadyStateOrNull() {
        Object observed = state.get();
        if (observed == PENDING
                || (observed instanceof Cancelled && !cancelRevocationComplete.get())) {
            return null;
        }
        return observed;
    }

    /**
     * Decode a terminal state previously returned by
     * {@link #runtimeTerminalStateOrNull()} without allocating a wrapper.
     */
    static Object runtimeTerminalValue(Object terminal) {
        if (terminal instanceof Success<?> success) return success.value();
        if (terminal instanceof Failure || terminal instanceof Cancelled) return null;
        throw new IllegalArgumentException("terminal Future state required");
    }

    /**
     * Decode the failure/cancellation of a terminal state previously returned
     * by {@link #runtimeTerminalStateOrNull()} without allocating a wrapper.
     */
    static Throwable runtimeTerminalFailure(Object terminal) {
        if (terminal instanceof Success<?>) return null;
        if (terminal instanceof Failure failed) return failed.failure();
        if (terminal instanceof Cancelled cancelled) return cancelled.failure();
        throw new IllegalArgumentException("terminal Future state required");
    }

    /**
     * Read-only compatibility observation used by runtime/tests. As with
     * CompletableFuture, cancellation is also an exceptional terminal state.
     */
    public boolean isCompletedExceptionally() {
        Object observed = state.get();
        return observed instanceof Failure || observed instanceof Cancelled;
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        Object observed = awaitState(0L, null);
        return reportGet(observed);
    }

    @Override
    public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("timeout must be non-negative");
        Object observed = awaitState(timeout, unit);
        if (observed == PENDING) {
            throw new TimeoutException("OresFuture did not complete before timeout");
        }
        return reportGet(observed);
    }

    /**
     * Host/embedder blocking bridge. Oreslang actor/root lowering must use the
     * scheduler suspension ABI rather than calling join on a carrier.
     */
    public T join() {
        Object observed = state.get();
        boolean interrupted = false;
        if (observed == PENDING) {
            requireBlockingAllowed();
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            whenCompleteRuntime((value, failure) -> done.countDown());
            for (;;) {
                try {
                    done.await();
                    break;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
            observed = state.get();
        }
        if (interrupted) Thread.currentThread().interrupt();
        return reportJoin(observed);
    }

    /**
     * Runtime callback adapters use this to compose an Ores Future with APIs
     * that still require CompletionStage. Guest/language code should never
     * receive the returned stage.
     */
    CompletionStage<T> asCompletionStage() {
        CompletableFuture<T> bridge = new CompletableFuture<>();
        RuntimeWaiterRegistration<T> waiter =
                whenCompleteRuntimeCancellable((value, failure) -> {
                    if (failure == null) bridge.complete(value);
                    else bridge.completeExceptionally(failure);
                });
        // The bridge is only an observation adapter: cancelling it must
        // release the producer listener, never cancel the producer itself.
        // Registering after the producer subscription also covers a source
        // that has already settled synchronously.
        bridge.whenComplete((ignored, failure) -> waiter.detach());
        return bridge;
    }

    private void invokeCancelHook() {
        Runnable hook = cancelHook.getAndSet(null);
        if (hook != null && cancelHookRun.compareAndSet(false, true)) {
            try {
                hook.run();
            } catch (RuntimeException | Error failure) {
                // The terminal cancellation remains authoritative and other
                // subscribers must still be notified. Preserve the physical
                // revoker failure for actor arena-retirement/quarantine checks.
                cancelHookFailure.compareAndSet(null, failure);
            }
        }
    }

    /** Host-only inspection after a terminal notification-drain receipt. */
    Throwable cancellationHookFailureFromRuntime() {
        return cancelHookFailure.get();
    }

    private boolean settle(Object terminal) {
        return settle(terminal, () -> { });
    }

    private boolean settle(Object terminal, Runnable afterStateClaimed) {
        return settle(terminal, afterStateClaimed, false);
    }

    private boolean settle(Object terminal, Runnable afterStateClaimed,
            boolean cleanupAuthority) {
        if (deferredCancellation) {
            synchronized (deferredAdmissionLock) {
                // Once cancellation was requested, only its authenticated
                // cleanup terminator may settle the result. A concurrent
                // ordinary success/failure must not silently defeat unwind.
                if (requestedCancellation.get() != null && !cleanupAuthority) return false;
                if (!state.compareAndSet(PENDING, terminal)) return false;
            }
        } else if (!state.compareAndSet(PENDING, terminal)) return false;

        // The operation has irrevocably settled. The admission predicate may
        // capture a SelectRegistration, its cases, channels and write values;
        // keeping it on a completed Future retains the whole graph. A racing
        // cancellation holds its own local predicate and must still win the
        // terminal CAS to change the result, so releasing the stored reference
        // here does not weaken cancellation arbitration.
        cancelAdmission = null;
        // Completion/failure also irrevocably defeats cancellation; release
        // its revoker now, not after a synchronous subscriber eventually
        // returns. Cancellation itself uses invokeCancelHook() after CAS.
        if (!(terminal instanceof Cancelled)) cancelHook.set(null);

        try {
            // The winning cancellation claim runs its internal revoker before
            // notifying potentially blocking synchronous host subscribers.
            // Publish revocation completion BEFORE draining registrations:
            // later registrations can then notify directly without waiting.
            if (terminal instanceof Cancelled) {
                try {
                    afterStateClaimed.run();
                } finally {
                    cancelRevocationComplete.set(true);
                }
            } else {
                afterStateClaimed.run();
            }
            RuntimeWaiterRegistration<T> waiter;
            while ((waiter = waiters.poll()) != null) {
                notifyWaiter(waiter, terminal);
            }
            return true;
        } finally {
            // Terminal state can be visible long before a synchronous waiter
            // returns. A bounded host notification permit cannot be reused
            // just because isDone() became true during a blocked callback.
            Runnable drained = onNotificationDrained;
            onNotificationDrained = null;
            // There is exactly one winning terminal CAS, hence one release.
            // Run the existing scheduler receipt before publishing the general
            // host-notification drain receipt used by actor resource owners.
            try {
                if (drained != null) drained.run();
            } finally {
                List<Runnable> receipts;
                synchronized (notificationDrainLock) {
                    notificationDrainComplete = true;
                    receipts = notificationDrainListeners;
                    notificationDrainListeners = null;
                }
                if (receipts != null) {
                    for (Runnable receipt : receipts) {
                        try {
                            receipt.run();
                        } catch (RuntimeException | Error ignored) {
                            // One broken runtime observer cannot suppress the
                            // remaining physical notification-drain receipts.
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void notifyWaiter(RuntimeWaiterRegistration<T> waiter, Object terminal) {
        if (!waiter.claimed.compareAndSet(false, true)) return;
        try {
            if (terminal instanceof Success<?> success) {
                waiter.callback.accept((T) success.value(), null);
            } else if (terminal instanceof Failure failed) {
                waiter.callback.accept(null, failed.failure());
            } else if (terminal instanceof Cancelled cancelled) {
                waiter.callback.accept(null, cancelled.failure());
            } else {
                throw new IllegalStateException("attempted to notify waiter from pending Future");
            }
        } catch (RuntimeException | Error ignored) {
            // Runtime waiter failures must not stop delivery to other waiters or
            // mutate the settled Future. Scheduler plumbing owns its own
            // failure path.
        }
    }

    private Object awaitState(long timeout, TimeUnit unit) throws InterruptedException {
        Object observed = state.get();
        if (observed != PENDING) return observed;
        // Zero-timeout polling observes state without registering an actor waiter.
        // The public timed-get API rejects negative timeouts.
        if (unit != null && timeout == 0L) return PENDING;
        requireBlockingAllowed();

        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        RuntimeWaiterRegistration<T> waiter =
                whenCompleteRuntimeCancellable((value, failure) -> done.countDown());
        try {
            if (unit == null) {
                done.await();
            } else if (!done.await(timeout, unit)) {
                // Timed polling must not retain one producer waiter per
                // timeout until the producer eventually settles.
                return state.get();
            }
            return state.get();
        } finally {
            // An interrupted host waiter is also no longer interested in
            // settlement. The claimed flag arbitrates with completion drain.
            // Never detach a runtime waiter while holding a channel lock.
            waiter.detach();
        }
    }

    private static void requireBlockingAllowed() {
        if (ActorRuntime.inActorExecution()) {
            throw new IllegalStateException(
                    "await would block an actor dispatcher carrier; actor continuation lowering must suspend/resume the mailbox turn");
        }
    }

    @SuppressWarnings("unchecked")
    private T reportGet(Object terminal) throws ExecutionException {
        if (terminal instanceof Success<?> success) return (T) success.value();
        if (terminal instanceof Failure failed) throw new ExecutionException(failed.failure());
        if (terminal instanceof Cancelled cancelled) throw cancelled.failure();
        throw new IllegalStateException("Future is still pending");
    }

    @SuppressWarnings("unchecked")
    private T reportJoin(Object terminal) {
        if (terminal instanceof Success<?> success) return (T) success.value();
        if (terminal instanceof Failure failed) {
            throw new CompletionException(failed.failure());
        }
        if (terminal instanceof Cancelled cancelled) throw cancelled.failure();
        throw new IllegalStateException("Future is still pending");
    }

    /**
     * Completion remains runtime-owned. These methods intentionally exist so
     * Java interop receives an explicit failure instead of silently gaining a
     * completion capability.
     */
    public boolean complete(T value) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public boolean completeExceptionally(Throwable ex) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public CompletableFuture<T> completeAsync(
            Supplier<? extends T> supplier,
            java.util.concurrent.Executor executor) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public CompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException(
                "OresFuture completion is runtime-owned; use an Oreslang timeout combinator");
    }

    public CompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException(
                "OresFuture completion is runtime-owned; use an Oreslang timeout combinator");
    }

    public void obtrudeValue(T value) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public void obtrudeException(Throwable ex) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
