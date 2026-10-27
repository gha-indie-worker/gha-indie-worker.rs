package dev.oreslang.types;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ownership / borrow / closure-capture analysis.
 *
 * This pass is intentionally independent of the interpreter. Borrows erase at
 * runtime; compile-time ownership remains authoritative for every backend.
 *
 * Current model:
 * - primitive immutable values are Copy;
 * - class/list/object/function values are move-only by default;
 * - by-value call/binding/return moves move-only values;
 * - &T permits shared immutable borrows;
 * - &mut T is exclusive and requires a mutable owner;
 * - Bar mut b makes an owned parameter mutable inside the callee;
 * - escaping closures own non-Copy captures and mutable captures;
 * - closures may not capture a borrow (pass it as a lambda parameter instead);
 * - moving an outer value from a repeating loop is rejected conservatively.
 */
public final class OwnershipChecker {
    private record ResolvedMethod(Ast.ClassDecl owner, Ast.TypeRef ownerType, Ast.MethodDecl method) { }
    private record ResolvedField(Ast.ClassDecl owner, Ast.TypeRef ownerType, Ast.FieldDecl field) { }
    private record CallSignature(List<Ast.Param> parameters, Ast.TypeRef result) { }
    // Must retain every (module, name, arity) slot. A name-only map loses
    // one overload and can silently skip/misapply its move/borrow effects.
    private final Map<String, Map<Integer, Ast.FunctionDecl>> functions = new HashMap<>();
    private final Map<String, String> unqualifiedFunctionOwners = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Set<String> modules = new HashSet<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    /** Only bindings admitted as new short aliases by the type checker. */
    private final Map<Ast.BindingStmt, String> validatedShortAliases;
    /** Exact immutable Program identity checked by this ownership pass. */
    private final Ast.Program checkedProgram;
    /** Monotonic epoch published only if this whole ownership pass succeeds. */
    private final long taskCheckEpoch;
    private int mutexCriticalSectionDepth;
    private int loopDepth;
    private boolean currentAsyncGenerator;
    /** Every active try records outer roots transferred anywhere inside its body. */
    private final List<Set<VarState>> tryMoveObservers = new ArrayList<>();
    /**
     * Exact Task capture admissions are staged during checking and published
     * only after the complete ownership pass succeeds. This prevents a Task
     * from retaining executable authority when a later statement/declaration
     * makes the enclosing program invalid.
     */
    private final IdentityHashMap<
            Ast.TaskExpr, Map<String, Ast.TaskCaptureTransfer>> pendingTaskCaptures =
            new IdentityHashMap<>();

    private OwnershipChecker(Ast.Program program) {
        this(program, Map.of());
    }

    private OwnershipChecker(
            Ast.Program program, Map<Ast.BindingStmt, String> validatedShortAliases) {
        // Preserve AST declaration identity: equality-based maps can merge
        // separate bindings with identical syntax in different scopes.
        this.checkedProgram =
                java.util.Objects.requireNonNull(program, "ownership program");
        this.taskCheckEpoch = Ast.CheckedTaskCaptures.newCheckEpoch();
        // Rechecking an existing immutable Program immediately invalidates
        // older executable Task proofs. A failed check must fail closed.
        Ast.CheckedTaskCaptures.beginCheck(checkedProgram, taskCheckEpoch);
        this.validatedShortAliases = new IdentityHashMap<>(validatedShortAliases);
        index(program);
    }

    public static Ast.Program check(Ast.Program program) {
        return check(program, Map.of());
    }

    /** Called only after TypeChecker has proved which short forms create aliases. */
    public static Ast.Program check(
            Ast.Program program, Map<Ast.BindingStmt, String> validatedShortAliases) {
        OwnershipChecker checker = new OwnershipChecker(program, validatedShortAliases);
        checker.validate(program);
        checker.publishTaskAdmissions();
        return program;
    }

    /** Reuse only public, explicitly linked class layouts for cross-file loans. */
    public static Ast.Program checkWithImportedClasses(
            Ast.Program program, Map<String, Ast.ClassDecl> linkedClasses) {
        return checkWithImportedClasses(program, linkedClasses, Map.of());
    }

    public static Ast.Program checkWithImportedClasses(
            Ast.Program program, Map<String, Ast.ClassDecl> linkedClasses,
            Map<Ast.BindingStmt, String> validatedShortAliases) {
        if (linkedClasses == null) {
            throw new IllegalArgumentException("linked public classes cannot be null");
        }
        OwnershipChecker checker = new OwnershipChecker(program, validatedShortAliases);
        for (Map.Entry<String, Ast.ClassDecl> entry : linkedClasses.entrySet()) {
            String name = entry.getKey();
            Ast.ClassDecl declaration = entry.getValue();
            if (name == null || declaration == null || !name.equals(declaration.name())
                    || declaration.visibility() != Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException("invalid linked ownership class: " + name);
            }
            // A checked actor signature is transport/protocol metadata, not
            // a transferable actor instance. Link it only through an exact
            // named actor import; class imports cannot silently reclassify
            // actor objects as mailbox-safe references (or vice versa).
            Ast.ImportKind expectedKind = declaration.actorKind()
                    == Ast.ActorKind.NONE
                    ? Ast.ImportKind.CLASS
                    : Ast.ImportKind.ACTOR;
            boolean imported = program.imports().stream().anyMatch(use ->
                    use.kind() == expectedKind && !use.wildcard()
                            && use.names().contains(name)
                            && (use.namespace() == null
                                    || use.namespace().equals(name)));
            if (!imported || checker.classes.containsKey(name)
                    || checker.ambiguousClasses.contains(name)) {
                throw new IllegalArgumentException(
                        "linked ownership class was not uniquely imported: " + name);
            }
            checker.classes.put(name, declaration);
        }
        checker.validate(program);
        checker.publishTaskAdmissions();
        return program;
    }

    private void publishTaskAdmissions() {
        Ast.CheckedTaskCaptures.publish(
                checkedProgram,
                taskCheckEpoch,
                pendingTaskCaptures);
    }

    private void index(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            modules.add(module.name());
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) indexFunction(module.name(), fn);
                else if (decl instanceof Ast.ClassDecl klass) index(classes, ambiguousClasses, module.name(), klass.name(), klass);
            }
        }
    }

    private void indexFunction(String module, Ast.FunctionDecl fn) {
        String qualified = module + "." + fn.name();
        putFunctionSlot(qualified, fn);
        String owner = unqualifiedFunctionOwners.putIfAbsent(fn.name(), module);
        if (owner == null || owner.equals(module)) {
            if (!ambiguousFunctions.contains(fn.name())) putFunctionSlot(fn.name(), fn);
        } else {
            ambiguousFunctions.add(fn.name());
            functions.remove(fn.name());
        }
    }

    private void putFunctionSlot(String key, Ast.FunctionDecl fn) {
        Ast.FunctionDecl previous = functions
                .computeIfAbsent(key, ignored -> new HashMap<>())
                .putIfAbsent(fn.parameters().size(), fn);
        if (previous != null && previous != fn) {
            throw error("duplicate function/routine overload " + key
                    + " with arity " + fn.parameters().size());
        }
    }

    private static <T> void index(Map<String,T> map, Set<String> ambiguous, String module, String name, T value) {
        map.put(module + "." + name, value);
        T previous = map.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            map.remove(name);
            ambiguous.add(name);
        }
    }

    private void validate(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) checkFunction(fn);
                else if (decl instanceof Ast.ClassDecl klass) checkClass(klass);
            }
        }
    }

    private void checkFunction(Ast.FunctionDecl fn) {
        Scope scope = new Scope(null, fn.nonLexical());
        for (Ast.Param param : fn.parameters()) {
            scope.define(param.name(), stateForParam(param));
        }
        defineImplicitArguments(scope, fn.parameters());
        boolean previousAsyncGenerator = currentAsyncGenerator;
        currentAsyncGenerator = fn.generator() && fn.async();
        try {
            checkBlock(fn.body(), scope, fn.returnType());
        } finally {
            currentAsyncGenerator = previousAsyncGenerator;
            scope.close();
        }
    }

    private Ast.ClassDecl initializingClass;
    // A deliberately conservative one-write constructor ledger. A later CFG
    // refinement may permit mutually exclusive writes, but this checker must
    // never permit a fixed slot to be assigned twice along a path.
    private Set<String> constructorFixedFieldWrites;

    private void checkClass(Ast.ClassDecl klass) {
        for (Ast.FieldDecl field : klass.fields()) {
            Ast.TypeRef fieldType = ownershipFieldType(field);
            if (fieldType != null && fieldType.isBorrow()) {
                throw error("field '" + klass.name() + "." + field.name()
                        + "' cannot store a borrowed reference; class/actor fields must own, copy, share, or proxy their state");
            }
        }

        // An initializer is executable code, not merely a type expression.
        // Historically the checker visited constructors/methods but skipped
        // class field initializer expressions entirely. Check each initializer
        // before constructor execution so rt take, borrow and storage transfer
        // rules cannot be bypassed by moving an operation into a field.
        //
        // Actor state uses a distinct initialization/capability protocol and
        // is not included in this ordinary-class migration.
        if (klass.actorKind() == Ast.ActorKind.NONE) {
            Scope initializerScope = new Scope(null);
            Ast.ConstructorDecl declaringConstructor = klass.constructor();
            if (declaringConstructor != null) {
                for (Ast.Param param : declaringConstructor.parameters()) {
                    Ast.TypeRef type = param.type();
                    String name = type.name();
                    if (!type.isBorrow() && type.arguments().isEmpty()
                            && !param.structural()
                            && (name.equals("int") || name.equals("bool")
                                    || name.equals("float") || name.equals("string"))) {
                        initializerScope.define(param.name(), stateForParam(param));
                    }
                }
            }
            try {
                for (Ast.FieldDecl field : klass.fields()) {
                    if (field.initializer() == null) continue;
                    ValueInfo value = checkExpr(field.initializer(), initializerScope, true);
                    if (isBorrowValue(value)) {
                        throw error("class field '" + klass.name() + "." + field.name()
                                + "' cannot store a borrowed initializer result");
                    }
                    if (containsMutexGuardType(value.type)) {
                        throw error("class field '" + klass.name() + "." + field.name()
                                + "' cannot store a MutexGuard");
                    }
                }
            } finally {
                initializerScope.close();
            }
        }

        if (klass.constructor() != null) {
            Ast.ConstructorDecl constructor = klass.constructor();
            Scope scope = new Scope(null);
            scope.define("self", new VarState(
                    Ast.TypeRef.borrowed(Ast.TypeRef.simple(klass.name()), true),
                    false,
                    ValueKind.MUT_BORROW,
                    Origin.PARAM));
            for (Ast.Param param : constructor.parameters()) {
                scope.define(param.name(), stateForParam(param));
            }
            defineImplicitArguments(scope, constructor.parameters());
            Ast.ClassDecl previousInitializingClass = initializingClass;
            Set<String> previousFixedWrites = constructorFixedFieldWrites;
            initializingClass = klass;
            constructorFixedFieldWrites = new HashSet<>();
            try {
                checkBlock(constructor.body(), scope, Ast.TypeRef.simple("void"));
            } finally {
                initializingClass = previousInitializingClass;
                constructorFixedFieldWrites = previousFixedWrites;
            }
            scope.close();
        }

        for (Ast.MethodDecl method : klass.methods()) {
            Scope scope = new Scope(null);
            if (!method.isStatic()) {
                if (klass.actorKind() != Ast.ActorKind.NONE) {
                    scope.define("self", new VarState(
                            Ast.TypeRef.borrowed(Ast.TypeRef.simple(klass.name()), true),
                            false,
                            ValueKind.MUT_BORROW,
                            Origin.PARAM));
                } else {
                    Ast.TypeRef explicitReceiver = method.explicitReceiverType();
                    if (explicitReceiver != null && explicitReceiver.isBorrow()) {
                        Ast.TypeRef target = explicitReceiver.borrowedTarget();
                        if (target.name().equals("self")) {
                            target = Ast.TypeRef.simple(klass.name());
                        }
                        ValueKind receiverKind = explicitReceiver.mutableBorrow()
                                ? ValueKind.MUT_BORROW
                                : ValueKind.IMM_BORROW;
                        scope.define(
                                "self",
                                new VarState(
                                        Ast.TypeRef.borrowed(
                                                target,
                                                explicitReceiver.mutableBorrow()),
                                        false,
                                        receiverKind,
                                        Origin.PARAM));
                    } else {
                        ValueKind receiverKind = AnnotationExpander.isGeneratedFromJsonSetter(method)
                                ? ValueKind.MUT_BORROW
                                : ValueKind.IMM_BORROW;
                        scope.define(
                                "self",
                                new VarState(
                                        Ast.TypeRef.simple(klass.name()),
                                        false,
                                        receiverKind,
                                        Origin.PARAM));
                    }
                }
            }
            for (Ast.Param param : method.parameters()) scope.define(param.name(), stateForParam(param));
            defineImplicitArguments(scope, method.parameters());
            checkBlock(method.body(), scope, method.returnType());
            scope.close();
        }
    }

    private void defineImplicitArguments(Scope scope, List<Ast.Param> parameters) {
        List<Ast.TypeRef> elementTypes = parameters.stream().map(Ast.Param::type).toList();
        Ast.TypeRef tupleType = Ast.TypeRef.tupleType(elementTypes);
        VarState arguments = new VarState(
                tupleType,
                false,
                false,
                kindOfType(tupleType),
                Origin.PARAM);

        ArrayList<VarState> sources = new ArrayList<>(parameters.size());
        for (Ast.Param parameter : parameters) {
            VarState source = scope.lookup(parameter.name());
            if (source == null) {
                throw new IllegalStateException(
                        "parameter '" + parameter.name() + "' is missing before arguments tuple binding");
            }
            sources.add(source);
        }
        arguments.aggregateSources = List.copyOf(sources);
        scope.define("arguments", arguments);
    }

    private VarState stateForParam(Ast.Param param) {
        if (param.structural() && param.mutable()) {
            throw error("structural parameter '" + param.name()
                    + "' is a read-only borrowed view and cannot be declared mut");
        }
        ValueKind kind = param.structural() && !param.type().isBorrow()
                ? ValueKind.IMM_BORROW
                : kindOfType(param.type());
        boolean mutableOwner = param.structural() ? false : param.mutable();
        if (param.type().isBorrow() && param.type().mutableBorrow()) mutableOwner = false;
        return new VarState(param.type(), mutableOwner, kind, Origin.PARAM);
    }

    private void checkBlock(List<Ast.Stmt> body, Scope parent, Ast.TypeRef returnType) {
        Scope scope = new Scope(parent);
        for (Ast.Stmt stmt : body) checkStatement(stmt, scope, returnType);
        scope.close();
    }

    private void checkLoopBlock(List<Ast.Stmt> body, Scope parent, Ast.TypeRef returnType) {
        loopDepth++;
        try {
            checkBlock(body, parent, returnType);
        } finally {
            loopDepth--;
        }
    }

    private boolean discardingSelectReturn;

    private void checkStatement(Ast.Stmt stmt, Scope scope, Ast.TypeRef returnType) {
        if (stmt instanceof Ast.BindingStmt binding) {
            checkBinding(binding, scope);
            return;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            // Immediate tuple unpacking does not store references in an owned
            // container. Give each resulting binding its own scoped borrow lease.
            if (destructure.kind() == Ast.DestructureKind.SEQUENCE
                    && destructure.initializer() instanceof Ast.TupleExpr tuple
                    && destructure.bindings().size() == tuple.elements().size()
                    && destructure.bindings().stream().noneMatch(Ast.DestructureBinding::rest)) {
                Map<String, VarState> unpacked = new LinkedHashMap<>();
                for (int i = 0; i < destructure.bindings().size(); i++) {
                    Ast.DestructureBinding binding = destructure.bindings().get(i);
                    if (binding.isDiscard()) {
                        checkExpr(tuple.elements().get(i), scope, true);
                    } else {
                        // Every initializer sees the original scope, including
                        // when an unpacked binding shadows an outer owner.
                        Scope elementScope = new Scope(scope);
                        checkBinding(new Ast.BindingStmt(binding.kind(), null, binding.name(),
                                tuple.elements().get(i), binding.mutableReferent()), elementScope);
                        if (unpacked.putIfAbsent(binding.name(), elementScope.lookup(binding.name())) != null) {
                            throw error("duplicate tuple binding '" + binding.name() + "'");
                        }
                    }
                }
                unpacked.forEach(scope::define);
                return;
            }
            ValueInfo source = checkExpr(destructure.initializer(), scope, true);
            for (int i = 0; i < destructure.bindings().size(); i++) {
                Ast.DestructureBinding binding = destructure.bindings().get(i);
                if (binding.isDiscard()) continue;
                Ast.TypeRef bindingType = destructureBindingType(destructure, source.type, i, binding.name());
                ValueKind bindingKind = bindingType.name().equals("$infer$")
                        ? (source.kind == ValueKind.COPY ? ValueKind.COPY : ValueKind.MOVE_ONLY)
                        : kindOfType(bindingType);
                scope.define(binding.name(), new VarState(
                        bindingType,
                        binding.mutableReferent(),
                        binding.kind() == Ast.BindingKind.LET,
                        bindingKind,
                        Origin.LOCAL));
            }
            return;
        }
        if (stmt instanceof Ast.BlockStmt block) {
            checkBlock(block.body(), scope, returnType);
            return;
        }
        if (stmt instanceof Ast.TaskScopeStmt taskScope) {
            checkBlock(taskScope.body(), scope, returnType);
            // Structured scope exit is an implicit asynchronous wait for
            // physical child quiescence. Until exact scoped READ proof is
            // attached to a capture, every ordinary borrow/guard must end
            // before this hidden suspension boundary.
            checkSuspensionBorrows(scope, "task scope exit");
            return;
        }
        if (stmt instanceof Ast.BreakStmt) {
            if (loopDepth == 0) throw error("'break' may only appear inside loop or for");
            return;
        }
        if (stmt instanceof Ast.ContinueStmt) {
            if (loopDepth == 0) throw error("'continue' may only appear inside loop or for");
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            if (discardingSelectReturn) {
                // The returned expression is evaluated as a side effect only.
                // It never escapes the selected arm or its actor's heap.
                if (ret.value() != null) checkExpr(ret.value(), scope, false);
                return;
            }
            if (ret.value() != null) {
                if (mutexCriticalSectionDepth > 0) {
                    throw error("with_lock/recover critical-section callbacks cannot return a value");
                }
                if (ret.value() instanceof Ast.UnaryExpr unary && (unary.operator().equals("&") || unary.operator().equals("&mut"))) {
                    VarState owner = borrowOwner(unary.operand(), scope);
                    if (owner.origin == Origin.LOCAL) {
                        throw error("cannot return a borrow of local value '" + owner.debugName + "'; borrowed value would outlive its owner");
                    }
                    if (isActorConfinedBorrow(owner)) {
                        throw error("actor self cannot escape its mailbox turn as a returned borrow");
                    }
                }
                ValueInfo returned = checkExpr(ret.value(), scope, true);
                if (containsMutexGuardType(returned.type)) {
                    throw error("MutexGuard values are lexical and cannot be returned from a function/routine");
                }
                validateReturnedBorrow(ret.value(), returned);
            }
            return;
        }
        if (stmt instanceof Ast.YieldStmt yielded) {
            if (mutexCriticalSectionDepth > 0 || scope.hasLiveMutexGuard()) {
                throw error("cannot yield while holding a MutexGuard; release the guard before suspension");
            }
            if (scope.hasLiveBorrow()) {
                throw error("cannot yield while a borrow is live; suspended generator frames currently require owned locals");
            }
            ValueInfo value = checkExpr(yielded.value(), scope, true);
            Ast.TypeRef exposed = yielded.delegated()
                    ? iterableElementType(value.type, currentAsyncGenerator)
                    : value.type;
            if (containsMutexGuardType(exposed)) {
                throw error(yielded.delegated()
                        ? "MutexGuard values cannot cross a yield* delegation boundary"
                        : "MutexGuard values cannot be yielded from a generator");
            }
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) {
            ValueInfo value = checkExpr(expression.expression(), scope, false);
            if (containsMutexGuardType(value.type)) {
                throw error("guard-bearing values cannot be discarded; bind the result with val and release/await it");
            }
            return;
        }
        if (stmt instanceof Ast.DeferStmt defer) {
            // A deferred lambda is executed synchronously at this lexical
            // scope's exit. Its captures must remain owned by the caller
            // until cleanup, rather than being moved into an escaping closure.
            ValueInfo value = defer.expression() instanceof Ast.LambdaExpr lambda
                    ? checkLambda(lambda, scope, null, false, true)
                    : checkExpr(defer.expression(), scope, false);
            if (containsMutexGuardType(value.type)) {
                throw error("defer cannot produce a guard-bearing value");
            }
            return;
        }
        if (stmt instanceof Ast.IfStmt conditional) {
            Map<VarState, StateSnapshot> base = stateSnapshot(scope);
            List<Map<VarState, StateSnapshot>> exits = new ArrayList<>();

            for (Ast.IfBranch branch : conditional.branches()) {
                restoreState(base);
                checkExpr(branch.condition(), scope, false);
                Scope branchScope = new Scope(scope);
                defineConditionAliases(branch.condition(), branchScope, scope);
                checkBlock(branch.body(), branchScope, returnType);
                branchScope.close();
                exits.add(stateSnapshot(scope));
            }

            restoreState(base);
            if (!conditional.elseBody().isEmpty()) {
                checkBlock(conditional.elseBody(), scope, returnType);
                exits.add(stateSnapshot(scope));
            } else {
                exits.add(base); // condition may be false with no else
            }

            mergeBranchState(base, exits);
            return;
        }
        if (stmt instanceof Ast.MatchStmt matched) {
            ValueInfo subject = checkExpr(matched.subject(), scope, false);
            VarState source = matched.subject() instanceof Ast.NameExpr name ? scope.lookup(name.name()) : null;
            Map<VarState, StateSnapshot> base = stateSnapshot(scope);
            List<Map<VarState, StateSnapshot>> exits = new ArrayList<>();
            for (Ast.MatchArm arm : matched.arms()) {
                restoreState(base);
                Scope armScope = new Scope(scope);
                definePatternAliases(arm.pattern(), subject.type, source, armScope);
                if (arm.guard() != null) checkExpr(arm.guard(), armScope, false);
                checkBlock(arm.body(), armScope, returnType);
                armScope.close();
                exits.add(stateSnapshot(scope));
            }
            mergeBranchState(base, exits);
            return;
        }
        if (stmt instanceof Ast.SwitchStmt switched) {
            checkExpr(switched.subject(), scope, false);
            Map<VarState, StateSnapshot> entry = stateSnapshot(scope);
            List<Map<VarState, StateSnapshot>> exits = new ArrayList<>();
            for (Ast.SwitchCase arm : switched.cases()) {
                restoreState(entry);
                for (Ast.Expr constant : arm.constants()) checkExpr(constant, scope, false);
                checkBlock(arm.body(), scope, returnType);
                exits.add(stateSnapshot(scope));
            }
            restoreState(entry);
            checkBlock(switched.defaultBody(), scope, returnType);
            exits.add(stateSnapshot(scope));
            // Missing/default-empty cases retain the original owner state.
            // Moving in any case poisons post-switch use, but not other cases.
            mergeBranchState(entry, exits);
            return;
        }
        if (stmt instanceof Ast.TryStmt attempted) {
            Map<VarState, StateSnapshot> entry = stateSnapshot(scope);
            // Catch can run after any throwing operation, including one after
            // a move in a nested branch or in an expression. Tracking moves
            // directly is safer than looking only at the try's final state.
            Set<VarState> movedDuringTry = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            tryMoveObservers.add(movedDuringTry);
            try {
                checkBlock(attempted.body(), scope, returnType);
            } finally {
                tryMoveObservers.removeLast();
            }
            Map<VarState, StateSnapshot> normal = stateSnapshot(scope);
            restoreState(entry);
            for (VarState moved : movedDuringTry) {
                if (entry.containsKey(moved)) moved.moved = true;
            }
            Scope caught = new Scope(scope);
            caught.define(attempted.errorName(), new VarState(Ast.TypeRef.inferred(), false, ValueKind.MOVE_ONLY, Origin.LOCAL));
            checkBlock(attempted.catchBody(), caught, returnType);
            caught.close();
            Map<VarState, StateSnapshot> exceptional = stateSnapshot(scope);
            // Finally sees both the ordinary and exceptional predecessor.
            mergeBranchState(entry, List.of(normal, exceptional));
            checkBlock(attempted.finallyBody(), scope, returnType);
            return;
        }
        if (stmt instanceof Ast.SelectStmt selected) {
            if (selected.mode() == Ast.WaitMode.BLOCKING
                    && selected.arms().stream().noneMatch(arm ->
                            arm.operation() == Ast.ChannelOperation.DEFAULT)) {
                // Without a default arm, the mailbox lane can yield while
                // waiting for a winner. A default arm makes the selection
                // immediately decidable, so no borrow-suspension ban is needed.
                checkSuspensionBorrows(scope, "select");
            } else if (selected.mode() == Ast.WaitMode.NONBLOCKING
                    && (mutexCriticalSectionDepth > 0 || scope.hasLiveMutexGuard())) {
                // An armed callback can re-enter the same actor on a later turn.
                throw error("cannot arm nb select while holding a MutexGuard");
            }

            // Case operands are evaluated once when the selection is armed.
            // Public Channel/SelectSet capabilities are execution-domain local
            // in the current model, so channel references are borrowed for the
            // registration lifetime and cannot cross an actor boundary. This
            // prevents writech from becoming an implicit shared-memory escape
            // hatch while preserving ordinary actor-local channel use.
            List<Ast.TypeRef> elementTypes = new ArrayList<>(selected.arms().size());
            for (Ast.SelectArm arm : selected.arms()) {
                if (arm.operation() == Ast.ChannelOperation.DEFAULT) {
                    elementTypes.add(Ast.TypeRef.inferred());
                    continue;
                }
                ValueInfo source = checkExpr(arm.channel(), scope, false);
                Ast.TypeRef element;
                if (arm.operation() == Ast.ChannelOperation.AWAIT) {
                    if (!source.type.name().equals("Future")
                            || source.type.arguments().size() != 1) {
                        throw error("select await requires Future<T> inside this actor domain");
                    }
                    element = source.type.arguments().getFirst();
                } else {
                    element = channelElementType(source.type);
                }
                // Ownership must follow the actual event's source type, not
                // the user's annotation. TypeChecker validates assignability,
                // but an ownership-only pass must not let a forged/widened
                // bindingType erase borrow, MutexGuard, or move-only semantics.
                // Treat the annotation as a naming/typechecking constraint;
                // never as proof that the source value is copyable.
                elementTypes.add(element);
                if (arm.operation() == Ast.ChannelOperation.WRITE) {
                    // Blocking/immediate select can conditionally move only the winning
                    // send. Nonblocking select returns before settlement, so its
                    // registration must own every candidate write payload immediately.
                    ValueInfo payload = checkExpr(
                            arm.value(),
                            scope,
                            selected.mode() == Ast.WaitMode.NONBLOCKING);
                    if (containsMutexGuardType(payload.type)) {
                        throw error("writech cannot transport MutexGuard");
                    }
                    if (isBorrowValue(payload)) {
                        throw error("writech cannot transport a borrowed reference; channel payloads must own/copy/share their data");
                    }
                }
            }

            List<CaptureSet> deferredArmCaptures = List.of();
            if (selected.mode() == Ast.WaitMode.NONBLOCKING) {
                deferredArmCaptures =
                        prepareDeferredSelectCaptures(selected, scope);
            }

            Map<VarState, StateSnapshot> base = stateSnapshot(scope);
            List<Map<VarState, StateSnapshot>> exits = new ArrayList<>();
            boolean hasDefault = false;

            for (int i = 0; i < selected.arms().size(); i++) {
                Ast.SelectArm arm = selected.arms().get(i);
                restoreState(base);
                Scope armScope = selected.mode() == Ast.WaitMode.NONBLOCKING
                        ? deferredSelectArmScope(deferredArmCaptures.get(i))
                        : new Scope(scope);
                if (arm.operation() == Ast.ChannelOperation.DEFAULT) {
                    hasDefault = true;
                } else if (arm.operation() == Ast.ChannelOperation.WRITE
                        && selected.mode() != Ast.WaitMode.NONBLOCKING) {
                    // Only the winning blocking/immediate arm commits the send.
                    // Join possible exits so outer values become maybe-moved
                    // (represented conservatively as moved) after selection.
                    ValueInfo outgoing = checkExpr(arm.value(), armScope, true);
                    if (containsMutexGuardType(outgoing.type)) {
                        throw error("writech cannot transport MutexGuard");
                    }
                } else if ((arm.operation() == Ast.ChannelOperation.READ
                        || arm.operation() == Ast.ChannelOperation.AWAIT)
                        && arm.bindingName() != null) {
                    Ast.TypeRef element = elementTypes.get(i);
                    armScope.define(
                            arm.bindingName(),
                            new VarState(
                                    element,
                                    arm.mutableReferent(),
                                    arm.bindingKind() == Ast.BindingKind.LET,
                                    kindOfType(element),
                                    Origin.LOCAL));
                }

                if (selected.mode() == Ast.WaitMode.NONBLOCKING) {
                    int previousLoopDepth = loopDepth;
                    boolean previousDiscard = discardingSelectReturn;
                    loopDepth = 0;
                    discardingSelectReturn = selected.explicitDo();
                    try {
                        checkBlock(arm.body(), armScope,
                                selected.explicitDo()
                                        ? Ast.TypeRef.inferred()
                                        : Ast.TypeRef.simple("void"));
                    } finally {
                        loopDepth = previousLoopDepth;
                        discardingSelectReturn = previousDiscard;
                    }
                } else {
                    checkBlock(arm.body(), armScope, returnType);
                }
                armScope.close();
                Map<VarState, StateSnapshot> exit = stateSnapshot(scope);

                if (selected.mode() != Ast.WaitMode.NONBLOCKING) {
                    exits.add(exit);
                }
            }

            if (selected.mode() == Ast.WaitMode.NONBLOCKING) {
                // Captures were transferred before base was snapshotted.
                // Arm-local moves happen in detached capture scopes and do not
                // mutate the continuing outer ownership state.
                restoreState(base);
            } else {
                if (selected.mode() == Ast.WaitMode.IMMEDIATE && !hasDefault) {
                    exits.add(base);
                }
                mergeBranchState(base, exits);
            }
            return;
        }
        if (stmt instanceof Ast.ForOfDestructureStmt loop) {
            if (loop.asyncIteration()
                    && (mutexCriticalSectionDepth > 0 || scope.hasLiveMutexGuard() || scope.hasLiveBorrow())) {
                throw error("cannot suspend for async iteration while a borrow or MutexGuard is live");
            }
            ValueInfo iterable = checkExpr(loop.iterable(), scope, false);
            Ast.TypeRef elementType = iterableElementType(iterable.type, loop.asyncIteration());
            Map<VarState,Boolean> before = movedSnapshot(scope);
            Scope loopScope = new Scope(scope);
            for (int i = 0; i < loop.bindings().size(); i++) {
                Ast.DestructureBinding binding = loop.bindings().get(i);
                if (binding.isDiscard()) continue;
                Ast.TypeRef bindingType = sequenceDestructureBindingType(elementType, i, binding.rest());
                loopScope.define(binding.name(), new VarState(
                        bindingType,
                        binding.mutableReferent(),
                        binding.kind() == Ast.BindingKind.LET,
                        kindOfType(bindingType),
                        Origin.LOCAL));
            }
            checkLoopBlock(loop.body(), loopScope, returnType);
            loopScope.close();
            rejectLoopMoves(before, scope);
            return;
        }
        if (stmt instanceof Ast.ForOfStmt loop) {
            if (loop.asyncIteration()
                    && (mutexCriticalSectionDepth > 0 || scope.hasLiveMutexGuard() || scope.hasLiveBorrow())) {
                throw error("cannot suspend for async iteration while a borrow or MutexGuard is live");
            }
            ValueInfo iterable = checkExpr(loop.iterable(), scope, false);
            Ast.TypeRef elementType = iterableElementType(iterable.type, loop.asyncIteration());
            Map<VarState,Boolean> before = movedSnapshot(scope);
            Scope loopScope = new Scope(scope);
            loopScope.define(loop.bindingName(), new VarState(
                    elementType,
                    loop.mutableReferent(),
                    loop.bindingKind() == Ast.BindingKind.LET,
                    kindOfType(elementType),
                    Origin.LOCAL));
            checkLoopBlock(loop.body(), loopScope, returnType);
            loopScope.close();
            rejectLoopMoves(before, scope);
            return;
        }
        if (stmt instanceof Ast.ForStmt loop) {
            Scope loopScope = new Scope(scope);
            if (loop.initializer() != null) checkStatement(loop.initializer(), loopScope, returnType);
            if (loop.condition() != null) checkExpr(loop.condition(), loopScope, false);
            Map<VarState,Boolean> before = movedSnapshot(scope);
            checkLoopBlock(loop.body(), loopScope, returnType);
            if (loop.update() != null) checkExpr(loop.update(), loopScope, false);
            rejectLoopMoves(before, scope);
            loopScope.close();
            return;
        }
        if (stmt instanceof Ast.DoWhileStmt loop) {
            Map<VarState,Boolean> before = movedSnapshot(scope);
            checkLoopBlock(loop.body(), scope, returnType);
            checkExpr(loop.condition(), scope, false);
            rejectLoopMoves(before, scope);
            return;
        }
        if (stmt instanceof Ast.LoopStmt loop) {
            Map<VarState,Boolean> before = movedSnapshot(scope);
            checkLoopBlock(loop.body(), scope, returnType);
            rejectLoopMoves(before, scope);
        }
    }

    private void checkSuspensionBorrows(Scope scope, String operation) {
        if (mutexCriticalSectionDepth > 0 || scope.hasLiveMutexGuard()) {
            throw error("cannot " + operation + " while holding a MutexGuard; release the guard before suspension");
        }
        // Actor self is retained by the mailbox continuation under the same
        // execution lease. Ordinary user borrows (including aliases of self)
        // still cannot cross suspension.
        if (scope.visibleStates().stream().anyMatch(state -> !state.moved
                && !("self".equals(state.debugName) && isActorConfinedBorrow(state) && state.immutableBorrows == 0 && !state.mutableBorrowed)
                && (state.kind == ValueKind.IMM_BORROW || state.kind == ValueKind.MUT_BORROW
                    || state.immutableBorrows > 0 || state.mutableBorrowed))) {
            throw error("cannot " + operation + " while an ordinary borrow is live; end the lexical borrow before suspension");
        }
    }

    private void checkBinding(Ast.BindingStmt binding, Scope scope) {
        boolean recursiveLambda = binding.initializer() instanceof Ast.LambdaExpr;
        VarState placeholder = null;
        if (recursiveLambda) {
            placeholder = new VarState(
                    binding.declaredType() == null ? Ast.TypeRef.inferred() : binding.declaredType(),
                    binding.mutableReferent(),
                    binding.kind() == Ast.BindingKind.LET,
                    ValueKind.MOVE_ONLY,
                    Origin.LOCAL);
            scope.define(binding.name(), placeholder);
        }

        ValueInfo value;
        if (binding.initializer() instanceof Ast.RuntimeCallExpr runtime
                && runtime.operation().equals("borrow")) {
            if (runtime.arguments().size() != 1) {
                throw error("rt borrow expects exactly one argument");
            }
            BorrowPlace place = borrowPlace(runtime.arguments().getFirst(), scope);
            beginPersistentBorrow(place.owner(), false);
            value = new ValueInfo(
                    Ast.TypeRef.borrowed(place.type(), false),
                    ValueKind.IMM_BORROW,
                    ownershipRoot(place.owner()),
                    true);
        } else if (binding.initializer() instanceof Ast.UnaryExpr unary && (unary.operator().equals("&") || unary.operator().equals("&mut"))) {
            // Legacy compatibility only. Canonical source uses rt borrow.
            boolean mutableBorrow = unary.operator().equals("&mut");
            BorrowPlace place = borrowPlace(unary.operand(), scope);
            if (mutableBorrow && !(unary.operand() instanceof Ast.NameExpr)) {
                throw error("mutable projected borrows need path-exclusive loan tracking");
            }
            beginPersistentBorrow(place.owner(), mutableBorrow);
            value = new ValueInfo(
                    Ast.TypeRef.borrowed(place.type(), mutableBorrow),
                    mutableBorrow ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW,
                    ownershipRoot(place.owner()),
                    true);
        } else if (binding.initializer() instanceof Ast.LambdaExpr lambda) {
            value = checkLambda(lambda, scope, binding.name());
        } else {
            value = checkExpr(binding.initializer(), scope, true);
        }

        if (recursiveLambda) {
            placeholder.type = binding.declaredType() == null ? value.type : binding.declaredType();
            placeholder.kind = value.kind;
            placeholder.borrowSource = value.borrowSource;
            return;
        }

        boolean readonlyRecord = value.type.isReadonlyRecordType();
        if (readonlyRecord && binding.mutableReferent()) {
            throw error("readonly structural records cannot gain mutable bindings or aliases");
        }

        // A whole move-only owner may transfer from a read-only binding into a
        // new mutable owner. A field/index projection is different: ordinary
        // projection is an aliasing read and does not invalidate the containing
        // owner. Therefore a mutable projected alias needs mutable authority at
        // the projection's receiver/root or it would launder write access
        // through an immutable alias.
        if (binding.mutableReferent() && value.kind != ValueKind.COPY) {
            if (binding.initializer() instanceof Ast.MemberExpr member) {
                ensureMutableReceiver(member.receiver(), scope, "mutable projected alias");
            } else if (binding.initializer() instanceof Ast.IndexExpr indexed) {
                ensureMutableReceiver(indexed.receiver(), scope, "mutable projected alias");
            }
        }

        value = retainBorrowForBinding(value);
        Ast.TypeRef storedType = binding.declaredType() == null ? value.type : binding.declaredType();
        String shortAlias = validatedShortAliases.get(binding);
        String inferredAlias = binding.inferredTypeName() == null
                ? shortAlias : binding.inferredTypeName();
        if (inferredAlias != null) {
            // The typechecker proves that short syntax creates a fresh alias;
            // the ownership checker must not reinterpret opaque nominal names
            // or trust a guess based only on capitalization.
            if (shortAlias != null
                    && (binding.inferredTypeName() != null
                        || binding.kind() != Ast.BindingKind.CONST
                        || binding.mutableReferent()
                        || binding.declaredType() == null
                        || !binding.declaredType().name().equals(shortAlias)
                        || !(binding.initializer() instanceof Ast.ObjectExpr object
                             && object.inferredAggregate()))) {
                throw error("invalid validated short inferred type alias");
            }
            if (value.type.name().equals("$infer$")
                    || shortAlias != null && !value.type.isClosedAggregateType()) {
                throw error("cannot bind inferred alias '" + inferredAlias
                        + "' to an unknown or non-aggregate ownership type");
            }
            scope.defineInferredType(inferredAlias, value.type);
            storedType = value.type;
        } else if (binding.declaredType() != null) {
            Ast.TypeRef scoped = scope.lookupInferredType(binding.declaredType().name());
            if (scoped != null) storedType = scoped;
        }
        if (readonlyRecord && storedType.isRecordType() && !storedType.isReadonlyRecordType()) {
            storedType = Ast.TypeRef.readonlyRecordType(storedType.recordMembers());
        }
        if (binding.kind() == Ast.BindingKind.LET && containsMutexGuardType(storedType)) {
            throw error("guard-bearing values are linear and cannot use let; bind them once with val");
        }

        ValueKind storedKind = binding.declaredType() == null ? value.kind : kindOfType(storedType);
        VarState state = new VarState(
                storedType,
                binding.mutableReferent(),
                binding.kind() == Ast.BindingKind.LET,
                storedKind,
                Origin.LOCAL);
        state.borrowSource = value.borrowSource;
        state.borrowLease = value.borrowLease;
        if (binding.kind() == Ast.BindingKind.CONST) {
            Object directSelector = directComputedIndexConstant(binding);
            state.computedIndexConstant = directSelector != null
                    ? directSelector
                    : computedIndexConstant(binding.initializer(), scope);
        }
        scope.define(binding.name(), state);
    }

    private ValueInfo checkExpr(Ast.Expr expr, Scope scope, boolean consuming) {
        if (expr instanceof Ast.MatchExpr match) {
            ValueInfo subject = checkExpr(match.subject(), scope, false);
            VarState source = match.subject() instanceof Ast.NameExpr name
                    ? scope.lookup(name.name()) : null;
            Map<VarState, StateSnapshot> base = stateSnapshot(scope);
            List<Map<VarState, StateSnapshot>> exits = new ArrayList<>();
            ValueInfo result = null;
            for (Ast.MatchValueArm arm : match.arms()) {
                restoreState(base);
                Scope armScope = new Scope(scope);
                try {
                    definePatternAliases(arm.pattern(), subject.type, source, armScope);
                    if (arm.guard() != null) checkExpr(arm.guard(), armScope, false);
                    ValueInfo value = checkExpr(arm.value(), armScope, consuming);
                    // Do not let branch-scoped loans become expression values:
                    // a returned borrow could outlive its pattern bindings.
                    if (isBorrowValue(value) || value.borrowSource != null) {
                        throw error("value match cannot return a borrowed branch-local value");
                    }
                    if (result == null) result = value;
                } finally {
                    armScope.close();
                }
                exits.add(stateSnapshot(scope));
            }
            mergeBranchState(base, exits);
            return result;
        }
        if (expr instanceof Ast.LiteralExpr literal) {
            return new ValueInfo(inferLiteralType(literal.value()), ValueKind.COPY, null);
        }
        if (expr instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            if (state == null && name.name().equals("None")) {
                return new ValueInfo(
                        new Ast.TypeRef("Option", List.of(Ast.TypeRef.inferred()), false),
                        ValueKind.MOVE_ONLY,
                        null);
            }
            if (state == null) return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.COPY, null); // function/module/global
            state.debugName = name.name();
            requireUsable(state, name.name(), false);
            if (consuming && initializingClass != null && name.name().equals("self")) {
                throw error("partially initialized constructor self cannot escape");
            }
            if (consuming && isActorConfinedBorrow(state)) {
                throw error("actor self is a non-escapable mailbox capability; access its fields/methods inside the actor turn");
            }
            if (consuming && mutexCriticalSectionDepth > 0 && state.kind == ValueKind.MUT_BORROW) {
                throw error("protected with_lock/recover state cannot be moved by value; use it through its &mut critical-section borrow");
            }
            if (consuming && state.kind == ValueKind.MOVE_ONLY) move(state, name.name());
            boolean transferredBorrowLease = false;
            VarState provenance = state.borrowSource;
            if (provenance == null && isIncomingBorrow(state)) provenance = state;
            if (consuming && state.kind == ValueKind.MUT_BORROW) {
                move(state, name.name());
                transferredBorrowLease = state.borrowLease;
                state.borrowLease = false;
            }
            return new ValueInfo(state.type, state.kind, provenance, transferredBorrowLease);
        }
        if (expr instanceof Ast.TrapExpr trapped) {
            ValueInfo value = checkExpr(trapped.expression(), scope, true);
            if (isBorrowValue(value) || containsMutexGuardType(value.type))
                throw error("trap cannot package a borrow or MutexGuard without proven scoped lifetime");
            Ast.TypeRef option = new Ast.TypeRef("Option", List.of(value.type), false);
            return new ValueInfo(option, kindOfType(option), null);
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            if (unary.operator().equals("&") || unary.operator().equals("&mut")) {
                if (initializingClass != null
                        && unary.operand() instanceof Ast.NameExpr selfName
                        && selfName.name().equals("self")) {
                    throw error("constructor cannot borrow partially initialized self");
                }
                boolean mutable = unary.operator().equals("&mut");
                BorrowPlace place = borrowPlace(unary.operand(), scope);
                if (mutable && !(unary.operand() instanceof Ast.NameExpr)) {
                    throw error("mutable projected borrows need path-exclusive loan tracking");
                }
                validateBorrow(place.owner(), mutable);
                return new ValueInfo(Ast.TypeRef.borrowed(place.type(), mutable), mutable ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW, ownershipRoot(place.owner()));
            }
            return checkExpr(unary.operand(), scope, false);
        }
        if (expr instanceof Ast.RuntimeCallExpr runtime) {
            if (runtime.operation().equals("spawn")) {
                if (runtime.arguments().size() != 1
                        || !(runtime.arguments().getFirst() instanceof Ast.NewExpr created)) {
                    throw error("internal spawn intrinsic requires exactly one actor target");
                }
                if (!created.arguments().isEmpty()) {
                    throw error("actor spawn arguments are not enabled; initialize actor-local state with fields and on_start()");
                }
                return new ValueInfo(
                        new Ast.TypeRef(
                                "ActorRef",
                                List.of(created.type()),
                                false),
                        ValueKind.COPY,
                        null);
            }
            if (runtime.operation().equals("cooperate")) {
                checkSuspensionBorrows(scope, "cooperate");
                if (!runtime.arguments().isEmpty()) {
                    throw error("rt cooperate takes no arguments");
                }
                return new ValueInfo(Ast.TypeRef.simple("void"), ValueKind.COPY, null);
            }
            if (runtime.arguments().size() != 1) {
                throw error("rt " + runtime.operation() + " expects exactly one argument");
            }
            Ast.Expr argument = runtime.arguments().getFirst();
            if (initializingClass != null
                    && argument instanceof Ast.NameExpr selfName
                    && selfName.name().equals("self")) {
                if (runtime.operation().equals("borrow")) {
                    throw error("constructor cannot borrow partially initialized self");
                }
                throw error("constructor cannot apply runtime operation to partially initialized self");
            }
            if (runtime.operation().equals("fail")) {
                checkExpr(argument, scope, false);
                return new ValueInfo(Ast.TypeRef.simple("void"), ValueKind.COPY, null);
            }
            return switch (runtime.operation()) {
                case "borrow" -> {
                    BorrowPlace place = borrowPlace(argument, scope);
                    validateBorrow(place.owner(), false);
                    yield new ValueInfo(
                            Ast.TypeRef.borrowed(place.type(), false),
                            ValueKind.IMM_BORROW,
                            ownershipRoot(place.owner()));
                }
                case "take" -> {
                    if (argument instanceof Ast.IndexExpr) {
                        throw error("rt take cannot move an indexed projection until projection-level move tracking is available; move the containing owner or use a read/borrow");
                    }
                    if (argument instanceof Ast.MemberExpr member) {
                        Ast.ClassDecl ownerClass = classOfReceiver(member.receiver(), scope);
                        if (ownerClass != null) {
                            Ast.FieldDecl slot = findField(
                                    ownerClass, member.member(), new LinkedHashSet<>());
                            if (slot != null && slot.bindingKind() != Ast.BindingKind.LET) {
                                throw error("cannot rt take immutable/final class field '"
                                        + ownerClass.name() + "." + member.member() + "'");
                            }
                        }
                    }
                    ValueInfo taken = checkExpr(argument, scope, true);
                    if (taken.kind == ValueKind.IMM_BORROW || taken.kind == ValueKind.MUT_BORROW
                            || (taken.type != null && taken.type.isBorrow())) {
                        throw error("rt take requires owned data; a borrow cannot regain ownership");
                    }
                    // Explicit take is a transfer, including for otherwise
                    // copyable handles such as Channel<T>. Ordinary calls and
                    // channel operations may still copy/borrow that handle.
                    if (argument instanceof Ast.NameExpr name && taken.kind == ValueKind.COPY) {
                        VarState binding = scope.lookup(name.name());
                        if (binding != null) move(binding, name.name());
                    }
                    yield taken;
                }
                case "copy" -> {
                    ValueInfo source = checkExpr(argument, scope, false);
                    if (source.kind != ValueKind.COPY) {
                        throw error(
                                "rt copy of move-only values requires the Symbol.rtCopy copy-contract lowering; "
                                        + "this current-main convergence patch fails closed instead of aliasing");
                    }
                    yield source;
                }
                case "share" -> {
                    ValueInfo source = checkExpr(argument, scope, false);
                    if (source.kind != ValueKind.COPY) {
                        throw error(
                                "rt share of move-only values requires the owning Shared<T> convergence; "
                                        + "this current-main patch fails closed instead of manufacturing shared mutable aliasing");
                    }
                    yield source;
                }
                default -> throw error("unknown runtime operation 'rt " + runtime.operation() + "'");
            };
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            boolean directRebind = assignment.target() instanceof Ast.NameExpr;
            // Runtime assignment is RHS-first. A projection target therefore
            // must be validated after the RHS: the RHS may move or borrow the
            // receiver, and a subsequent field/index write must see that state.
            // Direct name rebinding is the exception because replacing a moved
            // owner is legal; validate that target before checking the RHS, then
            // restore usability only after a valid owned replacement exists.
            if (directRebind) {
                checkAssignmentTarget(assignment.target(), scope);
            }
            ValueInfo assigned = checkExpr(assignment.value(), scope, true);
            if (containsMutexGuardType(assigned.type)) {
                throw error("guard-bearing values cannot be assigned or overwritten; bind them once with val");
            }
            if (isBorrowValue(assigned)) {
                throw error("borrowed references cannot be assigned into longer-lived storage; create a fresh lexical borrow binding instead");
            }
            if (!directRebind) {
                checkAssignmentTarget(assignment.target(), scope);
            }
            if (assignment.target() instanceof Ast.NameExpr name) {
                ownershipRoot(requireState(scope, name.name())).moved = false;
            }
            return assigned;
        }
        if (expr instanceof Ast.TypeTestExpr test) {
            checkExpr(test.value(), scope, false);
            return new ValueInfo(Ast.TypeRef.simple("bool"), ValueKind.COPY, null);
        }
        if (expr instanceof Ast.PatternTestExpr test) {
            checkExpr(test.value(), scope, false);
            return new ValueInfo(Ast.TypeRef.simple("bool"), ValueKind.COPY, null);
        }
        if (expr instanceof Ast.CastExpr cast) {
            ValueInfo source = checkExpr(cast.value(), scope, consuming);
            Ast.TypeRef result = cast.mode() == Ast.CastMode.OPTIONAL
                    ? new Ast.TypeRef("Option", List.of(cast.targetType()), false)
                    : cast.targetType();
            return new ValueInfo(result,
                    cast.mode() == Ast.CastMode.OPTIONAL ? kindOfType(result) : source.kind,
                    source.borrowSource);
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            checkExpr(binary.left(), scope, false);
            if (binary.operator().equals("&&")) {
                Scope rightScope = new Scope(scope);
                defineConditionAliases(binary.left(), rightScope, scope);
                checkExpr(binary.right(), rightScope, false);
                rightScope.close();
            } else {
                checkExpr(binary.right(), scope, false);
            }
            return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.COPY, null);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            checkExpr(conditional.condition(), scope, false);
            Map<VarState, StateSnapshot> base = stateSnapshot(scope);

            ValueInfo left = checkExpr(conditional.whenTrue(), scope, consuming);
            Map<VarState, StateSnapshot> leftExit = stateSnapshot(scope);

            restoreState(base);
            ValueInfo right = checkExpr(conditional.whenFalse(), scope, consuming);
            Map<VarState, StateSnapshot> rightExit = stateSnapshot(scope);

            mergeBranchState(base, List.of(leftExit, rightExit));
            Ast.TypeRef joinedType = joinConditionalType(left.type, right.type);
            return new ValueInfo(
                    joinedType,
                    left.kind == ValueKind.COPY && right.kind == ValueKind.COPY
                            ? ValueKind.COPY
                            : ValueKind.MOVE_ONLY,
                    null);
        }
        if (expr instanceof Ast.SpreadExpr spread) {
            return checkExpr(spread.expression(), scope, consuming);
        }
        if (expr instanceof Ast.CallExpr call) {
            return checkCall(call, scope);
        }
        if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr receiverName) {
                VarState receiverState = scope.lookup(receiverName.name());
                if (receiverState != null && isScopedMutexReceiver(receiverState)) {
                    requireUsable(receiverState, receiverName.name(), false);
                    Ast.TypeRef concreteReceiver = receiverType(member.receiver(), scope);
                    Ast.ClassDecl klass = concreteReceiver == null ? null : findClass(concreteReceiver.name());
                    if (klass != null) {
                        ResolvedField target = findFieldTarget(klass, concreteReceiver, member.member(), new LinkedHashSet<>());
                        if (target != null) {
                            Ast.TypeRef fieldType = substituteType(
                                    ownershipFieldType(target.field()),
                                    genericBindings(target.owner().genericParameters(), target.ownerType().arguments()));
                            if (!isCopyType(fieldType)) {
                                throw error("cannot extract move-only field '" + target.owner().name() + "." + member.member()
                                        + "' from protected mutex state; operate on it inside with_lock or replace the field as a whole");
                            }
                            return new ValueInfo(fieldType, ValueKind.COPY, null);
                        }
                        if (hasMethodNamed(klass, member.member(), new LinkedHashSet<>())) {
                            throw error("instance methods are direct-call-only and cannot be extracted from protected mutex state; invoke the method directly while the guard is live or use an explicit lambda where capture is legal");
                        }
                    }
                    if (isMutexGuardType(receiverState.type)) {
                        throw error("unknown or non-extractable MutexGuard member '" + member.member() + "'");
                    }
                }
            }
            ValueInfo receiverValue = checkExpr(member.receiver(), scope, false);
            Ast.TypeRef concreteReceiver = receiverType(member.receiver(), scope);
            if (concreteReceiver == null) concreteReceiver = receiverValue.type;
            if (concreteReceiver != null && concreteReceiver.isBorrow()) {
                concreteReceiver = concreteReceiver.borrowedTarget();
            }
            if (concreteReceiver != null && concreteReceiver.isClosedAggregateType()) {
                Ast.TypeRef valueType = concreteReceiver.aggregateMembers().get(member.member());
                if (valueType == null) {
                    throw error("unknown record field '" + member.member() + "'");
                }
                ValueKind valueKind = kindOfType(valueType);
                return new ValueInfo(valueType, valueKind, null);
            }
            Ast.ClassDecl klass = concreteReceiver == null ? null : findClass(concreteReceiver.name());
            if (klass != null) {
                ResolvedField target = findFieldTarget(klass, concreteReceiver, member.member(), new LinkedHashSet<>());
                if (target != null) {
                    Ast.TypeRef fieldType = substituteType(
                            ownershipFieldType(target.field()),
                            genericBindings(target.owner().genericParameters(), target.ownerType().arguments()));
                    ValueKind fieldKind = kindOfType(fieldType);
                    if (consuming && isRootedAtActorSelf(member.receiver(), scope) && fieldKind != ValueKind.COPY) {
                        throw error("cannot move actor-owned field '" + member.member()
                                + "' out of its mailbox turn; return a copy/immutable value or explicit shared snapshot");
                    }
                    return new ValueInfo(fieldType, fieldKind, null);
                }
                if (hasMethodNamed(klass, member.member(), new LinkedHashSet<>())
                        && isRootedAtActorSelf(member.receiver(), scope)) {
                    throw error("actor self method '" + klass.name() + "." + member.member()
                            + "' cannot escape its mailbox turn as a bound method; invoke it directly inside the turn");
                }
            }
            return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            ValueInfo receiver = checkExpr(indexed.receiver(), scope, false);
            checkExpr(indexed.index(), scope, false);
            Ast.TypeRef elementType = indexedElementType(indexed, receiver.type, scope);
            ValueKind elementKind = elementType.name().equals("$infer$") ? ValueKind.MOVE_ONLY : kindOfType(elementType);
            if (consuming && elementKind != ValueKind.COPY
                    && isRootedAtActorSelf(indexed.receiver(), scope)) {
                throw error("cannot move actor-owned indexed state out of its mailbox turn");
            }
            // Ordinary projection is an aliasing read, not an implicit partial
            // move. Explicit ownership transfer remains spelled rt take; indexed
            // rt take stays fail-closed below until projection-level move
            // tracking can invalidate exactly the selected slot.
            return new ValueInfo(elementType, elementKind, null);
        }
        if (expr instanceof Ast.NewExpr created) {
            List<Ast.TypeRef> argumentTypes = new ArrayList<>(created.arguments().size());
            for (Ast.Expr arg : created.arguments()) {
                ValueInfo info = checkExpr(arg, scope, true);
                if (containsMutexGuardType(info.type)) {
                    throw error("MutexGuard cannot be stored in a constructed object");
                }
                argumentTypes.add(info.type);
            }
            Ast.TypeRef constructedType = inferConstructedType(created, argumentTypes);
            return new ValueInfo(constructedType, ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.TaskExpr task) {
            if (task.domain() == Ast.ExecutionDomain.GPU) {
                throw error("gpu task cannot pass ownership lowering without a physical GPU provider");
            }
            CaptureSet captured = new CaptureSet();
            scanStatements(task.body(), Set.of(), scope, null, captured);
            // Deferred select/channel callbacks execute under the owning
            // mailbox continuation and may retain its exact self capability.
            // A new Task is independently scheduled: it must never inherit
            // mailbox self or mutate actor state outside the actor turn.
            for (Capture capture : captured.values.values()) {
                if (isDirectActorSelfCapture(capture)
                        && isActorConfinedBorrow(capture.source)) {
                    throw error("independent task cannot capture actor self; send a message or pass owned data instead");
                }
            }
            // Generic deferred callbacks are not a complete Task Send proof:
            // they may run under a mailbox's existing authority. A new Task
            // needs a compiler-derived Send proof from the canonical
            // AutoTraitRules lattice. Task-specific runtime seams are limited
            // to affine Future<T> MOVE and synchronized Channel<T> handles;
            // guards, Rc and foreign/scheduler authority remain non-Send.
            for (Capture capture : captured.values.values()) {
                if (!capture.source.type.isBorrow()
                        && forbiddenTaskCapability(capture.source.type)) {
                    throw error("independent task capture '" + capture.name
                            + "' contains a non-Send execution capability; "
                            + "pass owned values or an explicit Channel<T> instead");
                }
            }
            validateDeferredCaptures(captured);
            Scope taskScope = deferredSelectArmScope(captured);
            int savedLoopDepth = loopDepth;
            boolean savedDiscard = discardingSelectReturn;
            loopDepth = 0;
            discardingSelectReturn = false;
            try {
                checkBlock(task.body(), taskScope, Ast.TypeRef.inferred());
            } finally {
                loopDepth = savedLoopDepth;
                discardingSelectReturn = savedDiscard;
                taskScope.close();
            }
            LinkedHashMap<String, Ast.TaskCaptureTransfer> admitted =
                    new LinkedHashMap<>();
            for (Capture capture : captured.values.values()) {
                Ast.TaskCaptureTransfer transfer =
                        !capture.write
                                && !capture.consume
                                && capture.source.kind == ValueKind.COPY
                                ? Ast.TaskCaptureTransfer.COPY
                                : Ast.TaskCaptureTransfer.MOVE;
                admitted.put(capture.name, transfer);
            }
            Map<String, Ast.TaskCaptureTransfer> immutableAdmission =
                    java.util.Collections.unmodifiableMap(
                            new LinkedHashMap<>(admitted));
            Map<String, Ast.TaskCaptureTransfer> previousAdmission =
                    pendingTaskCaptures.putIfAbsent(
                            task, immutableAdmission);
            if (previousAdmission != null
                    && !previousAdmission.equals(immutableAdmission)) {
                throw error(
                        "same Task expression produced inconsistent capture admission");
            }
            Ast.TypeRef result = new Ast.TypeRef(
                    "Future", List.of(Ast.TypeRef.inferred()), false);
            return new ValueInfo(result, kindOfType(result), null);
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            checkSuspensionBorrows(scope, "await");
            ValueInfo awaitedValue = checkExpr(awaited.expression(), scope, consuming);
            if (awaitedValue.type.name().equals("Future") && awaitedValue.type.arguments().size() == 1) {
                Ast.TypeRef result = awaitedValue.type.arguments().getFirst();
                return new ValueInfo(result, kindOfType(result), null);
            }
            return awaitedValue;
        }
        if (expr instanceof Ast.ChannelOpExpr channelOp) {
            if (channelOp.mode() == Ast.WaitMode.BLOCKING) {
                checkSuspensionBorrows(scope, "readch/writech");
            } else if (channelOp.mode() == Ast.WaitMode.NONBLOCKING
                    && (mutexCriticalSectionDepth > 0 || scope.hasLiveMutexGuard())) {
                throw error("cannot register a channel waiter while holding a MutexGuard");
            }
            ValueInfo channel = checkExpr(channelOp.channel(), scope, false);
            Ast.TypeRef element = channelElementType(channel.type);

            if (channelOp.operation() == Ast.ChannelOperation.WRITE) {
                // Channel enqueue/registration takes ownership of move-only payloads.
                // Waiting writers and failed immediate probes conservatively retain
                // transferred ownership until the runtime supplies a recoverable send.
                ValueInfo payload = checkExpr(channelOp.value(), scope, true);
                if (containsMutexGuardType(payload.type)) {
                    throw error("writech cannot transport MutexGuard");
                }
                if (isBorrowValue(payload)) {
                    throw error("writech cannot transport a borrowed reference; channel payloads must own/copy/share their data");
                }
                if (channelOp.callback()) {
                    // nb cb writech returns to the enclosing actor turn before
                    // running its callback. Transfer captures now, not when the
                    // asynchronous writer eventually settles.
                    CaptureSet captured = new CaptureSet();
                    scanStatements(channelOp.callbackBody(), Set.of(), scope, null, captured);
                    validateDeferredCaptures(captured);
                    Scope callbackScope = deferredSelectArmScope(captured);
                    int savedLoopDepth = loopDepth;
                    boolean savedDiscard = discardingSelectReturn;
                    loopDepth = 0;
                    discardingSelectReturn = false;
                    try {
                        checkBlock(channelOp.callbackBody(), callbackScope, Ast.TypeRef.simple("void"));
                    } finally {
                        loopDepth = savedLoopDepth;
                        discardingSelectReturn = savedDiscard;
                        callbackScope.close();
                    }
                }
                Ast.TypeRef result = switch (channelOp.mode()) {
                    case BLOCKING -> Ast.TypeRef.simple("void");
                    case NONBLOCKING -> new Ast.TypeRef(
                            "Future", List.of(Ast.TypeRef.simple("void")), false);
                    case IMMEDIATE -> Ast.TypeRef.simple("bool");
                };
                return new ValueInfo(result, kindOfType(result), null);
            }

            Ast.TypeRef result = switch (channelOp.mode()) {
                case BLOCKING -> element;
                case NONBLOCKING -> new Ast.TypeRef("Future", List.of(element), false);
                case IMMEDIATE -> new Ast.TypeRef("Option", List.of(element), false);
            };
            return new ValueInfo(result, kindOfType(result), null);
        }
        if (expr instanceof Ast.DynamicSelectExpr selected) {
            if (selected.mode() == Ast.WaitMode.BLOCKING) {
                checkSuspensionBorrows(scope, "select from cases");
            } else if (selected.mode() == Ast.WaitMode.NONBLOCKING
                    && (mutexCriticalSectionDepth > 0 || scope.hasLiveMutexGuard())) {
                throw error("cannot register dynamic select while holding a MutexGuard");
            }
            checkExpr(selected.cases(), scope, false);
            Ast.TypeRef selectedResult = Ast.TypeRef.simple("SelectResult");
            Ast.TypeRef result = switch (selected.mode()) {
                case BLOCKING -> selectedResult;
                case NONBLOCKING -> new Ast.TypeRef("Future", List.of(selectedResult), false);
                case IMMEDIATE -> new Ast.TypeRef("Option", List.of(selectedResult), false);
            };
            return new ValueInfo(result, kindOfType(result), null);
        }
        if (expr instanceof Ast.ListExpr list) {
            Ast.TypeRef elementType = null;
            for (Ast.Expr item : list.elements()) {
                ValueInfo info = checkExpr(item, scope, true);
                if (containsMutexGuardType(info.type)) throw error("MutexGuard cannot be stored in an array/list");
                if (isBorrowValue(info)) throw error("borrowed references cannot be stored in an owned array/list until explicit container lifetimes are supported");
                elementType = elementType == null
                        ? info.type
                        : joinConditionalType(elementType, info.type);
            }
            if (elementType == null) elementType = Ast.TypeRef.inferred();
            return new ValueInfo(
                    new Ast.TypeRef("Array", List.of(elementType), false),
                    ValueKind.MOVE_ONLY,
                    null);
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            boolean copy = true;
            List<Ast.TypeRef> slots = new ArrayList<>(tuple.elements().size());
            for (Ast.Expr item : tuple.elements()) {
                ValueInfo info = checkExpr(item, scope, true);
                if (containsMutexGuardType(info.type)) throw error("MutexGuard cannot be stored in a tuple");
                if (isBorrowValue(info)) throw error("borrowed references cannot be stored in an owned tuple until explicit container lifetimes are supported");
                copy &= info.kind == ValueKind.COPY;
                slots.add(info.type);
            }
            return new ValueInfo(Ast.TypeRef.tupleType(slots), copy ? ValueKind.COPY : ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.ObjectExpr object) {
            LinkedHashMap<String, Ast.TypeRef> members = new LinkedHashMap<>();
            if (object.explicitStruct()) {
                for (Ast.StructFieldSpec field : object.declaredFields()) {
                    if (members.putIfAbsent(field.name(), field.type()) != null) {
                        throw error("duplicate explicit struct field '" + field.name() + "'");
                    }
                }
            }
            for (Ast.ObjectField field : object.fields()) {
                ValueInfo info = checkExpr(field.value(), scope, true);
                if (containsMutexGuardType(info.type)) {
                    throw error("MutexGuard cannot be stored in a struct");
                }
                if (isBorrowValue(info)) {
                    throw error("borrowed references cannot be stored in an owned struct until explicit container lifetimes are supported");
                }
                if (!object.explicitStruct()) members.put(field.name(), info.type);
            }
            Ast.TypeRef type;
            if (object.structuralRecord()) type = Ast.TypeRef.readonlyRecordType(members);
            else if (object.inferredStruct() || object.explicitStruct()) type = Ast.TypeRef.structType(members);
            else throw error("unknown anonymous aggregate kind " + object.kind());
            return new ValueInfo(type, ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.LambdaExpr lambda) return checkLambda(lambda, scope, null);
        return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
    }

    private ValueInfo checkCall(Ast.CallExpr call, Scope scope) {
        // A constructor owns an unpublished, incompletely initialized object.
        // Calling any instance method on self can transitively read absent
        // fields, publish the receiver or invoke suspension. No transitive
        // constructor-safe method effect summaries exist yet, so fail closed.
        if (initializingClass != null) {
            if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr name
                    && name.name().equals("self")) {
                throw error("constructor cannot invoke methods on partially initialized self");
            }
            for (Ast.Expr argument : call.arguments()) {
                // Some intrinsic and first-class callable paths inspect their
                // arguments without consuming them. That is appropriate for
                // fully initialized borrows, but not for the constructor's
                // unpublishable self capability.
                if (argument instanceof Ast.NameExpr name && name.name().equals("self")) {
                    throw error("constructor cannot pass partially initialized self to a call");
                }
                if (argument instanceof Ast.UnaryExpr unary
                        && (unary.operator().equals("&") || unary.operator().equals("&mut"))
                        && unary.operand() instanceof Ast.NameExpr name
                        && name.name().equals("self")) {
                    throw error("constructor cannot borrow partially initialized self for a call");
                }
                if (argument instanceof Ast.RuntimeCallExpr runtime
                        && runtime.operation().equals("borrow")
                        && runtime.arguments().size() == 1
                        && runtime.arguments().getFirst() instanceof Ast.NameExpr name
                        && name.name().equals("self")) {
                    throw error("constructor cannot borrow partially initialized self for a call");
                }
            }
        }
        if (isBooleanIntrinsicCall(call, scope)) {
            for (Ast.Expr argument : call.arguments()) {
                checkExpr(argument, scope, false);
            }
            return new ValueInfo(Ast.TypeRef.simple("bool"), ValueKind.COPY, null);
        }
        if (isBuiltinFormattingCall(call, scope)) {
            checkFormattingArguments(call.arguments(), scope);
            return new ValueInfo(Ast.TypeRef.simple("void"), ValueKind.COPY, null);
        }
        if (call.callee() instanceof Ast.MemberExpr factoryCall
                && factoryCall.receiver() instanceof Ast.NameExpr factory
                && (factory.name().equals("Mutex") || factory.name().equals("SharedMutex"))
                && factoryCall.member().equals("new")
                && call.arguments().size() == 1) {
            ValueInfo owned = checkExpr(call.arguments().getFirst(), scope, true);
            if (owned.type != null && owned.type.isBorrow()) {
                throw error(factory.name()
                        + ".new requires an owned value; borrowed values cannot become mutex state");
            }
            if (containsMutexGuardType(owned.type)) {
                throw error(factory.name() + ".new cannot hide a guard-bearing value");
            }
            Ast.TypeRef mutexType = new Ast.TypeRef(factory.name(), List.of(owned.type), false);
            return new ValueInfo(mutexType, factory.name().equals("SharedMutex") ? ValueKind.COPY : ValueKind.MOVE_ONLY, null);
        }

        if (call.callee() instanceof Ast.NameExpr name
                && (name.name().equals("Some") || name.name().equals("Ok") || name.name().equals("Err"))) {
            if (call.arguments().size() != 1) {
                return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
            }
            ValueInfo payload = checkExpr(call.arguments().getFirst(), scope, true);
            if (payload.kind == ValueKind.IMM_BORROW || payload.kind == ValueKind.MUT_BORROW
                    || (payload.type != null && payload.type.isBorrow())) {
                throw error(name.name()
                        + " cannot store a borrow in an owned sum value until explicit lifetime parameters are supported");
            }
            if (name.name().equals("Some")) {
                Ast.TypeRef type = new Ast.TypeRef("Option", List.of(payload.type), false);
                return new ValueInfo(type, kindOfType(type), null);
            }
            Ast.TypeRef unknown = Ast.TypeRef.inferred();
            Ast.TypeRef type = name.name().equals("Ok")
                    ? new Ast.TypeRef("Result", List.of(payload.type, unknown), false)
                    : new Ast.TypeRef("Result", List.of(unknown, payload.type), false);
            return new ValueInfo(type, ValueKind.MOVE_ONLY, null);
        }

        if (call.callee() instanceof Ast.NameExpr name
                && scope.lookup(name.name()) == null) {
            Ast.FunctionDecl fn = findFunction(
                    name.name(), syntacticCallArgumentTypes(call.arguments(), scope).size());
            if (fn != null) {
                CallSignature signature = specializeCall(
                        fn.genericParameters(), fn.genericParameters(),
                        fn.parameters(), fn.returnType(), call, scope, Map.of(), fn.genericSpecs());
                if (fn.suspends()) checkSuspensionBorrows(scope, "suspends call " + fn.name());
                checkArguments(call.arguments(), signature.parameters(), scope, "function " + fn.name());
                Ast.TypeRef callResult = callableResultType(signature.result(), fn.async(), fn.generator());
                return new ValueInfo(callResult, kindOfType(callResult), null);
            }
        }

        if (call.callee() instanceof Ast.MemberExpr qualified
                && qualified.receiver() instanceof Ast.NameExpr namespace) {
            Ast.FunctionDecl fn = findFunction(
                    namespace.name() + "." + qualified.member(),
                    syntacticCallArgumentTypes(call.arguments(), scope).size());
            if (fn != null) {
                CallSignature signature = specializeCall(
                        fn.genericParameters(), fn.genericParameters(),
                        fn.parameters(), fn.returnType(), call, scope, Map.of(), fn.genericSpecs());
                if (fn.suspends()) checkSuspensionBorrows(scope, "suspends call " + fn.name());
                checkArguments(
                        call.arguments(),
                        signature.parameters(),
                        scope,
                        "function " + namespace.name() + "." + qualified.member());
                Ast.TypeRef callResult = callableResultType(signature.result(), fn.async(), fn.generator());
                return new ValueInfo(callResult, kindOfType(callResult), null);
            }
        }

        if (call.callee() instanceof Ast.MemberExpr member) {
            ValueInfo sumCall = checkBuiltinSumCall(member, call.arguments(), scope);
            if (sumCall != null) return sumCall;
            ValueInfo stringCall = checkBuiltinStringCall(member, call.arguments(), scope);
            if (stringCall != null) return stringCall;
            // Native collection mutations are effects on the receiver, not
            // harmless reads. TypeChecker validates method availability/arity.
            // A const/let read-only view must not gain mutation via .add/.set.
            Ast.TypeRef memberReceiverType = receiverType(member.receiver(), scope);
            if (memberReceiverType == null
                    && (member.member().equals("add") || member.member().equals("set"))
                    && (member.receiver() instanceof Ast.MemberExpr
                        || member.receiver() instanceof Ast.IndexExpr)) {
                // Nested collection receivers are mutable *places*, not opaque
                // host callables. Resolve the owning root so a live projected
                // loan cannot be bypassed by holder.items.add(...) or set(...).
                memberReceiverType = borrowPlace(member.receiver(), scope).type();
            }
            if (memberReceiverType != null) {
                Ast.TypeRef concreteCollection = memberReceiverType.isBorrow()
                        ? memberReceiverType.borrowedTarget() : memberReceiverType;
                if ((concreteCollection.name().equals("Array")
                        || concreteCollection.name().equals("List"))
                        && (member.member().equals("add") || member.member().equals("set"))) {
                    ensureMutableReceiver(
                            member.receiver(),
                            scope,
                            "collection method '" + member.member() + "'");
                }
            }

            if (member.receiver() instanceof Ast.NameExpr receiverName
                    && receiverName.name().equals("self")
                    && member.member().equals("endWithCleanup")) {
                VarState actorSelf = scope.lookup("self");
                if (actorSelf != null) {
                    actorSelf.debugName = "self";
                }
                if (actorSelf != null && isActorConfinedBorrow(actorSelf)) {
                    if (call.arguments().size() == 1
                            && call.arguments().getFirst() instanceof Ast.LambdaExpr cleanup) {
                        checkLambda(cleanup, scope, null, true);
                    } else {
                        for (Ast.Expr argument : call.arguments()) {
                            checkExpr(argument, scope, false);
                        }
                    }
                    return new ValueInfo(Ast.TypeRef.simple("void"), ValueKind.COPY, null);
                }
            }

            Ast.ClassDecl staticClass = classNamespaceOf(member.receiver(), scope);
            if (staticClass != null) {
                Ast.MethodDecl staticFunction = findStaticMethod(
                        staticClass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                if (staticFunction != null) {
                    CallSignature signature = specializeCall(
                            staticFunction.genericParameters(),
                            staticFunction.genericParameters(),
                            staticFunction.parameters(),
                            staticFunction.returnType(),
                            call,
                            scope,
                            Map.of());
                    if (staticFunction.suspends()) checkSuspensionBorrows(scope, "suspends call " + staticFunction.name());
                    checkArguments(
                            call.arguments(),
                            signature.parameters(),
                            scope,
                            "static function " + staticClass.name() + "." + staticFunction.name());
                    Ast.TypeRef callResult = callableResultType(signature.result(), staticFunction.async(), false);
                    return new ValueInfo(callResult, kindOfType(callResult), null);
                }
            }

            if (member.receiver() instanceof Ast.NameExpr receiverName) {
                VarState receiverState = scope.lookup(receiverName.name());
                if (receiverState != null) {
                    requireUsable(receiverState, receiverName.name(), false);
                    Ast.TypeRef receiverType = receiverState.type;
                    if ((receiverType.name().equals("Mutex") || receiverType.name().equals("SharedMutex"))
                            && receiverType.arguments().size() == 1) {
                        Ast.TypeRef element = receiverType.arguments().getFirst();
                        if (member.member().equals("lock") && call.arguments().isEmpty()) {
                            return new ValueInfo(new Ast.TypeRef("MutexGuard", List.of(element), false), ValueKind.MOVE_ONLY, null);
                        }
                        if (member.member().equals("try_lock") && call.arguments().isEmpty()) {
                            Ast.TypeRef guard = new Ast.TypeRef("MutexGuard", List.of(element), false);
                            return new ValueInfo(new Ast.TypeRef("Option", List.of(guard), false), ValueKind.MOVE_ONLY, null);
                        }
                        if (member.member().equals("lock_async") && call.arguments().isEmpty()) {
                            Ast.TypeRef guard = new Ast.TypeRef("MutexGuard", List.of(element), false);
                            return new ValueInfo(new Ast.TypeRef("Future", List.of(guard), false), ValueKind.MOVE_ONLY, null);
                        }
                        if ((member.member().equals("with_lock") || member.member().equals("recover"))
                                && call.arguments().size() == 1) {
                            if (!(call.arguments().getFirst() instanceof Ast.LambdaExpr lambda)) {
                                throw error(member.member()
                                        + " requires an inline lambda so protected mutex state remains lexical");
                            }
                            if (member.member().equals("recover") && !receiverType.name().equals("SharedMutex")) {
                                throw error("recover is only available on SharedMutex<T>");
                            }
                            if (lambda.parameters().size() != 1) {
                                throw error(member.member() + " callback must accept exactly one protected-value parameter");
                            }
                            Ast.Param original = lambda.parameters().getFirst();
                            Ast.Param protectedParam = new Ast.Param(
                                    Ast.TypeRef.borrowed(element, true),
                                    original.name(),
                                    original.structural(),
                                    false);
                            Ast.LambdaExpr protectedLambda = new Ast.LambdaExpr(
                                    List.of(protectedParam), lambda.expressionBody(), lambda.blockBody());
                            mutexCriticalSectionDepth++;
                            try {
                                checkLambda(protectedLambda, scope, null);
                            } finally {
                                mutexCriticalSectionDepth--;
                            }
                            return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
                        }
                    }
                    if (isMutexGuardType(receiverType) && member.member().equals("release") && call.arguments().isEmpty()) {
                        move(receiverState, receiverName.name());
                        return new ValueInfo(Ast.TypeRef.simple("void"), ValueKind.COPY, null);
                    }
                    if (isMutexGuardType(receiverType) && member.member().equals("is_released") && call.arguments().isEmpty()) {
                        return new ValueInfo(Ast.TypeRef.simple("bool"), ValueKind.COPY, null);
                    }
                    if (isMutexGuardType(receiverType)) {
                        Ast.TypeRef protectedType = receiverType.arguments().getFirst();
                        if ((protectedType.name().equals("List") || protectedType.name().equals("Array"))
                                && protectedType.arguments().size() == 1) {
                            Ast.TypeRef element = protectedType.arguments().getFirst();
                            switch (member.member()) {
                                case "size" -> {
                                    if (call.arguments().isEmpty()) {
                                        return new ValueInfo(Ast.TypeRef.simple("int"), ValueKind.COPY, null);
                                    }
                                }
                                case "get" -> {
                                    if (call.arguments().size() == 1) {
                                        checkExpr(call.arguments().getFirst(), scope, false);
                                        if (!isCopyType(element)) {
                                            throw error("cannot extract move-only List element through a MutexGuard; "
                                                    + "mutate it under the guard or replace it as a whole");
                                        }
                                        return new ValueInfo(element, ValueKind.COPY, null);
                                    }
                                }
                                case "add" -> {
                                    if (call.arguments().size() == 1) {
                                        ValueInfo value = checkExpr(call.arguments().getFirst(), scope, true);
                                        if (containsMutexGuardType(value.type)) {
                                            throw error("MutexGuard cannot be stored inside protected List state");
                                        }
                                        return new ValueInfo(Ast.TypeRef.simple("void"), ValueKind.COPY, null);
                                    }
                                }
                                case "set" -> {
                                    if (call.arguments().size() == 2) {
                                        checkExpr(call.arguments().get(0), scope, false);
                                        ValueInfo value = checkExpr(call.arguments().get(1), scope, true);
                                        if (containsMutexGuardType(value.type)) {
                                            throw error("MutexGuard cannot be stored inside protected List state");
                                        }
                                        return new ValueInfo(Ast.TypeRef.simple("void"), ValueKind.COPY, null);
                                    }
                                }
                                default -> {
                                    // Fall through to class/unknown guarded-member handling.
                                }
                            }
                        }
                    }
                }
            }
            ValueInfo receiverValue = checkExpr(member.receiver(), scope, false);
            Ast.TypeRef concreteReceiver = receiverType(member.receiver(), scope);
            if (concreteReceiver == null) {
                concreteReceiver = receiverValue.type.isBorrow()
                        ? receiverValue.type.borrowedTarget() : receiverValue.type;
            }
            Ast.ClassDecl klass = concreteReceiver == null ? null : findClass(concreteReceiver.name());
            ResolvedMethod target = klass == null ? null
                    : findMethodTarget(klass, concreteReceiver, member.member(), call.arguments().size(), new LinkedHashSet<>());
            if (target != null) {
                Ast.MethodDecl method = target.method();
                if (AnnotationExpander.isGeneratedFromJsonSetter(method)) {
                    ensureMutableReceiver(
                            member.receiver(),
                            scope,
                            "generated JSON setter '" + method.name() + "'");
                }
                if (method.explicitReceiverType() != null
                        && method.explicitReceiverType().isBorrow()
                        && method.explicitReceiverType().mutableBorrow()) {
                    ensureMutableReceiver(
                            member.receiver(),
                            scope,
                            "mutable method receiver '" + method.name() + "'");
                }
                boolean protectedReceiver = false;
                if (member.receiver() instanceof Ast.NameExpr receiverName) {
                    VarState receiverState = scope.lookup(receiverName.name());
                    protectedReceiver = receiverState != null && isScopedMutexReceiver(receiverState);
                }
                Map<String, Ast.TypeRef> ownerBindings = genericBindings(
                        target.owner().genericParameters(), target.ownerType().arguments());
                List<String> allGenerics = new ArrayList<>(target.owner().genericParameters());
                allGenerics.addAll(method.genericParameters());
                CallSignature signature = specializeCall(
                        allGenerics, method.genericParameters(),
                        method.parameters(), method.returnType(), call, scope, ownerBindings);
                if (method.suspends()) checkSuspensionBorrows(scope, "suspends call " + method.name());
                if (method.explicitReceiverType() != null
                        && method.explicitReceiverType().isBorrow()
                        && method.explicitReceiverType().mutableBorrow()
                        && member.receiver() instanceof Ast.NameExpr receiverName
                        && scope.lookup(receiverName.name()) != null) {
                    // The implicit mutable receiver is the first live exclusive
                    // argument, even though it is not in call.arguments().
                    // Otherwise q.method(rt mut borrow q) aliases a writable
                    // receiver within one invocation.
                    VarState receiver = requireState(scope, receiverName.name());
                    beginPersistentBorrow(receiver, true);
                    VarState root = ownershipRoot(receiver);
                    receiverReadReservations.merge(root, 1, Integer::sum);
                    try {
                        checkArguments(call.arguments(), signature.parameters(), scope, "method " + method.name());
                    } finally {
                        int left = receiverReadReservations.get(root) - 1;
                        if (left == 0) receiverReadReservations.remove(root);
                        else receiverReadReservations.put(root, left);
                        if (!root.mutableBorrowed)
                            throw new IllegalStateException("missing mutable method receiver loan");
                        root.mutableBorrowed = false;
                    }
                } else if (member.receiver() instanceof Ast.NameExpr receiverName
                        && scope.lookup(receiverName.name()) != null) {
                    // A read-only method has an implicit &self loan. That
                    // shared loan must remain live while later arguments
                    // are evaluated: q.inspect(&mut q) and q.read(q.bump())
                    // must not alias/mutate the receiver.
                    VarState receiver = requireState(scope, receiverName.name());
                    VarState root = ownershipRoot(receiver);
                    if (receiverReadReservations.containsKey(root)) {
                        // A two-phase mutable receiver already reserves this
                        // root. Nested read-only receiver calls are permitted
                        // while building an argument, but no write or new
                        // exclusive loan may occur before outer activation.
                        checkArguments(call.arguments(), signature.parameters(), scope, "method " + method.name());
                    } else {
                        beginPersistentBorrow(receiver, false);
                        try {
                            checkArguments(call.arguments(), signature.parameters(), scope, "method " + method.name());
                        } finally {
                            if (root.immutableBorrows <= 0)
                                throw new IllegalStateException("missing shared method receiver loan");
                            root.immutableBorrows--;
                        }
                    }
                } else {
                    checkArguments(call.arguments(), signature.parameters(), scope, "method " + method.name());
                }
                if (protectedReceiver && !isCopyType(signature.result())
                        && !signature.result().name().equals("void")) {
                    throw error("method '" + target.owner().name() + "." + method.name()
                            + "' cannot return move-only state through a mutex guard/critical-section borrow");
                }
                Ast.TypeRef result = signature.result().name().equals("self")
                        ? concreteReceiver : signature.result();
                Ast.TypeRef callResult = callableResultType(result, method.async(), false);
                return new ValueInfo(callResult, kindOfType(callResult), null);
            }
        }

        ValueInfo callee = checkExpr(call.callee(), scope, false);
        Ast.TypeRef callableType = callee.type;
        if (callableType != null
                && !callableType.isBorrow()
                && (callableType.name().equals("Fnc") || callableType.name().equals("Function"))
                && !callableType.arguments().isEmpty()) {
            int parameterCount = callableType.arguments().size() - 1;
            if (parameterCount == call.arguments().size()) {
                for (int i = 0; i < call.arguments().size(); i++) {
                    ValueInfo argument = checkExpr(call.arguments().get(i), scope, false);
                    if (containsMutexGuardType(argument.type)) {
                        throw error("guard-bearing values cannot cross a first-class Fnc call boundary");
                    }
                    if (argument.kind == ValueKind.MUT_BORROW) {
                        throw error("ordinary Fnc<T,...> parameters are read-only call borrows; use an explicit mutable callable contract when that surface lands");
                    }
                }
                Ast.TypeRef result = callableType.arguments().getLast();
                return new ValueInfo(result, kindOfType(result), null);
            }
        }

        // Unknown host/dynamic callables remain conservative and consuming.
        for (Ast.Expr arg : call.arguments()) {
            ValueInfo argument = checkExpr(arg, scope, true);
            if (containsMutexGuardType(argument.type)) {
                throw error("guard-bearing values cannot cross an arbitrary call boundary");
            }
        }
        return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
    }

    private boolean isBooleanIntrinsicCall(Ast.CallExpr call, Scope scope) {
        if (call.callee() instanceof Ast.NameExpr name) {
            return isBooleanIntrinsicName(name.name())
                    && scope.lookup(name.name()) == null
                    && !functions.containsKey(name.name())
                    && !ambiguousFunctions.contains(name.name());
        }
        return call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr namespace
                && namespace.name().equals("BooleanOps")
                && isBooleanIntrinsicName(member.member())
                && scope.lookup("BooleanOps") == null
                && !modules.contains("BooleanOps");
    }

    private static boolean isBooleanIntrinsicName(String name) {
        return name.equals("And") || name.equals("Or") || name.equals("Xor");
    }

    private static boolean isBuiltinFormattingCall(Ast.CallExpr call, Scope scope) {
        if (!(call.callee() instanceof Ast.MemberExpr member)) return false;
        if (member.receiver() instanceof Ast.NameExpr namespace
                && namespace.name().equals("stdio") && scope.lookup("stdio") == null) {
            return member.member().equals("print") || member.member().equals("println");
        }
        return isBuiltinStdoutCall(call, "write", scope)
                || isBuiltinStdoutCall(call, "println", scope)
                || isBuiltinStdoutCall(call, "log", scope)
                || isBuiltinStdoutCall(call, "logList", scope);
    }

    private void checkFormattingArguments(List<Ast.Expr> arguments, Scope scope) {
        List<VarState> loans = new ArrayList<>();
        try {
            for (Ast.Expr argument : arguments) {
                Ast.Expr value = argument instanceof Ast.SpreadExpr spread ? spread.expression() : argument;
                int prefixLoans = loans.size();
                Ast.TypeRef element = formattingCollectionElement(value, scope);
                if (element != null && value instanceof Ast.CallExpr call
                        && call.callee() instanceof Ast.MemberExpr member) {
                    Set<VarState> receivers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                    collectFormattingRoots(member.receiver(), scope, receivers);
                    for (VarState receiver : receivers) {
                        beginPersistentBorrow(receiver, false);
                        loans.add(receiver);
                    }
                }
                ValueInfo info = checkExpr(value, scope, false);
                // The generic host-call fallback cannot distinguish Copy
                // collection elements from views. Use the checked element type
                // for formatting without changing collection transfer rules.
                if (element != null) info = new ValueInfo(element, kindOfType(element), null);
                if (containsMutexGuardType(info.type))
                    throw error("MutexGuard cannot be formatted or logged");
                if (info.kind == ValueKind.COPY && info.borrowSource == null) {
                    releaseFormattingLoans(loans, prefixLoans);
                    continue;
                }
                Set<VarState> roots = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                collectFormattingRoots(value, scope, roots);
                if (roots.isEmpty() && info.borrowSource != null)
                    roots.add(ownershipRoot(info.borrowSource));
                if (roots.isEmpty() && isBorrowValue(info))
                    throw error("formatting a borrowed result requires proven lifetime provenance");
                for (VarState root : roots) {
                    beginPersistentBorrow(root, false);
                    loans.add(root);
                }
            }
        } finally {
            // The evaluator retains all argument values before formatting.
            // Keep earlier values frozen across later argument effects, and
            // release temporary loans on both success and validation failure.
            releaseFormattingLoans(loans, 0);
        }
    }

    private static void releaseFormattingLoans(List<VarState> loans, int prefix) {
        while (loans.size() > prefix) {
            VarState root = loans.removeLast();
            if (root.immutableBorrows <= 0)
                throw new IllegalStateException("missing formatting argument loan");
            --root.immutableBorrows;
        }
    }

    private Ast.TypeRef formattingCollectionElement(Ast.Expr value, Scope scope) {
        if (!(value instanceof Ast.CallExpr call)
                || !(call.callee() instanceof Ast.MemberExpr member)
                || !member.member().equals("get") || call.arguments().size() != 1) return null;
        Ast.TypeRef receiver = receiverType(member.receiver(), scope);
        if (receiver == null && (member.receiver() instanceof Ast.MemberExpr
                || member.receiver() instanceof Ast.IndexExpr)) {
            receiver = borrowPlace(member.receiver(), scope).type();
            if (receiver.isBorrow()) receiver = receiver.borrowedTarget();
        }
        if (receiver == null || receiver.arguments().size() != 1
                || !(receiver.name().equals("Array") || receiver.name().equals("List"))) return null;
        return receiver.arguments().getFirst();
    }

    private void collectFormattingRoots(Ast.Expr value, Scope scope, Set<VarState> roots) {
        if (value instanceof Ast.ConditionalExpr conditional) {
            collectFormattingRoots(conditional.whenTrue(), scope, roots);
            collectFormattingRoots(conditional.whenFalse(), scope, roots);
        } else if (value instanceof Ast.MatchExpr match) {
            if (!isCopyType(syntacticType(match.subject(), scope)))
                collectFormattingRoots(match.subject(), scope, roots);
            for (Ast.MatchValueArm arm : match.arms())
                collectFormattingRoots(arm.value(), scope, roots);
        } else if (value instanceof Ast.AssignExpr assignment) {
            // Assignment evaluates to the newly installed value, which can
            // alias its target while later format arguments are evaluated.
            collectFormattingRoots(assignment.target(), scope, roots);
        } else if (value instanceof Ast.CastExpr cast) {
            collectFormattingRoots(cast.value(), scope, roots);
        } else if (value instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.MemberExpr member) {
                Set<VarState> receivers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                collectFormattingRoots(member.receiver(), scope, receivers);
                Ast.TypeRef receiver = receiverType(member.receiver(), scope);
                for (VarState root : receivers) {
                    // Owned sum/Future extraction can consume the carrier;
                    // its returned value no longer borrows that dead slot.
                    if (root.moved && receiver != null
                            && Set.of("Option", "Result", "Future").contains(receiver.name())) continue;
                    roots.add(root);
                }
            } else {
                collectFormattingRoots(call.callee(), scope, roots);
            }
            for (Ast.Expr argument : call.arguments()) {
                if (argument instanceof Ast.RuntimeCallExpr runtime && runtime.operation().equals("borrow")
                        || argument instanceof Ast.UnaryExpr unary
                            && (unary.operator().equals("&") || unary.operator().equals("&mut"))
                        || syntacticType(argument, scope).isBorrow()) {
                    collectFormattingRoots(argument, scope, roots);
                }
            }
        } else if (value instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            if (state != null) roots.add(ownershipRoot(state));
        } else if (value instanceof Ast.MemberExpr || value instanceof Ast.IndexExpr) {
            // Only a stable, checked projection may borrow an existing owner.
            roots.add(ownershipRoot(borrowPlace(value, scope).owner()));
        } else {
            VarState root = invocationLoanRoot(value, scope);
            if (root != null) roots.add(root);
        }
    }

    private static boolean isBuiltinStdoutCall(Ast.CallExpr call, String memberName, Scope scope) {
        if (!(call.callee() instanceof Ast.MemberExpr member) || !member.member().equals(memberName)) return false;
        if (!(member.receiver() instanceof Ast.MemberExpr stdout) || !stdout.member().equals("stdout")) return false;
        return stdout.receiver() instanceof Ast.NameExpr stdio
                && stdio.name().equals("stdio")
                && scope.lookup("stdio") == null;
    }

    /**
     * Ownership-side mirror of TypeChecker's built-in String signatures.
     *
     * Keep this deliberately narrow: it exists so copy-safe results such as
     * split_literal(...): List<String> retain their element ownership type.
     * Unknown/dynamic member calls remain conservative and move-only.
     */
    private ValueInfo checkBuiltinStringCall(
            Ast.MemberExpr member,
            List<Ast.Expr> arguments,
            Scope scope) {
        Ast.TypeRef receiver = receiverType(member.receiver(), scope);
        if (receiver == null
                || !(receiver.name().equals("string") || receiver.name().equals("String"))) {
            return null;
        }

        int arity;
        Ast.TypeRef result;
        switch (member.member()) {
            case "replace_literal" -> {
                arity = 2;
                result = Ast.TypeRef.simple("String");
            }
            case "split_literal" -> {
                arity = 1;
                result = new Ast.TypeRef(
                        "List", List.of(Ast.TypeRef.simple("String")), false);
            }
            case "trim" -> {
                arity = 0;
                result = Ast.TypeRef.simple("String");
            }
            case "is_identifier", "is_route_segment" -> {
                arity = 0;
                result = Ast.TypeRef.simple("bool");
            }
            case "starts_with", "ends_with", "contains_literal" -> {
                arity = 1;
                result = Ast.TypeRef.simple("bool");
            }
            default -> {
                return null;
            }
        }
        if (arguments.size() != arity) {
            throw error("built-in String." + member.member()
                    + " expects " + arity + " argument(s), got " + arguments.size());
        }

        checkExpr(member.receiver(), scope, false);
        for (Ast.Expr argument : arguments) {
            checkExpr(argument, scope, false);
        }
        return new ValueInfo(result, kindOfType(result), null);
    }

    private ValueInfo checkBuiltinSumCall(Ast.MemberExpr member, List<Ast.Expr> arguments, Scope scope) {
        boolean query = member.member().equals("is_some") || member.member().equals("is_none")
                || member.member().equals("is_ok") || member.member().equals("is_err");
        boolean extracting = member.member().equals("unwrap") || member.member().equals("unwrap_safe")
                || member.member().equals("unwrap_all") || member.member().equals("flatten")
                || member.member().equals("expect") || member.member().equals("unwrap_or")
                || member.member().equals("unwrap_or_else")
                || member.member().equals("or_else") || member.member().equals("ok_or");
        if (!query && !extracting) return null;
        // A nested call has no reliably resolved result type in this checker
        // until TypeChecker supplies its typed IR. Do not inspect it here and
        // then again in generic method dispatch: that double-checks affine
        // lambda captures, and can manufacture spurious use-after-move errors.
        // TypeChecker still verifies the exact flatten receiver/result type.
        if (member.member().equals("flatten")
                && member.receiver() instanceof Ast.CallExpr) {
            return null;
        }
        // New nested-only names must not cause a non-Option member receiver
        // to be ownership-checked twice on the ordinary method-call path.
        if (member.member().equals("flatten") || member.member().equals("unwrap_all")) {
            Ast.TypeRef hint = receiverType(member.receiver(), scope);
            if (hint != null
                    && !(hint.name().equals("Option") && hint.arguments().size() == 1)
                    && !(member.member().equals("flatten")
                            && hint.name().equals("Future")
                            && hint.arguments().size() == 1)) {
                return null;
            }
        }

        ValueInfo receiver = checkExpr(member.receiver(), scope, false);
        Ast.TypeRef receiverType = receiver.type;
        // Dereferencing an Option/Result borrow must never transfer its affine
        // payload. The type checker resolves member return types after deref,
        // so the ownership checker must enforce this independently; otherwise
        // (&Option<Box>).unwrap() could duplicate the borrowed Box owner.
        // Read-only queries and extraction from a wholly copyable sum remain
        // valid, since neither transfers any owned state.
        if (receiverType != null && receiverType.isBorrow()) {
            Ast.TypeRef target = receiverType.borrowedTarget();
            if (member.member().equals("flatten")
                    && target.name().equals("Future")
                    && target.arguments().size() == 1) {
                throw error("Future.flatten cannot consume a borrowed Future");
            }
            boolean borrowedSum = (target.name().equals("Option")
                    && target.arguments().size() == 1)
                    || (target.name().equals("Result")
                    && target.arguments().size() == 2);
            if (borrowedSum && extracting && !isCopyType(target)) {
                throw error(member.member()
                        + " cannot consume an affine Option/Result through a borrow");
            }
            return null;
        }
        // Analyze each receiver exactly once. Previously a Future returned
        // from a call was checked here and again on the ordinary call path,
        // potentially consuming affine captures twice during compilation.
        if (member.member().equals("flatten") && receiverType != null) {
            Ast.TypeRef concrete = receiverType.isBorrow()
                    ? receiverType.borrowedTarget() : receiverType;
            if (concrete.name().equals("Future") && concrete.arguments().size() == 1) {
                if (receiverType.isBorrow()) {
                    throw error("Future.flatten cannot consume a borrowed Future");
                }
                Ast.TypeRef inner = concrete.arguments().getFirst();
                if (!inner.name().equals("Future") || inner.arguments().size() != 1) {
                    throw error("Future.flatten requires Future<Future<T>>");
                }
                if (!arguments.isEmpty()) {
                    throw error("Future.flatten expects no arguments");
                }
                if (member.receiver() instanceof Ast.NameExpr name) {
                    VarState owner = scope.lookup(name.name());
                    if (owner != null && owner.kind == ValueKind.MOVE_ONLY) {
                        move(owner, name.name());
                    }
                } else if (receiver.kind == ValueKind.MOVE_ONLY
                        && (member.receiver() instanceof Ast.MemberExpr
                            || member.receiver() instanceof Ast.IndexExpr)) {
                    throw error("Future.flatten cannot consume an owned Future projection; "
                            + "move the Future into a named local first");
                }
                return new ValueInfo(inner, kindOfType(inner), null);
            }
        }
        if (receiverType == null || receiverType.isBorrow()) return null;
        boolean option = receiverType.name().equals("Option") && receiverType.arguments().size() == 1;
        boolean result = receiverType.name().equals("Result") && receiverType.arguments().size() == 2;
        if (!option && !result) return null;
        if (member.member().equals("flatten") && !option) return null;

        if (query) {
            for (Ast.Expr argument : arguments) checkExpr(argument, scope, true);
            return new ValueInfo(Ast.TypeRef.simple("bool"), ValueKind.COPY, null);
        }

        // An affine sum payload cannot be taken from a projected field/index
        // until projection-level move tracking is available. A read-only check
        // of holder.option would otherwise allow repeated extraction.
        // Copyable Option/Result projections remain safe to observe/extract.
        if (receiver.kind == ValueKind.MOVE_ONLY
                && (member.receiver() instanceof Ast.MemberExpr
                    || member.receiver() instanceof Ast.IndexExpr)) {
            throw error(member.member()
                    + " cannot consume an affine Option/Result projection; "
                    + "move the owned value into a named local first");
        }

        if (member.receiver() instanceof Ast.NameExpr receiverName) {
            VarState state = scope.lookup(receiverName.name());
            if (state != null && state.kind == ValueKind.MOVE_ONLY) move(state, receiverName.name());
        }

        Ast.TypeRef okType = option ? receiverType.arguments().getFirst() : receiverType.arguments().get(0);
        // The first ergonomic API slice deliberately admits only Copy values.
        // Affine payloads and error values require branch-sensitive closure
        // ownership/drop proofs before lazy callbacks may carry them.
        // Result.or_else predates Option.or_else and is an affine-consuming
        // Result combinator, not a Copy-only Option fallback. In particular
        // Result<Box,E>.or_else must not be silently rejected or checked twice.
        boolean newCopyOnlyFallback = member.member().equals("unwrap_or_else")
                || member.member().equals("ok_or")
                || (option && member.member().equals("or_else"));
        if (newCopyOnlyFallback && (!isCopyType(okType)
                || (result && !isCopyType(receiverType.arguments().get(1))))) {
            throw error(member.member()
                    + " requires Copy Option/Result payloads until affine fallback ownership is proven");
        }
        if (okType.isBorrow()) {
            throw error(member.member()
                    + " cannot extract a borrow from an owned Option/Result until explicit lifetime parameters are supported");
        }

        if (member.member().equals("unwrap_all")) {
            // Every nested Option is consumed by extraction; do not return an
            // intermediate wrapper or permit a borrowed leaf to escape.
            while (okType.name().equals("Option") && okType.arguments().size() == 1) {
                okType = okType.arguments().getFirst();
            }
            if (okType.isBorrow()) {
                throw error("unwrap_all cannot extract a borrow from an owned nested Option");
            }
        }

        if (member.member().equals("flatten")) {
            if (!okType.name().equals("Option") || okType.arguments().size() != 1) {
                throw error("Option.flatten requires Option<Option<T>>");
            }
        }

        if (member.member().equals("unwrap_or") && containsMutexGuardType(okType)) {
            throw error("unwrap_or cannot eagerly discard a MutexGuard fallback; use explicit branching so every guard is released");
        }
        Ast.TypeRef okOrErrorType = null;
        for (Ast.Expr argument : arguments) {
            ValueInfo arg = checkExpr(argument, scope, true);
            if (containsMutexGuardType(arg.type) && !member.member().equals("unwrap_or")) {
                throw error(member.member() + " argument cannot contain MutexGuard");
            }
            if (member.member().equals("ok_or")) {
                if (arg.type == null || arg.type.isBorrow() || !isCopyType(arg.type)) {
                    throw error("Option.ok_or eager error must be a concrete owned Copy value");
                }
                okOrErrorType = arg.type;
            }
        }
        if (member.member().equals("ok_or")) {
            if (!option || okOrErrorType == null) {
                throw error("Option.ok_or requires one eager error value");
            }
            Ast.TypeRef resultType = new Ast.TypeRef(
                    "Result", List.of(okType, okOrErrorType), false);
            return new ValueInfo(resultType, kindOfType(resultType), null);
        }
        if (member.member().equals("or_else")) {
            // Both Option.or_else and Result.or_else keep the sum carrier.
            // Never return null after checking/consuming the receiver: the
            // ordinary member-call path would evaluate and consume it again.
            return new ValueInfo(receiverType, kindOfType(receiverType), null);
        }

        if (member.member().equals("unwrap_safe")) {
            if (option) {
                Ast.TypeRef safe = new Ast.TypeRef(
                        "Result",
                        List.of(okType, Ast.TypeRef.simple("OptionUnwrapError")),
                        false);
                return new ValueInfo(safe, kindOfType(safe), null);
            }
            return new ValueInfo(receiverType, kindOfType(receiverType), null);
        }
        return new ValueInfo(okType, kindOfType(okType), null);
    }

    /** Roots whose mutable method receiver is reserved until argument evaluation ends. */
    private final Map<VarState, Integer> receiverReadReservations = new IdentityHashMap<>();

    // A borrow argument becomes live as soon as its expression is evaluated
    // and remains live until the complete invocation has been validated.
    // Argument-root comparison alone does not protect f(&mut q, q.method()).
    private record CallArgumentLoan(VarState root, boolean mutable) { }

    private void checkArguments(List<Ast.Expr> arguments, List<Ast.Param> params, Scope scope, String callable) {
        int parameterIndex = 0;
        Map<VarState, Boolean> invocationLoans = new IdentityHashMap<>();
        List<CallArgumentLoan> activeLoans = new ArrayList<>();
        try {
            for (Ast.Expr argument : arguments) {
                if (argument instanceof Ast.SpreadExpr spread) {
                    Ast.TypeRef spreadType = syntacticType(spread.expression(), scope);
                    if (spreadType.isBorrow()) spreadType = spreadType.borrowedTarget();
                    if (!spreadType.isTupleType()) {
                        throw error(callable
                                + " spread requires a statically known tuple; runtime-length collections "
                                + "need an explicitly variadic callable");
                    }

                    int width = spreadType.arguments().size();
                    if (parameterIndex + width > params.size()) return; // TypeChecker reports arity.
                    for (int i = 0; i < width; i++) {
                        Ast.Param target = params.get(parameterIndex + i);
                        if (target.structural() || target.type().isBorrow()) {
                            throw error(callable + " argument " + (parameterIndex + i + 1)
                                    + " cannot currently receive tuple spread into a structural/borrowed parameter; "
                                    + "pass that argument explicitly");
                        }
                    }

                    ValueInfo aggregate = checkExpr(spread.expression(), scope, true);
                    if (containsMutexGuardType(aggregate.type)) {
                        throw error(callable + " spread cannot consume a guard-bearing tuple");
                    }
                    parameterIndex += width;
                    continue;
                }

                if (parameterIndex >= params.size()) return; // TypeChecker reports arity.
                Ast.Param param = params.get(parameterIndex);
                VarState loanOwner = param.type().isBorrow()
                        ? invocationLoanRoot(argument, scope) : null;
                boolean mutable = param.type().isBorrow() && param.type().mutableBorrow();
                if (loanOwner != null) {
                    Boolean previous = invocationLoans.get(loanOwner);
                    if (previous != null && (previous || mutable)) {
                        throw error(callable + " has overlapping mutable borrow arguments at position "
                                + (parameterIndex + 1));
                    }
                }
                checkArgument(argument, param, scope, callable, parameterIndex + 1);
                if (loanOwner != null) {
                    // Borrowed arguments remain live until the call completes,
                    // including forwarded incoming &mut parameters. Their
                    // incoming lifetime is longer than this specific call and
                    // is not itself protection against nested argument reads.
                    // A local retained borrow already owns a lexical lease;
                    // unlike incoming parameters it must not be double-leased.
                    boolean explicit = argument instanceof Ast.UnaryExpr unary
                            && (unary.operator().equals("&") || unary.operator().equals("&mut"));
                    explicit |= argument instanceof Ast.RuntimeCallExpr runtime
                            && runtime.operation().equals("borrow")
                            && runtime.arguments().size() == 1;
                    boolean forwarded = argument instanceof Ast.NameExpr name
                            && isIncomingBorrow(scope.lookup(name.name()));
                    if (explicit || forwarded) {
                        beginPersistentBorrow(loanOwner, mutable);
                        activeLoans.add(new CallArgumentLoan(ownershipRoot(loanOwner), mutable));
                    }
                    invocationLoans.put(loanOwner, mutable);
                }
                parameterIndex++;
            }
        } finally {
            // Restore the parent scope on every path: successful call,
            // failed argument and nested validation exception alike.
            for (int i = activeLoans.size() - 1; i >= 0; i--) {
                CallArgumentLoan loan = activeLoans.get(i);
                if (loan.mutable()) {
                    if (!loan.root().mutableBorrowed)
                        throw new IllegalStateException("missing exclusive call argument loan");
                    loan.root().mutableBorrowed = false;
                } else {
                    if (loan.root().immutableBorrows <= 0)
                        throw new IllegalStateException("missing shared call argument loan");
                    loan.root().immutableBorrows--;
                }
            }
        }
    }

    private VarState invocationLoanRoot(Ast.Expr expression, Scope scope) {
        Ast.Expr place = null;
        if (expression instanceof Ast.UnaryExpr unary
                && (unary.operator().equals("&") || unary.operator().equals("&mut"))) {
            place = unary.operand();
        } else if (expression instanceof Ast.RuntimeCallExpr runtime
                && runtime.operation().equals("borrow") && runtime.arguments().size() == 1) {
            place = runtime.arguments().getFirst();
        } else if (expression instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            return state == null ? null : ownershipRoot(state);
        }

        // Resolve a simple syntactic owner without reading it first. A loan
        // from an earlier argument may already prohibit such a read; the
        // invocation alias map must issue the stable duplicate-loan error
        // before ordinary usability checks. checkArgument validates this
        // operand after the overlap test, including its visibility/mutability.
        if (place instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            return state == null ? null : ownershipRoot(state);
        }
        return place == null ? null : ownershipRoot(borrowPlace(place, scope).owner());
    }

    private void checkArgument(
            Ast.Expr arg,
            Ast.Param param,
            Scope scope,
            String callable,
            int position) {
        if (arg instanceof Ast.RuntimeCallExpr runtime && runtime.operation().equals("borrow")
                && runtime.arguments().size() == 1) {
            arg = new Ast.UnaryExpr("&", runtime.arguments().getFirst());
        }
        if (param.structural() && !param.type().isBorrow()) {
            ValueInfo argument = checkExpr(arg, scope, false);
            if (containsMutexGuardType(argument.type)
                    || (mutexCriticalSectionDepth > 0 && argument.type.isBorrow())) {
                throw error(callable + " argument " + position
                        + " cannot consume protected mutex state through a structural by-value parameter");
            }
            return;
        }
        if (param.type().isBorrow()) {
            boolean mutable = param.type().mutableBorrow();
            if (arg instanceof Ast.UnaryExpr unary && (unary.operator().equals("&") || unary.operator().equals("&mut"))) {
                if (mutable && !unary.operator().equals("&mut")) {
                    throw error(callable + " argument " + position + " requires rt mut borrow");
                }
                BorrowPlace place = borrowPlace(unary.operand(), scope);
                if (mutable && !(unary.operand() instanceof Ast.NameExpr)) {
                    throw error("mutable projected borrows need path-exclusive loan tracking");
                }
                validateBorrow(place.owner(), mutable);
                if (isActorConfinedBorrow(place.owner())) {
                    throw error("actor self borrow cannot cross an ordinary callable boundary");
                }
                return;
            }
            if (arg instanceof Ast.NameExpr name) {
                VarState state = requireState(scope, name.name());
                requireUsable(state, name.name(), false);
                if (isActorConfinedBorrow(state)) {
                    throw error("actor self borrow cannot cross an ordinary callable boundary");
                }
                if (mutable && state.kind != ValueKind.MUT_BORROW) {
                    throw error(callable + " argument " + position + " requires a mutable borrowed value");
                }
                if (!mutable && state.kind != ValueKind.IMM_BORROW && state.kind != ValueKind.MUT_BORROW) {
                    throw error(callable + " argument " + position + " requires borrowed value; pass rt borrow " + name.name());
                }
                return;
            }
            throw error(callable + " argument " + position + " must be an explicit borrow");
        }
        ValueInfo argument = checkExpr(arg, scope, true);
        if (containsMutexGuardType(argument.type)) {
            throw error(callable + " argument " + position + " cannot consume a guard-bearing value");
        }
    }

    private void checkAssignmentTarget(Ast.Expr target, Scope scope) {
        if (target instanceof Ast.NameExpr name) {
            VarState state = requireState(scope, name.name());
            if (!state.rebindable) throw error("cannot reassign fixed binding '" + name.name() + "'; use let or let mut");
            VarState root = ownershipRoot(state);
            if (root.moved) {
                // A moved let/owned-mut binding is temporarily uninitialized;
                // assigning a new owned value is legal, but reading its old
                // value in the RHS is still rejected. Never resurrect a
                // projected/pattern alias or a borrowed capability.
                if (root != state || state.borrowSource != null
                        || state.kind != ValueKind.MOVE_ONLY
                        || state.origin == Origin.CAPTURE) {
                    throw error("cannot reinitialize moved alias/borrow '" + name.name() + "'");
                }
            } else {
                requireUsable(state, name.name(), true);
            }
            if (root.immutableBorrows > 0 || root.mutableBorrowed) {
                throw error("cannot assign '" + name.name() + "' while it is borrowed");
            }
            return;
        }
        if (target instanceof Ast.MemberExpr member) {
            Ast.ClassDecl klass = classOfReceiver(member.receiver(), scope);
            if (klass != null) {
                Ast.FieldDecl field = findField(klass, member.member(), new LinkedHashSet<>());
                if (field == null) throw error("unknown field '" + member.member() + "' on " + klass.name());
                if (field.bindingKind() != Ast.BindingKind.LET) {
                    boolean initializingFixedSlot =
                            field.initializer() == null
                            && klass == initializingClass
                            && member.receiver() instanceof Ast.NameExpr name
                            && name.name().equals("self");
                    if (!initializingFixedSlot) {
                        throw error("field '" + klass.name() + "." + member.member()
                                + "' is immutable; declare the field with let to permit mutation");
                    }
                    if (constructorFixedFieldWrites == null
                            || !constructorFixedFieldWrites.add(member.member())) {
                        throw error("field '" + klass.name() + "." + member.member()
                                + "' is immutable; constructor must initialize it at most once");
                    }
                }
            }
            ensureMutableReceiver(member.receiver(), scope, "field '" + member.member() + "'");
            return;
        }
        if (target instanceof Ast.IndexExpr indexed) {
            ensureMutableReceiver(indexed.receiver(), scope, "indexed value");
            checkExpr(indexed.index(), scope, false);
            return;
        }
        throw error("unsupported assignment target");
    }

    private void ensureMutableReceiver(Ast.Expr receiver, Scope scope, String what) {
        if (receiver instanceof Ast.NameExpr name) {
            VarState state = requireState(scope, name.name());
            requireUsable(state, name.name(), true);
            if (state.type.isReadonlyRecordType()) {
                throw error("cannot mutate readonly structural record '" + name.name() + "'");
            }
            if (isMutexGuardType(state.type)) return;
            boolean mutableBorrow = state.kind == ValueKind.MUT_BORROW || (state.type.isBorrow() && state.type.mutableBorrow());
            if (!state.mutable && !mutableBorrow) {
                throw error("cannot mutate " + what + " through read-only binding '" + name.name() + "'; use val, const mut, or let mut with valid ownership");
            }
            if (state.kind == ValueKind.IMM_BORROW || (state.type.isBorrow() && !state.type.mutableBorrow())) {
                throw error("cannot mutate " + what + " through immutable borrow '" + name.name() + "'");
            }
            if (state.kind != ValueKind.MUT_BORROW && (state.immutableBorrows > 0 || state.mutableBorrowed)) {
                throw error("cannot mutate '" + name.name() + "' while borrowed");
            }
            return;
        }
        if (receiver instanceof Ast.UnaryExpr unary && unary.operator().equals("&mut")) {
            VarState owner = borrowOwner(unary.operand(), scope);
            validateBorrow(owner, true);
            return;
        }
        if (receiver instanceof Ast.MemberExpr member) {
            // Do not silently regain mutation rights through a read-only intermediate field.
            Ast.ClassDecl klass = classOfReceiver(member.receiver(), scope);
            if (klass != null) {
                Ast.FieldDecl field = findField(klass, member.member(), new LinkedHashSet<>());
                if (field != null && !field.mutableReferent()) {
                    throw error("cannot mutate through read-only field '" + member.member() + "'");
                }
            }
            ensureMutableReceiver(member.receiver(), scope, what);
            return;
        }
        if (receiver instanceof Ast.IndexExpr indexed) {
            ensureMutableReceiver(indexed.receiver(), scope, what);
            checkExpr(indexed.index(), scope, false);
            return;
        }
        throw error("mutation target must be rooted in a mutable local/parameter or exclusive borrow");
    }

    /**
     * Resolve an immutable projection to its named storage owner and precise type.
     * The owner (not a synthetic projected alias) holds the borrow lease, so
     * moving/mutating the containing value is forbidden until the lease ends.
     *
     * Only stable field/index places are admitted: no temporary owners, method
     * invocations, side-effecting indexes, or unresolved/dynamic field types.
     * Mutable projections stay fail-closed until path-exclusive loans land.
     */
    private record BorrowPlace(VarState owner, Ast.TypeRef type) { }

    private BorrowPlace borrowPlace(Ast.Expr operand, Scope scope) {
        if (operand instanceof Ast.NameExpr name) {
            VarState owner = borrowOwner(operand, scope);
            return new BorrowPlace(owner, owner.type);
        }
        if (operand instanceof Ast.MemberExpr member) {
            BorrowPlace receiver = borrowPlace(member.receiver(), scope);
            Ast.TypeRef type = receiver.type();
            if (type.isBorrow()) type = type.borrowedTarget();
            Ast.TypeRef fieldType = null;
            if (type.isClosedAggregateType()) {
                fieldType = type.aggregateMembers().get(member.member());
            } else {
                Ast.ClassDecl klass = findClass(type.name());
                if (klass != null) {
                    ResolvedField target = findFieldTarget(
                            klass, type, member.member(), new LinkedHashSet<>());
                    if (target != null) {
                        fieldType = substituteType(ownershipFieldType(target.field()),
                                genericBindings(target.owner().genericParameters(),
                                        target.ownerType().arguments()));
                    }
                }
            }
            if (fieldType == null || fieldType.name().equals("$infer$") || fieldType.isBorrow()) {
                throw error("projected borrow requires a known owned field type for '"
                        + member.member() + "'");
            }
            return new BorrowPlace(receiver.owner(), fieldType);
        }
        if (operand instanceof Ast.IndexExpr indexed) {
            BorrowPlace receiver = borrowPlace(indexed.receiver(), scope);
            // A computed index may call a mutation/side-effect before a loan is
            // issued. Only lexical integer bindings/literals are admissible.
            if (!(indexed.index() instanceof Ast.NameExpr)
                    && !(indexed.index() instanceof Ast.LiteralExpr)) {
                throw error("projected borrow index must be a stable name or literal");
            }
            checkExpr(indexed.index(), scope, false);
            Ast.TypeRef type = receiver.type();
            if (type.isBorrow()) type = type.borrowedTarget();
            if (!((type.name().equals("Array") || type.name().equals("List"))
                    && type.arguments().size() == 1)) {
                throw error("projected borrow index requires a statically typed Array/List");
            }
            Ast.TypeRef element = type.arguments().getFirst();
            if (element.name().equals("$infer$") || element.isBorrow()) {
                throw error("projected borrow index requires a known owned element type");
            }
            return new BorrowPlace(receiver.owner(), element);
        }
        throw error("borrow requires a named owner or stable field/index projection of one");
    }

    private VarState borrowOwner(Ast.Expr operand, Scope scope) {
        if (!(operand instanceof Ast.NameExpr name)) {
            throw error("borrows currently require a named owner; borrow the binding before projecting fields/indexes");
        }
        VarState owner = requireState(scope, name.name());
        owner.debugName = name.name();
        requireUsable(owner, name.name(), false);
        return owner;
    }

    private void validateBorrow(VarState owner, boolean mutable) {
        VarState root = ownershipRoot(owner);
        if (root.moved) throw error("cannot borrow moved value '" + owner.debugName + "'");
        if (mutable) {
            if (owner.type.isReadonlyRecordType()) {
                throw error("cannot mutably borrow readonly structural record '" + owner.debugName + "'");
            }
            if (owner.kind == ValueKind.IMM_BORROW)
                throw error("cannot mutably reborrow a shared borrow '" + owner.debugName + "'");
            // An incoming exclusive loan may be reborrowed for a call, even
            // though its parameter binding itself cannot be reassigned.
            // This does not grant mutability to an owned immutable binding
            // or to any shared loan, and all live-loan conflict checks below
            // still apply.
            if (!owner.mutable && !(owner.origin == Origin.PARAM
                    && owner.kind == ValueKind.MUT_BORROW))
                throw error("cannot mutably borrow immutable owner '" + owner.debugName + "'");
            if (root.mutableBorrowed || root.immutableBorrows > 0) throw error("cannot mutably borrow '" + owner.debugName + "' while another borrow is active");
        } else if (root.mutableBorrowed) {
            throw error("cannot immutably borrow '" + owner.debugName + "' while a mutable borrow is active");
        }
    }

    private void beginPersistentBorrow(VarState owner, boolean mutable) {
        validateBorrow(owner, mutable);
        VarState root = ownershipRoot(owner);
        if (mutable) root.mutableBorrowed = true;
        else root.immutableBorrows++;
    }

    private boolean isBorrowValue(ValueInfo value) {
        return value != null
                && (value.kind == ValueKind.IMM_BORROW
                    || value.kind == ValueKind.MUT_BORROW
                    || (value.type != null && value.type.isBorrow()));
    }

    private boolean isIncomingBorrow(VarState state) {
        return state != null
                && state.origin == Origin.PARAM
                && (state.kind == ValueKind.IMM_BORROW
                    || state.kind == ValueKind.MUT_BORROW
                    || (state.type != null && state.type.isBorrow()));
    }

    private ValueInfo retainBorrowForBinding(ValueInfo value) {
        if (!isBorrowValue(value)) return value;
        if (value.borrowSource == null) {
            throw error("cannot store a borrow whose lifetime provenance is unknown; "
                    + "return explicit owned data or pass the borrow directly until explicit lifetime parameters are supported");
        }
        VarState source = ownershipRoot(value.borrowSource);
        if (value.borrowLease || isIncomingBorrow(source)) return value;

        if (value.kind == ValueKind.MUT_BORROW) {
            throw error("cannot alias a mutable borrow; transfer the existing &mut binding instead");
        }
        if (source.mutableBorrowed) {
            throw error("cannot retain immutable borrow while a mutable borrow is active");
        }
        source.immutableBorrows++;
        return new ValueInfo(value.type, value.kind, source, true);
    }

    private void validateReturnedBorrow(Ast.Expr expression, ValueInfo returned) {
        if (!isBorrowValue(returned)) return;

        VarState source = returned.borrowSource == null
                ? null
                : ownershipRoot(returned.borrowSource);
        if (source != null && isIncomingBorrow(source)) return;

        String name = source == null ? "<unknown>" : source.debugName;
        throw error("cannot return borrow rooted in '" + name
                + "'; returned borrows must originate from an incoming borrowed parameter/receiver so they cannot outlive their owner");
    }

    private ValueInfo checkLambda(Ast.LambdaExpr lambda, Scope outer, String recursiveBinding) {
        return checkLambda(lambda, outer, recursiveBinding, false);
    }

    private ValueInfo checkLambda(
            Ast.LambdaExpr lambda,
            Scope outer,
            String recursiveBinding,
            boolean allowActorSelfCapture) {
        return checkLambda(lambda, outer, recursiveBinding, allowActorSelfCapture, false);
    }

    private ValueInfo checkLambda(
            Ast.LambdaExpr lambda,
            Scope outer,
            String recursiveBinding,
            boolean allowActorSelfCapture,
            boolean deferredCleanup) {
        boolean nonLexical = lambda.nonLexical() || outer.descendantsNonLexical();
        CaptureSet captures = nonLexical ? new CaptureSet() : collectCaptures(lambda, outer, recursiveBinding);
        Scope closure = new Scope(null, nonLexical);

        for (Capture capture : captures.values.values()) {
            VarState source = capture.source;
            source.debugName = capture.name;
            requireUsable(source, capture.name, capture.write);

            if (source.kind == ValueKind.IMM_BORROW || source.kind == ValueKind.MUT_BORROW || source.type.isBorrow()) {
                if (allowActorSelfCapture
                        && capture.name.equals("self")
                        && isActorConfinedBorrow(source)) {
                    // self.endWithCleanup is an actor-owned final continuation:
                    // the mailbox is already sealed, the actor stays alive until
                    // cleanup finishes, and no later receive turn can overlap it.
                    // Preserve self as a non-escapable borrow inside this one closure.
                    closure.define(
                            capture.name,
                            new VarState(source.type, false, source.kind, Origin.CAPTURE));
                    continue;
                }
                throw error("closure cannot capture borrowed value '" + capture.name + "'; capture its owner by value or pass the borrow as a lambda parameter");
            }
            if (containsMutexGuardType(source.type)) {
                throw error("closure cannot capture guard-bearing value '" + capture.name + "'; MutexGuard values are lexical");
            }

            if (capture.write) {
                if (!source.mutable && !source.rebindable) throw error("closure cannot write read-only capture '" + capture.name + "'");
                if (source.immutableBorrows > 0 || source.mutableBorrowed) throw error("closure cannot capture '" + capture.name + "' mutably while borrowed");
                if (!deferredCleanup) move(source, capture.name);
                closure.define(
                        capture.name,
                        capturedState(source, source.mutable, source.rebindable, source.kind));
            } else if (source.kind == ValueKind.MOVE_ONLY) {
                if (!deferredCleanup) move(source, capture.name);
                closure.define(
                        capture.name,
                        capturedState(source, false, false, ValueKind.MOVE_ONLY));
            } else {
                closure.define(
                        capture.name,
                        capturedState(source, false, false, ValueKind.COPY));
            }
        }

        for (Ast.Param param : lambda.parameters()) closure.define(param.name(), stateForParam(param));
        defineImplicitArguments(closure, lambda.parameters());
        int previousLoopDepth = loopDepth;
        boolean previousDiscard = discardingSelectReturn;
        Ast.ClassDecl previousInitializingClass = initializingClass;
        loopDepth = 0;
        discardingSelectReturn = false;
        // Closures may execute after the class constructor returns; their
        // bodies cannot write fixed slots through the ctor-only exception.
        initializingClass = null;
        try {
            if (lambda.expressionBody() != null) {
                // An expression body has return-value ownership semantics even
                // though it has no explicit return statement in source.
                ValueInfo returned = checkExpr(lambda.expressionBody(), closure, true);
                if (containsMutexGuardType(returned.type)) {
                    throw error("MutexGuard values are lexical and cannot be returned from a lambda");
                }
            } else {
                for (Ast.Stmt stmt : lambda.blockBody()) {
                    checkStatement(stmt, closure, Ast.TypeRef.inferred());
                }
            }
        } finally {
            loopDepth = previousLoopDepth;
            discardingSelectReturn = previousDiscard;
            initializingClass = previousInitializingClass;
            closure.close();
        }
        return new ValueInfo(Ast.TypeRef.simple("Fnc"), ValueKind.MOVE_ONLY, null);
    }

    private CaptureSet collectCaptures(Ast.LambdaExpr lambda, Scope outer, String recursiveBinding) {
        CaptureSet captures = new CaptureSet();
        Set<String> locals = new HashSet<>();
        for (Ast.Param param : lambda.parameters()) locals.add(param.name());
        locals.add("arguments");
        if (lambda.expressionBody() != null) {
            scanExpr(lambda.expressionBody(), locals, outer, recursiveBinding, captures, false);
        } else {
            scanStatements(lambda.blockBody(), locals, outer, recursiveBinding, captures);
        }
        return captures;
    }

    private void scanStatements(List<Ast.Stmt> statements, Set<String> locals, Scope outer, String recursiveBinding, CaptureSet captures) {
        Set<String> blockLocals = new HashSet<>(locals);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                // A lambda binding is recursive: its own name is local inside
                // the lambda initializer. Without this shadowing rule, a Task
                // can falsely capture/move an unrelated outer value with the
                // same name.
                if (binding.initializer() instanceof Ast.LambdaExpr) {
                    Set<String> initializerLocals = new HashSet<>(blockLocals);
                    initializerLocals.add(binding.name());
                    scanExpr(
                            binding.initializer(),
                            initializerLocals,
                            outer,
                            recursiveBinding,
                            captures,
                            false);
                } else {
                    scanExpr(
                            binding.initializer(),
                            blockLocals,
                            outer,
                            recursiveBinding,
                            captures,
                            false);
                }
                blockLocals.add(binding.name());
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                scanExpr(destructure.initializer(), blockLocals, outer, recursiveBinding, captures, false);
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (!binding.isDiscard()) blockLocals.add(binding.name());
                }
            } else if (stmt instanceof Ast.ReturnStmt ret) {
                if (ret.value() != null) {
                    scanExpr(
                            ret.value(),
                            blockLocals,
                            outer,
                            recursiveBinding,
                            captures,
                            false);
                }
            } else if (stmt instanceof Ast.YieldStmt yielded) {
                scanExpr(yielded.value(), blockLocals, outer, recursiveBinding, captures, false);
            } else if (stmt instanceof Ast.ExprStmt e) scanExpr(e.expression(), blockLocals, outer, recursiveBinding, captures, false);
            else if (stmt instanceof Ast.DeferStmt e) scanExpr(e.expression(), blockLocals, outer, recursiveBinding, captures, false);
            else if (stmt instanceof Ast.BlockStmt s) {
                scanStatements(s.body(), blockLocals, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.TaskScopeStmt s) {
                scanStatements(s.body(), blockLocals, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.LoopStmt s) {
                scanStatements(s.body(), blockLocals, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.IfStmt s) {
                for (Ast.IfBranch b : s.branches()) {
                    scanConditionExpr(
                            b.condition(),
                            blockLocals,
                            outer,
                            recursiveBinding,
                            captures);
                    Set<String> branchLocals = new HashSet<>(blockLocals);
                    collectConditionBindingNames(b.condition(), branchLocals);
                    scanStatements(b.body(), branchLocals, outer, recursiveBinding, captures);
                }
                scanStatements(s.elseBody(), blockLocals, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.MatchStmt s) {
                scanExpr(s.subject(), blockLocals, outer, recursiveBinding, captures, false);
                for (Ast.MatchArm arm : s.arms()) {
                    Set<String> armLocals = new HashSet<>(blockLocals);
                    collectPatternBindingNames(arm.pattern(), armLocals);
                    if (arm.guard() != null) {
                        scanConditionExpr(
                                arm.guard(),
                                armLocals,
                                outer,
                                recursiveBinding,
                                captures);
                    }
                    scanStatements(arm.body(), armLocals, outer, recursiveBinding, captures);
                }
            } else if (stmt instanceof Ast.SwitchStmt s) {
                scanExpr(s.subject(), blockLocals, outer, recursiveBinding, captures, false);
                for (Ast.SwitchCase arm : s.cases()) {
                    for (Ast.Expr constant : arm.constants()) scanExpr(constant, blockLocals, outer, recursiveBinding, captures, false);
                    scanStatements(arm.body(), blockLocals, outer, recursiveBinding, captures);
                }
                scanStatements(s.defaultBody(), blockLocals, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.TryStmt s) {
                scanStatements(s.body(), blockLocals, outer, recursiveBinding, captures);
                Set<String> caught = new HashSet<>(blockLocals);
                caught.add(s.errorName());
                scanStatements(s.catchBody(), caught, outer, recursiveBinding, captures);
                scanStatements(s.finallyBody(), blockLocals, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.SelectStmt s) {
                for (Ast.SelectArm arm : s.arms()) {
                    Set<String> armLocals = new HashSet<>(blockLocals);
                    if (arm.operation() != Ast.ChannelOperation.DEFAULT) {
                        scanExpr(
                                arm.channel(),
                                blockLocals,
                                outer,
                                recursiveBinding,
                                captures,
                                false);
                    }
                    if (arm.operation() == Ast.ChannelOperation.WRITE) {
                        scanExpr(
                                arm.value(),
                                blockLocals,
                                outer,
                                recursiveBinding,
                                captures,
                                false);
                    }
                    if (arm.bindingName() != null) {
                        armLocals.add(arm.bindingName());
                    }
                    scanStatements(
                            arm.body(),
                            armLocals,
                            outer,
                            recursiveBinding,
                            captures);
                }
            } else if (stmt instanceof Ast.ForOfDestructureStmt s) {
                scanExpr(s.iterable(), blockLocals, outer, recursiveBinding, captures, false);
                Set<String> loop = new HashSet<>(blockLocals);
                for (Ast.DestructureBinding binding : s.bindings()) {
                    if (!binding.isDiscard()) loop.add(binding.name());
                }
                scanStatements(s.body(), loop, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.ForOfStmt s) {
                scanExpr(s.iterable(), blockLocals, outer, recursiveBinding, captures, false);
                Set<String> loop = new HashSet<>(blockLocals);
                loop.add(s.bindingName());
                scanStatements(s.body(), loop, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.ForStmt s) {
                // A classic for-loop initializer may declare a loop-local
                // binding. Its initializer executes in the enclosing scope and
                // can therefore capture/move an outer Task value, while the
                // declared name itself shadows any outer binding for the
                // condition, update and body.
                Set<String> loop = new HashSet<>(blockLocals);
                if (s.initializer() instanceof Ast.BindingStmt binding) {
                    Set<String> initializerLocals = blockLocals;
                    if (binding.initializer() instanceof Ast.LambdaExpr) {
                        initializerLocals = new HashSet<>(blockLocals);
                        initializerLocals.add(binding.name());
                    }
                    scanExpr(
                            binding.initializer(),
                            initializerLocals,
                            outer,
                            recursiveBinding,
                            captures,
                            false);
                    loop.add(binding.name());
                } else if (s.initializer() instanceof Ast.ExprStmt e) {
                    scanExpr(
                            e.expression(),
                            blockLocals,
                            outer,
                            recursiveBinding,
                            captures,
                            false);
                }
                if (s.condition() != null) {
                    scanExpr(s.condition(), loop, outer, recursiveBinding, captures, false);
                }
                if (s.update() != null) {
                    scanExpr(s.update(), loop, outer, recursiveBinding, captures, false);
                }
                scanStatements(s.body(), loop, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.DoWhileStmt s) {
                scanStatements(s.body(), blockLocals, outer, recursiveBinding, captures);
                scanExpr(s.condition(), blockLocals, outer, recursiveBinding, captures, false);
            } else if (stmt instanceof Ast.BreakStmt
                    || stmt instanceof Ast.ContinueStmt) {
                // No values referenced by loop-control statements.
            } else {
                throw error(
                        "unsupported Task capture statement '"
                                + stmt.getClass().getName()
                                + "'; capture analysis must fail closed");
            }
        }
    }

    private void scanExpr(Ast.Expr expr, Set<String> locals, Scope outer, String recursiveBinding, CaptureSet captures, boolean write) {
        if (expr == null || expr instanceof Ast.LiteralExpr) return;
        if (expr instanceof Ast.NameExpr name) {
            if (name.name().equals(recursiveBinding)) return;
            if (!locals.contains(name.name())) {
                VarState state = outer.lookup(name.name());
                if (state != null) captures.add(name.name(), state, write);
            }
            return;
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            scanExpr(assignment.target(), locals, outer, recursiveBinding, captures, true);
            scanExpr(assignment.value(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.MatchExpr match) {
            scanExpr(
                    match.subject(),
                    locals,
                    outer,
                    recursiveBinding,
                    captures,
                    false);
            for (Ast.MatchValueArm arm : match.arms()) {
                Set<String> armLocals = new HashSet<>(locals);
                collectPatternBindingNames(arm.pattern(), armLocals);
                if (arm.guard() != null) {
                    scanConditionExpr(
                            arm.guard(),
                            armLocals,
                            outer,
                            recursiveBinding,
                            captures);
                }
                scanExpr(
                        arm.value(),
                        armLocals,
                        outer,
                        recursiveBinding,
                        captures,
                        false);
            }
        } else if (expr instanceof Ast.TypeTestExpr e) {
            scanExpr(e.value(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.PatternTestExpr e) {
            scanExpr(e.value(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.CastExpr e) {
            scanExpr(e.value(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.BinaryExpr e) {
            scanExpr(e.left(), locals, outer, recursiveBinding, captures, false);
            scanExpr(e.right(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.TrapExpr e) scanExpr(e.expression(), locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.UnaryExpr e) scanExpr(e.operand(), locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.ConditionalExpr e) {
            scanExpr(e.condition(), locals, outer, recursiveBinding, captures, false);
            scanExpr(e.whenTrue(), locals, outer, recursiveBinding, captures, false);
            scanExpr(e.whenFalse(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.RuntimeCallExpr e) {
            for (Ast.Expr arg : e.arguments()) {
                scanExpr(arg, locals, outer, recursiveBinding, captures, false);
                // rt take is ownership consumption, not mutation. A direct
                // free-name take must therefore force MOVE admission even for
                // otherwise-Copy values, without requiring a mutable binding.
                if (e.operation().equals("take")
                        && arg instanceof Ast.NameExpr name
                        && !name.name().equals(recursiveBinding)
                        && !locals.contains(name.name())) {
                    VarState state = outer.lookup(name.name());
                    if (state != null) {
                        captures.add(name.name(), state, false, true);
                    }
                }
            }
        } else if (expr instanceof Ast.CallExpr e) {
            scanExpr(e.callee(), locals, outer, recursiveBinding, captures, false);
            for (Ast.Expr arg : e.arguments()) scanExpr(arg, locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.SpreadExpr e) {
            scanExpr(e.expression(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.MemberExpr e) scanExpr(e.receiver(), locals, outer, recursiveBinding, captures, write);
        else if (expr instanceof Ast.IndexExpr e) {
            scanExpr(e.receiver(), locals, outer, recursiveBinding, captures, write);
            scanExpr(e.index(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.NewExpr e) for (Ast.Expr arg : e.arguments()) scanExpr(arg, locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.AwaitExpr e) scanExpr(e.expression(), locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.TaskExpr e) {
            scanStatements(e.body(), locals, outer, recursiveBinding, captures);
        }
        else if (expr instanceof Ast.ChannelOpExpr e) {
            scanExpr(e.channel(), locals, outer, recursiveBinding, captures, false);
            if (e.value() != null) {
                scanExpr(e.value(), locals, outer, recursiveBinding, captures, false);
            }
            // Callback-bearing writes keep their own future activation alive.
            // Nested deferred callbacks therefore contribute transitive captures.
            if (e.callback()) {
                scanStatements(e.callbackBody(), locals, outer, recursiveBinding, captures);
            }
        }
        else if (expr instanceof Ast.DynamicSelectExpr e) {
            scanExpr(e.cases(), locals, outer, recursiveBinding, captures, false);
        }
        else if (expr instanceof Ast.ListExpr e) for (Ast.Expr item : e.elements()) scanExpr(item, locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.TupleExpr e) for (Ast.Expr item : e.elements()) scanExpr(item, locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.ObjectExpr e) {
            for (Ast.ObjectField field : e.fields()) {
                scanExpr(field.value(), locals, outer, recursiveBinding, captures, false);
            }
        }
        else if (expr instanceof Ast.LambdaExpr lambda) {
            // A lexical nested closure can reference values that belong to an
            // activation outside the current lambda. Those are transitive
            // captures of the current closure: it must keep/move them alive so
            // the nested closure can subsequently capture them from its parent.
            //
            // Parameters and locals of the current lambda are deliberately kept
            // in nestedLocals so references such as |x| -> |y| -> x + y do not
            // become captures of the outer activation. An explicit nlex nested
            // lambda is a capture barrier and therefore contributes nothing.
            if (lambda.nonLexical()) return;
            Set<String> nestedLocals = new HashSet<>(locals);
            for (Ast.Param parameter : lambda.parameters()) {
                nestedLocals.add(parameter.name());
            }
            nestedLocals.add("arguments");
            if (lambda.expressionBody() != null) {
                scanExpr(
                        lambda.expressionBody(),
                        nestedLocals,
                        outer,
                        recursiveBinding,
                        captures,
                        false);
            } else {
                scanStatements(
                        lambda.blockBody(),
                        nestedLocals,
                        outer,
                        recursiveBinding,
                        captures);
            }
        } else {
            throw error(
                    "unsupported Task capture expression '"
                            + expr.getClass().getName()
                            + "'; capture analysis must fail closed");
        }
    }

    private void defineConditionAliases(Ast.Expr condition, Scope destination, Scope source) {
        if (condition instanceof Ast.TypeTestExpr test && test.binding() != null) {
            VarState original = test.value() instanceof Ast.NameExpr name ? source.lookup(name.name()) : null;
            defineRefinementAlias(test.binding(), test.targetType(), original, destination);
            return;
        }
        if (condition instanceof Ast.PatternTestExpr test) {
            VarState original = test.value() instanceof Ast.NameExpr name ? source.lookup(name.name()) : null;
            Ast.TypeRef type = original == null ? Ast.TypeRef.inferred() : original.type;
            definePatternAliases(test.pattern(), type, original, destination);
            return;
        }
        if (condition instanceof Ast.BinaryExpr binary && binary.operator().equals("&&")) {
            defineConditionAliases(binary.left(), destination, source);
            defineConditionAliases(binary.right(), destination, source);
        }
    }

    private void definePatternAliases(Ast.Pattern pattern, Ast.TypeRef subjectType, VarState source, Scope destination) {
        if (pattern instanceof Ast.BindingPattern binding) {
            defineRefinementAlias(binding.name(), subjectType, source, destination);
            return;
        }
        if (pattern instanceof Ast.TypePattern typed) {
            if (typed.binding() != null) {
                // A negative predicate rules out a type but must never grant
                // the capabilities of the excluded positive type.
                Ast.TypeRef bindingType = typed.type().isComplement()
                        ? subjectType : typed.type();
                defineRefinementAlias(typed.binding(), bindingType, source, destination);
            }
            return;
        }
        if (pattern instanceof Ast.StructuralPattern structural) {
            if (structural.binding() != null) {
                defineRefinementAlias(structural.binding(), structural.target(), source, destination);
            }
            return;
        }
        if (pattern instanceof Ast.TuplePattern tuple) {
            List<Ast.TypeRef> elementTypes =
                    subjectType != null && subjectType.isTupleType()
                            ? subjectType.arguments()
                            : List.of();
            for (int i = 0; i < tuple.elements().size(); i++) {
                Ast.TypeRef elementType =
                        i < elementTypes.size()
                                ? elementTypes.get(i)
                                : Ast.TypeRef.inferred();
                definePatternAliases(
                        tuple.elements().get(i),
                        elementType,
                        source,
                        destination);
            }
            return;
        }
        if (pattern instanceof Ast.ConstructorPattern constructor) {
            List<Ast.TypeRef> argTypes = constructorPatternTypes(constructor.constructor(), subjectType);
            for (int i = 0; i < constructor.arguments().size(); i++) {
                Ast.TypeRef argType = i < argTypes.size() ? argTypes.get(i) : Ast.TypeRef.inferred();
                definePatternAliases(constructor.arguments().get(i), argType, source, destination);
            }
        }
    }

    private List<Ast.TypeRef> constructorPatternTypes(String constructor, Ast.TypeRef subjectType) {
        if (subjectType == null) return List.of();
        if (constructor.equals("Some") && subjectType.name().equals("Option") && subjectType.arguments().size() == 1) {
            return List.of(subjectType.arguments().getFirst());
        }
        if (constructor.equals("Ok") && subjectType.name().equals("Result") && subjectType.arguments().size() == 2) {
            return List.of(subjectType.arguments().get(0));
        }
        if (constructor.equals("Err") && subjectType.name().equals("Result") && subjectType.arguments().size() == 2) {
            return List.of(subjectType.arguments().get(1));
        }
        return List.of();
    }

    private void defineRefinementAlias(String name, Ast.TypeRef narrowedType, VarState source, Scope destination) {
        Ast.TypeRef capabilitySafeType = source == null
                ? narrowedType
                : overlayReadonlyCapabilities(source.type, narrowedType);
        ValueKind kind = source == null ? kindOfType(capabilitySafeType) : source.kind;
        VarState alias = new VarState(capabilitySafeType, false, kind, Origin.LOCAL);
        alias.aliasSource = source == null ? null : ownershipRoot(source);
        destination.define(name, alias);
    }

    /**
     * Preserve value capabilities while narrowing a refinement alias.
     *
     * <p>Type tests and typed match patterns may narrow structural shape, but
     * they must never upgrade a readonly struct-infer value (including nested
     * record members) into a mutable record shape. This mirrors the TypeChecker
     * overlay so standalone ownership analysis fails closed as well.
     */
    private Ast.TypeRef overlayReadonlyCapabilities(Ast.TypeRef source, Ast.TypeRef target) {
        if (source == null || target == null) return target;

        if (target.isBorrow()) {
            Ast.TypeRef sourceTarget = source.isBorrow() ? source.borrowedTarget() : source;
            return Ast.TypeRef.borrowed(
                    overlayReadonlyCapabilities(sourceTarget, target.borrowedTarget()),
                    target.mutableBorrow());
        }
        if (source.isBorrow()) {
            return overlayReadonlyCapabilities(source.borrowedTarget(), target);
        }

        if (source.isClosedAggregateType() && target.isClosedAggregateType()) {
            if (source.isStructType() != target.isStructType()) return target;
            Map<String, Ast.TypeRef> sourceMembers = source.aggregateMembers();
            Map<String, Ast.TypeRef> members = new LinkedHashMap<>();
            for (Map.Entry<String, Ast.TypeRef> entry : target.aggregateMembers().entrySet()) {
                Ast.TypeRef sourceMember = sourceMembers.get(entry.getKey());
                members.put(
                        entry.getKey(),
                        sourceMember == null
                                ? entry.getValue()
                                : overlayReadonlyCapabilities(sourceMember, entry.getValue()));
            }
            if (target.isStructType()) return Ast.TypeRef.structType(members);
            return source.isReadonlyRecordType() || target.isReadonlyRecordType()
                    ? Ast.TypeRef.readonlyRecordType(members)
                    : Ast.TypeRef.recordType(members);
        }

        if (source.isTupleType()
                && target.isTupleType()
                && source.arguments().size() == target.arguments().size()) {
            List<Ast.TypeRef> elements = new ArrayList<>(target.arguments().size());
            for (int i = 0; i < target.arguments().size(); i++) {
                elements.add(overlayReadonlyCapabilities(
                        source.arguments().get(i), target.arguments().get(i)));
            }
            return Ast.TypeRef.tupleType(elements);
        }

        if (source.name().equals(target.name())
                && source.arguments().size() == target.arguments().size()
                && !target.arguments().isEmpty()) {
            List<Ast.TypeRef> arguments = new ArrayList<>(target.arguments().size());
            for (int i = 0; i < target.arguments().size(); i++) {
                arguments.add(overlayReadonlyCapabilities(
                        source.arguments().get(i), target.arguments().get(i)));
            }
            return new Ast.TypeRef(target.name(), arguments, target.inferArguments());
        }

        return target;
    }

    /**
     * Mirror TypeChecker.typeOfCondition lexical refinement flow: bindings
     * introduced by the left operand of && are visible while scanning the
     * right operand. Without this, a same-named parent binding can be falsely
     * classified as a Task capture and moved at submission.
     */
    private void scanConditionExpr(
            Ast.Expr condition,
            Set<String> locals,
            Scope outer,
            String recursiveBinding,
            CaptureSet captures) {
        if (condition instanceof Ast.BinaryExpr binary
                && binary.operator().equals("&&")) {
            scanConditionExpr(
                    binary.left(),
                    locals,
                    outer,
                    recursiveBinding,
                    captures);
            Set<String> rightLocals = new HashSet<>(locals);
            collectConditionBindingNames(binary.left(), rightLocals);
            scanConditionExpr(
                    binary.right(),
                    rightLocals,
                    outer,
                    recursiveBinding,
                    captures);
            return;
        }
        scanExpr(
                condition,
                locals,
                outer,
                recursiveBinding,
                captures,
                false);
    }

    private void collectConditionBindingNames(Ast.Expr condition, Set<String> names) {
        if (condition instanceof Ast.TypeTestExpr test && test.binding() != null) names.add(test.binding());
        else if (condition instanceof Ast.PatternTestExpr test) collectPatternBindingNames(test.pattern(), names);
        else if (condition instanceof Ast.BinaryExpr binary && binary.operator().equals("&&")) {
            collectConditionBindingNames(binary.left(), names);
            collectConditionBindingNames(binary.right(), names);
        }
    }

    private void collectPatternBindingNames(Ast.Pattern pattern, Set<String> names) {
        if (pattern instanceof Ast.BindingPattern binding) names.add(binding.name());
        else if (pattern instanceof Ast.TypePattern typed && typed.binding() != null) names.add(typed.binding());
        else if (pattern instanceof Ast.StructuralPattern structural && structural.binding() != null) {
            names.add(structural.binding());
        } else if (pattern instanceof Ast.TuplePattern tuple) {
            for (Ast.Pattern nested : tuple.elements()) collectPatternBindingNames(nested, names);
        } else if (pattern instanceof Ast.ConstructorPattern constructor) {
            for (Ast.Pattern nested : constructor.arguments()) collectPatternBindingNames(nested, names);
        }
    }

    private Ast.ClassDecl classNamespaceOf(Ast.Expr expr, Scope scope) {
        if (expr instanceof Ast.NameExpr name) {
            if (scope.lookup(name.name()) != null) return null;
            return findClass(name.name());
        }
        if (expr instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr namespace
                && scope.lookup(namespace.name()) == null) {
            return findClass(namespace.name() + "." + member.member());
        }
        return null;
    }

    private Ast.TypeRef receiverType(Ast.Expr receiver, Scope scope) {
        Ast.TypeRef type = null;
        if (receiver instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            if (state != null) type = state.type;
        } else if (receiver instanceof Ast.NewExpr created) type = created.type();
        if (type == null) return null;
        if (type.isBorrow()) type = type.borrowedTarget();
        if (isMutexGuardType(type)) type = type.arguments().getFirst();
        return type;
    }

    private Ast.ClassDecl classOfReceiver(Ast.Expr receiver, Scope scope) {
        Ast.TypeRef type = receiverType(receiver, scope);
        return type == null ? null : findClass(type.name());
    }

    private Map<String, Ast.TypeRef> genericBindings(List<String> names, List<Ast.TypeRef> arguments) {
        Map<String, Ast.TypeRef> result = new HashMap<>();
        if (names.size() != arguments.size()) return result;
        for (int i = 0; i < names.size(); i++) result.put(names.get(i), arguments.get(i));
        return result;
    }

    private Ast.TypeRef substituteType(Ast.TypeRef type, Map<String, Ast.TypeRef> bindings) {
        if (type == null) return null;
        if (type.isBorrow()) {
            return Ast.TypeRef.borrowed(substituteType(type.borrowedTarget(), bindings), type.mutableBorrow());
        }
        Ast.TypeRef replacement = bindings.get(type.name());
        if (replacement != null && type.arguments().isEmpty() && !type.inferArguments()) return replacement;
        return new Ast.TypeRef(
                type.name(),
                type.arguments().stream().map(arg -> substituteType(arg, bindings)).toList(),
                type.inferArguments());
    }

    private Ast.TypeRef concreteParentType(Ast.TypeRef parentRef, Ast.ClassDecl child, Ast.TypeRef childType) {
        return substituteType(parentRef, genericBindings(child.genericParameters(), childType.arguments()));
    }

    private Ast.TypeRef inferConstructedType(Ast.NewExpr created, List<Ast.TypeRef> argumentTypes) {
        if (!created.type().inferArguments()) return created.type();
        Ast.ClassDecl klass = findClass(created.type().name());
        if (klass == null || klass.genericParameters().isEmpty()) return created.type();

        Ast.TypeRef patternType = new Ast.TypeRef(
                created.type().name(),
                klass.genericParameters().stream().map(Ast.TypeRef::simple).toList(),
                false);
        List<ResolvedField> fields = effectiveFieldTargets(klass, patternType, new LinkedHashSet<>());
        Map<String, Ast.TypeRef> bindings = new HashMap<>();
        Set<String> genericNames = Set.copyOf(klass.genericParameters());

        for (int i = 0; i < Math.min(argumentTypes.size(), fields.size()); i++) {
            ResolvedField target = fields.get(i);
            Ast.TypeRef fieldPattern = substituteType(
                    ownershipFieldType(target.field()),
                    genericBindings(target.owner().genericParameters(), target.ownerType().arguments()));
            inferGenericBindings(fieldPattern, argumentTypes.get(i), genericNames, bindings, Set.of());
        }

        List<Ast.TypeRef> inferred = new ArrayList<>(klass.genericParameters().size());
        for (String generic : klass.genericParameters()) {
            Ast.TypeRef bound = bindings.get(generic);
            if (bound == null || bound.name().equals("$infer$")) return created.type();
            inferred.add(bound);
        }
        return new Ast.TypeRef(created.type().name(), inferred, false);
    }

    private List<ResolvedField> effectiveFieldTargets(
            Ast.ClassDecl klass, Ast.TypeRef concreteType, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) return List.of();
        LinkedHashMap<String, ResolvedField> fields = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = findClass(parentRef.name());
            if (parent == null) continue;
            Ast.TypeRef parentType = concreteParentType(parentRef, klass, concreteType);
            for (ResolvedField field : effectiveFieldTargets(parent, parentType, stack)) {
                fields.putIfAbsent(field.field().name(), field);
            }
        }
        for (Ast.FieldDecl field : klass.fields()) {
            fields.put(field.name(), new ResolvedField(klass, concreteType, field));
        }
        stack.remove(klass);
        return List.copyOf(fields.values());
    }

    private ResolvedField findFieldTarget(
            Ast.ClassDecl klass, Ast.TypeRef concreteType, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.name().equals(name)) {
                seen.remove(klass);
                return new ResolvedField(klass, concreteType, field);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = findClass(parentRef.name());
            if (parent == null) continue;
            Ast.TypeRef parentType = concreteParentType(parentRef, klass, concreteType);
            ResolvedField found = findFieldTarget(parent, parentType, name, seen);
            if (found != null) {
                seen.remove(klass);
                return found;
            }
        }
        seen.remove(klass);
        return null;
    }

    private Ast.MethodDecl findStaticMethod(
            Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic() && method.name().equals(name) && method.parameters().size() == arity) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = findClass(parentRef.name());
            if (parent == null) continue;
            Ast.MethodDecl found = findStaticMethod(parent, name, arity, seen);
            if (found != null) {
                seen.remove(klass);
                return found;
            }
        }
        seen.remove(klass);
        return null;
    }

    private ResolvedMethod findMethodTarget(
            Ast.ClassDecl klass, Ast.TypeRef concreteType, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name) && method.parameters().size() == arity) {
                seen.remove(klass);
                return new ResolvedMethod(klass, concreteType, method);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = findClass(parentRef.name());
            if (parent == null) continue;
            Ast.TypeRef parentType = concreteParentType(parentRef, klass, concreteType);
            ResolvedMethod found = findMethodTarget(parent, parentType, name, arity, seen);
            if (found != null) {
                seen.remove(klass);
                return found;
            }
        }
        seen.remove(klass);
        return null;
    }

    private Ast.TypeRef syntacticType(Ast.Expr expr, Scope scope) {
        if (expr instanceof Ast.LiteralExpr literal) return inferLiteralType(literal.value());
        if (expr instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            return state == null ? Ast.TypeRef.inferred() : state.type;
        }
        if (expr instanceof Ast.NewExpr created) return created.type();
        if (expr instanceof Ast.TrapExpr trapped) {
            // Preserve the Option wrapper during generic call inference, even
            // when the operand is a local or literal rather than a CallExpr.
            return new Ast.TypeRef("Option", List.of(
                    syntacticType(trapped.expression(), scope)), false);
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            Ast.TypeRef operand = syntacticType(unary.operand(), scope);
            if (unary.operator().equals("&") || unary.operator().equals("&mut")) {
                return Ast.TypeRef.borrowed(operand, unary.operator().equals("&mut"));
            }
            return operand;
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            return Ast.TypeRef.tupleType(tuple.elements().stream().map(item -> syntacticType(item, scope)).toList());
        }
        if (expr instanceof Ast.ListExpr list && !list.elements().isEmpty()) {
            return new Ast.TypeRef("Array", List.of(syntacticType(list.elements().getFirst(), scope)), false);
        }
        return Ast.TypeRef.inferred();
    }

    private void inferGenericBindings(
            Ast.TypeRef pattern,
            Ast.TypeRef actual,
            Set<String> genericNames,
            Map<String, Ast.TypeRef> bindings,
            Set<String> fixed) {
        if (pattern == null || actual == null || actual.name().equals("$infer$")) return;
        if (genericNames.contains(pattern.name()) && pattern.arguments().isEmpty() && !pattern.inferArguments()) {
            if (!fixed.contains(pattern.name())) bindings.putIfAbsent(pattern.name(), actual);
            return;
        }
        if (pattern.isBorrow() && actual.isBorrow()) {
            inferGenericBindings(pattern.borrowedTarget(), actual.borrowedTarget(), genericNames, bindings, fixed);
            return;
        }
        if (pattern.name().equals(actual.name()) && pattern.arguments().size() == actual.arguments().size()) {
            for (int i = 0; i < pattern.arguments().size(); i++) {
                inferGenericBindings(pattern.arguments().get(i), actual.arguments().get(i), genericNames, bindings, fixed);
            }
        }
    }

    private List<Ast.TypeRef> syntacticCallArgumentTypes(
            List<Ast.Expr> arguments,
            Scope scope) {
        ArrayList<Ast.TypeRef> result = new ArrayList<>();
        for (Ast.Expr argument : arguments) {
            if (argument instanceof Ast.SpreadExpr spread) {
                Ast.TypeRef spreadType = syntacticType(spread.expression(), scope);
                if (spreadType.isBorrow()) spreadType = spreadType.borrowedTarget();
                if (spreadType.isTupleType()) {
                    result.addAll(spreadType.arguments());
                } else {
                    result.add(Ast.TypeRef.inferred());
                }
            } else {
                result.add(syntacticType(argument, scope));
            }
        }
        return List.copyOf(result);
    }

    private CallSignature specializeCall(
            List<String> allGenericNames,
            List<String> explicitGenericNames,
            List<Ast.Param> parameters,
            Ast.TypeRef result,
            Ast.CallExpr call,
            Scope scope,
            Map<String, Ast.TypeRef> initialBindings) {
        return specializeCall(allGenericNames, explicitGenericNames, parameters, result,
                call, scope, initialBindings, List.of());
    }

    private CallSignature specializeCall(
            List<String> allGenericNames,
            List<String> explicitGenericNames,
            List<Ast.Param> parameters,
            Ast.TypeRef result,
            Ast.CallExpr call,
            Scope scope,
            Map<String, Ast.TypeRef> initialBindings,
            List<Ast.GenericParameter> genericSpecs) {
        Map<String, Ast.TypeRef> bindings = new HashMap<>(initialBindings);
        Set<String> fixed = new HashSet<>(initialBindings.keySet());
        GenericCallArguments.Bound bound =
                GenericCallArguments.bind(explicitGenericNames, call.typeArguments(), "generic call");
        bindings.putAll(bound.explicitTypes());
        fixed.addAll(bound.explicitTypes().keySet());
        List<Ast.TypeRef> actualTypes = syntacticCallArgumentTypes(call.arguments(), scope);
        for (int i = 0; i < Math.min(parameters.size(), actualTypes.size()); i++) {
            inferGenericBindings(parameters.get(i).type(), actualTypes.get(i),
                    Set.copyOf(allGenericNames), bindings, fixed);
        }
        GenericCallArguments.applyDefaults(
                explicitGenericNames, genericSpecs, bindings, bound.inferenceHoles(),
                ref -> ref,
                this::substituteType,
                ref -> GenericCallArguments.containsUnresolvedAstGeneric(
                        ref, Set.copyOf(allGenericNames)),
                "generic call");
        GenericCallArguments.requireBound(explicitGenericNames, bindings, "generic call");
        List<Ast.Param> specialized = parameters.stream()
                .map(param -> new Ast.Param(
                        substituteType(param.type(), bindings),
                        param.name(), param.structural(), param.mutable()))
                .toList();
        return new CallSignature(specialized, substituteType(result, bindings));
    }

    private Ast.FieldDecl findField(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.name().equals(name)) {
                seen.remove(klass);
                return field;
            }
        }
        for (Ast.TypeRef parent : klass.parents()) {
            Ast.ClassDecl p = findClass(parent.name());
            if (p == null) continue;
            Ast.FieldDecl found = findField(p, name, seen);
            if (found != null) {
                seen.remove(klass);
                return found;
            }
        }
        seen.remove(klass);
        return null;
    }

    private boolean hasMethodNamed(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return false;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name)) {
                seen.remove(klass);
                return true;
            }
        }
        for (Ast.TypeRef parent : klass.parents()) {
            Ast.ClassDecl p = findClass(parent.name());
            if (p == null) continue;
            if (hasMethodNamed(p, name, seen)) {
                seen.remove(klass);
                return true;
            }
        }
        seen.remove(klass);
        return false;
    }

    private Ast.FunctionDecl findFunction(String name, int arity) {
        if (ambiguousFunctions.contains(name)) return null;
        Map<Integer, Ast.FunctionDecl> slots = functions.get(name);
        return slots == null ? null : slots.get(arity);
    }

    private Ast.ClassDecl findClass(String name) {
        if (ambiguousClasses.contains(name)) return null;
        return classes.get(name);
    }

    private VarState ownershipRoot(VarState state) {
        VarState current = state;
        Set<VarState> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        while (current.aliasSource != null && seen.add(current)) current = current.aliasSource;
        return current;
    }

    private void requireUsable(VarState state, String name, boolean write) {
        VarState root = ownershipRoot(state);
        if (root.moved) throw error("use of moved value '" + name + "'");
        // A live &mut view can access the object through that view, not
        // through its original owner. An incoming &mut parameter *is* its own
        // root, and forwarding it into another call must freeze that root.
        boolean throughExclusiveView = state.kind == ValueKind.MUT_BORROW && state != root;
        // Method-call receivers use two-phase borrowing: reading a primitive
        // field for an argument (self.remove(self.head)) is safe before the
        // mutating method executes, but writes/new loans/moves are not.
        boolean reservedReceiverRead = receiverReadReservations.containsKey(root);
        if (write) {
            if (root.mutableBorrowed && !throughExclusiveView)
                throw error("cannot mutate '" + name + "' while mutably borrowed");
            if (root.immutableBorrows > 0)
                throw error("cannot mutate '" + name + "' while immutably borrowed");
        } else if (root.mutableBorrowed && !throughExclusiveView && !reservedReceiverRead) {
            throw error("cannot read '" + name + "' while it is mutably borrowed");
        }
    }

    private void move(VarState state, String name) {
        VarState root = ownershipRoot(state);
        requireUsable(root, name, false);
        if (root.immutableBorrows > 0 || root.mutableBorrowed) {
            throw error("cannot move '" + name + "' while it is borrowed");
        }

        if (!root.aggregateSources.isEmpty()) {
            for (VarState source : root.aggregateSources) {
                VarState sourceRoot = ownershipRoot(source);
                if (sourceRoot.kind == ValueKind.IMM_BORROW
                        || sourceRoot.kind == ValueKind.MUT_BORROW
                        || sourceRoot.type.isBorrow()) {
                    throw error("cannot consume '" + name
                            + "' as an aggregate while it contains borrowed parameter '"
                            + source.debugName + "'; pass borrowed arguments explicitly");
                }
                if (sourceRoot.kind == ValueKind.MOVE_ONLY) {
                    requireUsable(sourceRoot, source.debugName, false);
                    if (sourceRoot.immutableBorrows > 0 || sourceRoot.mutableBorrowed) {
                        throw error("cannot move parameter '" + source.debugName
                                + "' through '" + name + "' while it is borrowed");
                    }
                }
            }
            for (VarState source : root.aggregateSources) {
                VarState sourceRoot = ownershipRoot(source);
                if (sourceRoot.kind == ValueKind.MOVE_ONLY) {
                    move(sourceRoot, source.debugName);
                }
            }
        }

        for (Set<VarState> observer : tryMoveObservers) observer.add(root);
        root.moved = true;
    }

    private VarState requireState(Scope scope, String name) {
        VarState state = scope.lookup(name);
        if (state == null) throw error("unknown owned binding '" + name + "'");
        state.debugName = name;
        return state;
    }

    private Map<VarState, StateSnapshot> stateSnapshot(Scope scope) {
        Map<VarState, StateSnapshot> result = new IdentityHashMap<>();
        for (VarState state : scope.visibleStates()) {
            VarState root = ownershipRoot(state);
            result.putIfAbsent(root, new StateSnapshot(root.moved, root.immutableBorrows, root.mutableBorrowed));
        }
        return result;
    }

    private void restoreState(Map<VarState, StateSnapshot> snapshot) {
        for (Map.Entry<VarState, StateSnapshot> entry : snapshot.entrySet()) {
            VarState state = entry.getKey();
            StateSnapshot saved = entry.getValue();
            state.moved = saved.moved();
            state.immutableBorrows = saved.immutableBorrows();
            state.mutableBorrowed = saved.mutableBorrowed();
        }
    }

    private void mergeBranchState(Map<VarState, StateSnapshot> base, List<Map<VarState, StateSnapshot>> exits) {
        restoreState(base);
        for (VarState state : base.keySet()) {
            boolean movedOnAnyPath = base.get(state).moved();
            for (Map<VarState, StateSnapshot> exit : exits) {
                StateSnapshot saved = exit.get(state);
                if (saved != null) movedOnAnyPath |= saved.moved();
            }
            state.moved = movedOnAnyPath;
        }
    }

    private Map<VarState,Boolean> movedSnapshot(Scope scope) {
        Map<VarState,Boolean> result = new IdentityHashMap<>();
        for (VarState state : scope.visibleStates()) {
            VarState root = ownershipRoot(state);
            result.putIfAbsent(root, root.moved);
        }
        return result;
    }

    private void rejectLoopMoves(Map<VarState,Boolean> before, Scope after) {
        for (Map.Entry<VarState,Boolean> entry : before.entrySet()) {
            if (!entry.getValue() && entry.getKey().moved && entry.getKey().kind != ValueKind.COPY) {
                throw error("cannot move outer value '" + entry.getKey().debugName + "' from a repeating loop; borrow it or move it before entering the loop");
            }
        }
    }

    private boolean isRootedAtActorSelf(Ast.Expr expr, Scope scope) {
        Ast.Expr current = expr;
        while (true) {
            if (current instanceof Ast.MemberExpr member) current = member.receiver();
            else if (current instanceof Ast.IndexExpr indexed) current = indexed.receiver();
            else break;
        }
        if (!(current instanceof Ast.NameExpr name)) return false;
        VarState root = scope.lookup(name.name());
        return root != null && isActorConfinedBorrow(root);
    }

    private boolean isActorConfinedBorrow(VarState state) {
        VarState current = state;
        Set<VarState> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            if ("self".equals(current.debugName)
                    && current.kind == ValueKind.MUT_BORROW
                    && current.origin == Origin.PARAM) {
                Ast.TypeRef type = current.type.isBorrow() ? current.type.borrowedTarget() : current.type;
                Ast.ClassDecl klass = findClass(type.name());
                return klass != null && klass.actorKind() != Ast.ActorKind.NONE;
            }
            current = current.aliasSource != null ? current.aliasSource : current.borrowSource;
        }
        return false;
    }

    private Ast.TypeRef channelElementType(Ast.TypeRef type) {
        if (type == null) return Ast.TypeRef.inferred();
        Ast.TypeRef concrete = type.isBorrow() ? type.borrowedTarget() : type;
        if (concrete.name().equals("Channel") && concrete.arguments().size() == 1) {
            return concrete.arguments().getFirst();
        }
        return Ast.TypeRef.inferred();
    }

    private List<CaptureSet> prepareDeferredSelectCaptures(
            Ast.SelectStmt selected,
            Scope outer) {
        ArrayList<CaptureSet> perArm =
                new ArrayList<>(selected.arms().size());
        CaptureSet union = new CaptureSet();

        for (Ast.SelectArm arm : selected.arms()) {
            CaptureSet captures = new CaptureSet();
            Set<String> locals = new HashSet<>();
            if (arm.bindingName() != null) locals.add(arm.bindingName());
            scanStatements(arm.body(), locals, outer, null, captures);
            perArm.add(captures);

            for (Capture capture : captures.values.values()) {
                union.add(capture.name, capture.source, capture.write);
            }
        }

        validateDeferredCaptures(union);
        return List.copyOf(perArm);
    }

    private static boolean isDirectActorSelfCapture(Capture capture) {
        // Caller additionally validates actor confinement. A user-created
        // alias rooted at self is still an ordinary borrowed local and must
        // never acquire mailbox self's extended lifetime.
        return capture.name.equals("self")
                && capture.source.origin == Origin.PARAM
                && capture.source.kind == ValueKind.MUT_BORROW;
    }

    /**
     * Minimal explicit forbidden-capability backstop for independently
     * scheduled Tasks. This is NOT a full nominal Send derivation. Future<T>
     * is intentionally not on this list: OresFuture is runtime-thread-safe,
     * cross-scheduler await is supported, and Future is affine so Task capture
     * transfers it with MOVE rather than copying cancellation/consumer authority.
     */
    /**
     * Conservative compiler-derived Send backstop for independently scheduled
     * Tasks. Owned data may move only when every statically visible component
     * is safe to transfer. Unknown/inferred values and first-class closures
     * remain fail-closed until they carry explicit Send provenance.
     *
     * <p>Future<T> is intentionally allowed: OresFuture is runtime-thread-safe,
     * the await graph is VM-wide, and Future is affine so Task capture uses
     * MOVE rather than duplicating cancellation/consumer authority.
     */
    private boolean forbiddenTaskCapability(Ast.TypeRef type) {
        return !taskCaptureCapabilities(
                type,
                java.util.Collections.newSetFromMap(
                        new IdentityHashMap<>())).send();
    }

    /**
     * Task-specific adapter over the language's canonical Send/Sync/Share
     * lattice. This keeps ownership capture admission aligned with
     * AutoTraitRules without weakening the stricter generic async-callable
     * boundary policy in TypeChecker.
     *
     * <p>Two runtime-owned handles intentionally differ from the closed
     * TypeAutoTraits table here:
     * <ul>
     *   <li>Future&lt;T&gt; is affine and may MOVE between Tasks when T is
     *       itself task-Send (Future&lt;void&gt; carries no payload).</li>
     *   <li>Channel&lt;T&gt; is an explicitly synchronized Copy handle and may
     *       cross Tasks when T is task-Send.</li>
     * </ul>
     * Unknown nominal/generic provenance fails closed.
     */
    private AutoTraitRules.Capabilities taskCaptureCapabilities(
            Ast.TypeRef type,
            Set<Ast.ClassDecl> visitingClasses) {
        AutoTraitRules.Capabilities none =
                new AutoTraitRules.Capabilities(false, false, false);
        AutoTraitRules.Capabilities scalar =
                AutoTraitRules.infer(AutoTraitRules.TypeModel.scalar());

        if (type == null || type.isBorrow() || type.inferArguments()
                || type.name().equals("$infer$")
                || type.isComplement()
                || type.isSequenceShape()
                || type.isRepeatExact()
                || type.isRepeatMany()
                || type.isNamedParameter()) {
            return none;
        }

        if (type.isStringLiteral() || type.isIntLiteral()
                || type.isBoolLiteral()) {
            // Literal metadata is compiler-created and never generic.
            // A fabricated $string$/$int$/$bool$ wrapper containing a
            // non-Send argument must not inherit scalar Send authority.
            return type.arguments().isEmpty() && !type.inferArguments()
                    ? scalar : none;
        }

        if (type.isUnion() || type.isTupleType()) {
            // A compiler-made union always has members. A synthetic,
            // zero-member union must not derive Send by vacuous truth.
            if (type.isUnion() && type.arguments().isEmpty()) return none;
            boolean send = true;
            boolean sync = true;
            boolean share = true;
            for (Ast.TypeRef member : type.arguments()) {
                AutoTraitRules.Capabilities child =
                        taskCaptureCapabilities(member, visitingClasses);
                send &= child.send();
                sync &= child.sync();
                share &= child.share();
            }
            return new AutoTraitRules.Capabilities(send, sync, share);
        }

        if (type.isClosedAggregateType()) {
            boolean send = true;
            boolean sync = true;
            boolean share = true;
            for (Ast.TypeRef member : type.aggregateMembers().values()) {
                AutoTraitRules.Capabilities child =
                        taskCaptureCapabilities(member, visitingClasses);
                send &= child.send();
                sync &= child.sync();
                share &= child.share();
            }
            boolean immutable = type.isReadonlyRecordType();
            return new AutoTraitRules.Capabilities(
                    send,
                    immutable && sync,
                    immutable && share);
        }

        String name = type.name();
        if (hasLocalTypeAliasNamed(name)) return none;
        Ast.ClassDecl declaredClass = findClass(name);
        if (declaredClass != null && java.util.Set.of(
                "ActorRef", "Future", "Channel", "SharedMutex",
                "Option", "Result", "IteratorResult",
                "Array", "List", "Vector", "Slice",
                "FixedArray", "FixedList",
                "i8", "i16", "i32", "i64",
                "u8", "u16", "u32", "u64",
                "int", "uint", "bigint",
                "f32", "f64", "float", "decimal",
                "complex64", "complex128", "complex",
                "bool", "Bool", "string", "String",
                "Symbol", "$builtin$Symbol",
                "ActorId", "ActorKind",
                "OptionUnwrapError").contains(name)) return none;

        if (switch (name) {
            case "i8", "i16", "i32", "i64",
                    "u8", "u16", "u32", "u64",
                    "int", "uint", "bigint",
                    "f32", "f64", "float", "decimal",
                    "complex64", "complex128", "complex",
                    "bool", "Bool", "string", "String",
                    "Symbol", "$builtin$Symbol",
                    "ActorId", "ActorKind",
                    "OptionUnwrapError" -> true;
            default -> false;
        }) {
            return type.arguments().isEmpty() ? scalar : none;
        }

        if (name.equals("void") || name.equals("null")) {
            return none;
        }

        // ActorRef<T> is an explicit runtime-synchronized mailbox capability.
        // T is routing/protocol metadata, not embedded actor state.
        if (name.equals("ActorRef")) {
            return isVerifiedActorReference(type) ? scalar : none;
        }

        // Task-owned authority and actor-turn/foreign capabilities do not
        // become Send merely by being wrapped in an owned binding.
        if (switch (name) {
            case "Task", "TaskHandle", "TaskScope",
                    "MutexGuard",
                    "Fnc", "Function",
                    "ActorMail", "ActorOutput", "ActorOutputStream",
                    "TcpConnection", "HttpExchange", "HttpServer",
                    "HttpHandler", "HttpResponse", "HttpResponseDraft",
                    "SelectCase", "SelectSet", "SelectResult",
                    "Iterator", "AsyncIterator" -> true;
            default -> false;
        }) {
            return none;
        }

        if (name.equals("Option") || name.equals("IteratorResult")) {
            return type.arguments().size() == 1
                    ? taskCaptureCapabilities(
                            type.arguments().getFirst(), visitingClasses)
                    : none;
        }
        if (name.equals("Result")) {
            if (type.arguments().size() != 2) return none;
            AutoTraitRules.Capabilities left =
                    taskCaptureCapabilities(
                            type.arguments().get(0), visitingClasses);
            AutoTraitRules.Capabilities right =
                    taskCaptureCapabilities(
                            type.arguments().get(1), visitingClasses);
            return new AutoTraitRules.Capabilities(
                    left.send() && right.send(),
                    left.sync() && right.sync(),
                    left.share() && right.share());
        }

        if (name.equals("Future")) {
            if (type.arguments().size() != 1) return none;
            Ast.TypeRef payload = type.arguments().getFirst();
            // Only the exact unshadowed void primitive is an empty result.
            // Malformed void<T> or aliased/nominal "void" is not proof of
            // an empty, Send-safe payload.
            boolean emptyPayload = payload.name().equals("void")
                    && payload.arguments().isEmpty()
                    && !payload.inferArguments()
                    && !hasLocalTypeAliasNamed("void")
                    && findClass("void") == null;
            boolean payloadSend = emptyPayload
                    || taskCaptureCapabilities(
                            payload, visitingClasses).send();
            return new AutoTraitRules.Capabilities(
                    payloadSend, false, false);
        }

        if (name.equals("Channel")) {
            if (type.arguments().size() != 1) return none;
            AutoTraitRules.Capabilities payload =
                    taskCaptureCapabilities(
                            type.arguments().getFirst(), visitingClasses);
            return payload.send()
                    ? new AutoTraitRules.Capabilities(true, true, true)
                    : none;
        }

        if (name.equals("SharedMutex")) {
            if (type.arguments().size() != 1) return none;
            AutoTraitRules.Capabilities payload =
                    taskCaptureCapabilities(
                            type.arguments().getFirst(), visitingClasses);
            return payload.send()
                    ? new AutoTraitRules.Capabilities(true, true, true)
                    : none;
        }

        // User classes with builtin-looking names follow their declared class
        // layout where TypeChecker permits that shadowing (Box/Arc/etc.).
        AutoTraitRules.Kind wrapperKind = switch (name) {
            case "Box" -> AutoTraitRules.Kind.BOX;
            case "Arc" -> AutoTraitRules.Kind.ARC;
            case "Weak" -> AutoTraitRules.Kind.WEAK;
            case "Rc" -> AutoTraitRules.Kind.RC;
            case "RefCell" -> AutoTraitRules.Kind.REFCELL;
            case "Mutex" -> AutoTraitRules.Kind.MUTEX;
            case "RwLock" -> AutoTraitRules.Kind.RWLOCK;
            default -> null;
        };
        if (wrapperKind != null && declaredClass == null) {
            if (type.arguments().size() != 1) return none;
            AutoTraitRules.Capabilities child =
                    taskCaptureCapabilities(
                            type.arguments().getFirst(), visitingClasses);
            AutoTraitRules.TypeModel childModel =
                    TypeAutoTraits.modelFromCapabilities(child);
            boolean shareableDomain =
                    wrapperKind == AutoTraitRules.Kind.ARC
                            || wrapperKind == AutoTraitRules.Kind.WEAK;
            return AutoTraitRules.infer(
                    AutoTraitRules.TypeModel.wrapped(
                            wrapperKind,
                            childModel,
                            shareableDomain));
        }

        if (switch (name) {
            case "Array", "List", "Vector",
                    "Slice", "FixedArray", "FixedList" -> true;
            default -> false;
        }) {
            if (type.arguments().size() != 1) return none;
            AutoTraitRules.Capabilities element =
                    taskCaptureCapabilities(
                            type.arguments().getFirst(), visitingClasses);
            // Mutable sequence ownership may move when elements are Send, but
            // the sequence itself is not implicitly Sync/Share.
            return new AutoTraitRules.Capabilities(
                    element.send(), false, false);
        }

        Ast.ClassDecl klass = declaredClass;
        if (klass == null || klass.actorKind() != Ast.ActorKind.NONE) {
            // Includes unresolved generic parameters (for example T), opaque
            // imported/foreign nominals without linked layout, and actor
            // instances. All require explicit provenance before Task transfer.
            return none;
        }

        if (!visitingClasses.add(klass)) {
            // A nominal cycle is not a Send proof. In particular, a generic
            // recursion can revisit the SAME declaration with DIFFERENT
            // type arguments and hide a nested non-Send capability:
            // Node<T> -> Node<Rc<T>>. Until we have a coinductive proof
            // keyed by fully substituted nominal instantiations, reject.
            // Recursive values may still be represented via checked
            // indirections; never infer safety from class identity alone.
            return none;
        }
        try {
            if (type.arguments().size()
                    != klass.genericParameters().size()) {
                return none;
            }

            for (ResolvedField field : effectiveFieldTargets(
                    klass, type, new LinkedHashSet<>())) {
                Ast.TypeRef fieldType = substituteType(
                        ownershipFieldType(field.field()),
                        genericBindings(
                                field.owner().genericParameters(),
                                field.ownerType().arguments()));
                if (!taskCaptureCapabilities(
                        fieldType, visitingClasses).send()) {
                    return none;
                }
            }
            return new AutoTraitRules.Capabilities(true, false, false);
        } finally {
            visitingClasses.remove(klass);
        }
    }

    private void validateDeferredCaptures(CaptureSet union) {
        for (Capture capture : union.values.values()) {
            VarState source = capture.source;
            source.debugName = capture.name;
            requireUsable(source, capture.name, capture.write);

            // Only the canonical actor self capability can cross actor turns.
            // Borrowed aliases of self follow ordinary lexical lifetime rules.
            if (isDirectActorSelfCapture(capture) && isActorConfinedBorrow(source)) continue;

            if (source.kind == ValueKind.IMM_BORROW
                    || source.kind == ValueKind.MUT_BORROW
                    || source.type.isBorrow()) {
                throw error("deferred task/continuation cannot capture borrowed value '"
                        + capture.name
                        + "'; capture owned data or actor self instead");
            }
            if (containsMutexGuardType(source.type)) {
                throw error("deferred task/continuation cannot capture guard-bearing value '"
                        + capture.name + "'");
            }

            if (capture.write) {
                if (!source.mutable && !source.rebindable) {
                    throw error("deferred task/continuation cannot write read-only capture '"
                            + capture.name + "'");
                }
                if (source.immutableBorrows > 0 || source.mutableBorrowed) {
                    throw error("deferred task/continuation cannot capture '"
                            + capture.name + "' mutably while borrowed");
                }
                move(source, capture.name);
            } else if (capture.consume || source.kind == ValueKind.MOVE_ONLY) {
                // Registration owns an explicitly consumed value immediately,
                // including Copy values named by rt take. Consumption is not a
                // write, so fixed/const bindings remain valid transfer sources.
                move(source, capture.name);
            }
        }
    }

    private Scope deferredSelectArmScope(CaptureSet captures) {
        Scope continuation = new Scope(null);
        for (Capture capture : captures.values.values()) {
            VarState source = capture.source;

            if (isDirectActorSelfCapture(capture) && isActorConfinedBorrow(source)) {
                VarState self = new VarState(
                        source.type,
                        false,
                        ValueKind.MUT_BORROW,
                        Origin.PARAM);
                continuation.define(capture.name, self);
                continue;
            }

            continuation.define(
                    capture.name,
                    capturedState(
                            source,
                            capture.write && source.mutable,
                            capture.write && source.rebindable,
                            source.kind));
        }
        return continuation;
    }

    /**
     * Captured const selectors keep the same compile-time proof as their lexical
     * source. Only CONST bindings ever carry this metadata, so copying it cannot
     * make a writable/rebindable selector stale.
     */
    private VarState capturedState(
            VarState source,
            boolean mutable,
            boolean rebindable,
            ValueKind kind) {
        VarState captured = new VarState(
                source.type, mutable, rebindable, kind, Origin.CAPTURE);
        captured.computedIndexConstant = source.computedIndexConstant;
        return captured;
    }

    private Ast.TypeRef indexedElementType(
            Ast.IndexExpr indexed, Ast.TypeRef receiverType, Scope scope) {
        if (receiverType == null) return Ast.TypeRef.inferred();
        Ast.TypeRef concrete = receiverType.isBorrow()
                ? receiverType.borrowedTarget() : receiverType;

        Object constantIndex = computedIndexConstant(indexed.index(), scope);

        if (concrete.isClosedAggregateType()) {
            Map<String, Ast.TypeRef> members = concrete.aggregateMembers();
            if (constantIndex instanceof String key) {
                return members.getOrDefault(key, Ast.TypeRef.inferred());
            }
            if (members.isEmpty()) return Ast.TypeRef.inferred();
            return Ast.TypeRef.union(new ArrayList<>(members.values()));
        }

        if (concrete.isTupleType()) {
            if (constantIndex instanceof Long raw
                    && raw >= 0 && raw < concrete.arguments().size()) {
                return concrete.arguments().get(raw.intValue());
            }
            if (concrete.arguments().isEmpty()) return Ast.TypeRef.inferred();
            return Ast.TypeRef.union(concrete.arguments());
        }

        if ((concrete.name().equals("Array") || concrete.name().equals("List"))
                && concrete.arguments().size() == 1) {
            return concrete.arguments().getFirst();
        }

        Ast.ClassDecl klass = findClass(concrete.name());
        if (klass != null) {
            if (constantIndex instanceof String key) {
                ResolvedField target = findFieldTarget(
                        klass, concrete, key, new LinkedHashSet<>());
                if (target == null) return Ast.TypeRef.inferred();
                return substituteType(
                        ownershipFieldType(target.field()),
                        genericBindings(
                                target.owner().genericParameters(),
                                target.ownerType().arguments()));
            }
            List<Ast.TypeRef> fields = new ArrayList<>();
            for (ResolvedField field : effectiveFieldTargets(
                    klass, concrete, new LinkedHashSet<>())) {
                fields.add(substituteType(
                        ownershipFieldType(field.field()),
                        genericBindings(
                                field.owner().genericParameters(),
                                field.ownerType().arguments())));
            }
            return fields.isEmpty() ? Ast.TypeRef.inferred() : Ast.TypeRef.union(fields);
        }

        return collectionElementType(concrete);
    }

    private Ast.TypeRef collectionElementType(Ast.TypeRef type) {
        if (type == null) return Ast.TypeRef.inferred();
        Ast.TypeRef concrete = type.isBorrow() ? type.borrowedTarget() : type;
        if ((concrete.name().equals("Array") || concrete.name().equals("List"))
                && concrete.arguments().size() == 1) {
            return concrete.arguments().getFirst();
        }
        if (concrete.isTupleType() && !concrete.arguments().isEmpty()) {
            Ast.TypeRef first = concrete.arguments().getFirst();
            boolean same = concrete.arguments().stream().allMatch(first::equals);
            return same ? first : Ast.TypeRef.inferred();
        }
        return Ast.TypeRef.inferred();
    }

    private Ast.TypeRef sequenceDestructureBindingType(Ast.TypeRef source, int index, boolean rest) {
        if (source == null) return Ast.TypeRef.inferred();
        Ast.TypeRef concrete = source.isBorrow() ? source.borrowedTarget() : source;

        if (concrete.isUnion()) {
            List<Ast.TypeRef> alternatives = concrete.arguments().stream()
                    .map(option -> sequenceDestructureBindingType(option, index, rest))
                    .toList();
            if (alternatives.stream().anyMatch(type -> type.name().equals("$infer$"))) {
                return Ast.TypeRef.inferred();
            }
            return Ast.TypeRef.union(alternatives);
        }

        Ast.TypeRef iterableShape = sequenceDestructureSourceType(concrete);
        if (!iterableShape.equals(concrete)) {
            return sequenceDestructureBindingType(iterableShape, index, rest);
        }

        if (concrete.isTupleType()) {
            if (rest) {
                if (index > concrete.arguments().size()) return Ast.TypeRef.inferred();
                return Ast.TypeRef.tupleType(concrete.arguments().subList(index, concrete.arguments().size()));
            }
            if (index < concrete.arguments().size()) return concrete.arguments().get(index);
            return Ast.TypeRef.inferred();
        }

        if ((concrete.name().equals("Array") || concrete.name().equals("List"))
                && concrete.arguments().size() == 1) {
            return rest ? concrete : concrete.arguments().getFirst();
        }
        return Ast.TypeRef.inferred();
    }

    private Ast.TypeRef sequenceDestructureSourceType(Ast.TypeRef source) {
        Ast.TypeRef concrete = source.isBorrow() ? source.borrowedTarget() : source;
        Ast.ClassDecl klass = findClass(concrete.name());
        if (klass == null) return concrete;

        ResolvedMethod iterator =
                findMethodTarget(klass, concrete, "Symbol.iterator", 0, new LinkedHashSet<>());
        if (iterator == null) return concrete;

        Ast.TypeRef result = substituteType(
                iterator.method().returnType(),
                genericBindings(iterator.owner().genericParameters(), iterator.ownerType().arguments()));
        if (result == null) return Ast.TypeRef.inferred();
        if (result.isTupleType()) return result;
        if ((result.name().equals("Array") || result.name().equals("List"))
                && result.arguments().size() == 1) {
            return result;
        }
        return Ast.TypeRef.inferred();
    }

    private Ast.TypeRef destructureBindingType(Ast.DestructureStmt destructure, Ast.TypeRef source, int index, String name) {
        if (source == null) return Ast.TypeRef.inferred();
        Ast.DestructureBinding binding = destructure.bindings().get(index);
        if (destructure.kind() == Ast.DestructureKind.SEQUENCE) {
            return sequenceDestructureBindingType(source, index, binding.rest());
        } else {
            Ast.TypeRef concrete = source.isBorrow() ? source.borrowedTarget() : source;

            if (binding.rest()) {
                if (concrete.isUnion()) {
                    List<Ast.TypeRef> alternatives = concrete.arguments().stream()
                            .map(option -> objectRestBindingType(option, destructure.bindings()))
                            .toList();
                    if (alternatives.stream().anyMatch(type -> type.name().equals("$infer$"))) {
                        return Ast.TypeRef.inferred();
                    }
                    return Ast.TypeRef.union(alternatives);
                }
                return objectRestBindingType(concrete, destructure.bindings());
            }

            if (concrete.isUnion()) {
                List<Ast.TypeRef> alternatives = concrete.arguments().stream()
                        .map(option -> objectMemberBindingType(option, name))
                        .toList();
                if (alternatives.stream().anyMatch(type -> type.name().equals("$infer$"))) {
                    return Ast.TypeRef.inferred();
                }
                return Ast.TypeRef.union(alternatives);
            }
            return objectMemberBindingType(concrete, name);
        }
    }

    private Ast.TypeRef objectRestBindingType(Ast.TypeRef source, List<Ast.DestructureBinding> bindings) {
        Ast.TypeRef concrete = source.isBorrow() ? source.borrowedTarget() : source;
        if (!concrete.isClosedAggregateType()) return Ast.TypeRef.inferred();

        Map<String, Ast.TypeRef> remainder = new LinkedHashMap<>(concrete.aggregateMembers());
        for (Ast.DestructureBinding binding : bindings) {
            if (!binding.rest() && !binding.isDiscard()) remainder.remove(binding.name());
        }
        // Removing fields from a concrete struct no longer denotes that exact
        // struct type. The rest projection is therefore a structural record.
        return concrete.isReadonlyRecordType() || concrete.isStructType()
                ? Ast.TypeRef.readonlyRecordType(remainder)
                : Ast.TypeRef.recordType(remainder);
    }

    private Ast.TypeRef objectMemberBindingType(Ast.TypeRef concrete, String name) {
        if (concrete.isBorrow()) concrete = concrete.borrowedTarget();
        if (concrete.isClosedAggregateType()) {
            Ast.TypeRef member = concrete.aggregateMembers().get(name);
            if (member != null) return member;
        }
        Ast.ClassDecl klass = findClass(concrete.name());
        if (klass != null) {
            ResolvedField target = findFieldTarget(klass, concrete, name, new LinkedHashSet<>());
            if (target != null && target.field().visibility() == Ast.Visibility.PUBLIC) {
                return substituteType(
                        ownershipFieldType(target.field()),
                        genericBindings(target.owner().genericParameters(), target.ownerType().arguments()));
            }
        }
        return Ast.TypeRef.inferred();
    }

    private Ast.TypeRef callableResultType(
            Ast.TypeRef result,
            boolean async,
            boolean generator) {
        if (generator) {
            Ast.TypeRef element = generatorYieldType(result, async);
            return new Ast.TypeRef(
                    async ? "AsyncIterator" : "Iterator",
                    List.of(element),
                    false);
        }
        if (async) return new Ast.TypeRef("Future", List.of(result), false);
        return result;
    }

    private Ast.TypeRef generatorYieldType(Ast.TypeRef result, boolean async) {
        if (result != null && result.arguments().size() == 1) {
            boolean syncProtocol =
                    result.name().equals("Iterator") || result.name().equals("Generator");
            boolean asyncProtocol =
                    result.name().equals("AsyncIterator") || result.name().equals("AsyncGenerator");
            if ((async && asyncProtocol) || (!async && syncProtocol)) {
                return result.arguments().getFirst();
            }
        }
        return result;
    }

    private Ast.TypeRef iterableElementType(Ast.TypeRef iterableType, boolean asyncIteration) {
        if (iterableType == null) return Ast.TypeRef.inferred();
        Ast.TypeRef concrete = iterableType.isBorrow() ? iterableType.borrowedTarget() : iterableType;
        if (asyncIteration && (concrete.name().equals("AsyncGenerator") || concrete.name().equals("AsyncIterator"))
                && concrete.arguments().size() == 1) {
            return concrete.arguments().getFirst();
        }
        if ((concrete.name().equals("Array") || concrete.name().equals("List")
                        || concrete.name().equals("Generator") || concrete.name().equals("Iterator"))
                && concrete.arguments().size() == 1) {
            return concrete.arguments().getFirst();
        }
        if (concrete.isTupleType() && !concrete.arguments().isEmpty()) {
            Ast.TypeRef first = concrete.arguments().getFirst();
            return concrete.arguments().stream().allMatch(first::equals)
                    ? first
                    : Ast.TypeRef.inferred();
        }
        Ast.TypeRef sequence = sequenceDestructureSourceType(concrete);
        if (!sequence.equals(concrete)) return iterableElementType(sequence, asyncIteration);
        return Ast.TypeRef.inferred();
    }

    private Ast.TypeRef joinConditionalType(Ast.TypeRef left, Ast.TypeRef right) {
        if (left == null) return right == null ? Ast.TypeRef.inferred() : right;
        if (right == null) return left;
        if (left.equals(right)) return left;
        if (left.name().equals("$infer$")) return right;
        if (right.name().equals("$infer$")) return left;
        return Ast.TypeRef.union(List.of(left, right));
    }

    private ValueKind kindOfType(Ast.TypeRef type) {
        if (type == null) return ValueKind.MOVE_ONLY;
        if (type.isBorrow()) return type.mutableBorrow() ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW;
        return isCopyType(type) ? ValueKind.COPY : ValueKind.MOVE_ONLY;
    }

    /**
     * A mailbox handle's nominal type is Copy/Send only when the target is
     * a linked or locally checked actor declaration, never merely because
     * the identifier is spelled ActorRef. Used identically for the base
     * ownership Copy classifier and the independent Task Send proof.
     */
    private boolean hasLocalTypeAliasNamed(String name) {
        for (Ast.ModuleDecl module : checkedProgram.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.TypeAliasDecl alias
                        && alias.name().equals(name)) return true;
            }
        }
        return false;
    }

    private boolean isVerifiedActorReference(Ast.TypeRef type) {
        if (type == null || type.isBorrow()
                || !type.name().equals("ActorRef")
                || type.inferArguments()
                || type.arguments().size() != 1
                || findClass("ActorRef") != null
                || hasLocalTypeAliasNamed("ActorRef")) {
            return false;
        }
        Ast.TypeRef target = type.arguments().getFirst();
        if (target.isBorrow() || target.inferArguments()
                || !target.arguments().isEmpty()
                || target.isComplement() || target.isUnion()
                || target.isTupleType() || target.isClosedAggregateType()) {
            return false;
        }
        if (hasLocalTypeAliasNamed(target.name())) return false;
        Ast.ClassDecl actorTarget = findClass(target.name());
        return actorTarget != null
                && actorTarget.actorKind() != Ast.ActorKind.NONE;
    }

    private boolean isCopyType(Ast.TypeRef type) {
        // Inference markers and compiler-only unverified shapes carry no
        // structural Copy proof. Task Send already rejects them; Copy must
        // not silently certify the same unresolved nominal metadata.
        if (type == null || type.isBorrow() || type.inferArguments()
                || type.isComplement() || type.isSequenceShape()
                || type.isRepeatExact() || type.isRepeatMany()
                || type.isNamedParameter()) return false;
        if (type.isUnion()) return !type.arguments().isEmpty()
                && type.arguments().stream().allMatch(this::isCopyType);
        if (type.isTupleType()) return type.arguments().stream().allMatch(this::isCopyType);
        if (hasLocalTypeAliasNamed(type.name())) return false;
        // Same spelling is not enough to bestow a runtime wrapper's Copy
        // semantics on a locally declared nominal class.
        // A locally declared class is never certified as a builtin Copy
        // scalar by sharing its name (including ActorId, Symbol and String).
        // Ordinary user classes remain move-only by default.
        if (findClass(type.name()) != null) return false;
        return switch (type.name()) {
            case "i8","i16","i32","i64","u8","u16","u32","u64","int","uint","bigint",
                    "f32","f64","float","decimal","complex64","complex128","complex",
                    "bool","Bool","string","String","void",
                    "SelectCase","SelectSet","OptionUnwrapError"
                    -> type.arguments().isEmpty();
            case "SharedMutex", "Channel" -> type.arguments().size() == 1;
            case "ActorRef" -> isVerifiedActorReference(type);
            case "Option" -> type.arguments().size() == 1 && isCopyType(type.arguments().getFirst());
            case "Result" -> type.arguments().size() == 2
                    && isCopyType(type.arguments().get(0))
                    && isCopyType(type.arguments().get(1));
            default -> false;
        };
    }

    private boolean isScopedMutexReceiver(VarState state) {
        return isMutexGuardType(state.type)
                || (mutexCriticalSectionDepth > 0 && state.type.isBorrow() && state.type.mutableBorrow());
    }

    private static boolean isMutexGuardType(Ast.TypeRef type) {
        return type != null && !type.isBorrow() && type.name().equals("MutexGuard") && type.arguments().size() == 1;
    }

    private static boolean containsMutexGuardType(Ast.TypeRef type) {
        if (type == null) return false;
        if (type.isBorrow()) return containsMutexGuardType(type.borrowedTarget());
        if (isMutexGuardType(type)) return true;
        for (Ast.TypeRef argument : type.arguments()) {
            if (containsMutexGuardType(argument)) return true;
        }
        return false;
    }

    private static boolean containsAcquiredMutexGuardType(Ast.TypeRef type) {
        if (type == null || type.isBorrow()) return false;
        if (isMutexGuardType(type)) return true;
        if (type.name().equals("Future")) return false;
        for (Ast.TypeRef argument : type.arguments()) {
            if (containsAcquiredMutexGuardType(argument)) return true;
        }
        return false;
    }

    private Ast.TypeRef ownershipFieldType(Ast.FieldDecl field) {
        if (field.type() != null) return field.type();
        if (field.initializer() instanceof Ast.LiteralExpr literal) {
            return inferLiteralType(literal.value());
        }
        return Ast.TypeRef.inferred();
    }

    private Ast.TypeRef inferLiteralType(Object value) {
        if (value instanceof Boolean) return Ast.TypeRef.simple("bool");
        if (value instanceof Long) return Ast.TypeRef.simple("int");
        if (value instanceof Double) return Ast.TypeRef.simple("float");
        if (value instanceof String) return Ast.TypeRef.simple("String");
        if (value instanceof Ast.Imaginary) return Ast.TypeRef.simple("complex");
        return Ast.TypeRef.inferred();
    }

    /**
     * Match TypeChecker's exact computed-index proofs without evaluating guest code.
     * A CONST binding with a direct integer/string literal initializer is a stable
     * selector even when its declared scalar type is wider than that literal.
     */
    private Object directComputedIndexConstant(Ast.BindingStmt binding) {
        Ast.Expr initializer = binding.initializer();
        if (initializer instanceof Ast.LiteralExpr literal) {
            if (literal.value() instanceof Long value) return value;
            if (literal.value() instanceof String value) return value;
        }
        if (initializer instanceof Ast.UnaryExpr unary
                && unary.operator().equals("-")
                && unary.operand() instanceof Ast.LiteralExpr literal
                && literal.value() instanceof Long value
                && value != Long.MIN_VALUE) {
            return -value;
        }
        return null;
    }

    private Object computedIndexConstant(Ast.Expr expression, Scope scope) {
        if (expression instanceof Ast.LiteralExpr literal
                && (literal.value() instanceof String || literal.value() instanceof Long)) {
            return literal.value();
        }
        if (expression instanceof Ast.UnaryExpr unary
                && unary.operator().equals("-")
                && unary.operand() instanceof Ast.LiteralExpr literal
                && literal.value() instanceof Long value
                && value != Long.MIN_VALUE) {
            return -value;
        }
        if (expression instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            return state == null ? null : state.computedIndexConstant;
        }
        return null;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Oreslang ownership error: " + message);
    }

    private enum ValueKind { COPY, MOVE_ONLY, IMM_BORROW, MUT_BORROW }
    private enum Origin { PARAM, LOCAL, CAPTURE }

    private static final class ValueInfo {
        private final Ast.TypeRef type;
        private final ValueKind kind;
        private final VarState borrowSource;
        private final boolean borrowLease;
        private ValueInfo(Ast.TypeRef type, ValueKind kind, VarState borrowSource) {
            this(type, kind, borrowSource, false);
        }
        private ValueInfo(Ast.TypeRef type, ValueKind kind, VarState borrowSource, boolean borrowLease) {
            this.type = type;
            this.kind = kind;
            this.borrowSource = borrowSource;
            this.borrowLease = borrowLease;
        }
    }

    private static final class VarState {
        private Ast.TypeRef type;
        private final boolean mutable;
        private final boolean rebindable;
        private ValueKind kind;
        private final Origin origin;
        private boolean moved;
        private int immutableBorrows;
        private boolean mutableBorrowed;
        private VarState borrowSource;
        private boolean borrowLease;
        /** Narrowing/pattern alias: ownership operations are forwarded to this root place. */
        private VarState aliasSource;
        /** Synthetic aggregate aliases, currently used by the implicit arguments tuple. */
        private List<VarState> aggregateSources = List.of();
        private String debugName = "<value>";
        /** Literal key/index proven constant by the frontend ownership pass. */
        private Object computedIndexConstant;

        private VarState(Ast.TypeRef type, boolean mutable, ValueKind kind, Origin origin) {
            this(type, mutable, mutable, kind, origin);
        }
        private VarState(Ast.TypeRef type, boolean mutable, boolean rebindable, ValueKind kind, Origin origin) {
            this.type = type;
            this.mutable = mutable;
            this.rebindable = rebindable;
            this.kind = kind;
            this.origin = origin;
        }
    }

    private static final class Scope {
        private final Scope parent;
        private final boolean descendantsNonLexical;
        private final Map<String,VarState> locals = new LinkedHashMap<>();
        private final Map<String,Ast.TypeRef> inferredTypes = new LinkedHashMap<>();
        private void defineInferredType(String name, Ast.TypeRef type) {
            if (lookupInferredType(name) != null || inferredTypes.putIfAbsent(name, type) != null) {
                throw new IllegalArgumentException(
                        "Oreslang ownership error: duplicate inferred type '" + name + "'");
            }
        }
        private Ast.TypeRef lookupInferredType(String name) {
            Ast.TypeRef found = inferredTypes.get(name);
            return found != null ? found : parent == null ? null : parent.lookupInferredType(name);
        }
        private boolean closed;

        private Scope(Scope parent) { this(parent, parent != null && parent.descendantsNonLexical); }
        private Scope(Scope parent, boolean descendantsNonLexical) {
            this.parent = parent;
            this.descendantsNonLexical = descendantsNonLexical;
        }
        private boolean descendantsNonLexical() { return descendantsNonLexical; }

        private void define(String name, VarState state) {
            if (locals.putIfAbsent(name, state) != null) throw new IllegalArgumentException("Oreslang ownership error: duplicate binding '" + name + "'");
            state.debugName = name;
        }

        private VarState lookup(String name) {
            VarState local = locals.get(name);
            return local != null ? local : parent == null ? null : parent.lookup(name);
        }

        private List<VarState> visibleStates() {
            ArrayList<VarState> result = new ArrayList<>();
            if (parent != null) result.addAll(parent.visibleStates());
            result.addAll(locals.values());
            return result;
        }

        private boolean hasLiveMutexGuard() {
            for (VarState state : visibleStates()) {
                if (!state.moved && containsAcquiredMutexGuardType(state.type)) return true;
            }
            return false;
        }

        private boolean hasLiveBorrow() {
            for (VarState state : visibleStates()) {
                if (state.moved) continue;
                if (state.kind == ValueKind.IMM_BORROW || state.kind == ValueKind.MUT_BORROW
                        || state.immutableBorrows > 0 || state.mutableBorrowed) {
                    return true;
                }
            }
            return false;
        }

        private void close() {
            if (closed) return;
            closed = true;
            for (VarState state : locals.values()) {
                if (state.borrowSource != null && state.borrowLease) {
                    if (state.kind == ValueKind.MUT_BORROW) state.borrowSource.mutableBorrowed = false;
                    else if (state.kind == ValueKind.IMM_BORROW) {
                        state.borrowSource.immutableBorrows--;
                        if (state.borrowSource.immutableBorrows < 0) {
                            throw new IllegalStateException("Oreslang ownership invariant violated: negative immutable borrow count");
                        }
                    }
                }
            }
        }
    }

    private record StateSnapshot(boolean moved, int immutableBorrows, boolean mutableBorrowed) { }

    private record Capture(
            String name, VarState source, boolean write, boolean consume) { }

    private static final class CaptureSet {
        private final Map<String,Capture> values = new LinkedHashMap<>();

        private void add(String name, VarState state, boolean write) {
            add(name, state, write, false);
        }

        private void add(
                String name, VarState state, boolean write, boolean consume) {
            Capture existing = values.get(name);
            values.put(
                    name,
                    existing == null
                            ? new Capture(name, state, write, consume)
                            : new Capture(
                                    name,
                                    state,
                                    existing.write() || write,
                                    existing.consume() || consume));
        }
    }
}
