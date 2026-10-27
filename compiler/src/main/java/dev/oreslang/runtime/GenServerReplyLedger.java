package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/**
 * Host-only bounded reply ledger. No per-call UUID, random source, hashing, or
 * guest-observable handle. Do not serialize Ticket or treat a slot as authority.
 *
 * This is not actor mailbox integration, network correlation, CONTROL, or a
 * complete OresFuture implementation. No callbacks execute under the gate.
 */
final class GenServerReplyLedger {
    record Target(UUID runtimeId, UUID actorId, long incarnation) {
        Target {
            Objects.requireNonNull(runtimeId, "runtimeId");
            Objects.requireNonNull(actorId, "actorId");
            if (incarnation <= 0) throw new IllegalArgumentException("incarnation must be positive");
        }
    }

    static final class Ticket {
        private final GenServerReplyLedger ledger;
        private final Target target;
        private final int slotIndex;
        private final long slotGeneration;

        private Ticket(GenServerReplyLedger ledger, Target target,
                       int slotIndex, long slotGeneration) {
            this.ledger = ledger;
            this.target = target;
            this.slotIndex = slotIndex;
            this.slotGeneration = slotGeneration;
        }

        // Diagnostics for runtime-package tests; NOT remotely sendable IDs.
        int slotIndex() { return slotIndex; }
        long slotGeneration() { return slotGeneration; }
        Target target() { return target; }
        boolean matchesTarget(Target candidate) { return target.equals(candidate); }
    }

    private static final long MAX_SLOT_GENERATION = 0xffff_ffffL;

    private static final class Slot {
        private long generation = 1;
        private Ticket current;
    }

    private final int capacity;
    private final Object gate = new Object();
    // Lazy slot expansion, bounded by capacity. Access always guarded by gate.
    private final ArrayList<Slot> slots = new ArrayList<>();
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private boolean closed;
    private int live;

    GenServerReplyLedger(int capacity) {
        if (capacity < 1 || capacity > 1_000_000)
            throw new IllegalArgumentException("capacity must be 1..1,000,000");
        this.capacity = capacity;
    }

    Ticket register(Target target) {
        Objects.requireNonNull(target, "target");
        synchronized (gate) {
            if (closed) throw new IllegalStateException("ledger is closed");
            if (live >= capacity) throw new IllegalStateException("bounded call quota exceeded");
            final int index;
            final Slot slot;
            if (!free.isEmpty()) {
                index = free.removeLast();
                slot = slots.get(index);
            } else {
                if (slots.size() == capacity)
                    throw new IllegalStateException("all reply slots permanently retired");
                index = slots.size();
                slot = new Slot();
                slots.add(slot);
            }
            // Ticket construction can fail (e.g. OOM); do not strand a credit.
            try {
                Ticket ticket = new Ticket(this, target, index, slot.generation);
                if (slot.current != null)
                    throw new IllegalStateException("allocated occupied slot");
                slot.current = ticket;
                live++;
                return ticket;
            } catch (RuntimeException | Error failure) {
                // A fresh never-used slot is still eligible for reuse.
                free.addLast(index);
                throw failure;
            }
        }
    }

    /** True only once for the exact live Ticket object and target incarnation. */
    boolean tryReply(Ticket ticket, Target respondingActor) {
        Objects.requireNonNull(respondingActor, "respondingActor");
        if (ticket == null || ticket.ledger != this || !ticket.target.equals(respondingActor))
            return false;
        synchronized (gate) {
            return claim(ticket);
        }
    }

    /** Timeout/cancel retire only the original ticket; accepted work continues. */
    boolean retire(Ticket ticket) {
        if (ticket == null || ticket.ledger != this) return false;
        synchronized (gate) {
            return claim(ticket);
        }
    }

    private boolean claim(Ticket ticket) {
        if (closed || ticket.slotIndex >= slots.size() || ticket.slotIndex < 0)
            return false;
        Slot slot = slots.get(ticket.slotIndex);
        if (slot.current != ticket || slot.generation != ticket.slotGeneration)
            return false;
        slot.current = null;
        live--;
        // Never wrap generation: permanently retire an exhausted slot.
        if (slot.generation < MAX_SLOT_GENERATION) {
            slot.generation++;
            free.addLast(ticket.slotIndex);
        }
        return true;
    }

    int pendingCount() {
        synchronized (gate) { return live; }
    }

    /** Close fences new calls, drops all retained tickets and clears memory. */
    int close() {
        synchronized (gate) {
            if (closed) return 0;
            closed = true;
            int discarded = live;
            live = 0;
            slots.clear();
            free.clear();
            return discarded;
        }
    }
}
