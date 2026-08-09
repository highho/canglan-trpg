package com.canglan.ai;

import java.util.Random;

/**
 * RuleFallbackService — AI 不可用时的规则兜底（MIGRATION_PLAN §4.3）。
 * 关键词匹配 + 固定文案，永不失败。对应 C# NullAiService 的角色。
 */
public final class RuleFallbackService {

    /** {关键词(逗号分隔), 回复}。 */
    private static final String[][] KEYWORD_REPLIES = {
            { "你好,您好,在吗", "嗯？是你啊。有什么事就说吧，我正忙着呢。" },
            { "天气", "天色说变就变，出门在外记得带伞。" },
            { "任务,委托,讨伐", "公会告示板上贴着新委托，去那儿看看准没错。" },
            { "商店,买,卖,交易", "集市就在村子里头，货比三家不吃亏。" },
            { "危险,怪物,哥布林", "荒野里不太平，夜里千万别走太远。" },
            { "谢谢,感谢", "客气什么，出门在外互相照应是应该的。" },
            { "再见,告辞", "路上小心。愿风指引你的方向。" },
    };

    private static final String[] GENERIC_REPLIES = {
            "嗯……这事我也说不好，你去村里打听打听吧。",
            "有意思。不过眼下我得先把手头的活干完。",
            "（对方若有所思地点了点头）冒险者，愿你一路平安。",
            "哈，你这人说话还挺有趣的。",
    };

    private final Random rng;

    public RuleFallbackService(Random rng) {
        this.rng = rng != null ? rng : new Random();
    }

    /** 依据玩家话语做关键词匹配；无命中返回通用文案。 */
    public String reply(ChatRequest request) {
        String utterance = request == null || request.utterance() == null ? "" : request.utterance();
        for (String[] kv : KEYWORD_REPLIES) {
            for (String key : kv[0].split(",")) {
                if (utterance.contains(key)) return kv[1];
            }
        }
        return GENERIC_REPLIES[rng.nextInt(GENERIC_REPLIES.length)];
    }
}
