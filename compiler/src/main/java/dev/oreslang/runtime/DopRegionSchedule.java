package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Host-side DOP dependency planner; no storage authority or GPU dispatch.
 * Region roots are identity tokens minted by trusted runtime code only.
 */
public final class DopRegionSchedule {
    private DopRegionSchedule() { }

    public enum Mode { READ, WRITE, DISCARD, REDUCE, ATOMIC }

    /** Exclude hidden/control/confusable Unicode metadata while keeping ordinary UTF-8 labels. */
    private static boolean unsafeCodePoint(int c) {
        return c < 0x20 || (c >= 0x7f && c <= 0x9f)
                || c == 0xad || c == 0xa0 || c == 0x034f || c == 0x061c
                || c == 0x1680 || c == 0x180e || (c >= 0x2000 && c <= 0x200f)
                || (c >= 0x2028 && c <= 0x202f) || c == 0x205f
                || (c >= 0x2060 && c <= 0x206f) || c == 0x3000
                || c == 0xfeff || (c >= 0xd800 && c <= 0xdfff);
    }

    private static boolean hasUnsafeLabelCharacters(String label) {
        for (int i = 0; i < label.length();) {
            int cp = label.codePointAt(i);
            if (unsafeCodePoint(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    public static final class Root {
        private final long rows;
        private final int fields;
        private Root(long rows, int fields) {
            if (rows < 0 || fields <= 0) throw new IllegalArgumentException("invalid region shape");
            this.rows = rows;
            this.fields = fields;
        }
        // Package-private: never expose minting or a Root token to guest code.
        static Root trustedCreate(long rows, int fields) { return new Root(rows, fields); }
        public long rows() { return rows; }
        public int fields() { return fields; }
    }

    public record Access(Root root, int field, long begin, long end,
                         Mode mode, String reduction) {
        public Access {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(mode, "mode");
            if (field < 0 || field >= root.fields || begin < 0 || begin > root.rows
                    || end < begin || end > root.rows) {
                throw new IllegalArgumentException("DOP field or row slice out of bounds");
            }
            if ((mode == Mode.REDUCE && (reduction == null || reduction.isBlank()
                    || !reduction.equals(reduction.trim())
                    || hasUnsafeLabelCharacters(reduction)))
                    || (mode != Mode.REDUCE && reduction != null)) {
                throw new IllegalArgumentException("reduction requires exactly one named operator");
            }
        }
        public static Access rows(Root root, int field, long begin, long count,
                                  Mode mode, String reduction) {
            Objects.requireNonNull(root, "root");
            if (count < 0 || begin < 0 || begin > root.rows || count > root.rows - begin) {
                throw new IllegalArgumentException("DOP row count out of bounds");
            }
            return new Access(root, field, begin, begin + count, mode, reduction);
        }
        public static Access rows(Root root, int field, long begin, long count, Mode mode) {
            return rows(root, field, begin, count, mode, null);
        }
    }

    public record Task(String name, List<Access> accesses) {
        public Task {
            if (name == null || name.isBlank() || !name.equals(name.trim())
                    || hasUnsafeLabelCharacters(name))
                throw new IllegalArgumentException("invalid DOP task name");
            accesses = List.copyOf(Objects.requireNonNull(accesses, "accesses"));
        }
    }

    public static boolean independent(Access a, Access b) {
        Objects.requireNonNull(a, "left");
        Objects.requireNonNull(b, "right");
        if (a.root() != b.root() || a.field() != b.field()
                || a.begin() == a.end() || b.begin() == b.end()
                || a.end() <= b.begin() || b.end() <= a.begin()) return true;
        // Even same-op reductions serialize without a certified reduction tree.
        return a.mode() == Mode.READ && b.mode() == Mode.READ;
    }

    public static boolean independent(Task a, Task b) {
        Objects.requireNonNull(a, "left");
        Objects.requireNonNull(b, "right");
        for (Access left : a.accesses()) {
            for (Access right : b.accesses()) {
                if (!independent(left, right)) return false;
            }
        }
        return true;
    }

    /** Stable batch order: each conflicting task follows all its predecessors. */
    public static List<List<Integer>> dependencyLevels(List<Task> tasks) {
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        if (tasks.isEmpty()) return List.of();
        int[] level = new int[tasks.size()];
        int highest = 0;
        for (int i = 0; i < tasks.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (!independent(tasks.get(i), tasks.get(j))) {
                    level[i] = Math.max(level[i], Math.addExact(level[j], 1));
                }
            }
            highest = Math.max(highest, level[i]);
        }
        List<List<Integer>> batches = new ArrayList<>();
        for (int i = 0; i <= highest; i++) batches.add(new ArrayList<>());
        for (int i = 0; i < tasks.size(); i++) batches.get(level[i]).add(i);
        return batches.stream().map(List::copyOf).toList();
    }
}
