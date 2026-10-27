package dev.oreslang.runtime.compute;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, explicit compute-provider metadata registry.
 *
 * <p>The registry snapshots provider/device metadata only. It deliberately
 * does not retain executable {@link ComputeProvider} authority, grant source
 * code a device handle, or fall back to another backend implicitly.</p>
 */
public final class ComputeProviderRegistry {
    private static final int MAX_PROVIDERS = 32;
    private static final int MAX_DEVICES_PER_PROVIDER = 1024;
    private static final int MAX_TOTAL_DEVICES =
            MAX_PROVIDERS * MAX_DEVICES_PER_PROVIDER;

    private final List<String> backends;
    private final Map<String, ComputeDevice> devices;

    private ComputeProviderRegistry(
            Set<String> backendIds,
            Map<String, ComputeDevice> devices) {
        this.backends = List.copyOf(backendIds);
        this.devices = Collections.unmodifiableMap(new LinkedHashMap<>(devices));
    }

    public static ComputeProviderRegistry of(List<? extends ComputeProvider> input) {
        Objects.requireNonNull(input, "input");
        if (input.size() > MAX_PROVIDERS) {
            throw new IllegalArgumentException("too many compute providers");
        }

        Set<String> backendIds = new LinkedHashSet<>();
        Map<String, ComputeDevice> devices = new LinkedHashMap<>();
        int totalDevices = 0;

        for (ComputeProvider provider : input) {
            Objects.requireNonNull(provider, "provider");
            String backendId = Objects.requireNonNull(provider.backendId(), "backendId");
            if (backendId.isBlank()
                    || backendId.length() > 64
                    || !backendId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new IllegalArgumentException("invalid compute backend id");
            }
            if (!backendIds.add(backendId)) {
                throw new IllegalArgumentException(
                        "duplicate compute backend provider: " + backendId);
            }

            // Apply the provider budget before defensive copying. A provider
            // must not force an unbounded allocation merely so the registry
            // can discover that the metadata exceeds its declared limit.
            List<ComputeDevice> rawDevices =
                    Objects.requireNonNull(provider.devices(), "devices");
            int providerDeviceCount = rawDevices.size();
            if (providerDeviceCount > MAX_DEVICES_PER_PROVIDER) {
                throw new IllegalArgumentException(
                        "too many devices for compute backend: " + backendId);
            }
            if (providerDeviceCount > MAX_TOTAL_DEVICES - totalDevices) {
                throw new IllegalArgumentException("too many compute devices");
            }

            List<ComputeDevice> providerDevices = List.copyOf(rawDevices);
            totalDevices += providerDevices.size();
            for (ComputeDevice device : providerDevices) {
                Objects.requireNonNull(device, "device");
                if (!backendId.equals(device.backendId())) {
                    throw new IllegalArgumentException(
                            "provider returned device for different backend");
                }
                String key = key(backendId, device.deviceId());
                if (devices.putIfAbsent(key, device) != null) {
                    throw new IllegalArgumentException(
                            "duplicate compute device id: "
                                    + backendId + "/" + device.deviceId());
                }
            }
        }
        return new ComputeProviderRegistry(backendIds, devices);
    }

    public static ComputeProviderRegistry empty() {
        return of(List.of());
    }

    public List<String> backends() {
        return backends;
    }

    public List<ComputeDevice> devices() {
        return List.copyOf(devices.values());
    }

    public Optional<ComputeDevice> device(String backendId, String deviceId) {
        Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(deviceId, "deviceId");
        return Optional.ofNullable(devices.get(key(backendId, deviceId)));
    }

    private static String key(String backendId, String deviceId) {
        return backendId + "\0" + deviceId;
    }
}
