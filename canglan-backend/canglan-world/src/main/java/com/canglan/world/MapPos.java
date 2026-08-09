package com.canglan.world;

/** 大地图坐标。对应 C# MapPos。 */
public record MapPos(int x, int y) {

    public double distanceTo(MapPos other) {
        int dx = x - other.x;
        int dy = y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
