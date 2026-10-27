package dev.oreslang.runtime.accel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Validated small-qubit circuit IR with explicit OpenQASM 3 lowering and a
 * real (not mock) state-vector simulator for X/H/CX + terminal measurement.
 * No hardware provider, hardware fallback or claimed general quantum runtime.
 */
public final class QuantumCircuitIR {
    public enum Op { X, H, CX, MEASURE }
    public record Gate(Op op, int target, int control, int classicalBit) { }
    public record Circuit(int qubits, int classicalBits, List<Gate> gates) {
        public Circuit {
            if (qubits < 1 || qubits > 16 || classicalBits < 1 || classicalBits > 16) {
                throw new IllegalArgumentException("simulation profile supports 1..16 qubits and bits");
            }
            Objects.requireNonNull(gates, "gates");
            // Reject oversized user-supplied collections before List.copyOf
            // traverses/allocates their entire contents.
            if (gates.size() > 1024) throw new IllegalArgumentException("circuit exceeds gate quota");
            gates = List.copyOf(gates);
            boolean measuring = false;
            boolean[] consumedQubits = new boolean[qubits];
            boolean[] filledBits = new boolean[classicalBits];
            for (Gate g : gates) {
                Objects.requireNonNull(g);
                Objects.requireNonNull(g.op());
                if (g.target() < 0 || g.target() >= qubits) {
                    throw new IllegalArgumentException("gate target qubit outside register");
                }
                if (g.op() == Op.MEASURE) {
                    measuring = true;
                    if (g.control() != -1 || g.classicalBit() < 0 || g.classicalBit() >= classicalBits
                            || consumedQubits[g.target()] || filledBits[g.classicalBit()]) {
                        throw new IllegalArgumentException("duplicate/invalid measurement or classical alias");
                    }
                    consumedQubits[g.target()] = true;
                    filledBits[g.classicalBit()] = true;
                } else {
                    if (measuring) throw new IllegalArgumentException("nonterminal quantum gate after measurement");
                    if (g.classicalBit() != -1 || (g.op() != Op.CX && g.control() != -1)
                            || (g.op() == Op.CX && (g.control() < 0 || g.control() >= qubits
                                    || g.control() == g.target()))) {
                        throw new IllegalArgumentException("invalid gate operand shape");
                    }
                }
            }
            if (!measuring) throw new IllegalArgumentException("circuit requires terminal measurement");
        }
    }

    public static final class Builder {
        private final int qubits;
        private final int classicalBits;
        private final List<Gate> gates = new ArrayList<>();
        public Builder(int qubits, int classicalBits) {
            if (qubits < 1 || qubits > 16 || classicalBits < 1 || classicalBits > 16) {
                throw new IllegalArgumentException("circuit dimensions outside simulator profile");
            }
            this.qubits = qubits;
            this.classicalBits = classicalBits;
        }
        private Builder append(Gate gate) {
            // Enforce the circuit quota before retaining another Gate. A
            // guest must not build an unbounded intermediate gate list and
            // rely on the Circuit constructor to reject it only at build().
            if (gates.size() >= 1024) {
                throw new IllegalArgumentException("circuit exceeds gate quota");
            }
            gates.add(gate);
            return this;
        }
        public Builder x(int target) { return append(new Gate(Op.X, target, -1, -1)); }
        public Builder h(int target) { return append(new Gate(Op.H, target, -1, -1)); }
        public Builder cx(int control, int target) { return append(new Gate(Op.CX, target, control, -1)); }
        public Builder measure(int qubit, int classicalBit) {
            return append(new Gate(Op.MEASURE, qubit, -1, classicalBit));
        }
        public Circuit build() { return new Circuit(qubits, classicalBits, gates); }
    }

    public static String lowerOpenQasm3(Circuit circuit) {
        Objects.requireNonNull(circuit);
        StringBuilder out = new StringBuilder("OPENQASM 3.0;\ninclude \"stdgates.inc\";\n");
        out.append("qubit[").append(circuit.qubits()).append("] q;\n");
        out.append("bit[").append(circuit.classicalBits()).append("] c;\n");
        for (Gate g : circuit.gates()) {
            switch (g.op()) {
                case X -> out.append("x q[").append(g.target()).append("];\n");
                case H -> out.append("h q[").append(g.target()).append("];\n");
                case CX -> out.append("cx q[").append(g.control()).append("], q[")
                        .append(g.target()).append("];\n");
                case MEASURE -> out.append("c[").append(g.classicalBit()).append("] = measure q[")
                        .append(g.target()).append("];\n");
            }
        }
        return out.toString();
    }

    /** Returns exact ideal probabilities for all classical bit patterns. */
    public static double[] simulateProbabilities(Circuit circuit) {
        Objects.requireNonNull(circuit);
        int states = 1 << circuit.qubits();
        double[] amplitudes = new double[states];
        amplitudes[0] = 1.0;
        final double invSqrt2 = 1.0 / Math.sqrt(2.0);
        for (Gate gate : circuit.gates()) {
            int targetBit = 1 << gate.target();
            switch (gate.op()) {
                case X -> {
                    for (int i = 0; i < states; i++) {
                        if ((i & targetBit) != 0) continue;
                        double tmp = amplitudes[i];
                        amplitudes[i] = amplitudes[i | targetBit];
                        amplitudes[i | targetBit] = tmp;
                    }
                }
                case H -> {
                    for (int i = 0; i < states; i++) {
                        if ((i & targetBit) != 0) continue;
                        double lo = amplitudes[i], hi = amplitudes[i | targetBit];
                        amplitudes[i] = (lo + hi) * invSqrt2;
                        amplitudes[i | targetBit] = (lo - hi) * invSqrt2;
                    }
                }
                case CX -> {
                    int controlBit = 1 << gate.control();
                    for (int i = 0; i < states; i++) {
                        if ((i & targetBit) != 0 || (i & controlBit) == 0) continue;
                        double tmp = amplitudes[i];
                        amplitudes[i] = amplitudes[i | targetBit];
                        amplitudes[i | targetBit] = tmp;
                    }
                }
                case MEASURE -> { /* terminal measurements project the final distribution */ }
            }
        }
        double[] probabilities = new double[1 << circuit.classicalBits()];
        for (int state = 0; state < states; state++) {
            int classical = 0;
            for (Gate gate : circuit.gates()) {
                if (gate.op() == Op.MEASURE && (state & (1 << gate.target())) != 0) {
                    classical |= 1 << gate.classicalBit();
                }
            }
            probabilities[classical] += amplitudes[state] * amplitudes[state];
        }
        return probabilities;
    }

    /** Reproducible simulated shot counts. Hardware selection is NOT implicit. */
    public static long[] simulateShots(Circuit circuit, int shots, long seed) {
        if (shots < 0 || shots > 1_000_000) throw new IllegalArgumentException("shot quota exceeded");
        double[] probabilities = simulateProbabilities(circuit);
        long[] counts = new long[probabilities.length];
        // Precompute a monotone CDF once. Linear scanning for every shot
        // costs O(shots * 2^classicalBits), up to 65,536,000,000 probes in
        // this admitted 16-bit / 1M-shot profile: a denial-of-service risk.
        double[] cdf = new double[probabilities.length];
        double total = 0.0;
        for (int i = 0; i < probabilities.length; i++) {
            if (!Double.isFinite(probabilities[i]) || probabilities[i] < 0.0) {
                throw new IllegalStateException("invalid simulated probability");
            }
            total += probabilities[i];
            cdf[i] = total;
        }
        if (!Double.isFinite(total) || total <= 0.0) {
            throw new IllegalStateException("invalid simulated probability mass");
        }
        Random random = new Random(seed);
        for (int shot = 0; shot < shots; shot++) {
            // Multiplication can round a value drawn below 1.0 up to
            // total; clamp to the greatest representable value below total
            // so the strict CDF upper-bound never selects a zero-weight tail.
            double value = Math.min(random.nextDouble() * total, Math.nextDown(total));
            // Upper bound: cdf[outcome] must strictly exceed value. Equal
            // prefix sums represent impossible outcomes and are skipped.
            int low = 0, high = cdf.length - 1;
            while (low < high) {
                int mid = low + (high - low) / 2;
                if (value < cdf[mid]) high = mid;
                else low = mid + 1;
            }
            counts[low]++;
        }
        return counts;
    }
}
