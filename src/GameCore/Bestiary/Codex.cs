using GameCore.EventBus;

namespace GameCore.Bestiary;

/// <summary>怪物图鉴条目：记录击杀次数与首次击杀时间。</summary>
public sealed class CodexEntry
{
    public string MonsterId { get; init; }
    public string MonsterName { get; init; }
    public int Kills { get; set; }
    public int FirstKillTurn { get; set; }
}

/// <summary>怪物图鉴：自动订阅 UNIT_DEATH 事件，随击杀逐渐解锁奖励标签。</summary>
public sealed class Codex
{
    public static Codex Instance { get; private set; }

    private readonly Dictionary<string, CodexEntry> _entries = new();
    private int _turnNumber;   // 由外部更新（Move 时递增）

    public Codex(IEventBus bus)
    {
        Instance = this;
        bus.SubscribeWithOwner(EventTypes.UnitDeath, OnUnitDeath, this);
    }

    public int TurnNumber { set => _turnNumber = value; }
    public IReadOnlyDictionary<string, CodexEntry> Entries => _entries;

    /// <summary>按条件匹配的已击杀怪物ID集合。</summary>
    public HashSet<string> GetMonstersByTag(string tag)
    {
        // 简单实现：通过 MonsterTemplateRegistry 查怪物标签
        var result = new HashSet<string>();
        foreach (var (id, entry) in _entries)
        {
            if (entry.Kills > 0 && Monster.MonsterTemplateRegistry.Instance.TryGet(id, out var tmpl))
            {
                if (tmpl.RaceTagIds.Contains(tag) || tmpl.PersonalityTagIds.Contains(tag))
                    result.Add(id);
            }
        }
        return result;
    }

    /// <summary>全图鉴种类数（至少击杀 1 次）。</summary>
    public int TotalSpeciesKilled => _entries.Values.Count(e => e.Kills > 0);

    /// <summary>总击杀数。</summary>
    public int TotalKills => _entries.Values.Sum(e => e.Kills);

    /// <summary>特定怪物击杀数。</summary>
    public int KillsOf(string monsterId)
        => _entries.TryGetValue(monsterId, out var e) ? e.Kills : 0;

    /// <summary>读档恢复。</summary>
    public void Restore(List<CodexEntry> saved)
    {
        foreach (var e in saved)
            _entries[e.MonsterId] = e;
    }

    public List<CodexEntry> Snapshot() => _entries.Values.ToList();

    private void OnUnitDeath(Event e)
    {
        var dead = e.DeadUnit;
        if (dead == null || dead.Role != Unit.UnitRole.Monster) return;
        var monsterId = dead.Metadata.TryGetValue("monsterTemplateId", out var mid) ? mid as string : dead.Name;
        if (string.IsNullOrEmpty(monsterId)) return;

        if (!_entries.TryGetValue(monsterId, out var entry))
        {
            entry = new CodexEntry
            {
                MonsterId = monsterId,
                MonsterName = dead.Name,
                FirstKillTurn = _turnNumber,
                Kills = 0
            };
            _entries[monsterId] = entry;
        }
        entry.Kills++;
    }
}
