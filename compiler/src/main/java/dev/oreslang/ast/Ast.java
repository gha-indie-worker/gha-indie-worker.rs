package dev.oreslang.ast;

import java.util.List;
import java.util.HashSet;

public final class Ast {
    private Ast() { }

    /** Shared guest AST admission rule for closed structural field keys. */
    private static String requireStaticFieldName(String name) {
        if (name == null || name.length() > 1024) {
            throw new IllegalArgumentException("static field name is null or too long");
        }
        for (int i = 0; i < name.length(); i++) {
            char code = name.charAt(i);
            if (code == 0 || Character.isLowSurrogate(code)) {
                throw new IllegalArgumentException("static field name contains invalid Unicode");
            }
            if (Character.isHighSurrogate(code)) {
                if (++i >= name.length() || !Character.isLowSurrogate(name.charAt(i))) {
                    throw new IllegalArgumentException("static field name contains invalid Unicode");
                }
            }
        }
        return name;
    }

    public record Program(String namespace, List<ImportDecl> imports, List<ModuleDecl> modules) {
        public Program {
            imports = List.copyOf(imports);
            modules = List.copyOf(modules);
        }
        public Program(List<ImportDecl> imports, List<ModuleDecl> modules) { this(null, imports, modules); }
        public Program(List<ModuleDecl> modules) { this(null, List.of(), modules); }
    }

    public enum ImportKind { MODULE, ACTOR, CLASS, FUNCTION, INTERFACE, CONTRACT, TRAIT, STRUCT, TYPE, TYPES, ALL }

    public record ImportDecl(
            ImportKind kind,
            List<String> names,
            boolean wildcard,
            String namespace,
            String path) {
        public ImportDecl { names = List.copyOf(names); }
    }

    public record ModuleDecl(String name, List<Annotation> annotations, List<TypeRef> contracts, List<Decl> declarations) {
        public ModuleDecl {
            annotations = List.copyOf(annotations);
            contracts = List.copyOf(contracts);
            declarations = List.copyOf(declarations);
        }
        public ModuleDecl(String name, List<Decl> declarations) { this(name, List.of(), List.of(), declarations); }
        public ModuleDecl(String name, List<Annotation> annotations, List<Decl> declarations) { this(name, annotations, List.of(), declarations); }
    }

    public sealed interface Decl permits FunctionDecl, ClassDecl, InterfaceDecl, FieldDecl, TypeAliasDecl { }

    public enum Visibility { PRIVATE, PUBLIC }
    public enum CallableKind { FNC, ROUTINE }
    public enum ActorKind { NONE, PRIVATE, SHARED, UNTRUSTED }

    public record Annotation(String name, List<TypeRef> arguments) {
        public Annotation { arguments = List.copyOf(arguments); }
    }

    public record TypeRef(String name, List<TypeRef> arguments, boolean inferArguments) {
        public TypeRef {
            // Both source spellings denote one primitive throughout every compiler pass.
            if (name.equals("boolean")) name = "bool";
            arguments = List.copyOf(arguments);
        }
        public static TypeRef simple(String name) { return new TypeRef(name, List.of(), false); }
        /** A complement in type position. The token '!' is not a value operator here. */
        public static TypeRef complement(TypeRef excluded) {
            return excluded.isComplement()
                    ? excluded.arguments().getFirst()
                    : new TypeRef("$not$", List.of(excluded), false);
        }
        public boolean isComplement() { return name.equals("$not$"); }
        public TypeRef complementedType() {
            if (!isComplement() || arguments.size() != 1) {
                throw new IllegalStateException("malformed complement type");
            }
            return arguments.getFirst();
        }
        public static TypeRef inferred() { return new TypeRef("$infer$", List.of(), false); }
        public static TypeRef borrowed(TypeRef target, boolean mutable) {
            return new TypeRef(mutable ? "$borrow_mut$" : "$borrow$", List.of(target), false);
        }
        public boolean isBorrow() { return name.equals("$borrow$") || name.equals("$borrow_mut$"); }
        public boolean mutableBorrow() { return name.equals("$borrow_mut$"); }
        public TypeRef borrowedTarget() {
            if (!isBorrow() || arguments.size() != 1) throw new IllegalStateException("not a borrow type");
            return arguments.getFirst();
        }
        public static TypeRef functionType(List<TypeRef> parameters, TypeRef result) {
            java.util.ArrayList<TypeRef> all = new java.util.ArrayList<>(parameters);
            all.add(result);
            return new TypeRef("Fnc", all, false);
        }
        public static TypeRef stringLiteral(String value) { return new TypeRef("$string$" + value, List.of(), false); }
        public boolean isStringLiteral() { return name.startsWith("$string$"); }
        public String stringLiteralValue() { return name.substring("$string$".length()); }

        // Compiler-only TypeRef forms for named metadata and sequence shapes.
        // These synthetic names cannot collide with source identifiers.
        public static TypeRef intLiteral(String value) {
            return new TypeRef("$int$" + value.replace("_", ""), List.of(), false);
        }
        public boolean isIntLiteral() { return name.startsWith("$int$"); }
        public long intLiteralValue() {
            if (!isIntLiteral()) throw new IllegalStateException("not an integer meta literal");
            return Long.parseLong(name.substring("$int$".length()));
        }

        public static TypeRef boolLiteral(boolean value) {
            return new TypeRef(value ? "$bool$true" : "$bool$false", List.of(), false);
        }
        public boolean isBoolLiteral() { return name.equals("$bool$true") || name.equals("$bool$false"); }
        public boolean boolLiteralValue() {
            if (!isBoolLiteral()) throw new IllegalStateException("not a boolean meta literal");
            return name.equals("$bool$true");
        }

        public static TypeRef namedParameter(String parameterName, TypeRef value) {
            if (parameterName == null || parameterName.isBlank()) {
                throw new IllegalArgumentException("named parameter name cannot be blank");
            }
            return new TypeRef("$named$" + parameterName, List.of(value), false);
        }
        public boolean isNamedParameter() { return name.startsWith("$named$"); }
        public String namedParameterName() {
            if (!isNamedParameter()) throw new IllegalStateException("not a named parameter");
            return name.substring("$named$".length());
        }
        public TypeRef namedParameterValue() {
            if (!isNamedParameter() || arguments.size() != 1) {
                throw new IllegalStateException("malformed named parameter");
            }
            return arguments.getFirst();
        }

        public static TypeRef sequenceShape(List<TypeRef> patterns) {
            if (patterns.isEmpty()) throw new IllegalArgumentException("sequence shape cannot be empty");
            return new TypeRef("$shape$", List.copyOf(patterns), false);
        }
        public boolean isSequenceShape() { return name.equals("$shape$"); }

        public static TypeRef repeatExact(String count, TypeRef pattern) {
            long parsed = Long.parseLong(count.replace("_", ""));
            if (parsed < 0) throw new IllegalArgumentException("sequence repeat count cannot be negative");
            return new TypeRef("$repeat_exact$" + parsed, List.of(pattern), false);
        }
        public boolean isRepeatExact() { return name.startsWith("$repeat_exact$"); }
        public long repeatExactCount() {
            if (!isRepeatExact()) throw new IllegalStateException("not an exact sequence repeat");
            return Long.parseLong(name.substring("$repeat_exact$".length()));
        }

        public static TypeRef repeatMany(List<TypeRef> group) {
            if (group.isEmpty()) throw new IllegalArgumentException("repeating sequence group cannot be empty");
            return new TypeRef("$repeat_many$", List.copyOf(group), false);
        }
        public boolean isRepeatMany() { return name.equals("$repeat_many$"); }


        public static TypeRef union(List<TypeRef> options) {
            java.util.ArrayList<TypeRef> flattened = new java.util.ArrayList<>();
            for (TypeRef option : options) {
                if (option.isUnion()) {
                    for (TypeRef nested : option.arguments()) if (!flattened.contains(nested)) flattened.add(nested);
                } else if (!flattened.contains(option)) flattened.add(option);
            }
            if (flattened.isEmpty()) throw new IllegalArgumentException("union type requires at least one member");
            if (flattened.size() == 1) return flattened.getFirst();
            flattened.sort(java.util.Comparator.comparing(TypeRef::toString));
            return new TypeRef("$union$", List.copyOf(flattened), false);
        }
        public boolean isUnion() { return name.equals("$union$"); }

        public static TypeRef tupleType(List<TypeRef> elements) {
            return new TypeRef("$tuple$", List.copyOf(elements), false);
        }
        public boolean isTupleType() { return name.equals("$tuple$"); }

        private static TypeRef aggregateType(
                java.util.Map<String, TypeRef> members,
                String typeTag,
                String diagnosticName) {
            java.util.ArrayList<TypeRef> fields = new java.util.ArrayList<>(members.size());
            java.util.ArrayList<java.util.Map.Entry<String, TypeRef>> entries = new java.util.ArrayList<>(members.entrySet());
            // Reject invalid keys before sorting; even a malicious null key
            // must not trigger an uncontrolled comparison or enter a type ID.
            for (java.util.Map.Entry<String, TypeRef> entry : entries) {
                requireStaticFieldName(entry.getKey());
                java.util.Objects.requireNonNull(entry.getValue(), diagnosticName + " type field");
            }
            entries.sort(java.util.Map.Entry.comparingByKey());
            for (java.util.Map.Entry<String, TypeRef> entry : entries) {
                fields.add(new TypeRef("$field$" + entry.getKey(), List.of(entry.getValue()), false));
            }
            return new TypeRef(typeTag, List.copyOf(fields), false);
        }
        private static TypeRef recordType(java.util.Map<String, TypeRef> members, boolean readOnly) {
            return aggregateType(members, readOnly ? "$readonly_record$" : "$record$", "record");
        }
        public static TypeRef recordType(java.util.Map<String, TypeRef> members) {
            // Record values are structurally readonly; mutable fixed products
            // are represented by structType, never by a writable record shape.
            return recordType(members, true);
        }
        public static TypeRef readonlyRecordType(java.util.Map<String, TypeRef> members) {
            return recordType(members, true);
        }
        public static TypeRef structType(java.util.Map<String, TypeRef> members) {
            return aggregateType(members, "$struct$", "struct");
        }
        public boolean isRecordType() { return name.equals("$record$") || name.equals("$readonly_record$"); }
        public boolean isReadonlyRecordType() { return name.equals("$readonly_record$"); }
        public boolean isStructType() { return name.equals("$struct$"); }
        public boolean isClosedAggregateType() { return isRecordType() || isStructType(); }
        public java.util.Map<String, TypeRef> aggregateMembers() {
            if (!isClosedAggregateType()) throw new IllegalStateException("not a closed aggregate type");
            java.util.LinkedHashMap<String, TypeRef> members = new java.util.LinkedHashMap<>();
            for (TypeRef field : arguments) {
                if (!field.name().startsWith("$field$") || field.arguments().size() != 1 || field.inferArguments()) {
                    throw new IllegalStateException("malformed closed aggregate type field");
                }
                String fieldName = field.name().substring("$field$".length());
                if (members.putIfAbsent(fieldName, field.arguments().getFirst()) != null) {
                    throw new IllegalStateException("duplicate closed aggregate type field " + fieldName);
                }
            }
            return java.util.Collections.unmodifiableMap(members);
        }
        public java.util.Map<String, TypeRef> recordMembers() {
            if (!isRecordType()) throw new IllegalStateException("not a record type");
            return aggregateMembers();
        }
        public java.util.Map<String, TypeRef> structMembers() {
            if (!isStructType()) throw new IllegalStateException("not a struct type");
            return aggregateMembers();
        }
    }

    public record Param(TypeRef type, String name, boolean structural, boolean mutable) {
        public Param(TypeRef type, String name) { this(type, name, false, false); }
        public Param(TypeRef type, String name, boolean structural) { this(type, name, structural, false); }
    }

    /** A source generic name and its optional compile-time default type. */
    public record GenericParameter(String name, TypeRef defaultType) {
        public GenericParameter {
            if (name == null || name.isBlank() || name.equals("_") || name.startsWith("$")) {
                throw new IllegalArgumentException("invalid generic parameter name");
            }
        }
    }

    /** Immutable, category-distinct declarations; effects are may-sets, not handlers. */
    public record EffectSpec(List<TypeRef> throwsTypes, List<TypeRef> raisesTypes,
                             List<TypeRef> panicsTypes) {
        public EffectSpec {
            throwsTypes = List.copyOf(throwsTypes);
            raisesTypes = List.copyOf(raisesTypes);
            panicsTypes = List.copyOf(panicsTypes);
            if (new HashSet<>(throwsTypes).size() != throwsTypes.size()
                    || new HashSet<>(raisesTypes).size() != raisesTypes.size()
                    || new HashSet<>(panicsTypes).size() != panicsTypes.size()) {
                throw new IllegalArgumentException("duplicate declared checked effect type");
            }
        }

        public static EffectSpec none() {
            return new EffectSpec(List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return throwsTypes.isEmpty() && raisesTypes.isEmpty() && panicsTypes.isEmpty();
        }
    }

    public record FunctionDecl(
            String name,
            CallableKind kind,
            Visibility visibility,
            boolean async,
            boolean suspends,
            boolean generator,
            boolean structural,
            boolean nonLexical,
            boolean trapped,
            ActorKind actorKind,
            List<String> genericParameters,
            List<Param> parameters,
            TypeRef returnType,
            List<Annotation> annotations,
            List<Stmt> body,
            List<GenericParameter> genericSpecs,
            EffectSpec effects) implements Decl {
        /** Keep the existing generic-default-aware AST constructor ABI. */
        public FunctionDecl(String name, CallableKind kind, Visibility visibility, boolean async,
                            boolean suspends, boolean generator, boolean structural,
                            boolean nonLexical, boolean trapped, ActorKind actorKind,
                            List<String> genericParameters, List<Param> parameters,
                            TypeRef returnType, List<Annotation> annotations,
                            List<Stmt> body, List<GenericParameter> genericSpecs) {
            this(name, kind, visibility, async, suspends, generator, structural,
                    nonLexical, trapped, actorKind, genericParameters, parameters,
                    returnType, annotations, body, genericSpecs, EffectSpec.none());
        }

        /** Preserve the pre-default FunctionDecl constructor ABI. */
        public FunctionDecl(String name, CallableKind kind, Visibility visibility, boolean async,
                            boolean suspends, boolean generator, boolean structural,
                            boolean nonLexical, boolean trapped, ActorKind actorKind,
                            List<String> genericParameters, List<Param> parameters,
                            TypeRef returnType, List<Annotation> annotations,
                            List<Stmt> body) {
            this(name, kind, visibility, async, suspends, generator, structural,
                    nonLexical, trapped, actorKind, genericParameters, parameters,
                    returnType, annotations, body,
                    genericParameters.stream().map(n -> new GenericParameter(n, null)).toList());
        }

        /** Preserve all existing AST construction sites without an explicit effect. */
        public FunctionDecl(String name, CallableKind kind, Visibility visibility, boolean async,
                            boolean generator, boolean structural, boolean nonLexical, boolean trapped,
                            ActorKind actorKind, List<String> genericParameters,
                            List<Param> parameters, TypeRef returnType,
                            List<Annotation> annotations, List<Stmt> body) {
            this(name, kind, visibility, async, false, generator, structural, nonLexical,
                    trapped, actorKind, genericParameters, parameters, returnType, annotations, body);
        }

        public FunctionDecl {
            genericParameters = List.copyOf(genericParameters);
            genericSpecs = List.copyOf(genericSpecs);
            if (!genericSpecs.stream().map(GenericParameter::name).toList().equals(genericParameters)) {
                throw new IllegalArgumentException("generic parameter specifications must match their declared slots");
            }
            if (new HashSet<>(genericParameters).size() != genericParameters.size()) {
                throw new IllegalArgumentException("duplicate generic parameter names");
            }
            parameters = List.copyOf(parameters);
            annotations = List.copyOf(annotations);
            body = List.copyOf(body);
            java.util.Objects.requireNonNull(effects, "function checked effects");
        }

        public FunctionDecl(String name, CallableKind kind, Visibility visibility, boolean async,
                            boolean generator, boolean nonLexical, ActorKind actorKind,
                            List<String> genericParameters, List<Param> parameters,
                            TypeRef returnType, List<Annotation> annotations, List<Stmt> body) {
            this(name, kind, visibility, async, generator, false, nonLexical, false, actorKind,
                    genericParameters, parameters, returnType, annotations, body);
        }

        /** Compatibility constructor for callers that already carry structural metadata. */
        public FunctionDecl(String name, CallableKind kind, Visibility visibility, boolean async,
                            boolean generator, boolean structural, boolean nonLexical,
                            ActorKind actorKind, List<String> genericParameters,
                            List<Param> parameters, TypeRef returnType,
                            List<Annotation> annotations, List<Stmt> body) {
            this(name, kind, visibility, async, generator, structural, nonLexical, false, actorKind,
                    genericParameters, parameters, returnType, annotations, body);
        }

        /** Compatibility constructor for non-generator callables. */
        public FunctionDecl(String name, CallableKind kind, Visibility visibility, boolean async,
                            boolean nonLexical, ActorKind actorKind, List<String> genericParameters,
                            List<Param> parameters, TypeRef returnType, List<Annotation> annotations,
                            List<Stmt> body) {
            this(name, kind, visibility, async, false, false, nonLexical, false, actorKind,
                    genericParameters, parameters, returnType, annotations, body);
        }

        public FunctionDecl(String name, CallableKind kind, Visibility visibility, boolean async,
                            ActorKind actorKind, List<String> genericParameters, List<Param> parameters,
                            TypeRef returnType, List<Annotation> annotations, List<Stmt> body) {
            this(name, kind, visibility, async, false, false, false, false, actorKind,
                    genericParameters, parameters, returnType, annotations, body);
        }

        public FunctionDecl(String name, CallableKind kind, Visibility visibility, boolean async,
                            List<String> genericParameters, List<Param> parameters, TypeRef returnType,
                            List<Annotation> annotations, List<Stmt> body) {
            this(name, kind, visibility, async, false, false, false, false, ActorKind.NONE,
                    genericParameters, parameters, returnType, annotations, body);
        }

        public FunctionDecl(String name, Visibility visibility, boolean async, List<String> genericParameters,
                            List<Param> parameters, TypeRef returnType, List<Annotation> annotations, List<Stmt> body) {
            this(name, CallableKind.FNC, visibility, async, false, false, false, false, ActorKind.NONE,
                    genericParameters, parameters, returnType, annotations, body);
        }
    }

    /** Connection-oriented actors have two distinct admission dimensions. */
    public enum ActorTransport { GENERIC, HTTP, TCP, WEBSOCKET }

    /**
     * Declarative admission limits are VM policy, NOT mutable actor fields.
     * The legacy actor scheduler currently implements precisely (1,1).
     */
    public record ActorConcurrency(
            ActorTransport transport, int requestConcurrency, int messageConcurrency) {
        public ActorConcurrency {
            java.util.Objects.requireNonNull(transport, "actor transport");
            if (requestConcurrency < 1 || messageConcurrency < 1) {
                throw new IllegalArgumentException(
                        "actor concurrency limits must be positive");
            }
        }
        public static ActorConcurrency serial(ActorTransport transport) {
            return new ActorConcurrency(transport, 1, 1);
        }
    }

    public record ClassDecl(
            String name,
            Visibility visibility,
            boolean isAbstract,
            ActorKind actorKind,
            List<String> genericParameters,
            List<TypeRef> parents,
            List<TypeRef> interfaces,
            List<FieldDecl> fields,
            ConstructorDecl constructor,
            List<MethodDecl> methods,
            ActorConcurrency actorConcurrency) implements Decl {
        public ClassDecl {
            actorConcurrency = java.util.Objects.requireNonNull(actorConcurrency);
            if (actorKind == ActorKind.NONE && actorConcurrency.transport() != ActorTransport.GENERIC)
                throw new IllegalArgumentException("ordinary classes cannot carry actor transport policy");
            genericParameters = List.copyOf(genericParameters);
            parents = List.copyOf(parents);
            interfaces = List.copyOf(interfaces);
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
        }

        /** Compatibility constructor for all existing AST/JIT compiler clients. */
        public ClassDecl(String name, Visibility visibility, boolean isAbstract,
                         ActorKind actorKind, List<String> genericParameters,
                         List<TypeRef> parents, List<TypeRef> interfaces,
                         List<FieldDecl> fields, ConstructorDecl constructor,
                         List<MethodDecl> methods) {
            this(name, visibility, isAbstract, actorKind, genericParameters,
                    parents, interfaces, fields, constructor, methods,
                    ActorConcurrency.serial(ActorTransport.GENERIC));
        }

        /** Compatibility constructor for pre-constructor AST producers. */
        public ClassDecl(String name, boolean isAbstract, ActorKind actorKind,
                         List<String> genericParameters, List<TypeRef> parents,
                         List<TypeRef> interfaces, List<FieldDecl> fields,
                         List<MethodDecl> methods) {
            this(name, Visibility.PRIVATE, isAbstract, actorKind, genericParameters,
                    parents, interfaces, fields, null, methods);
        }

        public ClassDecl(String name, boolean isAbstract, List<String> genericParameters,
                         List<TypeRef> parents, List<TypeRef> interfaces,
                         List<FieldDecl> fields, List<MethodDecl> methods) {
            this(name, Visibility.PRIVATE, isAbstract, ActorKind.NONE, genericParameters,
                    parents, interfaces, fields, null, methods);
        }

        public ClassDecl(String name, boolean isAbstract, List<String> genericParameters,
                         List<FieldDecl> fields, List<MethodDecl> methods) {
            this(name, Visibility.PRIVATE, isAbstract, ActorKind.NONE, genericParameters,
                    List.of(), List.of(), fields, null, methods);
        }
    }

    public record ConstructorDecl(
            Visibility visibility,
            List<Param> parameters,
            List<Annotation> annotations,
            List<Stmt> body) {
        public ConstructorDecl {
            parameters = List.copyOf(parameters);
            annotations = List.copyOf(annotations);
            body = List.copyOf(body);
        }

        public int arity() { return parameters.size(); }
    }

    public sealed interface InterfaceMember permits InterfaceFunctionDecl, InterfaceFieldDecl { }

    public record InterfaceFunctionDecl(
            String name,
            boolean structural,
            List<String> genericParameters,
            List<Param> parameters,
            TypeRef returnType,
            EffectSpec effects) implements InterfaceMember {
        public InterfaceFunctionDecl {
            genericParameters = List.copyOf(genericParameters);
            parameters = List.copyOf(parameters);
            java.util.Objects.requireNonNull(effects, "interface checked effects");
        }

        public InterfaceFunctionDecl(String name, boolean structural,
                                     List<String> genericParameters,
                                     List<Param> parameters, TypeRef returnType) {
            this(name, structural, genericParameters, parameters, returnType, EffectSpec.none());
        }

        /** Compatibility constructor for nominal interface callable contracts. */
        public InterfaceFunctionDecl(
                String name,
                List<String> genericParameters,
                List<Param> parameters,
                TypeRef returnType) {
            this(name, false, genericParameters, parameters, returnType);
        }
    }

    public record InterfaceFieldDecl(
            String name,
            TypeRef type,
            BindingKind bindingKind,
            boolean mutableReferent) implements InterfaceMember {
        public InterfaceFieldDecl(String name, TypeRef type, BindingKind bindingKind) {
            this(name, type, bindingKind, bindingKind == BindingKind.VAL);
        }
        public InterfaceFieldDecl(String name, TypeRef type) {
            this(name, type, BindingKind.VAL, true);
        }
    }

    public record InterfaceDecl(
            String name,
            Visibility visibility,
            List<String> genericParameters,
            List<TypeRef> parents,
            List<InterfaceMember> members,
            boolean moduleContract) implements Decl {
        public InterfaceDecl {
            genericParameters = List.copyOf(genericParameters);
            parents = List.copyOf(parents);
            members = List.copyOf(members);
        }
        public InterfaceDecl(String name, List<String> genericParameters, List<InterfaceMember> members) {
            this(name, Visibility.PRIVATE, genericParameters, List.of(), members, false);
        }
        public InterfaceDecl(String name, Visibility visibility, List<String> genericParameters, List<TypeRef> parents, List<InterfaceMember> members) {
            this(name, visibility, genericParameters, parents, members, false);
        }
    }

    public record FieldDecl(
            String name,
            Visibility visibility,
            BindingKind bindingKind,
            TypeRef type,
            List<Annotation> annotations,
            Expr initializer,
            boolean mutableReferent) implements Decl {
        public FieldDecl { annotations = List.copyOf(annotations); }
        public FieldDecl(String name, Visibility visibility, BindingKind bindingKind, TypeRef type,
                         List<Annotation> annotations, Expr initializer) {
            this(name, visibility, bindingKind, type, annotations, initializer,
                    bindingKind == BindingKind.VAL);
        }
        public FieldDecl(String name, Visibility visibility, BindingKind bindingKind, TypeRef type, Expr initializer) {
            this(name, visibility, bindingKind, type, List.of(), initializer);
        }
    }

    public record MethodDecl(
            String name,
            Visibility visibility,
            boolean isStatic,
            boolean isAbstract,
            boolean async,
            boolean suspends,
            boolean structural,
            TypeRef explicitReceiverType,
            List<String> genericParameters,
            List<Param> parameters,
            TypeRef returnType,
            List<Annotation> annotations,
            List<Stmt> body,
            EffectSpec effects) {
        /** Existing methods retain empty effects until declared by source. */
        public MethodDecl(String name, Visibility visibility, boolean isStatic,
                          boolean isAbstract, boolean async, boolean suspends,
                          boolean structural, TypeRef explicitReceiverType,
                          List<String> genericParameters, List<Param> parameters,
                          TypeRef returnType, List<Annotation> annotations,
                          List<Stmt> body) {
            this(name, visibility, isStatic, isAbstract, async, suspends, structural,
                    explicitReceiverType, genericParameters, parameters,
                    returnType, annotations, body, EffectSpec.none());
        }

        /** Preserve all existing AST construction sites without an explicit effect. */
        public MethodDecl(String name, Visibility visibility, boolean isStatic,
                          boolean isAbstract, boolean async, boolean structural,
                          TypeRef explicitReceiverType, List<String> genericParameters,
                          List<Param> parameters, TypeRef returnType,
                          List<Annotation> annotations, List<Stmt> body) {
            this(name, visibility, isStatic, isAbstract, async, false, structural,
                    explicitReceiverType, genericParameters, parameters, returnType, annotations, body);
        }

        public MethodDecl {
            genericParameters = List.copyOf(genericParameters);
            parameters = List.copyOf(parameters);
            annotations = List.copyOf(annotations);
            body = List.copyOf(body);
            java.util.Objects.requireNonNull(effects, "method checked effects");
        }

        /** Compatibility constructor for methods that do not use declaration-level structural matching. */
        public MethodDecl(
                String name,
                Visibility visibility,
                boolean isStatic,
                boolean isAbstract,
                boolean async,
                TypeRef explicitReceiverType,
                List<String> genericParameters,
                List<Param> parameters,
                TypeRef returnType,
                List<Annotation> annotations,
                List<Stmt> body) {
            this(name, visibility, isStatic, isAbstract, async, false, explicitReceiverType,
                    genericParameters, parameters, returnType, annotations, body);
        }

        public int arity() { return parameters.size(); }

        /**
         * Overload identity for class callables is deliberately only name + arity.
         * Parameter types, parameter names, generic parameters, return types,
         * and the implicit/explicit self receiver never participate in overload selection.
         */
        public MethodArity overloadIdentity() { return new MethodArity(name, arity()); }

        public boolean matchesArity(String candidateName, int candidateArity) {
            return name.equals(candidateName) && arity() == candidateArity;
        }
    }

    public record MethodArity(String name, int arity) {
        public MethodArity {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("method name cannot be blank");
            if (arity < 0) throw new IllegalArgumentException("method arity cannot be negative");
        }
    }

    public record TypeAliasDecl(
            String name, Visibility visibility, List<String> genericParameters, TypeRef target) implements Decl {
        public TypeAliasDecl { genericParameters = List.copyOf(genericParameters); }

        /** Existing AST producers without an explicit visibility are private by default. */
        public TypeAliasDecl(String name, List<String> genericParameters, TypeRef target) {
            this(name, Visibility.PRIVATE, genericParameters, target);
        }
    }

    public enum BindingKind { CONST, VAL, LET }

    public sealed interface Stmt permits BindingStmt, DestructureStmt, ReturnStmt, YieldStmt, ExprStmt, DeferStmt,
            BlockStmt, TaskScopeStmt, UsingBorrowStmt, BreakStmt, ContinueStmt, IfStmt, MatchStmt, SwitchStmt, TryStmt,
            ForOfStmt, ForOfDestructureStmt, ForStmt, DoWhileStmt, LoopStmt, SelectStmt { }

    /**
     * Binding rebinding and mutable access to the referent are independent capabilities.
     * inferredTypeName is compiler-only metadata for the local type alias introduced
     * by `const T value = infer<T> struct {...}`, inferred records, or tuples.
     */
    public record BindingStmt(BindingKind kind, TypeRef declaredType, String name, Expr initializer,
                              boolean mutableReferent, String inferredTypeName) implements Stmt {
        public BindingStmt(BindingKind kind, TypeRef declaredType, String name, Expr initializer) {
            this(kind, declaredType, name, initializer, kind == BindingKind.VAL, null);
        }
        public BindingStmt(BindingKind kind, TypeRef declaredType, String name, Expr initializer,
                           boolean mutableReferent) {
            this(kind, declaredType, name, initializer, mutableReferent, null);
        }
        public BindingStmt {
            if (inferredTypeName != null) {
                if (kind != BindingKind.CONST || mutableReferent) {
                    throw new IllegalArgumentException("named inferred types require an immutable const binding");
                }
                if (declaredType == null || !declaredType.name().equals(inferredTypeName)
                        || !declaredType.arguments().isEmpty() || declaredType.inferArguments()) {
                    throw new IllegalArgumentException("named inferred type must match the declared binding type");
                }
                if (!(initializer instanceof ObjectExpr object && object.inferredAggregate())
                        && !(initializer instanceof TupleExpr)) {
                    throw new IllegalArgumentException("named inferred type requires infer struct, record or tuple literal");
                }
            }
        }
    }
    public record DestructureBinding(
            BindingKind kind,
            String name,
            boolean rest,
            boolean mutableReferent) {
        public DestructureBinding(BindingKind kind, String name, boolean rest) {
            this(kind, name, rest, kind == BindingKind.VAL);
        }
        public DestructureBinding(BindingKind kind, String name) {
            this(kind, name, false, kind == BindingKind.VAL);
        }

        public DestructureBinding {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("destructure binding name cannot be blank");
            }
            if (rest && "_".equals(name)) {
                throw new IllegalArgumentException("rest destructure binding cannot be a discard");
            }
        }

        public static DestructureBinding discard() {
            return new DestructureBinding(BindingKind.CONST, "_", false, false);
        }

        public boolean isDiscard() {
            return "_".equals(name);
        }
    }

    public enum DestructureKind { SEQUENCE, OBJECT }

    public record DestructureStmt(DestructureKind kind, List<DestructureBinding> bindings, Expr initializer) implements Stmt {
        public DestructureStmt {
            bindings = List.copyOf(bindings);
            validateRestBinding(bindings, "destructure");
        }
        public DestructureStmt(List<DestructureBinding> bindings, Expr initializer) {
            this(DestructureKind.SEQUENCE, bindings, initializer);
        }
    }

    public record ReturnStmt(Expr value) implements Stmt { }
    public record YieldStmt(Expr value, boolean delegated) implements Stmt {
        public YieldStmt(Expr value) {
            this(value, false);
        }

        public YieldStmt {
            if (value == null) throw new IllegalArgumentException("yield requires a value");
        }
    }
    public record ExprStmt(Expr expression) implements Stmt { }
    public record DeferStmt(Expr expression) implements Stmt { }

    public record BlockStmt(List<Stmt> body) implements Stmt {
        public BlockStmt { body = List.copyOf(body); }
    }

    /**
     * Lexical structured Task scope. Scope exit is an asynchronous
     * physical-quiescence barrier; children may not outlive this statement.
     * Borrowed captures remain separately gated by compiler-issued scoped
     * READ authority.
     */
    public record TaskScopeStmt(List<Stmt> body) implements Stmt {
        public TaskScopeStmt { body = List.copyOf(body); }
    }
    /**
     * Scoped transport/resource loan syntax. Keeping it as a distinct AST node
     * prevents a generic call/block rewrite from accidentally extending a
     * borrow lifetime across returns, exceptions or suspension.
     *
     * The execution and ownership admission gates remain CLOSED until
     * authenticated transport handles, loan fencing, and unwind cleanup are
     * implemented across all applicable backends.
     */
    public record UsingBorrowStmt(Expr resource, String binding, List<Stmt> body) implements Stmt {
        public UsingBorrowStmt {
            if (resource == null || binding == null || binding.isBlank() || body == null) {
                throw new IllegalArgumentException("using borrowed scope needs resource, alias and body");
            }
            body = List.copyOf(body);
        }
    }
    public record BreakStmt() implements Stmt { }
    public record ContinueStmt() implements Stmt { }

    public record IfBranch(Expr condition, List<Stmt> body) {
        public IfBranch { body = List.copyOf(body); }
    }

    public record IfStmt(List<IfBranch> branches, List<Stmt> elseBody) implements Stmt {
        public IfStmt {
            branches = List.copyOf(branches);
            elseBody = List.copyOf(elseBody);
        }
    }

    /**
     * Oreslang patterns are language-level values for static analysis and native lowering.
     * They deliberately do not encode JVM Class/instanceof semantics.
     */
    public sealed interface Pattern permits WildcardPattern, LiteralPattern, BindingPattern,
            TypePattern, StructuralPattern, TuplePattern, ConstructorPattern { }

    public record WildcardPattern() implements Pattern { }
    public record LiteralPattern(Object value) implements Pattern { }
    public record BindingPattern(String name) implements Pattern { }

    /** Nominal/runtime type pattern such as `Dog dog` or legacy `is Dog dog`. */
    public record TypePattern(TypeRef type, String binding) implements Pattern { }

    /**
     * Structural type pattern. The target is either an inline record shape
     * (`structural { name: String } value`) or a named interface/contract
     * whose public shape is matched without requiring nominal implementation.
     */
    public record StructuralPattern(TypeRef target, String binding) implements Pattern { }

    /** Fixed-arity recursive tuple pattern. Lists deliberately do not participate. */
    public record TuplePattern(List<Pattern> elements) implements Pattern {
        public TuplePattern {
            elements = List.copyOf(elements);
            if (elements.size() < 2) {
                throw new IllegalArgumentException("tuple patterns require at least two elements");
            }
        }
    }

    public record ConstructorPattern(String constructor, List<Pattern> arguments) implements Pattern {
        public ConstructorPattern { arguments = List.copyOf(arguments); }
    }

    public record MatchArm(Pattern pattern, Expr guard, List<Stmt> body) {
        public MatchArm { body = List.copyOf(body); }
    }

    /**
     * Match arms use source order: the first matching arm wins. The ordered
     * flag is retained for AST compatibility; canonical source syntax is
     * always ordered and the parser emits true.
     */
    public record MatchStmt(
            Expr subject, boolean ordered, List<MatchArm> arms, boolean procedural) implements Stmt {
        public MatchStmt { arms = List.copyOf(arms); }
        // All pre-existing statement-oriented match constructors retain their
        // source spelling, AST meaning, and runtime evaluation during migration.
        public MatchStmt(Expr subject, boolean ordered, List<MatchArm> arms) {
            this(subject, ordered, arms, false);
        }
    }

    public record SwitchCase(List<Expr> constants, List<Stmt> body) {
        public SwitchCase {
            constants = List.copyOf(constants);
            body = List.copyOf(body);
        }
    }

    public record SwitchStmt(Expr subject, List<SwitchCase> cases, List<Stmt> defaultBody) implements Stmt {
        public SwitchStmt {
            cases = List.copyOf(cases);
            defaultBody = List.copyOf(defaultBody);
        }
    }

    public record TryStmt(List<Stmt> body, String errorName, List<Stmt> catchBody, List<Stmt> finallyBody) implements Stmt {
        public TryStmt {
            body = List.copyOf(body);
            catchBody = List.copyOf(catchBody);
            finallyBody = List.copyOf(finallyBody);
        }
    }

    public record ForOfStmt(
            BindingKind bindingKind,
            String bindingName,
            Expr iterable,
            boolean asyncIteration,
            List<Stmt> body,
            boolean mutableReferent) implements Stmt {
        public ForOfStmt {
            body = List.copyOf(body);
            requireForOfBindingKind(bindingKind);
        }
        public ForOfStmt(BindingKind bindingKind, String bindingName, Expr iterable,
                         boolean asyncIteration, List<Stmt> body) {
            this(bindingKind, bindingName, iterable, asyncIteration, body,
                    bindingKind == BindingKind.VAL);
        }
        public ForOfStmt(BindingKind bindingKind, String bindingName, Expr iterable, List<Stmt> body) {
            this(bindingKind, bindingName, iterable, false, body,
                    bindingKind == BindingKind.VAL);
        }
    }

    public record ForOfDestructureStmt(
            List<DestructureBinding> bindings,
            Expr iterable,
            boolean asyncIteration,
            List<Stmt> body) implements Stmt {
        public ForOfDestructureStmt {
            bindings = List.copyOf(bindings);
            body = List.copyOf(body);
            if (bindings.isEmpty()) throw new IllegalArgumentException("for-of destructure pattern cannot be empty");
            java.util.Set<String> names = new java.util.HashSet<>();
            for (DestructureBinding binding : bindings) {
                if (binding.isDiscard()) continue;
                requireForOfBindingKind(binding.kind());
                if (!names.add(binding.name())) throw new IllegalArgumentException(
                        "duplicate binding '" + binding.name() + "' in for-of destructure pattern");
            }
            validateRestBinding(bindings, "for-of destructure");
        }
        public ForOfDestructureStmt(List<DestructureBinding> bindings, Expr iterable, List<Stmt> body) {
            this(bindings, iterable, false, body);
        }

    }

    private static void validateRestBinding(List<DestructureBinding> bindings, String context) {
        int restIndex = -1;
        for (int i = 0; i < bindings.size(); i++) {
            if (!bindings.get(i).rest()) continue;
            if (restIndex >= 0) throw new IllegalArgumentException(context + " may contain at most one rest binding");
            restIndex = i;
        }
        if (restIndex >= 0 && restIndex != bindings.size() - 1) {
            throw new IllegalArgumentException(context + " rest binding must be last");
        }
    }

    private static void requireForOfBindingKind(BindingKind kind) {
        if (kind == null) throw new IllegalArgumentException("for-of binding kind is required");
    }

    public record ForStmt(Stmt initializer, Expr condition, Expr update, List<Stmt> body) implements Stmt {
        public ForStmt { body = List.copyOf(body); }
    }

    public record DoWhileStmt(List<Stmt> body, Expr condition) implements Stmt {
        public DoWhileStmt {
            body = List.copyOf(body);
            if (condition == null) throw new IllegalArgumentException("do-while condition cannot be null");
        }
    }

    public record LoopStmt(List<Stmt> body) implements Stmt {
        public LoopStmt { body = List.copyOf(body); }
    }

    public enum ChannelOperation { READ, WRITE, AWAIT, DEFAULT }
    public enum WaitMode { BLOCKING, NONBLOCKING, IMMEDIATE }
    public enum SelectPolicy { FAIR, PRIORITY, RANDOM }

    public record SelectArm(
            ChannelOperation operation,
            Expr channel,
            Expr value,
            BindingKind bindingKind,
            String bindingName,
            List<Stmt> body,
            boolean mutableReferent,
            TypeRef bindingType) {
        public SelectArm {
            if (operation == ChannelOperation.DEFAULT && mutableReferent) {
                throw new IllegalArgumentException("default select arm cannot carry mutable binding");
            }
            if (bindingType != null && (bindingName == null || operation == ChannelOperation.DEFAULT || operation == ChannelOperation.WRITE)) {
                throw new IllegalArgumentException("typed select binding requires a readable arm and a name");
            }
            body = List.copyOf(body);
            if (operation == ChannelOperation.DEFAULT) {
                if (channel != null || value != null || bindingKind != null || bindingName != null) {
                    throw new IllegalArgumentException("default select arm cannot carry channel/value/binding metadata");
                }
            } else {
                if (channel == null) throw new IllegalArgumentException("channel select arm requires a channel");
                if ((operation == ChannelOperation.READ || operation == ChannelOperation.AWAIT) && value != null) {
                    throw new IllegalArgumentException("read/await select arm cannot carry a write value");
                }
                if (operation == ChannelOperation.WRITE && value == null) {
                    throw new IllegalArgumentException("write select arm requires a value");
                }
                if ((bindingKind == null) != (bindingName == null)) {
                    throw new IllegalArgumentException("select binding kind/name must appear together");
                }
                if (operation == ChannelOperation.WRITE && bindingName != null) {
                    throw new IllegalArgumentException("write select arm cannot bind a read value");
                }
            }
        }
        public SelectArm(ChannelOperation operation, Expr channel, Expr value,
                         BindingKind bindingKind, String bindingName, List<Stmt> body,
                         boolean mutableReferent) {
            this(operation, channel, value, bindingKind, bindingName, body, mutableReferent, null);
        }
        public SelectArm(ChannelOperation operation, Expr channel, Expr value,
                         BindingKind bindingKind, String bindingName, List<Stmt> body) {
            this(operation, channel, value, bindingKind, bindingName, body,
                    bindingKind == BindingKind.VAL, null);
        }
    }

    /**
     * Static select carries an explicitDo marker for procedural dispatch.
     * In blocking do-select, return/break/continue belong to the enclosing
     * callable/loop, just as in do-match/do-switch. Detached nb-select arms
     * retain their separate compatibility-only continuation control scope.
     */
    public record SelectStmt(
            WaitMode mode,
            SelectPolicy policy,
            List<SelectArm> arms,
            boolean explicitDo) implements Stmt {
        public SelectStmt(WaitMode mode, SelectPolicy policy, List<SelectArm> arms) {
            this(mode, policy, arms, false);
        }

        public SelectStmt {
            if (mode == null || policy == null) {
                throw new IllegalArgumentException("select mode and policy are required");
            }
            if (explicitDo && mode == WaitMode.IMMEDIATE) {
                throw new IllegalArgumentException(
                        "'do try select' is not supported; use 'try select' for an immediate probe");
            }
            arms = List.copyOf(arms);
            if (arms.isEmpty()) throw new IllegalArgumentException("select requires at least one arm");
            long defaults = arms.stream().filter(arm -> arm.operation() == ChannelOperation.DEFAULT).count();
            if (defaults > 1) throw new IllegalArgumentException("select permits at most one default arm");
        }
    }

    public sealed interface Expr permits LiteralExpr, NameExpr, BinaryExpr, UnaryExpr, AssignExpr, ConditionalExpr,
            MatchExpr, TypeTestExpr, PatternTestExpr, CastExpr, SpreadExpr,
            CallExpr, RuntimeCallExpr, MemberExpr, IndexExpr, NewExpr, AwaitExpr, TaskExpr, TrapExpr, ChannelOpExpr, DynamicSelectExpr,
            ListExpr, TupleExpr, ObjectExpr, LambdaExpr { }

    public record LiteralExpr(Object value) implements Expr { }
    public record Imaginary(double coefficient) { }
    public record NameExpr(String name) implements Expr { }
    public record BinaryExpr(String operator, Expr left, Expr right) implements Expr { }
    public record UnaryExpr(String operator, Expr operand) implements Expr { }
    public record AssignExpr(Expr target, Expr value) implements Expr { }
    public record ConditionalExpr(Expr condition, Expr whenTrue, Expr whenFalse) implements Expr { }

    /**
     * Expression-form match: each arm returns a value *to this expression*,
     * never to the surrounding callable. Distinct from procedural MatchStmt.
     *
     * The initial compiler admission accepts exactly one explicit return
     * expression per arm. More complex arm bodies need an explicit local
     * control-flow representation and must not be silently lowered.
     */
    public record MatchValueArm(Pattern pattern, Expr guard, Expr value) {
        public MatchValueArm {
            java.util.Objects.requireNonNull(pattern, "value match pattern");
            java.util.Objects.requireNonNull(value, "value match return expression");
        }
    }
    public record MatchExpr(Expr subject, List<MatchValueArm> arms, TypeRef resultType) implements Expr {
        public MatchExpr {
            java.util.Objects.requireNonNull(subject, "value match subject");
            arms = List.copyOf(arms);
            if (arms.isEmpty()) throw new IllegalArgumentException("value match requires arms");
        }
        /** Backwards-compatible inferred-result expression. */
        public MatchExpr(Expr subject, List<MatchValueArm> arms) {
            this(subject, arms, null);
        }
    }

    /** "value is Type [binding]" -- a nominal/refinement test, not general pattern matching. */
    public record TypeTestExpr(Expr value, TypeRef targetType, String binding) implements Expr { }

    /** "value matches Pattern" -- full pattern predicate, with bindings scoped by the enclosing condition. */
    public record PatternTestExpr(Expr value, Pattern pattern) implements Expr { }

    public enum CastMode { CHECKED, OPTIONAL }

    /** "value as Type" or "value as? Type". */
    public record CastExpr(Expr value, TypeRef targetType, CastMode mode) implements Expr { }

    /** Argument-list spread. The parser only constructs this inside call argument lists. */
    public record SpreadExpr(Expr expression) implements Expr { }

    /** Syntax provenance, not proof that asynchronous pipeline lowering exists. */
    public enum CallOrigin {
        DIRECT,
        /** The upstream value occupies argument zero: value |> stage(args). */
        VALUE_PIPE_ARGUMENT,
        /** Option/Result namespace combinators use the upstream as receiver. */
        VALUE_PIPE_RECEIVER
    }

    public record CallExpr(
            Expr callee,
            List<TypeRef> typeArguments,
            boolean typeArgumentsPresent,
            List<Expr> arguments,
            CallOrigin origin) implements Expr {
        public CallExpr {
            java.util.Objects.requireNonNull(callee, "call callee");
            typeArguments = List.copyOf(typeArguments);
            arguments = List.copyOf(arguments);
            java.util.Objects.requireNonNull(origin, "call origin");
            if (origin == CallOrigin.VALUE_PIPE_ARGUMENT && arguments.isEmpty()) {
                throw new IllegalArgumentException("value pipeline requires an upstream argument");
            }
            if (origin == CallOrigin.VALUE_PIPE_RECEIVER && !(callee instanceof MemberExpr)) {
                throw new IllegalArgumentException("receiver pipeline requires a member callee");
            }
            if (!typeArgumentsPresent && !typeArguments.isEmpty()) {
                throw new IllegalArgumentException("call type arguments require an explicit <...> marker");
            }
        }
        public CallExpr(Expr callee, List<TypeRef> typeArguments,
                        boolean typeArgumentsPresent, List<Expr> arguments) {
            this(callee, typeArguments, typeArgumentsPresent, arguments, CallOrigin.DIRECT);
        }
        public CallExpr(Expr callee, List<Expr> arguments) {
            this(callee, List.of(), false, arguments);
        }
        public CallExpr(Expr callee, List<TypeRef> typeArguments, List<Expr> arguments) {
            this(callee, typeArguments, true, arguments);
        }

        /**
         * Rebuild a call without erasing syntax origin. The replacement must
         * be checked again: identity-bound type/ownership proofs do not follow
         * arbitrary changes to its callee or arguments.
         */
        public CallExpr rewrite(Expr newCallee, List<TypeRef> newTypeArguments,
                                boolean newTypeArgumentsPresent, List<Expr> newArguments) {
            return new CallExpr(newCallee, newTypeArguments,
                    newTypeArgumentsPresent, newArguments, origin);
        }
    }

    /** Compiler-owned runtime namespace operation. The rt prefix cannot be rebound or shadowed. */
    public record RuntimeCallExpr(String operation, List<Expr> arguments) implements Expr {
        public RuntimeCallExpr {
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("runtime operation cannot be blank");
            }
            arguments = List.copyOf(arguments);
        }
    }

    /**
     * Compiler-verified static Option depth attached to an AST call by
     * identity, never by structurally-equal source text. Weak keys prevent
     * cached type metadata from retaining abandoned compilation units.
     * Runtime reads fail closed for ASTs that never passed type checking.
     */
    public static final class CheckedOptionDepth {
        private CheckedOptionDepth() { }

        private static final java.lang.ref.ReferenceQueue<CallExpr> CLEARED =
                new java.lang.ref.ReferenceQueue<>();
        private static final java.util.Map<IdentityKey, Integer> DEPTHS =
                new java.util.HashMap<>();

        private static final class IdentityKey extends java.lang.ref.WeakReference<CallExpr> {
            private final int hash;
            private IdentityKey(
                    CallExpr call, java.lang.ref.ReferenceQueue<CallExpr> queue) {
                super(call, queue);
                hash = System.identityHashCode(call);
            }
            @Override public int hashCode() { return hash; }
            @Override public boolean equals(Object other) {
                if (this == other) return true;
                if (!(other instanceof IdentityKey key)) return false;
                CallExpr value = get();
                return value != null && value == key.get();
            }
        }

        private static void expunge() {
            java.lang.ref.Reference<? extends CallExpr> collected;
            while ((collected = CLEARED.poll()) != null) {
                DEPTHS.remove(collected);
            }
        }

        public static synchronized void record(CallExpr call, int depth) {
            java.util.Objects.requireNonNull(call, "call");
            if (depth < 1) throw new IllegalArgumentException("Option depth must be positive");
            expunge();
            IdentityKey key = new IdentityKey(call, CLEARED);
            Integer old = DEPTHS.putIfAbsent(key, depth);
            if (old != null && old != depth) {
                throw new IllegalStateException(
                        "same Option call site received inconsistent checked depths");
            }
        }

        public static synchronized int require(CallExpr call) {
            java.util.Objects.requireNonNull(call, "call");
            expunge();
            Integer depth = DEPTHS.get(new IdentityKey(call, null));
            if (depth == null) {
                throw new IllegalStateException(
                        "Option.unwrap_all requires a statically checked call site");
            }
            return depth;
        }

        public static synchronized void copy(CallExpr source, CallExpr target) {
            java.util.Objects.requireNonNull(source, "source checked Option call");
            java.util.Objects.requireNonNull(target, "target checked Option call");
            expunge();
            Integer depth = DEPTHS.get(new IdentityKey(source, null));
            if (depth != null) record(target, depth);
        }
    }

    /**
     * Compiler-verified static ordinary-class domain for a computed-index
     * receiver. The key is the exact receiver AST node admitted by the type
     * checker, so runtime never has to rediscover a class view from syntax or
     * from the more-derived runtime object.
     *
     * Weak identity keys avoid retaining abandoned compilation units. A
     * structurally equal expression from another parse is intentionally not a
     * match.
     */
    public static final class CheckedClassView {
        private CheckedClassView() { }

        private static final java.lang.ref.ReferenceQueue<Expr> CLEARED =
                new java.lang.ref.ReferenceQueue<>();
        private static final java.util.Map<IdentityKey, String> VIEWS =
                new java.util.HashMap<>();

        private static final class IdentityKey extends java.lang.ref.WeakReference<Expr> {
            private final int hash;
            private IdentityKey(
                    Expr expression, java.lang.ref.ReferenceQueue<Expr> queue) {
                super(expression, queue);
                hash = System.identityHashCode(expression);
            }
            @Override public int hashCode() { return hash; }
            @Override public boolean equals(Object other) {
                if (this == other) return true;
                if (!(other instanceof IdentityKey key)) return false;
                Expr value = get();
                return value != null && value == key.get();
            }
        }

        private static void expunge() {
            java.lang.ref.Reference<? extends Expr> collected;
            while ((collected = CLEARED.poll()) != null) {
                VIEWS.remove(collected);
            }
        }

        public static synchronized void record(Expr receiver, String className) {
            java.util.Objects.requireNonNull(receiver, "computed-index receiver");
            java.util.Objects.requireNonNull(className, "checked class name");
            if (className.isBlank()) {
                throw new IllegalArgumentException("checked class name cannot be blank");
            }
            expunge();
            IdentityKey key = new IdentityKey(receiver, CLEARED);
            String old = VIEWS.putIfAbsent(key, className);
            if (old != null && !old.equals(className)) {
                throw new IllegalStateException(
                        "same computed-index receiver received inconsistent static class views");
            }
        }

        public static synchronized String lookup(Expr receiver) {
            java.util.Objects.requireNonNull(receiver, "computed-index receiver");
            expunge();
            return VIEWS.get(new IdentityKey(receiver, null));
        }

        public static void copy(Expr source, Expr target) {
            java.util.Objects.requireNonNull(source, "source computed-index receiver");
            java.util.Objects.requireNonNull(target, "target computed-index receiver");
            String className = lookup(source);
            if (className != null) record(target, className);
        }
    }

    /**
     * Compiler-verified field-name domain for a structural contract receiver.
     * Runtime values satisfying a structural view may contain additional
     * fields; computed access must remain bounded to the statically visible
     * contract instead of exposing those extra fields.
     */
    public static final class CheckedStructuralFieldDomain {
        private CheckedStructuralFieldDomain() { }

        private static final java.lang.ref.ReferenceQueue<Expr> CLEARED =
                new java.lang.ref.ReferenceQueue<>();
        private static final java.util.Map<IdentityKey, java.util.Set<String>> DOMAINS =
                new java.util.HashMap<>();

        private static final class IdentityKey extends java.lang.ref.WeakReference<Expr> {
            private final int hash;
            private IdentityKey(
                    Expr expression, java.lang.ref.ReferenceQueue<Expr> queue) {
                super(expression, queue);
                hash = System.identityHashCode(expression);
            }
            @Override public int hashCode() { return hash; }
            @Override public boolean equals(Object other) {
                if (this == other) return true;
                if (!(other instanceof IdentityKey key)) return false;
                Expr value = get();
                return value != null && value == key.get();
            }
        }

        private static void expunge() {
            java.lang.ref.Reference<? extends Expr> collected;
            while ((collected = CLEARED.poll()) != null) {
                DOMAINS.remove(collected);
            }
        }

        public static synchronized void record(
                Expr receiver, java.util.Collection<String> fieldNames) {
            java.util.Objects.requireNonNull(receiver, "structural computed-index receiver");
            java.util.Objects.requireNonNull(fieldNames, "checked structural field domain");
            java.util.LinkedHashSet<String> checked = new java.util.LinkedHashSet<>();
            for (String fieldName : fieldNames) {
                if (fieldName == null || fieldName.isBlank()) {
                    throw new IllegalArgumentException(
                            "checked structural field names must be non-blank");
                }
                if (!checked.add(fieldName)) {
                    throw new IllegalArgumentException(
                            "checked structural field domain contains duplicate '" + fieldName + "'");
                }
            }
            java.util.Set<String> domain = java.util.Set.copyOf(checked);
            expunge();
            IdentityKey key = new IdentityKey(receiver, CLEARED);
            java.util.Set<String> old = DOMAINS.putIfAbsent(key, domain);
            if (old != null && !old.equals(domain)) {
                throw new IllegalStateException(
                        "same computed-index receiver received inconsistent structural field domains");
            }
        }

        public static synchronized java.util.Set<String> lookup(Expr receiver) {
            java.util.Objects.requireNonNull(receiver, "structural computed-index receiver");
            expunge();
            return DOMAINS.get(new IdentityKey(receiver, null));
        }

        public static void copy(Expr source, Expr target) {
            java.util.Objects.requireNonNull(source, "source structural computed-index receiver");
            java.util.Objects.requireNonNull(target, "target structural computed-index receiver");
            java.util.Set<String> domain = lookup(source);
            if (domain != null) record(target, domain);
        }
    }

    /**
     * Central preservation policy for checker-owned, identity-bound AST metadata.
     *
     * <p>Optimizers that replace an expression must use this method instead of
     * copying individual side tables. Metadata whose node shape is incompatible
     * with the replacement is deliberately not transferred, so authorization
     * fails closed rather than being projected onto an unrelated AST kind.
     */
    private enum CheckedMetadataTransfer {
        OPTION_DEPTH("CheckedOptionDepth") {
            @Override void copy(Expr source, Expr target) {
                if (source instanceof CallExpr sourceCall
                        && target instanceof CallExpr targetCall) {
                    CheckedOptionDepth.copy(sourceCall, targetCall);
                }
            }
        },
        CLASS_VIEW("CheckedClassView") {
            @Override void copy(Expr source, Expr target) {
                CheckedClassView.copy(source, target);
            }
        },
        STRUCTURAL_FIELD_DOMAIN("CheckedStructuralFieldDomain") {
            @Override void copy(Expr source, Expr target) {
                CheckedStructuralFieldDomain.copy(source, target);
            }
        };

        private final String tableName;

        CheckedMetadataTransfer(String tableName) {
            this.tableName = tableName;
        }

        abstract void copy(Expr source, Expr target);
    }

    public static void copyCheckedMetadata(Expr source, Expr target) {
        java.util.Objects.requireNonNull(source, "source expression");
        java.util.Objects.requireNonNull(target, "target expression");
        if (source instanceof CallExpr sourceCall && target instanceof CallExpr targetCall
                && sourceCall.origin() != targetCall.origin()) {
            throw new IllegalArgumentException(
                    "call rewrites must preserve syntax origin; use CallExpr.rewrite");
        }
        for (CheckedMetadataTransfer transfer : CheckedMetadataTransfer.values()) {
            transfer.copy(source, target);
        }
    }

    static java.util.Set<String> checkedMetadataTransferPolicy() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (CheckedMetadataTransfer transfer : CheckedMetadataTransfer.values()) {
            if (!names.add(transfer.tableName)) {
                throw new IllegalStateException(
                        "duplicate checked metadata transfer table: " + transfer.tableName);
            }
        }
        return java.util.Set.copyOf(names);
    }

    public record MemberExpr(Expr receiver, String member) implements Expr { }
    public record IndexExpr(Expr receiver, Expr index) implements Expr { }

    public record NewExpr(TypeRef type, List<Expr> arguments) implements Expr {
        public NewExpr { arguments = List.copyOf(arguments); }
    }

    public record AwaitExpr(Expr expression) implements Expr { }

    /** CPU/GPU task expression. Legacy nb spelling is normalized here for diagnostics/formatting only. */
    public enum ExecutionDomain { CPU, GPU }
    public enum TaskCaptureTransfer { COPY, MOVE }
    public record TaskExpr(ExecutionDomain domain, List<Stmt> body, boolean legacyNb) implements Expr {
        public TaskExpr {
            java.util.Objects.requireNonNull(domain, "task execution domain");
            body = List.copyOf(body);
        }
        public TaskExpr(ExecutionDomain domain, List<Stmt> body) {
            this(domain, body, false);
        }
    }

    /**
     * Ownership-checker admission for the exact TaskExpr that may execute.
     *
     * <p>The capture set is published only after the complete ownership pass
     * succeeds. Runtime consumers must require this identity-bound proof
     * instead of re-deriving authority from source spelling. It is
     * intentionally absent from {@link #copyCheckedMetadata(Expr, Expr)}:
     * optimizer/rewrite clones must be rechecked rather than inheriting Task
     * execution authority from a structurally similar node.
     */
    public record TaskCaptureAdmission(
            long checkEpoch,
            ExecutionDomain domain,
            java.util.Map<String, TaskCaptureTransfer> captureTransfers) {
        public TaskCaptureAdmission {
            if (checkEpoch <= 0) {
                throw new IllegalArgumentException(
                        "Task capture check epoch must be positive");
            }
            java.util.Objects.requireNonNull(
                    domain, "Task capture execution domain");
            java.util.Objects.requireNonNull(
                    captureTransfers, "Task capture transfers");
            captureTransfers = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(captureTransfers));
        }
    }

    /**
     * Identity-bound ownership admission for executable TaskExpr nodes.
     *
     * <p>Authority is keyed by both the exact checked Program and exact
     * TaskExpr. A monotonic check epoch distinguishes successful rechecks of
     * the same immutable AST under different linked/imported ownership
     * metadata. Evaluators capture an epoch at construction and must keep
     * using that exact epoch.
     */
    public static final class CheckedTaskCaptures {
        private CheckedTaskCaptures() { }

        private static final java.util.concurrent.atomic.AtomicLong NEXT_CHECK_EPOCH =
                new java.util.concurrent.atomic.AtomicLong();
        private static final java.lang.ref.ReferenceQueue<TaskExpr> CLEARED_TASKS =
                new java.lang.ref.ReferenceQueue<>();
        private static final java.lang.ref.ReferenceQueue<Program> CLEARED_PROGRAMS =
                new java.lang.ref.ReferenceQueue<>();

        private static final java.util.Map<PairIdentityKey, TaskCaptureAdmission> CAPTURES =
                new java.util.HashMap<>();
        private static final java.util.Map<ProgramIdentityKey, Long> PROGRAM_EPOCHS =
                new java.util.HashMap<>();

        private static final class PairIdentityKey
                extends java.lang.ref.WeakReference<TaskExpr> {
            private final java.lang.ref.WeakReference<Program> program;
            private final int hash;

            private PairIdentityKey(
                    Program program,
                    TaskExpr task,
                    java.lang.ref.ReferenceQueue<TaskExpr> queue) {
                super(task, queue);
                this.program = new java.lang.ref.WeakReference<>(
                        java.util.Objects.requireNonNull(
                                program, "checked Task program"));
                java.util.Objects.requireNonNull(task, "checked Task expression");
                hash = 31 * System.identityHashCode(program)
                        + System.identityHashCode(task);
            }

            private Program program() {
                return program.get();
            }

            @Override public int hashCode() { return hash; }

            @Override public boolean equals(Object other) {
                if (this == other) return true;
                if (!(other instanceof PairIdentityKey key)) return false;
                Program leftProgram = program();
                Program rightProgram = key.program();
                TaskExpr leftTask = get();
                TaskExpr rightTask = key.get();
                return leftProgram != null
                        && leftProgram == rightProgram
                        && leftTask != null
                        && leftTask == rightTask;
            }
        }

        private static final class ProgramIdentityKey
                extends java.lang.ref.WeakReference<Program> {
            private final int hash;

            private ProgramIdentityKey(
                    Program program,
                    java.lang.ref.ReferenceQueue<Program> queue) {
                super(java.util.Objects.requireNonNull(
                        program, "checked Task program"), queue);
                hash = System.identityHashCode(program);
            }

            @Override public int hashCode() { return hash; }

            @Override public boolean equals(Object other) {
                if (this == other) return true;
                if (!(other instanceof ProgramIdentityKey key)) return false;
                Program value = get();
                return value != null && value == key.get();
            }
        }

        private static void expunge() {
            java.lang.ref.Reference<? extends TaskExpr> clearedTask;
            while ((clearedTask = CLEARED_TASKS.poll()) != null) {
                CAPTURES.remove(clearedTask);
            }
            java.lang.ref.Reference<? extends Program> clearedProgram;
            while ((clearedProgram = CLEARED_PROGRAMS.poll()) != null) {
                PROGRAM_EPOCHS.remove(clearedProgram);
            }
            // Pair keys are queued by TaskExpr lifetime. A deliberately shared
            // TaskExpr can outlive one Program, so opportunistically retire
            // pair entries whose weak Program side has cleared.
            CAPTURES.keySet().removeIf(key ->
                    key.program() == null || key.get() == null);
        }

        public static long newCheckEpoch() {
            long epoch = NEXT_CHECK_EPOCH.incrementAndGet();
            if (epoch <= 0) {
                throw new IllegalStateException(
                        "Task ownership check epoch space exhausted");
            }
            return epoch;
        }

        /**
         * A new ownership check revokes every older Task execution proof for
         * the same exact Program before validation begins. Failed rechecks
         * must not leave previously admitted Tasks executable. The negative
         * epoch records an in-progress attempt and prevents a slower,
         * superseded concurrent checker from publishing stale authority.
         */
        public static synchronized void beginCheck(
                Program program, long checkEpoch) {
            java.util.Objects.requireNonNull(program, "Task program");
            if (checkEpoch <= 0) {
                throw new IllegalArgumentException(
                        "Task ownership check epoch must be positive");
            }
            expunge();
            Long existing = PROGRAM_EPOCHS.get(
                    new ProgramIdentityKey(program, null));
            if (existing != null
                    && Math.abs(existing) >= checkEpoch) {
                throw new IllegalStateException(
                        "Task ownership check epoch is not newer than the latest attempt");
            }
            CAPTURES.keySet().removeIf(key -> key.program() == program);
            PROGRAM_EPOCHS.put(
                    new ProgramIdentityKey(program, CLEARED_PROGRAMS),
                    -checkEpoch);
        }

        public static synchronized void publish(
                Program program,
                long checkEpoch,
                java.util.Map<
                        TaskExpr,
                        java.util.Map<String, TaskCaptureTransfer>> staged) {
            java.util.Objects.requireNonNull(program, "checked Task program");
            java.util.Objects.requireNonNull(staged, "staged Task admissions");
            if (checkEpoch <= 0) {
                throw new IllegalArgumentException(
                        "Task ownership check epoch must be positive");
            }

            java.util.IdentityHashMap<TaskExpr, TaskCaptureAdmission> normalized =
                    new java.util.IdentityHashMap<>();
            for (java.util.Map.Entry<
                    TaskExpr,
                    java.util.Map<String, TaskCaptureTransfer>> entry
                    : staged.entrySet()) {
                TaskExpr task = java.util.Objects.requireNonNull(
                        entry.getKey(), "checked Task expression");
                java.util.Map<String, TaskCaptureTransfer> transfers =
                        validateTransfers(entry.getValue());
                // An admission may name only free variables actually used
                // by this exact Task. Extra staged captures would otherwise
                // grant MOVE/COPY authority to unrelated parent bindings.
                java.util.Set<String> referenced =
                        TaskCaptureNames.referencedIn(task.body());
                if (!referenced.containsAll(transfers.keySet())) {
                    throw new IllegalArgumentException(
                            "Task capture admission includes unreferenced bindings");
                }
                normalized.put(
                        task,
                        new TaskCaptureAdmission(
                                checkEpoch,
                                task.domain(),
                                transfers));
            }

            expunge();
            Long currentAttempt = PROGRAM_EPOCHS.get(
                    new ProgramIdentityKey(program, null));
            if (currentAttempt == null
                    || currentAttempt.longValue() != -checkEpoch) {
                throw new IllegalStateException(
                        "Task ownership check was superseded before publication");
            }

            // Only the current successful ownership attempt can publish
            // execution authority for this exact Program identity.
            CAPTURES.keySet().removeIf(key -> key.program() == program);
            for (java.util.Map.Entry<TaskExpr, TaskCaptureAdmission> entry
                    : normalized.entrySet()) {
                CAPTURES.put(
                        new PairIdentityKey(
                                program, entry.getKey(), CLEARED_TASKS),
                        entry.getValue());
            }
            PROGRAM_EPOCHS.put(
                    new ProgramIdentityKey(program, CLEARED_PROGRAMS),
                    checkEpoch);
        }

        private static java.util.Map<String, TaskCaptureTransfer> validateTransfers(
                java.util.Map<String, TaskCaptureTransfer> captureTransfers) {
            java.util.Objects.requireNonNull(
                    captureTransfers, "checked Task capture transfers");
            java.util.LinkedHashMap<String, TaskCaptureTransfer> checked =
                    new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, TaskCaptureTransfer> entry
                    : captureTransfers.entrySet()) {
                String name = entry.getKey();
                TaskCaptureTransfer transfer = entry.getValue();
                if (name == null || name.isBlank() || transfer == null) {
                    throw new IllegalArgumentException(
                            "checked Task capture admission requires non-blank names and transfer modes");
                }
                if (checked.putIfAbsent(name, transfer) != null) {
                    throw new IllegalArgumentException(
                            "checked Task capture set contains duplicate '"
                                    + name + "'");
                }
            }
            return java.util.Collections.unmodifiableMap(checked);
        }

        public static synchronized long requireProgramEpoch(Program program) {
            java.util.Objects.requireNonNull(program, "Task program");
            expunge();
            Long epoch = PROGRAM_EPOCHS.get(
                    new ProgramIdentityKey(program, null));
            if (epoch == null || epoch <= 0) {
                throw new IllegalStateException(
                        "Task execution requires the latest successfully ownership-checked Program");
            }
            return epoch;
        }

        public static synchronized TaskCaptureAdmission require(
                Program program,
                TaskExpr task,
                long expectedCheckEpoch) {
            java.util.Objects.requireNonNull(program, "Task program");
            java.util.Objects.requireNonNull(task, "Task expression");
            if (expectedCheckEpoch <= 0) {
                throw new IllegalArgumentException(
                        "expected Task ownership check epoch must be positive");
            }
            expunge();

            Long currentProgramEpoch = PROGRAM_EPOCHS.get(
                    new ProgramIdentityKey(program, null));
            if (currentProgramEpoch == null
                    || currentProgramEpoch.longValue() != expectedCheckEpoch) {
                throw new IllegalStateException(
                        "Task Program ownership admission is stale or was superseded by a recheck");
            }

            TaskCaptureAdmission admission = CAPTURES.get(
                    new PairIdentityKey(program, task, null));
            if (admission == null
                    || admission.checkEpoch() != expectedCheckEpoch
                    || admission.domain() != task.domain()) {
                throw new IllegalStateException(
                        "Task execution requires the exact Program/TaskExpr ownership admission; "
                                + "reparsed, cloned, unchecked, or stale Tasks are not executable");
            }
            return admission;
        }
    }

    /** Evaluate one expression into Option<T>; only ordinary guest failures become None. */
    public record TrapExpr(Expr expression) implements Expr { }

    public record ChannelOpExpr(
            ChannelOperation operation,
            WaitMode mode,
            Expr channel,
            Expr value,
            boolean callback,
            List<Stmt> callbackBody) implements Expr {
        public ChannelOpExpr {
            if (operation != ChannelOperation.READ && operation != ChannelOperation.WRITE) {
                throw new IllegalArgumentException("only readch/writech are standalone channel operations");
            }
            if (channel == null) throw new IllegalArgumentException("channel operation requires a channel");
            if (operation == ChannelOperation.READ && value != null) {
                throw new IllegalArgumentException("readch cannot carry a write value");
            }
            if (operation == ChannelOperation.WRITE && value == null) {
                throw new IllegalArgumentException("writech requires a value");
            }
            callbackBody = List.copyOf(callbackBody == null ? List.of() : callbackBody);
            if (callback && (operation != ChannelOperation.WRITE || mode != WaitMode.NONBLOCKING)) {
                throw new IllegalArgumentException(
                        "channel callbacks are only valid for nonblocking writech");
            }
            if (!callback && !callbackBody.isEmpty()) {
                throw new IllegalArgumentException(
                        "non-callback channel operations cannot carry a callback body");
            }
        }

        public ChannelOpExpr(
                ChannelOperation operation,
                WaitMode mode,
                Expr channel,
                Expr value) {
            this(operation, mode, channel, value, false, List.of());
        }

        public ChannelOpExpr(
                ChannelOperation operation,
                WaitMode mode,
                Expr channel,
                Expr value,
                List<Stmt> callbackBody) {
            this(operation, mode, channel, value, true, callbackBody);
        }
    }

    /**
     * Dynamic select operates on a runtime SelectSet or iterable/map of
     * SelectCase values. Static select remains a statement so branch control
     * flow (return/break/continue) is checked in its enclosing callable.
     */
    public record DynamicSelectExpr(
            WaitMode mode,
            SelectPolicy policy,
            Expr cases) implements Expr {
        public DynamicSelectExpr {
            if (cases == null) throw new IllegalArgumentException("dynamic select requires a case collection");
        }
    }

    public record ListExpr(List<Expr> elements) implements Expr {
        public ListExpr { elements = List.copyOf(elements); }
    }

    public record TupleExpr(List<Expr> elements) implements Expr {
        public TupleExpr { elements = List.copyOf(elements); }
    }

    /**
     * One statically named field in an anonymous struct value.
     *
     * <p>The field name is part of the compile-time struct shape. Runtime or
     * computed field names are intentionally not representable in the AST.</p>
     */
    public record ObjectField(String name, Expr value) {
        public ObjectField {
            requireStaticFieldName(name);
            java.util.Objects.requireNonNull(value, "value");
        }
        public static ObjectField named(String name, Expr value) {
            return new ObjectField(java.util.Objects.requireNonNull(name, "name"), value);
        }
    }

    public enum ObjectKind { RECORD, INFERRED_STRUCT, INFERRED_RECORD, EXPLICIT_STRUCT }

    /** A field declaration in the closed shape of an explicit anonymous struct. */
    public record StructFieldSpec(String name, TypeRef type) {
        public StructFieldSpec {
            requireStaticFieldName(name);
            java.util.Objects.requireNonNull(type, "struct field type");
        }
    }

    /**
     * Anonymous closed aggregate expression.
     *
     * <p>RECORD is the canonical structural record value and carries no
     * type-capture intent. INFERRED_RECORD is the compatibility infer-record
     * spelling and may participate in an explicit/short inferred alias.
     * Both are closed structural readonly value shapes. INFERRED_STRUCT is a
     * concrete closed struct shape whose field types are
     * inferred from the initializer; mutation authority remains a property of
     * the binding/borrow, not of the {@code infer} keyword. The distinct tags
     * therefore survive semantic lowering instead of collapsing to one record type.
     * EXPLICIT_STRUCT carries a separately
     * declared closed shape so declared fields may be initialized after
     * construction under an owned mutable binding. Oreslang has no open-ended
     * dynamic struct/object shape.</p>
     */
    public record ObjectExpr(
            List<ObjectField> fields,
            ObjectKind kind,
            List<StructFieldSpec> declaredFields) implements Expr {
        public ObjectExpr {
            fields = List.copyOf(fields);
            declaredFields = List.copyOf(declaredFields);
            java.util.Objects.requireNonNull(kind, "object kind");
            if (kind != ObjectKind.EXPLICIT_STRUCT && !declaredFields.isEmpty()) {
                throw new IllegalArgumentException("only explicit structs may carry a declared field shape");
            }
            // Direct AST builders and post-parse transforms are equally trusted
            // by codegen. Prevent ambiguous or overwritten field identities at
            // construction, not merely during parser/type-checker traversal.
            java.util.Set<String> fieldNames = new java.util.HashSet<>();
            for (ObjectField field : fields) {
                if (!fieldNames.add(field.name())) {
                    throw new IllegalArgumentException("duplicate struct field '" + field.name() + "'");
                }
            }
            java.util.Set<String> declaredNames = new java.util.HashSet<>();
            for (StructFieldSpec field : declaredFields) {
                if (!declaredNames.add(field.name())) {
                    throw new IllegalArgumentException("duplicate struct field declaration '" + field.name() + "'");
                }
            }
            // Repetition across the two sets is intentional: an explicit
            // initializer supplies the value of a declared field.
        }
        public ObjectExpr(List<ObjectField> fields) {
            this(fields, ObjectKind.INFERRED_STRUCT, List.of());
        }
        public ObjectExpr(List<ObjectField> fields, boolean inferredStruct) {
            this(fields, requireInferredStructKind(inferredStruct), List.of());
        }
        private static ObjectKind requireInferredStructKind(boolean inferredStruct) {
            if (!inferredStruct) {
                throw new IllegalArgumentException(
                        "legacy non-struct ObjectExpr has been removed; use an inferred or explicit struct");
            }
            return ObjectKind.INFERRED_STRUCT;
        }
        /** True only when the source explicitly used infer and may introduce a captured type alias. */
        public boolean inferredAggregate() {
            return kind == ObjectKind.INFERRED_STRUCT || kind == ObjectKind.INFERRED_RECORD;
        }
        public boolean inferredStruct() { return kind == ObjectKind.INFERRED_STRUCT; }
        public boolean inferredRecord() { return kind == ObjectKind.INFERRED_RECORD; }
        public boolean plainRecord() { return kind == ObjectKind.RECORD; }
        public boolean structuralRecord() { return plainRecord() || inferredRecord(); }
        /** All non-explicit aggregate literals infer their field types from initializer values. */
        public boolean inferredFieldTypes() { return kind != ObjectKind.EXPLICIT_STRUCT; }
        /** Compatibility helper: structural records carry the readonly record capability. */
        public boolean readonlyInferredAggregate() { return structuralRecord(); }
        public boolean explicitStruct() { return kind == ObjectKind.EXPLICIT_STRUCT; }
    }

    public record LambdaExpr(List<Param> parameters, Expr expressionBody, List<Stmt> blockBody, boolean nonLexical) implements Expr {
        public LambdaExpr {
            parameters = List.copyOf(parameters);
            blockBody = blockBody == null ? null : List.copyOf(blockBody);
            if ((expressionBody == null) == (blockBody == null)) {
                throw new IllegalArgumentException(
                        "lambda requires exactly one expression body or block body");
            }
        }
        public LambdaExpr(List<Param> parameters, Expr expressionBody, List<Stmt> blockBody) {
            this(parameters, expressionBody, blockBody, false);
        }
    }
}

