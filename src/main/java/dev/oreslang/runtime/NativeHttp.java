package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** HTTP/1.1 transport. Guest code sees revocable exchange capabilities, never sockets. */
public final class NativeHttp implements AutoCloseable {
    private final OresContext context;
    private final Set<Server> servers = ConcurrentHashMap.newKeySet();
    private final Map<String, Exchange> transfers = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "ores-http-deadlines"); t.setDaemon(true); return t;
    });
    private boolean closed;

    public NativeHttp(OresContext context) {
        this.context = Objects.requireNonNull(context);
        timers.setRemoveOnCancelPolicy(true);
        timers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public synchronized Server listen(String host, int port, int capacity, int bodyLimit, int deadlineMillis) {
        if (closed) throw new IllegalStateException("HTTP runtime closed");
        if (ActorRuntime.inActorExecution()) throw new SecurityException("HTTP listeners belong to the supervisor");
        if (host == null || host.isBlank() || port < 0 || port > 65535
                || capacity < 1 || capacity > 65536 || bodyLimit < 1 || bodyLimit > 64 * 1024 * 1024
                || deadlineMillis < 1) throw new IllegalArgumentException("invalid HTTP listener limits");
        context.requirePermission(RuntimePermissions.Permission.NET, host.toLowerCase(Locale.ROOT) + ":" + port, "http.listen");
        configureTransportDefaults();
        try {
            Server server = new Server(HttpServer.create(new InetSocketAddress(host, port), capacity),
                    capacity, bodyLimit, deadlineMillis);
            servers.add(server);
            server.transport.start();
            return server;
        } catch (IOException e) { throw new IllegalStateException("HTTP listen failed", e); }
    }

    // JDK HTTP server limits are JVM-wide and read on its first initialization.
    // Embedders that initialize it earlier must configure these themselves.
    private static void configureTransportDefaults() {
        Map.of("jdk.httpserver.maxConnections", "1024",
               "sun.net.httpserver.maxReqHeaders", "100",
               "sun.net.httpserver.maxReqHeaderSize", "32768",
               "sun.net.httpserver.maxReqTime", "15",
               "sun.net.httpserver.maxRspTime", "30",
               "sun.net.httpserver.drainAmount", "1",
               "sun.net.httpserver.nodelay", "true").forEach((key, value) -> System.getProperties().putIfAbsent(key, value));
    }

    public Handler handler(ActorRuntime.ActorCodeExecutor code, String type, ActorRuntime.ActorKind kind) {
        if (ActorRuntime.inActorExecution()) throw new SecurityException("HTTP handlers are registered by the supervisor");
        if (kind == ActorRuntime.ActorKind.UNTRUSTED) throw new SecurityException("untrusted HTTP handlers are not supported");
        return new Handler(code, type, kind);
    }

    /** Immutable code registration; no captured guest objects enter the actor heap. */
    public final class Handler {
        private final ActorRuntime.ActorCodeExecutor code;
        private final String type;
        private final ActorRuntime.ActorKind kind;
        private Handler(ActorRuntime.ActorCodeExecutor code, String type, ActorRuntime.ActorKind kind) {
            this.code = code; this.type = type; this.kind = kind;
        }
        public boolean isolated() { return kind == ActorRuntime.ActorKind.PRIVATE; }
        public void dispatch(Exchange exchange) {
            if (ActorRuntime.inActorExecution()) throw new SecurityException("HTTP dispatch belongs to supervisor");
            synchronized (exchange.state) { exchange.checkOwner(); exchange.state.spawnNanos = System.nanoTime(); }
            ActorRuntime.ActorRef<Object> actor = null;
            try {
                actor = context.actors().spawnCodeActor(kind, code, type);
                actor.ready().whenCompleteRuntime((ignored, failure) -> {
                    synchronized (exchange.state) { exchange.state.readyNanos = System.nanoTime(); }
                });
                actor.send(exchange.moveTo(actor));
            } catch (RuntimeException failure) {
                if (actor != null) actor.cancel();
                exchange.state.abort(500);
            }
        }
    }

    public Exchange claim(String token) {
        Exchange exchange = transfers.get(token);
        if (exchange == null) throw new IllegalStateException("unknown or already claimed HTTP transfer");
        synchronized (exchange.state) {
            exchange.checkOwner();
            if (!transfers.remove(token, exchange)) throw new IllegalStateException("HTTP transfer already claimed");
            exchange.state.ticket = null;
            exchange.state.claimedNanos = System.nanoTime();
            return exchange;
        }
    }

    private Object owner() {
        if (ActorRuntime.inActorExecution()) {
            if (ActorRuntime.currentActorRuntime() != context.actors()) throw new SecurityException("foreign actor runtime");
            return ActorRuntime.currentActorId().orElseThrow();
        }
        return context;
    }

    public final class Server implements AutoCloseable {
        private final HttpServer transport;
        private final Semaphore admission;
        private final int bodyLimit, deadlineMillis;
        private final ArrayDeque<Exchange> pending = new ArrayDeque<>();
        private final Set<State> active = ConcurrentHashMap.newKeySet();
        private OresFuture<Object> waiter;
        private boolean stopped, transportStopped;
        private OresFuture<Object> drained;
        private final AtomicLong accepted = new AtomicLong(), rejected = new AtomicLong(), completed = new AtomicLong();

        private Server(HttpServer transport, int capacity, int bodyLimit, int deadlineMillis) {
            this.transport = transport; this.admission = new Semaphore(capacity);
            this.bodyLimit = bodyLimit; this.deadlineMillis = deadlineMillis;
            transport.setExecutor(io);
            transport.createContext("/", this::receive);
        }
        private void supervisor() {
            if (ActorRuntime.inActorExecution()) throw new SecurityException("HTTP server is supervisor-owned");
        }
        public int port() { supervisor(); return transport.getAddress().getPort(); }
        public long accepted() { supervisor(); return accepted.get(); }
        public long rejected() { supervisor(); return rejected.get(); }
        public long completed() { supervisor(); return completed.get(); }
        public int active() { supervisor(); return active.size(); }

        public synchronized OresFuture<Object> accept() {
            supervisor();
            if (stopped) return OresFuture.failed(new IllegalStateException("HTTP server closed"));
            if (waiter != null) throw new IllegalStateException("only one pending HTTP accept is permitted");
            Exchange exchange = pending.poll();
            while (exchange != null && exchange.state.terminal) exchange = pending.poll();
            if (exchange != null) return OresFuture.completed(exchange);
            waiter = new OresFuture<>(() -> false, () -> { });
            return waiter;
        }
        private void receive(HttpExchange raw) {
            if (!admission.tryAcquire()) { rejected.incrementAndGet(); reject(raw, 503); return; }
            State state = null;
            try {
                validateRequest(raw);
                state = new State(this, raw);
                synchronized (this) {
                    if (stopped) { admission.release(); reject(raw, 503); return; }
                    active.add(state); accepted.incrementAndGet();
                    State timed = state;
                    state.deadline = timers.schedule(() -> timed.abort(504), deadlineMillis, TimeUnit.MILLISECONDS);
                    Exchange exchange = new Exchange(state, context, 0);
                    if (waiter == null) pending.add(exchange);
                    else { OresFuture<Object> next = waiter; waiter = null; next.completeFromRuntime(exchange); }
                }
            } catch (IllegalArgumentException invalid) {
                admission.release(); reject(raw, invalid.getMessage().equals("body too large") ? 413 : 400);
            } catch (RuntimeException failure) {
                if (state != null && active.contains(state)) state.abort(500);
                else { admission.release(); raw.close(); }
            }
        }
        private void validateRequest(HttpExchange raw) {
            if (raw.getRequestURI().isAbsolute() || raw.getRequestURI().getRawAuthority() != null || raw.getRequestURI().getRawFragment() != null)
                throw new IllegalArgumentException("only origin-form request targets are supported");
            String target = raw.getRequestURI().toASCIIString();
            if (target.length() > 8192) throw new IllegalArgumentException("request target too long");
            segments(raw.getRequestURI().getRawPath());
            int size = 0;
            for (var entry : raw.getRequestHeaders().entrySet()) {
                size += entry.getKey().length();
                for (String value : entry.getValue()) size += value.length();
            }
            if (size > 32768 || raw.getRequestHeaders().size() > 100) throw new IllegalArgumentException("headers too large");
            String length = raw.getRequestHeaders().getFirst("Content-Length");
            if (length != null && Long.parseLong(length) > bodyLimit) throw new IllegalArgumentException("body too large");
        }
        public synchronized OresFuture<Object> shutdown(int graceMillis) {
            supervisor();
            if (graceMillis < 0) throw new IllegalArgumentException("negative drain deadline");
            if (drained != null) return drained;
            drained = new OresFuture<>(() -> false, () -> { });
            stopAccepting();
            io.execute(() -> {
                try { transport.stop((int) (((long) graceMillis + 999) / 1000)); }
                finally {
                    synchronized (this) { transportStopped = true; }
                    for (State state : active) state.abort(503);
                    servers.remove(this);
                    completeDrain();
                }
            });
            return drained;
        }
        private synchronized void completeDrain() {
            if (transportStopped && active.isEmpty() && drained != null) drained.completeFromRuntime(null);
        }
        private synchronized void stopAccepting() {
            stopped = true;
            if (waiter != null) { waiter.failFromRuntime(new IllegalStateException("HTTP server closed")); waiter = null; }
            pending.clear();
        }
        @Override public void close() {
            stopAccepting(); transport.stop(0);
            synchronized (this) { transportStopped = true; }
            for (State state : active) state.abort(503);
            servers.remove(this); completeDrain();
        }
    }

    private final class State {
        final Server server;
        final HttpExchange raw;
        final List<String> path;
        long generation;
        long spawnNanos, readyNanos, movedNanos, claimedNanos;
        Object owner;
        boolean busy, committed, bodyRead, streamingBody, suppressBody;
        int bodyBytes;
        volatile boolean terminal;
        boolean transportClosed;
        String ticket;
        final String requestId = UUID.randomUUID().toString();
        final long admittedNanos = System.nanoTime();
        final OresFuture<Object> completion = new OresFuture<>(() -> false, () -> { });
        int responseStatus;
        String operationId = "";
        int pipelineId = -1, contractId = -1;
        final Map<String, List<String>> params = new LinkedHashMap<>();
        ScheduledFuture<?> deadline;
        ActorRuntime.ActorRef<?> actor;
        State(Server server, HttpExchange raw) {
            this.server = server; this.raw = raw; this.path = segments(raw.getRequestURI().getRawPath()); this.owner = context;
        }
        void abort(int status) {
            boolean send, alreadyClosed;
            synchronized (this) {
                alreadyClosed = terminal;
                send = !terminal && !committed && !busy;
                if (!terminal) responseStatus = send ? status : 0;
                terminal = true;
            }
            // Never perform socket I/O or actor cancellation while holding the ownership monitor.
            if (actor != null) actor.cancel();
            if (alreadyClosed) { release(); return; }
            try { io.execute(() -> { try { if (send) reject(raw, status); else raw.close(); } finally { synchronized (this) { transportClosed = true; } release(); } }); }
            catch (RejectedExecutionException closed) { release(); }
        }
        void finish() {
            synchronized (this) { if (terminal) return; terminal = true; }
            try { raw.close(); } finally { synchronized (this) { transportClosed = true; } release(); }
        }
        void release() {
            // Admission includes the actor lifetime, even after the response ends.
            // A handler that forgets self.end cannot accumulate unbounded actors.
            synchronized (this) {
                if (!transportClosed || (actor != null && !actor.done().isDone())) return;
                if (ticket != null) { transfers.remove(ticket); ticket = null; }
                if (deadline != null) deadline.cancel(false);
            }
            if (server.active.remove(this)) {
                synchronized (server) { server.pending.removeIf(exchange -> exchange.state == this); }
                server.admission.release(); server.completed.incrementAndGet(); server.completeDrain();
                completion.completeFromRuntime((long) responseStatus);
            }
        }
    }

    /** A generation-bound capability; moving invalidates every alias of the sender wrapper. */
    public final class Exchange {
        private final State state;
        private final Object owner;
        private final long generation;
        private Exchange(State state, Object owner, long generation) {
            this.state = state; this.owner = owner; this.generation = generation;
        }
        private void checkOwner() {
            if (state.terminal) throw new IllegalStateException("HTTP exchange is closed");
            if (generation != state.generation || !owner.equals(state.owner)) throw new IllegalStateException("HTTP exchange has moved");
            if (!owner.equals(NativeHttp.this.owner())) throw new SecurityException("HTTP exchange belongs to another execution domain");
        }
        public String moveTo(ActorRuntime.ActorRef<?> destination) {
            synchronized (state) {
                checkOwner();
                if (ActorRuntime.inActorExecution()) throw new SecurityException("only the supervisor may dispatch an HTTP exchange");
                if (state.busy || state.committed) throw new IllegalStateException("cannot move an active HTTP exchange");
                if (!context.actors().isAlive(destination)) throw new IllegalStateException("destination actor is not alive");
                if (destination.kind() == ActorRuntime.ActorKind.UNTRUSTED) throw new SecurityException("HTTP exchange transfer to untrusted actors is not supported");
                state.movedNanos = System.nanoTime();
                state.generation++; state.owner = destination.id(); state.actor = destination;
                String token = UUID.randomUUID().toString(); state.ticket = token;
                Exchange moved = new Exchange(state, destination.id(), state.generation);
                transfers.put(token, moved);
                destination.done().whenCompleteRuntime((ignored, failure) -> state.abort(500));
                return token;
            }
        }
        private void supervisorMetadata() {
            checkOwner();
            if (ActorRuntime.inActorExecution() || state.committed || state.busy)
                throw new SecurityException("route metadata is set by supervisor before dispatch");
        }
        public void route(String operation, int pipeline, int contract) {
            synchronized (state) { supervisorMetadata(); state.operationId = operation; state.pipelineId = pipeline; state.contractId = contract; }
        }
        public void addParam(String name, String value) {
            synchronized (state) { supervisorMetadata(); state.params.computeIfAbsent(name, key -> new ArrayList<>()).add(value); }
        }
        public String param(String name) { synchronized (state) { checkOwner(); return state.params.getOrDefault(name, List.of("")).getFirst(); } }
        public int paramSize(String name) { synchronized (state) { checkOwner(); return state.params.getOrDefault(name, List.of()).size(); } }
        public String paramAt(String name, int index) { synchronized (state) { checkOwner(); return state.params.getOrDefault(name, List.of()).get(index); } }
        public boolean canRespond() {
            synchronized (state) {
                return owner.equals(NativeHttp.this.owner()) && generation == state.generation
                    && !state.terminal && !state.busy && !state.committed;
            }
        }
        public String serverTiming() { return "actor-startup;dur=" + startupNanos() / 1_000_000.0 + ", move-to-claim;dur=" + transferNanos() / 1_000_000.0; }
        public long startupNanos() { synchronized (state) { checkOwner(); return state.spawnNanos == 0 ? 0 : Math.max(0, state.readyNanos - state.spawnNanos); } }
        public long transferNanos() { synchronized (state) { checkOwner(); return state.claimedNanos == 0 ? 0 : Math.max(0, state.claimedNanos - state.movedNanos); } }
        public long admittedNanos() { synchronized (state) { checkOwner(); return state.admittedNanos; } }
        public OresFuture<Object> completion() { synchronized (state) { checkOwner(); return state.completion; } }
        public String requestId() { synchronized (state) { checkOwner(); return state.requestId; } }
        public String operationId() { synchronized (state) { checkOwner(); return state.operationId; } }
        public int pipelineId() { synchronized (state) { checkOwner(); return state.pipelineId; } }
        public int contractId() { synchronized (state) { checkOwner(); return state.contractId; } }
        public String cookie(String name) {
            for (String item : header("Cookie").split(";")) {
                String[] pair = item.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals(name)) return pair[1];
            }
            return "";
        }
        public String method() { synchronized (state) { checkOwner(); return state.raw.getRequestMethod(); } }
        public String target() { synchronized (state) { checkOwner(); return state.raw.getRequestURI().toASCIIString(); } }
        public int pathSize() { synchronized (state) { checkOwner(); return state.path.size(); } }
        public String pathAt(int index) { synchronized (state) { checkOwner(); return state.path.get(index); } }
        public String header(String name) { synchronized (state) { checkOwner(); return Objects.toString(state.raw.getRequestHeaders().getFirst(name), ""); } }
        public String query(String name) {
            synchronized (state) {
                checkOwner();
                String query = state.raw.getRequestURI().getRawQuery();
                if (query == null) return "";
                for (String pair : query.split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (decode(parts[0], true).equals(name)) return parts.length == 1 ? "" : decode(parts[1], true);
                }
                return "";
            }
        }
        public void setHeader(String name, String value) {
            synchronized (state) {
                checkOwner();
                if (state.committed || state.busy) throw new IllegalStateException("response already started");
                if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value.chars().anyMatch(c -> c < 32 || c == 127))
                    throw new IllegalArgumentException("invalid HTTP header");
                if (Set.of("content-length", "transfer-encoding", "connection", "trailer", "upgrade").contains(name.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("transport-owned response header");
                state.raw.getResponseHeaders().set(name, value);
            }
        }
        public OresFuture<Object> bodyText() {
            synchronized (state) {
                checkOwner();
                if (state.busy) throw new IllegalStateException("await the pending HTTP operation first");
                if (state.bodyRead) throw new IllegalStateException("request body already consumed");
                state.bodyRead = true;
            }
            return operation(() -> {
                byte[] bytes = state.raw.getRequestBody().readNBytes(state.server.bodyLimit + 1);
                if (bytes.length > state.server.bodyLimit) throw new IllegalArgumentException("body too large");
                return utf8(bytes);
            });
        }
        public OresFuture<Object> readChunk(int maximum) {
            if (maximum < 1 || maximum > 65536) throw new IllegalArgumentException("chunk size must be 1..65536");
            synchronized (state) {
                checkOwner();
                if (state.busy) throw new IllegalStateException("await the pending HTTP operation first");
                if (state.bodyRead && !state.streamingBody) throw new IllegalStateException("request body already consumed");
                state.streamingBody = true; state.bodyRead = true;
            }
            return operation(() -> {
                byte[] bytes = state.raw.getRequestBody().readNBytes(Math.min(maximum, state.server.bodyLimit - state.bodyBytes + 1));
                state.bodyBytes += bytes.length;
                if (state.bodyBytes > state.server.bodyLimit) throw new IllegalArgumentException("body too large");
                List<Object> owned = new ArrayList<>(bytes.length);
                for (byte value : bytes) owned.add((long) (value & 255));
                return owned;
            });
        }
        public OresFuture<Object> respond(int status, String contentType, String body) {
            return respondBuffer(status, contentType, body.getBytes(StandardCharsets.UTF_8));
        }
        public OresFuture<Object> respondBytes(int status, String contentType, List<?> values) {
            return respondBuffer(status, contentType, byteBuffer(values, state.server.bodyLimit));
        }
        private OresFuture<Object> respondBuffer(int status, String contentType, byte[] bytes) {
            validateStatus(status); setHeader("Content-Type", contentType);
            if (bytes.length > state.server.bodyLimit) throw new IllegalArgumentException("response too large");
            return operation(() -> {
                synchronized (state) {
                    if (state.committed) throw new IllegalStateException("response already started");
                    state.committed = true;
                    state.responseStatus = status;
                }
                boolean noBody = noBody(status);
                if (state.raw.getRequestMethod().equals("HEAD") && status != 204 && status != 205)
                    state.raw.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
                state.raw.sendResponseHeaders(status, noBody || bytes.length == 0 ? -1 : bytes.length);
                if (!noBody && bytes.length != 0) state.raw.getResponseBody().write(bytes);
                state.finish(); return null;
            });
        }
        public OresFuture<Object> start(int status, String contentType) {
            validateStatus(status); setHeader("Content-Type", contentType);
            return operation(() -> {
                synchronized (state) {
                    if (state.committed) throw new IllegalStateException("response already started");
                    state.committed = true;
                    state.responseStatus = status;
                }
                state.suppressBody = noBody(status);
                state.raw.sendResponseHeaders(status, state.suppressBody ? -1 : 0);
                return null;
            });
        }
        public OresFuture<Object> write(String text) { return writeBuffer(text.getBytes(StandardCharsets.UTF_8)); }
        public OresFuture<Object> writeBytes(List<?> values) { return writeBuffer(byteBuffer(values, Math.min(65536, state.server.bodyLimit))); }
        private OresFuture<Object> writeBuffer(byte[] bytes) {
            if (bytes.length > state.server.bodyLimit) throw new IllegalArgumentException("response chunk too large");
            return operation(() -> {
                if (!state.committed) throw new IllegalStateException("response not started");
                if (!state.suppressBody) { state.raw.getResponseBody().write(bytes); state.raw.getResponseBody().flush(); }
                return null;
            });
        }
        public OresFuture<Object> finish() { return operation(() -> { if (!state.committed) throw new IllegalStateException("response not started"); state.finish(); return null; }); }
        public void abort() { synchronized (state) { checkOwner(); } state.abort(500); }
        private boolean noBody(int status) { return state.raw.getRequestMethod().equals("HEAD") || status == 204 || status == 205 || status == 304; }
        private OresFuture<Object> operation(Callable<Object> operation) {
            synchronized (state) {
                checkOwner();
                if (state.busy) throw new IllegalStateException("await the pending HTTP operation first");
                state.busy = true;
            }
            OresFuture<Object> result = new OresFuture<>(() -> false, () -> { });
            try {
                io.execute(() -> {
                    try {
                        Object value = operation.call();
                        synchronized (state) { state.busy = false; }
                        result.completeFromRuntime(value);
                    } catch (Throwable failure) {
                        synchronized (state) { state.busy = false; }
                        state.abort("body too large".equals(failure.getMessage()) ? 413 : 500);
                        SourceBoundaryTrace.record(failure, "native:http", state.requestId, "I/O");
                        result.failFromRuntime(failure);
                        if (failure instanceof VirtualMachineError fatal) throw fatal;
                        if (failure instanceof ThreadDeath fatal) throw fatal;
                        if (failure instanceof LinkageError fatal) throw fatal;
                    }
                });
            } catch (RuntimeException failure) { state.abort(500); result.failFromRuntime(failure); }
            return result;
        }
    }

    private static byte[] byteBuffer(List<?> values, int maximum) {
        if (values.size() > maximum) throw new IllegalArgumentException("byte buffer too large");
        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (!(value instanceof Long number) || number < 0 || number > 255) throw new IllegalArgumentException("HTTP bytes must be integers in 0..255");
            bytes[i] = (byte) number.longValue();
        }
        return bytes;
    }

    private static void validateStatus(int status) { if (status < 200 || status > 599) throw new IllegalArgumentException("status must be 200..599"); }
    static List<String> segments(String path) {
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("expected origin-form path");
        if (path.equals("/")) return List.of();
        String[] parts = path.substring(1).split("/", -1);
        if (parts.length > 128) throw new IllegalArgumentException("too many path segments");
        return Arrays.stream(parts).map(s -> decode(s, false)).toList();
    }
    static String decode(String text, boolean form) {
        // URLDecoder preserves invalid UTF-8 by replacement; round-trip through a strict decoder instead.
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i++);
            if (c == '%') {
                if (i + 1 >= text.length()) throw new IllegalArgumentException("malformed percent escape");
                int hi = Character.digit(text.charAt(i++), 16), lo = Character.digit(text.charAt(i++), 16);
                if (hi < 0 || lo < 0) throw new IllegalArgumentException("malformed percent escape");
                bytes.write((hi << 4) | lo);
            } else {
                String literal;
                if (Character.isHighSurrogate(c) && i < text.length() && Character.isLowSurrogate(text.charAt(i))) {
                    literal = new String(new char[] {c, text.charAt(i++)});
                } else {
                    if (Character.isSurrogate(c)) throw new IllegalArgumentException("invalid Unicode surrogate");
                    literal = form && c == '+' ? " " : String.valueOf(c);
                }
                bytes.writeBytes(literal.getBytes(StandardCharsets.UTF_8));
            }
        }
        String result = utf8(bytes.toByteArray());
        if (result.chars().anyMatch(c -> c < 32 || c == 127 || c == '\\')) throw new IllegalArgumentException("invalid request character");
        return result;
    }
    private static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw new IllegalArgumentException("invalid UTF-8", invalid); }
    }
    private static void reject(HttpExchange raw, int status) {
        try { raw.sendResponseHeaders(status, -1); } catch (IOException ignored) { } finally { raw.close(); }
    }
    @Override public synchronized void close() {
        closed = true;
        for (Server server : List.copyOf(servers)) server.close();
        timers.shutdownNow(); io.shutdownNow(); transfers.clear();
    }
}
