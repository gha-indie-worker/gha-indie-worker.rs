package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime-private cancellation-request authority; NOT a termination barrier.
 * The owning scheduler must separately confirm physical task quiescence.
 * Ownership changes and cancellation decisions linearize under GRAPH_LOCK.
 * Callbacks never run under GRAPH_LOCK and must be trusted host-only code.
 */
final class TaskCancellation {
    private static final Object GRAPH_LOCK = new Object();
    private static final String DEFAULT_REASON = "cancelled";
    private static final int MAX_REASON_LENGTH = 256;
    // A linked chain may contain thousands of cancellation edges. Drain events
    // iteratively rather than recursively calling another cancellation hook.
    private static final ThreadLocal<ArrayDeque<Runnable>> DISPATCH = new ThreadLocal<>();

    private final Set<TaskCancellation> children =
            Collections.newSetFromMap(new IdentityHashMap<>());
    // Separate physical-lifetime edges from revocation edges. Cancellation
    // detaches revocation edges immediately, but a child still occupies a
    // parent join slot until its own scheduler-confirmed retirement.
    private final Set<TaskCancellation> lifetimeChildren =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Registration> revokers = new LinkedHashSet<>();
    private final Set<Registration> observers = new LinkedHashSet<>();
    // Links are registered on a separate source authority. Their target
    // owns the subscription lifetime so source observers cannot retain a
    // physically completed downstream task indefinitely.
    private final Set<Subscription> upstreamLinks = new LinkedHashSet<>();
    private TaskCancellation owner;
    private TaskCancellation lifetimeParent;
    // Set once the owning Task reaches terminal/quiescent publication.
    // No new child or adoption may race a completed parent join barrier.
    private boolean childAdmissionSealed;
    private Runnable childrenDrained;
    // Protected by GRAPH_LOCK: completed tokens must not regain authority.
    private boolean retired;
    private volatile boolean cancelled;
    private volatile String cancellationReason;
    // Protected by GRAPH_LOCK: observers registered after the cancel decision
    // wait until all revokers admitted before that decision have finished.
    private boolean revocationComplete;

    TaskCancellation() { }

    TaskCancellation child() {
        synchronized (GRAPH_LOCK) {
            if (retired || childAdmissionSealed) {
                throw new IllegalStateException("cannot spawn from sealed task authority");
            }
            TaskCancellation child = new TaskCancellation();
            child.lifetimeParent = this;
            lifetimeChildren.add(child);
            if (cancelled) {
                child.cancellationReason = cancellationReason;
                child.cancelled = true;
                child.revocationComplete = true;
            } else {
                child.owner = this;
                children.add(child);
            }
            return child;
        }
    }

    boolean isCancelled() {
        return cancelled;
    }

    /**
     * Retire a physically completed task from its lifetime owner. This is
     * distinct from cancellation: it must only be called after scheduler
     * quiescence AND terminal-result publication. Without retirement, a
     * long-lived scope retains every successfully completed child token.
     *
     * A concurrent parent cancellation either observes this child while it
     * is still live or observes a completed child already unlinked from the
     * tree. The graph lock supplies the linearization point.
     */
    void retire() {
        List<Registration> detached = new ArrayList<>();
        List<Subscription> detachedLinks = new ArrayList<>();
        Runnable notifyLifetimeParent;
        synchronized (GRAPH_LOCK) {
            if (retired) return;
            // Retiring a parent with live children would orphan structured
            // cancellation authority: their next owner could no longer
            // request revocation through this parent.
            if (!lifetimeChildren.isEmpty()) {
                throw new IllegalStateException("cannot retire authority with live children");
            }
            retired = true;
            detachedLinks.addAll(upstreamLinks);
            upstreamLinks.clear();
            if (owner != null) owner.children.remove(this);
            owner = null;
            notifyLifetimeParent = detachLifetimeParentLocked();
            detached.addAll(revokers);
            revokers.clear();
            // Cancellation may have marked this token while a synchronous
            // revoker is still executing. Its pre-admitted observers are
            // entitled to exactly one notification once revocation finishes,
            // even if physical task quiescence races ahead of that callback.
            // Never discard them here: the cancellation dispatch drains them
            // after publishing revocationComplete under GRAPH_LOCK.
            if (!cancelled || revocationComplete) {
                detached.addAll(observers);
                observers.clear();
            }
        }
        // Release retained callback captures even when the caller keeps a
        // Subscription reference after task completion. Do not invoke hooks.
        // The graph lock cannot be held while detaching registrations.
        for (Registration registration : detached) registration.close();
        // Close subscriptions outside GRAPH_LOCK: Registration.close()
        // synchronizes on GRAPH_LOCK to unlink from the source's observers.
        for (Subscription link : detachedLinks) link.close();
        if (notifyLifetimeParent != null) notifyLifetimeParent.run();
    }

    /**
     * Close child admission and subscribe to the physical child-lifetime
     * barrier. Caller invokes onDrained itself if true is returned. If not,
     * onDrained is invoked once outside GRAPH_LOCK when the final child
     * retires or transfers to another live owner. Never call guest code here.
     */
    boolean sealAndObserveChildQuiescence(Runnable onDrained) {
        Objects.requireNonNull(onDrained, "onDrained");
        synchronized (GRAPH_LOCK) {
            if (retired) return true;
            if (childAdmissionSealed) {
                throw new IllegalStateException("child completion observer already registered");
            }
            childAdmissionSealed = true;
            if (lifetimeChildren.isEmpty()) return true;
            childrenDrained = onDrained;
            return false;
        }
    }

    private Runnable detachLifetimeParentLocked() {
        TaskCancellation previous = lifetimeParent;
        lifetimeParent = null;
        if (previous == null) return null;
        previous.lifetimeChildren.remove(this);
        if (!previous.lifetimeChildren.isEmpty()) return null;
        Runnable ready = previous.childrenDrained;
        previous.childrenDrained = null;
        return ready;
    }

    /** Runtime diagnostic for bounded lifetime-tree retention tests. */
    int ownedChildCount() {
        synchronized (GRAPH_LOCK) {
            return children.size();
        }
    }

    /** Null until cancellation; writing reason precedes volatile cancelled. */
    String cancellationReason() {
        return cancellationReason;
    }

    boolean isOwnedBy(TaskCancellation expectedOwner) {
        synchronized (GRAPH_LOCK) {
            return owner == expectedOwner;
        }
    }

    /** Atomic reparent, never a detach. Caller must separately prove authority. */
    void adoptInto(TaskCancellation newOwner) {
        Objects.requireNonNull(newOwner, "newOwner");
        Runnable notifyPreviousOwner = null;
        synchronized (GRAPH_LOCK) {
            if (retired) throw new IllegalStateException("cannot transfer retired task");
            if (newOwner.retired || newOwner.childAdmissionSealed) {
                throw new IllegalStateException("cannot adopt into sealed task owner");
            }
            if (cancelled) throw new IllegalStateException("cannot transfer cancelled task");
            if (newOwner.cancelled) throw new IllegalStateException("cannot adopt into cancelled owner");
            if (owner == null) throw new IllegalStateException("cannot transfer a root authority");
            for (TaskCancellation cursor = newOwner; cursor != null; cursor = cursor.owner) {
                if (cursor == this) throw new IllegalArgumentException("ownership cycle");
            }
            if (owner == newOwner) return;
            owner.children.remove(this);
            newOwner.children.add(this);
            owner = newOwner;
            notifyPreviousOwner = detachLifetimeParentLocked();
            lifetimeParent = newOwner;
            newOwner.lifetimeChildren.add(this);
        }
        if (notifyPreviousOwner != null) notifyPreviousOwner.run();
    }

    /**
     * Marks the entire live subtree before delivering any callback. A second
     * request never changes its first reason. This is not a join/cleanup proof.
     */
    boolean cancel(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason must contain 1..256 nonblank characters");
        }
        List<TaskCancellation> marked = new ArrayList<>();
        List<Registration> revoke = new ArrayList<>();
        synchronized (GRAPH_LOCK) {
            if (retired || cancelled) return false;
            ArrayDeque<TaskCancellation> queue = new ArrayDeque<>();
            queue.addLast(this);
            while (!queue.isEmpty()) {
                TaskCancellation node = queue.removeFirst();
                if (node.cancelled) continue;
                node.cancellationReason = reason;
                node.cancelled = true;
                queue.addAll(node.children);
                node.children.clear();
                if (node.owner != null) node.owner.children.remove(node);
                node.owner = null; // terminal token cannot retain old owner tree
                revoke.addAll(node.revokers);
                node.revokers.clear();
                marked.add(node);
            }
        }
        dispatch(() -> {
            // Marked before any callback; observer delivery is buffered until
            // this entire admission batch has completed revocation callbacks.
            Error firstFatal = null;
            for (Registration hook : revoke) {
                try {
                    hook.deliver();
                } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
                    // This subtree is already marked cancelled. A failed
                    // trusted revoker must not abandon sibling revocations.
                    if (firstFatal == null) firstFatal = fatal;
                }
            }
            List<Registration> notify = new ArrayList<>();
            synchronized (GRAPH_LOCK) {
                for (TaskCancellation node : marked) {
                    node.revocationComplete = true;
                    notify.addAll(node.observers);
                    node.observers.clear();
                }
            }
            for (Registration hook : notify) {
                try {
                    hook.deliver();
                } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
                    if (firstFatal == null) firstFatal = fatal;
                }
            }
            // Propagate fatal host errors, but never strand late observers
            // behind an incomplete revocation barrier.
            if (firstFatal != null) throw firstFatal;
        });
        return true;
    }

    boolean cancel() {
        return cancel(DEFAULT_REASON);
    }

    Subscription onRevoke(Runnable hook) {
        return register(hook, true);
    }

    Subscription onCancel(Runnable hook) {
        return register(hook, false);
    }

    /** A link observes cancellation; it never grants ownership. */
    Subscription linkCancellationFrom(TaskCancellation source) {
        Objects.requireNonNull(source, "source");
        if (source == this) throw new IllegalArgumentException("self cancellation link");
        Subscription link = source.onCancel(() -> {
            String from = Objects.requireNonNullElse(
                    source.cancellationReason(), DEFAULT_REASON);
            String linked = from.startsWith("linked:") ? from : "linked:" + from;
            if (linked.length() > MAX_REASON_LENGTH) {
                linked = linked.substring(0, MAX_REASON_LENGTH);
            }
            cancel(linked);
        });
        boolean discard;
        synchronized (GRAPH_LOCK) {
            // Registration can race task completion. A link installed before
            // retirement is owned by this target; one installed afterward
            // is immediately closed instead of pinning this target on source.
            discard = retired;
            if (!discard) upstreamLinks.add(link);
        }
        if (discard) link.close();
        return link;
    }

    private Subscription register(Runnable hook, boolean revoke) {
        Objects.requireNonNull(hook, "hook");
        Registration registration = new Registration(this, hook, revoke);
        boolean deliver;
        boolean discard;
        synchronized (GRAPH_LOCK) {
            // A completed task cannot accept new listeners that keep its
            // captured actor/guest state alive indefinitely.
            discard = retired;
            deliver = !discard && cancelled && (revoke || revocationComplete);
            if (!discard && !deliver) {
                (revoke ? revokers : observers).add(registration);
            }
        }
        if (discard) registration.close();
        else if (deliver) dispatch(registration::deliver);
        return registration;
    }

    private static void dispatch(Runnable action) {
        ArrayDeque<Runnable> current = DISPATCH.get();
        if (current != null) {
            current.addLast(action);
            return;
        }
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        DISPATCH.set(queue);
        try {
            queue.addLast(action);
            Error firstFatal = null;
            while (!queue.isEmpty()) {
                try {
                    queue.removeFirst().run();
                } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
                    // Nested cancellation is queued on this thread. Do not
                    // discard already-admitted descendant cancellation batches
                    // merely because a previous trusted hook failed.
                    if (firstFatal == null) firstFatal = fatal;
                }
            }
            if (firstFatal != null) throw firstFatal;
        } finally {
            DISPATCH.remove();
        }
    }

    interface Subscription extends AutoCloseable {
        @Override void close();
    }

    private static final class Registration implements Subscription {
        private TaskCancellation owner;
        private Runnable hook;
        private final boolean revoke;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(TaskCancellation owner, Runnable hook, boolean revoke) {
            this.owner = owner;
            this.hook = hook;
            this.revoke = revoke;
        }

        private void deliver() {
            if (!active.compareAndSet(true, false)) return;
            Runnable call = hook;
            owner = null;
            hook = null; // a retained Subscription cannot pin the source graph
            try {
                call.run();
            } catch (RuntimeException | Error failure) {
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                if (failure instanceof LinkageError fatal) throw fatal;
                // Other trusted host-hook errors cannot roll cancellation back
                // or prevent delivery of the remaining revokers/observers.
            }
        }

        @Override public void close() {
            if (!active.compareAndSet(true, false)) return;
            TaskCancellation previousOwner = owner;
            owner = null;
            hook = null;
            synchronized (GRAPH_LOCK) {
                if (previousOwner != null) {
                    (revoke ? previousOwner.revokers : previousOwner.observers).remove(this);
                }
            }
        }
    }
}
