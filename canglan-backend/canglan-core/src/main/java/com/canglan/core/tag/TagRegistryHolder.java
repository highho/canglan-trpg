package com.canglan.core.tag;

/**
 * 持有当前激活的 TagRegistry。对应 C# TagRegistry.Instance 静态点。
 * TagTierAtLeast 等条件评估时无参数签名，需通过此 holder 访问注册表。
 * 刻意保留的全局点（与 C# 对齐）；由 Bootstrap 在加载 tags.json 后 set。
 */
public final class TagRegistryHolder {
    private static volatile TagRegistry current;

    private TagRegistryHolder() {}

    public static void set(TagRegistry registry) { current = registry; }
    public static TagRegistry current() { return current; }
}
