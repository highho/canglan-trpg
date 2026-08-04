using System.Text.Json;

namespace GameCore.AI;

/// <summary>
/// NpcDialogueAi — NPC 动态对话生成。
/// 依据 NPC 身份/人格标签 + 好感度 + 玩家标签 + 场景备注，由本地 LLM 生成符合人设的台词。
/// 失败/离线返回 null，调用方回退静态对话树。
/// </summary>
public sealed class NpcDialogueAi
{
    private readonly IAiChatService _chat;

    public NpcDialogueAi(IAiChatService chat) => _chat = chat;

    public bool IsAvailable => _chat.IsAvailable;

    public string GenerateLine(Unit.Unit npc, Unit.Unit player, string sceneNote = null)
        => !_chat.IsAvailable || npc == null || player == null ? null
            : GenerateLine(npc.Name, npc.ActiveTagIds, npc.Affinity, player.Name, player.ActiveTagIds, sceneNote);

    /// <summary>
    /// 快照重载 —— 调用方先在持有状态的线程抓取只读快照，再交给后台推理，
    /// 避免推理线程读取可变游戏状态（并发脏读）。
    /// </summary>
    public string GenerateLine(string npcName, IEnumerable<string> npcTagIds, int affinity,
        string playerName, IEnumerable<string> playerTagIds, string sceneNote = null)
    {
        if (!_chat.IsAvailable) return null;

        var system =
            "你是一款标签驱动RPG中的NPC扮演引擎。请完全代入NPC身份，用一句简短的中文台词回应玩家（不超过60字），" +
            "语气必须贴合NPC的人格标签与对玩家的好感度，不得提及自己是AI或游戏机制。只输出台词本身。";

        var user =
            $"NPC名字: {npcName}\n" +
            $"NPC身份标签: {string.Join("、", npcTagIds)}\n" +
            $"NPC对玩家好感度: {affinity}（-100敌视 ~ 100挚爱）\n" +
            $"玩家名字: {playerName}\n" +
            $"玩家标签: {string.Join("、", playerTagIds)}\n" +
            (sceneNote != null ? $"当前情景: {sceneNote}\n" : "") +
            "请生成NPC的台词。";

        return _chat.Chat(system, user)?.Trim();
    }
}

/// <summary>
/// BattleNarrator — 战斗回合解说。把数值行动转化为战报文字（可选增强，不影响结算）。
/// </summary>
public sealed class BattleNarrator
{
    private readonly IAiChatService _chat;

    public BattleNarrator(IAiChatService chat) => _chat = chat;

    public bool IsAvailable => _chat.IsAvailable;

    /// <summary>解说一次攻击。返回 null 表示离线（调用方使用规则文案）。</summary>
    public string NarrateAttack(Unit.Unit actor, Unit.Unit target, int damage, bool crit)
    {
        if (!_chat.IsAvailable) return null;

        var system =
            "你是回合制RPG的战斗解说员。用一句不超过40字的中文热血短句描述这次攻击，" +
            "暴击要格外强调。只输出解说词，不要任何解释。";

        var user =
            $"攻击者: {actor.Name}（标签: {string.Join("、", actor.ActiveTagIds)}）\n" +
            $"目标: {target.Name}\n" +
            $"伤害: {damage} {(crit ? "【暴击】" : "")}";

        return _chat.Chat(system, user)?.Trim();
    }

    /// <summary>离线规则文案回退。</summary>
    public static string Fallback(Unit.Unit actor, Unit.Unit target, int damage, bool crit)
        => $"{actor.Name} 攻击 {target.Name}，造成 {damage} 点伤害{(crit ? "（暴击！）" : "")}。";
}

/// <summary>
/// EmotionEvaluatorAi — 事件→情感强度评估。
/// 替代固定情感值：LLM 依据事件语义与单位人格输出 0~100 强度。
/// </summary>
public sealed class EmotionEvaluator
{
    private readonly IAiChatService _chat;

    public EmotionEvaluator(IAiChatService chat) => _chat = chat;

    public bool IsAvailable => _chat.IsAvailable;

    public sealed record EmotionJudgement(string Emotion, int Intensity);

    /// <summary>评估事件引发的主导情感。离线返回 null。</summary>
    public EmotionJudgement Evaluate(string eventDescription, IReadOnlySet<string> unitTagIds)
    {
        if (!_chat.IsAvailable) return null;

        var system =
            "你是游戏情感系统评估器。根据事件与角色人格，判断角色产生的主导情感及强度。" +
            "情感只能是以下四种之一: 恐惧、愤怒、悲伤、喜悦。强度为0~100整数。" +
            "只输出JSON: {\"emotion\":\"...\",\"intensity\":数字}";

        var user =
            $"事件: {eventDescription}\n角色人格/身份标签: {string.Join("、", unitTagIds)}";

        var raw = _chat.Chat(system, user, jsonMode: true);
        if (raw == null) return null;
        try
        {
            using var doc = JsonDocument.Parse(ExtractJson(raw));
            var emotion = doc.RootElement.GetProperty("emotion").GetString();
            var intensity = doc.RootElement.GetProperty("intensity").GetInt32();
            if (emotion is not ("恐惧" or "愤怒" or "悲伤" or "喜悦")) return null;
            return new EmotionJudgement(emotion, Math.Clamp(intensity, 0, 100));
        }
        catch
        {
            return null;
        }
    }

    /// <summary>从可能带前后文的回复中提取第一个 JSON 对象。</summary>
    public static string ExtractJson(string raw)
    {
        var start = raw.IndexOf('{');
        var end = raw.LastIndexOf('}');
        return start >= 0 && end > start ? raw[start..(end + 1)] : raw;
    }
}

/// <summary>
/// IntentMatcher — 自然语言意图→行为选项语义匹配（bge-m3 嵌入 + 余弦相似度）。
/// 用途：玩家自由输入 → 映射到行为池选项（talk/trade/quest/attack...）。
/// </summary>
public sealed class IntentMatcher
{
    private readonly IAiEmbeddingService _embeddings;
    private readonly List<string> _optionIds = new();
    private readonly List<string> _descriptions = new();
    private float[][] _corpusVectors;

    public bool IsAvailable => _embeddings.IsAvailable;

    public IntentMatcher(IAiEmbeddingService embeddings) => _embeddings = embeddings;

    /// <summary>注册候选意图（optionId + 语义描述）。注册完调用 Build 向量化。</summary>
    public void AddOption(string optionId, string description)
    {
        _optionIds.Add(optionId);
        _descriptions.Add(description);
        _corpusVectors = null;
    }

    /// <summary>批量向量化语料。失败返回 false（调用方回退关键字匹配）。</summary>
    public bool Build()
    {
        if (!_embeddings.IsAvailable || _descriptions.Count == 0) return false;
        _corpusVectors = _embeddings.Embed(_descriptions.ToArray());
        return _corpusVectors != null && _corpusVectors.Length == _descriptions.Count;
    }

    /// <summary>匹配查询 → (optionId, 相似度)。相似度 < minScore 返回 null。</summary>
    public (string OptionId, float Score)? Match(string query, float minScore = 0.35f)
    {
        if (_corpusVectors == null && !Build()) return null;
        var qv = _embeddings.Embed(new[] { query });
        if (qv == null || qv.Length == 0) return null;

        var bestIdx = -1;
        var bestScore = float.MinValue;
        for (var i = 0; i < _corpusVectors.Length; i++)
        {
            var score = VectorMath.Cosine(qv[0], _corpusVectors[i]);
            if (score > bestScore) { bestScore = score; bestIdx = i; }
        }
        return bestIdx < 0 || bestScore < minScore ? null : (_optionIds[bestIdx], bestScore);
    }

    /// <summary>规则回退：简单关键字匹配（离线可用）。</summary>
    public string FallbackMatch(string query)
    {
        if (query.Contains("买") || query.Contains("卖") || query.Contains("交易") || query.Contains("装备")) return "trade";
        if (query.Contains("任务") || query.Contains("委托") || query.Contains("冒险")) return "quest";
        if (query.Contains("打") || query.Contains("攻击") || query.Contains("战斗")) return "attack";
        if (query.Contains("聊") || query.Contains("问") || query.Contains("说")) return "talk";
        if (query.Contains("跑") || query.Contains("逃")) return "flee";
        return "talk";
    }
}

/// <summary>
/// QuestFlavorAi — 任务叙事文本生成：把任务定义渲染为面向当前玩家的沉浸式描述。
/// </summary>
public sealed class QuestFlavorAi
{
    private readonly IAiChatService _chat;

    public QuestFlavorAi(IAiChatService chat) => _chat = chat;

    public bool IsAvailable => _chat.IsAvailable;

    public string Describe(Graph.QuestNode quest, Unit.Unit player)
    {
        if (!_chat.IsAvailable) return null;

        var system =
            "你是RPG任务文案生成器。根据任务信息与玩家现状，写一段不超过80字的沉浸式任务描述，" +
            "可点出玩家身份标签与任务的关联。只输出文案。";

        var user =
            $"任务名: {quest.Name}\n" +
            $"任务简介: {quest.Data.Description}\n" +
            $"奖励: {(quest.Data.Rewards != null ? string.Join("、", quest.Data.Rewards.Select(kv => $"{kv.Key}x{kv.Value}")) : "无")}\n" +
            $"玩家: {player.Name}，标签 {string.Join("、", player.ActiveTagIds)}，Lv{player.Level}";

        return _chat.Chat(system, user)?.Trim();
    }
}

/// <summary>
/// BalanceAnalyzer — 数值平衡分析：模拟战斗统计报告 → LLM 输出调整建议（离线策划工具）。
/// </summary>
public sealed class BalanceAnalyzer
{
    private readonly IAiChatService _chat;

    public BalanceAnalyzer(IAiChatService chat) => _chat = chat;

    public bool IsAvailable => _chat.IsAvailable;

    /// <summary>输入模拟统计（自由文本行），输出策划调整建议。</summary>
    public string Analyze(string simulationReport)
    {
        if (!_chat.IsAvailable) return null;

        var system =
            "你是资深游戏数值策划。分析战斗模拟统计数据，指出失衡点（胜率、回合数、伤害分布），" +
            "给出3条以内具体可执行的数值调整建议（含具体数值）。用中文，不超过150字。";

        return _chat.Chat(system, simulationReport)?.Trim();
    }
}

/// <summary>
/// BehaviorExplainer — 行为决策解释：把权重法决策结果翻译成拟人化心理活动（调试/演出用）。
/// 不改变决策本身（权重法仍为唯一决策源），仅做自然语言解释。
/// </summary>
public sealed class BehaviorExplainer
{
    private readonly IAiChatService _chat;

    public BehaviorExplainer(IAiChatService chat) => _chat = chat;

    public bool IsAvailable => _chat.IsAvailable;

    /// <summary>解释一次行为决策。candidates 格式: "选项名(权重)"。离线返回 null。</summary>
    public string Explain(string unitName, IReadOnlySet<string> unitTagIds,
        string chosenOption, IReadOnlyList<string> candidates)
    {
        if (!_chat.IsAvailable) return null;

        var system =
            "你是RPG角色心理活动旁白。根据角色标签与行为权重决策结果，" +
            "用一句不超过35字的中文心理独白解释角色为何做出该选择。只输出独白。";

        var user =
            $"角色: {unitName}（标签: {string.Join("、", unitTagIds)}）\n" +
            $"候选行为权重: {string.Join("、", candidates)}\n" +
            $"最终选择: {chosenOption}";

        return _chat.Chat(system, user)?.Trim();
    }

    /// <summary>离线规则回退文案。</summary>
    public static string Fallback(string chosenOption)
        => $"权衡再三，它选择了：{chosenOption}。";
}

/// <summary>
/// FlavorTextGenerator — 物品/装备风味文本生成（带磁盘缓存，同一物品只生成一次）。
/// </summary>
public sealed class FlavorTextGenerator
{
    private readonly IAiChatService _chat;
    private readonly string _cachePath;

    public bool IsAvailable => _chat.IsAvailable;

    /// <param name="chat">生成服务</param>
    /// <param name="cachePath">缓存文件（JSON: itemId → 风味文本）</param>
    public FlavorTextGenerator(IAiChatService chat, string cachePath)
    {
        _chat = chat;
        _cachePath = cachePath;
    }

    /// <summary>
    /// 获取物品风味文本：优先读缓存 → 缺且AI可用则生成并写缓存 → 否则返回 null。
    /// </summary>
    public string GetOrGenerate(string itemId, string itemName, string hint = null)
    {
        var cache = LoadCache();
        if (cache.TryGetValue(itemId, out var cached)) return cached;
        if (!_chat.IsAvailable) return null;

        var system =
            "你是奇幻RPG物品文案师。为物品写一句不超过25字的风味描述（背景故事/气质），" +
            "不提数值与游戏机制。只输出描述。";

        var text = _chat.Chat(system, $"物品名: {itemName}{(hint != null ? $"（特征: {hint}）" : "")}")?.Trim();
        if (string.IsNullOrWhiteSpace(text)) return null;

        cache[itemId] = text;
        SaveCache(cache);
        return text;
    }

    private Dictionary<string, string> LoadCache()
    {
        try
        {
            if (!string.IsNullOrEmpty(_cachePath) && File.Exists(_cachePath))
                return JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(_cachePath))
                       ?? new Dictionary<string, string>();
        }
        catch { /* 缓存损坏当空处理 */ }
        return new Dictionary<string, string>();
    }

    private void SaveCache(Dictionary<string, string> cache)
    {
        try
        {
            if (string.IsNullOrEmpty(_cachePath)) return;
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(_cachePath))!);
            File.WriteAllText(_cachePath,
                JsonSerializer.Serialize(cache, new JsonSerializerOptions { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }));
        }
        catch { /* 缓存写入失败不影响主流程 */ }
    }
}
