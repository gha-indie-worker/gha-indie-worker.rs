package dev.oreslang.runtime;

import org.graalvm.nativeimage.ImageInfo;

/** Native capability captured during image generation, never from launch-time flags. */
public final class NativeBuildMode {
    public enum Kind { JVM, AOT, HYBRID }
    private static final Kind KIND = imageBuildKind();

    private NativeBuildMode() { }

    private static Kind imageBuildKind() {
        if (!ImageInfo.inImageBuildtimeCode()) return Kind.JVM;
        String selected = System.getProperty("ores.native.build-mode");
        if ("aot".equals(selected)) return Kind.AOT;
        if ("hybrid".equals(selected)) return Kind.HYBRID;
        throw new IllegalStateException("Native builds require -Dores.native.build-mode=aot|hybrid");
    }

    public static Kind kind() { return KIND; }
    public static String defaultExecutionMode() {
        return switch (KIND) {
            case JVM -> "jit";
            case AOT -> "aot";
            case HYBRID -> "hybrid";
        };
    }

    public static void requireCompatible(ExecutionProfile profile) {
        requireCompatible(KIND, profile.mode());
    }

    static void requireCompatible(Kind built, ExecutionProfile.Mode requested) {
        if (built == Kind.AOT && requested != ExecutionProfile.Mode.AOT) {
            throw new IllegalArgumentException("This executable was built AOT-only; --mode="
                    + requested.name().toLowerCase(java.util.Locale.ROOT)
                    + " cannot enable runtime compilation. Rebuild with native-hybrid.");
        }
        if (built == Kind.HYBRID && requested == ExecutionProfile.Mode.JIT) {
            throw new IllegalArgumentException("Native hybrid executables accept --mode=hybrid or --mode=aot, not JVM --mode=jit");
        }
    }
}
