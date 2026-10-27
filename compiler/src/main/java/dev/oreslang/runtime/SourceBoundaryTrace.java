package dev.oreslang.runtime;

import java.util.Objects;

/** Bounded source diagnostics that never capture guest frames, receivers or heaps. */
public final class SourceBoundaryTrace extends RuntimeException {
    private static final int MAX_BOUNDARIES = 64;
    private static final int MAX_CODE_UNIT_LENGTH = 160;
    // Cap untrusted source metadata *before* any path/URI scanning or slicing.
    private static final int MAX_CODE_UNIT_INPUT_LENGTH = 4_096;
    private static final int MAX_CALLABLE_LENGTH = 96;
    private static final int MAX_BOUNDARY_LENGTH = 64;

    private static final String TRUNCATED =
            "ores source trace truncated: one or more source boundaries omitted";

    private SourceBoundaryTrace() {
        super(TRUNCATED, null, false, false);
    }

    private SourceBoundaryTrace(String codeUnit, String callable, String boundary) {
        super("ores " + sanitized(boundary, MAX_BOUNDARY_LENGTH) + " "
                + sanitized(callable, MAX_CALLABLE_LENGTH) + " ["
                + sanitizedCodeUnit(codeUnit) + "]", null, false, false);
    }

    /**
     * Avoid control-sequence, newline, bidi-spoofing and unbounded label injection
     * in stack traces. Metadata is diagnostic-only and must never invoke guest
     * formatting or capture guest values.
     */
    private static String sanitized(String value, int maxLength) {
        if (value == null || value.isEmpty()) return "<unknown>";
        StringBuilder label = new StringBuilder(Math.min(value.length(), maxLength));
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset);
            int width = Character.charCount(point);
            if (label.length() + width > maxLength) {
                // An ellipsis is part of the bound, not one extra UTF-16 unit.
                // Back up by a whole code point so a trailing surrogate pair
                // is never torn apart when the buffer is exactly full.
                if (label.length() == maxLength) {
                    int last = label.codePointBefore(label.length());
                    label.setLength(label.length() - Character.charCount(last));
                }
                label.append('…');
                break;
            }
            offset += width;
            int kind = Character.getType(point);
            if (Character.isISOControl(point) || kind == Character.FORMAT
                    || kind == Character.LINE_SEPARATOR || kind == Character.PARAGRAPH_SEPARATOR
                    || kind == Character.SURROGATE) {
                label.append('?');
            } else {
                label.appendCodePoint(point);
            }
        }
        return label.toString();
    }

    /**
     * Untrusted source metadata can be an absolute filesystem path or a URI
     * containing credentials. Display only a safe leaf name in that case;
     * relative module and source names retain their useful path context.
     */
    private static String sanitizedCodeUnit(String value) {
        if (value == null || value.isEmpty()) {
            return sanitized(value, MAX_CODE_UNIT_LENGTH);
        }
        // Input sizes, not just rendered output sizes, must be bounded on
        // failure paths. A large secret-bearing URL is never partially echoed.
        if (value.length() > MAX_CODE_UNIT_INPUT_LENGTH) {
            return "<source-id-too-long>";
        }
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            int pathStart = value.indexOf('/', scheme + 3);
            if (pathStart < 0) return "<remote-source>";
            value = value.substring(pathStart + 1);
            // A URI authority must never appear in the printed trace.
            value = value.substring(Math.max(value.lastIndexOf('/'),
                    value.lastIndexOf('\\')) + 1);
        } else if (value.startsWith("/") || value.startsWith("\\")
                || value.startsWith("file:") || value.startsWith("jar:")
                || (value.length() >= 3 && Character.isLetter(value.charAt(0))
                        && value.charAt(1) == ':'
                        && (value.charAt(2) == '/' || value.charAt(2) == '\\'))) {
            int leafStart = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
            if (leafStart < 0) return "<unknown>";
            value = value.substring(leafStart + 1);
        }
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int fragment = value.indexOf('#');
        if (fragment >= 0) value = value.substring(0, fragment);
        return sanitized(value, MAX_CODE_UNIT_LENGTH);
    }

    /** Preserve the original failure type, cause and message, including cancellation. */
    public static <T extends Throwable> T record(
            T failure, String codeUnit, String callable, String boundary) {
        Objects.requireNonNull(failure, "failure");
        // A fatal host VM failure may leave insufficient memory or stack to
        // allocate even a minimal source marker. Do not compound that failure
        // with diagnostic work; these are not recoverable Oreslang effects.
        if (failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError) {
            return failure;
        }
        synchronized (failure) {
            int existing = failure.getSuppressed().length;
            if (existing < MAX_BOUNDARIES - 1) {
                failure.addSuppressed(new SourceBoundaryTrace(codeUnit, callable, boundary));
            } else if (existing == MAX_BOUNDARIES - 1) {
                // Reserve one slot so a capped source trace explicitly reports
                // that the current source boundary could not be retained.
                failure.addSuppressed(new SourceBoundaryTrace());
            }
        }
        return failure;
    }
}
