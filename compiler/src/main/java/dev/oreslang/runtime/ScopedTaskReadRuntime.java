package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.ScopedTaskReadAuthority;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime bridge for the compiler-proven structured immutable Task READ gate.
 *
 * <p>This is deliberately not guest-callable syntax lowering. The compiler
 * supplies an exact {@link ScopedTaskReadAuthority}; this runtime retains a
 * same-generation read claim until the scheduler reports physical quiescence.
 * Future completion or cancellation alone never retires the claim.
 */
public final class ScopedTaskReadRuntime {
    private ScopedTaskReadRuntime() { }

    private static final class AllocationKey {
        private final Ast.Program programIdentity;
        private final String domain;
        private final long allocationId;
        private final long generation;

        private AllocationKey(
                Ast.Program programIdentity,
                String domain,
                long allocationId,
                long generation) {
            this.programIdentity =
                    Objects.requireNonNull(programIdentity, "checked Program identity");
            this.domain = Objects.requireNonNull(domain, "allocation domain");
            this.allocationId = allocationId;
            this.generation = generation;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AllocationKey that
                    && programIdentity == that.programIdentity
                    && domain.equals(that.domain)
                    && allocationId == that.allocationId
                    && generation == that.generation;
        }

        @Override
        public int hashCode() {
            int hash = System.identityHashCode(programIdentity);
            hash = 31 * hash + domain.hashCode();
            hash = 31 * hash + Long.hashCode(allocationId);
            return 31 * hash + Long.hashCode(generation);
        }
    }

    private static final int WRITE_HELD = -1;
    private static final int POISONED = Integer.MIN_VALUE;
    private static final ConcurrentHashMap<AllocationKey, Integer> ACCESS =
            new ConcurrentHashMap<>();

    public static <T> OresFuture<T> start(
            OresScheduler scheduler,
            OresScheduler.Task<T> task,
            ScopedTaskReadAuthority authority,
            Ast.Program program,
            Ast.TaskExpr taskBoundary,
            long ownershipEpoch,
            long allocationId,
            long generation) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(authority, "scoped READ authority");
        Objects.requireNonNull(program, "checked Program");
        Objects.requireNonNull(taskBoundary, "checked Task boundary");

        ReadLease lease = acquireRead(
                authority,
                program,
                taskBoundary,
                ownershipEpoch,
                allocationId,
                generation);
        try {
            // The scheduler callback is the physical lifetime edge. A Future
            // may settle/cancel earlier; the read claim intentionally survives.
            return scheduler.start(task, lease::close);
        } catch (RuntimeException | Error failure) {
            // start() can throw only before a tracked Task is successfully
            // admitted. If publication happened, the scheduler owns callback
            // retirement and start() returns the failed/cancelled Future.
            lease.close();
            throw failure;
        }
    }

    /**
     * Mint one exact compiler-proven READ lease without scheduling work.
     *
     * <p>The caller must bind {@link ReadLease#close()} to a physical child
     * quiescence edge. Closing on Future settlement is insufficient. This
     * split form is used by structured {@link TaskScope}, whose TaskHandle
     * already owns the exact physical child lifetime.</p>
     */
    public static ReadLease acquireRead(
            ScopedTaskReadAuthority authority,
            Ast.Program program,
            Ast.TaskExpr taskBoundary,
            long ownershipEpoch,
            long allocationId,
            long generation) {
        Objects.requireNonNull(authority, "scoped READ authority");
        Objects.requireNonNull(program, "checked Program");
        Objects.requireNonNull(taskBoundary, "checked Task boundary");

        authority.require(
                program,
                taskBoundary,
                ownershipEpoch,
                allocationId,
                generation);

        ScopedTaskReadAuthority.AllocationIdentity allocation =
                authority.allocation();
        AllocationKey key = new AllocationKey(
                program,
                allocation.domain(),
                allocationId,
                generation);
        register(key);
        return new ReadLease(key);
    }

    /**
     * Bind an already-proven READ lease to one exact structured child.
     *
     * <p>The TaskScope callback is a physical-quiescence edge derived from the
     * exact TaskHandle. If child admission fails before publication, this
     * method retires the lease on that proven pre-admission path.</p>
     */
    public static <T> TaskHandle<T> startInScope(
            TaskScope scope,
            OresScheduler.Task<T> task,
            ReadLease lease) {
        Objects.requireNonNull(scope, "structured Task scope");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(lease, "scoped READ lease");
        try {
            return scope.startTask(task, lease::close);
        } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
        }
    }

    /**
     * Atomically reserve parent/runtime WRITE authority for the exact
     * allocation generation. The returned lease MUST bracket the physical
     * write. A check-only API is insufficient because a reader could register
     * immediately after the check and race the write.
     */
    public static WriteLease acquireWrite(
            Ast.Program program,
            ScopedTaskReadAuthority.AllocationIdentity allocation) {
        Objects.requireNonNull(program, "checked Program");
        Objects.requireNonNull(allocation, "allocation");
        AllocationKey key = key(program, allocation);
        ACCESS.compute(key, (ignored, current) -> {
            if (current != null) {
                if (current == WRITE_HELD) {
                    throw new IllegalStateException(
                            "allocation already has live WRITE authority");
                }
                if (current == POISONED) {
                    throw new IllegalStateException(
                            "allocation Task access authority is poisoned");
                }
                if (current > 0) {
                    throw new IllegalStateException(
                            "allocation has live scoped Task READ authority");
                }
                throw new IllegalStateException(
                        "scoped Task access ledger is corrupt");
            }
            return WRITE_HELD;
        });
        return new WriteLease(key);
    }

    /**
     * Check-only diagnostic helper. Callers that will actually mutate storage
     * must use {@link #acquireWrite(ScopedTaskReadAuthority.AllocationIdentity)}
     * so reader admission and WRITE acquisition are linearized.
     */
    public static void requireWritable(
            Ast.Program program,
            ScopedTaskReadAuthority.AllocationIdentity allocation) {
        Objects.requireNonNull(program, "checked Program");
        Objects.requireNonNull(allocation, "allocation");
        Integer state = ACCESS.get(key(program, allocation));
        if (state == null) return;
        if (state == WRITE_HELD) {
            throw new IllegalStateException(
                    "allocation already has live WRITE authority");
        }
        if (state == POISONED) {
            throw new IllegalStateException(
                    "allocation Task access authority is poisoned");
        }
        if (state > 0) {
            throw new IllegalStateException(
                    "allocation has live scoped Task READ authority");
        }
        throw new IllegalStateException(
                "scoped Task access ledger is corrupt");
    }

    static int activeReadersForTesting(
            Ast.Program program,
            ScopedTaskReadAuthority.AllocationIdentity allocation) {
        Integer state = ACCESS.get(key(program, allocation));
        return state != null && state > 0 ? state : 0;
    }

    static boolean writeHeldForTesting(
            Ast.Program program,
            ScopedTaskReadAuthority.AllocationIdentity allocation) {
        return Integer.valueOf(WRITE_HELD).equals(ACCESS.get(key(program, allocation)));
    }


    private static AllocationKey key(
            Ast.Program program,
            ScopedTaskReadAuthority.AllocationIdentity allocation) {
        return new AllocationKey(
                program,
                allocation.domain(),
                allocation.allocationId(),
                allocation.generation());
    }

    private static ReadLease register(AllocationKey key) {
        Integer readers = ACCESS.compute(key, (ignored, current) -> {
            if (current != null && current == WRITE_HELD) {
                throw new IllegalStateException(
                        "cannot admit scoped Task READ while WRITE authority is live");
            }
            if (current != null && current == POISONED) {
                throw new IllegalStateException(
                        "cannot admit scoped Task READ on poisoned allocation authority");
            }
            int count = current == null ? 0 : current;
            if (count < 0) {
                throw new IllegalStateException(
                        "scoped Task access ledger is corrupt");
            }
            if (count == Integer.MAX_VALUE) {
                throw new IllegalStateException(
                        "scoped Task READ reader count overflow");
            }
            return count + 1;
        });
        if (readers == null || readers <= 0) {
            throw new IllegalStateException(
                    "scoped Task READ registration failed");
        }
        return new ReadLease(key);
    }

    public static final class ReadLease implements AutoCloseable {
        private final AllocationKey key;
        private final AtomicBoolean retired = new AtomicBoolean();

        private ReadLease(AllocationKey key) {
            this.key = key;
        }

        @Override
        public void close() {
            retire("physical/pre-admission close");
        }

        private void retire(String edge) {
            if (!retired.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "scoped Task READ lease retired more than once at " + edge);
            }
            Integer after = ACCESS.compute(key, (ignored, counter) -> {
                if (counter == null || counter <= 0) {
                    return POISONED;
                }
                int remaining = counter - 1;
                return remaining == 0 ? null : remaining;
            });
            if (Integer.valueOf(POISONED).equals(after)) {
                throw new IllegalStateException(
                        "scoped Task READ ledger entry missing/corrupt at " + edge);
            }
        }
    }

    public static final class WriteLease implements AutoCloseable {
        private final AllocationKey key;
        private final AtomicBoolean released = new AtomicBoolean();

        private WriteLease(AllocationKey key) {
            this.key = key;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "scoped Task WRITE lease released more than once");
            }
            Integer after = ACCESS.compute(key, (ignored, state) -> {
                if (state == null || state != WRITE_HELD) {
                    return POISONED;
                }
                return null;
            });
            if (Integer.valueOf(POISONED).equals(after)) {
                throw new IllegalStateException(
                        "scoped Task WRITE ledger entry missing/corrupt at release");
            }
        }
    }

}
