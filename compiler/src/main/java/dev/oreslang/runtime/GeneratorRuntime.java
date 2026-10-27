package dev.oreslang.runtime;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime substrate for Oreslang generator activations.
 *
 * <p>A generator owns exactly one suspended activation. Pulls are serialized:
 * the consumer grants one resume permit, the producer runs until the next
 * yield/completion/failure, and then control returns to the consumer. The
 * producer uses {@link AsyncRuntime}'s context-owned virtual carrier so a
 * suspended generator does not pin an OS thread and is cancelled with its
 * OresContext.</p>
 *
 * <p>This is an interpreter implementation detail. A native/AOT lowering may
 * represent the same contract as an explicit program-counter/state-machine
 * frame without changing language semantics.</p>
 */
public final class GeneratorRuntime {
    private GeneratorRuntime() { }

    @FunctionalInterface
    public interface Emitter<T> {
        void emit(T value);
    }

    @FunctionalInterface
    public interface Producer<T> {
        void run(Emitter<T> emitter) throws Exception;
    }

    public record Step<T>(boolean done, T value) {
        public static <T> Step<T> yielded(T value) { return new Step<>(false, value); }
        public static <T> Step<T> doneStep() { return new Step<>(true, null); }
    }

    public static <T> Generator<T> generator(AsyncRuntime runtime, Producer<T> producer) {
        return new Generator<>(new Engine<>(runtime, producer));
    }

    public static <T> AsyncGenerator<T> asyncGenerator(AsyncRuntime runtime, Producer<T> producer) {
        return new AsyncGenerator<>(runtime, new Engine<>(runtime, producer));
    }

    public static final class Generator<T> implements Iterable<T>, AutoCloseable {
        private final Engine<T> engine;

        private Generator(Engine<T> engine) {
            this.engine = engine;
        }

        public Step<T> nextStep() {
            if (ActorRuntime.inActorExecution()) {
                throw new IllegalStateException(
                        "synchronous generator iteration cannot park an actor carrier; "
                                + "use an async generator and 'for await'");
            }
            return engine.next();
        }

        @Override
        public Iterator<T> iterator() {
            return new Iterator<>() {
                private Step<T> buffered;

                @Override
                public boolean hasNext() {
                    if (buffered == null) buffered = nextStep();
                    return !buffered.done();
                }

                @Override
                public T next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    T value = buffered.value();
                    buffered = null;
                    return value;
                }
            };
        }

        @Override
        public void close() {
            engine.close();
        }
    }

    public static final class AsyncGenerator<T> implements AutoCloseable {
        private final AsyncRuntime runtime;
        private final Engine<T> engine;

        private AsyncGenerator(AsyncRuntime runtime, Engine<T> engine) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.engine = engine;
        }

        /**
         * One asynchronous pull. Calls are serialized by the activation even
         * if callers issue more than one pull before an earlier pull completes.
         */
        public OresFuture<Step<T>> nextStep() {
            // Only a *settled* terminal generator can return an immediate
            // Future. A terminal event can precede the physical guest exit;
            // never park the caller (possibly an actor carrier) in this API.
            if (engine.isTerminalReady()) {
                // A producer admission denial remains an exceptional async
                // result across repeated pulls; never launder it into DONE.
                Throwable rejected = engine.terminalFailure();
                return rejected == null
                        ? OresFuture.completed(Step.doneStep())
                        : OresFuture.failed(rejected);
            }
            // An async pull always returns an OresFuture, including when
            // scheduler admission itself rejects before any guest work starts.
            try {
                return runtime.submit(engine::next);
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                return OresFuture.failed(rejected);
            }
        }

        @Override
        public void close() {
            engine.close();
        }
    }

    private sealed interface Event<T> permits YieldEvent, DoneEvent, FailedEvent { }
    private record YieldEvent<T>(T value) implements Event<T> { }
    private record DoneEvent<T>() implements Event<T> { }
    private record FailedEvent<T>(Throwable failure) implements Event<T> { }

    private static final Object RESUME = new Object();

    private static final class Engine<T> implements AutoCloseable {
        private final AsyncRuntime runtime;
        private final Producer<T> producer;
        private final ArrayBlockingQueue<Object> resumes = new ArrayBlockingQueue<>(1);
        private final ArrayBlockingQueue<Event<T>> events = new ArrayBlockingQueue<>(1);
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<OresFuture<Void>> producerTask = new AtomicReference<>();
        private final Object launchLock = new Object();
        private volatile boolean done;
        // True only after the producer's actual guest turn has exited and
        // the terminal observation may be published without any blocking.
        private volatile boolean terminalReady;
        private volatile Throwable backingFailure;

        private Engine(AsyncRuntime runtime, Producer<T> producer) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.producer = Objects.requireNonNull(producer, "producer");
        }

        private boolean isTerminalReady() {
            return terminalReady;
        }

        private Throwable terminalFailure() {
            return closed.get() ? null : backingFailure;
        }

        private synchronized Step<T> next() {
            // Explicit close stops iteration; otherwise preserve the
            // producer's terminal failure across every later pull.
            if (closed.get()) {
                awaitProducerExit();
                terminalReady = true;
                return Step.doneStep();
            }
            throwIfBackingFailed();
            if (done) {
                awaitProducerExit();
                terminalReady = true;
                return Step.doneStep();
            }
            startIfNeeded();
            throwIfBackingFailed();
            putResume();
            Event<T> event = takeEvent();
            if (closed.get()) {
                awaitProducerExit();
                terminalReady = true;
                return Step.doneStep();
            }
            throwIfBackingFailed();
            if (event instanceof YieldEvent<?> yielded) {
                @SuppressWarnings("unchecked")
                T value = (T) yielded.value();
                return Step.yielded(value);
            }
            done = true;
            awaitProducerExit();
            if (event instanceof FailedEvent<?> failed) {
                // The published failure is an immutable terminal observation,
                // not a one-shot event to forget after its first consumer.
                backingFailure = failed.failure();
                throwIfBackingFailed();
            }
            terminalReady = true;
            return Step.doneStep();
        }

        private void startIfNeeded() {
            synchronized (launchLock) {
                if (closed.get() || !started.compareAndSet(false, true)) return;
                OresFuture<Void> task;
                try {
                    task = runtime.submit(() -> {
                        try {
                            takeResume();
                            if (!closed.get()) {
                                producer.run(this::emit);
                            }
                            publish(new DoneEvent<>());
                        } catch (CancellationException cancelled) {
                            if (!closed.get()) publish(new FailedEvent<>(cancelled));
                        } catch (Throwable failure) {
                            if (!closed.get()) publish(new FailedEvent<>(failure));
                        }
                        return null;
                    });
                } catch (RuntimeException | Error rejection) {
                    // The producer never started, so no event can arrive.
                    // Persist the denial rather than leaving a started
                    // activation waiting indefinitely on an empty queue.
                    backingFailure = rejection;
                    return;
                }
                producerTask.set(task);
                // Admission/context entry can fail before the producer body runs.
                // Its catch block then never publishes an event. Deliver that
                // backing failure without blocking the runtime completion thread.
                task.whenCompleteRuntime((value, failure) -> {
                    if (failure != null && !closed.get()) {
                        backingFailure = OresFuture.unwrap(failure);
                        events.offer(new FailedEvent<>(backingFailure));
                    }
                });
                if (closed.get()) task.cancel(true);
            }
        }

        private void awaitProducerExit() {
            OresFuture<Void> task;
            synchronized (launchLock) { task = producerTask.get(); }
            if (task != null) runtime.awaitTaskExit(task);
        }

        private void throwIfBackingFailed() {
            Throwable failure = backingFailure;
            if (failure == null) return;
            done = true;
            awaitProducerExit();
            terminalReady = true;
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error error) throw error;
            throw new RuntimeException(failure);
        }

        private void emit(T value) {
            if (closed.get()) throw new CancellationException("generator is closed");
            publish(new YieldEvent<>(value));
            takeResume();
            if (closed.get()) throw new CancellationException("generator is closed");
        }

        private void publish(Event<T> event) {
            try {
                events.put(event);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("generator producer interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        private void putResume() {
            try {
                resumes.put(RESUME);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("generator consumer interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        private void takeResume() {
            try {
                resumes.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("generator producer interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        private Event<T> takeEvent() {
            try {
                return events.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("generator consumer interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        @Override
        public void close() {
            boolean first = closed.compareAndSet(false, true);
            if (first) {
                done = true;
                synchronized (launchLock) {
                    OresFuture<Void> task = producerTask.get();
                    if (task != null) task.cancel(true);
                }
                resumes.offer(RESUME);
                events.offer(new DoneEvent<>());
            }
            awaitProducerExit();
            terminalReady = true;
        }
    }
}
