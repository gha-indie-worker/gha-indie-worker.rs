package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * VM-owned, scheduler-free, two-dimensional transport admission primitive.
 *
 * A request/connection occupies one global slot. Each admitted connection
 * may have up to messageConcurrency active message leases. Pending requests
 * and messages are bounded FIFO queues, never threads or parked carriers.
 *
 * THIS IS ONLY AN ADMISSION CONTROL PRIMITIVE: it does NOT grant concurrent
 * access to an actor's mutable state, invoke source handlers, or override the
 * ActorCell single-writer lease. Transport adapters must validate isolated
 * request-local state / exclusive loans before dispatching concurrently.
 */
public final class ActorTransportAdmission implements AutoCloseable {
    private final Object monitor = new Object();
    private final int requestConcurrency;
    private final int messageConcurrency;
    private final int maxPendingRequests;
    private final int maxPendingMessagesPerRequest;
    private final ArrayDeque<PendingRequest> requests = new ArrayDeque<>();
    /** Never invoke Future callbacks while the admission monitor is held. */
    private final ArrayDeque<Runnable> readyCompletions = new ArrayDeque<>();
    private boolean dispatchingCompletions;
    private final Set<RequestLease> activeRequests =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    public ActorTransportAdmission(
            int requestConcurrency, int messageConcurrency,
            int maxPendingRequests, int maxPendingMessagesPerRequest) {
        if (requestConcurrency < 1 || messageConcurrency < 1
                || maxPendingRequests < 0 || maxPendingMessagesPerRequest < 0)
            throw new IllegalArgumentException("invalid transport admission limits");
        this.requestConcurrency = requestConcurrency;
        this.messageConcurrency = messageConcurrency;
        this.maxPendingRequests = maxPendingRequests;
        this.maxPendingMessagesPerRequest = maxPendingMessagesPerRequest;
    }

    public int activeRequestCount() {
        synchronized (monitor) { return activeRequests.size(); }
    }

    public int pendingRequestCount() {
        synchronized (monitor) { return requests.size(); }
    }

    public OresFuture<RequestLease> requestAsync() {
        synchronized (monitor) {
            if (closed) return OresFuture.failed(new IllegalStateException("transport admission closed"));
            if (activeRequests.size() < requestConcurrency && requests.isEmpty())
                return OresFuture.completed(createRequestLocked());
            if (requests.size() >= maxPendingRequests)
                return OresFuture.failed(new IllegalStateException("request admission queue full"));
            PendingRequest pending = new PendingRequest();
            requests.addLast(pending);
            return pending.future;
        }
    }

    private RequestLease createRequestLocked() {
        RequestLease lease = new RequestLease();
        activeRequests.add(lease);
        return lease;
    }

    /**
     * Serializes completion publication in grant order without holding the
     * monitor during callbacks, even when a callback re-enters admission.
     * Source guest continuations remain under OresScheduler's own dispatch.
     */
    private void publishCompletions() {
        synchronized (monitor) {
            if (dispatchingCompletions) return;
            dispatchingCompletions = true;
        }
        Throwable firstFailure = null;
        for (;;) {
            Runnable completion;
            synchronized (monitor) {
                completion = readyCompletions.pollFirst();
                if (completion == null) {
                    dispatchingCompletions = false;
                    break;
                }
            }
            try {
                completion.run();
            } catch (Throwable error) {
                if (firstFailure == null) firstFailure = error;
                else firstFailure.addSuppressed(error);
            }
        }
        if (firstFailure != null) {
            if (firstFailure instanceof Error fatal) throw fatal;
            if (firstFailure instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException("transport admission callback failed", firstFailure);
        }
    }

    private void promoteRequestLocked() {
        if (closed) return;
        while (activeRequests.size() < requestConcurrency && !requests.isEmpty()) {
            PendingRequest next = requests.removeFirst();
            if (next.future.isCancelled()) continue;
            RequestLease lease = createRequestLocked();
            // A Future callback can re-enter the gate. Publish outside monitor.
            readyCompletions.addLast(() -> {
                if (!next.future.completeFromRuntime(lease)) lease.close();
            });
        }
    }

    private final class PendingRequest {
        private final OresFuture<RequestLease> future = new OresFuture<>(() -> {
            synchronized (monitor) { requests.remove(this); }
        });
    }

    public final class RequestLease implements AutoCloseable {
        private final ArrayDeque<PendingMessage> messages = new ArrayDeque<>();
        private int activeMessages;
        private boolean ending;
        private boolean released;

        private RequestLease() { }

        public int activeMessageCount() {
            synchronized (monitor) { return activeMessages; }
        }

        public int pendingMessageCount() {
            synchronized (monitor) { return messages.size(); }
        }

        public OresFuture<MessageLease> messageAsync() {
            synchronized (monitor) {
                if (ending || released || closed)
                    return OresFuture.failed(new IllegalStateException("request lease closed"));
                if (activeMessages < messageConcurrency && messages.isEmpty()) {
                    activeMessages++;
                    return OresFuture.completed(new MessageLease(this));
                }
                if (messages.size() >= maxPendingMessagesPerRequest)
                    return OresFuture.failed(new IllegalStateException("message admission queue full"));
                PendingMessage waiter = new PendingMessage(this);
                messages.addLast(waiter);
                return waiter.future;
            }
        }

        private void promoteMessagesLocked() {
            if (ending || closed) return;
            while (activeMessages < messageConcurrency && !messages.isEmpty()) {
                PendingMessage waiter = messages.removeFirst();
                if (waiter.future.isCancelled()) continue;
                activeMessages++;
                MessageLease granted = new MessageLease(this);
                readyCompletions.addLast(() -> {
                    if (!waiter.future.completeFromRuntime(granted)) granted.close();
                });
            }
        }

        private void finishMessage() {
            synchronized (monitor) {
                if (activeMessages == 0)
                    throw new IllegalStateException("message lease double release");
                activeMessages--;
                if (ending) finishRequestLocked();
                else promoteMessagesLocked();
            }
            publishCompletions();
        }

        private void finishRequestLocked() {
            if (!ending || released || activeMessages != 0) return;
            released = true;
            activeRequests.remove(this);
            promoteRequestLocked();
        }

        @Override public void close() {
            List<PendingMessage> abandoned;
            synchronized (monitor) {
                if (ending) return;
                ending = true;
                abandoned = new ArrayList<>(messages);
                messages.clear();
                finishRequestLocked();
            }
            try {
                for (PendingMessage p : abandoned)
                    p.future.failFromRuntime(new IllegalStateException("request lease ended"));
            } finally {
                // A hostile or buggy internal callback must not strand a
                // promoted request's Future behind the release path.
                publishCompletions();
            }
        }
    }

    private final class PendingMessage {
        private final RequestLease request;
        private final OresFuture<MessageLease> future;

        private PendingMessage(RequestLease request) {
            this.request = Objects.requireNonNull(request);
            this.future = new OresFuture<>(() -> {
                synchronized (monitor) { this.request.messages.remove(this); }
            });
        }
    }

    public final class MessageLease implements AutoCloseable {
        private final RequestLease request;
        private boolean released;

        private MessageLease(RequestLease request) { this.request = request; }

        @Override public void close() {
            synchronized (monitor) {
                if (released) return;
                released = true;
            }
            request.finishMessage();
        }
    }

    @Override public void close() {
        List<PendingRequest> pending;
        List<RequestLease> requestsToEnd;
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            pending = new ArrayList<>(requests);
            requests.clear();
            requestsToEnd = new ArrayList<>(activeRequests);
        }
        for (PendingRequest p : pending)
            p.future.failFromRuntime(new IllegalStateException("transport admission closed"));
        for (RequestLease lease : requestsToEnd) lease.close();
    }
}
