package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compiler-only proof for the narrow structured immutable Task READ exception.
 *
 * <p>This class intentionally does not mint runtime execution/retirement
 * authority. It binds an immutable READ footprint to the exact ownership-
 * checked Program/TaskExpr/epoch while the Task-v2 scheduler bridge is rebuilt.
 * Ordinary Task capture checking still rejects borrowed values; mutable WRITE
 * authority has no representation here.
 */
public final class ScopedTaskReadAuthority {
    private static final int MAX_PROJECTION_DEPTH = 64;

    public record AllocationIdentity(
            String domain,
            String ownerId,
            long allocationId,
            long generation,
            boolean uniqueAllocationProven) {
        public AllocationIdentity {
            Objects.requireNonNull(domain, "allocation domain");
            Objects.requireNonNull(ownerId, "allocation owner");
            if (!domain.equals("PROCESS")) {
                throw new IllegalArgumentException(
                        "scoped Task READ is PROCESS-only until actor/private domain authorization exists");
            }
            if (ownerId.isBlank() || allocationId <= 0 || generation <= 0
                    || !uniqueAllocationProven) {
                throw new IllegalArgumentException(
                        "scoped Task READ requires exact unique allocation identity/generation");
            }
        }
    }

    public sealed interface Projection permits Field, Range { }

    public record Field(String name, boolean inlineLayoutProven)
            implements Projection {
        public Field {
            Objects.requireNonNull(name, "field name");
            if (name.isBlank() || !inlineLayoutProven) {
                throw new IllegalArgumentException(
                        "scoped Task READ field requires trusted inline layout proof");
            }
        }
    }

    public enum RangeUnit { ELEMENTS, BYTES }

    public record Range(
            long start,
            long end,
            RangeUnit unit,
            boolean contiguousLayoutProven)
            implements Projection {
        public Range {
            Objects.requireNonNull(unit, "range unit");
            if (start < 0 || end <= start || !contiguousLayoutProven) {
                throw new IllegalArgumentException(
                        "scoped Task READ range requires trusted half-open layout proof");
            }
        }
    }

    private final Ast.Program programIdentity;
    private final Ast.TaskExpr taskIdentity;
    private final long ownershipEpoch;
    private final AllocationIdentity allocation;
    private final List<Projection> projections;

    private ScopedTaskReadAuthority(
            Ast.Program programIdentity,
            Ast.TaskExpr taskIdentity,
            long ownershipEpoch,
            AllocationIdentity allocation,
            List<Projection> projections) {
        this.programIdentity = programIdentity;
        this.taskIdentity = taskIdentity;
        this.ownershipEpoch = ownershipEpoch;
        this.allocation = allocation;
        this.projections = projections;
    }

    public static ScopedTaskReadAuthority prove(
            Ast.Program program,
            Ast.TaskExpr task,
            long ownershipEpoch,
            AllocationIdentity allocation,
            List<Projection> projections,
            Types.Type referentType) {
        ScopedTaskReadAuthority frozen =
                freezeForOwnershipCheck(
                        program,
                        task,
                        ownershipEpoch,
                        allocation,
                        projections,
                        referentType);
        // Public proof construction is executable-authority construction:
        // require the exact successful Task-v2 admission before returning.
        Ast.CheckedTaskCaptures.require(program, task, ownershipEpoch);
        return frozen;
    }

    /**
     * Freeze the compiler-side READ proof while the ownership pass is still
     * staging Task admissions.
     *
     * <p>This method intentionally does not grant execution authority. The
     * returned object becomes usable only when {@link #require} observes the
     * exact same Program/Task/epoch in CheckedTaskCaptures after successful
     * whole-program publication. Failed/superseded checks therefore leave the
     * frozen proof inert.</p>
     */
    static ScopedTaskReadAuthority freezeForOwnershipCheck(
            Ast.Program program,
            Ast.TaskExpr task,
            long ownershipEpoch,
            AllocationIdentity allocation,
            List<Projection> projections,
            Types.Type referentType) {
        Objects.requireNonNull(program, "ownership Program");
        Objects.requireNonNull(task, "TaskExpr");
        Objects.requireNonNull(allocation, "allocation identity");
        Objects.requireNonNull(projections, "READ projections");
        Objects.requireNonNull(referentType, "referent type");
        if (ownershipEpoch <= 0) {
            throw new IllegalArgumentException(
                    "scoped Task READ ownership epoch must be positive");
        }

        if (task.domain() != Ast.ExecutionDomain.CPU) {
            throw new IllegalArgumentException(
                    "scoped Task READ requires CPU execution until device-fence authority exists");
        }
        AutoTraitRules.Capabilities referentCapabilities =
                TypeAutoTraits.inferClosed(referentType);
        if (!referentCapabilities.sync()) {
            throw new IllegalArgumentException(
                    "scoped Task READ referent requires canonical compiler-derived Sync proof");
        }
        if (projections.size() > MAX_PROJECTION_DEPTH) {
            throw new IllegalArgumentException(
                    "scoped Task READ projection depth exceeded");
        }

        ArrayList<Projection> frozen = new ArrayList<>(projections.size());
        for (Projection projection : projections) {
            frozen.add(Objects.requireNonNull(
                    projection, "scoped Task READ projection"));
        }

        return new ScopedTaskReadAuthority(
                program,
                task,
                ownershipEpoch,
                allocation,
                List.copyOf(frozen));
    }

    public void require(
            Ast.Program program,
            Ast.TaskExpr task,
            long ownershipEpoch,
            long allocationId,
            long generation) {
        if (programIdentity != program || taskIdentity != task) {
            throw new IllegalArgumentException(
                    "scoped Task READ authority belongs to a different exact compiler boundary");
        }
        if (this.ownershipEpoch != ownershipEpoch) {
            throw new IllegalArgumentException(
                    "scoped Task READ ownership epoch is stale");
        }
        if (allocation.allocationId() != allocationId
                || allocation.generation() != generation) {
            throw new IllegalArgumentException(
                    "scoped Task READ allocation identity/generation mismatch");
        }
        // Revalidate that the Task-v2 admission has not been revoked by a
        // failed or superseding ownership pass.
        Ast.CheckedTaskCaptures.require(program, task, ownershipEpoch);
    }

    public AllocationIdentity allocation() { return allocation; }
    public List<Projection> projections() { return projections; }
}
