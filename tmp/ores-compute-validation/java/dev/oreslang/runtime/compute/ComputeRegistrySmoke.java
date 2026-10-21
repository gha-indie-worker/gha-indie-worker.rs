package dev.oreslang.runtime.compute;

import java.util.List;

public final class ComputeRegistrySmoke {
    private static ComputeCapabilities caps(String backend) {
        return new ComputeCapabilities(
                backend, "test device", 64, 1L << 30, 256, true, false);
    }

    private static ComputeProvider provider(String backend, String... ids) {
        return new ComputeProvider() {
            public String backendId() { return backend; }
            public List<ComputeDevice> devices() {
                return java.util.Arrays.stream(ids)
                        .map(id -> new ComputeDevice(backend, id, caps(backend)))
                        .toList();
            }
        };
    }

    public static void main(String[] args) {
        var registry = ComputeProviderRegistry.of(List.of(
                provider("vulkan", "gpu0", "gpu1"),
                provider("metal", "integrated")));

        if (!registry.device("vulkan", "gpu1").isPresent()) {
            throw new AssertionError("exact device lookup failed");
        }
        if (registry.device("cuda", "gpu0").isPresent()) {
            throw new AssertionError("implicit backend fallback occurred");
        }

        try {
            ComputeProviderRegistry.of(List.of(
                    provider("vulkan", "gpu0"),
                    provider("vulkan", "gpu1")));
            throw new AssertionError("duplicate backend accepted");
        } catch (IllegalArgumentException expected) { }

        try {
            new ComputeCapabilities("vulkan", "gpu", 48, 1024, 64, true, false);
            throw new AssertionError("invalid address width accepted");
        } catch (IllegalArgumentException expected) { }

        System.out.println("compute-registry-smoke-ok");
    }
}
