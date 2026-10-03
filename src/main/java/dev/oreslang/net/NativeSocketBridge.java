package dev.oreslang.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JNI boundary for Oreslang networking.
 *
 * This class deliberately does not use java.net.Socket, ServerSocket,
 * SocketChannel, or java.net.http. All transport I/O crosses directly into the
 * platform socket API through liboresnet.
 */
public final class NativeSocketBridge {
    private static final String LIBRARY = "oresnet";

    static {
        loadNativeLibrary();
    }

    private NativeSocketBridge() { }

    private static void loadNativeLibrary() {
        String explicit = System.getProperty("ores.net.native.path");
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

    public static native long connect(String host, int port, int timeoutMillis) throws IOException;
    public static native long listen(String host, int port, int backlog, boolean reuseAddress) throws IOException;
    public static native long accept(long fd) throws IOException;

    public static native int read(long fd, byte[] bytes, int offset, int length) throws IOException;
    public static native int write(long fd, byte[] bytes, int offset, int length) throws IOException;
    public static native int available(long fd) throws IOException;

    public static native void shutdownInput(long fd) throws IOException;
    public static native void shutdownOutput(long fd) throws IOException;
    public static native void close(long fd) throws IOException;

    public static native String remoteAddress(long fd) throws IOException;
    public static native String localAddress(long fd) throws IOException;
    public static native int remotePort(long fd) throws IOException;
    public static native int localPort(long fd) throws IOException;
    public static native String[] resolveAll(String host) throws IOException;

    public static native void setTcpNoDelay(long fd, boolean enabled) throws IOException;
    public static native boolean getTcpNoDelay(long fd) throws IOException;
    public static native void setKeepAlive(long fd, boolean enabled) throws IOException;
    public static native boolean getKeepAlive(long fd) throws IOException;
    public static native void setReuseAddress(long fd, boolean enabled) throws IOException;
    public static native boolean getReuseAddress(long fd) throws IOException;
    public static native void setReceiveBufferSize(long fd, int bytes) throws IOException;
    public static native int getReceiveBufferSize(long fd) throws IOException;
    public static native void setSendBufferSize(long fd, int bytes) throws IOException;
    public static native int getSendBufferSize(long fd) throws IOException;
    public static native void setSoTimeout(long fd, int timeoutMillis) throws IOException;
    public static native int getSoTimeout(long fd) throws IOException;
}
