package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded generation-fenced metadata store for logical execution/continuations.
 * A handle identifies a store instance and a live generation, never a carrier,
 * native frame, heap address, or Java object identity.
 *
 * <p>Records are immutable caller-owned values; this block layout is an
 * interoperability substrate, not an assertion that Java objects are SoA.</p>
 */
public final class LogicalTraceStore<T> implements AutoCloseable {
    private static final int LANES = 8;
    private static final AtomicLong NEXT_STORE_ID = new AtomicLong(1);

    public record Handle(long storeId, long slotGeneration) {
        public static final Handle NONE = new Handle(0, 0);
        public boolean isPresent() { return storeId != 0 && slotGeneration != 0; }
    }

    /** Pointer-free causal metadata: absent ancestry is Handle.NONE. */
    public record Continuation(
            Handle parentExecution, Handle parentContinuation,
            long sourceSpan, long awaitSite, long causalKind) {
        public Continuation {
            Objects.requireNonNull(parentExecution, "parentExecution");
            Objects.requireNonNull(parentContinuation, "parentContinuation");
            if (sourceSpan < 0 || awaitSite < 0 || causalKind < 0) {
                throw new IllegalArgumentException("causal metadata must be nonnegative");
            }
        }
    }

    private static final class Block {
        final Object[] values = new Object[LANES];
        final int[] generations = new int[LANES];
        final int[] freeNext = new int[LANES];
    }

    private final Block[] blocks;
    private final int capacity;
    private final long storeId;
    private int nextUnused;
    private int freeHead = -1;
    private boolean closed;

    public LogicalTraceStore(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.blocks = new Block[capacity / LANES + (capacity % LANES == 0 ? 0 : 1)];
        long id = NEXT_STORE_ID.getAndUpdate(current ->
                current > 0 && current < Long.MAX_VALUE ? current + 1 : 0);
        if (id <= 0) throw new IllegalStateException("trace store id space exhausted");
        this.storeId = id;
        for (int i = 0; i < blocks.length; i++) blocks[i] = new Block();
    }

    public synchronized Handle insert(T value) {
        Objects.requireNonNull(value, "value");
        if (closed) return Handle.NONE;
        final int slot;
        if (freeHead != -1) {
            slot = freeHead;
            Block block = blocks[slot / LANES];
            freeHead = block.freeNext[slot % LANES];
        } else if (nextUnused < capacity) {
            slot = nextUnused++;
        } else {
            return Handle.NONE;
        }
        Block block = blocks[slot / LANES];
        int lane = slot % LANES;
        if (block.generations[lane] == 0) block.generations[lane] = 1;
        block.values[lane] = value;
        return new Handle(storeId,
                ((long) block.generations[lane] << 32) | (slot + 1L));
    }

    public synchronized T load(Handle handle) {
        int slot = liveSlot(handle);
        if (slot < 0) return null;
        @SuppressWarnings("unchecked")
        T value = (T) blocks[slot / LANES].values[slot % LANES];
        return value;
    }

    public synchronized boolean release(Handle handle) {
        int slot = liveSlot(handle);
        if (slot < 0) return false;
        Block block = blocks[slot / LANES];
        int lane = slot % LANES;
        block.values[lane] = null;
        if (block.generations[lane] != Integer.MAX_VALUE) {
            block.generations[lane]++;
            block.freeNext[lane] = freeHead;
            freeHead = slot;
        }
        return true;
    }

    private int liveSlot(Handle handle) {
        if (closed || handle == null || handle.storeId() != storeId) return -1;
        int oneBased = (int) handle.slotGeneration();
        int generation = (int) (handle.slotGeneration() >>> 32);
        if (oneBased <= 0 || generation <= 0 || oneBased > nextUnused) return -1;
        int slot = oneBased - 1;
        Block block = blocks[slot / LANES];
        return block.generations[slot % LANES] == generation
                && block.values[slot % LANES] != null ? slot : -1;
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Block block : blocks) java.util.Arrays.fill(block.values, null);
        freeHead = -1;
    }
}
