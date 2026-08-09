package com.canglan.core.effect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.canglan.core.json.JsonValue;
import com.canglan.core.tag.TagCondition;
import com.canglan.core.tag.TagConditionParser;

/**
 * EffectParser — 按 type 路由解析效果 JSON。对应 C# EffectParser。
 * STAT_MOD / DAMAGE_MOD / TRIGGER / FLAG。
 */
public final class EffectParser {

    private final TagConditionParser conditionParser;

    public EffectParser(TagConditionParser conditionParser) {
        this.conditionParser = conditionParser;
    }

    public EffectDef parse(JsonValue node) {
        String type = node.getString("type", null);
        if (type == null) throw new IllegalArgumentException("效果缺少 type 字段");
        return switch (type) {
            case "STAT_MOD" -> parseStatMod(node);
            case "DAMAGE_MOD" -> parseDamageMod(node);
            case "TRIGGER" -> parseTrigger(node);
            case "FLAG" -> new FlagDef(node.getString("flagName", null));
            default -> throw new IllegalArgumentException("未知效果类型: " + type);
        };
    }

    /** 解析节点中的 effects 数组；无该字段时返回空列表。 */
    public List<EffectDef> parseEffects(JsonValue node) {
        List<EffectDef> list = new ArrayList<>();
        JsonValue arr = node.get("effects");
        if (arr == null || !arr.isArray()) return list;
        for (JsonValue e : arr.asArray()) list.add(parse(e));
        return list;
    }

    private StatMod parseStatMod(JsonValue n) {
        return new StatMod(
                n.getString("target", null),
                Operator.parse(n.getString("operator", "ADD")),
                (float) n.getDouble("value", 0));
    }

    private DamageMod parseDamageMod(JsonValue n) {
        return new DamageMod(
                n.has("againstTag") ? n.getString("againstTag", null) : null,
                n.getInt("onGridPos", -1),
                Operator.parse(n.getString("operator", "ADD")),
                (float) n.getDouble("value", 0));
    }

    private TriggerDef parseTrigger(JsonValue n) {
        TagCondition cond = null;
        JsonValue c = n.get("condition");
        if (c != null && c.isString()) cond = conditionParser.parse(c.asString());

        Map<String, Object> params = new HashMap<>();
        JsonValue p = n.get("params");
        if (p != null && p.isObject()) {
            for (Map.Entry<String, JsonValue> entry : p.asObject().entrySet()) {
                params.put(entry.getKey(), toJavaValue(entry.getValue()));
            }
        }
        return new TriggerDef(
                n.getString("on", null),
                n.getDouble("chance", 1.0),
                n.getString("action", null),
                params,
                cond);
    }

    private static Object toJavaValue(JsonValue v) {
        if (v.isNumber()) {
            double d = v.asDouble();
            return (d == Math.floor(d) && !Double.isInfinite(d)) ? (Object) (int) d : (Object) d;
        }
        if (v.isBoolean()) return v.asBoolean();
        if (v.isString()) return v.asString();
        return null;
    }
}
