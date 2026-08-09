package com.canglan.data.shop;

import java.util.List;

/**
 * 商店定义（shops.json）。对应 C# ShopDef record。
 * owner 为 NPC id：玩家在该 NPC 附近时开店。
 */
public record ShopDef(String id, String name, String owner, List<ShopItemDef> items) {

    /** 商店货品条目。stock=maxStock（C# 同名字段复用）。 */
    public record ShopItemDef(String itemId, int price, int stock) {
    }
}
