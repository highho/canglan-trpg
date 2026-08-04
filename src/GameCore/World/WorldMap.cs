namespace GameCore.World;

/// <summary>大地图坐标。</summary>
public sealed record MapPos(int X, int Y)
{
    public double DistanceTo(MapPos other)
    {
        var dx = X - other.X;
        var dy = Y - other.Y;
        return Math.Sqrt(dx * dx + dy * dy);
    }
}

/// <summary>迷雾格子状态：未探索(黑) → 已探索(半透明) → 可见。</summary>
public enum CellState { Unexplored, Explored, Visible }

/// <summary>迷雾存档行（每行 U/E/V 字符序列）。</summary>
public sealed record FogRow(int Y, string States);

/// <summary>
/// FogOfWar — 战争迷雾。圆形视野（dx²+dy² ≤ range²），视野离开后 VISIBLE → EXPLORED。
/// 视野范围由基础值 + 标签修正（[夜视]/[鹰眼] 等 VISION 效果）。
/// </summary>
public sealed class FogOfWar
{
    public int Width { get; }
    public int Height { get; }
    public int VisionRange { get; set; }

    private readonly CellState[,] _states;

    public FogOfWar(int width, int height, int baseVision)
    {
        Width = width;
        Height = height;
        VisionRange = baseVision;
        _states = new CellState[height, width];   // 缺省 Unexplored
    }

    /// <summary>根据 Unit 位置 + 视野更新迷雾。</summary>
    public void Update(Unit.Unit unit)
    {
        var range = VisionRange + unit.GetVisionBonus();
        var pos = unit.WorldPos;
        if (pos == null) return;
        for (var dy = -range; dy <= range; dy++)
            for (var dx = -range; dx <= range; dx++)
            {
                var nx = pos.X + dx;
                var ny = pos.Y + dy;
                if (InBounds(nx, ny) && dx * dx + dy * dy <= range * range)
                    _states[ny, nx] = CellState.Visible;
            }
    }

    /// <summary>视野离开后：VISIBLE → EXPLORED（半透明）。</summary>
    public void DecayAfterMove(Unit.Unit unit)
    {
        var range = VisionRange + unit.GetVisionBonus();
        var pos = unit.WorldPos;
        if (pos == null) return;
        for (var y = 0; y < Height; y++)
            for (var x = 0; x < Width; x++)
            {
                if (_states[y, x] == CellState.Visible && Distance(pos.X, pos.Y, x, y) > range)
                    _states[y, x] = CellState.Explored;
            }
    }

    public CellState Get(int x, int y) => InBounds(x, y) ? _states[y, x] : CellState.Unexplored;

    public bool IsVisible(int x, int y) => InBounds(x, y) && _states[y, x] == CellState.Visible;

    /// <summary>存档导出：每行 → "U/E/V" 字符序列。</summary>
    public List<FogRow> ExportRows()
    {
        var rows = new List<FogRow>();
        for (var y = 0; y < Height; y++)
        {
            var chars = new char[Width];
            for (var x = 0; x < Width; x++)
                chars[x] = _states[y, x] switch
                {
                    CellState.Visible => 'V',
                    CellState.Explored => 'E',
                    _ => 'U'
                };
            rows.Add(new FogRow(y, new string(chars)));
        }
        return rows;
    }

    /// <summary>读档恢复迷雾。</summary>
    public void ImportRows(List<FogRow> rows)
    {
        if (rows == null) return;
        foreach (var row in rows)
        {
            var y = row.Y;
            var states = row.States;
            if (y < 0 || y >= Height || states == null) continue;
            for (var x = 0; x < Width && x < states.Length; x++)
                _states[y, x] = states[x] switch
                {
                    'V' => CellState.Visible,
                    'E' => CellState.Explored,
                    _ => CellState.Unexplored
                };
        }
    }

    private bool InBounds(int x, int y) => x >= 0 && x < Width && y >= 0 && y < Height;

    private static double Distance(int x1, int y1, int x2, int y2)
        => Math.Sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2));
}

/// <summary>地图层（每层独立迷雾）。</summary>
public enum MapLayer
{
    Surface = 5,        // 地表 — 默认视野5格
    Underground = 3,    // 地下 — 视野减半
    Sky = 8             // 天空 — 视野最大
}

/// <summary>地形物类型。</summary>
public enum FeatureType { GatherPoint, Building, NpcSpawn, DungeonEntrance, Home, MonsterSpawn }

/// <summary>地形物（采集点/建筑/NPC刷新点/副本入口/家园）。</summary>
public sealed record TerrainFeature(string Id, MapPos Pos, FeatureType Type);

/// <summary>
/// WorldMap — 多层地图（地上/地下/天空），每层独立 FogOfWar。
/// 维护地形物列表与生态格子（biome 决定体温/水分消耗）。
/// </summary>
public sealed class WorldMap
{
    public int Width { get; }
    public int Height { get; }
    public MapLayer CurrentLayer { get; private set; } = MapLayer.Surface;

    private readonly Dictionary<MapLayer, FogOfWar> _layers = new();
    private readonly List<TerrainFeature> _features = new();
    private readonly BiomeType[,] _biomes;

    public WorldMap(int width, int height)
    {
        Width = width;
        Height = height;
        foreach (MapLayer layer in Enum.GetValues<MapLayer>())
            _layers[layer] = new FogOfWar(width, height, (int)layer);
        _biomes = new BiomeType[height, width];   // 缺省 Plains
        for (var y = 0; y < height; y++)
            for (var x = 0; x < width; x++)
                _biomes[y, x] = BiomeType.Plains;
    }

    public void SwitchLayer(MapLayer layer) => CurrentLayer = layer;

    public FogOfWar CurrentFog() => _layers[CurrentLayer];

    public FogOfWar GetFog(MapLayer layer) => _layers[layer];

    // ==================== 生态 ====================

    public void SetBiome(int x, int y, BiomeType biome)
    {
        if (x >= 0 && x < Width && y >= 0 && y < Height) _biomes[y, x] = biome;
    }

    public BiomeType CurrentBiome(MapPos pos)
        => pos != null && pos.X >= 0 && pos.X < Width && pos.Y >= 0 && pos.Y < Height
            ? _biomes[pos.Y, pos.X]
            : BiomeType.Plains;

    // ==================== 地形物 ====================

    /// <summary>查找范围内的可交互物。</summary>
    public List<TerrainFeature> FindNearby(MapPos pos, int range)
        => _features.Where(f => f.Pos.DistanceTo(pos) <= range).ToList();

    public void AddFeature(TerrainFeature feature) => _features.Add(feature);

    public void RemoveFeature(TerrainFeature feature) => _features.Remove(feature);

    public IReadOnlyList<TerrainFeature> Features => _features;
}
