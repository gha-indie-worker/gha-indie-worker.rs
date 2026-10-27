package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Host-side, nonblocking admission accounting for future typed actor ingress.
 *
 * <p>Ordinary actors have one user-configurable axis: in-flight messages.
 * Network actors have two: open connections and in-flight messages per
 * connection. A message is in flight until its handler COMPLETES or is
 * cancelled, including periods suspended on async I/O. A connection remains
 * open until its transport session is closed, irrespective of in-flight work.
 *
 * <p>This does not execute callbacks or grant socket/request authority. The
 * actor-wide single-writer execution lease remains independently mandatory.
 * NativeHttp still uses listener-wide HTTP exchange admission; connection-
 * aware typed actor adapters must wire this gate into their own dispatch.
 */
public final class ActorIngressConcurrency implements AutoCloseable {
    public enum Protocol { REGULAR, HTTP, TCP, WS }

    private final Protocol protocol;
    private final long incarnation;
    private final int maxConnections;
    private final int maxMessagesPerConnection;
    private final Map<Long, Connection> connections = new HashMap<>();
    private final Map<Long, Message> ordinaryMessages = new HashMap<>();
    private long lastOrdinarySequence;
    private long lastOpenedConnectionId;
    private boolean closed;

    private ActorIngressConcurrency(Protocol protocol, long incarnation,
                                    int connections, int messagesPerConnection) {
        this.protocol = Objects.requireNonNull(protocol);
        if (incarnation <= 0 || connections < 1 || messagesPerConnection < 1)
            throw new IllegalArgumentException("concurrency limits and actor incarnation must be positive");
        this.incarnation = incarnation;
        this.maxConnections = connections;
        this.maxMessagesPerConnection = messagesPerConnection;
    }

    /** One independent user setting for ordinary actors. */
    public static ActorIngressConcurrency regular(long incarnation, int messages) {
        return new ActorIngressConcurrency(Protocol.REGULAR, incarnation, 1, messages);
    }

    /** Exactly two independent user settings for HTTP, TCP and WebSocket actors. */
    public static ActorIngressConcurrency network(Protocol protocol, long incarnation,
                                                  int connections, int messagesPerConnection) {
        if (protocol == null || protocol == Protocol.REGULAR)
            throw new IllegalArgumentException("network concurrency requires HTTP, TCP or WS");
        return new ActorIngressConcurrency(protocol, incarnation, connections, messagesPerConnection);
    }

    public Protocol protocol() { return protocol; }
    public int connectionLimit() { return maxConnections; }
    public int messageLimitPerConnection() { return maxMessagesPerConnection; }
    public synchronized int activeConnections() { return connections.size(); }
    public synchronized int activeOrdinaryMessages() { return ordinaryMessages.size(); }
    public synchronized boolean isClosed() { return closed; }

    /**
     * Host-issued monotonically increasing delivery sequence; 0/replayed
     * sequences fail closed. A full quota returns null without consuming
     * the sequence, allowing the broker to apply bounded backpressure.
     */
    public synchronized Message tryEnterRegular(long sequence, long actorIncarnation) {
        requireIncarnation(actorIncarnation);
        if (protocol != Protocol.REGULAR) throw new IllegalStateException("network actors require connection-scoped admission");
        requireOpen();
        if (sequence <= lastOrdinarySequence) throw new IllegalArgumentException("stale or duplicate actor message");
        if (ordinaryMessages.size() >= maxMessagesPerConnection) return null;
        Message message = new Message(null, sequence);
        ordinaryMessages.put(sequence, message);
        lastOrdinarySequence = sequence;
        return message;
    }

    /**
     * The broker admits a session and receives a fresh, internally minted
     * connection ID. This is atomic under contention: callers cannot choose
     * duplicate, out-of-order or recycled session identities. A full quota
     * returns null without consuming an ID or admitting the connection.
     */
    public synchronized Connection tryOpenConnection(long actorIncarnation) {
        requireIncarnation(actorIncarnation);
        if (protocol == Protocol.REGULAR) throw new IllegalStateException("regular actors have no connections");
        requireOpen();
        if (connections.size() >= maxConnections) return null;
        if (lastOpenedConnectionId == Long.MAX_VALUE)
            throw new IllegalStateException("connection generation exhausted");
        long connectionId = ++lastOpenedConnectionId;
        Connection connection = new Connection(connectionId);
        connections.put(connectionId, connection);
        return connection;
    }

    public final class Connection implements AutoCloseable {
        private final long id;
        private final Map<Long, Message> messages = new HashMap<>();
        private long lastSequence;
        private boolean ended;

        private Connection(long id) { this.id = id; }
        public long id() { return id; }

        /** Each session's message budget is independent of other sessions. */
        public Message tryEnter(long sequence, long actorIncarnation) {
            synchronized (ActorIngressConcurrency.this) {
                requireIncarnation(actorIncarnation);
                requireOpen();
                if (ended || connections.get(id) != this)
                    throw new IllegalStateException("connection has ended");
                if (sequence <= lastSequence)
                    throw new IllegalArgumentException("stale or duplicate connection message");
                if (messages.size() >= maxMessagesPerConnection) return null;
                Message message = new Message(this, sequence);
                messages.put(sequence, message);
                lastSequence = sequence;
                return message;
            }
        }

        public int activeMessages() {
            synchronized (ActorIngressConcurrency.this) { return messages.size(); }
        }

        public boolean isClosed() {
            synchronized (ActorIngressConcurrency.this) { return ended; }
        }

        @Override public void close() {
            synchronized (ActorIngressConcurrency.this) {
                if (ended) return;
                ended = true;
                for (Message message : messages.values()) message.ended = true;
                messages.clear();
                connections.remove(id, this);
            }
        }
    }

    /** One logical handler activation, not a thread, FD or response pointer. */
    public final class Message implements AutoCloseable {
        private final Connection connection;
        private final long sequence;
        private boolean ended;

        private Message(Connection connection, long sequence) {
            this.connection = connection;
            this.sequence = sequence;
        }

        public long sequence() { return sequence; }
        public boolean isClosed() {
            synchronized (ActorIngressConcurrency.this) { return ended; }
        }

        @Override public void close() {
            synchronized (ActorIngressConcurrency.this) {
                if (ended) return;
                ended = true;
                if (connection == null) ordinaryMessages.remove(sequence, this);
                else connection.messages.remove(sequence, this);
            }
        }
    }

    private void requireIncarnation(long actual) {
        if (actual != incarnation) throw new SecurityException("stale or foreign actor incarnation");
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("actor ingress has ended");
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Message message : ordinaryMessages.values()) message.ended = true;
        ordinaryMessages.clear();
        for (Connection connection : connections.values()) {
            connection.ended = true;
            for (Message message : connection.messages.values()) message.ended = true;
            connection.messages.clear();
        }
        connections.clear();
    }
}
