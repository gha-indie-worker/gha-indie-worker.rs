package dev.oreslang.runtime.compute;

import java.util.AbstractList;
import java.util.List;

public final class ComputeRegistrySmoke {
    private static ComputeCapabilities caps(String backend) {
        return new ComputeCapabilities(
                backend, "test device", 64, 1L << 30, 256L, true, false);
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

    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // fail closed
        }
    }

    public static void main(String[] args) {
        var registry = ComputeProviderRegistry.of(List.of(
                provider("vulkan", "gpu0", "gpu1"),
                provider("metal", "integrated")));

        if (!registry.backends().equals(List.of("vulkan", "metal"))) {
            throw new AssertionError("backend order is not deterministic");
        }
        if (!registry.device("vulkan", "gpu1").isPresent()) {
            throw new AssertionError("exact device lookup failed");
        }
        if (registry.device("cuda", "gpu0").isPresent()) {
            throw new AssertionError("implicit backend fallback occurred");
        }
        expectIllegal(() -> ComputeProviderRegistry.of(List.of(
                provider("vulkan", "gpu0"), provider("vulkan", "gpu1"))),
                "duplicate backend accepted");

        List<ComputeDevice> oversized = new AbstractList<>() {
            @Override public ComputeDevice get(int index) {
                throw new AssertionError("oversized provider must be rejected before iteration/copy");
            }
            @Override public int size() { return 1025; }
        };
        ComputeProvider oversizedProvider = new ComputeProvider() {
            public String backendId() { return "vulkan"; }
            public List<ComputeDevice> devices() { return oversized; }
        };
        expectIllegal(() -> ComputeProviderRegistry.of(List.of(oversizedProvider)),
                "oversized provider accepted");

        for (var field : ComputeProviderRegistry.class.getDeclaredFields()) {
            if (field.getGenericType().getTypeName().contains("ComputeProvider")) {
                throw new AssertionError("metadata registry retains executable provider authority");
            }
        }

        expectIllegal(() -> new ComputeCapabilities(
                "vulkan", "gpu", 48, 1024, 64, true, false),
                "invalid address width accepted");
        expectIllegal(() -> new ComputeCapabilities(
                "vulkan", "gpu", 64, -1, 64, true, false),
                "negative maxBufferBytes accepted");
        expectIllegal(() -> new ComputeCapabilities(
                "vulkan", "gpu", 64, 1024, -1, true, false),
                "negative maxWorkgroupSize accepted");

        System.out.println("compute-registry-hardening-smoke-ok");
    }
}
