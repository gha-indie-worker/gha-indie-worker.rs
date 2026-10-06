package dev.oreslang.runtime;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Oreslang guest-visible collection/record values.
 *
 * Storage and mutation are native.  Java collections used below are temporary
 * interpreter views only; no guest List/Map/struct state is stored in them.
 */
public final class NativeValues {
    private NativeValues() { }

    private static int index(Object value, String api) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(api + " index must be an integer");
        }
        return Math.toIntExact(number.longValue());
    }

    private static int checkedIndex(int index, int size, boolean insertion, String api) {
        int max = insertion ? size : size - 1;
        if (index < 0 || index > max) {
            throw new IndexOutOfBoundsException(api + " index " + index + " out of range");
        }
        return index;
    }

    public static final class ListValue implements BuiltinValue, OresMutex.SharedState, AutoCloseable {
        private final OresContext context;
        private long handle;

        public ListValue(OresContext context) {
            this.context = Objects.requireNonNull(context);
            this.handle = NativeCoreBridge.listCreate();
            context.registerNativeResource(this);
        }

        public static ListValue of(OresContext context, Object... values) {
            ListValue list = new ListValue(context);
            for (Object value : values) list.add(value);
            return list;
        }

        private long handle() {
            if (handle == 0L) throw new IllegalStateException("List is closed");
            return handle;
        }

        public int size() { return NativeCoreBridge.listSize(handle()); }
        public Object get(int index) { return NativeCoreBridge.listGet(handle(), checkedIndex(index, size(), false, "List.get")); }
        public Object set(int index, Object value) { return NativeCoreBridge.listSet(handle(), checkedIndex(index, size(), false, "List.set"), value); }
        public void add(Object value) { NativeCoreBridge.listAdd(handle(), value); }
        public Object remove(int index) { return NativeCoreBridge.listRemove(handle(), checkedIndex(index, size(), false, "List.remove")); }
        public Object[] snapshot() { return NativeCoreBridge.listSnapshot(handle()); }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "length", "size" -> (long) size();
                case "get" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "List.get");
                    return get(index(args.getFirst(), "List.get"));
                };
                case "set" -> (BuiltinCallable) args -> {
                    requireArity(args, 2, "List.set");
                    return set(index(args.getFirst(), "List.set"), args.get(1));
                };
                case "push", "add" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "List.push");
                    add(args.getFirst());
                    return this;
                };
                case "pop" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "List.pop");
                    if (size() == 0) throw new IllegalStateException("cannot pop an empty List");
                    return remove(size() - 1);
                };
                case "remove" -> (BuiltinCallable) args -> {
                    requireArity(args, 1, "List.remove");
                    return remove(index(args.getFirst(), "List.remove"));
                };
                case "clear" -> (BuiltinCallable) args -> {
                    requireArity(args, 0, "List.clear");
                    NativeCoreBridge.listClear(handle());
                    return this;
                };
                default -> throw new IllegalArgumentException("unknown List member " + name);
            };
        }

        /** Internal interpreter view; guest state remains in liborescore. */
        public List<Object> interpreterView() {
            return new AbstractList<>() {
                @Override public Object get(int index) { return ListValue.this.get(index); }
                @Override public int size() { return ListValue.this.size(); }
                @Override public Object set(int index, Object element) { return ListValue.this.set(index, element); }
            };
        }

        @Override public Iterable<?> sharedStateChildren() { return Arrays.asList(snapshot()); }

        @Override
        public void close() {
            long current = handle;
            if (current == 0L) return;
            handle = 0L;
            context.unregisterNativeResource(this);
            NativeCoreBridge.listFree(current);
        }

        @Override public String toString() { return Arrays.toString(snapshot()); }
    }

    private abstract static class StringMapValue implements BuiltinValue, OresMutex.SharedState, AutoCloseable {
        final OresContext context;
        private long handle;
        private final boolean mutable;

        StringMapValue(OresContext context, boolean mutable) {
            this.context = Objects.requireNonNull(context);
            this.mutable = mutable;
            this.handle = NativeCoreBridge.mapCreate();
            context.registerNativeResource(this);
        }

        final long handle() {
            if (handle == 0L) throw new IllegalStateException("native map/struct is closed");
            return handle;
        }

        public final int size() { return NativeCoreBridge.mapSize(handle()); }
        public final boolean containsString(String key) { return NativeCoreBridge.mapContains(handle(), key); }
        public final Object getString(String key) {
            if (!containsString(key)) throw new IllegalArgumentException("unknown key/member " + key);
            return NativeCoreBridge.mapGet(handle(), key);
        }
        public final Object putString(String key, Object value) {
            if (!mutable) throw new IllegalArgumentException("value is immutable");
            return putStringUnchecked(key, value);
        }
        public final Object putStringUnchecked(String key, Object value) {
            return NativeCoreBridge.mapPut(handle(), Objects.requireNonNull(key), value);
        }
        public final Object[] valuesSnapshot() { return NativeCoreBridge.mapValues(handle()); }
        public final String[] keysSnapshot() { return NativeCoreBridge.mapKeys(handle()); }

        @Override public Iterable<?> sharedStateChildren() { return Arrays.asList(valuesSnapshot()); }

        @Override
        public void close() {
            long current = handle;
            if (current == 0L) return;
            handle = 0L;
            context.unregisterNativeResource(this);
            NativeCoreBridge.mapFree(current);
        }
    }

    /** Immutable structural object created by obj{static: fields}. */
    public static final class RecordValue extends StringMapValue {
        public RecordValue(OresContext context) { super(context, false); }
        public void initialize(String key, Object value) { putStringUnchecked(key, value); }
        @Override public Object member(String name) { return getString(name); }
        @Override public String toString() { return "obj" + renderStringMap(this); }
    }

    /** DynamicStruct<T>: arbitrary string keys with compiler-checked value type. */
    public static final class DynamicStructValue extends StringMapValue {
        public DynamicStructValue(OresContext context) { super(context, true); }
        @Override public Object member(String name) { return getString(name); }
        @Override public String toString() { return "DynamicStruct" + renderStringMap(this); }
    }

    /** Fixed-shape nominal Oreslang struct. Field types are enforced by the compiler. */
    public static final class StructValue extends StringMapValue {
        private final String typeName;
        public StructValue(OresContext context, String typeName) {
            super(context, true);
            this.typeName = Objects.requireNonNull(typeName);
        }
        public void initialize(String key, Object value) { putStringUnchecked(key, value); }
        @Override public Object member(String name) { return getString(name); }
        public String typeName() { return typeName; }
        @Override public String toString() { return typeName + renderStringMap(this); }
    }

    /** Native Map<K,V>. Keys are value-keyed Oreslang scalar values, not Java hash keys. */
    public static final class MapValue implements BuiltinValue, OresMutex.SharedState, AutoCloseable {
        private final StringMapValue storage;

        public MapValue(OresContext context) {
            storage = new DynamicStructValue(context);
        }

        private static String key(Object key) {
            if (key instanceof String value) return "s:" + value;
            if (key instanceof Boolean value) return value ? "b:1" : "b:0";
            if (key instanceof Byte || key instanceof Short || key instanceof Integer || key instanceof Long) {
                return "i:" + ((Number) key).longValue();
            }
            throw new IllegalArgumentException("Map keys must currently be String, Bool, or integral values");
        }

        public int size() { return storage.size(); }
        public boolean contains(Object key) { return storage.containsString(key(key)); }
        public Object get(Object key) {
            String encoded = key(key);
            if (!storage.containsString(encoded)) throw new IllegalArgumentException("unknown Map key");
            return storage.getString(encoded);
        }
        public Object put(Object key, Object value) { return storage.putString(key(key), value); }
        public Object remove(Object key) { return NativeCoreBridge.mapRemove(storage.handle(), key(key)); }

        @Override
        public Object member(String name) {
            return switch (name) {
                case "length", "size" -> (long) size();
                case "get" -> (BuiltinCallable) args -> { requireArity(args, 1, "Map.get"); return get(args.getFirst()); };
                case "contains", "has" -> (BuiltinCallable) args -> { requireArity(args, 1, "Map.contains"); return contains(args.getFirst()); };
                case "set", "put" -> (BuiltinCallable) args -> { requireArity(args, 2, "Map.set"); put(args.getFirst(), args.get(1)); return this; };
                case "remove" -> (BuiltinCallable) args -> { requireArity(args, 1, "Map.remove"); return remove(args.getFirst()); };
                case "clear" -> (BuiltinCallable) args -> { requireArity(args, 0, "Map.clear"); NativeCoreBridge.mapClear(storage.handle()); return this; };
                default -> throw new IllegalArgumentException("unknown Map member " + name);
            };
        }

        @Override public Iterable<?> sharedStateChildren() { return storage.sharedStateChildren(); }
        @Override public void close() { storage.close(); }
        @Override public String toString() { return "Map(size=" + size() + ")"; }
    }

    private static String renderStringMap(StringMapValue value) {
        String[] keys = value.keysSnapshot();
        Object[] values = value.valuesSnapshot();
        ArrayList<String> pairs = new ArrayList<>(keys.length);
        for (int i = 0; i < keys.length; i++) pairs.add(keys[i] + "=" + values[i]);
        return pairs.toString();
    }

    static void requireArity(List<Object> args, int count, String api) {
        if (args.size() != count) throw new IllegalArgumentException(api + " expects " + count + " argument(s)");
    }
}
