package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Explicit-state lifetime model for actor-local storage that may be promoted
 * into a runtime-owned synchronized proxy domain.
 *
 * <p>The invariant is deliberately independent of JVM placement: actor teardown
 * may retire actor ownership, but reclamation cannot occur while a promoted
 * runtime capability still roots the underlying storage.</p>
 */
final class FormalActorStorageLifetimeModelCheckTest {
    private enum Action {
        ALLOCATE_LOCAL,
        PROMOTE_PROXY,
        DROP_LOCAL_OWNER,
        DISPOSE_PROXY,
        STOP_ACTOR,
        RECLAIM
    }

    private record State(
            boolean actorAlive,
            boolean allocated,
            boolean localOwner,
            boolean proxyRoot,
            boolean reclaimed) {

        static State initial() {
            return new State(true, false, false, false, false);
        }

        boolean rooted() {
            return localOwner || proxyRoot;
        }
    }

    @Test
    void everyReachableStateForbidsReclaimWhileAnyRootRemains() {
        Set<State> states = explore();
        assertTrue(states.contains(new State(false, true, false, true, false)),
                "model must reach actor-stop with promoted runtime root still live");
        assertTrue(states.contains(new State(false, true, false, false, false)),
                "model must reach stopped unrooted storage before reclamation");
        assertTrue(states.contains(new State(false, true, false, false, true)),
                "model must reach reclaimed storage only after all roots are gone");

        for (State state : states) {
            if (state.reclaimed()) {
                assertFalse(state.rooted(),
                        "reclaimed storage cannot retain a local owner or runtime proxy root");
            }
            if (state.proxyRoot()) {
                assertTrue(state.allocated());
                assertFalse(state.reclaimed(),
                        "runtime-owned proxy capability must keep promoted storage alive");
            }
        }
    }

    @Test
    void actorStopRetiresLocalOwnershipButCannotKillPromotedStorage() {
        State state = State.initial();
        state = step(state, Action.ALLOCATE_LOCAL).orElseThrow();
        state = step(state, Action.PROMOTE_PROXY).orElseThrow();
        state = step(state, Action.STOP_ACTOR).orElseThrow();

        assertFalse(state.actorAlive());
        assertFalse(state.localOwner(),
                "actor teardown retires the actor-local owner");
        assertTrue(state.proxyRoot(),
                "promotion transfers lifetime responsibility to the runtime-owned proxy");

        assertTrue(step(state, Action.RECLAIM).isEmpty(),
                "actor teardown must not reclaim storage still rooted by a proxy");

        state = step(state, Action.DISPOSE_PROXY).orElseThrow();
        assertTrue(step(state, Action.RECLAIM).isPresent(),
                "reclamation becomes legal only after the promoted root is revoked");
    }

    @Test
    void ordinaryUnpromotedActorStorageBecomesReclaimableAtStop() {
        State state = State.initial();
        state = step(state, Action.ALLOCATE_LOCAL).orElseThrow();
        state = step(state, Action.STOP_ACTOR).orElseThrow();

        assertFalse(state.rooted());
        assertTrue(step(state, Action.RECLAIM).isPresent());
    }

    private static Set<State> explore() {
        Set<State> seen = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        State initial = State.initial();
        seen.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            for (Action action : Action.values()) {
                Optional<State> next = step(state, action);
                if (next.isPresent() && seen.add(next.orElseThrow())) {
                    queue.addLast(next.orElseThrow());
                }
            }
        }
        return Set.copyOf(seen);
    }

    private static Optional<State> step(State s, Action action) {
        return switch (action) {
            case ALLOCATE_LOCAL -> {
                if (!s.actorAlive() || s.allocated()) yield Optional.empty();
                yield Optional.of(new State(true, true, true, false, false));
            }
            case PROMOTE_PROXY -> {
                if (!s.actorAlive()
                        || !s.allocated()
                        || !s.localOwner()
                        || s.proxyRoot()
                        || s.reclaimed()) {
                    yield Optional.empty();
                }
                // Promotion consumes raw actor-local ownership and replaces it
                // with the runtime-owned proxy root.
                yield Optional.of(new State(true, true, false, true, false));
            }
            case DROP_LOCAL_OWNER -> {
                if (!s.localOwner() || s.reclaimed()) yield Optional.empty();
                yield Optional.of(new State(
                        s.actorAlive(), s.allocated(), false, s.proxyRoot(), false));
            }
            case DISPOSE_PROXY -> {
                if (!s.proxyRoot() || s.reclaimed()) yield Optional.empty();
                yield Optional.of(new State(
                        s.actorAlive(), s.allocated(), s.localOwner(), false, false));
            }
            case STOP_ACTOR -> {
                if (!s.actorAlive()) yield Optional.empty();
                yield Optional.of(new State(
                        false, s.allocated(), false, s.proxyRoot(), s.reclaimed()));
            }
            case RECLAIM -> {
                if (!s.allocated() || s.reclaimed() || s.rooted()) yield Optional.empty();
                yield Optional.of(new State(
                        s.actorAlive(), true, false, false, true));
            }
        };
    }
}
