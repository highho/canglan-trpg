package com.canglan.save;

/** 存档槽位信息。对应 C# SaveSlotInfo。 */
public record SaveSlotInfo(int slot, long timestamp, long playTime, String location, int level) {
}
