package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Stable logical execution identity for one Oreslang task.
 *
 * <p>An execution context is not a Java thread, scheduler dispatch, actor heap,
 * or allocation arena. It survives await/cooperate carrier migration while the
 * physical dispatch id changes. Allocation ownership is represented separately
 * so many executions may legitimately share one actor allocation domain.</p>
 *
 * <p>The value is intentionally pointer-free at the semantic boundary. Backend
 * runtimes may store large populations in AoS, SoA, or AoSoA layouts without
 * changing these identities.</p>
 */
public final class ExecutionContext {
    public enum AllocationKind {
        NONE,
        ACTOR,
        TASK,
        RUNTIME
    }

    /** Scheduler/runtime domain plus a stable execution number in that domain. */
    public record ExecutionId(long runtimeDomain, long execution) {
        public ExecutionId {
            if (runtimeDomain <= 0 || execution <= 0) {
                throw new IllegalArgumentException(
                        "execution identity components must be positive");
            }
        }
    }

    /**
     * Allocation ownership identity. NONE is valid for tasks that do not own a
     * persistent heap/arena. ACTOR/TASK/RUNTIME domains are generation-fenced.
     */
    public record AllocationDomain(
            AllocationKind kind,
            long domain,
            long owner,
            long generation) {
        public AllocationDomain {
            Objects.requireNonNull(kind, "kind");
            if (kind == AllocationKind.NONE) {
                if (domain != 0 || owner != 0 || generation != 0) {
                    throw new IllegalArgumentException(
                            "unbound allocation domain must contain only zeros");
                }
            } else if (domain <= 0 || owner < 0 || generation <= 0) {
                throw new IllegalArgumentException(
                        "bound allocation domain requires positive domain/generation and nonnegative owner");
            }
        }

        public static AllocationDomain none() {
            return new AllocationDomain(AllocationKind.NONE, 0, 0, 0);
        }

        public boolean isBound() {
            return kind != AllocationKind.NONE;
        }
    }

    private final ExecutionId id;
    private final AllocationDomain allocationDomain;
    private final long parentExecution;
    private final long continuation;

    private ExecutionContext(
            ExecutionId id,
            AllocationDomain allocationDomain,
            long parentExecution,
            long continuation) {
        this.id = Objects.requireNonNull(id, "id");
        this.allocationDomain = Objects.requireNonNull(allocationDomain, "allocationDomain");
        if (parentExecution < 0 || continuation < 0) {
            throw new IllegalArgumentException("logical ancestry handles cannot be negative");
        }
        this.parentExecution = parentExecution;
        this.continuation = continuation;
    }

    static ExecutionContext task(long runtimeDomain, long execution) {
        return new ExecutionContext(
                new ExecutionId(runtimeDomain, execution),
                AllocationDomain.none(),
                0,
                0);
    }

    static ExecutionContext actor(
            long runtimeDomain,
            long execution,
            long actorDomain,
            long actorOwner,
            long actorGeneration,
            long parentExecution,
            long continuation) {
        return new ExecutionContext(
                new ExecutionId(runtimeDomain, execution),
                new AllocationDomain(
                        AllocationKind.ACTOR,
                        actorDomain,
                        actorOwner,
                        actorGeneration),
                parentExecution,
                continuation);
    }

    public ExecutionId id() {
        return id;
    }

    public AllocationDomain allocationDomain() {
        return allocationDomain;
    }

    /** Zero until compiler-lowered causal ancestry is attached. */
    public long parentExecution() {
        return parentExecution;
    }

    /** Zero until compiler-lowered continuation storage is attached. */
    public long continuation() {
        return continuation;
    }

    @Override
    public String toString() {
        return "ExecutionContext[" + id.runtimeDomain() + ":" + id.execution()
                + ", allocation=" + allocationDomain.kind() + "]";
    }
}
