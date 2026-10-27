package dev.oreslang.types;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Types {
    private Types() { }

    public sealed interface Type permits Primitive, Named, SelfType, Borrow, ClassNamespace, Record, Function, ListType, Tuple, Union, Generic, StringLiteral, BoolLiteral, Not, Unknown { }

    /** Distinguishes runtime records, internal structural contracts, and concrete structs. */
    public enum AggregateKind { RECORD, CONTRACT, STRUCT }

    private static boolean compatibleAggregateKinds(AggregateKind left, AggregateKind right) {
        // Concrete structs are a distinct exact-shape value domain. RECORD and
        // CONTRACT share structural row semantics; CONTRACT only carries
        // compiler-internal method-signature metadata in addition to data fields.
        return left == AggregateKind.STRUCT || right == AggregateKind.STRUCT
                ? left == right
                : true;
    }

    public enum Primitive implements Type {
        INT, FLOAT, DECIMAL, COMPLEX, BOOL, STRING, VOID, NULL
    }

    public record Named(String name, List<Type> arguments) implements Type {
        public Named { arguments = List.copyOf(arguments); }
    }

    /**
     * Receiver-polymorphic type carried by the implicit instance receiver.
     *
     * <p>The bound is the nominal type whose members are available while checking
     * the method body. A SelfType value may widen to that nominal type, but a
     * fresh value of the nominal type cannot narrow back to SelfType because the
     * actual receiver may be a more-derived subtype. Values already proven to
     * have the receiver-polymorphic self type may flow through aliases/parameters.
     */
    public record SelfType(Type bound) implements Type {
        public SelfType { Objects.requireNonNull(bound, "self bound"); }
    }

    /** Rust-style compile-time borrow; erased by the interpreter runtime. */
    public record Borrow(Type target, boolean mutable) implements Type { }

    /** Compile-time meta-value for access to static class functions. */
    public record ClassNamespace(String className) implements Type { }

    /**
     * Closed structural record type. readOnly is a value capability, not a
     * structural compatibility dimension: it prevents mutation from being
     * regained through aliases while preserving ordinary shape matching.
     */
    public record Record(Map<String, Type> members, boolean readOnly, AggregateKind kind) implements Type {
        public Record {
            members = Map.copyOf(members);
            kind = Objects.requireNonNull(kind, "aggregate kind");
            if (kind == AggregateKind.STRUCT && readOnly) {
                throw new IllegalArgumentException(
                        "struct mutability is a binding/borrow capability, not a readonly structural type bit");
            }
        }
        public Record(Map<String, Type> members) { this(members, false, AggregateKind.RECORD); }
        public Record(Map<String, Type> members, boolean readOnly) {
            this(members, readOnly, AggregateKind.RECORD);
        }
        public static Record struct(Map<String, Type> members) {
            return new Record(members, false, AggregateKind.STRUCT);
        }
        public static Record contract(Map<String, Type> members) {
            return new Record(members, false, AggregateKind.CONTRACT);
        }
    }

    /** Compiler-internal resolved may-effects; source effect syntax is not admitted here. */
    public record CheckedEffects(List<Type> throwsTypes, List<Type> raisesTypes,
                                 List<Type> panicsTypes) {
        public CheckedEffects {
            throwsTypes = normalize(throwsTypes);
            raisesTypes = normalize(raisesTypes);
            panicsTypes = normalize(panicsTypes);
        }

        /** Effect clauses denote sets rather than declaration-order-sensitive lists. */
        private static List<Type> normalize(List<Type> input) {
            return List.copyOf(input).stream()
                    .distinct()
                    .sorted(java.util.Comparator.comparing(Type::toString))
                    .toList();
        }

        public static CheckedEffects none() {
            return new CheckedEffects(List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return throwsTypes.isEmpty() && raisesTypes.isEmpty() && panicsTypes.isEmpty();
        }

        /** Exact-type category preservation; nominal Error subtyping is a separate gate. */
        public boolean isSubsetOf(CheckedEffects allowance) {
            return allowance.throwsTypes.containsAll(throwsTypes)
                    && allowance.raisesTypes.containsAll(raisesTypes)
                    && allowance.panicsTypes.containsAll(panicsTypes);
        }
    }

    public record Function(
            List<Type> parameters,
            List<Boolean> mutableParameters,
            boolean async,
            Type result,
            CheckedEffects effects) implements Type {
        public Function {
            parameters = List.copyOf(parameters);
            mutableParameters = List.copyOf(mutableParameters);
            Objects.requireNonNull(effects, "checked function effects");
            if (parameters.size() != mutableParameters.size()) {
                throw new IllegalArgumentException(
                        "function parameter mutability metadata must match parameter arity");
            }
        }

        /** Existing producers emit pure function values until effect inference is connected. */
        public Function(List<Type> parameters, List<Boolean> mutableParameters,
                        boolean async, Type result) {
            this(parameters, mutableParameters, async, result, CheckedEffects.none());
        }

        public Function(List<Type> parameters, Type result) {
            this(parameters, java.util.Collections.nCopies(parameters.size(), false), false,
                    result, CheckedEffects.none());
        }
    }

    public record ListType(Type element) implements Type { }

    public enum TupleKind {
        TUPLE,
        FIXED_ARRAY,
        FIXED_LIST
    }

    public record Tuple(List<Type> elements, TupleKind kind) implements Type {
        public Tuple {
            elements = List.copyOf(elements);
            kind = Objects.requireNonNull(kind, "kind");
        }

        public Tuple(List<Type> elements) {
            this(elements, TupleKind.TUPLE);
        }
    }

    public record Union(List<Type> options) implements Type {
        public Union {
            options = List.copyOf(options);
            if (options.size() < 2) throw new IllegalArgumentException("union needs at least two distinct types");
        }
    }

    public record Generic(String name) implements Type { }

    public record StringLiteral(String value) implements Type { }

    /** Compile-time singleton boolean; unlike Primitive.BOOL it is not the entire boolean domain. */
    public record BoolLiteral(boolean value) implements Type { }

    /** The complement of excluded in the universe of Oreslang values. */
    public record Not(Type excluded) implements Type {
        public Not { Objects.requireNonNull(excluded, "excluded type"); }
    }

    public enum Unknown implements Type { INSTANCE }

    public static Type notOf(Type type) {
        Objects.requireNonNull(type, "type");
        return type instanceof Not not ? not.excluded() : new Not(type);
    }

    /**
     * A conservative disjointness proof, never a runtime guess.
     *
     * Unknown and generics must not pass a negative type merely because
     * ordinary dynamic compatibility treats them permissively. Structural
     * absence is not proof: a narrowed/widened record view may hide fields.
     */
    public static boolean areProvablyDisjoint(Type first, Type second) {
        if (first == Unknown.INSTANCE || second == Unknown.INSTANCE
                || first instanceof Generic || second instanceof Generic) return false;
        if (first.equals(second)) return false;
        if (first instanceof Union union) {
            return union.options().stream().allMatch(option -> areProvablyDisjoint(option, second));
        }
        if (second instanceof Union union) {
            return union.options().stream().allMatch(option -> areProvablyDisjoint(first, option));
        }
        if (first instanceof Not not) {
            return isAssignable(second, not.excluded()) && !containsUncertainType(second);
        }
        if (second instanceof Not not) {
            return isAssignable(first, not.excluded()) && !containsUncertainType(first);
        }
        if (first instanceof BoolLiteral a && second instanceof BoolLiteral b) return a.value() != b.value();
        if (first instanceof BoolLiteral) return second != Primitive.BOOL
                && second != Primitive.VOID && second != Primitive.NULL
                && !(second instanceof Not);
        if (second instanceof BoolLiteral) return areProvablyDisjoint(second, first);
        if (first instanceof StringLiteral && second instanceof StringLiteral) return !first.equals(second);
        if (first instanceof StringLiteral) return second != Primitive.STRING;
        if (second instanceof StringLiteral) return areProvablyDisjoint(second, first);
        if (first instanceof Primitive a && second instanceof Primitive b) {
            // Numeric widening makes primitive numeric domains potentially overlap.
            return a != b && !(isNumeric(a) && isNumeric(b));
        }
        if (first instanceof Record a && second instanceof Record b) {
            if (!compatibleAggregateKinds(a.kind(), b.kind())) return true;
            // Only conflicting *known* fields prove disjointness. Missing keys do not.
            for (var entry : a.members().entrySet()) {
                Type candidate = b.members().get(entry.getKey());
                if (candidate != null && areProvablyDisjoint(entry.getValue(), candidate)) return true;
            }
            return false;
        }
        if (first instanceof Record && second instanceof Primitive
                || second instanceof Record && first instanceof Primitive) return true;
        if (first instanceof Named && second instanceof Primitive
                || second instanceof Named && first instanceof Primitive) return true;
        if (first instanceof ListType && second instanceof Primitive
                || second instanceof ListType && first instanceof Primitive) return true;
        if (first instanceof Tuple && second instanceof Primitive
                || second instanceof Tuple && first instanceof Primitive) return true;
        return false; // Not knowing if types overlap is NOT proof of exclusion.
    }

    // Shared package-internal proof query for both type relations.
    static boolean containsUncertainType(Type type) {
        if (type == Unknown.INSTANCE || type instanceof Generic) return true;
        if (type instanceof Union union) return union.options().stream().anyMatch(Types::containsUncertainType);
        if (type instanceof Not not) return containsUncertainType(not.excluded());
        if (type instanceof Named named) return named.arguments().stream().anyMatch(Types::containsUncertainType);
        if (type instanceof Record record) return record.members().values().stream().anyMatch(Types::containsUncertainType);
        if (type instanceof ListType list) return containsUncertainType(list.element());
        if (type instanceof Tuple tuple) return tuple.elements().stream().anyMatch(Types::containsUncertainType);
        if (type instanceof Function function) return function.parameters().stream().anyMatch(Types::containsUncertainType)
                || containsUncertainType(function.result())
                || function.effects().throwsTypes().stream().anyMatch(Types::containsUncertainType)
                || function.effects().raisesTypes().stream().anyMatch(Types::containsUncertainType)
                || function.effects().panicsTypes().stream().anyMatch(Types::containsUncertainType);
        if (type instanceof Borrow borrow) return containsUncertainType(borrow.target());
        if (type instanceof SelfType self) return containsUncertainType(self.bound());
        return false;
    }

    public static boolean isAssignable(Type from, Type to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        // Complements must be checked before permissive Unknown/Generic rules.
        // No compiler pass may turn uncertainty into a negative-type proof.
        if (to instanceof Not negative) {
            if (from instanceof Not source) return source.excluded().equals(negative.excluded());
            return areProvablyDisjoint(from, negative.excluded());
        }
        if (from instanceof Not) return to == Unknown.INSTANCE;
        if (from instanceof BoolLiteral && to == Primitive.BOOL) return true;
        // Unknown cannot prove a callable's checked effect contract; an
        // effectful callable cannot discard its effects through Unknown.
        if (from == Unknown.INSTANCE && to instanceof Function) return false;
        if (to == Unknown.INSTANCE && from instanceof Function fn
                && !fn.effects().isEmpty()) return false;
        if (from == Unknown.INSTANCE || to == Unknown.INSTANCE) return true;
        if (to instanceof Generic || from instanceof Generic) return true;
        if (from.equals(to)) return true;
        if (from instanceof SelfType source) {
            if (to instanceof SelfType target) {
                return isAssignable(source.bound(), target.bound())
                        && isAssignable(target.bound(), source.bound());
            }
            return isAssignable(source.bound(), to);
        }
        // A nominal base value cannot manufacture the receiver-polymorphic proof.
        if (to instanceof SelfType) return false;
        if (from instanceof StringLiteral && to == Primitive.STRING) return true;

        if (from instanceof Union source) {
            return source.options().stream().allMatch(option -> isAssignable(option, to));
        }
        if (to instanceof Union target) {
            return target.options().stream().anyMatch(option -> isAssignable(from, option));
        }

        if (from instanceof Borrow source && to instanceof Borrow target) {
            if (target.mutable() && !source.mutable()) return false;
            return isAssignable(source.target(), target.target()) && isAssignable(target.target(), source.target());
        }

        if (from instanceof Named source && to instanceof Named target && source.name().equals(target.name())) {
            if (source.arguments().size() != target.arguments().size()) return false;
            for (int i = 0; i < source.arguments().size(); i++) {
                Type a = source.arguments().get(i), b = target.arguments().get(i);
                if (!isAssignable(a, b) || !isAssignable(b, a)) return false;
            }
            return true;
        }

        if (from instanceof Record source && to instanceof Record target) {
            if (!compatibleAggregateKinds(source.kind(), target.kind())) return false;
            // Structs are closed concrete shapes: unlike records/contracts, they do not
            // participate in row-width subtyping. A lexical infer<T> alias can
            // therefore name the exact generated struct shape.
            if (source.kind() == AggregateKind.STRUCT
                    && source.members().size() != target.members().size()) return false;
            // Readonly structural views may covariantly read fields, but
            // writable destinations cannot widen even one member type:
            // such an alias could later write a FLOAT/Unknown into an INT slot.
            if (!target.readOnly() && source.readOnly()) return false;
            for (Map.Entry<String, Type> required : target.members().entrySet()) {
                Type actual = source.members().get(required.getKey());
                if (actual == null) return false;
                if (target.readOnly()) {
                    // Unknown/Generic are inference holes, not evidence that
                    // a concrete readable field exists under an alias.
                    if (!actual.equals(required.getValue())
                            && (containsUncertainType(actual)
                                    || !isAssignable(actual, required.getValue()))) return false;
                } else if (!actual.equals(required.getValue())) {
                    return false;
                }
            }
            return true;
        }

        if (from instanceof ListType source && to instanceof ListType target) {
            return isAssignable(source.element(), target.element()) && isAssignable(target.element(), source.element());
        }

        if (from instanceof Tuple source && to instanceof Tuple target) {
            if (source.kind() != target.kind()) return false;
            if (source.elements().size() != target.elements().size()) return false;
            for (int i = 0; i < source.elements().size(); i++) {
                if (!isAssignable(source.elements().get(i), target.elements().get(i))) return false;
            }
            return true;
        }

        if (from instanceof Function source && to instanceof Function target) {
            if (source.parameters().size() != target.parameters().size()) return false;
            if (source.async() != target.async()) return false;
            if (!source.effects().isSubsetOf(target.effects())) return false;
            // Parameter mutation authority is part of the callable contract.
            // Keep it invariant: callers and implementations must agree exactly
            // on which arguments may be mutated.
            if (!source.mutableParameters().equals(target.mutableParameters())) return false;
            for (int i = 0; i < source.parameters().size(); i++) {
                if (!isAssignable(target.parameters().get(i), source.parameters().get(i))) return false;
            }
            return isAssignable(source.result(), target.result());
        }

        return numericWidening(from, to);
    }

    private static boolean numericWidening(Type from, Type to) {
        if (from == Primitive.INT && (to == Primitive.FLOAT || to == Primitive.DECIMAL || to == Primitive.COMPLEX)) return true;
        if ((from == Primitive.FLOAT || from == Primitive.DECIMAL) && to == Primitive.COMPLEX) return true;
        return false;
    }

    public static Type numericJoin(Type left, Type right) {
        if (!isNumeric(left) || !isNumeric(right)) return Unknown.INSTANCE;
        if (left == Primitive.COMPLEX || right == Primitive.COMPLEX) return Primitive.COMPLEX;
        if (left == Primitive.DECIMAL || right == Primitive.DECIMAL) return Primitive.DECIMAL;
        if (left == Primitive.FLOAT || right == Primitive.FLOAT) return Primitive.FLOAT;
        return Primitive.INT;
    }

    public static Type unionOf(Type left, Type right) {
        return unionOf(List.of(left, right));
    }

    public static Type unionOf(List<Type> types) {
        java.util.ArrayList<Type> flattened = new java.util.ArrayList<>();
        for (Type type : types) {
            if (type == Unknown.INSTANCE) return Unknown.INSTANCE;
            if (type instanceof Union union) {
                for (Type option : union.options()) if (!flattened.contains(option)) flattened.add(option);
            } else if (!flattened.contains(type)) flattened.add(type);
        }
        if (flattened.isEmpty()) return Unknown.INSTANCE;
        if (flattened.size() == 1) return flattened.getFirst();
        flattened.sort(java.util.Comparator.comparing(Object::toString));
        return new Union(flattened);
    }

    public static boolean isNumeric(Type type) {
        return type == Primitive.INT || type == Primitive.FLOAT || type == Primitive.DECIMAL || type == Primitive.COMPLEX;
    }
}
