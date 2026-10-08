package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;

/** Shared classification for actor supervision, guest effect handlers, and host diagnostics. */
public enum FailureKind {
    THROW, RAISE, PANIC, CANCELLATION, TERMINATION, VM_FATAL, HOST_FAULT;

    public static FailureKind classify(Throwable failure) {
        if (failure instanceof OresFailure.Throw) return THROW;
        if (failure instanceof OresFailure.Raise) return RAISE;
        if (failure instanceof OresFailure.Panic) return PANIC;
        if (failure instanceof ActorRuntime.ActorCancellationSignal
                || failure instanceof CancellationException) return CANCELLATION;
        if (failure instanceof ActorRuntime.ActorTerminatedException) return TERMINATION;
        if (failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError) return VM_FATAL;
        return HOST_FAULT;
    }
}
