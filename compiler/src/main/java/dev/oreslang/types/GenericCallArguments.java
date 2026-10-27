package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Canonical declaration-slot binding for call-site generic type arguments.
 *
 * <p>Generic names are source-level slot identities. Positional arguments
 * occupy slots from the beginning; named arguments select unoccupied slots.
 * A bare underscore requires type inference and never supplies a type or
 * triggers a default. Neither the type checker nor ownership analysis may
 * reimplement these rules independently.
 */
final class GenericCallArguments {
    private GenericCallArguments() { }

    record Bound(Map<String, Ast.TypeRef> explicitTypes, Set<String> inferenceHoles) {
        Bound {
            explicitTypes = Map.copyOf(explicitTypes);
            inferenceHoles = Set.copyOf(inferenceHoles);
        }
    }

    static Bound bind(List<String> parameters, List<Ast.TypeRef> arguments, String label) {
        if (arguments.size() > parameters.size()) {
            throw new IllegalArgumentException(label + " expects at most " + parameters.size()
                    + " explicit type argument(s), got " + arguments.size());
        }
        if (new HashSet<>(parameters).size() != parameters.size()) {
            throw new IllegalArgumentException(label + " has duplicate generic parameter declarations");
        }
        Map<String, Ast.TypeRef> types = new LinkedHashMap<>();
        Set<String> inferenceHoles = new LinkedHashSet<>();
        Set<String> assigned = new HashSet<>();
        boolean seenNamed = false;
        int positionalIndex = 0;
        for (Ast.TypeRef argument : arguments) {
            String name;
            Ast.TypeRef value;
            if (argument.isNamedParameter()) {
                seenNamed = true;
                name = argument.namedParameterName();
                value = argument.namedParameterValue();
                if (!parameters.contains(name)) {
                    throw new IllegalArgumentException(label + " has no generic parameter named '" + name + "'");
                }
            } else {
                if (seenNamed) {
                    throw new IllegalArgumentException(label
                            + ": positional generic arguments must precede named arguments");
                }
                name = parameters.get(positionalIndex++);
                value = argument;
            }
            if (!assigned.add(name)) {
                throw new IllegalArgumentException(label + ": duplicate generic argument '" + name + "'");
            }
            if (value.name().equals("_") && value.arguments().isEmpty() && !value.inferArguments()) {
                inferenceHoles.add(name);
            } else {
                // Only a *whole* generic argument may be an inference hole.
                // Nested '_' or synthetic '$infer$' markers must never reach
                // the type checker as apparently concrete nominal types.
                requireConcreteExplicitType(value, label);
                types.put(name, value);
            }
        }
        return new Bound(types, inferenceHoles);
    }

    private static void requireConcreteExplicitType(Ast.TypeRef value, String label) {
        Deque<Ast.TypeRef> pending = new ArrayDeque<>();
        pending.add(value);
        while (!pending.isEmpty()) {
            Ast.TypeRef next = pending.removeLast();
            if (next.name().equals("_") || next.name().equals("$infer$")) {
                throw new IllegalArgumentException(label
                        + " contains a nested inference hole; only a bare '_' call type argument "
                        + "may request inference");
            }
            pending.addAll(next.arguments());
        }
    }

    /**
     * One ordered defaults algorithm for type checking and ownership checking.
     * Both passes infer value types first; this fills only remaining slots.
     * Explicit inference holes never fall back to a declaration default.
     *
     * <p>The two passes keep their own type representations, but neither may
     * silently publish a default referring to an unresolved generic slot.
     */
    static <T> void applyDefaults(
            List<String> genericNames,
            List<Ast.GenericParameter> specs,
            Map<String, T> bindings,
            Set<String> inferenceHoles,
            Function<Ast.TypeRef, T> resolve,
            BiFunction<T, Map<String, T>, T> substitute,
            Predicate<T> unresolved,
            String label) {
        if (specs.isEmpty()) return;
        if (!specs.stream().map(Ast.GenericParameter::name).toList().equals(genericNames)) {
            throw new IllegalArgumentException(label + " has inconsistent generic declarations");
        }
        // Atomicity matters for callers that retain the original binding map:
        // a late unresolved dependency cannot publish earlier partial defaults.
        Map<String, T> staged = new LinkedHashMap<>(bindings);
        for (Ast.GenericParameter spec : specs) {
            if (spec.defaultType() == null
                    || staged.containsKey(spec.name())
                    || inferenceHoles.contains(spec.name())) continue;
            T concrete = substitute.apply(resolve.apply(spec.defaultType()), Map.copyOf(staged));
            if (concrete == null || unresolved.test(concrete)) {
                throw new IllegalArgumentException("cannot resolve dependent default for generic '"
                        + spec.name() + "' in " + label);
            }
            staged.put(spec.name(), concrete);
        }
        bindings.putAll(staged);
    }

    /** Fail closed after inference/defaults in both TypeChecker and OwnershipChecker. */
    static void requireBound(
            List<String> genericNames, Map<String, ?> bindings, String label) {
        List<String> missing = genericNames.stream()
                .filter(name -> !bindings.containsKey(name) || bindings.get(name) == null)
                .distinct().sorted().toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("cannot infer generic parameter(s) "
                    + missing + " for " + label
                    + "; specify them explicitly or make them inferable from value arguments");
        }
    }

    static boolean containsUnresolvedAstGeneric(Ast.TypeRef ref, Set<String> genericNames) {
        if (ref == null) return false;
        Deque<Ast.TypeRef> pending = new ArrayDeque<>();
        pending.add(ref);
        while (!pending.isEmpty()) {
            Ast.TypeRef next = pending.removeLast();
            if (genericNames.contains(next.name())
                    || next.name().equals("$infer$")
                    || next.name().equals("_")) return true;
            pending.addAll(next.arguments());
        }
        return false;
    }

    static List<String> canonicalStructuralNames(int arity) {
        List<String> names = new ArrayList<>(arity);
        for (int i = 0; i < arity; i++) names.add("$callable" + i);
        return List.copyOf(names);
    }
}
