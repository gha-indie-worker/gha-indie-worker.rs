package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Public Oreslang factories/namespaces backed only by liborescore primitives. */
public final class NativeRuntimeBuiltins {
    private NativeRuntimeBuiltins() { }

    public record ListFactory(OresContext context) implements BuiltinValue {
        @Override public Object member(String name) {
            return switch (name) {
                case "new" -> (BuiltinCallable) args -> {
                    NativeValues.ListValue result = new NativeValues.ListValue(context);
                    for (Object arg : args) result.add(arg);
                    return result;
                };
                case "of" -> (BuiltinCallable) args -> {
                    NativeValues.ListValue result = new NativeValues.ListValue(context);
                    for (Object arg : args) result.add(arg);
                    return result;
                };
                default -> throw new IllegalArgumentException("unknown List factory member " + name);
            };
        }
    }

    public record MapFactory(OresContext context) implements BuiltinValue {
        @Override public Object member(String name) {
            if (!name.equals("new")) throw new IllegalArgumentException("unknown Map factory member " + name);
            return (BuiltinCallable) args -> {
                NativeValues.requireArity(args, 0, "Map.new");
                return new NativeValues.MapValue(context);
            };
        }
    }

    public record FileFactory(OresContext context) implements BuiltinValue {
        @Override public Object member(String name) {
            return switch (name) {
                case "open" -> (BuiltinCallable) this::open;
                case "read_text" -> (BuiltinCallable) this::readText;
                case "write_text" -> (BuiltinCallable) this::writeText;
                default -> throw new IllegalArgumentException("unknown File member " + name);
            };
        }

        private Object open(List<Object> args) {
            NativeValues.requireArity(args, 2, "File.open");
            String path = string(args.get(0), "File.open path");
            String mode = string(args.get(1), "File.open mode");
            requireFileCapabilities(context, mode, "File.open");
            try { return new FileValue(context, NativeCoreBridge.fileOpen(path, mode), mode); }
            catch (IOException failure) { throw new IllegalStateException("File.open: " + failure.getMessage(), failure); }
        }

        private Object readText(List<Object> args) {
            NativeValues.requireArity(args, 1, "File.read_text");
            context.requireCapability(IsolatePolicy.Capability.FILESYSTEM_READ, "File.read_text");
            try (FileValue file = new FileValue(context,
                    NativeCoreBridge.fileOpen(string(args.getFirst(), "File.read_text path"), "r"), "r")) {
                return new String(file.readBytes(Integer.MAX_VALUE), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new IllegalStateException("File.read_text: " + failure.getMessage(), failure);
            }
        }

        private Object writeText(List<Object> args) {
            NativeValues.requireArity(args, 2, "File.write_text");
            context.requireCapability(IsolatePolicy.Capability.FILESYSTEM_WRITE, "File.write_text");
            try (FileValue file = new FileValue(context,
                    NativeCoreBridge.fileOpen(string(args.get(0), "File.write_text path"), "w"), "w")) {
                byte[] bytes = string(args.get(1), "File.write_text content").getBytes(StandardCharsets.UTF_8);
                file.write(bytes);
                file.flush();
                return (long) bytes.length;
            } catch (IOException failure) {
                throw new IllegalStateException("File.write_text: " + failure.getMessage(), failure);
            }
        }
    }

    public static final class FileValue implements BuiltinValue, AutoCloseable {
        private final OresContext context;
        private final String mode;
        private long handle;

        FileValue(OresContext context, long handle, String mode) {
            this.context = Objects.requireNonNull(context);
            this.handle = handle;
            this.mode = mode;
            context.registerNativeResource(this);
        }

        private long handle() {
            if (handle == 0L) throw new IllegalStateException("File is closed");
            return handle;
        }

        byte[] readBytes(int maxBytes) throws IOException {
            context.requireCapability(IsolatePolicy.Capability.FILESYSTEM_READ, "File.read");
            return NativeCoreBridge.fileRead(handle(), maxBytes);
        }

        int write(byte[] bytes) throws IOException {
            context.requireCapability(IsolatePolicy.Capability.FILESYSTEM_WRITE, "File.write");
            return NativeCoreBridge.fileWrite(handle(), bytes, 0, bytes.length);
        }

        void flush() throws IOException {
            context.requireCapability(IsolatePolicy.Capability.FILESYSTEM_WRITE, "File.flush");
            NativeCoreBridge.fileFlush(handle());
        }

        @Override public Object member(String name) {
            return switch (name) {
                case "read_text" -> (BuiltinCallable) args -> {
                    NativeValues.requireArity(args, 0, "File.read_text");
                    try { return new String(readBytes(Integer.MAX_VALUE), StandardCharsets.UTF_8); }
                    catch (IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
                };
                case "read_bytes" -> (BuiltinCallable) args -> {
                    if (args.size() > 1) throw new IllegalArgumentException("File.read_bytes expects 0 or 1 arguments");
                    int limit = args.isEmpty() ? Integer.MAX_VALUE : Math.toIntExact(number(args.getFirst(), "File.read_bytes max"));
                    try {
                        byte[] bytes = readBytes(limit);
                        NativeValues.ListValue result = new NativeValues.ListValue(context);
                        for (byte value : bytes) result.add((long) (value & 0xff));
                        return result;
                    } catch (IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
                };
                case "write_text" -> (BuiltinCallable) args -> {
                    NativeValues.requireArity(args, 1, "File.write_text");
                    byte[] bytes = string(args.getFirst(), "File.write_text content").getBytes(StandardCharsets.UTF_8);
                    try { return (long) write(bytes); }
                    catch (IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
                };
                case "write_bytes" -> (BuiltinCallable) args -> {
                    NativeValues.requireArity(args, 1, "File.write_bytes");
                    if (!(args.getFirst() instanceof NativeValues.ListValue values)) {
                        throw new IllegalArgumentException("File.write_bytes expects List<int>");
                    }
                    Object[] snapshot = values.snapshot();
                    byte[] bytes = new byte[snapshot.length];
                    for (int i = 0; i < snapshot.length; i++) {
                        long value = number(snapshot[i], "File.write_bytes byte");
                        if (value < 0 || value > 255) throw new IllegalArgumentException("File.write_bytes values must be 0..255");
                        bytes[i] = (byte) value;
                    }
                    try { return (long) write(bytes); }
                    catch (IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
                };
                case "seek" -> (BuiltinCallable) args -> {
                    if (args.size() < 1 || args.size() > 2) throw new IllegalArgumentException("File.seek expects offset and optional whence");
                    long offset = number(args.get(0), "File.seek offset");
                    int whence = args.size() == 1 ? 0 : Math.toIntExact(number(args.get(1), "File.seek whence"));
                    try { return NativeCoreBridge.fileSeek(handle(), offset, whence); }
                    catch (IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
                };
                case "size" -> (BuiltinCallable) args -> {
                    NativeValues.requireArity(args, 0, "File.size");
                    try { return NativeCoreBridge.fileSize(handle()); }
                    catch (IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
                };
                case "flush" -> (BuiltinCallable) args -> {
                    NativeValues.requireArity(args, 0, "File.flush");
                    try { flush(); return this; }
                    catch (IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
                };
                case "close" -> (BuiltinCallable) args -> {
                    NativeValues.requireArity(args, 0, "File.close");
                    close();
                    return OresNull.INSTANCE;
                };
                case "closed" -> handle == 0L;
                case "mode" -> mode;
                default -> throw new IllegalArgumentException("unknown File member " + name);
            };
        }

        @Override public void close() {
            long current = handle;
            if (current == 0L) return;
            handle = 0L;
            context.unregisterNativeResource(this);
            try { NativeCoreBridge.fileClose(current); }
            catch (IOException failure) { throw new IllegalStateException("File.close: " + failure.getMessage(), failure); }
        }
    }

    public record ThreadNamespace(OresContext context) implements BuiltinValue {
        @Override public Object member(String name) {
            return switch (name) {
                case "sleep" -> (BuiltinCallable) args -> {
                    NativeValues.requireArity(args, 1, "thread.sleep");
                    context.requireCapability(IsolatePolicy.Capability.THREAD_CREATE, "thread.sleep");
                    try { NativeCoreBridge.threadSleepMillis(number(args.getFirst(), "thread.sleep millis")); }
                    catch (InterruptedException interrupted) {
                        throw new IllegalStateException("native thread sleep interrupted", interrupted);
                    }
                    return OresNull.INSTANCE;
                };
                case "yield" -> (BuiltinCallable) args -> {
                    NativeValues.requireArity(args, 0, "thread.yield");
                    context.requireCapability(IsolatePolicy.Capability.THREAD_CREATE, "thread.yield");
                    NativeCoreBridge.threadYield();
                    return OresNull.INSTANCE;
                };
                case "current_id" -> (BuiltinCallable) args -> {
                    NativeValues.requireArity(args, 0, "thread.current_id");
                    context.requireCapability(IsolatePolicy.Capability.THREAD_CREATE, "thread.current_id");
                    return NativeCoreBridge.threadCurrentId();
                };
                default -> throw new IllegalArgumentException("unknown native thread member " + name);
            };
        }
    }

    private static void requireFileCapabilities(OresContext context, String mode, String api) {
        boolean read = mode.contains("r") || mode.contains("+");
        boolean write = mode.contains("w") || mode.contains("a") || mode.contains("+");
        if (!read && !write) throw new IllegalArgumentException("unsupported File mode '" + mode + "'");
        if (read) context.requireCapability(IsolatePolicy.Capability.FILESYSTEM_READ, api);
        if (write) context.requireCapability(IsolatePolicy.Capability.FILESYSTEM_WRITE, api);
    }

    private static String string(Object value, String api) {
        if (value instanceof String string) return string;
        throw new IllegalArgumentException(api + " expects String");
    }

    private static long number(Object value, String api) {
        if (value instanceof Number number) return number.longValue();
        throw new IllegalArgumentException(api + " expects integer");
    }
}
