package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exit-cleanup contract shared by compiled and interpreted entry points.
 *
 * <p>A finally block cannot return from the surrounding callable. A defer
 * may mutate a returned mutable object, but cannot rebind the local whose
 * value is being returned. The pending result is evaluated before defers run.
 * This deliberately tracks binding identities rather than spelling, so
 * shadowed names do not produce false conflicts.</p>
 */
public final class ReturnCleanupChecker {
    private ReturnCleanupChecker() { }

    private static final class Binding {
        final String name;
        final Ast.LambdaExpr closure;
        final Scope definingScope;
        boolean reassigned;
        Binding(String name, Ast.LambdaExpr closure, Scope definingScope) {
            this.name = name;
            this.closure = closure;
            this.definingScope = definingScope;
        }
    }

    private static final class Scope {
        final Scope parent;
        final Map<String, Binding> locals = new HashMap<>();
        Scope(Scope parent) { this.parent = parent; }
        void define(String name) { define(name, null, this); }
        void define(String name, Ast.LambdaExpr closure) {
            define(name, closure, this);
        }
        void define(String name, Ast.LambdaExpr closure, Scope definingScope) {
            locals.put(name, new Binding(name, closure, definingScope));
        }
        Binding resolve(String name) {
            Binding local = locals.get(name);
            return local != null ? local : parent == null ? null : parent.resolve(name);
        }
    }

    private static final class State {
        final Set<Binding> returned = new HashSet<>();
        final Set<Binding> reboundInDefer = new HashSet<>();
        final Set<Binding> scannedDeferredCallbacks = new HashSet<>();
        // Track deferred calls independently of the first scan: a callback
        // may be reassigned *after* registration but before scope exit.
        final Set<Binding> deferredLocalCallbacks = new HashSet<>();
        int loopsInsideFinally;
    }

    public static Ast.Program check(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    callable(fn.body(), fn.parameters(), null);
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    if (klass.constructor() != null) {
                        callable(klass.constructor().body(), klass.constructor().parameters(), null);
                    }
                    for (Ast.MethodDecl method : klass.methods()) {
                        callable(method.body(), method.parameters(), null);
                    }
                }
            }
        }
        return program;
    }

    private static void callable(List<Ast.Stmt> statements, List<Ast.Param> params, Scope parent) {
        Scope scope = new Scope(parent);
        for (Ast.Param param : params) scope.define(param.name());
        State state = new State();
        statements(statements, scope, false, false, true, state);
        // Deferred callbacks are invoked on scope exit, not when registered.
        // Earlier static scans cannot authorize a later-replaced closure.
        for (Binding callback : state.deferredLocalCallbacks) {
            if (callback.reassigned) {
                throw new IllegalArgumentException(
                        "defer cannot verify reassigned or dynamic local callback '" +
                        callback.name + "'; use a statically known lexical callback");
            }
        }
        for (Binding binding : state.returned) {
            if (state.reboundInDefer.contains(binding)) {
                throw new IllegalArgumentException(
                        "defer cannot reassign returned binding '" + binding.name
                        + "'; mutate its referent instead");
            }
        }
    }

    private static void statements(List<Ast.Stmt> statements, Scope parent,
                                   boolean inFinally, boolean inDefer,
                                   boolean trackReturns, State state) {
        Scope scope = new Scope(parent);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                expression(binding.initializer(), scope, inDefer, state);
                Ast.LambdaExpr cleanupClosure = null;
                Scope definingScope = scope;
                boolean unknownAlias = false;
                if (binding.initializer() instanceof Ast.LambdaExpr lambda) {
                    cleanupClosure = lambda;
                } else if (binding.initializer() instanceof Ast.NameExpr alias) {
                    Binding source = scope.resolve(alias.name());
                    if (source != null) {
                        cleanupClosure = source.closure;
                        definingScope = source.definingScope;
                        unknownAlias = source.reassigned;
                    }
                }
                // Aliases retain the closure's original lexical capture
                // environment, not the potentially shadowing call site.
                scope.define(binding.name(), cleanupClosure, definingScope);
                if (unknownAlias) scope.resolve(binding.name()).reassigned = true;
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                expression(destructure.initializer(), scope, inDefer, state);
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (!binding.isDiscard()) scope.define(binding.name());
                }
            } else if (stmt instanceof Ast.ReturnStmt ret) {
                if (inFinally) {
                    throw new IllegalArgumentException(
                            "return is not allowed inside finally; finally is cleanup-only");
                }
                if (trackReturns && ret.value() instanceof Ast.NameExpr name) {
                    Binding returned = scope.resolve(name.name());
                    if (returned != null) state.returned.add(returned);
                }
                expression(ret.value(), scope, inDefer, state);
            } else if (stmt instanceof Ast.YieldStmt yielded) {
                expression(yielded.value(), scope, inDefer, state);
            } else if (stmt instanceof Ast.DeferStmt deferred) {
                // A direct deferred lambda runs on scope exit. Creating other
                // nested lambdas inside it does not execute their bodies.
                if (deferred.expression() instanceof Ast.LambdaExpr lambda) {
                    expression(lambda, scope, false, state);
                    deferredLambda(lambda, scope, state);
                } else {
                    expression(deferred.expression(), scope, true, state);
                }
            } else if (stmt instanceof Ast.ExprStmt expression) {
                expression(expression.expression(), scope, inDefer, state);
            } else if (stmt instanceof Ast.BreakStmt || stmt instanceof Ast.ContinueStmt) {
                // A break/continue targeting a loop outside finally would
                // discard a pending return just like a return in finally.
                if (inFinally && state.loopsInsideFinally == 0) {
                    String kind = stmt instanceof Ast.BreakStmt ? "break" : "continue";
                    throw new IllegalArgumentException(
                            kind + " cannot escape finally; finally is cleanup-only");
                }
            } else if (stmt instanceof Ast.BlockStmt block) {
                statements(block.body(), scope, inFinally, inDefer, trackReturns, state);
            } else if (stmt instanceof Ast.TaskScopeStmt taskScope) {
                statements(taskScope.body(), scope, inFinally, inDefer, trackReturns, state);
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    expression(branch.condition(), scope, inDefer, state);
                    statements(branch.body(), scope, inFinally, inDefer, trackReturns, state);
                }
                statements(conditional.elseBody(), scope, inFinally, inDefer, trackReturns, state);
            } else if (stmt instanceof Ast.MatchStmt matched) {
                expression(matched.subject(), scope, inDefer, state);
                for (Ast.MatchArm arm : matched.arms()) {
                    Scope armScope = new Scope(scope);
                    bindPattern(arm.pattern(), armScope);
                    expression(arm.guard(), armScope, inDefer, state);
                    statements(arm.body(), armScope, inFinally, inDefer, trackReturns, state);
                }
            } else if (stmt instanceof Ast.SwitchStmt switched) {
                expression(switched.subject(), scope, inDefer, state);
                for (Ast.SwitchCase arm : switched.cases()) {
                    arm.constants().forEach(e -> expression(e, scope, inDefer, state));
                    statements(arm.body(), scope, inFinally, inDefer, trackReturns, state);
                }
                statements(switched.defaultBody(), scope, inFinally, inDefer, trackReturns, state);
            } else if (stmt instanceof Ast.TryStmt attempted) {
                statements(attempted.body(), scope, inFinally, inDefer, trackReturns, state);
                Scope caught = new Scope(scope);
                caught.define(attempted.errorName());
                statements(attempted.catchBody(), caught, inFinally, inDefer, trackReturns, state);
                int enclosingLoops = state.loopsInsideFinally;
                state.loopsInsideFinally = 0;
                try {
                    statements(attempted.finallyBody(), scope, true, inDefer, trackReturns, state);
                } finally {
                    state.loopsInsideFinally = enclosingLoops;
                }
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                expression(loop.iterable(), scope, inDefer, state);
                Scope iteration = new Scope(scope);
                iteration.define(loop.bindingName());
                loopBody(loop.body(), iteration, inFinally, inDefer, trackReturns, state);
            } else if (stmt instanceof Ast.ForOfDestructureStmt loop) {
                expression(loop.iterable(), scope, inDefer, state);
                Scope iteration = new Scope(scope);
                for (Ast.DestructureBinding binding : loop.bindings()) {
                    if (!binding.isDiscard()) iteration.define(binding.name());
                }
                loopBody(loop.body(), iteration, inFinally, inDefer, trackReturns, state);
            } else if (stmt instanceof Ast.ForStmt loop) {
                Scope loopScope = new Scope(scope);
                if (loop.initializer() instanceof Ast.BindingStmt binding) {
                    expression(binding.initializer(), loopScope, inDefer, state);
                    loopScope.define(binding.name());
                } else if (loop.initializer() instanceof Ast.ExprStmt expr) {
                    expression(expr.expression(), loopScope, inDefer, state);
                } else if (loop.initializer() != null) {
                    statements(List.of(loop.initializer()), loopScope, inFinally, inDefer, trackReturns, state);
                }
                expression(loop.condition(), loopScope, inDefer, state);
                expression(loop.update(), loopScope, inDefer, state);
                loopBody(loop.body(), loopScope, inFinally, inDefer, trackReturns, state);
            } else if (stmt instanceof Ast.LoopStmt loop) {
                loopBody(loop.body(), scope, inFinally, inDefer, trackReturns, state);
            } else if (stmt instanceof Ast.SelectStmt select) {
                for (Ast.SelectArm arm : select.arms()) {
                    expression(arm.channel(), scope, inDefer, state);
                    expression(arm.value(), scope, inDefer, state);
                    Scope armScope = new Scope(scope);
                    if (arm.bindingName() != null) armScope.define(arm.bindingName());
                    // A nonblocking static arm runs as a detached continuation.
                    // Its arm-local return never becomes the already-returned
                    // caller's pending result. Keep scanning effects/finally,
                    // but do not mark its return operand as a caller return.
                    boolean returnsToCaller = trackReturns
                            && select.mode() != Ast.WaitMode.NONBLOCKING;
                    statements(arm.body(), armScope, inFinally, inDefer,
                            returnsToCaller, state);
                }
            }
        }
    }

    private static void loopBody(List<Ast.Stmt> body, Scope scope,
                                 boolean inFinally, boolean inDefer,
                                 boolean trackReturns, State state) {
        if (inFinally) state.loopsInsideFinally++;
        try {
            statements(body, scope, inFinally, inDefer, trackReturns, state);
        } finally {
            if (inFinally) state.loopsInsideFinally--;
        }
    }

    private static void bindPattern(Ast.Pattern pattern, Scope scope) {
        if (pattern instanceof Ast.BindingPattern p) scope.define(p.name());
        else if (pattern instanceof Ast.TypePattern p && p.binding() != null) scope.define(p.binding());
        else if (pattern instanceof Ast.StructuralPattern p && p.binding() != null) scope.define(p.binding());
        else if (pattern instanceof Ast.TuplePattern p) p.elements().forEach(e -> bindPattern(e, scope));
        else if (pattern instanceof Ast.ConstructorPattern p) p.arguments().forEach(e -> bindPattern(e, scope));
    }

    private static void deferredLambda(Ast.LambdaExpr lambda, Scope definingScope, State state) {
        Scope captured = new Scope(definingScope);
        for (Ast.Param param : lambda.parameters()) captured.define(param.name());
        if (lambda.blockBody() != null) {
            statements(lambda.blockBody(), captured, false, true, false, state);
        } else {
            expression(lambda.expressionBody(), captured, true, state);
        }
    }

    private static void expression(Ast.Expr expr, Scope scope, boolean inDefer, State state) {
        if (expr == null) return;
        if (expr instanceof Ast.AssignExpr assignment) {
            if (assignment.target() instanceof Ast.NameExpr name) {
                Binding target = scope.resolve(name.name());
                // A closure reassigned after registration cannot be proven
                // safe using the original captured lambda declaration.
                if (target != null) {
                    if (target.closure != null) target.reassigned = true;
                    if (inDefer) state.reboundInDefer.add(target);
                }
            }
            expression(assignment.target(), scope, inDefer, state);
            expression(assignment.value(), scope, inDefer, state);
        } else if (expr instanceof Ast.LambdaExpr lambda) {
            // A return inside a nested lambda returns from that lambda, not
            // from the enclosing finally or function.
            callable(lambda.blockBody() == null ? List.of() : lambda.blockBody(),
                    lambda.parameters(), scope);
            // The lambda is only a value here. Its effects are analyzed
            // when a deferred callback (or an inline callee) invokes it.
            // Otherwise dormant nested lambdas cause false rebind reports.
        } else if (expr instanceof Ast.BinaryExpr binary) {
            expression(binary.left(), scope, inDefer, state);
            expression(binary.right(), scope, inDefer, state);
        } else if (expr instanceof Ast.TrapExpr trapped) {
            expression(trapped.expression(), scope, inDefer, state);
        } else if (expr instanceof Ast.UnaryExpr unary) {
            expression(unary.operand(), scope, inDefer, state);
        } else if (expr instanceof Ast.ConditionalExpr conditional) {
            expression(conditional.condition(), scope, inDefer, state);
            expression(conditional.whenTrue(), scope, inDefer, state);
            expression(conditional.whenFalse(), scope, inDefer, state);
        } else if (expr instanceof Ast.TypeTestExpr test) {
            expression(test.value(), scope, inDefer, state);
        } else if (expr instanceof Ast.PatternTestExpr test) {
            expression(test.value(), scope, inDefer, state);
        } else if (expr instanceof Ast.CastExpr cast) {
            expression(cast.value(), scope, inDefer, state);
        } else if (expr instanceof Ast.SpreadExpr spread) {
            expression(spread.expression(), scope, inDefer, state);
        } else if (expr instanceof Ast.CallExpr call) {
            if (inDefer && call.callee() instanceof Ast.NameExpr name) {
                Binding target = scope.resolve(name.name());
                if (target != null && (target.closure == null || target.reassigned)) {
                    throw new IllegalArgumentException(
                            "defer cannot verify reassigned or dynamic local callback '" +
                            name.name() + "'; use a statically known lexical callback");
                }
                if (target != null) state.deferredLocalCallbacks.add(target);
                // A named local cleanup callback must not evade the direct
                // deferred-lambda returned-binding check. Scan each lexical
                // callback once to avoid recursive-call analysis loops.
                if (target != null && target.closure != null
                        && state.scannedDeferredCallbacks.add(target)) {
                    deferredLambda(target.closure, target.definingScope, state);
                }
            }
            if (inDefer && call.callee() instanceof Ast.LambdaExpr lambda) {
                deferredLambda(lambda, scope, state);
            }
            expression(call.callee(), scope, inDefer, state);
            call.arguments().forEach(e -> expression(e, scope, inDefer, state));
        } else if (expr instanceof Ast.RuntimeCallExpr call) {
            call.arguments().forEach(e -> expression(e, scope, inDefer, state));
        } else if (expr instanceof Ast.MemberExpr member) {
            expression(member.receiver(), scope, inDefer, state);
        } else if (expr instanceof Ast.IndexExpr index) {
            expression(index.receiver(), scope, inDefer, state);
            expression(index.index(), scope, inDefer, state);
        } else if (expr instanceof Ast.NewExpr constructor) {
            constructor.arguments().forEach(e -> expression(e, scope, inDefer, state));
        } else if (expr instanceof Ast.AwaitExpr awaited) {
            expression(awaited.expression(), scope, inDefer, state);
        } else if (expr instanceof Ast.TaskExpr task) {
            // Return/finally/defer rules inside a Task belong to that Task,
            // never to the enclosing caller.
            callable(task.body(), List.of(), scope);
        } else if (expr instanceof Ast.ChannelOpExpr channel) {
            expression(channel.channel(), scope, inDefer, state);
            expression(channel.value(), scope, inDefer, state);
            statements(channel.callbackBody(), scope, false, inDefer, false, state);
        } else if (expr instanceof Ast.DynamicSelectExpr select) {
            expression(select.cases(), scope, inDefer, state);
        } else if (expr instanceof Ast.ListExpr list) {
            list.elements().forEach(e -> expression(e, scope, inDefer, state));
        } else if (expr instanceof Ast.TupleExpr tuple) {
            tuple.elements().forEach(e -> expression(e, scope, inDefer, state));
        } else if (expr instanceof Ast.ObjectExpr object) {
            object.fields().forEach(f -> expression(f.value(), scope, inDefer, state));
        }
    }
}
