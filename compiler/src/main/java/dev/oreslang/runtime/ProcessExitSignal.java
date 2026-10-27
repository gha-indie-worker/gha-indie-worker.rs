package dev.oreslang.runtime;

/**
 * Internal control signal for an explicitly requested CLI exit.
 * No call to System.exit is made by the guest runtime or a hosted Graal Context.
 * Errors bypass catch(RuntimeException) guest traps while defer/finally unwind.
 * Suppressed cleanup diagnostics are retained; Java stack-trace collection is disabled.
 */
public final class ProcessExitSignal extends Error {
    private final int exitStatus;

    public ProcessExitSignal(int exitStatus) {
        super("Oreslang process exit " + exitStatus, null, true, false);
        if (exitStatus < 0 || exitStatus > 255) {
            throw new IllegalArgumentException("process exit status must be in 0..255");
        }
        this.exitStatus = exitStatus;
    }

    public int exitStatus() { return exitStatus; }
}
