package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trusted-host, actor-owned request-slot slab. All hot-path state is charged
 * primitive storage; admission and completion return scalar handles only.
 * PRIVATE actors use their existing direct-memory actor arena. SHARED actors
 * use a quota-charged primitive array. No source Oreslang binding exists yet.
 *
 * <p>Only the owning logical actor turn may access this slab. In particular,
 * callbacks arriving on a foreign I/O carrier must be posted to that owner;
 * they must not mutate it directly. The owner must join external I/O before
 * stopping, since actor finalization revokes backing regardless of pins.</p>
 */
public final class ActorProxyRequestSlab extends ActorShardBacking implements AutoCloseable {
    public enum Layout { SOA, AOSOA }
    public static final int PINS_PER_SLOT = 8;
    private static final AtomicLong NEXT_SLAB_ID = new AtomicLong(1);

    // Never silently roll slab IDs back to 1 after 64-bit exhaustion:
    // a replayed completion would then have the same slab identity.
    static long allocateSlabIdentity(AtomicLong counter) {
        for (;;) {
            long value = counter.get();
            if (value <= 0 || value == Long.MAX_VALUE) {
                throw new IllegalStateException("proxy slab identity exhausted");
            }
            if (counter.compareAndSet(value, value + 1)) return value;
        }
    }

    private static final int FIELDS = 8;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int INCARNATION = 0;
    private static final int EPOCH = 1;
    private static final int CONNECTION = 2;
    private static final int ROUTE = 3;
    private static final int PHASE = 4;
    private static final int PENDING = 5;
    private static final int NEXT_FREE = 6;
    private static final int CLAIM_SERIAL = 7;
    private static final int FREE = 0;
    private static final int OPEN = 1;
    private static final int RETIRING = 2;
    private static final int EXHAUSTED = 3;

    private final ActorRuntime.ActorShardLease lease;
    private final long slabIdentity;
    private final Layout layout;
    private final int capacity;
    private final int lanes;
    private final int physicalRows;
    private final int receiptStart;
    private int[] storage;
    private int freeHead;
    private int live;
    private volatile boolean closed;

    public ActorProxyRequestSlab(Layout layout, int capacity, int lanes) {
        ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("proxy slab requires an owning actor turn"));
        this.layout = Objects.requireNonNull(layout, "layout");
        this.slabIdentity = allocateSlabIdentity(NEXT_SLAB_ID);
        if (capacity < 1 || capacity > 65536
                || (layout == Layout.SOA && lanes != 1)
                || (layout == Layout.AOSOA && lanes != 16 && lanes != 32 && lanes != 64)) {
            throw new IllegalArgumentException("invalid slab capacity or tile width");
        }
        this.capacity = capacity;
        this.lanes = lanes;
        this.physicalRows = layout == Layout.AOSOA
                ? (capacity + lanes - 1) / lanes * lanes : capacity;
        this.receiptStart = physicalRows * FIELDS;
        int words = Math.addExact(receiptStart, Math.multiplyExact(capacity, PINS_PER_SLOT));
        int bytes = Math.multiplyExact(words, Integer.BYTES);
        if (bytes > MAX_BYTES) throw new IllegalArgumentException("slab exceeds quota ceiling");
        ActorRuntime runtime = ActorRuntime.currentActorRuntime();
        if (runtime == null) throw new SecurityException("slab needs runtime owner");
        ActorRuntime.ActorShardLease allocated = runtime.reserveActorShard(bytes);
        int[] heap = null;
        try {
            if (!allocated.privateBacking()) heap = new int[words];
        } catch (RuntimeException | Error failed) {
            allocated.close();
            throw failed;
        }
        this.lease = allocated;
        this.storage = heap;
        this.freeHead = 0;
        try {
            for (int row = 0; row < capacity; row++) {
                put(row, NEXT_FREE, row + 1 == capacity ? -1 : row + 1);
            }
            allocated.attach(this);
        } catch (RuntimeException | Error failed) {
            allocated.close();
            throw failed;
        }
    }

    /** Process-local identity for a trusted I/O completion envelope. */
    public long identity() { owner(); return slabIdentity; }
    public int capacity() { owner(); return capacity; }
    public Layout layout() { owner(); return layout; }
    public int lanes() { owner(); return lanes; }
    public int live() { owner(); return live; }
    public boolean full() { owner(); return freeHead == -1; }

    @Override boolean ownedBy(ActorRuntime runtime, ActorRuntime.ActorId actor) {
        return lease.ownedBy(runtime, actor);
    }
    @Override boolean isPrivateBacked() { return lease.privateBacking(); }

    @Override void revokeBackingFromLease() {
        int[] previous = storage;
        if (previous != null) {
            Arrays.fill(previous, 0);
            storage = null;
        }
        freeHead = -1;
        live = 0;
    }

    private void owner() { lease.requireOwner(); }

    private int fieldOffset(int row, int field) {
        return layout == Layout.SOA ? field * physicalRows + row
                : ((row / lanes) * FIELDS + field) * lanes + (row % lanes);
    }
    private int get(int row, int field) {
        int offset = fieldOffset(row, field);
        return lease.privateBacking() ? lease.readInt(offset) : storage[offset];
    }
    private void put(int row, int field, int value) {
        int offset = fieldOffset(row, field);
        if (lease.privateBacking()) lease.writeInt(offset, value);
        else storage[offset] = value;
    }
    private int receipt(int row, int index) {
        int address = receiptStart + row * PINS_PER_SLOT + index;
        return lease.privateBacking() ? lease.readInt(address) : storage[address];
    }
    private void receipt(int row, int index, int serial) {
        int address = receiptStart + row * PINS_PER_SLOT + index;
        if (lease.privateBacking()) lease.writeInt(address, serial);
        else storage[address] = serial;
    }
    private static long id(int row, int incarnation) {
        return ((long) incarnation << 32) | (row & 0xffffffffL);
    }
    private int checkedRow(long ticket) {
        int row = (int) ticket;
        int incarnation = (int) (ticket >>> 32);
        if (row < 0 || row >= capacity || incarnation <= 0
                || get(row, INCARNATION) != incarnation || get(row, PHASE) == FREE
                || get(row, PHASE) == EXHAUSTED) {
            throw new IllegalStateException("stale or invalid proxy request ticket");
        }
        return row;
    }

    /** O(1), allocation-free. Zero means backpressure (no free slot). */
    public long admit(int epoch, int connection, int route) {
        owner();
        if (epoch <= 0 || connection < 0 || route < 0) {
            throw new IllegalArgumentException("invalid request metadata");
        }
        if (freeHead == -1) return 0L;
        int row = freeHead;
        freeHead = get(row, NEXT_FREE);
        int incarnation = get(row, INCARNATION) + 1;
        put(row, INCARNATION, incarnation);
        put(row, EPOCH, epoch);
        put(row, CONNECTION, connection);
        put(row, ROUTE, route);
        put(row, PHASE, OPEN);
        put(row, PENDING, 0);
        put(row, NEXT_FREE, -1);
        live++;
        return id(row, incarnation);
    }

    public int route(long ticket) { owner(); return get(checkedRow(ticket), ROUTE); }
    public int connection(long ticket) { owner(); return get(checkedRow(ticket), CONNECTION); }
    public int epoch(long ticket) { owner(); return get(checkedRow(ticket), EPOCH); }
    public int pending(long ticket) { owner(); return get(checkedRow(ticket), PENDING); }

    /** Bounded O(8) receipt allocation. Zero means all pin lanes occupied. */
    public long pin(long ticket) {
        owner();
        int row = checkedRow(ticket);
        if (get(row, PHASE) != OPEN) throw new IllegalStateException("request is retiring");
        int serial = get(row, CLAIM_SERIAL);
        if (serial == Integer.MAX_VALUE) throw new IllegalStateException("pin nonce exhausted");
        for (int i = 0; i < PINS_PER_SLOT; i++) {
            if (receipt(row, i) == 0) {
                serial++;
                receipt(row, i, serial);
                put(row, CLAIM_SERIAL, serial);
                put(row, PENDING, get(row, PENDING) + 1);
                int position = row * PINS_PER_SLOT + i + 1;
                return ((long) serial << 32) | (position & 0xffffffffL);
            }
        }
        return 0L;
    }

    /**
     * Resolve only a fully correlated trusted completion envelope. A raw claim
     * serial is insufficient: two different slabs may issue the same number.
     * Callers must preserve the exact slab identity and request ticket through
     * their mailbox/transport continuation; neither is external authority.
     * Returns false for stale, misrouted, forged or repeated completions.
     */
    public boolean settle(long expectedSlab, long ticket, long claim) {
        owner();
        if (expectedSlab != slabIdentity || ticket == 0L || claim == 0L) return false;
        final int ticketRow;
        try { ticketRow = checkedRow(ticket); }
        catch (IllegalStateException stale) { return false; }
        final int position = (int) claim - 1;
        if (position < 0 || position >= capacity * PINS_PER_SLOT
                || position / PINS_PER_SLOT != ticketRow) return false;
        int serial = (int) (claim >>> 32);
        if (serial <= 0) return false;
        int row = position / PINS_PER_SLOT;
        int index = position % PINS_PER_SLOT;
        if (get(row, PHASE) != OPEN && get(row, PHASE) != RETIRING) return false;
        if (receipt(row, index) != serial) return false;
        receipt(row, index, 0);
        int left = get(row, PENDING) - 1;
        put(row, PENDING, left);
        if (left == 0 && get(row, PHASE) == RETIRING) recycle(row);
        return true;
    }

    /** Cancel/close is idempotent until the physical slot is recycled. */
    public boolean retire(long ticket) {
        owner();
        int row;
        try { row = checkedRow(ticket); }
        catch (IllegalStateException stale) { return false; }
        if (get(row, PHASE) == RETIRING) return true;
        if (get(row, PHASE) != OPEN) return false;
        put(row, PHASE, RETIRING);
        if (get(row, PENDING) == 0) recycle(row);
        return true;
    }

    private void recycle(int row) {
        put(row, CONNECTION, 0);
        put(row, ROUTE, 0);
        put(row, EPOCH, 0);
        live--;
        if (get(row, INCARNATION) == Integer.MAX_VALUE
                || get(row, CLAIM_SERIAL) == Integer.MAX_VALUE) {
            put(row, PHASE, EXHAUSTED); // do not wrap to a stale live ticket
        } else {
            put(row, PHASE, FREE);
            put(row, NEXT_FREE, freeHead);
            freeHead = row;
        }
    }

    @Override public void close() {
        if (closed) {
            if (!lease.ownedBy(ActorRuntime.currentActorRuntime(),
                    ActorRuntime.currentActorId().orElse(null))) {
                throw new SecurityException("closed slab requires original actor");
            }
            return;
        }
        owner();
        // Explicit close is permitted only after external I/O has quiesced.
        if (live != 0) throw new IllegalStateException("proxy slab has unretired requests");
        lease.close();
        closed = true;
    }
}
