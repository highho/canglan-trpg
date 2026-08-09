package com.canglan.world.quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.canglan.core.graph.GraphEngine;
import com.canglan.core.graph.GraphNode;
import com.canglan.core.graph.QuestData;
import com.canglan.core.graph.QuestNode;
import com.canglan.world.social.Rank;
import com.canglan.world.social.ReputationSystem;
import com.canglan.world.unit.Unit;

/**
 * AdventureGuild — 冒险者公会：任务板（标签+声望推荐）/ 训练场（声望折扣）。
 * 对应 C# AdventureGuild。
 */
public final class AdventureGuild {

    public static final String GUILD_ID = "adventurer_guild";

    /** 技能解锁动作（由会话层注入，例如 skillTrees.unlock）。 */
    @FunctionalInterface
    public interface UnlockAction {
        void unlock(String skillId);
    }

    private final GraphEngine<QuestData> questGraph;
    private final ReputationSystem reputation;
    private final Map<String, Rank> questMinRanks = new HashMap<>();   // questId → 最低声望等级

    public AdventureGuild(GraphEngine<QuestData> questGraph, ReputationSystem reputation) {
        this.questGraph = questGraph;
        this.reputation = reputation;
    }

    /** 设置任务声望门槛。 */
    public void setQuestMinRank(String questId, Rank rank) { questMinRanks.put(questId, rank); }

    /** 根据玩家标签+等级+声望推荐可用任务。 */
    public List<QuestNode> getAvailableQuests(Unit player) {
        List<QuestNode> result = new ArrayList<>();
        for (GraphNode<QuestData> n : questGraph.allNodes()) {
            if (!(n instanceof QuestNode quest)) continue;
            if (!quest.canAccept(player.activeTagIds(), player.level())) continue;
            if (!meetsReputation(quest.id(), player)) continue;
            result.add(quest);
        }
        return result;
    }

    private boolean meetsReputation(String questId, Unit player) {
        Rank minRank = questMinRanks.get(questId);
        if (minRank == null) return true;
        int rep = reputation.get(GUILD_ID, player.id());
        return reputation.compareRank(rep, minRank.name()) >= 0;
    }

    /** 训练场：消耗金币（声望折扣）训练技能，返回是否成功。 */
    public boolean trainSkill(Unit player, String skillId, int goldCost, UnlockAction unlockAction) {
        float discount = reputation.getDiscount(GUILD_ID, player.id());
        int actualCost = (int) (goldCost * (1 - discount));
        if (player.gold() < actualCost) return false;
        player.setGold(player.gold() - actualCost);
        if (unlockAction != null) unlockAction.unlock(skillId);
        return true;
    }
}
