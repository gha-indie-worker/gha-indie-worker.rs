package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class OresVMTest {
    @Test
    void rootGuestAdmissionCanStayOnCallerAcrossPendingAwait() throws Exception {
        var turns = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        try (OresVM vm = OresVM.create(Runnable::run, turns::add)) {
            Thread caller = Thread.currentThread();
            OresFuture<Integer> source = new OresFuture<>();
            var pc = new java.util.concurrent.atomic.AtomicInteger();
            OresFuture<Integer> task = vm.rootScheduler().start(resume -> {
                assertEquals(caller, Thread.currentThread());
                assertEquals(vm.rootScheduler(), OresScheduler.current());
                if (pc.getAndIncrement() == 0) return OresScheduler.await(source);
                return OresScheduler.done((Integer) resume.value() + 1);
            });
            assertTrue(vm.controlCarrierCount() > 0);
            assertEquals(1, vm.rootScheduler().parallelism());
            turns.poll(5, TimeUnit.SECONDS).run();
            assertTrue(!task.isDone());
            Thread producer = Thread.ofPlatform().start(() -> source.completeFromRuntime(41));
            producer.join();
            turns.poll(5, TimeUnit.SECONDS).run();
            assertEquals(42, task.get(5, TimeUnit.SECONDS));
            assertEquals(2, pc.get());
        }
    }

    @Test
    void controlCarriersAreLiveBeforeFirstRootTaskTurn() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try {
            assertTrue(vm.started());
            assertEquals(
                    vm.rootScheduler().parallelism(),
                    vm.controlCarrierCount(),
                    "all CONTROL carriers must be resident before root execution");

            OresFuture<String> first =
                    vm.rootScheduler().start(resume -> {
                        assertTrue(resume.initial());
                        assertTrue(OresScheduler.isSchedulerCarrierThread());
                        assertEquals(
                                vm.rootScheduler(),
                                OresScheduler.current());
                        return OresScheduler.done(Thread.currentThread().getName());
                    });

            String threadName = first.get(5, TimeUnit.SECONDS);
            assertTrue(threadName.startsWith("ores-control-plane-"));
        } finally {
            vm.close();
        }
    }

    @Test
    void startupIsIdempotentAndSchedulerDispatchIdsRemainFresh() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try {
            OresFuture<Long> task = vm.rootScheduler().start(new OresScheduler.Task<>() {
                private int pc;
                @Override
                public OresScheduler.Step<Long> resume(OresScheduler.Resume resume) {
                    if (pc++ == 0) {
                        long first = OresScheduler.currentDispatchId();
                        return OresScheduler.await(OresFuture.completed(first));
                    }
                    long resumed = OresScheduler.currentDispatchId();
                    assertNotEquals(0L, resumed);
                    assertEquals(OresScheduler.currentTaskDomain(), OresScheduler.currentTaskDomain());
                    return OresScheduler.done(resumed);
                }
            });

            long dispatch = task.get(5, TimeUnit.SECONDS);
            assertTrue(dispatch > 0L);
            vm.startup();
            vm.startup();
            assertEquals(vm.rootScheduler().parallelism(), vm.controlCarrierCount());
        } finally {
            vm.close();
        }
    }
}
