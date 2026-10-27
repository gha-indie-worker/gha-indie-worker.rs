package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Immutable nominal runtime representation of the built-in Entry<K,V> value.
 *
 * <p>Entry is deliberately not represented as a Map/record: preserving this
 * tag prevents a structural {key,value} record from masquerading as an Entry
 * and lets actor transport retain the nominal product across copy/freeze.</p>
 */
public record OresEntry(Object key, Object value) implements OresMutex.SharedState {
    public OresEntry {
        Objects.requireNonNull(key, "Entry key");
        Objects.requireNonNull(value, "Entry value");
    }

    /**
     * Host equality is intentionally identity-only. Oreslang `==` performs
     * its own bounded, nominal, recursive value comparison in the evaluator.
     * Java record component equality is not the language's key-domain or
     * equality contract: it may invoke arbitrary host equals() implementations,
     * silently collapse entries in a host Set/Map, or recurse forever through
     * mutable payload cycles.
     */
    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    /**
     * Logging must not recursively render untrusted/cyclic payloads or leak
     * their contents. Guest formatting uses the bounded CycleSafeDisplay path.
     */
    @Override
    public String toString() {
        return "OresEntry<opaque>";
    }

    @Override
    public Iterable<?> sharedStateChildren() {
        return List.of(key, value);
    }
}
