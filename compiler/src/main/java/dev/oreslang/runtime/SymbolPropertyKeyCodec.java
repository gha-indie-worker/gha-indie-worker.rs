package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Versioned, explicitly tagged key interchange, not a replacement for the
 * existing text-only record/struct layout or a guest Map implementation.
 *
 * v1 layout: ASCII "OK", u8 version, u8 key kind, u16 big-endian byte length,
 * exactly that many UTF-8 bytes, no trailing data.
 *
 * Kind 0 = String, 1 = PROCESS Symbol, 2 = STABLE Symbol.
 * The prefix # in String contents is ordinary text, never a Symbol tag.
 */
public final class SymbolPropertyKeyCodec {
    public static final int VERSION = 1;
    public static final int MAX_TEXT_KEY_BYTES = 16_384;
    public static final int MAX_SYMBOL_KEY_BYTES = 256;
    public static final int MAX_KEYS_PER_OBJECT = 4_096;
    public static final long MAX_KEY_SET_WIRE_BYTES = 1_048_576L;

    private static final int HEADER_LENGTH = 6;
    private static final byte TEXT = 0;
    private static final byte PROCESS_SYMBOL = 1;
    private static final byte STABLE_SYMBOL = 2;

    private SymbolPropertyKeyCodec() { }

    private record KeyParts(byte kind, String value, int limit) { }

    private static KeyParts keyParts(Object key) {
        if (key instanceof String text) {
            return new KeyParts(TEXT, text, MAX_TEXT_KEY_BYTES);
        }
        if (key instanceof OresSymbol symbol) {
            return new KeyParts(
                    symbol.kind() == OresSymbol.Kind.PROCESS
                            ? PROCESS_SYMBOL : STABLE_SYMBOL,
                    symbol.name(),
                    MAX_SYMBOL_KEY_BYTES);
        }
        throw new IllegalArgumentException(
                "key codec accepts only String or Symbol");
    }

    /**
     * Return the exact v1 wire size without allocating an encoded frame.
     * Package-private so OresKeyedMap can enforce quotas on hot lookup/write
     * paths without constructing throwaway UTF-8 buffers.
     */
    static int encodedWireLength(Object key) {
        KeyParts parts = keyParts(key);
        String value = parts.value();

        // A valid UTF-8 key cannot use fewer bytes than UTF-16 code units.
        // Reject obviously oversized input before scanning every code point.
        if (value.length() > parts.limit()) {
            throw new IllegalArgumentException("key exceeds tagged wire length budget");
        }
        int utf8Bytes = strictUtf8Length(value);
        if (utf8Bytes > parts.limit()
                || parts.kind() != TEXT && utf8Bytes == 0) {
            throw new IllegalArgumentException("key exceeds tagged wire length budget");
        }
        return HEADER_LENGTH + utf8Bytes;
    }

    /** Return independent UTF-8 bytes; never leak a Symbol table/handle ID. */
    public static byte[] encode(Object key) {
        KeyParts parts = keyParts(key);
        int wireLength = encodedWireLength(key);
        byte[] utf8 = strictEncode(parts.value());
        if (wireLength != HEADER_LENGTH + utf8.length) {
            throw new IllegalStateException("UTF-8 key length preflight disagrees with encoder");
        }
        ByteBuffer wire = ByteBuffer.allocate(wireLength);
        wire.put((byte) 'O').put((byte) 'K').put((byte) VERSION)
                .put(parts.kind()).putShort((short) utf8.length).put(utf8);
        return wire.array();
    }

    /** Decode without process-wide interning, with exact length and NFC checks. */
    public static Object decode(byte[] input) {
        Objects.requireNonNull(input, "tagged key bytes");
        if (input.length < HEADER_LENGTH
                || input.length > HEADER_LENGTH + MAX_TEXT_KEY_BYTES) {
            throw new IllegalArgumentException("invalid tagged key length");
        }

        // Never decode directly from caller-owned mutable bytes: validation,
        // length checks and UTF-8 decoding must see one consistent snapshot.
        ByteBuffer wire = ByteBuffer.wrap(input.clone());
        if (wire.get() != (byte) 'O' || wire.get() != (byte) 'K'
                || Byte.toUnsignedInt(wire.get()) != VERSION) {
            throw new IllegalArgumentException("invalid tagged key magic/version");
        }
        byte kind = wire.get();
        int length = Short.toUnsignedInt(wire.getShort());
        int limit = kind == TEXT ? MAX_TEXT_KEY_BYTES : MAX_SYMBOL_KEY_BYTES;
        if (kind != TEXT && kind != PROCESS_SYMBOL && kind != STABLE_SYMBOL
                || length != wire.remaining()
                || length > limit
                || kind != TEXT && length == 0) {
            throw new IllegalArgumentException("invalid tagged key kind/length");
        }

        String text = strictDecode(wire);
        if (kind == TEXT) return text;

        // Source constructors may NFC-normalize; wire inputs must already
        // be canonical so that keys cannot bypass duplicate detection.
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException("noncanonical NFC Symbol wire key");
        }
        return kind == PROCESS_SYMBOL
                ? OresSymbol.process(text)
                : OresSymbol.stable(text);
    }

    /** Scope identity is resolved by the trusted receiver, not guest source. */
    public record AdmissionScope(String tenant, String endpoint, String protocol) {
        public AdmissionScope {
            if (tenant == null || tenant.isBlank() || endpoint == null || endpoint.isBlank()
                    || protocol == null || protocol.isBlank()) {
                throw new IllegalArgumentException("untrusted Symbol admission requires tenant, endpoint and protocol");
            }
        }
    }

    /**
     * Immutable, detached export manifest. Its package-private constructor
     * prevents a guest-facing decoder API from accepting an ad hoc mutable
     * allowlist. The actor runtime must authenticate recipient scope before
     * constructing/passing one of these trusted snapshots.
     */
    static final class ExportManifest {
        private final java.util.Map<AdmissionScope, Set<OresSymbol>> scopes;

        ExportManifest(java.util.Map<AdmissionScope, Set<OresSymbol>> source) {
            Objects.requireNonNull(source, "export manifest");
            // Configuration is still an input boundary. IdentityHashMap and
            // identity-backed Sets can contain logically equal scope/Symbol
            // objects, and Set.copyOf would silently collapse those entries.
            // Check reported AND actual cardinality before sealing the policy.
            int reportedScopes = source.size();
            if (reportedScopes < 0 || reportedScopes > MAX_KEYS_PER_OBJECT) {
                throw new IllegalArgumentException("too many Symbol export scopes");
            }
            java.util.Map<AdmissionScope, Set<OresSymbol>> frozen =
                    new java.util.LinkedHashMap<>();
            int visitedScopes = 0;
            long totalExports = 0L;
            for (var entry : source.entrySet()) {
                if (++visitedScopes > MAX_KEYS_PER_OBJECT) {
                    throw new IllegalArgumentException("too many Symbol export scopes");
                }
                AdmissionScope scope = Objects.requireNonNull(
                        Objects.requireNonNull(entry, "export entry").getKey(), "export scope");
                Set<OresSymbol> incoming = Objects.requireNonNull(
                        entry.getValue(), "Symbol exports");
                int reportedExports = incoming.size();
                if (reportedExports < 0 || reportedExports > MAX_KEYS_PER_OBJECT
                        || reportedExports > MAX_KEYS_PER_OBJECT - totalExports) {
                    throw new IllegalArgumentException("too many exported Symbols");
                }
                Set<OresSymbol> detached = new HashSet<>();
                int visitedExports = 0;
                for (OresSymbol symbol : incoming) {
                    if (++visitedExports > MAX_KEYS_PER_OBJECT
                            || ++totalExports > MAX_KEYS_PER_OBJECT) {
                        throw new IllegalArgumentException("too many exported Symbols");
                    }
                    Objects.requireNonNull(symbol, "exported Symbol");
                    if (symbol.kind() != OresSymbol.Kind.PROCESS) {
                        throw new IllegalArgumentException(
                                "v1 untrusted Symbol exports must be PROCESS identities");
                    }
                    if (!detached.add(symbol)) {
                        throw new IllegalArgumentException(
                                "duplicate logical Symbol in export manifest");
                    }
                }
                if (visitedExports != reportedExports) {
                    throw new IllegalArgumentException(
                            "Symbol export set size changed during snapshot");
                }
                if (frozen.putIfAbsent(scope, Set.copyOf(detached)) != null) {
                    throw new IllegalArgumentException(
                            "duplicate logical export scope in manifest");
                }
            }
            if (visitedScopes != reportedScopes) {
                throw new IllegalArgumentException(
                        "export manifest scope count changed during snapshot");
            }
            this.scopes = java.util.Map.copyOf(frozen);
        }

        boolean permits(AdmissionScope receivingScope, OresSymbol symbol) {
            // v1 scoped userland exports admit only PROCESS Symbols. Stable
            // UUID Symbols are not implicitly public or exportable to actors.
            return symbol.kind() == OresSymbol.Kind.PROCESS
                    && scopes.getOrDefault(receivingScope, Set.of()).contains(symbol);
        }
    }

    /**
     * Default fail-closed untrusted ingress. Userland exports are indexed by
     * the exact receiver (tenant, endpoint, protocol), never a global public
     * flag or a mutable unscoped set.
     */
    static Object decodeForUntrusted(
            byte[] input,
            AdmissionScope receivingScope,
            ExportManifest manifest) {
        Objects.requireNonNull(receivingScope, "receiving scope");
        Objects.requireNonNull(manifest, "trusted export manifest");
        Object key = decode(input);
        if (key instanceof OresSymbol symbol
                && (symbol.kind() != OresSymbol.Kind.PROCESS
                    || !symbol.publicToUntrustedCoreV1()
                    && !manifest.permits(receivingScope, symbol))) {
            throw new SecurityException("Symbol key is not exported to recipient scope");
        }
        return key;
    }

    public static Object decodeForUntrusted(byte[] input) {
        Object key = decode(input);
        if (key instanceof OresSymbol symbol && !symbol.publicToUntrustedCoreV1()) {
            throw new SecurityException("Symbol key is not visible to untrusted actor");
        }
        return key;
    }

    /**
     * Decode once, reject duplicate logical keys and return immutable values.
     * Callers must use the returned keys, never re-read the mutable source
     * arrays after validation. Both per-entry and aggregate byte budgets apply.
     */
    public static List<Object> decodeDistinctKeys(List<byte[]> encodedKeys) {
        return decodeDistinctKeysChecked(encodedKeys, SymbolPropertyKeyCodec::decode);
    }

    /** For untrusted bulk ingress, never call the permissive trusted decoder. */
    public static List<Object> decodeDistinctKeysForUntrusted(List<byte[]> encodedKeys) {
        return decodeDistinctKeysChecked(encodedKeys, SymbolPropertyKeyCodec::decodeForUntrusted);
    }

    /** Trusted receiver must resolve the authenticated destination scope. */
    static List<Object> decodeDistinctKeysForUntrusted(
            List<byte[]> encodedKeys, AdmissionScope receivingScope, ExportManifest manifest) {
        Objects.requireNonNull(receivingScope, "receiving scope");
        Objects.requireNonNull(manifest, "trusted export manifest");
        return decodeDistinctKeysChecked(encodedKeys,
                encoded -> decodeForUntrusted(encoded, receivingScope, manifest));
    }

    private static List<Object> decodeDistinctKeysChecked(
            List<byte[]> encodedKeys, Function<byte[], Object> decodeKey) {
        Objects.requireNonNull(encodedKeys, "encoded keys");
        // Do not pass an untrusted Collection into List.copyOf(): a custom
        // List can lie about size() and supply unbounded entries from its
        // iterator, forcing unbounded toArray() growth before our quota check.
        int reportedCount = encodedKeys.size();
        if (reportedCount < 0 || reportedCount > MAX_KEYS_PER_OBJECT) {
            throw new IllegalArgumentException("too many tagged keys");
        }
        // Detach ALL frames before decoding or checking duplicate identities.
        // Caller-owned frames may change across iterations; an atomic snapshot
        // requires transport-level ownership or synchronization as well.
        long total = 0L;
        java.util.ArrayList<byte[]> frames = new java.util.ArrayList<>(reportedCount);
        for (byte[] source : encodedKeys) {
            if (frames.size() >= MAX_KEYS_PER_OBJECT) {
                throw new IllegalArgumentException("too many tagged keys");
            }
            Objects.requireNonNull(source, "tagged key entry");
            int size = source.length;
            if (size < HEADER_LENGTH || size > HEADER_LENGTH + MAX_TEXT_KEY_BYTES) {
                throw new IllegalArgumentException("invalid tagged key frame size");
            }
            total += size;
            if (total > MAX_KEY_SET_WIRE_BYTES) {
                throw new IllegalArgumentException("tagged key collection exceeds byte budget");
            }
            frames.add(source.clone());
        }
        if (frames.size() != reportedCount) {
            throw new IllegalArgumentException(
                    "tagged key collection size changed during snapshot");
        }

        Set<Object> distinct = new HashSet<>();
        java.util.ArrayList<Object> decoded = new java.util.ArrayList<>(frames.size());
        for (byte[] encoded : frames) {
            Object key = decodeKey.apply(encoded);
            if (!distinct.add(key)) {
                throw new IllegalArgumentException(
                        "duplicate tagged String or Symbol key");
            }
            decoded.add(key);
        }
        return List.copyOf(decoded);
    }

    /** Backward-compatible validation helper; prefer decodeDistinctKeys. */
    public static void requireDistinct(List<byte[]> encodedKeys) {
        decodeDistinctKeys(encodedKeys);
    }

    private static int strictUtf8Length(String text) {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch <= 0x7f) {
                bytes += 1;
            } else if (ch <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= text.length()
                        || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new IllegalArgumentException("invalid Unicode key");
                }
                bytes += 4;
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("invalid Unicode key");
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private static byte[] strictEncode(String text) {
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException bad) {
            throw new IllegalArgumentException("invalid Unicode key", bad);
        }
    }

    private static String strictDecode(ByteBuffer bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes).toString();
        } catch (CharacterCodingException bad) {
            throw new IllegalArgumentException("malformed UTF-8 tagged key", bad);
        }
    }
}
