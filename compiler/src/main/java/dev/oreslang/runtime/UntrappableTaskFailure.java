package dev.oreslang.runtime;

/**
 * Runtime-only classification for source panics and untrappable failures.
 * Unlike inspecting a host Java exception superclass, this marker preserves
 * the compiler's source PANIC category through asynchronous Task cleanup.
 * No ordinary Oreslang guest value can implement this Java runtime contract.
 */
public interface UntrappableTaskFailure { }
