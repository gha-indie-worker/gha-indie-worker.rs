package dev.oreslang.types;

import java.util.List;
import java.util.Objects;

/**
 * Canonical alias/access-set rules for AoS, SoA, AoSoA and ECS storage.
 *
 * <p>Physical layout is intentionally not an aliasing permission. The checker
 * reasons over logical places: store -> archetype -> component -> field ->
 * row/chunk range. Sibling fields/components and disjoint row ranges can be
 * borrowed independently; a whole-place access overlaps all descendants.
 */
public final class DataOrientedBorrowChecker {
    private DataOrientedBorrowChecker() { }

    public enum Layout { AOS, SOA, AOSOA }
    public enum Access { READ, WRITE }

    /** Half-open row/lane interval [start, end). Null means all rows. */
    public record Range(long start, long end) {
        public Range {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("invalid DOP borrow range");
            }
        }

        boolean overlaps(Range other) {
            return start < other.end && other.start < end;
        }
    }

    /**
     * Null archetype/component/field is the whole parent place. Epoch fences
     * relocation, compaction and explicit relayout.
     */
    public record Place(
            Layout layout,
            String store,
            String archetype,
            String component,
            String field,
            Range rows,
            long epoch) {
        public Place {
            Objects.requireNonNull(layout, "layout");
            if (store == null || store.isBlank()) {
                throw new IllegalArgumentException("DOP place requires store identity");
            }
            requireNonBlankIfPresent(archetype, "archetype");
            requireNonBlankIfPresent(component, "component");
            requireNonBlankIfPresent(field, "field");
            if (epoch < 0) throw new IllegalArgumentException("negative storage epoch");
            if (component == null && field != null) {
                throw new IllegalArgumentException("field requires component");
            }
        }
    }

    public record Loan(Place place, Access access) {
        public Loan {
            Objects.requireNonNull(place, "place");
            Objects.requireNonNull(access, "access");
        }
    }

    public static void requireCurrentEpoch(Place place, long currentEpoch) {
        if (currentEpoch < 0 || place.epoch() != currentEpoch) {
            throw new IllegalArgumentException(
                    "stale DOP borrow: place epoch " + place.epoch()
                            + " != current epoch " + currentEpoch);
        }
    }

    public static boolean overlaps(Place left, Place right) {
        if (!left.store().equals(right.store())) return false;
        if (left.epoch() != right.epoch()) {
            throw new IllegalArgumentException(
                    "cannot compare DOP places from different storage epochs: "
                            + left.epoch() + " vs " + right.epoch());
        }
        if (!sameOrWhole(left.archetype(), right.archetype())) return false;
        if (!sameOrWhole(left.component(), right.component())) return false;
        if (!sameOrWhole(left.field(), right.field())) return false;
        return left.rows() == null || right.rows() == null
                || left.rows().overlaps(right.rows());
    }

    public static boolean conflicts(Loan left, Loan right) {
        return overlaps(left.place(), right.place())
                && (left.access() == Access.WRITE || right.access() == Access.WRITE);
    }

    public static boolean canRunConcurrently(List<Loan> left, List<Loan> right) {
        left = List.copyOf(left);
        right = List.copyOf(right);
        for (Loan a : left) {
            for (Loan b : right) {
                if (conflicts(a, b)) return false;
            }
        }
        return true;
    }

    public static void requireInternallyValid(List<Loan> loans) {
        loans = List.copyOf(loans);
        for (int i = 0; i < loans.size(); i++) {
            for (int j = i + 1; j < loans.size(); j++) {
                if (conflicts(loans.get(i), loans.get(j))) {
                    throw new IllegalArgumentException(
                            "conflicting DOP loans: " + loans.get(i)
                                    + " overlaps " + loans.get(j));
                }
            }
        }
    }

    private static boolean sameOrWhole(String left, String right) {
        return left == null || right == null || left.equals(right);
    }

    private static void requireNonBlankIfPresent(String value, String label) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(
                    "DOP " + label + " identity cannot be blank");
        }
    }
}

