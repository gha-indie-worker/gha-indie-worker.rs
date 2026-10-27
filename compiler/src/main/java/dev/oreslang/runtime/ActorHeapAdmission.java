package dev.oreslang.runtime;

import java.util.Locale;
import java.util.Objects;

/**
 * Fail-closed boundary between actor-confinement and zero *guest-tracing* claims.
 *
 * <p>The JVM actor's ByteBuffer.allocateDirect regions can be logically retired,
 * but ordinary guest objects and the direct-buffer cleaner are JVM managed.
 * Physical isolation of an actor's direct bytes is not proof of a GC-free
 * guest object heap. Native Image Java isolate heaps also use GC.</p>
 *
 * <p>Compressed object references are orthogonal to this contract. HotSpot
 * compressed oops and Graal Native Image compressed references reduce storage
 * width but remain traced Java references. Likewise, Oreslang 32-bit DOD/ECS
 * indices remove object references from selected tables without proving that
 * every guest allocation has left the traced heap.</p>
 *
 * <p>A future native or foreign-memory backend may opt in only after its
 * compiler/runtime can supply every required proof. This class records that
 * admission contract; it does not implement allocation lowering or drops.</p>
 */
public final class ActorHeapAdmission {
    private ActorHeapAdmission() { }

    public enum Backing {
        JVM_OBJECT_HEAP,
        JVM_DIRECT_BYTE_BUFFER,
        NATIVE_IMAGE_JAVA_ISOLATE,
        EXPLICIT_NATIVE_REGION,
        LLVM_NATIVE_ARENA
    }

    public record OwnershipProof(
            boolean allGuestAllocationsLowered,
            boolean checkedUniqueOwnershipAndBorrows,
            boolean deterministicDropOnAllExits,
            boolean noImplicitStrongOwnershipCycles,
            boolean actorAndAsyncQuiescenceFenced,
            boolean nativeAndFfiLeasesFenced,
            boolean physicalReleaseIsDeterministic,
            boolean noTracedForeignHeapReferences) {

        public static OwnershipProof unproven() {
            return new OwnershipProof(false, false, false, false,
                    false, false, false, false);
        }

        public boolean complete() {
            return allGuestAllocationsLowered
                    && checkedUniqueOwnershipAndBorrows
                    && deterministicDropOnAllExits
                    && noImplicitStrongOwnershipCycles
                    && actorAndAsyncQuiescenceFenced
                    && nativeAndFfiLeasesFenced
                    && physicalReleaseIsDeterministic
                    && noTracedForeignHeapReferences;
        }
    }

    /**
     * Reject unsupported combinations rather than advertising a false no-GC
     * execution mode. A successful admission means no *guest tracing* should
     * be required, not that the control-plane JVM can never pause for GC.
     */
    public static void requireZeroGuestTracing(Backing backing, OwnershipProof proof) {
        Objects.requireNonNull(backing, "backing");
        Objects.requireNonNull(proof, "proof");
        if (backing != Backing.EXPLICIT_NATIVE_REGION && backing != Backing.LLVM_NATIVE_ARENA) {
            throw new IllegalStateException("backend " + backing
                    + " is not a deterministic native guest heap; actor confinement is not zero GC");
        }
        if (!proof.complete()) {
            throw new IllegalStateException("strict native ownership requires compiler-generated"
                    + " drops, cycle policy, actor/async/FFI quiescence, and explicit native release");
        }
        // A caller can construct a record full of true values. This is NOT a
        // trusted compiler witness or evidence that the source object's
        // allocator and drop paths are actually implemented. No current
        // backend can activate strict mode through this public API.
        throw new IllegalStateException("strict no-guest-tracing remains unavailable:"
                + " native backend requires an unforgeable compiler/runtime lowering witness");
    }

    /**
     * Current JVM runtime has no source-object-native lowering. Even a Graal
     * native-image isolate's Java object heap can still collect automatically.
     * These modes fail closed before the CONTROL pool or actors are started.
     */
    public static void requireSupportedJvmMode(String requestedMode) {
        Objects.requireNonNull(requestedMode, "requestedMode");
        switch (requestedMode.toLowerCase(Locale.ROOT).trim()) {
            case "hybrid", "ownership-first" -> { return; }
            case "strict-no-gc", "zero-gc", "manual-only" ->
                    throw new IllegalStateException("OresVM JVM/Graal cannot guarantee "
                            + requestedMode + ": ordinary Java objects and ByteBuffer cleaners "
                            + "remain managed; use hybrid until native source-object lowering is verified");
            default -> throw new IllegalArgumentException(
                    "unknown ores.runtime.gc.mode: " + requestedMode);
        }
    }
}
