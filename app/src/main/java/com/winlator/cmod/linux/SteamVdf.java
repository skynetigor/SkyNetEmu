package com.winlator.cmod.linux;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reader for Steam's binary key-value format (the body of {@code appinfo.vdf} and {@code packageinfo.vdf}).
 *
 * <p>A node is a {@code Map<String, Object>} whose values are {@code String}, {@code Long}, {@code Double} or again a map.
 * No Android classes are used: the reader runs on the host in unit tests.
 */
final class SteamVdf {
    private static final int TYPE_MAP = 0, TYPE_STRING = 1, TYPE_INT = 2, TYPE_FLOAT = 3, TYPE_POINTER = 4, TYPE_WIDE = 5,
        TYPE_COLOR = 6, TYPE_UINT64 = 7, TYPE_END = 8, TYPE_INT64 = 10;

    private SteamVdf() {
    }

    /** Reads one map from {@code buffer} (little endian). Keys come from {@code strings} when given, else are inline text. */
    static Map<String, Object> readMap(ByteBuffer buffer, String[] strings) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        while (true) {
            if (!buffer.hasRemaining()) throw new IOException("Truncated key-value data");
            int type = buffer.get() & 0xff;
            if (type == TYPE_END) return out;
            String key = strings != null ? tableString(buffer, strings) : cString(buffer);
            switch (type) {
                case TYPE_MAP: out.put(key, readMap(buffer, strings)); break;
                case TYPE_STRING: out.put(key, cString(buffer)); break;
                case TYPE_INT: case TYPE_POINTER: out.put(key, (long) buffer.getInt()); break;
                case TYPE_COLOR: out.put(key, buffer.getInt() & 0xffffffffL); break;
                case TYPE_FLOAT: out.put(key, (double) buffer.getFloat()); break;
                case TYPE_UINT64: case TYPE_INT64: out.put(key, buffer.getLong()); break;
                case TYPE_WIDE: out.put(key, wideString(buffer)); break;
                default: throw new IOException("Unknown key-value type " + type);
            }
        }
    }

    static ByteBuffer little(byte[] data, int offset, int length) {
        return ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static String tableString(ByteBuffer buffer, String[] strings) throws IOException {
        int index = buffer.getInt();
        if (index < 0 || index >= strings.length) throw new IOException("Bad string index " + index);
        return strings[index];
    }

    static String cString(ByteBuffer buffer) throws IOException {
        int start = buffer.position();
        while (buffer.hasRemaining() && buffer.get() != 0) { /* scan to the terminator */ }
        if (buffer.position() == start || buffer.get(buffer.position() - 1) != 0) throw new IOException("Unterminated string");
        return new String(buffer.array(), buffer.arrayOffset() + start, buffer.position() - start - 1, StandardCharsets.UTF_8);
    }

    private static String wideString(ByteBuffer buffer) {
        StringBuilder text = new StringBuilder();
        while (buffer.remaining() >= 2) {
            char c = buffer.getChar();
            if (c == 0) break;
            text.append(c);
        }
        return text.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object node, String... path) {
        Object current = node;
        for (String key : path) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(key);
        }
        return current instanceof Map ? (Map<String, Object>) current : null;
    }

    static String string(Map<String, Object> node, String key, String fallback) {
        Object value = node == null ? null : node.get(key);
        if (value == null || value instanceof Map) return fallback;
        return value instanceof Double && ((Double) value) % 1 == 0 ? String.valueOf(((Double) value).longValue()) : String.valueOf(value);
    }

    /** Numbers are stored as integers or as decimal text, depending on the field and the app. */
    static long number(Map<String, Object> node, String key, long fallback) {
        Object value = node == null ? null : node.get(key);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            }
            catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
