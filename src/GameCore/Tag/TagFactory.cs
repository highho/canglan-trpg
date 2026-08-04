namespace GameCore.Tag;

/// <summary>
/// TagFactory — 从 TagDef 创建运行时 Tag 实例。
/// recalculateTags 第四步调用 createAll。
/// </summary>
public sealed class TagFactory
{
    private readonly TagRegistry _registry;

    public TagFactory(TagRegistry registry)
    {
        _registry = registry;
    }

    public Tag Create(string tagId) => new(_registry.Get(tagId));

    public List<Tag> CreateAll(IEnumerable<string> tagIds)
        => tagIds.Select(Create).ToList();
}
