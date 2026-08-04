using GameCore.Unit;

namespace GameCore.Npc;

/// <summary>
/// DialogueAction — 对话操作接口。
/// 实现：ChangeAffinity / GiveItem / SetQuestFlag / AddTag / RemoveTag / EnterCombatMode。
/// </summary>
public interface IDialogueAction
{
    void Execute(Unit.Unit player, Unit.Unit npc);
}

/// <summary>好感度调整。</summary>
public sealed record ChangeAffinity(int Amount) : IDialogueAction
{
    public void Execute(Unit.Unit player, Unit.Unit npc) => npc.AddAffinity(Amount);
}

/// <summary>给予物品（NPC → 玩家）。</summary>
public sealed record GiveItem(string ItemId, int Quantity) : IDialogueAction
{
    public void Execute(Unit.Unit player, Unit.Unit npc)
    {
        npc.Inventory.Remove(ItemId, Quantity);
        player.Inventory.Add(ItemId, Quantity);
    }
}

/// <summary>设置任务旗标（存 Metadata["questFlags"]）。</summary>
public sealed record SetQuestFlag(string FlagId, bool Value) : IDialogueAction
{
    public void Execute(Unit.Unit player, Unit.Unit npc)
    {
        if (!player.Metadata.TryGetValue("questFlags", out var raw) || raw is not Dictionary<string, bool> flags)
            player.Metadata["questFlags"] = flags = new Dictionary<string, bool>();
        flags[FlagId] = Value;
    }
}

/// <summary>给玩家添加任务标签（不可逆来源）。</summary>
public sealed record AddTag(string TagId) : IDialogueAction
{
    public void Execute(Unit.Unit player, Unit.Unit npc)
    {
        player.QuestTagIds.Add(TagId);
        player.RecalculateTags();
    }
}

/// <summary>移除玩家任务标签。</summary>
public sealed record RemoveTag(string TagId) : IDialogueAction
{
    public void Execute(Unit.Unit player, Unit.Unit npc)
    {
        player.QuestTagIds.Remove(TagId);
        player.RecalculateTags();
    }
}

/// <summary>NPC 切换战斗模式（切磋/打劫/袭杀）。</summary>
public sealed record EnterCombatMode(CombatMode Mode) : IDialogueAction
{
    public void Execute(Unit.Unit player, Unit.Unit npc) => npc.EnterCombat(Mode);
}

/// <summary>对话分支：条件 + 文本 + 下一节点 + 触发操作。</summary>
public sealed record DialogueBranch(
    Tag.ITagCondition Condition,
    string Text,
    string NextNodeId,
    IReadOnlyList<IDialogueAction> Actions);

/// <summary>
/// DialogueNode — 对话节点。基于双方标签匹配选择分支（第一个满足条件的）。
/// </summary>
public sealed class DialogueNode
{
    public string Id { get; }
    public string Text { get; }
    public List<DialogueBranch> Branches { get; } = new();
    public List<IDialogueAction> OnEnterActions { get; } = new();
    public bool IsExit { get; set; }

    public DialogueNode(string id, string text)
    {
        Id = id;
        Text = text;
    }

    /// <summary>根据上下文选择最佳分支（第一个满足条件的）。</summary>
    public DialogueBranch SelectBranch(Tag.EvalContext ctx)
        => Branches.FirstOrDefault(b => b.Condition.Evaluate(ctx.UnitTagIds));

    /// <summary>无条件的默认分支。</summary>
    public DialogueBranch DefaultBranch()
        => Branches.FirstOrDefault(b => b.Condition is Tag.AlwaysTrue);
}

/// <summary>
/// DialogueTree — 对话树。条件分支遍历，选择分支时执行操作。
/// </summary>
public sealed class DialogueTree
{
    private readonly Dictionary<string, DialogueNode> _nodesById;
    private readonly string _rootNodeId;

    public DialogueTree(string rootNodeId, Dictionary<string, DialogueNode> nodes)
    {
        _rootNodeId = rootNodeId;
        _nodesById = nodes;
    }

    /// <summary>从根节点开始遍历。</summary>
    public DialogueNode GetRoot() => _nodesById.TryGetValue(_rootNodeId, out var n) ? n : null;

    /// <summary>根据触发器选择对话入口（无匹配回退根节点）。</summary>
    public DialogueNode SelectTrigger(string trigger, Unit.Unit context)
        => _nodesById.TryGetValue(trigger, out var n) ? n : GetRoot();

    public DialogueNode GetNode(string id) => _nodesById.TryGetValue(id, out var n) ? n : null;

    /// <summary>推进对话：选择分支 → 执行操作 → 返回下一节点（null=对话结束）。</summary>
    public DialogueNode Next(DialogueNode current, Tag.EvalContext ctx)
    {
        var branch = current.SelectBranch(ctx) ?? current.DefaultBranch();
        if (branch == null || branch.NextNodeId == null) return null;
        foreach (var action in branch.Actions) action.Execute(ctx.Player, ctx.Npc);
        return GetNode(branch.NextNodeId);
    }
}

/// <summary>对话树加载器（从 npcs.json 的 dialogueTree 节解析）。</summary>
public static class DialogueTreeLoader
{
    private static readonly Tag.TagConditionParser Parser = new();

    public static DialogueTree Load(System.Text.Json.JsonElement treeNode)
    {
        var parser = Parser;
        var nodes = new Dictionary<string, DialogueNode>();
        string rootId = null;

        foreach (var prop in treeNode.EnumerateObject())
        {
            var n = prop.Value;
            var node = new DialogueNode(prop.Name,
                n.TryGetProperty("text", out var t) ? t.GetString() : "");
            node.IsExit = n.TryGetProperty("isExit", out var ex) && ex.GetBoolean();

            if (n.TryGetProperty("onEnter", out var enters))
                foreach (var a in enters.EnumerateArray())
                    node.OnEnterActions.Add(ParseAction(a));

            if (n.TryGetProperty("branches", out var branches))
            {
                foreach (var b in branches.EnumerateArray())
                {
                    var condText = b.TryGetProperty("condition", out var c) ? c.GetString() : null;
                    var condition = string.IsNullOrEmpty(condText)
                        ? (Tag.ITagCondition)new Tag.AlwaysTrue()
                        : parser.Parse(condText);
                    var actions = new List<IDialogueAction>();
                    if (b.TryGetProperty("actions", out var acts))
                        foreach (var a in acts.EnumerateArray())
                            actions.Add(ParseAction(a));
                    node.Branches.Add(new DialogueBranch(
                        condition,
                        b.TryGetProperty("text", out var bt) ? bt.GetString() : "",
                        b.TryGetProperty("next", out var nx) ? nx.GetString() : null,
                        actions));
                }
            }

            if (rootId == null) rootId = prop.Name;   // 第一个节点为根
            if (n.TryGetProperty("root", out var isRoot) && isRoot.GetBoolean()) rootId = prop.Name;
            nodes[prop.Name] = node;
        }
        return rootId == null ? null : new DialogueTree(rootId, nodes);
    }

    private static IDialogueAction ParseAction(System.Text.Json.JsonElement a)
    {
        var type = a.TryGetProperty("type", out var t) ? t.GetString() : "";
        return type switch
        {
            "affinity" => new ChangeAffinity(a.GetProperty("amount").GetInt32()),
            "giveItem" => new GiveItem(a.GetProperty("itemId").GetString(),
                a.TryGetProperty("count", out var c) ? c.GetInt32() : 1),
            "questFlag" => new SetQuestFlag(a.GetProperty("flag").GetString(),
                !a.TryGetProperty("value", out var v) || v.GetBoolean()),
            "addTag" => new AddTag(a.GetProperty("tag").GetString()),
            "removeTag" => new RemoveTag(a.GetProperty("tag").GetString()),
            "enterCombat" => new EnterCombatMode(
                (a.GetProperty("mode").GetString() ?? "SPAR").ToUpperInvariant() switch
                {
                    "ROB" => CombatMode.Rob,
                    "LETHAL" => CombatMode.Lethal,
                    _ => CombatMode.Spar
                }),
            _ => new ChangeAffinity(0)
        };
    }
}
