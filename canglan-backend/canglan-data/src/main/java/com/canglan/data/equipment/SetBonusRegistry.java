package com.canglan.data.equipment;

import java.util.HashMap;
import java.util.Map;

import com.canglan.core.effect.EffectParser;
import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;

/**
 * 套装效果注册表。get(setId, count) 返回 ≤ count 的最大件数效果。
 * 对应 C# SetBonusRegistry。
 */
public final class SetBonusRegistry {

    private final Map<String, Map<Integer, SetBonusDef>> bonusesById = new HashMap<>();

    public void loadFromText(String json, EffectParser effectParser) {
        JsonValue root = JsonReader.parse(json);
        for (Map.Entry<String, JsonValue> setEntry : root.asObject().entrySet()) {
            String setId = setEntry.getKey();
            JsonValue bonuses = setEntry.getValue().get("bonuses");
            if (bonuses == null || !bonuses.isObject()) continue;
            Map<Integer, SetBonusDef> tierMap = new HashMap<>();
            for (Map.Entry<String, JsonValue> bonusEntry : bonuses.asObject().entrySet()) {
                int pieces = Integer.parseInt(bonusEntry.getKey());
                SetBonusDef def = new SetBonusDef(
                        setId,
                        pieces,
                        bonusEntry.getValue().getString("name", setId + "_" + pieces),
                        effectParser.parseEffects(bonusEntry.getValue()));
                tierMap.put(pieces, def);
            }
            bonusesById.put(setId, tierMap);
        }
    }

    public void register(SetBonusDef def) {
        bonusesById.computeIfAbsent(def.setId(), k -> new HashMap<>())
                .put(def.piecesRequired(), def);
    }

    /** 查找特定套装N件时的效果：返回 ≤ count 的最大件数档位；无则 null。 */
    public SetBonusDef get(String setId, int count) {
        Map<Integer, SetBonusDef> tierMap = bonusesById.get(setId);
        if (tierMap == null) return null;
        SetBonusDef best = null;
        for (Map.Entry<Integer, SetBonusDef> kv : tierMap.entrySet()) {
            if (kv.getKey() <= count && (best == null || kv.getKey() > best.piecesRequired())) {
                best = kv.getValue();
            }
        }
        return best;
    }
}
