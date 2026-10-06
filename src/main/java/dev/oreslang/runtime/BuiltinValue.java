package dev.oreslang.runtime;

/** Explicit deny-by-default member surface for runtime-owned Oreslang values. */
public interface BuiltinValue {
    Object member(String name);
}
