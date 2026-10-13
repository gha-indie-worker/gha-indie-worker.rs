package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.MessagePackCollectionCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates nominal enum payload dependencies before attempting to construct
 * a wire schema. An enum is a value type, not a metatype. A case with a Foo
 * payload contains a value of Foo; it does not hold Foo's type descriptor.
 *
 * This graph pass must be independent of source-declaration order and must
 * not derive or admit an unimplemented nested-enum binary codec.
 */
public final class EnumPayloadDependencies {
    private EnumPayloadDependencies() { }

    public record Reference(String from, String to, String caseName,
                            boolean byValue) { }

    public static List<Reference> check(Ast.Program program) {
        Objects.requireNonNull(program);
        Map<String, Ast.EnumDecl> declarations = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.EnumDecl enumeration
                        && declarations.putIfAbsent(enumeration.name(), enumeration) != null) {
                    throw new IllegalArgumentException(
                            "duplicate enum declaration '" + enumeration.name() + "'");
                }
            }
        }

        Map<String, Set<String>> inlineDependencies = new LinkedHashMap<>();
        for (String owner : declarations.keySet()) {
            inlineDependencies.put(owner, new LinkedHashSet<>());
        }
        List<Reference> references = new ArrayList<>();
        for (Ast.EnumDecl enumeration : declarations.values()) {
            for (Ast.EnumCase variant : enumeration.cases()) {
                if (variant.payload() != null) {
                    collect(variant.payload(), enumeration.name(), variant.name(),
                            true, declarations.keySet(), references, inlineDependencies,
                            0, new int[]{0});
                }
            }
        }
        // The graph is finite and evaluated without recursively expanding
        // payload types, so mutually recursive enums cannot overflow the JVM
        // parser or schema descriptor stack.
        Map<String, Integer> state = new HashMap<>();
        List<String> path = new ArrayList<>();
        for (String name : declarations.keySet()) {
            visit(name, inlineDependencies, state, path);
        }
        return List.copyOf(references);
    }

    private static void collect(Ast.TypeRef type, String owner, String caseName,
                                boolean byValue, Set<String> enums,
                                List<Reference> references,
                                Map<String, Set<String>> graph,
                                int depth, int[] nodes) {
        if (depth > MessagePackCollectionCodec.MAX_DEPTH
                || ++nodes[0] > MessagePackCollectionCodec.MAX_NODES) {
            throw new IllegalArgumentException(
                    "enum payload dependency descriptor exceeds depth/node quota");
        }
        if (type.name().equals("Box")) {
            // Box is only a finite-sized *indirection type constructor*.
            // Until its runtime is implemented, never infer size breakage
            // from Box<> / Box<Foo, Bar> / boxed metadata.
            if (type.inferArguments() || type.arguments().size() != 1
                    || type.arguments().getFirst().isNamedParameter()
                    || type.arguments().getFirst().isSequenceShape()) {
                throw new IllegalArgumentException(
                        "Box<T> requires exactly one explicit element type");
            }
        }
        if (enums.contains(type.name())) {
            if (!type.arguments().isEmpty()) {
                throw new IllegalArgumentException(
                        "non-generic enum '" + type.name() + "' cannot take type arguments");
            }
            references.add(new Reference(owner, type.name(), caseName, byValue));
            if (byValue) graph.get(owner).add(type.name());
            return;
        }
        // Homogeneous lists/arrays and Box are indirection for SIZE checking;
        // their binary encoders are not automatically granted enum support.
        // FixedArray, Tuple and Option remain inline regardless of element
        // count or whether another variant in the union is payloadless.
        boolean childByValue = byValue
                && !type.name().equals("List")
                && !type.name().equals("Array")
                && !type.name().equals("Box");
        for (Ast.TypeRef arg : type.arguments()) {
            collect(arg, owner, caseName, childByValue, enums, references, graph,
                    depth + 1, nodes);
        }
    }

    private static void visit(String name, Map<String, Set<String>> graph,
                              Map<String, Integer> state, List<String> path) {
        int current = state.getOrDefault(name, 0);
        if (current == 2) return;
        if (current == 1) {
            int start = path.indexOf(name);
            List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
            cycle.add(name);
            throw new IllegalArgumentException(
                    "unboxed recursive enum payload: " + String.join(" -> ", cycle)
                    + "; use explicit indirection, never implicit enum references");
        }
        if (path.size() >= 256) {
            throw new IllegalArgumentException(
                    "enum inline dependency chain exceeds maximum depth of 256");
        }
        state.put(name, 1);
        path.add(name);
        for (String dependency : graph.get(name)) {
            visit(dependency, graph, state, path);
        }
        path.remove(path.size() - 1);
        state.put(name, 2);
    }
}
