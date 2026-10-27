package dev.oreslang.compiler;

import java.util.Objects;

/**
 * Versioned target descriptor and conservative contiguous-memory bounds.
 * Descriptors do NOT enable cross-compilation or imply runtime certification.
 * Keep registry in sync with contracts/architecture/v1/targets.tsv.
 */
public final class TargetAbi {
    private final String triple;
    private final int pointerBits;
    private final String endian;
    private final String abi;
    private final String emission;

    private TargetAbi(String triple, int pointerBits, String endian, String abi, String emission) {
        this.triple = triple;
        this.pointerBits = pointerBits;
        this.endian = endian;
        this.abi = abi;
        this.emission = emission;
    }

    public static TargetAbi parse(String triple) {
        Objects.requireNonNull(triple, "target triple");
        return switch (triple) {
            case "x86_64-unknown-linux-gnu" ->
                    new TargetAbi(triple, 64, "little", "sysv", "unimplemented");
            case "aarch64-unknown-linux-gnu" ->
                    new TargetAbi(triple, 64, "little", "aapcs64", "unimplemented");
            case "i686-unknown-linux-gnu" ->
                    new TargetAbi(triple, 32, "little", "sysv", "unimplemented");
            case "armv7-unknown-linux-gnueabihf" ->
                    new TargetAbi(triple, 32, "little", "aapcs32-hardfp", "unimplemented");
            case "wasm32-unknown-unknown" ->
                    new TargetAbi(triple, 32, "little", "wasm-c", "experimental-wasm32-ir");
            default -> throw new IllegalArgumentException("unsupported target triple: " + triple);
        };
    }

    public String triple() { return triple; }
    public int pointerBits() { return pointerBits; }
    public String endian() { return endian; }
    public String abi() { return abi; }
    public String emission() { return emission; }
    public boolean hasExperimentalWasmIr() { return emission.equals("experimental-wasm32-ir"); }

    /** Conservative maximum signed pointer-distance for a single target allocation. */
    public long maxContiguousBytes() {
        return pointerBits == 32 ? Integer.MAX_VALUE : Long.MAX_VALUE;
    }

    public long checkedContiguousBytes(long bytes) {
        if (bytes < 0 || bytes > maxContiguousBytes()) {
            throw new ArithmeticException("contiguous byte length exceeds target address range");
        }
        return bytes;
    }

    /** Java ByteBuffer addresses are int-indexed even on a 64-bit JVM. */
    public int checkedJavaByteBufferBytes(long bytes) {
        return Math.toIntExact(checkedContiguousBytes(bytes));
    }
}
