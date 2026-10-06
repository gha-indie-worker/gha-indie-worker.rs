package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive finite algebra for rt copy/take/borrow/share/proxy provenance.
 *
 * <p>This model specifies semantic ownership/domain transitions independently of
 * physical JVM placement. It is intentionally suitable as a refinement target
 * for the allocator-lowering work: implementations may optimize representation,
 * but they may not change these observable authority/provenance laws.</p>
 */
final class FormalOwnershipDomainModelCheckTest {
    private enum ContextKind {
        ROOT,
        SHARED_ACTOR,
        PRIVATE_ACTOR,
        UNTRUSTED_ACTOR
    }

    private enum ValueKind {
        PRIMITIVE,
        STRUCT,
        CLASS
    }

    private enum Domain {
        PROCESS,
        ACTOR_LOCAL,
        ACTOR_PRIVATE,
        UNTRUSTED_ISOLATE,
        RUNTIME_PROXY
    }

    private enum SourceOwnership {
        UNIQUE,
        SHARED,
        MOVED
    }

    private enum ResultKind {
        NONE,
        COPY,
        BORROW,
        TAKE,
        SHARE,
        PROXY
    }

    private enum Action {
        COPY,
        BORROW,
        RELEASE_BORROW,
        TAKE,
        SHARE,
        PROXY
    }

    private record State(
            ContextKind context,
            ValueKind valueKind,
            Domain sourceDomain,
            SourceOwnership sourceOwnership,
            boolean classCopyContract,
            boolean borrowOutstanding,
            ResultKind resultKind,
            Domain resultDomain,
            int sourceIdentity,
            int resultIdentity,
            int freshAllocations,
            int promotions) {

        static State initial(
                ContextKind context,
                ValueKind kind,
                boolean classCopyContract) {
            return new State(
                    context,
                    kind,
                    domainFor(context),
                    SourceOwnership.UNIQUE,
                    classCopyContract,
                    false,
                    ResultKind.NONE,
                    null,
                    identityFor(kind, 1),
                    0,
                    0,
                    0);
        }

        boolean hasResult() {
            return resultKind != ResultKind.NONE;
        }
    }

    @Test
    void ownershipOperatorsPreserveTheAllocationDomainContract() {
        Set<State> states = explore();

        assertTrue(states.size() >= 70,
                "finite ownership algebra should explore all context/kind/operator combinations");

        for (State state : states) {
            assertSafety(state);
        }
    }

    @Test
    void classCopyWithoutAnExplicitContractHasNoTransition() {
        for (ContextKind context : ContextKind.values()) {
            State state = State.initial(context, ValueKind.CLASS, false);
            assertTrue(step(state, Action.COPY).isEmpty(),
                    "identity class copy must fail closed without a concrete copy contract");
        }
    }

    @Test
    void proxyPromotionExistsOnlyForUniqueClassValuesInSharedActors() {
        for (ContextKind context : ContextKind.values()) {
            State initial = State.initial(context, ValueKind.CLASS, true);
            Optional<State> proxy = step(initial, Action.PROXY);
            if (context == ContextKind.SHARED_ACTOR) {
                assertTrue(proxy.isPresent());
                State promoted = proxy.orElseThrow();
                assertEquals(SourceOwnership.MOVED, promoted.sourceOwnership());
                assertEquals(ResultKind.PROXY, promoted.resultKind());
                assertEquals(Domain.RUNTIME_PROXY, promoted.resultDomain());
                assertEquals(initial.sourceIdentity(), promoted.resultIdentity(),
                        "proxy wraps/promotes the authoritative target; it is not rt copy");
                assertEquals(0, promoted.freshAllocations());
                assertEquals(1, promoted.promotions());
            } else {
                assertTrue(proxy.isEmpty(),
                        "root/private/untrusted contexts must not acquire shared proxy authority");
            }
        }
    }

    private static Set<State> explore() {
        Set<State> seen = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();

        for (ContextKind context : ContextKind.values()) {
            for (ValueKind kind : ValueKind.values()) {
                if (kind == ValueKind.CLASS) {
                    add(State.initial(context, kind, false), seen, queue);
                    add(State.initial(context, kind, true), seen, queue);
                } else {
                    add(State.initial(context, kind, false), seen, queue);
                }
            }
        }

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            for (Action action : Action.values()) {
                Optional<State> next = step(state, action);
                if (next.isPresent()) add(next.orElseThrow(), seen, queue);
            }
        }

        return Set.copyOf(seen);
    }

    private static void add(State state, Set<State> seen, ArrayDeque<State> queue) {
        if (seen.add(state)) queue.addLast(state);
    }

    private static Optional<State> step(State state, Action action) {
        return switch (action) {
            case COPY -> copy(state);
            case BORROW -> borrow(state);
            case RELEASE_BORROW -> releaseBorrow(state);
            case TAKE -> take(state);
            case SHARE -> share(state);
            case PROXY -> proxy(state);
        };
    }

    private static Optional<State> copy(State s) {
        if (s.hasResult()
                || s.borrowOutstanding()
                || s.sourceOwnership() == SourceOwnership.MOVED) {
            return Optional.empty();
        }
        if (s.valueKind() == ValueKind.CLASS && !s.classCopyContract()) {
            return Optional.empty();
        }

        boolean primitive = s.valueKind() == ValueKind.PRIMITIVE;
        return Optional.of(new State(
                s.context(),
                s.valueKind(),
                s.sourceDomain(),
                s.sourceOwnership(),
                s.classCopyContract(),
                false,
                ResultKind.COPY,
                s.sourceDomain(),
                s.sourceIdentity(),
                primitive ? s.sourceIdentity() : s.sourceIdentity() + 1,
                primitive ? s.freshAllocations() : s.freshAllocations() + 1,
                s.promotions()));
    }

    private static Optional<State> borrow(State s) {
        if (s.hasResult()
                || s.borrowOutstanding()
                || s.sourceOwnership() == SourceOwnership.MOVED) {
            return Optional.empty();
        }

        return Optional.of(new State(
                s.context(),
                s.valueKind(),
                s.sourceDomain(),
                s.sourceOwnership(),
                s.classCopyContract(),
                true,
                ResultKind.BORROW,
                s.sourceDomain(),
                s.sourceIdentity(),
                s.sourceIdentity(),
                s.freshAllocations(),
                s.promotions()));
    }

    private static Optional<State> releaseBorrow(State s) {
        if (s.resultKind() != ResultKind.BORROW || !s.borrowOutstanding()) {
            return Optional.empty();
        }

        return Optional.of(new State(
                s.context(),
                s.valueKind(),
                s.sourceDomain(),
                s.sourceOwnership(),
                s.classCopyContract(),
                false,
                ResultKind.NONE,
                null,
                s.sourceIdentity(),
                0,
                s.freshAllocations(),
                s.promotions()));
    }

    private static Optional<State> take(State s) {
        if (s.hasResult()
                || s.borrowOutstanding()
                || s.sourceOwnership() != SourceOwnership.UNIQUE) {
            return Optional.empty();
        }

        return Optional.of(new State(
                s.context(),
                s.valueKind(),
                s.sourceDomain(),
                SourceOwnership.MOVED,
                s.classCopyContract(),
                false,
                ResultKind.TAKE,
                s.sourceDomain(),
                s.sourceIdentity(),
                s.sourceIdentity(),
                s.freshAllocations(),
                s.promotions()));
    }

    private static Optional<State> share(State s) {
        if (s.hasResult()
                || s.borrowOutstanding()
                || s.sourceOwnership() != SourceOwnership.UNIQUE) {
            return Optional.empty();
        }

        return Optional.of(new State(
                s.context(),
                s.valueKind(),
                s.sourceDomain(),
                SourceOwnership.SHARED,
                s.classCopyContract(),
                false,
                ResultKind.SHARE,
                s.sourceDomain(),
                s.sourceIdentity(),
                s.sourceIdentity(),
                s.freshAllocations(),
                s.promotions()));
    }

    private static Optional<State> proxy(State s) {
        if (s.hasResult()
                || s.borrowOutstanding()
                || s.sourceOwnership() != SourceOwnership.UNIQUE
                || s.valueKind() != ValueKind.CLASS
                || s.context() != ContextKind.SHARED_ACTOR) {
            return Optional.empty();
        }

        return Optional.of(new State(
                s.context(),
                s.valueKind(),
                s.sourceDomain(),
                SourceOwnership.MOVED,
                s.classCopyContract(),
                false,
                ResultKind.PROXY,
                Domain.RUNTIME_PROXY,
                s.sourceIdentity(),
                s.sourceIdentity(),
                s.freshAllocations(),
                s.promotions() + 1));
    }

    private static void assertSafety(State state) {
        assertTrue(state.freshAllocations() >= 0);
        assertTrue(state.promotions() >= 0);

        if (state.resultKind() == ResultKind.NONE) {
            assertEquals(null, state.resultDomain());
            assertEquals(0, state.resultIdentity());
        } else {
            assertTrue(state.resultDomain() != null);
        }

        if (state.borrowOutstanding()) {
            assertEquals(ResultKind.BORROW, state.resultKind());
            assertNotEquals(SourceOwnership.MOVED, state.sourceOwnership());
        }

        switch (state.resultKind()) {
            case NONE -> { }
            case COPY -> {
                assertEquals(state.sourceDomain(), state.resultDomain(),
                        "rt copy allocates in the current semantic domain");
                assertFalse(state.borrowOutstanding());
                assertNotEquals(SourceOwnership.MOVED, state.sourceOwnership());
                if (state.valueKind() == ValueKind.PRIMITIVE) {
                    assertEquals(0, state.freshAllocations(),
                            "primitive rt copy is allocation-elided");
                    assertEquals(state.sourceIdentity(), state.resultIdentity());
                } else {
                    assertEquals(1, state.freshAllocations(),
                            "storage-bearing rt copy creates fresh storage");
                    assertNotEquals(state.sourceIdentity(), state.resultIdentity(),
                            "struct/class copy cannot alias the source identity");
                }
                if (state.valueKind() == ValueKind.CLASS) {
                    assertTrue(state.classCopyContract(),
                            "class copy exists only under the explicit class copy contract");
                }
            }
            case BORROW -> {
                assertEquals(state.sourceDomain(), state.resultDomain());
                assertEquals(state.sourceIdentity(), state.resultIdentity());
                assertEquals(0, state.freshAllocations());
                assertEquals(0, state.promotions());
            }
            case TAKE -> {
                assertEquals(SourceOwnership.MOVED, state.sourceOwnership());
                assertEquals(state.sourceDomain(), state.resultDomain(),
                        "rt take transfers authority without relocating storage");
                assertEquals(state.sourceIdentity(), state.resultIdentity());
                assertEquals(0, state.freshAllocations());
                assertEquals(0, state.promotions());
            }
            case SHARE -> {
                assertEquals(SourceOwnership.SHARED, state.sourceOwnership());
                assertEquals(state.sourceDomain(), state.resultDomain(),
                        "rt share changes ownership, not allocation domain");
                assertEquals(state.sourceIdentity(), state.resultIdentity());
                assertEquals(0, state.freshAllocations());
                assertEquals(0, state.promotions());
            }
            case PROXY -> {
                assertEquals(ContextKind.SHARED_ACTOR, state.context());
                assertEquals(ValueKind.CLASS, state.valueKind());
                assertEquals(SourceOwnership.MOVED, state.sourceOwnership(),
                        "proxy creation consumes the raw unique owner");
                assertEquals(Domain.RUNTIME_PROXY, state.resultDomain());
                assertEquals(state.sourceIdentity(), state.resultIdentity(),
                        "proxy is a synchronized capability to one authoritative target");
                assertEquals(0, state.freshAllocations(),
                        "promotion is not modeled as rt copy");
                assertEquals(1, state.promotions());
            }
        }
    }

    private static Domain domainFor(ContextKind context) {
        return switch (context) {
            case ROOT -> Domain.PROCESS;
            case SHARED_ACTOR -> Domain.ACTOR_LOCAL;
            case PRIVATE_ACTOR -> Domain.ACTOR_PRIVATE;
            case UNTRUSTED_ACTOR -> Domain.UNTRUSTED_ISOLATE;
        };
    }

    private static int identityFor(ValueKind kind, int identity) {
        return kind == ValueKind.PRIMITIVE ? 0 : identity;
    }
}
