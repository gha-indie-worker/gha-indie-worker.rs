package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-only substrate for bounded, nonblocking asynchronous actor resource
 * shutdown. Completion/timer threads ONLY publish a result and signal a
 * trusted CONTROL enqueue; they never execute Oreslang guest callbacks.
 *
 * <p>A timeout is NOT proof that native handles/FFI loans are quiescent. The
 * supervisor must revoke/fence outstanding work before reclaiming memory.
 * Source-level async tear_down lowering is deliberately not admitted here.</p>
 */
final class ActorAsyncTeardownGate {
    enum State { SUCCESS, FAILURE, TIMED_OUT }
    record Outcome(State state, Throwable error) {
        Outcome {
            Objects.requireNonNull(state);
            if ((state == State.FAILURE) != (error != null)) {
                throw new IllegalArgumentException("teardown failure cause must match terminal state");
            }
        }
    }

    @FunctionalInterface
    interface ControlEnqueue {
        /** Enqueue a CONTROL turn only. Never execute actor guest code inline. */
        void signal();
    }

    private final AtomicReference<Outcome> terminal = new AtomicReference<>();
    // The Future may never complete after timeout. Clear this reference on
    // first terminal event so the still-registered completion callback cannot
    // retain the actor's CONTROL closure and its guest state indefinitely.
    private final AtomicReference<ControlEnqueue> controlEnqueue = new AtomicReference<>();
    private final AtomicReference<Throwable> notificationFailure = new AtomicReference<>();
    // A one-nanosecond deadline can fire *inside* schedule(), before it
    // returns the ScheduledFuture. Never dereference an unassigned deadline.
    private final AtomicReference<ScheduledFuture<?>> deadline = new AtomicReference<>();

    ActorAsyncTeardownGate(CompletionStage<?> operation,
                           Duration timeout,
                           ScheduledExecutorService timer,
                           ControlEnqueue controlEnqueue) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(timer, "timer");
        this.controlEnqueue.set(Objects.requireNonNull(controlEnqueue, "controlEnqueue"));
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("actor teardown deadline must be positive");
        }
        final long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("actor teardown deadline too large", overflow);
        }
        // If scheduling is rejected, construction fails closed. Never arm a
        // Future without a timeout watcher.
        ScheduledFuture<?> scheduled = timer.schedule(
                () -> publish(new Outcome(State.TIMED_OUT, null)),
                nanos, TimeUnit.NANOSECONDS);
        deadline.set(scheduled);
        if (terminal.get() != null) cancelDeadline();
        try {
            operation.whenComplete((unused, error) -> publish(error == null
                    ? new Outcome(State.SUCCESS, null)
                    : new Outcome(State.FAILURE, unwrap(error))));
        } catch (Throwable registrationFailure) {
            cancelDeadline();
            throw registrationFailure;
        }
    }

    Optional<Outcome> outcome() {
        return Optional.ofNullable(terminal.get());
    }

    /** Rejected CONTROL notification is never silently mistaken for delivery.
     * The trusted caller must fail closed or let a supervisor watchdog poll
     * outcome(); this host-only gate cannot enqueue actor work itself. */
    Optional<Throwable> notificationFailure() {
        return Optional.ofNullable(notificationFailure.get());
    }

    private void publish(Outcome result) {
        if (!terminal.compareAndSet(null, result)) return;
        cancelDeadline();
        // This is intentionally just a trusted CONTROL enqueue, not an actor
        // callback. A failed enqueue must be handled by the caller's existing
        // fail-closed supervisor/host watchdog; outcome remains observable.
        ControlEnqueue enqueue = controlEnqueue.getAndSet(null);
        if (enqueue != null) {
            try {
                enqueue.signal();
            } catch (RuntimeException rejection) {
                notificationFailure.compareAndSet(null, rejection);
            }
        }
    }

    private void cancelDeadline() {
        ScheduledFuture<?> pending = deadline.getAndSet(null);
        if (pending != null) pending.cancel(false);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable value = failure;
        while (value instanceof java.util.concurrent.CompletionException
                && value.getCause() != null) {
            value = value.getCause();
        }
        return value;
    }
}
