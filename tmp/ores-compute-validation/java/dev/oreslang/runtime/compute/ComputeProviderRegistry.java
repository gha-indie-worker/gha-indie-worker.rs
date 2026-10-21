package dev.oreslang.runtime.compute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, explicit compute-provider registry.
 *
 * <p>The registry is descriptive only. It does not grant source code a device
 * handle and it never falls back to another backend implicitly.</p>
 */
public final class ComputeProviderRegistry {
    private static final int MAX_PROVIDERS = 32;
    private static final int MAX_DEVICES_PER_PROVIDER = 1024;

    private final Map<String, ComputeProvider> providers;
    private final Map<String, ComputeDevice> devices;

    private ComputeProviderRegistry(
            Map<String, ComputeProvider> providers,
            Map<String, ComputeDevice> devices) {
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
        this.devices = Collections.unmodifiableMap(new LinkedHashMap<>(devices));
    }

    public static ComputeProviderRegistry of(List<? extends ComputeProvider> input) {
        Objects.requireNonNull(input, "input");
        if (input.size() > MAX_PROVIDERS) {
            throw new IllegalArgumentException("too many compute providers");
        }

        Map<String, ComputeProvider> providers = new LinkedHashMap<>();
        Map<String, ComputeDevice> devices = new LinkedHashMap<>();

        for (ComputeProvider provider : input) {
            Objects.requireNonNull(provider, "provider");
            String backendId = Objects.requireNonNull(provider.backendId(), "backendId");
            if (backendId.isBlank()
                    || backendId.length() > 64
                    || !backendId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new IllegalArgumentException("invalid compute backend id");
            }
            if (providers.putIfAbsent(backendId, provider) != null) {
                throw new IllegalArgumentException(
                        "duplicate compute backend provider: " + backendId);
            }

            List<ComputeDevice> providerDevices =
                    List.copyOf(Objects.requireNonNull(provider.devices(), "devices"));
            if (providerDevices.size() > MAX_DEVICES_PER_PROVIDER) {
                throw new IllegalArgumentException(
                        "too many devices for compute backend: " + backendId);
            }
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
        return new ComputeProviderRegistry(providers, devices);
    }

    public static ComputeProviderRegistry empty() {
        return of(List.of());
    }

    public List<String> backends() {
        return List.copyOf(providers.keySet());
    }

    public List<ComputeDevice> devices() {
        return new ArrayList<>(devices.values());
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
