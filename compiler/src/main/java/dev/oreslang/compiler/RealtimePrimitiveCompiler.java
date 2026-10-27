package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import javax.lang.model.SourceVersion;
import javax.tools.ToolProvider;

/**
 * A deliberately narrow, closed-world source-to-Java-bytecode proof of concept.
 *
 * This is a SOURCE admission filter and portable backend for a pure primitive
 * subset, NOT an RTSJ NoHeapRealtimeThread verifier or a timing certificate.
 * Only a caller which has already passed TypeChecker and OwnershipChecker may
 * treat the report as a compiler-derived source property. Post-lowering bytecode
 * admission and target-specific runtime qualification are separate mandatory gates.
 */
public final class RealtimePrimitiveCompiler {
    public static final String CLASS_NAME = "RtPrimitiveModule";
    private static final int MAX_FUNCTIONS = 128;
    private static final int MAX_PARAMS = 32;
    private static final int MAX_STMTS = 4096;
    private static final int MAX_REACHABLE_CALL_DEPTH = 64;
    private static final int MAX_AST_NESTING = 64;
    private int visitedStatements;
    private int currentNesting;

    private RealtimePrimitiveCompiler() { }

    public record Budget(long maxSourceSteps, int maxCallDepth) {
        public Budget {
            if (maxSourceSteps < 1 || maxCallDepth < 1) {
                throw new IllegalArgumentException("real-time budgets must be positive");
            }
        }
    }

    public record SourceReport(String entry, long maxSourceSteps, int maxCallDepth,
                               int reachableFunctions, String assurance) { }

    public record Artifact(Path classFile, String bytecodeSha256, SourceReport sourceReport) { }

    private record Cost(long steps, int depth) { }
    private final Map<String, Ast.FunctionDecl> functions = new LinkedHashMap<>();
    private final Map<String, Cost> costs = new HashMap<>();
    private final Set<String> active = new HashSet<>();
    private final LinkedHashSet<String> reachable = new LinkedHashSet<>();

    private RealtimePrimitiveCompiler(Ast.Program program) {
        if (!program.imports().isEmpty() || program.modules().size() != 1) {
            throw invalid("primitive real-time subset requires one closed module without imports");
        }
        Ast.ModuleDecl module = program.modules().getFirst();
        if (!module.annotations().isEmpty() || !module.contracts().isEmpty()) {
            throw invalid("module annotations/contracts have not been qualified for RT");
        }
        if (module.declarations().size() > MAX_FUNCTIONS) {
            throw invalid("too many functions for bounded real-time compilation");
        }
        for (Ast.Decl declaration : module.declarations()) {
            if (!(declaration instanceof Ast.FunctionDecl fn)) {
                throw invalid("only ordinary free functions are admitted");
            }
            validateFunction(fn);
            if (functions.putIfAbsent(key(fn.name(), fn.parameters().size()), fn) != null) {
                throw invalid("ambiguous function overload: " + fn.name());
            }
        }
    }

    static SourceReport analyze(Ast.Program checkedProgram, String entry,
                                       int arity, Budget budget) {
        RealtimePrimitiveCompiler compiler = new RealtimePrimitiveCompiler(checkedProgram);
        return compiler.report(entry, arity, budget);
    }

    /** Emits ordinary Java 8 classfile bytecode; does not load or execute it. */
    static Artifact compile(Ast.Program checkedProgram, String entry, int arity,
                                   Budget budget, Path outputDirectory) throws IOException {
        RealtimePrimitiveCompiler compiler = new RealtimePrimitiveCompiler(checkedProgram);
        SourceReport report = compiler.report(entry, arity, budget);
        if (outputDirectory == null) throw invalid("output directory is required");
        Files.createDirectories(outputDirectory);
        Path directory = outputDirectory.toRealPath();
        Path javaFile = directory.resolve(CLASS_NAME + ".java");
        Files.writeString(javaFile, compiler.javaSource(), StandardCharsets.UTF_8);
        var javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw invalid("a full JDK with javac is required at build time");
        int exit = javac.run(null, null, null,
                "--release", "8", "-g:none", "-proc:none",
                "-d", directory.toString(), javaFile.toString());
        if (exit != 0) throw invalid("generated Java failed verification by javac");
        Path result = directory.resolve(CLASS_NAME + ".class");
        if (!Files.isRegularFile(result)) throw invalid("javac produced no classfile");
        return new Artifact(result, sha256(Files.readAllBytes(result)), report);
    }

    private SourceReport report(String entry, int arity, Budget budget) {
        if (budget == null) throw invalid("budget is required");
        validateIdentifier(entry);
        Cost cost = analyzeFunction(key(entry, arity));
        if (cost.steps() > budget.maxSourceSteps() || cost.depth() > budget.maxCallDepth()) {
            throw invalid("real-time resource budget exceeded: source steps "
                    + cost.steps() + ", call depth " + cost.depth());
        }
        return new SourceReport(entry, cost.steps(), cost.depth(), reachable.size(),
                "SOURCE_AST_SUBSET_ONLY;BYTECODE_AND_WCET_UNVERIFIED");
    }

    private Cost analyzeFunction(String id) {
        Cost cached = costs.get(id);
        if (cached != null) return cached;
        Ast.FunctionDecl fn = functions.get(id);
        if (fn == null) throw invalid("unknown or external function: " + id);
        if (!active.add(id)) throw invalid("recursive calls are not admitted: " + id);
        if (active.size() > MAX_REACHABLE_CALL_DEPTH) throw invalid("call depth exceeds compiler safety ceiling");
        reachable.add(id);
        try {
            Set<String> locals = new HashSet<>();
            for (Ast.Param param : fn.parameters()) {
                if (!locals.add(param.name())) throw invalid("duplicate parameter: " + param.name());
            }
            if (!alwaysReturns(fn.body(), 0)) throw invalid("function can complete without returning: " + id);
            Cost body = statements(fn.body(), locals);
            Cost total = new Cost(plus(1, body.steps()), body.depth());
            costs.put(id, total);
            return total;
        } finally {
            active.remove(id);
        }
    }

    /**
     * Every accepted non-void function must return on every path. Reject
     * unreachable trailing statements: they cannot silently hide effects.
     */
    private static boolean alwaysReturns(List<Ast.Stmt> statements, int nesting) {
        if (nesting > MAX_AST_NESTING) throw invalid("control flow nesting too deep");
        boolean terminated = false;
        for (Ast.Stmt stmt : statements) {
            if (terminated) throw invalid("unreachable statement after guaranteed return");
            if (stmt instanceof Ast.ReturnStmt) {
                terminated = true;
            } else if (stmt instanceof Ast.IfStmt cond) {
                if (cond.branches().isEmpty()) throw invalid("empty conditional");
                boolean branchesReturn = true;
                for (Ast.IfBranch arm : cond.branches()) {
                    branchesReturn &= alwaysReturns(arm.body(), nesting + 1);
                }
                boolean elseReturns = !cond.elseBody().isEmpty() && alwaysReturns(cond.elseBody(), nesting + 1);
                terminated = branchesReturn && elseReturns;
            }
        }
        return terminated;
    }

    private Cost statements(List<Ast.Stmt> statements, Set<String> locals) {
        long steps = 0;
        int depth = 1;
        for (Ast.Stmt stmt : statements) {
            if (++visitedStatements > MAX_STMTS) throw invalid("source AST exceeds statement budget");
            Cost cost = statement(stmt, locals);
            steps = plus(steps, cost.steps());
            depth = Math.max(depth, cost.depth());
        }
        return new Cost(steps, depth);
    }

    private Cost statement(Ast.Stmt stmt, Set<String> locals) {
        if (stmt instanceof Ast.ReturnStmt ret) {
            if (ret.value() == null) throw invalid("only non-void returns are admitted");
            return added(1, expression(ret.value(), locals));
        }
        if (stmt instanceof Ast.BindingStmt bind) {
            if (!primitive(bind.declaredType()) || bind.initializer() == null
                    || bind.mutableReferent() || bind.inferredTypeName() != null) {
                throw invalid("only initialized primitive local bindings are admitted");
            }
            validateIdentifier(bind.name());
            Cost value = expression(bind.initializer(), locals);
            if (!locals.add(bind.name())) throw invalid("duplicate local: " + bind.name());
            return added(1, value);
        }
        if (stmt instanceof Ast.IfStmt branch) {
            long tests = 1;
            long mostExpensiveBody = 0;
            int depth = 1;
            for (Ast.IfBranch arm : branch.branches()) {
                Cost cond = expression(arm.condition(), locals);
                Cost body = statements(arm.body(), new HashSet<>(locals));
                tests = plus(tests, cond.steps());
                mostExpensiveBody = Math.max(mostExpensiveBody, body.steps());
                depth = Math.max(depth, Math.max(cond.depth(), body.depth()));
            }
            Cost other = statements(branch.elseBody(), new HashSet<>(locals));
            mostExpensiveBody = Math.max(mostExpensiveBody, other.steps());
            depth = Math.max(depth, other.depth());
            return new Cost(plus(tests, mostExpensiveBody), depth);
        }
        throw invalid("unbounded or unverified statement: " + stmt.getClass().getSimpleName());
    }

    private Cost expression(Ast.Expr expr, Set<String> locals) {
        if (++currentNesting > MAX_AST_NESTING) throw invalid("source AST expression nesting too deep");
        try {
            return expressionInner(expr, locals);
        } finally {
            currentNesting--;
        }
    }

    private Cost expressionInner(Ast.Expr expr, Set<String> locals) {
        if (expr instanceof Ast.LiteralExpr lit) {
            Object v = lit.value();
            if (!(v instanceof Boolean) && !(v instanceof Byte) && !(v instanceof Short)
                    && !(v instanceof Integer) && !(v instanceof Long l
                    && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE)) {
                throw invalid("only i32/bool constants are admitted");
            }
            return new Cost(1, 1);
        }
        if (expr instanceof Ast.NameExpr name) {
            if (!locals.contains(name.name())) throw invalid("unbound primitive local: " + name.name());
            return new Cost(1, 1);
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            if (!Set.of("+", "-", "*", "==", "!=", "<", "<=", ">", ">=",
                    "&&", "||", "&", "|", "^").contains(binary.operator())) {
                throw invalid("operator may throw or lacks bounded primitive semantics: " + binary.operator());
            }
            return combined(1, expression(binary.left(), locals), expression(binary.right(), locals));
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            if (!Set.of("-", "!", "~").contains(unary.operator())) {
                throw invalid("unverified unary operator: " + unary.operator());
            }
            return added(1, expression(unary.operand(), locals));
        }
        if (expr instanceof Ast.CallExpr call) {
            if (!(call.callee() instanceof Ast.NameExpr name)
                    || call.typeArgumentsPresent() || !call.typeArguments().isEmpty()) {
                throw invalid("dynamic/generic/external dispatch is forbidden");
            }
            long steps = 1;
            int depth = 1;
            for (Ast.Expr argument : call.arguments()) {
                Cost c = expression(argument, locals);
                steps = plus(steps, c.steps());
                depth = Math.max(depth, c.depth());
            }
            Cost callee = analyzeFunction(key(name.name(), call.arguments().size()));
            return new Cost(plus(steps, callee.steps()), Math.max(depth, 1 + callee.depth()));
        }
        throw invalid("reference/heap/suspend/unknown expression: " + expr.getClass().getSimpleName());
    }

    private static Cost combined(long own, Cost first, Cost second) {
        return new Cost(plus(plus(own, first.steps()), second.steps()),
                Math.max(first.depth(), second.depth()));
    }
    private static Cost added(long own, Cost inner) {
        return new Cost(plus(own, inner.steps()), inner.depth());
    }
    private static long plus(long a, long b) {
        try { return Math.addExact(a, b); }
        catch (ArithmeticException overflow) { throw invalid("resource bound overflow"); }
    }
    private static String key(String name, int arity) { return name + "/" + arity; }

    private static boolean primitive(Ast.TypeRef t) {
        return t != null && t.arguments().isEmpty() && !t.inferArguments()
                && (t.name().equals("i32") || t.name().equals("bool"));
    }
    private static String javaType(Ast.TypeRef t) {
        if (!primitive(t)) throw invalid("reference/non-i32 type cannot pass the primitive boundary");
        return t.name().equals("bool") ? "boolean" : "int";
    }
    private static void validateIdentifier(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z_0-9]*")
                || SourceVersion.isKeyword(name) || Set.of("var", "record", "yield", "_").contains(name)) {
            throw invalid("invalid portable Java identifier: " + name);
        }
    }
    private static void validateFunction(Ast.FunctionDecl fn) {
        validateIdentifier(fn.name());
        if (fn.async() || fn.suspends() || fn.generator() || fn.structural()
                || fn.nonLexical() || fn.trapped() || fn.actorKind() != Ast.ActorKind.NONE
                || !fn.genericParameters().isEmpty() || !fn.annotations().isEmpty()) {
            throw invalid("only synchronous, nongeneric, non-actor free functions are admitted");
        }
        javaType(fn.returnType());
        if (fn.parameters().size() > MAX_PARAMS) throw invalid("too many parameters");
        for (Ast.Param param : fn.parameters()) {
            validateIdentifier(param.name());
            javaType(param.type());
            if (param.mutable() || param.structural()) throw invalid("mutable/structural params forbidden");
        }
    }

    private String javaSource() {
        StringBuilder out = new StringBuilder("final class ").append(CLASS_NAME).append(" {\n");
        for (String id : reachable) {
            Ast.FunctionDecl fn = functions.get(id);
            out.append("  static ").append(javaType(fn.returnType())).append(" ")
                    .append(fn.name()).append("(");
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param p = fn.parameters().get(i);
                if (i > 0) out.append(",");
                out.append(javaType(p.type())).append(" ").append(p.name());
            }
            out.append(") {\n");
            emitStatements(out, fn.body(), "    ");
            out.append("  }\n");
        }
        return out.append("}\n").toString();
    }

    private static void emitStatements(StringBuilder out, List<Ast.Stmt> body, String indent) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt ret) {
                out.append(indent).append("return ").append(emitExpr(ret.value())).append(";\n");
            } else if (stmt instanceof Ast.BindingStmt bind) {
                out.append(indent).append(javaType(bind.declaredType())).append(" ")
                        .append(bind.name()).append(" = ").append(emitExpr(bind.initializer()))
                        .append(";\n");
            } else if (stmt instanceof Ast.IfStmt branch) {
                for (int i = 0; i < branch.branches().size(); i++) {
                    Ast.IfBranch arm = branch.branches().get(i);
                    out.append(indent).append(i == 0 ? "if (" : "else if (")
                            .append(emitExpr(arm.condition())).append(") {\n");
                    emitStatements(out, arm.body(), indent + "  ");
                    out.append(indent).append("} ");
                }
                if (branch.elseBody().isEmpty()) out.append("\n");
                else {
                    out.append("else {\n");
                    emitStatements(out, branch.elseBody(), indent + "  ");
                    out.append(indent).append("}\n");
                }
            } else {
                throw invalid("internal error: admitted statement was not lowered");
            }
        }
    }

    private static String emitExpr(Ast.Expr expr) {
        if (expr instanceof Ast.LiteralExpr lit) return String.valueOf(lit.value());
        if (expr instanceof Ast.NameExpr name) return name.name();
        if (expr instanceof Ast.BinaryExpr b) {
            return "(" + emitExpr(b.left()) + " " + b.operator() + " " + emitExpr(b.right()) + ")";
        }
        if (expr instanceof Ast.UnaryExpr u) return "(" + u.operator() + emitExpr(u.operand()) + ")";
        if (expr instanceof Ast.CallExpr c && c.callee() instanceof Ast.NameExpr name) {
            List<String> args = new ArrayList<>();
            for (Ast.Expr arg : c.arguments()) args.add(emitExpr(arg));
            return name.name() + "(" + String.join(", ", args) + ")";
        }
        throw invalid("internal error: admitted expression was not lowered");
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static IllegalArgumentException invalid(String text) {
        return new IllegalArgumentException("RT-PRIMITIVE: " + text);
    }
}
