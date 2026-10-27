package dev.oreslang.ast;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative, lexically scoped inventory of referenced parent names.
 *
 * This inventory is a runtime consistency check over lexical free names.
 * Actual COPY/MOVE authority comes only from the identity-bound admission
 * published by the ownership checker. Nested Tasks, closures,
 * select callbacks and cleanup bodies are traversed transitively; unknown
 * AST forms fail closed. A name is captured only when no lexical local of
 * the same name is visible at that expression.
 */
public final class TaskCaptureNames {
    private TaskCaptureNames() { }

    public static Set<String> referencedIn(List<Ast.Stmt> body) {
        LinkedHashSet<String> references = new LinkedHashSet<>();
        visitBody(body, Set.of(), references);
        return Set.copyOf(references);
    }

    /** New block has a distinct declaration scope; flow retains source order. */
    private static void visitBody(
            List<Ast.Stmt> body, Set<String> outer, Set<String> references) {
        Set<String> locals = new HashSet<>(outer);
        for (Ast.Stmt stmt : body) visitStmt(stmt, locals, references);
    }

    private static void visitStmt(
            Ast.Stmt stmt, Set<String> locals, Set<String> names) {
        if (stmt == null) return;
        if (stmt instanceof Ast.BindingStmt s) {
            // Recursive lexical lambda bindings see their own name, never an
            // unrelated parent slot with the same identifier.
            if (s.initializer() instanceof Ast.LambdaExpr) {
                Set<String> recursive = new HashSet<>(locals);
                recursive.add(s.name());
                visitExpr(s.initializer(), recursive, names);
            } else {
                visitExpr(s.initializer(), locals, names);
            }
            locals.add(s.name());
        } else if (stmt instanceof Ast.DestructureStmt s) {
            visitExpr(s.initializer(), locals, names);
            for (Ast.DestructureBinding b : s.bindings()) {
                if (!b.isDiscard()) locals.add(b.name());
            }
        } else if (stmt instanceof Ast.ReturnStmt s) {
            visitExpr(s.value(), locals, names);
        } else if (stmt instanceof Ast.YieldStmt s) {
            visitExpr(s.value(), locals, names);
        } else if (stmt instanceof Ast.ExprStmt s) {
            visitExpr(s.expression(), locals, names);
        } else if (stmt instanceof Ast.DeferStmt s) {
            visitExpr(s.expression(), locals, names);
        } else if (stmt instanceof Ast.BlockStmt s) {
            visitBody(s.body(), locals, names);
        } else if (stmt instanceof Ast.TaskScopeStmt s) {
            visitBody(s.body(), locals, names);
        } else if (stmt instanceof Ast.IfStmt s) {
            for (Ast.IfBranch branch : s.branches()) {
                visitConditionExpr(branch.condition(), locals, names);
                Set<String> branchLocals = new HashSet<>(locals);
                bindCondition(branch.condition(), branchLocals);
                visitBody(branch.body(), branchLocals, names);
            }
            visitBody(s.elseBody(), locals, names);
        } else if (stmt instanceof Ast.MatchStmt s) {
            visitExpr(s.subject(), locals, names);
            for (Ast.MatchArm arm : s.arms()) {
                Set<String> armLocals = new HashSet<>(locals);
                bindPattern(arm.pattern(), armLocals);
                visitConditionExpr(arm.guard(), armLocals, names);
                visitBody(arm.body(), armLocals, names);
            }
        } else if (stmt instanceof Ast.SwitchStmt s) {
            visitExpr(s.subject(), locals, names);
            for (Ast.SwitchCase arm : s.cases()) {
                for (Ast.Expr value : arm.constants()) visitExpr(value, locals, names);
                visitBody(arm.body(), locals, names);
            }
            visitBody(s.defaultBody(), locals, names);
        } else if (stmt instanceof Ast.TryStmt s) {
            visitBody(s.body(), locals, names);
            Set<String> caught = new HashSet<>(locals);
            if (s.errorName() != null) caught.add(s.errorName());
            visitBody(s.catchBody(), caught, names);
            visitBody(s.finallyBody(), locals, names);
        } else if (stmt instanceof Ast.ForOfStmt s) {
            visitExpr(s.iterable(), locals, names);
            Set<String> iteration = new HashSet<>(locals);
            iteration.add(s.bindingName());
            visitBody(s.body(), iteration, names);
        } else if (stmt instanceof Ast.ForOfDestructureStmt s) {
            visitExpr(s.iterable(), locals, names);
            Set<String> iteration = new HashSet<>(locals);
            for (Ast.DestructureBinding b : s.bindings()) {
                if (!b.isDiscard()) iteration.add(b.name());
            }
            visitBody(s.body(), iteration, names);
        } else if (stmt instanceof Ast.ForStmt s) {
            Set<String> loop = new HashSet<>(locals);
            visitStmt(s.initializer(), loop, names);
            visitExpr(s.condition(), loop, names);
            visitExpr(s.update(), loop, names);
            visitBody(s.body(), loop, names);
        } else if (stmt instanceof Ast.DoWhileStmt s) {
            visitBody(s.body(), locals, names);
            visitExpr(s.condition(), locals, names);
        } else if (stmt instanceof Ast.LoopStmt s) {
            visitBody(s.body(), locals, names);
        } else if (stmt instanceof Ast.SelectStmt s) {
            for (Ast.SelectArm arm : s.arms()) {
                visitExpr(arm.channel(), locals, names);
                visitExpr(arm.value(), locals, names);
                Set<String> armLocals = new HashSet<>(locals);
                if (arm.bindingName() != null) armLocals.add(arm.bindingName());
                visitBody(arm.body(), armLocals, names);
            }
        } else if (!(stmt instanceof Ast.BreakStmt
                || stmt instanceof Ast.ContinueStmt)) {
            throw new IllegalStateException("unsupported Task capture statement: "
                    + stmt.getClass().getName());
        }
    }

    /**
     * Match TypeChecker condition-flow semantics: a refinement binding from
     * the left side of && is local while visiting the right side.
     */
    private static void visitConditionExpr(
            Ast.Expr condition,
            Set<String> locals,
            Set<String> names) {
        if (condition instanceof Ast.BinaryExpr binary
                && binary.operator().equals("&&")) {
            visitConditionExpr(binary.left(), locals, names);
            Set<String> rightLocals = new HashSet<>(locals);
            bindCondition(binary.left(), rightLocals);
            visitConditionExpr(binary.right(), rightLocals, names);
            return;
        }
        visitExpr(condition, locals, names);
    }

    private static void bindCondition(
            Ast.Expr condition,
            Set<String> locals) {
        if (condition instanceof Ast.TypeTestExpr test
                && test.binding() != null) {
            locals.add(test.binding());
        } else if (condition instanceof Ast.PatternTestExpr test) {
            bindPattern(test.pattern(), locals);
        } else if (condition instanceof Ast.BinaryExpr binary
                && binary.operator().equals("&&")) {
            bindCondition(binary.left(), locals);
            bindCondition(binary.right(), locals);
        }
    }

    private static void bindPattern(Ast.Pattern pattern, Set<String> locals) {
        if (pattern instanceof Ast.BindingPattern s) locals.add(s.name());
        else if (pattern instanceof Ast.TypePattern s) {
            if (s.binding() != null) locals.add(s.binding());
        } else if (pattern instanceof Ast.StructuralPattern s) {
            if (s.binding() != null) locals.add(s.binding());
        } else if (pattern instanceof Ast.TuplePattern s) {
            for (Ast.Pattern item : s.elements()) bindPattern(item, locals);
        } else if (pattern instanceof Ast.ConstructorPattern s) {
            for (Ast.Pattern item : s.arguments()) bindPattern(item, locals);
        } else if (!(pattern instanceof Ast.WildcardPattern
                || pattern instanceof Ast.LiteralPattern)) {
            throw new IllegalStateException("unsupported Task capture pattern: "
                    + pattern.getClass().getName());
        }
    }

    private static void visitExpr(
            Ast.Expr expr, Set<String> locals, Set<String> names) {
        if (expr == null || expr instanceof Ast.LiteralExpr) return;
        if (expr instanceof Ast.NameExpr s) {
            if (!locals.contains(s.name())) names.add(s.name());
        } else if (expr instanceof Ast.BinaryExpr s) {
            visitExpr(s.left(), locals, names);
            visitExpr(s.right(), locals, names);
        } else if (expr instanceof Ast.UnaryExpr s) {
            visitExpr(s.operand(), locals, names);
        } else if (expr instanceof Ast.AssignExpr s) {
            visitExpr(s.target(), locals, names);
            visitExpr(s.value(), locals, names);
        } else if (expr instanceof Ast.ConditionalExpr s) {
            visitExpr(s.condition(), locals, names);
            visitExpr(s.whenTrue(), locals, names);
            visitExpr(s.whenFalse(), locals, names);
        } else if (expr instanceof Ast.MatchExpr s) {
            visitExpr(s.subject(), locals, names);
            for (Ast.MatchValueArm arm : s.arms()) {
                Set<String> armLocals = new HashSet<>(locals);
                bindPattern(arm.pattern(), armLocals);
                visitConditionExpr(arm.guard(), armLocals, names);
                visitExpr(arm.value(), armLocals, names);
            }
        } else if (expr instanceof Ast.TypeTestExpr s) {
            visitExpr(s.value(), locals, names);
        } else if (expr instanceof Ast.PatternTestExpr s) {
            visitExpr(s.value(), locals, names);
        } else if (expr instanceof Ast.CastExpr s) {
            visitExpr(s.value(), locals, names);
        } else if (expr instanceof Ast.SpreadExpr s) {
            visitExpr(s.expression(), locals, names);
        } else if (expr instanceof Ast.CallExpr s) {
            visitExpr(s.callee(), locals, names);
            for (Ast.Expr arg : s.arguments()) visitExpr(arg, locals, names);
        } else if (expr instanceof Ast.RuntimeCallExpr s) {
            for (Ast.Expr arg : s.arguments()) visitExpr(arg, locals, names);
        } else if (expr instanceof Ast.MemberExpr s) {
            visitExpr(s.receiver(), locals, names);
        } else if (expr instanceof Ast.IndexExpr s) {
            visitExpr(s.receiver(), locals, names);
            visitExpr(s.index(), locals, names);
        } else if (expr instanceof Ast.NewExpr s) {
            for (Ast.Expr arg : s.arguments()) visitExpr(arg, locals, names);
        } else if (expr instanceof Ast.AwaitExpr s) {
            visitExpr(s.expression(), locals, names);
        } else if (expr instanceof Ast.TaskExpr s) {
            visitBody(s.body(), locals, names);
        } else if (expr instanceof Ast.TrapExpr s) {
            visitExpr(s.expression(), locals, names);
        } else if (expr instanceof Ast.ChannelOpExpr s) {
            visitExpr(s.channel(), locals, names);
            visitExpr(s.value(), locals, names);
            visitBody(s.callbackBody(), locals, names);
        } else if (expr instanceof Ast.DynamicSelectExpr s) {
            visitExpr(s.cases(), locals, names);
        } else if (expr instanceof Ast.ListExpr s) {
            for (Ast.Expr item : s.elements()) visitExpr(item, locals, names);
        } else if (expr instanceof Ast.TupleExpr s) {
            for (Ast.Expr item : s.elements()) visitExpr(item, locals, names);
        } else if (expr instanceof Ast.ObjectExpr s) {
            for (Ast.ObjectField field : s.fields()) visitExpr(field.value(), locals, names);
        } else if (expr instanceof Ast.LambdaExpr s) {
            if (s.nonLexical()) return;
            Set<String> params = new HashSet<>(locals);
            for (Ast.Param param : s.parameters()) params.add(param.name());
            params.add("arguments");
            visitExpr(s.expressionBody(), params, names);
            if (s.blockBody() != null) visitBody(s.blockBody(), params, names);
        } else {
            throw new IllegalStateException("unsupported Task capture expression: "
                    + expr.getClass().getName());
        }
    }
}
