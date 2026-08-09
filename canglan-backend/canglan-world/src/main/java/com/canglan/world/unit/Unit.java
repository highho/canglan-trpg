package com.canglan.world.unit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.canglan.core.effect.EffectTarget;
import com.canglan.core.effect.Operator;
import com.canglan.core.eventbus.EventBus;
import com.canglan.core.eventbus.EventTypes;
import com.canglan.core.graph.ClassNode;
import com.canglan.core.graph.RaceNode;
import com.canglan.core.tag.Tag;
import com.canglan.core.tag.TagFactory;
import com.canglan.data.item.Inventory;
import com.canglan.data.item.ItemRegistry;
import com.canglan.world.DifficultyMode;
import com.canglan.world.DifficultySettings;
import com.canglan.world.MapPos;
import com.canglan.world.SurvivalStats;
import com.canglan.world.buff.BuffManager;
import com.canglan.world.effect.EffectEngine;
import com.canglan.world.stats.StatValue;
import com.canglan.world.stats.Stats;

/**
 * Unit — 统一模型。NPC、队友、怪物本质相同，偏向不同：
 * 角色差异 = 行为池 + 关系状态 + 偏向（role）。
 * 转换只是改 role + relationState + behaviorPool，不创建新 Unit。
 * 对应 C# Unit（静态单例改为构造注入）。
 */
public final class Unit implements EffectTarget {

    private final String id = UUID.randomUUID().toString().replace("-", "");
    private String name;

    // ===== 角色偏向 / 关系 / 战斗模式 =====
    private UnitRole role;
    private RelationState relationToPlayer = RelationState.NEUTRAL;
    private CombatMode combatMode = CombatMode.NONE;
    private int affinity;                       // 好感度（仅NPC/Ally）
    private boolean isDead;
    private GridPos gridPos = new GridPos(1, 1);

    // ===== 标签（共享） =====
    private RaceNode currentRace;
    private ClassNode currentClass;
    private final Set<String> questTagIds = new HashSet<>();   // 任务标签（不可逆）
    private final Set<String> traitTagIds = new HashSet<>();   // 特质标签
    private final Set<String> equipTagIds = new HashSet<>();   // 装备标签（穿戴装备注入，卸下移除）
    private Set<String> activeTagIds = new HashSet<>();
    private List<Tag> activeTags = new ArrayList<>();

    // ===== 属性 / 情感 / Buff / 背包 =====
    private final Stats stats = new Stats();
    private final EmotionSystem emotion;
    private final BuffManager buffManager;
    private final Inventory inventory;
    private final SurvivalStats survival;

    // ===== 大地图位置 =====
    private MapPos worldPos = new MapPos(0, 0);

    // ===== 行为池 =====
    private BehaviorPool socialPool;           // Monster 为 null
    private BehaviorPool combatPool;
    private BehaviorPool activePool;

    // ===== 队友间关系 =====
    private final Map<Unit, Integer> allyAffinities = new HashMap<>();  // -100 ~ 100

    // ===== 雇佣（仅 Mercenary 类型） =====
    private int hireCost;                      // 雇佣价格（金币）
    private int contractDuration;              // 剩余合约天数/战斗次数，0=永久
    private boolean isMercenary;               // true=雇佣兵，false=感情招募

    // ===== 经济 =====
    private int gold;                          // 持有金币（交易/修理/雇佣）

    // ===== 负重 / 难度 =====
    /** 当前难度（创建时选定，存档恢复）。 */
    private DifficultyMode difficulty = DifficultyMode.NORMAL;

    /** 装备词缀提供的负重加成（EquipmentManager 刷新）。 */
    private double equipmentCarryBonus;

    // ===== 成长 =====
    private int level = 1;                     // 等级（任务门槛/队友成长）
    private int exp;                           // 经验值

    // ===== 元数据（怪物掉落表/经验值等扩展数据） =====
    private final Map<String, Object> metadata = new HashMap<>();

    // ===== 依赖 =====
    private final TagFactory tagFactory;
    private final EffectEngine effectEngine;
    private final EventBus eventBus;

    // ===== 属性快照 =====
    private final Map<String, StatValue> tagStats = new HashMap<>();    // 标签层
    private final Map<String, StatValue> buffStats = new HashMap<>();   // Buff层（不进TagSet）
    private boolean hpWarned;

    public Unit(String name, UnitRole role, TagFactory tagFactory, EffectEngine effectEngine,
                EventBus bus, ItemRegistry itemRegistry) {
        this.name = name;
        this.role = role;
        this.tagFactory = tagFactory;
        this.effectEngine = effectEngine;
        this.eventBus = bus;
        this.emotion = new EmotionSystem(this, bus);
        this.buffManager = new BuffManager(this, bus, effectEngine);
        this.survival = new SurvivalStats(this, bus);
        this.inventory = new Inventory(itemRegistry);

        this.combatPool = BehaviorPools.defaultCombatPool();
        this.activePool = role == UnitRole.MONSTER ? combatPool : null;
    }

    // ==================== 基础访问器 ====================

    public String id() { return id; }
    public String name() { return name; }
    public void setName(String v) { this.name = v; }
    public UnitRole role() { return role; }
    public void setRole(UnitRole v) { this.role = v; }
    public RelationState relationToPlayer() { return relationToPlayer; }
    public void setRelationToPlayer(RelationState v) { this.relationToPlayer = v; }
    public CombatMode combatMode() { return combatMode; }
    public void setCombatMode(CombatMode v) { this.combatMode = v; }
    public int affinity() { return affinity; }
    public void setAffinity(int v) { this.affinity = v; }
    public boolean isDead() { return isDead; }
    public GridPos gridPos() { return gridPos; }
    public void setGridPos(GridPos v) { this.gridPos = v; }
    public RaceNode currentRace() { return currentRace; }
    public ClassNode currentClass() { return currentClass; }
    public Set<String> questTagIds() { return questTagIds; }
    public Set<String> traitTagIds() { return traitTagIds; }
    public Set<String> equipTagIds() { return equipTagIds; }
    public Set<String> activeTagIds() { return activeTagIds; }
    public List<Tag> activeTags() { return activeTags; }
    public Stats stats() { return stats; }
    public EmotionSystem emotion() { return emotion; }
    public BuffManager buffManager() { return buffManager; }
    public Inventory inventory() { return inventory; }
    public SurvivalStats survival() { return survival; }
    public MapPos worldPos() { return worldPos; }
    public void setWorldPos(MapPos v) { this.worldPos = v; }
    public BehaviorPool socialPool() { return socialPool; }
    public void setSocialPool(BehaviorPool v) { this.socialPool = v; }
    public BehaviorPool combatPool() { return combatPool; }
    public void setCombatPool(BehaviorPool v) { this.combatPool = v; }
    public BehaviorPool activePool() { return activePool; }
    public void setActivePool(BehaviorPool v) { this.activePool = v; }
    public Map<Unit, Integer> allyAffinities() { return allyAffinities; }
    public int hireCost() { return hireCost; }
    public void setHireCost(int v) { this.hireCost = v; }
    public int contractDuration() { return contractDuration; }
    public void setContractDuration(int v) { this.contractDuration = v; }
    public boolean isMercenary() { return isMercenary; }
    public void setMercenary(boolean v) { this.isMercenary = v; }
    public int gold() { return gold; }
    public void setGold(int v) { this.gold = v; }
    public DifficultyMode difficulty() { return difficulty; }
    public void setDifficulty(DifficultyMode v) { this.difficulty = v; }
    public int level() { return level; }
    public void setLevel(int v) { this.level = v; }
    public int exp() { return exp; }
    public void setExp(int v) { this.exp = v; }
    public Map<String, Object> metadata() { return metadata; }
    public EventBus eventBus() { return eventBus; }

    /** 负重容量：基础 20 + 等级×2 + 力量×1 + 装备CARRY词缀（开拓者式，按难度倍率缩放）。 */
    public double carryCapacity() {
        double baseCap = 20 + level * 2 + stats.getBase("STR");
        return (baseCap + equipmentCarryBonus) * DifficultySettings.get(difficulty).carryMul();
    }

    public double equipmentCarryBonus() { return equipmentCarryBonus; }

    public void setEquipmentCarryBonus(double value) {
        if (Math.abs(equipmentCarryBonus - value) > 0.001) {
            equipmentCarryBonus = value;
            recalculateTags();
        }
    }

    /** 是否超重（超重时移动距离减半、无法探索）。 */
    public boolean isOverloaded() { return inventory.totalWeight() > carryCapacity(); }

    // ==================== 标签 ====================

    @Override
    public boolean hasTag(String tagId) { return activeTagIds.contains(tagId); }

    @Override
    public int gridRow() { return gridPos.row(); }

    /**
     * recalculateTags — 无状态全量重建（运行时标签层核心方法）。
     * 第一步：清除旧效果（订阅+属性快照）；第二步：重建标签ID集合（Set天然去重）；
     * 第三步：工厂创建完整Tag实例；第四步：应用效果。
     */
    public void recalculateTags() {
        // 第一步：清除旧效果
        eventBus.unsubscribeAll(this);
        tagStats.clear();

        // 第二步：重建标签ID集合
        Set<String> newIds = new HashSet<>();
        if (currentRace != null) newIds.addAll(currentRace.tagIds());
        if (currentClass != null) newIds.addAll(currentClass.tagIds());
        newIds.addAll(questTagIds);
        newIds.addAll(traitTagIds);
        newIds.addAll(equipTagIds);
        newIds.addAll(emotion.activeEmotionIds());
        activeTagIds = newIds;

        // 第三步：工厂创建完整Tag实例
        activeTags = tagFactory.createAll(newIds);

        // 第四步：应用效果
        effectEngine.applyStatMods(this, activeTags);
        effectEngine.registerTriggers(this, activeTags, eventBus);

        eventBus.emit(EventTypes.TAG_CHANGED, this);
    }

    public void changeRace(RaceNode newRace) {
        // conflictTags 统一处理：新节点自带的冲突标签从任务/特质来源中清除
        if (newRace != null) {
            for (String conflict : newRace.conflictTags()) {
                questTagIds.remove(conflict);
                traitTagIds.remove(conflict);
            }
        }
        currentRace = newRace;
        recalculateTags();   // 旧种族标签自然消失
        eventBus.emit(EventTypes.RACE_CHANGED, this);
    }

    public void changeClass(ClassNode newClass) {
        currentClass = newClass;
        recalculateTags();
        eventBus.emit(EventTypes.CLASS_CHANGED, this);
    }

    /** 施加情感并立即重建标签（情感 → 标签 → 行为权重链路）。 */
    public void applyEmotion(String emotionId, int intensity) {
        emotion.applyEmotion(emotionId, intensity);
        recalculateTags();
    }

    // ==================== 属性快照 ====================

    public void applyTagStat(String target, Operator op, float value) {
        getOrCreate(tagStats, target).apply(op, value);
    }

    public void resetTagStats() { tagStats.clear(); }

    public void applyBuffStat(String target, Operator op, float value) {
        getOrCreate(buffStats, target).apply(op, value);
    }

    public void resetBuffStats() { buffStats.clear(); }

    private static StatValue getOrCreate(Map<String, StatValue> dict, String key) {
        return dict.computeIfAbsent(key, k -> new StatValue());
    }

    /** 最终属性 = 基础值 → 标签快照 + Buff快照 合并修正（同类效果叠加）。 */
    public float getStat(String key) {
        float baseValue = stats.getBase(key);
        boolean hasSet = false;
        float setValue = 0f, add = 0f, multiply = 1f;
        for (Map<String, StatValue> dict : List.of(tagStats, buffStats)) {
            StatValue sv = dict.get(key);
            if (sv == null) continue;
            if (sv.set() != null) { hasSet = true; setValue = sv.set(); }
            add += sv.add();
            multiply *= sv.multiply();
        }
        return hasSet ? setValue : (baseValue + add) * multiply;
    }

    public int maxHp() { return (int) getStat("HP"); }
    public float atk() { return getStat("ATK"); }
    public float def() { return getStat("DEF"); }
    public float spd() { return getStat("SPD"); }
    public float hpPercent() { return maxHp() <= 0 ? 0f : stats.hp() * 100f / maxHp(); }

    // ==================== 战斗 ====================

    public void heal(int amount) {
        if (isDead) return;
        stats.setHp(Math.min(maxHp(), stats.hp() + amount));
        if (hpPercent() > 30f) hpWarned = false;
    }

    public void takeDamage(float amount, Unit attacker, EventBus bus) {
        takeDamage(amount, attacker, bus, true);
    }

    /**
     * 受到伤害。lethal=false（切磋/打劫）时 HP 归零不死亡，由战斗系统判定结束。
     */
    public void takeDamage(float amount, Unit attacker, EventBus bus, boolean lethal) {
        if (isDead) return;
        stats.setHp(stats.hp() - (int) Math.max(1f, amount));

        if (stats.hp() <= 0) {
            stats.setHp(0);
            if (lethal) {
                isDead = true;
                if (bus != null) bus.emitEvent(DeathEvent.of(this, attacker));
            }
            return;
        }

        if (!hpWarned && hpPercent() <= 30f) {
            hpWarned = true;
            if (bus != null) bus.emit(EventTypes.HP_BELOW_30, this);
        }
    }

    /** 死亡（剧情/处决等非战斗路径）。 */
    public void kill(Unit killer) {
        if (isDead) return;
        stats.setHp(0);
        isDead = true;
        eventBus.emitEvent(DeathEvent.of(this, killer));
    }

    /** 复活（轻度死亡惩罚模式：保留进度原地复活）。 */
    public void revive(float hpPercent) {
        isDead = false;
        stats.setHp(Math.max(1, (int) (maxHp() * Math.max(0.1f, Math.min(1f, hpPercent)))));
    }

    public void revive() { revive(0.5f); }

    /** 切换至战斗行为池（切磋/打劫/袭杀/遇敌）。 */
    public void enterCombat(CombatMode mode) {
        combatMode = mode;
        activePool = combatPool;
    }

    /** 恢复社交行为池（战斗结束后）。 */
    public void exitCombat() {
        combatMode = CombatMode.NONE;
        activePool = (role == UnitRole.NPC || role == UnitRole.ALLY || role == UnitRole.PLAYER)
                ? socialPool : null;
    }

    /** 获取对另一个队友的好感度（缺省0）。 */
    public int getAllyAffinity(Unit other) {
        return allyAffinities.getOrDefault(other, 0);
    }

    /** 视野加成（标签 [夜视]/[鹰眼] 等的 VISION 效果，战争迷雾用）。 */
    public int getVisionBonus() { return (int) getStat("VISION"); }

    public void addAllyAffinity(Unit other, int delta) {
        allyAffinities.put(other, clamp(getAllyAffinity(other) + delta));
    }

    public void addAffinity(int delta) { affinity = clamp(affinity + delta); }

    private static int clamp(int v) { return Math.max(-100, Math.min(100, v)); }

    @Override
    public String toString() { return name + "[" + role + "]" + (isDead ? "(死亡)" : ""); }
}
