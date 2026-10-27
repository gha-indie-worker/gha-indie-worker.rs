package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-runtime adapters for a one-reader asynchronous sequence.
 *
 * <p>This is NOT an Observable multicast implementation or a guest-language
 * Stream intrinsic. An OresFuture represents one readiness/result; {@link
 * Cursor#nextAsync()} exposes one item (or end-of-sequence) per invocation.
 * Only runtime bookkeeping is executed from producer completion callbacks:
 * guest continuation placement stays with the awaiting OresScheduler.</p>
 *
 * <p>A channel read is committed before the consumer necessarily processes
 * its item. Cancellation after commitment cannot requeue the value.
 * This is an at-most-once cursor, NOT a TaskQueue ACK/NACK or exactly-once
 * work processing primitive.</p>
 */
public final class AsyncSequences {
    private AsyncSequences() { }

    /**
     * The initial unicast, sequential-consumption protocol. A cancelled next
     * operation does not automatically cancel the independent producer. Each
     * cursor owns its subscription/read registration, not an actor or Task.
     */
    public interface Cursor<T> extends AutoCloseable {
        OresFuture<Optional<T>> nextAsync();

        @Override
        void close();
    }

    /**
     * Borrows an existing channel. Closing this cursor only closes its pending
     * read, never the caller-owned Channel. Additional readers compete for
     * items (MPMC work sharing), NOT multicast/broadcast.
     */
    public static <T> Cursor<T> fromChannel(ChannelRuntime.Channel<T> source) {
        return new ChannelCursor<>(source, false);
    }

    /**
     * Transfers lifecycle ownership of a channel to the cursor. Closing the
     * cursor closes the channel, preserving its already buffered items for any
     * other channel readers under ChannelRuntime's normal close contract.
     */
    public static <T> Cursor<T> owningChannel(ChannelRuntime.Channel<T> source) {
        return new ChannelCursor<>(source, true);
    }

    /**
     * Observe one existing Future exactly once. Never reruns or cancels the
     * producer. A null success is rejected: use a nonnull Option/Unit payload
     * for streams rather than turning null into a silent end-of-stream.
     */
    public static <T> Cursor<T> fromFuture(OresFuture<? extends T> source) {
        return new FutureCursor<>(source);
    }

    private static final class ChannelCursor<T> implements Cursor<T> {
        private final ChannelRuntime.Channel<T> channel;
        private final boolean ownsChannel;
        private final Object lock = new Object();
        private boolean closed;
        private Throwable terminalFailure;
        private OresFuture<Optional<T>> pendingResult;

        ChannelCursor(ChannelRuntime.Channel<T> channel, boolean ownsChannel) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.ownsChannel = ownsChannel;
        }

        @Override
        public OresFuture<Optional<T>> nextAsync() {
            final AtomicReference<OresFuture<T>> readRef = new AtomicReference<>();
            final OresFuture<Optional<T>> result;
            synchronized (lock) {
                if (terminalFailure != null) {
                    return OresFuture.failed(terminalFailure);
                }
                if (closed) return OresFuture.completed(Optional.empty());
                if (pendingResult != null) {
                    // A successful prior result can notify runtime listeners
                    // synchronously before its callback finishes unwinding.
                    // Permit a new read only AFTER authoritative terminal
                    // publication (including cancellation-hook revocation).
                    if (pendingResult.runtimeTerminalStateOrNull() == null) {
                        return OresFuture.failed(new IllegalStateException(
                                "concurrent nextAsync on a single Stream cursor"));
                    }
                    pendingResult = null;
                }

                // Reserve the single-reader slot BEFORE touching the channel.
                // Channel callbacks can notify other operations synchronously;
                // never run channel selection under our own lifecycle monitor.
                // Cancellation in the short pre-registration gap is reconciled
                // as soon as the underlying read is available.
                result = new OresFuture<>(() -> {
                    OresFuture<T> active = readRef.get();
                    if (active != null) active.cancel(false);
                });
                pendingResult = result;
            }

            if (result.isCancelled()) {
                synchronized (lock) {
                    if (pendingResult == result) pendingResult = null;
                }
                return result;
            }
            final OresFuture<T> read;
            try {
                read = channel.readAsync();
            } catch (RuntimeException | Error failure) {
                // Admission failed before a read was established. Block new
                // reads and publish the actual failure before releasing the
                // single-consumer reservation.
                synchronized (lock) {
                    closed = true;
                    terminalFailure = failure;
                }
                try {
                    result.failFromRuntime(failure);
                } finally {
                    synchronized (lock) {
                        if (pendingResult == result) pendingResult = null;
                    }
                }
                return result;
            }
            readRef.set(read);
            if (result.isCancelled()) read.cancel(false);
            read.whenCompleteRuntime((value, failure) -> {
                synchronized (lock) {
                    if (failure instanceof ChannelRuntime.ChannelClosedException closedError) {
                        closed = true;
                        if (closedError.getCause() != null) {
                            terminalFailure = closedError.getCause();
                        }
                    } else if (failure != null && !read.isCancelled()) {
                        closed = true;
                        terminalFailure = failure;
                    }
                }

                try {
                    if (result.isDone()) return;
                    if (failure == null) {
                        // Channel rejects null on admission. Never an EOS sentinel.
                        result.completeFromRuntime(Optional.of(value));
                    } else if (read.isCancelled()) {
                        result.cancel(false);
                    } else if (failure instanceof ChannelRuntime.ChannelClosedException
                            && failure.getCause() == null) {
                        result.completeFromRuntime(Optional.empty());
                    } else if (failure instanceof ChannelRuntime.ChannelClosedException
                            && failure.getCause() != null) {
                        result.failFromRuntime(failure.getCause());
                    } else {
                        result.failFromRuntime(failure);
                    }
                } finally {
                    // Keep the cursor's one-reader lease until result
                    // publication completes. A concurrent nextAsync cannot
                    // overtake an in-flight terminal notification.
                    synchronized (lock) {
                        if (pendingResult == result) pendingResult = null;
                    }
                }
            });
            return result;
        }

        @Override
        public void close() {
            OresFuture<Optional<T>> pending;
            synchronized (lock) {
                closed = true;
                pending = pendingResult;
            }
            if (pending != null) pending.cancel(false);
            if (ownsChannel) channel.close();
        }
    }

    private static final class FutureCursor<T> implements Cursor<T> {
        private final OresFuture<? extends T> source;
        private final Object lock = new Object();
        private boolean consumed;
        private boolean closed;
        private OresFuture<Optional<T>> pending;
        private final AtomicReference<OresFuture.RuntimeWaiterRegistration<?>> subscription =
                new AtomicReference<>();

        FutureCursor(OresFuture<? extends T> source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        private void detach() {
            OresFuture.RuntimeWaiterRegistration<?> old =
                    subscription.getAndSet(null);
            if (old != null) old.detach();
        }

        @Override
        public OresFuture<Optional<T>> nextAsync() {
            final OresFuture<Optional<T>> result;
            synchronized (lock) {
                if (closed) return OresFuture.completed(Optional.empty());
                if (pending != null) {
                    if (pending.runtimeTerminalStateOrNull() == null) {
                        return OresFuture.failed(new IllegalStateException(
                                "concurrent nextAsync on a single Future cursor"));
                    }
                    pending = null;
                }
                if (consumed) return OresFuture.completed(Optional.empty());
                consumed = true;
                result = new OresFuture<>(this::detach);
                pending = result;
            }

            OresFuture.RuntimeWaiterRegistration<?> registered =
                    source.whenCompleteRuntimeCancellable((value, failure) -> {
                        try {
                            if (result.isDone()) return;
                            if (failure != null) {
                                if (source.isCancelled()) result.cancel(false);
                                else result.failFromRuntime(failure);
                            } else if (value == null) {
                                result.failFromRuntime(new IllegalStateException(
                                        "Future-to-stream needs a nonnull value; use Option/Unit"));
                            } else {
                                result.completeFromRuntime(Optional.of(value));
                            }
                        } finally {
                            synchronized (lock) {
                                if (pending == result) pending = null;
                            }
                            detach();
                        }
                    });
            subscription.set(registered);
            // A completed source can run the listener inline, before its
            // registration is published. Close that retain-after-completion race.
            if (result.isDone()) detach();
            return result;
        }

        @Override
        public void close() {
            OresFuture<Optional<T>> toCancel;
            synchronized (lock) {
                closed = true;
                toCancel = pending;
                pending = null;
            }
            if (toCancel != null) toCancel.cancel(false);
            detach();
            // Deliberately NEVER source.cancel(): producer is not owned.
        }
    }
}
