package dev.oreslang.runtime.compute;

public final class ComputeG2AuthoritySmoke {
    private static void reject(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException | IllegalStateException expected) {
            // fail closed
        }
    }

    public static void main(String[] args) {
        var gpu = ComputeAuthority.issueDevice("opencl", "gpu0", 1, "tenant");
        var buffer = ComputeAuthority.<Byte>bindBuffer(
                gpu, 1, 256, 16, "u8", "actor:1");

        var reserved = ComputeAuthority.beginUpload(
                buffer, gpu, "actor:1", 0, 32);
        reject(() -> ComputeAuthority.complete(buffer, reserved, true),
                "reserved transfer completed before submit");
        reject(() -> ComputeAuthority.cancel(buffer, reserved),
                "reserved transfer cancelled as if submitted");
        ComputeAuthority.abortBeforeEnqueue(buffer, reserved);
        if (buffer.state() != ComputeBufferHandle.State.IDLE) {
            throw new AssertionError("pre-enqueue abort did not restore idle");
        }
        reject(() -> ComputeAuthority.abortBeforeEnqueue(buffer, reserved),
                "stale pre-enqueue abort accepted");

        var submitted = ComputeAuthority.beginUpload(
                buffer, gpu, "actor:1", 32, 32);
        ComputeAuthority.markSubmitted(buffer, submitted);
        reject(() -> ComputeAuthority.abortBeforeEnqueue(buffer, submitted),
                "submitted transfer used pre-enqueue abort");
        ComputeAuthority.complete(buffer, submitted, true);

        var cancelled = ComputeAuthority.beginDownload(
                buffer, gpu, "actor:1", 0, 16);
        ComputeAuthority.markSubmitted(buffer, cancelled);
        ComputeAuthority.cancel(buffer, cancelled);
        reject(() -> ComputeAuthority.retire(buffer, gpu, "actor:1"),
                "cancel implied quiescence");
        ComputeAuthority.acknowledgeCancelled(buffer, cancelled);
        ComputeAuthority.retire(buffer, gpu, "actor:1");
        if (buffer.state() != ComputeBufferHandle.State.RETIRED) {
            throw new AssertionError("retirement terminal state missing");
        }
        reject(() -> ComputeAuthority.retire(buffer, gpu, "actor:1"),
                "duplicate retirement accepted");

        System.out.println("compute-g2-authority-smoke-ok");
    }
}
