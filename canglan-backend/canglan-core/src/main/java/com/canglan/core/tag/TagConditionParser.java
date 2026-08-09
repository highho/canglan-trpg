package com.canglan.core.tag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TagConditionParser — 条件表达式字符串 → 条件树。对应 C# TagConditionParser。
 * 支持: HasTag(火焰Lv3) / HasAllTags([神圣,光明,Lv5]) / HasAnyTag([匕首,毒]) /
 *       TierAtLeast(火焰,5) / NOT x / x AND y / x OR y。
 * 列表内 "LvN" 项附加到前一个标签项，形成层级条件。
 */
public final class TagConditionParser {

    private static final Pattern LV_SUFFIX = Pattern.compile("^(.*?)Lv(\\d+)$", Pattern.CASE_INSENSITIVE);

    public TagCondition parse(String expr) {
        expr = expr.trim();
        // 括号外 AND/OR 优先切分（修正 C# 原版前缀抢先导致 "HasTag(x) AND HasTag(y)" 无法解析的缺陷）
        if (indexOfOutsideParens(expr, " AND ") >= 0) {
            List<TagCondition> parts = new ArrayList<>();
            for (String p : splitOutsideParens(expr, " AND ")) parts.add(parse(p));
            return new AndCondition(parts);
        }
        if (indexOfOutsideParens(expr, " OR ") >= 0) {
            List<TagCondition> parts = new ArrayList<>();
            for (String p : splitOutsideParens(expr, " OR ")) parts.add(parse(p));
            return new OrCondition(parts);
        }
        if (expr.startsWith("HasAllTags(")) return parseHasAllTagsFull(expr);
        if (expr.startsWith("HasAnyTag(")) return new HasAnyTag(parseTagList(inner(expr, "HasAnyTag(")));
        if (expr.startsWith("HasTag(")) return parseHasTag(expr);
        if (expr.startsWith("TierAtLeast(")) return parseTierAtLeast(expr);
        if (expr.startsWith("NOT ")) return new NotCondition(parse(expr.substring(4)));
        throw new IllegalArgumentException("无法解析条件: " + expr);
    }

    /** "HasTag(暗杀)" → HasTag；"HasTag(火焰Lv3)" → TagTierAtLeast(火焰,3)。 */
    private TagCondition parseHasTag(String expr) {
        String content = inner(expr, "HasTag(").trim();
        Matcher m = LV_SUFFIX.matcher(content);
        if (m.matches() && !m.group(1).isEmpty()) {
            return new TagTierAtLeast(m.group(1), Integer.parseInt(m.group(2)));
        }
        return new HasTag(content);
    }

    /**
     * "[神圣,光明,Lv5]" → {HasTag(神圣), TagTierAtLeast(光明,5)}；
     * 裸 LvN 提升前一项为层级条件。
     */
    private List<TagCondition> parseTagListAsConditions(String content) {
        content = stripBrackets(content);
        List<TagCondition> conditions = new ArrayList<>();
        for (String raw : splitCsv(content)) {
            Matcher m = LV_SUFFIX.matcher(raw);
            if (m.matches() && !m.group(1).isEmpty()) {
                conditions.add(new TagTierAtLeast(m.group(1), Integer.parseInt(m.group(2))));
            } else if (raw.regionMatches(true, 0, "Lv", 0, 2) && !conditions.isEmpty()) {
                TagCondition last = conditions.get(conditions.size() - 1);
                if (last instanceof HasTag ht) {
                    conditions.set(conditions.size() - 1,
                            new TagTierAtLeast(ht.tagId(), Integer.parseInt(raw.substring(2))));
                }
            } else {
                conditions.add(new HasTag(raw));
            }
        }
        return conditions;
    }

    private Set<String> parseTagList(String content) {
        content = stripBrackets(content);
        Set<String> set = new LinkedHashSet<>();
        for (String raw : splitCsv(content)) {
            if (raw.regionMatches(true, 0, "Lv", 0, 2)) continue; // LvN 交由层级条件处理
            set.add(raw);
        }
        return set;
    }

    /** HasAllTags：单项直接返回，多项 AND 组合（含层级条件）。 */
    private TagCondition parseHasAllTagsFull(String expr) {
        List<TagCondition> conditions = parseTagListAsConditions(inner(expr, "HasAllTags("));
        return conditions.size() == 1 ? conditions.get(0) : new AndCondition(conditions);
    }

    private TagCondition parseTierAtLeast(String expr) {
        String content = inner(expr, "TierAtLeast(").trim();
        String[] parts = content.split(",");
        return new TagTierAtLeast(parts[0].trim(), Integer.parseInt(parts[1].trim()));
    }

    // ==================== 辅助 ====================

    private static String inner(String expr, String prefix) {
        return expr.substring(prefix.length(), expr.length() - 1);
    }

    private static String stripBrackets(String s) {
        s = s.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        return s.trim();
    }

    private static List<String> splitCsv(String s) {
        List<String> result = new ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }

    private static int indexOfOutsideParens(String s, String token) {
        int depth = 0;
        for (int i = 0; i <= s.length() - token.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(' || ch == '[') depth++;
            else if (ch == ')' || ch == ']') depth--;
            else if (depth == 0 && s.startsWith(token, i)) return i;
        }
        return -1;
    }

    private static List<String> splitOutsideParens(String s, String token) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(' || ch == '[') depth++;
            else if (ch == ')' || ch == ']') depth--;
            else if (depth == 0 && i + token.length() <= s.length() && s.startsWith(token, i)) {
                parts.add(s.substring(start, i));
                i += token.length() - 1;
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }
}
