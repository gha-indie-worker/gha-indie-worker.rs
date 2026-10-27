package dev.oreslang.types;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.oreslang.types.Types.Borrow;
import dev.oreslang.types.Types.BoolLiteral;
import dev.oreslang.types.Types.ClassNamespace;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Generic;
import dev.oreslang.types.Types.ListType;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import dev.oreslang.types.Types.SelfType;
import dev.oreslang.types.Types.StringLiteral;
import dev.oreslang.types.Types.Tuple;
import dev.oreslang.types.Types.Type;
import dev.oreslang.types.Types.Union;
import dev.oreslang.types.Types.Unknown;

/**
 * Conservative, compiler-side Rust-style thread-safety auto-trait query.
 *
 * <p>Send means owned value transfer across worker threads is safe. Sync means
 * immutable references can be shared across worker threads. Neither trait
 * grants an actor-message transport contract or proves a borrow is 'static.
 * The borrow checker must still establish ownership, exclusivity and scope.
 *
 * <p>No guest declarations or unsafe/user-defined implementations can forge a
 * trait proof. NominalProof is a trusted compiler callback, not a guest hook.
 */
public final class ThreadSafetyTraits {
    private ThreadSafetyTraits() { }

    // Bound recursive compiler queries on attacker-controlled nested type input.
    private static final int MAX_TYPE_DEPTH = 256;
    // Depth bounds stack usage; this separate budget prevents enormous flat
    // types or exponentially repeated DAG traversals from exhausting CPU.
    private static final int MAX_PROOF_STEPS = 100_000;
    private static final int MAX_METADATA_NAME_CHARS = 1_024;
    private static final Set<String> UNPROVEN_CAPABILITIES = Set.of(
            "Mutex", "MutexGuard", "SharedMutex", "Future",
            "ActorRef", "ActorMail", "ActorOutput", "ActorOutputStream",
            "SelectCase", "SelectSet", "SelectResult",
            "Iterator", "AsyncIterator", "TcpConnection", "HttpExchange",
            "HttpServer", "HttpHandler", "ExecutionPermit",
            "ReadLoan", "WriteLoan", "PartitionLoan",
            // Guest lowering, allocation-domain and physical lifetime proofs
            // for these shared/task capabilities are not yet established.
            "Arc", "Weak", "Rc", "RefCell", "Cell",
            "RwLock", "RwLockReadGuard", "RwLockWriteGuard",
            "Ref", "TRef", "EtsTable", "TableId",
            "Task", "TaskHandle", "TaskScope", "TaskCancellation",
            "ThreadBound", "NativeHandle", "Pointer", "RawPointer");

    public enum Trait { SEND, SYNC }

    // Keep depth-keyed memoization collision-free if the trusted trait set grows.
    private static final int TRAIT_COUNT = Trait.values().length;

    @FunctionalInterface
    public interface NominalProof {
        boolean has(Named type, Trait trait);
    }

    public static boolean has(Type type, Trait trait, NominalProof nominalProof) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(trait, "trait");
        Objects.requireNonNull(nominalProof, "nominalProof");
        if (!isStructurallyWellFormed(type)) return false;
        return has(type, trait, nominalProof,
                Collections.newSetFromMap(new IdentityHashMap<>()),
                new IdentityHashMap<>(), new int[] {MAX_PROOF_STEPS}, 0);
    }

    /**
     * Local fail-closed metadata validation keeps this primitive independent
     * from broader type-relation rewrites. Depth is part of the memo key so a
     * shared DAG proven on a shallow path cannot hide an over-deep path.
     */
    private static boolean isStructurallyWellFormed(Type type) {
        if (type == null) return false;
        return isStructurallyWellFormed(
                type,
                Collections.newSetFromMap(new IdentityHashMap<>()),
                new IdentityHashMap<>(),
                new int[] {MAX_PROOF_STEPS},
                0);
    }

    private static boolean isStructurallyWellFormed(
            Type type, Set<Type> visiting,
            IdentityHashMap<Type, Map<Integer, Boolean>> proven,
            int[] steps, int depth) {
        if (type == null || depth > MAX_TYPE_DEPTH || --steps[0] < 0) return false;
        if (!visiting.add(type)) return false;
        try {
            Map<Integer, Boolean> cached = proven.get(type);
            if (cached != null && cached.containsKey(depth)) return cached.get(depth);
            boolean valid;
            if (type instanceof Primitive || type == Unknown.INSTANCE
                    || type instanceof BoolLiteral) {
                valid = true;
            } else if (type instanceof StringLiteral literal) {
                valid = literal.value() != null;
            } else if (type instanceof Generic generic) {
                valid = validName(generic.name());
            } else if (type instanceof ClassNamespace namespace) {
                valid = validName(namespace.className());
            } else if (type instanceof SelfType self) {
                valid = isStructurallyWellFormed(
                        self.bound(), visiting, proven, steps, depth + 1);
            } else if (type instanceof Borrow borrow) {
                valid = isStructurallyWellFormed(
                        borrow.target(), visiting, proven, steps, depth + 1);
            } else if (type instanceof ListType list) {
                valid = isStructurallyWellFormed(
                        list.element(), visiting, proven, steps, depth + 1);
            } else if (type instanceof Tuple tuple) {
                valid = tuple.kind() != null
                        && allWellFormed(tuple.elements(), visiting, proven, steps, depth + 1);
            } else if (type instanceof Record record) {
                valid = record.kind() != null;
                if (valid) {
                    for (var entry : record.members().entrySet()) {
                        if (!validName(entry.getKey())
                                || !isStructurallyWellFormed(
                                        entry.getValue(), visiting, proven, steps, depth + 1)) {
                            valid = false;
                            break;
                        }
                    }
                }
            } else if (type instanceof Union union) {
                valid = union.options().size() >= 2
                        && allWellFormed(union.options(), visiting, proven, steps, depth + 1);
            } else if (type instanceof Function function) {
                valid = function.parameters().size() == function.mutableParameters().size()
                        && allWellFormed(
                                function.parameters(), visiting, proven, steps, depth + 1)
                        && isStructurallyWellFormed(
                                function.result(), visiting, proven, steps, depth + 1);
            } else if (type instanceof Named named) {
                valid = validName(named.name())
                        && allWellFormed(
                                named.arguments(), visiting, proven, steps, depth + 1);
            } else if (type instanceof Types.Not not) {
                valid = isStructurallyWellFormed(
                        not.excluded(), visiting, proven, steps, depth + 1);
            } else {
                valid = false;
            }
            proven.computeIfAbsent(type, ignored -> new HashMap<>()).put(depth, valid);
            return valid;
        } finally {
            visiting.remove(type);
        }
    }

    private static boolean allWellFormed(
            Iterable<? extends Type> types, Set<Type> visiting,
            IdentityHashMap<Type, Map<Integer, Boolean>> proven,
            int[] steps, int depth) {
        if (types == null) return false;
        for (Type member : types) {
            if (!isStructurallyWellFormed(member, visiting, proven, steps, depth)) return false;
        }
        return true;
    }

    private static boolean validName(String value) {
        return value != null
                && value.length() <= MAX_METADATA_NAME_CHARS
                && !value.isBlank();
    }

    private static boolean has(
            Type type, Trait trait, NominalProof nominalProof, Set<Type> visiting,
            IdentityHashMap<Type, Map<Integer, Boolean>> proven, int[] steps, int depth) {
        if (depth > MAX_TYPE_DEPTH || --steps[0] < 0) return false;
        // The active identity set must be checked *before* memoization; it
        // must never turn a cycle into a positive proof.
        if (!visiting.add(type)) return false;
        try {
            // A positive proof at shallow depth MUST NOT authorize a deeper
            // occurrence whose descendants exceed MAX_TYPE_DEPTH. Include
            // depth in the cache key, not only node identity and trait.
            int proofKey = depth * TRAIT_COUNT + trait.ordinal();
            Map<Integer, Boolean> cached = proven.get(type);
            if (cached != null && cached.containsKey(proofKey)) {
                return cached.get(proofKey);
            }
            boolean result = derive(type, trait, nominalProof, visiting, proven, steps, depth);
            proven.computeIfAbsent(type, ignored -> new HashMap<>())
                    .put(proofKey, result);
            return result;
        } finally {
            visiting.remove(type);
        }
    }

    private static boolean derive(
            Type type, Trait trait, NominalProof nominalProof, Set<Type> visiting,
            IdentityHashMap<Type, Map<Integer, Boolean>> proven, int[] steps, int depth) {
        if (type == Unknown.INSTANCE || type instanceof Generic
                || type instanceof SelfType || type instanceof ClassNamespace
                || type instanceof Function || type instanceof Types.Not) return false;
        if (type instanceof Primitive primitive) return primitive != Primitive.VOID;
        if (type instanceof StringLiteral || type instanceof BoolLiteral) return true;

        if (type instanceof Borrow borrow) {
            // &T: Send iff T: Sync; &mut T: Send iff T: Send.
            // Both forms: Sync iff T: Sync (shared access does not grant
            // mutable access through a borrowed mutable reference).
            Trait required = trait == Trait.SYNC || !borrow.mutable()
                    ? Trait.SYNC : Trait.SEND;
            return has(borrow.target(), required, nominalProof, visiting, proven, steps, depth + 1);
        }
        if (type instanceof ListType list) {
            // Array/List/Vector/Slice expose mutable storage. Moving the
            // owning container is Send when its elements are Send, but
            // sharing &List<T> does not provide a Sync proof merely because
            // T is Sync. A future immutable buffer gets its own type.
            return trait == Trait.SEND
                    && has(list.element(), Trait.SEND, nominalProof, visiting, proven, steps, depth + 1);
        }
        if (type instanceof Tuple tuple) {
            // FixedArray/FixedList are mutable aggregate storage. The
            // ordinary Tuple is immutable under shared access.
            if (trait == Trait.SYNC && tuple.kind() != Types.TupleKind.TUPLE) return false;
            for (Type member : tuple.elements()) {
                if (!has(member, trait, nominalProof, visiting, proven, steps, depth + 1)) return false;
            }
            return true;
        }
        if (type instanceof Record record) {
            // Record.readOnly is a *view capability*, not a frozen
            // allocation or a proof that all aliases are immutable.
            // TypeChecker.overlayReadonlyCapabilities can add readOnly
            // to a view of writable storage. Claiming Sync here would
            // let a readonly alias race a remaining writable alias.
            // Deny even readOnly records until a separate compiler
            // witness proves a frozen backing allocation/exclusivity.
            if (trait == Trait.SYNC) return false;
            for (Type member : record.members().values()) {
                if (!has(member, trait, nominalProof, visiting, proven, steps, depth + 1)) return false;
            }
            return true;
        }
        if (type instanceof Union union) {
            for (Type member : union.options()) {
                if (!has(member, trait, nominalProof, visiting, proven, steps, depth + 1)) return false;
            }
            return true;
        }
        if (!(type instanceof Named named)) return false;
        String name = named.name();
        // Compiler transformations and embedding callers can construct Type
        // objects directly. A malformed nominal spelling must deny the proof,
        // not crash the compiler or reach a permissive nominal callback.
        if (name == null || name.isBlank()) return false;
        // ActorId/ActorKind are sealed compiler/runtime scalar metadata. Their
        // spelling is reserved and their canonical form has no type arguments;
        // a nominal callback must not be able to bless forged generic arities.
        if (name.equals("ActorId") || name.equals("ActorKind")) {
            return named.arguments().isEmpty();
        }
        if (name.equals("OptionUnwrapError")) return named.arguments().isEmpty();
        if (name.equals("Option") || name.equals("IteratorResult")) {
            return named.arguments().size() == 1
                    && has(named.arguments().getFirst(), trait, nominalProof, visiting, proven, steps, depth + 1);
        }
        if (name.equals("Result")) {
            return named.arguments().size() == 2
                    && has(named.arguments().get(0), trait, nominalProof, visiting, proven, steps, depth + 1)
                    && has(named.arguments().get(1), trait, nominalProof, visiting, proven, steps, depth + 1);
        }
        // Oreslang's Channel is an owned synchronized transport. The
        // payload must be Send to cross its worker boundary. Do NOT claim
        // Sync for the handle until the underlying runtime sharing proof
        // and immutable-reference API have been audited separately.
        if (name.equals("Channel")) {
            return trait == Trait.SEND && named.arguments().size() == 1
                    && has(named.arguments().getFirst(), Trait.SEND, nominalProof, visiting, proven, steps, depth + 1);
        }
        if (UNPROVEN_CAPABILITIES.contains(name)) {
            return false;
        }
        // The nominal callback must never turn an unresolved or
        // capability-bearing generic argument into a valid auto-trait.
        // This is intentionally stricter than Rust's PhantomData-style
        // exceptions: a future trusted variance/phantom proof is needed
        // before any argument may be ignored.
        for (Type argument : named.arguments()) {
            if (!has(argument, trait, nominalProof, visiting, proven, steps, depth + 1)) {
                return false;
            }
        }
        // Unknown nominal types are *not* automatically Send or Sync.
        // The callback is compiler-trusted, but its result is still
        // conditional on all nested type arguments passing the same trait.
        return nominalProof.has(named, trait);
    }
}
