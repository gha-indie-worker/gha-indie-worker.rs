package dev.oreslang.runtime;

/**
 * Per-compilation accounting for the entire transitive import closure.
 * This is local to one validation/run; never share mutable budgets between tenants.
 */
final class SourceAdmissionBudget {
    static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    static final int MAX_UNITS = 1024;
    static final int MAX_DEPTH = 96;

    private long bytes;
    private int units;

    void checkDepth(int depth) {
        if (depth < 0 || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Oreslang import depth exceeds " + MAX_DEPTH);
        }
    }

    void checkCanAdmitUnit(int depth) {
        checkDepth(depth);
        if (units >= MAX_UNITS) {
            throw new IllegalArgumentException("Oreslang source unit count exceeds " + MAX_UNITS);
        }
    }

    void admit(int actualUtf8Bytes, int depth) {
        checkCanAdmitUnit(depth);
        if (actualUtf8Bytes < 0) {
            throw new IllegalArgumentException("invalid source size");
        }
        if (actualUtf8Bytes > MAX_TOTAL_BYTES - bytes) {
            throw new IllegalArgumentException("Oreslang import closure exceeds 64 MiB");
        }
        bytes += actualUtf8Bytes;
        units++;
    }
}
