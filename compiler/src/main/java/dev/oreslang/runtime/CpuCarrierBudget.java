package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Snapshot of OS-visible CPU capacity at OresVM creation.
 *
 * <p>CPU capacity is an admission heuristic, not a promise of exclusive cores,
 * CPU affinity, or thread-local actor ownership. Explicit dispatcher settings
 * remain authoritative. No filesystem access occurs in an actor hot path.</p>
 */
final class CpuCarrierBudget {
    private CpuCarrierBudget() { }

    static int effectiveProcessors() {
        int available = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            return available;
        }
        int cpuset = firstPositive(
                parseCpuSet(read("/sys/fs/cgroup/cpuset.cpus.effective")),
                parseCpuSet(read("/sys/fs/cgroup/cpuset/cpuset.cpus")));
        int quota = firstPositive(
                parseCpuMax(read("/sys/fs/cgroup/cpu.max")),
                parseCfsQuota(read("/sys/fs/cgroup/cpu/cpu.cfs_quota_us"),
                        read("/sys/fs/cgroup/cpu/cpu.cfs_period_us")));
        return clamp(available, cpuset, quota);
    }

    static int clamp(int available, int cpuset, int quota) {
        int result = Math.max(1, available);
        if (cpuset > 0) result = Math.min(result, cpuset);
        if (quota > 0) result = Math.min(result, quota);
        return result;
    }

    /**
     * cgroup v2: "max 100000" means unlimited; e.g. "150000 100000"
     * admits two carriers (rounded up). Invalid/unavailable readings fail
     * back to the remaining limits rather than inventing a quota.
     */
    static int parseCpuMax(String value) {
        if (value == null) return 0;
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 2 || parts[0].equals("max")) return 0;
        return quota(parts[0], parts[1]);
    }

    static int parseCfsQuota(String quotaValue, String periodValue) {
        if (quotaValue == null || periodValue == null) return 0;
        return quota(quotaValue.trim(), periodValue.trim());
    }

    private static int quota(String amount, String period) {
        try {
            long q = Long.parseLong(amount);
            long p = Long.parseLong(period);
            if (q <= 0 || p <= 0) return 0;
            long count = q / p + (q % p == 0 ? 0 : 1);
            return (int) Math.min(Integer.MAX_VALUE, count);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /** cgroup range syntax: "0-2,5,8-9"; rejects unsorted/overlapping sets. */
    static int parseCpuSet(String value) {
        if (value == null || value.isBlank()) return 0;
        long count = 0;
        long previous = -1;
        try {
            for (String token : value.trim().split(",", -1)) {
                String[] range = token.trim().split("-", -1);
                if (range.length < 1 || range.length > 2) return 0;
                long start = Long.parseLong(range[0].trim());
                long end = range.length == 2
                        ? Long.parseLong(range[1].trim()) : start;
                if (start <= previous || end < start || end < 0
                        || end >= Integer.MAX_VALUE) return 0;
                count += end - start + 1;
                if (count > Integer.MAX_VALUE) return 0;
                previous = end;
            }
        } catch (NumberFormatException exception) {
            return 0;
        }
        return (int) count;
    }

    private static int firstPositive(int primary, int fallback) {
        return primary > 0 ? primary : fallback;
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException | SecurityException exception) {
            return null;
        }
    }

    /**
     * Defaults for three distinct Java actor pools: SHARED, PRIVATE, UNTRUSTED.
     * UNTRUSTED currently follows PRIVATE's parallelism; a quota below three
     * CPUs cannot be represented without overcommit by independent one-worker
     * pools. The desired long-term solution is a cross-domain admission broker.
     */
    static PoolSizes defaults(int budget) {
        int capacity = Math.max(1, budget);
        int privateWorkers = Math.max(1, capacity / 4);
        int sharedWorkers = Math.max(1, capacity - 2 * privateWorkers);
        return new PoolSizes(privateWorkers, sharedWorkers, privateWorkers);
    }

    record PoolSizes(int privateWorkers, int sharedWorkers, int untrustedWorkers) {
        int total() { return privateWorkers + sharedWorkers + untrustedWorkers; }
    }
}
