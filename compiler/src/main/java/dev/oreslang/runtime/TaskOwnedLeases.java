package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trusted HOST-only task-owned transferable lease cleanup.
 *
 * <p>This is not a generic Java monitor/ReentrantLock unlock mechanism:
 * thread-affine locks MUST be unwound on their owning carrier by compiler
 * emitted try/finally, with suspension forbidden while those guards live.
 * Only nonblocking, task-identity-owned lease tokens whose release is
 * explicitly valid on ANY host notification thread may be registered.
 * Never run untrusted guest callbacks from a release implementation.</p>
 *
 * <p>The runtime retires any remaining registered leases in reverse
 * acquisition order after the parent Task and owned descendants are
 * physically quiescent, before publishing TaskHandle.getDone().</p>
 */
public final class TaskOwnedLeases {
    public static final int MAX_LEASES_PER_TASK = 4_096;

    private TaskOwnedLeases() { }

    /** Verified nonblocking, thread-independent token-release capability. */
    @FunctionalInterface
    public interface TransferableRelease {
        void release();
    }

    /** Exact-once handle. An explicit early close requires its owning Task turn. */
    public interface Lease extends AutoCloseable {
        /** Enforce owning logical Task identity for any guest-visible access. */
        void requireCurrentOwner();
        /**
         * Trusted runtime only: cancel a reservation that never delivered
         * a lock/guard. No release callback is run, and the operation is
         * safe from a foreign Future-notification carrier.
         */
        void disarmWithoutRelease();
        @Override void close();
    }

    /**
     * Attach an actual mutex acquisition to the current tracked Task, if any.
     * The ordinary release and abandoned-release paths are intentionally
     * distinct: only abandonment may poison protected mutable state.
     * A mismatched or exhausted tracked Task MUST fail closed.
     */
    static Lease registerIfTracked(
            TransferableRelease ordinaryRelease,
            TransferableRelease abandonedRelease) {
        Objects.requireNonNull(ordinaryRelease, "ordinaryRelease");
        Objects.requireNonNull(abandonedRelease, "abandonedRelease");
        if (!TaskHandle.isCurrentTrackedTaskTurn()) return null;
        return TaskHandle.registerTransferableLease(ordinaryRelease, abandonedRelease);
    }

    /**
     * Register a logical lease with the CURRENT tracked Task. Compiler/native
     * lock lowering may call this only after checking actual token ownership
     * and cross-thread release safety. Do NOT pass java.util.concurrent.Lock::unlock.
     */
    // Package-private: only trusted runtime lowering/adapters may register.
    // Never surface a generic host callback registration to Oreslang guest code.
    static Lease register(TransferableRelease release) {
        return TaskHandle.registerTransferableLease(Objects.requireNonNull(release, "release"));
    }

    /**
     * Immutable snapshot of failed releases. A failed release invalidates
     * memory reclamation even if the Task's value Future settled successfully.
     */
    public static final class CleanupFailure extends IllegalStateException {
        private final List<Throwable> failures;
        private final TaskHandle.TaskOutcome<?> originalOutcome;

        CleanupFailure(List<Throwable> failures) {
            this(failures, null);
        }

        CleanupFailure(
                List<Throwable> failures, TaskHandle.TaskOutcome<?> originalOutcome) {
            super("task-owned lease cleanup failed (" + failures.size() + " leases)",
                    originalOutcome instanceof TaskHandle.Failed<?> failed
                            ? failed.failure() : null);
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("cleanup failure requires failures");
            }
            this.failures = List.copyOf(failures);
            this.originalOutcome = originalOutcome;
        }

        public List<Throwable> failures() {
            return failures;
        }

        /** Preserves the exact primary source outcome; null at scope level. */
        public TaskHandle.TaskOutcome<?> originalOutcome() {
            return originalOutcome;
        }
    }

    static final class Registry {
        private final Object lock = new Object();
        // Insertion-ordered live leases permit O(1) explicit retirement, so
        // sequential acquisitions cannot exhaust the live-leases quota.
        private final LinkedHashSet<Entry> active = new LinkedHashSet<>();
        // In-flight grants are tracked before async waiter publication. Their
        // physical settlement is separate from Future/result settlement.
        private final LinkedHashSet<CompletableFuture<Void>> physicalSettlements =
                new LinkedHashSet<>();
        private static final int MAX_EARLY_FAILURES = 64;
        // Bounded: arbitrary repeated early failures must not grow Task memory.
        private final ArrayList<Throwable> earlyFailures = new ArrayList<>();
        private long suppressedEarlyFailures;
        private boolean retired;
        private CompletableFuture<List<Throwable>> retirementResult;

        Lease register(TransferableRelease token) {
            return register(token, token);
        }

        Lease register(TransferableRelease ordinaryRelease,
                       TransferableRelease abandonedRelease) {
            synchronized (lock) {
                if (retired) throw new IllegalStateException(
                        "task lease registration after retirement");
                if (active.size() == MAX_LEASES_PER_TASK) throw new IllegalStateException(
                        "task lease quota exceeded");
                Entry entry = new Entry(this, ordinaryRelease, abandonedRelease);
                active.add(entry);
                return entry;
            }
        }

        void trackPhysicalSettlement(CompletableFuture<Void> receipt) {
            Objects.requireNonNull(receipt, "receipt");
            synchronized (lock) {
                if (retired) throw new IllegalStateException(
                        "task physical settlement registered after retirement");
                if (physicalSettlements.size() == MAX_LEASES_PER_TASK) {
                    throw new IllegalStateException("task in-flight settlement quota exceeded");
                }
                if (!physicalSettlements.add(receipt)) {
                    throw new IllegalStateException("duplicate task physical settlement");
                }
            }
            // Physical receipts do not retain a completed Task indefinitely.
            // This callback invokes no guest code or user Future subscriber.
            receipt.whenComplete((ignored, failure) -> {
                synchronized (lock) {
                    physicalSettlements.remove(receipt);
                }
            });
        }

        private void forget(Entry entry) {
            synchronized (lock) {
                active.remove(entry);
            }
        }

        private void recordEarlyFailure(Throwable failure) {
            synchronized (lock) {
                if (earlyFailures.size() < MAX_EARLY_FAILURES) {
                    earlyFailures.add(failure);
                } else if (suppressedEarlyFailures != Long.MAX_VALUE) {
                    suppressedEarlyFailures++;
                }
            }
        }

        // Synchronous host test utility. Runtime TaskHandle must use the
        // nonblocking variant to avoid parking a Task's scheduler carrier.
        List<Throwable> releaseOnRetirement() {
            return releaseOnRetirementAsync().join();
        }

        CompletableFuture<List<Throwable>> releaseOnRetirementAsync() {
            final ArrayList<Entry> reversed;
            final ArrayList<Throwable> errors;
            final ArrayList<CompletableFuture<Void>> pending;
            final CompletableFuture<List<Throwable>> receipt;
            synchronized (lock) {
                // Repeated observation joins the original retirement, rather
                // than fabricating an empty successful cleanup while pending.
                if (retired) return retirementResult;
                retired = true;
                receipt = new CompletableFuture<>();
                retirementResult = receipt;
                reversed = new ArrayList<>(active);
                Collections.reverse(reversed);
                active.clear();
                // Atomic snapshot with the retirement admission fence: an
                // async waiter must register its receipt BEFORE publication.
                pending = new ArrayList<>(physicalSettlements);
                physicalSettlements.clear();
                errors = new ArrayList<>(earlyFailures);
                if (suppressedEarlyFailures != 0L) {
                    errors.add(new IllegalStateException(
                            suppressedEarlyFailures + " additional early lease release failures"));
                }
                earlyFailures.clear();
                suppressedEarlyFailures = 0L;
            }
            // Revoke leases only after the scheduler and children quiesce.
            // Never run host releases or await claimed handoffs under lock.
            for (Entry entry : reversed) {
                try {
                    entry.releaseOnce(true);
                } catch (RuntimeException | Error failed) {
                    errors.add(failed);
                }
            }
            if (pending.isEmpty()) {
                receipt.complete(List.copyOf(errors));
                return receipt;
            }
            CompletableFuture<?>[] all = pending.toArray(new CompletableFuture<?>[0]);
            CompletableFuture.allOf(all).whenComplete((ignored, failure) -> {
                for (CompletableFuture<Void> pendingReceipt : pending) {
                    try {
                        pendingReceipt.join(); // allOf has already joined every receipt
                    } catch (CompletionException failed) {
                        errors.add(failed.getCause() == null ? failed : failed.getCause());
                    } catch (java.util.concurrent.CancellationException cancelled) {
                        errors.add(cancelled);
                    }
                }
                receipt.complete(List.copyOf(errors));
            });
            return receipt;
        }

        private static final class Entry implements Lease {
            private final Registry owner;
            private final TransferableRelease ordinaryRelease;
            private final TransferableRelease abandonedRelease;
            private final AtomicBoolean released = new AtomicBoolean();

            Entry(Registry owner, TransferableRelease ordinaryRelease,
                  TransferableRelease abandonedRelease) {
                this.owner = owner;
                this.ordinaryRelease = ordinaryRelease;
                this.abandonedRelease = abandonedRelease;
            }

            private void releaseOnce(boolean abandoned) {
                if (released.compareAndSet(false, true)) {
                    (abandoned ? abandonedRelease : ordinaryRelease).release();
                }
            }

            @Override public void requireCurrentOwner() {
                if (!TaskHandle.isCurrentLeaseOwner(owner)) {
                    throw new IllegalStateException(
                            "mutex guard belongs to a different logical Task");
                }
            }

            @Override public void disarmWithoutRelease() {
                // The caller must first prove no grant was delivered, or that
                // a failed-publication grant has ALREADY returned its permit.
                // Never invoke the ordinary/abandoned release callback here.
                released.compareAndSet(false, true);
                owner.forget(this);
            }

            @Override public void close() {
                requireCurrentOwner();
                // A logical lease can be released early inside its owning
                // Task; retirement's stack entry is safely idempotent.
                try {
                    releaseOnce(false);
                } catch (RuntimeException | Error failed) {
                    owner.recordEarlyFailure(failed);
                    throw failed;
                } finally {
                    // Unlink after an explicit close regardless of success.
                    // The failure remains in cleanupFailures and prevents
                    // falsely claiming resource reclamation.
                    owner.forget(this);
                }
            }
        }
    }
}
