package dev.oreslang.nodes;

import dev.oreslang.runtime.Awaitable;
import dev.oreslang.runtime.OresFuture;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/**
 * Boundary between guest-source no-yield authority and host await adapters.
 * A host adapter may execute arbitrary projection code or register callbacks
 * synchronously. Both are forbidden until the lexical MutexGuard preflight has
 * passed, regardless of whether the projected Future would be already settled.
 *
 * This does not replace source ownership validation. It protects host values
 * supplied through runtime/interop code that cannot be statically inspected.
 */
final class HostAwaitProjection {
    private HostAwaitProjection() { }

    static OresFuture<?> project(
            Awaitable<?> awaitable, BooleanSupplier hasLiveMutexGuards) {
        Objects.requireNonNull(awaitable, "awaitable");
        Objects.requireNonNull(hasLiveMutexGuards, "hasLiveMutexGuards");
        if (hasLiveMutexGuards.getAsBoolean()) {
            throw new IllegalStateException(
                    "cannot project an Awaitable while holding a live MutexGuard");
        }
        return awaitable.getAwaited();
    }

    static OresFuture<?> adapt(
            CompletionStage<?> stage, BooleanSupplier hasLiveMutexGuards) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(hasLiveMutexGuards, "hasLiveMutexGuards");
        if (hasLiveMutexGuards.getAsBoolean()) {
            throw new IllegalStateException(
                    "cannot adapt a CompletionStage while holding a live MutexGuard");
        }
        return OresFuture.from(stage);
    }
}
