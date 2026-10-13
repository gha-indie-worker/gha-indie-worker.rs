package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GenerationalGraphArenaTest {

    @Test
    void directSelfCycleDoesNotNeedReferenceCountingOrRecursiveDrop() {
        try (GenerationalGraphArena<String> graph = new GenerationalGraphArena<>(2, 2)) {
            var node = graph.add("self");
            graph.connect(node, node);
            assertEquals(List.of(node), graph.neighbors(node));
            graph.clear();
            assertEquals(0, graph.size());
            assertTrue(graph.resolve(node).isEmpty());
        }
    }

    @Test
    void parentChildCycleUsesNonOwningGenerationalEdges() {
        try (GenerationalGraphArena<String> graph = new GenerationalGraphArena<>(3, 3)) {
            var parent = graph.add("parent");
            var child = graph.add("child");
            graph.connect(parent, child);
            graph.connect(child, parent);
            assertEquals(List.of(child), graph.neighbors(parent));
            assertEquals(List.of(parent), graph.neighbors(child));
            assertEquals("parent", graph.resolve(parent).orElseThrow());

            graph.remove(parent);
            assertTrue(graph.resolve(parent).isEmpty());
            assertTrue(graph.neighbors(child).isEmpty(),
                    "stale weak-like backedge must not upgrade");
            assertEquals("child", graph.resolve(child).orElseThrow());
        }
    }

    @Test
    void slotReuseDoesNotResurrectOldEdgesOrHandles() {
        try (GenerationalGraphArena<String> graph = new GenerationalGraphArena<>(2, 2)) {
            var root = graph.add("root");
            var previous = graph.add("previous");
            graph.connect(root, previous);
            graph.remove(previous);
            var next = graph.add("next");

            assertEquals(previous.slot(), next.slot());
            assertNotEquals(previous.generation(), next.generation());
            assertTrue(graph.resolve(previous).isEmpty());
            assertTrue(graph.neighbors(root).isEmpty());
            assertEquals("next", graph.resolve(next).orElseThrow());
            assertThrows(IllegalStateException.class, () -> graph.connect(previous, root));
            graph.connect(root, next);
            assertEquals(List.of(next), graph.neighbors(root));
        }
    }

    @Test
    void crossArenaHandlesCannotBeInsertedOrResolved() {
        try (GenerationalGraphArena<String> first = new GenerationalGraphArena<>(1, 2);
             GenerationalGraphArena<String> second = new GenerationalGraphArena<>(1, 2)) {
            var a = first.add("a");
            var b = second.add("b");
            assertTrue(first.resolve(b).isEmpty());
            assertThrows(IllegalStateException.class, () -> first.connect(a, b));
            assertFalse(first.disconnect(a, b));
            assertNotEquals(a, b);
        }
    }

    @Test
    void repeatedClearNeverAliasesPriorGeneration() {
        try (GenerationalGraphArena<Integer> graph = new GenerationalGraphArena<>(1, 1)) {
            var old = graph.add(42);
            for (int i = 0; i < 30; i++) {
                graph.clear();
                var newer = graph.add(i);
                assertEquals(old.slot(), newer.slot());
                assertTrue(graph.resolve(old).isEmpty());
                assertNotEquals(old.generation(), newer.generation());
            }
        }
    }

    @Test
    void staleIncomingEdgesAreReusedUnderFixedDegreeBudget() {
        try (GenerationalGraphArena<Integer> graph = new GenerationalGraphArena<>(2, 1)) {
            var root = graph.add(1);
            for (int n = 0; n < 100; n++) {
                var child = graph.add(n + 2);
                graph.connect(root, child);
                assertEquals(List.of(child), graph.neighbors(root));
                graph.remove(child);
                assertTrue(graph.neighbors(root).isEmpty());
            }
        }
    }

    @Test
    void hardCapacityAndPerNodeEdgeBudgetRejectOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new GenerationalGraphArena<>(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new GenerationalGraphArena<>(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new GenerationalGraphArena<>(65_537, 1));
        assertThrows(IllegalArgumentException.class, () -> new GenerationalGraphArena<>(65_536, 100));
        assertThrows(ArithmeticException.class, () ->
                new GenerationalGraphArena<>(65_536, Integer.MAX_VALUE));

        try (GenerationalGraphArena<Integer> graph = new GenerationalGraphArena<>(2, 1)) {
            var a = graph.add(1);
            var b = graph.add(2);
            graph.connect(a, b);
            graph.connect(a, b); // idempotent, does not exhaust quota
            assertEquals(1, graph.neighbors(a).size());
            assertThrows(IllegalStateException.class, () -> graph.connect(a, a));
            assertThrows(IllegalStateException.class, () -> graph.add(3));
        }
    }

    @Test
    void closeIsIdempotentAndDropsAllReferencesWithoutFollowingCycles() {
        GenerationalGraphArena<Object> graph = new GenerationalGraphArena<>(2, 2);
        Object value = new Object();
        var a = graph.add(value);
        var b = graph.add(value);
        graph.connect(a, b);
        graph.connect(b, a);

        graph.close();
        graph.close();
        assertEquals(0, graph.size());
        assertTrue(graph.resolve(a).isEmpty());
        assertTrue(graph.resolve(b).isEmpty());
        assertThrows(IllegalStateException.class, () -> graph.add("after close"));
        assertThrows(IllegalStateException.class, () -> graph.clear());
    }

    @Test
    void cyclesRemainBoundedUnderManyRecycleRounds() {
        try (GenerationalGraphArena<Integer> graph = new GenerationalGraphArena<>(4, 3)) {
            for (int i = 0; i < 200; i++) {
                var a = graph.add(i);
                var b = graph.add(i + 1);
                graph.connect(a, b);
                graph.connect(b, a);
                graph.remove(a);
                graph.remove(b);
                assertEquals(0, graph.size());
            }
        }
    }
}
