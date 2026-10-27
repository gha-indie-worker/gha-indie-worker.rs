package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Strict, source-derived effect-IR v1 for a closed scalar Oreslang subset.
 *
 * Every emitted "compute" unit corresponds to one admitted scalar AST node;
 * this is an abstract work model, NOT signed provenance, WCET, a resource
 * certificate, or proof about the general Oreslang language.
 *
 * No API is exposed for converting unchecked caller-supplied ASTs.
 */
public final class ScalarEffectIrV1 {
    private ScalarEffectIrV1() { }

    private static final int MAX_FUNCTIONS = 1024;
    private static final int MAX_NODES = 100_000;
    private static final int MAX_DEPTH = 64;

    public static String fromSource(String source) {
        if (source == null || source.length() > 1024 * 1024 ||
                source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024) {
            throw new IllegalArgumentException("scalar effect IR source exceeds byte budget");
        }
        Ast.Program parsed = Parser.parse(source);
        // Reject the bounded subset's depth before recursive compiler passes.
        // The shape preflight grants no type or ownership admission.
        collectEntries(parsed);
        return fromValidated(OresCompiler.analyze(parsed));
    }

    private record Node(Ast.Expr expr, int depth) { }
    private record Entry(String name, int units) { }

    private static String fromValidated(Ast.Program checked) {
        return encode(collectEntries(checked));
    }

    private static List<Entry> collectEntries(Ast.Program checked) {
        if (checked.namespace() != null || !checked.imports().isEmpty() ||
                checked.modules().size() != 1) {
            throw new IllegalArgumentException("scalar effect IR requires one import-free module");
        }
        Ast.ModuleDecl module = checked.modules().getFirst();
        if (!Parser.ROOT_MODULE.equals(module.name()) ||
                !module.annotations().isEmpty() || !module.contracts().isEmpty() ||
                module.declarations().isEmpty() || module.declarations().size() > MAX_FUNCTIONS) {
            throw new IllegalArgumentException("scalar effect IR requires bounded scalar module");
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int totalNodes = 0;
        for (Ast.Decl decl : module.declarations()) {
            if (!(decl instanceof Ast.FunctionDecl fn) ||
                    fn.kind() != Ast.CallableKind.FNC || fn.actorKind() != Ast.ActorKind.NONE ||
                    fn.async() || fn.suspends() || fn.generator() || fn.trapped() ||
                    fn.structural() || fn.nonLexical() ||
                    !fn.genericParameters().isEmpty() || !fn.parameters().isEmpty() ||
                    !fn.annotations().isEmpty() ||
                    fn.returnType() == null || !"int".equals(fn.returnType().name()) ||
                    !fn.returnType().arguments().isEmpty() || fn.returnType().inferArguments() ||
                    fn.body().size() != 1 ||
                    !(fn.body().getFirst() instanceof Ast.ReturnStmt)) {
                throw new IllegalArgumentException("scalar effect IR rejects unmodeled declarations");
            }
            String name = fn.name();
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,255}") ||
                    "main".equals(name) || !names.add(name)) {
                throw new IllegalArgumentException("scalar effect IR rejects malformed or duplicate symbol");
            }
            Ast.ReturnStmt ret = (Ast.ReturnStmt) fn.body().getFirst();
            if (ret.value() == null) throw new IllegalArgumentException("scalar return requires an expression");
            ArrayDeque<Node> stack = new ArrayDeque<>();
            stack.addLast(new Node(ret.value(), 1));
            int units = 0;
            while (!stack.isEmpty()) {
                Node item = stack.removeLast();
                if (item.depth() > MAX_DEPTH || totalNodes >= MAX_NODES) {
                    throw new IllegalArgumentException("scalar effect IR expression budget exceeded");
                }
                totalNodes++;
                units++;
                if (item.expr() instanceof Ast.LiteralExpr literal) {
                    if (!(literal.value() instanceof Long)) {
                        throw new IllegalArgumentException("scalar effect IR requires signed i64 literals");
                    }
                } else if (item.expr() instanceof Ast.BinaryExpr binary) {
                    if (!("+".equals(binary.operator()) || "-".equals(binary.operator()) ||
                            "*".equals(binary.operator())) ||
                            binary.left() == null || binary.right() == null) {
                        throw new IllegalArgumentException("scalar effect IR unsupported operator");
                    }
                    stack.addLast(new Node(binary.right(), item.depth() + 1));
                    stack.addLast(new Node(binary.left(), item.depth() + 1));
                } else {
                    // No call/await/borrow, actor handle, allocation, unary
                    // expression or other unknown effect can be silently ignored.
                    throw new IllegalArgumentException("scalar effect IR unsupported expression");
                }
            }
            entries.add(new Entry(name, units));
        }
        entries.sort(Comparator.comparing(Entry::name));
        return entries;
    }

    private static String encode(List<Entry> entries) {
        StringBuilder json = new StringBuilder("{\"schema_version\":1,\"functions\":[");
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (i != 0) json.append(',');
            json.append("{\"name\":\"").append(entry.name()).append("\",\"operations\":[");
            for (int j = 0; j < entry.units(); j++) {
                if (j != 0) json.append(',');
                json.append("{\"kind\":\"compute\",\"units\":1}");
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }
}
