package dev.oreslang.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Bounded admission for host stream suppliers. A hostile supplier cannot be
 * forcibly stopped by Java: its permit remains occupied until it actually
 * returns. The request writer, however, stops waiting at cancellation/deadline
 * and late streams are immediately closed rather than installed on a dead
 * exchange. A separate permit bounds how many stuck supplier calls can exist.
 */
final class HttpResponseBodyOpenGate {
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private final Semaphore slots;
    private final Executor executor;

    HttpResponseBodyOpenGate(int capacity, Executor executor) {
        if (capacity < 1) throw new IllegalArgumentException("body opener capacity must be positive");
        this.slots = new Semaphore(capacity);
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    int availableSlots() { return slots.availablePermits(); }

    InputStream open(HttpResponseDraft draft, BooleanSupplier cancelled, long waitNanos) throws IOException {
        Objects.requireNonNull(draft, "response");
        Objects.requireNonNull(cancelled, "cancelled");
        // Dispatch/queue delay consumes the same deadline as supplier startup.
        // Never restart the timer after Executor.execute() returns.
        long started = System.nanoTime();
        if (cancelled.getAsBoolean()) throw new IOException("HTTP response body opening cancelled");
        if (waitNanos <= 0) throw new IOException("HTTP response body opening deadline expired");
        if (!slots.tryAcquire()) throw new IOException("HTTP response body opener admission exhausted");

        var result = new CompletableFuture<InputStream>();
        var abandoned = new AtomicBoolean();
        var openingThread = new AtomicReference<Thread>();
        try {
            executor.execute(() -> {
                Thread current = Thread.currentThread();
                openingThread.set(current);
                try {
                    if (abandoned.get()) return;
                    InputStream input = draft.openBody();
                    result.complete(input);
                    if (abandoned.get()) closeQuietly(input);
                } catch (Throwable failed) {
                    result.completeExceptionally(failed);
                    if (failed instanceof VirtualMachineError fatal) throw fatal;
                    if (failed instanceof ThreadDeath fatal) throw fatal;
                    if (failed instanceof LinkageError fatal) throw fatal;
                } finally {
                    openingThread.compareAndSet(current, null);
                    slots.release();
                }
            });
        } catch (RuntimeException failed) {
            slots.release();
            throw new IOException("HTTP response body opener could not start", failed);
        }

        boolean delivered = false;
        try {
            while (true) {
                if (cancelled.getAsBoolean())
                    throw new IOException("HTTP response body opening cancelled");
                long left = waitNanos - (System.nanoTime() - started);
                if (left <= 0)
                    throw new IOException("HTTP response body opening deadline expired");
                try {
                    InputStream input = result.get(Math.min(left, POLL_NANOS), TimeUnit.NANOSECONDS);
                    if (cancelled.getAsBoolean())
                        throw new IOException("HTTP response body opening cancelled");
                    delivered = true;
                    return input;
                } catch (TimeoutException ignored) {
                    // Poll cancellation without parking a transport carrier forever.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP response body opening interrupted", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException("HTTP response body supplier failed", cause);
        } finally {
            if (!delivered) {
                abandoned.set(true);
                Thread running = openingThread.get();
                if (running != null) running.interrupt();
                if (result.isDone() && !result.isCompletedExceptionally())
                    closeQuietly(result.getNow(null));
            }
        }
    }

    private static void closeQuietly(InputStream input) {
        if (input != null) {
            try { input.close(); }
            catch (IOException ignored) { }
        }
    }
}
