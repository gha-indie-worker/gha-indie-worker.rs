package dev.oreslang.types;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.ast.CallableSelector;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Borrow;
import dev.oreslang.types.Types.ClassNamespace;
import dev.oreslang.types.Types.Generic;
import dev.oreslang.types.Types.ListType;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import dev.oreslang.types.Types.AggregateKind;
import dev.oreslang.types.Types.SelfType;
import dev.oreslang.types.Types.StringLiteral;
import dev.oreslang.types.Types.Tuple;
import dev.oreslang.types.Types.TupleKind;
import dev.oreslang.types.Types.Union;
import dev.oreslang.types.Types.Type;
import dev.oreslang.types.Types.Unknown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static semantic pass run before Oreslang code is lowered/executed. */
public final class TypeChecker {
    // Keep the stage-one default-type allowlist consistent with concrete
    // compiler-known scalar and nominal constructors. Do not allocate it per
    // recursive validation node or admit arbitrary unresolved nominal names.
    private static final Set<String> GENERIC_DEFAULT_BUILTINS = Set.of(
            "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64",
            "int", "uint", "bigint", "f32", "f64", "float",
            "decimal", "complex64", "complex128", "complex",
            "bool", "Bool", "string", "String", "void", "null",
            "Symbol", "Array", "List", "Vector", "Slice",
            "Tuple", "FixedArray", "FixedList",
            "ActorId", "ActorKind", "ActorMail", "ActorOutput",
            "ActorOutputStream", "Option", "Result", "Box", "Arc", "Weak",
            "Rc", "RefCell", "Mutex", "RwLock", "SharedMutex",
            "Channel", "Future", "Fnc", "Function");

    private record ResolvedMethod(Ast.ClassDecl owner, Named ownerType, Ast.MethodDecl method) { }
    private record ResolvedInterfaceFunction(
            Ast.InterfaceDecl owner,
            Named ownerType,
            Ast.InterfaceFunctionDecl function) { }
    private record ResolvedField(Ast.ClassDecl owner, Named ownerType, Ast.FieldDecl field) { }
    /** Compile-time actor stream ports; not a second actor or scheduler. */
    private record StreamPorts(Type input, Type output) { }

    private record CallableTarget(Ast.ClassDecl owner, Ast.MethodDecl method) { }
    private final Map<String, LinkedHashMap<Integer, Ast.FunctionDecl>> functions = new HashMap<>();
    private final Map<String, String> unqualifiedFunctionOwners = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Map<String, Ast.InterfaceDecl> interfaces = new HashMap<>();
    private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
    // Foreign aliases are visible only while expanding a public field in
    // their declaring class, never as names available to importer source.
    private final IdentityHashMap<Ast.ClassDecl, Map<String, Ast.TypeAliasDecl>> linkedClassAliases = new IdentityHashMap<>();
    private Map<String, Ast.TypeAliasDecl> resolvingLinkedFieldAliases = Map.of();
    private final Set<String> hiddenLinkedAliasNames = new HashSet<>();
    private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();
    /** Lexical compile-time type aliases local to the block currently being checked. */
    private Env currentTypeScope;
    /** Names declared by named inference, including aliases not yet initialized. */
    private final Set<String> inferredTypeNames = new HashSet<>();
    /** Short named inference is resolved only for otherwise unbound capitalized row type names. */
    private final IdentityHashMap<Ast.BindingStmt, String> shortInferredTypeNames = new IdentityHashMap<>();

    private final IdentityHashMap<Ast.ClassDecl, String> classOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.InterfaceDecl, String> interfaceOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.TypeAliasDecl, String> typeAliasOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.ClassDecl, Record> classShapeCache = new IdentityHashMap<>();

    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final Set<String> ambiguousInterfaces = new HashSet<>();
    private final Set<String> ambiguousTypeAliases = new HashSet<>();
    private final Set<String> importedValues = new HashSet<>();
    /** Both type-only and value imports occupy names short inference may not redefine. */
    private final Set<String> importedNames = new HashSet<>();
    private final Set<Ast.TypeAliasDecl> resolvingAliases = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private Ast.ActorKind currentActorKind = Ast.ActorKind.NONE;
    private Ast.ClassDecl currentClassOwner;
    // Only an actual constructor may initialize an otherwise fixed, initially
    // absent class field. Class methods never inherit constructor privileges.
    private Ast.ClassDecl currentConstructorOwner;
    // Conservative constructor-initialization ledger. A fixed field without
    // a declaration initializer may be assigned syntactically at most once
    // in a constructor, even across alternative CFG arms. Until this ledger
    // is promoted to a path-sensitive may-initialize lattice, reject
    // ambiguous multi-write constructors rather than permit double writes.
    private Set<String> constructorFixedFieldWrites;
    // Unlike private-member access, declaration origin must survive nlex.
    private Ast.ClassDecl lexicalClassOrigin;
    // Source modules have their own lexical value namespace. File-level
    // declarations (the synthetic ROOT_MODULE) remain the composition root.
    private String currentLexicalModule;
    private boolean currentGenerator;
    private boolean currentAsyncCallable;
    private boolean currentMaySuspend;
    private int loopDepth;
    private boolean inDiscardingSelectArm;
    private final List<String> warnings = new ArrayList<>();

    /** Non-fatal compiler diagnostics, detached from mutable checker state. */
    public record TypeCheckResult(
            Ast.Program program,
            List<String> warnings,
            Map<Ast.BindingStmt, String> shortInferredAliases) {
        public TypeCheckResult {
            warnings = List.copyOf(warnings);
            // BindingStmt is a value record. Two separately declared bindings
            // can compare equal, but they are not the same lexical declaration.
            // Retain AST identity instead of collapsing them in Map.copyOf().
            shortInferredAliases = java.util.Collections.unmodifiableMap(
                    new IdentityHashMap<>(shortInferredAliases));
        }

        public TypeCheckResult(Ast.Program program, List<String> warnings) {
            this(program, warnings, Map.of());
        }
    }
    /** The one explicit struct literal currently permitted to omit declared fields. */
    private Ast.ObjectExpr permittedPartialStructInitializer;

    public static Ast.Program check(Ast.Program program) {
        TypeCheckResult checked = checkTypesWithDiagnostics(program);
        OwnershipChecker.check(checked.program(), checked.shortInferredAliases());
        return checked.program();
    }

    /** Type analysis only; executable admission must use OresCompiler.analyze. */
    public static Ast.Program checkTypes(Ast.Program program) {
        return checkTypesWithDiagnostics(program).program();
    }

    /**
     * Parsing effect annotations does not prove effect propagation or handler
     * discharge. Until the nominal Error hierarchy and all call paths carry
     * these rows, reject *before* annotation expansion or linked-type shape
     * projection could silently discard the metadata.
     */
    private static void rejectUnloweredCheckedEffects(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn && !fn.effects().isEmpty()) {
                    throw new IllegalArgumentException(
                            "checked exceptional effect on function '" + fn.name()
                                    + "' is parsed but not yet enabled for type checking");
                }
                if (declaration instanceof Ast.ClassDecl klass) {
                    rejectUnloweredCheckedClassEffects(klass);
                }
                if (declaration instanceof Ast.InterfaceDecl iface) {
                    rejectUnloweredCheckedInterfaceEffects(iface);
                }
            }
        }
    }

    private static void rejectUnloweredCheckedClassEffects(Ast.ClassDecl klass) {
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.effects().isEmpty()) {
                throw new IllegalArgumentException(
                        "checked exceptional effect on method '" + klass.name() + "."
                                + method.name()
                                + "' is parsed but not yet enabled for type checking");
            }
        }
    }

    private static void rejectUnloweredCheckedInterfaceEffects(Ast.InterfaceDecl iface) {
        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn && !fn.effects().isEmpty()) {
                throw new IllegalArgumentException(
                        "checked exceptional effect on interface method '" + iface.name()
                                + "." + fn.name()
                                + "' is parsed but not yet enabled for type checking");
            }
        }
    }

    public static TypeCheckResult checkTypesWithDiagnostics(Ast.Program program) {
        rejectUnloweredCheckedEffects(program);
        program = AnnotationExpander.expand(program);
        // Expansion can synthesize declarations; recheck before type or
        // structural projection can discard newly introduced effect rows.
        rejectUnloweredCheckedEffects(program);
        ReturnCleanupChecker.check(program);
        TypeChecker checker = new TypeChecker();
        checker.validateImports(program);
        checker.collect(program);
        checker.validate(program);
        return new TypeCheckResult(program, checker.warnings, checker.shortInferredTypeNames);
    }

    /**
     * Checked, public, explicitly imported class declarations supply field
     * signatures to this compilation unit without importing foreign bodies.
     * A missing linked signature must remain Unknown (and fail on arithmetic)
     * rather than fabricating an integer or disabling type checking.
     */
    public static Ast.Program checkTypesWithImportedClasses(
            Ast.Program program, Map<String, Ast.ClassDecl> linkedClasses,
            Map<String, Map<String, Ast.TypeAliasDecl>> linkedAliases) {
        return checkTypesWithImportedClassesWithDiagnostics(
                program, linkedClasses, linkedAliases).program();
    }

    /** Proof metadata must cross both type and ownership passes in linked builds. */
    public static TypeCheckResult checkTypesWithImportedClassesWithDiagnostics(
            Ast.Program program, Map<String, Ast.ClassDecl> linkedClasses,
            Map<String, Map<String, Ast.TypeAliasDecl>> linkedAliases) {
        return checkTypesWithLinkedMetadataWithDiagnostics(
                program, linkedClasses, linkedAliases, Map.of());
    }

    /** Linked classes and module contracts share the same admission pipeline. */
    public static TypeCheckResult checkTypesWithLinkedMetadataWithDiagnostics(
            Ast.Program program, Map<String, Ast.ClassDecl> linkedClasses,
            Map<String, Map<String, Ast.TypeAliasDecl>> linkedAliases,
            Map<String, Ast.InterfaceDecl> linkedContracts) {
        if (linkedContracts == null) {
            throw new IllegalArgumentException("linked contracts cannot be null");
        }
        if (linkedClasses == null || linkedAliases == null) {
            throw new IllegalArgumentException("linked public classes/aliases cannot be null");
        }
        rejectUnloweredCheckedEffects(program);
        for (Ast.ClassDecl linked : linkedClasses.values()) {
            rejectUnloweredCheckedClassEffects(linked);
        }
        for (Ast.InterfaceDecl linked : linkedContracts.values()) {
            rejectUnloweredCheckedInterfaceEffects(linked);
        }
        program = AnnotationExpander.expand(program);
        // Expansion can synthesize declarations; recheck before type or
        // structural projection can discard newly introduced effect rows.
        rejectUnloweredCheckedEffects(program);
        ReturnCleanupChecker.check(program);
        TypeChecker checker = new TypeChecker();
        checker.validateImports(program);
        checker.collect(program);
        for (Map.Entry<String, Ast.InterfaceDecl> entry : linkedContracts.entrySet()) {
            String localName = entry.getKey();
            Ast.InterfaceDecl declaration = entry.getValue();
            if (localName == null || localName.isBlank() || declaration == null
                    || !declaration.moduleContract()
                    || declaration.visibility() != Ast.Visibility.PUBLIC
                    || !declaration.parents().isEmpty()) {
                throw new IllegalArgumentException("invalid linked module contract metadata");
            }
            boolean imported = program.imports().stream().anyMatch(use ->
                    use.kind() == Ast.ImportKind.CONTRACT && !use.wildcard()
                            && use.names().contains(declaration.name())
                            && ImportRules.localName(use, declaration.name()).equals(localName));
            if (!imported || checker.interfaces.containsKey(localName)
                    || checker.ambiguousInterfaces.contains(localName)) {
                throw new IllegalArgumentException(
                        "linked contract was not uniquely imported: " + localName);
            }
            // Alias identity is local type-only metadata, never a runtime export.
            Ast.InterfaceDecl local = new Ast.InterfaceDecl(
                    localName, declaration.visibility(), declaration.genericParameters(),
                    declaration.parents(), declaration.members(), true);
            checker.interfaces.put(localName, local);
        }
        for (Map.Entry<String, Ast.ClassDecl> entry : linkedClasses.entrySet()) {
            String name = entry.getKey();
            Ast.ClassDecl declaration = entry.getValue();
            if (name == null || declaration == null || !name.equals(declaration.name())
                    || declaration.visibility() != Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException("invalid linked public class: " + name);
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
                        "linked public class was not uniquely imported: " + name);
            }
            checker.classes.put(name, declaration);
            Map<String, Ast.TypeAliasDecl> aliases =
                    linkedAliases.getOrDefault(name, Map.of());
            for (Map.Entry<String, Ast.TypeAliasDecl> alias : aliases.entrySet()) {
                if (alias.getKey() == null || alias.getValue() == null
                        || !alias.getKey().equals(alias.getValue().name())) {
                    throw new IllegalArgumentException("invalid private linked field type alias");
                }
            }
            checker.linkedClassAliases.put(declaration, Map.copyOf(aliases));
            checker.hiddenLinkedAliasNames.addAll(aliases.keySet());
        }
        if (!linkedClasses.keySet().containsAll(linkedAliases.keySet())) {
            throw new IllegalArgumentException("orphaned linked class field alias metadata");
        }
        checker.validate(program);
        return new TypeCheckResult(program, checker.warnings, checker.shortInferredTypeNames);
    }

    private void validateImports(Ast.Program program) {
        Set<String> exposed = new HashSet<>();
        Set<String> localNames = new HashSet<>(Set.of(
                "stdio", "std", "process", "actor", "fs", "File", "network", "net", "http", "env",
                "print", "Some", "None", "Ok", "Err", "And", "Or", "Xor", "BooleanOps",
                "Box", "Arc", "Weak", "Rc", "RefCell", "Mutex", "RwLock",
                "SharedMutex", "Channel", "SelectCase", "SelectSet", "SelectResult",
                "Object", "List", "Option", "Result", "Future",
                "HttpServer", "HttpExchange", "HttpHandler", "HttpResponse", "TcpConnection",
                "ActorMail", "ActorOutput", "ActorOutputStream", "ActorId", "ActorKind", "ActorStream", "StreamOperator",
                "Iterator", "AsyncIterator", "IteratorResult", "Generator", "AsyncGenerator",
                "int", "uint", "float", "decimal", "complex", "bool", "boolean", "String", "Symbol", "void",
                "self", "null"));

        for (Ast.ModuleDecl module : program.modules()) {
            if (!module.name().equals(Parser.ROOT_MODULE)) localNames.add(module.name());
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) localNames.add(fn.name());
                else if (declaration instanceof Ast.ClassDecl klass) {
                    if (klass.name().equals("HttpResponse")) {
                        throw new IllegalArgumentException(
                                "HttpResponse is a sealed transport response type; user classes cannot shadow it");
                    }
                    localNames.add(klass.name());
                }
                else if (declaration instanceof Ast.InterfaceDecl iface) localNames.add(iface.name());
                else if (declaration instanceof Ast.FieldDecl field) localNames.add(field.name());
                else if (declaration instanceof Ast.TypeAliasDecl alias) {
                    if (alias.name().equals("HttpResponse")) {
                        throw new IllegalArgumentException(
                                "HttpResponse is a sealed transport response type; type aliases cannot shadow it");
                    }
                    localNames.add(alias.name());
                }
            }
        }

        for (Ast.ImportDecl imported : program.imports()) {
            ImportRules.validate(imported);
            for (String name : ImportRules.exposedBindings(imported)) {
                if (!exposed.add(name)) throw new IllegalArgumentException("duplicate imported name '" + name + "'");
                importedNames.add(name);
                if (localNames.contains(name)) {
                    throw new IllegalArgumentException("imported name '" + name + "' conflicts with a local or builtin name");
                }
                // Cross-file imports are opaque to this per-unit typechecker.
                // Runtime-bearing imports enter the value namespace. Oreslang
                // classes also have a value-side constructor/static namespace,
                // but actors/interfaces/traits/structs/type aliases do not.
                if (!ImportRules.isTypeOnlyKind(imported.kind())
                        || imported.kind() == Ast.ImportKind.CLASS
                        || ImportRules.isJavaPath(imported.path())) {
                    importedValues.add(name);
                }
            }
        }
    }

    private void collect(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            if (modules.putIfAbsent(module.name(), module) != null) throw new IllegalArgumentException("duplicate module '" + module.name() + "'");
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    putFunction(module.name(), fn);
                } else if (decl instanceof Ast.ClassDecl klass) {
                    rejectReservedSymbolTypeName(klass.name(), "class");
                    putQualified(classes, ambiguousClasses, module.name(), klass.name(), klass, "class");
                    classOwners.put(klass, module.name());
                } else if (decl instanceof Ast.InterfaceDecl iface) {
                    rejectReservedSymbolTypeName(iface.name(), "interface");
                    putQualified(interfaces, ambiguousInterfaces, module.name(), iface.name(), iface, "interface");
                    interfaceOwners.put(iface, module.name());
                } else if (decl instanceof Ast.TypeAliasDecl alias) {
                    rejectReservedSymbolTypeName(alias.name(), "type alias");
                    putQualified(typeAliases, ambiguousTypeAliases, module.name(), alias.name(), alias, "type alias");
                    typeAliasOwners.put(alias, module.name());
                }
            }
        }
    }

    private static void rejectReservedSymbolTypeName(String name, String declarationKind) {
        if ("Symbol".equals(name)) {
            throw new IllegalArgumentException("Symbol is a reserved nominal built-in; cannot declare "
                    + declarationKind + " Symbol");
        }
    }

    private void putFunction(String module, Ast.FunctionDecl fn) {
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
        LinkedHashMap<Integer, Ast.FunctionDecl> family =
                functions.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        Ast.FunctionDecl previous = family.putIfAbsent(fn.parameters().size(), fn);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "callable '" + key + "' already has arity " + fn.parameters().size()
                            + "; module/top-level overload identity is name + arity only; "
                            + "parameter types and return types do not participate");
        }
    }

    private static <T> void putQualified(Map<String, T> map, Set<String> ambiguous, String module, String name, T value, String kind) {
        String qualified = module + "." + name;
        if (map.putIfAbsent(qualified, value) != null) {
            throw new IllegalArgumentException("duplicate " + kind + " '" + qualified + "'; fnc/routine declarations cannot overload");
        }
        T previous = map.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            ambiguous.add(name);
            map.remove(name);
        }
    }

    private void validate(Ast.Program program) {
        // Classes, traits/interfaces and transparent type aliases all occupy
        // the *type* namespace. Keeping them in distinct lookup maps must not
        // permit same-scope spelling collisions or ambiguous nominal identity.
        // Functions/bindings are checked separately in the value namespace.
        for (Ast.ModuleDecl module : program.modules()) {
            Map<String, String> types = new LinkedHashMap<>();
            for (Ast.Decl decl : module.declarations()) {
                String name;
                String category;
                if (decl instanceof Ast.ClassDecl klass) {
                    name = klass.name();
                    category = "class";
                } else if (decl instanceof Ast.InterfaceDecl iface) {
                    name = iface.name();
                    category = iface.moduleContract() ? "contract" : "interface/trait";
                } else if (decl instanceof Ast.TypeAliasDecl alias) {
                    name = alias.name();
                    category = "type alias";
                } else {
                    continue;
                }
                String previous = types.putIfAbsent(name, category);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate type namespace name '" + module.name() + "." + name
                                    + "' (" + previous + " versus " + category + ")");
                }
            }
        }
        // Local overload identity must be validated before class-shape
        // construction. Otherwise same-name/same-arity declarations whose
        // types differ can fail later as a generic structural conflict before
        // the language's arity-only overload rule is diagnosed.
        for (Ast.ClassDecl klass : classOwners.keySet()) validateLocalClassOverloads(klass);
        for (Ast.ClassDecl klass : classOwners.keySet()) validateInheritedClassOverloads(klass);
        for (Ast.ClassDecl klass : classOwners.keySet()) validateAbstractMethodSlots(klass);
        for (Ast.InterfaceDecl iface : interfaceOwners.keySet()) validateInterfaceAritySlots(iface, new LinkedHashSet<>());
        for (Ast.ClassDecl klass : classOwners.keySet()) classShape(klass, new LinkedHashSet<>());
        for (Ast.InterfaceDecl iface : interfaceOwners.keySet()) interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());

        for (Ast.ModuleDecl module : program.modules()) {
            String previousModule = currentLexicalModule;
            currentLexicalModule = module.name();
            try {
                validateModuleValueNamespace(module);
                checkModuleAdherence(module);
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.FunctionDecl fn) checkFunction(module.name(), fn);
                    else if (decl instanceof Ast.ClassDecl klass) checkClass(module.name(), klass);
                    else if (decl instanceof Ast.InterfaceDecl iface) checkInterface(iface);
                    else if (decl instanceof Ast.TypeAliasDecl alias) {
                        Set<String> aliasGenerics = uniqueGenerics(alias.genericParameters(), "type alias " + alias.name());
                        resolve(alias.target(), aliasGenerics, null);
                    }
                    else if (decl instanceof Ast.FieldDecl field) checkModuleBinding(field);
                }
            } finally {
                currentLexicalModule = previousModule;
            }
        }
    }

    private void validateModuleValueNamespace(Ast.ModuleDecl module) {
        Map<String, String> categories = new LinkedHashMap<>();
        for (Ast.Decl decl : module.declarations()) {
            String name;
            String category;
            if (decl instanceof Ast.FunctionDecl fn) {
                name = fn.name();
                // FNC and ROUTINE share the same name+arity callable family.
                // Their kinds affect whether a value can be reified, not the slot key.
                // putFunctionSlot has already rejected any duplicate arity.
                category = "callable";
            } else if (decl instanceof Ast.ClassDecl klass) {
                name = klass.name();
                category = "class";
            } else if (decl instanceof Ast.FieldDecl field) {
                name = field.name();
                category = "binding";
            } else {
                continue; // interfaces and aliases occupy the type namespace
            }

            String previous = categories.putIfAbsent(name, category);
            if (previous != null && !previous.equals(category)) {
                throw new IllegalArgumentException(
                        "module value member '" + module.name() + "." + name
                                + "' is ambiguous between " + previous + " and " + category
                                + "; callable/class/binding names share one runtime value namespace");
            }
        }
    }

    private void checkModuleAdherence(Ast.ModuleDecl module) {
        for (Ast.Annotation annotation : module.annotations()) {
            if (annotation.name().equals("AdheresTo")) {
                throw new IllegalArgumentException("@AdheresTo is no longer valid for modules; use 'conforms <Contract>'");
            }
        }
        if (module.contracts().isEmpty()) return;
        Record actual = moduleShape(module);
        Set<String> seenContracts = new HashSet<>();
        for (Ast.TypeRef ref : module.contracts()) {
            if (!seenContracts.add(ref.name())) {
                throw new IllegalArgumentException("module '" + module.name() + "' lists contract '" + ref.name() + "' more than once");
            }
            Ast.InterfaceDecl contract = findInterface(ref.name());
            if (contract == null || !contract.moduleContract()) {
                throw new IllegalArgumentException("unknown module contract '" + ref.name() + "'");
            }
            Type resolvedRef = resolveModuleContractRef(ref, contract);
            if (!(resolvedRef instanceof Named namedRef)) {
                throw new IllegalArgumentException("module contract target must be a named contract type");
            }
            Record expectedTemplate = interfaceShape(contract, Set.copyOf(contract.genericParameters()), new LinkedHashSet<>());
            Record expected = (Record) substituteGenerics(expectedTemplate,
                    genericBindings(contract.genericParameters(), namedRef.arguments(), "contract " + contract.name()));
            if (!assignable(actual, expected)) {
                throw new IllegalArgumentException("module '" + module.name() + "' does not conform to contract '" + ref.name()
                        + "': expected " + expected + " but got " + actual);
            }
            validateModuleContractFields(module, contract, new LinkedHashSet<>());
        }
    }

    private void validateModuleContractFields(
            Ast.ModuleDecl module,
            Ast.InterfaceDecl contract,
            Set<Ast.InterfaceDecl> stack) {
        if (!stack.add(contract)) {
            throw new IllegalArgumentException("module contract inheritance cycle involving '" + contract.name() + "'");
        }

        for (Ast.TypeRef parentRef : contract.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent == null || !parent.moduleContract()) {
                throw new IllegalArgumentException(
                        "module contract '" + contract.name() + "' has invalid parent '" + parentRef.name() + "'");
            }
            validateModuleContractFields(module, parent, stack);
        }

        Map<String, Ast.FieldDecl> actualFields = new LinkedHashMap<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FieldDecl field && field.visibility() == Ast.Visibility.PUBLIC) {
                actualFields.put(field.name(), field);
            }
        }

        for (Ast.InterfaceMember member : contract.members()) {
            if (!(member instanceof Ast.InterfaceFieldDecl required)) continue;
            Ast.FieldDecl actual = actualFields.get(required.name());
            if (actual == null) {
                throw new IllegalArgumentException(
                        "module '" + module.name() + "' is missing required contract field '" + contract.name()
                                + "." + required.name() + "'");
            }
            boolean actualRebindable = actual.bindingKind() == Ast.BindingKind.LET;
            boolean requiredRebindable = required.bindingKind() == Ast.BindingKind.LET;
            if (required.bindingKind() != null
                    && (actualRebindable != requiredRebindable
                            || actual.mutableReferent() != required.mutableReferent())) {
                throw new IllegalArgumentException(
                        "module '" + module.name() + "." + actual.name()
                                + "' has binding capabilities rebindable=" + actualRebindable
                                + ", mutableReferent=" + actual.mutableReferent()
                                + " but contract '" + contract.name()
                                + "' requires rebindable=" + requiredRebindable
                                + ", mutableReferent=" + required.mutableReferent());
            }
        }

        stack.remove(contract);
    }

    private Type resolveModuleContractRef(Ast.TypeRef ref, Ast.InterfaceDecl contract) {
        return resolveModuleContractRef(ref, contract, Set.of());
    }

    private Type resolveModuleContractRef(Ast.TypeRef ref, Ast.InterfaceDecl contract, Set<String> generics) {
        validateGenericArity(ref, contract.genericParameters(), "contract " + contract.name());
        return new Named(qualifiedInterfaceName(contract),
                ref.arguments().stream().map(arg -> resolve(arg, generics, null)).toList());
    }

    private Record moduleShape(Ast.ModuleDecl module) {
        Map<String, Type> members = new LinkedHashMap<>();
        Map<String, Integer> callableCounts = new HashMap<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn
                    && fn.visibility() == Ast.Visibility.PUBLIC
                    && fn.actorKind() == Ast.ActorKind.NONE) {
                callableCounts.merge(fn.name(), 1, Integer::sum);
            }
        }
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn && fn.visibility() == Ast.Visibility.PUBLIC
                    && fn.actorKind() == Ast.ActorKind.NONE) {
                Type signature = callableContractType(
                        fn.genericParameters(), fn.parameters(), fn.returnType(), fn.async(), fn.generator(), Set.of(), null);
                if (fn.trapped() && signature instanceof Function trappedSignature) {
                    signature = new Function(
                            trappedSignature.parameters(),
                            trappedSignature.mutableParameters(),
                            trappedSignature.async(),
                            trapResult(true, trappedSignature.result()),
                            trappedSignature.effects());
                }

                // Only concretely reifiable fnc declarations become raw
                // function-valued namespace members. Routines remain
                // direct-call-only, and generic fnc values still require
                // direct-call specialization because polymorphic function
                // values are not implemented.
                if (fn.kind() == Ast.CallableKind.FNC
                        && fn.genericParameters().isEmpty()
                        && callableCounts.getOrDefault(fn.name(), 0) == 1) {
                    mergeMember(members, fn.name(), signature, "module " + module.name());
                }
                mergeMember(members,
                        methodContractKey(fn.name(), fn.parameters().size(), fn.genericParameters().size()),
                        signature,
                        "module " + module.name());
            } else if (decl instanceof Ast.FieldDecl field && field.visibility() == Ast.Visibility.PUBLIC) {
                Type type = field.type() == null ? typeOf(field.initializer(), new Env(null), Set.of(), null) : resolve(field.type(), Set.of(), null);
                mergeMember(members, field.name(), type, "module " + module.name());
            }
        }
        return new Record(members);
    }

    private void checkInterface(Ast.InterfaceDecl iface) {
        Set<String> generics = uniqueGenerics(iface.genericParameters(), "interface " + iface.name());
        Type interfaceSelf = iface.moduleContract() ? null : new SelfType(nominalInterfaceType(iface));

        Set<String> interfaceFieldNames = new LinkedHashSet<>();
        collectInterfaceFieldNames(iface, interfaceFieldNames, new LinkedHashSet<>());
        Set<String> interfaceMethodNames = new LinkedHashSet<>();
        collectInterfaceMethodNames(iface, interfaceMethodNames, new LinkedHashSet<>());
        for (String name : interfaceFieldNames) {
            if (interfaceMethodNames.contains(name)) {
                throw new IllegalArgumentException(
                        "interface member '" + iface.name() + "." + name
                                + "' cannot be both a field and a method across inheritance; "
                                + "member-value and direct-call syntax must remain unambiguous");
            }
        }

        Set<String> memberKeys = new HashSet<>();
        for (Ast.TypeRef parentRef : iface.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent == null) throw new IllegalArgumentException("unknown parent interface '" + parentRef.name() + "' for " + iface.name());
            if (iface.moduleContract() != parent.moduleContract()) {
                throw new IllegalArgumentException("interfaces and module contracts cannot extend each other");
            }
            if (iface.moduleContract()) resolveModuleContractRef(parentRef, parent, generics);
            else resolve(parentRef, generics, null);
        }

        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn) {
                String key = CallableSelector.instance(fn.name(), fn.parameters().size()).mangledName();
                if (!memberKeys.add(key)) throw new IllegalArgumentException("duplicate interface method '" + iface.name() + "." + fn.name() + "' with arity " + fn.parameters().size());
                Set<String> all = new HashSet<>(generics);
                for (String generic : fn.genericParameters()) {
                    if (!all.add(generic)) throw new IllegalArgumentException("duplicate/shadowed generic '" + generic + "' in interface " + iface.name() + "." + fn.name());
                }
                functionType(fn.parameters(), fn.returnType(), false, false, all, interfaceSelf);
            } else {
                Ast.InterfaceFieldDecl field = (Ast.InterfaceFieldDecl) member;
                if (!memberKeys.add(field.name())) throw new IllegalArgumentException("duplicate interface member '" + iface.name() + "." + field.name() + "'");
                resolve(field.type(), generics, null);
            }
        }
    }

    private void defineImplicitArguments(Env env, List<Type> parameterTypes) {
        env.define(
                "arguments",
                new Tuple(List.copyOf(parameterTypes), TupleKind.TUPLE),
                Ast.BindingKind.CONST);
    }

    private void requireSuspendableCall(boolean suspends, String label) {
        if (suspends && !currentMaySuspend)
            throw new IllegalArgumentException(
                    "suspends " + label + " requires an async or suspends caller");
    }

    private void checkFunction(String module, Ast.FunctionDecl fn) {
        if (fn.name().endsWith("_blocking") && !fn.suspends())
            throw new IllegalArgumentException(
                    "*_blocking function '" + fn.name() + "' must declare suspends");
        if (fn.trapped()) {
            if (fn.async()) {
                throw new IllegalArgumentException("async trap callables are not enabled until the trap boundary spans every await");
            }
            if (fn.generator()) {
                throw new IllegalArgumentException("trap generators are not enabled until the trap boundary spans generator suspension");
            }
            if (fn.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalArgumentException("trap actor callables are not enabled until actor supervision preserves the trap effect");
            }
        }
        if (fn.name().equals("init")) {
            Ast.TypeRef initReturn = fn.returnType();
            if (fn.visibility() != Ast.Visibility.PRIVATE
                    || fn.async()
                    || fn.suspends()
                    || fn.generator()
                    || fn.structural()
                    || fn.nonLexical()
                    || fn.trapped()
                    || fn.actorKind() != Ast.ActorKind.NONE
                    || !fn.genericParameters().isEmpty()
                    || !fn.parameters().isEmpty()
                    || initReturn == null
                    || !"void".equals(initReturn.name())
                    || !initReturn.arguments().isEmpty()
                    || initReturn.inferArguments()) {
                throw new IllegalArgumentException(
                        "init hook must be a private synchronous non-generator non-structural non-actor non-generic zero-arity "
                                + "fnc/routine returning void");
            }
        }
        if (fn.name().equals("main") && (fn.actorKind() != Ast.ActorKind.NONE || fn.generator())) {
            throw new IllegalArgumentException(
                    "program entrypoint 'main' cannot be an actor or generator fnc; main must run synchronously and explicitly launch work");
        }
        if (fn.generator() && fn.actorKind() != Ast.ActorKind.NONE) {
            throw new IllegalArgumentException(
                    "actor callables cannot be generators because a generator activation would escape a mailbox turn");
        }
        if (fn.async() && fn.actorKind() != Ast.ActorKind.NONE) {
            throw new IllegalArgumentException(
                    "async actor callables require mailbox continuation lowering; use an ordinary async fnc or a mailbox actor");
        }
        if (fn.async() && !fn.genericParameters().isEmpty()) {
            throw new IllegalArgumentException(
                    "generic async callables require an explicit task-safe/sendable generic bound, which is not available yet");
        }

        Set<String> generics = uniqueGenerics(
                fn.genericParameters(),
                (fn.kind() == Ast.CallableKind.ROUTINE ? "routine " : "function ") + fn.name());
        validateFunctionGenericDefaults(fn);
        Env env = new Env(moduleBindingEnv(module), fn.nonLexical());
        List<Type> argumentTypes = new ArrayList<>(fn.parameters().size());
        for (Ast.Param param : fn.parameters()) {
            Type parameterType = resolveParam(param, generics, null);
            argumentTypes.add(parameterType);
            if (param.structural() && fn.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalArgumentException(
                        "structural parameter '" + param.name() + "' cannot cross an actor mailbox boundary; "
                                + "pass an owned/sendable nominal value instead");
            }
            if (param.structural() && (fn.async() || fn.generator())) {
                throw new IllegalArgumentException(
                        "structural parameter '" + param.name() + "' cannot outlive a synchronous call across await/suspension; "
                                + "pass an owned nominal value or copy the required fields");
            }
            if (fn.actorKind() != Ast.ActorKind.NONE) {
                validateActorCallableBoundaryType(
                        parameterType,
                        fn.actorKind(),
                        false,
                        "parameter '" + param.name() + "' of actor callable '" + module + "." + fn.name() + "'");
            }
            if (fn.async()) {
                validateAsyncBoundaryType(
                        parameterType,
                        false,
                        "parameter '" + param.name() + "' of async callable '" + module + "." + fn.name() + "'");
            }
            env.define(
                    param.name(),
                    parameterType,
                    param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        }
        defineImplicitArguments(env, argumentTypes);

        // Generator declarations may spell either the yielded element type
        // (: int) or the public iterator protocol (: Iterator<int> /
        // : AsyncIterator<int>). The body always checks yields against the
        // element type and calls expose exactly one iterator wrapper.
        Type declaredReturn = resolve(fn.returnType(), generics, null);
        Type returns = fn.generator()
                ? generatorYieldType(fn.async(), declaredReturn)
                : declaredReturn;
        if (returns == Primitive.VOID && fn.generator()) {
            throw new IllegalArgumentException(
                    "generator '" + module + "." + fn.name() + "' must declare a non-void yielded element type");
        }
        if (fn.actorKind() != Ast.ActorKind.NONE) {
            validateActorCallableBoundaryType(
                    returns,
                    fn.actorKind(),
                    true,
                    "return type of actor callable '" + module + "." + fn.name() + "'");
        }
        if (fn.async()) {
            validateAsyncBoundaryType(
                    returns,
                    true,
                    (fn.generator() ? "yield type of async generator '" : "return type of async callable '")
                            + module + "." + fn.name() + "'");
        }

        Ast.ActorKind previousActorKind = currentActorKind;
        boolean previousGenerator = currentGenerator;
        boolean previousAsync = currentAsyncCallable;
        currentActorKind = fn.actorKind();
        currentGenerator = fn.generator();
        currentAsyncCallable = fn.async();
        boolean previousMaySuspend = currentMaySuspend;
        currentMaySuspend = fn.async() || fn.suspends();
        try {
            checkBlock(fn.body(), env, generics, returns, null);
        } finally {
            currentActorKind = previousActorKind;
            currentGenerator = previousGenerator;
            currentAsyncCallable = previousAsync;
            currentMaySuspend = previousMaySuspend;
        }

        if (!fn.generator() && returns != Primitive.VOID && !definitelyReturns(fn.body())) {
            throw new IllegalArgumentException(
                    "non-void " + fn.kind().name().toLowerCase() + " '" + module + "." + fn.name()
                            + "' must explicitly return on every path");
        }
        if (fn.visibility() == Ast.Visibility.PUBLIC && hasInferredArgs(fn.returnType())) {
            throw new IllegalArgumentException(
                    "public callable '" + fn.name() + "' cannot export unresolved <> type arguments");
        }
    }

    private void validateLocalClassOverloads(Ast.ClassDecl klass) {
        Set<Ast.MethodArity> instanceMethodOverloads = new HashSet<>();
        Set<Ast.MethodArity> staticFunctionOverloads = new HashSet<>();
        for (Ast.MethodDecl method : klass.methods()) {
            Set<Ast.MethodArity> overloads = method.isStatic() ? staticFunctionOverloads : instanceMethodOverloads;
            if (!overloads.add(method.overloadIdentity())) {
                String label = method.isStatic() ? "static function" : "method";
                throw new IllegalArgumentException(label + " '" + klass.name() + "." + method.name() + "' already has arity " + method.arity()
                        + "; overload identity is name + arity only; parameter types and return types do not participate");
            }
        }
    }

    private record MethodOrigin(Ast.ClassDecl owner, Named ownerType) { }

    private void validateInheritedClassOverloads(Ast.ClassDecl klass) {
        validateInheritedClassOverloads(klass, false);
        validateInheritedClassOverloads(klass, true);
    }

    private void validateInheritedClassOverloads(Ast.ClassDecl klass, boolean staticNamespace) {
        Set<Ast.MethodArity> locallyResolved = new HashSet<>();
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic() == staticNamespace) locallyResolved.add(method.overloadIdentity());
        }

        Named classType = nominalClassType(klass);
        Map<Ast.MethodArity, List<MethodOrigin>> inherited =
                inheritedMethodOrigins(klass, classType, staticNamespace, new LinkedHashSet<>());

        for (Map.Entry<Ast.MethodArity, List<MethodOrigin>> entry : inherited.entrySet()) {
            for (Ast.MethodDecl local : klass.methods()) {
                if (local.isStatic() != staticNamespace || !local.overloadIdentity().equals(entry.getKey())) continue;
                for (MethodOrigin origin : entry.getValue()) {
                    for (Ast.MethodDecl parentMethod : origin.owner().methods()) {
                        if (parentMethod.isStatic() != staticNamespace
                                || !parentMethod.overloadIdentity().equals(entry.getKey())) continue;
                        if (parentMethod.genericParameters().size() != local.genericParameters().size()) {
                            throw new IllegalArgumentException("incompatible generic arity for method slot '"
                                    + klass.name() + "." + local.name() + "' with arity " + local.arity());
                        }
                        // Dispatch selects the child's declaration, but inherited public
                        // contracts still advertise this slot to callers (including through
                        // a base-typed receiver). A private override would typecheck as
                        // public and then fail runtime access checks.
                        if (parentMethod.visibility() == Ast.Visibility.PUBLIC
                                && local.visibility() != Ast.Visibility.PUBLIC) {
                            String kind = staticNamespace ? "static function" : "method";
                            throw new IllegalArgumentException("private " + kind + " '"
                                    + klass.name() + "." + local.name() + "' with arity " + local.arity()
                                    + " cannot override inherited public " + kind + " from "
                                    + methodOriginLabel(origin)
                                    + "; keep the overriding declaration public");
                        }
                    }
                }
            }
            if (locallyResolved.contains(entry.getKey())) continue;
            List<MethodOrigin> origins = entry.getValue();
            if (origins.size() <= 1) continue;

            String label = staticNamespace ? "static function" : "method";
            String owners = origins.stream()
                    .map(this::methodOriginLabel)
                    .sorted()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            throw new IllegalArgumentException("ambiguous inherited " + label + " '" + klass.name() + "."
                    + entry.getKey().name() + "' with arity " + entry.getKey().arity()
                    + " from " + owners
                    + "; add an explicit child declaration for this name + arity slot; argument and return types never select an overload");
        }
    }

    private Map<Ast.MethodArity, List<MethodOrigin>> inheritedMethodOrigins(
            Ast.ClassDecl klass,
            Named klassType,
            boolean staticNamespace,
            Set<Ast.ClassDecl> stack) {
        Map<Ast.MethodArity, List<MethodOrigin>> result = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Named parentType = resolveParentView(klass, klassType, parentRef, parent);
            mergeMethodOrigins(result, effectiveMethodOrigins(parent, parentType, staticNamespace, stack));
        }
        return result;
    }

    private Map<Ast.MethodArity, List<MethodOrigin>> effectiveMethodOrigins(
            Ast.ClassDecl klass,
            Named klassType,
            boolean staticNamespace,
            Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        try {
            Map<Ast.MethodArity, List<MethodOrigin>> result =
                    inheritedMethodOrigins(klass, klassType, staticNamespace, stack);
            for (Ast.MethodDecl method : klass.methods()) {
                if (method.isStatic() != staticNamespace) continue;
                result.put(method.overloadIdentity(),
                        new ArrayList<>(List.of(new MethodOrigin(klass, canonicalClassView(klass, klassType)))));
            }
            return result;
        } finally {
            stack.remove(klass);
        }
    }

    private void mergeMethodOrigins(
            Map<Ast.MethodArity, List<MethodOrigin>> target,
            Map<Ast.MethodArity, List<MethodOrigin>> source) {
        for (Map.Entry<Ast.MethodArity, List<MethodOrigin>> entry : source.entrySet()) {
            List<MethodOrigin> origins = target.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>());
            for (MethodOrigin candidate : entry.getValue()) {
                boolean alreadyPresent = origins.stream().anyMatch(existing ->
                        existing.owner() == candidate.owner() && existing.ownerType().equals(candidate.ownerType()));
                if (!alreadyPresent) origins.add(candidate);
            }
        }
    }

    private Named resolveParentView(
            Ast.ClassDecl child,
            Named childType,
            Ast.TypeRef parentRef,
            Ast.ClassDecl parent) {
        Map<String, Type> bindings = classGenericBindings(child, childType);
        Type rawParent = resolve(parentRef, Set.copyOf(child.genericParameters()), childType);
        Type substituted = substituteGenerics(rawParent, bindings);
        if (!(substituted instanceof Named named)) {
            throw new IllegalArgumentException("parent class view for '" + parentRef.name() + "' is not nominal");
        }
        if (named.arguments().size() != parent.genericParameters().size()) {
            throw new IllegalArgumentException("parent class '" + qualifiedClassName(parent) + "' expects "
                    + parent.genericParameters().size() + " type argument(s), got " + named.arguments().size());
        }
        return new Named(qualifiedClassName(parent), named.arguments());
    }

    private Named canonicalClassView(Ast.ClassDecl klass, Named view) {
        if (view.arguments().size() != klass.genericParameters().size()) {
            throw new IllegalArgumentException("class '" + qualifiedClassName(klass) + "' expects "
                    + klass.genericParameters().size() + " type argument(s), got " + view.arguments().size());
        }
        return new Named(qualifiedClassName(klass), view.arguments());
    }

    private String methodOriginLabel(MethodOrigin origin) {
        String owner = qualifiedClassName(origin.owner());
        if (origin.ownerType().arguments().isEmpty()) return owner;
        return owner + origin.ownerType().arguments();
    }

    private Map<Ast.MethodArity, Integer> validateInterfaceAritySlots(
            Ast.InterfaceDecl iface, Set<Ast.InterfaceDecl> stack) {
        if (!stack.add(iface)) throw new IllegalArgumentException("interface inheritance cycle involving '" + iface.name() + "'");
        try {
            Map<Ast.MethodArity, Integer> slots = new LinkedHashMap<>();
            for (Ast.TypeRef parentRef : iface.parents()) {
                Ast.InterfaceDecl parent = findInterface(parentRef.name());
                if (parent == null) continue; // interfaceShape diagnoses unknown parents.
                for (var entry : validateInterfaceAritySlots(parent, stack).entrySet()) {
                    Integer existing = slots.putIfAbsent(entry.getKey(), entry.getValue());
                    if (existing != null && !existing.equals(entry.getValue())) {
                        throw new IllegalArgumentException("incompatible generic arity for interface method slot '"
                                + iface.name() + "." + entry.getKey().name() + "'");
                    }
                }
            }
            Set<Ast.MethodArity> localSlots = new HashSet<>();
            for (Ast.InterfaceMember member : iface.members()) {
                if (!(member instanceof Ast.InterfaceFunctionDecl fn)) continue;
                Ast.MethodArity slot = new Ast.MethodArity(fn.name(), fn.parameters().size());
                if (!localSlots.add(slot)) {
                    throw new IllegalArgumentException("interface method '" + iface.name() + "." + fn.name()
                            + "' already has arity " + fn.parameters().size() + "; overload identity is name + arity only");
                }
                Integer inheritedCount = slots.putIfAbsent(slot, fn.genericParameters().size());
                if (inheritedCount != null && inheritedCount != fn.genericParameters().size()) {
                    throw new IllegalArgumentException("incompatible generic arity for interface method slot '"
                            + iface.name() + "." + fn.name() + "'");
                }
            }
            return slots;
        } finally {
            stack.remove(iface);
        }
    }

    private void validateAbstractMethodSlots(Ast.ClassDecl klass) {
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isAbstract()) continue;
            if (method.isStatic()) {
                throw new IllegalArgumentException(
                        "static class functions cannot be abstract: " + klass.name() + "." + method.name());
            }
            if (method.visibility() != Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException(
                        "abstract method '" + klass.name() + "." + method.name()
                                + "' with arity " + method.arity()
                                + " must be public so subclasses can satisfy the slot");
            }
        }

        if (klass.isAbstract()) return;

        Map<Ast.MethodArity, Ast.MethodDecl> effective =
                effectiveInstanceMethods(klass, new LinkedHashSet<>());
        for (Map.Entry<Ast.MethodArity, Ast.MethodDecl> entry : effective.entrySet()) {
            if (!entry.getValue().isAbstract()) continue;
            throw new IllegalArgumentException(
                    "concrete class '" + klass.name() + "' has unresolved abstract method '"
                            + entry.getKey().name() + "' with arity " + entry.getKey().arity()
                            + "; implement that exact name + arity slot or declare the class abstract");
        }
    }

    private Map<Ast.MethodArity, Ast.MethodDecl> effectiveInstanceMethods(
            Ast.ClassDecl klass,
            Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) {
            throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        }
        try {
            Map<Ast.MethodArity, Ast.MethodDecl> result = new LinkedHashMap<>();
            for (Ast.TypeRef parentRef : klass.parents()) {
                Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
                if (parent == null) continue;
                for (Map.Entry<Ast.MethodArity, Ast.MethodDecl> inherited :
                        effectiveInstanceMethods(parent, stack).entrySet()) {
                    result.putIfAbsent(inherited.getKey(), inherited.getValue());
                }
            }
            for (Ast.MethodDecl method : klass.methods()) {
                if (!method.isStatic()) result.put(method.overloadIdentity(), method);
            }
            return result;
        } finally {
            stack.remove(klass);
        }
    }
    private void checkClass(String module, Ast.ClassDecl klass) {
        Ast.ClassDecl previousLexicalClass = lexicalClassOrigin;
        lexicalClassOrigin = klass;
        try {
            checkClassBody(module, klass);
        } finally {
            lexicalClassOrigin = previousLexicalClass;
        }
    }

    /**
     * Ordinary class construction is synchronous. Reject suspension anywhere
     * in an eager initializer expression, including nested call operands,
     * collection elements and branches. A stored lambda is not executed
     * during construction; directly invoking a lambda initializer remains
     * rejected until its body has a verified non-suspending effect bound.
     */
    private boolean fieldInitializerMaySuspend(Ast.Expr expr) {
        if (expr instanceof Ast.AwaitExpr
                || expr instanceof Ast.TaskExpr
                || expr instanceof Ast.ChannelOpExpr
                || expr instanceof Ast.DynamicSelectExpr
                || expr instanceof Ast.AssignExpr) return true;
        if (expr instanceof Ast.RuntimeCallExpr) {
            // All runtime intrinsics carry semantics beyond an ordinary
            // scalar expression. In particular rt fail may unwind after
            // earlier fields have acquired resources, rt take can move
            // storage, and borrow/share/copy need an initializer-specific
            // lifetime or ownership proof. Such proofs and failure cleanup
            // are not established here, so do not infer safety from the
            // operands alone. This also rejects new intrinsics by default.
            return true;
        }
        if (expr instanceof Ast.BinaryExpr op) {
            // Division/remainder can fail (notably integer remainder by
            // zero) even when both operands were evaluated without effects.
            // Failure cleanup of a partially built instance is not proven.
            // Keep them outside eager initialization until that proof exists.
            if (op.operator().equals("/") || op.operator().equals("%")) return true;
            return fieldInitializerMaySuspend(op.left()) || fieldInitializerMaySuspend(op.right());
        }
        if (expr instanceof Ast.UnaryExpr op) return fieldInitializerMaySuspend(op.operand());
        if (expr instanceof Ast.ConditionalExpr op) {
            return fieldInitializerMaySuspend(op.condition())
                    || fieldInitializerMaySuspend(op.whenTrue())
                    || fieldInitializerMaySuspend(op.whenFalse());
        }
        if (expr instanceof Ast.CallExpr call) {
            // Constructor-safe calls must resolve to a statically known,
            // synchronous zero-argument function whose *entire* body is one
            // return of a primitive scalar expression. No user callbacks,
            // mutations, dynamic dispatch, suspension or partial-self escape.
            return !isConstructorSafeScalarCall(call);
        }
        if (expr instanceof Ast.NewExpr created) {
            // Nested construction is itself a transitive initialization
            // effect. Until class-constructor effect summaries exist, permit
            // only zero-argument ctorless objects with literal-only fields:
            // no methods execute, no owned resources are acquired, and no
            // partially initialized child can escape into user callbacks.
            if (created.type().name().equals("Array") && created.arguments().isEmpty()
                    && findClass("Array") == null)
                return false; // built-in zero-arg allocator, never a shadowing guest class
            if (!created.arguments().isEmpty() || !created.type().arguments().isEmpty())
                return true;
            Ast.ClassDecl nested = findClass(created.type().name());
            if (nested == null || nested.constructor() != null || !nested.parents().isEmpty()
                    || nested.actorKind() != Ast.ActorKind.NONE) return true;
            return nested.fields().stream().anyMatch(field ->
                    !(field.initializer() instanceof Ast.LiteralExpr));
        }
        if (expr instanceof Ast.MemberExpr member)
            return fieldInitializerMaySuspend(member.receiver());
        if (expr instanceof Ast.IndexExpr) {
            // Even pure operands do not establish a safe indexing operation.
            // Bounds failure (and any future index dispatch) may unwind after
            // earlier fields have acquired resources. Partial-construction
            // failure cleanup is not proven, so reject indexing by default.
            return true;
        }
        if (expr instanceof Ast.TypeTestExpr test) return fieldInitializerMaySuspend(test.value());
        if (expr instanceof Ast.PatternTestExpr test) return fieldInitializerMaySuspend(test.value());
        if (expr instanceof Ast.CastExpr) {
            // Checked casts can fail independently of their source expression;
            // permitting one could unwind a partially constructed instance.
            return true;
        }
        if (expr instanceof Ast.SpreadExpr spread) return fieldInitializerMaySuspend(spread.expression());
        if (expr instanceof Ast.ListExpr list)
            return list.elements().stream().anyMatch(this::fieldInitializerMaySuspend);
        if (expr instanceof Ast.TupleExpr tuple)
            return tuple.elements().stream().anyMatch(this::fieldInitializerMaySuspend);
        if (expr instanceof Ast.ObjectExpr object)
            return object.fields().stream().anyMatch(field ->
                    fieldInitializerMaySuspend(field.value()));
        // These are the only effect-free leaf expressions. In particular a
        // lambda is stored, not invoked, and the initializer environment has
        // no self or reference-valued constructor arguments to capture.
        // New expression AST variants are unsafe until reviewed explicitly.
        return !(expr instanceof Ast.LiteralExpr
                || expr instanceof Ast.NameExpr
                || expr instanceof Ast.LambdaExpr);
    }

    /**
     * Narrow constructor-effect proof: a known zero-argument function may
     * initialize a scalar only when its complete body is a single primitive
     * return expression. This is independent of the function's name or
     * visibility; it never assumes that a routine is pure by default.
     */
    private boolean isConstructorSafeScalarCall(Ast.CallExpr call) {
        if (!call.arguments().isEmpty() || call.typeArgumentsPresent()) return false;
        List<Ast.Stmt> body = null;
        Ast.TypeRef returnType = null;
        if (call.callee() instanceof Ast.NameExpr name) {
            Ast.FunctionDecl fn = findFunction(name.name(), 0);
            // A literal-looking body is not enough if the callable's
            // invocation contract is trapped or actor-dispatched. Keep the
            // initializer proof restricted to ordinary synchronous callables.
            if (fn == null || fn.trapped() || fn.actorKind() != Ast.ActorKind.NONE
                    || fn.async() || fn.suspends() || fn.generator()
                    || !fn.parameters().isEmpty() || !fn.genericParameters().isEmpty()) return false;
            body = fn.body();
            returnType = fn.returnType();
        } else if (call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr name) {
            Ast.ClassDecl owner = findClass(name.name());
            if (owner == null) return false;
            Ast.MethodDecl candidate = null;
            for (Ast.MethodDecl method : owner.methods()) {
                if (!method.isStatic() || !method.name().equals(member.member())
                        || !method.parameters().isEmpty()) continue;
                if (candidate != null) return false;
                candidate = method;
            }
            if (candidate == null || candidate.isAbstract()
                    || candidate.async() || candidate.suspends()
                    || !candidate.genericParameters().isEmpty()) return false;
            body = candidate.body();
            returnType = candidate.returnType();
        } else {
            return false;
        }
        if (returnType == null || returnType.isBorrow()
                || !returnType.arguments().isEmpty()
                || !(returnType.name().equals("int") || returnType.name().equals("bool")
                        || returnType.name().equals("float")
                        || returnType.name().equals("string")
                        || returnType.name().equals("String"))) return false;
        return body.size() == 1 && body.getFirst() instanceof Ast.ReturnStmt returned
                && constructorSafeScalarExpression(returned.value());
    }

    private boolean constructorSafeScalarExpression(Ast.Expr expr) {
        if (expr instanceof Ast.LiteralExpr) return true;
        if (expr instanceof Ast.UnaryExpr unary)
            return (unary.operator().equals("-") || unary.operator().equals("+"))
                    && constructorSafeScalarExpression(unary.operand());
        if (expr instanceof Ast.BinaryExpr binary)
            return (binary.operator().equals("+") || binary.operator().equals("-")
                        || binary.operator().equals("*"))
                    && constructorSafeScalarExpression(binary.left())
                    && constructorSafeScalarExpression(binary.right());
        return false;
    }

    private void checkClassBody(String module, Ast.ClassDecl klass) {
        Ast.ActorConcurrency concurrency = klass.actorConcurrency();
        if (klass.actorKind() == Ast.ActorKind.NONE
                && concurrency.transport() != Ast.ActorTransport.GENERIC) {
            throw new IllegalArgumentException("only actors may declare a transport concurrency policy");
        }
        // The VM currently holds one logical actor-state lease across await.
        // Do not admit a concurrency policy the ActorCell scheduler cannot
        // enforce merely because a configuration literal type-checks.
        if (concurrency.requestConcurrency() > 1 || concurrency.messageConcurrency() > 1) {
            throw new IllegalArgumentException(
                    "actor '" + klass.name() + "' requests concurrency ("
                            + concurrency.requestConcurrency() + ","
                            + concurrency.messageConcurrency() + ") but the current ActorRuntime "
                            + "enforces one logical receive at a time across await/rt cooperate; "
                            + "parallel handlers require a new per-request/per-message admission "
                            + "scheduler plus exclusive state loans. Refusing unsafe configuration.");
        }
        Set<String> classGenerics = uniqueGenerics(klass.genericParameters(), "class " + klass.name());
        Type self = nominalClassType(klass);

        Set<String> parentNames = new HashSet<>();
        for (Ast.TypeRef parent : klass.parents()) {
            if (!parentNames.add(parent.name())) throw new IllegalArgumentException("duplicate parent class '" + parent.name() + "' on " + klass.name());
            if (parent.name().equals("Object") || parent.name().equals("List")) {
                if (parent.inferArguments() || !parent.arguments().isEmpty()) {
                    throw new IllegalArgumentException("built-in inheritance marker '" + parent.name() + "' does not take type arguments");
                }
                resolveClassParent(parent, klass);
                if (klass.actorKind() != Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException("actor '" + klass.name()
                            + "' cannot extend built-in class '" + parent.name()
                            + "'; actor inheritance must preserve the actor isolation domain");
                }
                continue;
            }
            resolve(parent, classGenerics, self);
            Ast.ClassDecl resolvedParent = resolveClassParent(parent, klass);
            if (resolvedParent != null && klass.actorKind() != resolvedParent.actorKind()) {
                throw new IllegalArgumentException("actor isolation kind must be preserved across inheritance: "
                        + klass.name() + " is " + klass.actorKind() + " but parent "
                        + resolvedParent.name() + " is " + resolvedParent.actorKind());
            }
            if (resolvedParent != null
                    && klass.actorKind() != Ast.ActorKind.NONE
                    && !klass.actorConcurrency().equals(resolvedParent.actorConcurrency())) {
                throw new IllegalArgumentException("actor transport and concurrency policy must "
                        + "be preserved across inheritance: " + klass.name() + " has "
                        + klass.actorConcurrency() + " but parent "
                        + resolvedParent.name() + " has " + resolvedParent.actorConcurrency());
            }
            if (resolvedParent != null && resolvedParent.constructor() != null) {
                throw new IllegalArgumentException(
                        "class '" + klass.name() + "' cannot extend '" + resolvedParent.name()
                                + "' while the parent declares an explicit constructor; "
                                + "explicit super-constructor chaining is not implemented yet");
            }
        }

        validateFieldLayout(klass);

        Set<String> effectiveFieldNames = new LinkedHashSet<>();
        for (ResolvedField field : effectiveFieldTargets(
                klass, nominalClassType(klass), new LinkedHashSet<>())) {
            effectiveFieldNames.add(field.field().name());
        }
        Set<String> effectiveInstanceMethodNames = new LinkedHashSet<>();
        collectInstanceMethodNames(klass, effectiveInstanceMethodNames, new LinkedHashSet<>());
        for (String name : effectiveFieldNames) {
            if (effectiveInstanceMethodNames.contains(name)) {
                throw new IllegalArgumentException(
                        "class member '" + klass.name() + "." + name
                                + "' cannot be both a field and an instance method across inheritance; "
                                + "member-value and direct-call syntax must remain unambiguous");
            }
        }
        // Every effective (kind, name, arity) slot must have one statically
        // determined implementation. This is the vtable/static-table contract
        // shared by AOT and JIT. Multiple-inheritance collisions require the
        // child to declare an explicit local override/hide for that slot.
        effectiveCallableTargets(klass, new LinkedHashSet<>());

        // Declaration-site initialization may read constructor arguments in
        // the declaring class. Unlike constructor-body assignment, the
        // initialized final slot becomes immutable before any body executes.
        // This scope is local to klass; inherited initializers are validated
        // against their own declaring class and cannot capture child params.
        Ast.ConstructorDecl constructor = klass.constructor();
        Env fieldInitializerEnv = new Env(null);
        if (constructor != null) {
            // The field-initializer ownership checker does not yet prove
            // moves, borrows or alias lifetime across the constructor boundary.
            // Admit only independent scalar argument values here. In
            // particular, never expose the implicit `arguments` tuple:
            // it can contain an unchecked reference-valued constructor arg.
            for (Ast.Param param : constructor.parameters()) {
                Ast.TypeRef paramRef = param.type();
                String paramName = paramRef.name();
                boolean scalar = !paramRef.isBorrow()
                        && paramRef.arguments().isEmpty()
                        && !param.structural()
                        && (paramName.equals("int") || paramName.equals("bool")
                                || paramName.equals("float") || paramName.equals("string"));
                if (scalar) {
                    Type parameterType = resolveParam(param, classGenerics, self);
                    fieldInitializerEnv.define(
                            param.name(), parameterType,
                            param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
                }
            }
        }

        for (Ast.FieldDecl field : klass.fields()) {
            if (klass.actorKind() == Ast.ActorKind.NONE
                    && field.initializer() != null
                    && fieldInitializerMaySuspend(field.initializer())) {
                throw new IllegalArgumentException("class field initializer '"
                        + klass.name() + "." + field.name()
                        + "' cannot await, select, call, or suspend during object construction "
                        + "without a proven transitive effect bound");
            }
            if (klass.actorKind() != Ast.ActorKind.NONE && field.visibility() == Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException("actor state field '" + klass.name() + "." + field.name()
                        + "' cannot be public; expose state through mailbox-dispatched methods");
            }
            Type fieldType = classFieldType(klass, field);
            if (field.initializer() != null && field.type() != null) {
                Ast.ClassDecl previousClassOwner = currentClassOwner;
                Ast.ClassDecl previousLexicalClass = lexicalClassOrigin;
                currentClassOwner = klass;
                lexicalClassOrigin = klass;
                try {
                    Type actual = typeOf(field.initializer(), fieldInitializerEnv, classGenerics, self);
                    requireReadonlyCapabilityPreserved(
                            actual,
                            fieldType,
                            "field initializer " + klass.name() + "." + field.name());
                    requireMutableReferentCompatible(
                            actual,
                            field.mutableReferent(),
                            "field '" + klass.name() + "." + field.name() + "'");
                    requireAssignable(actual, fieldType, "field initializer " + klass.name() + "." + field.name());
                } finally {
                    currentClassOwner = previousClassOwner;
                    lexicalClassOrigin = previousLexicalClass;
                }
            }
        }

        if (constructor != null) {
            if (klass.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalArgumentException(
                        "actor '" + klass.name() + "' cannot declare a class constructor");
            }
            if (constructor.visibility() == Ast.Visibility.PUBLIC
                    && klass.visibility() != Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException(
                        "public constructor on private class '" + klass.name()
                                + "' is unreachable; make the module class public or keep the constructor private");
            }

            Env constructorEnv = new Env(null);
            constructorEnv.define("self", self, Ast.BindingKind.VAL);
            List<Type> constructorArgumentTypes = new ArrayList<>(constructor.parameters().size());
            for (Ast.Param param : constructor.parameters()) {
                Type parameterType = resolveParam(param, classGenerics, self);
                constructorArgumentTypes.add(parameterType);
                constructorEnv.define(
                        param.name(),
                        parameterType,
                        param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            defineImplicitArguments(constructorEnv, constructorArgumentTypes);

            Ast.ActorKind previousActorKind = currentActorKind;
            Ast.ClassDecl previousClassOwner = currentClassOwner;
            Ast.ClassDecl previousLexicalClass = lexicalClassOrigin;
            Ast.ClassDecl previousConstructorOwner = currentConstructorOwner;
            Set<String> previousFixedWrites = constructorFixedFieldWrites;
            currentActorKind = Ast.ActorKind.NONE;
            currentClassOwner = klass;
            currentConstructorOwner = klass;
            constructorFixedFieldWrites = new HashSet<>();
            lexicalClassOrigin = klass;
            try {
                checkBlock(constructor.body(), constructorEnv, classGenerics, Primitive.VOID, self);
            } finally {
                currentActorKind = previousActorKind;
                currentClassOwner = previousClassOwner;
                currentConstructorOwner = previousConstructorOwner;
                constructorFixedFieldWrites = previousFixedWrites;
                lexicalClassOrigin = previousLexicalClass;
            }
        }

        for (Ast.MethodDecl method : klass.methods()) {
            if (klass.actorKind() != Ast.ActorKind.NONE
                    && !method.isStatic()
                    && (method.name().equals("send")
                            || method.name().equals("end")
                            || method.name().equals("endWithCleanup"))) {
                throw new IllegalArgumentException(
                        "actor method name '" + method.name()
                                + "' is reserved by the non-overridable actor transport/lifecycle API");
            }

            boolean actorStartupHook = method.name().equals("on_start");
            boolean actorReceiveHook = klass.actorKind() != Ast.ActorKind.NONE
                    && method.name().equals("receive");
            if (actorStartupHook) {
                if (klass.actorKind() == Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException(
                            "reserved lifecycle hook 'on_start' may only be declared by an actor class");
                }
                if (method.isStatic()) {
                    throw new IllegalArgumentException(
                            "actor lifecycle hook '" + klass.name() + ".on_start' must be an instance method");
                }
                if (method.visibility() == Ast.Visibility.PUBLIC) {
                    throw new IllegalArgumentException(
                            "actor lifecycle hook '" + klass.name() + ".on_start' is actor-local and cannot be public");
                }
                if (method.isAbstract()) {
                    throw new IllegalArgumentException(
                            "actor lifecycle hook '" + klass.name() + ".on_start' must have an implementation");
                }
                if (method.async() || method.suspends()) {
                    throw new IllegalArgumentException(
                            "actor lifecycle hook '" + klass.name() + ".on_start' must be synchronous; "
                                    + "readiness settles only after startup completes on the actor execution domain");
                }
                if (!method.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException(
                            "actor lifecycle hook '" + klass.name() + ".on_start' cannot declare generic parameters");
                }
                if (!method.parameters().isEmpty()) {
                    throw new IllegalArgumentException(
                            "actor lifecycle hook '" + klass.name() + ".on_start' must take zero arguments");
                }
            }

            if (actorReceiveHook) {
                if (method.isStatic()) {
                    throw new IllegalArgumentException(
                            "actor mailbox handler '" + klass.name() + ".receive' must be an instance method");
                }
                if (method.visibility() == Ast.Visibility.PUBLIC) {
                    throw new IllegalArgumentException(
                            "actor mailbox handler '" + klass.name() + ".receive' is actor-local and cannot be public");
                }
                if (method.isAbstract()) {
                    throw new IllegalArgumentException(
                            "actor mailbox handler '" + klass.name() + ".receive' must have an implementation");
                }
                if (method.async()) {
                    throw new IllegalArgumentException(
                            "actor mailbox handler '" + klass.name() + ".receive' uses actor-turn suspension; "
                                    + "declare it synchronously and use explicit await/rt cooperate safepoints");
                }
                if (!method.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException(
                            "actor mailbox handler '" + klass.name() + ".receive' cannot declare generic parameters");
                }
                if (method.parameters().size() != 1) {
                    throw new IllegalArgumentException(
                            "actor mailbox handler '" + klass.name()
                                    + ".receive' must take exactly one ActorMail<T> parameter");
                }
            }

            if (method.name().endsWith("_blocking") && !method.suspends())
                throw new IllegalArgumentException(
                        "*_blocking method '" + method.name() + "' must declare suspends");
            if (method.async() && !method.isStatic()) {
                throw new IllegalArgumentException(
                        "async instance method '" + klass.name() + "." + method.name()
                                + "' requires moving/borrowing the receiver into the task; use an async static fnc for now");
            }
            if (method.async() && !method.genericParameters().isEmpty()) {
                throw new IllegalArgumentException(
                        "generic async static functions require an explicit task-safe/sendable generic bound");
            }
            Set<String> generics = new HashSet<>();
            if (!method.isStatic()) generics.addAll(classGenerics);
            for (String generic : method.genericParameters()) {
                if (classGenerics.contains(generic) || !generics.add(generic)) {
                    throw new IllegalArgumentException("duplicate/shadowed generic '" + generic + "' in " + klass.name() + "." + method.name());
                }
            }

            if (method.isStatic()) {
                for (Ast.Param param : method.parameters()) {
                    rejectStaticClassGenericReference(param.type(), classGenerics, klass, method);
                }
                rejectStaticClassGenericReference(method.returnType(), classGenerics, klass, method);
                for (Ast.Annotation annotation : method.annotations()) {
                    for (Ast.TypeRef argument : annotation.arguments()) {
                        rejectStaticClassGenericReference(argument, classGenerics, klass, method);
                    }
                }
                rejectStaticClassGenericReferences(method.body(), classGenerics, klass, method);
            }

            if (method.explicitReceiverType() != null) {
                Ast.TypeRef receiverRef = method.explicitReceiverType();
                Ast.TypeRef receiverTarget =
                        receiverRef.isBorrow() ? receiverRef.borrowedTarget() : receiverRef;
                boolean namesEnclosingClass =
                        receiverTarget.name().equals(klass.name())
                                || receiverTarget.name().equals(qualifiedClassName(klass))
                                || receiverTarget.name().equals("self");
                if (!namesEnclosingClass) {
                    Type receiver = resolve(receiverTarget, generics, self);
                    requireAssignable(
                            self,
                            receiver,
                            "explicit self receiver in "
                                    + klass.name() + "." + method.name());
                }
            }

            // The value binding and the type-level spelling `self` carry the
            // receiver-polymorphic type, not merely the enclosing nominal class.
            // This rejects `return new Foo()` because the runtime receiver may
            // be a subtype, while allowing values already typed as `self`.
            Type callableSelf = method.isStatic() ? null : new SelfType(self);
            Env env = new Env(null);
            if (!method.isStatic()) env.define("self", callableSelf, Ast.BindingKind.VAL);
            List<Type> methodArgumentTypes = new ArrayList<>(method.parameters().size());
            for (Ast.Param param : method.parameters()) {
                Type parameterType = resolveParam(param, generics, callableSelf);
                methodArgumentTypes.add(parameterType);
                if (param.structural() && klass.actorKind() != Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException(
                            "structural parameter '" + param.name() + "' cannot cross actor method mailbox boundaries; "
                                    + "actor APIs require owned/sendable nominal message values");
                }
                if (param.structural() && method.async()) {
                    throw new IllegalArgumentException(
                            "structural parameter '" + param.name() + "' cannot outlive a synchronous call across await/suspension; "
                                    + "pass an owned nominal value or copy the required fields");
                }
                if (method.async()) {
                    validateAsyncBoundaryType(
                            parameterType,
                            false,
                            "parameter '" + param.name() + "' of async static function '" + klass.name() + "." + method.name() + "'");
                }
                if (actorReceiveHook) {
                    if (!(parameterType instanceof Named mail)
                            || !mail.name().equals("ActorMail")
                            || mail.arguments().size() != 1) {
                        throw new IllegalArgumentException(
                                "actor mailbox handler '" + klass.name()
                                        + ".receive' parameter must be ActorMail<T>");
                    }
                    validateActorCallableBoundaryType(
                            mail.arguments().getFirst(),
                            klass.actorKind(),
                            false,
                            "mailbox payload of actor '" + klass.name() + ".receive'");
                }
                env.define(param.name(), parameterType, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            defineImplicitArguments(env, methodArgumentTypes);
            Type returns = resolve(method.returnType(), generics, callableSelf);
            if (actorStartupHook && returns != Primitive.VOID) {
                throw new IllegalArgumentException(
                        "actor lifecycle hook '" + klass.name() + ".on_start' must return void");
            }
            if (actorReceiveHook && returns != Primitive.VOID) {
                throw new IllegalArgumentException(
                        "actor mailbox handler '" + klass.name() + ".receive' must return void");
            }
            if (method.async()) {
                validateAsyncBoundaryType(
                        returns,
                        true,
                        "return type of async static function '" + klass.name() + "." + method.name() + "'");
            }
            Ast.ActorKind previousActorKind = currentActorKind;
            Ast.ClassDecl previousClassOwner = currentClassOwner;
            Ast.ClassDecl previousLexicalClass = lexicalClassOrigin;
            currentActorKind = method.isStatic() ? Ast.ActorKind.NONE : klass.actorKind();
            currentClassOwner = klass;
            lexicalClassOrigin = klass;
            boolean previousMaySuspend = currentMaySuspend;
            currentMaySuspend = method.async() || method.suspends();
            try {
                checkBlock(method.body(), env, generics, returns, callableSelf);
            } finally {
                currentActorKind = previousActorKind;
                currentClassOwner = previousClassOwner;
                lexicalClassOrigin = previousLexicalClass;
                currentMaySuspend = previousMaySuspend;
            }
            if (!method.isAbstract() && returns != Primitive.VOID && !definitelyReturns(method.body())) {
                String label = method.isStatic() ? "static function" : "method";
                throw new IllegalArgumentException("non-void " + label + " '" + module + "." + klass.name() + "." + method.name() + "' must explicitly return on every path");
            }
        }

        Set<String> implemented = new HashSet<>();
        for (Ast.TypeRef interfaceRef : klass.interfaces()) {
            if (!implemented.add(interfaceRef.name())) throw new IllegalArgumentException("duplicate implemented interface '" + interfaceRef.name() + "' on " + klass.name());
            if (interfaceRef.name().equals("StreamOperator")) {
                streamPortsForClass(klass, nominalClassType(klass));
                continue;
            }
            Ast.InterfaceDecl iface = findInterface(interfaceRef.name());
            if (iface == null) throw new IllegalArgumentException("unknown interface '" + interfaceRef.name() + "' implemented by " + klass.name());
            if (iface.moduleContract()) throw new IllegalArgumentException("module contract '" + interfaceRef.name() + "' cannot be implemented by class '" + klass.name() + "'");
            Type resolvedInterface = resolve(interfaceRef, classGenerics, self);
            if (!(resolvedInterface instanceof Named interfaceType)) throw new IllegalArgumentException("implemented interface must resolve to a named type");
            Record expectedTemplate = interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());
            Record expected = (Record) substituteGenerics(expectedTemplate,
                    genericBindings(iface.genericParameters(), interfaceType.arguments(), "interface " + iface.name()));
            Record actual = publicClassShape(klass, new LinkedHashSet<>());
            if (!assignable(actual, expected)) {
                throw new IllegalArgumentException("class '" + klass.name() + "' does not implement interface '" + interfaceRef.name() + "': expected " + expected + " but got " + actual);
            }
        }
    }

    /**
     * Ordinary modules/classes retain normal same-source-unit visibility.
     * Actor classes are the hard executable dependency boundary: actor code may
     * use only actor-owned declarations, explicit cross-unit imports and
     * compiler/runtime intrinsics admitted by policy.
     */
    private boolean insideActorLexicalBoundary() {
        return lexicalClassOrigin != null
                && lexicalClassOrigin.actorKind() != Ast.ActorKind.NONE;
    }

    private String lexicalContainerLabel() {
        if (lexicalClassOrigin == null) return "class";
        return lexicalClassOrigin.actorKind() == Ast.ActorKind.NONE ? "class" : "actor";
    }

    private void rejectImplicitClassCallable(String name) {
        if (!insideActorLexicalBoundary()) return;
        throw new IllegalArgumentException(
                "actor '" + lexicalClassOrigin.name()
                        + "' cannot implicitly access file/module callable '" + name
                        + "'; move the dependency to another source unit and import it explicitly");
    }

    private void rejectUnimportedModuleNamespace(String name) {
        if (!insideActorLexicalBoundary()) return;
        throw new IllegalArgumentException(
                "actor '" + lexicalClassOrigin.name()
                        + "' cannot implicitly access module '" + name
                        + "'; same-file placement does not grant actor dependency authority");
    }

    private void rejectUnimportedClassNamespace(Ast.ClassDecl target) {
        if (!insideActorLexicalBoundary() || lexicalClassOrigin == target) return;
        throw new IllegalArgumentException(
                "actor '" + lexicalClassOrigin.name()
                        + "' cannot implicitly access sibling "
                        + (target.actorKind() == Ast.ActorKind.NONE ? "class '" : "actor '")
                        + target.name()
                        + "'; move the dependency to another source unit and import it explicitly");
    }

    private void rejectUnimportedTypeDependency(String name) {
        if (!insideActorLexicalBoundary()) return;

        Ast.ClassDecl localClass = findClass(name);
        if (localClass != null) {
            if (localClass == lexicalClassOrigin || isExplicitActorParent(localClass)) return;
            throw new IllegalArgumentException(
                    "actor '" + lexicalClassOrigin.name()
                            + "' cannot use same-file type '" + name
                            + "'; move the type to another source unit and import it explicitly");
        }

        Ast.InterfaceDecl localInterface = findInterface(name);
        if (localInterface != null) {
            throw new IllegalArgumentException(
                    "actor '" + lexicalClassOrigin.name()
                            + "' cannot use same-file "
                            + (localInterface.moduleContract() ? "module contract '" : "interface '")
                            + name + "'; move the type to another source unit and import it explicitly");
        }

        Ast.TypeAliasDecl localAlias = findTypeAlias(name);
        if (localAlias != null) {
            throw new IllegalArgumentException(
                    "actor '" + lexicalClassOrigin.name()
                            + "' cannot use same-file type alias '" + name
                            + "'; move the type to another source unit and import it explicitly");
        }
    }

    /**
     * An extends clause is a declaration-time dependency edge, not an ambient
     * lexical lookup. Permit the directly named parent so actor inheritance can
     * be typechecked, then let the actor-kind/inheritance rules below reject
     * unsafe cross-domain inheritance.
     */
    private boolean isExplicitActorParent(Ast.ClassDecl candidate) {
        if (!insideActorLexicalBoundary() || candidate == null) return false;
        for (Ast.TypeRef parent : lexicalClassOrigin.parents()) {
            if (parent.name().equals(candidate.name())
                    || parent.name().equals(qualifiedClassName(candidate))) {
                return true;
            }
        }
        return false;
    }

    private Env moduleBindingEnv(String moduleName) {
        Env env = new Env(null);
        Ast.ModuleDecl module = modules.get(moduleName);
        if (module == null) return env;
        for (Ast.Decl declaration : module.declarations()) {
            if (!(declaration instanceof Ast.FieldDecl field) || field.initializer() == null) continue;
            Type type = field.type() == null
                    ? typeOf(field.initializer(), env, Set.of(), null)
                    : resolve(field.type(), Set.of(), null);
            env.define(field.name(), type, field.bindingKind());
        }
        return env;
    }

    private void checkModuleBinding(Ast.FieldDecl field) {
        if (field.initializer() == null) throw new IllegalArgumentException("module binding '" + field.name() + "' requires an initializer");
        Type actual = typeOf(field.initializer(), new Env(null), Set.of(), null);
        requireMutableReferentCompatible(
                actual,
                field.mutableReferent(),
                "module binding '" + field.name() + "'");
        if (field.type() != null) {
            Type expected = resolve(field.type(), Set.of(), null);
            requireReadonlyCapabilityPreserved(
                    actual,
                    expected,
                    "initializer for module binding " + field.name());
            requireAssignable(actual, expected, "initializer for " + field.name());
        }
    }

    private void checkBlock(List<Ast.Stmt> body, Env parent, Set<String> generics, Type expectedReturn, Type self) {
        Env env = new Env(parent);
        Env previousTypeScope = currentTypeScope;
        currentTypeScope = env;
        try {
            // Reserve names before checking any initializer, so a use before the
            // declaration cannot silently resolve as an unrelated nominal type.
            for (Ast.Stmt stmt : body) {
                if (stmt instanceof Ast.BindingStmt binding && binding.inferredTypeName() != null) {
                    inferredTypeNames.add(binding.inferredTypeName());
                    env.reserveInferredType(binding.inferredTypeName());
                }
            }
            // Short-form aliases are lexical declarations, so reserve them
            // in statement order. Pre-reserving every later short alias would
            // let a future declaration interfere with an earlier nested block
            // and invert lexical scope. Explicit infer<T> declarations above
            // remain pre-reserved because their name is stated explicitly and
            // must reject forward use before initialization.
            for (Ast.Stmt stmt : body) {
                if (stmt instanceof Ast.BindingStmt binding
                        && isShortNamedAggregateInference(binding, env, generics)) {
                    String name = binding.declaredType().name();
                    shortInferredTypeNames.put(binding, name);
                    inferredTypeNames.add(name);
                    env.reserveInferredType(name);
                }
                checkStatement(stmt, env, generics, expectedReturn, self);
            }
        } finally {
            currentTypeScope = previousTypeScope;
        }
    }

    /**
     * A short name introduces an alias only when the name is truly absent.
     * Existing aliases, class/interface names, generics, builtins, and an
     * explicitly reserved forward alias use the ordinary declared-type path.
     * Limiting the implicit form to uppercase-starting names prevents a typo
     * in lowercase builtins from silently manufacturing a type.
     */
    private boolean isShortNamedAggregateInference(
            Ast.BindingStmt binding, Env env, Set<String> generics) {
        if (binding.inferredTypeName() != null || binding.kind() != Ast.BindingKind.CONST
                || binding.mutableReferent()
                || !(binding.initializer() instanceof Ast.ObjectExpr object && object.inferredAggregate())) {
            return false;
        }
        Ast.TypeRef ref = binding.declaredType();
        if (ref == null || ref.name() == null || ref.name().isEmpty()
                || !Character.isUpperCase(ref.name().codePointAt(0))
                || ref.name().contains(".") || ref.isRecordType()
                || ref.inferArguments() || !ref.arguments().isEmpty()) {
            return false;
        }
        String name = ref.name();
        if (generics.contains(name) || GENERIC_DEFAULT_BUILTINS.contains(name)
                || Set.of("Object", "Any", "Unknown", "Error", "Symbol",
                        "Ref", "WeakRef", "Map", "OrderedMap", "DynamicStruct").contains(name)
                || findClass(name) != null || findInterface(name) != null
                || findTypeAlias(name) != null || modules.containsKey(name)
                || importedNames.contains(name)
                || env.hasReservedInferredType(name) || env.lookupInferredType(name) != null
                || env.hasSeenInferredType(name)
                || hiddenLinkedAliasNames.contains(name)) {
            return false;
        }
        // Do not turn a missing import into an inferred alias.
        rejectUnimportedTypeDependency(name);
        return true;
    }

    private void checkLoopBlock(List<Ast.Stmt> body, Env parent, Set<String> generics, Type expectedReturn, Type self) {
        loopDepth++;
        try {
            checkBlock(body, parent, generics, expectedReturn, self);
        } finally {
            loopDepth--;
        }
    }

    private void checkCallableBlock(List<Ast.Stmt> body, Env parent, Set<String> generics, Type expectedReturn, Type self) {
        checkIsolatedArmBlock(body, parent, generics, expectedReturn, self, false);
    }

    private void checkIsolatedArmBlock(List<Ast.Stmt> body, Env parent, Set<String> generics,
                                       Type expectedReturn, Type self, boolean discardReturn) {
        int previousLoopDepth = loopDepth;
        boolean previousGenerator = currentGenerator;
        boolean previousAsync = currentAsyncCallable;
        boolean previousDiscard = inDiscardingSelectArm;
        loopDepth = 0;
        currentGenerator = false;
        currentAsyncCallable = false;
        inDiscardingSelectArm = discardReturn;
        try {
            checkBlock(body, parent, generics, expectedReturn, self);
        } finally {
            loopDepth = previousLoopDepth;
            currentGenerator = previousGenerator;
            currentAsyncCallable = previousAsync;
            inDiscardingSelectArm = previousDiscard;
        }
    }

    private void checkStatement(Ast.Stmt stmt, Env env, Set<String> generics, Type expectedReturn, Type self) {
        // Parse into a dedicated lexical node, but do not authorize execution
        // until RequestHandle provenance, scoped-loan fencing, suspension,
        // cancellation and unwind behavior have all been implemented. This
        // prevents the evaluator's generic statement fallback from silently
        // treating a borrow as a no-op or a normal heap-backed local.
        if (stmt instanceof Ast.UsingBorrowStmt loan) {
            ScopedRequestBorrowPreflight.verify(loan);
            throw new IllegalArgumentException(
                    "using request.borrow() as view requires verified transport-loan "
                            + "lifetime checking and evaluator lowering; feature is not enabled");
        }
        if (stmt instanceof Ast.BindingStmt binding) {
            String inferredName = binding.inferredTypeName() != null
                    ? binding.inferredTypeName() : shortInferredTypeNames.get(binding);
            boolean namedInference = inferredName != null;
            Type declaredAhead = namedInference || binding.declaredType() == null
                    ? null : resolve(binding.declaredType(), generics, self);
            if (binding.declaredType() != null && binding.initializer() instanceof Ast.ListExpr listLiteral) {
                validateSequenceLiteralAgainstDeclared(binding.declaredType(), listLiteral, env, generics, self);
            }
            boolean recursiveLambda = binding.initializer() instanceof Ast.LambdaExpr;
            if (recursiveLambda && declaredAhead instanceof Function) {
                env.define(binding.name(), declaredAhead, binding.kind());
            }
            if (binding.initializer() instanceof Ast.LambdaExpr lambda && declaredAhead instanceof Function expectedFunction) {
                validateLambdaAgainstExpected(lambda, expectedFunction, env, generics, self);
            }

            Ast.ObjectExpr explicitInitializer =
                    binding.initializer() instanceof Ast.ObjectExpr object && object.explicitStruct()
                            ? object
                            : null;
            Set<String> missingExplicitFields = explicitInitializer == null
                    ? Set.of()
                    : explicitStructMissingFields(explicitInitializer);
            if (!missingExplicitFields.isEmpty() && !binding.mutableReferent()) {
                throw new IllegalArgumentException(
                        "explicit struct bound read-only must initialize every declared field; missing "
                                + missingExplicitFields);
            }

            Ast.ObjectExpr previousPartial = permittedPartialStructInitializer;
            if (explicitInitializer != null && binding.mutableReferent()) {
                permittedPartialStructInitializer = explicitInitializer;
            }
            Type actual;
            try {
                actual = typeOfAgainstExpected(binding.initializer(), declaredAhead, env, generics, self);
            } finally {
                permittedPartialStructInitializer = previousPartial;
            }
            if (actual instanceof Record record && record.readOnly() && binding.mutableReferent()) {
                throw new IllegalArgumentException(
                        "readonly record values cannot be bound with mutable referent access");
            }

            if (namedInference) {
                String name = inferredName;
                if (generics.contains(name)
                        || Set.of(
                                "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64",
                                "int", "uint", "bigint", "f32", "f64", "float", "decimal",
                                "complex64", "complex128", "complex", "bool", "Bool",
                                "boolean", "string", "String", "void", "null", "self",
                                "Tuple", "Array", "List", "Vector", "Slice",
                                "FixedArray", "FixedList", "DynamicStruct", "Option",
                                "Result", "Future", "ActorId", "ActorKind",
                                "ActorMail", "ActorOutput", "ActorOutputStream",
                                "MutexGuard", "Mutex", "SharedMutex", "Channel",
                                "Generator", "AsyncGenerator", "Iterator", "AsyncIterator",
                                "IteratorResult", "Fnc", "Function").contains(name)
                        || findClass(name) != null || findInterface(name) != null
                        || findTypeAlias(name) != null || modules.containsKey(name)
                        || importedNames.contains(name)) {
                    throw new IllegalArgumentException(
                            "inferred type name '" + name + "' conflicts with an existing type or namespace");
                }
                if (!(actual instanceof Record || actual instanceof Tuple)) {
                    throw new IllegalArgumentException(
                            "infer<" + name + "> requires an inferred struct, inferred record, or finite tuple");
                }
                // String literals infer their general field/slot type, not a
                // value-dependent singleton. Preserve all nested ownership/readonly
                // qualifiers; only widen the top-level string literal slots.
                if (actual instanceof Tuple tuple) {
                    actual = new Tuple(
                            tuple.elements().stream().map(this::widenCollectionElement).toList(),
                            tuple.kind());
                }
                env.defineInferredType(name, actual);
            }
            Type declared = declaredAhead == null
                    ? actual
                    : overlayReadonlyCapabilities(actual, declaredAhead);
            requireAssignable(actual, declared, "initializer for " + binding.name());

            // Resolve CONST selector metadata before defining the new binding so
            // an initializer alias refers to the lexical source, not to the just-
            // declared name in a shadowing scope.
            Long constantInteger = null;
            String constantString = null;
            if (binding.kind() == Ast.BindingKind.CONST) {
                constantInteger = constantTupleIndex(binding.initializer(), env);
                constantString = constantStringKey(binding.initializer(), env);
            }

            if (recursiveLambda && declaredAhead instanceof Function) env.replace(binding.name(), declared, binding.kind());
            else env.define(binding.name(), declared, binding.kind(), missingExplicitFields);
            if (binding.kind() == Ast.BindingKind.CONST) {
                if (constantInteger != null) {
                    env.defineConstantInteger(binding.name(), constantInteger);
                }
                if (constantString != null) {
                    env.defineConstantString(binding.name(), constantString);
                }
            }
            return;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            Type source = deref(typeOf(destructure.initializer(), env, generics, self));
            if (destructure.kind() == Ast.DestructureKind.SEQUENCE) {
                List<Type> elementTypes = sequenceDestructureTypes(source, destructure.bindings());
                for (int i = 0; i < destructure.bindings().size(); i++) {
                    defineDestructureBinding(env, destructure.bindings().get(i), elementTypes.get(i));
                }
            } else {
                Set<String> selectedMembers = new LinkedHashSet<>();
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (!binding.isDiscard() && !binding.rest()) selectedMembers.add(binding.name());
                }
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (binding.isDiscard()) continue;
                    Type bindingType = binding.rest()
                            ? objectDestructureRestType(source, selectedMembers)
                            : objectDestructureMemberType(source, binding.name());
                    requireMutableReferentCompatible(
                            bindingType, binding.mutableReferent(),
                            "destructure binding '" + binding.name() + "'");
                    env.define(binding.name(), bindingType, binding.kind());
                }
            }
            return;
        }
        if (stmt instanceof Ast.BlockStmt block) {
            checkBlock(block.body(), env, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.TaskScopeStmt taskScope) {
            if (currentActorKind != Ast.ActorKind.NONE) {
                throw new IllegalArgumentException(
                        "task scope is not enabled inside actor execution until "
                                + "actor quota/hot-reload structured lifetime authority is proven");
            }
            if (!currentMaySuspend) {
                throw new IllegalArgumentException(
                        "task scope exit is a physical-quiescence suspension point; "
                                + "declare the enclosing callable async or suspends");
            }
            checkBlock(taskScope.body(), env, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.BreakStmt) {
            if (loopDepth == 0) throw new IllegalArgumentException("'break' may only appear inside loop or for");
            return;
        }
        if (stmt instanceof Ast.ContinueStmt) {
            if (loopDepth == 0) throw new IllegalArgumentException("'continue' may only appear inside loop or for");
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            if (inDiscardingSelectArm) {
                warnings.add(ret.value() == null
                        ? "W-SELECT-RETURN: legacy do nb select return; exits only the detached arm"
                        : "W-SELECT-RETURN: legacy do nb select return value is arm-local and discarded");
            }
            if (currentGenerator) {
                if (ret.value() != null) {
                    throw new IllegalArgumentException(
                            "generator return cannot carry a value; use 'yield value' and bare 'return;' to finish");
                }
                return;
            }
            if (ret.value() instanceof Ast.LambdaExpr lambda && expectedReturn instanceof Function expectedFunction) {
                validateLambdaAgainstExpected(lambda, expectedFunction, env, generics, self);
            }
            Type actual = ret.value() == null
                    ? Primitive.VOID
                    : typeOfAgainstExpected(ret.value(), expectedReturn, env, generics, self);
            requireReadonlyCapabilityPreserved(actual, expectedReturn, "return value");
            requireAssignable(actual, expectedReturn, "return value");
            return;
        }
        if (stmt instanceof Ast.YieldStmt yielded) {
            if (!currentGenerator) {
                throw new IllegalArgumentException("'yield' may only appear inside a generator fnc or routine");
            }
            if (yielded.delegated()) {
                Type iterable = typeOf(yielded.value(), env, generics, self);
                Type element = currentAsyncCallable
                        ? asyncIterableElementType(iterable)
                        : iterableElementType(iterable);
                requireAssignable(element, expectedReturn, "yield* element");
            } else {
                Type actual = typeOfAgainstExpected(yielded.value(), expectedReturn, env, generics, self);
                requireAssignable(actual, expectedReturn, "yield value");
            }
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) { typeOf(expression.expression(), env, generics, self); return; }
        if (stmt instanceof Ast.DeferStmt defer) {
            if (defer.expression() instanceof Ast.LambdaExpr lambda && !lambda.parameters().isEmpty()) {
                throw new IllegalArgumentException(
                        "defer cleanup lambda must accept zero parameters");
            }
            Map<Env.Binding, Set<String>> beforeDefer = env.initializationSnapshot();
            try {
                typeOf(defer.expression(), env, generics, self);
            } finally {
                // Defer executes later; checking its expression now must not
                // establish definite-initialization facts for following code.
                env.restoreInitialization(beforeDefer);
            }
            return;
        }
        if (stmt instanceof Ast.IfStmt conditional) {
            Map<Env.Binding, Set<String>> before = env.initializationSnapshot();
            List<Map<Env.Binding, Set<String>>> exits = new ArrayList<>();
            for (Ast.IfBranch branch : conditional.branches()) {
                env.restoreInitialization(before);
                requireAssignable(typeOfCondition(branch.condition(), env, generics, self), Primitive.BOOL, "if condition");
                Env branchEnv = new Env(env);
                defineConditionBindings(branch.condition(), branchEnv, env, generics, self);
                checkBlock(branch.body(), branchEnv, generics, expectedReturn, self);
                exits.add(env.initializationSnapshot());
            }
            env.restoreInitialization(before);
            checkBlock(conditional.elseBody(), env, generics, expectedReturn, self);
            exits.add(env.initializationSnapshot());
            env.mergeInitialization(exits);
            return;
        }
        if (stmt instanceof Ast.MatchStmt matched) {
            Type subject = deref(typeOf(matched.subject(), env, generics, self));
            Map<Env.Binding, Set<String>> afterSubject = env.initializationSnapshot();
            checkMatch(matched, subject, env, generics, expectedReturn, self);
            // Match-arm flow is checked independently by checkMatch. Until it
            // returns per-arm state, do not let one arm establish definite
            // initialization for code after the match.
            env.restoreInitialization(afterSubject);
            return;
        }
        if (stmt instanceof Ast.SwitchStmt switched) {
            Type subject = deref(typeOf(switched.subject(), env, generics, self));
            Map<Env.Binding, Set<String>> beforeCases = env.initializationSnapshot();
            List<Map<Env.Binding, Set<String>>> exits = new ArrayList<>();
            Set<Object> seen = new HashSet<>();
            for (Ast.SwitchCase arm : switched.cases()) {
                env.restoreInitialization(beforeCases);
                if (arm.constants().isEmpty()) throw new IllegalArgumentException("switch case requires at least one constant");
                for (Ast.Expr constant : arm.constants()) {
                    if (!constant(constant)) throw new IllegalArgumentException("switch case must be a compile-time constant");
                    Type actual = typeOf(constant, env, generics, self);
                    if (!assignable(actual, subject) && !assignable(subject, actual)) {
                        throw new IllegalArgumentException("switch case type " + actual + " is incompatible with " + subject);
                    }
                    // Display text cannot be used for identity: String "#x" and
                    // Symbol #x can render alike, while #x and #"x" are equal.
                    Object key = constant instanceof Ast.LiteralExpr literal
                            ? new SwitchLiteralCaseKey(literal.value().getClass(), literal.value())
                            : constant.toString();
                    if (!seen.add(key)) throw new IllegalArgumentException("duplicate switch case: " + constant);
                }
                checkBlock(arm.body(), env, generics, expectedReturn, self);
                exits.add(env.initializationSnapshot());
            }
            env.restoreInitialization(beforeCases);
            checkBlock(switched.defaultBody(), env, generics, expectedReturn, self);
            exits.add(env.initializationSnapshot());
            env.mergeInitialization(exits);
            return;
        }
        if (stmt instanceof Ast.TryStmt attempted) {
            Map<Env.Binding, Set<String>> before = env.initializationSnapshot();
            env.restoreInitialization(before);
            checkBlock(attempted.body(), env, generics, expectedReturn, self);
            Map<Env.Binding, Set<String>> normalExit = env.initializationSnapshot();

            env.restoreInitialization(before);
            Env caught = new Env(env);
            caught.define(attempted.errorName(), Unknown.INSTANCE, Ast.BindingKind.VAL);
            checkBlock(attempted.catchBody(), caught, generics, expectedReturn, self);
            Map<Env.Binding, Set<String>> caughtExit = env.initializationSnapshot();

            env.mergeInitialization(List.of(normalExit, caughtExit));
            checkBlock(attempted.finallyBody(), env, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.SelectStmt selected) {
            if (selected.mode() == Ast.WaitMode.NONBLOCKING
                    && currentActorKind == Ast.ActorKind.NONE) {
                throw new IllegalArgumentException(
                        "static 'nb select { ... }' requires an actor execution domain "
                                + "because its selected branch executes later; "
                                + "use 'nb select from cases' when only a Future<SelectResult> is needed");
            }
            Map<Env.Binding, Set<String>> before = env.initializationSnapshot();
            List<Map<Env.Binding, Set<String>>> exits = new ArrayList<>();
            for (Ast.SelectArm arm : selected.arms()) {
                env.restoreInitialization(before);
                Env armEnv = selected.mode() == Ast.WaitMode.NONBLOCKING
                        ? new Env(env, env.descendantsNonLexical(), true)
                        : new Env(env);
                if (arm.operation() != Ast.ChannelOperation.DEFAULT) {
                    Type source = typeOf(arm.channel(), env, generics, self);
                    Type element;
                    if (arm.operation() == Ast.ChannelOperation.AWAIT) {
                        if (!(source instanceof Named future)
                                || !future.name().equals("Future")
                                || future.arguments().size() != 1) {
                            throw new IllegalArgumentException(
                                    "when await requires Future<T>, got " + source);
                        }
                        element = future.arguments().getFirst();
                    } else {
                        element = channelElementType(
                                source, "select " + arm.operation().name().toLowerCase());
                    }
                    if (arm.operation() == Ast.ChannelOperation.WRITE) {
                        requireAssignable(
                                typeOf(arm.value(), env, generics, self),
                                element, "writech select value");
                    } else if (arm.bindingName() != null) {
                        Type bound = element;
                        if (arm.bindingType() != null) {
                            bound = resolve(arm.bindingType(), generics, self);
                            // A type annotation cannot strip the readonly
                            // capability of a selected infer-struct event.
                            // Apply the same capability check used for
                            // assignments, parameters and return values.
                            requireReadonlyCapabilityPreserved(
                                    element, bound,
                                    "typed select binding '" + arm.bindingName() + "'");
                            requireAssignable(element, bound,
                                    "typed select binding '" + arm.bindingName() + "'");
                        }
                        requireMutableReferentCompatible(
                                bound, arm.mutableReferent(),
                                "select binding '" + arm.bindingName() + "'");
                        armEnv.define(arm.bindingName(), bound, arm.bindingKind());
                    }
                }
                if (selected.explicitDo() && selected.mode() == Ast.WaitMode.NONBLOCKING) {
                    // Legacy detached do-nb-select arms cannot return from an
                    // already-continued enclosing callable.
                    checkIsolatedArmBlock(arm.body(), armEnv, generics,
                            Unknown.INSTANCE, self, true);
                } else if (selected.mode() == Ast.WaitMode.NONBLOCKING) {
                    // Compatibility: detached legacy nb select arms cannot return a value.
                    checkCallableBlock(arm.body(), armEnv, generics, Primitive.VOID, self);
                } else {
                    checkBlock(arm.body(), armEnv, generics, expectedReturn, self);
                    exits.add(env.initializationSnapshot());
                }
            }
            if (selected.mode() == Ast.WaitMode.NONBLOCKING) {
                env.restoreInitialization(before);
            } else {
                env.mergeInitialization(exits);
            }
            return;
        }
        if (stmt instanceof Ast.ForOfDestructureStmt loop) {
            Type iterable = typeOf(loop.iterable(), env, generics, self);
            Map<Env.Binding, Set<String>> afterIterable = env.initializationSnapshot();
            Type element;
            if (loop.asyncIteration()) {
                if (!currentAsyncCallable) {
                    throw new IllegalArgumentException("'for await' is only valid inside an async fnc/routine or async generator");
                }
                element = asyncIterableElementType(iterable);
            } else {
                element = iterableElementType(iterable);
            }
            List<Type> elementTypes = sequenceDestructureTypes(element, loop.bindings());
            Env loopEnv = new Env(env);
            for (int i = 0; i < loop.bindings().size(); i++) {
                defineDestructureBinding(loopEnv, loop.bindings().get(i), elementTypes.get(i));
            }
            checkLoopBlock(loop.body(), loopEnv, generics, expectedReturn, self);
            env.restoreInitialization(afterIterable);
            return;
        }
        if (stmt instanceof Ast.ForOfStmt loop) {
            Type iterable = typeOf(loop.iterable(), env, generics, self);
            Map<Env.Binding, Set<String>> afterIterable = env.initializationSnapshot();
            Type element;
            if (loop.asyncIteration()) {
                if (!currentAsyncCallable) {
                    throw new IllegalArgumentException("'for await' is only valid inside an async fnc/routine or async generator");
                }
                element = asyncIterableElementType(iterable);
            } else {
                element = iterableElementType(iterable);
            }
            Env loopEnv = new Env(env);
            requireMutableReferentCompatible(
                    element, loop.mutableReferent(),
                    "for-of binding '" + loop.bindingName() + "'");
            loopEnv.define(loop.bindingName(), element, loop.bindingKind());
            checkLoopBlock(loop.body(), loopEnv, generics, expectedReturn, self);
            env.restoreInitialization(afterIterable);
            return;
        }
        if (stmt instanceof Ast.ForStmt loop) {
            Env loopEnv = new Env(env);
            if (loop.initializer() != null) checkStatement(loop.initializer(), loopEnv, generics, expectedReturn, self);
            if (loop.condition() != null) requireAssignable(typeOf(loop.condition(), loopEnv, generics, self), Primitive.BOOL, "for condition");
            Map<Env.Binding, Set<String>> afterEntryCheck = env.initializationSnapshot();
            if (loop.update() != null) typeOf(loop.update(), loopEnv, generics, self);
            checkLoopBlock(loop.body(), loopEnv, generics, expectedReturn, self);
            env.restoreInitialization(afterEntryCheck);
            return;
        }
        if (stmt instanceof Ast.DoWhileStmt loop) {
            Map<Env.Binding, Set<String>> before = env.initializationSnapshot();
            checkLoopBlock(loop.body(), env, generics, expectedReturn, self);
            requireAssignable(typeOf(loop.condition(), env, generics, self), Primitive.BOOL, "do-while condition");
            env.restoreInitialization(before);
            return;
        }
        if (stmt instanceof Ast.LoopStmt loop) {
            Map<Env.Binding, Set<String>> before = env.initializationSnapshot();
            checkLoopBlock(loop.body(), env, generics, expectedReturn, self);
            env.restoreInitialization(before);
        }
    }

    private Set<String> explicitStructMissingFields(Ast.ObjectExpr object) {
        if (!object.explicitStruct()) return Set.of();
        Set<String> declared = new LinkedHashSet<>();
        for (Ast.StructFieldSpec field : object.declaredFields()) declared.add(field.name());
        for (Ast.ObjectField field : object.fields()) {
            declared.remove(field.name());
        }
        return java.util.Collections.unmodifiableSet(declared);
    }

    private Type typeOf(Ast.Expr expr, Env env, Set<String> generics, Type self) {
        if (expr instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value == null) throw new IllegalArgumentException("standalone null values are forbidden; use Option<T>");
            if (value instanceof Long) return Primitive.INT;
            if (value instanceof Double) return Primitive.FLOAT;
            if (value instanceof Boolean) return Primitive.BOOL;
            if (value instanceof String s) return new StringLiteral(s);
            if (value instanceof dev.oreslang.runtime.OresSymbol symbol) {
                // Userland identities need tenant-scoped exports before an untrusted
                // actor may compile a reference to them. Never silently intern them.
                if (currentActorKind == Ast.ActorKind.UNTRUSTED
                        && !symbol.publicToUntrustedCoreV1()) {
                    throw new SecurityException("Symbol is not exported to untrusted actor");
                }
                return new Named("$builtin$Symbol", List.of());
            }
            if (value instanceof Ast.Imaginary) return Primitive.COMPLEX;
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.NameExpr name) {
            Env.Binding local = env.lookup(name.name());
            if (local != null) {
                if (local.hasUninitializedFields()) {
                    throw new IllegalArgumentException(
                            "explicit struct binding '" + name.name()
                                    + "' cannot be used before declared fields are initialized: "
                                    + local.uninitializedFields());
                }
                return local.type();
            }
            if (name.name().equals("stdio")
                    || name.name().equals("std")
                    || name.name().equals("process")
                    || name.name().equals("actor")
                    || name.name().equals("fs")
                    || name.name().equals("File")
                    || name.name().equals("network")
                    || name.name().equals("net")
                    || name.name().equals("http")
                    || name.name().equals("env")) {
                return new Named(name.name(), List.of());
            }
            if (name.name().equals("Mutex") || name.name().equals("SharedMutex")
                    || name.name().equals("Channel") || name.name().equals("SelectCase") || name.name().equals("SelectSet")) return new Named("$" + name.name() + "Factory", List.of());
            if (name.name().equals("Future")) return new Named("$FutureFactory", List.of());
            if (name.name().equals("BooleanOps") && !modules.containsKey("BooleanOps")) {
                return new Named("$BooleanOps", List.of());
            }
            if (name.name().equals("print")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            if (name.name().equals("None")) return new Named("Option", List.of(Unknown.INSTANCE));
            Ast.ModuleDecl moduleNamespace = modules.get(name.name());
            if (moduleNamespace != null) {
                rejectUnimportedModuleNamespace(name.name());
                return moduleShape(moduleNamespace);
            }
            Ast.ClassDecl classNamespace = findClass(name.name());
            if (classNamespace != null) {
                rejectUnimportedClassNamespace(classNamespace);
                return new ClassNamespace(qualifiedClassName(classNamespace));
            }
            if (importedValues.contains(name.name())) return Unknown.INSTANCE;
            Ast.FunctionDecl fn = findSingleFunction(name.name());
            if (fn != null) {
                rejectImplicitClassCallable(name.name());
                if (fn.actorKind() != Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException("actor callable '" + fn.name()
                            + "' is an actor entry point, not an ordinary function value; spawn it through the actor runtime");
                }
                if (fn.kind() == Ast.CallableKind.ROUTINE) {
                    throw new IllegalArgumentException("routine '" + fn.name()
                            + "' is direct-call-only and cannot be used as a first-class callable value; "
                            + "wrap the call in an explicit lambda when a callback is required");
                }
                // A Function value records async but not suspends. Reifying
                // this declaration would silently erase the suspension effect
                // and make a synchronous Option/Result callback unsafe.
                if (fn.suspends()) {
                    throw new IllegalArgumentException(
                            "suspends function '" + fn.name()
                            + "' cannot be used as a first-class callable until Fnc types encode suspension; "
                            + "invoke it directly from an async or suspends callable");
                }
                if (!fn.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException(
                            "generic callable '" + fn.name()
                                    + "' must be specialized by a direct call; polymorphic function values are not supported yet");
                }
                return trapFunctionType(fn, Set.of(), null);
            }
            throw new IllegalArgumentException("unknown name '" + name.name() + "'");
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            Type targetType;
            List<Type> dynamicFixedSlots = List.of();
            String initializedIndexedField = null;
            String where;
            if (assignment.target() instanceof Ast.NameExpr name) {
                Env.Binding binding = env.lookup(name.name());
                if (binding == null) throw new IllegalArgumentException("cannot assign unknown name '" + name.name() + "'");
                if (binding.kind() != Ast.BindingKind.LET) throw new IllegalArgumentException("cannot reassign " + binding.kind().name().toLowerCase() + " binding '" + name.name() + "'");
                targetType = binding.type();
                where = name.name();
            } else if (assignment.target() instanceof Ast.MemberExpr member) {
                targetType = memberType(member, env, generics, self, true);
                where = member.member();
            } else if (assignment.target() instanceof Ast.IndexExpr indexed) {
                if (indexed.receiver() instanceof Ast.NameExpr namespace
                        && modules.containsKey(namespace.name())) {
                    throw new IllegalArgumentException(
                            "module namespace '" + namespace.name()
                                    + "' does not support indexed assignment");
                }
                String constantKey = constantStringKey(indexed.index(), env);
                Type receiverSource;
                if (indexed.receiver() instanceof Ast.NameExpr receiverName) {
                    Env.Binding receiverBinding = env.lookup(receiverName.name());
                    if (receiverBinding != null && receiverBinding.hasUninitializedFields()) {
                        if (constantKey == null) {
                            throw new IllegalArgumentException(
                                    "dynamic indexed assignment cannot target partially initialized explicit struct '"
                                            + receiverName.name()
                                            + "'; initialize a statically known missing field first");
                        }
                        receiverSource = receiverBinding.type();
                    } else {
                        receiverSource = typeOf(indexed.receiver(), env, generics, self);
                    }
                } else {
                    receiverSource = typeOf(indexed.receiver(), env, generics, self);
                }
                Type receiver = receiverDispatchType(deref(receiverSource));
                Type index = typeOf(indexed.index(), env, generics, self);
                if (receiver instanceof Record record) {
                    requireAssignable(index, Primitive.STRING, "record/struct field key");
                    Map<String, Type> indexableMembers = indexableRecordMembers(record);
                    if (record.kind() == AggregateKind.CONTRACT) {
                        throw new IllegalArgumentException(
                                "cannot mutate through a structural contract view");
                    }
                    if (record.readOnly()) {
                        throw new IllegalArgumentException(
                                "cannot mutate a readonly structural record through indexed access");
                    }
                    if (constantKey != null) {
                        initializedIndexedField = constantKey;
                        targetType = indexableMembers.get(constantKey);
                        if (targetType == null) {
                            throw new IllegalArgumentException(
                                    "unknown record/struct field key '" + constantKey + "'");
                        }
                    } else {
                        if (indexableMembers.isEmpty()) {
                            throw new IllegalArgumentException(
                                    "cannot dynamically assign through an empty record/struct shape");
                        }
                        targetType = indexedElementUnion(indexableMembers.values());
                        dynamicFixedSlots = List.copyOf(indexableMembers.values());
                    }
                } else if (receiver instanceof Named named && findClass(named.name()) != null) {
                    requireAssignable(index, Primitive.STRING, "class field key");
                    Ast.ClassDecl klass = findClass(named.name());
                    if (klass.actorKind() != Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException("computed indexing is not supported for this receiver kind");
                    }
                    Ast.CheckedClassView.record(indexed.receiver(), named.name());
                    if (constantKey != null) {
                        ResolvedField field = findFieldTarget(
                                klass, named, constantKey, new LinkedHashSet<>());
                        if (field == null) {
                            throw new IllegalArgumentException(
                                    "unknown class field key '" + constantKey + "' on " + named.name());
                        }
                        requireClassMemberVisible(
                                field.field().visibility(), field.owner(), "field", field.field().name());
                        if (field.field().bindingKind() != Ast.BindingKind.LET) {
                            throw new IllegalArgumentException(
                                    "field '" + field.owner().name() + "." + field.field().name()
                                            + "' is immutable/final; indexed assignment cannot initialize fixed class fields");
                        }
                        Type pattern = classFieldType(field.owner(), field.field());
                        targetType = substituteGenerics(
                                pattern, classGenericBindings(field.owner(), field.ownerType()));
                    } else {
                        List<Type> writableFields = new ArrayList<>();
                        for (ResolvedField field : effectiveFieldTargets(
                                klass, named, new LinkedHashSet<>())) {
                            requireClassMemberVisible(
                                    field.field().visibility(), field.owner(), "field", field.field().name());
                            if (field.field().bindingKind() != Ast.BindingKind.LET) {
                                throw new IllegalArgumentException(
                                        "dynamic class field assignment requires every reachable field to be mutable; '"
                                                + field.owner().name() + "." + field.field().name()
                                                + "' is immutable/final");
                            }
                            Type pattern = classFieldType(field.owner(), field.field());
                            writableFields.add(substituteGenerics(
                                    pattern, classGenericBindings(field.owner(), field.ownerType())));
                        }
                        if (writableFields.isEmpty()) {
                            throw new IllegalArgumentException(
                                    "class '" + named.name() + "' has no indexable instance fields");
                        }
                        targetType = indexedElementUnion(writableFields);
                        dynamicFixedSlots = List.copyOf(writableFields);
                    }
                } else {
                    requireAssignable(index, Primitive.INT, "array/list/tuple index");
                    if (receiver instanceof ListType list) {
                        targetType = list.element();
                    } else if (receiver instanceof Tuple tuple) {
                        if (tuple.kind() == TupleKind.TUPLE) {
                            throw new IllegalArgumentException(
                                    "Tuple values are positional immutable products; indexed assignment is not allowed");
                        }
                        // FixedArray/FixedList retain their existing indexed mutation contract.
                        Long constant = constantTupleIndex(indexed.index(), env);
                        if (tuple.elements().isEmpty() || (constant != null
                                && (constant < 0 || constant >= tuple.elements().size()))) {
                            throw new IllegalArgumentException("fixed collection index outside arity "
                                    + tuple.elements().size());
                        }
                        if (constant != null) {
                            targetType = tuple.elements().get(constant.intValue());
                        } else {
                            targetType = indexedElementUnion(tuple.elements());
                            dynamicFixedSlots = tuple.elements();
                        }
                    } else {
                        throw new IllegalArgumentException(
                                "indexed assignment requires a mutable array/list/fixed collection, mutable struct, or class field");
                    }
                }
                where = "index";
            } else throw new IllegalArgumentException("unsupported assignment target");
            Type value = typeOf(assignment.value(), env, generics, self);
            requireReadonlyCapabilityPreserved(value, targetType, "assignment to " + where);
            requireAssignable(value, targetType, "assignment to " + where);
            for (Type slot : dynamicFixedSlots) {
                requireReadonlyCapabilityPreserved(value, slot, "dynamic indexed target assignment");
                requireAssignable(value, slot, "dynamic indexed target assignment");
            }
            if (assignment.target() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr owner) {
                env.markInitialized(owner.name(), member.member());
            }
            if (initializedIndexedField != null
                    && assignment.target() instanceof Ast.IndexExpr indexed
                    && indexed.receiver() instanceof Ast.NameExpr owner) {
                env.markInitialized(owner.name(), initializedIndexedField);
            }
            return targetType;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            requireAssignable(typeOf(conditional.condition(), env, generics, self), Primitive.BOOL, "ternary condition");
            Map<Env.Binding, Set<String>> afterCondition = env.initializationSnapshot();

            env.restoreInitialization(afterCondition);
            Type left = typeOf(conditional.whenTrue(), env, generics, self);
            Map<Env.Binding, Set<String>> leftExit = env.initializationSnapshot();

            env.restoreInitialization(afterCondition);
            Type right = typeOf(conditional.whenFalse(), env, generics, self);
            Map<Env.Binding, Set<String>> rightExit = env.initializationSnapshot();

            env.mergeInitialization(List.of(leftExit, rightExit));
            return commonType(left, right);
        }
        if (expr instanceof Ast.TypeTestExpr test) {
            requireRuntimeReifiableType(test.targetType(), "is");
            Type source = deref(typeOf(test.value(), env, generics, self));
            Type target = deref(resolve(test.targetType(), generics, self));
            if (!typesMayOverlap(source, target)) {
                throw new IllegalArgumentException("impossible type test: " + source + " cannot be " + target);
            }
            return Primitive.BOOL;
        }
        if (expr instanceof Ast.PatternTestExpr test) {
            Type subject = deref(typeOf(test.value(), env, generics, self));
            checkPattern(test.pattern(), subject, new Env(env), generics, self);
            return Primitive.BOOL;
        }
        if (expr instanceof Ast.CastExpr cast) {
            requireRuntimeReifiableType(cast.targetType(), cast.mode() == Ast.CastMode.OPTIONAL ? "as?" : "as");
            Type source = deref(typeOf(cast.value(), env, generics, self));
            Type target = deref(resolve(cast.targetType(), generics, self));
            if (!typesMayOverlap(source, target)) {
                throw new IllegalArgumentException("impossible cast: " + source + " cannot be cast to " + target);
            }
            Type narrowed = overlayReadonlyCapabilities(source, target);
            return cast.mode() == Ast.CastMode.OPTIONAL
                    ? new Named("Option", List.of(narrowed))
                    : narrowed;
        }
        if (expr instanceof Ast.TrapExpr trapped) {
            Type inner = typeOf(trapped.expression(), env, generics, self);
            if (inner instanceof Borrow)
                throw new IllegalArgumentException(
                        "trap cannot wrap a borrowed value without a proven Option loan lifetime");
            return new Named("Option", List.of(inner));
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            Type operand = typeOf(unary.operand(), env, generics, self);
            if (unary.operator().equals("&")) {
                // Reborrow an existing loan; borrowing the wrapper must not
                // create Borrow<Borrow<T>> or erase the source lifetime.
                if (operand instanceof Borrow existing)
                    return new Borrow(existing.target(), false);
                return new Borrow(operand, false);
            }
            if (unary.operator().equals("&mut")) {
                if (operand instanceof Borrow existing) {
                    // Shared loans cannot be escalated into exclusive loans.
                    if (!existing.mutable())
                        throw new IllegalArgumentException("cannot mutably reborrow a shared borrow");
                    return new Borrow(existing.target(), true);
                }
                return new Borrow(operand, true);
            }
            if (unary.operator().equals("!")) {
                requireAssignable(operand, Primitive.BOOL, "! operand");
                return Primitive.BOOL;
            }
            if (unary.operator().equals("~")) {
                requireInteger(operand, "~ operand");
                return Primitive.INT;
            }
            if (!Types.isNumeric(operand)) throw new IllegalArgumentException("unary " + unary.operator() + " needs a numeric operand");
            return operand;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            if (binary.operator().equals("pipe")) {
                checkPipeTopology(binary, env, generics, self);
                // Still fail closed: the current actor mailbox send() is void
                // and lacks awaitable bounded admission. Do not silently spawn
                // actors or build an unbackpressured bridge.
                throw new IllegalArgumentException(
                        "typed pipe ports are compatible, but ActorStream runtime "
                        + "with bounded delivery/startup/cancellation is not implemented yet");
            }
            Type left = typeOf(binary.left(), env, generics, self);
            if (binary.operator().equals("&&") || binary.operator().equals("||")) {
                requireAssignable(left, Primitive.BOOL, "logical operand");
                Map<Env.Binding, Set<String>> afterLeft = env.initializationSnapshot();
                Type right = typeOf(binary.right(), env, generics, self);
                requireAssignable(right, Primitive.BOOL, "logical operand");
                // RHS is short-circuited, so it cannot establish definite
                // initialization for subsequent code.
                env.restoreInitialization(afterLeft);
                return Primitive.BOOL;
            }
            Type right = typeOf(binary.right(), env, generics, self);
            return switch (binary.operator()) {
                case "^^" -> {
                    requireAssignable(left, Primitive.BOOL, "logical operand");
                    requireAssignable(right, Primitive.BOOL, "logical operand");
                    yield Primitive.BOOL;
                }
                case "&", "|", "^", "<<", ">>", ">>>" -> {
                    requireInteger(left, "bitwise left operand");
                    requireInteger(right, "bitwise right operand");
                    yield Primitive.INT;
                }
                case "eq", "neq", "==", "!=" -> {
                    requireEqualityComparable(left, right);
                    yield Primitive.BOOL;
                }
                case "is" -> {
                    requireIdentityBearing(left, "left operand of 'is'");
                    requireIdentityBearing(right, "right operand of 'is'");
                    if (!typesMayOverlap(deref(left), deref(right))) {
                        throw new IllegalArgumentException(
                                "identity operands have disjoint runtime domains: " + left + " and " + right);
                    }
                    yield Primitive.BOOL;
                }
                case "<", "<=", ">", ">=" -> {
                    if (!(Types.isNumeric(left) && Types.isNumeric(right)) && !(isStringLike(left) && isStringLike(right))) {
                        throw new IllegalArgumentException("comparison operands must both be numeric or both strings");
                    }
                    yield Primitive.BOOL;
                }
                case "+" -> isStringLike(left) && isStringLike(right) ? Primitive.STRING : numericJoin(left, right, "+");
                case "-", "*", "%" -> numericJoin(left, right, binary.operator());
                case "/" -> {
                    // OresEvalRootNode.numeric evaluates real division as a Double,
                    // even for int/int. The result cannot be assigned to int without
                    // an explicit conversion. Complex/decimal joins retain their
                    // existing representation pending dedicated numeric contracts.
                    Type result = numericJoin(left, right, "/");
                    yield result == Primitive.INT ? Primitive.FLOAT : result;
                }
                default -> Unknown.INSTANCE;
            };
        }
        if (expr instanceof Ast.SpreadExpr) {
            throw new IllegalArgumentException(
                    "spread expressions are only valid as arguments to a variadic callable");
        }
        if (expr instanceof Ast.RuntimeCallExpr runtime) {
            if (runtime.operation().equals("spawn")) {
                if (runtime.arguments().size() != 1
                        || !(runtime.arguments().getFirst() instanceof Ast.NewExpr created)) {
                    throw new IllegalArgumentException(
                            "internal spawn intrinsic requires exactly one actor target");
                }
                Ast.ClassDecl actorClass = findClass(created.type().name());
                if (actorClass == null) {
                    throw new IllegalArgumentException(
                            "spawn target '" + created.type().name()
                                    + "' is not a source actor visible to this compilation unit");
                }
                rejectUnimportedClassNamespace(actorClass);
                if (actorClass.actorKind() == Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException(
                            "spawn requires an actor class; '" + actorClass.name()
                                    + "' is an ordinary class");
                }
                if (actorClass.isAbstract()) {
                    throw new IllegalArgumentException(
                            "cannot spawn abstract actor '" + actorClass.name() + "'");
                }
                if (actorClass.actorConcurrency().transport() != Ast.ActorTransport.GENERIC) {
                    throw new IllegalArgumentException(
                            "actor '" + actorClass.name() + "' is a "
                                    + actorClass.actorConcurrency().transport()
                                    + " transport actor; generic spawn has no HTTP/TCP/WebSocket "
                                    + "request-dispatch adapter yet. Use an explicit transport "
                                    + "binding once the runtime provides one.");
                }
                if (!created.arguments().isEmpty()) {
                    throw new IllegalArgumentException(
                            "actor '" + actorClass.name()
                                    + "' does not accept constructor/startup arguments; "
                                    + "initialize actor-local state with fields and on_start()");
                }
                Type actorType = resolve(created.type(), generics, self);
                return new Named("ActorRef", List.of(actorType));
            }
            if (runtime.operation().equals("cooperate")) {
                if (!runtime.arguments().isEmpty()) {
                    throw new IllegalArgumentException("rt cooperate takes no arguments");
                }
                if (currentActorKind == Ast.ActorKind.UNTRUSTED) {
                    throw new IllegalArgumentException(
                            "rt cooperate is not enabled for untrusted actors until continuation quotas preserve fuel/deadline state across scheduler handoffs");
                }
                return Primitive.VOID;
            }
            if (runtime.arguments().size() != 1) {
                throw new IllegalArgumentException(
                        "rt " + runtime.operation() + " expects exactly one argument");
            }
            Type operand = typeOf(runtime.arguments().getFirst(), env, generics, self);
            if (runtime.operation().equals("fail")) {
                requireAssignable(operand, Primitive.STRING, "rt fail message");
                return Primitive.VOID;
            }
            return switch (runtime.operation()) {
                // Canonical rt borrow must preserve the same reborrow type
                // as the internal unary form, without nesting loan wrappers.
                case "borrow" -> operand instanceof Borrow existing
                        ? new Borrow(existing.target(), false) : new Borrow(operand, false);
                case "copy", "take", "share" -> operand;
                default -> throw new IllegalArgumentException(
                        "unknown runtime ownership operation 'rt " + runtime.operation() + "'");
            };
        }
        if (expr instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.MemberExpr sumMethod
                    && Set.of("map", "map_err", "and_then", "or_else", "filter",
                              "ok_or_else", "unwrap_or_else", "ok_or", "transpose", "ok").contains(sumMethod.member())) {
                Type sumReceiver = deref(typeOf(sumMethod.receiver(), env, generics, self));
                if (sumReceiver instanceof Named sum
                        && (sum.name().equals("Option") || sum.name().equals("Result"))) {
                    return checkTypedSumCombinator(call, sum, sumMethod.member(),
                            env, generics, self);
                }
            }
            if (call.callee() instanceof Ast.MemberExpr console
                    && console.receiver() instanceof Ast.NameExpr namespace
                    && namespace.name().equals("stdio") && env.lookup("stdio") == null
                    && (console.member().equals("print") || console.member().equals("println"))) {
                if (call.typeArgumentsPresent() || call.arguments().isEmpty()
                        || call.arguments().size() > 32) {
                    throw new IllegalArgumentException("stdio." + console.member()
                            + " requires 1 to 32 positional values and no type arguments");
                }
                for (Ast.Expr argument : call.arguments()) {
                    if (argument instanceof Ast.SpreadExpr) {
                        throw new IllegalArgumentException("stdio console spread is unsupported; use stdio.stdout.log");
                    }
                    typeOf(argument, env, generics, self);
                }
                return Primitive.VOID;
            }
            if (isBuiltinStdoutCall(call, "log", env)) {
                if (call.typeArgumentsPresent()) {
                    throw new IllegalArgumentException("stdio.stdout.log does not accept call-site type arguments");
                }
                for (Ast.Expr argument : call.arguments()) {
                    if (argument instanceof Ast.SpreadExpr spread) {
                        requireSpreadable(typeOf(spread.expression(), env, generics, self), "stdio.stdout.log spread");
                    } else {
                        typeOf(argument, env, generics, self);
                    }
                }
                return Primitive.VOID;
            }
            if (isBuiltinStdoutCall(call, "logList", env)) {
                if (call.typeArgumentsPresent()) {
                    throw new IllegalArgumentException("stdio.stdout.logList does not accept call-site type arguments");
                }
                if (call.arguments().size() != 1 || call.arguments().getFirst() instanceof Ast.SpreadExpr) {
                    throw new IllegalArgumentException("stdio.stdout.logList expects exactly one array/list/tuple argument");
                }
                requireSpreadable(typeOf(call.arguments().getFirst(), env, generics, self), "stdio.stdout.logList");
                return Primitive.VOID;
            }
            if (call.callee() instanceof Ast.NameExpr name
                    && name.name().equals("init")) {
                throw new IllegalArgumentException(
                        "init is a lifecycle hook and cannot be called directly; startup invokes it exactly once");
            }
            if (call.callee() instanceof Ast.MemberExpr member
                    && member.member().equals("init")
                    && member.receiver() instanceof Ast.NameExpr namespace
                    && modules.containsKey(namespace.name())) {
                throw new IllegalArgumentException(
                        "module init is a lifecycle hook and cannot be called directly; startup invokes it exactly once");
            }
            if (call.callee() instanceof Ast.NameExpr functionName
                    && env.lookup(functionName.name()) == null) {
                int arity = fixedCallArity(
                        call.arguments(), env, generics, self,
                        "function " + functionName.name());
                Ast.FunctionDecl target = findFunction(functionName.name(), arity);
                if (target != null) {
                    rejectImplicitClassCallable(functionName.name());
                    if (target.actorKind() != Ast.ActorKind.NONE
                            && currentActorKind != Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException(
                                "actor callable '" + target.name()
                                        + "' cannot be synchronously invoked from another actor turn; "
                                        + "use mailbox-oriented actor composition");
                    }
                    String label = "function " + functionName.name();
                    validateCallTypeArgumentMarker(call, target.genericParameters(), label);
                    Type result = checkGenericCallable(
                            target.genericParameters(),
                            target.parameters(),
                            target.returnType(),
                            call.arguments(),
                            env, generics, self,
                            null,
                            explicitGenericBindings(target.genericParameters(), call.typeArguments(), generics, self, label),
                            label, target.genericSpecs(),
                            GenericCallArguments.bind(
                                    target.genericParameters(), call.typeArguments(), label).inferenceHoles());
                    requireSuspendableCall(target.suspends(), "function '" + target.name() + "'");
                    return trapResult(target.trapped(), callableResult(target.async(), target.generator(), result));
                }
            }
            String booleanIntrinsic = booleanIntrinsicName(call, env);
            if (booleanIntrinsic != null) {
                return typeBooleanIntrinsicCall(booleanIntrinsic, call, env, generics, self);
            }
            if (call.callee() instanceof Ast.MemberExpr qualifiedCall
                    && qualifiedCall.receiver() instanceof Ast.NameExpr namespace
                    && env.lookup(namespace.name()) == null
                    && modules.containsKey(namespace.name())) {
                rejectUnimportedModuleNamespace(namespace.name());
                int arity = fixedCallArity(
                        call.arguments(), env, generics, self,
                        "function " + namespace.name() + "." + qualifiedCall.member());
                Ast.FunctionDecl target = findQualifiedFunction(
                        namespace.name() + "." + qualifiedCall.member(),
                        arity);
                if (target != null) {
                    if (target.actorKind() != Ast.ActorKind.NONE
                            && currentActorKind != Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException(
                                "actor callable '" + namespace.name() + "." + target.name()
                                        + "' cannot be synchronously invoked from another actor turn; "
                                        + "use mailbox-oriented actor composition");
                    }
                    String label = "function " + namespace.name() + "." + qualifiedCall.member();
                    validateCallTypeArgumentMarker(call, target.genericParameters(), label);
                    Type result = checkGenericCallable(
                            target.genericParameters(),
                            target.parameters(),
                            target.returnType(),
                            call.arguments(),
                            env, generics, self,
                            null,
                            explicitGenericBindings(target.genericParameters(), call.typeArguments(), generics, self, label),
                            label, target.genericSpecs(),
                            GenericCallArguments.bind(
                                    target.genericParameters(), call.typeArguments(), label).inferenceHoles());
                    requireSuspendableCall(target.suspends(), "function '" + target.name() + "'");
                    return trapResult(target.trapped(), callableResult(target.async(), target.generator(), result));
                }
            }
            if (call.callee() instanceof Ast.MemberExpr channelCall
                    && channelCall.receiver() instanceof Ast.NameExpr factory
                    && factory.name().equals("Channel")
                    && channelCall.member().equals("new")) {
                if (!call.typeArgumentsPresent() || call.typeArguments().size() != 1) {
                    throw new IllegalArgumentException(
                            "Channel.new<T>(capacity) requires exactly one explicit element type");
                }
                if (call.arguments().size() != 1) {
                    throw new IllegalArgumentException("Channel.new<T> expects exactly one capacity");
                }
                requireAssignable(
                        typeOf(call.arguments().getFirst(), env, generics, self),
                        Primitive.INT,
                        "Channel.new capacity");
                Ast.TypeRef channelArgument = call.typeArguments().getFirst();
                if (channelArgument.isNamedParameter()
                        || (channelArgument.name().equals("_")
                                && channelArgument.arguments().isEmpty()
                                && !channelArgument.inferArguments())) {
                    throw new IllegalArgumentException(
                            "Channel.new<T> requires one positional concrete payload type");
                }
                Type element = resolve(channelArgument, generics, self);
                validateChannelPayloadType(element, "Channel.new");
                return new Named("Channel", List.of(element));
            }

            if (call.callee() instanceof Ast.MemberExpr selectCaseCall
                    && selectCaseCall.receiver() instanceof Ast.NameExpr factory
                    && factory.name().equals("SelectCase")) {
                if (call.typeArgumentsPresent()) {
                    throw new IllegalArgumentException(
                            "SelectCase constructors infer channel element types");
                }
                return switch (selectCaseCall.member()) {
                    case "read" -> {
                        if (call.arguments().size() != 1) {
                            throw new IllegalArgumentException("SelectCase.read expects one Channel<T>");
                        }
                        channelElementType(
                                typeOf(call.arguments().getFirst(), env, generics, self),
                                "SelectCase.read");
                        yield new Named("SelectCase", List.of());
                    }
                    case "write" -> {
                        if (call.arguments().size() != 2) {
                            throw new IllegalArgumentException(
                                    "SelectCase.write expects Channel<T>, value");
                        }
                        Type element = channelElementType(
                                typeOf(call.arguments().get(0), env, generics, self),
                                "SelectCase.write");
                        requireAssignable(
                                typeOf(call.arguments().get(1), env, generics, self),
                                element,
                                "SelectCase.write value");
                        yield new Named("SelectCase", List.of());
                    }
                    case "future" -> {
                        if (call.arguments().size() != 1) {
                            throw new IllegalArgumentException("SelectCase.future expects one Future<T>");
                        }
                        Type pending = typeOf(call.arguments().getFirst(), env, generics, self);
                        if (!(pending instanceof Named future)
                                || !future.name().equals("Future")
                                || future.arguments().size() != 1) {
                            throw new IllegalArgumentException(
                                    "SelectCase.future requires Future<T>, got " + pending);
                        }
                        yield new Named("SelectCase", List.of());
                    }
                    case "default" -> {
                        if (!call.arguments().isEmpty()) {
                            throw new IllegalArgumentException("SelectCase.default expects no arguments");
                        }
                        yield new Named("SelectCase", List.of());
                    }
                    default -> throw new IllegalArgumentException(
                            "unknown SelectCase constructor '" + selectCaseCall.member() + "'");
                };
            }

            if (call.callee() instanceof Ast.MemberExpr selectSetCall
                    && selectSetCall.receiver() instanceof Ast.NameExpr factory
                    && factory.name().equals("SelectSet")
                    && selectSetCall.member().equals("new")) {
                if (call.typeArgumentsPresent()) {
                    throw new IllegalArgumentException("SelectSet.new does not accept type arguments");
                }
                if (call.arguments().size() != 1) {
                    throw new IllegalArgumentException(
                            "SelectSet.new expects one list/map of SelectCase values");
                }
                typeOf(call.arguments().getFirst(), env, generics, self);
                return new Named("SelectSet", List.of());
            }

            if (call.callee() instanceof Ast.MemberExpr futureCall
                    && futureCall.receiver() instanceof Ast.NameExpr futureFactory
                    && futureFactory.name().equals("Future")
                    && (futureCall.member().equals("all") || futureCall.member().equals("race"))) {
                if (call.typeArgumentsPresent()) {
                    throw new IllegalArgumentException(
                            "Future." + futureCall.member() + " does not accept call-site type arguments");
                }
                if (call.arguments().size() != 1) {
                    throw new IllegalArgumentException(
                            "Future." + futureCall.member() + " expects exactly one list of Future values");
                }
                Type input = deref(typeOf(call.arguments().getFirst(), env, generics, self));
                if (!(input instanceof ListType list)) {
                    throw new IllegalArgumentException(
                            "Future." + futureCall.member() + " expects List<Future<T>>; got " + input);
                }
                Type child = list.element();
                Type value;
                if (child == Unknown.INSTANCE) {
                    value = Unknown.INSTANCE;
                } else if (child instanceof Named named
                        && named.name().equals("Future")
                        && named.arguments().size() == 1) {
                    value = named.arguments().getFirst();
                } else {
                    throw new IllegalArgumentException(
                            "Future." + futureCall.member() + " expects List<Future<T>>; got List<" + child + ">");
                }
                return futureCall.member().equals("all")
                        ? new Named("Future", List.of(new ListType(value)))
                        : new Named("Future", List.of(value));
            }
            if (call.callee() instanceof Ast.MemberExpr factoryCall
                    && factoryCall.receiver() instanceof Ast.NameExpr factory
                    && (factory.name().equals("Mutex") || factory.name().equals("SharedMutex"))
                    && factoryCall.member().equals("new")) {
                if (call.typeArgumentsPresent()) throw new IllegalArgumentException(factory.name() + ".new does not accept call-site type arguments");
                if (call.arguments().size() != 1) throw new IllegalArgumentException(factory.name() + ".new expects exactly one value");
                Type element = typeOf(call.arguments().getFirst(), env, generics, self);
                if (element instanceof Borrow) {
                    throw new IllegalArgumentException(
                            factory.name() + "<T> requires owned data; borrowed values cannot become mutex state");
                }
                if (factory.name().equals("SharedMutex") && !isSharedMutexPayloadSafe(element, new LinkedHashSet<>(), Map.of())) {
                    throw new IllegalArgumentException(
                            "SharedMutex<T> requires shared-safe owned data; borrows, Mutex, MutexGuard, Future, closures, and unresolved generic/dynamic values are not shareable");
                }
                return new Named(factory.name(), List.of(element));
            }
            if (call.callee() instanceof Ast.NameExpr name
                    && (name.name().equals("Some") || name.name().equals("Ok") || name.name().equals("Err"))) {
                if (call.typeArgumentsPresent()) {
                    throw new IllegalArgumentException(name.name() + " does not accept call-site type arguments");
                }
                // Zero-payload Ok() is legal only when the expected T is
                // void. Its inferred type remains Result<void, Unknown>,
                // so a Result<int,E> target rejects it without coercion.
                if (name.name().equals("Ok") && call.arguments().isEmpty()) {
                    return new Named("Result", List.of(Primitive.VOID, Unknown.INSTANCE));
                }
                if (call.arguments().size() != 1) {
                    throw new IllegalArgumentException(name.name() + " expects exactly one value");
                }
                Type value = widenCollectionElement(typeOf(call.arguments().getFirst(), env, generics, self));
                return switch (name.name()) {
                    case "Some" -> new Named("Option", List.of(value));
                    case "Ok" -> new Named("Result", List.of(value, Unknown.INSTANCE));
                    case "Err" -> new Named("Result", List.of(Unknown.INSTANCE, value));
                    default -> throw new IllegalStateException("unreachable sum constructor");
                };
            }
            if (call.callee() instanceof Ast.MemberExpr member) {
                Type receiver = deref(typeOf(member.receiver(), env, generics, self));
                Type receiverValueType = receiver;
                receiver = receiverDispatchType(receiver);
                if (member.member().equals("unwrap_all")
                        && receiver instanceof Named outer
                        && outer.name().equals("Option")
                        && outer.arguments().size() == 1) {
                    int depth = 0;
                    Type current = receiver;
                    while (current instanceof Named layer
                            && layer.name().equals("Option")
                            && layer.arguments().size() == 1) {
                        if (++depth > 4096) {
                            throw new IllegalArgumentException(
                                    "Option.unwrap_all static depth exceeds 4096");
                        }
                        current = layer.arguments().getFirst();
                    }
                    // The compiler has already resolved the receiver's
                    // lexical type. Store an immutable call-site proof; the
                    // evaluator must not infer depth from OptionValue tags.
                    Ast.CheckedOptionDepth.record(call, depth);
                }

                // Nested extraction has no user-visible generic or value
                // arguments. Diagnose at the built-in boundary instead of
                // reporting an opaque first-class "call arity mismatch".
                if (receiver instanceof Named builtin
                        && ((builtin.name().equals("Future")
                                && member.member().equals("flatten"))
                            || (builtin.name().equals("Option")
                                && (member.member().equals("flatten")
                                    || member.member().equals("unwrap_all"))))) {
                    String operator = builtin.name() + "." + member.member();
                    if (call.typeArgumentsPresent()) {
                        throw new IllegalArgumentException(
                                operator + " does not accept call-site type arguments");
                    }
                    if (!call.arguments().isEmpty()) {
                        throw new IllegalArgumentException(operator + " expects no arguments");
                    }
                    // Direct invocation must NOT fall through to
                    // typeOf(call.callee()): that route intentionally rejects
                    // consuming nested extraction as a first-class member.
                    // The call-site has already been checked for arity, and
                    // unwrap_all has its static-depth proof attached above.
                    Type checkedOperation = builtin.name().equals("Option")
                            ? builtinOptionResultMember(receiver, member.member())
                            : builtinFutureMember(receiver, member.member());
                    if (!(checkedOperation instanceof Function checkedFunction)) {
                        throw new IllegalArgumentException(
                                operator + " requires a correctly typed receiver");
                    }
                    return checkedFunction.result();
                }

                boolean actorSelf = currentActorKind != Ast.ActorKind.NONE
                        && member.receiver() instanceof Ast.NameExpr selfName
                        && selfName.name().equals("self");
                if (actorSelf && (member.member().equals("on_start")
                        || member.member().equals("receive"))) {
                    throw new IllegalArgumentException(
                            member.member()
                                    + " is a reserved actor lifecycle hook and cannot be called directly");
                }
                if (actorSelf
                        && (member.member().equals("send")
                            || member.member().equals("end")
                            || member.member().equals("endWithCleanup"))) {
                    if (call.typeArgumentsPresent()) {
                        throw new IllegalArgumentException(
                                "self." + member.member()
                                        + " does not accept call-site type arguments");
                    }
                    if (member.member().equals("send")) {
                        if (call.arguments().size() != 1) {
                            throw new IllegalArgumentException(
                                    "self.send expects exactly one outbound value");
                        }
                        Type payload = typeOf(call.arguments().getFirst(), env, generics, self);
                        validateActorCallableBoundaryType(
                                payload,
                                currentActorKind,
                                false,
                                "self.send outbound value");
                        if (currentClassOwner != null) {
                            StreamPorts ports = streamPortsForClass(
                                    currentClassOwner, nominalClassType(currentClassOwner));
                            if (ports != null) {
                                if (ports.output() == Primitive.VOID)
                                    throw new IllegalArgumentException(
                                            "StreamOperator sink '" + currentClassOwner.name()
                                            + "' cannot emit a value with self.send");
                                requireAssignable(payload, ports.output(),
                                        "StreamOperator output of actor '" + currentClassOwner.name() + "'");
                            }
                        }
                        return Primitive.VOID;
                    }
                    if (member.member().equals("end")) {
                        if (!call.arguments().isEmpty()) {
                            throw new IllegalArgumentException(
                                    "self.end expects no arguments; use self.endWithCleanup(...) for final cleanup");
                        }
                        return Primitive.VOID;
                    }

                    if (call.arguments().size() != 1) {
                        throw new IllegalArgumentException(
                                "self.endWithCleanup expects exactly one final cleanup function");
                    }
                    Ast.Expr cleanup = call.arguments().getFirst();
                    Function expectedCleanup =
                            new Function(List.of(), Primitive.VOID);
                    if (cleanup instanceof Ast.LambdaExpr lambda) {
                        validateLambdaAgainstExpected(
                                lambda,
                                expectedCleanup,
                                env,
                                generics,
                                self);
                    } else {
                        Type cleanupType =
                                typeOf(cleanup, env, generics, self);
                        if (!(cleanupType instanceof Function function)
                                || function.async()
                                || !function.parameters().isEmpty()
                                || function.result() != Primitive.VOID) {
                            throw new IllegalArgumentException(
                                    "self.endWithCleanup cleanup must be a synchronous zero-argument function returning void");
                        }
                    }
                    return Primitive.VOID;
                }

                if (receiver instanceof Named actorRef
                        && actorRef.name().equals("ActorRef")) {
                    Ast.ClassDecl actorClass = actorClassForRef(actorRef);
                    if (call.typeArgumentsPresent()) {
                        throw new IllegalArgumentException(
                                "ActorRef." + member.member()
                                        + " does not accept call-site type arguments");
                    }
                    return switch (member.member()) {
                        case "send" -> {
                            if (call.arguments().size() != 1) {
                                throw new IllegalArgumentException(
                                        "ActorRef.send expects exactly one mailbox value");
                            }
                            Type payload = typeOf(
                                    call.arguments().getFirst(), env, generics, self);
                            validateActorCallableBoundaryType(
                                    payload,
                                    actorClass.actorKind(),
                                    false,
                                    "ActorRef.send mailbox value");
                            Type expectedPayload = actorMailboxPayloadType(actorRef);
                            requireAssignable(
                                    payload,
                                    expectedPayload,
                                    "ActorRef.send mailbox value for actor '" + actorClass.name() + "'");
                            yield Primitive.VOID;
                        }
                        case "stop" -> {
                            if (!call.arguments().isEmpty()) {
                                throw new IllegalArgumentException(
                                        "ActorRef.stop expects no arguments");
                            }
                            yield Primitive.VOID;
                        }
                        case "cancel", "kill" -> {
                            if (!call.arguments().isEmpty()) {
                                throw new IllegalArgumentException(
                                        "ActorRef." + member.member()
                                                + " expects no arguments");
                            }
                            yield Primitive.BOOL;
                        }
                        case "get_ready", "get_done" -> {
                            if (!call.arguments().isEmpty()) {
                                throw new IllegalArgumentException(
                                        "ActorRef." + member.member()
                                                + " expects no arguments");
                            }
                            yield new Named("Future", List.of(Primitive.VOID));
                        }
                        case "ready", "done" -> throw new IllegalArgumentException(
                                "ActorRef lifecycle futures are methods: use get_ready() or get_done()");
                        case "outputs", "id", "kind" ->
                                throw new IllegalArgumentException(
                                        "ActorRef." + member.member()
                                                + " is a property and is not callable");
                        default -> throw new IllegalArgumentException(
                                "unknown ActorRef callable member '"
                                        + member.member() + "'");
                    };
                }

                if (receiver instanceof Named outputStream
                        && outputStream.name().equals("ActorOutputStream")) {
                    if (outputStream.arguments().size() != 1) {
                        throw new IllegalArgumentException(
                                "ActorOutputStream<T> requires exactly one payload type");
                    }
                    if (call.typeArgumentsPresent()) {
                        throw new IllegalArgumentException(
                                "ActorOutputStream." + member.member()
                                        + " does not accept call-site type arguments");
                    }
                    if (member.member().equals("next")) {
                        if (!call.arguments().isEmpty()) {
                            throw new IllegalArgumentException(
                                    "ActorOutputStream.next expects no arguments");
                        }
                        Type output = new Named(
                                "ActorOutput",
                                List.of(outputStream.arguments().getFirst()));
                        return new Named(
                                "Future",
                                List.of(new Named(
                                        "IteratorResult",
                                        List.of(output))));
                    }
                    throw new IllegalArgumentException(
                            "unknown ActorOutputStream callable member '"
                                    + member.member() + "'");
                }

                Type collectionBuiltin = builtinCollectionMember(receiver, member.member());
                if (collectionBuiltin != null) {
                    if (!(collectionBuiltin instanceof Function builtin)) {
                        throw new IllegalArgumentException(
                                "collection member '" + member.member() + "' is not callable");
                    }
                    if (call.typeArgumentsPresent()) {
                        throw new IllegalArgumentException(
                                "collection member '" + member.member()
                                        + "' does not accept call-site type arguments");
                    }
                    if (builtin.parameters().size() != call.arguments().size()) {
                        throw new IllegalArgumentException(
                                "collection member '" + member.member() + "' call arity mismatch");
                    }
                    for (int i = 0; i < builtin.parameters().size(); i++) {
                        Type actualArgument =
                                typeOf(call.arguments().get(i), env, generics, self);
                        requireReadonlyCapabilityPreserved(
                                actualArgument,
                                builtin.parameters().get(i),
                                "collection member argument " + (i + 1));
                        requireAssignable(
                                actualArgument,
                                builtin.parameters().get(i),
                                "collection member argument " + (i + 1));
                    }
                    return builtin.result();
                }
                if (receiver instanceof ListType || receiver instanceof Tuple) {
                    throw new IllegalArgumentException(
                            "unknown collection member '" + member.member() + "'");
                }

                if (receiver instanceof Named guard
                        && guard.name().equals("MutexGuard")
                        && guard.arguments().size() == 1) {
                    Type guardBuiltin = builtinMutexMember(receiver, member.member());
                    if (guardBuiltin instanceof Function builtin) {
                        if (call.typeArgumentsPresent()) {
                            throw new IllegalArgumentException("MutexGuard." + member.member()
                                    + " does not accept call-site type arguments");
                        }
                        if (builtin.parameters().size() != call.arguments().size()) {
                            throw new IllegalArgumentException("MutexGuard." + member.member() + " call arity mismatch");
                        }
                        for (int i = 0; i < builtin.parameters().size(); i++) {
                            Type actualArgument =
                                    typeOf(call.arguments().get(i), env, generics, self);
                            requireReadonlyCapabilityPreserved(
                                    actualArgument,
                                    builtin.parameters().get(i),
                                    "MutexGuard argument " + (i + 1));
                            requireAssignable(
                                    actualArgument,
                                    builtin.parameters().get(i),
                                    "argument " + (i + 1));
                        }
                        return builtin.result();
                    }
                    // Non-builtin members are direct calls on the protected value.
                    // Unwrap only for call resolution; plain guard.method remains
                    // non-reifiable through the ordinary MemberExpr path.
                    receiver = unwrapMutexGuard(receiver);
                    receiverValueType = receiver;
                }

                if (receiver instanceof Record record) {
                    List<ExpandedCallArgument> structuralArguments =
                            expandFixedCallArguments(
                                    call.arguments(), env, generics, self,
                                    "structural method " + member.member());
                    int structuralArity = structuralArguments.size();
                    String prefix = CallableSelector.instance(member.member(), structuralArity).mangledName() + "$generics";
                    List<Map.Entry<String, Type>> candidates = record.members().entrySet().stream()
                            .filter(entry -> entry.getKey().startsWith(prefix))
                            .toList();
                    if (candidates.size() > 1) {
                        throw new IllegalArgumentException(
                                "ambiguous structural method '" + member.member()
                                        + "' with arity " + structuralArity);
                    }
                    if (candidates.size() == 1) {
                        Map.Entry<String, Type> candidate = candidates.getFirst();
                        int genericArity = Integer.parseInt(candidate.getKey().substring(prefix.length()));
                        if (!(candidate.getValue() instanceof Function rawFunction)) {
                            throw new IllegalArgumentException(
                                    "structural method contract for '" + member.member() + "' is not callable");
                        }
                        Function fn = (Function) bindReceiverSelf(rawFunction, receiverValueType);

                        String label = "structural method " + member.member();
                        List<String> canonicalNames =
                                GenericCallArguments.canonicalStructuralNames(genericArity);
                        Set<String> canonicalGenerics = new LinkedHashSet<>(canonicalNames);
                        // Structural callable contracts retain only alpha-normalized
                        // generic slots, not their original source names. Never
                        // interpret caller-provided named arguments as these slots.
                        if (call.typeArguments().stream().anyMatch(Ast.TypeRef::isNamedParameter)) {
                            throw new IllegalArgumentException(
                                    label + " cannot use named generic arguments because structural "
                                            + "method contracts do not preserve source parameter names");
                        }
                        if (call.typeArgumentsPresent() && genericArity == 0) {
                            throw new IllegalArgumentException(
                                    label + " is not generic and cannot be called with <...> or <>");
                        }
                        Map<String, Type> bindings = explicitGenericBindings(
                                canonicalNames, call.typeArguments(), generics, self, label);
                        Set<String> fixedBindings = new HashSet<>(bindings.keySet());

                        List<Type> actuals = new ArrayList<>(structuralArguments.size());
                        for (int i = 0; i < structuralArguments.size(); i++) {
                            Type actual = expandedCallArgumentType(
                                    structuralArguments.get(i),
                                    fn.parameters().get(i),
                                    env, generics, self);
                            actuals.add(actual);
                            inferGenericBindings(
                                    fn.parameters().get(i),
                                    actual,
                                    bindings,
                                    fixedBindings,
                                    label);
                        }

                        Set<String> unbound = new HashSet<>(canonicalGenerics);
                        unbound.removeAll(bindings.keySet());
                        if (!unbound.isEmpty()) {
                            throw new IllegalArgumentException("cannot infer generic parameter(s) "
                                    + unbound.stream().sorted().toList() + " for " + label);
                        }
                        for (int i = 0; i < structuralArguments.size(); i++) {
                            Type expected = substituteGenerics(fn.parameters().get(i), bindings);
                            if (containsGenericNamed(expected, unbound)) {
                                throw new IllegalArgumentException(
                                        "cannot infer all generic parameters for "
                                                + label + " from argument " + (i + 1));
                            }
                            ExpandedCallArgument argument = structuralArguments.get(i);
                            if (argument.expression() != null) {
                                validateLambdaArgument(
                                        argument.expression(), expected, env, generics, self);
                            }
                            requireReadonlyCapabilityPreserved(
                                    actuals.get(i),
                                    expected,
                                    "argument " + (i + 1) + " to structural method");
                            requireMutableReferentCompatible(
                                    actuals.get(i),
                                    fn.mutableParameters().get(i),
                                    "argument " + (i + 1) + " to mutable structural method parameter");
                            requireAssignable(
                                    actuals.get(i),
                                    expected,
                                    "argument " + (i + 1));
                        }

                        Type result = substituteGenerics(fn.result(), bindings);
                        if (containsGenericNamed(result, unbound)) {
                            throw new IllegalArgumentException(
                                    "cannot infer generic return type for " + label
                                            + "; provide explicit type arguments or an inferable value parameter");
                        }
                        return result;
                    }
                }

                if (receiver instanceof ClassNamespace classNamespace) {
                    Ast.ClassDecl klass = findClass(classNamespace.className());
                    if (klass == null) throw new IllegalArgumentException("unknown class namespace '" + classNamespace.className() + "'");
                    int staticArity = fixedCallArity(
                            call.arguments(), env, generics, self,
                            "static function " + klass.name() + "." + member.member());
                    Ast.MethodDecl fn = findStaticFunction(klass, member.member(), staticArity, new LinkedHashSet<>());
                    if (fn == null) throw new IllegalArgumentException("no static function '" + member.member() + "' with arity " + staticArity + " on " + klass.name());
                    Ast.ClassDecl fnOwner = findDeclaringClass(klass, fn, new LinkedHashSet<>());
                    requireClassMemberVisible(fn.visibility(), fnOwner, "static function", fn.name());
                    validateCallTypeArgumentMarker(call, fn.genericParameters(), "static function " + fnOwner.name() + "." + fn.name());
                    List<String> callableGenerics = new ArrayList<>(fn.genericParameters());
                    String label = "static function " + klass.name() + "." + fn.name();
                    Type result = checkGenericCallable(
                            callableGenerics,
                            fn.parameters(),
                            fn.returnType(),
                            call.arguments(),
                            env, generics, self,
                            null,
                            explicitGenericBindings(fn.genericParameters(), call.typeArguments(), generics, self, label),
                            label);
                    requireSuspendableCall(fn.suspends(), "static function '" + fn.name() + "'");
                    return callableResult(fn.async(), false, result);
                }
                if (receiver instanceof Named named) {
                    if (named.name().equals("SharedMutex")
                            && currentActorKind != Ast.ActorKind.NONE
                            && member.member().equals("with_lock")) {
                        throw new IllegalArgumentException(
                                "actor code cannot use blocking SharedMutex.with_lock(); use try_lock() or await lock_async()");
                    }
                    if ((named.name().equals("Mutex") || named.name().equals("SharedMutex"))
                            && named.arguments().size() == 1
                            && (member.member().equals("with_lock") || member.member().equals("recover"))) {
                        if (member.member().equals("recover") && !named.name().equals("SharedMutex")) {
                            throw new IllegalArgumentException("recover is only available on SharedMutex<T>");
                        }
                        if (call.arguments().size() != 1) {
                            throw new IllegalArgumentException(member.member() + " expects exactly one callback");
                        }
                        Ast.Expr callback = call.arguments().getFirst();
                        if (!(callback instanceof Ast.LambdaExpr lambda)) {
                            throw new IllegalArgumentException(
                                    member.member() + " requires an inline one-argument lambda so the protected borrow cannot escape");
                        }
                        validateMutexCallback(lambda, named.arguments().getFirst(), env, generics, self);
                        return Primitive.VOID;
                    }
                    Ast.ClassDecl klass = findClass(named.name());
                    if (klass != null) {
                        int methodArity = fixedCallArity(
                                call.arguments(), env, generics, self,
                                "method " + named.name() + "." + member.member());
                        ResolvedMethod target = findMethodTarget(
                                klass, named, member.member(), methodArity, new LinkedHashSet<>());
                        if (target == null) {
                            ResolvedField field = findFieldTarget(
                                    klass, named, member.member(), new LinkedHashSet<>());
                            if (field != null) {
                                requireClassMemberVisible(
                                        field.field().visibility(), field.owner(), "field", field.field().name());
                                if (call.typeArgumentsPresent()) {
                                    throw new IllegalArgumentException(
                                            "function-valued field '" + member.member()
                                                    + "' does not accept call-site type arguments");
                                }
                                Type fieldType = substituteGenerics(
                                        classFieldType(field.owner(), field.field()),
                                        classGenericBindings(field.owner(), field.ownerType()));
                                if (!(fieldType instanceof Function fn)) {
                                    throw new IllegalArgumentException(
                                            "field '" + member.member() + "' on " + named.name()
                                                    + " is not callable");
                                }
                                List<ExpandedCallArgument> fieldArguments =
                                        expandFixedCallArguments(
                                                call.arguments(), env, generics, self,
                                                "function-valued field " + member.member());
                                if (fn.parameters().size() != fieldArguments.size()) {
                                    throw new IllegalArgumentException(
                                            "function-valued field '" + member.member() + "' call arity mismatch");
                                }
                                for (int i = 0; i < fn.parameters().size(); i++) {
                                    ExpandedCallArgument argument = fieldArguments.get(i);
                                    if (argument.expression() != null) {
                                        validateLambdaArgument(
                                                argument.expression(), fn.parameters().get(i), env, generics, self);
                                    }
                                    Type actualArgument =
                                            expandedCallArgumentType(
                                                    argument, fn.parameters().get(i), env, generics, self);
                                    requireReadonlyCapabilityPreserved(
                                            actualArgument,
                                            fn.parameters().get(i),
                                            "argument " + (i + 1) + " to function-valued field");
                                    requireMutableReferentCompatible(
                                            actualArgument,
                                            fn.mutableParameters().get(i),
                                            "argument " + (i + 1) + " to mutable function-valued field parameter");
                                    requireAssignable(
                                            actualArgument,
                                            fn.parameters().get(i),
                                            "argument " + (i + 1));
                                }
                                return fn.result();
                            }
                            throw new IllegalArgumentException(
                                    "no method or callable field '" + member.member()
                                            + "' with arity " + methodArity
                                            + " on " + named.name());
                        }
                        Ast.MethodDecl method = target.method();
                        requireSuspendableCall(method.suspends(), "method '" + method.name() + "'");
                        Ast.ClassDecl owner = target.owner();
                        Named ownerType = target.ownerType();
                        requireClassMemberVisible(method.visibility(), owner, "method", method.name());
                        validateCallTypeArgumentMarker(call, method.genericParameters(), "method " + owner.name() + "." + method.name());
                        List<String> callableGenerics = new ArrayList<>(owner.genericParameters());
                        callableGenerics.addAll(method.genericParameters());
                        String label = "method " + owner.name() + "." + method.name();
                        Map<String, Type> bindings = new HashMap<>(classGenericBindings(owner, ownerType));
                        bindings.putAll(explicitGenericBindings(method.genericParameters(), call.typeArguments(), generics, self, label));
                        Type callableReceiver = receiverValueType instanceof SelfType
                                ? receiverValueType
                                : named;
                        return checkGenericCallable(
                                callableGenerics,
                                method.parameters(),
                                method.returnType(),
                                call.arguments(),
                                env, generics, self,
                                callableReceiver,
                                bindings,
                                label);
                    }

                    Ast.InterfaceDecl iface = findInterface(named.name());
                    if (iface != null) {
                        int interfaceArity = fixedCallArity(
                                call.arguments(), env, generics, self,
                                "interface method " + named.name() + "." + member.member());
                        ResolvedInterfaceFunction target = findInterfaceFunctionTarget(
                                iface, named, member.member(), interfaceArity, new LinkedHashSet<>());
                        if (target == null) {
                            throw new IllegalArgumentException(
                                    "no interface method '" + member.member() + "' with arity "
                                            + interfaceArity + " on " + named.name());
                        }
                        Ast.InterfaceFunctionDecl method = target.function();
                        Ast.InterfaceDecl owner = target.owner();
                        Named ownerType = target.ownerType();
                        String label = "interface method " + owner.name() + "." + method.name();
                        validateCallTypeArgumentMarker(call, method.genericParameters(), label);

                        List<String> callableGenerics = new ArrayList<>(owner.genericParameters());
                        callableGenerics.addAll(method.genericParameters());
                        Map<String, Type> bindings = new HashMap<>(genericBindings(
                                owner.genericParameters(), ownerType.arguments(), "interface " + owner.name()));
                        bindings.putAll(explicitGenericBindings(
                                method.genericParameters(), call.typeArguments(), generics, self, label));

                        Type callableReceiver = receiverValueType instanceof SelfType
                                ? receiverValueType
                                : named;
                        return checkGenericCallable(
                                callableGenerics,
                                method.parameters(),
                                method.returnType(),
                                call.arguments(),
                                env, generics, self,
                                callableReceiver,
                                bindings,
                                label);
                    }
                }
            }
            if (call.typeArgumentsPresent()) {
                throw new IllegalArgumentException("call-site type arguments require a declared generic function or method");
            }
            Type callee = typeOf(call.callee(), env, generics, self);
            if (!(callee instanceof Function fn)) return Unknown.INSTANCE;
            List<ExpandedCallArgument> expanded =
                    expandFixedCallArguments(
                            call.arguments(), env, generics, self,
                            "function value call");
            if (fn.parameters().size() != expanded.size()) throw new IllegalArgumentException("call arity mismatch");
            for (int i = 0; i < fn.parameters().size(); i++) {
                ExpandedCallArgument argument = expanded.get(i);
                if (argument.expression() != null) {
                    validateLambdaArgument(argument.expression(), fn.parameters().get(i), env, generics, self);
                }
                Type actualArgument = expandedCallArgumentType(
                        argument, fn.parameters().get(i), env, generics, self);
                requireReadonlyCapabilityPreserved(
                        actualArgument,
                        fn.parameters().get(i),
                        "argument " + (i + 1) + " to function");
                requireMutableReferentCompatible(
                        actualArgument,
                        fn.mutableParameters().get(i),
                        "argument " + (i + 1) + " to mutable function parameter");
                requireAssignable(actualArgument, fn.parameters().get(i), "argument " + (i + 1));
            }
            return fn.result();
        }
        if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr namespace
                    && namespace.name().equals("BooleanOps")
                    && !modules.containsKey("BooleanOps")
                    && isBooleanIntrinsicName(member.member())) {
                throw new IllegalArgumentException(
                        "overloaded builtin BooleanOps." + member.member()
                                + " must be called so arity can select the scalar or list overload");
            }
            if (member.receiver() instanceof Ast.NameExpr namespace
                    && env.lookup(namespace.name()) == null
                    && modules.containsKey(namespace.name())) {
                rejectUnimportedModuleNamespace(namespace.name());
                Ast.FunctionDecl moduleFunction = findSingleQualifiedFunction(
                        namespace.name() + "." + member.member());
                if (moduleFunction != null) {
                    if (moduleFunction.kind() == Ast.CallableKind.ROUTINE) {
                        throw new IllegalArgumentException("routine '" + namespace.name() + "." + member.member()
                                + "' is direct-call-only and cannot be used as a first-class callable value; "
                                + "invoke it directly or wrap the call in an explicit lambda");
                    }
                    if (!moduleFunction.genericParameters().isEmpty()) {
                        throw new IllegalArgumentException(
                                "generic callable '" + namespace.name() + "." + member.member()
                                        + "' must be specialized by a direct call; polymorphic function values are not supported yet");
                    }
                    return trapFunctionType(moduleFunction, Set.of(), null);
                }
                Ast.ClassDecl memberClass = classes.get(namespace.name() + "." + member.member());
                if (memberClass != null) return new ClassNamespace(qualifiedClassName(memberClass));
            }
            if (member.receiver() instanceof Ast.NameExpr stdName && stdName.name().equals("std")
                    && env.lookup("std") == null && member.member().equals("process")) {
                return new Named("process", List.of());
            }
            if (member.member().equals("exit")
                    && ((member.receiver() instanceof Ast.NameExpr processName
                             && processName.name().equals("process") && env.lookup("process") == null)
                        || (member.receiver() instanceof Ast.MemberExpr namespace
                             && namespace.member().equals("process")
                             && namespace.receiver() instanceof Ast.NameExpr root
                             && root.name().equals("std") && env.lookup("std") == null))) {
                return new Function(List.of(Primitive.INT), Primitive.VOID);
            }
            if (member.receiver() instanceof Ast.NameExpr idName && idName.name().equals("process")
                    && env.lookup("process") == null && member.member().equals("unique_id")) {
                return new Function(List.of(), Primitive.STRING);
            }
            if (member.receiver() instanceof Ast.NameExpr clockName && clockName.name().equals("process")
                    && env.lookup("process") == null
                    && (member.member().equals("monotonic_ns") || member.member().equals("unix_ms"))) {
                return new Function(List.of(), Primitive.INT);
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("stdio")
                    && env.lookup("stdio") == null) {
                if (member.member().equals("print") || member.member().equals("println")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
                if (member.member().equals("stdout")) return new Named("stdio.stdout", List.of());
                if (member.member().equals("stdin")) return new Named("stdio.stdin", List.of());
            }
            Type receiver = typeOf(member.receiver(), env, generics, self);
            Type sumReceiver = receiverDispatchType(deref(receiver));
            // Nested extraction consumes the receiver. unwrap_all also
            // requires a checked static-depth proof bound to the CallExpr's
            // identity. A first-class method value would bypass both direct
            // call checks, potentially duplicating an affine payload.
            if (sumReceiver instanceof Named extractionReceiver
                    && extractionReceiver.arguments().size() == 1
                    && ((extractionReceiver.name().equals("Option")
                            && (member.member().equals("unwrap_all")
                                    || member.member().equals("flatten")))
                        || (extractionReceiver.name().equals("Future")
                            && member.member().equals("flatten")))) {
                throw new IllegalArgumentException(
                        extractionReceiver.name() + "." + member.member()
                                + " is a consuming nested extraction operation; "
                                + "invoke it directly rather than capturing a method value");
            }
            // Channels are actor-local capabilities, but their lifecycle can
            // be controlled by the owning source code without Java interop.
            if (sumReceiver instanceof Named channelType
                    && channelType.name().equals("Channel")
                    && channelType.arguments().size() == 1) {
                return switch (member.member()) {
                    case "close" -> new Function(List.of(), Primitive.VOID);
                    case "is_closed" -> new Function(List.of(), Primitive.BOOL);
                    default -> throw new IllegalArgumentException(
                            "unknown Channel member '" + member.member() + "'");
                };
            }
            Type ioMember = builtinIoMember(sumReceiver, member.member());
            if (ioMember != null) return ioMember;
            Type httpMember = builtinHttpMember(sumReceiver, member.member());
            if (httpMember != null) return httpMember;
            Type futureMember = builtinFutureMember(sumReceiver, member.member());
            if (futureMember != null) return futureMember;
            if (sumReceiver instanceof Named actorRef
                    && actorRef.name().equals("ActorRef")) {
                actorClassForRef(actorRef);
                return switch (member.member()) {
                    case "get_ready", "get_done" ->
                            throw new IllegalArgumentException(
                                    "ActorRef." + member.member()
                                            + " is a method; invoke it with ()");
                    case "ready", "done" ->
                            throw new IllegalArgumentException(
                                    "ActorRef lifecycle futures are methods: use get_ready() or get_done()");
                    case "outputs" -> new Named(
                            "ActorOutputStream",
                            List.of(Unknown.INSTANCE));
                    case "id" -> new Named("ActorId", List.of());
                    case "kind" -> new Named("ActorKind", List.of());
                    case "send", "stop", "cancel", "kill" ->
                            throw new IllegalArgumentException(
                                    "ActorRef." + member.member()
                                            + " is direct-call-only; invoke it with (...)");
                    default -> throw new IllegalArgumentException(
                            "unknown ActorRef member '" + member.member() + "'");
                };
            }
            if (sumReceiver instanceof Named outputStream
                    && outputStream.name().equals("ActorOutputStream")) {
                if (outputStream.arguments().size() != 1) {
                    throw new IllegalArgumentException(
                            "ActorOutputStream<T> requires exactly one payload type");
                }
                return switch (member.member()) {
                    case "is_done" -> Primitive.BOOL;
                    case "next" -> throw new IllegalArgumentException(
                            "ActorOutputStream.next is direct-call-only; invoke it with ()");
                    default -> throw new IllegalArgumentException(
                            "unknown ActorOutputStream member '"
                                    + member.member() + "'");
                };
            }
            if (sumReceiver instanceof Named output
                    && output.name().equals("ActorOutput")) {
                if (output.arguments().size() != 1) {
                    throw new IllegalArgumentException(
                            "ActorOutput<T> requires exactly one payload type");
                }
                return switch (member.member()) {
                    case "actor_id" -> new Named("ActorId", List.of());
                    case "sequence" -> Primitive.INT;
                    case "value" -> output.arguments().getFirst();
                    default -> throw new IllegalArgumentException(
                            "unknown ActorOutput member '" + member.member() + "'");
                };
            }
            if (sumReceiver instanceof Named mail
                    && mail.name().equals("ActorMail")) {
                if (mail.arguments().size() != 1) {
                    throw new IllegalArgumentException("ActorMail<T> requires exactly one payload type");
                }
                return switch (member.member()) {
                    case "value" -> mail.arguments().getFirst();
                    case "recipient" -> new Named("ActorId", List.of());
                    case "sender" -> new Named(
                            "Option",
                            List.of(new Named("ActorId", List.of())));
                    case "sequence" -> Primitive.INT;
                    default -> throw new IllegalArgumentException(
                            "unknown ActorMail member '" + member.member() + "'");
                };
            }
            // Array/list and tuple lengths are native integer properties.
            // Returning Unknown here makes safe bounds checks and subtraction
            // fail typechecking even when the receiver is statically typed.
            if ((sumReceiver instanceof ListType || sumReceiver instanceof Tuple)
                    && member.member().equals("length")) {
                return Primitive.INT;
            }
            if (sumReceiver instanceof Function) {
                throw new IllegalArgumentException("unknown function member '" + member.member()
                        + "'; invoke directly or spread a statically known tuple with f(...args)");
            }
            Type collectionMember = builtinCollectionMember(sumReceiver, member.member());
            if (isStringLike(sumReceiver)) {
                return switch (member.member()) {
                    case "replace_literal" -> new Function(List.of(Primitive.STRING, Primitive.STRING), Primitive.STRING);
                    case "split_literal" -> new Function(List.of(Primitive.STRING), new ListType(Primitive.STRING));
                    case "trim" -> new Function(List.of(), Primitive.STRING);
                    case "is_identifier", "is_route_segment" -> new Function(List.of(), Primitive.BOOL);
                    case "starts_with", "ends_with", "contains_literal" ->
                            new Function(List.of(Primitive.STRING), Primitive.BOOL);
                    default -> throw new IllegalArgumentException("unknown string member '" + member.member() + "'");
                };
            }
            if (collectionMember != null) return collectionMember;
            if (sumReceiver instanceof ListType || sumReceiver instanceof Tuple) {
                throw new IllegalArgumentException(
                        "unknown collection member '" + member.member() + "'");
            }
            Type iteratorMember = builtinIteratorMember(sumReceiver, member.member());
            if (iteratorMember != null) return iteratorMember;
            Type sumMember = builtinOptionResultMember(sumReceiver, member.member());
            if (sumMember != null) {
                // The synthetic callback signatures for combinators are not
                // first-class polymorphic function values. Reifying them would
                // expose Unknown result parameters and bypass direct-call
                // inference, allowing unsound generic widening.
                if (Set.of("map", "map_err", "and_then", "or_else",
                           "filter", "ok_or_else", "unwrap_or_else", "ok_or").contains(member.member())) {
                    throw new IllegalArgumentException(
                            "Option/Result combinators require a direct call; "
                            + "first-class extraction is not yet type-safe");
                }
                return sumMember;
            }
            if (sumReceiver instanceof Named sumNamed
                    && (sumNamed.name().equals("Option") || sumNamed.name().equals("Result"))) {
                throw new IllegalArgumentException(
                        "unknown " + sumNamed.name() + " member '" + member.member() + "'");
            }
            Type mutexMember = builtinMutexMember(receiver, member.member());
            if (mutexMember != null) return mutexMember;

            Type selectedReceiver = deref(receiver);

            if (selectedReceiver instanceof Named selected
                    && selected.name().equals("SelectResult")) {
                return switch (member.member()) {
                    case "index" -> Primitive.INT;
                    case "operation" -> Primitive.STRING;
                    case "value" -> Unknown.INSTANCE;
                    default -> throw new IllegalArgumentException(
                            "unknown SelectResult member '" + member.member() + "'");
                };
            }

            receiver = receiverDispatchType(unwrapMutexGuard(deref(receiver)));
            if (receiver instanceof Named named && named.name().equals("stdio.stdout")) {
                if (member.member().equals("write") || member.member().equals("println") || member.member().equals("logList")) {
                    return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
                }
                if (member.member().equals("log")) {
                    throw new IllegalArgumentException(
                            "variadic stdio.stdout.log is direct-call-only; wrap it in an explicit lambda to reify logging behavior");
                }
            }
            if (receiver instanceof Named named
                    && named.name().equals("stdio.stdin")
                    && member.member().equals("read_line")) {
                return new Function(List.of(), Primitive.STRING);
            }
            if (member.receiver() instanceof Ast.NameExpr name
                    && (name.name().equals("process")
                        || name.name().equals("actor")
                        || name.name().equals("fs")
                        || name.name().equals("File")
                        || name.name().equals("network")
                        || name.name().equals("net")
                        || name.name().equals("http")
                        || name.name().equals("env"))) return Unknown.INSTANCE;
            if (member.receiver() instanceof Ast.NameExpr name && importedValues.contains(name.name())) return Unknown.INSTANCE;

            if (receiver instanceof ClassNamespace classNamespace) {
                Ast.ClassDecl klass = findClass(classNamespace.className());
                if (klass == null) throw new IllegalArgumentException("unknown class namespace '" + classNamespace.className() + "'");
                List<Ast.MethodDecl> functions = findStaticFunctionsByName(klass, member.member(), new LinkedHashSet<>());
                if (functions.size() == 1) {
                    Ast.MethodDecl fn = functions.getFirst();
                    Ast.ClassDecl fnOwner = findDeclaringClass(klass, fn, new LinkedHashSet<>());
                    requireClassMemberVisible(fn.visibility(), fnOwner, "static function", fn.name());
                    if (!fn.genericParameters().isEmpty()) {
                        throw new IllegalArgumentException(
                                "generic static function '" + klass.name() + "." + fn.name()
                                        + "' must be specialized by a direct call; polymorphic function values are not supported yet");
                    }
                    // Generator declarations are deliberately restricted to top-level/module
                    // callables, so class static functions cannot produce generator activations.
                    return functionType(fn.parameters(), fn.returnType(), fn.async(), false, Set.of(), null);
                }
                if (functions.size() > 1) throw new IllegalArgumentException("overloaded static function '" + member.member() + "' must be called so arity can select the overload");
                throw new IllegalArgumentException("unknown static member '" + member.member() + "' on " + klass.name());
            }

            if (receiver instanceof Record record) {
                Type result = record.members().get(member.member());
                if (result != null) return result;

                String methodPrefix = CallableSelector.instance(member.member(), 0).mangledName().replace("$arity0", "$arity");
                boolean structuralMethod = record.members().keySet().stream()
                        .anyMatch(key -> key.startsWith(methodPrefix) && key.contains("$generics"));
                if (structuralMethod) {
                    throw new IllegalArgumentException(
                            "structural method '" + member.member()
                                    + "' is direct-call-only and cannot be used as a first-class callable value; "
                                    + "invoke it directly or wrap that call in an explicit lambda");
                }
                throw new IllegalArgumentException("unknown structural member '" + member.member() + "'");
            }
            if (receiver instanceof Named named) {
                Ast.ClassDecl klass = findClass(named.name());
                if (klass != null) {
                    ResolvedField field = findFieldTarget(klass, named, member.member(), new LinkedHashSet<>());
                    if (field != null) {
                        requireClassMemberVisible(
                                field.field().visibility(), field.owner(), "field", field.field().name());
                        Type pattern = classFieldType(field.owner(), field.field());
                        return substituteGenerics(pattern, classGenericBindings(field.owner(), field.ownerType()));
                    }
                    List<Ast.MethodDecl> methods = findMethodsByName(klass, member.member(), new LinkedHashSet<>());
                    if (!methods.isEmpty()) {
                        throw new IllegalArgumentException(
                                "instance method '" + klass.name() + "." + member.member()
                                        + "' is direct-call-only and cannot be used as a first-class callable value; "
                                        + "invoke it as receiver." + member.member()
                                        + "(...) or wrap that call in an explicit lambda");
                    }
                }

                Ast.InterfaceDecl iface = findInterface(named.name());
                if (iface != null && hasInterfaceFunctionNamed(
                        iface, member.member(), new LinkedHashSet<>())) {
                    throw new IllegalArgumentException(
                            "interface method '" + iface.name() + "." + member.member()
                                    + "' is direct-call-only and cannot be used as a first-class callable value; "
                                    + "invoke it as receiver." + member.member()
                                    + "(...) or wrap that call in an explicit lambda");
                }
            }
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            if (indexed.receiver() instanceof Ast.NameExpr namespace
                    && modules.containsKey(namespace.name())) {
                throw new IllegalArgumentException(
                        "module namespace '" + namespace.name()
                                + "' does not support computed property access; use a declared member name");
            }
            Type receiver = receiverDispatchType(deref(typeOf(indexed.receiver(), env, generics, self)));
            Type index = typeOf(indexed.index(), env, generics, self);
            String constantKey = constantStringKey(indexed.index(), env);
            Env.Binding indexedOwner = indexed.receiver() instanceof Ast.NameExpr ownerName
                    ? env.lookup(ownerName.name()) : null;
            if (receiver instanceof Record record) {
                requireAssignable(index, Primitive.STRING, "record/struct field key");
                Map<String, Type> indexableMembers = indexableRecordMembers(record);
                if (record.kind() == AggregateKind.CONTRACT) {
                    Ast.CheckedStructuralFieldDomain.record(
                            indexed.receiver(), indexableMembers.keySet());
                }
                if (indexedOwner != null && indexedOwner.hasUninitializedFields()) {
                    if (constantKey != null) {
                        if (indexedOwner.isUninitialized(constantKey)) {
                            throw new IllegalArgumentException(
                                    "explicit struct field '" + constantKey
                                            + "' is read before initialization on '"
                                            + ((Ast.NameExpr) indexed.receiver()).name() + "'");
                        }
                    } else {
                        throw new IllegalArgumentException(
                                "dynamic indexed read cannot select from partially initialized struct '"
                                        + ((Ast.NameExpr) indexed.receiver()).name()
                                        + "'; initialize every field or use an exact initialized key");
                    }
                }
                if (constantKey != null) {
                    Type member = indexableMembers.get(constantKey);
                    if (member == null) {
                        throw new IllegalArgumentException(
                                "unknown record/struct field key '" + constantKey + "'");
                    }
                    return member;
                }
                if (indexableMembers.isEmpty()) {
                    throw new IllegalArgumentException(
                            "structural value has no indexable data fields");
                }
                return indexedElementUnion(indexableMembers.values());
            }
            if (receiver instanceof Named named) {
                Ast.ClassDecl klass = findClass(named.name());
                if (klass != null) {
                    requireAssignable(index, Primitive.STRING, "class field key");
                    if (!klass.actorKind().equals(Ast.ActorKind.NONE)) {
                        throw new IllegalArgumentException(
                                "computed indexing requires an ordinary class instance");
                    }
                    Ast.CheckedClassView.record(indexed.receiver(), named.name());
                    if (constantKey != null) {
                        ResolvedField field = findFieldTarget(
                                klass, named, constantKey, new LinkedHashSet<>());
                        if (field == null) {
                            throw new IllegalArgumentException(
                                    "unknown class field key '" + constantKey + "' on " + named.name());
                        }
                        requireClassMemberVisible(
                                field.field().visibility(), field.owner(), "field", field.field().name());
                        Type pattern = classFieldType(field.owner(), field.field());
                        return substituteGenerics(
                                pattern, classGenericBindings(field.owner(), field.ownerType()));
                    }
                    List<Type> fields = new ArrayList<>();
                    for (ResolvedField field : effectiveFieldTargets(
                            klass, named, new LinkedHashSet<>())) {
                        // Arbitrary dynamic keys must not become a private-field
                        // side channel. Outside the declaring class, fail closed
                        // if the runtime key could select an inaccessible field.
                        requireClassMemberVisible(
                                field.field().visibility(), field.owner(), "field", field.field().name());
                        Type pattern = classFieldType(field.owner(), field.field());
                        fields.add(substituteGenerics(
                                pattern, classGenericBindings(field.owner(), field.ownerType())));
                    }
                    if (fields.isEmpty()) {
                        throw new IllegalArgumentException(
                                "class '" + named.name() + "' has no indexable instance fields");
                    }
                    return indexedElementUnion(fields);
                }
            }
            requireAssignable(index, Primitive.INT, "array/list/tuple index");
            if (receiver instanceof ListType list) return list.element();
            if (receiver instanceof Tuple tuple) {
                if (tuple.elements().isEmpty()) {
                    throw new IllegalArgumentException("cannot index an empty finite tuple");
                }
                Long constant = constantTupleIndex(indexed.index(), env);
                if (constant != null) {
                    if (constant < 0 || constant >= tuple.elements().size()) {
                        throw new IllegalArgumentException(
                                "tuple index " + constant + " outside arity " + tuple.elements().size());
                    }
                    return tuple.elements().get(constant.intValue());
                }
                return indexedElementUnion(tuple.elements());
            }
            throw new IllegalArgumentException(
                    "indexing requires an array/list, tuple, record/struct, or class instance field");
        }
        if (expr instanceof Ast.NewExpr created) {
            if (created.type().name().equals("HttpResponse") && findClass("HttpResponse") == null) {
                if (created.type().inferArguments() || !created.type().arguments().isEmpty()
                        || created.arguments().size() > 1) {
                    throw new IllegalArgumentException(
                            "HttpResponse constructor currently accepts zero or one string body; "
                            + "status/headers/streams need the checked Fetch response initializer");
                }
                if (!created.arguments().isEmpty()) {
                    requireAssignable(typeOf(created.arguments().getFirst(), env, generics, self),
                            Primitive.STRING, "HttpResponse body");
                }
                return new Named("HttpResponse", List.of());
            }
            // Guest Symbols are immutable keys, not fresh reference capabilities.
            // Do not silently reinterpret the historical STABLE constructor.
            if (created.type().name().equals("Symbol")) {
                throw new IllegalArgumentException("new Symbol(...) is not supported; use #name or Symbol.stable(uuid)");
            }
            if (created.type().name().equals("DynamicStruct")) {
                throw new IllegalArgumentException(
                        "DynamicStruct has been removed; use infer struct{...}, "
                                + "struct{field: Type,...}{...}, or an explicit Map<K,V> collection");
            }
            Ast.ClassDecl klass = findClass(created.type().name());
            if (klass == null) return resolve(created.type(), generics, self);
            rejectUnimportedClassNamespace(klass);
            if (klass.isAbstract()) {
                throw new IllegalArgumentException("cannot instantiate abstract class '" + klass.name() + "'");
            }
            if (klass.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalArgumentException("actor '" + klass.name()
                        + "' cannot be constructed with new; actor instances must be created through the actor runtime");
            }

            Named nominal;
            if (created.type().inferArguments()) {
                if (!created.type().arguments().isEmpty()) {
                    throw new IllegalArgumentException("inferred constructor type arguments must use an empty <> marker");
                }
                nominal = inferConstructedClassType(klass, created.arguments(), env, generics, self);
            } else {
                Type resolvedCreated = resolve(created.type(), generics, self);
                if (!(resolvedCreated instanceof Named named)) {
                    throw new IllegalArgumentException("constructor target must resolve to a named class type");
                }
                nominal = named;
            }

            Ast.ConstructorDecl constructor = klass.constructor();
            if (constructor != null) {
                if (created.arguments().size() != constructor.arity()) {
                    throw new IllegalArgumentException(
                            "constructor for " + klass.name() + " expects "
                                    + constructor.arity() + " argument(s), got "
                                    + created.arguments().size());
                }
                Map<String, Type> classBindings = classGenericBindings(klass, nominal);
                Set<String> classGenerics = Set.copyOf(klass.genericParameters());
                Type constructorSelf = nominalClassType(klass);
                for (int i = 0; i < constructor.parameters().size(); i++) {
                    Ast.Param param = constructor.parameters().get(i);
                    Type expectedPattern = resolveParam(param, classGenerics, constructorSelf);
                    Type expected = substituteGenerics(expectedPattern, classBindings);
                    Type actualArgument =
                            typeOf(created.arguments().get(i), env, generics, self);
                    requireReadonlyCapabilityPreserved(
                            actualArgument,
                            expected,
                            "constructor parameter " + param.name());
                    requireMutableReferentCompatible(
                            actualArgument,
                            param.mutable(),
                            "constructor parameter '" + param.name() + "'");
                    requireAssignable(
                            actualArgument,
                            expected,
                            "constructor parameter " + param.name());
                }
                return nominal;
            }

            // Backward-compatible synthesized constructor: when no explicit
            // constructor exists, positional arguments still initialize fields
            // in effective storage order and remaining fields use initializers.
            List<ResolvedField> fields = effectiveFieldTargets(klass, nominal, new LinkedHashSet<>());
            if (created.arguments().size() > fields.size()) throw new IllegalArgumentException("constructor for " + klass.name() + " received too many positional fields");
            for (int i = 0; i < fields.size(); i++) {
                ResolvedField resolvedField = fields.get(i);
                Ast.FieldDecl field = resolvedField.field();
                if (i < created.arguments().size()) {
                    Type fieldPattern = classFieldType(resolvedField.owner(), field);
                    Type expected = substituteGenerics(
                            fieldPattern,
                            classGenericBindings(resolvedField.owner(), resolvedField.ownerType()));
                    Type actualArgument =
                            typeOf(created.arguments().get(i), env, generics, self);
                    requireReadonlyCapabilityPreserved(
                            actualArgument,
                            expected,
                            "constructor field " + field.name());
                    requireAssignable(
                            actualArgument,
                            expected,
                            "constructor field " + field.name());
                } else if (field.initializer() == null) {
                    throw new IllegalArgumentException("constructor for " + klass.name() + " is missing field '" + field.name() + "'");
                }
            }
            return nominal;
        }
        if (expr instanceof Ast.TaskExpr task) {
            // A value-producing task needs contextual Future<T> information so
            // every return path can be checked without guessing a result type.
            // A no-value task is unambiguously Future<void>.
            if (taskHasValueReturn(task.body())) {
                throw new IllegalArgumentException(
                        "value-producing task blocks require a contextual Future<T> type; "
                                + "for example: let Future<int> t = cpu task { return 42; }");
            }
            checkTaskExpression(task, Primitive.VOID, env, generics, self);
            return new Named("Future", List.of(Primitive.VOID));
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            Type awaitedType = typeOf(awaited.expression(), env, generics, self);
            if (awaitedType instanceof Named named
                    && named.name().equals("Future")
                    && named.arguments().size() == 1) {
                return named.arguments().getFirst();
            }
            if (awaitedType instanceof Named actorRef
                    && actorRef.name().equals("ActorRef")) {
                // await spawn Actor() is a readiness barrier, not a termination
                // barrier. The expression retains its ActorRef<T> type.
                actorClassForRef(actorRef);
                return actorRef;
            }
            throw new IllegalArgumentException(
                    "await requires Future<T> or ActorRef<T>; got " + awaitedType);
        }
        if (expr instanceof Ast.ChannelOpExpr channelOp) {
            Type element = channelElementType(
                    typeOf(channelOp.channel(), env, generics, self),
                    channelOp.operation() == Ast.ChannelOperation.READ ? "readch" : "writech");

            if (channelOp.operation() == Ast.ChannelOperation.WRITE) {
                requireAssignable(
                        typeOf(channelOp.value(), env, generics, self),
                        element,
                        "writech value");
                if (channelOp.callback()) {
                    if (channelOp.mode() != Ast.WaitMode.NONBLOCKING) {
                        throw new IllegalArgumentException(
                                "channel callbacks require nonblocking writech");
                    }
                    if (currentActorKind == Ast.ActorKind.NONE) {
                        throw new IllegalArgumentException(
                                "nb cb writech requires an actor execution domain because its callback runs later");
                    }
                    checkBlock(
                            channelOp.callbackBody(),
                            new Env(env, env.descendantsNonLexical(), true),
                            generics,
                            Primitive.VOID,
                            self);
                    return Primitive.VOID;
                }
                return switch (channelOp.mode()) {
                    case BLOCKING -> Primitive.VOID;
                    case NONBLOCKING -> new Named("Future", List.of(Primitive.VOID));
                    case IMMEDIATE -> Primitive.BOOL;
                };
            }

            return switch (channelOp.mode()) {
                case BLOCKING -> element;
                case NONBLOCKING -> new Named("Future", List.of(element));
                case IMMEDIATE -> new Named("Option", List.of(element));
            };
        }
        if (expr instanceof Ast.MatchExpr valueMatch) {
            Type subject = deref(typeOf(valueMatch.subject(), env, generics, self));
            Type declaredResult = valueMatch.resultType() == null ? null
                    : resolve(valueMatch.resultType(), generics, self);
            if (declaredResult == Primitive.VOID || declaredResult == Unknown.INSTANCE) {
                throw new IllegalArgumentException(
                        "value match result parameter must be a known, non-void value type");
            }
            if (declaredResult instanceof Borrow) {
                // Inferred value matches already reject branch-local loan escape.
                // An explicit type must not circumvent that check by erasing &T.
                throw new IllegalArgumentException(
                        "value match cannot produce an explicit borrowed result until loan escape is proven");
            }
            Type result = declaredResult;
            List<Ast.MatchArm> proofArms = new ArrayList<>();
            Map<Env.Binding, Set<String>> before = env.initializationSnapshot();
            try {
                for (Ast.MatchValueArm arm : valueMatch.arms()) {
                    env.restoreInitialization(before);
                    Env armEnv = new Env(env);
                    checkPattern(arm.pattern(), subject, armEnv, generics, self);
                    if (arm.guard() != null) {
                        requireAssignable(
                                typeOfCondition(arm.guard(), armEnv, generics, self),
                                Primitive.BOOL,
                                "value match guard");
                    }
                    Type actual = declaredResult == null
                            ? typeOf(arm.value(), armEnv, generics, self)
                            : typeOfAgainstExpected(arm.value(), declaredResult, armEnv, generics, self);
                    if (actual == Primitive.VOID || actual == Unknown.INSTANCE) {
                        throw new IllegalArgumentException(
                                "value match arms must return a statically known non-void value");
                    }
                    if (declaredResult != null) {
                        // An explicit result type constrains each arm, but
                        // cannot erase readonly/ownership capabilities.
                        requireReadonlyCapabilityPreserved(actual, declaredResult, "value match arm");
                        requireAssignable(actual, declaredResult, "value match arm");
                    } else if (result == null) {
                        result = actual;
                    } else {
                        // Preserve the existing invariant inference contract
                        // when no result parameter was supplied.
                        requireReadonlyCapabilityPreserved(actual, result, "value match arm");
                        requireAssignable(actual, result, "value match arm");
                        requireAssignable(result, actual, "value match arm");
                    }
                    proofArms.add(new Ast.MatchArm(
                            arm.pattern(), arm.guard(),
                            List.of(new Ast.ReturnStmt(arm.value()))));
                }
                // Reuse the canonical finite-domain reachability and
                // exhaustive-pattern checker. These arm returns are checked
                // against MatchExpr's result, not the enclosing function.
                checkMatch(
                        new Ast.MatchStmt(valueMatch.subject(), true, proofArms),
                        subject, env, generics, result, self);
                return result;
            } finally {
                // Only the selected arm executes at runtime. Type-checking
                // all branches cannot establish outer definite-init facts.
                env.restoreInitialization(before);
            }
        }
        if (expr instanceof Ast.DynamicSelectExpr selected) {
            // Dynamic select accepts a SelectSet directly or a runtime
            // list/map of SelectCase values. The exact case element type may
            // remain Unknown until collection generic constraints are richer.
            typeOf(selected.cases(), env, generics, self);
            Type result = new Named("SelectResult", List.of());
            Type optional = new Named("Option", List.of(result));
            return switch (selected.mode()) {
                case BLOCKING, IMMEDIATE -> optional;
                case NONBLOCKING -> new Named("Future", List.of(optional));
            };
        }
        if (expr instanceof Ast.ListExpr list) {
            if (list.elements().isEmpty()) return new ListType(Unknown.INSTANCE);
            Type element = widenCollectionElement(typeOf(list.elements().getFirst(), env, generics, self));
            for (int i = 1; i < list.elements().size(); i++) {
                element = collectionElementJoin(element, widenCollectionElement(typeOf(list.elements().get(i), env, generics, self)));
            }
            return new ListType(element);
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            return new Tuple(tuple.elements().stream().map(item -> typeOf(item, env, generics, self)).toList());
        }
        if (expr instanceof Ast.ObjectExpr object) {
            if (object.explicitStruct()) {
                Map<String, Type> members = new LinkedHashMap<>();
                for (Ast.StructFieldSpec field : object.declaredFields()) {
                    Type declared = resolve(field.type(), generics, self);
                    if (declared == Primitive.VOID) {
                        throw new IllegalArgumentException(
                                "explicit struct field '" + field.name() + "' cannot have type void");
                    }
                    if (members.putIfAbsent(field.name(), declared) != null) {
                        throw new IllegalArgumentException(
                                "duplicate explicit struct field '" + field.name() + "'");
                    }
                }

                Set<String> initialized = new LinkedHashSet<>();
                for (Ast.ObjectField field : object.fields()) {
                    Type expected = members.get(field.name());
                    if (expected == null) {
                        throw new IllegalArgumentException(
                                "explicit struct initializer contains undeclared field '" + field.name() + "'");
                    }
                    if (!initialized.add(field.name())) {
                        throw new IllegalArgumentException(
                                "duplicate explicit struct initializer field '" + field.name() + "'");
                    }
                    Type actualField =
                            typeOf(field.value(), env, generics, self);
                    requireReadonlyCapabilityPreserved(
                            actualField,
                            expected,
                            "explicit struct field '" + field.name() + "'");
                    requireAssignable(
                            actualField,
                            expected,
                            "explicit struct field '" + field.name() + "'");
                }

                Set<String> missing = new LinkedHashSet<>(members.keySet());
                missing.removeAll(initialized);
                if (!missing.isEmpty() && object != permittedPartialStructInitializer) {
                    throw new IllegalArgumentException(
                            "explicit struct literal is missing declared fields " + missing
                                    + "; partial initialization is only allowed as the direct initializer "
                                    + "of a mutable binding");
                }
                return Record.struct(members);
            }

            Map<String, Type> members = new LinkedHashMap<>();
            for (Ast.ObjectField field : object.fields()) {
                Type exactValueType = typeOf(field.value(), env, generics, self);
                if (members.putIfAbsent(
                        field.name(),
                        object.inferredFieldTypes()
                                ? widenCollectionElement(exactValueType)
                                : exactValueType) != null) {
                    throw new IllegalArgumentException(
                            "duplicate struct field '" + field.name() + "'");
                }
            }
            if (object.structuralRecord()) return new Record(members, true, AggregateKind.RECORD);
            if (object.inferredStruct()) return Record.struct(members);
            throw new IllegalArgumentException("unknown anonymous aggregate kind " + object.kind());
        }
        if (expr instanceof Ast.LambdaExpr lambda) {
            boolean nonLexical = lambda.nonLexical() || env.descendantsNonLexical();
            Env lambdaEnv = new Env(nonLexical ? null : env, nonLexical, !nonLexical);
            List<Type> parameters = new ArrayList<>();
            for (Ast.Param param : lambda.parameters()) {
                Type type = resolveParam(param, generics, self);
                parameters.add(type);
                lambdaEnv.define(param.name(), type, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            defineImplicitArguments(lambdaEnv, parameters);
            Ast.ClassDecl previousClassOwner = currentClassOwner;
            Ast.ClassDecl previousConstructorOwner = currentConstructorOwner;
            Map<Env.Binding, Set<String>> outerInitialization = env.initializationSnapshot();
            if (nonLexical) currentClassOwner = null;
            // A lambda checked within a constructor runs on invocation, not
            // necessarily during initialization. It must never inherit the
            // constructor's one-time fixed-field initialization privilege.
            currentConstructorOwner = null;
            Type result;
            try {
                if (lambda.expressionBody() != null) {
                    result = typeOf(lambda.expressionBody(), lambdaEnv, generics, self);
                } else {
                    // A one-expression return block has an unambiguous result
                    // type. Infer it without inventing a return type for more
                    // complex control flow, and still check the complete block.
                    // This preserves source-level |args| -> { return expr; }
                    // as a callable whose result can be used in typed code.
                    if (lambda.blockBody().size() == 1
                            && lambda.blockBody().getFirst() instanceof Ast.ReturnStmt returned
                            && returned.value() != null) {
                        Type inferredReturn = typeOf(returned.value(), lambdaEnv, generics, self);
                        checkCallableBlock(lambda.blockBody(), lambdaEnv, generics, inferredReturn, self);
                        result = inferredReturn;
                    } else {
                        checkCallableBlock(lambda.blockBody(), lambdaEnv, generics, Unknown.INSTANCE, self);
                        result = Unknown.INSTANCE;
                    }
                }
            } finally {
                // Lambda bodies are checked now but execute later. Never let
                // assignments inside a closure establish definite-init facts
                // for the enclosing flow merely because the closure exists.
                env.restoreInitialization(outerInitialization);
                currentClassOwner = previousClassOwner;
                currentConstructorOwner = previousConstructorOwner;
            }
            return new Function(parameters, result);
        }
        return Unknown.INSTANCE;
    }

    private Type channelElementType(Type channel, String where) {
        channel = deref(channel);
        if (channel == Unknown.INSTANCE) return Unknown.INSTANCE;
        if (channel instanceof Named named
                && named.name().equals("Channel")
                && named.arguments().size() == 1) {
            Type element = named.arguments().getFirst();
            validateChannelPayloadType(element, where);
            return element;
        }
        throw new IllegalArgumentException(
                where + " requires Channel<T>; got " + channel);
    }

    private void validateChannelPayloadType(Type element, String where) {
        if (element == Primitive.VOID) {
            throw new IllegalArgumentException(
                    "Channel<void> cannot carry a value; use an explicit signal/unit type");
        }
        // Ordinary Channel<T> is an execution-domain-local capability, not a
        // mailbox. Owned function values and objects containing callables may
        // move through local channels. Actor message types are checked by
        // validateActorCallableBoundaryType/isSharedSafe instead and remain
        // strictly non-callable. Borrows cannot inhabit buffered channels
        // until channel-bound lifetimes are expressible and checked.
        if (containsChannelBorrow(element, new LinkedHashSet<>(), Map.of())) {
            throw new IllegalArgumentException(
                    where + " cannot transport borrowed payloads, including nested fields: "
                            + "channel storage may outlive their owners");
        }
        if (element == Unknown.INSTANCE) {
            throw new IllegalArgumentException(where + " requires a statically resolved channel payload type");
        }
    }

    private boolean containsChannelCallable(
            Type type,
            Set<Ast.ClassDecl> seen,
            Map<String, Type> genericBindings) {
        type = deref(type);
        if (type instanceof Function) return true;
        if (type instanceof SelfType receiverSelf) {
            return containsChannelCallable(receiverSelf.bound(), seen, genericBindings);
        }
        if (type instanceof Borrow borrow) {
            return containsChannelCallable(borrow.target(), seen, genericBindings);
        }
        if (type instanceof ListType list) {
            return containsChannelCallable(list.element(), seen, genericBindings);
        }
        if (type instanceof Tuple tuple) {
            return tuple.elements().stream()
                    .anyMatch(element -> containsChannelCallable(element, seen, genericBindings));
        }
        if (type instanceof Union union) {
            return union.options().stream()
                    .anyMatch(option -> containsChannelCallable(option, seen, genericBindings));
        }
        if (type instanceof Record record) {
            return record.members().values().stream()
                    .anyMatch(member -> containsChannelCallable(member, seen, genericBindings));
        }
        if (type instanceof Generic generic) {
            Type bound = genericBindings.get(generic.name());
            return bound != null
                    && bound != type
                    && containsChannelCallable(bound, seen, genericBindings);
        }
        if (!(type instanceof Named named)) return false;

        for (Type argument : named.arguments()) {
            if (containsChannelCallable(argument, seen, genericBindings)) return true;
        }

        Ast.ClassDecl klass = findClass(named.name());
        if (klass == null || !seen.add(klass)) return false;
        try {
            if (named.arguments().size() != klass.genericParameters().size()) return false;
            Map<String, Type> classBindings = new HashMap<>(genericBindings);
            for (int i = 0; i < klass.genericParameters().size(); i++) {
                classBindings.put(
                        klass.genericParameters().get(i),
                        resolveSharedGeneric(named.arguments().get(i), genericBindings));
            }

            for (Ast.FieldDecl field : klass.fields()) {
                Type fieldType = resolveSharedGeneric(classFieldType(klass, field), classBindings);
                if (containsChannelCallable(fieldType, seen, classBindings)) return true;
            }

            Set<String> classGenerics = Set.copyOf(klass.genericParameters());
            Type nominal = nominalClassType(klass);
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Type parentType = resolveSharedGeneric(
                        resolve(parentRef, classGenerics, nominal),
                        classBindings);
                if (containsChannelCallable(parentType, seen, classBindings)) return true;
            }
            return false;
        } finally {
            seen.remove(klass);
        }
    }

    /** Traverses nested channel payloads, including class fields and generic substitutions.
     * A local channel cannot retain any borrowed value while its buffer/registration
     * may outlive the borrow's lexical scope.
     */
    private boolean containsChannelBorrow(
            Type type,
            Set<Ast.ClassDecl> seen,
            Map<String, Type> genericBindings) {
        // Check the borrow wrapper BEFORE deref can erase lifetime metadata.
        if (type instanceof Borrow) return true;
        type = deref(type);
        if (type instanceof SelfType receiverSelf) {
            return containsChannelBorrow(receiverSelf.bound(), seen, genericBindings);
        }
        if (type instanceof ListType list) {
            return containsChannelBorrow(list.element(), seen, genericBindings);
        }
        if (type instanceof Tuple tuple) {
            return tuple.elements().stream()
                    .anyMatch(element -> containsChannelBorrow(element, seen, genericBindings));
        }
        if (type instanceof Union union) {
            return union.options().stream()
                    .anyMatch(option -> containsChannelBorrow(option, seen, genericBindings));
        }
        if (type instanceof Record record) {
            return record.members().values().stream()
                    .anyMatch(member -> containsChannelBorrow(member, seen, genericBindings));
        }
        if (type instanceof Generic generic) {
            Type bound = genericBindings.get(generic.name());
            return bound != null
                    && bound != type
                    && containsChannelBorrow(bound, seen, genericBindings);
        }
        if (!(type instanceof Named named)) return false;

        for (Type argument : named.arguments()) {
            if (containsChannelBorrow(argument, seen, genericBindings)) return true;
        }

        Ast.ClassDecl klass = findClass(named.name());
        if (klass == null || !seen.add(klass)) return false;
        try {
            if (named.arguments().size() != klass.genericParameters().size()) return false;
            Map<String, Type> classBindings = new HashMap<>(genericBindings);
            for (int i = 0; i < klass.genericParameters().size(); i++) {
                classBindings.put(
                        klass.genericParameters().get(i),
                        resolveSharedGeneric(named.arguments().get(i), genericBindings));
            }

            for (Ast.FieldDecl field : klass.fields()) {
                Type fieldType = resolveSharedGeneric(classFieldType(klass, field), classBindings);
                if (containsChannelBorrow(fieldType, seen, classBindings)) return true;
            }

            Set<String> classGenerics = Set.copyOf(klass.genericParameters());
            Type nominal = nominalClassType(klass);
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Type parentType = resolveSharedGeneric(
                        resolve(parentRef, classGenerics, nominal),
                        classBindings);
                if (containsChannelBorrow(parentType, seen, classBindings)) return true;
            }
            return false;
        } finally {
            seen.remove(klass);
        }
    }

    private static boolean isBuiltinStdoutCall(Ast.CallExpr call, String memberName, Env env) {
        if (!(call.callee() instanceof Ast.MemberExpr member) || !member.member().equals(memberName)) return false;
        if (!(member.receiver() instanceof Ast.MemberExpr stdout) || !stdout.member().equals("stdout")) return false;
        return stdout.receiver() instanceof Ast.NameExpr stdio
                && stdio.name().equals("stdio")
                && env.lookup("stdio") == null;
    }

    private void requireSpreadable(Type type, String operation) {
        type = deref(type);
        if (type instanceof ListType || type instanceof Tuple) return;
        throw new IllegalArgumentException(operation + " requires an array/list/tuple value");
    }

    private static boolean isBooleanIntrinsicName(String name) {
        return name.equals("And") || name.equals("Or") || name.equals("Xor");
    }

    private String booleanIntrinsicName(Ast.CallExpr call, Env env) {
        if (call.callee() instanceof Ast.NameExpr name
                && isBooleanIntrinsicName(name.name())
                && env.lookup(name.name()) == null
                && !hasFunctionFamily(name.name())) {
            return name.name();
        }
        if (call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr namespace
                && namespace.name().equals("BooleanOps")
                && isBooleanIntrinsicName(member.member())
                && env.lookup("BooleanOps") == null
                && !modules.containsKey("BooleanOps")) {
            return member.member();
        }
        return null;
    }

    private Type typeBooleanIntrinsicCall(
            String name,
            Ast.CallExpr call,
            Env env,
            Set<String> generics,
            Type self) {
        if (call.typeArgumentsPresent()) {
            throw new IllegalArgumentException(name + " does not accept call-site type arguments");
        }
        List<Ast.Expr> arguments = call.arguments();
        if (arguments.size() == 1) {
            Type aggregate = deref(typeOf(arguments.getFirst(), env, generics, self));
            if (!(aggregate instanceof ListType list)) {
                throw new IllegalArgumentException(
                        name + " arity 1 selects the List<bool> overload; got " + aggregate);
            }
            Type element = deref(list.element());
            if (element != Unknown.INSTANCE) {
                requireAssignable(element, Primitive.BOOL, name + " list element");
            }
            return Primitive.BOOL;
        }
        if (arguments.size() < 2) {
            throw new IllegalArgumentException(
                    name + " expects either one List<bool> argument or at least two bool arguments");
        }
        for (int i = 0; i < arguments.size(); i++) {
            requireAssignable(
                    typeOf(arguments.get(i), env, generics, self),
                    Primitive.BOOL,
                    name + " scalar argument " + (i + 1));
        }
        return Primitive.BOOL;
    }

    private Type checkTaskExpression(
            Ast.TaskExpr task,
            Type result,
            Env env,
            Set<String> generics,
            Type self) {
        if (task.domain() == Ast.ExecutionDomain.GPU) {
            throw new IllegalArgumentException(
                    "gpu task is parsed and typed as an execution-domain boundary, "
                            + "but physical GPU dispatch is not enabled on this backend; "
                            + "no implicit CPU fallback");
        }
        if (currentActorKind != Ast.ActorKind.NONE) {
            throw new IllegalArgumentException(
                    "task blocks inside actor state are not enabled until captured actor-state "
                            + "authority is proven; launch a task from ordinary owned data instead");
        }
        validateAsyncBoundaryType(result, true, "CPU task result");

        Map<Env.Binding, Set<String>> before = env.initializationSnapshot();
        boolean previousGenerator = currentGenerator;
        boolean previousAsync = currentAsyncCallable;
        boolean previousMaySuspend = currentMaySuspend;
        try {
            Env taskEnv = new Env(env, env.descendantsNonLexical(), true);
            currentGenerator = false;
            currentAsyncCallable = true;
            currentMaySuspend = true;
            checkBlock(task.body(), taskEnv, generics, result, self);
            if (result != Primitive.VOID && !definitelyReturns(task.body())) {
                throw new IllegalArgumentException(
                        "non-void CPU task must explicitly return on every path");
            }
        } finally {
            env.restoreInitialization(before);
            currentGenerator = previousGenerator;
            currentAsyncCallable = previousAsync;
            currentMaySuspend = previousMaySuspend;
        }
        return new Named("Future", List.of(result));
    }

    private boolean taskHasValueReturn(List<Ast.Stmt> body) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt ret && ret.value() != null) return true;
            if (stmt instanceof Ast.BlockStmt block && taskHasValueReturn(block.body())) return true;
            if (stmt instanceof Ast.TaskScopeStmt scope && taskHasValueReturn(scope.body())) return true;
            if (stmt instanceof Ast.LoopStmt loop && taskHasValueReturn(loop.body())) return true;
            if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    if (taskHasValueReturn(branch.body())) return true;
                }
                if (taskHasValueReturn(conditional.elseBody())) return true;
            } else if (stmt instanceof Ast.MatchStmt matched) {
                for (Ast.MatchArm arm : matched.arms()) {
                    if (taskHasValueReturn(arm.body())) return true;
                }
            } else if (stmt instanceof Ast.SwitchStmt switched) {
                for (Ast.SwitchCase arm : switched.cases()) {
                    if (taskHasValueReturn(arm.body())) return true;
                }
                if (taskHasValueReturn(switched.defaultBody())) return true;
            } else if (stmt instanceof Ast.TryStmt attempted) {
                if (taskHasValueReturn(attempted.body())
                        || taskHasValueReturn(attempted.catchBody())
                        || taskHasValueReturn(attempted.finallyBody())) return true;
            }
        }
        return false;
    }

    private Type typeOfAgainstExpected(Ast.Expr expr, Type expected, Env env, Set<String> generics, Type self) {
        if (expr instanceof Ast.TaskExpr task
                && expected instanceof Named future
                && future.name().equals("Future")
                && future.arguments().size() == 1) {
            Type result = future.arguments().getFirst();
            checkTaskExpression(task, result, env, generics, self);
            return future;
        }
        if (expr instanceof Ast.LambdaExpr lambda && expected instanceof Function fn) {
            validateLambdaAgainstExpected(lambda, fn, env, generics, self);
            return fn;
        }
        if (expr instanceof Ast.MemberExpr member && expected instanceof Function fn) {
            Type contextual = contextualCallableMemberType(member, fn, env, generics, self);
            if (contextual != null) return contextual;
        }
        if (expr instanceof Ast.ListExpr list) {
            if (expected instanceof Tuple tupleExpected) {
                return new Tuple(list.elements().stream().map(item -> typeOf(item, env, generics, self)).toList(),
                        tupleExpected.kind());
            }
            if (expected instanceof Union union
                    && union.options().stream().allMatch(option -> option instanceof Tuple)) {
                List<TupleKind> kinds = union.options().stream()
                        .map(option -> ((Tuple) option).kind())
                        .distinct()
                        .toList();
                if (kinds.size() == 1) {
                    return new Tuple(
                            list.elements().stream().map(item -> typeOf(item, env, generics, self)).toList(),
                            kinds.getFirst());
                }
            }
        }
        return typeOf(expr, env, generics, self);
    }

    /**
     * Resolve only static class functions as first-class callable values
     * against an expected function type. Instance methods require a receiver
     * and are never implicitly reified as callbacks, even with expected arity.
     * Callers wanting capture semantics must write an explicit lambda.
     */
    private Type contextualCallableMemberType(
            Ast.MemberExpr member,
            Function expected,
            Env env,
            Set<String> generics,
            Type self) {
        Type receiver = typeOf(member.receiver(), env, generics, self);
        Type receiverValueType = deref(unwrapMutexGuard(receiver));
        Type normalized = receiverDispatchType(receiverValueType);
        int arity = expected.parameters().size();

        if (normalized instanceof ClassNamespace classNamespace) {
            Ast.ClassDecl klass = findClass(classNamespace.className());
            if (klass == null) return null;
            Ast.MethodDecl fn = findStaticFunction(
                    klass, member.member(), arity, new LinkedHashSet<>());
            if (fn == null) {
                if (!findStaticFunctionsByName(klass, member.member(), new LinkedHashSet<>()).isEmpty()) {
                    throw new IllegalArgumentException(
                            "no overload of static function '" + klass.name() + "." + member.member()
                                    + "' matches callback arity " + arity);
                }
                return null;
            }
            if (!fn.genericParameters().isEmpty()) {
                throw new IllegalArgumentException(
                        "generic static function '" + klass.name() + "." + fn.name()
                                + "' must be specialized by a direct call; polymorphic function values are not supported yet");
            }
            requireClassMemberVisible(fn.visibility(), findDeclaringClass(klass, fn, new LinkedHashSet<>()), "static function", fn.name());
            return functionType(fn.parameters(), fn.returnType(), fn.async(), false, Set.of(), null);
        }

        // An instance method always requires its receiver. Even an expected
        // Function type must not implicitly capture self or turn a method into
        // a bound callback. An explicit lambda is the opt-in capture mechanism.

        return null;
    }

    private boolean containsReadonlyRecordCapability(Type type) {
        Type concrete = deref(type);
        if (concrete instanceof Record record) return record.readOnly();
        if (concrete instanceof Union union) {
            return union.options().stream().anyMatch(this::containsReadonlyRecordCapability);
        }
        return false;
    }

    private Type overlayReadonlyCapabilities(Type actual, Type expected) {
        if (actual instanceof Borrow actualBorrow && expected instanceof Borrow expectedBorrow) {
            return new Borrow(
                    overlayReadonlyCapabilities(actualBorrow.target(), expectedBorrow.target()),
                    expectedBorrow.mutable());
        }
        Type source = deref(actual);
        Type target = deref(expected);

        if (source instanceof Record sourceRecord && target instanceof Record targetRecord) {
            Map<String, Type> members = new LinkedHashMap<>();
            for (Map.Entry<String, Type> entry : targetRecord.members().entrySet()) {
                Type sourceMember = sourceRecord.members().get(entry.getKey());
                members.put(
                        entry.getKey(),
                        sourceMember == null
                                ? entry.getValue()
                                : overlayReadonlyCapabilities(sourceMember, entry.getValue()));
            }
            if (!aggregateKindsCompatible(sourceRecord.kind(), targetRecord.kind())) return expected;
            return new Record(
                    members,
                    sourceRecord.readOnly() || targetRecord.readOnly(),
                    targetRecord.kind());
        }
        if (source instanceof ListType sourceList && target instanceof ListType targetList) {
            return new ListType(overlayReadonlyCapabilities(sourceList.element(), targetList.element()));
        }
        if (source instanceof Tuple sourceTuple && target instanceof Tuple targetTuple
                && sourceTuple.kind() == targetTuple.kind()
                && sourceTuple.elements().size() == targetTuple.elements().size()) {
            List<Type> elements = new ArrayList<>(targetTuple.elements().size());
            for (int i = 0; i < targetTuple.elements().size(); i++) {
                elements.add(overlayReadonlyCapabilities(
                        sourceTuple.elements().get(i), targetTuple.elements().get(i)));
            }
            return new Tuple(elements, targetTuple.kind());
        }
        if (source instanceof Named sourceNamed && target instanceof Named targetNamed
                && sourceNamed.name().equals(targetNamed.name())
                && sourceNamed.arguments().size() == targetNamed.arguments().size()) {
            List<Type> arguments = new ArrayList<>(targetNamed.arguments().size());
            for (int i = 0; i < targetNamed.arguments().size(); i++) {
                arguments.add(overlayReadonlyCapabilities(
                        sourceNamed.arguments().get(i), targetNamed.arguments().get(i)));
            }
            return new Named(targetNamed.name(), arguments);
        }
        if (target instanceof Union targetUnion) {
            List<Type> options = new ArrayList<>(targetUnion.options().size());
            for (Type targetOption : targetUnion.options()) {
                Type overlaid = targetOption;
                if (source instanceof Union sourceUnion) {
                    for (Type sourceOption : sourceUnion.options()) {
                        if (typesMayOverlap(sourceOption, targetOption)) {
                            overlaid = overlayReadonlyCapabilities(sourceOption, overlaid);
                        }
                    }
                } else if (typesMayOverlap(source, targetOption)) {
                    overlaid = overlayReadonlyCapabilities(source, targetOption);
                }
                options.add(overlaid);
            }
            return Types.unionOf(options);
        }
        if (source instanceof Function sourceFunction && target instanceof Function targetFunction
                && sourceFunction.parameters().size() == targetFunction.parameters().size()) {
            return new Function(
                    targetFunction.parameters(),
                    targetFunction.mutableParameters(),
                    targetFunction.async(),
                    overlayReadonlyCapabilities(sourceFunction.result(), targetFunction.result()));
        }
        return expected;
    }

    private boolean readonlyCapabilityErased(Type actual, Type expected) {
        if (actual instanceof Borrow actualBorrow && expected instanceof Borrow expectedBorrow) {
            return readonlyCapabilityErased(actualBorrow.target(), expectedBorrow.target());
        }
        Type source = deref(actual);
        Type target = deref(expected);

        if (source instanceof Record sourceRecord && target instanceof Record targetRecord) {
            if (sourceRecord.readOnly() && !targetRecord.readOnly()) return true;
            for (Map.Entry<String, Type> entry : targetRecord.members().entrySet()) {
                Type sourceMember = sourceRecord.members().get(entry.getKey());
                if (sourceMember != null
                        && readonlyCapabilityErased(sourceMember, entry.getValue())) return true;
            }
            return false;
        }
        if (source instanceof ListType sourceList && target instanceof ListType targetList) {
            return readonlyCapabilityErased(sourceList.element(), targetList.element());
        }
        if (source instanceof Tuple sourceTuple && target instanceof Tuple targetTuple
                && sourceTuple.kind() == targetTuple.kind()
                && sourceTuple.elements().size() == targetTuple.elements().size()) {
            for (int i = 0; i < sourceTuple.elements().size(); i++) {
                if (readonlyCapabilityErased(
                        sourceTuple.elements().get(i), targetTuple.elements().get(i))) return true;
            }
            return false;
        }
        if (source instanceof Named sourceNamed && target instanceof Named targetNamed
                && sourceNamed.name().equals(targetNamed.name())
                && sourceNamed.arguments().size() == targetNamed.arguments().size()) {
            for (int i = 0; i < sourceNamed.arguments().size(); i++) {
                if (readonlyCapabilityErased(
                        sourceNamed.arguments().get(i), targetNamed.arguments().get(i))) return true;
            }
            return false;
        }
        if (source instanceof Union sourceUnion) {
            return sourceUnion.options().stream()
                    .anyMatch(option -> readonlyCapabilityErased(option, target));
        }
        if (target instanceof Union targetUnion) {
            return targetUnion.options().stream()
                    .filter(option -> typesMayOverlap(source, option))
                    .anyMatch(option -> readonlyCapabilityErased(source, option));
        }
        if (source instanceof Function sourceFunction && target instanceof Function targetFunction
                && sourceFunction.parameters().size() == targetFunction.parameters().size()) {
            if (readonlyCapabilityErased(sourceFunction.result(), targetFunction.result())) return true;
            for (int i = 0; i < sourceFunction.parameters().size(); i++) {
                if (readonlyCapabilityErased(
                        targetFunction.parameters().get(i), sourceFunction.parameters().get(i))) return true;
            }
        }
        return false;
    }

    private void requireReadonlyCapabilityPreserved(Type actual, Type expected, String where) {
        if (readonlyCapabilityErased(actual, expected)) {
            throw new IllegalArgumentException(
                    where + " would erase readonly record capability; "
                            + "use a generic/capability-preserving type or an explicit mutable struct instead");
        }
    }

    private void requireMutableReferentCompatible(
            Type type,
            boolean mutableReferent,
            String where) {
        if (!mutableReferent) return;
        Type concrete = deref(type);
        if (concrete instanceof Record record && record.readOnly()) {
            throw new IllegalArgumentException(
                    where + " cannot request mutable access to a readonly record value; "
                            + "records are readonly structural values");
        }
        if (concrete instanceof Union union
                && union.options().stream().anyMatch(option ->
                        deref(option) instanceof Record record && record.readOnly())) {
            throw new IllegalArgumentException(
                    where + " cannot request mutable access because the value may be a readonly record");
        }
    }

    private void defineDestructureBinding(Env env, Ast.DestructureBinding binding, Type type) {
        if (binding.isDiscard()) return;
        requireMutableReferentCompatible(
                type, binding.mutableReferent(),
                "destructure binding '" + binding.name() + "'");
        env.define(binding.name(), type, binding.kind());
    }

    private List<Type> sequenceDestructureTypes(Type source, List<Ast.DestructureBinding> bindings) {
        source = sequenceDestructureSourceType(deref(source));
        int restIndex = restBindingIndex(bindings);
        int fixedArity = restIndex >= 0 ? restIndex : bindings.size();

        if (source instanceof Tuple tuple) {
            if (restIndex < 0 && tuple.elements().size() != fixedArity) {
                throw new IllegalArgumentException("destructure arity mismatch: tuple has " + tuple.elements().size()
                        + " element(s), pattern has " + fixedArity);
            }
            if (restIndex >= 0 && tuple.elements().size() < fixedArity) {
                throw new IllegalArgumentException("destructure arity mismatch: tuple has " + tuple.elements().size()
                        + " element(s), pattern requires at least " + fixedArity + " before rest");
            }

            List<Type> result = new ArrayList<>(bindings.size());
            for (int i = 0; i < fixedArity; i++) result.add(tuple.elements().get(i));
            if (restIndex >= 0) {
                result.add(new Tuple(tuple.elements().subList(fixedArity, tuple.elements().size()), tuple.kind()));
            }
            return List.copyOf(result);
        }

        if (source instanceof ListType list) {
            List<Type> result = new ArrayList<>(bindings.size());
            for (int i = 0; i < fixedArity; i++) result.add(list.element());
            if (restIndex >= 0) result.add(new ListType(list.element()));
            return List.copyOf(result);
        }

        if (source instanceof Union union) {
            List<List<Type>> alternatives = new ArrayList<>(union.options().size());
            for (Type option : union.options()) {
                alternatives.add(sequenceDestructureTypes(option, bindings));
            }
            List<Type> joined = new ArrayList<>(bindings.size());
            for (int i = 0; i < bindings.size(); i++) {
                final int slot = i;
                joined.add(Types.unionOf(alternatives.stream().map(items -> items.get(slot)).toList()));
            }
            return List.copyOf(joined);
        }

        throw new IllegalArgumentException(
                "sequence destructuring requires a tuple, array/list, or statically typed iterable value");
    }

    private Type sequenceDestructureSourceType(Type source) {
        if (!(source instanceof Named named)) return source;
        Ast.ClassDecl klass = findClass(named.name());
        if (klass == null) return source;

        ResolvedMethod iteratorTarget =
                findMethodTarget(klass, named, "Symbol.iterator", 0, new LinkedHashSet<>());
        if (iteratorTarget == null) return source;

        Ast.MethodDecl iterator = iteratorTarget.method();
        requireClassMemberVisible(
                iterator.visibility(),
                iteratorTarget.owner(),
                "method",
                iterator.name());

        Set<String> iteratorGenerics = new HashSet<>(iteratorTarget.owner().genericParameters());
        iteratorGenerics.addAll(iterator.genericParameters());
        Type result = resolve(iterator.returnType(), iteratorGenerics, iteratorTarget.ownerType());
        result = substituteGenerics(
                result,
                classGenericBindings(iteratorTarget.owner(), iteratorTarget.ownerType()));

        if (result instanceof ListType || result instanceof Tuple) return result;
        throw new IllegalArgumentException(
                "[Symbol.iterator]() used for destructuring must return Array<T>, List<T>, or a finite tuple");
    }

    private int restBindingIndex(List<Ast.DestructureBinding> bindings) {
        for (int i = 0; i < bindings.size(); i++) {
            if (bindings.get(i).rest()) return i;
        }
        return -1;
    }

    private Type objectDestructureMemberType(Type source, String memberName) {
        source = deref(source);
        if (source instanceof Record record) {
            Type member = record.members().get(memberName);
            if (member == null) throw new IllegalArgumentException("object destructure requires member '" + memberName + "'");
            return member;
        }
        if (source instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass == null) throw new IllegalArgumentException("object destructuring requires a record/map-like value");
            ResolvedField field = findFieldTarget(
                    klass, named, memberName, new LinkedHashSet<>());
            if (field == null) {
                throw new IllegalArgumentException(
                        "object destructure requires field '" + memberName + "'");
            }
            requireClassMemberVisible(
                    field.field().visibility(), field.owner(), "field", field.field().name());
            Type pattern = classFieldType(field.owner(), field.field());
            return substituteGenerics(
                    pattern,
                    classGenericBindings(field.owner(), field.ownerType()));
        }
        if (source instanceof Union union) {
            List<Type> alternatives = new ArrayList<>(union.options().size());
            for (Type option : union.options()) alternatives.add(objectDestructureMemberType(option, memberName));
            return Types.unionOf(alternatives);
        }
        if (source == Unknown.INSTANCE) return Unknown.INSTANCE;
        throw new IllegalArgumentException("object destructuring requires a record/map-like value");
    }

    private Type objectDestructureRestType(Type source, Set<String> selectedMembers) {
        source = deref(source);
        if (source instanceof Record record) {
            Map<String, Type> remainder = new LinkedHashMap<>();
            for (Map.Entry<String, Type> member : indexableRecordMembers(record).entrySet()) {
                if (!selectedMembers.contains(member.getKey())) {
                    remainder.put(member.getKey(), member.getValue());
                }
            }
            if (record.kind() == AggregateKind.STRUCT
                    || record.kind() == AggregateKind.CONTRACT) {
                // Removing fields destroys concrete struct identity, and an
                // internal method-bearing contract must never escape as data.
                return new Record(remainder, true, AggregateKind.RECORD);
            }
            return new Record(remainder, record.readOnly(), AggregateKind.RECORD);
        }
        if (source instanceof Union union) {
            List<Type> alternatives = new ArrayList<>(union.options().size());
            for (Type option : union.options()) {
                alternatives.add(objectDestructureRestType(option, selectedMembers));
            }
            return Types.unionOf(alternatives);
        }
        throw new IllegalArgumentException(
                "object rest destructuring requires a statically known record type");
    }

    private void validateMutexCallback(Ast.LambdaExpr lambda, Type expectedParameter, Env parent, Set<String> generics, Type self) {
        if (lambda.parameters().size() != 1) {
            throw new IllegalArgumentException("mutex callback must accept exactly one protected-value parameter");
        }
        Ast.Param param = lambda.parameters().getFirst();
        Type declared = param.type().name().equals("$infer$") ? expectedParameter : resolveParam(param, generics, self);
        requireAssignable(expectedParameter, declared, "mutex callback parameter " + param.name());
        requireAssignable(declared, expectedParameter, "mutex callback parameter " + param.name());
        boolean nonLexical = lambda.nonLexical() || parent.descendantsNonLexical();
        Env lambdaEnv = new Env(nonLexical ? null : parent, nonLexical, !nonLexical);
        lambdaEnv.define(param.name(), declared, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        defineImplicitArguments(lambdaEnv, List.of(declared));
        Ast.ClassDecl previousClassOwner = currentClassOwner;
        Ast.ClassDecl previousConstructorOwner = currentConstructorOwner;
        if (nonLexical) currentClassOwner = null;
        currentConstructorOwner = null;
        try {
            if (lambda.expressionBody() != null) {
                Type actual = typeOfAgainstExpected(
                        lambda.expressionBody(), Primitive.VOID, lambdaEnv, generics, self);
                requireAssignable(actual, Primitive.VOID, "mutex callback expression body");
            } else {
                checkCallableBlock(lambda.blockBody(), lambdaEnv, generics, Primitive.VOID, self);
            }
        } finally {
            currentClassOwner = previousClassOwner;
            currentConstructorOwner = previousConstructorOwner;
        }
    }


    private void validateLambdaArgument(Ast.Expr argument, Type expected, Env env, Set<String> generics, Type self) {
        if (argument instanceof Ast.LambdaExpr lambda && expected instanceof Function fn) {
            validateLambdaAgainstExpected(lambda, fn, env, generics, self);
        }
    }

    private void validateLambdaAgainstExpected(Ast.LambdaExpr lambda, Function expected, Env parent, Set<String> generics, Type self) {
        if (lambda.parameters().size() != expected.parameters().size()) {
            throw new IllegalArgumentException("lambda arity " + lambda.parameters().size() + " does not match expected function arity " + expected.parameters().size());
        }
        boolean nonLexical = lambda.nonLexical() || parent.descendantsNonLexical();
        Env lambdaEnv = new Env(nonLexical ? null : parent, nonLexical, !nonLexical);
        List<Type> lambdaArgumentTypes = new ArrayList<>(lambda.parameters().size());
        for (int i = 0; i < lambda.parameters().size(); i++) {
            Ast.Param param = lambda.parameters().get(i);
            Type expectedParam = expected.parameters().get(i);
            boolean expectedMutable = expected.mutableParameters().get(i);
            if (param.mutable() != expectedMutable) {
                throw new IllegalArgumentException(
                        "lambda parameter '" + param.name() + "' mutability does not match expected callable contract");
            }
            Type declared = param.type().name().equals("$infer$") ? expectedParam : resolveParam(param, generics, self);
            lambdaArgumentTypes.add(declared);
            requireAssignable(expectedParam, declared, "lambda parameter " + param.name());
            requireAssignable(declared, expectedParam, "lambda parameter " + param.name());
            lambdaEnv.define(param.name(), declared, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        }
        defineImplicitArguments(lambdaEnv, lambdaArgumentTypes);
        Ast.ClassDecl previousClassOwner = currentClassOwner;
        Ast.ClassDecl previousConstructorOwner = currentConstructorOwner;
        if (nonLexical) currentClassOwner = null;
        currentConstructorOwner = null;
        try {
            if (lambda.expressionBody() != null) {
                Type actual = typeOfAgainstExpected(
                        lambda.expressionBody(), expected.result(), lambdaEnv, generics, self);
                requireReadonlyCapabilityPreserved(
                        actual,
                        expected.result(),
                        "lambda expression body");
                requireAssignable(actual, expected.result(), "lambda expression body");
            } else {
                checkCallableBlock(lambda.blockBody(), lambdaEnv, generics, expected.result(), self);
            }
        } finally {
            currentClassOwner = previousClassOwner;
            currentConstructorOwner = previousConstructorOwner;
        }
        if (lambda.blockBody() != null
                && expected.result() != Primitive.VOID
                && !definitelyReturns(lambda.blockBody())) {
            throw new IllegalArgumentException("non-void block lambda must explicitly return on every path");
        }
    }

    private Type deref(Type type) {
        return type instanceof Borrow borrow ? borrow.target() : type;
    }

    /** Nominal/structural view used only to resolve members on a polymorphic receiver. */
    private Type receiverDispatchType(Type type) {
        return type instanceof SelfType receiverSelf ? receiverSelf.bound() : type;
    }

    /**
     * Replace receiver-polymorphic markers in a signature/result with the
     * actual static receiver type.  This also supports nested forms such as
     * Option<self> without turning an arbitrary nominal value into SelfType.
     */
    private Type bindReceiverSelf(Type type, Type receiver) {
        if (type instanceof SelfType) return receiver;
        if (type instanceof Borrow borrow) return new Borrow(bindReceiverSelf(borrow.target(), receiver), borrow.mutable());
        if (type instanceof ListType list) return new ListType(bindReceiverSelf(list.element(), receiver));
        if (type instanceof Tuple tuple) return new Tuple(tuple.elements().stream().map(t -> bindReceiverSelf(t, receiver)).toList(), tuple.kind());
        if (type instanceof Union union) return Types.unionOf(union.options().stream().map(t -> bindReceiverSelf(t, receiver)).toList());
        if (type instanceof Named named) return new Named(
                named.name(), named.arguments().stream().map(t -> bindReceiverSelf(t, receiver)).toList());
        if (type instanceof Function fn) return new Function(
                fn.parameters().stream().map(t -> bindReceiverSelf(t, receiver)).toList(),
                fn.mutableParameters(), fn.async(),
                bindReceiverSelf(fn.result(), receiver),
                mapFunctionEffects(fn.effects(), t -> bindReceiverSelf(t, receiver)));
        if (type instanceof Record record) {
            Map<String, Type> members = new LinkedHashMap<>();
            record.members().forEach((name, member) -> members.put(name, bindReceiverSelf(member, receiver)));
            return new Record(members, record.readOnly(), record.kind());
        }
        return type;
    }

    private StreamPorts streamPortsForClass(Ast.ClassDecl actor, Type actorType) {
        Ast.TypeRef marker = null;
        for (Ast.TypeRef iface : actor.interfaces()) {
            if (!iface.name().equals("StreamOperator")) continue;
            if (marker != null)
                throw new IllegalArgumentException(
                        "duplicate StreamOperator on actor '" + actor.name() + "'");
            marker = iface;
        }
        if (marker == null) return null;
        if (actor.actorKind() == Ast.ActorKind.NONE)
            throw new IllegalArgumentException(
                    "StreamOperator<I,O> can only be implemented by actors");
        if (marker.inferArguments() || marker.arguments().size() != 2)
            throw new IllegalArgumentException(
                    "StreamOperator requires exactly two explicit port type arguments");
        Set<String> ownerGenerics = Set.copyOf(actor.genericParameters());
        Type rawInput = resolve(marker.arguments().get(0), ownerGenerics, actorType);
        Type rawOutput = resolve(marker.arguments().get(1), ownerGenerics, actorType);
        Map<String, Type> bindings = actorType instanceof Named concrete
                ? classGenericBindings(actor, concrete) : Map.of();
        Type input = substituteGenerics(rawInput, bindings);
        Type output = substituteGenerics(rawOutput, bindings);
        if (input != Primitive.VOID)
            validateActorCallableBoundaryType(input, actor.actorKind(), false,
                    "StreamOperator input for actor '" + actor.name() + "'");
        if (output != Primitive.VOID)
            validateActorCallableBoundaryType(output, actor.actorKind(), false,
                    "StreamOperator output for actor '" + actor.name() + "'");
        if (input != Primitive.VOID) {
            Type declared = actorMailboxPayloadType(new Named("ActorRef", List.of(actorType)));
            if (!input.equals(declared))
                throw new IllegalArgumentException(
                        "StreamOperator input for actor '" + actor.name()
                        + "' is " + input + ", but receive(ActorMail<T>) uses " + declared);
        } else {
            for (Ast.MethodDecl method : actor.methods()) {
                if (method.name().equals("receive") && !method.isStatic())
                    throw new IllegalArgumentException(
                            "StreamOperator source actor '" + actor.name()
                            + "' has void input and cannot declare receive");
            }
        }
        return new StreamPorts(input, output);
    }

    private StreamPorts checkPipeTopology(
            Ast.Expr expression, Env env, Set<String> generics, Type self) {
        if (expression instanceof Ast.BinaryExpr binary
                && binary.operator().equals("pipe")) {
            StreamPorts from = checkPipeTopology(binary.left(), env, generics, self);
            StreamPorts into = checkPipeTopology(binary.right(), env, generics, self);
            if (from.output() == Primitive.VOID || into.input() == Primitive.VOID)
                throw new IllegalArgumentException(
                        "pipe cannot attach stages through a void output/input port");
            if (!from.output().equals(into.input()))
                throw new IllegalArgumentException(
                        "pipe port type mismatch: producer emits " + from.output()
                        + " but consumer requires " + into.input());
            return new StreamPorts(from.input(), into.output());
        }
        return streamPortsForPipeOperand(
                typeOf(expression, env, generics, self), "stage");
    }

    private StreamPorts streamPortsForPipeOperand(Type value, String side) {
        Type type = receiverDispatchType(deref(value));
        if (!(type instanceof Named named))
            throw new IllegalArgumentException("pipe " + side + " operand must be a typed actor stage/graph");
        if (named.name().equals("ActorStream") && named.arguments().size() == 2)
            return new StreamPorts(named.arguments().get(0), named.arguments().get(1));
        if (!named.name().equals("ActorRef") || named.arguments().size() != 1)
            throw new IllegalArgumentException("pipe " + side + " operand must be ActorRef<StreamOperator<I,O>> or ActorStream<I,O>");
        Ast.ClassDecl actor = actorClassForRef(named);
        StreamPorts ports = streamPortsForClass(actor, named.arguments().get(0));
        if (ports == null)
            throw new IllegalArgumentException(
                    "pipe " + side + " actor '" + actor.name()
                    + "' must explicitly implement StreamOperator<I,O>");
        return ports;
    }

    private Type actorMailboxPayloadType(Named actorRef) {
        if (actorRef.arguments().size() != 1) {
            throw new IllegalArgumentException(
                    "ActorRef requires exactly one actor type argument");
        }
        Type target = receiverDispatchType(deref(actorRef.arguments().getFirst()));
        if (!(target instanceof Named concreteActor)) {
            throw new IllegalArgumentException(
                    "ActorRef target must be a concrete actor class; got " + target);
        }
        Ast.ClassDecl actorClass = findClass(concreteActor.name());
        if (actorClass == null || actorClass.actorKind() == Ast.ActorKind.NONE) {
            throw new IllegalArgumentException(
                    "ActorRef target '" + concreteActor.name() + "' is not a known actor class");
        }
        ResolvedMethod receive = findMethodTarget(
                actorClass,
                concreteActor,
                "receive",
                1,
                new LinkedHashSet<>());
        if (receive == null) {
            throw new IllegalArgumentException(
                    "actor '" + actorClass.name()
                            + "' has no receive(ActorMail<T>) mailbox handler");
        }
        Ast.MethodDecl handler = receive.method();
        if (handler.parameters().size() != 1) {
            throw new IllegalArgumentException(
                    "actor '" + actorClass.name() + ".receive' has invalid mailbox arity");
        }
        Set<String> ownerGenerics = Set.copyOf(receive.owner().genericParameters());
        Type mailType = resolve(
                handler.parameters().getFirst().type(),
                ownerGenerics,
                receive.ownerType());
        mailType = substituteGenerics(
                mailType,
                classGenericBindings(receive.owner(), receive.ownerType()));
        if (!(mailType instanceof Named mail)
                || !mail.name().equals("ActorMail")
                || mail.arguments().size() != 1) {
            throw new IllegalArgumentException(
                    "actor '" + actorClass.name()
                            + ".receive' must accept ActorMail<T>");
        }
        return mail.arguments().getFirst();
    }

    private Ast.ClassDecl actorClassForRef(Named actorRef) {
        if (!actorRef.name().equals("ActorRef") || actorRef.arguments().size() != 1) {
            throw new IllegalArgumentException(
                    "ActorRef requires exactly one actor type argument");
        }
        Type target = receiverDispatchType(deref(actorRef.arguments().getFirst()));
        if (!(target instanceof Named namedTarget)) {
            throw new IllegalArgumentException(
                    "ActorRef target must be a concrete actor class; got " + target);
        }
        Ast.ClassDecl actorClass = findClass(namedTarget.name());
        if (actorClass == null || actorClass.actorKind() == Ast.ActorKind.NONE) {
            throw new IllegalArgumentException(
                    "ActorRef target '" + namedTarget.name()
                            + "' is not a known actor class");
        }
        return actorClass;
    }

    private static Type builtinIoMember(Type receiver, String name) {
        if (!(receiver instanceof Named named)) return null;
        if (named.name().equals("fs") || named.name().equals("File")) {
            Type string = Primitive.STRING;
            Type booleanType = Primitive.BOOL;
            switch (name) {
                case "list_dir": return new Function(List.of(string), new ListType(string));
                case "is_dir", "is_symlink", "exists": return new Function(List.of(string), booleanType);
                case "read_text": return new Function(List.of(string), string);
                case "write_text", "write_text_atomic", "append_text":
                    return new Function(List.of(string, string), Primitive.VOID);
                case "remove", "mkdir_all": return new Function(List.of(string), Primitive.VOID);
                default: break;
            }
        }
        if (named.name().equals("env")) {
            switch (name) {
                case "get": return new Function(List.of(Primitive.STRING),
                        new Named("Option", List.of(Primitive.STRING)));
                case "has": return new Function(List.of(Primitive.STRING), Primitive.BOOL);
                default: break;
            }
        }
        Type result;
        List<Type> parameters;
        switch (named.name()) {
            case "fs", "File" -> {
                switch (name) {
                    case "read_text_async" -> { result = Primitive.STRING; parameters = List.of(Primitive.STRING); }
                    case "exists_async" -> { result = Primitive.BOOL; parameters = List.of(Primitive.STRING); }
                    case "write_text_async", "append_text_async" -> { result = Primitive.VOID; parameters = List.of(Primitive.STRING, Primitive.STRING); }
                    case "remove_async", "mkdir_all_async" -> { result = Primitive.VOID; parameters = List.of(Primitive.STRING); }
                    default -> { return null; }
                }
            }
            case "network", "net" -> {
                if (!name.equals("connect_async")) return null;
                result = new Named("TcpConnection", List.of()); parameters = List.of(Primitive.STRING, Primitive.INT);
            }
            case "TcpConnection" -> {
                switch (name) {
                    case "read_line_async" -> { result = new Named("Option", List.of(Primitive.STRING)); parameters = List.of(); }
                    case "write_text_async" -> { result = Primitive.VOID; parameters = List.of(Primitive.STRING); }
                    case "close_async" -> { result = Primitive.VOID; parameters = List.of(); }
                    default -> { return null; }
                }
            }
            case "http" -> {
                switch (name) {
                    case "get_text_async" -> { result = Primitive.STRING; parameters = List.of(Primitive.STRING); }
                    case "post_text_async" -> { result = Primitive.STRING; parameters = List.of(Primitive.STRING, Primitive.STRING); }
                    default -> { return null; }
                }
            }
            default -> { return null; }
        }
        return new Function(parameters, new Named("Future", List.of(result)));
    }

    private static Type builtinHttpMember(Type receiver, String name) {
        if (!(receiver instanceof Named named)) return null;
        Type exchange = new Named("HttpExchange", List.of());
        Type server = new Named("HttpServer", List.of());
        Type voidFuture = new Named("Future", List.of(Primitive.VOID));
        return switch (named.name()) {
            case "http" -> switch (name) {
                case "handler" -> new Function(List.of(Primitive.STRING), new Named("HttpHandler", List.of()));
                case "listen" -> new Function(List.of(Primitive.STRING, Primitive.INT, Primitive.INT, Primitive.INT, Primitive.INT), server);
                case "claim" -> new Function(List.of(Primitive.STRING), exchange);
                case "response" -> new Function(List.of(Primitive.INT, Primitive.STRING, Primitive.STRING), new Named("HttpResponse", List.of()));
                case "response_empty" -> new Function(List.of(Primitive.INT), new Named("HttpResponse", List.of()));
                default -> null;
            };
            case "HttpHandler" -> switch (name) {
                case "isolated" -> Primitive.BOOL;
                case "created", "reused" -> Primitive.INT;
                case "pooled" -> new Function(List.of(Primitive.INT), new Named("HttpHandler", List.of()));
                case "dispatch" -> new Function(List.of(new Borrow(exchange, true)), Primitive.VOID);
                default -> throw new IllegalArgumentException("unknown HTTP handler member " + name);
            };
            case "HttpServer" -> switch (name) {
                case "port", "active", "accepted", "rejected", "completed" -> Primitive.INT;
                case "accept" -> new Function(List.of(), new Named("Future", List.of(exchange)));
                case "shutdown" -> new Function(List.of(Primitive.INT), voidFuture);
                default -> throw new IllegalArgumentException("unknown HTTP server member " + name);
            };
            case "HttpExchange" -> switch (name) {
                case "can_respond" -> Primitive.BOOL;
                case "method", "target", "request_id", "operation_id", "server_timing" -> Primitive.STRING;
                case "pipeline_id", "contract_id", "actor_startup_ns", "move_to_claim_ns", "admitted_ns" -> Primitive.INT;
                case "completion" -> new Named("Future", List.of(Primitive.INT));
                case "set_route" -> new Function(List.of(Primitive.STRING, Primitive.INT, Primitive.INT), Primitive.VOID);
                case "add_param" -> new Function(List.of(Primitive.STRING, Primitive.STRING), Primitive.VOID);
                case "param_size" -> new Function(List.of(Primitive.STRING), Primitive.INT);
                case "param_at" -> new Function(List.of(Primitive.STRING, Primitive.INT), Primitive.STRING);
                case "path_size" -> Primitive.INT;
                case "path_at" -> new Function(List.of(Primitive.INT), Primitive.STRING);
                case "header", "query", "param", "cookie" -> new Function(List.of(Primitive.STRING), Primitive.STRING);
                case "set_header" -> new Function(List.of(Primitive.STRING, Primitive.STRING), Primitive.VOID);
                case "read_chunk" -> new Function(List.of(Primitive.INT), new Named("Future", List.of(new ListType(Primitive.INT))));
                case "write_bytes" -> new Function(List.of(new ListType(Primitive.INT)), voidFuture);
                case "respond_bytes" -> new Function(List.of(Primitive.INT, Primitive.STRING, new ListType(Primitive.INT)), voidFuture);
                case "body_text" -> new Function(List.of(), new Named("Future", List.of(Primitive.STRING)));
                case "respond" -> new Function(List.of(Primitive.INT, Primitive.STRING, Primitive.STRING), voidFuture);
                case "respond_response" -> new Function(List.of(new Named("HttpResponse", List.of())), voidFuture);
                case "start" -> new Function(List.of(Primitive.INT, Primitive.STRING), voidFuture);
                case "write" -> new Function(List.of(Primitive.STRING), voidFuture);
                case "finish" -> new Function(List.of(), voidFuture);
                case "abort" -> new Function(List.of(), Primitive.VOID);
                case "move_to" -> new Function(List.of(Unknown.INSTANCE), Primitive.STRING);
                default -> throw new IllegalArgumentException("unknown HTTP exchange member " + name);
            };
            default -> null;
        };
    }

    private Type memberType(Ast.MemberExpr member, Env env, Set<String> generics, Type self) {
        return memberType(member, env, generics, self, false);
    }

    private Type memberType(
            Ast.MemberExpr member,
            Env env,
            Set<String> generics,
            Type self,
            boolean writing) {
        Type rawReceiver;
        if (member.receiver() instanceof Ast.NameExpr name && env.lookup(name.name()) != null) {
            Env.Binding binding = env.lookup(name.name());
            if (!writing && binding.isUninitialized(member.member())) {
                throw new IllegalArgumentException(
                        "explicit struct field '" + member.member()
                                + "' is read before initialization on '" + name.name() + "'");
            }
            rawReceiver = binding.type();
        } else {
            rawReceiver = typeOf(member.receiver(), env, generics, self);
        }
        Type receiver = receiverDispatchType(
                unwrapMutexGuard(deref(rawReceiver)));
        if (writing && receiver instanceof Record record && record.readOnly()) {
            throw new IllegalArgumentException(
                    "cannot mutate a readonly structural record value");
        }
        if (receiver instanceof Record record) {
            Type result = record.members().get(member.member());
            if (result == null) throw new IllegalArgumentException("unknown structural member '" + member.member() + "'");
            return result;
        }
        if (receiver instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                ResolvedField field = findFieldTarget(klass, named, member.member(), new LinkedHashSet<>());
                if (field != null) {
                    requireClassMemberVisible(
                            field.field().visibility(), field.owner(), "field", field.field().name());
                    if (writing && field.field().bindingKind() != Ast.BindingKind.LET) {
                        // Preserve the pre-existing constructor assignment path
                        // for fields declared without an initializer. A field
                        // initialized in its declaration is NEVER writable
                        // through that exception (including new 'final' fields).
                        boolean initializingInConstructor =
                                currentConstructorOwner == klass
                                && field.field().initializer() == null
                                && member.receiver() instanceof Ast.NameExpr root
                                && root.name().equals("self");
                        if (!initializingInConstructor) {
                            throw new IllegalArgumentException(
                                    "field '" + field.owner().name() + "." + field.field().name()
                                            + "' is immutable/final; cannot reassign fixed class field");
                        }
                        if (constructorFixedFieldWrites == null
                                || !constructorFixedFieldWrites.add(field.field().name())) {
                            throw new IllegalArgumentException(
                                    "field '" + field.owner().name() + "." + field.field().name()
                                            + "' is immutable/final; constructor must initialize it at most once");
                        }
                    }
                    Type pattern = classFieldType(field.owner(), field.field());
                    return substituteGenerics(pattern, classGenericBindings(field.owner(), field.ownerType()));
                }
            }
        }
        throw new IllegalArgumentException("assignment target '" + member.member() + "' is not a mutable data field");
    }

    private void validateAsyncBoundaryType(
            Type type,
            boolean returnPosition,
            String where) {
        if (returnPosition && type == Primitive.VOID) return;
        // A typed channel handle is an Ores-owned synchronized transport, not
        // an actor/mailbox capability. Async tasks may receive the handle when
        // its payload is concrete, owned and task-safe. Actor boundaries retain
        // their stricter rule and continue to reject Channel<T> entirely.
        if (type instanceof Named named && named.name().equals("Channel")
                && named.arguments().size() == 1
                && isSendSafe(named.arguments().getFirst(), new LinkedHashSet<>(), Map.of())) {
            return;
        }
        if (type == Primitive.VOID
                || !isSendSafe(type, new LinkedHashSet<>(), Map.of())) {
            throw new IllegalArgumentException(
                    where + " must be concrete owned task-safe data; borrows, futures, mutexes, functions, actor values, host capabilities, and unresolved generics cannot cross an async task boundary");
        }
    }

    private void validateActorCallableBoundaryType(
            Type type,
            Ast.ActorKind actorKind,
            boolean returnPosition,
            String where) {
        if (returnPosition && type == Primitive.VOID) return;
        if (type == Primitive.VOID) {
            throw new IllegalArgumentException(where + " cannot be void");
        }
        if (type == Unknown.INSTANCE || type instanceof Generic || type instanceof SelfType
                || type instanceof Borrow || type instanceof Function || type instanceof ClassNamespace) {
            throw new IllegalArgumentException(
                    where + " must be a concrete owned/sendable data type; borrows, functions, and unresolved types cannot cross an actor boundary");
        }
        if (type instanceof Primitive || type instanceof StringLiteral) return;
        if (type instanceof ListType list) {
            validateActorCallableBoundaryType(list.element(), actorKind, false, where + " element");
            return;
        }
        if (type instanceof Tuple tuple) {
            for (int i = 0; i < tuple.elements().size(); i++) {
                validateActorCallableBoundaryType(tuple.elements().get(i), actorKind, false, where + " tuple element " + i);
            }
            return;
        }
        if (type instanceof Union union) {
            for (Type option : union.options()) {
                validateActorCallableBoundaryType(option, actorKind, false, where + " union member");
            }
            return;
        }
        if (type instanceof Record record) {
            for (Map.Entry<String, Type> member : record.members().entrySet()) {
                validateActorCallableBoundaryType(member.getValue(), actorKind, false, where + " field '" + member.getKey() + "'");
            }
            return;
        }
        if (!(type instanceof Named named)) {
            throw new IllegalArgumentException(where + " is not actor-boundary sendable: " + type);
        }

        if (named.name().equals("TcpConnection") || named.name().equals("HttpExchange") || named.name().equals("HttpServer") || named.name().equals("HttpHandler") || named.name().equals("HttpResponse") || named.name().equals("HttpResponseDraft")
                || named.name().equals("Box") || named.name().equals("Arc")
                || named.name().equals("Weak") || named.name().equals("Rc")
                || named.name().equals("RefCell") || named.name().equals("RwLock")
                || named.name().equals("Mutex") || named.name().equals("MutexGuard") || named.name().equals("Future")
                || named.name().equals("ActorStream") || named.name().equals("StreamOperator")
                || named.name().equals("Channel") || named.name().equals("SelectCase")
                || named.name().equals("SelectSet") || named.name().equals("SelectResult")
                || named.name().equals("Iterator") || named.name().equals("AsyncIterator")) {
            throw new IllegalArgumentException(
                    where + " cannot use " + named.name()
                            + " across an actor boundary; actor communication uses ActorRef/mailbox transport");
        }
        if (named.name().equals("ActorRef")) {
            actorClassForRef(named);
            return;
        }
        if (named.name().equals("SharedMutex")) {
            if (actorKind == Ast.ActorKind.PRIVATE || actorKind == Ast.ActorKind.UNTRUSTED) {
                throw new IllegalArgumentException(
                        where + " cannot use SharedMutex<T> with isoactor/private actors");
            }
            if (named.arguments().size() != 1
                    || !isSharedMutexPayloadSafe(named.arguments().getFirst(), new LinkedHashSet<>(), Map.of())) {
                throw new IllegalArgumentException(
                        where + " requires SharedMutex<T> to contain shared-safe owned data");
            }
            return;
        }

        for (Type argument : named.arguments()) {
            validateActorCallableBoundaryType(argument, actorKind, false, where + " type argument");
        }

        // Built-in sum/container values are data-only when their arguments pass.
        if (named.name().equals("Option") || named.name().equals("Result")
                || named.name().equals("OptionUnwrapError")) {
            return;
        }

        Ast.ClassDecl klass = findClass(named.name());
        if (klass == null) {
            // Runtime capability types (for example ActorId/ActorRef) are
            // validated again by transport. Do not silently admit known
            // mutable/suspending primitives above, but avoid rejecting opaque
            // capability types solely because they have no source class body.
            return;
        }
        if (klass.actorKind() != Ast.ActorKind.NONE) {
            throw new IllegalArgumentException(
                    where + " cannot transport an actor instance by value; pass an actor capability/reference");
        }

        /*
         * Ordinary class instances are mutable identity-bearing objects. The
         * runtime transport deliberately rejects OresObject/unknown host
         * objects instead of sharing their identity or inventing an implicit
         * deep copy. Keep the compiler aligned with that fail-closed runtime
         * rule. A future explicit serializer/copy trait may opt a class in;
         * until then actor mail/output must use data-only records/tuples/lists,
         * scalars or explicit capabilities.
         */
        throw new IllegalArgumentException(
                where + " cannot transport class instance '" + klass.name()
                        + "' by value; actor boundaries require data-only values "
                        + "or an explicit serialization/copy contract");
    }

    private boolean isSharedMutexPayloadSafe(
            Type type,
            Set<Ast.ClassDecl> seen,
            Map<String, Type> genericBindings) {
        if (type == Unknown.INSTANCE || type instanceof SelfType
                || type instanceof Borrow || type instanceof Function
                || type instanceof ClassNamespace) {
            return false;
        }
        if (type instanceof Primitive primitive) {
            return primitive != Primitive.VOID;
        }
        if (type instanceof StringLiteral) return true;
        if (type instanceof Generic generic) {
            Type bound = genericBindings.get(generic.name());
            return bound != null
                    && bound != type
                    && isSharedMutexPayloadSafe(
                            bound, seen, genericBindings);
        }
        if (type instanceof ListType list) {
            return isSharedMutexPayloadSafe(
                    list.element(), seen, genericBindings);
        }
        if (type instanceof Union union) {
            for (Type option : union.options()) {
                if (!isSharedMutexPayloadSafe(
                        option, seen, genericBindings)) {
                    return false;
                }
            }
            return true;
        }
        if (type instanceof Tuple tuple) {
            for (Type element : tuple.elements()) {
                if (!isSharedMutexPayloadSafe(
                        element, seen, genericBindings)) {
                    return false;
                }
            }
            return true;
        }
        if (type instanceof Record record) {
            for (Type member : record.members().values()) {
                if (!isSharedMutexPayloadSafe(
                        member, seen, genericBindings)) {
                    return false;
                }
            }
            return true;
        }
        if (!(type instanceof Named named)) return false;

        // This is storage-admission policy, not another auto trait. Keep
        // runtime-domain capabilities out of SharedMutex payloads even when
        // some of them are independently Send.
        if (Set.of(
                "Box", "Arc", "Weak", "Rc", "RefCell", "RwLock",
                "Mutex", "MutexGuard", "SharedMutex",
                "Future", "Iterator", "AsyncIterator", "ActorStream", "StreamOperator",
                "Channel", "SelectCase", "SelectSet", "SelectResult")
                .contains(named.name())) {
            return false;
        }
        if (named.name().equals("OptionUnwrapError")) {
            return named.arguments().isEmpty();
        }
        if (named.name().equals("Option")
                || named.name().equals("IteratorResult")) {
            return named.arguments().size() == 1
                    && isSharedMutexPayloadSafe(
                            named.arguments().getFirst(),
                            seen,
                            genericBindings);
        }
        if (named.name().equals("Result")) {
            return named.arguments().size() == 2
                    && isSharedMutexPayloadSafe(
                            named.arguments().get(0),
                            seen,
                            genericBindings)
                    && isSharedMutexPayloadSafe(
                            named.arguments().get(1),
                            seen,
                            genericBindings);
        }

        for (Type argument : named.arguments()) {
            if (!isSharedMutexPayloadSafe(
                    argument, seen, genericBindings)) {
                return false;
            }
        }

        Ast.ClassDecl klass = findClass(named.name());
        if (klass == null || klass.actorKind() != Ast.ActorKind.NONE) {
            return false;
        }
        if (!seen.add(klass)) return true;

        try {
            Map<String, Type> classBindings = new HashMap<>(genericBindings);
            if (named.arguments().size()
                    != klass.genericParameters().size()) {
                return false;
            }
            for (int i = 0; i < klass.genericParameters().size(); i++) {
                classBindings.put(
                        klass.genericParameters().get(i),
                        resolveSharedGeneric(
                                named.arguments().get(i),
                                genericBindings));
            }

            Type nominal = nominalClassType(klass);
            Set<String> classGenerics =
                    Set.copyOf(klass.genericParameters());

            for (Ast.FieldDecl field : klass.fields()) {
                Type fieldType = resolveSharedGeneric(
                        resolve(
                                field.type(),
                                classGenerics,
                                nominal),
                        classBindings);
                if (!isSharedMutexPayloadSafe(
                        fieldType, seen, classBindings)) {
                    return false;
                }
            }

            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object")
                        || parentRef.name().equals("List")) {
                    continue;
                }
                Type parentType = resolveSharedGeneric(
                        resolve(
                                parentRef,
                                classGenerics,
                                nominal),
                        classBindings);
                if (!isSharedMutexPayloadSafe(
                        parentType, seen, classBindings)) {
                    return false;
                }
            }

            return true;
        } finally {
            seen.remove(klass);
        }
    }

    private boolean isSendSafe(
            Type type,
            Set<Ast.ClassDecl> seen,
            Map<String, Type> genericBindings) {
        return inferBoundaryAutoTraits(type, seen, genericBindings).send();
    }

    private AutoTraitRules.Capabilities inferBoundaryAutoTraits(
            Type type,
            Set<Ast.ClassDecl> seen,
            Map<String, Type> genericBindings) {
        if (type instanceof Generic generic) {
            Type bound = genericBindings.get(generic.name());
            if (bound == null || bound == type) {
                return new AutoTraitRules.Capabilities(false, false, false);
            }
            return inferBoundaryAutoTraits(bound, seen, genericBindings);
        }

        java.util.Optional<AutoTraitRules.Capabilities> builtin =
                TypeAutoTraits.inferBuiltin(
                        type,
                        nested -> inferBoundaryAutoTraits(
                                nested, seen, genericBindings));
        if (builtin.isPresent()) return builtin.get();

        if (!(type instanceof Named named)) {
            return new AutoTraitRules.Capabilities(false, false, false);
        }

        Ast.ClassDecl klass = findClass(named.name());
        if (klass == null || klass.actorKind() != Ast.ActorKind.NONE) {
            return new AutoTraitRules.Capabilities(false, false, false);
        }

        // A recursive nominal edge does not introduce a new unsafe capability
        // by itself; the outer traversal still checks every concrete field.
        if (!seen.add(klass)) {
            return new AutoTraitRules.Capabilities(true, false, false);
        }

        try {
            if (named.arguments().size() != klass.genericParameters().size()) {
                return new AutoTraitRules.Capabilities(false, false, false);
            }

            Map<String, Type> classBindings = new HashMap<>(genericBindings);
            for (int i = 0; i < klass.genericParameters().size(); i++) {
                classBindings.put(
                        klass.genericParameters().get(i),
                        resolveSharedGeneric(
                                named.arguments().get(i),
                                genericBindings));
            }

            Type nominal = nominalClassType(klass);
            Set<String> classGenerics = Set.copyOf(klass.genericParameters());
            boolean send = true;

            for (Ast.FieldDecl field : klass.fields()) {
                Type fieldType = resolveSharedGeneric(
                        resolve(field.type(), classGenerics, nominal),
                        classBindings);
                if (!inferBoundaryAutoTraits(
                        fieldType, seen, classBindings).send()) {
                    send = false;
                    break;
                }
            }

            if (send) {
                for (Ast.TypeRef parentRef : klass.parents()) {
                    if (parentRef.name().equals("Object")
                            || parentRef.name().equals("List")) {
                        continue;
                    }
                    Type parentType = resolveSharedGeneric(
                            resolve(parentRef, classGenerics, nominal),
                            classBindings);
                    if (!inferBoundaryAutoTraits(
                            parentType, seen, classBindings).send()) {
                        send = false;
                        break;
                    }
                }
            }

            // Ordinary nominal class identity is movable between task domains
            // when its graph is Send, but it is not implicitly Sync/Share.
            return new AutoTraitRules.Capabilities(send, false, false);
        } finally {
            seen.remove(klass);
        }
    }

    private Type typeOfCondition(Ast.Expr expr, Env env, Set<String> generics, Type self) {
        if (expr instanceof Ast.BinaryExpr binary && binary.operator().equals("&&")) {
            requireAssignable(typeOfCondition(binary.left(), env, generics, self), Primitive.BOOL, "logical operand");
            Env rightEnv = new Env(env);
            defineConditionBindings(binary.left(), rightEnv, env, generics, self);
            requireAssignable(typeOfCondition(binary.right(), rightEnv, generics, self), Primitive.BOOL, "logical operand");
            return Primitive.BOOL;
        }
        return typeOf(expr, env, generics, self);
    }

    private void defineConditionBindings(
            Ast.Expr condition, Env destination, Env sourceEnv, Set<String> generics, Type self) {
        if (condition instanceof Ast.TypeTestExpr test && test.binding() != null) {
            Type source = deref(typeOf(test.value(), sourceEnv, generics, self));
            Type target = deref(resolve(test.targetType(), generics, self));
            if (!typesMayOverlap(source, target)) {
                throw new IllegalArgumentException("impossible type refinement: " + source + " cannot be " + target);
            }
            destination.define(
                    test.binding(),
                    overlayReadonlyCapabilities(source, target),
                    Ast.BindingKind.VAL);
            return;
        }
        if (condition instanceof Ast.PatternTestExpr test) {
            Type subject = deref(typeOf(test.value(), sourceEnv, generics, self));
            checkPattern(test.pattern(), subject, destination, generics, self);
            return;
        }
        if (condition instanceof Ast.BinaryExpr binary && binary.operator().equals("&&")) {
            defineConditionBindings(binary.left(), destination, sourceEnv, generics, self);
            defineConditionBindings(binary.right(), destination, destination, generics, self);
        }
    }

    private void checkMatch(
            Ast.MatchStmt matched,
            Type subject,
            Env env,
            Set<String> generics,
            Type expectedReturn,
            Type self) {
        List<Ast.MatchArm> arms = matched.arms();
        for (int i = 0; i < arms.size(); i++) {
            Ast.MatchArm arm = arms.get(i);

            // Multiple earlier arms may collectively cover the domain even
            // when no single arm subsumes this one (e.g. true, false, then _).
            // Guards do not count toward unconditional reachability.
            if (i > 0 && patternsProvablyExhaustive(
                    arms.subList(0, i).stream()
                            .filter(previous -> previous.guard() == null)
                            .map(Ast.MatchArm::pattern)
                            .toList(),
                    subject, generics, self, 0)) {
                throw new IllegalArgumentException(
                        "unreachable match arm " + (i + 1)
                                + ": earlier arms already exhaust " + subject);
            }

            Env armEnv = new Env(env);
            checkPattern(arm.pattern(), subject, armEnv, generics, self);
            if (arm.guard() != null) {
                requireAssignable(typeOfCondition(arm.guard(), armEnv, generics, self), Primitive.BOOL, "match guard");
            }
            checkBlock(arm.body(), armEnv, generics, expectedReturn, self);

            // Match is ordered. Overlap is valid, but an earlier unguarded arm
            // that completely contains a later pattern makes that arm dead.
            for (int j = 0; j < i; j++) {
                Ast.MatchArm previous = arms.get(j);
                if (previous.guard() != null) continue;
                if (patternSubsumes(previous.pattern(), arm.pattern(), subject, generics, self)) {
                    throw new IllegalArgumentException(
                            "unreachable match arm " + (i + 1)
                                    + ": earlier arm " + (j + 1)
                                    + " already matches every value accepted by it");
                }
            }
        }

        if (!matchProvablyExhaustive(arms, subject, generics, self)) {
            throw new IllegalArgumentException(
                    "non-exhaustive match for " + subject
                            + "; add an unguarded 'on _ -> { ... }' arm or cover every provable case");
        }
    }

    private void checkPattern(
            Ast.Pattern pattern, Type subject, Env bindings, Set<String> generics, Type self) {
        if (pattern instanceof Ast.WildcardPattern) return;
        if (pattern instanceof Ast.BindingPattern binding) {
            bindings.define(binding.name(), subject, Ast.BindingKind.VAL);
            return;
        }
        if (pattern instanceof Ast.LiteralPattern literal) {
            Type literalType = typeOf(new Ast.LiteralExpr(literal.value()), bindings, generics, self);
            if (!assignable(literalType, subject) && !assignable(subject, literalType)) {
                throw new IllegalArgumentException("literal pattern type " + literalType + " is incompatible with " + subject);
            }
            return;
        }
        if (pattern instanceof Ast.TypePattern typed) {
            requireRuntimeReifiableType(typed.type(), "type pattern");
            requireKnownRuntimePatternTarget(typed.type(), new LinkedHashSet<>());
            if (typed.type().isComplement()) {
                Type excluded = deref(resolve(typed.type().complementedType(), generics, self));
                if (runtimeValueTypeCovered(subject, excluded, 0)) {
                    throw new IllegalArgumentException(
                            "unreachable negative type pattern: " + subject + " is contained in " + excluded);
                }
                if (typed.binding() != null) {
                    // A negative test does not prove a concrete positive
                    // nominal type; retain the subject's static capabilities.
                    bindings.define(typed.binding(), subject, Ast.BindingKind.VAL);
                }
                return;
            }
            Type target = deref(resolve(typed.type(), generics, self));
            if (!typesMayOverlap(subject, target)) {
                throw new IllegalArgumentException("unreachable type pattern: " + subject + " cannot be " + target);
            }
            if (typed.binding() != null) {
                bindings.define(
                        typed.binding(),
                        overlayReadonlyCapabilities(subject, target),
                        Ast.BindingKind.VAL);
            }
            return;
        }
        if (pattern instanceof Ast.StructuralPattern structural) {
            Type resolvedTarget = deref(resolve(structural.target(), generics, self));
            Record targetShape = structuralPatternShape(
                    structural.target(), resolvedTarget, generics);
            if (!structuralPatternMayMatch(subject, targetShape)) {
                throw new IllegalArgumentException(
                        "unreachable structural pattern: " + subject
                                + " cannot satisfy " + structural.target());
            }
            if (structural.binding() != null) {
                // Structural pattern matching proves shape only, NEVER
                // mutable field authority. Keep the current-main structural
                // CONTRACT kind, including public method slots, rather than
                // exposing the nominal subject or a writable record alias.
                Type bindingType = new Record(
                        targetShape.members(), true, AggregateKind.CONTRACT);
                bindingType = overlayReadonlyCapabilities(subject, bindingType);
                bindings.define(structural.binding(), bindingType, Ast.BindingKind.VAL);
            }
            return;
        }
        if (pattern instanceof Ast.TuplePattern tuplePattern) {
            if (!(subject instanceof Tuple tuple) || tuple.kind() != TupleKind.TUPLE) {
                throw new IllegalArgumentException(
                        "tuple pattern requires a Tuple value, got " + subject
                                + "; List/Array patterns are intentionally unsupported");
            }
            if (tuple.elements().size() != tuplePattern.elements().size()) {
                throw new IllegalArgumentException(
                        "tuple pattern arity " + tuplePattern.elements().size()
                                + " does not match tuple arity " + tuple.elements().size());
            }
            for (int i = 0; i < tuplePattern.elements().size(); i++) {
                checkPattern(
                        tuplePattern.elements().get(i),
                        tuple.elements().get(i),
                        bindings,
                        generics,
                        self);
            }
            return;
        }
        if (pattern instanceof Ast.ConstructorPattern constructor) {
            if (!(subject instanceof Named named)) {
                throw new IllegalArgumentException("constructor pattern " + constructor.constructor() + " requires a sum/constructor type, got " + subject);
            }
            List<Type> args = switch (constructor.constructor()) {
                case "Some" -> named.name().equals("Option") && named.arguments().size() == 1
                        ? List.of(named.arguments().getFirst()) : null;
                case "None" -> named.name().equals("Option") && named.arguments().size() == 1
                        ? List.of() : null;
                case "Ok" -> named.name().equals("Result") && named.arguments().size() == 2
                        ? (named.arguments().get(0) == Primitive.VOID
                                ? List.of() : List.of(named.arguments().get(0)))
                        : null;
                case "Err" -> named.name().equals("Result") && named.arguments().size() == 2
                        ? List.of(named.arguments().get(1)) : null;
                default -> null;
            };
            if (args == null) {
                throw new IllegalArgumentException("constructor " + constructor.constructor() + " is not valid for " + subject);
            }
            if (args.size() != constructor.arguments().size()) {
                throw new IllegalArgumentException("constructor pattern " + constructor.constructor()
                        + " expects " + args.size() + " argument(s), got " + constructor.arguments().size());
            }
            for (int i = 0; i < args.size(); i++) {
                checkPattern(constructor.arguments().get(i), args.get(i), bindings, generics, self);
            }
            return;
        }
        throw new IllegalArgumentException("unsupported pattern " + pattern);
    }

    private Record structuralPatternShape(
            Ast.TypeRef sourceTarget,
            Type resolvedTarget,
            Set<String> generics) {
        if (resolvedTarget instanceof Record record) {
            if (record.kind() == AggregateKind.STRUCT) {
                throw new IllegalArgumentException(
                        "structural pattern target must be a structural record, interface, or trait; concrete struct shapes require exact type matching");
            }
            // 'structural { ... }' is a contract view. Converting only the
            // pattern target keeps ordinary RECORD and STRUCT values distinct.
            if (record.kind() == AggregateKind.RECORD) {
                return new Record(record.members(), true, AggregateKind.CONTRACT);
            }
            return record;
        }
        if (!(resolvedTarget instanceof Named named)) {
            throw new IllegalArgumentException(
                    "structural pattern target must be an inline record shape or named interface/trait, got "
                            + sourceTarget);
        }

        Ast.ClassDecl klass = findClass(named.name());
        if (klass != null) {
            if (klass.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalArgumentException(
                        "actor identity cannot be used as a structural match target: " + sourceTarget);
            }
            if (klass.genericParameters().size() != named.arguments().size()) {
                throw new IllegalArgumentException(
                        "structural pattern target '" + sourceTarget + "' has invalid generic arity");
            }
            Type specialized = substituteGenerics(
                    publicClassShape(klass, new LinkedHashSet<>()),
                    classGenericBindings(klass, named));
            if (!(specialized instanceof Record record)) {
                throw new IllegalStateException("structural class shape did not remain a record");
            }
            return record;
        }

        Ast.InterfaceDecl iface = findInterface(named.name());
        if (iface == null || iface.moduleContract()) {
            throw new IllegalArgumentException(
                    "structural pattern target '" + sourceTarget
                            + "' must name a class/interface/trait value contract, not a module contract");
        }
        if (iface.genericParameters().size() != named.arguments().size()) {
            throw new IllegalArgumentException(
                    "structural pattern target '" + sourceTarget + "' has invalid generic arity");
        }

        Record raw = interfaceShape(
                iface,
                Set.copyOf(iface.genericParameters()),
                new LinkedHashSet<>());
        Type specialized = substituteGenerics(
                raw,
                genericBindings(
                        iface.genericParameters(),
                        named.arguments(),
                        "structural pattern " + iface.name()));
        if (!(specialized instanceof Record record)) {
            throw new IllegalStateException("structural interface shape did not remain a record");
        }
        return record;
    }

    private boolean structuralPatternMayMatch(Type subject, Record targetShape) {
        if (subject == Unknown.INSTANCE || subject instanceof Generic) return true;
        if (subject instanceof Union union) {
            return union.options().stream()
                    .anyMatch(option -> structuralPatternMayMatch(option, targetShape));
        }
        if (subject instanceof SelfType selfType) {
            return structuralPatternMayMatch(selfType.bound(), targetShape);
        }
        if (subject instanceof Record sourceRecord) {
            return assignable(sourceRecord,
                    new Record(targetShape.members(), true, AggregateKind.CONTRACT));
        }
        if (subject instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                // Open nominal classes may have a runtime subtype with a wider
                // public shape. Actors are the exception: structural erasure is
                // forbidden for the entire actor hierarchy.
                return klass.actorKind() == Ast.ActorKind.NONE;
            }
            Ast.InterfaceDecl iface = findInterface(named.name());
            if (iface != null && !iface.moduleContract()) return true;
        }
        return false;
    }

    /**
     * Keep static literal subsumption consistent with valueEquals in the
     * evaluator. In particular, do not collapse large integral literals to
     * double: 2^53+1 is not equal to the rounded float 2^53.
     */
    private boolean literalPatternRuntimeEquals(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            if (a instanceof java.math.BigDecimal || b instanceof java.math.BigDecimal
                    || a instanceof java.math.BigInteger || b instanceof java.math.BigInteger) {
                try {
                    return new java.math.BigDecimal(a.toString())
                            .compareTo(new java.math.BigDecimal(b.toString())) == 0;
                } catch (NumberFormatException ignored) {
                    return a.doubleValue() == b.doubleValue();
                }
            }
            boolean integralA = isRuntimeIntegralNumber(a);
            boolean integralB = isRuntimeIntegralNumber(b);
            if (integralA && integralB) return a.longValue() == b.longValue();
            if (integralA != integralB) {
                Number integer = integralA ? a : b;
                Number floating = integralA ? b : a;
                if (!Double.isFinite(floating.doubleValue())) return false;
                return java.math.BigDecimal.valueOf(integer.longValue())
                        .compareTo(java.math.BigDecimal.valueOf(floating.doubleValue())) == 0;
            }
            return a.doubleValue() == b.doubleValue();
        }
        return java.util.Objects.equals(left, right);
    }

    private boolean isRuntimeIntegralNumber(Number value) {
        return value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long;
    }

    private boolean patternSubsumes(
            Ast.Pattern earlier,
            Ast.Pattern later,
            Type subject,
            Set<String> generics,
            Type self) {
        if (earlier instanceof Ast.WildcardPattern || earlier instanceof Ast.BindingPattern) return true;
        if (later instanceof Ast.WildcardPattern || later instanceof Ast.BindingPattern) return false;

        if (earlier instanceof Ast.LiteralPattern a && later instanceof Ast.LiteralPattern b) {
            // Runtime patterns use numeric value equality, not Java boxed
            // identity: 1 and 1.0 match the same input. A second literal
            // representing the same runtime value is unreachable.
            return literalPatternRuntimeEquals(a.value(), b.value());
        }

        if (earlier instanceof Ast.TypePattern a && later instanceof Ast.TypePattern b) {
            Type earlierType = deref(resolve(a.type(), generics, self));
            Type laterType = deref(resolve(b.type(), generics, self));
            return runtimeValueTypeCovered(laterType, earlierType, 0);
        }
        if (earlier instanceof Ast.TypePattern a && later instanceof Ast.LiteralPattern b) {
            Type earlierType = deref(resolve(a.type(), generics, self));
            Type literalType = typeOf(new Ast.LiteralExpr(b.value()), new Env(null), generics, self);
            return runtimeValueTypeCovered(literalType, earlierType, 0);
        }

        if (earlier instanceof Ast.StructuralPattern a && later instanceof Ast.StructuralPattern b) {
            Type ar = deref(resolve(a.target(), generics, self));
            Type br = deref(resolve(b.target(), generics, self));
            Record earlierShape = structuralPatternShape(a.target(), ar, generics);
            Record laterShape = structuralPatternShape(b.target(), br, generics);
            return runtimeRecordShapeCovered(laterShape, earlierShape);
        }
        if (earlier instanceof Ast.StructuralPattern a && later instanceof Ast.TypePattern b) {
            Type ar = deref(resolve(a.target(), generics, self));
            Record earlierShape = structuralPatternShape(a.target(), ar, generics);
            Type laterType = deref(resolve(b.type(), generics, self));
            return laterType instanceof Record laterRecord
                    && runtimeRecordShapeCovered(laterRecord, earlierShape);
        }

        if (earlier instanceof Ast.TuplePattern a && later instanceof Ast.TuplePattern b) {
            if (!(subject instanceof Tuple tuple)
                    || tuple.kind() != TupleKind.TUPLE
                    || a.elements().size() != b.elements().size()
                    || a.elements().size() != tuple.elements().size()) return false;
            for (int i = 0; i < a.elements().size(); i++) {
                if (!patternSubsumes(
                        a.elements().get(i),
                        b.elements().get(i),
                        tuple.elements().get(i),
                        generics,
                        self)) return false;
            }
            return true;
        }

        if (earlier instanceof Ast.ConstructorPattern a && later instanceof Ast.ConstructorPattern b) {
            if (!a.constructor().equals(b.constructor())
                    || a.arguments().size() != b.arguments().size()) return false;
            for (int i = 0; i < a.arguments().size(); i++) {
                if (!patternSubsumes(
                        a.arguments().get(i),
                        b.arguments().get(i),
                        Unknown.INSTANCE,
                        generics,
                        self)) return false;
            }
            return true;
        }
        return false;
    }

    private boolean patternsProvablyDisjoint(
            Ast.Pattern left, Ast.Pattern right, Type subject, Set<String> generics, Type self) {
        if (left instanceof Ast.WildcardPattern || right instanceof Ast.WildcardPattern
                || left instanceof Ast.BindingPattern || right instanceof Ast.BindingPattern) return false;
        if (left instanceof Ast.LiteralPattern a && right instanceof Ast.LiteralPattern b) {
            // Do not certify 1 and 1.0 as disjoint: runtime equality
            // intentionally permits equal values across numeric types.
            return !literalPatternRuntimeEquals(a.value(), b.value());
        }
        if (left instanceof Ast.ConstructorPattern a && right instanceof Ast.ConstructorPattern b) {
            if (!a.constructor().equals(b.constructor())) {
                if ((a.constructor().equals("Some") && b.constructor().equals("None"))
                        || (a.constructor().equals("None") && b.constructor().equals("Some"))
                        || (a.constructor().equals("Ok") && b.constructor().equals("Err"))
                        || (a.constructor().equals("Err") && b.constructor().equals("Ok"))) return true;
            }
            return false;
        }
        if (left instanceof Ast.TypePattern a && right instanceof Ast.TypePattern b) {
            Type at = deref(resolve(a.type(), generics, self));
            Type bt = deref(resolve(b.type(), generics, self));
            return !typesMayOverlap(at, bt);
        }
        return false;
    }

    private boolean guardsProvablyDisjoint(Ast.Expr left, Ast.Expr right) {
        GuardRange a = guardRange(left);
        GuardRange b = guardRange(right);
        if (a == null || b == null || !a.variable().equals(b.variable())) return false;
        return a.max() < b.min() || b.max() < a.min()
                || (a.max() == b.min() && (!a.maxInclusive() || !b.minInclusive()))
                || (b.max() == a.min() && (!b.maxInclusive() || !a.minInclusive()));
    }

    private GuardRange guardRange(Ast.Expr guard) {
        if (!(guard instanceof Ast.BinaryExpr binary)
                || !(binary.left() instanceof Ast.NameExpr name)
                || !(binary.right() instanceof Ast.LiteralExpr literal)
                || !(literal.value() instanceof Number number)) return null;
        double v = number.doubleValue();
        return switch (binary.operator()) {
            case "<" -> new GuardRange(name.name(), Double.NEGATIVE_INFINITY, false, v, false);
            case "<=" -> new GuardRange(name.name(), Double.NEGATIVE_INFINITY, false, v, true);
            case ">" -> new GuardRange(name.name(), v, false, Double.POSITIVE_INFINITY, false);
            case ">=" -> new GuardRange(name.name(), v, true, Double.POSITIVE_INFINITY, false);
            case "eq", "==" -> new GuardRange(name.name(), v, true, v, true);
            default -> null;
        };
    }

    private record GuardRange(
            String variable, double min, boolean minInclusive, double max, boolean maxInclusive) { }

    private boolean isFallbackArm(Ast.MatchArm arm) {
        return arm.guard() == null
                && (arm.pattern() instanceof Ast.WildcardPattern
                    || arm.pattern() instanceof Ast.BindingPattern);
    }

    private boolean matchProvablyExhaustive(
            List<Ast.MatchArm> arms, Type subject, Set<String> generics, Type self) {
        // A constructor name does not cover its entire variant when the
        // payload pattern is refutable: Some(0) + None leaves Some(1).
        // Guards never contribute to an exhaustiveness proof.
        List<Ast.Pattern> unguarded = arms.stream()
                .filter(arm -> arm.guard() == null)
                .map(Ast.MatchArm::pattern)
                .toList();
        return patternsProvablyExhaustive(unguarded, subject, generics, self, 0);
    }

    private boolean patternsProvablyExhaustive(
            List<Ast.Pattern> patterns,
            Type subject,
            Set<String> generics,
            Type self,
            int depth) {
        // Recursive user-defined types must not exhaust the checker stack.
        // A failed proof is a compile-time rejection, never runtime coverage.
        if (depth >= 32) return false;
        for (Ast.Pattern pattern : patterns) {
            if (patternCoversType(pattern, subject, generics, self)) return true;
        }
        // Every runtime value either satisfies a reifiable type predicate or
        // its exact complement. The caller filtered guarded arms already.
        // Matching TypeRef identities here avoids unsound subtype guesses.
        for (Ast.Pattern a : patterns) {
            if (!(a instanceof Ast.TypePattern positive) || positive.type().isComplement()) continue;
            for (Ast.Pattern b : patterns) {
                if (b instanceof Ast.TypePattern negative
                        && negative.type().isComplement()
                        && negative.type().complementedType().equals(positive.type())) {
                    return true;
                }
            }
        }

        if (subject instanceof Union union) {
            return union.options().stream().allMatch(option ->
                    patternsProvablyExhaustive(patterns, option, generics, self, depth + 1));
        }

        if (subject == Primitive.BOOL) {
            boolean yes = false, no = false;
            for (Ast.Pattern pattern : patterns) {
                if (pattern instanceof Ast.LiteralPattern literal) {
                    if (Boolean.TRUE.equals(literal.value())) yes = true;
                    if (Boolean.FALSE.equals(literal.value())) no = true;
                }
            }
            return yes && no;
        }

        if (subject instanceof Named named
                && named.name().equals("Option") && named.arguments().size() == 1) {
            boolean none = false;
            List<Ast.Pattern> somePayloads = new ArrayList<>();
            for (Ast.Pattern pattern : patterns) {
                if (!(pattern instanceof Ast.ConstructorPattern ctor)) continue;
                if (ctor.constructor().equals("None") && ctor.arguments().isEmpty()) none = true;
                if (ctor.constructor().equals("Some") && ctor.arguments().size() == 1) {
                    somePayloads.add(ctor.arguments().getFirst());
                }
            }
            return none && patternsProvablyExhaustive(
                    somePayloads, named.arguments().getFirst(), generics, self, depth + 1);
        }

        if (subject instanceof Named named
                && named.name().equals("Result") && named.arguments().size() == 2) {
            List<Ast.Pattern> okPayloads = new ArrayList<>();
            List<Ast.Pattern> errPayloads = new ArrayList<>();
            boolean unitOk = false;
            for (Ast.Pattern pattern : patterns) {
                if (!(pattern instanceof Ast.ConstructorPattern ctor)) continue;
                if (ctor.constructor().equals("Ok") && ctor.arguments().isEmpty()
                        && named.arguments().get(0) == Primitive.VOID) unitOk = true;
                if (ctor.arguments().size() != 1) continue;
                if (ctor.constructor().equals("Ok")) okPayloads.add(ctor.arguments().getFirst());
                if (ctor.constructor().equals("Err")) errPayloads.add(ctor.arguments().getFirst());
            }
            return (named.arguments().get(0) == Primitive.VOID ? unitOk : patternsProvablyExhaustive(
                    okPayloads, named.arguments().get(0), generics, self, depth + 1))
                    && patternsProvablyExhaustive(
                            errPayloads, named.arguments().get(1), generics, self, depth + 1);
        }

        // Open/infinite domains require an irrefutable pattern. More elaborate
        // coverage proofs (e.g. multiple partially covering tuple rows) can
        // be added without weakening this soundness boundary.
        return false;
    }

    private boolean patternCoversType(
            Ast.Pattern pattern,
            Type subject,
            Set<String> generics,
            Type self) {
        if (pattern instanceof Ast.WildcardPattern || pattern instanceof Ast.BindingPattern) {
            return true;
        }
        // Unknown/generic subjects cannot be exhausted by a particular
        // nominal or structural type pattern. Assignment compatibility with
        // Unknown is deliberately permissive, but coverage must not be.
        if (subject == Unknown.INSTANCE || subject instanceof Generic) return false;
        // A union is completely covered only if each runtime alternative is
        // covered. Assignability alone is unsafe here: int can widen to float,
        // but an int runtime value does not satisfy an 'is float' pattern.
        if (subject instanceof Union union) {
            return union.options().stream().allMatch(option ->
                    patternCoversType(pattern, option, generics, self));
        }
        if (pattern instanceof Ast.TypePattern typed) {
            // A negative type test is not an irrefutable positive coverage proof.
            // The checker handles complementary T / !T partitions separately.
            if (typed.type().isComplement()) return false;
            Type target = deref(resolve(typed.type(), generics, self));
            // Assignment compatibility is not runtime membership. In
            // particular, numeric conversions and structural compatibility
            // must not make a nominal Error/interface pattern exhaustive.
            // Use the same conservative runtime relation as arm subsumption.
            return runtimeValueTypeCovered(subject, target, 0);
        }
        if (pattern instanceof Ast.StructuralPattern structural) {
            Type resolved = deref(resolve(structural.target(), generics, self));
            Record shape = structuralPatternShape(structural.target(), resolved, generics);
            // Runtime structural patterns check every field with oresTypeMatches,
            // not assignability. int -> float widening (including nested
            // record fields) must never prove structural coverage.
            return subject instanceof Record record
                    && runtimeRecordShapeCovered(record, shape);
        }
        if (pattern instanceof Ast.TuplePattern tuplePattern
                && subject instanceof Tuple tuple
                && tuple.kind() == TupleKind.TUPLE
                && tuple.elements().size() == tuplePattern.elements().size()) {
            for (int i = 0; i < tuple.elements().size(); i++) {
                if (!patternCoversType(
                        tuplePattern.elements().get(i),
                        tuple.elements().get(i),
                        generics,
                        self)) return false;
            }
            return true;
        }
        if (pattern instanceof Ast.LiteralPattern literal
                && subject instanceof StringLiteral stringLiteral
                && literal.value() instanceof String value) {
            return stringLiteral.value().equals(value);
        }
        return false;
    }

    private boolean runtimeRecordShapeCovered(Record source, Record target) {
        return runtimeRecordShapeCovered(source, target, 0);
    }

    private boolean runtimeRecordShapeCovered(Record source, Record target, int depth) {
        if (depth >= 32) return false;
        if (!aggregateKindsCompatible(source.kind(), target.kind())) return false;
        if (target.kind() == AggregateKind.STRUCT
                && source.members().size() != target.members().size()) return false;
        for (Map.Entry<String, Type> required : target.members().entrySet()) {
            Type actual = source.members().get(required.getKey());
            if (actual == null || !runtimeValueTypeCovered(actual, required.getValue(), depth + 1)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sufficient (not necessary) condition that every runtime value of source
     * matches the target field type test. This is intentionally stricter than
     * assignment compatibility; unknown, generic and callable field shapes
     * require a fallback unless a future verified runtime descriptor proves
     * otherwise.
     */
    private boolean runtimeValueTypeCovered(Type source, Type target, int depth) {
        if (depth >= 32) return false;
        if (source == Unknown.INSTANCE || target == Unknown.INSTANCE
                || source instanceof Generic || target instanceof Generic) return false;
        if (source instanceof Union union) {
            return union.options().stream().allMatch(option -> runtimeValueTypeCovered(option, target, depth + 1));
        }
        if (target instanceof Union union) {
            return union.options().stream().anyMatch(option -> runtimeValueTypeCovered(source, option, depth + 1));
        }
        if (source instanceof Primitive left && target instanceof Primitive right) {
            if (left == Primitive.VOID || left == Primitive.NULL
                    || right == Primitive.VOID || right == Primitive.NULL) return false;
            return left == right
                    || (left == Primitive.FLOAT && right == Primitive.DECIMAL)
                    || (left == Primitive.DECIMAL && right == Primitive.FLOAT);
        }
        if (source instanceof StringLiteral && target == Primitive.STRING) return true;
        if (source instanceof Record left && target instanceof Record right) {
            return runtimeRecordShapeCovered(left, right, depth + 1);
        }
        if (source instanceof Tuple left && target instanceof Tuple right) {
            if (left.kind() != TupleKind.TUPLE || right.kind() != TupleKind.TUPLE
                    || left.elements().size() != right.elements().size()) return false;
            for (int i = 0; i < left.elements().size(); i++) {
                if (!runtimeValueTypeCovered(left.elements().get(i), right.elements().get(i), depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        // Runtime List/Array and Option/Result tests currently erase element
        // parameters. Reified generic payload predicates are not assumed.
        if (source instanceof ListType && target instanceof ListType) return true;
        if (source instanceof Named left && target instanceof Named right) {
            if (left.name().equals(right.name())) return true;
            // A proven nominal subtype is also a runtime subtype; unrelated
            // nominal values cannot be certified from structural assignability.
            if (findClass(left.name()) != null && (findClass(right.name()) != null
                    || findInterface(right.name()) != null)) {
                return assignable(source, target);
            }
        }
        return false;
    }

    /**
     * Runtime-domain intersection, deliberately stricter than assignment
     * compatibility. Numeric widening (int -> float) is not a runtime type
     * identity relation, while open nominal types may share a future/common
     * subtype and therefore cannot be declared disjoint without a proof.
     */
    private boolean typesMayOverlap(Type a, Type b) {
        if (a == Unknown.INSTANCE || b == Unknown.INSTANCE
                || a instanceof Generic || b instanceof Generic) return true;
        if (a instanceof Union union) return union.options().stream().anyMatch(option -> typesMayOverlap(option, b));
        if (b instanceof Union union) return union.options().stream().anyMatch(option -> typesMayOverlap(a, option));

        if (a instanceof StringLiteral && (b instanceof StringLiteral || b == Primitive.STRING)) return true;
        if (b instanceof StringLiteral && (a instanceof StringLiteral || a == Primitive.STRING)) return true;

        if (a instanceof Primitive ap && b instanceof Primitive bp) {
            if (ap == bp) return true;
            // FLOAT and DECIMAL currently share the same runtime scalar domain.
            return (ap == Primitive.FLOAT && bp == Primitive.DECIMAL)
                    || (ap == Primitive.DECIMAL && bp == Primitive.FLOAT);
        }

        if (a instanceof Named an && b instanceof Named bn) {
            boolean aNominal = findClass(an.name()) != null || findInterface(an.name()) != null;
            boolean bNominal = findClass(bn.name()) != null || findInterface(bn.name()) != null;
            if (aNominal && bNominal) {
                // Oreslang permits interface composition and multiple class
                // parents. Without sealed/final proof metadata, unrelated open
                // nominal types are not safely disjoint.
                return true;
            }
            if (aNominal != bNominal) return false;
            return an.name().equals(bn.name());
        }

        if (a instanceof ListType && b instanceof ListType) return true;
        if (a instanceof Tuple at && b instanceof Tuple bt) {
            if (at.kind() != bt.kind()) return false;
            if (at.elements().size() != bt.elements().size()) return false;
            for (int i = 0; i < at.elements().size(); i++) {
                if (!typesMayOverlap(at.elements().get(i), bt.elements().get(i))) return false;
            }
            return true;
        }
        if (a instanceof Record ar && b instanceof Record br) {
            return aggregateKindsCompatible(ar.kind(), br.kind());
        }
        return a.equals(b);
    }

    /**
     * Exhaustiveness may use T and !T as a logical partition only if T has
     * a concrete runtime predicate. Unknown nominal names and unreified
     * aliases cannot silently become a predicate that always returns false.
     */
    private void requireKnownRuntimePatternTarget(Ast.TypeRef ref, Set<String> aliases) {
        if (ref.isComplement()) {
            requireKnownRuntimePatternTarget(ref.complementedType(), aliases);
            return;
        }
        if (ref.isUnion()) {
            for (Ast.TypeRef member : ref.arguments()) {
                requireKnownRuntimePatternTarget(member, aliases);
            }
            return;
        }
        String name = ref.name();
        if (Set.of(
                "int", "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "uint", "bigint",
                "float", "f32", "f64", "decimal", "bool", "Bool", "string", "String",
                "Symbol", "complex", "complex64", "complex128",
                "Option", "Result", "Array", "List", "Generator", "Iterator",
                "AsyncGenerator", "AsyncIterator", "IteratorResult").contains(name)
                || findClass(name) != null || findInterface(name) != null) {
            return;
        }
        Ast.TypeAliasDecl alias = findTypeAlias(name);
        if (alias != null && alias.genericParameters().isEmpty()) {
            if (!aliases.add(name)) {
                throw new IllegalArgumentException("cyclic type alias in runtime pattern '" + name + "'");
            }
            try {
                requireRuntimeReifiableType(alias.target(), "type pattern alias");
                requireKnownRuntimePatternTarget(alias.target(), aliases);
            } finally {
                aliases.remove(name);
            }
            return;
        }
        throw new IllegalArgumentException(
                "type pattern '" + name + "' has no known runtime identity; "
                        + "declare/import a reifiable nominal type or use a supported scalar");
    }

    private void requireRuntimeReifiableType(Ast.TypeRef ref, String operator) {
        if (ref == null) throw new IllegalArgumentException(operator + " requires a runtime-reifiable type");
        if (ref.isComplement()) {
            requireRuntimeReifiableType(ref.complementedType(), operator);
            return;
        }
        if (ref.isUnion()) {
            for (Ast.TypeRef option : ref.arguments()) requireRuntimeReifiableType(option, operator);
            return;
        }
        if (ref.isBorrow() || ref.isTupleType() || ref.isRecordType() || ref.isStringLiteral()
                || ref.name().equals("$infer$") || ref.name().equals("self")
                || ref.name().equals("Fnc") || ref.name().equals("Function")) {
            throw new IllegalArgumentException(
                    operator + " requires a nominal/runtime-reifiable type; use 'matches' for structural patterns");
        }
        if (ref.inferArguments() || !ref.arguments().isEmpty()) {
            throw new IllegalArgumentException(
                    operator + " cannot test parameterized type '" + ref
                            + "' until generic runtime type arguments are reified; match constructors/structure instead");
        }
    }

    private Type resolveSharedGeneric(Type type, Map<String, Type> bindings) {
        if (type instanceof Generic generic) {
            Type bound = bindings.get(generic.name());
            return bound == null || bound == type ? type : resolveSharedGeneric(bound, bindings);
        }
        if (type instanceof Named named) {
            return new Named(named.name(), named.arguments().stream()
                    .map(argument -> resolveSharedGeneric(argument, bindings)).toList());
        }
        if (type instanceof ListType list) return new ListType(resolveSharedGeneric(list.element(), bindings));
        if (type instanceof Tuple tuple) {
            return new Tuple(tuple.elements().stream().map(element -> resolveSharedGeneric(element, bindings)).toList(), tuple.kind());
        }
        if (type instanceof Union union) {
            return Types.unionOf(union.options().stream()
                    .map(option -> resolveSharedGeneric(option, bindings))
                    .toList());
        }
        if (type instanceof Types.Not not) {
            return Types.notOf(resolveSharedGeneric(not.excluded(), bindings));
        }
        if (type instanceof Function fn) {
            return new Function(
                    fn.parameters().stream()
                            .map(parameter -> resolveSharedGeneric(parameter, bindings))
                            .toList(),
                    fn.mutableParameters(),
                    fn.async(),
                    resolveSharedGeneric(fn.result(), bindings),
                    mapFunctionEffects(fn.effects(), t -> resolveSharedGeneric(t, bindings)));
        }
        if (type instanceof Record record) {
            Map<String, Type> members = new LinkedHashMap<>();
            for (Map.Entry<String, Type> entry : record.members().entrySet()) {
                members.put(entry.getKey(), resolveSharedGeneric(entry.getValue(), bindings));
            }
            return new Record(members, record.readOnly(), record.kind());
        }
        return type;
    }

    private Type builtinFutureMember(Type receiver, String member) {
        if (!(receiver instanceof Named named)
                || !named.name().equals("Future")
                || named.arguments().size() != 1) {
            return null;
        }

        Type element = named.arguments().getFirst();
        Type unknownFuture = new Named("Future", List.of(Unknown.INSTANCE));
        return switch (member) {
            case "map" -> new Function(
                    List.of(new Function(List.of(element), Unknown.INSTANCE)),
                    unknownFuture);
            case "compose", "flatMap" -> new Function(
                    List.of(new Function(List.of(element), unknownFuture)),
                    unknownFuture);
            case "onSuccess" -> new Function(
                    List.of(new Function(List.of(element), Primitive.VOID)),
                    named);
            case "flatten" -> {
                if (!(element instanceof Named inner)
                        || !inner.name().equals("Future")
                        || inner.arguments().size() != 1) {
                    throw new IllegalArgumentException(
                            "Future.flatten requires Future<Future<T>>, got " + named);
                }
                yield new Function(List.of(), element);
            }
            default -> null;
        };
    }

    /**
     * Unlike the old Unknown-returning synthetic Function descriptors, these
     * combinators must propagate the callback's *actual* return type. Otherwise
     * Option<int> can silently widen to Option<String> and Result.and_then
     * can change its error domain without a checked conversion.
     *
     * This v1 path requires a statically typed callable result. Lambdas whose
     * return type cannot be inferred are rejected, never treated as safe.
     */
    /**
     * Contextually type an inline pipe lambda from the sum payload, rather
     * than turning unannotated pipe parameters into Unknown or $infer$.
     * Only expression-bodied lambdas have an inferable return value in v1;
     * block-bodied callbacks require an explicitly typed named function.
     *
     * As for ordinary lambdas, checking the body must not leak definite
     * initialization into its surrounding lexical scope.
     */
    /**
     * An eager sum callback is not an async/suspending continuation.
     * Inspect nested expressions as well as the top level: a containing
     * async fnc must not implicitly authorize await or blocking channel
     * operations inside a callback typed as a synchronous Function.
     * Future expression variants fail closed until their effects are audited.
     */
    private static boolean hasSuspendingSumCallbackSyntax(Ast.Expr expr) {
        if (expr instanceof Ast.AwaitExpr || expr instanceof Ast.ChannelOpExpr
                || expr instanceof Ast.DynamicSelectExpr) return true;
        if (expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return false;
        if (expr instanceof Ast.BinaryExpr binary)
            return hasSuspendingSumCallbackSyntax(binary.left())
                    || hasSuspendingSumCallbackSyntax(binary.right());
        if (expr instanceof Ast.UnaryExpr unary)
            return hasSuspendingSumCallbackSyntax(unary.operand());
        if (expr instanceof Ast.AssignExpr assignment)
            return hasSuspendingSumCallbackSyntax(assignment.target())
                    || hasSuspendingSumCallbackSyntax(assignment.value());
        if (expr instanceof Ast.ConditionalExpr conditional)
            return hasSuspendingSumCallbackSyntax(conditional.condition())
                    || hasSuspendingSumCallbackSyntax(conditional.whenTrue())
                    || hasSuspendingSumCallbackSyntax(conditional.whenFalse());
        if (expr instanceof Ast.MatchExpr match) {
            if (hasSuspendingSumCallbackSyntax(match.subject())) return true;
            for (Ast.MatchValueArm arm : match.arms())
                if ((arm.guard() != null && hasSuspendingSumCallbackSyntax(arm.guard()))
                        || hasSuspendingSumCallbackSyntax(arm.value())) return true;
            return false;
        }
        if (expr instanceof Ast.TypeTestExpr test)
            return hasSuspendingSumCallbackSyntax(test.value());
        if (expr instanceof Ast.PatternTestExpr test)
            return hasSuspendingSumCallbackSyntax(test.value());
        if (expr instanceof Ast.CastExpr cast)
            return hasSuspendingSumCallbackSyntax(cast.value());
        if (expr instanceof Ast.SpreadExpr spread)
            return hasSuspendingSumCallbackSyntax(spread.expression());
        if (expr instanceof Ast.CallExpr call)
            return hasSuspendingSumCallbackSyntax(call.callee())
                    || call.arguments().stream().anyMatch(TypeChecker::hasSuspendingSumCallbackSyntax);
        if (expr instanceof Ast.RuntimeCallExpr runtime)
            return runtime.operation().equals("cooperate")
                    || runtime.operation().equals("yield")
                    || runtime.arguments().stream().anyMatch(TypeChecker::hasSuspendingSumCallbackSyntax);
        if (expr instanceof Ast.MemberExpr member)
            return hasSuspendingSumCallbackSyntax(member.receiver());
        if (expr instanceof Ast.IndexExpr index)
            return hasSuspendingSumCallbackSyntax(index.receiver())
                    || hasSuspendingSumCallbackSyntax(index.index());
        if (expr instanceof Ast.NewExpr created)
            return created.arguments().stream().anyMatch(TypeChecker::hasSuspendingSumCallbackSyntax);
        if (expr instanceof Ast.TrapExpr trap)
            return hasSuspendingSumCallbackSyntax(trap.expression());
        if (expr instanceof Ast.ListExpr list)
            return list.elements().stream().anyMatch(TypeChecker::hasSuspendingSumCallbackSyntax);
        if (expr instanceof Ast.TupleExpr tuple)
            return tuple.elements().stream().anyMatch(TypeChecker::hasSuspendingSumCallbackSyntax);
        if (expr instanceof Ast.ObjectExpr object)
            return object.fields().stream()
                    .anyMatch(field -> hasSuspendingSumCallbackSyntax(field.value()));
        if (expr instanceof Ast.LambdaExpr nested)
            return nested.expressionBody() == null
                    || hasSuspendingSumCallbackSyntax(nested.expressionBody());
        throw new IllegalArgumentException(
                "sum callback contains an unaudited expression form: " + expr.getClass().getSimpleName());
    }

    private Function inferSumLambda(
            Ast.LambdaExpr lambda, Type input, boolean zeroArg,
            Env parent, Set<String> generics, Type self, String operation) {
        int expected = zeroArg ? 0 : 1;
        if (lambda.parameters().size() != expected) {
            throw new IllegalArgumentException(
                    operation + " lambda expects " + expected + " parameter(s)");
        }
        if (lambda.expressionBody() == null) {
            throw new IllegalArgumentException(
                    operation + " block lambda result cannot be inferred safely");
        }
        if (hasSuspendingSumCallbackSyntax(lambda.expressionBody())) {
            throw new IllegalArgumentException(
                    operation + " inline callback cannot suspend or await");
        }

        boolean nonLexical = lambda.nonLexical() || parent.descendantsNonLexical();
        Env lambdaEnv = new Env(nonLexical ? null : parent, nonLexical, !nonLexical);
        List<Type> arguments = new ArrayList<>(expected);
        if (!zeroArg) {
            Ast.Param param = lambda.parameters().getFirst();
            if (param.mutable() || param.structural()) {
                throw new IllegalArgumentException(
                        operation + " lambda cannot mutate or erase its input capability");
            }
            Type declared = param.type().name().equals("$infer$")
                    ? input : resolveParam(param, generics, self);
            requireAssignable(input, declared, operation + " lambda parameter");
            requireAssignable(declared, input, operation + " lambda parameter");
            arguments.add(declared);
            lambdaEnv.define(param.name(), declared, Ast.BindingKind.VAL);
        }
        defineImplicitArguments(lambdaEnv, arguments);

        Ast.ClassDecl previousClass = currentClassOwner;
        Ast.ClassDecl previousConstructor = currentConstructorOwner;
        Map<Env.Binding, Set<String>> initialized = parent.initializationSnapshot();
        boolean previousMaySuspend = currentMaySuspend;
        if (nonLexical) currentClassOwner = null;
        currentConstructorOwner = null;
        currentMaySuspend = false;
        try {
            Type result = typeOf(lambda.expressionBody(), lambdaEnv, generics, self);
            return new Function(arguments, result);
        } finally {
            currentMaySuspend = previousMaySuspend;
            parent.restoreInitialization(initialized);
            currentClassOwner = previousClass;
            currentConstructorOwner = previousConstructor;
        }
    }

    private Type checkTypedSumCombinator(
            Ast.CallExpr call, Named sum, String operation,
            Env env, Set<String> generics, Type self) {
        final boolean option = sum.name().equals("Option");
        final String kind = option ? "Option" : "Result";
        final Type input = sum.arguments().getFirst();
        final Type error = option ? null : sum.arguments().get(1);
        final boolean noCallback = operation.equals("transpose") || operation.equals("ok");

        if (option) {
            if (!Set.of("map", "and_then", "or_else", "filter",
                        "ok_or_else", "unwrap_or_else", "ok_or", "transpose")
                    .contains(operation)) {
                throw new IllegalArgumentException("unknown Option member '" + operation + "'");
            }
        } else if (!Set.of("map", "map_err", "and_then", "or_else",
                           "unwrap_or_else", "ok")
                .contains(operation)) {
            throw new IllegalArgumentException("unknown Result member '" + operation + "'");
        }
        if (call.typeArgumentsPresent()) {
            throw new IllegalArgumentException(kind + "." + operation
                    + " does not accept call-site type arguments");
        }
        if (call.arguments().size() != (noCallback ? 0 : 1)
                || (!noCallback && call.arguments().getFirst() instanceof Ast.SpreadExpr)) {
            throw new IllegalArgumentException(kind + "." + operation
                    + " expects " + (noCallback ? "zero arguments"
                            : operation.equals("ok_or") ? "one eager error value" : "one callable"));
        }
        if (noCallback) {
            if (operation.equals("ok")) return new Named("Option", List.of(input));
            if (input instanceof Named nested
                    && nested.name().equals("Result") && nested.arguments().size() == 2) {
                return new Named("Result", List.of(
                        new Named("Option", List.of(nested.arguments().getFirst())),
                        nested.arguments().get(1)));
            }
            throw new IllegalArgumentException(
                    "Option.transpose requires Option<Result<T,E>>");
        }

        if (operation.equals("ok_or")) {
            Type errorValue = typeOf(call.arguments().getFirst(), env, generics, self);
            if (errorValue instanceof StringLiteral) errorValue = Primitive.STRING;
            if (errorValue instanceof dev.oreslang.types.Types.BoolLiteral) errorValue = Primitive.BOOL;
            if (containsUnknown(errorValue)
                    || containsChannelBorrow(errorValue, new LinkedHashSet<>(), Map.of())) {
                throw new IllegalArgumentException("Option.ok_or requires a concrete owned error type");
            }
            return new Named("Result", List.of(input, errorValue));
        }
        final Type callbackInput = !option && (operation.equals("map_err")
                || operation.equals("or_else") || operation.equals("unwrap_or_else"))
                ? error : input;
        final boolean zeroArg = operation.equals("ok_or_else")
                || (option && (operation.equals("or_else")
                        || operation.equals("unwrap_or_else")));
        Ast.Expr callbackExpression = call.arguments().getFirst();
        Type callbackType = callbackExpression instanceof Ast.LambdaExpr lambda
                ? inferSumLambda(lambda, callbackInput, zeroArg, env, generics, self,
                        kind + "." + operation)
                : typeOf(callbackExpression, env, generics, self);
        if (!(callbackType instanceof Function callback)
                || callback.parameters().size() != (zeroArg ? 0 : 1)
                || callback.async()
                || callback.mutableParameters().contains(true)) {
            throw new IllegalArgumentException(kind + "." + operation
                    + " requires a synchronous, non-mutating "
                    + (zeroArg ? "zero-argument" : "one-argument") + " callable");
        }
        if (!zeroArg) {
            requireAssignable(callbackInput, callback.parameters().getFirst(),
                    kind + "." + operation + " callback parameter");
        }
        Type mapped = callback.result();
        // The intrinsic None constructor has Option<Unknown> until it is
        // contextually typed. Option.or_else already fixes T from its
        // receiver, so a direct zero-arg lambda returning literal None can
        // safely mean Option<T>. Do not widen arbitrary Unknown callbacks:
        // a dynamic or user-supplied Option<Unknown> is not this intrinsic.
        if (option && operation.equals("or_else")
                && callbackExpression instanceof Ast.LambdaExpr literalFallback
                && literalFallback.expressionBody() instanceof Ast.NameExpr none
                && none.name().equals("None")
                // A shadowing local named None is a value, not the builtin.
                && env.lookup("None") == null
                && mapped instanceof Named optionNone
                && optionNone.name().equals("Option")
                && optionNone.arguments().size() == 1
                && optionNone.arguments().getFirst() == Unknown.INSTANCE) {
            mapped = sum;
        }
        // String and boolean singleton literals are scalar *values*, not
        // invariant generic parameter identities.
        if (mapped instanceof StringLiteral) mapped = Primitive.STRING;
        if (mapped instanceof dev.oreslang.types.Types.BoolLiteral) mapped = Primitive.BOOL;
        // Unknown may hide inside a nested callback result (for example
        // Option<Unknown>). Do not publish it as an inferred output type.
        // Result.and_then/or_else are the only exceptions: their unchanged
        // error/success slot may be Unknown for Ok/Err constructors, and is
        // rebuilt from the receiver after the invariant check below.
        if (containsUnknown(mapped)
                && (option || (!operation.equals("and_then") && !operation.equals("or_else")))) {
            throw new IllegalArgumentException(kind + "." + operation
                    + " callback result cannot be inferred safely");
        }
        if (containsChannelBorrow(mapped, new LinkedHashSet<>(), Map.of())) {
            throw new IllegalArgumentException(kind + "." + operation
                    + " cannot publish a borrowed callback result, including nested payloads");
        }
        if (option) {
            return switch (operation) {
                case "map" -> new Named("Option", List.of(mapped));
                case "filter" -> {
                    requireAssignable(mapped, Primitive.BOOL, "Option.filter predicate");
                    yield sum;
                }
                case "ok_or_else" -> new Named("Result", List.of(input, mapped));
                case "unwrap_or_else" -> {
                    requireAssignable(mapped, input, "Option.unwrap_or_else fallback");
                    yield input;
                }
                case "or_else" -> {
                    requireAssignable(mapped, sum, "Option.or_else fallback");
                    requireAssignable(sum, mapped, "Option.or_else fallback");
                    yield sum;
                }
                case "and_then" -> {
                    if (!(mapped instanceof Named nested) ||
                            !nested.name().equals("Option") ||
                            nested.arguments().size() != 1) {
                        throw new IllegalArgumentException(
                                "Option.and_then callback must return Option<U>");
                    }
                    yield mapped;
                }
                default -> throw new IllegalArgumentException("unknown Option member '" + operation + "'");
            };
        }
        return switch (operation) {
            case "unwrap_or_else" -> {
                requireAssignable(mapped, input, "Result.unwrap_or_else fallback");
                yield input;
            }
            case "map" -> new Named("Result", List.of(mapped, error));
            case "map_err" -> new Named("Result", List.of(input, mapped));
            case "and_then", "or_else" -> {
                if (!(mapped instanceof Named nested) ||
                        !nested.name().equals("Result") ||
                        nested.arguments().size() != 2) {
                    throw new IllegalArgumentException(
                            "Result." + operation + " callback must return Result");
                }
                // and_then cannot rewrite E, and or_else cannot rewrite T.
                // Only the preserved invariant branch may remain unknown.
                // The newly-produced success/error branch must be fully
                // inferred, including every nested generic component.
                final Type produced = nested.arguments().get(
                        operation.equals("and_then") ? 0 : 1);
                if (containsUnknown(produced)) {
                    throw new IllegalArgumentException("Result." + operation
                            + " callback result cannot be inferred safely");
                }
                final Type invariant = operation.equals("and_then") ? error : input;
                final Type actual = nested.arguments().get(
                        operation.equals("and_then") ? 1 : 0);
                requireAssignable(actual, invariant, "Result." + operation + " invariant");
                requireAssignable(invariant, actual, "Result." + operation + " invariant");
                // A success-only Ok(...) callback has an unbound error slot;
                // an error-only Err(...) callback has an unbound success slot.
                // The untouched branch still carries the receiver's original
                // type, never the callback constructor's Unknown parameter.
                yield operation.equals("and_then")
                        ? new Named("Result", List.of(nested.arguments().getFirst(), error))
                        : new Named("Result", List.of(input, nested.arguments().get(1)));
            }
            default -> throw new IllegalArgumentException("unknown Result member '" + operation + "'");
        };
    }

    private Type builtinOptionResultMember(Type receiver, String member) {
        if (!(receiver instanceof Named named)) return null;
        if (named.name().equals("Option") && named.arguments().size() == 1) {
            Type element = named.arguments().getFirst();
            return switch (member) {
                case "is_some", "is_none" -> new Function(List.of(), Primitive.BOOL);
                case "unwrap" -> new Function(List.of(), element);
                case "unwrap_all" -> {
                    Type innermost = element;
                    while (innermost instanceof Named inner
                            && inner.name().equals("Option")
                            && inner.arguments().size() == 1) {
                        innermost = inner.arguments().getFirst();
                    }
                    // The runtime currently carries OptionValue tags but not a
                    // reified generic nesting depth. Fail closed rather than
                    // recursively extracting past an erased static boundary.
                    if (innermost == Unknown.INSTANCE
                            || innermost instanceof Generic
                            || innermost instanceof Union
                            || innermost instanceof SelfType
                            || (innermost instanceof Named unresolved
                                    && (unresolved.name().equals("Any")
                                            || unresolved.name().equals("Unknown")))) {
                        throw new IllegalArgumentException(
                                "Option.unwrap_all requires a concrete innermost payload type; "
                                        + "use explicit unwrap() at erased/generic boundaries");
                    }
                    yield new Function(List.of(), innermost);
                }
                case "flatten" -> {
                    if (!(element instanceof Named inner)
                            || !inner.name().equals("Option")
                            || inner.arguments().size() != 1) {
                        throw new IllegalArgumentException(
                                "Option.flatten requires Option<Option<T>>, got " + named);
                    }
                    yield new Function(List.of(), element);
                }
                case "unwrap_safe" -> new Function(
                        List.of(),
                        new Named("Result", List.of(element, new Named("OptionUnwrapError", List.of()))));
                case "expect" -> new Function(List.of(Primitive.STRING), element);
                case "unwrap_or" -> new Function(List.of(element), element);
                case "unwrap_or_else" -> new Function(
                        List.of(new Function(List.of(), element)), element);
                case "or_else" -> new Function(
                        List.of(new Function(List.of(), named)), named);
                case "ok_or" -> new Function(
                        List.of(Unknown.INSTANCE),
                        new Named("Result", List.of(element, Unknown.INSTANCE)));
                case "map" -> new Function(
                        List.of(new Function(List.of(element), Unknown.INSTANCE)),
                        new Named("Option", List.of(Unknown.INSTANCE)));
                case "and_then" -> new Function(
                        List.of(new Function(List.of(element), new Named("Option", List.of(Unknown.INSTANCE)))),
                        new Named("Option", List.of(Unknown.INSTANCE)));
                case "filter" -> new Function(
                        List.of(new Function(List.of(element), Primitive.BOOL)), named);
                case "ok_or_else" -> new Function(
                        List.of(new Function(List.of(), Unknown.INSTANCE)),
                        new Named("Result", List.of(element, Unknown.INSTANCE)));
                case "transpose" -> element instanceof Named inner
                        && inner.name().equals("Result") && inner.arguments().size() == 2
                        ? new Function(List.of(),
                                new Named("Result", List.of(
                                        new Named("Option", List.of(inner.arguments().get(0))),
                                        inner.arguments().get(1)))) : null;
                default -> null;
            };
        }
        if (named.name().equals("Result") && named.arguments().size() == 2) {
            Type ok = named.arguments().get(0);
            return switch (member) {
                case "is_ok", "is_err" -> new Function(List.of(), Primitive.BOOL);
                case "unwrap" -> new Function(List.of(), ok);
                case "unwrap_safe" -> new Function(List.of(), named);
                case "expect" -> new Function(List.of(Primitive.STRING), ok);
                case "unwrap_or" -> new Function(List.of(ok), ok);
                case "unwrap_or_else" -> new Function(
                        List.of(new Function(List.of(named.arguments().get(1)), ok)), ok);
                case "map" -> new Function(
                        List.of(new Function(List.of(ok), Unknown.INSTANCE)),
                        new Named("Result", List.of(Unknown.INSTANCE, named.arguments().get(1))));
                case "map_err" -> new Function(
                        List.of(new Function(List.of(named.arguments().get(1)), Unknown.INSTANCE)),
                        new Named("Result", List.of(ok, Unknown.INSTANCE)));
                case "and_then" -> new Function(
                        List.of(new Function(List.of(ok),
                                new Named("Result", List.of(Unknown.INSTANCE, named.arguments().get(1))))),
                        new Named("Result", List.of(Unknown.INSTANCE, named.arguments().get(1))));
                case "or_else" -> new Function(
                        List.of(new Function(List.of(named.arguments().get(1)),
                                new Named("Result", List.of(ok, Unknown.INSTANCE)))),
                        new Named("Result", List.of(ok, Unknown.INSTANCE)));
                case "ok" -> new Function(List.of(), new Named("Option", List.of(ok)));
                default -> null;
            };
        }
        return null;
    }

    private Type builtinCollectionMember(Type receiver, String member) {
        receiver = deref(receiver);
        if (receiver instanceof ListType list) {
            return switch (member) {
                case "size" -> Primitive.INT;
                case "get" -> new Function(List.of(Primitive.INT), list.element());
                case "add" -> new Function(List.of(list.element()), Primitive.VOID);
                case "set" -> new Function(
                        List.of(Primitive.INT, list.element()),
                        Primitive.VOID);
                default -> null;
            };
        }
        if (receiver instanceof Tuple tuple) {
            Type element = indexedElementUnion(tuple.elements());
            return switch (member) {
                case "size" -> Primitive.INT;
                case "get" -> new Function(List.of(Primitive.INT), element);
                default -> null;
            };
        }
        return null;
    }

    private Type builtinIteratorMember(Type receiver, String member) {
        if (!(receiver instanceof Named named) || named.arguments().size() != 1) return null;
        Type element = named.arguments().getFirst();
        return switch (named.name()) {
            case "Iterator" -> switch (member) {
                case "next" -> new Function(List.of(), new Named("IteratorResult", List.of(element)));
                case "close" -> new Function(List.of(), Primitive.VOID);
                default -> null;
            };
            case "AsyncIterator" -> switch (member) {
                case "next" -> new Function(List.of(), new Named("Future", List.of(new Named("IteratorResult", List.of(element)))));
                case "close" -> new Function(List.of(), Primitive.VOID);
                default -> null;
            };
            case "IteratorResult" -> switch (member) {
                case "done" -> Primitive.BOOL;
                case "value" -> new Named("Option", List.of(element));
                default -> null;
            };
            default -> null;
        };
    }

    private Type builtinMutexMember(Type receiver, String member) {
        if (!(receiver instanceof Named named) || named.arguments().size() != 1) return null;
        Type element = named.arguments().getFirst();
        if (named.name().equals("Mutex") || named.name().equals("SharedMutex")) {
            if (named.name().equals("SharedMutex")
                    && currentActorKind != Ast.ActorKind.NONE
                    && (member.equals("lock") || member.equals("with_lock"))) {
                throw new IllegalArgumentException(
                        "actor code cannot use blocking SharedMutex." + member
                                + "(); use try_lock() or await lock_async()");
            }
            Type guard = new Named("MutexGuard", List.of(element));
            return switch (member) {
                case "lock" -> new Function(List.of(), guard);
                case "try_lock" -> new Function(List.of(), new Named("Option", List.of(guard)));
                case "lock_async" -> new Function(List.of(), new Named("Future", List.of(guard)));
                case "with_lock" -> new Function(List.of(new Function(List.of(element), Primitive.VOID)), Primitive.VOID);
                case "is_poisoned" -> new Function(List.of(), Primitive.BOOL);
                case "recover" -> named.name().equals("SharedMutex")
                        ? new Function(List.of(new Function(List.of(element), Primitive.VOID)), Primitive.VOID)
                        : null;
                default -> null;
            };
        }
        if (named.name().equals("MutexGuard")) {
            return switch (member) {
                case "release" -> new Function(List.of(), Primitive.VOID);
                case "is_released" -> new Function(List.of(), Primitive.BOOL);
                default -> null;
            };
        }
        return null;
    }

    private Type unwrapMutexGuard(Type type) {
        if (type instanceof Named named && named.name().equals("MutexGuard") && named.arguments().size() == 1) {
            return named.arguments().getFirst();
        }
        return type;
    }

    private Type iterableElementType(Type iterable) {
        iterable = receiverDispatchType(iterable);
        if (iterable instanceof ListType list) return list.element();
        if (iterable instanceof Tuple tuple) {
            Type result = Unknown.INSTANCE;
            for (Type element : tuple.elements()) {
                result = result == Unknown.INSTANCE ? element : commonType(result, element);
            }
            return result;
        }
        if (iterable instanceof Named named) {
            if (named.name().equals("Iterator") && named.arguments().size() == 1) {
                return named.arguments().getFirst();
            }
            if (named.name().equals("AsyncIterator")) {
                throw new IllegalArgumentException(
                        "ordinary for-of cannot consume AsyncGenerator<T>; use 'for await ... of ...'");
            }
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                ResolvedMethod iteratorTarget =
                        findMethodTarget(klass, named, "Symbol.iterator", 0, new LinkedHashSet<>());
                if (iteratorTarget != null) {
                    Ast.MethodDecl iterator = iteratorTarget.method();
                    requireClassMemberVisible(
                            iterator.visibility(),
                            iteratorTarget.owner(),
                            "method",
                            iterator.name());
                    Set<String> iteratorGenerics = new HashSet<>(iteratorTarget.owner().genericParameters());
                    iteratorGenerics.addAll(iterator.genericParameters());
                    Type result = resolve(iterator.returnType(), iteratorGenerics, iteratorTarget.ownerType());
                    result = substituteGenerics(
                            result,
                            classGenericBindings(iteratorTarget.owner(), iteratorTarget.ownerType()));
                    if (result instanceof ListType list) return list.element();
                    if (result instanceof Tuple tuple) return iterableElementType(tuple);
                    if (result instanceof Named generated
                            && generated.name().equals("Iterator")
                            && generated.arguments().size() == 1) {
                        return generated.arguments().getFirst();
                    }
                    throw new IllegalArgumentException(
                            "[Symbol.iterator]() must return Array<T>, List<T>, a tuple, or Generator<T>");
                }
            }
        }
        throw new IllegalArgumentException(
                "for-of requires an array/list, tuple, Generator<T>, or a class with [Symbol.iterator]()");
    }

    private Type asyncIterableElementType(Type iterable) {
        iterable = receiverDispatchType(iterable);
        if (iterable instanceof Named named) {
            if (named.name().equals("AsyncIterator") && named.arguments().size() == 1) {
                return named.arguments().getFirst();
            }
            if (named.name().equals("ActorOutputStream")
                    && named.arguments().size() == 1) {
                return new Named(
                        "ActorOutput",
                        List.of(named.arguments().getFirst()));
            }
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                ResolvedMethod iteratorTarget =
                        findMethodTarget(klass, named, "Symbol.asyncIterator", 0, new LinkedHashSet<>());
                if (iteratorTarget != null) {
                    Ast.MethodDecl iterator = iteratorTarget.method();
                    requireClassMemberVisible(
                            iterator.visibility(),
                            iteratorTarget.owner(),
                            "method",
                            iterator.name());
                    Set<String> iteratorGenerics = new HashSet<>(iteratorTarget.owner().genericParameters());
                    iteratorGenerics.addAll(iterator.genericParameters());
                    Type result = resolve(iterator.returnType(), iteratorGenerics, iteratorTarget.ownerType());
                    result = substituteGenerics(
                            result,
                            classGenericBindings(iteratorTarget.owner(), iteratorTarget.ownerType()));
                    if (result instanceof Named generated
                            && generated.name().equals("AsyncIterator")
                            && generated.arguments().size() == 1) {
                        return generated.arguments().getFirst();
                    }
                    throw new IllegalArgumentException(
                            "[Symbol.asyncIterator]() must return AsyncGenerator<T>");
                }
            }
        }
        // Match the newer iterator prototype: for-await also admits synchronous
        // iterables, but execution still crosses the runtime-owned Future boundary.
        return iterableElementType(iterable);
    }

    private Type indexedElementUnion(java.util.Collection<Type> elements) {
        return elements.stream().map(this::widenCollectionElement)
                .reduce(Types::unionOf).orElse(Unknown.INSTANCE);
    }

    /** Folds trivial signed integer constants without performing guest arithmetic. */
    private Long constantIntegerLiteral(Ast.Expr expression) {
        if (expression instanceof Ast.LiteralExpr literal
                && literal.value() instanceof Long index) return index;
        if (expression instanceof Ast.UnaryExpr unary
                && unary.operator().equals("-")
                && unary.operand() instanceof Ast.LiteralExpr literal
                && literal.value() instanceof Long index) {
            if (index == Long.MIN_VALUE) return null;
            return -index;
        }
        return null;
    }

    private Long constantTupleIndex(Ast.Expr expression, Env env) {
        Long literal = constantIntegerLiteral(expression);
        if (literal != null) return literal;
        if (expression instanceof Ast.NameExpr name) {
            return env.lookupConstantInteger(name.name());
        }
        return null;
    }

    private String constantStringKey(Ast.Expr expression, Env env) {
        if (expression instanceof Ast.LiteralExpr literal
                && literal.value() instanceof String key) {
            return key;
        }
        if (expression instanceof Ast.NameExpr name) {
            return env.lookupConstantString(name.name());
        }
        return null;
    }

    private Type widenCollectionElement(Type type) {
        return type instanceof StringLiteral ? Primitive.STRING : type;
    }

    private Type capabilitySafeEquivalentJoin(Type a, Type b) {
        if (assignable(b, a) && assignable(a, b)) {
            // Keep the left-hand shape while overlaying any stronger readonly
            // capability carried by the equivalent right-hand value. This is
            // recursive, so List<Record>, Tuple<Record>, Option<Record>, etc.
            // cannot erase nested readonly-record capability during joins.
            return overlayReadonlyCapabilities(b, a);
        }
        return null;
    }

    private Type collectionElementJoin(Type a, Type b) {
        Type safe = capabilitySafeEquivalentJoin(a, b);
        if (safe != null) return safe;
        if (Types.isNumeric(a) && Types.isNumeric(b)) return Types.numericJoin(a, b);
        if (isStringLike(a) && isStringLike(b)) return Primitive.STRING;
        return Types.unionOf(a, b);
    }

    private Type commonType(Type a, Type b) {
        Type safe = capabilitySafeEquivalentJoin(a, b);
        if (safe != null) return safe;
        if (Types.isNumeric(a) && Types.isNumeric(b)) return Types.numericJoin(a, b);
        if (isStringLike(a) && isStringLike(b)) return Primitive.STRING;
        return Unknown.INSTANCE;
    }

    private void requireInteger(Type type, String where) {
        if (type != Primitive.INT) throw new IllegalArgumentException(where + " must be an integer type");
    }

    private Type numericJoin(Type left, Type right, String op) {
        Type result = Types.numericJoin(left, right);
        if (result == Unknown.INSTANCE) throw new IllegalArgumentException("operator '" + op + "' needs numeric operands");
        return result;
    }

    private boolean isStringLike(Type type) {
        return type == Primitive.STRING || type instanceof StringLiteral;
    }

    private void requireEqualityComparable(Type left, Type right) {
        Type a = deref(left);
        Type b = deref(right);
        if (Types.isNumeric(a) && Types.isNumeric(b)) return;
        if (isStringLike(a) && isStringLike(b)) return;
        if (typesMayOverlap(a, b) || assignable(a, b) || assignable(b, a)) return;
        throw new IllegalArgumentException(
                "equality operands have disjoint value domains: " + left + " and " + right);
    }

    private void requireIdentityBearing(Type type, String where) {
        Type value = deref(type);
        if (value instanceof Union union) {
            for (Type option : union.options()) requireIdentityBearing(option, where);
            return;
        }

        boolean valueSemanticAggregate = value instanceof Record
                || value instanceof Tuple
                || value instanceof Function;
        boolean valueSemanticNamed = value instanceof Named named
                && (named.name().equals("Option")
                    || named.name().equals("Result")
                    || named.name().equals("IteratorResult"));

        if (value instanceof Primitive || value instanceof StringLiteral
                || value instanceof ClassNamespace || value instanceof Generic
                || value == Unknown.INSTANCE
                || valueSemanticAggregate
                || valueSemanticNamed) {
            throw new IllegalArgumentException(
                    where + " must be an identity-bearing reference/handle value, but found " + value);
        }
    }

    private Record classShape(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        Record cached = classShapeCache.get(klass);
        if (cached != null) return cached;
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) {
                Map<String, Type> parentBindings = parentBindings(parentRef, klass, parent);
                for (Map.Entry<String, Type> inherited : classShape(parent, stack).members().entrySet()) {
                    mergeMember(members, inherited.getKey(), substituteGenerics(inherited.getValue(), parentBindings),
                            "multiple inheritance of " + klass.name());
                }
            }
        }
        Set<String> generics = Set.copyOf(klass.genericParameters());
        Type self = new SelfType(nominalClassType(klass));
        for (Ast.FieldDecl field : klass.fields()) mergeMember(members, field.name(), classFieldType(klass, field), "class " + klass.name());
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic()) continue;
            mergeMember(members,
                    methodContractKey(method.name(), method.arity(), method.genericParameters().size()),
                    callableContractType(method.genericParameters(), method.parameters(), method.returnType(), method.async(), false, generics, self),
                    "class " + klass.name());
        }
        stack.remove(klass);
        Record result = Record.contract(members);
        classShapeCache.put(klass, result);
        return result;
    }

    private Record publicClassShape(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) {
                Map<String, Type> parentBindings = parentBindings(parentRef, klass, parent);
                for (Map.Entry<String, Type> inherited : publicClassShape(parent, stack).members().entrySet()) {
                    mergeMember(members, inherited.getKey(), substituteGenerics(inherited.getValue(), parentBindings),
                            "public inheritance of " + klass.name());
                }
            }
        }
        Set<String> generics = Set.copyOf(klass.genericParameters());
        Type self = new SelfType(nominalClassType(klass));
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.visibility() == Ast.Visibility.PUBLIC) mergeMember(members, field.name(), classFieldType(klass, field), "class " + klass.name());
        }
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.visibility() == Ast.Visibility.PUBLIC) {
                mergeMember(members,
                        methodContractKey(method.name(), method.arity(), method.genericParameters().size()),
                        callableContractType(method.genericParameters(), method.parameters(), method.returnType(), method.async(), false, generics, self),
                        "class " + klass.name());
            }
        }
        stack.remove(klass);
        return Record.contract(members);
    }

    private Record interfaceShape(Ast.InterfaceDecl iface, Set<String> generics, Set<Ast.InterfaceDecl> stack) {
        if (!stack.add(iface)) throw new IllegalArgumentException("interface inheritance cycle involving '" + iface.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : iface.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent == null) throw new IllegalArgumentException("unknown parent interface '" + parentRef.name() + "' for " + iface.name());
            if (iface.moduleContract() != parent.moduleContract()) {
                throw new IllegalArgumentException(
                        (iface.moduleContract() ? "module contract '" : "interface '") + iface.name()
                                + "' cannot extend " + (parent.moduleContract() ? "module contract '" : "interface '")
                                + parent.name() + "' across the interface/contract boundary");
            }
            Type resolvedParent = iface.moduleContract() ? resolveModuleContractRef(parentRef, parent, generics) : resolve(parentRef, generics, null);
            if (!(resolvedParent instanceof Named parentType)) {
                throw new IllegalArgumentException("parent interface must resolve to a named type");
            }
            Map<String, Type> parentBindings = genericBindings(parent.genericParameters(), parentType.arguments(),
                    "interface " + parent.name());
            for (Map.Entry<String, Type> inherited : interfaceShape(parent, Set.copyOf(parent.genericParameters()), stack).members().entrySet()) {
                mergeMember(members, inherited.getKey(), substituteGenerics(inherited.getValue(), parentBindings),
                        "interface inheritance of " + iface.name());
            }
        }
        Type interfaceSelf = new SelfType(nominalInterfaceType(iface));
        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn) {
                mergeMember(members,
                        methodContractKey(fn.name(), fn.parameters().size(), fn.genericParameters().size()),
                        callableContractType(fn.genericParameters(), fn.parameters(), fn.returnType(), false, false, generics, interfaceSelf),
                        "interface " + iface.name());
            } else if (member instanceof Ast.InterfaceFieldDecl field) {
                mergeMember(members, field.name(), resolve(field.type(), generics, null), "interface " + iface.name());
            }
        }
        stack.remove(iface);
        return Record.contract(members);
    }

    private Named inferConstructedClassType(
            Ast.ClassDecl klass,
            List<Ast.Expr> arguments,
            Env env,
            Set<String> callerGenerics,
            Type callerSelf) {
        if (klass.genericParameters().isEmpty()) {
            throw new IllegalArgumentException("class " + klass.name() + " is not generic; remove <>");
        }

        Named patternType = nominalClassType(klass);
        Map<String, Type> bindings = new HashMap<>();
        Set<String> classGenericNames = Set.copyOf(klass.genericParameters());

        Ast.ConstructorDecl constructor = klass.constructor();
        if (constructor != null) {
            if (arguments.size() != constructor.arity()) {
                throw new IllegalArgumentException(
                        "constructor for " + klass.name() + " expects "
                                + constructor.arity() + " argument(s), got " + arguments.size());
            }
            for (int i = 0; i < arguments.size(); i++) {
                Type parameterPattern = resolveParam(
                        constructor.parameters().get(i),
                        classGenericNames,
                        patternType);
                Type actual = typeOf(arguments.get(i), env, callerGenerics, callerSelf);
                inferGenericBindings(
                        parameterPattern,
                        actual,
                        bindings,
                        Set.of(),
                        "constructor " + klass.name());
            }
        } else {
            List<ResolvedField> fields =
                    effectiveFieldTargets(klass, patternType, new LinkedHashSet<>());
            if (arguments.size() > fields.size()) {
                throw new IllegalArgumentException(
                        "constructor for " + klass.name() + " received too many positional fields");
            }
            for (int i = 0; i < arguments.size(); i++) {
                ResolvedField resolvedField = fields.get(i);
                Type fieldPattern = resolve(
                        resolvedField.field().type(),
                        Set.copyOf(resolvedField.owner().genericParameters()),
                        resolvedField.ownerType());
                fieldPattern = substituteGenerics(
                        fieldPattern,
                        classGenericBindings(resolvedField.owner(), resolvedField.ownerType()));
                Type actual = typeOf(arguments.get(i), env, callerGenerics, callerSelf);
                inferGenericBindings(
                        fieldPattern,
                        actual,
                        bindings,
                        Set.of(),
                        "constructor " + klass.name());
            }
        }

        List<Type> inferred = new ArrayList<>(klass.genericParameters().size());
        for (String generic : klass.genericParameters()) {
            Type bound = bindings.get(generic);
            if (bound == null || containsGenericNamed(bound, classGenericNames)) {
                throw new IllegalArgumentException(
                        "cannot infer class generic '" + generic + "' for constructor "
                                + klass.name() + "<>; provide explicit type arguments");
            }
            inferred.add(bound);
        }
        return new Named(qualifiedClassName(klass), inferred);
    }

    private Map<String, Type> genericBindings(List<String> names, List<Type> arguments, String owner) {
        if (names.size() != arguments.size()) {
            throw new IllegalArgumentException(owner + " expects " + names.size() + " type argument(s), got " + arguments.size());
        }
        Map<String, Type> result = new HashMap<>();
        for (int i = 0; i < names.size(); i++) result.put(names.get(i), arguments.get(i));
        return result;
    }

    private Map<String, Type> parentBindings(Ast.TypeRef parentRef, Ast.ClassDecl child, Ast.ClassDecl parent) {
        Type resolved = resolve(parentRef, Set.copyOf(child.genericParameters()), nominalClassType(child));
        if (!(resolved instanceof Named named)) throw new IllegalArgumentException("parent class must resolve to a named type");
        return genericBindings(parent.genericParameters(), named.arguments(), "class " + parent.name());
    }

    private Named concreteParentType(Ast.TypeRef parentRef, Ast.ClassDecl child, Named childType) {
        Type parentPattern = resolve(parentRef, Set.copyOf(child.genericParameters()), nominalClassType(child));
        Type concrete = substituteGenerics(parentPattern, classGenericBindings(child, childType));
        if (!(concrete instanceof Named named)) throw new IllegalArgumentException("parent class must resolve to a named type");
        return named;
    }

    private Type classFieldType(Ast.ClassDecl klass, Ast.FieldDecl field) {
        Set<String> generics = Set.copyOf(klass.genericParameters());
        if (field.type() != null) {
            // Resolve a public imported field under its *own* defining module's
            // private type aliases. Never expose those aliases in importer code.
            Map<String, Ast.TypeAliasDecl> previous = resolvingLinkedFieldAliases;
            resolvingLinkedFieldAliases = linkedClassAliases.getOrDefault(klass, Map.of());
            try {
                return resolve(field.type(), generics, null);
            } finally {
                resolvingLinkedFieldAliases = previous;
            }
        }
        if (field.initializer() == null) {
            throw new IllegalArgumentException("inferred field '" + klass.name() + "." + field.name()
                    + "' requires an initializer");
        }
        return typeOf(field.initializer(), new Env(null), generics, null);
    }

    private void validateFieldLayout(Ast.ClassDecl klass) {
        collectFieldLayout(
                klass,
                nominalClassType(klass),
                new LinkedHashMap<>(),
                new LinkedHashSet<>());
    }

    private void collectFieldLayout(
            Ast.ClassDecl klass,
            Named concreteType,
            Map<String, ResolvedField> fields,
            Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) {
            throw new IllegalArgumentException(
                    "inheritance cycle involving class '" + klass.name() + "'");
        }

        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Named parentType = concreteParentType(parentRef, klass, concreteType);
            collectFieldLayout(parent, parentType, fields, stack);
        }

        Set<String> localNames = new LinkedHashSet<>();
        for (Ast.FieldDecl field : klass.fields()) {
            if (!localNames.add(field.name())) {
                throw new IllegalArgumentException(
                        "duplicate field '" + klass.name() + "." + field.name() + "'");
            }

            ResolvedField candidate = new ResolvedField(klass, concreteType, field);
            ResolvedField previous = fields.putIfAbsent(field.name(), candidate);
            if (previous != null
                    && (previous.owner() != candidate.owner()
                            || !previous.ownerType().equals(candidate.ownerType()))) {
                throw new IllegalArgumentException(
                        "field '" + klass.name() + "." + field.name()
                                + "' collides with inherited field slot "
                                + previous.owner().name() + previous.ownerType().arguments()
                                + "; class storage field names must be unique across inheritance "
                                + "and generic diamond paths must use the same concrete instantiation");
            }
        }

        stack.remove(klass);
    }

    private List<ResolvedField> effectiveFieldTargets(Ast.ClassDecl klass, Named concreteType, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        LinkedHashMap<String, ResolvedField> fields = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Named parentType = concreteParentType(parentRef, klass, concreteType);
            for (ResolvedField field : effectiveFieldTargets(parent, parentType, stack)) fields.putIfAbsent(field.field().name(), field);
        }
        for (Ast.FieldDecl field : klass.fields()) fields.put(field.name(), new ResolvedField(klass, concreteType, field));
        stack.remove(klass);
        return List.copyOf(fields.values());
    }

    private ResolvedField findFieldTarget(Ast.ClassDecl klass, Named concreteType, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.name().equals(name)) {
                seen.remove(klass);
                return new ResolvedField(klass, concreteType, field);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Named parentType = concreteParentType(parentRef, klass, concreteType);
            ResolvedField result = findFieldTarget(parent, parentType, name, seen);
            if (result != null) {
                seen.remove(klass);
                return result;
            }
        }
        seen.remove(klass);
        return null;
    }

    private ResolvedMethod findMethodTarget(Ast.ClassDecl klass, Named concreteType, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (CallableSelector.instance(name, arity).matches(method)) {
                seen.remove(klass);
                return new ResolvedMethod(klass, concreteType, method);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Named parentType = concreteParentType(parentRef, klass, concreteType);
            ResolvedMethod result = findMethodTarget(parent, parentType, name, arity, seen);
            if (result != null) {
                seen.remove(klass);
                return result;
            }
        }
        seen.remove(klass);
        return null;
    }

    private List<Ast.MethodDecl> findMethodsByName(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return List.of();
        LinkedHashMap<Integer, Ast.MethodDecl> methods = new LinkedHashMap<>();
        for (Ast.MethodDecl method : klass.methods()) {
            CallableSelector selector = CallableSelector.of(method);
            if (selector.kind() == CallableSelector.Kind.INSTANCE && selector.name().equals(name)) {
                methods.put(selector.arity(), method);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            for (Ast.MethodDecl method : findMethodsByName(parent, name, seen)) methods.putIfAbsent(method.arity(), method);
        }
        seen.remove(klass);
        return List.copyOf(methods.values());
    }

    private Named concreteInterfaceParentType(
            Ast.TypeRef parentRef,
            Ast.InterfaceDecl child,
            Named childType) {
        Type parentPattern = resolve(parentRef, Set.copyOf(child.genericParameters()), null);
        Type concrete = substituteGenerics(
                parentPattern,
                genericBindings(child.genericParameters(), childType.arguments(), "interface " + child.name()));
        if (!(concrete instanceof Named named)) {
            throw new IllegalArgumentException("parent interface must resolve to a named type");
        }
        return named;
    }

    private ResolvedInterfaceFunction findInterfaceFunctionTarget(
            Ast.InterfaceDecl iface,
            Named concreteType,
            String name,
            int arity,
            Set<Ast.InterfaceDecl> seen) {
        if (!seen.add(iface)) return null;
        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn
                    && fn.name().equals(name)
                    && fn.parameters().size() == arity) {
                seen.remove(iface);
                return new ResolvedInterfaceFunction(iface, concreteType, fn);
            }
        }
        for (Ast.TypeRef parentRef : iface.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent == null) {
                throw new IllegalArgumentException(
                        "unknown parent interface '" + parentRef.name() + "' for " + iface.name());
            }
            Named parentType = concreteInterfaceParentType(parentRef, iface, concreteType);
            ResolvedInterfaceFunction result = findInterfaceFunctionTarget(
                    parent, parentType, name, arity, seen);
            if (result != null) {
                seen.remove(iface);
                return result;
            }
        }
        seen.remove(iface);
        return null;
    }

    private boolean hasInterfaceFunctionNamed(
            Ast.InterfaceDecl iface,
            String name,
            Set<Ast.InterfaceDecl> seen) {
        if (!seen.add(iface)) return false;
        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn && fn.name().equals(name)) {
                seen.remove(iface);
                return true;
            }
        }
        for (Ast.TypeRef parentRef : iface.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent != null && hasInterfaceFunctionNamed(parent, name, seen)) {
                seen.remove(iface);
                return true;
            }
        }
        seen.remove(iface);
        return false;
    }

    private void collectInstanceMethodNames(
            Ast.ClassDecl klass,
            Set<String> names,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic()) names.add(method.name());
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) collectInstanceMethodNames(parent, names, seen);
        }
        seen.remove(klass);
    }

    private void collectInterfaceFieldNames(
            Ast.InterfaceDecl iface,
            Set<String> names,
            Set<Ast.InterfaceDecl> seen) {
        if (!seen.add(iface)) return;
        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFieldDecl field) names.add(field.name());
        }
        for (Ast.TypeRef parentRef : iface.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent != null) collectInterfaceFieldNames(parent, names, seen);
        }
        seen.remove(iface);
    }

    private void collectInterfaceMethodNames(
            Ast.InterfaceDecl iface,
            Set<String> names,
            Set<Ast.InterfaceDecl> seen) {
        if (!seen.add(iface)) return;
        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn) names.add(fn.name());
        }
        for (Ast.TypeRef parentRef : iface.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent != null) collectInterfaceMethodNames(parent, names, seen);
        }
        seen.remove(iface);
    }

    private void requireClassMemberVisible(
            Ast.Visibility visibility,
            Ast.ClassDecl owner,
            String kind,
            String name) {
        if (visibility == Ast.Visibility.PRIVATE && currentClassOwner != owner) {
            throw new IllegalArgumentException(
                    "private " + kind + " '" + owner.name() + "." + name
                            + "' is accessible only from code declared in class " + owner.name());
        }
    }

    private Ast.ClassDecl findDeclaringClass(
            Ast.ClassDecl klass,
            Ast.MethodDecl target,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (method == target) {
                seen.remove(klass);
                return klass;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Ast.ClassDecl owner = findDeclaringClass(parent, target, seen);
            if (owner != null) {
                seen.remove(klass);
                return owner;
            }
        }
        seen.remove(klass);
        throw new IllegalStateException(
                "cannot find declaring class for method '" + target.name() + "'");
    }

    private Ast.MethodDecl findStaticFunction(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (CallableSelector.staticFunction(name, arity).matches(method)) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Ast.MethodDecl candidate = findStaticFunction(parent, name, arity, seen);
            if (candidate != null) {
                seen.remove(klass);
                return candidate;
            }
        }
        seen.remove(klass);
        return null;
    }

    private List<Ast.MethodDecl> findStaticFunctionsByName(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return List.of();
        LinkedHashMap<Integer, Ast.MethodDecl> functions = new LinkedHashMap<>();
        for (Ast.MethodDecl method : klass.methods()) {
            CallableSelector selector = CallableSelector.of(method);
            if (selector.kind() == CallableSelector.Kind.STATIC && selector.name().equals(name)) {
                functions.put(selector.arity(), method);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            for (Ast.MethodDecl fn : findStaticFunctionsByName(parent, name, seen)) functions.putIfAbsent(fn.arity(), fn);
        }
        seen.remove(klass);
        return List.copyOf(functions.values());
    }

    private Map<CallableSelector, CallableTarget> effectiveCallableTargets(
            Ast.ClassDecl klass,
            Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) {
            throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        }

        Map<CallableSelector, CallableTarget> local = new LinkedHashMap<>();
        for (Ast.MethodDecl method : klass.methods()) {
            CallableSelector selector = CallableSelector.of(method);
            CallableTarget previous = local.putIfAbsent(selector, new CallableTarget(klass, method));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate callable slot '" + selector.mangledName() + "' on class '" + klass.name() + "'");
            }
        }

        Map<CallableSelector, CallableTarget> effective = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Map<CallableSelector, CallableTarget> parentTargets =
                    effectiveCallableTargets(parent, stack);
            for (Map.Entry<CallableSelector, CallableTarget> entry : parentTargets.entrySet()) {
                CallableSelector selector = entry.getKey();
                if (local.containsKey(selector)) continue;

                CallableTarget previous = effective.putIfAbsent(selector, entry.getValue());
                if (previous != null && previous.method() != entry.getValue().method()) {
                    String label = selector.kind() == CallableSelector.Kind.STATIC
                            ? "static function" : "method";
                    throw new IllegalArgumentException(
                            "ambiguous inherited " + label + " slot '"
                                    + selector.name() + "' with arity " + selector.arity()
                                    + " on class '" + klass.name()
                                    + "'; declare an explicit " + label
                                    + " with that arity on the child");
                }
            }
        }

        effective.putAll(local);
        stack.remove(klass);
        return effective;
    }

    private Ast.ClassDecl resolveClassParent(Ast.TypeRef parentRef, Ast.ClassDecl child) {
        if (parentRef.name().equals("Object") || parentRef.name().equals("List")) return null;
        if (parentRef.name().equals("obj") || parentRef.name().equals("arr")) throw new IllegalArgumentException("inline obj/arr values cannot be subclassed; extend Object or List instead");
        Ast.ClassDecl parent = findClass(parentRef.name());
        if (parent == null) throw new IllegalArgumentException("unknown parent class '" + parentRef.name() + "' for " + child.name());
        if (parent == child) throw new IllegalArgumentException("class '" + child.name() + "' cannot extend itself");
        return parent;
    }

    private void mergeMember(Map<String, Type> members, String name, Type type, String owner) {
        Type existing = members.get(name);
        boolean methodSlot = name.contains("$arity") && existing instanceof Function && type instanceof Function;
        boolean compatible = methodSlot ? existing.equals(type)
                : existing == null || (assignable(existing, type) && assignable(type, existing));
        if (!compatible) {
            throw new IllegalArgumentException("conflicting member '" + name + "' in " + owner + ": " + existing + " vs " + type);
        }
        members.put(name, type);
    }

    private Type trapResult(boolean trapped, Type result) {
        return trapped ? new Named("Option", List.of(result)) : result;
    }

    private Function trapFunctionType(Ast.FunctionDecl fn, Set<String> generics, Type self) {
        Function raw = functionType(
                fn.parameters(),
                fn.returnType(),
                fn.async(),
                fn.generator(),
                generics,
                self);
        if (!fn.trapped()) return raw;
        return new Function(
                raw.parameters(),
                raw.mutableParameters(),
                raw.async(),
                trapResult(true, raw.result()),
                raw.effects());
    }

    private Type generatorYieldType(boolean async, Type declared) {
        if (!(declared instanceof Named named) || named.arguments().size() != 1) {
            return declared;
        }

        String name = named.name();
        boolean syncProtocol = name.equals("Iterator") || name.equals("Generator");
        boolean asyncProtocol = name.equals("AsyncIterator") || name.equals("AsyncGenerator");

        if (async && asyncProtocol) return named.arguments().getFirst();
        if (!async && syncProtocol) return named.arguments().getFirst();

        if (syncProtocol || asyncProtocol) {
            throw new IllegalArgumentException(
                    (async ? "async " : "")
                            + "generator return protocol must be "
                            + (async ? "AsyncIterator<T>" : "Iterator<T>")
                            + ", not " + declared);
        }
        return declared;
    }

    private Type callableResult(boolean async, boolean generator, Type result) {
        if (generator) {
            Type element = generatorYieldType(async, result);
            return new Named(async ? "AsyncIterator" : "Iterator", List.of(element));
        }
        return async ? new Named("Future", List.of(result)) : result;
    }

    private Function functionType(
            List<Ast.Param> params,
            Ast.TypeRef returns,
            boolean async,
            boolean generator,
            Set<String> generics,
            Type self) {
        return new Function(
                params.stream().map(p -> resolveParam(p, generics, self)).toList(),
                params.stream().map(Ast.Param::mutable).toList(),
                async,
                callableResult(async, generator, resolve(returns, generics, self)));
    }

    private Function callableContractType(
            List<String> callableGenerics,
            List<Ast.Param> params,
            Ast.TypeRef returns,
            boolean async,
            boolean generator,
            Set<String> ownerGenerics,
            Type self) {
        Set<String> all = new HashSet<>(ownerGenerics);
        all.addAll(callableGenerics);
        Function raw = functionType(params, returns, async, generator, all, self);
        // Contract comparison must preserve the fact that a slot is
        // receiver-polymorphic without baking in the declaring class. Parent,
        // child, and interface declarations of (...): self therefore compare
        // as the same slot shape while concrete (...): Foo remains distinct.
        if (self instanceof SelfType) {
            raw = (Function) bindReceiverSelf(raw, new SelfType(Unknown.INSTANCE));
        }
        if (callableGenerics.isEmpty()) return raw;

        Map<String, Type> canonical = new HashMap<>();
        for (int i = 0; i < callableGenerics.size(); i++) {
            canonical.put(callableGenerics.get(i), new Generic("$callable" + i));
        }
        return (Function) substituteGenerics(raw, canonical);
    }

    private Type resolveParam(Ast.Param param, Set<String> generics, Type self) {
        if (!param.structural()) return resolve(param.type(), generics, self);
        if (param.mutable()) {
            throw new IllegalArgumentException(
                    "structural parameter '" + param.name()
                            + "' is a read-only borrowed view and cannot be declared mut");
        }

        Type concrete = resolve(param.type(), generics, self);
        if (concrete instanceof Record record) {
            // The explicit structural modifier converts an inline record
            // spelling into a non-storable contract view. Do not leave it as
            // ordinary RECORD data: concrete structs are allowed to satisfy
            // structural contracts without becoming records.
            return new Record(record.members(), true, AggregateKind.CONTRACT);
        }
        Ast.InterfaceDecl iface = findInterface(param.type().name());
        if (iface != null) {
            if (!(concrete instanceof Named named)) {
                throw new IllegalArgumentException("structural interface must resolve to a named type");
            }
            Type shape = interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());
            Type specialized = substituteGenerics(
                    shape,
                    genericBindings(iface.genericParameters(), named.arguments(), "interface " + iface.name()));
            if (!(specialized instanceof Record record)) {
                throw new IllegalStateException("structural interface shape must be a record");
            }
            return new Record(record.members(), true, AggregateKind.CONTRACT);
        }

        Ast.ClassDecl klass = findClass(param.type().name());
        if (klass != null) {
            if (klass.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalArgumentException(
                        "structural typing cannot erase actor identity/isolation for '" + klass.name()
                                + "'; actor references remain nominal and mailbox-mediated");
            }
            if (!(concrete instanceof Named named)) {
                throw new IllegalArgumentException("structural class must resolve to a named type");
            }
            Type shape = publicClassShape(klass, new LinkedHashSet<>());
            Type specialized = substituteGenerics(shape, classGenericBindings(klass, named));
            if (!(specialized instanceof Record record)) {
                throw new IllegalStateException("structural class shape must be a record");
            }
            return new Record(record.members(), true, AggregateKind.CONTRACT);
        }

        throw new IllegalArgumentException(
                "structural requires a known local class or interface contract, got '" + param.type().name() + "'");
    }

    private void rejectStaticClassGenericReferences(
            List<Ast.Stmt> statements,
            Set<String> classGenerics,
            Ast.ClassDecl klass,
            Ast.MethodDecl method) {
        for (Ast.Stmt statement : statements) {
            if (statement instanceof Ast.BindingStmt binding) {
                rejectStaticClassGenericReference(binding.declaredType(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(binding.initializer(), classGenerics, klass, method);
            } else if (statement instanceof Ast.DestructureStmt destructure) {
                rejectStaticClassGenericReferences(destructure.initializer(), classGenerics, klass, method);
            } else if (statement instanceof Ast.ReturnStmt returned) {
                rejectStaticClassGenericReferences(returned.value(), classGenerics, klass, method);
            } else if (statement instanceof Ast.YieldStmt yielded) {
                rejectStaticClassGenericReferences(yielded.value(), classGenerics, klass, method);
            } else if (statement instanceof Ast.ExprStmt expression) {
                rejectStaticClassGenericReferences(expression.expression(), classGenerics, klass, method);
            } else if (statement instanceof Ast.DeferStmt defer) {
                rejectStaticClassGenericReferences(defer.expression(), classGenerics, klass, method);
            } else if (statement instanceof Ast.BlockStmt block) {
                rejectStaticClassGenericReferences(block.body(), classGenerics, klass, method);
            } else if (statement instanceof Ast.TaskScopeStmt taskScope) {
                rejectStaticClassGenericReferences(taskScope.body(), classGenerics, klass, method);
            } else if (statement instanceof Ast.LoopStmt loop) {
                rejectStaticClassGenericReferences(loop.body(), classGenerics, klass, method);
            } else if (statement instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    rejectStaticClassGenericReferences(branch.condition(), classGenerics, klass, method);
                    rejectStaticClassGenericReferences(branch.body(), classGenerics, klass, method);
                }
                rejectStaticClassGenericReferences(conditional.elseBody(), classGenerics, klass, method);
            } else if (statement instanceof Ast.MatchStmt matched) {
                rejectStaticClassGenericReferences(matched.subject(), classGenerics, klass, method);
                for (Ast.MatchArm arm : matched.arms()) {
                    rejectStaticClassGenericReferences(arm.pattern(), classGenerics, klass, method);
                    rejectStaticClassGenericReferences(arm.guard(), classGenerics, klass, method);
                    rejectStaticClassGenericReferences(arm.body(), classGenerics, klass, method);
                }
            } else if (statement instanceof Ast.SwitchStmt switched) {
                rejectStaticClassGenericReferences(switched.subject(), classGenerics, klass, method);
                for (Ast.SwitchCase arm : switched.cases()) {
                    for (Ast.Expr constant : arm.constants()) {
                        rejectStaticClassGenericReferences(constant, classGenerics, klass, method);
                    }
                    rejectStaticClassGenericReferences(arm.body(), classGenerics, klass, method);
                }
                rejectStaticClassGenericReferences(switched.defaultBody(), classGenerics, klass, method);
            } else if (statement instanceof Ast.TryStmt attempted) {
                rejectStaticClassGenericReferences(attempted.body(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(attempted.catchBody(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(attempted.finallyBody(), classGenerics, klass, method);
            } else if (statement instanceof Ast.SelectStmt selected) {
                for (Ast.SelectArm arm : selected.arms()) {
                    rejectStaticClassGenericReferences(
                            arm.channel(), classGenerics, klass, method);
                    rejectStaticClassGenericReferences(
                            arm.value(), classGenerics, klass, method);
                    rejectStaticClassGenericReferences(
                            arm.body(), classGenerics, klass, method);
                }
            } else if (statement instanceof Ast.ForOfDestructureStmt loop) {
                rejectStaticClassGenericReferences(loop.iterable(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(loop.body(), classGenerics, klass, method);
            } else if (statement instanceof Ast.ForOfStmt loop) {
                rejectStaticClassGenericReferences(loop.iterable(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(loop.body(), classGenerics, klass, method);
            } else if (statement instanceof Ast.ForStmt loop) {
                if (loop.initializer() != null) {
                    rejectStaticClassGenericReferences(List.of(loop.initializer()), classGenerics, klass, method);
                }
                rejectStaticClassGenericReferences(loop.condition(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(loop.update(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(loop.body(), classGenerics, klass, method);
            } else if (statement instanceof Ast.DoWhileStmt loop) {
                rejectStaticClassGenericReferences(loop.body(), classGenerics, klass, method);
                rejectStaticClassGenericReferences(loop.condition(), classGenerics, klass, method);
            }
        }
    }

    private void rejectStaticClassGenericReferences(
            Ast.Expr expression,
            Set<String> classGenerics,
            Ast.ClassDecl klass,
            Ast.MethodDecl method) {
        if (expression == null) return;
        if (expression instanceof Ast.AssignExpr assignment) {
            rejectStaticClassGenericReferences(assignment.target(), classGenerics, klass, method);
            rejectStaticClassGenericReferences(assignment.value(), classGenerics, klass, method);
        } else if (expression instanceof Ast.TypeTestExpr test) {
            rejectStaticClassGenericReferences(test.value(), classGenerics, klass, method);
            rejectStaticClassGenericReference(test.targetType(), classGenerics, klass, method);
        } else if (expression instanceof Ast.PatternTestExpr test) {
            rejectStaticClassGenericReferences(test.value(), classGenerics, klass, method);
            rejectStaticClassGenericReferences(test.pattern(), classGenerics, klass, method);
        } else if (expression instanceof Ast.CastExpr cast) {
            rejectStaticClassGenericReferences(cast.value(), classGenerics, klass, method);
            rejectStaticClassGenericReference(cast.targetType(), classGenerics, klass, method);
        } else if (expression instanceof Ast.BinaryExpr binary) {
            rejectStaticClassGenericReferences(binary.left(), classGenerics, klass, method);
            rejectStaticClassGenericReferences(binary.right(), classGenerics, klass, method);
        } else if (expression instanceof Ast.TrapExpr trapped) {
            rejectStaticClassGenericReferences(trapped.expression(), classGenerics, klass, method);
        } else if (expression instanceof Ast.UnaryExpr unary) {
            rejectStaticClassGenericReferences(unary.operand(), classGenerics, klass, method);
        } else if (expression instanceof Ast.ConditionalExpr conditional) {
            rejectStaticClassGenericReferences(conditional.condition(), classGenerics, klass, method);
            rejectStaticClassGenericReferences(conditional.whenTrue(), classGenerics, klass, method);
            rejectStaticClassGenericReferences(conditional.whenFalse(), classGenerics, klass, method);
        } else if (expression instanceof Ast.SpreadExpr spread) {
            rejectStaticClassGenericReferences(spread.expression(), classGenerics, klass, method);
        } else if (expression instanceof Ast.RuntimeCallExpr runtime) {
            for (Ast.Expr argument : runtime.arguments()) {
                rejectStaticClassGenericReferences(argument, classGenerics, klass, method);
            }
        } else if (expression instanceof Ast.CallExpr call) {
            for (Ast.TypeRef argument : call.typeArguments()) {
                rejectStaticClassGenericReference(argument, classGenerics, klass, method);
            }
            rejectStaticClassGenericReferences(call.callee(), classGenerics, klass, method);
            for (Ast.Expr argument : call.arguments()) {
                rejectStaticClassGenericReferences(argument, classGenerics, klass, method);
            }
        } else if (expression instanceof Ast.MemberExpr member) {
            rejectStaticClassGenericReferences(member.receiver(), classGenerics, klass, method);
        } else if (expression instanceof Ast.IndexExpr indexed) {
            rejectStaticClassGenericReferences(indexed.receiver(), classGenerics, klass, method);
            rejectStaticClassGenericReferences(indexed.index(), classGenerics, klass, method);
        } else if (expression instanceof Ast.NewExpr created) {
            rejectStaticClassGenericReference(created.type(), classGenerics, klass, method);
            for (Ast.Expr argument : created.arguments()) {
                rejectStaticClassGenericReferences(argument, classGenerics, klass, method);
            }
        } else if (expression instanceof Ast.AwaitExpr awaited) {
            rejectStaticClassGenericReferences(awaited.expression(), classGenerics, klass, method);
        } else if (expression instanceof Ast.ChannelOpExpr operation) {
            rejectStaticClassGenericReferences(
                    operation.channel(), classGenerics, klass, method);
            rejectStaticClassGenericReferences(
                    operation.value(), classGenerics, klass, method);
        } else if (expression instanceof Ast.DynamicSelectExpr selected) {
            rejectStaticClassGenericReferences(
                    selected.cases(), classGenerics, klass, method);
        } else if (expression instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) {
                rejectStaticClassGenericReferences(item, classGenerics, klass, method);
            }
        } else if (expression instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) {
                rejectStaticClassGenericReferences(item, classGenerics, klass, method);
            }
        } else if (expression instanceof Ast.ObjectExpr object) {
            for (Ast.StructFieldSpec field : object.declaredFields()) {
                rejectStaticClassGenericReference(field.type(), classGenerics, klass, method);
            }
            for (Ast.ObjectField field : object.fields()) {
                rejectStaticClassGenericReferences(field.value(), classGenerics, klass, method);
            }
        } else if (expression instanceof Ast.LambdaExpr lambda) {
            for (Ast.Param param : lambda.parameters()) {
                rejectStaticClassGenericReference(param.type(), classGenerics, klass, method);
            }
            rejectStaticClassGenericReferences(lambda.expressionBody(), classGenerics, klass, method);
            if (lambda.blockBody() != null) {
                rejectStaticClassGenericReferences(lambda.blockBody(), classGenerics, klass, method);
            }
        }
    }

    private void rejectStaticClassGenericReferences(
            Ast.Pattern pattern,
            Set<String> classGenerics,
            Ast.ClassDecl klass,
            Ast.MethodDecl method) {
        if (pattern instanceof Ast.TypePattern typed) {
            rejectStaticClassGenericReference(typed.type(), classGenerics, klass, method);
        } else if (pattern instanceof Ast.ConstructorPattern constructor) {
            for (Ast.Pattern nested : constructor.arguments()) {
                rejectStaticClassGenericReferences(nested, classGenerics, klass, method);
            }
        }
    }

    private void rejectStaticClassGenericReference(
            Ast.TypeRef ref,
            Set<String> classGenerics,
            Ast.ClassDecl klass,
            Ast.MethodDecl method) {
        if (ref == null || classGenerics.isEmpty()) return;
        if (ref.isBorrow()) {
            rejectStaticClassGenericReference(ref.borrowedTarget(), classGenerics, klass, method);
            return;
        }
        if (classGenerics.contains(ref.name())) {
            throw new IllegalArgumentException(
                    "static function " + klass.name() + "." + method.name()
                            + " cannot reference enclosing class generic '" + ref.name()
                            + "'; declare a static-function generic parameter instead");
        }
        for (Ast.TypeRef argument : ref.arguments()) {
            rejectStaticClassGenericReference(argument, classGenerics, klass, method);
        }
    }

    private void validateGenericArity(Ast.TypeRef ref, List<String> parameters, String owner) {
        if (ref.inferArguments()) {
            throw new IllegalArgumentException(owner + " does not permit unresolved <> here; provide explicit type arguments");
        }
        if (ref.arguments().size() != parameters.size()) {
            throw new IllegalArgumentException(owner + " expects " + parameters.size()
                    + " type argument(s), got " + ref.arguments().size());
        }
    }

    private Map<String, Type> classGenericBindings(Ast.ClassDecl klass, Named actual) {
        if (actual.arguments().size() != klass.genericParameters().size()) {
            throw new IllegalArgumentException("class " + klass.name() + " expects " + klass.genericParameters().size()
                    + " type argument(s), got " + actual.arguments().size());
        }
        Map<String, Type> bindings = new HashMap<>();
        for (int i = 0; i < klass.genericParameters().size(); i++) {
            bindings.put(klass.genericParameters().get(i), actual.arguments().get(i));
        }
        return bindings;
    }

    private void validateCallTypeArgumentMarker(Ast.CallExpr call, List<String> genericNames, String label) {
        if (call.typeArgumentsPresent() && genericNames.isEmpty()) {
            throw new IllegalArgumentException(label + " is not generic and cannot be called with <...> or <>");
        }
    }

    private Map<String, Type> explicitGenericBindings(
            List<String> genericNames,
            List<Ast.TypeRef> typeArguments,
            Set<String> callerGenerics,
            Type callerSelf,
            String label) {
        GenericCallArguments.Bound bound =
                GenericCallArguments.bind(genericNames, typeArguments, label);
        Map<String, Type> resolved = new HashMap<>();
        for (Map.Entry<String, Ast.TypeRef> entry : bound.explicitTypes().entrySet()) {
            resolved.put(entry.getKey(), resolve(entry.getValue(), callerGenerics, callerSelf));
        }
        return resolved;
    }

    /** Fail closed on forward references, self references and unsound top defaults. */
    private void validateFunctionGenericDefaults(Ast.FunctionDecl fn) {
        Set<String> declared = Set.copyOf(fn.genericParameters());
        Set<String> previous = new HashSet<>();
        for (Ast.GenericParameter spec : fn.genericSpecs()) {
            if (spec.defaultType() != null) {
                validateGenericDefaultReferences(
                        spec.defaultType(), declared, previous,
                        "function " + fn.name() + " generic '" + spec.name() + "'");
                // Validate declared types even if every call explicitly overrides the default.
                resolve(spec.defaultType(), declared, null);
            }
            previous.add(spec.name());
        }
    }

    private void validateGenericDefaultReferences(
            Ast.TypeRef ref, Set<String> declared, Set<String> previous, String label) {
        if (ref.isNamedParameter() || ref.inferArguments()
                || ref.name().equals("_") || ref.name().equals("$infer$")) {
            throw new IllegalArgumentException(label
                    + " has unsupported named/inferred syntax in its default type");
        }
        if (ref.name().equals("Unknown") || ref.name().equals("Any")) {
            throw new IllegalArgumentException(label
                    + " cannot default to Any/Unknown until safe opaque/dynamic semantics are integrated");
        }
        if (declared.contains(ref.name()) && !previous.contains(ref.name())) {
            throw new IllegalArgumentException(label
                    + " has a forward or self-referential generic default '" + ref.name() + "'");
        }
        // General resolve() deliberately permits open nominal names for other
        // source contexts. Generic defaults are an ABI promise and must not
        // turn a misspelling into an apparently concrete, unsafe specialization.
        // Keep this admission list explicit until the linked type registry
        // exposes a single authoritative "is declared type" predicate.
        boolean compilerShape = ref.isBorrow() || ref.isComplement()
                || ref.isUnion() || ref.isTupleType() || ref.isRecordType()
                || ref.isSequenceShape() || ref.isRepeatExact() || ref.isRepeatMany()
                || ref.isStringLiteral() || ref.isIntLiteral() || ref.isBoolLiteral()
                || (ref.name().startsWith("$field$") && ref.arguments().size() == 1);
        if (ref.name().startsWith("$") && !compilerShape) {
            throw new IllegalArgumentException(label
                    + " contains an invalid synthetic type '" + ref.name() + "'");
        }
        if (!ref.name().startsWith("$")
                && !declared.contains(ref.name())
                && !GENERIC_DEFAULT_BUILTINS.contains(ref.name())
                && findClass(ref.name()) == null
                && findInterface(ref.name()) == null
                && findTypeAlias(ref.name()) == null) {
            throw new IllegalArgumentException(label
                    + " references an unresolved default type '" + ref.name() + "'");
        }
        for (Ast.TypeRef argument : ref.arguments()) {
            validateGenericDefaultReferences(argument, declared, previous, label);
        }
    }

    private record ExpandedCallArgument(Ast.Expr expression, Type staticType) { }

    private List<ExpandedCallArgument> expandFixedCallArguments(
            List<Ast.Expr> arguments,
            Env env,
            Set<String> generics,
            Type self,
            String label) {
        List<ExpandedCallArgument> expanded = new ArrayList<>();
        for (Ast.Expr argument : arguments) {
            if (!(argument instanceof Ast.SpreadExpr spread)) {
                expanded.add(new ExpandedCallArgument(argument, null));
                continue;
            }

            Type spreadType = deref(typeOf(spread.expression(), env, generics, self));
            if (!(spreadType instanceof Tuple tuple) || tuple.kind() != TupleKind.TUPLE) {
                throw new IllegalArgumentException(
                        label + " spread requires a statically known tuple; got " + spreadType
                                + ". Runtime-length spread arguments require a variadic callable.");
            }
            for (Type element : tuple.elements()) {
                expanded.add(new ExpandedCallArgument(null, element));
            }
        }
        return List.copyOf(expanded);
    }

    private int fixedCallArity(
            List<Ast.Expr> arguments,
            Env env,
            Set<String> generics,
            Type self,
            String label) {
        return expandFixedCallArguments(arguments, env, generics, self, label).size();
    }

    private Type expandedCallArgumentType(
            ExpandedCallArgument argument,
            Type expected,
            Env env,
            Set<String> generics,
            Type self) {
        return argument.expression() == null
                ? argument.staticType()
                : typeOfAgainstExpected(argument.expression(), expected, env, generics, self);
    }

    private Type checkGenericCallable(
            List<String> genericNames,
            List<Ast.Param> params,
            Ast.TypeRef returnRef,
            List<Ast.Expr> arguments,
            Env env,
            Set<String> callerGenerics,
            Type callerSelf,
            Type callableSelf,
            Map<String, Type> initialBindings,
            String label) {
        return checkGenericCallable(genericNames, params, returnRef, arguments,
                env, callerGenerics, callerSelf, callableSelf,
                initialBindings, label, List.of(), Set.of());
    }

    private Type checkGenericCallable(
            List<String> genericNames,
            List<Ast.Param> params,
            Ast.TypeRef returnRef,
            List<Ast.Expr> arguments,
            Env env,
            Set<String> callerGenerics,
            Type callerSelf,
            Type callableSelf,
            Map<String, Type> initialBindings,
            String label,
            List<Ast.GenericParameter> genericSpecs,
            Set<String> inferenceHoles) {
        List<ExpandedCallArgument> expanded =
                expandFixedCallArguments(arguments, env, callerGenerics, callerSelf, label);
        if (params.size() != expanded.size()) {
            throw new IllegalArgumentException(
                    label + " arity mismatch: expected " + params.size()
                            + " argument(s), got " + expanded.size());
        }
        Set<String> unique = uniqueGenerics(genericNames, label);
        Map<String, Type> bindings = new HashMap<>(initialBindings);
        Set<String> fixedBindings = new HashSet<>(initialBindings.keySet());
        List<Type> patterns = params.stream().map(p -> resolveParam(p, unique, callableSelf)).toList();

        // Keep the result of the inference pass. Re-checking an unchanged,
        // non-lambda argument below duplicates its entire call subtree.
        // A chain of N nested calls then takes exponential rather than linear
        // typechecking time (the 128-stage value-pipeline boundary exposed it).
        // Preserve a second context-sensitive pass for lambdas and for
        // arguments whose expected type changes after generic inference.
        List<Type> inferredArgumentTypes = new ArrayList<>(expanded.size());
        for (int i = 0; i < expanded.size(); i++) {
            Type actual = expandedCallArgumentType(
                    expanded.get(i), patterns.get(i), env, callerGenerics, callerSelf);
            inferredArgumentTypes.add(actual);
            inferGenericBindings(patterns.get(i), actual, bindings, fixedBindings, label);
        }
        GenericCallArguments.applyDefaults(
                genericNames, genericSpecs, bindings, inferenceHoles,
                ref -> resolve(ref, unique, callableSelf),
                this::substituteGenerics,
                type -> containsGenericNamed(type, unique),
                label);
        GenericCallArguments.requireBound(genericNames, bindings, label);
        Set<String> unbound = new HashSet<>(unique);
        unbound.removeAll(bindings.keySet());


        for (int i = 0; i < expanded.size(); i++) {
            Type expected = substituteGenerics(patterns.get(i), bindings);
            if (containsGenericNamed(expected, unbound)) {
                throw new IllegalArgumentException("cannot infer all generic parameters for " + label + " from argument " + (i + 1));
            }
            ExpandedCallArgument argument = expanded.get(i);
            if (argument.expression() != null) {
                validateLambdaArgument(argument.expression(), expected, env, callerGenerics, callerSelf);
            }
            Type actual = argument.expression() != null
                    && !(argument.expression() instanceof Ast.LambdaExpr)
                    && expected.equals(patterns.get(i))
                    ? inferredArgumentTypes.get(i)
                    : expandedCallArgumentType(
                            argument, expected, env, callerGenerics, callerSelf);
            if (!params.get(i).structural()) {
                requireReadonlyCapabilityPreserved(
                        actual,
                        expected,
                        "argument " + (i + 1) + " to " + label);
            }
            requireMutableReferentCompatible(
                    actual, params.get(i).mutable(),
                    "argument " + (i + 1) + " to mutable parameter");
            requireAssignable(
                    actual,
                    expected,
                    "argument " + (i + 1));
        }

        Type result = substituteGenerics(resolve(returnRef, unique, callableSelf), bindings);
        if (containsGenericNamed(result, unbound)) {
            throw new IllegalArgumentException("cannot infer generic return type for " + label + "; add an inferable value parameter");
        }
        return result;
    }

    private void inferGenericBindings(Type pattern, Type actual, Map<String, Type> bindings, Set<String> fixedBindings, String label) {
        if (containsUnknown(actual)) return;
        if (pattern instanceof Generic generic) {
            if (fixedBindings.contains(generic.name())) return;
            Type previous = bindings.putIfAbsent(generic.name(), actual);
            if (previous != null) {
                Type joined = commonType(previous, actual);
                if (joined == Unknown.INSTANCE) {
                    throw new IllegalArgumentException("conflicting inference for generic '" + generic.name() + "' in " + label
                            + ": " + previous + " vs " + actual);
                }
                bindings.put(generic.name(), joined);
            }
            return;
        }
        if (pattern instanceof SelfType p && actual instanceof SelfType a) {
            inferGenericBindings(p.bound(), a.bound(), bindings, fixedBindings, label);
            return;
        }
        if (pattern instanceof Borrow p && actual instanceof Borrow a) {
            inferGenericBindings(p.target(), a.target(), bindings, fixedBindings, label);
            return;
        }
        if (pattern instanceof ListType p && actual instanceof ListType a) {
            inferGenericBindings(p.element(), a.element(), bindings, fixedBindings, label);
            return;
        }
        if (pattern instanceof Tuple p && actual instanceof Tuple a
                && p.kind() == a.kind()
                && p.elements().size() == a.elements().size()) {
            for (int i = 0; i < p.elements().size(); i++) inferGenericBindings(p.elements().get(i), a.elements().get(i), bindings, fixedBindings, label);
            return;
        }
        if (pattern instanceof Named p && actual instanceof Named a
                && p.name().equals(a.name()) && p.arguments().size() == a.arguments().size()) {
            for (int i = 0; i < p.arguments().size(); i++) inferGenericBindings(p.arguments().get(i), a.arguments().get(i), bindings, fixedBindings, label);
            return;
        }
        if (pattern instanceof Function p && actual instanceof Function a
                && p.parameters().size() == a.parameters().size()) {
            for (int i = 0; i < p.parameters().size(); i++) inferGenericBindings(p.parameters().get(i), a.parameters().get(i), bindings, fixedBindings, label);
            inferGenericBindings(p.result(), a.result(), bindings, fixedBindings, label);
        }
    }

    private static Types.CheckedEffects mapFunctionEffects(
            Types.CheckedEffects effects,
            java.util.function.Function<Type, Type> mapper) {
        return new Types.CheckedEffects(
                effects.throwsTypes().stream().map(mapper).toList(),
                effects.raisesTypes().stream().map(mapper).toList(),
                effects.panicsTypes().stream().map(mapper).toList());
    }

    private Type substituteGenerics(Type type, Map<String, Type> bindings) {
        if (type instanceof Generic generic) return bindings.getOrDefault(generic.name(), type);
        if (type instanceof SelfType receiverSelf) return new SelfType(substituteGenerics(receiverSelf.bound(), bindings));
        if (type instanceof Borrow borrow) return new Borrow(substituteGenerics(borrow.target(), bindings), borrow.mutable());
        if (type instanceof ListType list) return new ListType(substituteGenerics(list.element(), bindings));
        if (type instanceof Tuple tuple) return new Tuple(tuple.elements().stream().map(t -> substituteGenerics(t, bindings)).toList(), tuple.kind());
        if (type instanceof Union union) return Types.unionOf(union.options().stream().map(t -> substituteGenerics(t, bindings)).toList());
        if (type instanceof Types.Not not) return Types.notOf(substituteGenerics(not.excluded(), bindings));
        if (type instanceof Named named) return new Named(named.name(), named.arguments().stream().map(t -> substituteGenerics(t, bindings)).toList());
        if (type instanceof Function fn) return new Function(
                fn.parameters().stream().map(t -> substituteGenerics(t, bindings)).toList(),
                fn.mutableParameters(),
                fn.async(),
                substituteGenerics(fn.result(), bindings),
                mapFunctionEffects(fn.effects(), t -> substituteGenerics(t, bindings)));
        if (type instanceof Record record) {
            Map<String, Type> members = new LinkedHashMap<>();
            record.members().forEach((name, member) -> members.put(name, substituteGenerics(member, bindings)));
            return new Record(members, record.readOnly(), record.kind());
        }
        return type;
    }

    private boolean containsUnknown(Type type) {
        if (type == Unknown.INSTANCE) return true;
        if (type instanceof SelfType receiverSelf) return containsUnknown(receiverSelf.bound());
        if (type instanceof Borrow borrow) return containsUnknown(borrow.target());
        if (type instanceof ListType list) return containsUnknown(list.element());
        if (type instanceof Tuple tuple) return tuple.elements().stream().anyMatch(this::containsUnknown);
        if (type instanceof Union union) return union.options().stream().anyMatch(this::containsUnknown);
        if (type instanceof Types.Not not) return containsUnknown(not.excluded());
        if (type instanceof Named named) return named.arguments().stream().anyMatch(this::containsUnknown);
        if (type instanceof Function fn) return fn.parameters().stream().anyMatch(this::containsUnknown)
                || containsUnknown(fn.result())
                || fn.effects().throwsTypes().stream().anyMatch(this::containsUnknown)
                || fn.effects().raisesTypes().stream().anyMatch(this::containsUnknown)
                || fn.effects().panicsTypes().stream().anyMatch(this::containsUnknown);
        if (type instanceof Record record) return record.members().values().stream().anyMatch(this::containsUnknown);
        return false;
    }

    private boolean containsGenericNamed(Type type, Set<String> names) {
        if (names.isEmpty()) return false;
        if (type instanceof Generic generic) return names.contains(generic.name());
        if (type instanceof SelfType receiverSelf) return containsGenericNamed(receiverSelf.bound(), names);
        if (type instanceof Borrow borrow) return containsGenericNamed(borrow.target(), names);
        if (type instanceof ListType list) return containsGenericNamed(list.element(), names);
        if (type instanceof Tuple tuple) return tuple.elements().stream().anyMatch(t -> containsGenericNamed(t, names));
        if (type instanceof Union union) return union.options().stream().anyMatch(t -> containsGenericNamed(t, names));
        if (type instanceof Types.Not not) return containsGenericNamed(not.excluded(), names);
        if (type instanceof Named named) return named.arguments().stream().anyMatch(t -> containsGenericNamed(t, names));
        if (type instanceof Function fn) return fn.parameters().stream().anyMatch(t -> containsGenericNamed(t, names))
                || containsGenericNamed(fn.result(), names)
                || fn.effects().throwsTypes().stream().anyMatch(t -> containsGenericNamed(t, names))
                || fn.effects().raisesTypes().stream().anyMatch(t -> containsGenericNamed(t, names))
                || fn.effects().panicsTypes().stream().anyMatch(t -> containsGenericNamed(t, names));
        if (type instanceof Record record) return record.members().values().stream().anyMatch(t -> containsGenericNamed(t, names));
        return false;
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type self) {
        return resolve(ref, generics, self, false);
    }

    private List<Ast.TypeRef> positionalTypeArguments(Ast.TypeRef ref) {
        return ref.arguments().stream()
                .filter(argument -> !argument.isNamedParameter() && !argument.isSequenceShape())
                .toList();
    }

    private List<Ast.TypeRef> sequenceShapeDimensions(Ast.TypeRef ref) {
        return ref.arguments().stream().filter(Ast.TypeRef::isSequenceShape).toList();
    }

    private Map<String, Ast.TypeRef> namedTypeParameters(Ast.TypeRef ref) {
        Map<String, Ast.TypeRef> named = new LinkedHashMap<>();
        for (Ast.TypeRef argument : ref.arguments()) {
            if (!argument.isNamedParameter()) continue;
            String name = argument.namedParameterName();
            if (named.putIfAbsent(name, argument.namedParameterValue()) != null) {
                throw new IllegalArgumentException("duplicate named type parameter '" + name + "'");
            }
        }
        return Map.copyOf(named);
    }

    private Type resolveCollectionType(
            Ast.TypeRef ref,
            Set<String> generics,
            Type self,
            boolean allowNullMarker) {
        List<Ast.TypeRef> positional = positionalTypeArguments(ref);
        List<Ast.TypeRef> dimensions = sequenceShapeDimensions(ref);
        Map<String, Ast.TypeRef> metadata = namedTypeParameters(ref);
        validateCollectionMetadata(ref.name(), metadata);

        boolean fixed = ref.name().equals("Tuple") || ref.name().equals("FixedArray") || ref.name().equals("FixedList");

        if (dimensions.isEmpty()) {
            if (fixed) {
                throw new IllegalArgumentException(ref.name()
                        + " requires an explicit finite sequence shape, for example "
                        + (ref.name().equals("Tuple") ? "Tuple[int, string]" : ref.name() + "[4 of int]"));
            }
            if (ref.inferArguments()) return new ListType(Unknown.INSTANCE);
            if (positional.size() != 1) {
                throw new IllegalArgumentException(ref.name()
                        + " requires one element type when using legacy '<T>' syntax; "
                        + "prefer " + ref.name() + "[T]");
            }
            return new ListType(resolve(positional.getFirst(), generics, self, allowNullMarker));
        }

        if (!positional.isEmpty()) {
            throw new IllegalArgumentException(ref.name()
                    + " cannot mix positional '<T>' type arguments with '[...]' sequence shape syntax");
        }

        Integer declaredRank = metadataRank(metadata);
        if (fixed && dimensions.size() != 1) {
            throw new IllegalArgumentException(ref.name()
                    + " is a fixed one-dimensional sequence type; nested fixed layouts must be expressed through nested element types");
        }
        if (fixed && declaredRank != null && declaredRank != 1) {
            throw new IllegalArgumentException(ref.name()
                    + " has fixed arity and currently requires rank=1; use nested Tuple/FixedArray element types for exact multidimensional layout");
        }
        if (declaredRank != null && declaredRank != dimensions.size()) {
            if (dimensions.size() != 1) {
                throw new IllegalArgumentException("rank=" + declaredRank + " disagrees with "
                        + dimensions.size() + " explicit '[...]' dimensions");
            }
        }

        if (dimensions.size() > 1) {
            Ast.TypeRef base = singleHomogeneousDimensionElement(dimensions.getFirst(), ref.name());
            for (int i = 1; i < dimensions.size(); i++) {
                Ast.TypeRef next = singleHomogeneousDimensionElement(dimensions.get(i), ref.name());
                if (!sameTypeRefIgnoringCompilerMetadata(base, next)) {
                    throw new IllegalArgumentException(ref.name()
                            + " multidimensional shorthand currently requires the same homogeneous element type in every dimension");
                }
            }
            Type nested = resolve(base, generics, self, allowNullMarker);
            int rank = declaredRank == null ? dimensions.size() : declaredRank;
            for (int i = 0; i < rank; i++) nested = new ListType(nested);
            return nested;
        }

        Ast.TypeRef shape = dimensions.getFirst();
        if (fixed) {
            List<Ast.TypeRef> exact = expandFiniteSequencePatterns(shape.arguments(), ref.name());
            validateFixedExtentMetadata(ref.name(), metadata, exact.size());
            TupleKind kind = switch (ref.name()) {
                case "FixedArray" -> TupleKind.FIXED_ARRAY;
                case "FixedList" -> TupleKind.FIXED_LIST;
                default -> TupleKind.TUPLE;
            };
            return new Tuple(exact.stream()
                    .map(element -> resolve(element, generics, self, allowNullMarker))
                    .toList(), kind);
        }

        if (shape.arguments().size() != 1) {
            throw new IllegalArgumentException(
                    "finite sequence pattern cannot be used directly with " + ref.name()
                            + "; use " + ref.name() + "[...(T1, T2, ...)] for repeating groups, "
                            + "a union such as " + ref.name() + "[T1 | T2], or FixedArray[T1, T2, ...]");
        }

        Ast.TypeRef pattern = shape.arguments().getFirst();
        Type element;
        if (pattern.isRepeatMany()) {
            List<Ast.TypeRef> unit = expandFiniteSequencePatterns(pattern.arguments(), ref.name());
            element = unionResolved(unit, generics, self, allowNullMarker);
        } else if (pattern.isRepeatExact()) {
            throw new IllegalArgumentException(ref.name() + "[" + pattern.repeatExactCount()
                    + " of T] declares a fixed extent; use FixedArray[" + pattern.repeatExactCount()
                    + " of T] or " + ref.name() + "[T] for runtime extent");
        } else {
            element = resolve(pattern, generics, self, allowNullMarker);
        }

        int rank = declaredRank == null ? 1 : declaredRank;
        Type result = element;
        for (int i = 0; i < rank; i++) result = new ListType(result);
        return result;
    }

    private Ast.TypeRef singleHomogeneousDimensionElement(Ast.TypeRef shape, String collectionName) {
        if (!shape.isSequenceShape() || shape.arguments().size() != 1) {
            throw new IllegalArgumentException(collectionName
                    + " multidimensional shorthand requires one homogeneous type per '[...]' dimension");
        }
        Ast.TypeRef element = shape.arguments().getFirst();
        if (element.isRepeatMany() || element.isRepeatExact()) {
            throw new IllegalArgumentException(collectionName
                    + " multidimensional shorthand does not combine with repeat patterns yet");
        }
        return element;
    }

    private List<Ast.TypeRef> expandFiniteSequencePatterns(List<Ast.TypeRef> patterns, String collectionName) {
        List<Ast.TypeRef> expanded = new ArrayList<>();
        for (Ast.TypeRef pattern : patterns) {
            if (pattern.isRepeatMany()) {
                throw new IllegalArgumentException(collectionName
                        + " finite sequence shape cannot contain unbounded '...' repetition");
            }
            if (pattern.isRepeatExact()) {
                long count = pattern.repeatExactCount();
                if (count > 1_000_000L) {
                    throw new IllegalArgumentException("sequence repeat count is too large: " + count);
                }
                Ast.TypeRef repeated = pattern.arguments().getFirst();
                if (repeated.isRepeatMany() || repeated.isRepeatExact() || repeated.isSequenceShape()) {
                    throw new IllegalArgumentException("nested counted sequence patterns require a grouped finite pattern");
                }
                for (long i = 0; i < count; i++) expanded.add(repeated);
            } else {
                expanded.add(pattern);
            }
        }
        if (expanded.isEmpty()) {
            throw new IllegalArgumentException(collectionName + " sequence shape cannot expand to zero elements");
        }
        return List.copyOf(expanded);
    }

    private Type unionResolved(
            List<Ast.TypeRef> refs,
            Set<String> generics,
            Type self,
            boolean allowNullMarker) {
        List<Type> resolved = refs.stream()
                .map(type -> resolve(type, generics, self, allowNullMarker))
                .map(this::widenCollectionElement)
                .toList();
        return Types.unionOf(resolved);
    }

    private void validateCollectionMetadata(String collectionName, Map<String, Ast.TypeRef> metadata) {
        Set<String> allowed = Set.of(
                "growth_policy", "allocator", "align", "inline_capacity",
                "max_capacity", "storage", "rank");
        for (Map.Entry<String, Ast.TypeRef> entry : metadata.entrySet()) {
            String name = entry.getKey();
            Ast.TypeRef value = entry.getValue();
            if (name.equals("capacity")) {
                throw new IllegalArgumentException("'capacity' is runtime instance state; pass it to the constructor '(...)', not '<...>'");
            }
            boolean fixedExtent = collectionName.equals("Tuple")
                    || collectionName.equals("FixedArray")
                    || collectionName.equals("FixedList");
            if (name.equals("size") && !fixedExtent) {
                throw new IllegalArgumentException("'size' is runtime instance state for resizable collections; "
                        + "pass it to the constructor '(...)', not '<...>'");
            }
            if (!allowed.contains(name) && !name.equals("size")) {
                throw new IllegalArgumentException("unknown named type parameter '" + name
                        + "' for " + collectionName);
            }
            if (Set.of("align", "inline_capacity", "max_capacity", "rank", "size").contains(name)) {
                if (!value.isIntLiteral()) {
                    throw new IllegalArgumentException("'" + name + "' requires an integer compile-time value");
                }
                long number = value.intLiteralValue();
                if (name.equals("align")) {
                    if (number <= 0 || (number & (number - 1)) != 0) {
                        throw new IllegalArgumentException("'align' must be a positive power of two");
                    }
                } else if (name.equals("rank")) {
                    if (number <= 0 || number > 32) {
                        throw new IllegalArgumentException("'rank' must be between 1 and 32");
                    }
                } else if (number < 0) {
                    throw new IllegalArgumentException("'" + name + "' cannot be negative");
                }
            }
        }
    }

    private void validateFixedExtentMetadata(
            String collectionName,
            Map<String, Ast.TypeRef> metadata,
            int exactSize) {
        Ast.TypeRef size = metadata.get("size");
        if (size != null && size.intLiteralValue() != exactSize) {
            throw new IllegalArgumentException(collectionName + " size=" + size.intLiteralValue()
                    + " disagrees with fixed sequence arity " + exactSize);
        }
        Ast.TypeRef inlineCapacity = metadata.get("inline_capacity");
        if (inlineCapacity != null && inlineCapacity.intLiteralValue() < exactSize) {
            throw new IllegalArgumentException(collectionName + " inline_capacity="
                    + inlineCapacity.intLiteralValue() + " is smaller than fixed sequence arity " + exactSize);
        }
        Ast.TypeRef maxCapacity = metadata.get("max_capacity");
        if (maxCapacity != null && maxCapacity.intLiteralValue() < exactSize) {
            throw new IllegalArgumentException(collectionName + " max_capacity="
                    + maxCapacity.intLiteralValue() + " is smaller than fixed sequence arity " + exactSize);
        }
    }

    private Integer metadataRank(Map<String, Ast.TypeRef> metadata) {
        Ast.TypeRef rank = metadata.get("rank");
        return rank == null ? null : Math.toIntExact(rank.intLiteralValue());
    }

    private boolean sameTypeRefIgnoringCompilerMetadata(Ast.TypeRef left, Ast.TypeRef right) {
        if (!left.name().equals(right.name()) || left.inferArguments() != right.inferArguments()) return false;
        List<Ast.TypeRef> leftArgs = positionalTypeArguments(left);
        List<Ast.TypeRef> rightArgs = positionalTypeArguments(right);
        if (leftArgs.size() != rightArgs.size()) return false;
        for (int i = 0; i < leftArgs.size(); i++) {
            if (!sameTypeRefIgnoringCompilerMetadata(leftArgs.get(i), rightArgs.get(i))) return false;
        }
        return true;
    }

    private void validateSequenceLiteralAgainstDeclared(
            Ast.TypeRef declared,
            Ast.ListExpr literal,
            Env env,
            Set<String> generics,
            Type self) {
        String name = declared.name();
        if (!Set.of("Array", "List", "Vector", "Slice", "FixedArray", "FixedList").contains(name)) return;
        List<Ast.TypeRef> dimensions = sequenceShapeDimensions(declared);
        if (dimensions.isEmpty() || dimensions.size() > 1) return;
        Integer declaredRank = metadataRank(namedTypeParameters(declared));
        if (declaredRank != null && declaredRank > 1) {
            // Nested list literals are checked against the fully resolved rank-N
            // type by the ordinary assignability pass below. Phase-by-phase
            // flat sequence validation only applies to rank-1 collections.
            return;
        }

        Ast.TypeRef shape = dimensions.getFirst();
        if (name.equals("FixedArray") || name.equals("FixedList")) {
            List<Ast.TypeRef> exact = expandFiniteSequencePatterns(shape.arguments(), name);
            if (literal.elements().size() != exact.size()) {
                throw new IllegalArgumentException(name + " literal has " + literal.elements().size()
                        + " element(s), but its fixed sequence shape requires " + exact.size());
            }
            for (int i = 0; i < exact.size(); i++) {
                Type expected = resolve(exact.get(i), generics, self);
                Type actual = typeOf(literal.elements().get(i), env, generics, self);
                requireAssignable(actual, expected, name + " literal element " + i);
            }
            return;
        }

        if (shape.arguments().size() != 1) return;
        Ast.TypeRef pattern = shape.arguments().getFirst();
        if (pattern.isRepeatMany()) {
            List<Ast.TypeRef> unit = expandFiniteSequencePatterns(pattern.arguments(), name);
            if (literal.elements().size() % unit.size() != 0) {
                throw new IllegalArgumentException(name + " literal does not complete repeating sequence group of "
                        + unit.size() + " element(s)");
            }
            for (int i = 0; i < literal.elements().size(); i++) {
                Ast.TypeRef expectedRef = unit.get(i % unit.size());
                Type expected = resolve(expectedRef, generics, self);
                Type actual = typeOf(literal.elements().get(i), env, generics, self);
                requireAssignable(actual, expected, name + " repeating sequence element " + i);
            }
        } else if (!pattern.isRepeatExact()) {
            Type expected = resolve(pattern, generics, self);
            for (int i = 0; i < literal.elements().size(); i++) {
                requireAssignable(typeOf(literal.elements().get(i), env, generics, self),
                        expected, name + " literal element " + i);
            }
        }
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type self, boolean allowNullMarker) {
        if (ref == null) return Unknown.INSTANCE;
        if (ref.name().equals("_")) {
            // '_' has meaning only as an entire generic *call* argument.
            // In ordinary type positions it is neither a type variable nor
            // an open nominal type; accepting it fabricates a concrete name.
            throw new IllegalArgumentException(
                    "bare '_' inference hole is only permitted as a whole generic call type argument");
        }
        if (ref.isNamedParameter()) {
            throw new IllegalArgumentException(
                    "named generic type arguments in type position are not supported yet; "
                            + "use positional type arguments");
        }
        if (ref.isBorrow()) return new Borrow(resolve(ref.borrowedTarget(), generics, self, allowNullMarker), ref.mutableBorrow());
        if (ref.isComplement()) return Types.notOf(resolve(ref.complementedType(), generics, self, allowNullMarker));
        if (ref.isBoolLiteral()) return new Types.BoolLiteral(ref.boolLiteralValue());
        if (ref.isStringLiteral()) return new StringLiteral(ref.stringLiteralValue());
        if (ref.name().equals("$infer$")) return Unknown.INSTANCE;
        if (ref.name().equals("self")) {
            if (self == null) {
                throw new IllegalArgumentException(
                        "'self' type is only available in an instance receiver context");
            }
            return self;
        }
        if (ref.name().equals("null")) {
            if (!allowNullMarker) throw new IllegalArgumentException("null is not a standalone type; it is only legal as Option<null>");
            return Primitive.NULL;
        }
        if (ref.isUnion()) {
            return Types.unionOf(ref.arguments().stream().map(option -> resolve(option, generics, self, allowNullMarker)).toList());
        }
        if (ref.isTupleType()) {
            Map<String, Ast.TypeRef> metadata = namedTypeParameters(ref);
            validateCollectionMetadata("Tuple", metadata);
            List<Ast.TypeRef> elements = positionalTypeArguments(ref);
            validateFixedExtentMetadata("Tuple", metadata, elements.size());
            return new Tuple(elements.stream()
                    .map(element -> resolve(element, generics, self, allowNullMarker))
                    .toList(), TupleKind.TUPLE);
        }
        if (ref.isClosedAggregateType()) {
            Map<String, Type> members = new LinkedHashMap<>();
            for (Map.Entry<String, Ast.TypeRef> member : ref.aggregateMembers().entrySet()) {
                members.put(member.getKey(), resolve(member.getValue(), generics, self, allowNullMarker));
            }
            return ref.isStructType()
                    ? Record.struct(members)
                    : new Record(members, true, AggregateKind.RECORD);
        }
        if (generics.contains(ref.name())) {
            if (!ref.arguments().isEmpty() || ref.inferArguments()) {
                throw new IllegalArgumentException(
                        "generic type variable '" + ref.name() + "' cannot be parameterized");
            }
            return new Generic(ref.name());
        }
        Type scopedType = currentTypeScope == null ? null : currentTypeScope.lookupInferredType(ref.name());
        if (scopedType != null) {
            if (!ref.arguments().isEmpty() || ref.inferArguments()) {
                throw new IllegalArgumentException(
                        "inferred type alias '" + ref.name() + "' does not accept generic arguments");
            }
            return scopedType;
        }
        if (inferredTypeNames.contains(ref.name())) {
            throw new IllegalArgumentException(
                    "inferred type alias '" + ref.name() + "' is out of scope or used before initialization");
        }

        rejectUnimportedTypeDependency(ref.name());

        // A non-public alias used by a linked class's public field is *not*
        // an imported type name in user code. Only the field's defining
        // module may expand it; otherwise a private nominal could escape.
        if (resolvingLinkedFieldAliases.get(ref.name()) == null
                && hiddenLinkedAliasNames.contains(ref.name())
                && !typeAliases.containsKey(ref.name())
                && !classes.containsKey(ref.name())) {
            throw new IllegalArgumentException(
                    "private linked type alias '" + ref.name()
                            + "' is not imported; use the public class field contract");
        }

        Ast.TypeAliasDecl alias = resolvingLinkedFieldAliases.get(ref.name());
        if (alias == null) alias = findTypeAlias(ref.name());
        if (alias != null) {
            if (alias.genericParameters().size() != ref.arguments().size()) {
                throw new IllegalArgumentException("type alias '" + alias.name() + "' expects " + alias.genericParameters().size()
                        + " type argument(s), got " + ref.arguments().size());
            }
            if (!resolvingAliases.add(alias)) throw new IllegalArgumentException("type alias cycle involving '" + alias.name() + "'");
            try {
                Map<String, Ast.TypeRef> substitutions = new HashMap<>();
                for (int i = 0; i < alias.genericParameters().size(); i++) {
                    substitutions.put(alias.genericParameters().get(i), ref.arguments().get(i));
                }
                return resolve(substituteAliasType(alias.target(), substitutions), generics, self, allowNullMarker);
            } finally {
                resolvingAliases.remove(alias);
            }
        }

        // Primitive and scalar built-in types do not accept higher-kinded
        // application. Previously e.g. int<string> silently resolved to int.
        if (Set.of("i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64",
                "int", "uint", "bigint", "f32", "f64", "float", "decimal",
                "complex64", "complex128", "complex", "string", "String",
                "void").contains(ref.name())
                && (ref.inferArguments() || !ref.arguments().isEmpty())) {
            throw new IllegalArgumentException(
                    "non-generic built-in type '" + ref.name() + "' cannot accept type arguments");
        }

        return switch (ref.name()) {
            case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "int", "uint", "bigint" -> Primitive.INT;
            case "f32", "f64", "float" -> Primitive.FLOAT;
            case "decimal" -> Primitive.DECIMAL;
            case "complex64", "complex128", "complex" -> Primitive.COMPLEX;
            case "bool", "Bool" -> {
                if (!ref.arguments().isEmpty() || ref.inferArguments()) {
                    throw new IllegalArgumentException("bool/boolean does not accept type arguments or sequence shapes");
                }
                yield Primitive.BOOL;
            }
            case "string", "String" -> Primitive.STRING;
            case "Symbol" -> {
                if (ref.inferArguments() || !ref.arguments().isEmpty()) {
                    throw new IllegalArgumentException("Symbol does not accept type arguments");
                }
                yield new Named("$builtin$Symbol", List.of());
            }
            case "void" -> Primitive.VOID;
            case "Array", "List", "Vector", "Slice", "Tuple", "FixedArray", "FixedList" -> {
                Ast.ClassDecl declared = findClass(ref.name());
                if (!Set.of("Array", "List").contains(ref.name()) && declared != null
                        && ref.arguments().stream().noneMatch(argument ->
                                argument.isSequenceShape() || argument.isNamedParameter())) {
                    validateGenericArity(ref, declared.genericParameters(), "class " + declared.name());
                    yield new Named(qualifiedClassName(declared),
                            ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
                }
                yield resolveCollectionType(ref, generics, self, allowNullMarker);
            }
            case "DynamicStruct" -> throw new IllegalArgumentException(
                    "DynamicStruct has been removed for AOT-safe static shape semantics; "
                            + "use infer struct{...} or struct{field: Type,...}{...}");
            case "ActorId", "ActorKind" -> {
                if (ref.inferArguments() || !ref.arguments().isEmpty()) {
                    throw new IllegalArgumentException(
                            ref.name() + " does not accept type arguments");
                }
                yield new Named(ref.name(), List.of());
            }
            case "ActorStream" -> {
                if (ref.inferArguments() || ref.arguments().size() != 2)
                    throw new IllegalArgumentException(
                            "ActorStream<I,O> requires two explicit endpoint types");
                yield new Named("ActorStream", List.of(
                        resolve(ref.arguments().get(0), generics, self),
                        resolve(ref.arguments().get(1), generics, self)));
            }
            case "StreamOperator" -> throw new IllegalArgumentException(
                    "StreamOperator<I,O> is a reserved actor implements marker, not a runtime value type");
            case "ActorMail", "ActorOutput", "ActorOutputStream" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) {
                    throw new IllegalArgumentException(
                            ref.name()
                                    + " requires exactly one explicit payload type");
                }
                Type payload = resolve(
                        ref.arguments().getFirst(),
                        generics,
                        self);
                if (payload == Primitive.VOID) {
                    throw new IllegalArgumentException(
                            ref.name() + "<void> is invalid");
                }
                yield new Named(ref.name(), List.of(payload));
            }
            case "Option" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("Option requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self, true);
                yield new Named("Option", List.of(element));
            }
            case "Result" -> {
                if (ref.inferArguments() || ref.arguments().size() != 2) {
                    throw new IllegalArgumentException("Result requires exactly two explicit type arguments");
                }
                yield new Named("Result", List.of(
                        resolve(ref.arguments().get(0), generics, self),
                        resolve(ref.arguments().get(1), generics, self)));
            }
            case "MutexGuard" -> throw new IllegalArgumentException(
                    "MutexGuard<T> is compiler-managed and cannot be named in source declarations; acquire it from lock()/try_lock()/lock_async()");
            case "Mutex" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("Mutex requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self);
                if (element == Primitive.VOID) throw new IllegalArgumentException("Mutex<void> is invalid");
                if (element instanceof Borrow) {
                    throw new IllegalArgumentException("Mutex<T> requires an owned value type; borrowed payload types are invalid");
                }
                yield new Named("Mutex", List.of(element));
            }
            case "Box", "Arc", "Weak", "Rc", "RefCell", "RwLock" -> {
                Ast.ClassDecl declared = findClass(ref.name());
                if (declared != null) {
                    validateGenericArity(
                            ref,
                            declared.genericParameters(),
                            "class " + declared.name());
                    yield new Named(
                            qualifiedClassName(declared),
                            ref.arguments().stream()
                                    .map(arg -> resolve(arg, generics, self))
                                    .toList());
                }
                if (ref.inferArguments() || ref.arguments().size() != 1) {
                    throw new IllegalArgumentException(
                            ref.name() + " requires exactly one explicit type argument");
                }
                Type element = resolve(ref.arguments().getFirst(), generics, self);
                if (element == Primitive.VOID) {
                    throw new IllegalArgumentException(ref.name() + "<void> is invalid");
                }
                if (element instanceof Borrow) {
                    throw new IllegalArgumentException(
                            ref.name() + "<T> requires an owned value type until explicit lifetime parameters are supported");
                }
                yield new Named(ref.name(), List.of(element));
            }
            case "Channel" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) {
                    throw new IllegalArgumentException("Channel requires exactly one explicit type argument");
                }
                Type element = resolve(ref.arguments().getFirst(), generics, self);
                validateChannelPayloadType(element, "Channel<T>");
                yield new Named("Channel", List.of(element));
            }
            case "Future" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("Future requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self);
                // Future<void> is the canonical completion type for nb writech.
                // OresFuture supports a null terminal payload, so void is valid here.
                yield new Named("Future", List.of(element));
            }
            case "Generator", "AsyncGenerator", "Iterator", "AsyncIterator", "IteratorResult" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) {
                    throw new IllegalArgumentException(ref.name() + " requires exactly one explicit type argument");
                }
                Type element = resolve(ref.arguments().getFirst(), generics, self);
                if (element == Primitive.VOID) {
                    throw new IllegalArgumentException(ref.name() + "<void> is invalid");
                }
                yield new Named(ref.name().equals("Generator") ? "Iterator" : ref.name().equals("AsyncGenerator") ? "AsyncIterator" : ref.name(), List.of(element));
            }
            case "SharedMutex" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("SharedMutex requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self);
                if (element == Primitive.VOID) throw new IllegalArgumentException("SharedMutex<void> is invalid");
                if (!isSharedMutexPayloadSafe(element, new LinkedHashSet<>(), Map.of())) {
                    throw new IllegalArgumentException(
                            "SharedMutex<T> requires a concrete shared-safe type; borrows, Mutex, MutexGuard, Future, closures, and unresolved generic/dynamic values are not shareable");
                }
                yield new Named("SharedMutex", List.of(element));
            }
            case "Fnc", "Function" -> {
                List<Type> args = ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList();
                if (args.isEmpty()) {
                    throw new IllegalArgumentException(ref.name() + " requires at least a result type");
                }
                yield new Function(args.subList(0, args.size() - 1), args.getLast());
            }
            default -> {
                Ast.ClassDecl knownClass = findClass(ref.name());
                if (knownClass != null) {
                    validateGenericArity(ref, knownClass.genericParameters(), "class " + knownClass.name());
                    yield new Named(qualifiedClassName(knownClass),
                            ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
                }
                Ast.InterfaceDecl knownInterface = findInterface(ref.name());
                if (knownInterface != null) {
                    if (knownInterface.moduleContract()) throw new IllegalArgumentException("module contract '" + knownInterface.name() + "' is only valid in module 'conforms' clauses");
                    validateGenericArity(ref, knownInterface.genericParameters(), "interface " + knownInterface.name());
                    yield new Named(qualifiedInterfaceName(knownInterface),
                            ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
                }
                if (ref.inferArguments()) {
                    throw new IllegalArgumentException("cannot infer type arguments for unknown type '" + ref.name() + "<>'");
                }
                yield new Named(ref.name(), ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
            }
        };
    }

    private Ast.TypeRef substituteAliasType(Ast.TypeRef ref, Map<String, Ast.TypeRef> substitutions) {
        Ast.TypeRef replacement = substitutions.get(ref.name());
        if (replacement != null && ref.arguments().isEmpty() && !ref.inferArguments()) return replacement;
        return new Ast.TypeRef(
                ref.name(),
                ref.arguments().stream().map(arg -> substituteAliasType(arg, substitutions)).toList(),
                ref.inferArguments());
    }

    private Named nominalClassType(Ast.ClassDecl klass) {
        return new Named(qualifiedClassName(klass), klass.genericParameters().stream().map(Generic::new).map(Type.class::cast).toList());
    }

    private Named nominalInterfaceType(Ast.InterfaceDecl iface) {
        return new Named(qualifiedInterfaceName(iface), iface.genericParameters().stream().map(Generic::new).map(Type.class::cast).toList());
    }

    private String qualifiedClassName(Ast.ClassDecl klass) {
        String owner = classOwners.get(klass);
        return owner == null || owner.equals("__root__") ? klass.name() : owner + "." + klass.name();
    }

    private String qualifiedInterfaceName(Ast.InterfaceDecl iface) {
        String owner = interfaceOwners.get(iface);
        return owner == null || owner.equals("__root__") ? iface.name() : owner + "." + iface.name();
    }

    private boolean assignable(Type actual, Type expected) {
        // A negative constraint requires positive evidence that values are disjoint.
        // The generic/Unknown and dynamic compatibility paths below are not evidence.
        if (expected instanceof Types.Not) return Types.isAssignable(actual, expected);
        if (expected instanceof SelfType expectedSelf) {
            if (!(actual instanceof SelfType actualSelf)) return false;
            return assignable(actualSelf.bound(), expectedSelf.bound())
                    && assignable(expectedSelf.bound(), actualSelf.bound());
        }
        if (actual instanceof SelfType actualSelf) {
            return assignable(actualSelf.bound(), expected);
        }
        if (expected instanceof Generic expectedGeneric) {
            return actual instanceof Generic actualGeneric
                    && actualGeneric.name().equals(expectedGeneric.name());
        }
        if (actual instanceof Generic actualGeneric) {
            return expected instanceof Generic expectedGeneric
                    && actualGeneric.name().equals(expectedGeneric.name());
        }
        if (actual instanceof Union source) {
            return source.options().stream().allMatch(option -> assignable(option, expected));
        }
        if (expected instanceof Union target) {
            return target.options().stream().anyMatch(option -> assignable(actual, option));
        }

        // Function compatibility is contravariant in parameters and covariant
        // in results. Leaf comparisons come back through this checker so
        // nominal inheritance/interface relations remain valid inside
        // structural method signatures.
        if (actual instanceof Function source && expected instanceof Function target) {
            if (source.parameters().size() != target.parameters().size()) return false;
            if (source.async() != target.async()) return false;
            if (!source.effects().isSubsetOf(target.effects())) return false;
            if (!source.mutableParameters().equals(target.mutableParameters())) return false;
            for (int i = 0; i < source.parameters().size(); i++) {
                if (!assignable(target.parameters().get(i), source.parameters().get(i))) return false;
            }
            return assignable(source.result(), target.result());
        }

        if (actual instanceof Record sourceShape && expected instanceof Record targetShape) {
            return structuralRecordAssignable(sourceShape, targetShape);
        }

        if (Types.isAssignable(actual, expected)) return true;

        if (actual instanceof Named actualNamed && expected instanceof Record targetShape) {
            Ast.InterfaceDecl actualContract = findInterface(actualNamed.name());
            if (actualContract != null && actualContract.moduleContract()) return false;
            Ast.ClassDecl klass = findClass(actualNamed.name());
            if (klass != null) {
                // Actors are mailbox/capability identities, not ordinary
                // structural objects. Never erase that boundary by shape.
                if (klass.actorKind() != Ast.ActorKind.NONE) return false;
                Type specializedShape = substituteGenerics(
                        publicClassShape(klass, new LinkedHashSet<>()),
                        classGenericBindings(klass, actualNamed));
                return specializedShape instanceof Record record
                        && structuralRecordAssignable(record, targetShape);
            }

            Ast.InterfaceDecl iface = findInterface(actualNamed.name());
            if (iface != null) {
                Type specializedShape = substituteGenerics(
                        interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>()),
                        genericBindings(
                                iface.genericParameters(),
                                actualNamed.arguments(),
                                "interface " + iface.name()));
                return specializedShape instanceof Record record
                        && structuralRecordAssignable(record, targetShape);
            }
            return false;
        }

        if (actual instanceof Named actualNamed && expected instanceof Named expectedNamed) {
            Ast.InterfaceDecl actualInterface = findInterface(actualNamed.name());
            Ast.InterfaceDecl expectedInterface = findInterface(expectedNamed.name());
            if ((actualInterface != null && actualInterface.moduleContract())
                    || (expectedInterface != null && expectedInterface.moduleContract())) {
                return false;
            }
            if (findClass(actualNamed.name()) != null) {
                if (findClass(expectedNamed.name()) != null
                        && classTypeExtends(actualNamed, expectedNamed, new LinkedHashSet<>())) {
                    return true;
                }
                if (findInterface(expectedNamed.name()) != null
                        && classTypeImplements(actualNamed, expectedNamed, new LinkedHashSet<>())) {
                    return true;
                }
            }
            if (findInterface(actualNamed.name()) != null
                    && findInterface(expectedNamed.name()) != null
                    && interfaceTypeExtends(actualNamed, expectedNamed, new LinkedHashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private static boolean aggregateKindsCompatible(
            AggregateKind left, AggregateKind right) {
        if (left == right) return true;
        // CONTRACT is an explicit structural view, not a stored data kind.
        // Concrete structs may satisfy that view without becoming RECORD data.
        if (left == AggregateKind.STRUCT && right == AggregateKind.CONTRACT) return true;
        if (left == AggregateKind.STRUCT || right == AggregateKind.STRUCT) return false;
        return true;
    }

    private static boolean methodContractMember(String key) {
        return key.startsWith("instance$")
                && key.contains("$arity")
                && key.contains("$generics");
    }

    /** Data members visible through [] on an internal structural contract. */
    private Map<String, Type> indexableRecordMembers(Record record) {
        if (record.kind() != AggregateKind.CONTRACT) return record.members();
        LinkedHashMap<String, Type> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Type> entry : record.members().entrySet()) {
            if (!methodContractMember(entry.getKey())) {
                fields.put(entry.getKey(), entry.getValue());
            }
        }
        return java.util.Collections.unmodifiableMap(fields);
    }

    private boolean structuralRecordAssignable(Record actual, Record expected) {
        if (!aggregateKindsCompatible(actual.kind(), expected.kind())) return false;
        if (expected.kind() == AggregateKind.STRUCT
                && actual.members().size() != expected.members().size()) return false;
        // A read-only structural view can be covariant, but a writable
        // structural alias must be invariant in each exposed member. Do not
        // use bidirectional assignability: Unknown and Generic are permissive.
        if (!expected.readOnly() && actual.readOnly()) return false;
        for (Map.Entry<String, Type> required : expected.members().entrySet()) {
            Type candidate = actual.members().get(required.getKey());
            if (candidate == null) return false;
            if (expected.readOnly()) {
                if (!candidate.equals(required.getValue())
                        && (Types.containsUncertainType(candidate)
                                || !assignable(candidate, required.getValue()))) return false;
            } else if (!candidate.equals(required.getValue())) {
                return false;
            }
        }
        return true;
    }

    private Named concreteClassReference(Ast.TypeRef ref, Ast.ClassDecl owner, Named ownerType) {
        Type pattern = resolve(ref, Set.copyOf(owner.genericParameters()), nominalClassType(owner));
        Type concrete = substituteGenerics(pattern, classGenericBindings(owner, ownerType));
        if (!(concrete instanceof Named named)) {
            throw new IllegalArgumentException("class relationship must resolve to a named type");
        }
        return named;
    }

    private boolean classTypeExtends(Named actualType, Named expectedType, Set<Named> seen) {
        if (Types.isAssignable(actualType, expectedType)) return true;
        if (!seen.add(actualType)) return false;

        Ast.ClassDecl actual = findClass(actualType.name());
        if (actual == null) return false;
        for (Ast.TypeRef parentRef : actual.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, actual);
            if (parent == null) continue;
            Named parentType = concreteClassReference(parentRef, actual, actualType);
            if (Types.isAssignable(parentType, expectedType)
                    || classTypeExtends(parentType, expectedType, seen)) {
                return true;
            }
        }
        return false;
    }

    private boolean classTypeImplements(Named actualType, Named expectedInterfaceType, Set<Named> seen) {
        if (!seen.add(actualType)) return false;
        Ast.ClassDecl actual = findClass(actualType.name());
        if (actual == null) return false;

        for (Ast.TypeRef ifaceRef : actual.interfaces()) {
            Named ifaceType = concreteClassReference(ifaceRef, actual, actualType);
            if (interfaceTypeExtends(ifaceType, expectedInterfaceType, new LinkedHashSet<>())) {
                return true;
            }
        }

        for (Ast.TypeRef parentRef : actual.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, actual);
            if (parent == null) continue;
            Named parentType = concreteClassReference(parentRef, actual, actualType);
            if (classTypeImplements(parentType, expectedInterfaceType, seen)) return true;
        }
        return false;
    }

    private boolean interfaceTypeExtends(Named actualType, Named expectedType, Set<Named> seen) {
        if (Types.isAssignable(actualType, expectedType)) return true;
        if (!seen.add(actualType)) return false;

        Ast.InterfaceDecl actual = findInterface(actualType.name());
        if (actual == null) return false;
        Map<String, Type> bindings = genericBindings(
                actual.genericParameters(),
                actualType.arguments(),
                "interface " + actual.name());
        Set<String> generics = Set.copyOf(actual.genericParameters());

        for (Ast.TypeRef parentRef : actual.parents()) {
            Type pattern = resolve(parentRef, generics, null);
            Type concrete = substituteGenerics(pattern, bindings);
            if (!(concrete instanceof Named parentType)) {
                throw new IllegalArgumentException("interface relationship must resolve to a named type");
            }
            if (Types.isAssignable(parentType, expectedType)
                    || interfaceTypeExtends(parentType, expectedType, seen)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasFunctionFamily(String name) {
        if (ambiguousFunctions.contains(name)) {
            throw new IllegalArgumentException(
                    "ambiguous function/routine name '" + name + "'; qualify it with its module");
        }
        return functions.containsKey(name);
    }

    private Ast.FunctionDecl findFunction(String name, int arity) {
        if (ambiguousFunctions.contains(name)) {
            throw new IllegalArgumentException(
                    "ambiguous function/routine name '" + name + "'; qualify it with its module");
        }
        LinkedHashMap<Integer, Ast.FunctionDecl> family = functions.get(name);
        return family == null ? null : family.get(arity);
    }

    private Ast.FunctionDecl findSingleFunction(String name) {
        if (ambiguousFunctions.contains(name)) {
            throw new IllegalArgumentException(
                    "ambiguous function/routine name '" + name + "'; qualify it with its module");
        }
        return singleFunction(functions.get(name), name);
    }

    private Ast.FunctionDecl findQualifiedFunction(String qualifiedName, int arity) {
        LinkedHashMap<Integer, Ast.FunctionDecl> family = functions.get(qualifiedName);
        return family == null ? null : family.get(arity);
    }

    private Ast.FunctionDecl findSingleQualifiedFunction(String qualifiedName) {
        return singleFunction(functions.get(qualifiedName), qualifiedName);
    }

    private Ast.FunctionDecl singleFunction(
            LinkedHashMap<Integer, Ast.FunctionDecl> family,
            String displayName) {
        if (family == null || family.isEmpty()) return null;
        if (family.size() > 1) {
            throw new IllegalArgumentException(
                    "overloaded callable '" + displayName
                            + "' must be called so name + arity can select a slot");
        }
        return family.values().iterator().next();
    }

    private Ast.ClassDecl findClass(String name) {
        if (ambiguousClasses.contains(name)) throw new IllegalArgumentException("ambiguous class name '" + name + "'; qualify it with its module");
        return classes.get(name);
    }

    private Ast.InterfaceDecl findInterface(String name) {
        if (ambiguousInterfaces.contains(name)) throw new IllegalArgumentException("ambiguous interface name '" + name + "'; qualify it with its module");
        return interfaces.get(name);
    }

    private Ast.TypeAliasDecl findTypeAlias(String name) {
        if (ambiguousTypeAliases.contains(name)) throw new IllegalArgumentException("ambiguous type alias '" + name + "'; qualify it with its module");
        return typeAliases.get(name);
    }

    private Set<String> uniqueGenerics(List<String> names, String owner) {
        Set<String> result = new HashSet<>();
        for (String name : names) {
            rejectReservedSymbolTypeName(name, "generic parameter in " + owner);
            if (!result.add(name)) throw new IllegalArgumentException("duplicate generic '" + name + "' in " + owner);
        }
        return result;
    }

    private record SwitchLiteralCaseKey(Class<?> nominalType, Object value) { }

    private boolean constant(Ast.Expr expr) {
        if (expr instanceof Ast.LiteralExpr) return true;
        if (expr instanceof Ast.UnaryExpr unary) return constant(unary.operand());
        if (expr instanceof Ast.BinaryExpr binary) return constant(binary.left()) && constant(binary.right());
        if (expr instanceof Ast.ConditionalExpr conditional) return constant(conditional.condition()) && constant(conditional.whenTrue()) && constant(conditional.whenFalse());
        if (expr instanceof Ast.ListExpr list) return list.elements().stream().allMatch(this::constant);
        if (expr instanceof Ast.TupleExpr tuple) return tuple.elements().stream().allMatch(this::constant);
        if (expr instanceof Ast.ObjectExpr object) return object.fields().stream().allMatch(field -> constant(field.value()));
        return false;
    }

    private boolean definitelyReturns(List<Ast.Stmt> body) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt) return true;
            if (stmt instanceof Ast.BlockStmt block && definitelyReturns(block.body())) return true;
            if (stmt instanceof Ast.TaskScopeStmt scope && definitelyReturns(scope.body())) return true;
            if (stmt instanceof Ast.LoopStmt loop && !containsBreakForCurrentLoop(loop.body())) return true;
            if (stmt instanceof Ast.IfStmt conditional) {
                boolean allBranches = !conditional.branches().isEmpty()
                        && conditional.branches().stream().allMatch(branch -> definitelyReturns(branch.body()))
                        && !conditional.elseBody().isEmpty()
                        && definitelyReturns(conditional.elseBody());
                if (allBranches) return true;
            }
            if (stmt instanceof Ast.MatchStmt matched
                    && !matched.arms().isEmpty()
                    && matched.arms().stream().allMatch(arm -> definitelyReturns(arm.body()))) return true;
            if (stmt instanceof Ast.SwitchStmt switched
                    && !switched.defaultBody().isEmpty()
                    && switched.cases().stream().allMatch(arm -> definitelyReturns(arm.body()))
                    && definitelyReturns(switched.defaultBody())) return true;
            if (stmt instanceof Ast.TryStmt attempted) {
                if (definitelyReturns(attempted.finallyBody())) return true;
                if (definitelyReturns(attempted.body()) && definitelyReturns(attempted.catchBody())) return true;
            }
        }
        return false;
    }

    private boolean containsBreakForCurrentLoop(List<Ast.Stmt> body) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.BreakStmt) return true;
            if (stmt instanceof Ast.BlockStmt block && containsBreakForCurrentLoop(block.body())) return true;
            if (stmt instanceof Ast.TaskScopeStmt scope
                    && containsBreakForCurrentLoop(scope.body())) return true;
            if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    if (containsBreakForCurrentLoop(branch.body())) return true;
                }
                if (containsBreakForCurrentLoop(conditional.elseBody())) return true;
            } else if (stmt instanceof Ast.MatchStmt matched) {
                for (Ast.MatchArm arm : matched.arms()) {
                    if (containsBreakForCurrentLoop(arm.body())) return true;
                }
            } else if (stmt instanceof Ast.SwitchStmt switched) {
                for (Ast.SwitchCase arm : switched.cases()) {
                    if (containsBreakForCurrentLoop(arm.body())) return true;
                }
                if (containsBreakForCurrentLoop(switched.defaultBody())) return true;
            } else if (stmt instanceof Ast.TryStmt attempted) {
                if (containsBreakForCurrentLoop(attempted.body())
                        || containsBreakForCurrentLoop(attempted.catchBody())
                        || containsBreakForCurrentLoop(attempted.finallyBody())) return true;
            }
        }
        return false;
    }

    private boolean hasInferredArgs(Ast.TypeRef ref) {
        return ref.inferArguments() || ref.arguments().stream().anyMatch(this::hasInferredArgs);
    }

    private void requireAssignable(Type actual, Type expected, String where) {
        if (!assignable(actual, expected)) throw new IllegalArgumentException(where + " has type " + actual + " but expected " + expected);
    }

    private String methodContractKey(String name, int arity, int genericArity) {
        return CallableSelector.instance(name, arity).contractKey(genericArity);
    }



    private static final class Env {
        private final Env parent;
        private final boolean descendantsNonLexical;
        private final boolean deferredCaptureBoundary;
        private final Map<String, Binding> bindings = new HashMap<>();
        /** Literal integer constants used only for exact fixed-product index selection. */
        private final Map<String, Long> constantIntegers = new HashMap<>();
        /** Literal string constants used only for exact fixed-product field selection. */
        private final Map<String, String> constantStrings = new HashMap<>();
        private final Map<String, Type> inferredTypes = new HashMap<>();
        private final Set<String> reservedInferredNames = new HashSet<>();
        /**
         * Shared by lexical descendants of one callable/root environment.
         * A short inferred alias may not silently reuse a name whose defining
         * block has ended; a separate callable gets a fresh domain.
         */
        private final Set<String> seenInferredNames;
        private void reserveInferredType(String name) {
            if (hasReservedInferredType(name) || lookupInferredType(name) != null
                    || seenInferredNames.contains(name)
                    || !reservedInferredNames.add(name)) {
                throw new IllegalArgumentException("duplicate or shadowed inferred type '" + name + "'");
            }
            seenInferredNames.add(name);
        }
        private boolean hasSeenInferredType(String name) {
            return seenInferredNames.contains(name);
        }
        private boolean hasReservedInferredType(String name) {
            return reservedInferredNames.contains(name)
                    || parent != null && parent.hasReservedInferredType(name);
        }
        private void defineInferredType(String name, Type type) {
            if (!reservedInferredNames.contains(name)
                    || inferredTypes.putIfAbsent(name, type) != null) {
                throw new IllegalArgumentException("duplicate or shadowed inferred type '" + name + "'");
            }
        }
        private Type lookupInferredType(String name) {
            Type local = inferredTypes.get(name);
            return local != null ? local : parent == null ? null : parent.lookupInferredType(name);
        }
        private Env(Env parent) {
            this(
                    parent,
                    parent != null && parent.descendantsNonLexical,
                    parent != null && parent.deferredCaptureBoundary);
        }
        private Env(Env parent, boolean descendantsNonLexical) {
            this(
                    parent,
                    descendantsNonLexical,
                    parent != null && parent.deferredCaptureBoundary);
        }
        private Env(
                Env parent,
                boolean descendantsNonLexical,
                boolean deferredCaptureBoundary) {
            this.parent = parent;
            this.descendantsNonLexical = descendantsNonLexical;
            this.deferredCaptureBoundary = deferredCaptureBoundary;
            this.seenInferredNames =
                    parent == null ? new HashSet<>() : parent.seenInferredNames;
        }
        private boolean descendantsNonLexical() { return descendantsNonLexical; }
        private void define(String name, Type type, Ast.BindingKind kind) {
            define(name, type, kind, Set.of());
        }
        private void define(
                String name,
                Type type,
                Ast.BindingKind kind,
                Set<String> uninitializedFields) {
            if (bindings.putIfAbsent(name, new Binding(type, kind, uninitializedFields)) != null) {
                throw new IllegalArgumentException("duplicate binding '" + name + "'");
            }
        }
        private void defineConstantInteger(String name, long value) {
            if (!bindings.containsKey(name)) {
                throw new IllegalStateException(
                        "constant integer metadata requires an existing binding '" + name + "'");
            }
            constantIntegers.put(name, value);
        }
        private Long lookupConstantInteger(String name) {
            if (bindings.containsKey(name)) return constantIntegers.get(name);
            return parent == null ? null : parent.lookupConstantInteger(name);
        }
        private void defineConstantString(String name, String value) {
            if (!bindings.containsKey(name)) {
                throw new IllegalStateException(
                        "constant string metadata requires an existing binding '" + name + "'");
            }
            constantStrings.put(name, value);
        }
        private String lookupConstantString(String name) {
            if (bindings.containsKey(name)) return constantStrings.get(name);
            return parent == null ? null : parent.lookupConstantString(name);
        }
        private Binding lookup(String name) {
            Binding binding = bindings.get(name);
            if (binding != null) return binding;
            if (parent == null) return null;
            Binding inherited = parent.lookup(name);
            if (deferredCaptureBoundary
                    && inherited != null
                    && inherited.hasUninitializedFields()) {
                throw new IllegalArgumentException(
                        "cannot capture partially initialized explicit struct '" + name
                                + "' in deferred code; initialize all declared fields first");
            }
            return inherited;
        }
        private void replace(String name, Type type, Ast.BindingKind kind) {
            if (!bindings.containsKey(name)) throw new IllegalArgumentException("unknown binding '" + name + "'");
            bindings.put(name, new Binding(type, kind, Set.of()));
            constantIntegers.remove(name);
            constantStrings.remove(name);
        }
        private void markInitialized(String name, String field) {
            Binding binding = lookup(name);
            if (binding != null) binding.markInitialized(field);
        }

        private Map<Binding, Set<String>> initializationSnapshot() {
            Map<Binding, Set<String>> snapshot = new IdentityHashMap<>();
            collectInitialization(snapshot);
            return snapshot;
        }

        private void collectInitialization(Map<Binding, Set<String>> snapshot) {
            if (parent != null) parent.collectInitialization(snapshot);
            for (Binding binding : bindings.values()) {
                snapshot.put(binding, new LinkedHashSet<>(binding.uninitializedFields));
            }
        }

        private void restoreInitialization(Map<Binding, Set<String>> snapshot) {
            for (Map.Entry<Binding, Set<String>> entry : snapshot.entrySet()) {
                entry.getKey().setUninitializedFields(entry.getValue());
            }
        }

        private void mergeInitialization(List<Map<Binding, Set<String>>> exits) {
            if (exits.isEmpty()) return;
            for (Binding binding : exits.getFirst().keySet()) {
                LinkedHashSet<String> maybeUninitialized = new LinkedHashSet<>();
                for (Map<Binding, Set<String>> exit : exits) {
                    Set<String> state = exit.get(binding);
                    if (state != null) maybeUninitialized.addAll(state);
                }
                binding.setUninitializedFields(maybeUninitialized);
            }
        }

        private static final class Binding {
            private final Type type;
            private final Ast.BindingKind kind;
            private final Set<String> uninitializedFields;

            private Binding(Type type, Ast.BindingKind kind, Set<String> uninitializedFields) {
                this.type = type;
                this.kind = kind;
                this.uninitializedFields = new LinkedHashSet<>(uninitializedFields);
            }
            private Type type() { return type; }
            private Ast.BindingKind kind() { return kind; }
            private boolean hasUninitializedFields() { return !uninitializedFields.isEmpty(); }
            private boolean isUninitialized(String field) { return uninitializedFields.contains(field); }
            private Set<String> uninitializedFields() {
                return java.util.Collections.unmodifiableSet(uninitializedFields);
            }
            private void markInitialized(String field) { uninitializedFields.remove(field); }
            private void setUninitializedFields(Set<String> fields) {
                uninitializedFields.clear();
                uninitializedFields.addAll(fields);
            }
        }
    }
}
