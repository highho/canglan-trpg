package com.canglan.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.canglan.ai.ChatReply;
import com.canglan.ai.ChatRequest;
import com.canglan.core.graph.ClassNode;
import com.canglan.core.graph.QuestNode;
import com.canglan.core.graph.RaceNode;
import com.canglan.core.tag.AlwaysTrue;
import com.canglan.core.tag.EvalContext;
import com.canglan.data.craft.GatherableResource;
import com.canglan.data.craft.Recipe;
import com.canglan.data.craft.ResourceCategory;
import com.canglan.data.equipment.EquipDef;
import com.canglan.data.home.BuildingDef;
import com.canglan.data.item.ItemDef;
import com.canglan.data.item.ItemStack;
import com.canglan.data.item.ItemType;
import com.canglan.data.monster.MonsterTemplate;
import com.canglan.data.npc.NpcDef;
import com.canglan.data.shop.ShopDef;
import com.canglan.data.trait.TraitDef;
import com.canglan.save.DeathHandler;
import com.canglan.save.DeathMode;
import com.canglan.save.DeathOutcome;
import com.canglan.save.SaveSlotInfo;
import com.canglan.world.BiomeType;
import com.canglan.world.DayPhase;
import com.canglan.world.DifficultySettings;
import com.canglan.world.FeatureType;
import com.canglan.world.FogOfWar;
import com.canglan.world.MapPos;
import com.canglan.world.TerrainFeature;
import com.canglan.world.ally.RecruitmentDef;
import com.canglan.world.ally.RecruitmentResult;
import com.canglan.world.ally.RecruitmentSystem;
import com.canglan.world.battle.BattleAI;
import com.canglan.world.battle.BattleManager;
import com.canglan.world.battle.BattleResult;
import com.canglan.world.battle.GridPosition;
import com.canglan.world.battle.GridSystem;
import com.canglan.world.battle.Side;
import com.canglan.world.behavior.BehaviorEngine;
import com.canglan.world.craft.CraftResult;
import com.canglan.world.craft.GatherPoint;
import com.canglan.world.equipment.Equip;
import com.canglan.world.equipment.EquipResult;
import com.canglan.world.home.Building;
import com.canglan.world.npc.NpcFactory;
import com.canglan.world.npc.dialogue.DialogueAction;
import com.canglan.world.npc.dialogue.DialogueNode;
import com.canglan.world.npc.dialogue.DialogueTree;
import com.canglan.world.shop.Shop;
import com.canglan.world.social.FactionId;
import com.canglan.world.unit.CombatMode;
import com.canglan.world.unit.Unit;

/**
 * CommandDispatcher — 32 指令解析器（对应 C# MainViewModel.ExecuteCommandAsync 与各 Cmd*）。
 * 铁律：所有游戏逻辑走规则系统；未识别指令回退提示（AI 自由对话延至 P7）。
 * P6 简化：随机遭遇/成就/图鉴/生涯统计不迁移；「交易」由 UI 面板改为指令式买/卖。
 */
final class CommandDispatcher {

    private final GameSession s;

    CommandDispatcher(GameSession session) {
        this.s = session;
    }

    /** 分发一行指令（全角空格已在会话层归一化）。 */
    void dispatch(String raw) {
        // 创建流程中：仅「取消」可退出，其余输入一律作为选择项
        if (s.creationStage > 0) {
            if (raw.equals("取消") || raw.equalsIgnoreCase("cancel")) { resetCreation(); return; }
            handleCreationChoice(raw);
            return;
        }

        String[] parts = raw.split(" +");
        if (parts.length == 0 || parts[0].isEmpty()) return;
        String head = parts[0];
        String arg = raw.length() > head.length() ? raw.substring(head.length()).trim() : "";

        switch (head) {
            case "帮助", "help" -> cmdHelp();
            case "创建" -> cmdCreate(arg);
            case "取消" -> s.narrate("当前没有可取消的操作。", NarrationKind.SYSTEM);
            case "状态" -> cmdStatus();
            case "背包" -> cmdInventory();
            case "标签" -> cmdTags();
            case "装备" -> cmdEquip(arg);
            case "东", "南", "西", "北" -> move(head);
            case "前往", "去", "走" -> {
                if (!arg.isEmpty()) move(arg.substring(0, 1));
                else s.narrate("去哪里？（东 / 南 / 西 / 北）", NarrationKind.SYSTEM);
            }
            case "查看", "环顾" -> cmdLook();
            case "探索", "搜寻" -> cmdExplore();
            case "等待", "等到天亮" -> cmdWait();
            case "交谈", "对话", "找" -> cmdTalk(arg);
            case "攻击", "讨伐", "战斗" -> cmdFight(arg);
            case "采集" -> cmdGather(arg);
            case "吃" -> cmdEat(arg);
            case "喝水", "饮水" -> cmdDrink();
            case "制造" -> cmdCraft(arg);
            case "配方" -> cmdRecipes();
            case "任务" -> cmdQuestList();
            case "传闻" -> cmdRumor();
            case "完成" -> cmdQuestComplete(arg);
            case "技能" -> cmdSkills();
            case "解锁技能" -> cmdUnlockSkills();
            case "建造" -> cmdBuild(arg);
            case "家园" -> cmdHome();
            case "家园升级" -> cmdHomeLevelUp();
            case "招募" -> cmdRecruit(arg);
            case "交易" -> cmdTrade(arg);
            case "购买", "买" -> cmdBuy(arg);
            case "出售", "卖" -> cmdSell(arg);
            case "存档" -> cmdSave();
            case "读档" -> cmdLoad();
            case "存档列表" -> cmdSlots();
            case "声望" -> cmdReputation();
            default -> aiFreeTalkFallback(raw);   // 未识别指令 → AI 自由对话（P7 接入）
        }
    }

    /** 未识别指令 → AI 自由对话（P7）；AI 不可用/失败时 chatSync 内部自动走规则兜底，永不抛异常。 */
    private void aiFreeTalkFallback(String utterance) {
        if (!s.ensurePlayer()) return;
        String npcId = s.lastTalkNpcId;
        if (npcId == null) npcId = s.registries.npcs.getAll().iterator().next().id();
        Unit npc = s.getNpcInstance(npcId);
        ChatRequest req = new ChatRequest(npcId, npc.name(), s.player.name(), utterance,
                new ArrayList<>(s.player.activeTagIds()), List.of());
        ChatReply reply = s.ai.chatSync(req);
        s.narrate(npc.name() + "：" + reply.text(), NarrationKind.DIALOGUE);
        if (reply.fallback()) {
            s.narrate("提示：输入「帮助」查看指令一览。", NarrationKind.SYSTEM);
        }
    }

    private void cmdHelp() {
        s.narrate("—— 指令一览 ——", NarrationKind.SYSTEM);
        s.narrate("创建 名字 …… 建档（随后按提示依次选择种族/职业/特质）", NarrationKind.SYSTEM);
        s.narrate("状态 / 背包 / 标签 / 装备[名称] / 技能 / 配方 / 家园", NarrationKind.SYSTEM);
        s.narrate("东 / 南 / 西 / 北 …… 移动；查看 …… 环顾四周", NarrationKind.SYSTEM);
        s.narrate("探索 …… 花费1小时搜索当前区域；等待 …… 等到天亮", NarrationKind.SYSTEM);
        s.narrate("交谈 [某人] …… 触发对话树；攻击 [怪物] …… 遭遇战", NarrationKind.SYSTEM);
        s.narrate("采集 [资源] / 吃 [食物] / 喝水 / 制造 [配方] / 建造 [建筑] / 家园升级", NarrationKind.SYSTEM);
        s.narrate("任务 / 完成 [任务] / 解锁技能 / 招募 [某人] / 交易 / 购买 / 出售", NarrationKind.SYSTEM);
        s.narrate("存档 / 读档 / 存档列表 / 声望 / 传闻", NarrationKind.SYSTEM);
    }

    // ==================== 创建角色（三选状态机） ====================

    private void cmdCreate(String name) {
        if (s.player != null) {
            s.narrate("你已有角色。如需重开，请重启会话或读档。", NarrationKind.SYSTEM);
            return;
        }
        s.creationName = name.isEmpty() ? "旅人" : name;
        s.creationRaces = new ArrayList<>(s.creation.getAvailableRaces());
        s.creationStage = 1;
        s.narrate("很好，" + s.creationName + "。命运的织机开始转动。", NarrationKind.NARRATION);
        s.narrate("第 1/3 步 · 选择种族（进阶种族需在冒险中进化）", NarrationKind.SYSTEM);
        for (int i = 0; i < s.creationRaces.size(); i++)
            s.narrate("  " + (i + 1) + ". " + s.creationRaces.get(i).name(), NarrationKind.SYSTEM);
    }

    private void handleCreationChoice(String input) {
        switch (s.creationStage) {
            case 1 -> {
                s.pickedRace = pickOne(s.creationRaces, input, RaceNode::name, RaceNode::id);
                if (s.pickedRace == null) { s.narrate("请输入列表中的编号或名称。", NarrationKind.SYSTEM); return; }
                s.creationClasses = new ArrayList<>(s.creation.getAvailableClasses());
                s.creationStage = 2;
                s.narrate("血脉已定：" + s.pickedRace.name() + "。", NarrationKind.NARRATION);
                s.narrate("第 2/3 步 · 选择职业（进阶职业需在冒险中转职）", NarrationKind.SYSTEM);
                for (int i = 0; i < s.creationClasses.size(); i++)
                    s.narrate("  " + (i + 1) + ". " + s.creationClasses.get(i).name(), NarrationKind.SYSTEM);
            }
            case 2 -> {
                s.pickedClass = pickOne(s.creationClasses, input, ClassNode::name, ClassNode::id);
                if (s.pickedClass == null) { s.narrate("请输入列表中的编号或名称。", NarrationKind.SYSTEM); return; }
                s.creationTraits = new ArrayList<>(s.creation.getAvailableTraits(
                        s.pickedRace.data(), s.pickedClass.data()));
                s.creationStage = 3;
                s.narrate("道路已选：" + s.pickedClass.name() + "。", NarrationKind.NARRATION);
                s.narrate("第 3/3 步 · 选择出身特质", NarrationKind.SYSTEM);
                for (int i = 0; i < s.creationTraits.size(); i++)
                    s.narrate("  " + (i + 1) + ". " + s.creationTraits.get(i).name()
                            + " —— " + s.creationTraits.get(i).description(), NarrationKind.SYSTEM);
            }
            case 3 -> {
                TraitDef trait = pickOne(s.creationTraits, input, TraitDef::name, TraitDef::id);
                if (trait == null) { s.narrate("请输入列表中的编号或名称。", NarrationKind.SYSTEM); return; }
                finishCreation(trait);
            }
            default -> { }
        }
    }

    /** 取消建档：重置创建状态机的全部中间状态。 */
    private void resetCreation() {
        s.creationStage = 0;
        s.pickedRace = null;
        s.pickedClass = null;
        s.creationRaces.clear();
        s.creationClasses.clear();
        s.creationTraits.clear();
        s.narrate("已取消建档，命运的织机停了下来。", NarrationKind.SYSTEM);
    }

    private static <T> T pickOne(List<T> list, String input, Function<T, String> name, Function<T, String> id) {
        try {
            int idx = Integer.parseInt(input.trim());
            if (idx >= 1 && idx <= list.size()) return list.get(idx - 1);
        } catch (NumberFormatException ignored) { }
        for (T x : list) if (name.apply(x).equals(input) || id.apply(x).equals(input)) return x;
        for (T x : list) if (name.apply(x).contains(input) || id.apply(x).contains(input)) return x;
        return null;
    }

    private void finishCreation(TraitDef trait) {
        s.creationStage = 0;
        s.player = s.creation.create(s.pickedRace.id(), s.pickedClass.id(), trait.id(), s.creationName);
        s.player.setWorldPos(GameSession.VILLAGE);
        s.player.setDifficulty(s.difficulty);
        s.equipment = new com.canglan.world.equipment.EquipmentManager(
                s.player, s.bus, s.registries.setBonuses, s.registries.equips);
        s.crafting = new com.canglan.world.craft.CraftingSystem(s.player, s.bus, s.registries.recipes);
        s.home = new com.canglan.world.home.HomeBase(GameSession.VILLAGE, 8, 8, s.bus, s.player);
        s.cooldowns = new com.canglan.data.skill.CooldownManager(s.bus);
        s.npcInstances.clear();
        s.companions.clear();
        s.skillTree = s.pickedClass.skillTreeRoot() != null
                ? s.registries.skillTrees.tryGet(s.pickedClass.skillTreeRoot()) : null;
        if (s.skillTree != null) s.skillTree.unlockRoots();

        s.narrate("建档完成：" + s.player.name() + "，" + s.pickedRace.name() + " / "
                + s.pickedClass.name() + " / " + trait.name(), NarrationKind.SYSTEM);
        s.narrate("你在村口的老橡树下醒来，行囊里是几份干粮、几瓶药水，和一百枚叮当作响的金币。", NarrationKind.NARRATION);
        s.narrate(trait.description() + "。从今天起，这片大陆的故事由你书写。", NarrationKind.NARRATION);
        s.narrate("提示：「交谈 村长罗万」了解村庄近况，「任务」需在布告板旁接取。", NarrationKind.SYSTEM);
        s.narrate("村子里太平无事——没有怪物，也没有采集点。向东南西北走出村庄，可用的行动会随你的坐标变化；不知道去哪就「传闻」。", NarrationKind.SYSTEM);
        s.map.currentFog().update(s.player);
        // 建档完成自动存档到所选存档位
        try {
            s.saveManager.save(s.selectedSlot, s.captureCurrent());
        } catch (Exception ex) {
            s.narrate("自动存档失败：" + ex.getMessage(), NarrationKind.ERROR);
        }
    }

    // ==================== 移动 ====================

    private void move(String dir) {
        if (!s.ensurePlayer()) return;
        if (!dir.equals("东") && !dir.equals("南") && !dir.equals("西") && !dir.equals("北")) {
            s.narrate("只认得东南西北。", NarrationKind.SYSTEM);
            return;
        }
        int dx = dir.equals("东") ? 1 : dir.equals("西") ? -1 : 0;
        int dy = dir.equals("南") ? 1 : dir.equals("北") ? -1 : 0;
        int x = Math.max(0, Math.min(s.map.width() - 1, s.player.worldPos().x() + dx));
        int y = Math.max(0, Math.min(s.map.height() - 1, s.player.worldPos().y() + dy));
        if (x == s.player.worldPos().x() && y == s.player.worldPos().y()) {
            s.narrate("前方已是大陆尽头，无路可走。", NarrationKind.NARRATION);
            return;
        }
        s.player.setWorldPos(new MapPos(x, y));
        s.stepCount++;

        // 游戏时间推进：每移动一步 = 1 小时；超重步履维艰 → 2 小时
        int moveHours = s.player.isOverloaded() ? 2 : 1;
        s.time.advance(moveHours);
        if (s.player.isOverloaded())
            s.narrate("你背着远超负荷的行囊，每一步都异常沉重。", NarrationKind.SYSTEM);
        // 夜晚赶路消耗理智（难度放大），白天缓慢恢复
        DifficultySettings diff = DifficultySettings.get(s.player.difficulty());
        if (s.time.isNight())
            s.player.survival().drainSanity(2, diff.sanityDrainMul());
        else if (s.player.survival().sanity() < 100 && s.time.phase() == DayPhase.DAY)
            s.player.survival().restoreSanity(1);

        s.survivalManager.onPlayerMove(s.player, s.map, s.time);
        for (GatherPoint gp : s.gatherPoints.values()) gp.tickCooldown();   // 每走一步 = 一回合
        s.map.currentFog().decayAfterMove(s.player);
        s.map.currentFog().update(s.player);
        s.narrate("你向" + dir + "走去，脚步落在（" + x + "," + y + "）的土地上。", NarrationKind.NARRATION);
        describeSurroundings();

        // 每 8 步静默自动存档（不打断游玩）
        if (s.stepCount % 8 == 0) {
            try { s.saveManager.save(s.selectedSlot, s.captureCurrent()); }
            catch (Exception ignored) { }
        }
    }

    private void cmdLook() {
        if (!s.ensurePlayer()) return;
        s.narrate("你站在（" + s.player.worldPos().x() + "," + s.player.worldPos().y() + "）环顾四周。", NarrationKind.NARRATION);
        describeSurroundings();
        s.narrate("饱食 " + s.player.survival().hunger() + "/100，水分 " + s.player.survival().thirst()
                + "/100，体温 " + s.player.survival().temperature() + "/100。", NarrationKind.SYSTEM);
    }

    private void describeSurroundings() {
        FogOfWar fog = s.map.currentFog();
        int visible = 0;
        for (int y = 0; y < s.map.height(); y++)
            for (int x = 0; x < s.map.width(); x++)
                if (fog.isVisible(x, y)) visible++;
        s.narrate("风拨开迷雾，你已探明 " + visible + " 处地域。", NarrationKind.SYSTEM);

        List<TerrainFeature> nearby = s.map.findNearby(s.player.worldPos(), GameSession.NEARBY_RANGE);
        List<String> monsters = nearby.stream().filter(f -> f.type() == FeatureType.MONSTER_SPAWN)
                .map(TerrainFeature::id).distinct()
                .map(id -> { MonsterTemplate t = s.registries.monsters.tryGet(id); return t != null ? t.name() : null; })
                .filter(Objects::nonNull).toList();
        List<String> resources = nearby.stream().filter(f -> f.type() == FeatureType.GATHER_POINT)
                .map(TerrainFeature::id).distinct()
                .map(id -> { GatherableResource r = s.registries.resources.tryGet(id); return r != null ? r.name() : null; })
                .filter(Objects::nonNull).toList();
        List<String> people = s.map.findNearby(s.player.worldPos(), GameSession.NPC_RANGE).stream()
                .filter(f -> f.type() == FeatureType.NPC_SPAWN)
                .map(TerrainFeature::id).distinct()
                .map(id -> { NpcDef n = s.registries.npcs.tryGet(id); return n != null ? n.name() : null; })
                .filter(Objects::nonNull).toList();
        s.narrate(monsters.isEmpty() ? "附近没有怪物的踪迹。" : "附近游荡着：" + String.join("、", monsters) + "。", NarrationKind.SYSTEM);
        s.narrate(resources.isEmpty() ? "附近没什么可采集的。" : "附近可采集：" + String.join("、", resources) + "。", NarrationKind.SYSTEM);
        if (!people.isEmpty()) s.narrate("附近的人：" + String.join("、", people) + "。", NarrationKind.SYSTEM);
        if (monsters.isEmpty() && resources.isEmpty())
            s.narrate("「传闻」可以探听最近目标的方向。", NarrationKind.SYSTEM);
    }

    // ==================== 探索 / 等待 ====================

    private void cmdExplore() {
        if (!s.ensurePlayer()) return;
        if (s.player.isOverloaded()) {
            s.narrate("你背上的东西太重了，连弯腰搜索都困难。先处理一下负重吧。", NarrationKind.SYSTEM);
            return;
        }
        DifficultySettings diff = DifficultySettings.get(s.player.difficulty());
        boolean night = s.time.isNight();
        double roll = s.rng.nextDouble();
        boolean found = false;

        if (roll < 0.28 * diff.dropMul()) {
            BiomeType biome = s.map.currentBiome(s.player.worldPos());
            String lootId = pickExploreLoot(biome);
            ItemDef def = s.registries.items.tryGet(lootId);
            if (def != null) {
                s.player.inventory().add(lootId, 1);
                s.narrate("你仔细翻找，在" + biomeName(biome) + "的角落发现了一个【" + def.name() + "】。", NarrationKind.REWARD);
                found = true;
            }
        } else if (roll < 0.55 * diff.dropMul()) {
            int gold = 5 + s.rng.nextInt(15);
            s.player.setGold(s.player.gold() + gold);
            s.narrate("你在碎石与落叶间拾到 " + gold + " 枚金币。", NarrationKind.REWARD);
            found = true;
        } else {
            s.narrate("你翻遍了周围的每一寸土地，只有风声作伴。", NarrationKind.NARRATION);
        }

        s.time.advance(1);
        if (night) s.player.survival().drainSanity(2, diff.sanityDrainMul());
        s.survivalManager.onPlayerMove(s.player, s.map, s.time);
        if (!found) s.narrate("时间流逝，现在" + s.time.display() + "。", NarrationKind.SYSTEM);
    }

    /** 等待到天亮：时间推进至黎明，期间恢复少量理智。 */
    private void cmdWait() {
        if (!s.ensurePlayer()) return;
        s.time.advanceTo(DayPhase.DAWN);
        s.player.survival().restoreSanity(8);
        s.narrate("你在原地休整，直到天际泛起鱼肚白。现在" + s.time.display() + "，精神稍复。", NarrationKind.NARRATION);
        s.survivalManager.onPlayerMove(s.player, s.map, s.time);
    }

    private static String biomeName(BiomeType biome) {
        return switch (biome) {
            case PLAINS -> "平原";
            case FOREST -> "林地";
            case DESERT -> "沙漠";
            case TUNDRA -> "冻原";
            case SWAMP -> "沼泽";
            case MOUNTAIN -> "山地";
            default -> "荒野";
        };
    }

    /** 生态 → 探索掉落表（每个生态有专属战利品）。 */
    private String pickExploreLoot(BiomeType biome) {
        return switch (biome) {
            case FOREST -> switch (s.rng.nextInt(3)) { case 0 -> "wolf_pelt"; case 1 -> "wood"; default -> "medicinal_herb"; };
            case DESERT -> switch (s.rng.nextInt(3)) { case 0 -> "iron_ore"; case 1 -> "bone_fragment"; default -> "sand_crystal"; };
            case TUNDRA -> switch (s.rng.nextInt(3)) { case 0 -> "ice_essence"; case 1 -> "wolf_pelt"; default -> "iron_ore"; };
            case SWAMP -> switch (s.rng.nextInt(3)) { case 0 -> "slime_gel"; case 1 -> "medicinal_herb"; default -> "bone_fragment"; };
            case MOUNTAIN -> switch (s.rng.nextInt(3)) { case 0 -> "iron_ore"; case 1 -> "mithril_ore"; default -> "stone"; };
            default -> switch (s.rng.nextInt(3)) { case 0 -> "wood"; case 1 -> "stone"; default -> "medicinal_herb"; };
        };
    }

    // ==================== 生存：吃 / 喝 / 采集 ====================

    private void cmdEat(String arg) {
        if (!s.ensurePlayer()) return;
        ItemDef food = null;
        for (ItemStack stack : s.player.inventory().stacks()) {
            ItemDef d = stack.def();
            if (d.type() == ItemType.CONSUMABLE && d.nutrition() > 0
                    && (arg.isEmpty() || d.name().contains(arg) || d.id().contains(arg))) {
                food = d;
                break;
            }
        }
        if (food == null) {
            s.narrate(arg.isEmpty() ? "行囊里没有能填肚子的东西。" : "行囊里没有「" + arg + "」。", NarrationKind.SYSTEM);
            return;
        }
        s.player.inventory().remove(food.id(), 1);
        s.player.survival().consume(food);
        s.bus.emit(com.canglan.core.eventbus.EventTypes.ITEM_USED, s.player, food.id());
        s.narrate("你吃下【" + food.name() + "】，饥意稍缓。", NarrationKind.NARRATION);
    }

    private void cmdDrink() {
        if (!s.ensurePlayer()) return;
        boolean waterNearby = s.map.findNearby(s.player.worldPos(), GameSession.NEARBY_RANGE).stream()
                .anyMatch(f -> f.type() == FeatureType.GATHER_POINT
                        && s.registries.resources.tryGet(f.id()) != null
                        && s.registries.resources.tryGet(f.id()).category() == ResourceCategory.WATER);
        if (!waterNearby) {
            s.narrate("附近没有水源——村里有水井，野外能找到清泉，「传闻」看看方向。", NarrationKind.SYSTEM);
            return;
        }
        s.player.survival().drink(30);
        s.narrate("你掬起清水饮下，喉咙里的干渴平息了。", NarrationKind.NARRATION);
    }

    private void cmdGather(String arg) {
        if (!s.ensurePlayer()) return;
        List<String> nearbyIds = s.map.findNearby(s.player.worldPos(), GameSession.NEARBY_RANGE).stream()
                .filter(f -> f.type() == FeatureType.GATHER_POINT)
                .map(TerrainFeature::id).distinct().toList();
        List<GatherableResource> nearby = new ArrayList<>();
        for (GatherableResource r : s.registries.resources.getAll())
            if (nearbyIds.contains(r.id())) nearby.add(r);
        if (arg.isEmpty()) {
            s.narrate(nearby.isEmpty()
                    ? "这附近没什么可采集的，换个地方看看。"
                    : "附近可采集的资源：" + String.join("、", nearby.stream().map(GatherableResource::name).toList()),
                    NarrationKind.SYSTEM);
            return;
        }
        GatherableResource resource = nearby.stream()
                .filter(r -> r.name().contains(arg) || r.id().contains(arg))
                .findFirst().orElse(null);
        if (resource == null) {
            s.narrate("这附近找不到【" + arg + "】——它长在别处，先走过去再说。", NarrationKind.SYSTEM);
            return;
        }
        GatherPoint point = s.gatherPoints.get(resource.id());
        if (point == null || point.isDepleted()) {
            point = new GatherPoint(resource);
            s.gatherPoints.put(resource.id(), point);
        }
        GatherPoint.GatherYield got = point.gather(s.player);
        if (got != null) {
            s.player.inventory().add(got.itemId(), got.count());
            ItemDef item = s.registries.items.tryGet(got.itemId());
            String itemName = item != null ? item.name() : got.itemId();
            s.narrate("你俯身劳作，从【" + resource.name() + "】收获了 " + itemName + " x" + got.count() + "。", NarrationKind.REWARD);
        } else if (point.cooldownRemaining() > 0) {
            s.narrate("【" + resource.name() + "】还没缓过来（冷却 " + point.cooldownRemaining() + " 步），稍后再试。", NarrationKind.SYSTEM);
        } else {
            s.narrate("你尝试采集【" + resource.name() + "】，但技有不逮——需要更专业的本事（标签门槛）。", NarrationKind.SYSTEM);
        }
    }

    // ==================== 战斗 ====================

    private void cmdFight(String arg) {
        if (!s.ensurePlayer()) return;
        Collection<MonsterTemplate> all = s.registries.monsters.getAll();
        if (arg.isEmpty()) {
            s.narrate("可以挑战的对手：" + String.join("、",
                    all.stream().map(MonsterTemplate::name).toList()), NarrationKind.SYSTEM);
            return;
        }
        int idx = arg.indexOf('（');
        if (idx > 0) arg = arg.substring(0, idx);   // 去除「（威胁 N）」后缀
        String needle = arg;
        MonsterTemplate template = all.stream()
                .filter(m -> m.name().contains(needle) || m.id().contains(needle))
                .findFirst().orElse(null);
        if (template == null) {
            s.narrate("没有听说过叫「" + needle + "」的怪物。", NarrationKind.SYSTEM);
            return;
        }
        if (!s.isNearby(FeatureType.MONSTER_SPAWN, template.id(), GameSession.NEARBY_RANGE)) {
            s.narrate("【" + template.name() + "】不在你附近（" + GameSession.NEARBY_RANGE
                    + " 步以内）——它在大陆的其他角落，先循着传闻走过去。", NarrationKind.SYSTEM);
            return;
        }
        if (s.player.isDead()) s.player.revive(0.5f);

        s.narrate("你握紧武器，迎面走向【" + template.name() + "】。战斗开始！", NarrationKind.COMBAT);
        Unit enemy = s.monsterFactory.create(template.id());
        GridSystem grid = new GridSystem();
        grid.placeUnit(s.player, new GridPosition(1, 2, Side.ALLY));
        grid.placeUnit(enemy, new GridPosition(1, 2, Side.ENEMY));
        BattleManager battle = new BattleManager(grid, s.bus,
                new BattleAI(new BehaviorEngine(s.rng), s.rng), s.effectEngine,
                List.of(s.player), List.of(enemy), CombatMode.LETHAL, s.rng);
        battle.skillManagers().put(s.player, s.cooldowns);
        BattleResult result = battle.runToCompletion();

        s.narrate("鏖战 " + battle.turnNumber() + " 回合——", NarrationKind.COMBAT);
        if (result.playerWin()) {
            s.narrate("【" + enemy.name() + "】倒下了。你赢得了胜利！", NarrationKind.COMBAT);
            s.narrate("获得经验，当前经验 " + s.player.exp() + "；剩余 HP "
                    + (int) s.player.stats().hp() + "/" + s.player.maxHp() + "。", NarrationKind.REWARD);
        } else {
            s.narrate("你不敌【" + enemy.name() + "】，倒在了血泊中……", NarrationKind.COMBAT);
            if (s.player.isDead()) {
                DeathOutcome outcome = new DeathHandler(DeathMode.PENALTY, s.saveManager, s.selectedSlot)
                        .handleDeath(s.player);
                s.narrate("死亡处理：" + outcome, NarrationKind.SYSTEM);
            }
        }
        s.player.recalculateTags();
    }

    // ==================== 对话（对话树 P4 留白在此接入） ====================

    private void cmdTalk(String arg) {
        if (!s.ensurePlayer()) return;
        Collection<NpcDef> all = s.registries.npcs.getAll();
        Unit npc;
        if (arg.isEmpty()) {
            if (s.lastTalkNpcId == null) {
                s.narrate("村里的人：" + String.join("、", all.stream().map(NpcDef::name).toList())
                        + "。想和谁谈谈？", NarrationKind.SYSTEM);
                return;
            }
            npc = s.getNpcInstance(s.lastTalkNpcId);
        } else {
            NpcDef def = all.stream()
                    .filter(n -> n.name().contains(arg) || n.id().contains(arg))
                    .findFirst().orElse(null);
            if (def == null) {
                s.narrate("没找到叫「" + arg + "」的人。", NarrationKind.SYSTEM);
                return;
            }
            npc = s.getNpcInstance(def.id());
        }
        s.lastTalkNpcId = (String) npc.metadata().get("npcId");
        if (!s.isNearby(FeatureType.NPC_SPAWN, s.lastTalkNpcId, GameSession.NPC_RANGE)) {
            s.narrate(npc.name() + "不在你身边——村里的人都在新手村（"
                    + GameSession.VILLAGE.x() + "," + GameSession.VILLAGE.y() + "）附近。", NarrationKind.SYSTEM);
            return;
        }

        DialogueTree dialogue = NpcFactory.getDialogueTree(npc);
        if (dialogue == null) {
            s.narrate("【" + npc.name() + "】沉默不语。", NarrationKind.DIALOGUE);
            return;
        }

        s.narrate("你走向" + npc.name() + "。", NarrationKind.NARRATION);
        EvalContext evalCtx = new EvalContext(s.player.activeTagIds(), new java.util.HashMap<>(), s.player, npc);
        DialogueNode node = dialogue.getRoot();
        while (node != null) {
            s.narrate(npc.name() + "：" + node.text(), NarrationKind.DIALOGUE);
            for (DialogueAction act : node.onEnterActions()) act.execute(s.player, npc);
            if (node.isExit()) break;
            node = dialogue.next(node, evalCtx);
        }
        s.bus.emit(com.canglan.core.eventbus.EventTypes.NPC_INTERACTION, s.player, npc.id());
    }

    // ==================== 任务 ====================

    private void cmdQuestList() {
        if (!s.ensurePlayer()) return;
        if (!s.isNearby(FeatureType.BUILDING, "布告板", GameSession.NEARBY_RANGE)) {
            s.narrate("布告板立在新手村的村口——先回村再说（「传闻」可知方向）。", NarrationKind.SYSTEM);
            return;
        }
        s.questCache = s.guild.getAvailableQuests(s.player);
        if (s.questCache.isEmpty()) {
            s.narrate("布告板上暂时没有你能接的委托（任务链由标签推进）。", NarrationKind.SYSTEM);
            return;
        }
        s.narrate("公会布告板上钉着 " + s.questCache.size() + " 份委托：", NarrationKind.SYSTEM);
        for (QuestNode q : s.questCache)
            s.narrate("  【" + q.name() + "】" + q.data().description()
                    + "（" + q.data().minLevel() + "级起）", NarrationKind.SYSTEM);
        s.narrate("输入「完成 任务名」直接结算委托。", NarrationKind.SYSTEM);
    }

    private void cmdQuestComplete(String arg) {
        if (!s.ensurePlayer()) return;
        if (s.questCache.isEmpty()) s.questCache = s.guild.getAvailableQuests(s.player);
        QuestNode quest = s.questCache.stream()
                .filter(q -> q.name().contains(arg) || q.id().contains(arg))
                .findFirst().orElse(null);
        if (quest == null) {
            s.narrate("没有可完成的委托叫「" + arg + "」。先用「任务」查看列表。", NarrationKind.SYSTEM);
            return;
        }

        List<String> gains = new ArrayList<>();
        if (quest.data().rewards() != null) {
            for (Map.Entry<String, Integer> kv : quest.data().rewards().entrySet()) {
                String key = kv.getKey();
                int value = kv.getValue();
                if (key.equals("gold")) {
                    s.player.setGold(s.player.gold() + value);
                    gains.add("金币 +" + value);
                } else if (key.startsWith("reputation_")) {
                    s.factions.add(key, value);
                    gains.add(key + " +" + value);
                } else {
                    s.player.inventory().add(key, value);
                    ItemDef item = s.registries.items.tryGet(key);
                    gains.add((item != null ? item.name() : key) + " x" + value);
                }
            }
        }
        if (quest.data().rewardTagIds() != null) {
            for (String tagId : quest.data().rewardTagIds()) {
                s.player.questTagIds().add(tagId);
                gains.add("获得标签[" + tagId + "]");
            }
        }
        s.player.recalculateTags();
        s.bus.emit(com.canglan.core.eventbus.EventTypes.QUEST_COMPLETED, quest.id(), s.player);
        s.narrate("委托【" + quest.name() + "】完成！", NarrationKind.REWARD);
        if (!gains.isEmpty()) s.narrate("报酬：" + String.join("，", gains), NarrationKind.REWARD);
        s.questCache = s.guild.getAvailableQuests(s.player);
    }

    // ==================== 传闻 ====================

    /** 传闻：指引玩家找到最近的可用目标（解决「不知道该往哪走」）。 */
    private void cmdRumor() {
        if (!s.ensurePlayer()) return;
        s.narrate("—— 风声里传来一些传闻 ——", NarrationKind.SYSTEM);
        if (!s.isNearby(FeatureType.BUILDING, "布告板", GameSession.NEARBY_RANGE))
            hint(FeatureType.BUILDING, "公会布告板（接委托）", null);
        if (!s.nearHome()) s.narrate("你的家园在" + s.directionTo(s.home.position()) + "。", NarrationKind.SYSTEM);
        hint(FeatureType.MONSTER_SPAWN, "怪物踪迹", id -> {
            MonsterTemplate t = s.registries.monsters.tryGet(id);
            return t != null ? t.name() : null;
        });
        hint(FeatureType.GATHER_POINT, "采集点", id -> {
            GatherableResource r = s.registries.resources.tryGet(id);
            return r != null ? r.name() : null;
        });
        s.narrate("提示：用「东 / 南 / 西 / 北」移动，行动会随坐标变化。", NarrationKind.SYSTEM);
    }

    private void hint(FeatureType type, String label, Function<String, String> nameOf) {
        TerrainFeature best = null;
        double bestDist = Double.MAX_VALUE;
        String bestName = null;
        for (TerrainFeature f : s.map.features()) {
            if (f.type() != type) continue;
            String name = nameOf == null ? f.id() : nameOf.apply(f.id());
            if (name == null) continue;
            double d = f.pos().distanceTo(s.player.worldPos());
            if (d < bestDist) {
                best = f;
                bestDist = d;
                bestName = name;
            }
        }
        if (best == null) return;
        boolean near = bestDist <= GameSession.NEARBY_RANGE;
        s.narrate(near ? label + "「" + bestName + "」就在你身边。"
                : label + "「" + bestName + "」在" + s.directionTo(best.pos()) + "。", NarrationKind.SYSTEM);
    }

    // ==================== 成长：技能 / 制造 ====================

    private void cmdSkills() {
        if (!s.ensurePlayer()) return;
        if (s.skillTree == null) {
            s.narrate("当前职业没有技能树。", NarrationKind.SYSTEM);
            return;
        }
        Set<String> unlocked = s.skillTree.unlockedSkillIds();
        List<String> names = new ArrayList<>();
        for (var node : s.skillTree.graph().allNodes()) {
            var skill = node.data().skill();
            names.add(skill.name() + (unlocked.contains(skill.id()) ? "(已解锁)" : "(未解锁)"));
        }
        s.narrate("技能树：" + String.join("，", names), NarrationKind.SYSTEM);
    }

    private void cmdUnlockSkills() {
        if (!s.ensurePlayer() || s.skillTree == null) return;
        var roots = s.skillTree.unlockRoots();
        var extra = s.skillTree.checkUnlocks(s.player.activeTagIds());
        if (roots.isEmpty() && extra.isEmpty()) {
            s.narrate("没有新的技能可以解锁。", NarrationKind.SYSTEM);
            return;
        }
        List<String> names = new ArrayList<>();
        roots.forEach(sk -> names.add(sk.name()));
        extra.forEach(sk -> names.add(sk.name()));
        s.narrate("技能解锁：" + String.join("、", names), NarrationKind.REWARD);
    }

    private void cmdRecipes() {
        if (!s.ensurePlayer()) return;
        s.recipeCache = s.crafting.getKnownRecipes();
        if (s.recipeCache.isEmpty()) {
            s.narrate("你还不会任何配方（学习锻造/炼金/烹饪等标签以解锁）。", NarrationKind.SYSTEM);
            return;
        }
        s.narrate("已掌握的配方：" + String.join("、",
                s.recipeCache.stream().map(Recipe::name).toList()), NarrationKind.SYSTEM);
    }

    private void cmdCraft(String arg) {
        if (!s.ensurePlayer()) return;
        if (s.recipeCache.isEmpty()) s.recipeCache = s.crafting.getKnownRecipes();
        if (arg.isEmpty()) {
            cmdRecipes();
            return;
        }
        Recipe recipe = s.recipeCache.stream()
                .filter(r -> r.name().contains(arg) || r.id().contains(arg))
                .findFirst().orElse(null);
        if (recipe == null) {
            s.narrate("你还没学会「" + arg + "」的配方。", NarrationKind.SYSTEM);
            return;
        }
        if (!s.nearHome()) {
            s.narrate("制造需要家园里的工作台——先回家园再动手（「传闻」可知方向）。", NarrationKind.SYSTEM);
            return;
        }
        CraftResult result = s.crafting.craft(recipe, s.player.inventory());
        if (result.success())
            s.narrate("炉火与锤声之后，【" + recipe.name() + "】完成了。", NarrationKind.REWARD);
        else
            s.narrate("制造【" + recipe.name() + "】失败：" + result.error(), NarrationKind.SYSTEM);
    }

    // ==================== 家园：建造 / 查看 / 升级 ====================

    private void cmdBuild(String arg) {
        if (!s.ensurePlayer()) return;
        List<String> ids = new ArrayList<>(s.registries.buildings.getAllIds());
        if (arg.isEmpty()) {
            s.narrate("可建造的建筑（用英文编号建造，如：建造 farm）：" + String.join("、", ids), NarrationKind.SYSTEM);
            return;
        }
        if (!s.nearHome()) {
            s.narrate("建造只能在家园范围内进行——先回家园（「传闻」可知方向）。", NarrationKind.SYSTEM);
            return;
        }
        String lower = arg.toLowerCase(Locale.ROOT);
        String id = ids.stream().filter(i -> i.toLowerCase(Locale.ROOT).contains(lower))
                .findFirst().orElse(null);
        if (id == null) {
            s.narrate("没有叫「" + arg + "」的建筑图纸。", NarrationKind.SYSTEM);
            return;
        }
        BuildingDef def = s.registries.buildings.tryGet(id);
        if (def == null) return;
        if (!def.materials().isEmpty() && !s.player.inventory().hasItems(def.materials())) {
            s.narrate("建造【" + def.name() + "】失败——材料不足。", NarrationKind.SYSTEM);
            return;
        }
        Building building = new Building(def);
        boolean placed = s.home.placeBuilding(building, s.buildX, s.buildY);
        if (placed) {
            // Java 侧改进：C# Building.Contribute 无任何调用点，建筑永远停留在蓝图态；
            // 此处放置成功后一次性扣除材料并投料至建成。
            for (Map.Entry<String, Integer> kv : def.materials().entrySet()) {
                s.player.inventory().remove(kv.getKey(), kv.getValue());
                building.contribute(kv.getKey(), kv.getValue());
            }
            s.narrate("【" + building.name() + "】在（" + s.buildX + "," + s.buildY + "）破土动工并落成了。", NarrationKind.REWARD);
            s.buildX++;
            if (s.buildX > 6) {
                s.buildX = 1;
                s.buildY++;
                if (s.buildY > 6) s.buildY = 1;
            }
        } else {
            s.narrate("建造【" + building.name() + "】失败——位置被占或前置建筑不满足。", NarrationKind.SYSTEM);
        }
    }

    private void cmdHome() {
        if (!s.ensurePlayer()) return;
        if (!s.nearHome()) {
            s.narrate("你不在家园——家园在" + s.directionTo(s.home.position()) + "。", NarrationKind.SYSTEM);
            return;
        }
        List<Building> buildings = s.home.getBuildings();
        s.narrate("家园等级 Lv" + s.home.level() + "。已建建筑："
                + (buildings.isEmpty() ? "（空）"
                        : String.join("、", buildings.stream().map(Building::name).toList())),
                NarrationKind.SYSTEM);
    }

    private void cmdHomeLevelUp() {
        if (!s.ensurePlayer()) return;
        if (!s.nearHome()) {
            s.narrate("扩建家园得站在自家地基上——先回家园。", NarrationKind.SYSTEM);
            return;
        }
        s.home.levelUp();
        s.narrate("家园扩建完成，升至 Lv" + s.home.level() + "。", NarrationKind.REWARD);
    }

    // ==================== 社交：招募 / 交易 ====================

    private void cmdRecruit(String arg) {
        if (!s.ensurePlayer()) return;
        Collection<NpcDef> all = s.registries.npcs.getAll();
        NpcDef def = all.stream()
                .filter(n -> n.name().contains(arg) || n.id().contains(arg))
                .findFirst().orElse(null);
        if (def == null) {
            s.narrate("你想招募谁？村里的人：" + String.join("、", all.stream().map(NpcDef::name).toList()), NarrationKind.SYSTEM);
            return;
        }
        Unit npc = s.getNpcInstance(def.id());
        if (!s.isNearby(FeatureType.NPC_SPAWN, def.id(), GameSession.NPC_RANGE)) {
            s.narrate(npc.name() + "不在你身边——先回到新手村再谈招募。", NarrationKind.SYSTEM);
            return;
        }
        if (!npc.metadata().containsKey("recruitmentDef"))
            npc.metadata().put("recruitmentDef", new RecruitmentDef(new AlwaysTrue(), 60, true, 500, 10));
        RecruitmentResult attempt = RecruitmentSystem.recruitByBond(s.player, npc);
        s.narrate("招募" + npc.name() + "：" + attempt.message(),
                attempt.success() ? NarrationKind.REWARD : NarrationKind.SYSTEM);
        if (attempt.success() && !s.companions.contains(npc)) s.companions.add(npc);
    }

    /** 附近 NPC 开设的商店实例列表。 */
    private List<Shop> nearbyShops() {
        List<Shop> shops = new ArrayList<>();
        if (s.player == null) return shops;
        List<String> ownerIds = s.map.findNearby(s.player.worldPos(), GameSession.NPC_RANGE).stream()
                .filter(f -> f.type() == FeatureType.NPC_SPAWN)
                .map(TerrainFeature::id).distinct().toList();
        for (String ownerId : ownerIds) {
            ShopDef def = s.registries.shops.getByOwner(ownerId);
            if (def != null) shops.add(s.getShopInstance(def));
        }
        return shops;
    }

    /** 交易（C# 为 UI 面板；API 层改为指令式货架展示）。 */
    private void cmdTrade(String arg) {
        if (!s.ensurePlayer()) return;
        List<Shop> shops = nearbyShops();
        if (shops.isEmpty()) {
            s.narrate("附近没有开张的商铺——找到摆摊的商人才能交易。", NarrationKind.SYSTEM);
            return;
        }
        for (Shop shop : shops) {
            if (!arg.isEmpty() && !shop.def().name().contains(arg) && !shop.def().id().contains(arg)) continue;
            s.narrate("—— " + shop.def().name() + " 的货架 ——", NarrationKind.SYSTEM);
            for (ShopDef.ShopItemDef it : shop.def().items()) {
                ItemDef item = s.registries.items.tryGet(it.itemId());
                String name = item != null ? item.name() : it.itemId();
                s.narrate("  【" + name + "】库存 " + shop.currentStock(it.itemId())
                        + " —— " + it.price() + " 金币", NarrationKind.SYSTEM);
            }
        }
        s.narrate("用「购买 物品名」购入，「出售 物品名」半价卖出。", NarrationKind.SYSTEM);
    }

    private void cmdBuy(String arg) {
        if (!s.ensurePlayer()) return;
        if (arg.isEmpty()) {
            s.narrate("买什么？先「交易」看看货架。", NarrationKind.SYSTEM);
            return;
        }
        List<Shop> shops = nearbyShops();
        if (shops.isEmpty()) {
            s.narrate("附近没有开张的商铺。", NarrationKind.SYSTEM);
            return;
        }
        for (Shop shop : shops) {
            for (ShopDef.ShopItemDef it : shop.def().items()) {
                ItemDef item = s.registries.items.tryGet(it.itemId());
                boolean match = it.itemId().contains(arg) || (item != null && item.name().contains(arg));
                if (match) {
                    s.narrate(shop.buy(s.player, it.itemId(), 1), NarrationKind.REWARD);
                    return;
                }
            }
        }
        s.narrate("附近的商铺没有「" + arg + "」。", NarrationKind.SYSTEM);
    }

    private void cmdSell(String arg) {
        if (!s.ensurePlayer()) return;
        if (arg.isEmpty()) {
            s.narrate("卖什么？（「背包」看看行囊）", NarrationKind.SYSTEM);
            return;
        }
        List<Shop> shops = nearbyShops();
        if (shops.isEmpty()) {
            s.narrate("附近没有开张的商铺。", NarrationKind.SYSTEM);
            return;
        }
        String itemId = null;
        for (ItemStack stack : s.player.inventory().stacks()) {
            ItemDef d = stack.def();
            if (d.name().contains(arg) || d.id().contains(arg)) {
                itemId = d.id();
                break;
            }
        }
        if (itemId == null) {
            s.narrate("行囊里没有「" + arg + "」。", NarrationKind.SYSTEM);
            return;
        }
        s.narrate(shops.get(0).sell(s.player, itemId, 1, Set.of()), NarrationKind.REWARD);
    }

    // ==================== 角色面板指令 ====================

    private void cmdStatus() {
        if (!s.ensurePlayer()) return;
        Unit p = s.player;
        String race = p.currentRace() != null ? p.currentRace().name() : "无";
        String cls = p.currentClass() != null ? p.currentClass().name() : "无";
        s.narrate(p.name() + "　Lv" + p.level() + "　" + race + " / " + cls, NarrationKind.SYSTEM);
        s.narrate("HP " + (int) p.stats().hp() + "/" + p.maxHp()
                + "　经验 " + p.exp() + "　金币 " + p.gold(), NarrationKind.SYSTEM);
        s.narrate("攻击 " + String.format(Locale.ROOT, "%.0f", p.getStat("ATK"))
                + "　防御 " + String.format(Locale.ROOT, "%.0f", p.getStat("DEF"))
                + "　速度 " + String.format(Locale.ROOT, "%.0f", p.getStat("SPD"))
                + "　暴击 " + String.format(Locale.ROOT, "%.2f", p.getStat("CRIT")), NarrationKind.SYSTEM);
        s.narrate("饱食 " + p.survival().hunger() + "　水分 " + p.survival().thirst()
                + "　体温 " + p.survival().temperature()
                + "　坐标（" + p.worldPos().x() + "," + p.worldPos().y() + "）", NarrationKind.SYSTEM);
    }

    private void cmdInventory() {
        if (!s.ensurePlayer()) return;
        if (s.player.inventory().stackCount() == 0) {
            s.narrate("行囊空空如也。", NarrationKind.SYSTEM);
            return;
        }
        List<String> entries = new ArrayList<>();
        for (ItemStack stack : s.player.inventory().stacks())
            entries.add(stack.def().name() + "x" + stack.count());
        s.narrate("行囊：" + String.join("，", entries), NarrationKind.SYSTEM);
    }

    private void cmdTags() {
        if (!s.ensurePlayer()) return;
        if (s.player.activeTagIds().isEmpty())
            s.narrate("你身上没有任何标签。", NarrationKind.SYSTEM);
        else
            s.narrate("当前标签（驱动一切）：" + String.join("、", s.player.activeTagIds()), NarrationKind.SYSTEM);
    }

    private void cmdEquip(String arg) {
        if (!s.ensurePlayer()) return;
        if (arg.isEmpty()) {
            var equipped = s.equipment.getAllEquipped();
            if (equipped.isEmpty()) {
                s.narrate("你什么都没装备。可装备的兵器甲胄：" + String.join("、",
                        s.registries.equips.getAll().stream().map(EquipDef::name).toList()), NarrationKind.SYSTEM);
            } else {
                List<String> rows = new ArrayList<>();
                for (var kv : equipped.entrySet())
                    rows.add("  [" + kv.getKey() + "] " + kv.getValue().name()
                            + "（耐久 " + kv.getValue().currentDurability() + "/" + kv.getValue().maxDurability() + "）");
                s.narrate("当前装备：\n" + String.join("\n", rows), NarrationKind.SYSTEM);
            }
            return;
        }
        EquipDef def = s.registries.equips.getAll().stream()
                .filter(e -> e.name().contains(arg) || e.id().contains(arg))
                .findFirst().orElse(null);
        if (def == null) {
            s.narrate("没有叫「" + arg + "」的装备。", NarrationKind.SYSTEM);
            return;
        }
        EquipResult result = s.equipment.equip(new Equip(def));
        if (result.success())
            s.narrate("你装备了【" + def.name() + "】。", NarrationKind.REWARD);
        else
            s.narrate("无法装备【" + def.name() + "】：" + result.error(), NarrationKind.SYSTEM);
    }

    // ==================== 存档 ====================

    private void cmdSave() {
        if (!s.ensurePlayer()) return;
        boolean ok = s.saveManager.save(s.selectedSlot, s.captureCurrent());
        s.narrate(ok ? "你把旅途的足迹封存在水晶里（存档位 " + s.selectedSlot + "）。"
                : "存档失败：写入出错。", ok ? NarrationKind.SYSTEM : NarrationKind.ERROR);
    }

    private void cmdLoad() {
        s.doLoad(1);
    }

    private void cmdSlots() {
        List<SaveSlotInfo> slots = s.saveManager.listSlots();
        if (slots.isEmpty()) {
            s.narrate("尚无存档。", NarrationKind.SYSTEM);
            return;
        }
        List<String> rows = new ArrayList<>();
        for (SaveSlotInfo info : slots)
            rows.add("存档位 " + info.slot() + " —— " + info.location());
        s.narrate(String.join("\n", rows), NarrationKind.SYSTEM);
    }

    private void cmdReputation() {
        if (!s.ensurePlayer()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("阵营声望\n");
        for (FactionId fid : FactionId.values()) {
            int val = s.factions.get(fid);
            String lvlName = switch (s.factions.getLevel(fid)) {
                case HOSTILE -> "敌视";
                case NEUTRAL -> "中立";
                case FRIENDLY -> "友善";
                case HONORED -> "尊敬";
                case REVERED -> "崇敬";
            };
            sb.append("  ").append(factionLabel(fid)).append("：").append(val)
                    .append("（").append(lvlName).append("）\n");
        }
        s.narrate(sb.toString().stripTrailing(), NarrationKind.SYSTEM);
    }

    private static String factionLabel(FactionId fid) {
        return switch (fid) {
            case RANGER -> "游骑兵";
            case ADVENTURER -> "冒险公会";
            case MERCHANT -> "商盟";
            case SHADOW -> "暗影";
            case HOLY -> "圣殿";
            case CITIZEN -> "平民";
        };
    }
}
