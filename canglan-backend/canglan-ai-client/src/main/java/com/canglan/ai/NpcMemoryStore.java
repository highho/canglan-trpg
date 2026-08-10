package com.canglan.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;
import com.canglan.core.json.JsonWriter;

/**
 * NpcMemoryStore — 二层记忆库（ai-service/main.py sqlite memories 表的 Java 移植）。
 * 个体记忆以 npcId 为 scope；群体记忆以 group:village / group:guild 为 scope。
 * JSON 文件持久化（零依赖、Android 兼容），线程安全，全局上限 {@link #GLOBAL_CAP} 条（FIFO 裁剪）。
 */
public final class NpcMemoryStore {

    static final int GLOBAL_CAP = 500;

    /** 一条记忆：scope 为个体 npcId 或群体 group:xxx。 */
    public record Entry(int id, String scope, String content, int importance) {}

    private final Path file;
    private final List<Entry> memories = new ArrayList<>();
    private int nextId = 1;

    public NpcMemoryStore(Path file) {
        this.file = file;
        load();
    }

    /** 按 id 倒序（最新在前）召回某 scope 的最近 limit 条内容。 */
    public synchronized List<String> recall(String scope, int limit) {
        List<String> out = new ArrayList<>();
        if (scope == null || scope.isBlank()) return out;
        for (int i = memories.size() - 1; i >= 0 && out.size() < limit; i--) {
            Entry e = memories.get(i);
            if (e.scope().equals(scope)) out.add(e.content());
        }
        return out;
    }

    /** 追加一条记忆并立即落盘；importance 收敛到 1~4。 */
    public synchronized void append(String scope, String content, int importance) {
        if (scope == null || scope.isBlank() || content == null || content.isBlank()) return;
        memories.add(new Entry(nextId++, scope, content, Math.max(1, Math.min(4, importance))));
        while (memories.size() > GLOBAL_CAP) memories.remove(0);
        save();
    }

    public synchronized int size() {
        return memories.size();
    }

    // ==================== JSON 持久化 ====================

    private void load() {
        try {
            if (!Files.exists(file)) return;
            JsonValue root = JsonReader.parse(Files.readString(file, StandardCharsets.UTF_8));
            nextId = root.getInt("nextId", 1);
            JsonValue arr = root.get("memories");
            if (arr != null && arr.isArray()) {
                for (JsonValue e : arr.asArray()) {
                    memories.add(new Entry(e.getInt("id", 0), e.getString("scope", ""),
                            e.getString("content", ""), e.getInt("importance", 1)));
                }
            }
        } catch (Exception ignored) {
            // 文件损坏/读取失败 → 从空库重建，不得影响游戏启动
        }
    }

    private void save() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            List<Object> arr = new ArrayList<>();
            for (Entry e : memories) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.id());
                m.put("scope", e.scope());
                m.put("content", e.content());
                m.put("importance", e.importance());
                arr.add(m);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("nextId", nextId);
            root.put("memories", arr);
            Files.writeString(file, JsonWriter.write(root, 1), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 落盘失败仅内存保留（AI 铁律：不得抛异常到游戏主流程）
        }
    }
}
