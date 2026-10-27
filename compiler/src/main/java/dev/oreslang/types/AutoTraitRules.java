package dev.oreslang.types;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Backend-independent semantic rules for ownership/concurrency auto traits.
 *
 * <p>This is deliberately independent of parser syntax. Frontends elaborate
 * source types into {@link TypeModel}; every backend must then reach the same
 * Send/Sync/Share answer. Share is Oreslang-specific: it proves that backing
 * storage may be aliased across actor/allocation domains. A value may be Send
 * without being Share (for example, unique transferable ownership).
 */
public final class AutoTraitRules {
    private AutoTraitRules() { }

    public enum AutoTrait { SEND, SYNC, SHARE }

    public enum Kind {
        SCALAR,
        NOMINAL,
        BOX,
        ARC,
        WEAK,
        RC,
        REFCELL,
        MUTEX,
        RWLOCK,
        DYN
    }

    public record Capabilities(boolean send, boolean sync, boolean share) {
        public boolean has(AutoTrait trait) {
            return switch (trait) {
                case SEND -> send;
                case SYNC -> sync;
                case SHARE -> share;
            };
        }

        private Capabilities without(Set<AutoTrait> negative) {
            return new Capabilities(
                    send && !negative.contains(AutoTrait.SEND),
                    sync && !negative.contains(AutoTrait.SYNC),
                    share && !negative.contains(AutoTrait.SHARE));
        }
    }

    /**
     * Canonical type shape after frontend elaboration.
     *
     * @param kind semantic wrapper/category
     * @param parameters fields or wrapped type arguments
     * @param dynBounds explicit auto-trait bounds on a dyn object
     * @param shareableDomain whether this value's backing storage has explicit
     *                        cross-actor sharing authority
     * @param negative explicit compiler-known negative auto traits
     */
    public record TypeModel(
            Kind kind,
            List<TypeModel> parameters,
            Set<AutoTrait> dynBounds,
            boolean shareableDomain,
            Set<AutoTrait> negative) {
        public TypeModel {
            Objects.requireNonNull(kind, "kind");
            parameters = List.copyOf(parameters);
            dynBounds = Set.copyOf(dynBounds);
            negative = Set.copyOf(negative);
        }

        public static TypeModel scalar() {
            return new TypeModel(Kind.SCALAR, List.of(), Set.of(), true, Set.of());
        }

        public static TypeModel nominal(boolean shareableDomain, TypeModel... fields) {
            return new TypeModel(
                    Kind.NOMINAL, List.of(fields), Set.of(), shareableDomain, Set.of());
        }

        public static TypeModel wrapped(
                Kind kind, TypeModel value, boolean shareableDomain) {
            return new TypeModel(
                    kind, List.of(value), Set.of(), shareableDomain, Set.of());
        }

        public static TypeModel dyn(Set<AutoTrait> bounds, boolean shareableDomain) {
            return new TypeModel(
                    Kind.DYN, List.of(), bounds, shareableDomain, Set.of());
        }

        public TypeModel denying(AutoTrait... traits) {
            return new TypeModel(
                    kind, parameters, dynBounds, shareableDomain, Set.of(traits));
        }
    }

    public static Capabilities infer(TypeModel type) {
        Objects.requireNonNull(type, "type");
        Capabilities base = switch (type.kind()) {
            case SCALAR -> new Capabilities(true, true, true);
            case NOMINAL -> {
                boolean send = all(type.parameters(), AutoTrait.SEND);
                boolean sync = all(type.parameters(), AutoTrait.SYNC);
                boolean share = type.shareableDomain()
                        && all(type.parameters(), AutoTrait.SHARE);
                yield new Capabilities(send, sync, share);
            }
            case BOX -> {
                TypeModel value = exactlyOne(type);
                Capabilities nested = infer(value);
                // Box is unique ownership. It may move between execution lanes
                // when T is Send, but it is not aliasable actor-shared storage.
                yield new Capabilities(nested.send(), nested.sync(), false);
            }
            case ARC, WEAK -> {
                TypeModel value = exactlyOne(type);
                Capabilities nested = infer(value);
                boolean concurrent = type.shareableDomain()
                        && nested.send() && nested.sync();
                yield new Capabilities(
                        concurrent,
                        concurrent,
                        concurrent && nested.share());
            }
            case RC -> {
                exactlyOne(type);
                yield new Capabilities(false, false, false);
            }
            case REFCELL -> {
                TypeModel value = exactlyOne(type);
                Capabilities nested = infer(value);
                yield new Capabilities(nested.send(), false, false);
            }
            case MUTEX -> {
                TypeModel value = exactlyOne(type);
                Capabilities nested = infer(value);
                // Like Rust's synchronization containers, cross-lane shared
                // access only requires moving T into/out of the protected slot.
                yield new Capabilities(nested.send(), nested.send(), false);
            }
            case RWLOCK -> {
                Capabilities nested = infer(exactlyOne(type));
                // Concurrent readers expose shared T, so T must also be Sync.
                yield new Capabilities(nested.send(), nested.send() && nested.sync(), false);
            }
            case DYN -> new Capabilities(
                    type.dynBounds().contains(AutoTrait.SEND),
                    type.dynBounds().contains(AutoTrait.SYNC),
                    type.shareableDomain()
                            && type.dynBounds().contains(AutoTrait.SHARE));
        };
        return base.without(type.negative());
    }

    private static TypeModel exactlyOne(TypeModel type) {
        if (type.parameters().size() != 1) {
            throw new IllegalArgumentException(
                    type.kind() + " requires exactly one type parameter");
        }
        return type.parameters().get(0);
    }

    private static boolean all(List<TypeModel> values, AutoTrait trait) {
        for (TypeModel value : values) {
            if (!infer(value).has(trait)) return false;
        }
        return true;
    }
}
