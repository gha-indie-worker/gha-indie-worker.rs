package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trusted-host, actor-identity-preserving hot-upgrade admission fence.
 *
 * This class is deliberately not wired into ActorRuntime yet: a host-only fence
 * cannot certify scheduler quiescence, state migration, sandbox isolation,
 * capability provenance, or FFI/arena leases. The caller must supply compiler-
 * verified digests; guest code must never obtain this coordinator or mint them.
 */
final class ActorUpgradeEpochGate implements AutoCloseable {
    record Image(String codeSha256, String stateSchemaSha256,
                 String messageAbiSha256, String capabilitySha256) {
        Image {
            validateDigest(codeSha256);
            validateDigest(stateSchemaSha256);
            validateDigest(messageAbiSha256);
            validateDigest(capabilitySha256);
        }

        private static void validateDigest(String sha) {
            if (sha == null || !sha.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("expected canonical lowercase SHA-256");
            }
        }

        boolean sameUpgradeBoundary(Image other) {
            return stateSchemaSha256.equals(other.stateSchemaSha256)
                    && messageAbiSha256.equals(other.messageAbiSha256)
                    && capabilitySha256.equals(other.capabilitySha256);
        }
    }

    static final class Lease implements AutoCloseable {
        private final ActorUpgradeEpochGate owner;
        private final long epoch;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(ActorUpgradeEpochGate owner, long epoch) {
            this.owner = owner;
            this.epoch = epoch;
        }

        long epoch() { return epoch; }

        @Override public synchronized void close() {
            // Accounting and authorization retirement share the gate lock.
            // Failed accounting retains the lease so retirement is retryable.
            owner.release(this);
        }
    }

    static final class Candidate {
        private final ActorUpgradeEpochGate owner;
        private final long originEpoch;
        private final Image proposed;

        private Candidate(ActorUpgradeEpochGate owner, long originEpoch, Image proposed) {
            this.owner = owner;
            this.originEpoch = originEpoch;
            this.proposed = proposed;
        }
    }

    private final long actorIdentity;
    private long epoch = 1;
    private int pins;
    private boolean closed;
    private Image active;
    private Candidate candidate;

    ActorUpgradeEpochGate(long actorIdentity, Image initial) {
        if (actorIdentity <= 0) throw new IllegalArgumentException("actor identity must be positive");
        this.actorIdentity = actorIdentity;
        this.active = Objects.requireNonNull(initial);
    }

    synchronized long actorIdentity() { return actorIdentity; }
    synchronized long epoch() { return epoch; }
    synchronized Image image() { return active; }
    synchronized int livePins() { return pins; }
    synchronized boolean frozen() { return candidate != null; }
    synchronized boolean retired() { return closed && pins == 0; }

    /**
     * A live operation pins its generation. Includes suspended continuations
     * and asynchronous callbacks once integrated into the actor scheduler.
     */
    synchronized Lease pin() {
        if (closed || candidate != null)
            throw new IllegalStateException("actor is closed or upgrade-frozen");
        if (pins == Integer.MAX_VALUE)
            throw new IllegalStateException("actor pin limit exhausted");
        // Allocate the lease BEFORE charging a pin. On OOME (or any
        // allocation failure), the supervisor must not strand reclamation.
        Lease lease = new Lease(this, epoch);
        ++pins;
        return lease;
    }

    synchronized boolean permits(Lease lease) {
        return lease != null && lease.owner == this && !lease.released.get()
                && lease.epoch == epoch && !closed;
    }

    /**
     * Freeze new admission. v0 requires byte-exact schema, wire ABI and
     * capabilities: unsupported state migration MUST fail closed.
     */
    synchronized Candidate prepare(Image proposed) {
        if (closed || candidate != null)
            throw new IllegalStateException("actor already closed or preparing upgrade");
        Objects.requireNonNull(proposed, "proposed");
        if (!active.sameUpgradeBoundary(proposed))
            throw new SecurityException("actor upgrade changes state, message ABI or capabilities");
        if (active.codeSha256().equals(proposed.codeSha256()))
            throw new IllegalArgumentException("identical code image is not an upgrade");
        candidate = new Candidate(this, epoch, proposed);
        return candidate;
    }

    /** Commit only with no outstanding execution, mailbox, or FFI pins. */
    synchronized boolean publish(Candidate prepared) {
        checkCandidate(prepared);
        if (pins != 0) return false;
        if (epoch == Long.MAX_VALUE)
            throw new IllegalStateException("actor upgrade epoch exhausted");
        active = prepared.proposed;
        ++epoch;
        candidate = null;
        return true;
    }

    synchronized void abort(Candidate prepared) {
        checkCandidate(prepared);
        candidate = null; // preserve old code and identity
    }

    private void checkCandidate(Candidate prepared) {
        if (closed || prepared == null || prepared.owner != this
                || prepared != candidate || prepared.originEpoch != epoch)
            throw new IllegalStateException("stale, foreign or retired upgrade candidate");
    }

    private synchronized void release(Lease lease) {
        if (lease.released.get()) return;
        if (pins <= 0) throw new IllegalStateException("actor pin accounting underflow");
        --pins;
        // permits(), publish() and livePins() cannot observe a retired pin
        // while its lease still authorizes work.
        lease.released.set(true);
    }

    @Override public synchronized void close() {
        closed = true;
        candidate = null;
        // Existing leases survive and are released by their owners.
        // This gate does not reclaim the actor heap or native code image.
    }
}
