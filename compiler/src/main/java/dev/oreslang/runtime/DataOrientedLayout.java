package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Backend-neutral data-oriented buffer layout planning.
 *
 * This is a host-side checked layout descriptor, NOT a GPU allocator or
 * an executable accelerator kernel. It uses 64-bit checked byte offsets and
 * rejects unsafe shapes before any host/device allocation is attempted.
 */
public final class DataOrientedLayout {
    private DataOrientedLayout() {}

    public enum Kind { AOS, SOA, AOSOA }

    /**
     * Homogeneous fields with a common scalar width. Heterogeneous structs,
     * packed booleans, device alignment restrictions and nested data require
     * a later explicit ABI revision; do not silently reinterpret them.
     */
    public record Plan(Kind kind, long rows, int fields, int scalarBytes,
                       int alignment, int lanes) {
        public Plan {
            Objects.requireNonNull(kind, "layout kind");
            if (rows < 0 || fields <= 0 || scalarBytes <= 0 || alignment <= 0) {
                throw new IllegalArgumentException("layout dimensions must be nonnegative and widths positive");
            }
            if (!powerOfTwo(scalarBytes) || !powerOfTwo(alignment)
                    || scalarBytes > alignment || alignment > 4096) {
                throw new IllegalArgumentException("scalar width/alignment must be compatible powers of two (up to 4096)");
            }
            if (!powerOfTwo(lanes) || lanes > 1024
                    || (kind != Kind.AOSOA && lanes != 1)) {
                throw new IllegalArgumentException("AoSoA lanes must be a power of two <= 1024; other layouts use lanes=1");
            }
            measure(kind, rows, fields, scalarBytes, alignment, lanes);
        }

        public long byteLength() {
            return measure(kind, rows, fields, scalarBytes, alignment, lanes);
        }

        public long byteOffset(long row, int field) {
            if (row < 0 || row >= rows || field < 0 || field >= fields) {
                throw new IndexOutOfBoundsException("buffer row/field out of bounds");
            }
            return switch (kind) {
                case AOS -> Math.addExact(
                        Math.multiplyExact(row, align(Math.multiplyExact((long) fields, scalarBytes), alignment)),
                        Math.multiplyExact((long) field, scalarBytes));
                case SOA -> Math.addExact(
                        Math.multiplyExact((long) field,
                                align(Math.multiplyExact(rows, scalarBytes), alignment)),
                        Math.multiplyExact(row, scalarBytes));
                case AOSOA -> {
                    long tileBytes = align(Math.multiplyExact(
                            Math.multiplyExact((long) fields, lanes), scalarBytes), alignment);
                    yield Math.addExact(Math.addExact(
                            Math.multiplyExact(row / lanes, tileBytes),
                            Math.multiplyExact(Math.multiplyExact((long) field, lanes), scalarBytes)),
                            Math.multiplyExact(row % lanes, scalarBytes));
                }
            };
        }
    }

    private static boolean powerOfTwo(int v) {
        return v > 0 && (v & (v - 1)) == 0;
    }

    private static long align(long bytes, long alignment) {
        if (bytes == 0) return 0;
        return Math.multiplyExact(((bytes - 1) / alignment) + 1, alignment);
    }

    private static long measure(Kind kind, long rows, int fields,
                                int scalarBytes, int alignment, int lanes) {
        return switch (kind) {
            case AOS -> Math.multiplyExact(rows,
                    align(Math.multiplyExact((long) fields, scalarBytes), alignment));
            case SOA -> Math.multiplyExact((long) fields,
                    align(Math.multiplyExact(rows, scalarBytes), alignment));
            case AOSOA -> {
                long tiles = (rows / lanes) + (rows % lanes == 0 ? 0 : 1);
                long bytesPerTile = align(Math.multiplyExact(
                        Math.multiplyExact((long) fields, lanes), scalarBytes), alignment);
                yield Math.multiplyExact(tiles, bytesPerTile);
            }
        };
    }
}
