package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.MessagePackCollectionCodec;
import dev.oreslang.runtime.TaggedMessageCodec;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Runtime boundary for nominal enum payload constructors. A host-created
 * TaggedValue is not automatically a valid value of its claimed enum.
 *
 * This check is independent of actor serialization admission: local nested
 * values can exist without a portable nested MessagePack wire descriptor.
 */
public final class NominalEnumValueGuards {
    private NominalEnumValueGuards() { }

    public static void requirePayload(TaggedMessageContracts.Registry types,
                                      TaggedMessageContracts.Case variant, Object payload) {
        Objects.requireNonNull(types, "enum registry");
        Objects.requireNonNull(variant, "enum variant");
        // A caller-supplied Case record is not a declaration capability.
        // Reject forged case tags, payload contracts, and wire descriptors
        // before considering the claimed case's payload value.
        TaggedMessageContracts.Case declared =
                types.requireCase(variant.qualifiedName());
        if (!declared.equals(variant)) {
            throw new IllegalArgumentException(
                    "unregistered or forged enum case contract: " + variant.qualifiedName());
        }
        if (variant.sourcePayload() == null) {
            if (payload != null) throw new IllegalArgumentException(
                    "payloadless enum case cannot carry data: " + variant.qualifiedName());
            return;
        }
        if (payload == null) throw new IllegalArgumentException(
                "enum case requires payload: " + variant.qualifiedName());
        validate(types, variant.sourcePayload(), payload,
                new IdentityHashMap<>(), new int[]{0}, 0);
    }

    /**
     * A reusable compiler/runtime guard for statically known literal values.
     * Unlike checking normalized Types.Primitive.INT, this retains the exact
     * source spelling (u8, i16, u64, etc.) and rejects impossible values.
     */
    public static void requireValue(TaggedMessageContracts.Registry types,
                                    Ast.TypeRef type, Object value) {
        Objects.requireNonNull(types, "enum registry");
        Objects.requireNonNull(type, "enum payload type");
        validate(types, type, value, new IdentityHashMap<>(), new int[]{0}, 0);
    }

    private static void validate(TaggedMessageContracts.Registry types, Ast.TypeRef expected,
                                 Object actual, IdentityHashMap<Object, Boolean> visiting,
                                 int[] nodes, int depth) {
        if (depth > MessagePackCollectionCodec.MAX_DEPTH
                || ++nodes[0] > MessagePackCollectionCodec.MAX_NODES) {
            throw new IllegalArgumentException("nested enum value exceeds depth/node budget");
        }
        if (actual == null) throw new IllegalArgumentException(
                "enum payload cannot contain an untyped null");

        var enumType = types.enums().get(expected.name());
        if (enumType != null) {
            if (!expected.arguments().isEmpty()) throw new IllegalArgumentException(
                    "enum payload type cannot have generic arguments: " + expected.name());
            if (!(actual instanceof TaggedMessageCodec.TaggedValue tagged)
                    || !expected.name().equals(tagged.enumName())) {
                throw new IllegalArgumentException(
                        "nested enum payload must have nominal owner " + expected.name());
            }
            var found = enumType.cases().stream()
                    .filter(c -> c.name().equals(tagged.variant())).findFirst();
            if (found.isEmpty() || found.get().tag() != tagged.tag()) {
                throw new IllegalArgumentException(
                        "unknown or forged nested enum case/tag " + tagged.qualifiedName());
            }
            if (visiting.put(actual, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("cyclic nested enum object graph");
            }
            try {
                var caseType = found.get();
                if (caseType.sourcePayload() == null) {
                    if (tagged.payload() != null) throw new IllegalArgumentException(
                            "nested nullary enum case carries payload");
                } else {
                    validate(types, caseType.sourcePayload(), tagged.payload(),
                            visiting, nodes, depth + 1);
                }
            } finally {
                visiting.remove(actual);
            }
            return;
        }

        // Construction is a trust boundary, including for host-created
        // TaggedValue payloads. A syntactically declared u8 must NOT silently
        // admit 256 merely because the current type IR erases integer width.
        switch (expected.name()) {
            case "bool", "Bool", "boolean" -> {
                if (!expected.arguments().isEmpty() || !(actual instanceof Boolean)) {
                    throw new IllegalArgumentException("enum bool payload type mismatch");
                }
                return;
            }
            case "string", "String" -> {
                if (!expected.arguments().isEmpty() || !(actual instanceof String)) {
                    throw new IllegalArgumentException("enum string payload type mismatch");
                }
                return;
            }
            case "int", "i64", "i32", "i16", "i8", "u8", "u16", "u32",
                    "u64", "uint", "bigint" -> {
                if (!expected.arguments().isEmpty()) {
                    throw new IllegalArgumentException("enum integer payload cannot have type arguments");
                }
                java.math.BigInteger number;
                if (actual instanceof java.math.BigInteger big) {
                    number = big;
                } else if (actual instanceof Byte || actual instanceof Short
                        || actual instanceof Integer || actual instanceof Long) {
                    number = java.math.BigInteger.valueOf(((Number) actual).longValue());
                } else {
                    throw new IllegalArgumentException("enum integer payload type mismatch");
                }
                int bits = switch (expected.name()) {
                    case "i8", "u8" -> 8;
                    case "i16", "u16" -> 16;
                    case "i32", "u32" -> 32;
                    case "u64", "uint", "int", "i64" -> 64;
                    default -> 0;
                };
                if (bits > 0) {
                    boolean unsigned = expected.name().startsWith("u");
                    java.math.BigInteger lowest = unsigned ? java.math.BigInteger.ZERO
                            : java.math.BigInteger.ONE.shiftLeft(bits - 1).negate();
                    java.math.BigInteger highest = java.math.BigInteger.ONE.shiftLeft(
                            unsigned ? bits : bits - 1).subtract(java.math.BigInteger.ONE);
                    if (number.compareTo(lowest) < 0 || number.compareTo(highest) > 0) {
                        throw new IllegalArgumentException("enum " + expected.name()
                                + " payload out of range");
                    }
                }
                return;
            }
            case "float", "f64" -> {
                if (!expected.arguments().isEmpty()
                        || !(actual instanceof Float || actual instanceof Double)
                        || !Double.isFinite(((Number) actual).doubleValue())) {
                    throw new IllegalArgumentException("enum finite f64 payload type mismatch");
                }
                return;
            }
            default -> { }
        }

        // The compiler does not yet lower owned Box<T> storage. Do not
        // accidentally treat its source type as a free host object pointer.
        if (expected.name().equals("Box")) {
            throw new IllegalArgumentException(
                    "Box<T> requires explicit runtime ownership and allocation support");
        }

        if (expected.name().equals("Tuple")
                || expected.name().equals("FixedArray")
                || expected.name().equals("FixedList")
                || expected.isTupleType()) {
            List<Ast.TypeRef> slots = elements(expected);
            if (!(actual instanceof List<?> values) || values.size() != slots.size()) {
                throw new IllegalArgumentException("nested enum tuple payload arity mismatch");
            }
            checkElements(types, slots, values, visiting, nodes, depth);
            return;
        }
        if (expected.name().equals("List") || expected.name().equals("Array")) {
            List<Ast.TypeRef> arguments = elements(expected);
            if (arguments.size() != 1 || !(actual instanceof List<?> values)) {
                throw new IllegalArgumentException("nested enum collection payload mismatch");
            }
            if (visiting.put(actual, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("cyclic nested enum collection");
            }
            try {
                for (Object element : values) {
                    validate(types, arguments.getFirst(), element, visiting, nodes, depth + 1);
                }
            } finally {
                visiting.remove(actual);
            }
            return;
        }
        // There is no safe dynamic contract for an arbitrary value here.
        // Never accept a host-injected capability, raw pointer or object as
        // a value of a compiler-owned enum payload type.
        throw new IllegalArgumentException("unsupported local enum payload type: "
                + expected.name());
    }

    private static List<Ast.TypeRef> elements(Ast.TypeRef type) {
        if (type.arguments().size() == 1 && type.arguments().getFirst().isSequenceShape()) {
            return type.arguments().getFirst().arguments();
        }
        List<Ast.TypeRef> positional = new ArrayList<>();
        for (Ast.TypeRef argument : type.arguments()) {
            if (!argument.isNamedParameter()) positional.add(argument);
        }
        return positional;
    }

    private static void checkElements(TaggedMessageContracts.Registry types,
                                      List<Ast.TypeRef> slots, List<?> values,
                                      IdentityHashMap<Object, Boolean> visiting,
                                      int[] nodes, int depth) {
        if (visiting.put(values, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic nested enum tuple");
        }
        try {
            for (int i = 0; i < slots.size(); i++) {
                validate(types, slots.get(i), values.get(i), visiting, nodes, depth + 1);
            }
        } finally {
            visiting.remove(values);
        }
    }
}
