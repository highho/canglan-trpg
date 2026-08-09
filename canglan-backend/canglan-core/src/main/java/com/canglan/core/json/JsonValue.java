package com.canglan.core.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON 值节点（不可变）。零外部依赖，支持本项目全部 JSON 子集（含中文键）。
 * 类型：OBJECT / ARRAY / STRING / NUMBER / BOOLEAN / NULL。
 */
public final class JsonValue {

    public enum Kind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

    private final Kind kind;
    private final Map<String, JsonValue> object;
    private final List<JsonValue> array;
    private final String str;
    private final double num;
    private final boolean bool;

    private JsonValue(Kind kind, Map<String, JsonValue> object, List<JsonValue> array,
                      String str, double num, boolean bool) {
        this.kind = kind;
        this.object = object;
        this.array = array;
        this.str = str;
        this.num = num;
        this.bool = bool;
    }

    public static JsonValue ofObject(Map<String, JsonValue> map) {
        return new JsonValue(Kind.OBJECT, new LinkedHashMap<>(map), null, null, 0, false);
    }
    public static JsonValue ofArray(List<JsonValue> list) {
        return new JsonValue(Kind.ARRAY, null, List.copyOf(list), null, 0, false);
    }
    public static JsonValue ofString(String s) { return new JsonValue(Kind.STRING, null, null, s, 0, false); }
    public static JsonValue ofNumber(double n) { return new JsonValue(Kind.NUMBER, null, null, null, n, false); }
    public static JsonValue ofBoolean(boolean b) { return new JsonValue(Kind.BOOLEAN, null, null, null, 0, b); }
    public static JsonValue ofNull() { return new JsonValue(Kind.NULL, null, null, null, 0, false); }

    public Kind kind() { return kind; }
    public boolean isObject() { return kind == Kind.OBJECT; }
    public boolean isArray() { return kind == Kind.ARRAY; }
    public boolean isString() { return kind == Kind.STRING; }
    public boolean isNumber() { return kind == Kind.NUMBER; }
    public boolean isBoolean() { return kind == Kind.BOOLEAN; }
    public boolean isNull() { return kind == Kind.NULL; }

    public Map<String, JsonValue> asObject() {
        if (!isObject()) throw new IllegalStateException("非对象节点: " + kind);
        return Collections.unmodifiableMap(object);
    }
    public List<JsonValue> asArray() {
        if (!isArray()) throw new IllegalStateException("非数组节点: " + kind);
        return array;
    }
    public String asString() {
        if (kind == Kind.STRING) return str;
        if (kind == Kind.NUMBER) return String.valueOf(num);
        if (kind == Kind.BOOLEAN) return String.valueOf(bool);
        return null;
    }
    public double asDouble() {
        if (kind == Kind.NUMBER) return num;
        if (kind == Kind.STRING) return Double.parseDouble(str);
        throw new IllegalStateException("非数值节点: " + kind);
    }
    public int asInt() { return (int) asDouble(); }
    public boolean asBoolean() {
        if (kind == Kind.BOOLEAN) return bool;
        throw new IllegalStateException("非布尔节点: " + kind);
    }

    /** 对象取子节点；不存在返回 null。 */
    public JsonValue get(String key) {
        return isObject() ? object.get(key) : null;
    }
    public boolean has(String key) { return isObject() && object.containsKey(key); }

    public String getString(String key, String fallback) {
        JsonValue v = get(key);
        return (v != null && v.isString()) ? v.str : fallback;
    }
    public int getInt(String key, int fallback) {
        JsonValue v = get(key);
        return (v != null && v.isNumber()) ? v.asInt() : fallback;
    }
    public double getDouble(String key, double fallback) {
        JsonValue v = get(key);
        return (v != null && v.isNumber()) ? v.asDouble() : fallback;
    }
    public boolean getBoolean(String key, boolean fallback) {
        JsonValue v = get(key);
        return (v != null && v.isBoolean()) ? v.asBoolean() : fallback;
    }

    @Override
    public String toString() {
        return switch (kind) {
            case OBJECT -> object.toString();
            case ARRAY -> array.toString();
            case STRING -> "\"" + str + "\"";
            case NUMBER -> String.valueOf(num);
            case BOOLEAN -> String.valueOf(bool);
            case NULL -> "null";
        };
    }
}
