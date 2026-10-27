package dev.oreslang.types;

import dev.oreslang.types.AutoTraitRules.AutoTrait;
import dev.oreslang.types.AutoTraitRules.Capabilities;
import dev.oreslang.types.AutoTraitRules.Kind;
import dev.oreslang.types.AutoTraitRules.TypeModel;
import dev.oreslang.types.Types.BoolLiteral;
import dev.oreslang.types.Types.Borrow;
import dev.oreslang.types.Types.ClassNamespace;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Generic;
import dev.oreslang.types.Types.ListType;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Not;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import dev.oreslang.types.Types.SelfType;
import dev.oreslang.types.Types.StringLiteral;
import dev.oreslang.types.Types.Tuple;
import dev.oreslang.types.Types.TupleKind;
import dev.oreslang.types.Types.Type;
import dev.oreslang.types.Types.Union;
import dev.oreslang.types.Types.Unknown;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Canonical auto-trait classification for compiler-known value shapes.
 *
 * <p>Nominal user classes are intentionally delegated back to TypeChecker,
 * because their field graph and generic substitutions live in the compilation
 * environment. Built-ins, structural values, and smart-pointer wrappers are
 * classified here so async/actor/task gates do not grow independent rules.
 */
public final class TypeAutoTraits {
    private TypeAutoTraits() { }

    private static final Capabilities NONE = new Capabilities(false, false, false);
    private static final Capabilities SCALAR = new Capabilities(true, true, true);

    public static Capabilities inferClosed(Type type) {
        return inferBuiltin(type, TypeAutoTraits::inferClosed).orElse(NONE);
    }

    public static Optional<Capabilities> inferBuiltin(
            Type type,
            java.util.function.Function<Type, Capabilities> nested) {
        if (type == null) return Optional.of(NONE);

        if (type instanceof Primitive primitive) {
            return Optional.of(primitive == Primitive.VOID ? NONE : SCALAR);
        }
        if (type instanceof StringLiteral || type instanceof BoolLiteral) {
            return Optional.of(SCALAR);
        }
        if (type == Unknown.INSTANCE || type instanceof Borrow
                || type instanceof Function || type instanceof ClassNamespace
                || type instanceof SelfType || type instanceof Generic
                || type instanceof Not) {
            return Optional.of(NONE);
        }
        if (type instanceof ListType list) {
            Capabilities child = nested.apply(list.element());
            return Optional.of(new Capabilities(child.send(), false, false));
        }
        if (type instanceof Tuple tuple) {
            Capabilities children = combine(tuple.elements(), nested);
            if (tuple.kind() == TupleKind.TUPLE) return Optional.of(children);
            return Optional.of(new Capabilities(children.send(), false, false));
        }
        if (type instanceof Union union) {
            return Optional.of(combine(union.options(), nested));
        }
        if (type instanceof Record record) {
            Capabilities members = combine(record.members().values().stream().toList(), nested);
            boolean immutable = record.readOnly();
            return Optional.of(new Capabilities(
                    members.send(),
                    immutable && members.sync(),
                    immutable && members.share()));
        }
        if (!(type instanceof Named named)) return Optional.of(NONE);

        return switch (named.name()) {
            case "$builtin$Symbol", "ActorId", "ActorKind" ->
                    Optional.of(named.arguments().isEmpty() ? SCALAR : NONE);
            case "ActorRef" ->
                    Optional.of(named.arguments().size() == 1 ? SCALAR : NONE);
            case "OptionUnwrapError" ->
                    Optional.of(named.arguments().isEmpty() ? SCALAR : NONE);
            case "Option", "IteratorResult", "ActorMail", "ActorOutput" ->
                    Optional.of(named.arguments().size() == 1
                            ? nested.apply(named.arguments().getFirst())
                            : NONE);
            case "Result" ->
                    Optional.of(named.arguments().size() == 2
                            ? combine(named.arguments(), nested)
                            : NONE);
            case "Box" -> Optional.of(wrapped(named, Kind.BOX, false, nested));
            case "Arc" -> Optional.of(wrapped(named, Kind.ARC, true, nested));
            case "Weak" -> Optional.of(wrapped(named, Kind.WEAK, true, nested));
            case "Rc" -> Optional.of(wrapped(named, Kind.RC, false, nested));
            case "RefCell" -> Optional.of(wrapped(named, Kind.REFCELL, false, nested));
            case "Mutex" -> Optional.of(wrapped(named, Kind.MUTEX, false, nested));
            case "RwLock" -> Optional.of(wrapped(named, Kind.RWLOCK, false, nested));
            case "SharedMutex" -> {
                if (named.arguments().size() != 1) yield Optional.of(NONE);
                Capabilities child = nested.apply(named.arguments().getFirst());
                // SharedMutex owns the payload and supplies synchronized,
                // explicitly shared backing. It therefore requires Send, not
                // Sync, from T.
                yield Optional.of(new Capabilities(
                        child.send(), child.send(), child.send()));
            }
            case "Channel", "Future", "Iterator", "AsyncIterator",
                    "MutexGuard", "SelectCase", "SelectSet", "SelectResult",
                    "TcpConnection", "HttpExchange", "HttpServer",
                    "HttpHandler", "HttpResponse", "HttpResponseDraft" ->
                    Optional.of(NONE);
            default -> Optional.empty();
        };
    }

    private static Capabilities wrapped(
            Named named,
            Kind kind,
            boolean shareableDomain,
            java.util.function.Function<Type, Capabilities> nested) {
        if (named.arguments().size() != 1) return NONE;
        Capabilities child = nested.apply(named.arguments().getFirst());
        return AutoTraitRules.infer(TypeModel.wrapped(
                kind, modelFromCapabilities(child), shareableDomain));
    }

    static TypeModel modelFromCapabilities(Capabilities capabilities) {
        EnumSet<AutoTrait> denied = EnumSet.noneOf(AutoTrait.class);
        if (!capabilities.send()) denied.add(AutoTrait.SEND);
        if (!capabilities.sync()) denied.add(AutoTrait.SYNC);
        if (!capabilities.share()) denied.add(AutoTrait.SHARE);
        return new TypeModel(
                Kind.NOMINAL,
                List.of(),
                java.util.Set.of(),
                capabilities.share(),
                denied);
    }

    private static Capabilities combine(
            List<? extends Type> values,
            java.util.function.Function<Type, Capabilities> nested) {
        boolean send = true;
        boolean sync = true;
        boolean share = true;
        for (Type value : values) {
            Capabilities capabilities = nested.apply(value);
            send &= capabilities.send();
            sync &= capabilities.sync();
            share &= capabilities.share();
        }
        return new Capabilities(send, sync, share);
    }
}
