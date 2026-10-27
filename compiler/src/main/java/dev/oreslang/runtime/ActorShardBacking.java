package dev.oreslang.runtime;

/**
 * Package-private lifecycle base for actor-owned DOD shard storage.
 *
 * <p>The runtime invokes these hooks while holding the owning lease lifecycle.
 * Callers outside the runtime package must not be able to detach backing
 * independently of lease accounting.</p>
 */
abstract sealed class ActorShardBacking
        permits ActorShardColumns, ActorDenseIndexColumns, ActorProxyRequestSlab {
    abstract void revokeBackingFromLease();
    abstract boolean ownedBy(ActorRuntime runtime, ActorRuntime.ActorId actor);
    abstract boolean isPrivateBacked();
}
