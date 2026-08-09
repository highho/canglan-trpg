package com.canglan.data.equipment;

import java.util.List;

import com.canglan.core.effect.EffectDef;

/**
 * SetBonusDef — 套装效果定义（N件同套装触发）。对应 C# SetBonusDef record。
 *
 * @param piecesRequired 2 / 3 / 4 / 5
 */
public record SetBonusDef(
        String setId,
        int piecesRequired,
        String bonusName,
        List<EffectDef> bonusEffects) {
}
