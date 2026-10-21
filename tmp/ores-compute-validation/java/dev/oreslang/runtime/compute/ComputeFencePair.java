package dev.oreslang.runtime.compute;

/** Atomic two-buffer runtime receipt for one admitted kernel submission. */
public final class ComputeFencePair {
    private final ComputeFenceHandle input;
    private final ComputeFenceHandle output;
    private final Object pairProof;

    ComputeFencePair(
            ComputeFenceHandle input,
            ComputeFenceHandle output,
            Object pairProof) {
        this.input = input;
        this.output = output;
        this.pairProof = pairProof;
    }

    public ComputeFenceHandle input() { return input; }
    public ComputeFenceHandle output() { return output; }

    Object pairProof() { return pairProof; }
}
