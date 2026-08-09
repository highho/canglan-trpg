package com.canglan.core.effect;

/** 数值运算符。对应 C# Operator。 */
public enum Operator {
    ADD, MULTIPLY, SET;

    public static Operator parse(String raw) {
        return switch (raw.toUpperCase()) {
            case "ADD" -> ADD;
            case "MULTIPLY" -> MULTIPLY;
            case "SET" -> SET;
            default -> throw new IllegalArgumentException("未知运算符: " + raw);
        };
    }
}
