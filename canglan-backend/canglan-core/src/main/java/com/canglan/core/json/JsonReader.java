package com.canglan.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 递归下降 JSON 解析器（字符串 → JsonValue）。零依赖，支持 UTF-8 中文与标准转义。
 */
public final class JsonReader {

    private final String src;
    private int pos;

    private JsonReader(String src) {
        this.src = src;
        this.pos = 0;
    }

    public static JsonValue parse(String text) {
        JsonReader r = new JsonReader(text);
        r.skipWhitespace();
        JsonValue v = r.parseValue();
        r.skipWhitespace();
        if (r.pos < r.src.length()) {
            throw new IllegalArgumentException("JSON 末尾存在多余内容 @ " + r.pos);
        }
        return v;
    }

    private JsonValue parseValue() {
        skipWhitespace();
        if (pos >= src.length()) throw new IllegalArgumentException("JSON 意外结束");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> JsonValue.ofString(parseString());
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private JsonValue parseObject() {
        expect('{');
        Map<String, JsonValue> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') { pos++; return JsonValue.ofObject(map); }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            JsonValue value = parseValue();
            map.put(key, value);
            skipWhitespace();
            char c = next();
            if (c == ',') continue;
            if (c == '}') break;
            throw new IllegalArgumentException("对象中期望 ',' 或 '}' @ " + (pos - 1));
        }
        return JsonValue.ofObject(map);
    }

    private JsonValue parseArray() {
        expect('[');
        List<JsonValue> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') { pos++; return JsonValue.ofArray(list); }
        while (true) {
            list.add(parseValue());
            skipWhitespace();
            char c = next();
            if (c == ',') continue;
            if (c == ']') break;
            throw new IllegalArgumentException("数组中期望 ',' 或 ']' @ " + (pos - 1));
        }
        return JsonValue.ofArray(list);
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') break;
            if (c == '\\') {
                char esc = next();
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) throw new IllegalArgumentException("非法 \\u 转义");
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("非法转义字符: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private JsonValue parseBoolean() {
        if (src.startsWith("true", pos)) { pos += 4; return JsonValue.ofBoolean(true); }
        if (src.startsWith("false", pos)) { pos += 5; return JsonValue.ofBoolean(false); }
        throw new IllegalArgumentException("非法布尔值 @ " + pos);
    }

    private JsonValue parseNull() {
        if (src.startsWith("null", pos)) { pos += 4; return JsonValue.ofNull(); }
        throw new IllegalArgumentException("非法 null @ " + pos);
    }

    private JsonValue parseNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < src.length() && isNumberChar(src.charAt(pos))) pos++;
        String token = src.substring(start, pos);
        if (token.isEmpty() || token.equals("-")) {
            throw new IllegalArgumentException("非法数字 @ " + start);
        }
        try {
            return JsonValue.ofNumber(Double.parseDouble(token));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法数字: " + token, e);
        }
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }

    private char peek() {
        if (pos >= src.length()) throw new IllegalArgumentException("JSON 意外结束");
        return src.charAt(pos);
    }

    private char next() {
        if (pos >= src.length()) throw new IllegalArgumentException("JSON 意外结束");
        return src.charAt(pos++);
    }

    private void expect(char c) {
        char actual = next();
        if (actual != c) throw new IllegalArgumentException("期望 '" + c + "' 但得到 '" + actual + "' @ " + (pos - 1));
    }
}
