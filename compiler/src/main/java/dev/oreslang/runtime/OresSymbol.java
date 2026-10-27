package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, nominal guest Symbol. Equality is logical (kind and NFC name),
 * never a VM handle, process-local interning slot or memory address.
 *
 * This value deliberately does not intern untrusted strings in a global table.
 * Admission of userland Symbols to untrusted actors is a separate, scoped policy.
 */
public final class OresSymbol {
    public enum Kind { PROCESS, STABLE }

    private static final Set<String> PUBLIC_CORE_V1 =
            Set.of("ready", "error", "Symbol.iterator");

    private final Kind kind;
    private final String name;

    private OresSymbol(Kind kind, String name) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.name = normalizeAndValidate(name);
    }

    public static OresSymbol process(String name) {
        return new OresSymbol(Kind.PROCESS, name);
    }

    public static OresSymbol stable(String canonicalUuid) {
        String checked = normalizeAndValidate(canonicalUuid);
        try {
            if (!UUID.fromString(checked).toString().equals(checked)) {
                throw new IllegalArgumentException("stable Symbol UUID is not canonical");
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("stable Symbol requires canonical lowercase UUID", failure);
        }
        return new OresSymbol(Kind.STABLE, checked);
    }

    public Kind kind() { return kind; }
    public String name() { return name; }

    /** Temporary sealed core allowlist; not a tenant-scoped export mechanism. */
    public boolean publicToUntrustedCoreV1() {
        return kind == Kind.PROCESS && PUBLIC_CORE_V1.contains(name);
    }

    public static String normalizeAndValidate(String input) {
        Objects.requireNonNull(input, "Symbol name");
        // Bound caller input before normalization; NFC must never process
        // unbounded attacker-controlled strings (including quoted literals).
        if (input.length() > 1_024) {
            throw new IllegalArgumentException("Symbol source name exceeds normalization budget");
        }
        // Reject malformed UTF-16 before invoking the host Unicode normalizer.
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= input.length() || !Character.isLowSurrogate(input.charAt(++i))) {
                    throw new IllegalArgumentException("unpaired UTF-16 surrogate in Symbol");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("unpaired UTF-16 surrogate in Symbol");
            }
        }
        String value = Normalizer.normalize(input, Normalizer.Form.NFC);
        if (value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new IllegalArgumentException("Symbol name requires 1..256 UTF-8 bytes");
        }
        for (int i = 0; i < value.length();) {
            char c = value.charAt(i);
            if (Character.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c) || i + 1 == value.length()
                        || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("unpaired UTF-16 surrogate in Symbol");
                }
            }
            int codepoint = value.codePointAt(i);
            int category = Character.getType(codepoint);
            if (codepoint < 0x20 || (codepoint >= 0x7f && codepoint <= 0x9f)
                    || category == Character.FORMAT
                    || category == Character.LINE_SEPARATOR
                    || category == Character.PARAGRAPH_SEPARATOR
                    || codepoint == 0x061c || codepoint == 0x200e || codepoint == 0x200f
                    || (codepoint >= 0x202a && codepoint <= 0x202e)
                    || (codepoint >= 0x2066 && codepoint <= 0x2069)) {
                throw new IllegalArgumentException(
                        "control, line-separator, or bidi character in Symbol");
            }
            i += Character.charCount(codepoint);
        }
        return value;
    }

    @Override public boolean equals(Object other) {
        return other instanceof OresSymbol symbol
                && kind == symbol.kind && name.equals(symbol.name);
    }

    @Override public int hashCode() {
        return Objects.hash(kind, name);
    }

    @Override public String toString() {
        if (kind == Kind.STABLE) return "Symbol.stable(\"" + name + "\")";
        if (name.length() <= 128 && name.matches("[A-Za-z_][A-Za-z0-9_]*")) return "#" + name;
        return "#\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
