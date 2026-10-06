package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Thin JNI declarations for Oreslang-owned runtime primitives.
 *
 * No collection, filesystem, or thread semantics live in this class.  Those
 * operations are implemented by liborescore; Java only marshals Truffle values
 * across the native ABI.
 */
public final class NativeCoreBridge {
    private static final String LIBRARY = "orescore";

    static { loadNativeLibrary(); }

    private NativeCoreBridge() { }

    public static void ensureLoaded() { }

    private static void loadNativeLibrary() {
        String explicit = System.getProperty("ores.core.native.path");
        if (explicit != null && !explicit.isBlank()) {
            System.load(Path.of(explicit).toAbsolutePath().normalize().toString());
            return;
        }
        Path local = Path.of("target", "native", System.mapLibraryName(LIBRARY))
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(local)) {
            System.load(local.toString());
            return;
        }
        System.loadLibrary(LIBRARY);
    }

    static native long listCreate();
    static native void listFree(long handle);
    static native int listSize(long handle);
    static native Object listGet(long handle, int index);
    static native Object listSet(long handle, int index, Object value);
    static native void listAdd(long handle, Object value);
    static native Object listRemove(long handle, int index);
    static native void listClear(long handle);
    static native Object[] listSnapshot(long handle);

    static native long mapCreate();
    static native void mapFree(long handle);
    static native int mapSize(long handle);
    static native boolean mapContains(long handle, String key);
    static native Object mapGet(long handle, String key);
    static native Object mapPut(long handle, String key, Object value);
    static native Object mapRemove(long handle, String key);
    static native String[] mapKeys(long handle);
    static native Object[] mapValues(long handle);
    static native void mapClear(long handle);

    static native long fileOpen(String path, String mode) throws IOException;
    static native byte[] fileRead(long handle, int maxBytes) throws IOException;
    static native int fileWrite(long handle, byte[] bytes, int offset, int length) throws IOException;
    static native long fileSeek(long handle, long offset, int whence) throws IOException;
    static native long fileSize(long handle) throws IOException;
    static native void fileFlush(long handle) throws IOException;
    static native void fileClose(long handle) throws IOException;

    static native void threadSleepMillis(long millis) throws InterruptedException;
    static native void threadYield();
    static native long threadCurrentId();
}
