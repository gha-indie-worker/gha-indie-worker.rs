package dev.oreslang.runtime;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Opt-in, core-only diagnostic flight recorder. No Ores guest objects, userland
 * telemetry, logging formatters or I/O execute on an instrumented hot path.
 *
 * The first bounded set of completed events is retained so cold-start work
 * cannot be evicted by a warmed server's steady-state requests.
 */
public final class CorePerf {
    public static final int VM_CONTROL_STARTUP = 1;
    public static final int ACTOR_POOLS_STARTUP = 2;
    public static final int CONTEXT_STARTUP = 3;
    public static final int HTTP_LISTEN = 4;
    public static final int HTTP_RECEIVE = 5;
    public static final int HTTP_DISPATCH = 6;
    public static final int ACTOR_READY = 7;
    public static final int HTTP_CLAIM = 8;
    public static final int HTTP_IO_QUEUE = 9;
    public static final int HTTP_IO_WORK = 10;
    public static final int ASYNC_IO_QUEUE = 11;
    public static final int ASYNC_IO_WORK = 12;
    public static final int HTTP_TIMER_SCHEDULE = 13;
    public static final int ACTOR_SPAWN = 14;
    public static final int HTTP_EXECUTOR_QUEUE = 15;
    public static final int STDIO_WRITE = 16;
    public static final int HTTP_LIFETIME = 17;

    private static final String[] NAMES = {
        "unused", "vm.control.startup", "actor.pools.startup",
        "context.startup", "http.listen", "http.receive",
        "http.dispatch", "actor.ready", "http.claim",
        "http.io.queue", "http.io.work",
        "async.io.queue", "async.io.work",
        "http.timer.schedule", "actor.spawn", "http.executor.queue",
        "stdio.write", "http.lifetime"
    };
    private static final String[] DEBUG_NAMES = {
        "unused", "actor.pools.created", "http.listener.ready",
        "http.executor.task.started"
    };
    private static final int DEFAULT_CAPACITY = 8192;
    private static volatile Recorder active;
    private static volatile Recorder debugActive;
    private static Recorder perfRecorder;
    private static Recorder debugRecorder;
    private static boolean shutdownHookInstalled;

    private CorePerf() { }

    /** Repeated configuration switches sampling without discarding buffered data. */
    public static synchronized void configure(boolean enabled) {
        if (!enabled) {
            active = null;
            return;
        }
        if (perfRecorder == null) {
            perfRecorder = new Recorder(DEFAULT_CAPACITY);
            installShutdownHook();
            System.err.println("ores-core-perf: enabled; first " + DEFAULT_CAPACITY
                    + " completed phases buffered; JSONL exported at JVM shutdown");
        }
        active = perfRecorder;
    }

    static synchronized void configureDebug(boolean enabled) {
        if (!enabled) {
            debugActive = null;
            return;
        }
        if (debugRecorder == null) {
            debugRecorder = new Recorder(DEFAULT_CAPACITY);
            installShutdownHook();
            System.err.println("ores-core-debug: enabled; first " + DEFAULT_CAPACITY
                    + " numeric events buffered; JSONL exported at JVM shutdown");
        }
        debugActive = debugRecorder;
    }

    private static void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (perfRecorder != null) {
                perfRecorder.dump(System.err);
            }
            if (debugRecorder != null) {
                debugRecorder.dumpDebug(System.err);
            }
        }, "ores-core-diagnostics-dump"));
        shutdownHookInstalled = true;
    }

    /** Query only at setup boundaries; saturated recorders need no new wrappers. */
    public static boolean enabled() {
        Recorder recorder = active;
        return recorder != null && !recorder.saturated;
    }

    static boolean debugEnabled() {
        Recorder recorder = debugActive;
        return recorder != null && !recorder.saturated;
    }

    static void debugEvent(int event, long value) {
        Recorder recorder = debugActive;
        if (recorder != null && !recorder.saturated) {
            recorder.recordEvent(event, value);
        }
    }

    /** Startup-only metadata. Guest and hot-path code never consult it. */
    public static void actorBackend(boolean nativeCarriers, int privateParallelism, int sharedParallelism) {
        Recorder recorder = active;
        if (recorder != null) {
            recorder.backend = nativeCarriers ? "native_pthread" : "jvm_thread_pool";
            recorder.privateParallelism = privateParallelism;
            recorder.sharedParallelism = sharedParallelism;
        }
    }

    /** Zero allocation and no clock read on the disabled hot path. */
    public static long start() {
        Recorder recorder = active;
        return recorder == null || recorder.saturated ? 0L : System.nanoTime();
    }

    public static void end(int phase, long started) {
        if (started == 0L) return;
        Recorder recorder = active;
        if (recorder != null && !recorder.saturated) {
            long finished = System.nanoTime();
            // Diagnostics must not change request outcomes if a clock sample is invalid.
            if (validDuration(started, finished)) {
                recorder.record(phase, started, finished);
            }
        }
    }

    /** Reuse timestamps already obtained for actor lifecycle bookkeeping. */
    public static void elapsed(int phase, long started, long ended) {
        Recorder recorder = active;
        if (recorder != null && !recorder.saturated
                && started != 0L && validDuration(started, ended)) {
            recorder.record(phase, started, ended);
        }
    }

    /** Guard subtraction overflow as well as reversed timestamps. */
    static boolean validDuration(long started, long finished) {
        return finished >= started && finished - started >= 0L;
    }

    /** Package-visible recorder for tests; separate from global CLI state. */
    static final class Recorder {
        private final int capacity;
        private final long origin = System.nanoTime();
        private final AtomicInteger cursor = new AtomicInteger();
        private final AtomicIntegerArray published;
        private final long[] offsets;
        private final long[] durations;
        private final long[] threadIds;
        private volatile boolean saturated;
        private volatile String backend = "unknown";
        private volatile int privateParallelism;
        private volatile int sharedParallelism;

        Recorder(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
            this.capacity = capacity;
            this.published = new AtomicIntegerArray(capacity);
            this.offsets = new long[capacity];
            this.durations = new long[capacity];
            this.threadIds = new long[capacity];
        }

        void record(int phase, long started, long finished) {
            long duration = finished - started;
            if (phase < 1 || phase >= NAMES.length || !validDuration(started, finished)) {
                throw new IllegalArgumentException("invalid core perf sample");
            }
            int index = reserve();
            if (index < 0) {
                return;
            }
            offsets[index] = Math.max(0L, started - origin);
            durations[index] = duration;
            threadIds[index] = Thread.currentThread().threadId();
            // Release-publish last. Dumps use acquire-reads, never partial rows.
            published.set(index, phase);
        }

        void recordEvent(int event, long value) {
            if (event < 1 || event >= DEBUG_NAMES.length) {
                throw new IllegalArgumentException("invalid core debug event");
            }
            int index = reserve();
            if (index < 0) {
                return;
            }
            offsets[index] = Math.max(0L, System.nanoTime() - origin);
            durations[index] = value;
            threadIds[index] = Thread.currentThread().threadId();
            published.set(index, -event);
        }

        /** Saturating CAS reservation cannot wrap an integer counter on a busy server. */
        // Package-visible so tests can inspect an unpublished reservation.
        int reserve() {
            while (true) {
                int index = cursor.get();
                if (index >= capacity) {
                    saturated = true;
                    return -1;
                }
                if (cursor.compareAndSet(index, index + 1)) {
                    if (index + 1 == capacity) {
                        saturated = true;
                    }
                    return index;
                }
            }
        }

        void dump(PrintStream out) {
            int reserved = cursor.get();
            int recorded = 0;
            out.println("{\"schema\":\"ores-core-perf.v1\",\"kind\":\"begin\",\"capacity\":" + capacity + "}");
            out.println("{\"schema\":\"ores-core-perf.v1\",\"kind\":\"backend\",\"actor_carriers\":\""
                    + backend + "\",\"private_parallelism\":" + privateParallelism
                    + ",\"shared_parallelism\":" + sharedParallelism + "}");
            for (int i = 0; i < reserved; i++) {
                int phase = published.get(i);
                if (phase <= 0) {
                    continue; // Not yet published; do not count incomplete reservations.
                }
                out.println("{\"schema\":\"ores-core-perf.v1\",\"kind\":\"phase\",\"phase\":\""
                        + NAMES[phase] + "\",\"start_ns\":" + offsets[i]
                        + ",\"duration_ns\":" + durations[i]
                        + ",\"thread_id\":" + threadIds[i] + "}");
                recorded++;
            }
            out.println("{\"schema\":\"ores-core-perf.v1\",\"kind\":\"end\",\"recorded\":"
                    + recorded + ",\"reserved\":" + reserved
                    + ",\"saturated\":" + saturated + "}");
            out.flush();
        }

        void dumpDebug(PrintStream out) {
            int reserved = cursor.get();
            int recorded = 0;
            out.println("{\"schema\":\"ores-core-debug.v1\",\"kind\":\"begin\",\"capacity\":" + capacity + "}");
            for (int i = 0; i < reserved; i++) {
                int event = published.get(i);
                if (event >= 0) {
                    continue;
                }
                out.println("{\"schema\":\"ores-core-debug.v1\",\"kind\":\"event\",\"event\":\""
                        + DEBUG_NAMES[-event] + "\",\"start_ns\":" + offsets[i]
                        + ",\"value\":" + durations[i]
                        + ",\"thread_id\":" + threadIds[i] + "}");
                recorded++;
            }
            out.println("{\"schema\":\"ores-core-debug.v1\",\"kind\":\"end\",\"recorded\":"
                    + recorded + ",\"reserved\":" + reserved
                    + ",\"saturated\":" + saturated + "}");
            out.flush();
        }
    }
}
