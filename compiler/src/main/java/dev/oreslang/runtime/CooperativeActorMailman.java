package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Resumable ActorGroup Mailman contract for CONTROL-plane work that may wait.
 *
 * <p>The factory itself must be non-blocking and return a small state machine.
 * Each state-machine turn executes on the owning runtime's CONTROL scheduler.
 * Returning {@link OresScheduler#await(OresFuture)} suspends the logical Mailman
 * and releases the physical CONTROL carrier. Future completion only queues the
 * next CONTROL turn; it never executes Mailman/user code on the producer thread.</p>
 *
 * <p>The supplied {@link ActorGroupContext} is a logical capability for this
 * mail item. It may be captured by the returned task, but it is usable only
 * while one of that task's CONTROL turns is actively executing. Calls from
 * completion threads or between resumptions fail closed.</p>
 */
@FunctionalInterface
public interface CooperativeActorMailman<Out> {
    OresScheduler.Task<Void> createTask(
            ActorMail<Out> mail,
            ActorGroupContext context) throws Exception;

    /** Convenience task for a one-turn cooperative Mailman callback. */
    static OresScheduler.Task<Void> sync(CheckedRunnable callback) {
        Objects.requireNonNull(callback, "callback");
        return new OresScheduler.Task<>() {
            private boolean ran;

            @Override
            public OresScheduler.Step<Void> resume(OresScheduler.Resume resume)
                    throws Exception {
                if (ran || !resume.initial()) {
                    throw new IllegalStateException(
                            "cooperative Mailman sync task resumed more than once");
                }
                ran = true;
                callback.run();
                return OresScheduler.done(null);
            }
        };
    }

    @FunctionalInterface
    interface CheckedRunnable {
        void run() throws Exception;
    }
}
