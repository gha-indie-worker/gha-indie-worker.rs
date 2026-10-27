package dev.oreslang.runtime;

import java.util.OptionalLong;

/**
 * Cross-backend managed-reference storage policy for the Java/Graal runtime.
 *
 * <p>Ordinary Java object references remain VM-managed: HotSpot or Native
 * Image owns their physical representation. Oreslang-controlled data-oriented
 * storage uses primitive 32-bit indices/offsets so SoA/AoSoA/ECS layouts do
 * not depend on the host VM's oop width.</p>
 */
public final class ManagedReferencePolicy {
    /** Zero is reserved for null, leaving 0..2^32-2 as logical indices. */
    public static final long MAX_DENSE_INDEX32 = 0xffff_fffeL;

    private ManagedReferencePolicy() { }

    public static NativeBuildMode.ManagedReferenceMode hostManagedReferenceMode() {
        return NativeBuildMode.managedReferenceMode();
    }

    public static int denseIndexStorageBytes() {
        return Integer.BYTES;
    }

    /**
     * Encode a logical non-null table index into exactly 32 bits.
     *
     * <p>The returned Java int is an unsigned storage word. Values above
     * Integer.MAX_VALUE therefore appear negative if interpreted as signed
     * Java integers; callers must preserve the encoded bits.</p>
     */
    public static int encodeDenseIndex32(long index) {
        if (index < 0 || index > MAX_DENSE_INDEX32) {
            throw new IllegalArgumentException("dense32 index outside unsigned compact range");
        }
        return (int) (index + 1L);
    }

    /** Decode zero as null and all other 32-bit patterns as unsigned indices. */
    public static OptionalLong decodeDenseIndex32(int encoded) {
        if (encoded == 0) return OptionalLong.empty();
        return OptionalLong.of(Integer.toUnsignedLong(encoded) - 1L);
    }

    public static boolean isNullDenseIndex32(int encoded) {
        return encoded == 0;
    }
}
