package com.canglan.core.json;

import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON 写出器（与 JsonReader/JsonValue 配套，零外部依赖）。
 * 支持 Map / List / String / Number / Boolean / null 的递归序列化，中文原样输出。
 */
public final class JsonWriter {

    private JsonWriter() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.toString();
    }

    /** indent < 0 表示紧凑模式。 */
    public static String write(Object value, int indent) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, indent);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, (Map<String, Object>) map, indent);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list, indent);
        } else if (value instanceof Iterable<?> it) {
            writeArray(sb, toList(it), indent);
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static List<Object> toList(Iterable<?> it) {
        List<Object> list = new java.util.ArrayList<>();
        for (Object o : it) list.add(o);
        return list;
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            newline(sb, indent + 1);
            writeString(sb, e.getKey());
            sb.append(indent >= 0 ? ": " : ":");
            writeValue(sb, e.getValue(), indent >= 0 ? indent + 1 : -1);
        }
        newline(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) sb.append(',');
            first = false;
            newline(sb, indent + 1);
            writeValue(sb, item, indent >= 0 ? indent + 1 : -1);
        }
        newline(sb, indent);
        sb.append(']');
    }

    private static void newline(StringBuilder sb, int indent) {
        if (indent < 0) return;
        sb.append('\n');
        sb.append("  ".repeat(indent));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
