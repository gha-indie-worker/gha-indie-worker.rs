package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host/runtime bridge for a cancellable scheduler task.
 *
 * <p>The existing result Future can settle on cancellation while its guest
 * continuation is still running. {@link #getDone()} has a distinct completion
 * barrier: it publishes only after BOTH the result is terminal and the owning
 * OresScheduler reports that the guest turn is quiescent. This is not a
 * source-language Task type, nor a substitute for actor/tenant permission
 * checking or structured child-scope finalization.</p>
 */
public final class TaskHandle<T> {
    /** Terminal outcome after scheduler-confirmed quiescence. */
    public sealed interface TaskOutcome<T>
            permits Success, Cancelled, Failed { }

    public record Success<T>(T value) implements TaskOutcome<T> { }

    public record Cancelled<T>(String reason) implements TaskOutcome<T> {
        public Cancelled {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Failed<T>(Throwable failure) implements TaskOutcome<T> {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }

    /**
     * Only scheduler lowering can create this marker. A user-raised
     * CancellationException must remain Failed if it wins terminal settlement.
     * A cancellation request already marked before Future binding must still
     * report Cancelled even if a first resume races the binding path.
     */
    private static final class CancelledAtResume extends CancellationException {
        private static final long serialVersionUID = 1L;
        // Java Throwable is Serializable. Never export a Task's internal
        // cancellation graph or execution-domain capability with an error.
        // A deserialized marker has no authority and is an ordinary failure.
        private final transient TaskCancellation authority;
        private final transient Object taskDomain;

        CancelledAtResume(TaskCancellation authority, Object taskDomain) {
            super("task cancelled at a cooperative scheduler checkpoint");
            this.authority = Objects.requireNonNull(authority, "authority");
            this.taskDomain = Objects.requireNonNull(taskDomain, "taskDomain");
        }
    }

    /**
     * Scheduler-private classification. The marker carries the actual owner
     * and running logical Task domain, so replay on another Task cannot
     * cancel its result Future. A mere CancellationException is never enough.
     */
    static boolean isAuthenticatedCheckpointCancellation(Throwable failure) {
        return failure instanceof CancelledAtResume marker
                && marker.authority != null
                && marker.taskDomain != null
                && marker.authority.isCancelled()
                && marker.taskDomain == OresScheduler.currentTaskDomain();
    }

    /**
     * Runtime-private acquisition bridge. The current Task may transfer a
     * logical, cross-thread-releasable lease into its own retirement stack.
     * An actor/source guest MUST NOT acquire arbitrary Java lock ownership.
     */
    static boolean isCurrentTrackedTaskTurn() {
        CancellationContext current = CURRENT_CANCELLATION.get();
        return current != null
                && current.taskDomain() == OresScheduler.currentTaskDomain();
    }

    static TaskOwnedLeases.Lease registerTransferableLease(
            TaskOwnedLeases.TransferableRelease release) {
        return registerTransferableLease(release, release);
    }

    static TaskOwnedLeases.Lease registerTransferableLease(
            TaskOwnedLeases.TransferableRelease ordinary,
            TaskOwnedLeases.TransferableRelease abandoned) {
        CancellationContext current = CURRENT_CANCELLATION.get();
        if (!isCurrentTrackedTaskTurn()) {
            throw new IllegalStateException(
                    "task lease registration requires current tracked Task turn");
        }
        return current.leases().register(ordinary, abandoned);
    }

    /** Called before an async mutex waiter can become visible to a granting carrier. */
    static void registerPhysicalSettlement(CompletableFuture<Void> receipt) {
        CancellationContext current = CURRENT_CANCELLATION.get();
        if (!isCurrentTrackedTaskTurn()) {
            throw new IllegalStateException(
                    "task physical settlement registration requires current tracked Task turn");
        }
        current.leases().trackPhysicalSettlement(receipt);
    }

    static boolean isCurrentLeaseOwner(TaskOwnedLeases.Registry owner) {
        CancellationContext current = CURRENT_CANCELLATION.get();
        return current != null
                && current.taskDomain() == OresScheduler.currentTaskDomain()
                && current.leases() == owner;
    }

    // Only the executing host Task turn can install this token. It is restored
    // after each resume, including nested inline scheduler dispatches, and is
    // never exposed as an actor/tenant cancellation capability.
    private record CancellationContext(
            TaskCancellation token, Object taskDomain,
            TaskOwnedLeases.Registry leases) { }

    private static final ThreadLocal<CancellationContext> CURRENT_CANCELLATION =
            new ThreadLocal<>();

    /**
     * The token is valid ONLY for the matching logical scheduler Task domain.
     * An untracked Task scheduled inline inside a tracked turn must not
     * inherit its parent's cancellation request (or cancellation identity).
     */
    static boolean currentCancellationRequested() {
        CancellationContext context = CURRENT_CANCELLATION.get();
        return context != null
                && context.taskDomain() == OresScheduler.currentTaskDomain()
                && context.token().isCancelled();
    }

    /** Compiler/host safepoint only: never call while holding guest locks. */
    static void checkCurrentCancellationCheckpoint() {
        if (!currentCancellationRequested()) return;
        CancellationContext context = CURRENT_CANCELLATION.get();
        throw new CancelledAtResume(context.token(), context.taskDomain());
    }

    private final TaskCancellation cancellation;
    private final TaskOwnedLeases.Registry ownedLeases = new TaskOwnedLeases.Registry();
    // Only host release tokens can be retired here; no guest defer callback
    // may execute on a Future notification or actor control thread.
    private final OresFuture<List<Throwable>> cleanupFailures =
            new OresFuture<>(() -> false, () -> { });
    // Observation only: cancelling getDone() must never forge a quiescence
    // witness or suppress the scheduler's eventual terminal outcome.
    private final OresFuture<TaskOutcome<T>> done =
            new OresFuture<>(() -> false, () -> { });
    private final AtomicReference<OresFuture<T>> result = new AtomicReference<>();
    private final AtomicReference<TaskOutcome<T>> settledOutcome = new AtomicReference<>();
    private final AtomicBoolean quiescent = new AtomicBoolean();
    // Only one thread may seal child admission / subscribe to descendant
    // retirement. Publishing remains distinct from installing this barrier.
    private final AtomicBoolean retirementArmed = new AtomicBoolean();
    private final AtomicBoolean published = new AtomicBoolean();
    // Cancellation requests may arrive on arbitrary host threads, including
    // a parent actor stopping while this Task executes guest code. Serialize
    // entering/leaving a resumable turn with revocation: a running turn is
    // allowed to reach its boundary and run Java finally cleanup before its
    // result is cancelled. No carrier is interrupted.
    private final Object resumeBoundaryLock = new Object();
    private boolean inResume;
    // A guest exception wins over a concurrent cancellation request once
    // it has escaped the guest turn. Do not erase it with source.cancel().
    private boolean guestFailureInFlight;
    private final TaskCancellation.Subscription revoker;

    private TaskHandle(TaskCancellation parent) {
        cancellation = parent == null ? new TaskCancellation() : parent.child();
        // The source Future might not exist when cancellation arrives. bind()
        // rechecks the token after publication, and the admission wrapper
        // checks it at every compiler-generated resume boundary.
        revoker = cancellation.onRevoke(this::cancelResultIfBound);
    }

    /** The result Future may become terminal before the physical task exits. */
    public OresFuture<T> result() {
        OresFuture<T> current = result.get();
        if (current == null) {
            throw new IllegalStateException("task admission is not complete");
        }
        return current;
    }

    /**
     * Nonparking observation suitable for Oreslang await lowering. Unlike
     * result(), completion is held until guest execution has physically left
     * its scheduler turn. No scheduler carrier may block on its host get/join.
     */
    public OresFuture<TaskOutcome<T>> getDone() {
        return done;
    }

    /**
     * The exact failures from nonblocking host-owned lease releases.
     * This observer is uncancellable and only settles before getDone().
     * A nonempty result is NOT a proven lock release/reclamation barrier.
     */
    public OresFuture<List<Throwable>> cleanupFailures() {
        return cleanupFailures;
    }

    /**
     * Requests cooperative cancellation. A queued/suspended task is revoked
     * promptly; a running guest turn is allowed to finish its current
     * compiler-generated resume and unwind local finally blocks first.
     * Cancellation is observed no later than its next resume boundary.
     * This does not interrupt native/FFI code or promise to run suspended
     * async-finally state machines (a separate lowering contract).
     */
    public boolean cancel(String reason) {
        // Validate even on an already-terminal handle: a caller must not be
        // able to bypass the public cancellation contract by racing completion.
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.length() > 256) {
            throw new IllegalArgumentException(
                    "reason must contain 1..256 nonblank characters");
        }
        // Cancellation is a request against live work. A terminal result is
        // immutable even when its physical quiescence notification is pending.
        OresFuture<T> current = result.get();
        if (done.isDone() || (current != null && current.isDone())) return false;
        return cancellation.cancel(reason);
    }

    public boolean cancel() {
        return cancel("cancelled");
    }

    public boolean isCancellationRequested() {
        return cancellation.isCancelled();
    }

    private void cancelResultIfBound() {
        OresFuture<T> current;
        synchronized (resumeBoundaryLock) {
            if (inResume || guestFailureInFlight) return;
            current = result.get();
        }
        if (current != null) current.cancel(false);
    }

    /**
     * The only TaskHandle-controlled guest entry point. The token check and
     * execution lease are atomic with revocation's decision about whether to
     * cancel the result immediately. Any request during guest execution is
     * delivered AFTER the guest turn has exited this wrapper.
     */
    private OresScheduler.Step<T> resumeAtCancellationBoundary(
            OresScheduler.Resume resume, OresScheduler.Task<T> task)
            throws Exception {
        synchronized (resumeBoundaryLock) {
            if (cancellation.isCancelled()) {
                throw new CancelledAtResume(cancellation, OresScheduler.currentTaskDomain());
            }
            inResume = true;
        }
        // An OresScheduler Task can be resumed by an inline executor. Restore
        // the prior logical context so nested turns do not overwrite the
        // cancellation authority of the caller.
        CancellationContext previous = CURRENT_CANCELLATION.get();
        CURRENT_CANCELLATION.set(
                new CancellationContext(
                        cancellation, OresScheduler.currentTaskDomain(), ownedLeases));
        boolean returnedNormally = false;
        try {
            // Invalid null Steps remain a guest failure rather than being
            // rewritten to cancellation by a concurrent shutdown request.
            OresScheduler.Step<T> step = Objects.requireNonNull(
                    task.resume(resume), "OresScheduler task returned null Step");
            returnedNormally = true;
            return step;
        } catch (Throwable failure) {
            synchronized (resumeBoundaryLock) {
                guestFailureInFlight = true;
            }
            throw failure;
        } finally {
            if (previous == null) CURRENT_CANCELLATION.remove();
            else CURRENT_CANCELLATION.set(previous);
            synchronized (resumeBoundaryLock) {
                inResume = false;
            }
            // A completed guest resume is a cancellation checkpoint. Throw
            // the authenticated runtime marker instead of reaching into the
            // source Future: inline runtime schedulers may complete a guest
            // turn before TaskHandle.start() has even bound that Future.
            // The scheduler will make the Future genuinely cancelled before
            // any next resume is admitted, and after Java finally cleanup.
            if (returnedNormally && cancellation.isCancelled()) {
                throw new CancelledAtResume(
                        cancellation, OresScheduler.currentTaskDomain());
            }
        }
    }

    private void onQuiescent() {
        quiescent.set(true);
        maybePublish();
    }

    private void onSettled(OresFuture<T> source, T value, Throwable failure) {
        final TaskOutcome<T> outcome;
        Throwable unwrapped = failure == null ? null : OresFuture.unwrap(failure);
        if (unwrapped == null) {
            outcome = new Success<>(value);
        } else if (source.isCancelled()
                || (unwrapped instanceof CancelledAtResume marker
                        && marker.authority == cancellation
                        && cancellation.isCancelled())) {
            outcome = new Cancelled<>(Objects.requireNonNullElse(
                    cancellation.cancellationReason(), "cancelled"));
        } else {
            // A guest may throw CancellationException without there being a
            // runtime cancellation request: that is a failure, not proof of
            // cancellation, and must not silently hide a task bug.
            outcome = new Failed<>(unwrapped);
        }
        settledOutcome.compareAndSet(null, outcome);
        maybePublish();
    }

    private void maybePublish() {
        if (!quiescent.get() || settledOutcome.get() == null) return;
        if (!retirementArmed.compareAndSet(false, true)) return;
        // A cancelled child leaves the *revocation* graph immediately, but
        // may still be executing guest code. Close admission atomically with
        // subscribing to the distinct physical child-lifetime barrier.
        // This callback never runs while the cancellation graph lock is held.
        if (cancellation.sealAndObserveChildQuiescence(this::publishAfterChildren)) {
            publishAfterChildren();
        }
    }

    private void publishAfterChildren() {
        // A cancelled Future is NOT permission to unlock state while guest
        // code or descendants still run. Only here, after physical retirement
        // and descendant joins, may transferable logical lock leases retire.
        // Thread-affine Java locks MUST instead unwind on the original carrier.
        // The release callbacks are synchronous, but a dequeued mutex grant
        // may still be physically held by a foreign notification carrier.
        // Observe its independently registered receipt without parking this
        // scheduler carrier or falsely publishing getDone().
        ownedLeases.releaseOnRetirementAsync().whenComplete((releaseErrors, failure) -> {
            if (failure != null) {
                Throwable root = OresFuture.unwrap(failure);
                publishAfterPhysicalSettlements(List.of(root));
            } else {
                publishAfterPhysicalSettlements(releaseErrors);
            }
        });
    }

    private void publishAfterPhysicalSettlements(List<Throwable> releaseErrors) {
        cleanupFailures.completeFromRuntime(releaseErrors);

        // Never claim done before lifecycle retirement and cleanup were
        // attempted. Retained host release errors fail physical completion;
        // callers must NOT reclaim arenas on a Failed cleanup outcome.
        cancellation.retire();
        revoker.close();
        if (!published.compareAndSet(false, true)) return;
        TaskOutcome<T> outcome = settledOutcome.get();
        if (!releaseErrors.isEmpty()) {
            outcome = new Failed<>(new TaskOwnedLeases.CleanupFailure(
                    releaseErrors, outcome));
        }
        done.completeFromRuntime(outcome);
    }

    /**
     * Trusted scheduler factory. This staging API deliberately creates no
     * source-visible Task or implicit actor scope. Parent ownership will be
     * wired by the subsequent explicit TaskScope/actor integration.
     */
    static <T> TaskHandle<T> start(OresScheduler scheduler, OresScheduler.Task<T> task) {
        return start(scheduler, task, null);
    }

    static <T> TaskHandle<T> start(
            OresScheduler scheduler, OresScheduler.Task<T> task,
            TaskCancellation parent) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(task, "task");
        TaskHandle<T> handle = new TaskHandle<>(parent);
        OresFuture<T> source;
        try {
            source = scheduler.start(
                    resume -> handle.resumeAtCancellationBoundary(resume, task),
                    handle::onQuiescent);
        } catch (RuntimeException | Error failure) {
            handle.revoker.close();
            handle.cancellation.cancel("admission rejected");
            handle.cancellation.retire();
            throw failure;
        }

        if (!handle.result.compareAndSet(null, source)) {
            throw new IllegalStateException("TaskHandle result bound more than once");
        }
        source.whenCompleteRuntime((value, failure) ->
                handle.onSettled(source, value, failure));
        // Cancellation can win before bind, after bind, or during subscriber
        // registration. The second check makes the early request observable.
        if (handle.cancellation.isCancelled()) handle.cancelResultIfBound();
        return handle;
    }
}
