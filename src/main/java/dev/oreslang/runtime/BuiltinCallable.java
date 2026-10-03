package dev.oreslang.runtime;

import java.util.List;

/** Runtime callable exposed to Oreslang without granting reflective host access. */
@FunctionalInterface
public interface BuiltinCallable {
    Object call(List<Object> args);
}
