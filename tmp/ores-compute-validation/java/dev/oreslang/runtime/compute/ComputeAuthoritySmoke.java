package dev.oreslang.runtime.compute;

import java.lang.reflect.Modifier;

public final class ComputeAuthoritySmoke {
    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException | IllegalStateException expected) {
            // fail closed
        }
    }

    public static void main(String[] args) throws Exception {
        for (Class<?> type : new Class<?>[] {
                ComputeDeviceHandle.class,
                ComputeBufferHandle.class,
                ComputeFenceHandle.class
        }) {
            for (var constructor : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    throw new AssertionError(type.getSimpleName() + " exposes public constructor");
                }
            }
        }
        if (Modifier.isPublic(ComputeAuthority.class.getModifiers())) {
            throw new AssertionError("trusted authority bootstrap must remain package-private");
        }

        var gpu = ComputeAuthority.issueDevice("vulkan", "gpu0", 7, "tenant-a");
        var sameTextForeign =
                ComputeAuthority.issueDevice("vulkan", "gpu0", 7, "tenant-a");
        var buffer = ComputeAuthority.<Byte>bindBuffer(
                gpu, 11, 4096, 16, "u8", "actor:42");

        expectIllegal(
                () -> ComputeAuthority.beginUpload(
                        buffer, sameTextForeign, "actor:42", 0, 4),
                "textually identical foreign device accepted");
        expectIllegal(
                () -> ComputeAuthority.beginUpload(buffer, gpu, "actor:99", 0, 4),
                "foreign owner domain accepted");
        expectIllegal(
                () -> ComputeAuthority.beginUpload(buffer, gpu, "actor:42", 4095, 2),
                "out-of-bounds transfer accepted");
        expectIllegal(
                () -> ComputeAuthority.beginUpload(buffer, gpu, "actor:42", 0, 0),
                "zero-length transfer accepted");

        var first = ComputeAuthority.beginUpload(buffer, gpu, "actor:42", 0, 64);
        if (buffer.state() != ComputeBufferHandle.State.IN_FLIGHT) {
            throw new AssertionError("upload did not enter in-flight state");
        }
        expectIllegal(
                () -> ComputeAuthority.beginDownload(buffer, gpu, "actor:42", 0, 4),
                "overlapping transfer accepted");
        ComputeAuthority.complete(buffer, first, true);

        var second = ComputeAuthority.beginDownload(buffer, gpu, "actor:42", 64, 64);
        expectIllegal(
                () -> ComputeAuthority.complete(buffer, first, true),
                "stale fence completed a newer transfer");
        ComputeAuthority.complete(buffer, second, true);

        var cancelled = ComputeAuthority.beginDownload(buffer, gpu, "actor:42", 0, 16);
        ComputeAuthority.cancel(buffer, cancelled);
        if (buffer.state() != ComputeBufferHandle.State.POISONED) {
            throw new AssertionError("cancel did not poison allocation");
        }
        expectIllegal(
                () -> ComputeAuthority.retire(buffer, gpu, "actor:42"),
                "cancel was incorrectly treated as quiescence");
        ComputeAuthority.acknowledgeCancelled(buffer, cancelled);
        ComputeAuthority.retire(buffer, gpu, "actor:42");

        expectIllegal(
                () -> ComputeAuthority.issueDevice("vulkan", "gpu0", 0, "tenant-a"),
                "zero provider generation accepted");
        expectIllegal(
                () -> ComputeAuthority.bindBuffer(gpu, 0, 64, 8, "u8", "actor:1"),
                "zero allocation generation accepted");
        expectIllegal(
                () -> ComputeAuthority.bindBuffer(gpu, 1, 64, 3, "u8", "actor:1"),
                "non-power-of-two alignment accepted");

        var sequenceBuffer = ComputeAuthority.<Byte>bindBuffer(
                gpu, 12, 64, 8, "u8", "actor:1");
        var sequence = ComputeBufferHandle.class.getDeclaredField("sequence");
        sequence.setAccessible(true);
        sequence.setLong(sequenceBuffer, Long.MAX_VALUE);
        expectIllegal(
                () -> ComputeAuthority.beginUpload(
                        sequenceBuffer, gpu, "actor:1", 0, 1),
                "sequence exhaustion did not fail closed");

        System.out.println("compute-authority-g1-smoke-ok");
    }
}
