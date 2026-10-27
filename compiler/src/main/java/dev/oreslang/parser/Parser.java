package dev.oreslang.parser;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;

import static dev.oreslang.parser.Token.Type.*;

public final class Parser {
    public static final String ROOT_MODULE = "__root__";

    public record SyntaxWarning(int line, int column, String message) { }

    public record ParseResult(Ast.Program program, List<SyntaxWarning> warnings) {
        public ParseResult {
            warnings = List.copyOf(warnings);
        }
    }

    private final List<Token> tokens;
    private final List<SyntaxWarning> warnings = new ArrayList<>();
    private int current;
    private boolean suppressRefinementOperators;
    // A match guard's outer '->' delimits an arm, not a lambda that happens
    // to follow a parenthesized guard expression.
    private int matchGuardStartToken = -1;

    // Forward type signatures are compile-time-only and intentionally absent
    // from executable ASTs. The v1 contract requires an exact same-scope
    // definition; cross-file and unresolved linker stubs fail closed.
    private record ForwardType(Token.Type kind, String name, Ast.Visibility visibility,
                               List<String> generics) { }
    private final List<ForwardType> rootForwards = new ArrayList<>();
    private List<ForwardType> moduleForwards;
    // The current AST represents traits using InterfaceDecl. Keep the parsed
    // source kind during forward validation, rather than permitting trait and
    // interface declarations to substitute for one another silently.
    private final java.util.IdentityHashMap<Ast.InterfaceDecl, Token.Type> interfaceKinds =
            new java.util.IdentityHashMap<>();

    public Parser(List<Token> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    public static Ast.Program parse(String source) {
        return new Parser(new Lexer(source).scan()).parseProgram();
    }

    /**
     * Parse one entire interpolation as an expression, never a statement.
     * Nested cpu/gpu domains cannot bypass the compilation-unit preflight.
     */
    static Ast.Expr parseInterpolatedExpression(String source) {
        Parser parser = new Parser(new Lexer(source).scan());
        for (Token token : parser.tokens) {
            if (token.type() == GPU || token.type() == CPU || token.type() == QUANTUM) {
                throw parser.error(token, "execution domain requires checked backend lowering");
            }
        }
        Ast.Expr result = parser.parseExpression();
        parser.consume(EOF, "expected end of template interpolation expression");
        return result;
    }

    public static ParseResult parseWithWarnings(String source) {
        Parser parser = new Parser(new Lexer(source).scan());
        Ast.Program program = parser.parseProgram();
        return new ParseResult(program, parser.warnings);
    }

    public Ast.Program parseProgram() {
        // CPU/GPU Task v2 has a real source AST. Keep unsupported synchronous
        // execution regions and quantum fail-closed, but allow canonical task
        // forms (plus legacy nb aliases) to reach typed lowering. GPU task is
        // deliberately rejected later until a physical device provider exists.
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type() == QUANTUM) {
                throw error(token, "quantum execution requires explicit backend lowering; no implicit CPU fallback");
            }
            if (token.type() == CPU || token.type() == GPU) {
                boolean canonicalTask =
                        i + 2 < tokens.size()
                                && tokens.get(i + 1).type() == IDENT
                                && tokens.get(i + 1).lexeme().equals("task")
                                && tokens.get(i + 2).type() == LBRACE;
                boolean legacyNbTask =
                        i > 0 && tokens.get(i - 1).type() == NB
                                && i + 1 < tokens.size()
                                && tokens.get(i + 1).type() == LBRACE;
                if (!canonicalTask && !legacyNbTask) {
                    throw error(token, "synchronous execution domain '" + token.lexeme()
                            + " { ... }' is reserved but not production-lowered yet;"
                            + " use '" + token.lexeme() + " task { ... }' for asynchronous work;"
                            + " no implicit CPU fallback");
                }
            }
        }
        String namespace = null;
        if (match(NAMESPACE)) {
            namespace = consume(IDENT, "expected flat namespace name").lexeme();
            if (check(DOT)) throw error(peek(), "namespaces cannot be nested or dotted");
            consume(SEMICOLON, "namespace declaration must end with ';'");
        }

        List<Ast.ImportDecl> imports = new ArrayList<>();
        while (match(IMPORT)) imports.add(parseImport());

        List<Ast.ModuleDecl> modules = new ArrayList<>();
        List<Ast.Decl> rootDeclarations = new ArrayList<>();

        while (!check(EOF)) {
            rejectUnsupportedAcceleratorSyntax();
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers modifiers = parseModifiers();

            if (isContextualIdentifier("declare")) {
                rootForwards.add(parseForwardType(annotations, modifiers));
                continue;
            }

            if (match(CLASS)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                // 'define' is optional; the old 'class ... define' order remains accepted.
                if (match(DEFINE)) modifiers = mergeModifiers(modifiers, parseModifiers());
                modifiers = mergeModifiers(modifiers, parseModifiers());
                rejectCallableStructuralAnnotation(annotations, "class declarations");
                rejectDiscardedDeclarationAnnotations(annotations, "class declarations");
                validateClassModifiers(modifiers);
                rootDeclarations.add(parseClass(
                        modifiers.visibility,
                        modifiers.isAbstract,
                        modifiers.visibility != Ast.Visibility.PUBLIC));
                continue;
            }

            if (check(CONTRACT) && defineFollowsAfterVisibilityModifiers()) {
                advance();
                modifiers = mergeModifiers(modifiers, parseModifiers());
                consume(DEFINE, "compatibility contract declaration requires both 'contract' and 'define'");
                modifiers = mergeModifiers(modifiers, parseModifiers());
                validateOnlyVisibilityModifiers(modifiers, "contracts");
                rejectCallableStructuralAnnotation(annotations, "contract declarations");
                rejectDiscardedDeclarationAnnotations(annotations, "contract declarations");
                rootDeclarations.add(parseContract(modifiers.visibility));
                continue;
            }

            if (check(INTERFACE) && defineFollowsAfterVisibilityModifiers()) {
                advance();
                modifiers = mergeModifiers(modifiers, parseModifiers());
                consume(DEFINE, "compatibility interface declaration requires both 'interface' and 'define'");
                modifiers = mergeModifiers(modifiers, parseModifiers());
                validateOnlyVisibilityModifiers(modifiers, "interfaces");
                rejectCallableStructuralAnnotation(annotations, "interface declarations");
                rejectDiscardedDeclarationAnnotations(annotations, "interface declarations");
                rootDeclarations.add(parseDefinedInterface(modifiers.visibility, INTERFACE));
                continue;
            }

            if (match(DEFINE)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                rejectUnloweredRuntimeEntityDeclaration();
                ActorPrefix actorPrefix = parseActorPrefix();
                if (match(ACTOR, ISOACTOR)) {
                    rootDeclarations.add(parseDefinedActorClass(previous(), modifiers, annotations, actorPrefix));
                    continue;
                }
                if (modifiers.shared) {
                    throw error(previous(), "'shared' must modify an actor declaration; use 'shared actor <Name>'");
                }
                if (match(MODULE)) {
                    if (modifiers.visibilitySeen || modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural || modifiers.nonLexical || modifiers.trapped
                            || modifiers.isStatic || modifiers.isAbstract || modifiers.shared || modifiers.untrusted) {
                        throw error(previous(), "modules do not accept declaration modifiers");
                    }
                    rejectCallableStructuralAnnotation(annotations, "module declarations");
                    modules.add(parseModule(annotations));
                    continue;
                }
                if (match(CONTRACT)) {
                    modifiers = mergeModifiers(modifiers, parseModifiers());
                    validateOnlyVisibilityModifiers(modifiers, "contracts");
                    rejectCallableStructuralAnnotation(annotations, "contract declarations");
                    rejectDiscardedDeclarationAnnotations(annotations, "contract declarations");
                    rootDeclarations.add(parseContract(modifiers.visibility));
                    continue;
                }
                if (match(CLASS)) {
                    modifiers = mergeModifiers(modifiers, parseModifiers());
                    rejectCallableStructuralAnnotation(annotations, "class declarations");
                    rejectDiscardedDeclarationAnnotations(annotations, "class declarations");
                    validateClassModifiers(modifiers);
                    rootDeclarations.add(parseClass(
                            modifiers.visibility,
                            modifiers.isAbstract,
                            modifiers.visibility != Ast.Visibility.PUBLIC));
                    continue;
                }
                if (match(INTERFACE, TRAIT)) {
                    Token.Type declarationKind = previous().type();
                    modifiers = mergeModifiers(modifiers, parseModifiers());
                    validateOnlyVisibilityModifiers(modifiers, "interfaces/traits");
                    rejectCallableStructuralAnnotation(annotations, "interface declarations");
                    rejectDiscardedDeclarationAnnotations(annotations, "interface declarations");
                    rootDeclarations.add(parseDefinedInterface(modifiers.visibility, declarationKind));
                    continue;
                }
                if (match(TYPE)) {
                    modifiers = mergeModifiers(modifiers, parseModifiers());
                    validateOnlyVisibilityModifiers(modifiers, "type aliases");
                    rejectCallableStructuralAnnotation(annotations, "type aliases");
                    rejectDiscardedDeclarationAnnotations(annotations, "type aliases");
                    rootDeclarations.add(parseTypeAlias(modifiers.visibility));
                    continue;
                }
                throw error(previous(), "expected module, class, interface, trait, or type after 'define'");
            }

            Ast.Decl declaration = parseDeclarationAfterModifiers(annotations, modifiers);
            if (declaration == null) throw error(peek(), "expected module or top-level declaration");
            rootDeclarations.add(declaration);
        }

        validateForwardTypes(rootForwards, rootDeclarations, "file root");
        if (!rootDeclarations.isEmpty()) {
            modules.add(new Ast.ModuleDecl(ROOT_MODULE, List.of(), List.of(), rootDeclarations));
        }
        if (modules.isEmpty()) throw error(peek(), "a source file must define at least one module or top-level declaration");
        return new Ast.Program(namespace, imports, modules);
    }

    private Ast.ImportDecl parseImport() {
        Ast.ImportKind kind;
        List<String> names = new ArrayList<>();
        boolean wildcard = false;
        String namespace = null;

        if (match(STAR)) {
            kind = Ast.ImportKind.ALL;
            wildcard = true;
            consume(AS, "'import *' requires 'as <namespace>'");
            namespace = consume(IDENT, "expected import namespace").lexeme();
        } else {
            if (isLegacyFnSpelling()) {
                throw error(peek(), "function imports use 'import fnc', not 'import fn'");
            }
            if (match(MODULE)) kind = Ast.ImportKind.MODULE;
            else if (match(ACTOR)) kind = Ast.ImportKind.ACTOR;
            else if (match(CLASS)) kind = Ast.ImportKind.CLASS;
            else if (match(FNC)) kind = Ast.ImportKind.FUNCTION;
            else if (match(INTERFACE)) kind = Ast.ImportKind.INTERFACE;
            else if (match(CONTRACT)) kind = Ast.ImportKind.CONTRACT;
            else if (match(TRAIT)) kind = Ast.ImportKind.TRAIT;
            else if (match(STRUCT)) kind = Ast.ImportKind.STRUCT;
            else if (match(TYPE)) kind = Ast.ImportKind.TYPE;
            else if (match(TYPES)) kind = Ast.ImportKind.TYPES;
            else throw error(peek(), "expected module, actor, class, fnc, interface, contract, trait, struct, type, types, or * after import");

            if (match(STAR)) {
                wildcard = true;
                consume(AS, "wildcard import requires 'as <namespace>'");
                namespace = consume(IDENT, "expected import namespace").lexeme();
            } else {
                parseImportSelection(kind, names);
            }

            if (!wildcard && match(AS)) {
                if (names.size() != 1) {
                    throw error(previous(), "named import aliases require exactly one selected name");
                }
                namespace = consume(IDENT, "expected import alias").lexeme();
            }
        }

        consume(FROM, "expected 'from' in import");
        String path = consume(STRING, "expected quoted import path").lexeme();
        if (path.isBlank()) throw error(previous(), "import path cannot be empty");
        consume(SEMICOLON, "expected ';' after import");
        return new Ast.ImportDecl(kind, names, wildcard, namespace, path);
    }

    private void parseImportSelection(Ast.ImportKind kind, List<String> names) {
        Token.Type closing = null;
        if (match(LBRACE)) closing = RBRACE;
        else if (match(LPAREN)) closing = RPAREN;

        if (closing != null) {
            if (check(closing)) throw error(peek(), "import selection cannot be empty");
            do names.add(consumeImportName(kind)); while (match(COMMA));
            consume(closing, closing == RBRACE
                    ? "expected '}' after imported names"
                    : "expected ')' after imported names");
            return;
        }

        names.add(consumeImportName(kind));
        while (match(COMMA)) names.add(consumeImportName(kind));
    }

    private String consumeImportName(Ast.ImportKind kind) {
        if (kind == Ast.ImportKind.FUNCTION) {
            return consumeCallableName("expected imported function name");
        }
        return consume(IDENT, "expected imported name").lexeme();
    }

    private Ast.ModuleDecl parseModule(List<Ast.Annotation> annotations) {
        String name = consume(IDENT, "expected flat module name").lexeme();
        if (check(DOT)) throw error(peek(), "modules cannot be nested or dotted");
        List<Ast.TypeRef> contracts = match(CONFORMS) ? parseTypeRefList() : List.of();
        // Canonical module syntax is "define module Name as ... end".
        // Keep the pre-"as" spelling accepted while existing source migrates.
        match(AS);
        List<Ast.Decl> declarations = new ArrayList<>();
        List<ForwardType> enclosing = moduleForwards;
        List<ForwardType> forwards = new ArrayList<>();
        moduleForwards = forwards;
        try {
            while (!check(END) && !check(EOF)) {
                Ast.Decl declaration = parseModuleMember();
                if (declaration != null) declarations.add(declaration);
            }
            consume(END, "expected 'end' to close module " + name);
            validateForwardTypes(forwards, declarations, "module " + name);
        } finally {
            moduleForwards = enclosing;
        }
        return new Ast.ModuleDecl(name, annotations, contracts, declarations);
    }

    private void rejectUnsupportedAcceleratorSyntax() {
        if (check(GPU) || check(QUANTUM)) {
            throw error(peek(), "gpu/quantum execution requires validated effect and ownership checking, "
                    + "device lowering and explicit backend admission; implicit CPU fallback is forbidden");
        }
    }

    private Ast.Decl parseModuleMember() {
        rejectUnsupportedAcceleratorSyntax();
        List<Ast.Annotation> annotations = parseAnnotations();
        Modifiers modifiers = parseModifiers();

        if (isContextualIdentifier("declare")) {
            if (moduleForwards == null) throw error(peek(), "forward declaration outside module scope");
            moduleForwards.add(parseForwardType(annotations, modifiers));
            return null;
        }

        if (match(CLASS)) {
            modifiers = mergeModifiers(modifiers, parseModifiers());
            // 'define' is optional; retain the legacy class-first permutation.
            if (match(DEFINE)) modifiers = mergeModifiers(modifiers, parseModifiers());
            modifiers = mergeModifiers(modifiers, parseModifiers());
            rejectCallableStructuralAnnotation(annotations, "class declarations");
            rejectDiscardedDeclarationAnnotations(annotations, "class declarations");
            validateClassModifiers(modifiers);
            return parseClass(modifiers.visibility, modifiers.isAbstract, false);
        }

        if (check(CONTRACT) && defineFollowsAfterVisibilityModifiers()) {
            advance();
            modifiers = mergeModifiers(modifiers, parseModifiers());
            consume(DEFINE, "compatibility contract declaration requires both 'contract' and 'define'");
            modifiers = mergeModifiers(modifiers, parseModifiers());
            validateOnlyVisibilityModifiers(modifiers, "contracts");
            rejectCallableStructuralAnnotation(annotations, "contract declarations");
            rejectDiscardedDeclarationAnnotations(annotations, "contract declarations");
            return parseContract(modifiers.visibility);
        }

        if (check(INTERFACE) && defineFollowsAfterVisibilityModifiers()) {
            advance();
            modifiers = mergeModifiers(modifiers, parseModifiers());
            consume(DEFINE, "compatibility interface declaration requires both 'interface' and 'define'");
            modifiers = mergeModifiers(modifiers, parseModifiers());
            validateOnlyVisibilityModifiers(modifiers, "interfaces");
            rejectCallableStructuralAnnotation(annotations, "interface declarations");
            rejectDiscardedDeclarationAnnotations(annotations, "interface declarations");
            return parseDefinedInterface(modifiers.visibility, INTERFACE);
        }

        if (match(DEFINE)) {
            modifiers = mergeModifiers(modifiers, parseModifiers());
            rejectUnloweredRuntimeEntityDeclaration();
            ActorPrefix actorPrefix = parseActorPrefix();
            if (match(ACTOR, ISOACTOR)) {
                return parseDefinedActorClass(previous(), modifiers, annotations, actorPrefix);
            }
            if (modifiers.shared || modifiers.untrusted) {
                throw error(previous(), "'shared'/'untrusted' must modify an actor declaration");
            }
            if (match(CONTRACT)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                validateOnlyVisibilityModifiers(modifiers, "contracts");
                rejectCallableStructuralAnnotation(annotations, "contract declarations");
                rejectDiscardedDeclarationAnnotations(annotations, "contract declarations");
                return parseContract(modifiers.visibility);
            }
            if (match(CLASS)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                rejectCallableStructuralAnnotation(annotations, "class declarations");
                rejectDiscardedDeclarationAnnotations(annotations, "class declarations");
                validateClassModifiers(modifiers);
                return parseClass(modifiers.visibility,
                        modifiers.isAbstract, false);
            }
            if (match(INTERFACE, TRAIT)) {
                Token.Type declarationKind = previous().type();
                modifiers = mergeModifiers(modifiers, parseModifiers());
                validateOnlyVisibilityModifiers(modifiers, "interfaces/traits");
                rejectCallableStructuralAnnotation(annotations, "interface declarations");
                rejectDiscardedDeclarationAnnotations(annotations, "interface declarations");
                return parseDefinedInterface(modifiers.visibility, declarationKind);
            }
            if (match(TYPE)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                validateOnlyVisibilityModifiers(modifiers, "type aliases");
                rejectCallableStructuralAnnotation(annotations, "type aliases");
                rejectDiscardedDeclarationAnnotations(annotations, "type aliases");
                return parseTypeAlias(modifiers.visibility);
            }
            throw error(previous(), "expected class, interface, trait, or type after 'define'");
        }

        Ast.Decl declaration = parseDeclarationAfterModifiers(annotations, modifiers);
        if (declaration != null) return declaration;
        throw error(peek(), "expected function, routine, class, interface, trait, type, or binding declaration");
    }

    private Ast.Decl parseDeclarationAfterModifiers(List<Ast.Annotation> annotations, Modifiers modifiers) {
        rejectUnsupportedAcceleratorSyntax();
        if (isLegacyFnSpelling()) {
            throw error(peek(), "functions are declared with 'fnc', not 'fn'");
        }
        if (match(ACTOR, ISOACTOR)) {
            Token actorToken = previous();
            boolean isolated = actorToken.type() == ISOACTOR;
            modifiers = mergeModifiers(modifiers, parseModifiers());

            if (isLegacyFnSpelling()) {
                throw error(peek(), "actor functions are declared with 'actor fnc', not 'actor fn'");
            }
            if (match(FNC)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                Ast.ActorKind actorKind = resolveActorKind(actorToken, isolated, modifiers);
                return parseFunction(annotations, modifiers, Ast.CallableKind.FNC, actorKind);
            }
            if (match(ROUTINE)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                Ast.ActorKind actorKind = resolveActorKind(actorToken, isolated, modifiers);
                return parseFunction(annotations, modifiers, Ast.CallableKind.ROUTINE, actorKind);
            }

            Ast.ActorKind actorKind = resolveActorKind(actorToken, isolated, modifiers);
            if (modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural || modifiers.nonLexical || modifiers.trapped
                    || modifiers.isStatic || modifiers.isAbstract) {
                throw error(actorToken, "actor class declarations accept only visibility and actor-kind modifiers");
            }
            rejectCallableStructuralAnnotation(annotations, "actor class declarations");
            rejectDiscardedDeclarationAnnotations(annotations, "actor class declarations");
            return parseActorClass(actorKind, modifiers.visibility);
        }
        if (match(FNC)) {
            modifiers = mergeModifiers(modifiers, parseModifiers());
            if (match(ACTOR, ISOACTOR)) {
                Token actorToken = previous();
                boolean isolated = actorToken.type() == ISOACTOR;
                modifiers = mergeModifiers(modifiers, parseModifiers());
                Ast.ActorKind actorKind = resolveActorKind(actorToken, isolated, modifiers);
                return parseFunction(annotations, modifiers, Ast.CallableKind.FNC, actorKind);
            }
            if (modifiers.shared || modifiers.untrusted) {
                throw error(previous(), "'shared'/'untrusted' must modify an actor declaration");
            }
            return parseFunction(annotations, modifiers, Ast.CallableKind.FNC);
        }
        if (match(ROUTINE)) {
            modifiers = mergeModifiers(modifiers, parseModifiers());
            if (match(ACTOR, ISOACTOR)) {
                Token actorToken = previous();
                boolean isolated = actorToken.type() == ISOACTOR;
                modifiers = mergeModifiers(modifiers, parseModifiers());
                Ast.ActorKind actorKind = resolveActorKind(actorToken, isolated, modifiers);
                return parseFunction(annotations, modifiers, Ast.CallableKind.ROUTINE, actorKind);
            }
            if (modifiers.shared || modifiers.untrusted) {
                throw error(previous(), "'shared'/'untrusted' must modify an actor declaration");
            }
            return parseFunction(annotations, modifiers, Ast.CallableKind.ROUTINE);
        }
        if (match(CONTRACT)) {
            validateOnlyVisibilityModifiers(modifiers, "contracts");
            rejectCallableStructuralAnnotation(annotations, "contract declarations");
            rejectDiscardedDeclarationAnnotations(annotations, "contract declarations");
            return parseContract(modifiers.visibility);
        }
        if (match(INTERFACE, TRAIT)) {
            Token.Type declarationKind = previous().type();
            validateOnlyVisibilityModifiers(modifiers, "interfaces/traits");
            rejectCallableStructuralAnnotation(annotations, "interface/trait declarations");
            rejectDiscardedDeclarationAnnotations(annotations, "interface/trait declarations");
            return parseDefinedInterface(modifiers.visibility, declarationKind);
        }
        if (match(TYPE)) {
            modifiers = mergeModifiers(modifiers, parseModifiers());
            validateOnlyVisibilityModifiers(modifiers, "type aliases");
            rejectCallableStructuralAnnotation(annotations, "type aliases");
            rejectDiscardedDeclarationAnnotations(annotations, "type aliases");
            return parseTypeAlias(modifiers.visibility);
        }
        if (isBindingKind(peek().type())) {
            validateOnlyVisibilityModifiers(modifiers, "module bindings");
            return parseModuleBinding(annotations, modifiers.visibility);
        }
        return null;
    }

    private Ast.ActorKind resolveActorKind(Token actorToken, boolean isolated, Modifiers modifiers) {
        if (isolated && (modifiers.shared || modifiers.untrusted)) {
            throw error(
                    actorToken,
                    "'shared/untrusted isoactor' is contradictory; use either shared actor, untrusted actor, or isoactor");
        }
        if (modifiers.shared && modifiers.untrusted) {
            throw error(actorToken, "an actor cannot be both shared and untrusted");
        }
        if (modifiers.untrusted) return Ast.ActorKind.UNTRUSTED;
        return isolated ? Ast.ActorKind.PRIVATE : Ast.ActorKind.SHARED;
    }

    private Ast.FunctionDecl parseFunction(List<Ast.Annotation> annotations, Modifiers modifiers, Ast.CallableKind kind) {
        return parseFunction(annotations, modifiers, kind, Ast.ActorKind.NONE);
    }

    private Ast.FunctionDecl parseFunction(
            List<Ast.Annotation> annotations,
            Modifiers modifiers,
            Ast.CallableKind kind,
            Ast.ActorKind actorKind) {
        if (modifiers.isStatic) throw error(previous(), "'static fnc' is only valid inside a class");
        if (modifiers.suspends && modifiers.generator)
            throw error(previous(), "suspends generators need suspension-aware generator frames");
        if (modifiers.isAbstract) throw error(previous(), "top-level/module callables cannot be abstract");
        if (modifiers.trapped && (modifiers.async || modifiers.suspends)) {
            throw error(previous(), "suspending trap fnc/routine is not enabled until trap spans every await suspension");
        }
        if (modifiers.trapped && modifiers.generator) {
            throw error(previous(), "trap generator fnc/routine is not enabled until trap spans generator suspension");
        }
        if (modifiers.trapped && actorKind != Ast.ActorKind.NONE) {
            throw error(previous(), "trap actor callables are not enabled until actor supervision preserves the trap effect");
        }
        CallableStructural structural = normalizeCallableStructural(annotations, modifiers.structural);
        annotations = structural.annotations();
        String name = consumeCallableName("expected callable name");
        List<Ast.GenericParameter> specs = parseGenericParameterSpecs();
        List<String> generics = specs.stream().map(Ast.GenericParameter::name).toList();

        if (match(EQUAL)) {
            consume(PIPE, "lambda-style callable declarations use '= |Type name, ...| -> [ReturnType] { ... }'");
            List<Ast.Param> params = parseDeclaredPipeParameters();
            consume(PIPE, "expected closing '|' in lambda-style callable declaration");
            consume(ARROW, "lambda-style callable declarations use the slim arrow '->'");
            Ast.TypeRef returnType = check(LBRACE)
                    ? Ast.TypeRef.simple("void")
                    : parseTypeRef();
            Ast.EffectSpec effects = parseCheckedEffects();
            List<Ast.Stmt> body = parseBlock();
            return new Ast.FunctionDecl(name, kind, modifiers.visibility, modifiers.async, modifiers.suspends, modifiers.generator, structural.enabled(), modifiers.nonLexical, modifiers.trapped, actorKind,
                    generics, params, returnType, annotations, body, specs, effects);
        }

        consume(LPAREN, "expected '(' after callable name or '=' for lambda-style declaration");
        java.util.Set<String> structuralNames = structuralAnnotationNames(annotations);
        List<Ast.Param> params = applyStructuralAnnotations(parseParametersUntil(RPAREN, structuralNames), annotations);
        consume(RPAREN, "expected ')' after parameters");
        Ast.TypeRef returnType = parseReturnType(annotations);
        Ast.EffectSpec effects = parseCheckedEffects();
        List<Ast.Stmt> body = parseBlock();
        return new Ast.FunctionDecl(name, kind, modifiers.visibility, modifiers.async, modifiers.suspends, modifiers.generator, structural.enabled(), modifiers.nonLexical, modifiers.trapped, actorKind, generics, params,
                returnType, annotations, body, specs, effects);
    }

    private Ast.ClassDecl parseClass(
            Ast.Visibility visibility,
            boolean isAbstract,
            boolean filePrivateScope) {
        String name = consume(IDENT, "expected class name").lexeme();
        List<String> generics = parseGenericParameters();
        List<Ast.TypeRef> parents = match(EXTENDS) ? parseTypeRefList() : List.of();
        List<Ast.TypeRef> interfaces = match(IMPLEMENTS, IMPL) ? parseTypeRefList() : List.of();
        consume(AS, "expected 'as' after class header");
        boolean braceStyle = match(LBRACE);
        Token.Type classTerminator = braceStyle ? RBRACE : END;
        List<Ast.FieldDecl> fields = new ArrayList<>();
        List<Ast.MethodDecl> methods = new ArrayList<>();
        Ast.ConstructorDecl constructor = null;

        while (!check(classTerminator) && !check(EOF)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers mods = parseModifiers();

            if (check(DEFINE) || check(CLASS) || check(MODULE) || check(INTERFACE)
                    || check(ACTOR) || check(ISOACTOR)) {
                throw error(peek(),
                        "declarations cannot be nested inside classes; declare nested types at file or module scope");
            }

            if (isContextualIdentifier("constructor")) {
                if (constructor != null) {
                    throw error(peek(), "class '" + name + "' may declare only one constructor");
                }
                constructor = parseConstructor(
                        annotations, mods, name, visibility, filePrivateScope);
                continue;
            }

            if (check(IDENT) && peek().lexeme().equals(name) && checkNext(LPAREN)) {
                throw error(peek(),
                        "constructors are declared with 'constructor(...)', not the class name '"
                                + name + "(...)'");
            }

            if (isLegacyFnSpelling()) {
                if (mods.isStatic) throw error(peek(), "static class functions use 'static fnc', not 'static fn'");
                throw error(peek(), "instance methods omit 'fn'/'fnc'; declare the method name directly");
            }
            if (isBindingKind(peek().type())) {
                validateClassFieldModifiers(mods);
                Ast.FieldDecl field = parseField(annotations, mods.visibility);
                if (field.bindingKind() == Ast.BindingKind.CONST
                        && field.initializer() == null) {
                    throw error(previous(), "final/const class field '" + field.name()
                            + "' requires a declaration-site initializer");
                }
                fields.add(field);
                continue;
            }
            if (check(IDENT) && checkNext(COLON)) {
                validateClassFieldModifiers(mods);
                fields.add(parseColonField(annotations, mods.visibility));
                continue;
            }
            // Type-first fields accept ':' canonically and '=' for compatibility.
            // 'final' is contextual, so final(...) remains an ordinary method.
            if ((isContextualIdentifier("final") && (checkNext(IDENT) || checkNext(MUT)))
                    || looksLikeTypeFirstClassField()) {
                validateClassFieldModifiers(mods);
                fields.add(parseTypeFirstClassField(annotations, mods.visibility));
                continue;
            }

            boolean explicitFnc = match(FNC);
            boolean explicitMethod = matchExplicitMethodMarker();
            if (explicitFnc && explicitMethod) {
                throw error(previous(), "an instance member cannot combine 'fnc' and 'method' markers");
            }
            if (explicitFnc || explicitMethod) {
                mods = mergeModifiers(mods, parseModifiers());
            }
            if (mods.generator) throw error(peek(), "'generator' is not permitted on class methods or static class fnc; declare a module/top-level generator fnc or routine");
            if (mods.nonLexical) throw error(peek(), "'nlex' is unnecessary on class members; methods/static fnc never capture enclosing local scopes");
            if (mods.trapped) throw error(peek(), "'trap' on class methods/static fnc is not enabled until method callable metadata carries the trap effect");
            if (mods.shared || mods.untrusted) throw error(peek(), "actor-kind modifiers are not permitted on class members");
            if (mods.isStatic) {
                if (explicitMethod) {
                    throw error(previous(), "static class functions use 'static fnc', not 'static method'");
                }
                if (!explicitFnc) {
                    consume(FNC, "static class functions must be declared with 'static fnc'");
                }
                if (mods.isAbstract) throw error(previous(), "static class functions cannot be abstract");
            }
            // Legacy 'fnc name' and explicit 'method name' both describe instance
            // methods. The AST's isStatic flag, not the spelling, controls binding.
            methods.add(parseMethod(annotations, mods, isAbstract));
        }
        consume(
                classTerminator,
                braceStyle
                        ? "expected '}' to close class " + name
                        : "expected 'end' to close class " + name);
        return new Ast.ClassDecl(
                name, visibility, isAbstract, Ast.ActorKind.NONE, generics,
                parents, interfaces, fields, constructor, methods);
    }

    private Ast.ConstructorDecl parseConstructor(
            List<Ast.Annotation> annotations,
            Modifiers mods,
            String className,
            Ast.Visibility classVisibility,
            boolean filePrivateScope) {
        Token keyword = advance(); // contextual 'constructor'
        if (mods.async || mods.suspends || mods.generator || mods.structural || mods.nonLexical || mods.trapped
                || mods.isStatic || mods.isAbstract || mods.shared || mods.untrusted) {
            throw error(keyword,
                    "constructors accept only private/pub visibility; callable/actor modifiers are not permitted");
        }
        if (mods.visibility == Ast.Visibility.PUBLIC
                && (filePrivateScope || classVisibility != Ast.Visibility.PUBLIC)) {
            throw error(keyword,
                    "a public constructor requires a public class; private file-root or module classes cannot export constructors");
        }

        consume(LPAREN, "expected '(' after constructor");
        List<Ast.Param> params = parseParametersUntil(RPAREN);
        consume(RPAREN, "expected ')' after constructor parameters");
        if (check(COLON) || check(ARROW) || check(FAT_ARROW)) {
            throw error(peek(), "constructors do not declare a return type");
        }
        List<Ast.Stmt> body = parseBlock();
        return new Ast.ConstructorDecl(mods.visibility, params, annotations, body);
    }

    private boolean defineFollowsAfterVisibilityModifiers() {
        int lookahead = current + 1;
        while (lookahead < tokens.size()) {
            Token.Type type = tokens.get(lookahead).type();
            if (type == PUB || type == PRIVATE) {
                lookahead++;
                continue;
            }
            return type == DEFINE;
        }
        return false;
    }

    private void validateClassModifiers(Modifiers modifiers) {
        if (modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural || modifiers.nonLexical || modifiers.trapped
                || modifiers.isStatic || modifiers.shared || modifiers.untrusted) {
            throw error(previous(),
                    "classes accept only private/pub visibility and abstract; callable/actor modifiers are not permitted");
        }
    }

    private void validateClassFieldModifiers(Modifiers modifiers) {
        if (modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural || modifiers.nonLexical || modifiers.trapped
                || modifiers.isStatic || modifiers.isAbstract || modifiers.shared || modifiers.untrusted) {
            throw error(peek(), "class fields accept only private/pub visibility modifiers");
        }
    }

    private void validateOnlyVisibilityModifiers(Modifiers modifiers, String context) {
        if (modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural || modifiers.nonLexical
                || modifiers.trapped || modifiers.isStatic || modifiers.isAbstract || modifiers.shared || modifiers.untrusted) {
            throw error(previous(), context + " accept only private/pub visibility modifiers");
        }
    }

    private void validateInterfaceCallableModifiers(Modifiers modifiers) {
        if (modifiers.visibilitySeen || modifiers.async || modifiers.suspends || modifiers.generator || modifiers.nonLexical || modifiers.trapped
                || modifiers.isStatic || modifiers.isAbstract || modifiers.shared || modifiers.untrusted) {
            throw error(previous(),
                    "interface callable signatures currently accept only the structural callable modifier");
        }
    }

    private void validateNoModifiers(Modifiers modifiers, String context) {
        if (modifiers.visibilitySeen || modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural
                || modifiers.nonLexical || modifiers.trapped || modifiers.isStatic || modifiers.isAbstract
                || modifiers.shared || modifiers.untrusted) {
            throw error(previous(), context + " do not accept declaration modifiers");
        }
    }

    private boolean isContextualIdentifier(String lexeme) {
        return check(IDENT) && peek().lexeme().equals(lexeme);
    }

    /**
     * "define actor Name as ... end" is a declaration spelling, not a new actor
     * allocation primitive. Actor construction/spawn must still be lowered by
     * the runtime before source programs may instantiate actor classes.
     */
    private record ActorPrefix(Ast.ActorTransport transport, boolean serial) {
        private static final ActorPrefix DEFAULT =
                new ActorPrefix(Ast.ActorTransport.GENERIC, false);
    }

    /**
     * Contextual actor-only modifiers preserve ordinary variables named
     * "serial", "http", "tcp", "websocket" in unrelated grammar positions.
     * Recognizes both "define http actor" and "define serial http actor".
     */
    private ActorPrefix parseActorPrefix() {
        boolean serial = false;
        Ast.ActorTransport transport = Ast.ActorTransport.GENERIC;
        boolean recognized = false;
        for (;;) {
            if (matchContextualIdentifier("serial")) {
                if (serial) throw error(previous(), "duplicate serial actor modifier");
                serial = true;
                recognized = true;
            } else if (matchContextualIdentifier("http")) {
                if (transport != Ast.ActorTransport.GENERIC)
                    throw error(previous(), "actor cannot have multiple transport kinds");
                transport = Ast.ActorTransport.HTTP;
                recognized = true;
            } else if (matchContextualIdentifier("tcp")) {
                if (transport != Ast.ActorTransport.GENERIC)
                    throw error(previous(), "actor cannot have multiple transport kinds");
                transport = Ast.ActorTransport.TCP;
                recognized = true;
            } else if (matchContextualIdentifier("websocket")
                    || matchContextualIdentifier("ws")) {
                if (transport != Ast.ActorTransport.GENERIC)
                    throw error(previous(), "actor cannot have multiple transport kinds");
                transport = Ast.ActorTransport.WEBSOCKET;
                recognized = true;
            } else break;
        }
        if (recognized && !check(ACTOR) && !check(ISOACTOR))
            throw error(peek(), "serial/http/tcp/websocket modifiers require an actor declaration");
        return new ActorPrefix(transport, serial);
    }

    private Ast.ClassDecl parseDefinedActorClass(
            Token actorToken,
            Modifiers modifiers,
            List<Ast.Annotation> annotations,
            ActorPrefix prefix) {
        modifiers = mergeModifiers(modifiers, parseModifiers());
        Ast.ActorKind kind = resolveActorKind(
                actorToken, actorToken.type() == ISOACTOR, modifiers);
        if (modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural
                || modifiers.nonLexical || modifiers.trapped || modifiers.isStatic
                || modifiers.isAbstract) {
            throw error(actorToken,
                    "actor class declarations accept only visibility and actor-kind modifiers");
        }
        rejectCallableStructuralAnnotation(annotations, "actor class declarations");
        rejectDiscardedDeclarationAnnotations(annotations, "actor class declarations");
        return parseActorClass(kind, modifiers.visibility, prefix);
    }

    /**
     * Do not treat supervision, proxying, scheduling or mail routing as a
     * class/actor alias before compiler-enforced CONTROL, isolation and
     * versioned hot-code replacement exist in both backends.
     */
    private void rejectUnloweredRuntimeEntityDeclaration() {
        if (check(EOF)) return;
        String name = peek().lexeme();
        if (name.equals("readable") || name.equals("transform")
                || name.equals("writable") || name.equals("duplex")) {
            if (current + 2 < tokens.size()
                    && tokens.get(current + 1).lexeme().equals("pipe")
                    && tokens.get(current + 2).type() == ACTOR) {
                throw error(peek(), "source " + name + " pipe actor declarations "
                        + "require compiler-checked pipe role typing, bounded "
                        + "edge transport and cross-backend lowering; not yet enabled");
            }
            return;
        }
        if (!name.equals("supervisor") && !name.equals("mailman")
                && !name.equals("scheduler") && !name.equals("proxy")
                && !name.equals("pipe")) return;
        throw error(peek(), "source " + name + " declarations require "
                + "compiler-checked runtime entity lowering, supervisor "
                + "CONTROL admission and cross-backend parity; not yet enabled");
    }

    private boolean matchContextualIdentifier(String lexeme) {
        if (!isContextualIdentifier(lexeme)) return false;
        advance();
        return true;
    }

    private void consumeContextualIdentifier(String lexeme, String message) {
        if (!matchContextualIdentifier(lexeme)) throw error(peek(), message);
    }

    private boolean matchCaseKeyword() {
        // CASE is retained for token-stream/backward compatibility, but the
        // lexer emits ordinary IDENT for "case" so it remains legal elsewhere.
        return match(CASE) || matchContextualIdentifier("case");
    }

    private boolean matchWhenKeyword() {
        // WHEN is retained for token-stream/backward compatibility, but the
        // lexer emits ordinary IDENT for "when" so it remains legal elsewhere.
        return match(WHEN) || matchContextualIdentifier("when");
    }

    private Ast.ClassDecl parseActorClass(Ast.ActorKind actorKind, Ast.Visibility visibility) {
        return parseActorClass(actorKind, visibility, ActorPrefix.DEFAULT);
    }

    private Ast.ClassDecl parseActorClass(
            Ast.ActorKind actorKind, Ast.Visibility visibility, ActorPrefix prefix) {
        String name = consume(IDENT, "expected actor name").lexeme();
        List<String> generics = parseGenericParameters();
        List<Ast.TypeRef> parents = match(EXTENDS) ? parseTypeRefList() : List.of();
        List<Ast.TypeRef> interfaces = match(IMPLEMENTS, IMPL) ? parseTypeRefList() : List.of();

        boolean braceStyle = match(LBRACE);
        if (!braceStyle) match(AS);
        Token.Type terminator = braceStyle ? RBRACE : END;
        List<Ast.FieldDecl> fields = new ArrayList<>();
        List<Ast.MethodDecl> methods = new ArrayList<>();
        int requestConcurrency = 1;
        int messageConcurrency = 1;
        boolean declaredRequestConcurrency = false;
        boolean declaredMessageConcurrency = false;

        while (!check(terminator) && !check(EOF)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers mods = parseModifiers();
            if (isContextualIdentifier("int") && current + 1 < tokens.size()
                    && (tokens.get(current + 1).lexeme().equals("requestConcurrency")
                        || tokens.get(current + 1).lexeme().equals("messageConcurrency"))) {
                if (prefix.transport() == Ast.ActorTransport.GENERIC)
                    throw error(peek(), "requestConcurrency/messageConcurrency require an http/tcp/websocket actor");
                if (!annotations.isEmpty() || mods.visibilitySeen || mods.async
                        || mods.generator || mods.isStatic || mods.isAbstract
                        || mods.shared || mods.untrusted || mods.structural
                        || mods.nonLexical || mods.trapped)
                    throw error(peek(), "actor concurrency declarations cannot use modifiers or annotations");
                advance(); // int is a contextual type name
                String property = consume(IDENT, "expected actor concurrency property").lexeme();
                consume(EQUAL, "actor concurrency configuration requires a constant initializer");
                Token value = consume(INT, "actor concurrency must be a positive integer literal");
                int count;
                try {
                    count = Integer.parseInt(value.lexeme().replace("_", ""));
                } catch (NumberFormatException overflow) {
                    throw error(value, "actor concurrency limit exceeds the supported int range");
                }
                if (count < 1) throw error(value, "actor concurrency must be >= 1");
                consume(SEMICOLON, "actor concurrency configuration requires ';'");
                if (property.equals("requestConcurrency")) {
                    if (declaredRequestConcurrency) throw error(value, "duplicate requestConcurrency");
                    requestConcurrency = count;
                    declaredRequestConcurrency = true;
                } else {
                    if (declaredMessageConcurrency) throw error(value, "duplicate messageConcurrency");
                    messageConcurrency = count;
                    declaredMessageConcurrency = true;
                }
                continue;
            }
            if (isLegacyFnSpelling()) {
                if (mods.isStatic) throw error(peek(), "static actor functions use 'static fnc', not 'static fn'");
                throw error(peek(), "actor methods omit 'fn'/'fnc'; declare the method name directly");
            }
            if (isContextualIdentifier("constructor")) {
                throw error(peek(),
                        "actors do not declare constructors; actor state is initialized by the actor runtime");
            }
            if (check(DEFINE) || check(CLASS) || check(MODULE) || check(INTERFACE)
                    || check(ACTOR) || check(ISOACTOR)) {
                throw error(peek(),
                        "declarations cannot be nested inside actors; declare nested types at file or module scope");
            }

            if (isBindingKind(peek().type())) {
                validateActorFieldModifiers(mods);
                fields.add(parseField(annotations, mods.visibility));
                continue;
            }
            if (isContextualIdentifier("final") && checkNext(IDENT)) {
                validateActorFieldModifiers(mods);
                fields.add(parseFinalActorField(annotations, mods.visibility));
                continue;
            }

            boolean explicitFnc = match(FNC);
            boolean explicitMethod = matchExplicitMethodMarker();
            if (explicitFnc && explicitMethod) {
                throw error(previous(), "an instance member cannot combine 'fnc' and 'method' markers");
            }
            if (explicitFnc || explicitMethod) {
                mods = mergeModifiers(mods, parseModifiers());
            }
            validateActorMethodModifiers(mods);
            if (mods.isStatic && explicitMethod) {
                throw error(previous(), "static actor functions use 'static fnc', not 'static method'");
            }
            if (mods.isStatic && !explicitFnc) {
                consume(FNC, "static actor functions must be declared with 'static fnc'");
            }
            // Reserved lifecycle and unhandled-failure methods must not be
            // silently treated as ordinary actor methods before runtime
            // teardown dispatch and typed exceptional-effect lowering exist.
            if (check(IDENT) && peek().lexeme().equals("tear_down")) {
                int lifecycleStartToken = current;
                Ast.MethodDecl teardown = parseMethod(annotations, mods);
                // Fail closed at admission, not after state has already been
                // mutated during shutdown. Nested lambda/defer awaits are also
                // disallowed in this first synchronous lifecycle slice.
                for (int i = lifecycleStartToken; i < current; i++) {
                    Token token = tokens.get(i);
                    if (token.type() == AWAIT
                            || (token.type() == RT
                                    && i + 1 < current
                                    && tokens.get(i + 1).lexeme().equals("cooperate"))) {
                        throw error(token,
                                "actor tear_down cannot await or cooperate until "
                                        + "scheduler-owned async teardown lowering exists");
                    }
                }
                // tear_down is a runtime-only lifecycle entry point, not an
                // ordinary user-invokable actor method. The first executable
                // slice is synchronous; suspend/async awaits require a pinned
                // teardown continuation and are rejected until implemented.
                if (teardown.isStatic() || teardown.async() || teardown.suspends()
                        || teardown.arity() != 0
                        || !teardown.genericParameters().isEmpty()
                        || !teardown.returnType().name().equals("void")
                        || !teardown.returnType().arguments().isEmpty()) {
                    throw error(previous(),
                            "actor tear_down must be a synchronous, nonstatic, "
                                    + "zero-argument, nongeneric void lifecycle method");
                }
                if (methods.stream().anyMatch(m -> m.name().equals("tear_down"))) {
                    throw error(previous(), "actor tear_down may be declared only once");
                }
                methods.add(teardown);
                continue;
            }
            if (check(IDENT) && peek().lexeme().equals("on_panic")) {
                throw error(peek(), "actor-local on_panic is forbidden: "
                        + "panic goes directly to the supervisor CONTROL lane");
            }
            if (check(IDENT) && (peek().lexeme().equals("on_throw")
                    || peek().lexeme().equals("on_throws")
                    || peek().lexeme().equals("on_raise")
                    || peek().lexeme().equals("on_recover"))) {
                throw error(peek(), "actor unhandled-failure on_* handlers require "
                        + "compiler-enforced exhaustiveness and CONTROL dispatch; "
                        + "source handlers are not yet enabled");
            }
            methods.add(parseMethod(annotations, mods));
        }

        consume(terminator, braceStyle
                ? "expected '}' to close actor " + name
                : "expected 'end' to close actor " + name);
        if (prefix.serial() && (requestConcurrency != 1 || messageConcurrency != 1)) {
            throw error(previous(), "serial actors require requestConcurrency = 1 and messageConcurrency = 1");
        }
        return new Ast.ClassDecl(
                name, visibility, false, actorKind, generics, parents, interfaces, fields, null, methods,
                new Ast.ActorConcurrency(prefix.transport(), requestConcurrency, messageConcurrency));
    }

    private void validateActorFieldModifiers(Modifiers modifiers) {
        if (modifiers.visibility == Ast.Visibility.PUBLIC) {
            throw error(peek(), "actor state fields are private; expose state through actor methods");
        }
        if (modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural || modifiers.nonLexical
                || modifiers.trapped || modifiers.isStatic || modifiers.isAbstract || modifiers.shared || modifiers.untrusted) {
            throw error(peek(), "actor state fields do not accept callable/static/actor modifiers");
        }
    }

    private void validateActorMethodModifiers(Modifiers modifiers) {
        if (modifiers.generator || modifiers.nonLexical || modifiers.trapped || modifiers.isAbstract
                || modifiers.shared || modifiers.untrusted) {
            throw error(peek(), "actor methods do not accept generator, nlex, abstract, shared, or untrusted modifiers");
        }
    }

    private Ast.InterfaceDecl parseContract(Ast.Visibility visibility) {
        Ast.InterfaceDecl iface = parseInterface(visibility, true);
        return new Ast.InterfaceDecl(iface.name(), iface.visibility(), iface.genericParameters(), iface.parents(), iface.members(), true);
    }

    private Ast.InterfaceDecl parseDefinedInterface(Ast.Visibility visibility, Token.Type kind) {
        Ast.InterfaceDecl declaration = parseInterface(visibility);
        interfaceKinds.put(declaration, kind);
        return declaration;
    }

    private Ast.InterfaceDecl parseInterface(Ast.Visibility visibility) { return parseInterface(visibility, false); }

    private Ast.InterfaceDecl parseInterface(Ast.Visibility visibility, boolean moduleContract) {
        String name = consume(IDENT, "expected interface name").lexeme();
        List<String> generics = parseGenericParameters();
        List<Ast.TypeRef> parents = match(EXTENDS) ? parseTypeRefList() : List.of();
        boolean braceStyle = match(LBRACE);
        if (!braceStyle) match(AS);
        Token.Type terminator = braceStyle ? RBRACE : END;

        List<Ast.InterfaceMember> members = new ArrayList<>();
        while (!check(terminator) && !check(EOF)) {
            List<Ast.Annotation> memberAnnotations = parseAnnotations();
            Modifiers memberModifiers = parseModifiers();

            if (isLegacyFnSpelling()) {
                throw error(peek(), "interface functions are declared with 'fnc', not 'fn'");
            }
            if (match(FNC)) {
                memberModifiers = mergeModifiers(memberModifiers, parseModifiers());
                validateInterfaceCallableModifiers(memberModifiers);
                CallableStructural callableStructural = normalizeCallableStructural(memberAnnotations, memberModifiers.structural);
                if (!callableStructural.annotations().isEmpty()) {
                    throw error(previous(),
                            "interface-member annotations are not represented by the current AST; "
                                    + "only @structural is accepted here and unsupported annotations must not be silently discarded");
                }
                String memberName = consumeCallableName("expected interface function name");
                List<String> memberGenerics = parseGenericParameters();
                consume(LPAREN, "expected '(' after interface function name");
                List<Ast.Param> params = parseParametersUntil(RPAREN);
                consume(RPAREN, "expected ')' after interface parameters");
                Ast.TypeRef returns = match(FAT_ARROW, COLON) ? parseTypeRef() : Ast.TypeRef.simple("void");
                Ast.EffectSpec effects = parseCheckedEffects();
                consumeMemberTerminator(terminator, "interface function signature should end with ';'");
                members.add(new Ast.InterfaceFunctionDecl(memberName, callableStructural.enabled(), memberGenerics, params, returns, effects));
                continue;
            }

            validateNoModifiers(memberModifiers, "interface fields");
            if (!memberAnnotations.isEmpty()) {
                throw error(previous(),
                        "interface-field annotations are not represented by the current AST and must not be silently discarded");
            }

            // A bare module-contract field requires readable access only.
            // Explicit bindings retain the current rebindability/referent model.
            Ast.BindingKind fieldBindingKind = moduleContract ? null : Ast.BindingKind.VAL;
            boolean fieldMutableReferent = !moduleContract;
            if (isBindingKind(peek().type())) {
                fieldBindingKind = parseBindingKind();
                fieldMutableReferent = parseReferentMutability(fieldBindingKind);
            }

            if (check(IDENT) && checkNext(COLON)) {
                String fieldName = advance().lexeme();
                consume(COLON, "expected ':' after interface field name");
                Ast.TypeRef type = parseTypeRef();
                consumeMemberTerminator(terminator, "interface field signature should end with ';'");
                members.add(new Ast.InterfaceFieldDecl(
                        fieldName, type, fieldBindingKind, fieldMutableReferent));
                continue;
            }

            Ast.TypeRef type = parseTypeRef();
            String fieldName = consume(IDENT, "expected interface field name").lexeme();
            consumeMemberTerminator(terminator, "interface field signature should end with ';'");
            members.add(new Ast.InterfaceFieldDecl(
                    fieldName, type, fieldBindingKind, fieldMutableReferent));
        }

        consume(terminator, braceStyle ? "expected '}' to close interface " + name : "expected 'end' to close interface " + name);
        return new Ast.InterfaceDecl(name, visibility, generics, parents, members, moduleContract);
    }

    private List<Ast.TypeRef> parseTypeRefList() {
        List<Ast.TypeRef> refs = new ArrayList<>();
        do refs.add(parseTypeRef()); while (match(COMMA));
        return refs;
    }

    private Ast.FieldDecl parseField(List<Ast.Annotation> annotations, Ast.Visibility visibility) {
        rejectCallableStructuralAnnotation(annotations, "fields");
        Ast.BindingKind kind = parseBindingKind();
        boolean mutableReferent = parseReferentMutability(kind);
        Ast.TypeRef type = null;
        String name;
        if (check(IDENT) && checkNext(EQUAL)) {
            name = advance().lexeme();
        } else {
            type = parseTypeRef();
            name = consume(IDENT, "expected field name").lexeme();
        }
        Ast.Expr initializer = match(EQUAL) ? parseExpression() : null;
        if (type == null && initializer == null) {
            throw error(previous(), "inferred field '" + name + "' requires an initializer");
        }
        consumeExpressionStatementTerminator(initializer, "field declaration should end with ';'");
        if (type != null) type = applyTypeMetadataAnnotations(type, annotations, false);
        return new Ast.FieldDecl(name, visibility, kind, type, annotations, initializer, mutableReferent);
    }

    private boolean looksLikeTypeFirstClassField() {
        int mark = current;
        try {
            match(MUT); // Explicit mutable referent is independent of finality.
            parseTypeRef();
            if (!check(IDENT)) return false;
            advance();
            return check(COLON) || check(EQUAL);
        } catch (IllegalArgumentException ex) {
            return false;
        } finally {
            current = mark;
        }
    }

    private Ast.FieldDecl parseTypeFirstClassField(
            List<Ast.Annotation> annotations, Ast.Visibility visibility) {
        rejectCallableStructuralAnnotation(annotations, "fields");
        boolean isFinal = matchContextualIdentifier("final");
        boolean mutableReferent = match(MUT);
        Ast.TypeRef type = applyTypeMetadataAnnotations(parseTypeRef(), annotations, false);
        String name = consume(IDENT, "expected class field name after type").lexeme();
        if (!match(COLON, EQUAL)) {
            throw error(peek(), "class field initializer requires ':' or '='");
        }
        Ast.Expr initializer = parseExpression();
        consumeExpressionClassFieldTerminator(initializer, "field declaration should end with ';'");
        // Slot reassignment and mutation through its stored referent are
        // separate capabilities. 'final' does NOT silently grant mutable
        // referent access; only an explicit 'mut' modifier requests it.
        Ast.BindingKind kind = isFinal ? Ast.BindingKind.VAL : Ast.BindingKind.LET;
        return new Ast.FieldDecl(name, visibility, kind, type, annotations, initializer, mutableReferent);
    }

    private Ast.FieldDecl parseColonField(List<Ast.Annotation> annotations, Ast.Visibility visibility) {
        rejectCallableStructuralAnnotation(annotations, "fields");
        String name = consume(IDENT, "expected field name").lexeme();
        consume(COLON, "expected ':' after field name");
        Ast.TypeRef type = parseTypeRef();
        Ast.Expr initializer = match(EQUAL) ? parseExpression() : null;
        Ast.BindingKind kind = hasAnnotation(annotations, "FromJson") ? Ast.BindingKind.LET : Ast.BindingKind.VAL;
        type = applyTypeMetadataAnnotations(type, annotations, false);
        consumeExpressionClassFieldTerminator(initializer, "field declaration should end with ';'");
        return new Ast.FieldDecl(name, visibility, kind, type, annotations, initializer);
    }

    /** Actor-state 'final name: Type' is a const/immutable field spelling. */
    private Ast.FieldDecl parseFinalActorField(List<Ast.Annotation> annotations, Ast.Visibility visibility) {
        rejectCallableStructuralAnnotation(annotations, "fields");
        consumeContextualIdentifier("final", "expected 'final' actor field");
        String name = consume(IDENT, "expected field name after 'final'").lexeme();
        consume(COLON, "expected ':' after final field name");
        Ast.TypeRef type = applyTypeMetadataAnnotations(parseTypeRef(), annotations, false);
        Ast.Expr initializer = match(EQUAL) ? parseExpression() : null;
        consumeExpressionClassFieldTerminator(initializer, "final actor field should end with ';'");
        return new Ast.FieldDecl(name, visibility, Ast.BindingKind.CONST,
                type, annotations, initializer, false);
    }

    private Ast.FieldDecl parseModuleBinding(List<Ast.Annotation> annotations, Ast.Visibility visibility) {
        rejectCallableStructuralAnnotation(annotations, "module bindings");
        Ast.BindingKind kind = parseBindingKind();
        boolean mutableReferent = parseReferentMutability(kind);
        Ast.TypeRef type = null;
        String name;
        boolean functionShorthand = match(FNC);
        if (functionShorthand && (kind != Ast.BindingKind.CONST || mutableReferent)) {
            throw error(previous(), "lambda binding shorthand requires 'const fnc'");
        }
        if (functionShorthand) name = consume(IDENT, "expected function binding name").lexeme();
        else if (check(IDENT) && checkNext(EQUAL)) name = advance().lexeme();
        else {
            type = parseTypeRef();
            name = consume(IDENT, "expected binding name").lexeme();
        }
        if (type != null) type = applyTypeMetadataAnnotations(type, annotations, false);
        consume(EQUAL, "module bindings require an initializer");
        Ast.Expr initializer = parseExpression();
        if (functionShorthand && !(initializer instanceof Ast.LambdaExpr)) {
            throw error(previous(), "'const fnc' requires a lambda initializer");
        }
        consumeExpressionStatementTerminator(initializer, "module binding should end with ';'");
        return new Ast.FieldDecl(name, visibility, kind, type, annotations, initializer, mutableReferent);
    }

    private Ast.MethodDecl parseMethod(List<Ast.Annotation> annotations, Modifiers mods) {
        return parseMethod(annotations, mods, false);
    }

    private Ast.MethodDecl parseMethod(List<Ast.Annotation> annotations, Modifiers mods,
                                       boolean enclosingAbstractClass) {
        CallableStructural structural = normalizeCallableStructural(annotations, mods.structural);
        annotations = structural.annotations();
        String name = parseMethodName();
        List<String> generics = parseGenericParameters();
        consume(LPAREN, "expected '(' after method name");

        Ast.TypeRef receiverType = null;
        List<Ast.Param> params;
        boolean mutableReceiver = check(MUT) && checkNext(SELF);
        if (mods.isStatic && (check(SELF) || mutableReceiver)) {
            throw error(peek(), "static class functions do not have a self receiver");
        }

        // A leading receiver is never a value parameter or overload discriminator:
        //   method(self, Type value)       -> default read-only receiver
        //   method(self)                   -> default read-only receiver, zero args
        //   method(mut self, Type value)   -> mutable receiver
        // Legacy two-parenthesis forms remain supported:
        //   method(mut self)(params)       -> mutable receiver
        //   method(self Foo)(params)       -> explicitly typed receiver
        // Ordinary instance methods without a receiver marker also receive self.
        int parameterStart = current;
        boolean explicitReceiver = false;
        boolean inlineReceiver = false;
        boolean inlineReceiverComma = false;
        if (mutableReceiver) {
            advance(); // mut
            consume(SELF, "mutable receiver syntax is 'mut self'");
            if (match(COMMA)) {
                inlineReceiver = true;
                inlineReceiverComma = true;
            } else if (check(RPAREN) && !checkNext(LPAREN)) {
                inlineReceiver = true;
            } else {
                Ast.TypeRef target = check(RPAREN) ? Ast.TypeRef.simple("self") : parseTypeRef();
                receiverType = Ast.TypeRef.borrowed(target, true);
                explicitReceiver = true;
            }
            if (inlineReceiver) {
                receiverType = Ast.TypeRef.borrowed(Ast.TypeRef.simple("self"), true);
            }
        } else if (check(SELF)) {
            advance();
            if (match(COMMA)) {
                inlineReceiver = true;
                inlineReceiverComma = true;
            } else if (check(RPAREN) && !checkNext(LPAREN)) {
                inlineReceiver = true;
            } else {
                Ast.TypeRef candidateReceiver = parseTypeRef();
                if (check(RPAREN) && checkNext(LPAREN)) {
                    receiverType = candidateReceiver;
                    explicitReceiver = true;
                } else {
                    current = parameterStart;
                }
            }
        }

        if (inlineReceiver) {
            if (inlineReceiverComma && check(RPAREN)) {
                throw error(peek(), "receiver 'self' must be followed by a value parameter after ','");
            }
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after inline self receiver and method parameters");
        } else if (explicitReceiver) {
            consume(RPAREN, "expected ')' after explicit self receiver");
            consume(LPAREN, mutableReceiver
                    ? "explicit mutable receiver form is method(mut self)(params)"
                    : "explicit receiver form is method(self Type)(params)");
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after method parameters");
        } else {
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after method parameters");
        }

        Ast.TypeRef returnType = parseReturnType(annotations);
        Ast.EffectSpec effects = parseCheckedEffects();
        // An abstract class may declare required slots as `name(args): Type;`.
        // Such signatures are abstract, but remain private unless explicitly pub.
        // Their identity is still name + arity only.
        // Outside abstract classes, a stray semicolon is never a method body.
        boolean implicitAbstract = check(SEMICOLON) && !mods.isAbstract && enclosingAbstractClass;
        if (check(SEMICOLON) && !mods.isAbstract && !enclosingAbstractClass) {
            throw error(peek(), "signature-only method '" + name
                    + "' requires an abstract class or explicit 'abstract' modifier");
        }
        List<Ast.Stmt> body;
        if (mods.isAbstract || implicitAbstract) {
            consumeStatementTerminator("abstract method should end with ';'");
            body = List.of();
        } else body = parseBlock();
        return new Ast.MethodDecl(name, mods.visibility, mods.isStatic, mods.isAbstract || implicitAbstract, mods.async, mods.suspends, structural.enabled(),
                receiverType, generics, params, returnType, annotations, body, effects);
    }

    private String parseMethodName() {
        if (match(LBRACKET)) {
            String namespace = consume(IDENT, "expected symbol namespace").lexeme();
            consume(DOT, "expected '.' in symbol method");
            String symbol = consume(IDENT, "expected symbol name").lexeme();
            consume(RBRACKET, "expected ']' after symbol method");
            if (!namespace.equals("Symbol")) throw error(previous(), "symbol methods must use Symbol.<name>");
            return namespace + "." + symbol;
        }
        return consumeCallableName("expected method name (methods omit 'fnc')");
    }


    private ForwardType parseForwardType(List<Ast.Annotation> annotations, Modifiers before) {
        Token start = advance(); // contextual 'declare'; do not reserve it globally
        if (!annotations.isEmpty() || before.visibilitySeen || before.async || before.suspends
                || before.generator || before.structural || before.nonLexical || before.trapped
                || before.isStatic || before.isAbstract || before.shared || before.untrusted) {
            throw error(start, "forward declarations must start with 'declare'; visibility follows it");
        }
        Modifiers after = parseModifiers();
        if (after.async || after.suspends || after.generator || after.structural
                || after.nonLexical || after.trapped || after.isStatic || after.isAbstract
                || after.shared || after.untrusted) {
            throw error(start, "forward declarations accept only private/pub visibility");
        }
        if (!match(TYPE, TRAIT, INTERFACE)) {
            throw error(peek(), "'declare' supports only type, trait or interface");
        }
        Token.Type kind = previous().type();
        String name = consume(IDENT, "expected forward type name").lexeme();
        List<String> generics = parseGenericParameters();
        consume(SEMICOLON, "forward declaration must end with ';' and cannot supply a body");
        return new ForwardType(kind, name, after.visibility, List.copyOf(generics));
    }

    private static String definedName(Ast.Decl declaration) {
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.name();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.name();
        if (declaration instanceof Ast.ClassDecl klass) return klass.name();
        if (declaration instanceof Ast.FunctionDecl fn) return fn.name();
        if (declaration instanceof Ast.FieldDecl field) return field.name();
        return null;
    }

    private void validateForwardTypes(List<ForwardType> declarations, List<Ast.Decl> definitions, String scope) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ForwardType forward : declarations) {
            if (!seen.add(forward.name())) {
                throw new IllegalArgumentException("duplicate forward declaration '" + forward.name() + "' in " + scope);
            }
            Ast.Decl definition = null;
            for (Ast.Decl candidate : definitions) {
                // Value-side functions and bindings occupy a distinct namespace.
                // They cannot satisfy a forward type, but also must not make an
                // otherwise valid type signature spuriously ambiguous.
                if (!(candidate instanceof Ast.TypeAliasDecl
                        || candidate instanceof Ast.InterfaceDecl
                        || candidate instanceof Ast.ClassDecl)) continue;
                if (!forward.name().equals(definedName(candidate))) continue;
                if (definition != null) {
                    throw new IllegalArgumentException("ambiguous forward type '" + forward.name()
                            + "': multiple definitions in " + scope);
                }
                definition = candidate;
            }
            if (definition == null) {
                throw new IllegalArgumentException("unresolved forward declaration '"
                        + forward.name() + "' in " + scope + "; cross-file forwards are not supported");
            }
            Token.Type actual;
            List<String> generics;
            Ast.Visibility visibility;
            if (definition instanceof Ast.TypeAliasDecl alias) {
                actual = TYPE;
                generics = alias.genericParameters();
                visibility = alias.visibility();
            } else if (definition instanceof Ast.InterfaceDecl iface) {
                actual = iface.moduleContract() ? CONTRACT : interfaceKinds.getOrDefault(iface, INTERFACE);
                generics = iface.genericParameters();
                visibility = iface.visibility();
            } else {
                throw new IllegalArgumentException("forward type '" + forward.name()
                        + "' resolved to a value declaration instead of a type/trait/interface in " + scope);
            }
            if (actual != forward.kind()) {
                throw new IllegalArgumentException("forward declaration kind mismatch for '"
                        + forward.name() + "' in " + scope + ": expected " + forward.kind()
                        + " but definition is " + actual);
            }
            if (!generics.equals(forward.generics())) {
                throw new IllegalArgumentException("forward declaration generic parameter mismatch for '"
                        + forward.name() + "' in " + scope);
            }
            if (visibility != forward.visibility()) {
                throw new IllegalArgumentException("forward declaration visibility mismatch for '"
                        + forward.name() + "' in " + scope + "; cannot widen or narrow visibility");
            }
        }
    }

    private Ast.TypeAliasDecl parseTypeAlias(Ast.Visibility visibility) {
        // The contextual marker must be followed by a name. In particular,
        // legacy declarations such as `type alias = int;` continue to use
        // "alias" as their actual identifier rather than a new reserved word.
        boolean namedAlias = check(IDENT) && peek().lexeme().equals("alias") && checkNext(IDENT);
        if (namedAlias) advance();
        String name = consume(IDENT, "expected type alias name").lexeme();
        List<String> generics = parseGenericParameters();
        if (namedAlias) {
            consume(AS, "expected 'as' after named type alias");
        } else {
            consume(EQUAL, "expected '=' in type alias");
        }
        Ast.TypeRef target = parseTypeRef();
        // A refinement is not an ordinary transparent alias: never erase
        // its predicate at this parser boundary. Enable this syntax only
        // together with typed proof-preserving rules + Zorde lowering.
        if (isContextualIdentifier("with") || check(AS)) {
            throw error(peek(), "refined type alias requires checked constraint HIR, "
                    + "Zorde exact-value validation and proof-preserving runtime lowering; "
                    + "with/as constraints are not yet executable");
        }
        consumeStatementTerminator("type alias should end with ';'");
        return new Ast.TypeAliasDecl(name, visibility, generics, target);
    }

    private List<Ast.Annotation> parseAnnotations() {
        List<Ast.Annotation> result = new ArrayList<>();
        while (match(AT)) {
            String name = consume(IDENT, "expected annotation name").lexeme();
            // These names claim code generation, target selection or ABI effects.
            // Until a typed annotation registry and AOT lowering exist, retaining
            // them as inert metadata would silently change program semantics.
            if (List.of("derive", "Derive", "repr", "Repr", "align",
                    "cfg", "Cfg", "inline", "Inline", "expand", "GenerateBuilder",
                    "GenerateSchema", "GenerateClient", "ActorMessage",
                    "PipelineStage", "RequiresState").contains(name)) {
                throw error(previous(), "@" + name
                        + " requires compile-time semantic validation and guest-AOT lowering; not implemented");
            }
            List<Ast.TypeRef> args = new ArrayList<>();
            Token.Type close = null;
            if (match(LT)) close = GT;
            else if (match(LPAREN)) close = RPAREN;
            if (close != null) {
                if (!check(close)) {
                    do {
                        if (check(IDENT) && checkNext(EQUAL)) {
                            String parameterName = advance().lexeme();
                            consume(EQUAL, "expected '=' after named annotation parameter");
                            args.add(Ast.TypeRef.namedParameter(parameterName, parseMetaValue()));
                        } else {
                            args.add(parseMetaValue());
                        }
                    } while (match(COMMA));
                }
                consume(close, "expected annotation terminator");
            }
            result.add(new Ast.Annotation(name, canonicalizeNamedParameters(args)));
        }
        return result;
    }

    private Modifiers parseModifiers() {
        Ast.Visibility visibility = Ast.Visibility.PRIVATE;
        boolean async = false;
        boolean suspends = false;
        boolean generator = false;
        boolean structural = false;
        boolean nonLexical = false;
        boolean trapped = false;
        boolean isStatic = false;
        boolean isAbstract = false;
        boolean shared = false;
        boolean untrusted = false;
        boolean visibilitySeen = false;
        boolean asyncSeen = false;
        boolean suspendsSeen = false;
        boolean generatorSeen = false;
        boolean structuralSeen = false;
        boolean nonLexicalSeen = false;
        boolean trapSeen = false;
        boolean staticSeen = false;
        boolean abstractSeen = false;
        boolean sharedSeen = false;
        boolean untrustedSeen = false;

        while (true) {
            // These spellings are recognized but deliberately unavailable
            // until typed RW admission / actor pipeline source lowering is
            // implemented. Never admit them as ordinary method names or
            // silently degrade the read mode to an exclusive write gate.
            if (isAdjacentCompoundKeyword("critical", "read")
                    || isAdjacentCompoundKeyword("critical", "write")) {
                throw error(peek(), "reader/writer critical declaration requires "
                        + "checked protected-state effects and scheduler-owned RW permits");
            }
            if (isAdjacentCompoundKeyword("transform", "pipe")) {
                throw error(peek(), "transform-pipe actor requires "
                        + "typed pipeline role, forwarding and transport lowering");
            }
            if (match(PUB)) {
                if (visibilitySeen) throw error(previous(), "duplicate/conflicting visibility modifier");
                visibilitySeen = true;
                visibility = Ast.Visibility.PUBLIC;
            } else if (match(PRIVATE)) {
                if (visibilitySeen) throw error(previous(), "duplicate/conflicting visibility modifier");
                visibilitySeen = true;
                visibility = Ast.Visibility.PRIVATE;
            } else if (match(ASYNC)) {
                if (asyncSeen) throw error(previous(), "duplicate 'async' modifier");
                asyncSeen = true;
                async = true;
            } else if (match(SUSPENDS) || matchContextualSuspendModifier()) {
                if (suspendsSeen) throw error(previous(), "duplicate 'suspends' modifier");
                suspendsSeen = true;
                suspends = true;
            } else if (match(GENERATOR)) {
                if (generatorSeen) throw error(previous(), "duplicate 'generator' modifier");
                generatorSeen = true;
                generator = true;
                // generator* is declaration sugar; plain generator remains accepted.
                match(STAR);
            } else if (check(IDENT) && peek().lexeme().equals("gen") && checkNext(STAR)) {
                Token gen = advance();
                advance(); // '*'
                if (generatorSeen) throw error(gen, "duplicate generator modifier");
                generatorSeen = true;
                generator = true;
            } else if (matchContextualStructuralModifier()) {
                if (structuralSeen) throw error(previous(), "duplicate 'structural' modifier");
                structuralSeen = true;
                structural = true;
            } else if (match(NLEX)) {
                if (nonLexicalSeen) throw error(previous(), "duplicate 'nlex' modifier");
                nonLexicalSeen = true;
                nonLexical = true;
            } else if (match(TRAP)) {
                if (trapSeen) throw error(previous(), "duplicate 'trap' modifier");
                trapSeen = true;
                trapped = true;
            } else if (match(STATIC)) {
                if (staticSeen) throw error(previous(), "duplicate 'static' modifier");
                staticSeen = true;
                isStatic = true;
            } else if (match(ABSTRACT)) {
                if (abstractSeen) throw error(previous(), "duplicate 'abstract' modifier");
                abstractSeen = true;
                isAbstract = true;
            } else if (matchContextualShared()) {
                if (sharedSeen) throw error(previous(), "duplicate 'shared' modifier");
                sharedSeen = true;
                shared = true;
            } else if (match(UNTRUSTED)) {
                if (untrustedSeen) throw error(previous(), "duplicate 'untrusted' modifier");
                untrustedSeen = true;
                untrusted = true;
            } else {
                break;
            }
        }
        if (async && suspends) throw error(previous(), "'async suspends' is redundant; choose one");
        return new Modifiers(visibility, visibilitySeen, async, suspends, generator, structural, nonLexical, trapped, isStatic, isAbstract, shared, untrusted);
    }

    private Modifiers mergeModifiers(Modifiers before, Modifiers after) {
        if (before.visibilitySeen && after.visibilitySeen) {
            throw error(previous(), "duplicate/conflicting visibility modifier");
        }
        if (before.async && after.async) throw error(previous(), "duplicate 'async' modifier");
        if (before.suspends && after.suspends) throw error(previous(), "duplicate 'suspends' modifier");
        if (before.async && after.suspends || before.suspends && after.async)
            throw error(previous(), "'async suspends' is redundant; choose one");
        if (before.generator && after.generator) throw error(previous(), "duplicate 'generator' modifier");
        if (before.structural && after.structural) throw error(previous(), "duplicate 'structural' modifier");
        if (before.nonLexical && after.nonLexical) throw error(previous(), "duplicate 'nlex' modifier");
        if (before.trapped && after.trapped) throw error(previous(), "duplicate 'trap' modifier");
        if (before.isStatic && after.isStatic) throw error(previous(), "duplicate 'static' modifier");
        if (before.isAbstract && after.isAbstract) throw error(previous(), "duplicate 'abstract' modifier");
        if (before.shared && after.shared) throw error(previous(), "duplicate 'shared' modifier");
        if (before.untrusted && after.untrusted) throw error(previous(), "duplicate 'untrusted' modifier");

        return new Modifiers(
                after.visibilitySeen ? after.visibility : before.visibility,
                before.visibilitySeen || after.visibilitySeen,
                before.async || after.async,
                before.suspends || after.suspends,
                before.generator || after.generator,
                before.structural || after.structural,
                before.nonLexical || after.nonLexical,
                before.trapped || after.trapped,
                before.isStatic || after.isStatic,
                before.isAbstract || after.isAbstract,
                before.shared || after.shared,
                before.untrusted || after.untrusted);
    }

    /**
     * Parse signature-only checked exceptional effects. These names remain
     * contextual so source-level throw/raise/panic keywords cannot be
     * misinterpreted as executable statements.
     */
    private Ast.EffectSpec parseCheckedEffects() {
        List<Ast.TypeRef> thrown = List.of();
        List<Ast.TypeRef> raised = List.of();
        List<Ast.TypeRef> panicked = List.of();
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (isContextualIdentifier("throws")
                || isContextualIdentifier("raises")
                || isContextualIdentifier("panics")) {
            String category = advance().lexeme();
            if (!seen.add(category)) {
                throw error(previous(), "duplicate checked " + category + " clause");
            }
            List<Ast.TypeRef> types = new ArrayList<>();
            if (check(LBRACE) || check(SEMICOLON) || check(END)
                    || check(EOF) || check(RBRACE)
                    || isContextualIdentifier("throws")
                    || isContextualIdentifier("raises")
                    || isContextualIdentifier("panics")) {
                throw error(peek(), "checked " + category + " requires a type");
            }
            types.add(parseTypeRef());
            while (match(COMMA)) types.add(parseTypeRef());
            if (new java.util.HashSet<>(types).size() != types.size()) {
                throw error(previous(), "duplicate checked " + category + " type");
            }
            switch (category) {
                case "throws" -> thrown = List.copyOf(types);
                case "raises" -> raised = List.copyOf(types);
                case "panics" -> panicked = List.copyOf(types);
                default -> throw new IllegalStateException("unknown checked effect");
            }
        }
        return new Ast.EffectSpec(thrown, raised, panicked);
    }

    private Ast.TypeRef parseReturnType(List<Ast.Annotation> annotations) {
        Ast.TypeRef annotated = null;
        for (Ast.Annotation annotation : annotations) {
            if (annotation.name().equals("Ret")) {
                if (annotation.arguments().size() != 1) throw error(previous(), "@Ret requires exactly one type");
                annotated = annotation.arguments().getFirst();
            }
        }

        if (check(FAT_ARROW)) {
            throw error(peek(),
                    "fat arrow '=>' is reserved for function types/interface callable signatures; "
                            + "named executable callables use ': ReturnType' or '-> ReturnType'");
        }

        Ast.TypeRef declared = null;
        if (match(COLON) || match(ARROW)) {
            declared = parseTypeRef();
        }
        if (annotated != null && declared != null && !sameType(annotated, declared)) {
            throw error(previous(), "@Ret type and declared return type disagree");
        }
        return declared != null ? declared : annotated != null ? annotated : Ast.TypeRef.simple("void");
    }

    // A pipe after a name-first type may be a union separator or the
    // parameter-list terminator. The latter is followed by the slim arrow.
    // Preserve unrestricted unions in ordinary (...) parameter declarations.
    private Ast.TypeRef parsePipeParameterType() {
        Ast.TypeRef first = parseTypeAtom();
        if (!check(PIPE) || checkNext(ARROW)) return first;

        List<Ast.TypeRef> options = new ArrayList<>();
        options.add(first);
        do {
            consume(PIPE, "expected '|' between union parameter types");
            options.add(parseTypeAtom());
        } while (check(PIPE) && !checkNext(ARROW));
        return Ast.TypeRef.union(options);
    }

    private List<Ast.Param> parseDeclaredPipeParameters() {
        if (check(PIPE)) return List.of();
        List<Ast.Param> params = new ArrayList<>();
        do {
            boolean structural = matchContextualKeyword("structural");

            // Migration spelling for named-argument-friendly declarations:
            // |value: Type| is accepted, while oreslang-format emits |Type value|.
            if (!structural && check(IDENT) && checkNext(COLON)) {
                String name = advance().lexeme();
                consume(COLON, "expected ':' after compatibility parameter name");
                Ast.TypeRef type = parsePipeParameterType();
                boolean mutable = match(MUT);
                params.add(new Ast.Param(type, name, false, mutable));
                continue;
            }

            Ast.TypeRef type = parsePipeParameterType();
            boolean mutable = match(MUT);
            String name = consume(IDENT, "lambda-style callable declaration parameters require canonical 'Type name'").lexeme();
            if (structural && mutable) {
                throw error(previous(), "structural parameters are read-only borrowed views and cannot be 'mut'");
            }
            params.add(new Ast.Param(type, name, structural, mutable));
        } while (match(COMMA));
        requireDistinctParameterNames(params);
        return List.copyOf(params);
    }

    private boolean matchContextualKeyword(String keyword) {
        if (!check(IDENT) || !peek().lexeme().equals(keyword)) return false;
        advance();
        return true;
    }

    private boolean sameType(Ast.TypeRef a, Ast.TypeRef b) {
        return a.name().equals(b.name()) && a.arguments().equals(b.arguments()) && a.inferArguments() == b.inferArguments();
    }

    private List<Ast.GenericParameter> parseGenericParameterSpecs() {
        if (!match(LT)) return List.of();
        List<Ast.GenericParameter> specs = new ArrayList<>();
        java.util.Set<String> declared = new java.util.HashSet<>();
        do {
            String name = consume(IDENT, "expected generic parameter name").lexeme();
            if (name.equals("_") || !declared.add(name)) {
                throw error(previous(), "invalid or duplicate generic parameter '" + name + "'");
            }
            Ast.TypeRef defaultType = match(EQUAL) ? parseTypeRef() : null;
            specs.add(new Ast.GenericParameter(name, defaultType));
        } while (match(COMMA));
        consume(GT, "expected '>' after generic parameters");
        return List.copyOf(specs);
    }

    private List<String> parseGenericParameters() {
        if (!match(LT)) return List.of();
        List<String> names = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        do {
            String name = consume(IDENT, "expected generic parameter name").lexeme();
            if (!seen.add(name)) {
                throw error(previous(), "duplicate generic parameter name '" + name + "'");
            }
            names.add(name);
        } while (match(COMMA));
        consume(GT, "expected '>' after generic parameters");
        return List.copyOf(names);
    }

    private void requireDistinctParameterNames(List<Ast.Param> params) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Ast.Param param : params) {
            if (!names.add(param.name())) {
                throw error(previous(), "duplicate parameter name '" + param.name() + "'");
            }
        }
    }

    private List<Ast.Param> parseParametersUntil(Token.Type terminator) {
        return parseParametersUntil(terminator, java.util.Set.of());
    }

    private List<Ast.Param> parseParametersUntil(Token.Type terminator, java.util.Set<String> annotationStructuralNames) {
        if (check(terminator)) return List.of();
        List<Ast.Param> params = new ArrayList<>();
        do {
            boolean structural = matchContextualKeyword("structural");

            // Canonical type-first structural spelling: structural Foo y
            if (structural) {
                Ast.TypeRef type = parseTypeRef();
                boolean mutable = match(MUT);
                String name = consume(IDENT, "expected parameter name").lexeme();
                if (mutable) {
                    throw error(previous(), "structural parameters are read-only borrowed views and cannot be 'mut'");
                }
                params.add(new Ast.Param(type, name, true, false));
                continue;
            }

            // Compatibility name-first structural spelling: y structural Foo
            if (check(IDENT) && checkNextLexeme("structural")) {
                String name = advance().lexeme();
                Token marker = consume(IDENT, "expected structural");
                if (!marker.lexeme().equals("structural")) throw error(marker, "expected structural");
                Ast.TypeRef type = parseTypeRef();
                boolean mutable = match(MUT);
                if (mutable) {
                    throw error(previous(), "structural parameters are read-only borrowed views and cannot be 'mut'");
                }
                params.add(new Ast.Param(type, name, true, false));
                continue;
            }

            // Function annotation spelling: @AllowStructural(y) fnc x(y Foo)
            if (check(IDENT) && annotationStructuralNames.contains(peek().lexeme()) && checkNext(IDENT)) {
                String name = advance().lexeme();
                Ast.TypeRef type = parseTypeRef();
                boolean mutable = match(MUT);
                if (mutable) {
                    throw error(previous(), "structural parameters are read-only borrowed views and cannot be 'mut'");
                }
                params.add(new Ast.Param(type, name, true, false));
                continue;
            }

            // Existing type-first compatibility spelling: @Structural Foo y
            if (match(AT)) {
                String annotation = consume(IDENT, "expected parameter annotation").lexeme();
                if (!annotation.equals("Structural")) throw error(previous(), "only @Structural is currently supported on parameters");
                structural = true;
            }

            // Temporary compatibility spelling: name: Type. Canonical source
            // remains Type name so ':' stays available for named call arguments.
            if (!structural && check(IDENT) && checkNext(COLON)) {
                String name = advance().lexeme();
                consume(COLON, "expected ':' after compatibility parameter name");
                Ast.TypeRef type = parseTypeRef();
                boolean mutable = match(MUT);
                params.add(new Ast.Param(type, name, false, mutable));
                continue;
            }

            Ast.TypeRef type = parseTypeRef();
            boolean mutable = match(MUT);
            String name = consume(IDENT, "expected parameter name after type").lexeme();
            if (structural && mutable) {
                throw error(previous(), "structural parameters are read-only borrowed views and cannot be 'mut'");
            }
            params.add(new Ast.Param(type, name, structural, mutable));
        } while (match(COMMA));
        requireDistinctParameterNames(params);
        return params;
    }

    private java.util.Set<String> structuralAnnotationNames(List<Ast.Annotation> annotations) {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (Ast.Annotation annotation : annotations) {
            if (!annotation.name().equals("AllowStructural")) continue;
            for (Ast.TypeRef argument : annotation.arguments()) {
                if (!argument.arguments().isEmpty() || argument.inferArguments() || argument.isStringLiteral()) {
                    throw error(previous(), "@AllowStructural arguments must be parameter names");
                }
                allowed.add(argument.name());
            }
        }
        return java.util.Set.copyOf(allowed);
    }

    private List<Ast.Param> applyStructuralAnnotations(List<Ast.Param> params, List<Ast.Annotation> annotations) {
        java.util.Set<String> allowed = new java.util.HashSet<>(structuralAnnotationNames(annotations));
        if (allowed.isEmpty()) return params;
        java.util.Set<String> found = new java.util.HashSet<>();
        List<Ast.Param> result = new ArrayList<>(params.size());
        for (Ast.Param param : params) {
            boolean structural = param.structural() || allowed.contains(param.name());
            if (structural && param.mutable()) {
                throw error(previous(), "structural parameters are read-only borrowed views and cannot be 'mut'");
            }
            if (allowed.contains(param.name())) found.add(param.name());
            result.add(new Ast.Param(param.type(), param.name(), structural, param.mutable()));
        }
        if (!found.equals(allowed)) {
            java.util.Set<String> missing = new java.util.HashSet<>(allowed);
            missing.removeAll(found);
            throw error(previous(), "@AllowStructural names unknown parameter(s): " + missing);
        }
        return List.copyOf(result);
    }

    private Ast.TypeRef parseTypeRef() {
        Ast.TypeRef first = parseTypeAtom();
        if (!match(PIPE)) return first;

        List<Ast.TypeRef> options = new ArrayList<>();
        options.add(first);
        do options.add(parseTypeAtom()); while (match(PIPE));
        return Ast.TypeRef.union(options);
    }

    private Ast.TypeRef parseTypeAtom() {
        if (match(BANG)) return Ast.TypeRef.complement(parseTypeAtom());
        if (match(TRUE)) return Ast.TypeRef.boolLiteral(true);
        if (match(FALSE)) return Ast.TypeRef.boolLiteral(false);
        if ((check(STRUCT) || (check(IDENT) && peek().lexeme().equals("record")))
                && checkNext(LBRACE)) {
            advance(); // 'struct' and 'record' denote a structural shape in type position.
            return parseTypeAtom(); // the existing '{field: Type}' parser owns the braces
        }
        if (match(TYPE)) {
            Ast.TypeRef marked = parseTypeAtom();
            if (marked.name().startsWith("$")) {
                throw error(previous(), "'type' alias marker must prefix a named type");
            }
            return marked;
        }

        if (matchContextualKeyword("borrow")) {
            boolean mutable = match(MUT);
            return Ast.TypeRef.borrowed(parseTypeAtom(), mutable);
        }
        if (match(AMP)) {
            throw error(previous(),
                    "borrowed types use 'borrow T' or 'borrow mut T'; pointer sigils are not Oreslang source syntax");
        }
        if (match(STRING)) return Ast.TypeRef.stringLiteral(previous().lexeme());

        if (match(LBRACKET)) {
            List<Ast.TypeRef> elements = new ArrayList<>();
            if (!check(RBRACKET)) {
                do elements.add(parseTypeRef()); while (match(COMMA));
            }
            consume(RBRACKET, "expected ']' after finite tuple type");
            return Ast.TypeRef.tupleType(elements);
        }

        if (match(LBRACE)) {
            java.util.LinkedHashMap<String, Ast.TypeRef> members = new java.util.LinkedHashMap<>();
            if (!check(RBRACE)) {
                do {
                    String field = consumeStaticObjectKeyName("expected record type field name");
                    consume(COLON, "expected ':' after record type field name");
                    Ast.TypeRef fieldType = parseTypeRef();
                    if (members.putIfAbsent(field, fieldType) != null) {
                        throw error(previous(), "duplicate record type field '" + field + "'");
                    }
                } while (match(COMMA));
            }
            consume(RBRACE, "expected '}' after record type");
            return Ast.TypeRef.recordType(members);
        }

        if (match(TYPEOF)) {
            if (isLegacyFnSpelling()) {
                throw error(peek(), "function types use 'typeof fnc(...) => ReturnType', not 'typeof fn(...)'");
            }
            consume(FNC, "typeof function types use 'typeof fnc(...) => ReturnType'");
            return parseFunctionTypeSignature();
        }

        if (check(LPAREN) && looksLikeFunctionType()) return parseFunctionTypeSignature();
        if (match(LPAREN)) {
            Ast.TypeRef grouped = parseTypeRef();
            consume(RPAREN, "expected ')' after grouped type");
            return grouped;
        }

        String name;
        if (match(VOID)) name = "void";
        else if (match(SELF)) name = "self";
        else if (match(NULL)) name = "null";
        else name = parseQualifiedName();

        List<Ast.TypeRef> args = new ArrayList<>();
        boolean infer = false;
        if (match(LT)) {
            if (match(GT)) {
                infer = true;
            } else if (name.equals("Fnc") || name.equals("Function")) {
                /*
                 * Callable types support both:
                 *   Fnc<Arg1, Arg2, Result>       (legacy comma form)
                 *   Fnc<Result(Arg1, Arg2)>       (signature-in-generics form)
                 *
                 * The signature form is normalized immediately to the same
                 * TypeRef representation used by "(Args...) => Result".
                 */
                Ast.TypeRef first = parseTypeRef();
                if (match(LPAREN)) {
                    List<Ast.TypeRef> params = parseFunctionTypeParametersAfterOpen();
                    consume(GT, "expected '>' after callable signature type");
                    return Ast.TypeRef.functionType(params, first);
                }

                args.add(first);
                while (match(COMMA)) args.add(parseTypeRef());
                consume(GT, "expected '>' after type arguments");
            } else {
                do {
                    if (check(IDENT) && checkNext(EQUAL)) {
                        String parameterName = advance().lexeme();
                        consume(EQUAL, "expected '=' after named type parameter");
                        args.add(Ast.TypeRef.namedParameter(parameterName, parseMetaValue()));
                    } else {
                        args.add(parseTypeRef());
                    }
                } while (match(COMMA));
                consume(GT, "expected '>' after type arguments / named parameters");
            }
        }

        args = canonicalizeNamedParameters(args);
        while (match(LBRACKET)) {
            args.add(parseSequenceShapeDimension());
            consume(RBRACKET, "expected ']' after sequence type shape");
        }
        return new Ast.TypeRef(name, args, infer);
    }

    private Ast.TypeRef parseMetaValue() {
        if (match(INT)) return Ast.TypeRef.intLiteral(previous().lexeme());
        if (match(STRING)) return Ast.TypeRef.stringLiteral(previous().lexeme());
        if (match(TRUE)) return Ast.TypeRef.boolLiteral(true);
        if (match(FALSE)) return Ast.TypeRef.boolLiteral(false);
        return parseTypeRef();
    }

    private Ast.TypeRef parseSequenceShapeDimension() {
        if (check(RBRACKET)) throw error(peek(), "sequence type shape cannot be empty");
        List<Ast.TypeRef> patterns = new ArrayList<>();
        do patterns.add(parseSequenceTypePattern()); while (match(COMMA));
        return Ast.TypeRef.sequenceShape(patterns);
    }

    private Ast.TypeRef parseSequenceTypePattern() {
        if (match(ELLIPSIS)) {
            if (match(LPAREN)) {
                if (check(RPAREN)) throw error(peek(), "repeating sequence group cannot be empty");
                List<Ast.TypeRef> group = new ArrayList<>();
                do group.add(parseSequenceTypePattern()); while (match(COMMA));
                consume(RPAREN, "expected ')' after repeating sequence group");
                return Ast.TypeRef.repeatMany(group);
            }
            return Ast.TypeRef.repeatMany(List.of(parseSequenceTypePattern()));
        }
        if (check(INT) && checkNext(OF)) {
            String count = advance().lexeme();
            consume(OF, "expected 'of' in counted sequence pattern");
            return Ast.TypeRef.repeatExact(count, parseTypeRef());
        }
        return parseTypeRef();
    }

    private List<Ast.TypeRef> canonicalizeNamedParameters(List<Ast.TypeRef> args) {
        List<Ast.TypeRef> positional = new ArrayList<>();
        java.util.TreeMap<String, Ast.TypeRef> named = new java.util.TreeMap<>();
        List<Ast.TypeRef> shapes = new ArrayList<>();
        for (Ast.TypeRef arg : args) {
            if (arg.isNamedParameter()) {
                String key = arg.namedParameterName();
                if (named.putIfAbsent(key, arg) != null) {
                    throw error(previous(), "duplicate named type parameter '" + key + "'");
                }
            } else if (arg.isSequenceShape()) shapes.add(arg);
            else positional.add(arg);
        }
        positional.addAll(named.values());
        positional.addAll(shapes);
        return positional;
    }

    private Ast.TypeRef parseFunctionTypeSignature() {
        consume(LPAREN, "expected '(' in function type");
        List<Ast.TypeRef> params = parseFunctionTypeParametersAfterOpen();
        consume(FAT_ARROW, "function types use the fat arrow '=>'");
        Ast.TypeRef result = parseTypeRef();
        return Ast.TypeRef.functionType(params, result);
    }

    private List<Ast.TypeRef> parseFunctionTypeParametersAfterOpen() {
        List<Ast.TypeRef> params = new ArrayList<>();
        if (!check(RPAREN)) {
            do {
                Ast.TypeRef paramType = parseTypeRef();
                if (check(IDENT)) advance(); // optional documentation-only parameter name
                params.add(paramType);
            } while (match(COMMA));
        }
        consume(RPAREN, "expected ')' after function type parameters");
        return params;
    }

    private boolean looksLikeFunctionType() {
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) depth++;
            else if (type == RPAREN) {
                depth--;
                if (depth == 0) return i + 1 < tokens.size() && tokens.get(i + 1).type() == FAT_ARROW;
            }
        }
        return false;
    }

    private String parseQualifiedName() {
        StringBuilder name = new StringBuilder(consume(IDENT, "expected name").lexeme());
        while (match(DOT)) name.append('.').append(consume(IDENT, "expected name after '.'").lexeme());
        return name.toString();
    }

    private List<Ast.Stmt> parseBlock() {
        consume(LBRACE, "expected '{'");
        List<Ast.Stmt> body = new ArrayList<>();
        while (!check(RBRACE) && !check(EOF)) body.add(parseStatement());
        consume(RBRACE, "expected '}'");
        return body;
    }

    private Ast.Stmt parseStatement() {
        rejectUnsupportedAcceleratorSyntax();
        // A contextual statement keyword: calls to a function named using(...)
        // retain their existing expression behavior. Scoped borrow syntax is
        // parsed distinctly so compiler admission cannot silently desugar it.
        if (check(IDENT) && peek().lexeme().equals("using")
                && (checkNext(IDENT) || checkNext(SELF))) {
            advance();
            // 'as' delimits the scoped alias here, rather than Oreslang's
            // ordinary checked-cast expression. Restore prior refinement
            // mode even if the resource expression is malformed.
            boolean previousRefinementMode = suppressRefinementOperators;
            Ast.Expr resource;
            try {
                suppressRefinementOperators = true;
                resource = parseExpression();
            } finally {
                suppressRefinementOperators = previousRefinementMode;
            }
            if (!(resource instanceof Ast.CallExpr call)
                    || !(call.callee() instanceof Ast.MemberExpr member)
                    || !member.member().equals("borrow")
                    || !call.arguments().isEmpty() || call.typeArgumentsPresent()) {
                throw error(previous(),
                        "using borrowed scope requires a zero-argument resource.borrow() call");
            }
            consume(AS, "using borrowed scope requires 'as <view>'");
            Token name = consume(IDENT, "using borrowed scope requires a binding name");
            if (name.lexeme().equals("_") || name.lexeme().equals("using")) {
                throw error(name, "using borrowed scope needs a named, usable view binding");
            }
            return new Ast.UsingBorrowStmt(resource, name.lexeme(), parseBlock());
        }
        // Keep these distinct from ordinary calls/identifiers. Exception
        // effect checking, typed recovery and actor CONTROL delivery have
        // not yet been lowered in the Java/Graal production compiler.
        if (check(THROW) || check(RAISE) || check(PANIC) || check(RECOVER)) {
            throw error(peek(), "typed exceptional effect '" + peek().lexeme()
                    + "' requires checked exception and supervision lowering; source effect is not yet enabled");
        }
        // The value-producing static select expression is not lowered yet.
        // Preserve historical statement sources until expression migration
        // provides an executable replacement; do-select stays procedural.
        if (looksLikeStaticSelectStatement()) return parseStaticSelectStatement();
        // "cb" is reserved for callback-bearing writech; it is not the
        // no-result/static-select marker. "do" is the canonical spelling.
        if (check(CB) && checkNext(SELECT)) {
            throw error(peek(), "use 'do select { ... }' instead of 'cb select'");
        }
        if (check(NB) && checkNext(CB)
                && current + 2 < tokens.size()
                && tokens.get(current + 2).type() == SELECT) {
            throw error(peek(), "use 'do nb select { ... }' instead of 'nb cb select'");
        }
        if (check(DO) && (checkNext(SELECT)
                || (checkNext(NB) && current + 2 < tokens.size()
                    && tokens.get(current + 2).type() == SELECT))) {
            throw error(peek(), "'do select' requires braced static cases; "
                    + "dynamic 'select from cases' produces a result and "
                    + "has no callback arms to handle discarded reads");
        }
        if (looksLikeImmediateChannelExpression()) {
            Ast.Expr expression = parseExpression();
            consumeStatementTerminator("channel probe expression should end with ';'");
            return new Ast.ExprStmt(expression);
        }
        // Runtime blocks may create values, never declaration identities. This
        // keeps source semantics compatible with both closed-world AOT and JIT:
        // JIT may specialize known declarations, but it cannot invent modules,
        // namespaces, classes, or trait/impl identities while executing code.
        if (check(NAMESPACE)) {
            throw error(peek(), "namespace declarations are file-scope compile-time declarations and cannot execute inside a block");
        }
        if (check(DEFINE)) {
            Token define = peek();
            Token.Type next = current + 1 < tokens.size() ? tokens.get(current + 1).type() : EOF;
            if (next == MODULE) {
                throw error(define, "module declarations are compile-time declarations and cannot execute inside a block");
            }
            if (next == CLASS) {
                throw error(define, "class declarations are compile-time declarations and cannot execute inside a block");
            }
            if (next == TRAIT) {
                throw error(define, "trait declarations are compile-time declarations and cannot execute inside a block");
            }
        }
        if (check(TRAIT)) {
            throw error(peek(), "trait declarations are static/module-scope declarations; runtime trait creation is forbidden");
        }

        if (check(AT)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            if (!isBindingKind(peek().type())) {
                throw error(peek(), "local annotations currently apply only to typed bindings");
            }
            return parseBindingStatement(annotations);
        }
        if (isBindingKind(peek().type()) && looksLikePrefixedDestructure()) {
            Ast.BindingKind inherited = parseBindingKind();
            boolean inheritedMutableReferent = parseReferentMutability(inherited);
            return parseDestructure(
                    (check(LBRACKET) || check(LPAREN)) ? Ast.DestructureKind.SEQUENCE : Ast.DestructureKind.OBJECT,
                    inherited,
                    inheritedMutableReferent);
        }
        if (isBindingKind(peek().type())) return parseBindingStatement();
        if (check(LBRACKET) && looksLikeDestructure()) {
            return parseDestructure(Ast.DestructureKind.SEQUENCE, null, false);
        }
        if (check(LBRACE) && looksLikeDestructure()) {
            return parseDestructure(Ast.DestructureKind.OBJECT, null, false);
        }
        if (match(RETURN)) {
            Ast.Expr value = check(SEMICOLON) || isSafeStatementBoundary() ? null : parseExpression();
            consumeExpressionStatementTerminator(value, "return statement should end with ';'");
            return new Ast.ReturnStmt(value);
        }
        if (match(YIELD)) {
            boolean delegated = match(STAR);
            if (check(SEMICOLON) || isSafeStatementBoundary()) {
                throw error(previous(), delegated ? "yield* requires an iterable value" : "yield requires a value");
            }
            Ast.Expr value = parseExpression();
            consumeExpressionStatementTerminator(
                    value,
                    delegated
                            ? "yield* statement should end with ';'"
                            : "yield statement should end with ';'");
            return new Ast.YieldStmt(value, delegated);
        }
        if (match(DEFER)) {
            Ast.Expr expression = parseExpression();
            consumeExpressionStatementTerminator(expression, "defer statement should end with ';'");
            return new Ast.DeferStmt(expression);
        }
        if (check(BLOCK) && checkNext(LBRACE)) {
            advance();
            return new Ast.BlockStmt(parseBlock());
        }
        if (match(BREAK)) {
            consumeStatementTerminator("break statement should end with ';'");
            return new Ast.BreakStmt();
        }
        if (match(CONTINUE)) {
            consumeStatementTerminator("continue statement should end with ';'");
            return new Ast.ContinueStmt();
        }
        if (match(WHILE)) {
            // Route the while backedge through the existing source-task
            // ForStmt trampoline: one continuation and one budget mechanism.
            ConditionHead head = parseConditionHead(true);
            match(SEMICOLON);
            List<Ast.Stmt> body = parseLoopBody();
            if (head.binding() == null) {
                return new Ast.ForStmt(null, head.predicate(), null, body);
            }
            // Re-enter the binding scope on every test, including after continue.
            List<Ast.Stmt> turn = new ArrayList<>();
            turn.add(head.binding());
            turn.add(new Ast.IfStmt(List.of(new Ast.IfBranch(
                    new Ast.UnaryExpr("!", head.predicate()),
                    List.of(new Ast.BreakStmt()))), List.of()));
            turn.addAll(body);
            return new Ast.ForStmt(null, new Ast.LiteralExpr(true), null, turn);
        }
        if (match(IF)) return parseIf();
        if ((check(NB)
                || (check(IDENT) && peek().lexeme().equals("task")))
                && current + 2 < tokens.size()
                && tokens.get(current + 1).type() == IDENT
                && tokens.get(current + 1).lexeme().equals("scope")
                && tokens.get(current + 2).type() == LBRACE) {
            advance(); // nb or contextual task
            advance(); // contextual scope
            Ast.Stmt scoped = new Ast.TaskScopeStmt(parseBlock());
            match(SEMICOLON);
            return scoped;
        }
        if (check(DO) && checkNext(LBRACE)) {
            advance();
            return parseDoWhile();
        }
        // Procedural dispatch returns from its enclosing callable. Keep this
        // distinction in the AST instead of relying on source spelling later.
        if (match(DO)) {
            if (match(MATCH)) return parseMatch(true);
            if (match(SWITCH)) return parseSwitch();
            throw error(previous(), "'do' requires match, switch, or a braced select");
        }
        // Legacy statement syntax remains accepted during migration.
        if (match(MATCH)) return parseMatch(false);
        if (match(SWITCH)) return parseSwitch();
        if (match(TRY)) return parseTry();
        if (check(LOOP) && (checkNext(LBRACE) || checkNext(DO))) {
            advance();
            return new Ast.LoopStmt(parseLoopBody());
        }
        if (match(FOR)) {
            if (check(LBRACE) || check(DO)) {
                return new Ast.LoopStmt(parseLoopBody());
            }
            boolean asyncIteration = match(AWAIT);
            return parseFor(asyncIteration);
        }

        Ast.Expr expression = parseExpression();
        consumeExpressionStatementTerminator(expression, "expression statement should end with ';'");
        return new Ast.ExprStmt(expression);
    }

    private boolean looksLikeImmediateChannelExpression() {
        if (!check(TRY) || current + 1 >= tokens.size()) return false;
        Token.Type next = tokens.get(current + 1).type();
        return next == READCH || next == WRITECH || next == SELECT;
    }

    private boolean looksLikeStaticSelectStatement() {
        int i = current;
        if (i >= tokens.size()) return false;
        if (tokens.get(i).type() == DO) i++;
        if (i < tokens.size()
                && (tokens.get(i).type() == NB || tokens.get(i).type() == TRY)) i++;
        if (i >= tokens.size() || tokens.get(i).type() != SELECT) return false;
        i++;

        if (i < tokens.size()
                && (tokens.get(i).type() == FIRST
                        || (tokens.get(i).type() == IDENT
                                && tokens.get(i).lexeme().equals("first")))) {
            i++;
        } else if (i < tokens.size()
                && tokens.get(i).type() == IDENT
                && (tokens.get(i).lexeme().equals("first")
                        || tokens.get(i).lexeme().equals("fair")
                        || tokens.get(i).lexeme().equals("random"))) {
            i++;
        }
        return i < tokens.size() && tokens.get(i).type() == LBRACE;
    }

    /** A select arm participates in one atomic selection; only the enclosing select sets its wait mode. */
    private void rejectPerArmChannelWaitModifier() {
        if ((check(NB) || check(TRY))
                && (checkNext(READCH) || checkNext(WRITECH))) {
            Token modifier = peek();
            Token operation = tokens.get(current + 1);
            throw error(modifier,
                    "select arm cannot use '" + modifier.lexeme() + " "
                            + operation.lexeme()
                            + "'; put the wait mode on the enclosing select "
                            + "('select', 'nb select', or 'try select') and use "
                            + "plain 'readch' or 'writech' in each arm");
        }
    }

    private Ast.SelectStmt parseStaticSelectStatement() {
        boolean explicitDo = match(DO);
        Ast.WaitMode mode = Ast.WaitMode.BLOCKING;
        if (match(NB)) mode = Ast.WaitMode.NONBLOCKING;
        else if (match(TRY)) {
            if (explicitDo) {
                throw error(previous(),
                        "'do try select' is not supported; use 'try select'");
            }
            mode = Ast.WaitMode.IMMEDIATE;
        }

        consume(SELECT, "expected 'select'");
        Ast.SelectPolicy policy = parseSelectPolicy();
        consume(LBRACE, "static select requires '{ ... }'; use 'select from cases' for dynamic select");

        List<Ast.SelectArm> arms = new ArrayList<>();
        while (!check(RBRACE) && !check(EOF)) {
            if (matchContextualKeyword("when", "case")) {
                rejectPerArmChannelWaitModifier();
                Ast.ChannelOperation operation;
                Ast.Expr channel;
                Ast.Expr value = null;
                Ast.BindingKind bindingKind = null;
                String bindingName = null;
                Ast.TypeRef bindingType = null;
                boolean mutableReferent = false;

                if (match(READCH) || match(AWAIT)) {
                    Token.Type op = previous().type();
                    operation = op == READCH ? Ast.ChannelOperation.READ : Ast.ChannelOperation.AWAIT;
                    channel = parseExpression();
                    consume(COLON, (op == READCH ? "readch" : "await") + " select arm requires ':'");
                    SelectReadBinding binding = parseSelectReadBinding();
                    bindingKind = binding.kind();
                    bindingName = binding.name();
                    bindingType = binding.type();
                    mutableReferent = binding.mutableReferent();
                } else if (match(WRITECH)) {
                    operation = Ast.ChannelOperation.WRITE;
                    channel = parseExpression();
                    consume(COMMA, "writech select arm requires 'channel, value'");
                    value = parseExpression();
                    consume(COLON, "writech select arm requires ':'");
                    // Typed write arms have no value binding. Arrow is optional
                    // compatibility syntax and cannot alter send admission.
                    match(ARROW);
                } else {
                    // Legacy binding-first: when const msg = readch(input) -> { ... }
                    if (match(MUT)) {
                        bindingKind = Ast.BindingKind.LET;
                        mutableReferent = true;
                    } else if (isBindingKind(peek().type())) {
                        bindingKind = parseBindingKind();
                        mutableReferent = parseReferentMutability(bindingKind);
                    } else {
                        bindingKind = Ast.BindingKind.CONST;
                    }
                    bindingName = consume(IDENT, "select read case requires binding name").lexeme();
                    consume(EQUAL, "select case binding requires '='");
                    rejectPerArmChannelWaitModifier();
                    consume(READCH, "select case binding requires readch");
                    consume(LPAREN, "binding-first select readch requires '('");
                    channel = parseExpression();
                    consume(RPAREN, "binding-first select readch requires ')'");
                    consume(ARROW, "binding-first select case requires '->'");
                    operation = Ast.ChannelOperation.READ;
                }

                // Accept the canonical '->' arm boundary while preserving
                // legacy arrowless select arms through the formatter migration.
                // Fat arrows remain reserved for type-level callable signatures.
                if (check(FAT_ARROW)) {
                    throw error(peek(),
                            "select executable arms use the slim arrow '->'; '=>' is not an arm delimiter");
                }
                match(ARROW);
                if (!check(LBRACE)) {
                    throw error(peek(), "select arm requires a braced body '{ ... }', optionally introduced by '->'");
                }
                List<Ast.Stmt> body = parseBlock();
                arms.add(new Ast.SelectArm(
                        operation,
                        channel,
                        value,
                        bindingKind,
                        bindingName,
                        body,
                        mutableReferent,
                        bindingType));
                continue;
            }

            if (match(DEFAULT)) {
                consume(COLON, "select default arm requires ':'");
                if (check(FAT_ARROW)) {
                    throw error(peek(),
                            "select default arms use the slim arrow '->'; '=>' is not an arm delimiter");
                }
                match(ARROW);
                if (!check(LBRACE)) {
                    throw error(peek(), "select default arm requires a braced body '{ ... }'");
                }
                List<Ast.Stmt> body = parseBlock();
                arms.add(new Ast.SelectArm(
                        Ast.ChannelOperation.DEFAULT,
                        null,
                        null,
                        null,
                        null,
                        body));
                continue;
            }

            throw error(peek(), "expected when/case/default inside select");
        }

        consume(RBRACE, "expected '}' after select");
        return new Ast.SelectStmt(mode, policy, arms, explicitDo);
    }

    private record SelectReadBinding(
            Ast.BindingKind kind, String name, Ast.TypeRef type, boolean mutableReferent) { }

    /**
     * Typed arrow bindings deliberately require a result type; the legacy
     * untyped `const name { ... }` form remains source compatible.
     * This parser never speculatively consumes an event while checking types.
     */
    private SelectReadBinding parseSelectReadBinding() {
        if (check(LBRACE) || match(ARROW)) {
            // A typed operation can return void; it has no value to bind.
            return new SelectReadBinding(null, null, null, false);
        }
        Ast.BindingKind kind = Ast.BindingKind.CONST;
        boolean mutable = false;
        if (isBindingKind(peek().type())) {
            kind = parseBindingKind();
            mutable = parseReferentMutability(kind);
            if (check(IDENT) && (checkNext(LBRACE) || checkNext(ARROW) || checkNext(FAT_ARROW))) {
                String name = advance().lexeme();
                return new SelectReadBinding(kind, name, null, mutable);
            }
        }
        Ast.TypeRef type = parseTypeRef();
        String name = consume(IDENT, "typed select arm requires a binding name").lexeme();
        if (!match(ARROW)) {
            if (!check(LBRACE)) {
                throw error(peek(), "select arm requires a braced body '{ ... }'");
            }
            throw error(peek(), "typed select arm requires '->' before its body");
        }
        return new SelectReadBinding(kind, name, type, mutable);
    }

    private Ast.SelectPolicy parseSelectPolicy() {
        if (match(FIRST)) return Ast.SelectPolicy.PRIORITY;
        if (check(IDENT) && peek().lexeme().equals("first")) {
            advance();
            return Ast.SelectPolicy.PRIORITY;
        }
        if (check(IDENT) && peek().lexeme().equals("fair")) {
            advance();
            return Ast.SelectPolicy.FAIR;
        }
        if (check(IDENT) && peek().lexeme().equals("random")) {
            advance();
            return Ast.SelectPolicy.RANDOM;
        }
        return Ast.SelectPolicy.FAIR;
    }

    private Ast.Stmt parseDoWhile() {
        List<Ast.Stmt> body = parseBlock();
        consume(WHILE, "expected 'while' after do-while body");
        consume(LPAREN, "post-test do-while requires 'while (<condition>);'");
        if (check(RPAREN)) {
            throw error(peek(), "do-while requires a condition; use 'loop { ... }' for an unconditional loop");
        }
        Ast.Expr condition = parseExpression();
        consume(RPAREN, "expected ')' after do-while condition");
        consume(SEMICOLON, "do-while must end with ';'");
        return new Ast.DoWhileStmt(body, condition);
    }

    private Ast.Stmt parseFor(boolean asyncIteration) {
        if (!match(LPAREN)) {
            if (looksLikeUnparenthesizedForOf()) {
                if (!isBindingKind(peek().type())) {
                    throw forOfUndeclaredBindingError();
                }
                Ast.BindingKind kind = parseForOfBindingKind();
                boolean mutableReferent = parseReferentMutability(kind);
                if (looksLikeTypedForOfBinding()) {
                    throw typedForOfBindingError();
                }
                if (looksLikeForOfDestructurePattern()) {
                    List<Ast.DestructureBinding> bindings =
                            parseForSequenceBindings(kind, mutableReferent);
                    consume(OF, "iterator destructuring uses 'for [a, b] of iterable'");
                    Ast.Expr iterable = parseExpression();
                    return new Ast.ForOfDestructureStmt(bindings, iterable, asyncIteration, parseLoopBody());
                }
                String name = consume(IDENT, "iterator for-loop requires a binding name").lexeme();
                consume(OF, "iterator for-loop shorthand uses 'for x of iterable'");
                Ast.Expr iterable = parseExpression();
                return new Ast.ForOfStmt(
                        kind, name, iterable, asyncIteration, parseLoopBody(), mutableReferent);
            }

            if (asyncIteration) {
                throw error(peek(), "'for await' requires iterator syntax: for await x of asyncIterable");
            }
            if (looksLikeTypedForOfBinding()) {
                throw typedForOfUndeclaredBindingError();
            }

            Ast.Stmt initializer = parseUnparenthesizedForInitializer();
            consume(SEMICOLON, "expected ';' after for initializer");
            Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
            consume(SEMICOLON, "expected ';' after for condition");
            Ast.Expr update = check(LBRACE) || check(DO) ? null : parseForUpdate();
            return new Ast.ForStmt(initializer, condition, update, parseLoopBody());
        }

        if (isBindingKind(peek().type())) {
            Ast.BindingKind kind = parseBindingKind();
            boolean mutableReferent = parseReferentMutability(kind);
            if (looksLikeTypedForOfBinding()) {
                requireForOfBindingKind(kind);
                throw typedForOfBindingError();
            }
            if (check(LBRACKET)) {
                requireForOfBindingKind(kind);
                List<Ast.DestructureBinding> bindings =
                        parseForSequenceBindings(kind, mutableReferent);
                consume(OF, "expected 'of' after for-of destructure pattern");
                Ast.Expr iterable = parseExpression();
                consume(RPAREN, "expected ')' after for-of header");
                return new Ast.ForOfDestructureStmt(bindings, iterable, asyncIteration, parseLoopBody());
            }
            if (check(IDENT) && checkNext(OF)) {
                requireForOfBindingKind(kind);
                String name = advance().lexeme();
                consume(OF, "expected 'of' in for-of loop");
                Ast.Expr iterable = parseExpression();
                consume(RPAREN, "expected ')' after for-of header");
                return new Ast.ForOfStmt(
                        kind, name, iterable, asyncIteration, parseLoopBody(), mutableReferent);
            }

            if (asyncIteration) {
                throw error(peek(), "'for await' requires iterator syntax: for await (x of asyncIterable)");
            }
            Ast.TypeRef type = null;
            String name;
            if (check(IDENT) && checkNext(EQUAL)) name = advance().lexeme();
            else {
                type = parseTypeRef();
                name = consume(IDENT, "expected loop initializer binding name").lexeme();
            }
            consume(EQUAL, "for initializer binding requires '='");
            Ast.Expr initializer = parseExpression();
            Ast.BindingStmt init = new Ast.BindingStmt(
                    kind, type, name, initializer, mutableReferent);
            consume(SEMICOLON, "expected ';' after for initializer");
            Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
            consume(SEMICOLON, "expected ';' after for condition");
            Ast.Expr update = check(RPAREN) ? null : parseForUpdate();
            consume(RPAREN, "expected ')' after for header");
            return new Ast.ForStmt(init, condition, update, parseLoopBody());
        }

        if (looksLikeTypedForOfBinding()) {
            throw typedForOfUndeclaredBindingError();
        }

        if (asyncIteration) {
            if (looksLikeForOfDestructurePattern() || (check(IDENT) && checkNext(OF))) {
                throw forOfUndeclaredBindingError();
            }
            throw error(peek(), "'for await' requires iterator syntax: for await (const x of asyncIterable)");
        }

        if (looksLikeTypedForInitializer()) {
            Ast.TypeRef type = parseTypeRef();
            String name = consume(IDENT, "expected typed loop binding name").lexeme();
            consume(EQUAL, "typed for initializer requires '='");
            Ast.BindingStmt init = new Ast.BindingStmt(
                    Ast.BindingKind.LET,
                    type,
                    name,
                    parseExpression());
            consume(SEMICOLON, "expected ';' after for initializer");
            Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
            consume(SEMICOLON, "expected ';' after for condition");
            Ast.Expr update = check(RPAREN) ? null : parseForUpdate();
            consume(RPAREN, "expected ')' after for header");
            return new Ast.ForStmt(init, condition, update, parseLoopBody());
        }

        if (looksLikeForOfDestructurePattern()) {
            throw forOfUndeclaredBindingError();
        }

        if (check(IDENT) && checkNext(OF)) {
            throw forOfUndeclaredBindingError();
        }

        Ast.Stmt initializer = null;
        if (!check(SEMICOLON)) initializer = new Ast.ExprStmt(parseExpression());
        consume(SEMICOLON, "expected ';' after for initializer");
        Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
        consume(SEMICOLON, "expected ';' after for condition");
        Ast.Expr update = check(RPAREN) ? null : parseForUpdate();
        consume(RPAREN, "expected ')' after for header");
        return new Ast.ForStmt(initializer, condition, update, parseLoopBody());
    }

    private boolean looksLikeUnparenthesizedForOf() {
        int mark = current;
        try {
            if (isBindingKind(peek().type())) {
                parseBindingKind();
                match(MUT);
                if (looksLikeTypedForOfBinding()) return true;
            }
            if (looksLikeForOfDestructurePattern()) return true;
            return check(IDENT) && checkNext(OF);
        } finally {
            current = mark;
        }
    }

    private boolean looksLikeTypedForOfBinding() {
        int mark = current;
        try {
            parseTypeRef();
            return check(IDENT) && checkNext(OF);
        } catch (IllegalArgumentException ignored) {
            return false;
        } finally {
            current = mark;
        }
    }

    private IllegalArgumentException typedForOfBindingError() {
        return error(peek(),
                "for-of element types are inferred; use 'for const/val/let name of iterable' "
                        + "(optionally 'let mut') and constrain/cast the iterable instead");
    }

    private IllegalArgumentException typedForOfUndeclaredBindingError() {
        int mark = current;
        Token name;
        try {
            parseTypeRef();
            name = consume(IDENT, "expected iterator binding name after type");
        } finally {
            current = mark;
        }
        return error(name,
                "variable used before declared '" + name.lexeme()
                        + "'; for-of bindings require an explicit const/val/let declaration");
    }

    private boolean looksLikeForOfDestructurePattern() {
        if (!check(LBRACKET)) return false;
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LBRACKET) depth++;
            else if (type == RBRACKET) {
                depth--;
                if (depth == 0) {
                    return i + 1 < tokens.size() && tokens.get(i + 1).type() == OF;
                }
            }
        }
        return false;
    }

    private Ast.Stmt parseUnparenthesizedForInitializer() {
        if (check(SEMICOLON)) return null;

        if (isBindingKind(peek().type())) {
            Ast.BindingKind kind = parseBindingKind();
            boolean mutableReferent = parseReferentMutability(kind);
            Ast.TypeRef type = null;
            String name;
            if (check(IDENT) && checkNext(EQUAL)) {
                name = advance().lexeme();
            } else {
                type = parseTypeRef();
                name = consume(IDENT, "expected loop initializer binding name").lexeme();
            }
            consume(EQUAL, "for initializer binding requires '='");
            return new Ast.BindingStmt(
                    kind, type, name, parseExpression(), mutableReferent);
        }

        if (looksLikeTypedForInitializer()) {
            Ast.TypeRef type = parseTypeRef();
            String name = consume(IDENT, "expected typed loop binding name").lexeme();
            consume(EQUAL, "typed for initializer requires '='");
            return new Ast.BindingStmt(Ast.BindingKind.LET, type, name, parseExpression());
        }

        return new Ast.ExprStmt(parseExpression());
    }

    private boolean looksLikeTypedForInitializer() {
        int mark = current;
        try {
            parseTypeRef();
            return check(IDENT) && checkNext(EQUAL);
        } catch (IllegalArgumentException ignored) {
            return false;
        } finally {
            current = mark;
        }
    }

    private Ast.Expr parseForUpdate() {
        int mark = current;
        if (check(IDENT)) {
            String name = advance().lexeme();
            if (matchAdjacentPair(PLUS)) {
                Ast.NameExpr target = new Ast.NameExpr(name);
                return new Ast.AssignExpr(
                        target,
                        new Ast.BinaryExpr("+", new Ast.NameExpr(name), new Ast.LiteralExpr(1L)));
            }
            if (matchAdjacentPair(MINUS)) {
                Ast.NameExpr target = new Ast.NameExpr(name);
                return new Ast.AssignExpr(
                        target,
                        new Ast.BinaryExpr("-", new Ast.NameExpr(name), new Ast.LiteralExpr(1L)));
            }
            current = mark;
        }
        return parseExpression();
    }

    private List<Ast.DestructureBinding> parseForSequenceBindings(
            Ast.BindingKind inheritedKind,
            boolean inheritedMutableReferent) {
        consume(LBRACKET, "expected '[' to start for-of destructure pattern");
        if (check(RBRACKET)) throw error(peek(), "for-of destructure pattern cannot be empty");

        if (inheritedKind == null) {
            throw forOfUndeclaredBindingError();
        }

        List<Ast.DestructureBinding> bindings = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        Ast.BindingKind currentKind = inheritedKind;
        boolean currentMutableReferent = inheritedMutableReferent;
        do {
            if (isBindingKind(peek().type())) {
                currentKind = parseForOfBindingKind();
                currentMutableReferent = parseReferentMutability(currentKind);
            }
            boolean rest = match(ELLIPSIS);
            if (isDiscardToken(peek())) {
                if (rest) throw error(peek(), "rest destructure binding cannot be a discard");
                advance();
                bindings.add(Ast.DestructureBinding.discard());
            } else {
                String name = consume(IDENT, "expected binding name in for-of destructure pattern").lexeme();
                if (!names.add(name)) {
                    throw error(previous(), "duplicate binding '" + name + "' in for-of destructure pattern");
                }
                bindings.add(new Ast.DestructureBinding(
                        currentKind, name, rest, currentMutableReferent));
            }
            if (rest && check(COMMA)) {
                throw error(peek(), "rest destructure binding must be the final binding");
            }
        } while (match(COMMA));

        consume(RBRACKET, "expected ']' after for-of destructure pattern");
        return List.copyOf(bindings);
    }

    private List<Ast.Stmt> parseLoopBody() {
        if (check(LBRACE)) return parseBlock();
        if (!match(DO)) {
            throw error(peek(), "loop body must use '{ ... }' or 'do ... done'");
        }

        List<Ast.Stmt> body = new ArrayList<>();
        while (!check(EOF) && !isBareDoneDelimiter()) {
            body.add(parseStatement());
        }
        consume(DONE, "expected 'done' to close loop body");
        return body;
    }

    private boolean isBareDoneDelimiter() {
        return check(DONE) && !reservedCallableNameFollowedByInvocation(current);
    }

    private Ast.BindingStmt parseBindingStatement() {
        return parseBindingStatement(List.of());
    }

    private Ast.BindingStmt parseBindingStatement(List<Ast.Annotation> annotations) {
        Ast.BindingKind kind = parseBindingKind();
        boolean mutableReferent = parseReferentMutability(kind);
        Ast.TypeRef type = null;
        String name;
        boolean functionShorthand = match(FNC);
        if (functionShorthand && (kind != Ast.BindingKind.CONST || mutableReferent)) {
            throw error(previous(), "lambda binding shorthand requires 'const fnc'");
        }
        if (functionShorthand) {
            name = consume(IDENT, "expected function binding name").lexeme();
        } else if (check(IDENT) && checkNext(COLON)) {
            name = advance().lexeme();
            consume(COLON, "expected ':' after binding name");
            type = parseTypeRef();
        } else if (check(IDENT) && checkNext(EQUAL)) {
            name = advance().lexeme();
        } else {
            type = parseTypeRef();
            name = consume(IDENT, "expected binding name").lexeme();
        }
        if (!annotations.isEmpty()) {
            if (type == null) {
                throw error(previous(), "type metadata annotations require an explicit declared type");
            }
            type = applyTypeMetadataAnnotations(type, annotations, true);
        }
        consume(EQUAL, "binding requires initializer");
        String inferredTypeName = null;
        Ast.Expr initializer;
        if (isContextualIdentifier("infer") && checkNext(LT)) {
            advance(); // contextual 'infer'
            consume(LT, "expected '<' after infer");
            inferredTypeName = consume(IDENT, "expected inferred type name in infer<T>").lexeme();
            consume(GT, "expected '>' after inferred type name");
            if (kind != Ast.BindingKind.CONST || mutableReferent) {
                throw error(previous(), "infer<T> requires an immutable const binding");
            }
            if (type == null || !type.name().equals(inferredTypeName)
                    || !type.arguments().isEmpty() || type.inferArguments()) {
                throw error(previous(), "binding type must match infer<T> exactly");
            }
            if (match(STRUCT)) {
                initializer = parseObjectLiteral(Ast.ObjectKind.INFERRED_STRUCT, List.of());
            } else if (matchContextualIdentifier("record")) {
                // Explicit alias capture remains available for structural records.
                initializer = parseObjectLiteral(Ast.ObjectKind.INFERRED_RECORD, List.of());
            } else if (matchContextualKeyword("tuple")) {
                consume(LPAREN, "expected '(' after infer<T> tuple");
                List<Ast.Expr> items = parseArgumentsUntil(RPAREN);
                consume(RPAREN, "expected ')' after inferred tuple");
                if (items.isEmpty()) {
                    throw error(previous(), "infer<T> tuple requires at least one element");
                }
                initializer = new Ast.TupleExpr(items);
            } else {
                throw error(peek(), "infer<T> requires a struct {...}, record {...}, or tuple (...) literal");
            }
        } else {
            initializer = parseExpression();
        }
        if (functionShorthand && !(initializer instanceof Ast.LambdaExpr)) {
            throw error(previous(), "'const fnc' requires a lambda initializer");
        }
        consumeExpressionStatementTerminator(initializer, "binding should end with ';'");
        return new Ast.BindingStmt(kind, type, name, initializer, mutableReferent, inferredTypeName);
    }

    private Ast.TypeRef applyTypeMetadataAnnotations(
            Ast.TypeRef type,
            List<Ast.Annotation> annotations,
            boolean rejectUnknown) {
        java.util.LinkedHashMap<String, Ast.TypeRef> additions = new java.util.LinkedHashMap<>();
        for (Ast.Annotation annotation : annotations) {
            String annotationName = annotation.name();
            if (annotationName.equals("NamedParams")) {
                if (annotation.arguments().isEmpty()) {
                    throw error(previous(), "@NamedParams requires at least one name=value entry");
                }
                for (Ast.TypeRef argument : annotation.arguments()) {
                    if (!argument.isNamedParameter()) {
                        throw error(previous(), "@NamedParams arguments must use name=value syntax");
                    }
                    putMetadataAnnotation(additions, argument.namedParameterName(), argument.namedParameterValue());
                }
                continue;
            }

            String parameterName = switch (annotationName) {
                case "GrowthPolicy" -> "growth_policy";
                case "Allocator" -> "allocator";
                case "Align" -> "align";
                case "InlineCapacity" -> "inline_capacity";
                case "MaxCapacity" -> "max_capacity";
                case "Storage" -> "storage";
                case "Rank" -> "rank";
                default -> null;
            };
            if (parameterName == null) {
                if (rejectUnknown) {
                    throw error(previous(), "unsupported local annotation @" + annotationName
                            + "; local annotations must map to named type parameters");
                }
                continue;
            }
            if (annotation.arguments().size() != 1 || annotation.arguments().getFirst().isNamedParameter()) {
                throw error(previous(), "@" + annotationName + " requires exactly one positional compile-time value");
            }
            putMetadataAnnotation(additions, parameterName, annotation.arguments().getFirst());
        }
        if (additions.isEmpty()) return type;

        List<Ast.TypeRef> positional = new ArrayList<>();
        java.util.TreeMap<String, Ast.TypeRef> named = new java.util.TreeMap<>();
        List<Ast.TypeRef> shapes = new ArrayList<>();
        for (Ast.TypeRef argument : type.arguments()) {
            if (argument.isNamedParameter()) {
                String key = argument.namedParameterName();
                named.put(key, argument);
            } else if (argument.isSequenceShape()) shapes.add(argument);
            else positional.add(argument);
        }
        for (java.util.Map.Entry<String, Ast.TypeRef> entry : additions.entrySet()) {
            if (named.containsKey(entry.getKey())) {
                throw error(previous(), "named type parameter '" + entry.getKey()
                        + "' is specified both inline and by annotation");
            }
            named.put(entry.getKey(), Ast.TypeRef.namedParameter(entry.getKey(), entry.getValue()));
        }
        positional.addAll(named.values());
        positional.addAll(shapes);
        return new Ast.TypeRef(type.name(), positional, type.inferArguments());
    }

    private void putMetadataAnnotation(
            java.util.Map<String, Ast.TypeRef> values,
            String name,
            Ast.TypeRef value) {
        if (values.putIfAbsent(name, value) != null) {
            throw error(previous(), "duplicate named type parameter '" + name + "' in annotations");
        }
    }

    private Ast.DestructureStmt parseDestructure(
            Ast.DestructureKind kind,
            Ast.BindingKind inheritedKind,
            boolean inheritedMutableReferent) {
        Token.Type close;
        if (kind == Ast.DestructureKind.SEQUENCE) {
            if (match(LPAREN)) close = RPAREN;
            else {
                consume(LBRACKET, "expected '['");
                close = RBRACKET;
            }
        } else {
            consume(LBRACE, "expected '{'");
            close = RBRACE;
        }

        if (check(close)) throw error(peek(), "destructure pattern cannot be empty");

        List<Ast.DestructureBinding> bindings = new ArrayList<>();
        Ast.BindingKind currentKind = inheritedKind;
        boolean currentMutableReferent = inheritedMutableReferent;
        do {
            if (isBindingKind(peek().type())) {
                currentKind = parseBindingKind();
                currentMutableReferent = parseReferentMutability(currentKind);
            }
            boolean rest = match(ELLIPSIS);

            if (isDiscardToken(peek())) {
                if (rest) throw error(peek(), "rest destructure binding cannot be a discard");
                if (kind == Ast.DestructureKind.OBJECT) {
                    throw error(peek(), "bare '_' discard is only valid in sequence destructuring");
                }
                advance();
                bindings.add(Ast.DestructureBinding.discard());
                continue;
            }

            if (currentKind == null) {
                throw error(peek(), "destructure binding kind must be declared before the first binding");
            }
            String name = consume(IDENT, "expected binding name in destructure").lexeme();
            bindings.add(new Ast.DestructureBinding(
                    currentKind, name, rest, currentMutableReferent));
            if (rest && check(COMMA)) {
                throw error(peek(), "rest destructure binding must be the final binding");
            }
        } while (match(COMMA));

        consume(close, kind == Ast.DestructureKind.SEQUENCE ? "expected ']'" : "expected '}'");
        consume(EQUAL, "expected '=' after destructure pattern");
        Ast.Expr initializer = parseExpression();
        consumeExpressionStatementTerminator(initializer, "destructure should end with ';'");
        return new Ast.DestructureStmt(kind, bindings, initializer);
    }

    private boolean looksLikeDestructure() {
        if (current + 1 >= tokens.size()) return false;
        Token first = tokens.get(current + 1);
        return isBindingKind(first.type()) || isDiscardToken(first);
    }

    private boolean looksLikePrefixedDestructure() {
        int openIndex = current + 1;
        if (openIndex < tokens.size() && tokens.get(openIndex).type() == MUT) openIndex++;
        if (openIndex + 1 >= tokens.size()) return false;
        Token.Type open = tokens.get(openIndex).type();
        Token.Type close;
        if (open == LBRACKET) close = RBRACKET;
        else if (open == LBRACE) close = RBRACE;
        else if (open == LPAREN) close = RPAREN;
        else return false;

        int depth = 0;
        for (int i = openIndex; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == open) depth++;
            else if (type == close) {
                depth--;
                if (depth == 0) {
                    return i + 1 < tokens.size() && tokens.get(i + 1).type() == EQUAL;
                }
            }
        }
        return false;
    }

    private boolean isDiscardToken(Token token) {
        return token.type() == IDENT && token.lexeme().equals("_");
    }

    private Ast.Stmt parseIf() {
        List<Ast.IfBranch> branches = new ArrayList<>();
        List<Ast.BindingStmt> bindings = new ArrayList<>();
        ConditionHead head = parseConditionHead(false);
        bindings.add(head.binding());
        Ast.Expr condition = head.predicate();

        // Brace form still obeys the universal Oreslang invariant: every if closes with fi.
        // Braces delimit branch bodies; they never replace the structural terminator.
        if (check(LBRACE)) {
            branches.add(new Ast.IfBranch(condition, parseBlock()));
            while (matchConditionalBranch()) {
                head = parseConditionHead(false);
                bindings.add(head.binding());
                condition = head.predicate();
                branches.add(new Ast.IfBranch(condition, parseBlock()));
            }

            List<Ast.Stmt> elseBody = List.of();
            if (match(ELSE)) elseBody = parseBlock();
            consume(FI, "expected 'fi' to close if");
            return lowerConditionBindings(branches, bindings, elseBody);
        }

        // Canonical keyword-delimited form:
        //   if condition; then
        //   elif condition; then
        //   else
        //   fi
        //
        // Compatibility forms remain accepted while source migrates:
        // - 'if ... do' / 'elseif ... do' are deprecated spellings.
        // - 'elseif' aliases canonical 'elif'.
        // - 'else if ... then' is accepted as a single-fi branch alias.
        match(SEMICOLON);
        consumeConditionalThen("if");
        List<Ast.Stmt> body = parseUntil(ELSEIF, ELSE, FI);
        branches.add(new Ast.IfBranch(condition, body));

        while (matchConditionalBranch()) {
            head = parseConditionHead(false);
            bindings.add(head.binding());
            condition = head.predicate();
            match(SEMICOLON);
            consumeConditionalThen("elif");
            body = parseUntil(ELSEIF, ELSE, FI);
            branches.add(new Ast.IfBranch(condition, body));
        }

        List<Ast.Stmt> elseBody = List.of();
        if (match(ELSE)) elseBody = parseUntil(FI);
        consume(FI, "expected 'fi' to close if");
        return lowerConditionBindings(branches, bindings, elseBody);
    }

    private void consumeConditionalThen(String branch) {
        if (match(THEN)) return;
        if (match(DO)) {
            Token deprecated = previous();
            warnings.add(new SyntaxWarning(
                    deprecated.line(),
                    deprecated.column(),
                    "conditional 'do' is deprecated; use 'then'"));
            return;
        }
        throw error(peek(), "expected 'then' after " + branch + " condition");
    }

    private boolean matchConditionalBranch() {
        if (match(ELSEIF)) return true; // Lexer maps both 'elif' and legacy 'elseif' here.
        // "else if" is a branch alias only when both keywords are on the
        // same source line. Otherwise a normal else body is allowed to begin
        // with a nested if statement on the following line.
        if (check(ELSE)
                && checkNext(IF)
                && peek().line() == tokens.get(current + 1).line()) {
            advance();
            advance();
            return true;
        }
        return false;
    }

    private record ConditionHead(Ast.BindingStmt binding, Ast.Expr predicate) { }

    private ConditionHead parseConditionHead(boolean whileHead) {
        boolean wrapped = check(LPAREN) && ((whileHead && wholeConditionParentheses()) || hasParenthesizedConditionBinding());
        if (wrapped) advance();
        Ast.Expr value = parseLogicalOr();
        Ast.BindingStmt binding = null;
        Ast.Expr predicate;
        if (check(AS) && checkNext(LET)) {
            advance();
            advance();
            String name = consume(IDENT, "condition binding requires a name").lexeme();
            if (!matchContextualKeyword("when")) {
                throw error(peek(), "condition binding requires 'when' and a boolean predicate");
            }
            binding = new Ast.BindingStmt(Ast.BindingKind.LET, null, name, value, false);
            predicate = parseCondition();
        } else {
            predicate = normalizeLegacyConditionPipe(value);
            while (match(COMMA)) {
                predicate = new Ast.BinaryExpr("&&", predicate,
                        normalizeLegacyConditionPipe(parseLogicalOr()));
            }
        }
        if (wrapped) consume(RPAREN, "expected ')' after condition");
        return new ConditionHead(binding, predicate);
    }

    private boolean wholeConditionParentheses() {
        int depth = 0;
        for (int i = current; i + 1 < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) depth++;
            if (type == RPAREN && --depth == 0) {
                Token.Type next = tokens.get(i + 1).type();
                // A postfix index/call/member or infix operator continues the
                // expression; its '(' belongs to that value, not the head.
                return next == LBRACE || next == DO || next == SEMICOLON;
            }
        }
        return false;
    }

    private boolean hasParenthesizedConditionBinding() {
        int depth = 0;
        int nestedValues = 0;
        for (int i = current; i + 1 < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) depth++;
            if (type == RPAREN && --depth == 0) return false;
            if (type == LBRACE || type == LBRACKET) nestedValues++;
            if (type == RBRACE || type == RBRACKET) nestedValues--;
            if (depth == 1 && nestedValues == 0 && type == AS
                    && tokens.get(i + 1).type() == LET) return true;
        }
        return false;
    }

    private Ast.Stmt lowerConditionBindings(List<Ast.IfBranch> branches,
            List<Ast.BindingStmt> bindings, List<Ast.Stmt> elseBody) {
        if (bindings.stream().allMatch(java.util.Objects::isNull)) {
            return new Ast.IfStmt(branches, elseBody);
        }
        // A later initializer is evaluated only if every preceding test failed.
        List<Ast.Stmt> fallback = elseBody;
        Ast.Stmt result = null;
        for (int i = branches.size() - 1; i >= 0; i--) {
            result = new Ast.IfStmt(List.of(branches.get(i)), fallback);
            if (bindings.get(i) != null) {
                result = new Ast.BlockStmt(List.of(bindings.get(i), result));
            }
            fallback = List.of(result);
        }
        return result;
    }

    private Ast.Expr parseCondition() {
        Ast.Expr expression = normalizeLegacyConditionPipe(parseLogicalOr());
        // Legacy condition-only comma means logical AND. Prefer && in new code.
        while (match(COMMA)) {
            expression = new Ast.BinaryExpr(
                    "&&",
                    expression,
                    normalizeLegacyConditionPipe(parseLogicalOr()));
        }
        return expression;
    }

    private Ast.Expr normalizeLegacyConditionPipe(Ast.Expr expr) {
        if (expr instanceof Ast.BinaryExpr binary) {
            String operator = binary.operator().equals("|") ? "||" : binary.operator();
            return new Ast.BinaryExpr(
                    operator,
                    normalizeLegacyConditionPipe(binary.left()),
                    normalizeLegacyConditionPipe(binary.right()));
        }
        return expr;
    }

    private Ast.MatchStmt parseMatch(boolean procedural) {
        // Canonical match is ordered and spells its boundary explicitly:
        //   match value over
        //     on Pattern -> { ... }
        //   end
        //
        // Preserve the pre-over grammar as a compatibility surface. Legacy
        // match remains proof-checked unless the historical contextual
        // `first` marker is present.
        boolean legacyFirst = matchContextualIdentifier("first");

        boolean previousSuppression = suppressRefinementOperators;
        suppressRefinementOperators = true;
        Ast.Expr subject;
        try {
            subject = parseExpression();
        } finally {
            suppressRefinementOperators = previousSuppression;
        }

        boolean canonicalOver = match(OVER) || matchContextualIdentifier("over");
        if (!canonicalOver) match(SEMICOLON);
        // "over { ... }" owns the outer braces, while "over ... end"
        // retains its established terminator. Neither form may consume the
        // enclosing function's closing brace as if it ended the match.
        boolean braced = canonicalOver && match(LBRACE);
        if (procedural && !canonicalOver) {
            throw error(peek(), "'do match' requires 'over' before its arms");
        }

        List<Ast.MatchArm> arms = new ArrayList<>();
        while (!check(EOF) && !(braced ? check(RBRACE) : check(END))) {
            if (canonicalOver) {
                if (!matchContextualIdentifier("on")) {
                    if (check(ELSE) || check(DEFAULT)) {
                        throw error(peek(), "match has no default/else keyword; use 'on _ -> { ... }'");
                    }
                    throw error(peek(), "every 'match ... over' arm must start with contextual keyword 'on'");
                }
            }

            Ast.Pattern pattern;
            if (!canonicalOver && match(ELSE)) {
                pattern = new Ast.WildcardPattern();
            } else {
                pattern = parsePattern();
            }

            Ast.Expr guard = null;
            if (matchWhenKeyword()) {
                int previousGuardStart = matchGuardStartToken;
                matchGuardStartToken = current;
                try {
                    guard = parseExpression();
                } finally {
                    matchGuardStartToken = previousGuardStart;
                }
            }
            if (check(FAT_ARROW)) {
                throw error(peek(), "match implementations use the slim arrow '->'; '=>' is reserved for type definitions");
            }
            // Canonical braced match permits an immediately braced arm:
            //   do match x over { on Some(v) { ... } }
            // Legacy and over/end arms retain the explicit slim arrow.
            if (!match(ARROW) && !braced) {
                throw error(peek(), "match arms in 'over ... end' use '->'");
            }
            List<Ast.Stmt> body = parseBlock();
            arms.add(new Ast.MatchArm(pattern, guard, body));
        }
        if (braced) consume(RBRACE, "expected '}' to close braced match");
        else consume(END, "expected 'end' to close match");
        if (arms.isEmpty()) {
            throw error(previous(), canonicalOver
                    ? "match requires at least one 'on' arm"
                    : "match requires at least one arm");
        }

        // Canonical match is source-ordered. Legacy bare match retains the
        // old exclusivity/proof semantics unless explicitly written
        // `match first ...`.
        return new Ast.MatchStmt(subject, canonicalOver || legacyFirst, arms, procedural);
    }

    /**
     * A value match cannot be implemented by reinterpreting a procedural
     * return. Each arm yields exactly one expression; the return belongs to
     * MatchExpr rather than the enclosing callable's control flow.
     */
    private Ast.MatchExpr parseValueMatch() {
        // A single result type is an expression constraint, not the subject type.
        // Preserve the historical <ret=T> spelling while preferring <T>.
        Ast.TypeRef declaredResult = null;
        if (match(LT)) {
            if (check(IDENT) && checkNext(EQUAL)) {
                String name = advance().lexeme();
                consume(EQUAL, "expected '=' in named match result parameter");
                if (!name.equals("ret")) {
                    throw error(previous(), "match supports only the named result parameter 'ret'");
                }
            }
            declaredResult = parseTypeRef();
            consume(GT, "match result parameter requires exactly one type argument and closing '>'");
        }
        Ast.MatchStmt parsed = parseMatch(true);
        List<Ast.MatchValueArm> valueArms = new ArrayList<>();
        for (Ast.MatchArm arm : parsed.arms()) {
            if (arm.body().size() != 1
                    || !(arm.body().getFirst() instanceof Ast.ReturnStmt returned)
                    || returned.value() == null) {
                throw error(previous(),
                        "value match arms require exactly one 'return expression;' and no other statements");
            }
            valueArms.add(new Ast.MatchValueArm(arm.pattern(), arm.guard(), returned.value()));
        }
        return new Ast.MatchExpr(parsed.subject(), valueArms, declaredResult);
    }

    private Ast.Pattern parsePattern() {
        if (match(BANG)) {
            // A negative type pattern is a complement of the runtime-reifiable
            // type test, not a cast to the excluded type.
            Ast.TypeRef negative = Ast.TypeRef.complement(parseTypeAtom());
            String binding = match(AS) ? consume(IDENT, "expected name after 'as'").lexeme() : null;
            return new Ast.TypePattern(negative, binding);
        }
        if (match(STRUCTURAL) || matchContextualIdentifier("structural")) {
            Ast.TypeRef target = parseTypeRef();
            String binding = check(IDENT) && !peek().lexeme().equals("_")
                    ? advance().lexeme()
                    : null;
            return new Ast.StructuralPattern(target, binding);
        }

        if (match(IS)) {
            Ast.TypeRef target = parseTypeRef();
            String binding = match(AS)
                    ? consume(IDENT, "expected name after type pattern 'as'").lexeme()
                    : check(IDENT) && !peek().lexeme().equals("_") ? advance().lexeme() : null;
            return new Ast.TypePattern(target, binding);
        }

        if (match(LPAREN)) {
            List<Ast.Pattern> elements = new ArrayList<>();
            elements.add(parsePattern());
            consume(COMMA, "tuple match patterns require at least two elements");
            do elements.add(parsePattern()); while (match(COMMA));
            consume(RPAREN, "expected ')' after tuple match pattern");
            return new Ast.TuplePattern(elements);
        }

        if (match(INT)) return new Ast.LiteralPattern(parseSignedI64(previous()));
        if (match(FLOAT)) return new Ast.LiteralPattern(Double.parseDouble(previous().lexeme().replace("_", "")));
        if (match(STRING)) return new Ast.LiteralPattern(previous().lexeme());
        if (match(SYMBOL_LITERAL)) return new Ast.LiteralPattern(dev.oreslang.runtime.OresSymbol.process(previous().lexeme()));
        if (match(TRUE)) return new Ast.LiteralPattern(Boolean.TRUE);
        if (match(FALSE)) return new Ast.LiteralPattern(Boolean.FALSE);
        if (check(IDENT) && peek().lexeme().equals("_")) {
            advance();
            return new Ast.WildcardPattern();
        }

        // Canonical nominal binding syntax is `Type name`.
        // Parse it speculatively so constructor patterns such as Some(value)
        // and ordinary catch-all bindings remain unambiguous.
        if (check(IDENT)) {
            int patternStart = current;
            try {
                Ast.TypeRef target = parseTypeRef();
                if (match(AS)) {
                    return new Ast.TypePattern(
                            target, consume(IDENT, "expected binding name after 'as'").lexeme());
                }
                if (check(IDENT) && !peek().lexeme().equals("_")) {
                    String binding = advance().lexeme();
                    return new Ast.TypePattern(target, binding);
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to constructor/binding parsing from the same token.
            }
            current = patternStart;
        }

        // Qualified variant bindings are retained as *nominal* constructor
        // patterns in the AST. Backends without enum-payload lowering reject
        // them at the checker rather than silently treating a variant as a
        // catch-all type or erasing its payload.
        if (check(IDENT) && checkNext(DOT)) {
            StringBuilder variant = new StringBuilder(advance().lexeme());
            do {
                consume(DOT, "expected '.' in qualified match variant");
                variant.append('.').append(consume(IDENT,
                        "expected variant member after '.'").lexeme());
            } while (check(DOT));
            consume(AS, "qualified variant destructuring requires 'as ( ... )'");
            consume(LPAREN, "expected '(' after variant 'as'");
            List<Ast.Pattern> fields = new ArrayList<>();
            if (!check(RPAREN)) {
                do {
                    fields.add(new Ast.BindingPattern(consume(IDENT,
                            "expected binding name in variant payload").lexeme()));
                } while (match(COMMA));
            }
            consume(RPAREN, "expected ')' after variant payload bindings");
            return new Ast.ConstructorPattern(variant.toString(), fields);
        }

        if (check(IDENT)) {
            String name = advance().lexeme();
            if (match(LPAREN)) {
                List<Ast.Pattern> args = new ArrayList<>();
                if (!check(RPAREN)) {
                    do args.add(parsePattern()); while (match(COMMA));
                }
                consume(RPAREN, "expected ')' after constructor pattern");
                return new Ast.ConstructorPattern(name, args);
            }
            // Upper-case bare names are zero-arity constructors; lower-case names bind.
            if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                return new Ast.ConstructorPattern(name, List.of());
            }
            return new Ast.BindingPattern(name);
        }

        if (check(LBRACKET)) {
            throw error(peek(), "list patterns are not supported; use tuples for fixed structural matching");
        }
        throw error(peek(), "expected match pattern");
    }

    private Ast.SwitchStmt parseSwitch() {
        Ast.Expr subject = parseExpression();
        match(SEMICOLON);
        List<Ast.SwitchCase> cases = new ArrayList<>();
        List<Ast.Stmt> defaultBody = List.of();
        boolean sawDefault = false;

        while (!check(END) && !check(EOF)) {
            if (matchContextualKeyword("case")) {
                if (sawDefault) throw error(previous(), "switch case cannot appear after default");
                List<Ast.Expr> constants = new ArrayList<>();
                do constants.add(parseExpression()); while (match(COMMA));
                if (check(FAT_ARROW)) {
                    throw error(peek(), "switch implementations use the slim arrow '->'; '=>' is reserved for type definitions");
                }
                consume(ARROW, "switch cases use the slim arrow '->'");
                cases.add(new Ast.SwitchCase(constants, parseBlock()));
                continue;
            }
            if (match(DEFAULT)) {
                if (sawDefault) throw error(previous(), "switch can contain only one default arm");
                sawDefault = true;
                if (check(FAT_ARROW)) {
                    throw error(peek(), "switch implementations use the slim arrow '->'; '=>' is reserved for type definitions");
                }
                consume(ARROW, "switch default uses the slim arrow '->'");
                defaultBody = parseBlock();
                continue;
            }
            throw error(peek(), "expected 'case', 'default', or 'end' in switch");
        }

        consume(END, "expected 'end' to close switch");
        return new Ast.SwitchStmt(subject, cases, defaultBody);
    }

    private Ast.TryStmt parseTry() {
        List<Ast.Stmt> body = parseBlock();
        consume(CATCH, "expected catch after try block");
        consume(LPAREN, "expected '(' after catch");
        String error = consume(IDENT, "expected catch binding").lexeme();
        consume(RPAREN, "expected ')' after catch binding");
        List<Ast.Stmt> catchBody = parseBlock();
        List<Ast.Stmt> finallyBody = match(FINALLY) ? parseBlock() : List.of();
        return new Ast.TryStmt(body, error, catchBody, finallyBody);
    }

    private List<Ast.Stmt> parseUntil(Token.Type... terminators) {
        List<Ast.Stmt> body = new ArrayList<>();
        outer: while (!check(EOF)) {
            for (Token.Type terminator : terminators) if (check(terminator)) break outer;
            body.add(parseStatement());
        }
        return body;
    }

    public Ast.Expr parseExpression() { return parseAssignment(); }

    private Ast.Expr parseAssignment() {
        Ast.Expr expr = parsePipeline();
        if (isContextualIdentifier("pipe")) {
            throw error(peek(), "actor pipe expressions require compiler-checked "
                    + "source/transform/sink role typing, supervisor exclusion, "
                    + "bounded edge transport and Java/LLVM lowering; not yet enabled");
        }
        if (match(EQUAL)) {
            if (!(expr instanceof Ast.NameExpr) && !(expr instanceof Ast.MemberExpr) && !(expr instanceof Ast.IndexExpr)) {
                throw error(previous(), "assignment target must be a local, field, or index");
            }
            return new Ast.AssignExpr(expr, parseAssignment());
        }
        return expr;
    }

    /**
     * Value pipelines currently execute as eager calls, but retain their
     * syntax provenance for future typed lifting. Do not infer a pipeline
     * from an ordinary stage(Future) call. The leading value is evaluated
     * once as the first argument. Namespace combinators
     * (Option.map, Result.map_err, etc.) lower to receiver members so that
     * the normal sum-type checker and ownership rules apply unchanged.
     */
    private Ast.Expr parsePipeline() {
        Ast.Expr value = parseConditional();
        int stages = 0;
        while (true) {
            boolean functional = match(PIPE_FORWARD);
            boolean actorPipe = !functional && matchContextualKeyword("pipe");
            if (!functional && !actorPipe) break;
            Token operator = previous();
            if (actorPipe) {
                throw error(operator, "actor pipe expressions require compiler-checked "
                        + "source/transform/sink role typing, supervisor exclusion, "
                        + "bounded edge transport and Java/LLVM lowering; not yet enabled");
            }
            if (++stages > 128) {
                throw error(operator, "pipeline exceeds 128 stages");
            }
            Ast.Expr stage = parsePostfix();
            if (actorPipe) {
                value = new Ast.BinaryExpr("pipe", value, stage);
            } else if (stage instanceof Ast.CallExpr call) {
                if (call.callee() instanceof Ast.MemberExpr member
                        && member.receiver() instanceof Ast.NameExpr namespace
                        && (namespace.name().equals("Option") || namespace.name().equals("Result"))) {
                    value = new Ast.CallExpr(new Ast.MemberExpr(value, member.member()),
                            call.typeArguments(), call.typeArgumentsPresent(),
                            call.arguments(), Ast.CallOrigin.VALUE_PIPE_RECEIVER);
                } else if (isPipelineCallable(call.callee())) {
                    List<Ast.Expr> args = new ArrayList<>();
                    args.add(value);
                    args.addAll(call.arguments());
                    value = new Ast.CallExpr(call.callee(), call.typeArguments(),
                            call.typeArgumentsPresent(), args, Ast.CallOrigin.VALUE_PIPE_ARGUMENT);
                } else {
                    throw error(operator, "pipeline stage must call a named function or Option/Result combinator");
                }
            } else if (isPipelineCallable(stage)) {
                value = new Ast.CallExpr(stage, List.of(), false, List.of(value),
                        Ast.CallOrigin.VALUE_PIPE_ARGUMENT);
            } else {
                throw error(operator, "pipeline stage must be a named callable");
            }
        }
        return value;
    }

    private static boolean isPipelineCallable(Ast.Expr stage) {
        if (stage instanceof Ast.NameExpr || stage instanceof Ast.LambdaExpr) return true;
        if (stage instanceof Ast.MemberExpr member)
            return isNamedPipelineCallee(member.receiver());
        return false;
    }

    private static boolean isNamedPipelineCallee(Ast.Expr callee) {
        if (callee instanceof Ast.NameExpr) return true;
        if (callee instanceof Ast.MemberExpr member)
            return isNamedPipelineCallee(member.receiver());
        return false;
    }

    private Ast.Expr parseConditional() {
        Ast.Expr condition = parseLogicalOr();
        if (!match(QUESTION)) return condition;
        Ast.Expr whenTrue = parseAssignment();
        consume(COLON, "expected ':' in ternary expression");
        Ast.Expr whenFalse = parseConditional();
        return new Ast.ConditionalExpr(condition, whenTrue, whenFalse);
    }

    private Ast.Expr parseLogicalOr() {
        Ast.Expr expr = parseLogicalXor();
        while (!isChannelCallbackDelimiter() && matchAdjacentPair(PIPE)) {
            expr = new Ast.BinaryExpr("||", expr, parseLogicalXor());
        }
        return expr;
    }

    private boolean isChannelCallbackDelimiter() {
        return current + 2 < tokens.size()
                && tokens.get(current).type() == PIPE
                && tokens.get(current + 1).type() == PIPE
                && tokens.get(current + 2).type() == ARROW
                && adjacent(tokens.get(current), tokens.get(current + 1));
    }

    private Ast.Expr parseLogicalXor() {
        Ast.Expr expr = parseLogicalAnd();
        while (matchAdjacentPair(CARET)) expr = new Ast.BinaryExpr("^^", expr, parseLogicalAnd());
        return expr;
    }

    private Ast.Expr parseLogicalAnd() {
        Ast.Expr expr = parseBitwiseOr();
        while (matchAdjacentPair(AMP)) expr = new Ast.BinaryExpr("&&", expr, parseBitwiseOr());
        return expr;
    }

    private Ast.Expr parseBitwiseOr() {
        Ast.Expr expr = parseBitwiseXor();
        while (!isChannelCallbackDelimiter() && matchSingleOperator(PIPE)) {
            expr = new Ast.BinaryExpr("|", expr, parseBitwiseXor());
        }
        return expr;
    }

    private Ast.Expr parseBitwiseXor() {
        Ast.Expr expr = parseBitwiseAnd();
        while (matchSingleOperator(CARET)) expr = new Ast.BinaryExpr("^", expr, parseBitwiseAnd());
        return expr;
    }

    private Ast.Expr parseBitwiseAnd() {
        Ast.Expr expr = parseEquality();
        while (matchSingleOperator(AMP)) expr = new Ast.BinaryExpr("&", expr, parseEquality());
        return expr;
    }

    private Ast.Expr parseEquality() {
        Ast.Expr expr = parseComparison();
        while (true) {
            String op;
            if (match(EQ, EQUAL_EQUAL)) op = "eq";
            else if (match(NEQ, BANG_EQUAL)) op = "neq";
            else if (matchAdjacentBangEqKeyword()) op = "neq";
            else if (!suppressRefinementOperators && match(IS)) op = "is";
            else break;
            expr = new Ast.BinaryExpr(op, expr, parseComparison());
        }
        return expr;
    }

    private Ast.Expr parseComparison() {
        Ast.Expr expr = parseShift();
        while (true) {
            if (match(LT, LTE, GT, GTE)) {
                String op = previous().lexeme();
                expr = new Ast.BinaryExpr(op, expr, parseShift());
                continue;
            }
            if (!suppressRefinementOperators && check(IS) && checkNext(TYPE)) {
                advance();
                consume(TYPE, "expected 'type' after 'is' in a nominal type test");
                Ast.TypeRef target = parseTypeRef();
                String binding = check(IDENT) && !peek().lexeme().equals("_") ? advance().lexeme() : null;
                expr = new Ast.TypeTestExpr(expr, target, binding);
                continue;
            }
            if (!suppressRefinementOperators && match(MATCHES)) {
                expr = new Ast.PatternTestExpr(expr, parsePattern());
                continue;
            }
            // In a condition head, `as let name when ...` ends the value.
            // Other `as T` uses retain ordinary checked-cast precedence.
            if (check(AS) && checkNext(LET)) break;
            if (!suppressRefinementOperators && match(AS)) {
                boolean optional = match(QUESTION);
                expr = new Ast.CastExpr(expr, parseTypeRef(),
                        optional ? Ast.CastMode.OPTIONAL : Ast.CastMode.CHECKED);
                continue;
            }
            break;
        }
        return expr;
    }

    private Ast.Expr parseShift() {
        Ast.Expr expr = parseAdditive();
        while (true) {
            String op;
            if (matchAdjacentTriple(GT)) op = ">>>";
            else if (matchAdjacentPair(LT)) op = "<<";
            else if (matchAdjacentPair(GT)) op = ">>";
            else break;
            expr = new Ast.BinaryExpr(op, expr, parseAdditive());
        }
        return expr;
    }

    private Ast.Expr parseAdditive() {
        Ast.Expr expr = parseMultiplicative();
        while (match(PLUS, MINUS)) {
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseMultiplicative());
        }
        return expr;
    }

    private Ast.Expr parseMultiplicative() {
        Ast.Expr expr = parseUnary();
        while (match(STAR, SLASH, PERCENT)) {
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseUnary());
        }
        return expr;
    }

    private Ast.Expr parseUnary() {
        // Signed i64's negative endpoint requires magnitude 2^63, which
        // cannot be parsed as a positive Java long. Treat exactly that
        // token sequence as a single negative literal, without broadening
        // positive literal admission or accepting malformed separators.
        if (match(MINUS)) {
            if (check(INT)) {
                String literal = peek().lexeme();
                if (!literal.endsWith("_") && !literal.contains("__")
                        && literal.replace("_", "").equals("9223372036854775808")) {
                    advance();
                    return new Ast.LiteralExpr(Long.MIN_VALUE);
                }
            }
            return new Ast.UnaryExpr("-", parseUnary());
        }
        if (match(BANG, TILDE, PLUS)) return new Ast.UnaryExpr(previous().lexeme(), parseUnary());
        if (match(AMP)) {
            throw error(previous(), "use rt borrow or rt mut borrow; unary pointer syntax is not supported");
        }
        if (match(AWAIT)) return new Ast.AwaitExpr(parseUnary());

        // task is contextual: task(...) and identifiers named task remain legal.
        if (check(IDENT) && peek().lexeme().equals("task") && checkNext(LBRACE)) {
            advance();
            return new Ast.TaskExpr(Ast.ExecutionDomain.CPU, parseBlock(), false);
        }
        if (match(CPU, GPU)) {
            Token domainToken = previous();
            Ast.ExecutionDomain domain = domainToken.type() == CPU
                    ? Ast.ExecutionDomain.CPU : Ast.ExecutionDomain.GPU;
            if (!matchContextualKeyword("task")) {
                throw error(domainToken,
                        "synchronous " + domainToken.lexeme()
                                + " { ... } lowering is not enabled yet; use "
                                + domainToken.lexeme() + " task { ... }");
            }
            return new Ast.TaskExpr(domain, parseBlock(), false);
        }

        // Expression-level trap is a typed Option boundary; the declaration
        // modifier "trap fnc" remains a distinct grammar production.
        if (match(TRAP)) return new Ast.TrapExpr(parseUnary());
        if (match(RT)) return parseRuntimeExpression();

        if (match(NB)) {
            if (check(LBRACE)) {
                return new Ast.TaskExpr(Ast.ExecutionDomain.CPU, parseBlock(), true);
            }
            if (match(CPU, GPU)) {
                Ast.ExecutionDomain domain = previous().type() == CPU
                        ? Ast.ExecutionDomain.CPU : Ast.ExecutionDomain.GPU;
                if (!check(LBRACE)) {
                    throw error(peek(), "legacy 'nb cpu/gpu' task form requires a block");
                }
                return new Ast.TaskExpr(domain, parseBlock(), true);
            }
            if (match(CB)) {
                if (!match(WRITECH)) {
                    throw error(previous(), "'nb cb' must prefix writech");
                }
                Ast.ChannelOpExpr operation =
                        (Ast.ChannelOpExpr) parseChannelOperation(
                                Ast.ChannelOperation.WRITE,
                                Ast.WaitMode.NONBLOCKING);
                consume(PIPE, "channel callback requires '|| -> { ... }'");
                consume(PIPE, "channel callback requires '|| -> { ... }'");
                consume(ARROW, "channel callback requires '|| -> { ... }'");
                return new Ast.ChannelOpExpr(
                        operation.operation(),
                        operation.mode(),
                        operation.channel(),
                        operation.value(),
                        parseBlock());
            }
            if (match(READCH)) return parseChannelOperation(Ast.ChannelOperation.READ, Ast.WaitMode.NONBLOCKING);
            if (match(WRITECH)) return parseChannelOperation(Ast.ChannelOperation.WRITE, Ast.WaitMode.NONBLOCKING);
            if (match(SELECT)) return parseDynamicSelect(Ast.WaitMode.NONBLOCKING);
            throw error(previous(), "'nb' must prefix readch, writech, select, or cb writech");
        }

        if (check(TRY) && current + 1 < tokens.size()) {
            Token.Type next = tokens.get(current + 1).type();
            if (next == READCH || next == WRITECH || next == SELECT) {
                advance();
                if (match(READCH)) return parseChannelOperation(Ast.ChannelOperation.READ, Ast.WaitMode.IMMEDIATE);
                if (match(WRITECH)) return parseChannelOperation(Ast.ChannelOperation.WRITE, Ast.WaitMode.IMMEDIATE);
                consume(SELECT, "expected select");
                return parseDynamicSelect(Ast.WaitMode.IMMEDIATE);
            }
        }

        if (match(READCH)) return parseChannelOperation(Ast.ChannelOperation.READ, Ast.WaitMode.BLOCKING);
        if (match(WRITECH)) return parseChannelOperation(Ast.ChannelOperation.WRITE, Ast.WaitMode.BLOCKING);
        if (match(SELECT)) return parseDynamicSelect(Ast.WaitMode.BLOCKING);

        return parsePostfix();
    }

    private Ast.Expr parseRuntimeExpression() {
        boolean mutableBorrow = match(MUT);
        Token operation;
        if (match(YIELD)) {
            operation = previous(); // compatibility spelling: rt yield
        } else {
            operation = consume(IDENT, "expected runtime operation after 'rt'");
        }

        String name = operation.lexeme();
        if (mutableBorrow && !name.equals("borrow")) {
            throw error(operation, "'rt mut' may only modify borrow; use 'rt mut borrow value'");
        }
        if (operation.type() == YIELD || name.equals("yield") || name.equals("cooperate")) {
            if (match(LPAREN)) {
                if (!check(RPAREN)) {
                    throw error(peek(),
                            "rt cooperate currently takes no arguments; scheduling policy hints are not enabled yet");
                }
                consume(RPAREN, "expected ')' after rt cooperate");
            }
            return new Ast.RuntimeCallExpr("cooperate", List.of());
        }

        if (!name.equals("copy") && !name.equals("take")
                && !name.equals("borrow") && !name.equals("share") && !name.equals("fail")) {
            throw error(operation,
                    "unknown rt operation '" + name
                            + "'; supported operations are cooperate, copy, take, borrow, share, and fail");
        }

        if (name.equals("borrow") && check(MUT)) {
            throw error(peek(),
                    "legacy 'rt borrow mut value' is not accepted; use 'rt mut borrow value'");
        }
        Ast.Expr argument;
        if (match(LPAREN)) {
            if (check(RPAREN)) {
                throw error(peek(), "rt " + name + " expects exactly one argument");
            }
            argument = parseExpression();
            consume(RPAREN, "expected ')' after rt " + name + " argument");
        } else {
            // Command form has unary precedence: rt borrow value.member
            argument = parseUnary();
        }
        return mutableBorrow ? new Ast.UnaryExpr("&mut", argument)
                : new Ast.RuntimeCallExpr(name, List.of(argument));
    }

    private Ast.Expr parseChannelOperation(
            Ast.ChannelOperation operation,
            Ast.WaitMode mode) {
        if (operation == Ast.ChannelOperation.DEFAULT) {
            throw new AssertionError("default is not a standalone channel operation");
        }

        if (match(LPAREN)) {
            Ast.Expr channel = parseExpression();
            Ast.Expr value = null;
            if (operation == Ast.ChannelOperation.WRITE) {
                consume(COMMA, "writech(channel, value) requires two arguments");
                value = parseExpression();
            }
            consume(RPAREN, "expected ')' after channel operation");
            return new Ast.ChannelOpExpr(operation, mode, channel, value);
        }

        Ast.Expr channel = parseUnary();
        Ast.Expr value = null;
        if (operation == Ast.ChannelOperation.WRITE) {
            consume(COMMA, "writech command form requires 'writech channel, value'");
            value = parseExpression();
        }
        return new Ast.ChannelOpExpr(operation, mode, channel, value);
    }

    private Ast.DynamicSelectExpr parseDynamicSelect(Ast.WaitMode mode) {
        Ast.SelectPolicy policy = parseSelectPolicy();
        consume(FROM,
                "dynamic select requires 'select from cases'; static select uses 'select { when ... }'");
        Ast.Expr cases = parseUnary();
        return new Ast.DynamicSelectExpr(mode, policy, cases);
    }

    private Ast.Expr parsePostfix() {
        Ast.Expr expr = parsePrimary();
        while (true) {
            if (check(LT) && adjacent(previous(), peek()) && looksLikeTypeArgumentCall()) {
                List<Ast.TypeRef> typeArguments = parseCallTypeArguments();
                consume(LPAREN, "expected '(' after call type arguments");
                List<Ast.Expr> args = parseCallArgumentsUntil(RPAREN);
                consume(RPAREN, "expected ')' after arguments");
                expr = new Ast.CallExpr(expr, typeArguments, args);
            } else if (match(LPAREN)) {
                List<Ast.Expr> args = parseCallArgumentsUntil(RPAREN);
                consume(RPAREN, "expected ')' after arguments");
                expr = new Ast.CallExpr(expr, args);
            } else if (match(DOT, DOUBLE_COLON)) {
                // Two explicit qualified-member spellings, one binding and
                // evaluation path; no dynamic symbol lookup or macro rewrite.
                String member = consumeMemberName();
                expr = new Ast.MemberExpr(expr, member);
            } else if (match(LBRACKET)) {
                Ast.Expr index = parseExpression();
                consume(RBRACKET, "expected ']' after index");
                expr = new Ast.IndexExpr(expr, index);
            } else break;
        }
        return expr;
    }

    private List<Ast.TypeRef> parseCallTypeArguments() {
        consume(LT, "expected '<' before call type arguments");
        List<Ast.TypeRef> arguments = new ArrayList<>();
        boolean seenNamed = false;
        if (!check(GT)) {
            do {
                if (check(IDENT) && checkNext(EQUAL)) {
                    String name = advance().lexeme();
                    consume(EQUAL, "expected '=' after named generic argument");
                    arguments.add(Ast.TypeRef.namedParameter(name, parseTypeRef()));
                    seenNamed = true;
                } else {
                    if (seenNamed) {
                        throw error(peek(), "positional generic arguments must precede named arguments");
                    }
                    arguments.add(parseTypeRef());
                }
            } while (match(COMMA));
        }
        consume(GT, "expected '>' after call type arguments");
        return List.copyOf(arguments);
    }

    private boolean looksLikeTypeArgumentCall() {
        return looksLikeTypeArgumentCallAt(current);
    }

    private boolean looksLikeTypeArgumentCallAt(int startIndex) {
        int depth = 0;
        for (int i = startIndex; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LT) depth++;
            else if (type == GT) {
                depth--;
                if (depth == 0) return i + 1 < tokens.size() && tokens.get(i + 1).type() == LPAREN;
                if (depth < 0) return false;
            } else if (type == SEMICOLON || type == QUESTION || type == COLON || type == EOF
                    || (type == EQUAL && (depth == 0 || i == 0
                            || tokens.get(i - 1).type() != IDENT))) {
                // '=' is allowed only after a generic parameter name inside <...>.
                return false;
            }
        }
        return false;
    }

    private String consumeCallableName(String message) {
        Token token = peek();
        if (token.type() == IDENT
                || isReservedCallableName(token.type())
                || isContextualStatementCallableName(token.type())
                || token.type() == TRUE || token.type() == FALSE) {
            advance();
            return token.lexeme();
        }
        throw error(token, message);
    }

    private boolean reservedCallableNameFollowedByInvocation(int nameIndex) {
        int nextIndex = nameIndex + 1;
        if (nextIndex >= tokens.size()) return false;
        Token next = tokens.get(nextIndex);
        if (next.type() == LPAREN) return true;
        return next.type() == LT
                && adjacent(tokens.get(nameIndex), next)
                && looksLikeTypeArgumentCallAt(nextIndex);
    }

    private static boolean isReservedCallableName(Token.Type type) {
        return type == STOP || type == DO || type == DONE;
    }

    private static boolean isContextualStatementCallableName(Token.Type type) {
        return type == LOOP || type == BLOCK || type == SELECT
                || type == READCH || type == WRITECH || type == NB;
    }

    private String consumeStaticObjectKeyName(String message) {
        if (match(STRING)) return previous().lexeme();
        Token token = peek();
        if (isMemberNameToken(token.type())) {
            advance();
            return token.lexeme();
        }
        throw error(token, message);
    }

    private String consumeMemberName() {
        Token token = peek();
        if (isMemberNameToken(token.type())) {
            if (isReservedCallableName(token.type())
                    && token.type() != DONE
                    && !reservedCallableNameFollowedByInvocation(current)) {
                throw error(token, "'" + token.lexeme()
                        + "' is reserved and may only be used as a function name in a call or as an object/map key");
            }
            advance();
            return token.lexeme();
        }
        throw error(token, "expected member name after '.'");
    }

    /**
     * Reserved words remain illegal lexical identifiers, but member names live
     * in a separate namespace. This permits APIs such as SharedMutex.new(...)
     * without allowing declarations such as `val new = ...`.
     */
    private static boolean isMemberNameToken(Token.Type type) {
        return switch (type) {
            case IDENT,
                    DEFINE, CLASS, MODULE, NAMESPACE, IMPORT, FROM, AS, EXTENDS, IMPLEMENTS,
                    TRY, CATCH, FINALLY, END, FI, IF, DO, ELSE, THEN,
                    NEW, SPAWN, STOP, DONE, AWAIT, ASYNC, NLEX, TRAP, NB, SELECT, READCH, WRITECH, ACTOR, ISOACTOR, SHARED, DEF, FNC, ROUTINE, FOR, OF, LOOP, BLOCK, BREAK, CONTINUE, YIELD, SUPER, ELSEIF, SWITCH, MATCH, MATCHES, EQ, NEQ, IS, WHEN, CASE, DEFAULT, FIRST, CB, UNTRUSTED, TYPE, TYPES, TYPEOF,
                    INTERFACE, TRAIT, STRUCT, IMPL, ABSTRACT, VOID, STATIC, PUB, PRIVATE, STRUCTURAL, RETURN, DEFER,
                    VAL, CONST, LET, MUT, SELF, TRUE, FALSE, NULL, OBJ, ARR -> true;
            default -> false;
        };
    }

    // A guest-language literal must not leak Java's NumberFormatException.
    // Both expression and pattern literals use the same signed-i64 bound.
    // Wider integers require a separately admitted type and backend contract.
    private long parseSignedI64(Token token) {
        String literal = token.lexeme();
        // Do not silently turn malformed token separators into valid integers.
        if (literal.endsWith("_") || literal.contains("__")) {
            throw error(token, "malformed integer separator: " + literal);
        }
        try {
            return Long.parseLong(literal.replace("_", ""));
        } catch (NumberFormatException invalid) {
            throw error(token, "integer literal overflows signed 64-bit: " + literal);
        }
    }

    private Ast.Expr parsePrimary() {
        if (match(MATCH)) return parseValueMatch();
        if (match(INT)) return new Ast.LiteralExpr(parseSignedI64(previous()));
        if (match(FLOAT)) return new Ast.LiteralExpr(Double.parseDouble(previous().lexeme().replace("_", "")));
        if (match(IMAG)) {
            String raw = previous().lexeme().substring(0, previous().lexeme().length() - 1).replace("_", "");
            return new Ast.LiteralExpr(new Ast.Imaginary(Double.parseDouble(raw)));
        }
        if (match(STRING)) return new Ast.LiteralExpr(previous().lexeme());
        if (match(TEMPLATE_LITERAL)) return TemplateLiterals.lower(previous().lexeme());
        if (match(SYMBOL_LITERAL)) return new Ast.LiteralExpr(dev.oreslang.runtime.OresSymbol.process(previous().lexeme()));
        if (match(TRUE)) return new Ast.LiteralExpr(Boolean.TRUE);
        if (match(FALSE)) return new Ast.LiteralExpr(Boolean.FALSE);
        if (match(NULL)) throw error(previous(), "standalone null values are forbidden; use Option<T>");
        if (match(SELF)) return new Ast.NameExpr("self");
        // 'actor' remains reserved, but in expression position it names the
        // actor-local runtime namespace (actor.gc and future local primitives).
        if (match(ACTOR)) return new Ast.NameExpr("actor");
        // loop/block are contextual statement keywords: parseStatement only
        // consumes them when followed by '{'. In every expression position they
        // remain valid references to same-named callables, including first-class
        // function values (not only direct calls).
        if (isContextualStatementCallableName(peek().type())) {
            return new Ast.NameExpr(advance().lexeme());
        }
        if (isReservedCallableName(peek().type())
                && reservedCallableNameFollowedByInvocation(current)) {
            return new Ast.NameExpr(advance().lexeme());
        }
        if (isContextualIdentifier("infer")
                && (checkNext(STRUCT)
                    || (checkNext(IDENT) && current + 1 < tokens.size()
                        && tokens.get(current + 1).lexeme().equals("record")))) {
            advance(); // contextual infer
            if (match(STRUCT)) {
                return parseObjectLiteral(Ast.ObjectKind.INFERRED_STRUCT, List.of());
            }
            consumeContextualIdentifier("record", "expected 'record' after infer");
            return parseObjectLiteral(Ast.ObjectKind.INFERRED_RECORD, List.of());
        }
        if (isContextualIdentifier("record") && checkNext(LBRACE)) {
            advance(); // contextual record
            return parseObjectLiteral(Ast.ObjectKind.RECORD, List.of());
        }
        if (isContextualIdentifier("tuple") && checkNext(LPAREN)) {
            advance();
            consume(LPAREN, "expected '(' after tuple");
            List<Ast.Expr> items = parseArgumentsUntil(RPAREN);
            consume(RPAREN, "expected ')' after explicit tuple");
            return new Ast.TupleExpr(items);
        }
        if (match(IDENT, SHARED, UNTRUSTED)) {
            return new Ast.NameExpr(previous().lexeme());
        }
        if (match(SPAWN)) {
            Ast.TypeRef type = parseTypeRef();
            consume(LPAREN, "expected '(' after actor type");
            List<Ast.Expr> args = parseArgumentsUntil(RPAREN);
            consume(RPAREN, "expected ')' after actor spawn arguments");
            // Keep actor creation semantically distinct from ordinary class construction.
            // RuntimeCallExpr is an internal intrinsic encoding; source syntax is only
            // `spawn ActorType(...)`, never `rt spawn` or `new ActorType(...)`.
            return new Ast.RuntimeCallExpr("spawn", List.of(new Ast.NewExpr(type, args)));
        }
        if (match(NEW)) {
            Ast.TypeRef type = parseTypeRef();
            consume(LPAREN, "expected '(' after new type");
            List<Ast.Expr> args = parseArgumentsUntil(RPAREN);
            consume(RPAREN, "expected ')' after constructor arguments");
            return new Ast.NewExpr(type, args);
        }
        if (match(OBJ)) {
            throw error(previous(),
                    "obj{...} has been removed; use infer struct{...} for a closed inferred struct "
                            + "or struct{field: Type,...}{...} for an explicit closed struct");
        }
        if (match(STRUCT)) {
            if (matchContextualKeyword("infer") || match(INFER)) {
                throw error(previous(), "inferred structs use 'infer struct{...}'");
            }
            if (check(LBRACE)) return parseExplicitStructLiteral();
            throw error(peek(), "struct expression requires 'infer struct{...}' or 'struct{field: Type,...}{...}'");
        }
        if (match(ARR)) {
            consume(LBRACKET, "expected '[' after arr");
            List<Ast.Expr> items = parseArgumentsUntil(RBRACKET);
            consume(RBRACKET, "expected ']' after arr literal");
            return new Ast.ListExpr(items);
        }
        if (match(NLEX)) {
            if (check(PIPE)) return parsePipeLambda(true);
            if (check(LPAREN) && looksLikeLambda()) return parseLambda(true);
            throw error(previous(), "'nlex' in expression position must prefix a lambda");
        }
        if (check(PIPE)) return parsePipeLambda(false);
        if (check(LPAREN) && looksLikeLambda()) return parseLambda(false);
        if (match(LPAREN)) {
            Ast.Expr first = parseExpression();
            if (match(COMMA)) {
                List<Ast.Expr> items = new ArrayList<>();
                items.add(first);
                do items.add(parseExpression()); while (match(COMMA));
                consume(RPAREN, "expected ')' after tuple");
                return new Ast.TupleExpr(items);
            }
            consume(RPAREN, "expected ')' after expression");
            return first;
        }
        if (match(LBRACKET)) {
            List<Ast.Expr> items = parseArgumentsUntil(RBRACKET);
            consume(RBRACKET, "expected ']'");
            return new Ast.ListExpr(items);
        }
        throw error(peek(), "expected expression");
    }

    private Ast.ObjectExpr parseExplicitStructLiteral() {
        consume(LBRACE, "expected '{' after struct");
        List<Ast.StructFieldSpec> declared = new ArrayList<>();
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        if (!check(RBRACE)) {
            do {
                String name = consumeStaticObjectKeyName("expected explicit struct field name");
                if (!names.add(name)) {
                    throw error(previous(), "duplicate explicit struct field '" + name + "'");
                }
                consume(COLON, "expected ':' after explicit struct field name");
                declared.add(new Ast.StructFieldSpec(name, parseTypeRef()));
            } while (match(COMMA));
        }
        consume(RBRACE, "expected '}' after explicit struct shape");
        if (!check(LBRACE)) {
            throw error(peek(), "explicit struct shape must be followed by an initializer: struct{field: Type}{field: value}");
        }
        return parseObjectLiteral(Ast.ObjectKind.EXPLICIT_STRUCT, declared);
    }

    private Ast.ObjectExpr parseObjectLiteral(
            Ast.ObjectKind kind,
            List<Ast.StructFieldSpec> declaredFields) {
        boolean plainRecord = kind == Ast.ObjectKind.RECORD;
        boolean inferredStruct = kind == Ast.ObjectKind.INFERRED_STRUCT;
        boolean inferredRecord = kind == Ast.ObjectKind.INFERRED_RECORD;
        boolean explicitStruct = kind == Ast.ObjectKind.EXPLICIT_STRUCT;
        consume(LBRACE, plainRecord
                ? "expected '{' after record"
                : inferredStruct ? "expected '{' after infer struct"
                : inferredRecord ? "expected '{' after infer record"
                : "expected '{' after explicit struct shape");
        List<Ast.ObjectField> fields = new ArrayList<>();
        if (!check(RBRACE)) {
            do {
                if (check(BACKTICK) || check(TEMPLATE_LITERAL)) {
                    throw error(peek(),
                            "struct fields must be statically named for AOT compilation; "
                                    + "computed/backtick field names are not supported");
                }
                String name = consumeStaticObjectKeyName("expected struct field name");
                consume(COLON, "expected ':' after struct field name");
                fields.add(Ast.ObjectField.named(name, parseExpression()));
            } while (match(COMMA));
        }
        consume(RBRACE, explicitStruct
                ? "expected '}' after explicit struct initializer"
                : inferredRecord ? "expected '}' after infer record literal"
                : "expected '}' after infer struct literal");
        return new Ast.ObjectExpr(fields, kind, declaredFields);
    }

    private Ast.LambdaExpr parseLambda(boolean nonLexical) {
        consume(LPAREN, "expected '('");
        List<Ast.Param> params = parseParametersUntil(RPAREN);
        consume(RPAREN, "expected ')' after lambda parameters");
        consume(ARROW, "expected '->' after lambda parameters");
        return parseLambdaBody(params, nonLexical);
    }

    private Ast.LambdaExpr parsePipeLambda(boolean nonLexical) {
        consume(PIPE, "expected '|'");
        List<Ast.Param> params = new ArrayList<>();
        if (!check(PIPE)) {
            do {
                if (check(IDENT) && (checkNext(COMMA) || checkNext(PIPE))) {
                    String name = advance().lexeme();
                    params.add(new Ast.Param(Ast.TypeRef.inferred(), name, false, false));
                } else if (check(IDENT) && checkNext(COLON)) {
                    String name = advance().lexeme();
                    consume(COLON, "expected ':' after lambda parameter name");
                    Ast.TypeRef type = parsePipeParameterType();
                    boolean mutable = match(MUT);
                    params.add(new Ast.Param(type, name, false, mutable));
                } else {
                    Ast.TypeRef type = parsePipeParameterType();
                    boolean mutable = match(MUT);
                    String name = consume(IDENT, "expected lambda parameter name").lexeme();
                    params.add(new Ast.Param(type, name, false, mutable));
                }
            } while (match(COMMA));
        }
        consume(PIPE, "expected closing '|' after lambda parameters");
        consume(ARROW, "lambdas use the slim arrow '->'");
        return parseLambdaBody(params, nonLexical);
    }

    private Ast.LambdaExpr parseLambdaBody(List<Ast.Param> params, boolean nonLexical) {
        // Only an immediate '{' denotes the statement-block form. Everything
        // else starts a normal expression body. This is intentionally lexical:
        // future prefix expressions such as `struct{...}{...}` must remain
        // expression bodies rather than being confused with a lambda block.
        if (check(LBRACE)) {
            return new Ast.LambdaExpr(params, null, parseBlock(), nonLexical);
        }
        return new Ast.LambdaExpr(params, parseExpression(), null, nonLexical);
    }

    private boolean looksLikeLambda() {
        if (matchGuardStartToken >= 0 && current >= matchGuardStartToken) {
            // At the guard's own lexical depth a following '->' belongs to
            // the match arm. Preserve real nested lambdas in calls/groups:
            // their parentheses are at positive depth from the guard start.
            int depth = 0;
            for (int i = matchGuardStartToken; i < current; i++) {
                Token.Type type = tokens.get(i).type();
                if (type == LPAREN || type == LBRACKET || type == LBRACE) depth++;
                else if (type == RPAREN || type == RBRACKET || type == RBRACE) depth--;
            }
            if (depth == 0) return false;
        }
        // An arm's slim arrow may follow a parenthesized expression, e.g.
        // 'when await (nb readch gate) -> { ... }'. A closing ')' followed
        // by '->' is not sufficient evidence of a lambda declaration:
        // lambda parameters require declaration syntax, never these
        // expression-only prefix operators.
        if (current + 1 < tokens.size()) {
            Token.Type first = tokens.get(current + 1).type();
            if (first == NB || first == AWAIT || first == READCH
                    || first == WRITECH || first == RT || first == TRAP
                    || first == TRY || first == SELECT || first == BANG) {
                return false;
            }
        }
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) depth++;
            else if (type == RPAREN) {
                depth--;
                if (depth == 0) return i + 1 < tokens.size() && tokens.get(i + 1).type() == ARROW;
            }
        }
        return false;
    }

    private List<Ast.Expr> parseCallArgumentsUntil(Token.Type terminator) {
        if (check(terminator)) return List.of();
        List<Ast.Expr> args = new ArrayList<>();
        do {
            boolean spread = match(ELLIPSIS);
            Ast.Expr argument = parseExpression();
            args.add(spread ? new Ast.SpreadExpr(argument) : argument);
        } while (match(COMMA));
        return args;
    }

    private List<Ast.Expr> parseArgumentsUntil(Token.Type terminator) {
        if (check(terminator)) return List.of();
        List<Ast.Expr> args = new ArrayList<>();
        do args.add(parseExpression()); while (match(COMMA));
        return args;
    }

    private void consumeMemberTerminator(Token.Type structuralTerminator, String message) {
        if (match(SEMICOLON) || check(structuralTerminator)) return;
        throw error(peek(), message);
    }

    private void consumeClassFieldTerminator(String message) {
        if (match(SEMICOLON) || check(AT) || check(END) || check(PUB) || check(PRIVATE)
                || check(STATIC) || check(ABSTRACT) || check(ASYNC) || check(SUSPENDS) || check(LBRACKET)
                || isBindingKind(peek().type())
                || (check(IDENT) && (checkNext(COLON) || checkNext(LPAREN)))
                || isNextContextualMemberLine()) return;
        throw error(peek(), message);
    }

    // A field without a semicolon can be followed by the new contextual
    // declaration spellings, but only on a *new* source line. Never treat
    // arbitrary same-line tokens as an implicit field terminator.
    private boolean isNextContextualMemberLine() {
        if (current == 0 || previous().line() >= peek().line()) return false;
        if (check(FNC)) return true;
        if (check(MUT)) return looksLikeTypeFirstClassField();
        if (!check(IDENT)) return false;
        String word = peek().lexeme();
        return ((word.equals("suspend") || word.equals("method")
                || word.equals("final") || word.equals("critical"))
                && (checkNext(IDENT) || checkNext(MUT) || checkNext(FNC) || checkNext(LPAREN)))
                || looksLikeTypeFirstClassField();
    }

    private boolean hasAnnotation(List<Ast.Annotation> annotations, String name) {
        for (Ast.Annotation annotation : annotations) if (annotation.name().equals(name)) return true;
        return false;
    }

    private record CallableStructural(boolean enabled, List<Ast.Annotation> annotations) { }

    private boolean isCallableStructuralAnnotation(Ast.Annotation annotation) {
        return annotation.name().equals("structural") || annotation.name().equals("Structural");
    }

    private CallableStructural normalizeCallableStructural(
            List<Ast.Annotation> annotations,
            boolean modifierStructural) {
        boolean annotationStructural = false;
        List<Ast.Annotation> remaining = new ArrayList<>(annotations.size());
        for (Ast.Annotation annotation : annotations) {
            if (!isCallableStructuralAnnotation(annotation)) {
                remaining.add(annotation);
                continue;
            }
            if (!annotation.arguments().isEmpty()) {
                throw error(previous(), "@structural does not take arguments");
            }
            if (annotationStructural) {
                throw error(previous(), "duplicate @structural callable annotation");
            }
            annotationStructural = true;
        }
        if (modifierStructural && annotationStructural) {
            throw error(previous(), "duplicate structural callable modifier; use either 'structural' or '@structural', not both");
        }
        return new CallableStructural(modifierStructural || annotationStructural, List.copyOf(remaining));
    }

    /**
     * Fail closed until these declaration nodes preserve typed annotation metadata.
     * Silently dropping metadata can bypass compile-time macro, ABI and safety contracts.
     * Module, function, method and field annotations have AST storage and are unaffected.
     */
    private void rejectDiscardedDeclarationAnnotations(List<Ast.Annotation> annotations, String target) {
        if (!annotations.isEmpty()) {
            throw error(previous(), "@" + annotations.getFirst().name() + " on " + target
                    + " would be silently discarded; declaration annotation storage is not implemented");
        }
    }

    private void rejectCallableStructuralAnnotation(List<Ast.Annotation> annotations, String target) {
        for (Ast.Annotation annotation : annotations) {
            if (isCallableStructuralAnnotation(annotation)) {
                throw error(previous(), "@structural is compile-time callable metadata and is not valid on " + target);
            }
        }
    }

    private void consumeExpressionStatementTerminator(Ast.Expr expression, String message) {
        if (containsExpressionBodyLambda(expression)) {
            consume(SEMICOLON, "expression-bodied lambda statements require an explicit ';'");
            return;
        }
        consumeStatementTerminator(message);
    }

    private void consumeExpressionClassFieldTerminator(Ast.Expr expression, String message) {
        if (containsExpressionBodyLambda(expression)) {
            consume(SEMICOLON, "expression-bodied lambda statements require an explicit ';'");
            return;
        }
        consumeClassFieldTerminator(message);
    }

    private boolean containsExpressionBodyLambda(Ast.Expr expression) {
        if (expression == null) return false;
        if (expression instanceof Ast.LambdaExpr lambda) {
            return lambda.expressionBody() != null;
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            return containsExpressionBodyLambda(binary.left()) || containsExpressionBodyLambda(binary.right());
        }
        if (expression instanceof Ast.TrapExpr trapped) {
            // A call-site trap must never hide an expression-bodied lambda
            // from the required explicit statement/class-field terminator.
            return containsExpressionBodyLambda(trapped.expression());
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            return containsExpressionBodyLambda(unary.operand());
        }
        if (expression instanceof Ast.AssignExpr assign) {
            return containsExpressionBodyLambda(assign.target()) || containsExpressionBodyLambda(assign.value());
        }
        if (expression instanceof Ast.ConditionalExpr conditional) {
            return containsExpressionBodyLambda(conditional.condition())
                    || containsExpressionBodyLambda(conditional.whenTrue())
                    || containsExpressionBodyLambda(conditional.whenFalse());
        }
        if (expression instanceof Ast.TypeTestExpr typeTest) {
            return containsExpressionBodyLambda(typeTest.value());
        }
        if (expression instanceof Ast.PatternTestExpr patternTest) {
            return containsExpressionBodyLambda(patternTest.value());
        }
        if (expression instanceof Ast.CastExpr cast) {
            return containsExpressionBodyLambda(cast.value());
        }
        if (expression instanceof Ast.SpreadExpr spread) {
            return containsExpressionBodyLambda(spread.expression());
        }
        if (expression instanceof Ast.CallExpr call) {
            if (containsExpressionBodyLambda(call.callee())) return true;
            return call.arguments().stream().anyMatch(this::containsExpressionBodyLambda);
        }
        if (expression instanceof Ast.RuntimeCallExpr runtime) {
            return runtime.arguments().stream().anyMatch(this::containsExpressionBodyLambda);
        }
        if (expression instanceof Ast.MemberExpr member) {
            return containsExpressionBodyLambda(member.receiver());
        }
        if (expression instanceof Ast.IndexExpr index) {
            return containsExpressionBodyLambda(index.receiver()) || containsExpressionBodyLambda(index.index());
        }
        if (expression instanceof Ast.NewExpr created) {
            return created.arguments().stream().anyMatch(this::containsExpressionBodyLambda);
        }
        if (expression instanceof Ast.AwaitExpr awaited) {
            return containsExpressionBodyLambda(awaited.expression());
        }
        if (expression instanceof Ast.ChannelOpExpr channel) {
            return containsExpressionBodyLambda(channel.channel())
                    || containsExpressionBodyLambda(channel.value());
        }
        if (expression instanceof Ast.DynamicSelectExpr select) {
            return containsExpressionBodyLambda(select.cases());
        }
        if (expression instanceof Ast.ListExpr list) {
            return list.elements().stream().anyMatch(this::containsExpressionBodyLambda);
        }
        if (expression instanceof Ast.TupleExpr tuple) {
            return tuple.elements().stream().anyMatch(this::containsExpressionBodyLambda);
        }
        if (expression instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                if (containsExpressionBodyLambda(field.value())) return true;
            }
        }
        return false;
    }

    private void consumeStatementTerminator(String message) {
        if (match(SEMICOLON) || isSafeStatementBoundary() || isImplicitNewlineTerminator()) return;
        throw error(peek(), message);
    }

    private boolean isImplicitNewlineTerminator() {
        if (current == 0 || check(EOF)) return false;
        return previous().line() < peek().line();
    }

    private boolean isSafeStatementBoundary() {
        return check(RBRACE) || check(FI) || check(END) || check(ELSE) || check(ELSEIF)
                || check(CATCH) || check(FINALLY) || isBareDoneDelimiter() || check(EOF);
    }

    private Ast.BindingKind parseBindingKind() {
        if (match(CONST)) return Ast.BindingKind.CONST;
        if (match(VAL)) return Ast.BindingKind.VAL;
        if (match(LET)) return Ast.BindingKind.LET;
        throw error(peek(), "expected const, val, or let");
    }

    private boolean parseReferentMutability(Ast.BindingKind kind) {
        if (kind == Ast.BindingKind.VAL) {
            if (match(MUT)) {
                throw error(previous(), "'val' already means 'const mut'; 'val mut' is a duplicate mutability qualifier");
            }
            return true;
        }
        return match(MUT);
    }

    private Ast.BindingKind parseForOfBindingKind() {
        return parseBindingKind();
    }

    private void requireForOfBindingKind(Ast.BindingKind kind) {
        if (kind == null) throw error(peek(), "for-of binding kind is required");
    }

    private IllegalArgumentException forOfUndeclaredBindingError() {
        String subject = check(IDENT) ? " '" + peek().lexeme() + "'" : "";
        return error(peek(),
                "variable used before declared" + subject
                        + "; for-of bindings require an explicit const/val/let declaration");
    }

    private boolean isBindingKind(Token.Type type) { return type == CONST || type == VAL || type == LET; }

    private boolean matchContextualStructuralModifier() {
        if (!check(IDENT) || !peek().lexeme().equals("structural")) return false;
        // Preserve ordinary identifiers such as:
        //   structural(): int { ... }
        //   structural<T>(T value): T { ... }
        //   structural: int
        // The modifier spelling is only consumed when it is not already
        // syntactically the member/declaration name.
        if (checkNext(LPAREN) || checkNext(LT) || checkNext(COLON)) return false;
        advance();
        return true;
    }

    // 'suspend' is an alias of 'suspends' only in modifier position; preserve
    // instance methods actually named suspend(...).
    private boolean matchContextualSuspendModifier() {
        if (!isContextualIdentifier("suspend")) return false;
        if (!checkNext(IDENT) && !checkNext(FNC)) return false;
        advance();
        return true;
    }

    // Preserve an ordinary method named 'method' (method(...)) while accepting
    // the opt-in declaration marker 'method other_name(...)'.
    private boolean matchExplicitMethodMarker() {
        if (!isContextualIdentifier("method")) return false;
        if (!checkNext(IDENT) && !checkNext(LBRACKET) && !checkNext(STATIC)) return false;
        advance();
        return true;
    }

    private boolean isAdjacentCompoundKeyword(String first, String second) {
        if (current + 2 >= tokens.size()) return false;
        Token left = tokens.get(current);
        Token dash = tokens.get(current + 1);
        Token right = tokens.get(current + 2);
        return left.type() == IDENT && left.lexeme().equals(first)
                && dash.type() == MINUS && right.type() == IDENT
                && right.lexeme().equals(second)
                && adjacent(left, dash) && adjacent(dash, right);
    }

    private boolean matchContextualShared() {
        if (match(SHARED)) return true;
        if (check(IDENT) && peek().lexeme().equals("shared")) {
            advance();
            return true;
        }
        return false;
    }

    /**
     * Matches words that are keywords only in a specific grammar position.
     *
     * <p>{@code case} and {@code when} intentionally lex as IDENT so they remain
     * legal variable/parameter names. Grammar productions opt into their keyword
     * meaning explicitly at switch/select arms and match guards.</p>
     */
    private boolean matchContextualKeyword(String... keywords) {
        if (!check(IDENT)) return false;
        String lexeme = peek().lexeme();
        for (String keyword : keywords) {
            if (lexeme.equals(keyword)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private static boolean isIdentifierLikeToken(Token.Type type) {
        return type == IDENT || type == SHARED || type == UNTRUSTED;
    }

    private boolean match(Token.Type... types) {
        for (Token.Type type : types) {
            if (check(type)) { advance(); return true; }
        }
        return false;
    }

    private boolean matchAdjacentBangEqKeyword() {
        if (current + 1 >= tokens.size()) return false;
        Token bang = tokens.get(current);
        Token eq = tokens.get(current + 1);
        if (bang.type() != BANG || eq.type() != EQ || !adjacent(bang, eq)) return false;
        advance();
        advance();
        return true;
    }

    private Token consume(Token.Type type, String message) {
        if (check(type)) return advance();
        throw error(peek(), message);
    }

    private boolean check(Token.Type type) {
        return type == IDENT
                ? isIdentifierLikeToken(peek().type())
                : peek().type() == type;
    }
    private boolean checkNext(Token.Type type) { return current + 1 < tokens.size() && tokens.get(current + 1).type() == type; }

    private boolean adjacent(Token left, Token right) {
        return left.line() == right.line() && right.column() == left.column() + left.lexeme().length();
    }

    private boolean checkAdjacentPair(Token.Type type) {
        return current + 1 < tokens.size()
                && tokens.get(current).type() == type
                && tokens.get(current + 1).type() == type
                && adjacent(tokens.get(current), tokens.get(current + 1));
    }

    private boolean matchAdjacentPair(Token.Type type) {
        if (!checkAdjacentPair(type)) return false;
        advance();
        advance();
        return true;
    }

    private boolean matchAdjacentTriple(Token.Type type) {
        if (current + 2 >= tokens.size()) return false;
        Token first = tokens.get(current);
        Token second = tokens.get(current + 1);
        Token third = tokens.get(current + 2);
        if (first.type() != type || second.type() != type || third.type() != type
                || !adjacent(first, second) || !adjacent(second, third)) return false;
        advance();
        advance();
        advance();
        return true;
    }

    private boolean matchSingleOperator(Token.Type type) {
        if (!check(type) || checkAdjacentPair(type)) return false;
        advance();
        return true;
    }
    private boolean isLegacyFnSpelling() {
        return check(IDENT) && peek().lexeme().equals("fn");
    }

    private boolean checkNextLexeme(String lexeme) {
        return current + 1 < tokens.size() && tokens.get(current + 1).type() == IDENT
                && tokens.get(current + 1).lexeme().equals(lexeme);
    }
    private Token advance() { if (!check(EOF)) current++; return previous(); }
    private Token peek() { return tokens.get(current); }
    private Token previous() { return tokens.get(current - 1); }

    private IllegalArgumentException error(Token token, String message) {
        return new IllegalArgumentException("Oreslang parse error at " + token.line() + ":" + token.column() + ": " + message);
    }

    private record Modifiers(Ast.Visibility visibility, boolean visibilitySeen, boolean async, boolean suspends, boolean generator, boolean structural, boolean nonLexical, boolean trapped, boolean isStatic, boolean isAbstract, boolean shared, boolean untrusted) { }
}

