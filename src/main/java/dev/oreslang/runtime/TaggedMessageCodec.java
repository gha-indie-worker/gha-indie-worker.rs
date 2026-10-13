package dev.oreslang.runtime;

import dev.oreslang.types.TaggedMessageContracts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Executable v2 wire adapter for a closed compiler-bound enum/pattern domain.
 *
 * Uses the existing canonical MessagePack v2 shape-bound codec per variant:
 * [2, schemaIdForDomainAndCase, SHA256(tuple-shape), [enumName, tag, payload]].
 * The domain is fixed by the receiver's compiled code; wire bytes cannot
 * introduce new enum constructors or select a host class.
 *
 * This is a first, bounded implementation. Native LLVM still needs identical
 * source-schema lowering and production message integration.
 */
public final class TaggedMessageCodec implements MessagePackCollectionCodec.MailboxWireCodec {
    public record TaggedValue(String enumName, String variant, int tag, Object payload) {
        public TaggedValue {
            Objects.requireNonNull(enumName);
            Objects.requireNonNull(variant);
            if (tag < 1) throw new IllegalArgumentException("invalid enum tag");
        }
        public String qualifiedName() { return enumName + "." + variant; }
    }

    private record CaseCodec(TaggedMessageContracts.Case source,
                             String schemaId,
                             MessagePackCollectionCodec.Shape shape) { }
    private final Map<String, CaseCodec> byName;
    private final Map<String, CaseCodec> bySchemaId;

    public TaggedMessageCodec(TaggedMessageContracts.Domain domain) {
        Objects.requireNonNull(domain);
        if (domain.cases().isEmpty() || domain.cases().size() > 4096) {
            throw new IllegalArgumentException("tagged actor domain must have 1..4096 cases");
        }
        Map<String, CaseCodec> entries = new HashMap<>();
        Map<String, CaseCodec> schemas = new HashMap<>();
        // The native nested-v2 emitter and the Java shape grammar both
        // require ASCII nominal identifiers across the *entire closed
        // domain*, not merely the inner referenced enum. Restrict only
        // domains that opt into the new nested format, leaving legacy
        // scalar-only domains unchanged.
        boolean nestedDomain = domain.cases().stream().anyMatch(
                c -> c.payload() != null && containsNestedEnum(c.payload()));
        if (nestedDomain) {
            MessagePackCollectionCodec.requireCanonicalEnumWireName(domain.name());
            for (TaggedMessageContracts.Case c : domain.cases()) {
                MessagePackCollectionCodec.requireCanonicalEnumWireName(c.owner());
                MessagePackCollectionCodec.requireCanonicalEnumWireName(c.name());
            }
        }
        StringBuilder fingerprint = new StringBuilder("oreslang.tagged.domain.v1/");
        fingerprint.append(domain.name()).append('/');
        domain.cases().stream().map(TaggedMessageContracts.Case::qualifiedName)
                .sorted().forEach(n -> fingerprint.append(n.length()).append(':').append(n).append(';'));
        String domainHash = digestHex(fingerprint.toString());
        Set<String> wireTags = new java.util.HashSet<>();
        for (TaggedMessageContracts.Case source : domain.cases()) {
            // A host-constructed Domain must obey the same admission rules
            // as the compiler registry, including unambiguous owner+tag.
            if (source.owner() == null || source.owner().isBlank()
                    || source.name() == null || source.name().isBlank()
                    || source.tag() < 1
                    || (source.sourcePayload() == null) != (source.payload() == null)) {
                throw new IllegalArgumentException(
                        "invalid enum case or mismatched payload descriptor");
            }
            if (!wireTags.add(source.owner() + "#" + source.tag())) {
                throw new IllegalArgumentException(
                        "duplicate enum wire tag in domain " + source.owner()
                                + "#" + source.tag());
            }
            var tuple = source.payload() == null
                    ? MessagePackCollectionCodec.tuple(
                        MessagePackCollectionCodec.string(),
                        MessagePackCollectionCodec.int64())
                    : MessagePackCollectionCodec.tuple(
                        MessagePackCollectionCodec.string(),
                        MessagePackCollectionCodec.int64(),
                        source.payload());
            String variantIdentity = source.qualifiedName() + "#" + source.tag();
            String id = "oreslang.enum." + domainHash + "." + digestHex(variantIdentity);
            CaseCodec cc = new CaseCodec(source, id, tuple);
            if (entries.putIfAbsent(source.qualifiedName(), cc) != null) {
                throw new IllegalArgumentException("duplicate case in pattern: " + source.qualifiedName());
            }
            if (schemas.putIfAbsent(id, cc) != null) {
                throw new IllegalArgumentException("duplicate compiled enum case schema ID");
            }
        }
        byName = Map.copyOf(entries);
        bySchemaId = Map.copyOf(schemas);
    }

    private static boolean containsNestedEnum(MessagePackCollectionCodec.Shape shape) {
        if (shape instanceof MessagePackCollectionCodec.NestedEnum) return true;
        if (shape instanceof MessagePackCollectionCodec.Sequence sequence) {
            for (MessagePackCollectionCodec.Shape member : sequence.slots()) {
                if (containsNestedEnum(member)) return true;
            }
            return sequence.element() != null && containsNestedEnum(sequence.element());
        }
        return false;
    }

    private static String digestHex(String input) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /**
     * Data-only preflight view of an *already decoded, schema-authenticated*
     * tagged value. ActorRuntime accounts and capability-checks this
     * projection before queue admission, while the mailbox stores only the
     * owned binary frame and later decodes the original nominal values on
     * the receiving actor lane. Never call this on an untrusted host value
     * instead of invoking the compiled codec first.
     */
    public static Object preflightProjection(TaggedValue trustedDecoded) {
        Objects.requireNonNull(trustedDecoded, "decoded enum");
        return project(trustedDecoded, new java.util.IdentityHashMap<>(),
                new int[]{0}, 0);
    }

    private static Object project(Object value,
                                  java.util.IdentityHashMap<Object, Boolean> visiting,
                                  int[] nodes, int depth) {
        if (depth > MessagePackCollectionCodec.MAX_DEPTH
                || ++nodes[0] > MessagePackCollectionCodec.MAX_NODES) {
            throw new IllegalArgumentException("enum actor preflight exceeds depth/node quota");
        }
        if (value instanceof TaggedValue nested) {
            if (visiting.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("cyclic tagged actor value");
            }
            try {
                if (nested.payload() == null)
                    return List.of(nested.enumName(), (long) nested.tag());
                return List.of(nested.enumName(), (long) nested.tag(),
                        project(nested.payload(), visiting, nodes, depth + 1));
            } finally {
                visiting.remove(value);
            }
        }
        if (value instanceof List<?> list) {
            if (visiting.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("cyclic tagged actor collection");
            }
            try {
                java.util.ArrayList<Object> copy = new java.util.ArrayList<>(list.size());
                for (Object item : list) {
                    copy.add(project(item, visiting, nodes, depth + 1));
                }
                return List.copyOf(copy);
            } finally {
                visiting.remove(value);
            }
        }
        // The decoder produced this scalar from a compiler-closed wire shape.
        // Any unexpected host reference still fails the ActorRuntime
        // capability/Sendable validation against the returned projection.
        return value;
    }

    @Override
    public byte[] encode(Object value) {
        if (!(value instanceof TaggedValue tagged)) {
            throw new IllegalArgumentException("typed actor mailbox requires enum-tagged value");
        }
        CaseCodec cc = byName.get(tagged.qualifiedName());
        if (cc == null || cc.source().tag() != tagged.tag()) {
            throw new IllegalArgumentException("enum variant is not allowed in actor pattern: "
                    + tagged.qualifiedName());
        }
        if (cc.source().payload() == null) {
            if (tagged.payload() != null) {
                throw new IllegalArgumentException("payloadless enum case cannot carry data");
            }
            return MessagePackCollectionCodec.encode(
                    cc.schemaId(), cc.shape(), List.of(tagged.enumName(), (long) tagged.tag()));
        }
        if (tagged.payload() == null) {
            throw new IllegalArgumentException("enum case requires serializable payload");
        }
        return MessagePackCollectionCodec.encode(cc.schemaId(), cc.shape(),
                List.of(tagged.enumName(), (long) tagged.tag(), tagged.payload()));
    }

    @Override
    public TaggedValue decode(byte[] bytes) {
        Objects.requireNonNull(bytes);
        // One bounded header parse, then a lookup in the *compiled* closed
        // schema table. Never execute up to 4096 decoder attempts for one
        // hostile message. This does not trust the on-wire schema: the
        // complete MessagePack shape and tag are rechecked below.
        String id = MessagePackCollectionCodec.envelopeSchemaId(bytes);
        CaseCodec cc = bySchemaId.get(id);
        if (cc == null) {
            throw new IllegalArgumentException(
                    "unknown enum message schema in this actor pattern");
        }
        Object decoded = MessagePackCollectionCodec.decode(cc.schemaId(), cc.shape(), bytes);
        if (!(decoded instanceof List<?> value)
                || value.size() != (cc.source().payload() == null ? 2 : 3)
                || !Objects.equals(value.get(0), cc.source().owner())
                || !(value.get(1) instanceof Long tag)
                || tag.longValue() != cc.source().tag()) {
            throw new IllegalArgumentException("enum type/tag mismatch in binary payload");
        }
        return new TaggedValue(cc.source().owner(), cc.source().name(),
                cc.source().tag(), value.size() == 3 ? value.get(2) : null);
    }
}
