package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Trusted host substrate for an actor-owned, fixed-size homogeneous ECS shard.
 *
 * <p>One physical layout is authoritative: AoS, SoA, or AoSoA. Allocation
 * and every operation require the same logical actor turn, not the same OS
 * carrier thread. Values never cross actor boundaries as mutable references:
 * snapshotColumn returns a copy. No guest lowering or scoped Task permit is
 * implied by this host-only type.</p>
 */
public final class ActorShardColumns extends ActorShardBacking implements AutoCloseable {
    public enum Layout { AOS, SOA, AOSOA }
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final Layout layout;
    private final int rows;
    private final int fields;
    private final int lanes;
    // Detached on explicit close; runtime-owned lease revokes physical access.
    private double[] storage;
    private final ActorRuntime.ActorShardLease reservation;

    /**
     * Construct only inside a scheduled actor turn. AoSoA must use a power of
     * two lane count; AoS and SoA use lanes=1.
     */
    public ActorShardColumns(Layout layout, int rows, int fields, int lanes) {
        ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("shard creation requires an executing actor"));
        this.layout = Objects.requireNonNull(layout, "layout");
        if (rows <= 0 || fields <= 0) {
            throw new IllegalArgumentException("shard dimensions must be positive");
        }
        if (lanes <= 0 || lanes > 1024 || (lanes & (lanes - 1)) != 0
                || (layout != Layout.AOSOA && lanes != 1)) {
            throw new IllegalArgumentException("invalid shard lane count");
        }
        this.rows = rows;
        this.fields = fields;
        this.lanes = lanes;
        long physicalRows = layout == Layout.AOSOA
                ? ((long) rows + lanes - 1L) / lanes * lanes
                : rows;
        long elements = Math.multiplyExact(physicalRows, (long) fields);
        if (elements > MAX_BYTES / Double.BYTES) {
            throw new IllegalArgumentException("shard exceeds 16 MiB host allocation cap");
        }
        int bytes = Math.toIntExact(elements * Double.BYTES);
        ActorRuntime runtime = ActorRuntime.currentActorRuntime();
        if (runtime == null) {
            throw new SecurityException("shard allocation requires an owning runtime");
        }
        ActorRuntime.ActorShardLease allocated = runtime.reserveActorShard(bytes);
        double[] heap = null;
        try {
            if (!allocated.privateBacking()) heap = new double[(int) elements];
        } catch (RuntimeException | Error failure) {
            allocated.close();
            throw failure;
        }
        this.reservation = allocated;
        this.storage = heap;
        try {
            allocated.attach(this);
        } catch (RuntimeException | Error failure) {
            revokeBackingFromLease();
            allocated.close();
            throw failure;
        }
    }

    @Override
    boolean ownedBy(ActorRuntime runtime, ActorRuntime.ActorId actor) {
        return reservation.ownedBy(runtime, actor);
    }
    @Override
    boolean isPrivateBacked() { return reservation.privateBacking(); }

    public Layout layout() { return layout; }
    public int rows() { return rows; }
    public int fields() { return fields; }
    public int lanes() { return lanes; }

    /**
     * Called by the owning lease before returning its allocation budget.
     * Also runs on actor-stop finalization, without any active actor turn.
     * Must not publish or retain the old mutable backing reference.
     */
    @Override
    void revokeBackingFromLease() {
        double[] old = storage;
        if (old != null) {
            java.util.Arrays.fill(old, 0.0);
            storage = null;
        }
    }

    private void requireOwner() {
        reservation.requireOwner();
    }

    private double get(int index) {
        return reservation.privateBacking() ? reservation.read(index) : storage[index];
    }

    private void put(int index, double value) {
        if (reservation.privateBacking()) reservation.write(index, value);
        else storage[index] = value;
    }

    @Override public void close() {
        // Explicit close is owner-only. Zero and detach shared backing before
        // the lease returns its byte reservation. Repeated owner close remains
        // safe; post-close data access is rejected by the lease.
        if (storage != null) {
            reservation.requireOwner();
            java.util.Arrays.fill(storage, 0.0);
            storage = null;
        }
        reservation.close();
    }

    private int offset(int row, int field) {
        if (row < 0 || row >= rows || field < 0 || field >= fields) {
            throw new IndexOutOfBoundsException("shard row/field outside bounds");
        }
        return switch (layout) {
            case AOS -> row * fields + field;
            case SOA -> field * rows + row;
            case AOSOA -> ((row / lanes) * fields + field) * lanes + row % lanes;
        };
    }

    public double read(int row, int field) {
        requireOwner();
        return get(offset(row, field));
    }

    public void write(int row, int field, double value) {
        requireOwner();
        put(offset(row, field), value);
    }

    /**
     * Dense column update, one physical actor turn for arbitrarily many
     * logical entities. Field columns may be distinct; no per-row actors or
     * Futures are created. This is not yet a parallel borrowed Task kernel.
     */
    public void integrate(int position, int velocity, double dt) {
        requireOwner();
        if (position == velocity || position < 0 || velocity < 0
                || position >= fields || velocity >= fields) {
            throw new IllegalArgumentException("requires distinct valid position/velocity fields");
        }
        if (!Double.isFinite(dt)) throw new IllegalArgumentException("nonfinite dt");
        for (int row = 0; row < rows; row++) {
            int p = offset(row, position);
            put(p, get(p) + get(offset(row, velocity)) * dt);
        }
    }

    /**
     * Quota-charged immutable snapshot handle. Its byte reservation persists
     * until close(), or the owner's actor is physically finalized. An
     * outside-actor trusted host may read it; foreign actor turns cannot.
     */
    public static final class ColumnSnapshot implements AutoCloseable {
        private final ActorRuntime.ActorShardSnapshotLease lease;

        private ColumnSnapshot(ActorRuntime.ActorShardSnapshotLease lease) {
            this.lease = lease;
        }
        public int length() { return lease.length(); }
        public double read(int index) { return lease.read(index); }
        public void copyInto(double[] destination, int offset) {
            lease.copyInto(destination, offset);
        }
        @Override public void close() { lease.close(); }
    }

    public ColumnSnapshot snapshotColumnLeased(int field) {
        requireOwner();
        if (field < 0 || field >= fields) throw new IndexOutOfBoundsException("field");
        ActorRuntime runtime = ActorRuntime.currentActorRuntime();
        ActorRuntime.ActorShardSnapshotLease lease = runtime.reserveActorShardSnapshot(rows);
        try {
            for (int i = 0; i < rows; i++) {
                lease.writeFromOwner(i, get(offset(i, field)));
            }
            return new ColumnSnapshot(lease);
        } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
        }
    }

    /** Copy into preallocated host storage with no new allocation. */
    public void copyColumnInto(int field, double[] dest, int offset) {
        requireOwner();
        Objects.requireNonNull(dest, "dest");
        if (field < 0 || field >= fields) throw new IndexOutOfBoundsException("field");
        if (offset < 0 || offset > dest.length || rows > dest.length - offset)
            throw new IndexOutOfBoundsException("column destination span");
        for (int i = 0; i < rows; i++) dest[offset + i] = get(offset(i, field));
    }

    /**
     * Legacy raw detached copy is intentionally disabled. Returning an array
     * here would outlive the reservation that paid for it and reintroduce an
     * unbounded allocation escape. Use snapshotColumnLeased() or copyColumnInto().
     */
    @Deprecated(forRemoval = true)
    public double[] snapshotColumn(int field) {
        requireOwner();
        if (field < 0 || field >= fields)
            throw new IndexOutOfBoundsException("field");
        throw new UnsupportedOperationException(
                "unmetered detached snapshots are disabled; use a leased snapshot");
    }
}
