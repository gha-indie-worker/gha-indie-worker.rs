package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/** Stable actor lanes. CPU binding, when requested, is mandatory rather than a hint. */
final class ActorAffinityDispatcher extends AbstractExecutorService {
    private final List<ExecutorService> lanes;
    private final int[] cpus;
    private final Semaphore queueSlots;
    private final AtomicIntegerArray queuedByLane;
    private final int softSpillThreshold;
    private final AtomicLong spills = new AtomicLong();
    private final AtomicInteger nextLane = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    ActorAffinityDispatcher(int parallelism, int capacity, int[] cpus,
                            IntFunction<ExecutorService> factory) {
        if (parallelism < 1 || capacity < 1) throw new IllegalArgumentException("invalid lane limits");
        this.cpus = cpus.clone();
        for (int cpu : cpus) NativeCarrierExecutor.requireAllowedCpu(cpu);
        this.queueSlots = new Semaphore(capacity);
        this.queuedByLane = new AtomicIntegerArray(parallelism);
        this.softSpillThreshold = Math.max(1, capacity / parallelism);
        var created = new ArrayList<ExecutorService>();
        try {
            for (int i = 0; i < parallelism; i++) created.add(Objects.requireNonNull(factory.apply(i)));
        } catch (RuntimeException | Error failure) {
            created.forEach(ExecutorService::shutdownNow);
            throw failure;
        }
        lanes = List.copyOf(created);
    }

    int reserveLane() { return Math.floorMod(nextLane.getAndIncrement(), lanes.size()); }
    int cpuForLane(int lane) { return cpus.length == 0 ? -1 : cpus[lane % cpus.length]; }
    boolean nativeCarriers() { return lanes.stream().allMatch(NativeCarrierExecutor.class::isInstance); }
    long softSpills() { return spills.get(); }

    private int selectLane(int preferred) {
        if (preferred < 0 || preferred >= lanes.size()) {
            throw new IllegalArgumentException("invalid actor dispatch lane");
        }
        // Explicit CPU binding is strict, not a best-effort migration hint.
        if (cpus.length != 0 || queuedByLane.get(preferred) < softSpillThreshold) {
            return preferred;
        }
        int best = preferred;
        int minimum = queuedByLane.get(preferred);
        for (int i = 0; i < lanes.size(); i++) {
            int queued = queuedByLane.get(i);
            if (queued < minimum) {
                minimum = queued;
                best = i;
            }
        }
        return best;
    }

    void executeOnLane(int lane, Runnable command) {
        Objects.requireNonNull(command);
        if (closed.get() || !queueSlots.tryAcquire()) throw new RejectedExecutionException("actor lane queue unavailable");
        // This is a dispatch-placement choice, not an ownership transfer:
        // ActorCell's physical execution lease still guards serial entry.
        int target;
        try {
            target = selectLane(lane);
        } catch (RuntimeException | Error failure) {
            queueSlots.release();
            throw failure;
        }
        QueuedTurn task = new QueuedTurn(command, target);
        queuedByLane.incrementAndGet(target);
        try {
            if (closed.get()) throw new RejectedExecutionException("actor lanes closed");
            lanes.get(target).execute(task);
            if (target != lane) spills.incrementAndGet();
        } catch (RuntimeException | Error failure) {
            task.rejectAdmission();
            throw failure;
        }
    }

    private final class QueuedTurn implements Runnable {
        private final Runnable command;
        private final int assignedLane;
        private final AtomicBoolean released = new AtomicBoolean();
        // 0=pending, 1=entered, 2=rejected. The task itself is the lock:
        // no additional per-dispatch claim allocation in the actor hot path.
        private int claim;
        private QueuedTurn(Runnable command, int assignedLane) {
            this.command = command;
            this.assignedLane = assignedLane;
        }
        private void releaseSlot() {
            if (released.compareAndSet(false, true)) {
                queuedByLane.decrementAndGet(assignedLane);
                queueSlots.release();
            }
        }
        private void rejectAdmission() {
            synchronized (this) {
                if (claim == 0) claim = 2;
            }
            releaseSlot();
        }
        @Override public void run() {
            synchronized (this) {
                if (claim != 0) return;
                claim = 1;
            }
            releaseSlot();
            command.run();
        }
    }

    @Override public void execute(Runnable command) { executeOnLane(reserveLane(), command); }
    @Override public void shutdown() { shutdownNow(); }
    @Override public List<Runnable> shutdownNow() {
        closed.set(true);
        var abandoned = new ArrayList<Runnable>();
        for (var lane : lanes) for (var pending : lane.shutdownNow()) {
            QueuedTurn task = (QueuedTurn) pending;
            task.rejectAdmission();
            abandoned.add(task.command);
        }
        return List.copyOf(abandoned);
    }
    @Override public boolean isShutdown() { return closed.get(); }
    @Override public boolean isTerminated() { return lanes.stream().allMatch(ExecutorService::isTerminated); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long budget = unit.toNanos(timeout);
        long start = System.nanoTime();
        for (var lane : lanes) {
            if (!lane.awaitTermination(Math.max(0, budget - (System.nanoTime() - start)), TimeUnit.NANOSECONDS)) return false;
        }
        return true;
    }
}
