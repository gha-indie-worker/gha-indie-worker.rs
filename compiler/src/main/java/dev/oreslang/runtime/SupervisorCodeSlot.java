package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Versioned host CONTROL-plane code slot for a stable supervisor identity.
 *
 * Acquiring a lease pins one immutable implementation revision. Activation
 * never rewrites a suspended frame. Existing leases finish on old code;
 * new leases observe the new revision. Cleanup only occurs after the final
 * lease releases and must be an enqueue-only owner-thread reclamation hook.
 *
 * This does NOT compile source `define supervisor actor`, migrate guest
 * state or permit Polyglot Context.close() on a foreign actor thread.
 */
public final class SupervisorCodeSlot<P> implements AutoCloseable {
    private final Object lock = new Object();
    private final int maxLiveRevisions;
    private final List<Generation<P>> live = new ArrayList<>();
    private ActorRuntime.ActorSupervisor boundSupervisor;
    private Generation<P> current;
    private boolean closed;

    private static final class Generation<P> {
        private final long revision;
        private final String abi;
        private final P code;
        private final Runnable reclaim;
        private int pins;
        private boolean retired;
        private Generation(long revision, String abi, P code, Runnable reclaim) {
            this.revision = revision;
            this.abi = abi;
            this.code = code;
            this.reclaim = reclaim;
        }
    }

    public record Descriptor(long revision, String abi) {
        public Descriptor {
            if (revision < 1) throw new IllegalArgumentException("revision must be positive");
            if (abi == null || abi.isBlank())
                throw new IllegalArgumentException("supervisor policy ABI required");
        }
    }

    /**
     * Cleanup failed after the new revision was already made visible. Callers
     * must use {@link #committed()} rather than retrying the old revision.
     */
    public static final class PostCommitReclamationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final long committedRevision;
        private final String committedAbi;
        private PostCommitReclamationException(Descriptor committed, Throwable cause) {
            super("supervisor code revision " + committed.revision()
                    + " committed, but retired-policy reclamation failed", cause);
            this.committedRevision = committed.revision();
            this.committedAbi = committed.abi();
        }
        public Descriptor committed() { return new Descriptor(committedRevision, committedAbi); }
    }

    public final class Lease implements AutoCloseable {
        private Generation<P> generation;
        private Lease(Generation<P> generation) {
            this.generation = generation;
        }
        public P code() {
            synchronized (lock) {
                if (generation == null)
                    throw new IllegalStateException("supervisor code lease already released");
                return generation.code;
            }
        }
        public Descriptor descriptor() {
            synchronized (lock) {
                if (generation == null)
                    throw new IllegalStateException("supervisor code lease already released");
                return new Descriptor(generation.revision, generation.abi);
            }
        }
        @Override public void close() {
            Runnable cleanup = null;
            synchronized (lock) {
                if (generation == null) return;
                Generation<P> previous = generation;
                generation = null;
                if (--previous.pins < 0)
                    throw new IllegalStateException("supervisor code lease underflow");
                if (previous.retired && previous.pins == 0) {
                    live.remove(previous);
                    cleanup = previous.reclaim;
                }
            }
            if (cleanup != null) cleanup.run();
        }
    }

    public SupervisorCodeSlot(String initialAbi, P initialCode, Runnable initialReclaim,
            int maxLiveRevisions) {
        if (maxLiveRevisions < 2)
            throw new IllegalArgumentException("at least two live code revisions required");
        requireAbi(initialAbi);
        current = new Generation<>(1, initialAbi, Objects.requireNonNull(initialCode),
                Objects.requireNonNull(initialReclaim));
        this.maxLiveRevisions = maxLiveRevisions;
        live.add(current);
    }

    private static void requireAbi(String abi) {
        if (abi == null || abi.isBlank())
            throw new IllegalArgumentException("supervisor policy ABI required");
    }

    /**
     * One code slot belongs to exactly one runtime-local supervisor identity.
     * Prevents accidentally applying Daddy1's upgrade to Daddy2.
     */
    void bindToSupervisor(ActorRuntime.ActorSupervisor owner) {
        Objects.requireNonNull(owner, "owner");
        synchronized (lock) {
            if (closed) throw new IllegalStateException("supervisor code slot closed");
            if (boundSupervisor != null)
                throw new IllegalStateException("supervisor code slot already bound");
            boundSupervisor = owner;
        }
    }

    public Descriptor current() {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("supervisor code slot closed");
            return new Descriptor(current.revision, current.abi);
        }
    }

    public Lease acquire() {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("supervisor code slot closed");
            current.pins++;
            return new Lease(current);
        }
    }

    /**
     * Commit a pre-validated/migrated immutable policy image. Mismatched ABI
     * never silently changes the live supervisor. A trusted compiler must
     * separately validate source capabilities and state migration.
     *
     * compare-and-activate fences concurrent upgrades at one revision.
     */
    public Descriptor activate(long expectedRevision, String abi, P code, Runnable reclaim) {
        requireAbi(abi);
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(reclaim, "reclaim");
        Runnable cleanup = null;
        Descriptor result;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("supervisor code slot closed");
            if (current.revision != expectedRevision)
                throw new IllegalStateException("stale supervisor code revision");
            if (current.revision == Long.MAX_VALUE)
                throw new IllegalStateException("supervisor code revision space exhausted");
            if (!current.abi.equals(abi))
                throw new IllegalArgumentException(
                        "supervisor code ABI change requires checked state migration");
            if (live.size() >= maxLiveRevisions && current.pins != 0)
                throw new IllegalStateException("pinned supervisor code revision limit exceeded");
            Generation<P> next = new Generation<>(current.revision + 1, abi, code, reclaim);
            Generation<P> old = current;
            old.retired = true;
            current = next;
            if (old.pins == 0) {
                live.remove(old);
                cleanup = old.reclaim;
            }
            live.add(next);
            result = new Descriptor(next.revision, next.abi);
        }
        if (cleanup != null) {
            try {
                cleanup.run();
            } catch (RuntimeException | Error failure) {
                // Activation has already committed under lock; never report a
                // generic failure that invites retrying the previous revision.
                // Reclamation is trusted infrastructure code, so Error must be
                // normalized too: the policy revision is already visible.
                throw new PostCommitReclamationException(result, failure);
            }
        }
        return result;
    }

    public int liveRevisions() {
        synchronized (lock) { return live.size(); }
    }

    @Override public void close() {
        Runnable cleanup = null;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            Generation<P> old = current;
            old.retired = true;
            if (old.pins == 0) {
                live.remove(old);
                cleanup = old.reclaim;
            }
        }
        if (cleanup != null) cleanup.run();
    }
}
