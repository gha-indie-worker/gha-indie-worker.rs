package dev.oreslang.dop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Host-only, bounded, immutable AoS/SoA reference storage for statically closed
 * scalar rows. This is not an Oreslang guest type, actor transport, or FFI ABI.
 *
 * <p>Both conversion directions snapshot caller input; there are no writable
 * aliases to row maps, column lists, or their immutable scalar payloads.</p>
 */
public final class SoaSnapshot {
    public static final int MAX_FIELDS = 64;
    public static final int MAX_CELLS = 1_000_000;
    public static final int MAX_FIELD_NAME_UNITS = 1024;
    private static final Set<Class<?>> SCALARS = Set.of(
            String.class, Boolean.class, Integer.class,
            Long.class, Float.class, Double.class);

    private final Map<String, Class<?>> shape;
    private final Map<String, List<Object>> columns;
    private final int size;

    private SoaSnapshot(Map<String, Class<?>> shape,
                        Map<String, List<Object>> columns, int size) {
        this.shape = Collections.unmodifiableMap(new LinkedHashMap<>(shape));
        this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        this.size = size;
    }

    public static SoaSnapshot fromRows(
            Map<String, Class<?>> schema, List<? extends Map<String, ?>> rows) {
        Map<String, Class<?>> types = validatedSchema(schema);
        Objects.requireNonNull(rows, "rows");
        checkCapacity(types.size(), rows.size());
        Map<String, List<Object>> builders = new LinkedHashMap<>();
        for (String key : types.keySet()) builders.put(key, new ArrayList<>(rows.size()));

        for (int i = 0; i < rows.size(); i++) {
            Map<String, ?> row = Objects.requireNonNull(rows.get(i), "row " + i);
            requireExactKeys(row.keySet(), types.keySet(), "row " + i);
            for (var member : types.entrySet()) {
                String key = member.getKey();
                Object value = Objects.requireNonNull(row.get(key),
                        "null value for field " + key + " in row " + i);
                if (!member.getValue().isInstance(value)) {
                    throw new IllegalArgumentException(
                            "wrong type for field " + key + " in row " + i);
                }
                builders.get(key).add(value);
            }
        }
        return new SoaSnapshot(types, freezeColumns(builders), rows.size());
    }

    public static SoaSnapshot fromColumns(
            Map<String, Class<?>> schema, Map<String, ? extends List<?>> supplied) {
        Map<String, Class<?>> types = validatedSchema(schema);
        Objects.requireNonNull(supplied, "columns");
        requireExactKeys(supplied.keySet(), types.keySet(), "columns");
        // Zero-column rows must be constructed using fromRows: their count
        // cannot be inferred from an empty collection of columns.
        if (types.isEmpty()) {
            throw new IllegalArgumentException(
                    "empty column schema cannot encode row count; use fromRows");
        }
        int count = -1;
        for (String key : types.keySet()) {
            List<?> values = Objects.requireNonNull(supplied.get(key), "column " + key);
            if (count < 0) count = values.size();
            if (values.size() != count) {
                throw new IllegalArgumentException("column length mismatch for field " + key);
            }
        }
        checkCapacity(types.size(), count);
        Map<String, List<Object>> copies = new LinkedHashMap<>();
        for (var field : types.entrySet()) {
            String key = field.getKey();
            List<?> values = supplied.get(key);
            List<Object> copy = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Object value = Objects.requireNonNull(values.get(i),
                        "null value for field " + key + " at index " + i);
                if (!field.getValue().isInstance(value)) {
                    throw new IllegalArgumentException(
                            "wrong type for field " + key + " at index " + i);
                }
                copy.add(value);
            }
            copies.put(key, List.copyOf(copy));
        }
        return new SoaSnapshot(types, copies, count);
    }

    public List<Map<String, Object>> toRows() {
        List<Map<String, Object>> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String key : shape.keySet()) {
                row.put(key, columns.get(key).get(i));
            }
            rows.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(rows);
    }

    public Map<String, Class<?>> schema() { return shape; }
    public Map<String, List<Object>> columns() { return columns; }
    public List<Object> column(String name) {
        List<Object> found = columns.get(name);
        if (found == null) throw new IllegalArgumentException("unknown column: " + name);
        return found;
    }
    public int rowCount() { return size; }

    private static Map<String, Class<?>> validatedSchema(Map<String, Class<?>> schema) {
        Objects.requireNonNull(schema, "schema");
        if (schema.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("too many fields");
        }
        // Canonical host order must not depend on HashMap iteration order.
        Map<String, Class<?>> validated = new TreeMap<>();
        for (var field : schema.entrySet()) {
            String name = Objects.requireNonNull(field.getKey(), "field name");
            // Spaces, punctuation, Unicode, and even empty quoted keys are data.
            if (name.length() > MAX_FIELD_NAME_UNITS) {
                throw new IllegalArgumentException("field name too long");
            }
            // Never normalize valid Unicode spelling; reject malformed UTF-16.
            for (int i = 0; i < name.length(); i++) {
                char code = name.charAt(i);
                if (code == 0 || Character.isLowSurrogate(code)) {
                    throw new IllegalArgumentException("invalid field name Unicode");
                }
                if (Character.isHighSurrogate(code)) {
                    if (++i >= name.length() || !Character.isLowSurrogate(name.charAt(i))) {
                        throw new IllegalArgumentException("invalid field name Unicode");
                    }
                }
            }
            Class<?> type = Objects.requireNonNull(field.getValue(), "field type");
            if (!SCALARS.contains(type)) {
                throw new IllegalArgumentException("unsupported scalar field " + name);
            }
            validated.put(name, type);
        }
        return new LinkedHashMap<>(validated);
    }

    private static Map<String, List<Object>> freezeColumns(
            Map<String, List<Object>> source) {
        Map<String, List<Object>> frozen = new LinkedHashMap<>();
        source.forEach((key, values) -> frozen.put(key, List.copyOf(values)));
        return frozen;
    }

    private static void checkCapacity(int fields, int rows) {
        if (rows < 0 || ((long) Math.max(fields, 1) * rows) > MAX_CELLS) {
            throw new IllegalArgumentException("SoA capacity exceeded");
        }
    }

    private static void requireExactKeys(
            Set<?> supplied, Set<String> expected, String where) {
        if (!supplied.equals(expected)) {
            throw new IllegalArgumentException("missing or extra fields in " + where);
        }
    }
}
