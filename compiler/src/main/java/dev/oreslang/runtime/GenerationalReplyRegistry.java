package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

/**
 * Host/runtime-only UUID-free correlation for actor and supervisor APPLICATION
 * calls. Local numeric handles are for indexing, not authorization. The future
 * adapter MUST marshal guest continuations to the logical caller actor lane:
 * CompletableFuture callbacks may otherwise execute inline on a responder.
 */
public final class GenerationalReplyRegistry implements AutoCloseable {
    public enum Role { ACTOR, SUPERVISOR_APPLICATION }
    public enum State { PENDING, REPLIED, REJECTED, CANCELLED, TIMED_OUT }

    /** One runtime-minted object per actor/supervisor incarnation. */
    public static final class Endpoint {
        private final Role role;
        private final Object runtime;
        private Endpoint(Role role, Object runtime) {
            this.role = Objects.requireNonNull(role);
            this.runtime = Objects.requireNonNull(runtime);
        }
        public Role role() { return role; }
    }

    /** The host-only permit. No guest can mint or complete it by integer ID. */
    public static final class Ticket<R> {
        private final GenerationalReplyRegistry owner;
        private final Endpoint caller;
        private final Endpoint callee;
        private final Class<R> replyType;
        private final int index;
        private final long generation;
        private final CompletableFuture<R> future = new CompletableFuture<>();
        private volatile State state = State.PENDING;
        private Ticket(GenerationalReplyRegistry owner, Endpoint caller,
                       Endpoint callee, Class<R> replyType, int index, long generation) {
            this.owner = owner;
            this.caller = caller;
            this.callee = callee;
            this.replyType = replyType;
            this.index = index;
            this.generation = generation;
        }
        public CompletionStage<R> result() { return future.minimalCompletionStage(); }
        public State state() { return state; }
        // Local diagnostics only, scoped by registry and caller incarnation.
        long localHandle() {
            return (generation << 32) | (Integer.toUnsignedLong(index) + 1);
        }
        boolean reply(Endpoint responder, R value) {
            Objects.requireNonNull(value, "unary reply value");
            if (!replyType.isInstance(value)) throw new IllegalArgumentException("reply type mismatch");
            if (!owner.claim(this, responder, true, State.REPLIED)) return false;
            future.complete(value);
            return true;
        }
        boolean reject(Endpoint responder, Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            if (!owner.claim(this, responder, true, State.REJECTED)) return false;
            future.completeExceptionally(failure);
            return true;
        }
        boolean cancel(Endpoint requester) {
            if (!owner.claim(this, requester, false, State.CANCELLED)) return false;
            future.completeExceptionally(new CancellationException("actor call cancelled"));
            return true;
        }
        boolean timeout(Endpoint requester) {
            if (!owner.claim(this, requester, false, State.TIMED_OUT)) return false;
            future.completeExceptionally(new TimeoutException("actor call timed out"));
            return true;
        }
        private void terminated() {
            future.completeExceptionally(new CancellationException("actor endpoint retired"));
        }
    }

    private static final long MAX_GENERATION = 0xffff_ffffL;
    private final Object runtime = new Object();
    private final IdentityHashMap<Endpoint, Boolean> liveEndpoints = new IdentityHashMap<>();
    private final Ticket<?>[] slots;
    private final long[] generations;
    private final int[] next;
    private int freeHead;
    private int active;
    private boolean closed;

    public GenerationalReplyRegistry(int capacity) {
        if (capacity <= 0 || capacity == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("reply capacity must be positive and bounded");
        }
        slots = new Ticket<?>[capacity];
        generations = new long[capacity];
        next = new int[capacity];
        for (int i = 0; i < capacity; ++i) next[i] = i + 1 < capacity ? i + 1 : -1;
        freeHead = 0;
    }
    /** Minted solely by trusted host/scheduler adapters. */
    synchronized Endpoint newActorEndpoint() { return mint(Role.ACTOR); }
    synchronized Endpoint newSupervisorApplicationEndpoint() {
        return mint(Role.SUPERVISOR_APPLICATION);
    }
    private Endpoint mint(Role role) {
        if (closed) throw new IllegalStateException("reply registry closed");
        Endpoint endpoint = new Endpoint(role, runtime);
        liveEndpoints.put(endpoint, Boolean.TRUE);
        return endpoint;
    }

    /** Caller-owned slot reservation precedes payload copy or mailbox enqueue. */
    synchronized <R> Ticket<R> reserve(Endpoint caller, Endpoint callee, Class<R> replyType) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(callee, "callee");
        Objects.requireNonNull(replyType, "reply type");
        if (caller.runtime != runtime || callee.runtime != runtime
                || !liveEndpoints.containsKey(caller) || !liveEndpoints.containsKey(callee)) {
            throw new SecurityException("foreign or retired runtime endpoint");
        }
        if (caller == callee) throw new IllegalArgumentException("self-call would deadlock");
        if (replyType == Void.TYPE || replyType == Void.class) {
            throw new IllegalArgumentException("unary reply must have a value");
        }
        if (closed) throw new IllegalStateException("reply registry closed");
        // 32-bit generation exhaustion permanently retires the physical slot.
        while (freeHead != -1 && generations[freeHead] == MAX_GENERATION) {
            freeHead = next[freeHead];
        }
        if (freeHead == -1) throw new IllegalStateException("reply capacity exhausted");
        int index = freeHead;
        freeHead = next[index];
        long generation = ++generations[index];
        Ticket<R> ticket = new Ticket<>(this, caller, callee, replyType, index, generation);
        slots[index] = ticket;
        active++;
        return ticket;
    }

    private synchronized boolean claim(Ticket<?> ticket, Endpoint endpoint,
                                       boolean fromCallee, State terminal) {
        if (endpoint != (fromCallee ? ticket.callee : ticket.caller)) {
            throw new SecurityException("reply endpoint capability mismatch");
        }
        if (slots[ticket.index] != ticket || generations[ticket.index] != ticket.generation) {
            return false;
        }
        ticket.state = terminal;
        slots[ticket.index] = null;
        next[ticket.index] = freeHead;
        freeHead = ticket.index;
        active--;
        return true;
    }
    public synchronized int pendingCount() { return active; }

    /** Retire an incarnation and all calls to/from it, but not other endpoints. */
    void retire(Endpoint endpoint) {
        if (endpoint == null || endpoint.runtime != runtime) {
            throw new SecurityException("foreign endpoint retirement");
        }
        List<Ticket<?>> abandoned = new ArrayList<>();
        synchronized (this) {
            if (liveEndpoints.remove(endpoint) == null) {
                throw new SecurityException("unknown or retired endpoint");
            }
            for (int i = 0; i < slots.length; ++i) {
                Ticket<?> ticket = slots[i];
                if (ticket != null && (ticket.caller == endpoint || ticket.callee == endpoint)) {
                    ticket.state = State.CANCELLED;
                    slots[i] = null;
                    next[i] = freeHead;
                    freeHead = i;
                    active--;
                    abandoned.add(ticket);
                }
            }
        }
        // Never execute arbitrary CompletableFuture callbacks while holding monitor.
        for (Ticket<?> ticket : abandoned) ticket.terminated();
    }

    @Override public void close() {
        List<Ticket<?>> abandoned = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            closed = true;
            liveEndpoints.clear();
            for (int i = 0; i < slots.length; ++i) {
                Ticket<?> ticket = slots[i];
                if (ticket != null) {
                    ticket.state = State.CANCELLED;
                    slots[i] = null;
                    next[i] = freeHead;
                    freeHead = i;
                    active--;
                    abandoned.add(ticket);
                }
            }
        }
        for (Ticket<?> ticket : abandoned) ticket.terminated();
    }
}
