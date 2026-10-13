package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.MessagePackCollectionCodec;
import dev.oreslang.runtime.MessagePackCollectionCodec.Shape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lossless, source-spelling-aware actor wire-schema construction.
 *
 * <p>Do not construct codec schemas from normalized Types.Primitive/ListType:
 * normalization deliberately loses signedness/bit width and the difference
 * between Array and List. The immutable TypeRef syntax and fully expanded
 * imported aliases must be supplied by the compiler instead.
 *
 * <p>Only the executable MessagePack v2 subset is accepted here. This class
 * rejects generic/erased, nonportable numeric, metadata, function/borrow and
 * unknown source shapes until there are corresponding native codecs.
 */
public final class ActorWireSchema {
    private ActorWireSchema() { }

    public static Shape fromSource(Ast.TypeRef ref) {
        return fromSource(ref, Map.of());
    }

    public static Shape fromSource(
            Ast.TypeRef ref, Map<String, Ast.TypeRef> resolvedAliases) {
        Objects.requireNonNull(ref, "source actor message type");
        Objects.requireNonNull(resolvedAliases, "resolved aliases");
        return fromSource(ref, resolvedAliases, ignored -> null);
    }

    /** Resolve nominal enums only through the trusted compiler declaration table. */
    public static Shape fromSource(
            Ast.TypeRef ref, Map<String, Ast.TypeRef> resolvedAliases,
            java.util.function.Function<String, Shape> enumResolver) {
        Objects.requireNonNull(ref, "source actor message type");
        Objects.requireNonNull(resolvedAliases, "resolved aliases");
        Objects.requireNonNull(enumResolver, "enum resolver");
        return resolve(ref, Map.copyOf(resolvedAliases), new HashSet<>(), 0,
                new int[]{0}, enumResolver);
    }

    private static Shape resolve(
            Ast.TypeRef ref, Map<String, Ast.TypeRef> aliases,
            Set<String> activeAliases, int depth, int[] nodes,
            java.util.function.Function<String, Shape> enumResolver) {
        if (depth > MessagePackCollectionCodec.MAX_DEPTH
                || ++nodes[0] > MessagePackCollectionCodec.MAX_NODES) {
            throw new IllegalArgumentException("Serializable wire schema exceeds maximum depth/node quota");
        }
        if (ref.inferArguments()) {
            throw new IllegalArgumentException("Serializable wire schema cannot use inferred type arguments");
        }
        Ast.TypeRef alias = aliases.get(ref.name());
        if (alias != null) {
            if (!ref.arguments().isEmpty()) {
                throw new IllegalArgumentException(
                        "Serializable wire schema needs fully substituted generic alias '" + ref.name() + "'");
            }
            if (!activeAliases.add(ref.name())) {
                throw new IllegalArgumentException("recursive Serializable alias '" + ref.name() + "'");
            }
            try {
                return resolve(alias, aliases, activeAliases, depth + 1, nodes, enumResolver);
            } finally {
                activeAliases.remove(ref.name());
            }
        }
        if (ref.isBorrow() || ref.isRecordType() || ref.isUnion()
                || ref.isNamedParameter() || ref.isSequenceShape()
                || ref.isRepeatExact() || ref.isRepeatMany()) {
            throw new IllegalArgumentException("type has no portable Serializable wire codec: " + ref);
        }
        Shape nominal = enumResolver.apply(ref.name());
        if (nominal != null) {
            if (!ref.arguments().isEmpty())
                throw new IllegalArgumentException("enum payload cannot have generic arguments: " + ref.name());
            return nominal;
        }
        switch (ref.name()) {
            case "bool", "Bool", "boolean" -> {
                requireNoArgs(ref);
                return MessagePackCollectionCodec.bool();
            }
            case "int", "i64" -> {
                requireNoArgs(ref);
                return MessagePackCollectionCodec.int64();
            }
            case "float", "f64" -> {
                requireNoArgs(ref);
                return MessagePackCollectionCodec.float64();
            }
            case "string", "String" -> {
                requireNoArgs(ref);
                return MessagePackCollectionCodec.string();
            }
            case "Tuple", "FixedArray", "FixedList" -> {
                List<Ast.TypeRef> shape = shapeParameters(ref);
                ArrayList<Shape> slots = new ArrayList<>();
                for (Ast.TypeRef item : shape) {
                    if (item.isRepeatExact()) {
                        long count = item.repeatExactCount();
                        if (count > MessagePackCollectionCodec.MAX_ELEMENTS
                                || count > MessagePackCollectionCodec.MAX_NODES - nodes[0]) {
                            throw new IllegalArgumentException(
                                    "fixed Serializable sequence exceeds maximum arity");
                        }
                        for (int n = 0; n < count; n++) {
                            slots.add(resolve(item.arguments().getFirst(), aliases,
                                    activeAliases, depth + 1, nodes, enumResolver));
                        }
                    } else {
                        slots.add(resolve(item, aliases, activeAliases, depth + 1, nodes, enumResolver));
                    }
                    if (slots.size() > MessagePackCollectionCodec.MAX_ELEMENTS) {
                        throw new IllegalArgumentException("fixed Serializable sequence exceeds maximum arity");
                    }
                }
                return switch (ref.name()) {
                    case "Tuple" -> new MessagePackCollectionCodec.Sequence(
                            MessagePackCollectionCodec.SequenceKind.TUPLE, slots, null);
                    case "FixedArray" -> new MessagePackCollectionCodec.Sequence(
                            MessagePackCollectionCodec.SequenceKind.FIXED_ARRAY, slots, null);
                    default -> new MessagePackCollectionCodec.Sequence(
                            MessagePackCollectionCodec.SequenceKind.FIXED_LIST, slots, null);
                };
            }
            case "Array", "List" -> {
                List<Ast.TypeRef> shape = shapeParameters(ref);
                if (shape.size() != 1 || shape.getFirst().isRepeatExact()
                        || shape.getFirst().isRepeatMany()) {
                    throw new IllegalArgumentException(
                            "Serializable homogeneous Array/List requires one element type");
                }
                Shape element = resolve(shape.getFirst(), aliases, activeAliases,
                        depth + 1, nodes, enumResolver);
                return ref.name().equals("Array")
                        ? MessagePackCollectionCodec.array(element)
                        : MessagePackCollectionCodec.list(element);
            }
            default -> throw new IllegalArgumentException(
                    "type has no portable Serializable wire codec: " + ref.name());
        }
    }

    private static List<Ast.TypeRef> shapeParameters(Ast.TypeRef ref) {
        if (ref.arguments().size() == 1 && ref.arguments().getFirst().isSequenceShape()) {
            return ref.arguments().getFirst().arguments();
        }
        if (ref.arguments().stream().anyMatch(arg -> arg.isSequenceShape()
                || arg.isNamedParameter())) {
            throw new IllegalArgumentException(
                    "Serializable collection metadata/rank is not yet a portable wire schema: " + ref);
        }
        if (ref.arguments().isEmpty()) {
            throw new IllegalArgumentException("Serializable sequence needs explicit type arguments");
        }
        return ref.arguments();
    }

    private static void requireNoArgs(Ast.TypeRef ref) {
        if (!ref.arguments().isEmpty()) {
            throw new IllegalArgumentException("Serializable scalar cannot have type arguments: " + ref);
        }
    }
}
