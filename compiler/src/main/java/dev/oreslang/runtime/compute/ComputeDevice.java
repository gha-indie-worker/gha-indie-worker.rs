package dev.oreslang.runtime.compute;

import java.util.Objects;

/**
 * Stable provider/device identity plus descriptive capabilities.
 *
 * <p>This is still metadata, not source-visible device authority. Future
 * compiler/runtime Device handles must carry unforgeable ownership/provenance
 * separate from this descriptor.</p>
 */
public record ComputeDevice(
        String backendId,
        String deviceId,
        ComputeCapabilities capabilities) {

    private static final int MAX_DEVICE_ID_CHARS = 128;

    public ComputeDevice {
        Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(capabilities, "capabilities");
        if (!backendId.equals(capabilities.backend())) {
            throw new IllegalArgumentException(
                    "device backend id must match capability backend");
        }
        if (deviceId.isBlank()
                || deviceId.length() > MAX_DEVICE_ID_CHARS
                || !deviceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException("invalid compute device id");
        }
    }
}
