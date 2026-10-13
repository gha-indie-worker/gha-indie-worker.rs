package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded, arena-owned graph of *non-owning* generational node links.
 *
 * <p>Arbitrary cycles are safe: the arena, not adjacency links, owns the
 * values, and close/clear retires all nodes without recursive destruction or
 * reference-count cycles. Recycled slots carry incremented generations so
 * stale edges and handles cannot alias a new node (ABA).</p>
 *
 * <p>Managed Java arena implementation for the Java runtime. This is not an
 * Oreslang source-language type, an off-heap allocator, a zero-GC collector,
 * or a cross-actor transport capability. External references to values still
 * participate in normal JVM GC and cannot be invalidated by this helper.</p>
 */
public final class GenerationalGraphArena<T> implements AutoCloseable {

    /**
     * Opaque node identity. Keep the identity token separate from the graph,
     * avoiding a strong Java handle reference back to the entire arena.
     */
    public static final class NodeId {
        private final Object arenaIdentity;
        private final int slot;
        private final long generation;

        private NodeId(Object arenaIdentity, int slot, long generation) {
            this.arenaIdentity = arenaIdentity;
            this.slot = slot;
            this.generation = generation;
        }

        public int slot() { return slot; }
        public long generation() { return generation; }

        @Override
        public boolean equals(Object other) {
            return other instanceof NodeId that
                    && arenaIdentity == that.arenaIdentity
                    && slot == that.slot
                    && generation == that.generation;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * System.identityHashCode(arenaIdentity) + slot)
                    + Long.hashCode(generation);
        }

        @Override
        public String toString() {
            return "NodeId[slot=" + slot + ",generation=" + generation + "]";
        }
    }

    private static final int MAX_SLOTS = 65_536;
    private static final int MAX_EDGES = 1_000_000;

    private final Object identity = new Object();
    private final Object[] values;
    private final boolean[] live;
    private final long[] generations;
    private final int[] edgeSlots;
    private final long[] edgeGenerations;
    private final int[] edgeCounts;
    private final int[] freeSlots;
    private final int capacity;
    private final int maxEdgesPerNode;
    private int nextSlot;
    private int freeCount;
    private int liveCount;
    private boolean closed;

    public GenerationalGraphArena(int capacity, int maxEdgesPerNode) {
        if (capacity <= 0 || capacity > MAX_SLOTS
                || maxEdgesPerNode <= 0) {
            throw new IllegalArgumentException("bounded capacity/edge degree required");
        }
        int edgeCapacity = Math.multiplyExact(capacity, maxEdgesPerNode);
        if (edgeCapacity > MAX_EDGES) {
            throw new IllegalArgumentException("graph exceeds bounded edge budget");
        }
        this.capacity = capacity;
        this.maxEdgesPerNode = maxEdgesPerNode;
        this.values = new Object[capacity];
        this.live = new boolean[capacity];
        this.generations = new long[capacity];
        this.edgeSlots = new int[edgeCapacity];
        this.edgeGenerations = new long[edgeCapacity];
        this.edgeCounts = new int[capacity];
        this.freeSlots = new int[capacity];
        java.util.Arrays.fill(generations, 1L);
    }

    public synchronized NodeId add(T value) {
        ensureOpen();
        Objects.requireNonNull(value, "node value");
        int slot;
        if (freeCount > 0) {
            slot = freeSlots[--freeCount];
        } else {
            if (nextSlot >= capacity) throw new IllegalStateException("graph node capacity exhausted");
            slot = nextSlot++;
        }
        if (live[slot]) throw new IllegalStateException("recycled live graph slot");
        values[slot] = value;
        live[slot] = true;
        edgeCounts[slot] = 0;
        liveCount++;
        return new NodeId(identity, slot, generations[slot]);
    }

    /** All edges are non-owning; the graph arena owns node values. */
    public synchronized void connect(NodeId from, NodeId to) {
        int src = requireLive(from);
        requireLive(to);
        int base = src * maxEdgesPerNode;
        int end = edgeCounts[src];
        for (int i = 0; i < end; i++) {
            int index = base + i;
            if (edgeSlots[index] == to.slot
                    && edgeGenerations[index] == to.generation) return;
        }
        // Deleted/reused targets leave stale non-owning edges. Reclaim such
        // entries before deciding that the source has exhausted its degree.
        pruneDeadEdges(src);
        end = edgeCounts[src];
        if (end >= maxEdgesPerNode) throw new IllegalStateException("graph edge degree limit");
        edgeSlots[base + end] = to.slot;
        edgeGenerations[base + end] = to.generation;
        edgeCounts[src]++;
    }

    public synchronized boolean disconnect(NodeId from, NodeId to) {
        int src = requireLive(from);
        if (to == null || to.arenaIdentity != identity) return false;
        int base = src * maxEdgesPerNode;
        for (int i = 0; i < edgeCounts[src]; i++) {
            int index = base + i;
            if (edgeSlots[index] == to.slot && edgeGenerations[index] == to.generation) {
                int last = base + --edgeCounts[src];
                edgeSlots[index] = edgeSlots[last];
                edgeGenerations[index] = edgeGenerations[last];
                edgeSlots[last] = 0;
                edgeGenerations[last] = 0;
                return true;
            }
        }
        return false;
    }

    /** Compact invalidated incoming edges without traversing graph cycles. */
    private void pruneDeadEdges(int source) {
        int base = source * maxEdgesPerNode;
        int i = 0;
        while (i < edgeCounts[source]) {
            int idx = base + i;
            int target = edgeSlots[idx];
            if (target >= 0 && target < nextSlot
                    && live[target] && generations[target] == edgeGenerations[idx]) {
                i++;
                continue;
            }
            int last = base + --edgeCounts[source];
            edgeSlots[idx] = edgeSlots[last];
            edgeGenerations[idx] = edgeGenerations[last];
            edgeSlots[last] = 0;
            edgeGenerations[last] = 0;
        }
    }

    /** A weak-like lookup: a removed node simply no longer resolves. */
    @SuppressWarnings("unchecked")
    public synchronized Optional<T> resolve(NodeId id) {
        if (!isLive(id)) return Optional.empty();
        return Optional.of((T) values[id.slot]);
    }

    public synchronized List<NodeId> neighbors(NodeId from) {
        int src = requireLive(from);
        List<NodeId> out = new ArrayList<>(edgeCounts[src]);
        int base = src * maxEdgesPerNode;
        for (int i = 0; i < edgeCounts[src]; i++) {
            int offset = base + i;
            int target = edgeSlots[offset];
            long version = edgeGenerations[offset];
            if (live[target] && generations[target] == version) {
                out.add(new NodeId(identity, target, version));
            }
        }
        return List.copyOf(out);
    }

    /** Release one node; inbound links become stale and are lazily compacted. */
    public synchronized void remove(NodeId id) {
        int slot = requireLive(id);
        if (generations[slot] == Long.MAX_VALUE) {
            throw new IllegalStateException("graph generation exhausted; retire entire arena");
        }
        live[slot] = false;
        values[slot] = null;
        edgeCounts[slot] = 0;
        generations[slot]++;
        freeSlots[freeCount++] = slot;
        liveCount--;
    }

    public synchronized int size() { return liveCount; }

    /** Retire all cyclic nodes together; stale IDs never become valid again. */
    public synchronized void clear() {
        ensureOpen();
        // Verify all generation increments before modifying any state.
        for (int i = 0; i < nextSlot; i++) {
            if (generations[i] == Long.MAX_VALUE) {
                throw new IllegalStateException("graph generation exhausted");
            }
        }
        for (int i = 0; i < nextSlot; i++) {
            generations[i]++;
            live[i] = false;
            values[i] = null;
            edgeCounts[i] = 0;
        }
        nextSlot = 0;
        freeCount = 0;
        liveCount = 0;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        java.util.Arrays.fill(live, false);
        java.util.Arrays.fill(values, null);
        java.util.Arrays.fill(edgeCounts, 0);
        java.util.Arrays.fill(edgeGenerations, 0);
        java.util.Arrays.fill(edgeSlots, 0);
        freeCount = 0;
        liveCount = 0;
    }

    private int requireLive(NodeId id) {
        ensureOpen();
        if (!isLive(id)) {
            throw new IllegalStateException("foreign, retired or stale graph node handle");
        }
        return id.slot;
    }

    private boolean isLive(NodeId id) {
        return !closed && id != null && id.arenaIdentity == identity
                && id.slot >= 0 && id.slot < nextSlot
                && live[id.slot] && generations[id.slot] == id.generation;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("graph arena is closed");
    }
}
