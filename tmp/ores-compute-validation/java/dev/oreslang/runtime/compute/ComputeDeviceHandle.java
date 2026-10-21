package dev.oreslang.runtime.compute;

import java.util.Objects;

/** Runtime-owned device authority. Numeric metadata alone never recreates this capability. */
public final class ComputeDeviceHandle {
    private final String backendId;
    private final String deviceId;
    private final long providerGeneration;
    private final String tenantDomain;
    private final Object proof;

    ComputeDeviceHandle(String backendId, String deviceId, long providerGeneration, String tenantDomain) {
        this.backendId = requireText(backendId, "backendId");
        this.deviceId = requireText(deviceId, "deviceId");
        this.tenantDomain = requireText(tenantDomain, "tenantDomain");
        if (providerGeneration <= 0) {
            throw new IllegalArgumentException("providerGeneration must be positive");
        }
        this.providerGeneration = providerGeneration;
        this.proof = new Object();
    }

    public String backendId() { return backendId; }
    public String deviceId() { return deviceId; }
    public long providerGeneration() { return providerGeneration; }
    public String tenantDomain() { return tenantDomain; }

    boolean sameAuthority(ComputeDeviceHandle other) {
        return other != null && proof == other.proof;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
