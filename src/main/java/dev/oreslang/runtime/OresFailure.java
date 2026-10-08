package dev.oreslang.runtime;

/**
 * The three guest-visible control effects are deliberately distinct from JVM
 * fatal errors, cancellation, and compiler/runtime invariant violations.
 * Payloads remain actor-owned and must not be passed across actor boundaries.
 */
public abstract sealed class OresFailure extends RuntimeException
        permits OresFailure.Throw, OresFailure.Raise, OresFailure.Panic {
    public enum Kind { THROW, RAISE, PANIC }
    private final Kind kind;
    private final Object value;
    private final ActorRuntime ownerRuntime;
    private final ActorRuntime.ActorId ownerActorId;

    private OresFailure(Kind kind, Object value) {
        super(String.valueOf(value), null, true, false);
        this.kind = kind;
        this.value = value;
        this.ownerRuntime = ActorRuntime.currentActorRuntime();
        this.ownerActorId = ActorRuntime.currentActorId().orElse(null);
    }

    public final Kind kind() { return kind; }
    public final Object value() { return value; }

    /**
     * A raise may only be recovered in the actor domain that produced it.
     * In particular, an awaiter in a different actor must not convert a remote
     * actor's exceptional completion into a locally recoverable raise.
     */
    public final boolean localToCurrentActorDomain() {
        return ownerRuntime == ActorRuntime.currentActorRuntime()
                && java.util.Objects.equals(
                        ownerActorId,
                        ActorRuntime.currentActorId().orElse(null));
    }

    /** Operation-local failure: a mailbox turn may fail without killing its actor. */
    public static non-sealed class Throw extends OresFailure {
        public Throw(Object value) { super(Kind.THROW, value); }
    }

    /** Skip ordinary catches and traps; only an explicit recovery boundary may handle it. */
    public static final class Raise extends OresFailure {
        public Raise(Object value) { super(Kind.RAISE, value); }
    }

    /** Always terminal to an actor; only a supervisor may start a replacement. */
    public static final class Panic extends OresFailure {
        public Panic(Object value) { super(Kind.PANIC, value); }
    }
}
