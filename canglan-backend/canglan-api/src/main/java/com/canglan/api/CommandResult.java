package com.canglan.api;

import java.util.List;
import java.util.Map;

/**
 * 指令执行结果：叙事行列表 + HUD 状态快照（前端渲染用）。
 * hud 键：hasPlayer/creating/time/stepCount/name/level/race/clazz/hp/maxHp/exp/gold/x/y/
 * hunger/thirst/temperature/sanity/tags/companions/homeLevel/homeBuildings。
 */
public record CommandResult(List<NarrationLine> lines, Map<String, Object> hud) {

    /** 全部叙事文本拼接（冒烟测试/纯文本客户端用）。 */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (NarrationLine line : lines) sb.append(line.text()).append('\n');
        return sb.toString().stripTrailing();
    }
}
