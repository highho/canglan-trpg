package com.canglan.save;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonWriter;
import com.canglan.world.FogRow;
import com.canglan.world.unit.Unit;

/**
 * SaveManager — 存档管理。对应 C# SaveManager。
 * 核心洞察：recalculateTags() 是无状态函数，
 * 存档只保存「输入」（源头数据），不保存「输出」（衍生状态）→ 文件极小、迁移安全。
 * 存储：JSON 文件（零依赖；SQLite 适配留待 Maven 可用后实现）。
 */
public final class SaveManager {

    public static final int MAX_SLOTS = 10;
    public static final int CURRENT_VERSION = 1;

    private final Path saveDir;

    public SaveManager(Path saveDir) {
        this.saveDir = saveDir;
        try {
            Files.createDirectories(saveDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建存档目录: " + saveDir, e);
        }
    }

    private Path slotPath(int slot) {
        return saveDir.resolve("save_" + slot + ".json");
    }

    // ==================== 保存 ====================

    /** 从当前游戏状态构建 SaveData（源头数据采集）。可选上下文参数允许 null。 */
    public static SaveData capture(Unit player, List<Unit> companions,
                                   Map<String, Integer> npcAffinities,
                                   Map<String, Map<String, Integer>> factionReputations,
                                   Map<String, QuestSaveData> quests,
                                   Map<String, Boolean> worldFlags,
                                   long playTime, String locationName,
                                   int gameDay, int gameHour,
                                   String mapLayer, String biomeId,
                                   List<String> quickBar, Set<String> lockedItems,
                                   Map<String, String> equippedItems,
                                   int stepCount,
                                   List<FogRow> fogOfWar) {
        SaveData data = new SaveData();
        data.version = CURRENT_VERSION;
        data.timestamp = System.currentTimeMillis();
        data.playTime = playTime;
        data.locationName = locationName;

        // 玩家身份（源头数据）
        data.playerName = player.name();
        data.currentRaceId = player.currentRace() != null ? player.currentRace().id() : null;
        data.currentClassId = player.currentClass() != null ? player.currentClass().id() : null;
        data.questTagIds = new HashSet<>(player.questTagIds());
        data.traitTagIds = new HashSet<>(player.traitTagIds());
        data.level = player.level();
        data.exp = player.exp();
        data.gold = player.gold();

        // 生存状态
        data.hunger = player.survival().hunger();
        data.thirst = player.survival().thirst();
        data.temperature = player.survival().temperature();
        data.sanity = player.survival().sanity();

        // 游戏时间 + 难度
        data.gameDay = gameDay;
        data.gameHour = gameHour;
        data.difficulty = player.difficulty().name();

        // 世界位置
        data.worldX = player.worldPos() != null ? player.worldPos().x() : 0;
        data.worldY = player.worldPos() != null ? player.worldPos().y() : 0;
        data.mapLayer = mapLayer != null ? mapLayer : "Surface";
        data.biomeId = biomeId != null ? biomeId : "Plains";
        data.fogOfWar = fogOfWar != null ? new ArrayList<>(fogOfWar) : new ArrayList<>();

        // 背包 / 快捷栏 / 锁定 / 装备
        data.inventory = player.inventory().toSaveMap();
        data.quickBar = quickBar != null ? new ArrayList<>(quickBar) : new ArrayList<>();
        data.lockedItems = lockedItems != null ? new HashSet<>(lockedItems) : new HashSet<>();
        data.equippedItems = equippedItems != null ? new HashMap<>(equippedItems) : new HashMap<>();

        // NPC关系 / 声望 / 队友 / 任务 / 全局标记
        data.npcAffinities = npcAffinities != null ? npcAffinities : new HashMap<>();
        data.factionReputations = factionReputations != null ? factionReputations : new HashMap<>();
        data.companions = new ArrayList<>();
        if (companions != null) {
            for (Unit c : companions) data.companions.add(captureCompanion(c));
        }
        data.quests = quests != null ? quests : new HashMap<>();
        data.worldFlags = worldFlags != null ? worldFlags : new HashMap<>();

        data.stepCount = stepCount;
        return data;
    }

    private static CompanionSaveData captureCompanion(Unit c) {
        CompanionSaveData csd = new CompanionSaveData();
        csd.name = c.name();
        csd.currentRaceId = c.currentRace() != null ? c.currentRace().id() : null;
        csd.currentClassId = c.currentClass() != null ? c.currentClass().id() : null;
        csd.questTagIds = new HashSet<>(c.questTagIds());
        csd.traitTagIds = new HashSet<>(c.traitTagIds());
        csd.level = c.level();
        csd.exp = c.exp();
        csd.recruitmentType = c.isMercenary() ? "Mercenary" : "Bond";
        csd.contractRemaining = c.isMercenary() ? c.contractDuration() : -1;
        csd.affinity = c.affinity();
        csd.allyAffinities = new HashMap<>();
        for (Map.Entry<Unit, Integer> kv : c.allyAffinities().entrySet()) {
            csd.allyAffinities.put(kv.getKey().name(), kv.getValue());
        }
        csd.inventory = c.inventory().toSaveMap();
        return csd;
    }

    /** 写入磁盘。 */
    public boolean save(int slot, SaveData data) {
        if (slot < 0 || slot >= MAX_SLOTS) return false;
        try {
            String json = JsonWriter.write(SaveDataCodec.toMap(data), 0);
            Files.writeString(slotPath(slot), json, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 自动保存（指定槽位）。 */
    public boolean autoSave(int slot, SaveData data) { return save(slot, data); }

    // ==================== 读取 ====================

    /** 读取存档文件（不存在返回 null）。 */
    public SaveData load(int slot) {
        if (slot < 0 || slot >= MAX_SLOTS) return null;
        Path path = slotPath(slot);
        if (!Files.exists(path)) return null;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return SaveDataCodec.fromJson(JsonReader.parse(json));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 列出所有存档槽位。 */
    public List<SaveSlotInfo> listSlots() {
        List<SaveSlotInfo> slots = new ArrayList<>();
        for (int i = 0; i < MAX_SLOTS; i++) {
            SaveData data = load(i);
            if (data != null) {
                slots.add(new SaveSlotInfo(i, data.timestamp, data.playTime, data.locationName, data.level));
            }
        }
        return slots;
    }

    /** 删除存档（硬核模式死亡时调用）。 */
    public boolean delete(int slot) {
        Path path = slotPath(slot);
        if (!Files.exists(path)) return false;
        try {
            Files.delete(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== 版本迁移 ====================

    /** 加载时自动迁移旧版本存档（逐版本推进，最后统一版本号）。 */
    public SaveData migrate(SaveData old) {
        if (old == null || old.version == CURRENT_VERSION) return old;
        // if (old.version < 2) old = migrateV1ToV2(old);
        // if (old.version < 3) old = migrateV2ToV3(old);
        old.version = CURRENT_VERSION;
        return old;
    }
}
