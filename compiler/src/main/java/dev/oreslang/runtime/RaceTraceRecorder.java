package dev.oreslang.runtime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Opt-in, host-owned ORES_RACE_V1 JSONL emission boundary.
 *
 * <p>This component records only <em>explicitly instrumented</em> events.
 * It does not instrument Java memory automatically, and a clean trace does
 * not establish complete runtime coverage or guest data-race freedom.
 * No guest callbacks execute on this diagnostic path.</p>
 *
 * <p>Events are serialized by a synchronized method to preserve a coherent
 * file order. Callers must preserve causal per-task ordering, associate real
 * logical task identities with accesses, and emit honest synchronization edges.
 * Files are exclusive-new, bounded and sealed with a counted END record only
 * on successful close. A partially recorded run cannot have a valid END.</p>
 */
public final class RaceTraceRecorder implements AutoCloseable {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@#-]{0,255}");
    private static final int MAX_EVENTS = 100_000;
    private static final int MAX_BYTES = 10_000_000;
    private static final int MAX_TASKS = 256;
    private final BufferedWriter out;
    private long bytes;
    private int events;
    private int accesses;
    private final String runId;
    private boolean closed;
    private boolean broken;
    // Owned by the same recorder monitor as the output buffer. No state is
    // published as successful until the corresponding event was written.
    private final Set<String> tasks = new HashSet<>();
    private final Set<String> finished = new HashSet<>();
    private final Map<String, String> parents = new HashMap<>();
    private final Set<String> joined = new HashSet<>();
    private final Map<String, String> locks = new HashMap<>();
    private final Set<String> sent = new HashSet<>();
    private final Set<String> received = new HashSet<>();

    private RaceTraceRecorder(Path path, String runId, String backend) throws IOException {
        this.runId = label(runId, "run_id");
        label(backend, "backend");
        Path absolute = Objects.requireNonNull(path, "trace path").toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new IOException("trace parent must be an existing non-symlink directory");
        }
        // CREATE_NEW also avoids following a preexisting symlink at the leaf.
        out = Files.newBufferedWriter(absolute, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            writeLine("{\"op\":\"begin\",\"version\":1,\"run_id\":" + quote(this.runId)
                    + ",\"backend\":" + quote(backend) + "}");
        } catch (RuntimeException | IOException failure) {
            broken = true;
            try { out.close(); } catch (IOException ignored) { failure.addSuppressed(ignored); }
            throw failure;
        }
    }

    /** Enabled only when both explicit environment settings are present. */
    public static Optional<RaceTraceRecorder> fromEnvironment() throws IOException {
        String filename = System.getenv("ORESLANG_RACE_TRACE");
        String runId = System.getenv("ORESLANG_RACE_RUN_ID");
        if (filename == null && runId == null) return Optional.empty();
        if (filename == null || filename.isBlank() || runId == null || runId.isBlank()) {
            throw new IOException("race trace requires ORESLANG_RACE_TRACE and ORESLANG_RACE_RUN_ID");
        }
        return Optional.of(open(Path.of(filename), runId, "graal"));
    }

    /** Explicit factory for runtime integration tests and managed runtimes. */
    public static RaceTraceRecorder open(Path path, String runId, String backend) throws IOException {
        return new RaceTraceRecorder(path, runId, backend);
    }

    private static String label(String value, String field) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid race trace " + field);
        }
        return value;
    }

    private static String quote(String literal) { return "\"" + literal + "\""; }

    private void writeLine(String json) throws IOException {
        int n = json.getBytes(StandardCharsets.UTF_8).length + 1;
        if (bytes + n > MAX_BYTES) throw new IOException("race trace exceeds 10 MB");
        out.write(json);
        out.write('\n');
        bytes += n;
    }

    private void active(String task) throws IOException {
        if (!tasks.contains(task) || finished.contains(task)) {
            throw new IOException("trace task is unregistered or finished: " + task);
        }
    }

    private void validateEvent(String op, String task, String value) throws IOException {
        if ("task".equals(op)) {
            if (tasks.contains(task) || tasks.size() >= MAX_TASKS) {
                throw new IOException("duplicate task or task capacity exceeded");
            }
            return;
        }
        active(task);
        switch (op) {
            case "fork" -> {
                if (tasks.contains(value) || tasks.size() >= MAX_TASKS) {
                    throw new IOException("duplicate child or task capacity exceeded");
                }
            }
            case "finish" -> {
                if (locks.containsValue(task)) throw new IOException("cannot finish with held locks");
                for (var entry : parents.entrySet()) {
                    if (entry.getValue().equals(task) && !joined.contains(entry.getKey())) {
                        throw new IOException("cannot finish with unjoined child");
                    }
                }
            }
            case "join" -> {
                if (!task.equals(parents.get(value)) || !finished.contains(value)
                        || joined.contains(value)) {
                    throw new IOException("join must name a finished, directly forked, unjoined child");
                }
            }
            case "lock" -> {
                if (locks.containsKey(value)) throw new IOException("lock already held");
            }
            case "unlock" -> {
                if (!task.equals(locks.get(value))) throw new IOException("unlock by non-owner");
            }
            case "send" -> {
                if (sent.contains(value)) throw new IOException("message ID reused");
            }
            case "recv" -> {
                if (!sent.contains(value) || received.contains(value)) {
                    throw new IOException("receive without unmatched send");
                }
            }
            case "access" -> { }
            default -> throw new IOException("unsupported trace operation: " + op);
        }
    }

    private void commitEvent(String op, String task, String value) {
        switch (op) {
            case "task" -> tasks.add(task);
            case "fork" -> {
                tasks.add(value);
                parents.put(value, task);
            }
            case "finish" -> finished.add(task);
            case "join" -> joined.add(value);
            case "lock" -> locks.put(value, task);
            case "unlock" -> locks.remove(value);
            case "send" -> sent.add(value);
            case "recv" -> received.add(value);
            default -> { }
        }
    }

    private synchronized void event(String op, String task, String key, String value) throws IOException {
        if (closed || broken) throw new IOException("race trace is closed or failed");
        if (events >= MAX_EVENTS) {
            broken = true;
            throw new IOException("race trace exceeds 100000 events");
        }
        try {
            label(task, "task");
            if (key != null) label(value, key);
            validateEvent(op, task, value);
            StringBuilder json = new StringBuilder("{\"op\":").append(quote(op));
            json.append(",\"task\":").append(quote(task));
            if (key != null) json.append(",\"").append(key).append("\":").append(quote(value));
            json.append('}');
            writeLine(json.toString());
            events++;
            commitEvent(op, task, value);
        } catch (IOException | RuntimeException failure) {
            broken = true;
            throw failure;
        }
    }

    public void task(String task) throws IOException { event("task", task, null, null); }
    public void finish(String task) throws IOException { event("finish", task, null, null); }
    public void fork(String task, String child) throws IOException { event("fork", task, "child", child); }
    public void join(String task, String child) throws IOException { event("join", task, "child", child); }
    public void lock(String task, String name) throws IOException { event("lock", task, "name", name); }
    public void unlock(String task, String name) throws IOException { event("unlock", task, "name", name); }
    public void send(String task, String message) throws IOException { event("send", task, "message", message); }
    public void recv(String task, String message) throws IOException { event("recv", task, "message", message); }

    public synchronized void access(String task, String location, boolean write) throws IOException {
        if (closed || broken) throw new IOException("race trace is closed or failed");
        if (events >= MAX_EVENTS) {
            broken = true;
            throw new IOException("race trace exceeds 100000 events");
        }
        try {
            label(task, "task");
            label(location, "location");
            validateEvent("access", task, null);
            String json = "{\"op\":\"access\",\"task\":" + quote(task)
                    + ",\"location\":" + quote(location)
                    + ",\"mode\":\"" + (write ? "W" : "R") + "\"}";
            writeLine(json);
            events++;
            accesses++;
        } catch (IOException | RuntimeException failure) {
            broken = true;
            throw failure;
        }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            if (!broken && (accesses == 0 || !locks.isEmpty() ||
                    parents.keySet().stream().anyMatch(child -> !joined.contains(child)))) {
                broken = true;
            }
            if (!broken) {
                writeLine("{\"op\":\"end\",\"run_id\":" + quote(runId)
                        + ",\"events\":" + events + "}");
                out.flush();
            }
        } finally {
            out.close();
        }
    }
}
