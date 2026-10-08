package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class FailureKindTest {
    @Test
    void classificationsDoNotMislabelUnrelatedHostFailuresAsGuestThrows() {
        assertEquals(FailureKind.THROW, FailureKind.classify(new OresFailure.Throw("recoverable")));
        assertEquals(FailureKind.RAISE, FailureKind.classify(new OresFailure.Raise("escape")));
        assertEquals(FailureKind.PANIC, FailureKind.classify(new OresFailure.Panic("fatal actor")));
        assertEquals(FailureKind.CANCELLATION,
                FailureKind.classify(new CancellationException("cancel")));
        assertEquals(FailureKind.VM_FATAL, FailureKind.classify(new OutOfMemoryError("vm fatal")));
        assertEquals(FailureKind.HOST_FAULT,
                FailureKind.classify(new IllegalStateException("invariant")));
    }
}
