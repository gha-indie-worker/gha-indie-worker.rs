package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure AST analysis for lexical closure capture candidates.
 *
 * The result contains names that are free relative to the lambda itself.
 * Runtime/codegen may then intersect those names with activation-local slots;
 * module/global/import/builtin names are intentionally left uncaptured.
 *
 * Nested lexical lambdas are traversed transitively because a returned outer
 * closure must retain any grandparent locals that a nested closure may capture
 * later. An explicit nlex lambda is a capture barrier and stops traversal.
 */
public final class ClosureCaptureAnalyzer {
    private ClosureCaptureAnalyzer() { }

    public static CapturePlan analyze(Ast.LambdaExpr lambda) {
        LinkedHashSet<String> free = new LinkedHashSet<>();
        LinkedHashSet<String> bound = new LinkedHashSet<>();
        for (Ast.Param parameter : lambda.parameters()) bound.add(parameter.name());

        if (lambda.expressionBody() != null) {
            scanExpr(lambda.expressionBody(), bound, free);
        }
        if (lambda.blockBody() != null) {
            scanStatements(lambda.blockBody(), bound, free);
        }
        return new CapturePlan(new ArrayList<>(free));
    }

    private static void scanStatements(List<Ast.Stmt> statements, Set<String> inherited, Set<String> free) {
        LinkedHashSet<String> bound = new LinkedHashSet<>(inherited);

        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                if (binding.initializer() instanceof Ast.LambdaExpr nested) {
                    // Lambda-valued bindings are recursive in the evaluator:
                    // reserve the slot before the closure is built.
                    LinkedHashSet<String> recursive = new LinkedHashSet<>(bound);
                    recursive.add(binding.name());
                    scanExpr(nested, recursive, free);
                } else {
                    scanExpr(binding.initializer(), bound, free);
                }
                bound.add(binding.name());
                continue;
            }

            if (stmt instanceof Ast.DestructureStmt destructure) {
                scanExpr(destructure.initializer(), bound, free);
                for (Ast.DestructureBinding binding : destructure.bindings()) bound.add(binding.name());
                continue;
            }

            if (stmt instanceof Ast.ReturnStmt returned) {
                if (returned.value() != null) scanExpr(returned.value(), bound, free);
                continue;
            }

            if (stmt instanceof Ast.ExprStmt expression) {
                scanExpr(expression.expression(), bound, free);
                continue;
            }

            if (stmt instanceof Ast.DeferStmt deferred) {
                scanExpr(deferred.expression(), bound, free);
                continue;
            }

            if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    scanExpr(branch.condition(), bound, free);
                    scanStatements(branch.body(), new LinkedHashSet<>(bound), free);
                }
                scanStatements(conditional.elseBody(), new LinkedHashSet<>(bound), free);
                continue;
            }

            if (stmt instanceof Ast.TryStmt attempted) {
                scanStatements(attempted.body(), new LinkedHashSet<>(bound), free);

                LinkedHashSet<String> caught = new LinkedHashSet<>(bound);
                caught.add(attempted.errorName());
                scanStatements(attempted.catchBody(), caught, free);

                scanStatements(attempted.finallyBody(), new LinkedHashSet<>(bound), free);
                continue;
            }

            if (stmt instanceof Ast.ForOfStmt loop) {
                scanExpr(loop.iterable(), bound, free);
                LinkedHashSet<String> loopBound = new LinkedHashSet<>(bound);
                loopBound.add(loop.bindingName());
                scanStatements(loop.body(), loopBound, free);
                continue;
            }

            if (stmt instanceof Ast.ForStmt loop) {
                LinkedHashSet<String> loopBound = new LinkedHashSet<>(bound);
                if (loop.initializer() != null) {
                    scanStatementInLoop(loop.initializer(), loopBound, free);
                }
                if (loop.condition() != null) scanExpr(loop.condition(), loopBound, free);
                scanStatements(loop.body(), new LinkedHashSet<>(loopBound), free);
                if (loop.update() != null) scanExpr(loop.update(), loopBound, free);
            }
        }
    }

    private static void scanStatementInLoop(Ast.Stmt stmt, Set<String> bound, Set<String> free) {
        if (stmt instanceof Ast.BindingStmt binding) {
            scanExpr(binding.initializer(), bound, free);
            bound.add(binding.name());
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) {
            scanExpr(expression.expression(), bound, free);
            return;
        }
        scanStatements(List.of(stmt), bound, free);
    }

    private static void scanExpr(Ast.Expr expr, Set<String> bound, Set<String> free) {
        if (expr == null) return;

        if (expr instanceof Ast.NameExpr name) {
            if (!bound.contains(name.name())) free.add(name.name());
            return;
        }

        if (expr instanceof Ast.BinaryExpr binary) {
            scanExpr(binary.left(), bound, free);
            scanExpr(binary.right(), bound, free);
            return;
        }

        if (expr instanceof Ast.UnaryExpr unary) {
            scanExpr(unary.operand(), bound, free);
            return;
        }

        if (expr instanceof Ast.AssignExpr assignment) {
            scanExpr(assignment.target(), bound, free);
            scanExpr(assignment.value(), bound, free);
            return;
        }

        if (expr instanceof Ast.ConditionalExpr conditional) {
            scanExpr(conditional.condition(), bound, free);
            scanExpr(conditional.whenTrue(), bound, free);
            scanExpr(conditional.whenFalse(), bound, free);
            return;
        }

        if (expr instanceof Ast.CallExpr call) {
            scanExpr(call.callee(), bound, free);
            for (Ast.Expr argument : call.arguments()) scanExpr(argument, bound, free);
            return;
        }

        if (expr instanceof Ast.MemberExpr member) {
            scanExpr(member.receiver(), bound, free);
            return;
        }

        if (expr instanceof Ast.IndexExpr indexed) {
            scanExpr(indexed.receiver(), bound, free);
            scanExpr(indexed.index(), bound, free);
            return;
        }

        if (expr instanceof Ast.NewExpr created) {
            for (Ast.Expr argument : created.arguments()) scanExpr(argument, bound, free);
            return;
        }

        if (expr instanceof Ast.AwaitExpr awaited) {
            scanExpr(awaited.expression(), bound, free);
            return;
        }

        if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr element : list.elements()) scanExpr(element, bound, free);
            return;
        }

        if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr element : tuple.elements()) scanExpr(element, bound, free);
            return;
        }

        if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) scanExpr(field.value(), bound, free);
            return;
        }

        if (expr instanceof Ast.LambdaExpr nested) {
            if (nested.nonLexical()) return;

            LinkedHashSet<String> nestedBound = new LinkedHashSet<>(bound);
            for (Ast.Param parameter : nested.parameters()) nestedBound.add(parameter.name());

            if (nested.expressionBody() != null) scanExpr(nested.expressionBody(), nestedBound, free);
            if (nested.blockBody() != null) scanStatements(nested.blockBody(), nestedBound, free);
        }
    }

    public record CapturePlan(List<String> names) {
        public CapturePlan {
            names = List.copyOf(names);
        }

        public boolean requiresEnvironment() {
            return !names.isEmpty();
        }
    }
}
