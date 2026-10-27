package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime-only optimistic scalar cell for continuation-safe actor updates.
 * The caller must own the actor state capability; this class does not grant it.
 * A snapshot is a copied value, NOT an actor-state loan or admission ticket.
 * No carrier thread or mutex is retained across a suspension.
 */
public final class VersionedActorCell {
    public enum Commit { COMMITTED, CONFLICT, FOREIGN, RETIRED, EXHAUSTED }

    private record State(long version, long value, boolean retired) { }

    public static final class Snapshot {
        private final VersionedActorCell origin;
        private final long version;
        private final long value;

        private Snapshot(VersionedActorCell origin, State state) {
            this.origin = origin;
            this.version = state.version();
            this.value = state.value();
        }

        public long value() { return value; }
    }

    private final AtomicReference<State> state;

    public VersionedActorCell(long initialValue) {
        this(initialValue, 0);
    }

    // Test-only exhaustion seam: production callers use version zero.
    VersionedActorCell(long initialValue, long initialVersion) {
        if (initialVersion < 0) throw new IllegalArgumentException("negative version");
        state = new AtomicReference<>(new State(initialVersion, initialValue, false));
    }

    public Snapshot snapshot() {
        State current = state.get();
        if (current.retired()) throw new IllegalStateException("actor state retired");
        return new Snapshot(this, current);
    }

    /** Exactly one atomic commit, never a blind retry of guest computations. */
    public Commit tryCommit(Snapshot captured, long replacement) {
        Objects.requireNonNull(captured, "captured actor snapshot");
        if (captured.origin != this) return Commit.FOREIGN;
        for (;;) {
            State current = state.get();
            if (current.retired()) return Commit.RETIRED;
            if (current.version() != captured.version) return Commit.CONFLICT;
            if (current.version() == Long.MAX_VALUE) return Commit.EXHAUSTED;
            if (state.compareAndSet(current,
                    new State(current.version() + 1, replacement, false))) {
                return Commit.COMMITTED;
            }
        }
    }

    /** Actor shutdown permanently fences all snapshots, including pending awaits. */
    public void retire() {
        for (;;) {
            State current = state.get();
            if (current.retired()) return;
            if (state.compareAndSet(current,
                    new State(current.version(), current.value(), true))) return;
        }
    }
}
