package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cleanup must not be lost when the runtime I/O pool has shut down. */
final class HttpResponseCleanupDispatcher {
    private HttpResponseCleanupDispatcher() { }

    static void dispatch(Executor io, Runnable cleanup) {
        Objects.requireNonNull(io, "io");
        Objects.requireNonNull(cleanup, "cleanup");
        AtomicBoolean started = new AtomicBoolean();
        Runnable once = () -> {
            // Also handle a nonconforming executor which runs and then throws.
            if (started.compareAndSet(false, true)) cleanup.run();
        };
        try {
            io.execute(once);
        } catch (RejectedExecutionException stopped) {
            // No socket I/O on the deadline or supervisor thread. This
            // independent virtual thread survives io.shutdownNow().
            Thread.ofVirtual().name("ores-http-cleanup-fallback").start(once);
        }
    }
}
