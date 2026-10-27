package dev.oreslang.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Coherent, compile-time dictionary/instance resolution for a future typeclass
 * frontend. Definitions are not first-class values or runtime heap objects.
 * No implicit instance search is performed by the existing trait parser yet.
 */
public final class TypeclassRegistry {
    private TypeclassRegistry() { }

    public record TypeRef(String module, String name, List<TypeRef> arguments, boolean variable) {
        public TypeRef {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) throw new IllegalArgumentException("type name cannot be empty");
            arguments = List.copyOf(arguments);
            if (variable && (!module.isEmpty() || !arguments.isEmpty())) {
                throw new IllegalArgumentException("type variable cannot have a module or arguments");
            }
        }
        public static TypeRef variable(String name) { return new TypeRef("", name, List.of(), true); }
        public static TypeRef named(String module, String name, TypeRef... arguments) {
            return new TypeRef(Objects.requireNonNull(module), name, List.of(arguments), false);
        }
        public boolean isGround() {
            return !variable && arguments.stream().allMatch(TypeRef::isGround);
        }
    }

    public record Typeclass(String module, String name, int arity) {
        public Typeclass {
            Objects.requireNonNull(module);
            Objects.requireNonNull(name);
            if (module.isBlank() || name.isBlank() || arity < 1)
                throw new IllegalArgumentException("typeclass requires module, name, and positive arity");
        }
        public String qualifiedName() { return module + "." + name; }
    }

    public record Instance(Typeclass typeclass, String definingModule,
                           List<TypeRef> arguments, String dictionarySymbol) {
        public Instance {
            Objects.requireNonNull(typeclass);
            Objects.requireNonNull(definingModule);
            Objects.requireNonNull(dictionarySymbol);
            arguments = List.copyOf(arguments);
            if (definingModule.isBlank() || dictionarySymbol.isBlank())
                throw new IllegalArgumentException("instance module/dictionary symbol cannot be empty");
            if (arguments.size() != typeclass.arity())
                throw new IllegalArgumentException("wrong typeclass instance arity");
        }
    }

    public static final class Registry {
        private final Map<String, Typeclass> definitions = new LinkedHashMap<>();
        private final Map<String, List<Instance>> instances = new LinkedHashMap<>();

        public void define(Typeclass typeclass) {
            if (definitions.putIfAbsent(typeclass.qualifiedName(), typeclass) != null)
                throw new IllegalArgumentException("duplicate typeclass: " + typeclass.qualifiedName());
        }

        public void register(Instance instance) {
            Typeclass tc = definitions.get(instance.typeclass().qualifiedName());
            if (!instance.typeclass().equals(tc))
                throw new IllegalArgumentException("undefined or mismatched typeclass: "
                        + instance.typeclass().qualifiedName());
            // Conservative orphan rule: own the typeclass or at least one
            // outermost concrete type constructor. Primitive/core types are foreign.
            if (!instance.definingModule().equals(tc.module())
                    && instance.arguments().stream().noneMatch(t ->
                            !t.variable() && t.module().equals(instance.definingModule()))) {
                throw new IllegalArgumentException("orphan instance: " + tc.qualifiedName()
                        + " in " + instance.definingModule());
            }
            List<Instance> list = instances.computeIfAbsent(tc.qualifiedName(), ignored -> new ArrayList<>());
            for (Instance prior : list) {
                if (overlaps(prior.arguments(), instance.arguments())) {
                    throw new IllegalArgumentException("overlapping typeclass instances: "
                            + prior.dictionarySymbol() + " and " + instance.dictionarySymbol());
                }
            }
            list.add(instance);
        }

        /** Resolution never invents a dictionary; missing evidence is an error. */
        public Instance resolve(Typeclass typeclass, List<TypeRef> groundArguments) {
            if (!typeclass.equals(definitions.get(typeclass.qualifiedName())))
                throw new IllegalArgumentException("unknown typeclass: " + typeclass.qualifiedName());
            if (groundArguments.size() != typeclass.arity()
                    || groundArguments.stream().anyMatch(arg -> !arg.isGround())) {
                throw new IllegalArgumentException("typeclass resolution needs concrete, correctly-arity-checked types");
            }
            Instance selected = null;
            for (Instance candidate : instances.getOrDefault(typeclass.qualifiedName(), List.of())) {
                Map<String, TypeRef> variables = new HashMap<>();
                boolean match = true;
                for (int i = 0; i < groundArguments.size(); i++) {
                    if (!matches(candidate.arguments().get(i), groundArguments.get(i), variables)) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    if (selected != null) throw new IllegalStateException("ambiguous typeclass dictionary");
                    selected = candidate;
                }
            }
            if (selected == null) throw new IllegalArgumentException("missing typeclass instance: "
                    + typeclass.qualifiedName() + groundArguments);
            return selected;
        }
    }

    private static boolean matches(TypeRef pattern, TypeRef actual, Map<String, TypeRef> variables) {
        if (pattern.variable()) {
            TypeRef bound = variables.putIfAbsent(pattern.name(), actual);
            return bound == null || bound.equals(actual);
        }
        if (!pattern.module().equals(actual.module()) || !pattern.name().equals(actual.name())
                || pattern.arguments().size() != actual.arguments().size()) return false;
        for (int i = 0; i < pattern.arguments().size(); i++) {
            if (!matches(pattern.arguments().get(i), actual.arguments().get(i), variables)) return false;
        }
        return true;
    }

    // Most-general unifier with separately renamed instance variables and
    // occurs check. An overlap means there exists any ground head both match.
    private record Term(String atom, List<Term> arguments, boolean variable) { }
    private static Term term(TypeRef type, String side) {
        if (type.variable()) return new Term(side + type.name(), List.of(), true);
        return new Term(type.module() + "::" + type.name(),
                type.arguments().stream().map(arg -> term(arg, side)).toList(), false);
    }

    private static boolean overlaps(List<TypeRef> left, List<TypeRef> right) {
        Map<String, Term> substitution = new HashMap<>();
        for (int i = 0; i < left.size(); i++) {
            if (!unify(term(left.get(i), "L:"), term(right.get(i), "R:"), substitution)) return false;
        }
        return true;
    }
    private static Term dereference(Term t, Map<String, Term> subs) {
        while (t.variable() && subs.containsKey(t.atom())) t = subs.get(t.atom());
        return t;
    }
    private static boolean occurs(String variable, Term t, Map<String, Term> subs) {
        t = dereference(t, subs);
        if (t.variable()) return variable.equals(t.atom());
        for (Term arg : t.arguments()) if (occurs(variable, arg, subs)) return true;
        return false;
    }
    private static boolean unify(Term a, Term b, Map<String, Term> subs) {
        a = dereference(a, subs);
        b = dereference(b, subs);
        if (a.equals(b)) return true;
        if (a.variable()) {
            if (occurs(a.atom(), b, subs)) return false;
            subs.put(a.atom(), b);
            return true;
        }
        if (b.variable()) {
            if (occurs(b.atom(), a, subs)) return false;
            subs.put(b.atom(), a);
            return true;
        }
        if (!a.atom().equals(b.atom()) || a.arguments().size() != b.arguments().size()) return false;
        for (int i = 0; i < a.arguments().size(); i++)
            if (!unify(a.arguments().get(i), b.arguments().get(i), subs)) return false;
        return true;
    }
}
