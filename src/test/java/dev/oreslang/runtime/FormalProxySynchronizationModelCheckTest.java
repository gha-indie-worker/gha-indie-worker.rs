package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Finite-state model for the fail-closed synchronization contract of rt proxy.
 *
 * <p>It models one actor/task using two proxies. The safety contract is:
 * no read-to-write upgrade, no cross-proxy nested locking, no suspension while
 * any proxy lock is held, and disposal only while unlocked. Suspension is
 * tracked as a boolean witness rather than an unbounded counter because the
 * count does not affect transition legality; this keeps the model finite.</p>
 */
final class FormalProxySynchronizationModelCheckTest {
    private enum ProxyId { A, B }

    private enum LockMode { NONE, READ, WRITE }

    private enum ActionKind {
        ACQUIRE_READ,
        ACQUIRE_WRITE,
        RELEASE,
        SUSPEND,
        DISPOSE
    }

    private record Action(ActionKind kind, ProxyId proxy) {
        static Action read(ProxyId proxy) { return new Action(ActionKind.ACQUIRE_READ, proxy); }
        static Action write(ProxyId proxy) { return new Action(ActionKind.ACQUIRE_WRITE, proxy); }
        static Action release(ProxyId proxy) { return new Action(ActionKind.RELEASE, proxy); }
        static Action dispose(ProxyId proxy) { return new Action(ActionKind.DISPOSE, proxy); }
        static Action suspend() { return new Action(ActionKind.SUSPEND, null); }
    }

    private record State(
            ProxyId lockedProxy,
            LockMode mode,
            boolean disposedA,
            boolean disposedB,
            boolean suspensionObserved) {

        static State initial() {
            return new State(null, LockMode.NONE, false, false, false);
        }

        boolean disposed(ProxyId id) {
            return id == ProxyId.A ? disposedA : disposedB;
        }
    }

    @Test
    void exhaustiveStatesNeverPermitUpgradeCrossProxyNestingOrSuspensionUnderLock() {
        Set<State> states = explore();
        assertTrue(states.size() >= 12);

        for (State state : states) {
            if (state.mode() == LockMode.NONE) {
                assertEquals(null, state.lockedProxy());
            } else {
                assertTrue(state.lockedProxy() != null);
                assertFalse(state.disposed(state.lockedProxy()));
            }
        }
    }

    @Test
    void readToWriteUpgradeFailsClosed() {
        State state = step(State.initial(), Action.read(ProxyId.A)).orElseThrow();
        assertTrue(step(state, Action.write(ProxyId.A)).isEmpty());
    }

    @Test
    void crossProxyNestedLockFailsClosed() {
        State state = step(State.initial(), Action.write(ProxyId.A)).orElseThrow();
        assertTrue(step(state, Action.read(ProxyId.B)).isEmpty());
        assertTrue(step(state, Action.write(ProxyId.B)).isEmpty());
    }

    @Test
    void suspensionIsLegalOnlyAfterLockRelease() {
        State locked = step(State.initial(), Action.read(ProxyId.A)).orElseThrow();
        assertTrue(step(locked, Action.suspend()).isEmpty(),
                "async/await or other suspension while holding proxy lock must fail closed");

        State released = step(locked, Action.release(ProxyId.A)).orElseThrow();
        State suspended = step(released, Action.suspend()).orElseThrow();
        assertTrue(suspended.suspensionObserved());
    }

    @Test
    void disposedProxyCannotBeLockedAgain() {
        State disposed = step(State.initial(), Action.dispose(ProxyId.A)).orElseThrow();
        assertTrue(step(disposed, Action.read(ProxyId.A)).isEmpty());
        assertTrue(step(disposed, Action.write(ProxyId.A)).isEmpty());
        assertTrue(step(disposed, Action.read(ProxyId.B)).isPresent());
    }

    private static Set<State> explore() {
        Set<State> seen = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        State initial = State.initial();
        seen.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            for (ProxyId proxy : ProxyId.values()) {
                for (Action action : new Action[] {
                        Action.read(proxy),
                        Action.write(proxy),
                        Action.release(proxy),
                        Action.dispose(proxy)
                }) {
                    Optional<State> next = step(state, action);
                    if (next.isPresent() && seen.add(next.orElseThrow())) {
                        queue.addLast(next.orElseThrow());
                    }
                }
            }

            Optional<State> suspend = step(state, Action.suspend());
            if (suspend.isPresent() && seen.add(suspend.orElseThrow())) {
                queue.addLast(suspend.orElseThrow());
            }
        }

        return Set.copyOf(seen);
    }

    private static Optional<State> step(State s, Action action) {
        return switch (action.kind()) {
            case ACQUIRE_READ -> acquire(s, action.proxy(), LockMode.READ);
            case ACQUIRE_WRITE -> acquire(s, action.proxy(), LockMode.WRITE);
            case RELEASE -> {
                if (s.mode() == LockMode.NONE || s.lockedProxy() != action.proxy()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        null, LockMode.NONE, s.disposedA(), s.disposedB(), s.suspensionObserved()));
            }
            case SUSPEND -> {
                if (s.mode() != LockMode.NONE) yield Optional.empty();
                yield Optional.of(new State(
                        null, LockMode.NONE, s.disposedA(), s.disposedB(), true));
            }
            case DISPOSE -> {
                if (s.mode() != LockMode.NONE || s.disposed(action.proxy())) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        null,
                        LockMode.NONE,
                        s.disposedA() || action.proxy() == ProxyId.A,
                        s.disposedB() || action.proxy() == ProxyId.B,
                        s.suspensionObserved()));
            }
        };
    }

    private static Optional<State> acquire(State s, ProxyId proxy, LockMode requested) {
        if (s.disposed(proxy)) return Optional.empty();

        // No nested proxy lock of any kind. This simultaneously forbids
        // cross-proxy nesting and read->write upgrade.
        if (s.mode() != LockMode.NONE) return Optional.empty();

        return Optional.of(new State(
                proxy, requested, s.disposedA(), s.disposedB(), s.suspensionObserved()));
    }
}
