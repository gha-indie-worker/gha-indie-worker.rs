package dev.oreslang.runtime;

import dev.oreslang.net.NativeSocketBridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Capability-gated OS networking primitives for the Oreslang stdlib.
 *
 * This is deliberately not a public FFI facility. Guest code reaches this
 * object only through the reserved native_net builtin, and every operation
 * requires NETWORK from both the context and the current actor policy.
 * HTTP, URI and other protocol semantics belong in .ores source.
 */
public final class NativeNetBuiltin implements BuiltinValue {
    private final OresContext context;

    public NativeNetBuiltin(OresContext context) {
        this.context = context;
    }

    @Override
    public Object member(String name) {
        return switch (name) {
            case "connect" -> (BuiltinCallable) args -> {
                require(args, 3, name);
                admit(name);
                return yieldIo(() -> NativeSocketBridge.connect(str(args, 0), integer(args, 1), integer(args, 2)));
            };
            case "listen" -> (BuiltinCallable) args -> {
                require(args, 4, name); admit(name);
                return yieldIo(() -> NativeSocketBridge.listen(
                        str(args, 0), integer(args, 1), integer(args, 2), bool(args, 3)));
            };
            case "accept" -> (BuiltinCallable) args -> {
                require(args, 1, name); admit(name);
                return yieldIo(() -> NativeSocketBridge.accept(integer64(args, 0)));
            };
            case "read" -> (BuiltinCallable) args -> {
                require(args, 4, name); admit(name);
                NativeValues.ListValue target = byteList(args.get(1), name);
                byte[] bytes = toBytes(target);
                int count = yieldIo(() -> NativeSocketBridge.read(
                        integer64(args, 0), bytes, integer(args, 2), integer(args, 3)));
                if (count > 0) {
                    for (int i = 0; i < bytes.length; i++) target.set(i, (long) (bytes[i] & 0xff));
                }
                return (long) count;
            };
            case "write" -> (BuiltinCallable) args -> {
                require(args, 4, name); admit(name);
                byte[] bytes = toBytes(byteList(args.get(1), name));
                return (long) yieldIo(() -> NativeSocketBridge.write(
                        integer64(args, 0), bytes, integer(args, 2), integer(args, 3)));
            };
            case "close" -> (BuiltinCallable) args -> {
                require(args, 1, name); admit(name);
                yieldIoVoid(() -> NativeSocketBridge.close(integer64(args, 0)));
                return OresNull.INSTANCE;
            };
            case "resolve_all" -> (BuiltinCallable) args -> {
                require(args, 1, name); admit(name);
                String[] values = yieldIo(() -> NativeSocketBridge.resolveAll(str(args, 0)));
                NativeValues.ListValue result = new NativeValues.ListValue(context);
                for (String value : values) result.add(value);
                return result;
            };
            default -> throw new IllegalArgumentException("unknown native_net primitive " + name);
        };
    }

    private void admit(String operation) {
        context.requireCapability(IsolatePolicy.Capability.NETWORK, "native_net." + operation);
    }

    private static void require(List<Object> args, int count, String operation) {
        if (args.size() != count) {
            throw new IllegalArgumentException("native_net." + operation + " expects " + count + " arguments");
        }
    }

    private static String str(List<Object> args, int index) {
        if (args.get(index) instanceof String value) return value;
        throw new IllegalArgumentException("native_net expects String argument " + index);
    }

    private static int integer(List<Object> args, int index) {
        long value = integer64(args, index);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException("integer out of range");
        return (int) value;
    }

    private static long integer64(List<Object> args, int index) {
        if (args.get(index) instanceof Number value) return value.longValue();
        throw new IllegalArgumentException("native_net expects integer argument " + index);
    }

    private static boolean bool(List<Object> args, int index) {
        if (args.get(index) instanceof Boolean value) return value;
        throw new IllegalArgumentException("native_net expects Bool argument " + index);
    }

    private static NativeValues.ListValue byteList(Object value, String operation) {
        if (value instanceof NativeValues.ListValue list) return list;
        throw new IllegalArgumentException("native_net." + operation + " expects List<int>");
    }

    private static byte[] toBytes(NativeValues.ListValue values) {
        byte[] result = new byte[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Object item = values.get(i);
            if (!(item instanceof Number number) || number.longValue() < 0 || number.longValue() > 255) {
                throw new IllegalArgumentException("native_net byte arrays contain integers 0..255");
            }
            result[i] = (byte) number.intValue();
        }
        return result;
    }

    private static <T> T yieldIo(IOSupplier<T> supplier) {
        try { return supplier.get(); }
        catch (IOException error) { throw new IllegalStateException(error.getMessage(), error); }
    }

    private static void yieldIoVoid(IORunnable runnable) {
        try { runnable.run(); }
        catch (IOException error) { throw new IllegalStateException(error.getMessage(), error); }
    }

    @FunctionalInterface private interface IOSupplier<T> { T get() throws IOException; }
    @FunctionalInterface private interface IORunnable { void run() throws IOException; }
}
