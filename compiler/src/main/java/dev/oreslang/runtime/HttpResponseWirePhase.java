package dev.oreslang.runtime;

import java.io.IOException;

/**
 * Header-emission state under the owning NativeHttp.State monitor.
 *
 * This object is not independently thread safe: caller serializes ALL methods
 * with the same lock that guards request terminal state and actor ownership.
 * It distinguishes logical response reservation, an irreversible first wire
 * attempt, and sendResponseHeaders returning successfully.
 */
final class HttpResponseWirePhase {
    private boolean started;
    private boolean sent;

    boolean begin(boolean reserved, boolean terminal) {
        if (terminal || !reserved || started) return false;
        started = true;
        return true;
    }

    void markSent() {
        if (!started || sent)
            throw new IllegalStateException("HTTP header send was not started or already recorded");
        sent = true;
    }

    boolean started() { return started; }
    boolean sent() { return sent; }

    boolean maySendError(boolean terminal, boolean operationBusy) {
        // After the first wire attempt there is no second legal response status,
        // even if the original send threw before confirming success.
        return !terminal && !started && !operationBusy;
    }

    static void requireLiveFinish(boolean terminal) throws IOException {
        if (terminal)
            throw new IOException("HTTP request was aborted before response completion");
    }
}
