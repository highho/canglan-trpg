package com.canglan.world.stats;

/**
 * Stats — Unit 基础属性容器。HP 键语义 = 生命上限；当前血量单独存于 hp。
 * 对应 C# Stats。
 */
public final class Stats {

    private int maxHp = 100;
    private int hp = 100;
    private float atk = 10f;
    private float def = 5f;
    private float spd = 10f;
    private float critRate = 0.05f;
    private float str = 5f;   // 力量（负重/近战加成，开拓者式属性）
    private float intel = 5f; // 智力（制造/技能效率）
    private float con = 5f;   // 体质（生存消耗减免）

    public int maxHp() { return maxHp; }
    public void setMaxHp(int v) { this.maxHp = v; }
    public int hp() { return hp; }
    public void setHp(int v) { this.hp = v; }
    public float atk() { return atk; }
    public void setAtk(float v) { this.atk = v; }
    public float def() { return def; }
    public void setDef(float v) { this.def = v; }
    public float spd() { return spd; }
    public void setSpd(float v) { this.spd = v; }
    public float critRate() { return critRate; }
    public void setCritRate(float v) { this.critRate = v; }
    public float str() { return str; }
    public void setStr(float v) { this.str = v; }
    public float intel() { return intel; }
    public void setIntel(float v) { this.intel = v; }
    public float con() { return con; }
    public void setCon(float v) { this.con = v; }

    public boolean isHpEmpty() { return hp <= 0; }

    /** 按属性键读取基础值（未含标签/Buff修正）。 */
    public float getBase(String key) {
        return switch (key.toUpperCase()) {
            case "HP", "MAXHP", "MAX_HP" -> maxHp;
            case "ATK" -> atk;
            case "DEF" -> def;
            case "SPD" -> spd;
            case "CRIT", "CRITRATE" -> critRate;
            case "STR" -> str;
            case "INT" -> intel;
            case "CON" -> con;
            default -> 0f;
        };
    }

    /** 按属性键写入基础值（角色创建/升级用）。 */
    public void setBase(String key, float value) {
        switch (key.toUpperCase()) {
            case "HP", "MAXHP", "MAX_HP" -> {
                maxHp = (int) value;
                hp = Math.min(hp == 0 ? maxHp : hp, maxHp);
                if (hp <= 0) hp = maxHp;
            }
            case "ATK" -> atk = value;
            case "DEF" -> def = value;
            case "SPD" -> spd = value;
            case "CRIT", "CRITRATE" -> critRate = value;
            default -> { }
        }
    }
}
