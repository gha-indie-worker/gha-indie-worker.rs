package dev.oreslang.runtime.realtime;

import java.util.Objects;

/**
 * Backend-neutral admission skeleton for Java real-time execution.
 *
 * This class does not make the current JVM/Graal runtime hard real time.
 * It records fail-closed properties that a qualified RTSJ/off-heap backend
 * must establish before a path can be labelled java-hard-rt.
 */
public final class RealtimeProfile {
    public enum Scheduler {
        FIXED_PRIORITY,
        EDF
    }

    public enum Failure {
        NONE,
        MISSING_TIMING_BUDGET,
        MEMORY_BUDGET_UNPROVEN,
        SCHEDULABILITY_UNPROVEN,
        MANAGED_HEAP_ACCESS,
        TRACING_GC_INTERFERENCE,
        RUNTIME_CODE_GENERATION,
        UNKNOWN_FFI,
        UNBOUNDED_ALLOCATION,
        UNBOUNDED_BLOCKING,
        UNBOUNDED_LOOP,
        UNBOUNDED_CLEANUP,
        PAGE_FAULT_RISK
    }

    public record Effects(
            boolean managedHeapAccess,
            boolean tracingCollectorMayPause,
            boolean runtimeCodeGeneration,
            boolean unknownFfi,
            boolean unboundedAllocation,
            boolean unboundedBlocking,
            boolean unboundedLoop,
            boolean unboundedCleanup,
            boolean pageFaultRisk) {}

    public record Budget(
            long deadlineNs,
            long wcetNs,
            long maxBlockingNs,
            long maxJitterNs,
            long periodNs,
            long maxMemoryBytes,
            Scheduler scheduler) {
        public Budget {
            Objects.requireNonNull(scheduler, "scheduler");
            if (deadlineNs < 0 || wcetNs < 0 || maxBlockingNs < 0
                    || maxJitterNs < 0 || periodNs < 0 || maxMemoryBytes < 0) {
                throw new IllegalArgumentException("real-time budgets must be non-negative");
            }
        }
    }

    /**
     * Evidence produced outside this scalar admission check.
     *
     * Package-internal by design: external callers cannot mint proof by
     * asserting booleans. A future compiler/runtime bridge must construct this
     * only after verifying this exact Budget/scheduler/deployment. peakMemoryBytes
     * is the proven reachable critical-path peak, not an allocation target.
     */
    record Proof(
            boolean schedulabilityProven,
            boolean peakMemoryProven,
            long peakMemoryBytes) {
        public Proof {
            if (peakMemoryBytes < 0) {
                throw new IllegalArgumentException("proven peak memory must be non-negative");
            }
        }
    }

    public record Result(Failure failure) {
        public boolean admitted() {
            return failure == Failure.NONE;
        }

        public String diagnosticCode() {
            return switch (failure) {
                case NONE -> "OK";
                case MISSING_TIMING_BUDGET -> "RT003_WCET_UNPROVEN";
                case MEMORY_BUDGET_UNPROVEN -> "RT001_UNBOUNDED_ALLOCATION";
                case SCHEDULABILITY_UNPROVEN -> "RT009_SCHEDULING_UNPROVEN";
                case MANAGED_HEAP_ACCESS -> "RT004_MANAGED_HEAP_ESCAPE";
                case TRACING_GC_INTERFERENCE -> "RT011_COLLECTOR_INTERFERENCE";
                case RUNTIME_CODE_GENERATION -> "RT005_DYNAMIC_CODE";
                case UNKNOWN_FFI -> "RT007_UNKNOWN_FFI";
                case UNBOUNDED_ALLOCATION -> "RT001_UNBOUNDED_ALLOCATION";
                case UNBOUNDED_BLOCKING -> "RT002_UNBOUNDED_BLOCKING";
                case UNBOUNDED_LOOP -> "RT008_UNBOUNDED_LOOP";
                case UNBOUNDED_CLEANUP -> "RT006_UNBOUNDED_CLEANUP";
                case PAGE_FAULT_RISK -> "RT010_PAGE_FAULT_RISK";
            };
        }
    }

    private RealtimeProfile() {}

    /**
     * Legacy/convenience entry point. Without independent scheduler and
     * peak-memory evidence, hard-RT admission must fail closed.
     */
    public static Result admitJavaHardRt(Effects effects, Budget budget) {
        return admitJavaHardRt(effects, budget, new Proof(false, false, 0));
    }

    static Result admitJavaHardRt(Effects effects, Budget budget, Proof proof) {
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(proof, "proof");

        if (budget.deadlineNs() == 0 || budget.wcetNs() == 0) {
            return new Result(Failure.MISSING_TIMING_BUDGET);
        }
        if (effects.managedHeapAccess()) {
            return new Result(Failure.MANAGED_HEAP_ACCESS);
        }
        if (effects.tracingCollectorMayPause()) {
            return new Result(Failure.TRACING_GC_INTERFERENCE);
        }
        if (effects.runtimeCodeGeneration()) {
            return new Result(Failure.RUNTIME_CODE_GENERATION);
        }
        if (effects.unknownFfi()) {
            return new Result(Failure.UNKNOWN_FFI);
        }
        if (effects.unboundedAllocation()) {
            return new Result(Failure.UNBOUNDED_ALLOCATION);
        }
        if (effects.unboundedBlocking()) {
            return new Result(Failure.UNBOUNDED_BLOCKING);
        }
        if (effects.unboundedLoop()) {
            return new Result(Failure.UNBOUNDED_LOOP);
        }
        if (effects.unboundedCleanup()) {
            return new Result(Failure.UNBOUNDED_CLEANUP);
        }
        if (effects.pageFaultRisk()) {
            return new Result(Failure.PAGE_FAULT_RISK);
        }

        final long response;
        try {
            response = Math.addExact(
                    Math.addExact(budget.wcetNs(), budget.maxBlockingNs()),
                    budget.maxJitterNs());
        } catch (ArithmeticException overflow) {
            return new Result(Failure.MISSING_TIMING_BUDGET);
        }
        if (response > budget.deadlineNs()) {
            return new Result(Failure.MISSING_TIMING_BUDGET);
        }
        if (!proof.peakMemoryProven()
                || proof.peakMemoryBytes() > budget.maxMemoryBytes()) {
            return new Result(Failure.MEMORY_BUDGET_UNPROVEN);
        }
        if (!proof.schedulabilityProven()) {
            return new Result(Failure.SCHEDULABILITY_UNPROVEN);
        }
        return new Result(Failure.NONE);
    }
}
