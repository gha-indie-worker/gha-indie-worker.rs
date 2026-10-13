package dev.oreslang.runtime;

/**
 * Host-owned, numeric-only diagnostic events. No guest environment lookup,
 * formatting, stdio, or userland otel runs on an instrumented hot path.
 * Always guard expensive arguments with enabled() at the call site.
 */
public final class CoreDebug {
    public static final int ACTOR_POOLS_CREATED = 1;
    public static final int HTTP_LISTENER_READY = 2;
    public static final int HTTP_EXECUTOR_TASK_STARTED = 3;

    private CoreDebug() { }

    public static void configure(boolean enabled) {
        CorePerf.configureDebug(enabled);
    }

    public static boolean enabled() {
        return CorePerf.debugEnabled();
    }

    public static void event(int event, long value) {
        CorePerf.debugEvent(event, value);
    }
}
