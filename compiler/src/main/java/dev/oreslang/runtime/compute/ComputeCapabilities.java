package dev.oreslang.runtime.compute;

import java.util.Objects;

/**
 * Immutable, non-authoritative accelerator capability metadata.
 *
 * <p>This record describes a device. It is not a device handle and grants no
 * permission to allocate, transfer, or submit work.</p>
 */
public record ComputeCapabilities(
        String backend,
        String name,
        int addressBits,
        long maxBufferBytes,
        long maxWorkgroupSize,
        boolean float32,
        boolean unifiedMemory) {

    private static final int MAX_BACKEND_CHARS = 64;
    private static final int MAX_NAME_CHARS = 256;

    public ComputeCapabilities {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(name, "name");
        if (backend.isBlank()
                || backend.length() > MAX_BACKEND_CHARS
                || !backend.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid compute backend id");
        }
        if (name.isBlank() || name.length() > MAX_NAME_CHARS) {
            throw new IllegalArgumentException("invalid compute device name");
        }
        if (addressBits != 32 && addressBits != 64) {
            throw new IllegalArgumentException("compute address width must be 32 or 64");
        }
        if (maxBufferBytes <= 0) {
            throw new IllegalArgumentException("maxBufferBytes must be positive");
        }
        if (maxWorkgroupSize <= 0) {
            throw new IllegalArgumentException("maxWorkgroupSize must be positive");
        }
    }
}
