package com.canglan.world.npc;

import java.util.Collections;
import java.util.HashMap;
import java.util.Set;

import com.canglan.core.eventbus.EventBus;
import com.canglan.core.tag.EvalContext;
import com.canglan.core.tag.TagFactory;
import com.canglan.data.item.ItemRegistry;
import com.canglan.data.npc.NpcDef;
import com.canglan.data.npc.NpcRegistry;
import com.canglan.world.effect.EffectEngine;
import com.canglan.world.npc.dialogue.DialogueNode;
import com.canglan.world.npc.dialogue.DialogueTree;
import com.canglan.world.npc.dialogue.DialogueTreeLoader;
import com.canglan.world.unit.BehaviorPools;
import com.canglan.world.unit.RelationState;
import com.canglan.world.unit.Unit;
import com.canglan.world.unit.UnitRole;

/**
 * NpcFactory — NPC 工厂。NPC = Unit 的社交偏向：
 * socialPool 激活 + 可变关系状态 + 对话树 + 切磋/打劫/袭杀战斗模式切换。
 * 对应 C# NPCFactory（对话树在此惰性解析，补齐 P4 留白）。
 */
public final class NpcFactory {

    /** 对话入口选择结果：节点 + 评估上下文。 */
    public record DialogueSelection(DialogueNode node, EvalContext ctx) {}

    private final TagFactory tagFactory;
    private final EffectEngine effectEngine;
    private final EventBus eventBus;
    private final ItemRegistry itemRegistry;
    private final NpcRegistry npcRegistry;
    private final DialogueTreeLoader dialogueLoader;

    public NpcFactory(TagFactory tagFactory, EffectEngine effectEngine, EventBus bus,
                      ItemRegistry itemRegistry, NpcRegistry npcRegistry,
                      DialogueTreeLoader dialogueLoader) {
        this.tagFactory = tagFactory;
        this.effectEngine = effectEngine;
        this.eventBus = bus;
        this.itemRegistry = itemRegistry;
        this.npcRegistry = npcRegistry;
        this.dialogueLoader = dialogueLoader;
    }

    public Unit create(NpcDef def) {
        Unit npc = new Unit(def.name(), UnitRole.NPC, tagFactory, effectEngine, eventBus, itemRegistry);

        for (var entry : def.baseStats().entrySet())
            npc.stats().setBase(entry.getKey(), entry.getValue());
        npc.stats().setHp(npc.maxHp());

        // 身份(IDENTITY) + 人格(PERSONALITY) 标签 → 特质集合
        npc.traitTagIds().addAll(def.identityTags());
        npc.traitTagIds().addAll(def.personalityTags());
        npc.recalculateTags();

        // 行为池：社交池激活，战斗池备用
        npc.setSocialPool(BehaviorPools.defaultSocialPool());
        npc.setCombatPool(BehaviorPools.defaultCombatPool());
        npc.setActivePool(npc.socialPool());

        npc.setRelationToPlayer(RelationState.parse(def.relation()));
        npc.metadata().put("npcId", def.id());
        if (def.dialogueTree() != null)
            npc.metadata().put("dialogueTree", dialogueLoader.load(def.dialogueTree()));
        npc.metadata().put("groupIds", def.groups());   // 所属社会群体 → 群体记忆传播域

        return npc;
    }

    public Unit create(String npcId) { return create(npcRegistry.get(npcId)); }

    /** 获取NPC对话树（可能为 null）。 */
    public static DialogueTree getDialogueTree(Unit npc) {
        Object raw = npc.metadata().get("dialogueTree");
        return raw instanceof DialogueTree tree ? tree : null;
    }

    /** 获取NPC所属社会群体ID列表（驱动群体记忆传播）。 */
    @SuppressWarnings("unchecked")
    public static Set<String> getGroups(Unit npc) {
        Object raw = npc.metadata().get("groupIds");
        return raw instanceof Set ? (Set<String>) raw : Collections.emptySet();
    }

    /**
     * 选择对话入口：战斗监听触发用 trigger 节点，否则根节点。
     * 条件按玩家标签评估。
     */
    public static DialogueSelection selectDialogue(Unit player, Unit npc, String trigger) {
        DialogueTree tree = getDialogueTree(npc);
        if (tree == null) return null;
        EvalContext ctx = new EvalContext(player.activeTagIds(), new HashMap<>(), player, npc);
        DialogueNode node = trigger != null ? tree.selectTrigger(trigger, player) : tree.getRoot();
        return new DialogueSelection(node, ctx);
    }
}
