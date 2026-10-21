package dev.oreslang.runtime.compute;

/**
 * Trusted runtime bootstrap surface. Package-private by design: ordinary Java
 * callers and Oreslang source do not receive a constructor for compute authority.
 */
final class ComputeAuthority {
    private ComputeAuthority() {}

    static ComputeDeviceHandle issueDevice(
            String backendId, String deviceId, long providerGeneration, String tenantDomain) {
        return new ComputeDeviceHandle(backendId, deviceId, providerGeneration, tenantDomain);
    }

    static ComputeQueueHandle issueQueue(
            ComputeDeviceHandle device,
            long generation) {
        return new ComputeQueueHandle(device, generation);
    }

    static <T> ComputeBufferHandle<T> bindBuffer(
            ComputeDeviceHandle device,
            long allocationGeneration,
            long byteLength,
            long alignment,
            String typeTag,
            String ownerDomain) {
        return new ComputeBufferHandle<>(
                device, allocationGeneration, byteLength, alignment, typeTag, ownerDomain);
    }

    static ComputeFenceHandle beginUpload(
            ComputeBufferHandle<?> buffer,
            ComputeDeviceHandle expectedDevice,
            String expectedOwnerDomain,
            long offset,
            long length) {
        return buffer.beginTransfer(
                expectedDevice, expectedOwnerDomain, offset, length,
                ComputeFenceHandle.Direction.UPLOAD);
    }

    static ComputeFenceHandle beginDownload(
            ComputeBufferHandle<?> buffer,
            ComputeDeviceHandle expectedDevice,
            String expectedOwnerDomain,
            long offset,
            long length) {
        return buffer.beginTransfer(
                expectedDevice, expectedOwnerDomain, offset, length,
                ComputeFenceHandle.Direction.DOWNLOAD);
    }

    static void markSubmitted(ComputeBufferHandle<?> buffer, ComputeFenceHandle fence) {
        buffer.markSubmitted(fence);
    }

    static void abortBeforeEnqueue(ComputeBufferHandle<?> buffer, ComputeFenceHandle fence) {
        buffer.abortBeforeEnqueue(fence);
    }

    static void complete(ComputeBufferHandle<?> buffer, ComputeFenceHandle fence, boolean succeeded) {
        buffer.complete(fence, succeeded);
    }

    static void cancel(ComputeBufferHandle<?> buffer, ComputeFenceHandle fence) {
        buffer.cancel(fence);
    }

    static void acknowledgeCancelled(ComputeBufferHandle<?> buffer, ComputeFenceHandle fence) {
        buffer.acknowledgeCancelled(fence);
    }


    static ComputeFencePair beginKernelPair(
            ComputeBufferHandle<?> input,
            ComputeBufferHandle<?> output,
            ComputeDeviceHandle expectedDevice,
            String expectedOwnerDomain,
            long length) {
        return ComputeBufferHandle.beginKernelPair(
                input, output, expectedDevice, expectedOwnerDomain, length);
    }

    static void markPairSubmitted(
            ComputeBufferHandle<?> input,
            ComputeBufferHandle<?> output,
            ComputeFencePair pair) {
        ComputeBufferHandle.markPairSubmitted(input, output, pair);
    }

    static void abortPairBeforeEnqueue(
            ComputeBufferHandle<?> input,
            ComputeBufferHandle<?> output,
            ComputeFencePair pair) {
        ComputeBufferHandle.abortPairBeforeEnqueue(input, output, pair);
    }

    static void completePair(
            ComputeBufferHandle<?> input,
            ComputeBufferHandle<?> output,
            ComputeFencePair pair,
            boolean succeeded) {
        ComputeBufferHandle.completePair(input, output, pair, succeeded);
    }

    static void cancelPair(
            ComputeBufferHandle<?> input,
            ComputeBufferHandle<?> output,
            ComputeFencePair pair) {
        ComputeBufferHandle.cancelPair(input, output, pair);
    }

    static void acknowledgeCancelledPair(
            ComputeBufferHandle<?> input,
            ComputeBufferHandle<?> output,
            ComputeFencePair pair) {
        ComputeBufferHandle.acknowledgeCancelledPair(input, output, pair);
    }

    static void retire(
            ComputeBufferHandle<?> buffer,
            ComputeDeviceHandle expectedDevice,
            String expectedOwnerDomain) {
        buffer.retire(expectedDevice, expectedOwnerDomain);
    }
}
