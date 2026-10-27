package dev.oreslang.runtime;

import java.io.PrintStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Host-only, CLI-configured diagnostic flight recorder. Never imports a guest
 * logging module or performs formatting, I/O, locking or allocations per event.
 * All events have fixed IDs and one primitive numeric value (no guest data).
 *
 * The disabled path reads one volatile reference, never samples a clock.
 * The enabled recorder captures the first bounded events and then stops.
 */
public final class CoreLog {
    public static final int ASYNC_IO_REJECTED = 1;
    public static final int ASYNC_IO_FAILED = 2;
    public static final int ACTOR_DISPATCHER_FAILED = 3;

    private static final String[] NAMES = {
        "unused", "async.io.rejected", "async.io.failed", "actor.dispatcher.failed"
    };
    private static final int DEFAULT_CAPACITY = 8192;

    public enum Level {
        OFF(Integer.MAX_VALUE),
        ERROR(50),
        WARN(40),
        INFO(30),
        DEBUG(20),
        TRACE(10);

        private final int rank;
        Level(int rank) { this.rank = rank; }

        /** Strict policy admission occurs only at startup, never on a hot path. */
        public static Level parse(String text) {
            if (text == null) throw new IllegalArgumentException("core log level is required");
            return switch (text.toLowerCase(Locale.ROOT)) {
                case "off" -> OFF;
                case "error" -> ERROR;
                case "warn" -> WARN;
                case "info" -> INFO;
                case "debug" -> DEBUG;
                case "trace" -> TRACE;
                default -> throw new IllegalArgumentException(
                        "invalid core log level: " + text
                                + " (expected off|error|warn|info|debug|trace)");
            };
        }
    }

    private record State(Level threshold, Recorder recorder) { }

    private static volatile State active;
    // Volatile: the JVM shutdown hook runs on a separate thread.
    private static volatile Recorder retained;
    private static boolean shutdownHookInstalled;

    private CoreLog() { }

    public static synchronized void configure(Level threshold) {
        if (threshold == null) throw new IllegalArgumentException("core log level is required");
        if (threshold == Level.OFF) {
            // Disable must revoke output as well as recording. This drops the
            // buffered session so a later re-enable cannot disclose old rows.
            active = null;
            retained = null;
            return;
        }
        if (retained == null) {
            // Install the shutdown hook before making the recorder visible.
            // A rejected hook installation must not leave an exportless
            // recorder that later configure() calls accidentally reuse.
            Recorder next = new Recorder(DEFAULT_CAPACITY);
            if (!shutdownHookInstalled) {
                Runtime.getRuntime().addShutdownHook(new Thread(
                        () -> dumpRetained(System.err), "ores-core-logging-dump"));
                shutdownHookInstalled = true;
            }
            retained = next;
        }
        active = new State(threshold, retained);
    }

    /** Host shutdown path; package-visible for lifecycle regression testing. */
    static synchronized void dumpRetained(PrintStream out) {
        // Serialize shutdown export with configure(OFF): after OFF returns,
        // no earlier recorder can still be printing to the supplied stream.
        // This monitor is acquired only on the cold export/configure path.
        Recorder recorder = retained;
        if (recorder != null) recorder.dump(out);
    }

    public static Level currentLevel() {
        State state = active;
        return state == null ? Level.OFF : state.threshold();
    }

    /** Guards expensive non-primitive work at the caller. */
    public static boolean enabled(Level severity) {
        State state = active;
        return severity != null && severity != Level.OFF
                && state != null && severity.rank >= state.threshold().rank
                && !state.recorder().saturated;
    }

    /** Numeric-only hot-path emission; unknown IDs/severities are inert. */
    public static void record(Level severity, int eventId, long value) {
        State state = active;
        if (state != null && severity != null && severity != Level.OFF
                && severity.rank >= state.threshold().rank
                && !state.recorder().saturated
                && eventId > 0 && eventId < NAMES.length) {
            state.recorder().record(severity, eventId, value);
        }
    }

    /** Package-private recorder supports deterministic saturation and concurrency tests. */
    static final class Recorder {
        private final int capacity;
        private final long origin = System.nanoTime();
        private final AtomicInteger cursor = new AtomicInteger();
        private final AtomicIntegerArray published;
        private final long[] offsets;
        private final long[] values;
        private final long[] threadIds;
        private volatile boolean saturated;

        Recorder(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
            this.capacity = capacity;
            published = new AtomicIntegerArray(capacity);
            offsets = new long[capacity];
            values = new long[capacity];
            threadIds = new long[capacity];
        }

        void record(Level severity, int eventId, long value) {
            if (severity == null || severity == Level.OFF
                    || eventId <= 0 || eventId >= NAMES.length) return;
            int index = reserve();
            if (index < 0) return;
            offsets[index] = CorePerf.Recorder.relativeNanos(System.nanoTime(), origin);
            values[index] = value;
            threadIds[index] = Thread.currentThread().threadId();
            // Release-publish after all primitive slots are initialized.
            published.set(index, eventId * 100 + severity.rank);
        }

        int reserve() {
            while (true) {
                int index = cursor.get();
                if (index >= capacity) {
                    saturated = true;
                    return -1;
                }
                if (cursor.compareAndSet(index, index + 1)) {
                    if (index + 1 == capacity) saturated = true;
                    return index;
                }
            }
        }

        void dump(PrintStream out) {
            int reserved = cursor.get();
            int recorded = 0;
            out.println("{\"schema\":\"ores-core-log.v1\",\"kind\":\"begin\",\"capacity\":" + capacity + "}");
            for (int i = 0; i < reserved; i++) {
                int code = published.get(i);
                if (code == 0) continue;
                int event = code / 100;
                int rank = code % 100;
                String level = switch (rank) {
                    case 50 -> "error";
                    case 40 -> "warn";
                    case 30 -> "info";
                    case 20 -> "debug";
                    case 10 -> "trace";
                    default -> "unknown";
                };
                out.println("{\"schema\":\"ores-core-log.v1\",\"kind\":\"event\",\"event\":\""
                        + NAMES[event] + "\",\"severity\":\"" + level
                        + "\",\"start_ns\":" + offsets[i] + ",\"value\":" + values[i]
                        + ",\"thread_id\":" + threadIds[i] + "}");
                recorded++;
            }
            // A writer may be preempted after reserving a slot but before
            // publishing it. Such rows are never serialized as valid events.
            // Report them explicitly rather than silently hiding the gap.
            out.println("{\"schema\":\"ores-core-log.v1\",\"kind\":\"end\",\"recorded\":"
                    + recorded + ",\"reserved\":" + reserved
                    + ",\"unpublished\":" + (reserved - recorded)
                    + ",\"saturated\":" + saturated + "}");
            out.flush();
        }
    }
}
