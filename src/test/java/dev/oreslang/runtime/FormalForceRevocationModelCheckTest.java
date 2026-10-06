package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Explicit-state safety model for force cancellation / external revocation.
 *
 * <p>Hard kill is a two-phase protocol. The actor is fenced first, external
 * revocation runs second, and logical termination may be published only after
 * revocation succeeds. A failed/throwing revoker must clear the fence so the
 * actor can resume. While fenced, mailbox admission and overlapping hard kills
 * fail closed.</p>
 */
final class FormalForceRevocationModelCheckTest {
    private enum ActorState {
        ACTIVE,
        FENCED,
        TERMINATED_CANCELLED
    }

    private enum Action {
        BEGIN_KILL,
        REVOCATION_SUCCEEDS,
        REVOCATION_RETURNS_FALSE,
        REVOCATION_THROWS,
        SEND_MAIL,
        OVERLAPPING_KILL
    }

    private record State(
            ActorState actor,
            boolean externalRevoked,
            int deliveredMessages,
            int rejectedMessages,
            int rejectedKills) {

        static State initial() {
            return new State(ActorState.ACTIVE, false, 0, 0, 0);
        }
    }

    @Test
    void everyReachableStatePreservesTwoPhaseRevocationOrdering() {
        Set<State> states = explore();
        assertTrue(states.size() >= 20,
                "force-revocation model must cover success, failure, throw, and fence races");

        for (State state : states) {
            assertSafety(state);
        }
    }

    @Test
    void failedRevokerClearsFenceAndAllowsProgressAgain() {
        State fenced = step(State.initial(), Action.BEGIN_KILL).orElseThrow();
        State failed =
                step(fenced, Action.REVOCATION_RETURNS_FALSE).orElseThrow();

        assertTrue(failed.actor() == ActorState.ACTIVE);
        assertFalse(failed.externalRevoked());

        State delivered = step(failed, Action.SEND_MAIL).orElseThrow();
        assertTrue(delivered.deliveredMessages() == 1);
    }

    @Test
    void throwingRevokerAlsoClearsFence() {
        State fenced = step(State.initial(), Action.BEGIN_KILL).orElseThrow();
        State failed = step(fenced, Action.REVOCATION_THROWS).orElseThrow();

        assertTrue(failed.actor() == ActorState.ACTIVE);
        assertFalse(failed.externalRevoked());
        assertTrue(step(failed, Action.BEGIN_KILL).isPresent(),
                "retrying a later hard kill is legal after the failed revoker cleared its fence");
    }

    @Test
    void fenceRejectsMailboxAdmissionAndOverlappingKill() {
        State fenced = step(State.initial(), Action.BEGIN_KILL).orElseThrow();

        State rejectedMail = step(fenced, Action.SEND_MAIL).orElseThrow();
        assertTrue(rejectedMail.deliveredMessages() == 0);
        assertTrue(rejectedMail.rejectedMessages() == 1);

        State rejectedKill =
                step(rejectedMail, Action.OVERLAPPING_KILL).orElseThrow();
        assertTrue(rejectedKill.rejectedKills() == 1);
        assertTrue(rejectedKill.actor() == ActorState.FENCED);
    }

    @Test
    void logicalTerminationExistsOnlyAfterSuccessfulExternalRevocation() {
        State fenced = step(State.initial(), Action.BEGIN_KILL).orElseThrow();
        State terminated =
                step(fenced, Action.REVOCATION_SUCCEEDS).orElseThrow();

        assertTrue(terminated.externalRevoked());
        assertTrue(terminated.actor() == ActorState.TERMINATED_CANCELLED);
        assertTrue(step(terminated, Action.SEND_MAIL).isEmpty());
        assertTrue(step(terminated, Action.BEGIN_KILL).isEmpty());
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
            case BEGIN_KILL -> {
                if (s.actor() != ActorState.ACTIVE) yield Optional.empty();
                yield Optional.of(new State(
                        ActorState.FENCED,
                        false,
                        s.deliveredMessages(),
                        s.rejectedMessages(),
                        s.rejectedKills()));
            }

            case REVOCATION_SUCCEEDS -> {
                if (s.actor() != ActorState.FENCED) yield Optional.empty();
                yield Optional.of(new State(
                        ActorState.TERMINATED_CANCELLED,
                        true,
                        s.deliveredMessages(),
                        s.rejectedMessages(),
                        s.rejectedKills()));
            }

            case REVOCATION_RETURNS_FALSE, REVOCATION_THROWS -> {
                if (s.actor() != ActorState.FENCED) yield Optional.empty();
                yield Optional.of(new State(
                        ActorState.ACTIVE,
                        false,
                        s.deliveredMessages(),
                        s.rejectedMessages(),
                        s.rejectedKills()));
            }

            case SEND_MAIL -> {
                if (s.actor() == ActorState.TERMINATED_CANCELLED) {
                    yield Optional.empty();
                }
                if (s.actor() == ActorState.FENCED) {
                    if (s.rejectedMessages() >= 1) yield Optional.empty();
                    yield Optional.of(new State(
                            ActorState.FENCED,
                            false,
                            s.deliveredMessages(),
                            1,
                            s.rejectedKills()));
                }
                if (s.deliveredMessages() >= 1) yield Optional.empty();
                yield Optional.of(new State(
                        ActorState.ACTIVE,
                        false,
                        1,
                        s.rejectedMessages(),
                        s.rejectedKills()));
            }

            case OVERLAPPING_KILL -> {
                if (s.actor() != ActorState.FENCED || s.rejectedKills() >= 1) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        ActorState.FENCED,
                        false,
                        s.deliveredMessages(),
                        s.rejectedMessages(),
                        1));
            }
        };
    }

    private static void assertSafety(State state) {
        if (state.actor() == ActorState.TERMINATED_CANCELLED) {
            assertTrue(state.externalRevoked(),
                    "logical hard-kill termination may be published only after external revocation");
        } else {
            assertFalse(state.externalRevoked(),
                    "external-revoked state must not coexist with a logically live actor");
        }

        if (state.actor() == ActorState.FENCED) {
            assertFalse(state.externalRevoked(),
                    "fence precedes external revocation completion");
        }
    }
}
