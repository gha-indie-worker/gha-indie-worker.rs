package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.MessagePackCollectionCodec;
import dev.oreslang.runtime.MessagePackCollectionCodec.Shape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Finite, closed, compiler-owned nominal enum wire descriptors.
 * Recursive source enum types (even through a List) are intentionally kept
 * local-only until an explicitly bounded cyclic-schema codec exists.
 */
public final class NestedEnumWireShapes {
    private NestedEnumWireShapes() { }

    public static Shape forPayload(
            Ast.TypeRef payload, Map<String, Ast.EnumDecl> enums) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(enums, "source enums");
        // Enforce source graph validity independently of declaration order.
        return new Builder(Map.copyOf(enums)).shape(payload, 0);
    }

    private static final class Builder {
        private final Map<String, Ast.EnumDecl> declarations;
        private final Map<String, MessagePackCollectionCodec.NestedEnum> cache = new HashMap<>();
        private final Set<String> expanding = new HashSet<>();
        private int nodes;

        Builder(Map<String, Ast.EnumDecl> declarations) {
            this.declarations = declarations;
        }

        Shape shape(Ast.TypeRef source, int depth) {
            if (depth > MessagePackCollectionCodec.MAX_DEPTH
                    || ++nodes > MessagePackCollectionCodec.MAX_NODES)
                throw new IllegalArgumentException("nested enum wire schema exceeds depth/node quota");
            return ActorWireSchema.fromSource(source, Map.of(), name -> {
                if (!declarations.containsKey(name)) return null;
                return enumeration(name, depth + 1);
            });
        }

        MessagePackCollectionCodec.NestedEnum enumeration(String name, int depth) {
            if (depth > MessagePackCollectionCodec.MAX_DEPTH)
                throw new IllegalArgumentException("nested enum wire schema exceeds depth quota");
            var cached = cache.get(name);
            if (cached != null) return cached;
            if (!expanding.add(name))
                throw new IllegalArgumentException(
                        "recursive enum wire schema requires explicit bounded recursion codec: " + name);
            try {
                Ast.EnumDecl source = declarations.get(name);
                List<MessagePackCollectionCodec.EnumCaseShape> cases = new ArrayList<>();
                if (source.cases().isEmpty() || source.cases().size() > 4096)
                    throw new IllegalArgumentException("nested enum must have 1..4096 cases");
                for (Ast.EnumCase entry : source.cases()) {
                    if (++nodes > MessagePackCollectionCodec.MAX_NODES)
                        throw new IllegalArgumentException("nested enum wire schema exceeds node quota");
                    Shape member = entry.payload() == null ? null : shape(entry.payload(), depth + 1);
                    cases.add(new MessagePackCollectionCodec.EnumCaseShape(
                            entry.name(), entry.tag(), member));
                }
                var complete = new MessagePackCollectionCodec.NestedEnum(name, cases);
                cache.put(name, complete);
                return complete;
            } finally {
                expanding.remove(name);
            }
        }
    }
}
