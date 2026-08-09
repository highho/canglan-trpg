package com.canglan.core.tag;

import java.util.ArrayList;
import java.util.List;

/**
 * TagFactory — 从 TagDef 创建运行时 Tag 实例。
 * recalculateTags 第三步调用 createAll。对应 C# TagFactory。
 */
public final class TagFactory {

    private final TagRegistry registry;

    public TagFactory(TagRegistry registry) {
        this.registry = registry;
    }

    public Tag create(String tagId) {
        return new Tag(registry.get(tagId));
    }

    public List<Tag> createAll(Iterable<String> tagIds) {
        List<Tag> tags = new ArrayList<>();
        for (String id : tagIds) tags.add(create(id));
        return tags;
    }
}
