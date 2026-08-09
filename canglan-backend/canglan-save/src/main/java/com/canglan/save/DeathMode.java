package com.canglan.save;

/** 死亡模式（定义在存档系统的配置中，而非独立系统）。对应 C# DeathMode。 */
public enum DeathMode {
    PERMADEATH,   // 硬核：删档
    RELOAD,       // 普通：强制读档
    PENALTY       // 轻度：扣金币/经验原地复活
}
