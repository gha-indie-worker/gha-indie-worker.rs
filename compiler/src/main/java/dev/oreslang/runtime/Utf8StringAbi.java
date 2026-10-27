package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Internal Java/Graal boundary model for the shared String ABI v1 contract.
 *
 * <p>Oreslang string values remain ordinary length-bearing guest strings and
 * may contain U+0000. CString is an explicit FFI adapter: its byte length
 * excludes the required trailing NUL and embedded NUL is rejected.</p>
 */
public final class Utf8StringAbi {
    private Utf8StringAbi() { }

    public record Utf8Bytes(byte[] bytes, int byteLength) {
        public Utf8Bytes {
            Objects.requireNonNull(bytes, "bytes");
            if (byteLength < 0 || byteLength != bytes.length) {
                throw new IllegalArgumentException("UTF-8 byte length must match storage extent");
            }
            // Freeze untrusted caller-owned input before validating it.
            bytes = bytes.clone();
            decodeUtf8(bytes, byteLength);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record CString(byte[] storage, int byteLength) {
        public CString {
            Objects.requireNonNull(storage, "storage");
            if (byteLength < 0 || byteLength != storage.length - 1) {
                throw new IllegalArgumentException("CString storage must be byte_length + 1");
            }
            // Clone first: validation must not race a caller mutating the input.
            storage = storage.clone();
            if (storage[byteLength] != 0) {
                throw new IllegalArgumentException("CString requires a trailing NUL");
            }
            for (int i = 0; i < byteLength; i++) {
                if (storage[i] == 0) {
                    throw new IllegalArgumentException("CString payload contains embedded NUL");
                }
            }
            decodeUtf8(storage, byteLength);
        }

        @Override
        public byte[] storage() {
            return storage.clone();
        }
    }

    public static Utf8Bytes encode(String value) {
        validateUnicode(value);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new Utf8Bytes(bytes, bytes.length);
    }

    public static String decode(byte[] storage, int byteLength) {
        Objects.requireNonNull(storage, "storage");
        if (byteLength < 0 || byteLength > storage.length) {
            throw new IllegalArgumentException("UTF-8 byte length is outside storage");
        }
        return decodeUtf8(storage, byteLength);
    }

    public static CString toCString(String value) {
        validateUnicode(value);
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("CString conversion rejects embedded NUL");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new CString(Arrays.copyOf(bytes, bytes.length + 1), bytes.length);
    }

    public static String fromCString(CString value) {
        Objects.requireNonNull(value, "value");
        return decodeUtf8(value.storage, value.byteLength);
    }

    private static String decodeUtf8(byte[] storage, int byteLength) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(storage, 0, byteLength))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("invalid UTF-8 at Oreslang string boundary", failure);
        }
    }

    private static void validateUnicode(String value) {
        Objects.requireNonNull(value, "value");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("Oreslang string contains unpaired Unicode surrogate");
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("Oreslang string contains unpaired Unicode surrogate");
            }
        }
    }
}
