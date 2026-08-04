namespace GameCore.Battle;

/// <summary>阵营。</summary>
public enum Side { Ally, Enemy }

/// <summary>格位坐标：row 1=前排 2=后排；col 1=左 2=中 3=右。</summary>
public sealed record GridPosition(int Row, int Col, Side Side)
{
    public Side Opposite => Side == Side.Ally ? Side.Enemy : Side.Ally;
}

/// <summary>
/// GridSystem — 双九宫格管理（3×3×2）。纯数据结构+计算，无外部依赖。
/// 站位效果：前列增减伤、相邻分担。
/// </summary>
public sealed class GridSystem
{
    private readonly Unit.Unit[,,] _grid = new Unit.Unit[2, 2, 3];   // [side][row][col]

    public void PlaceUnit(Unit.Unit unit, GridPosition pos)
    {
        _grid[(int)pos.Side, pos.Row - 1, pos.Col - 1] = unit;
        unit.GridPos = new Unit.GridPos(pos.Row, pos.Col);
    }

    public void RemoveUnit(Unit.Unit unit)
    {
        for (int s = 0; s < 2; s++)
            for (int r = 0; r < 2; r++)
                for (int c = 0; c < 3; c++)
                    if (ReferenceEquals(_grid[s, r, c], unit))
                        _grid[s, r, c] = null;
    }

    public void SwapUnits(Unit.Unit a, Unit.Unit b)
    {
        var pa = FindPosition(a);
        var pb = FindPosition(b);
        if (pa == null || pb == null) return;
        RemoveUnit(a);
        RemoveUnit(b);
        PlaceUnit(a, pb);
        PlaceUnit(b, pa);
    }

    public Unit.Unit GetAt(GridPosition pos)
        => _grid[(int)pos.Side, pos.Row - 1, pos.Col - 1];

    public GridPosition FindPosition(Unit.Unit unit)
    {
        for (int s = 0; s < 2; s++)
            for (int r = 0; r < 2; r++)
                for (int c = 0; c < 3; c++)
                    if (ReferenceEquals(_grid[s, r, c], unit))
                        return new GridPosition(r + 1, c + 1, (Side)s);
        return null;
    }

    public List<Unit.Unit> GetRow(Side side, int row)
    {
        var list = new List<Unit.Unit>();
        for (int c = 0; c < 3; c++)
            if (_grid[(int)side, row - 1, c] != null) list.Add(_grid[(int)side, row - 1, c]);
        return list;
    }

    public List<Unit.Unit> GetColumn(Side side, int col)
    {
        var list = new List<Unit.Unit>();
        for (int r = 0; r < 2; r++)
            if (_grid[(int)side, r, col - 1] != null) list.Add(_grid[(int)side, r, col - 1]);
        return list;
    }

    public List<Unit.Unit> GetAll(Side side)
    {
        var list = new List<Unit.Unit>();
        for (int r = 0; r < 2; r++)
            for (int c = 0; c < 3; c++)
                if (_grid[(int)side, r, c] != null) list.Add(_grid[(int)side, r, c]);
        return list;
    }

    /// <summary>按目标模式取目标集（origin 所在阵营/行列决定作用范围；ALL 取对面全场）。</summary>
    public List<Unit.Unit> GetTargets(GridPosition origin, Skill.TargetPattern pattern)
    {
        switch (pattern)
        {
            case Skill.TargetPattern.Single:
                var at = GetAt(origin);
                return at != null ? new List<Unit.Unit> { at } : new List<Unit.Unit>();
            case Skill.TargetPattern.Row:
                return GetRow(origin.Side, origin.Row);
            case Skill.TargetPattern.Column:
                return GetColumn(origin.Side, origin.Col);
            case Skill.TargetPattern.All:
                return GetAll(origin.Opposite);
            case Skill.TargetPattern.Self:
                var self = GetAt(origin);
                return self != null ? new List<Unit.Unit> { self } : new List<Unit.Unit>();
            case Skill.TargetPattern.Adjacent:
                return GetAdjacent(origin);
            default:
                return new List<Unit.Unit>();
        }
    }

    private List<Unit.Unit> GetAdjacent(GridPosition origin)
    {
        var list = new List<Unit.Unit>();
        foreach (var (dr, dc) in new[] { (0, -1), (0, 1), (-1, 0), (1, 0) })
        {
            var r = origin.Row - 1 + dr;
            var c = origin.Col - 1 + dc;
            if (r is >= 0 and < 2 && c is >= 0 and < 3)
            {
                var u = _grid[(int)origin.Side, r, c];
                if (u != null) list.Add(u);
            }
        }
        return list;
    }

    /// <summary>前排 +15% 伤害修正。</summary>
    public static float GetPositionModifier(Unit.GridPos pos)
        => pos.Row == 1 ? 1.15f : 1.0f;

    /// <summary>相邻友方（同行左右邻，分担伤害/掩护用）。</summary>
    public List<Unit.Unit> GetAdjacentAllies(Unit.Unit unit)
    {
        var allies = new List<Unit.Unit>();
        var p = FindPosition(unit);
        if (p == null) return allies;
        foreach (var dc in new[] { -1, 1 })
        {
            var nc = p.Col - 1 + dc;
            if (nc >= 0 && nc < 3)
            {
                var adj = _grid[(int)p.Side, p.Row - 1, nc];
                if (adj != null && !ReferenceEquals(adj, unit)) allies.Add(adj);
            }
        }
        return allies;
    }
}
