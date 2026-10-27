package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative, syntax-level preflight for scoped transport borrows.
 *
 * This is NOT a complete type/ownership/effect proof: the normal TypeChecker
 * must continue rejecting UsingBorrowStmt until a typed RequestHandle,
 * lifetime-aware view types, effect summaries and evaluator release lowering
 * exist. Failing closed here makes the first source-level negative gates
 * executable without accidentally enabling native-pointer escape.
 */
public final class ScopedRequestBorrowPreflight {
    private ScopedRequestBorrowPreflight() { }

    public static void verify(Ast.UsingBorrowStmt statement) {
        if (!(statement.resource() instanceof Ast.CallExpr call)
                || !(call.callee() instanceof Ast.MemberExpr method)
                || !method.member().equals("borrow")
                || !call.arguments().isEmpty() || call.typeArgumentsPresent()) {
            reject("requires a zero-argument request.borrow() initializer");
        }
        if (statement.binding().equals("_")) reject("requires a named scoped alias");
        Set<String> borrowed = new HashSet<>();
        borrowed.add(statement.binding());
        block(statement.body(), borrowed, statement.binding());
    }

    private static void reject(String message) {
        throw new IllegalArgumentException("scoped request borrow: " + message);
    }

    private static void block(List<Ast.Stmt> body, Set<String> borrowed, String root) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.BindingStmt binding) {
                if (borrowed.contains(binding.name())) {
                    reject("borrowed view alias cannot be shadowed or rebound");
                }
                if (binding.initializer() == null) {
                    reject("uninitialized binding cannot be admitted in a live loan");
                }
                if (expression(binding.initializer(), borrowed, root)) {
                    // Derived path/method and copied views retain the scope lifetime.
                    // Do not mistake an immutable local for an independent String.
                    borrowed.add(binding.name());
                }
            } else if (stmt instanceof Ast.ExprStmt expression) {
                expression(expression.expression(), borrowed, root);
            } else if (stmt instanceof Ast.ReturnStmt returned) {
                if (returned.value() != null && expression(returned.value(), borrowed, root)) {
                    reject("borrowed view or derived value cannot escape through return");
                }
            } else if (stmt instanceof Ast.BlockStmt nested) {
                block(nested.body(), new HashSet<>(borrowed), root);
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    expression(branch.condition(), borrowed, root);
                    block(branch.body(), new HashSet<>(borrowed), root);
                }
                block(conditional.elseBody(), new HashSet<>(borrowed), root);
            } else if (stmt instanceof Ast.UsingBorrowStmt) {
                // Nested loans require the full aliasing and shared/exclusive
                // compatibility analysis; parser acceptance alone is insufficient.
                reject("nested using loans require verified alias compatibility");
            } else if (stmt instanceof Ast.DeferStmt) {
                reject("defer may retain or execute after a request loan ends");
            } else {
                // Covers await-bearing channel/select statements, yield, loops,
                // match/switch, try/finally and future AST nodes. Do not infer
                // safety from an AST form we have not explicitly analyzed.
                reject("unverified statement/control effect while request loan is live");
            }
        }
    }

    /**
     * Returns true when a result is transitively borrowed. Never infer that a
     * method has no suspension effects from its name except the narrow builtins
     * we explicitly reserve for this syntactic preflight.
     */
    private static boolean expression(Ast.Expr expr, Set<String> borrowed, String root) {
        if (expr instanceof Ast.LiteralExpr) return false;
        if (expr instanceof Ast.NameExpr name) return borrowed.contains(name.name());
        if (expr instanceof Ast.CallExpr call) {
            if (!call.typeArgumentsPresent() && call.arguments().isEmpty()
                    && call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr name
                    && name.name().equals(root)
                    && (member.member().equals("method")
                        || member.member().equals("path"))) {
                return true;
            }
            reject("unknown call may suspend, retain, or escape a borrowed request view");
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            return expression(unary.operand(), borrowed, root);
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            boolean left = expression(binary.left(), borrowed, root);
            boolean right = expression(binary.right(), borrowed, root);
            return left || right; // conservatively propagate even scalar results
        }
        if (expr instanceof Ast.ConditionalExpr ternary) {
            boolean condition = expression(ternary.condition(), borrowed, root);
            boolean left = expression(ternary.whenTrue(), borrowed, root);
            boolean right = expression(ternary.whenFalse(), borrowed, root);
            return condition || left || right;
        }
        if (expr instanceof Ast.AwaitExpr || expr instanceof Ast.ChannelOpExpr
                || expr instanceof Ast.DynamicSelectExpr || expr instanceof Ast.RuntimeCallExpr) {
            reject("cannot suspend or perform runtime/channel operations with a live request view");
        }
        if (expr instanceof Ast.AssignExpr) {
            reject("assignment during a scoped loan needs typed alias/lifetime analysis");
        }
        // Lambda, struct/list/tuple storage, cast, match, computed member,
        // FFI and unknown syntax may retain a view or suspend transitively.
        reject("unverified expression or capture may escape a request view");
        return false;
    }
}
