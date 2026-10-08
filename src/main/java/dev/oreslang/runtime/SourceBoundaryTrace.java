package dev.oreslang.runtime;

/** Bounded source diagnostics that never capture guest frames, receivers or heaps. */
public final class SourceBoundaryTrace extends RuntimeException {
    private static final int MAX_BOUNDARIES = 64;

    private SourceBoundaryTrace(String codeUnit, String callable, String boundary) {
        super("ores " + boundary + " " + callable + " [" + codeUnit + "]", null, false, false);
    }

    /** Preserve the original failure type, cause and message, including cancellation. */
    public static <T extends Throwable> T record(
            T failure, String codeUnit, String callable, String boundary) {
        synchronized (failure) {
            if (failure.getSuppressed().length < MAX_BOUNDARIES) {
                failure.addSuppressed(new SourceBoundaryTrace(codeUnit, callable, boundary));
            }
        }
        return failure;
    }
}
