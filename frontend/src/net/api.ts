/**
 * api.ts — REST 客户端（对齐 HttpApiServer 端点全集；P8 补 creation/options 与 game/start）。
 */

export interface NarrationLine {
  text: string;
  kind: string;   // NarrationKind 枚举名：NARRATION/INPUT/SYSTEM/DIALOGUE/COMBAT/REWARD/ERROR
}

export interface DirectionInfo { dir: string; terrain: string; }

export interface QuickSlotInfo { label: string; count: number; }

export interface Hud {
  hasPlayer: boolean;
  creating: number;          // 数字阶段：0=未创建 1=种族 2=职业 3=特质
  time: string;
  difficulty: string;        // DifficultyMode 枚举名
  stepCount: number;
  name?: string;
  level?: number;
  race?: string;
  clazz?: string;
  hp?: number;
  maxHp?: number;
  exp?: number;
  gold?: number;
  x?: number;
  y?: number;
  hunger?: number;
  thirst?: number;
  temperature?: number;
  sanity?: number;
  atk?: number;
  def?: number;
  spd?: number;
  weight?: string;
  overloaded?: boolean;
  tags?: string[];
  companions?: string[];
  directions?: DirectionInfo[];
  nearby?: { monsters: string[]; npcs: string[] };
  quickBar?: QuickSlotInfo[];
  homeLevel?: number;
  homeBuildings?: string[];
}

export interface CommandResponse {
  narration: NarrationLine[];
  hud: Hud;
  sessionId?: string;   // 仅 /api/game/start 返回
}

export interface GameState {
  log: NarrationLine[];
  hud: Hud;
}

export interface SaveSlotInfo {
  slot: number;
  timestamp: string;
  playTime: string;
  location: string;
  level: number;
}

export interface CreationOption { id: string; name: string; description?: string; }

export interface CreationOptions {
  races: CreationOption[];
  classes: CreationOption[];
  traits: CreationOption[];
  difficulties: string[];
}

async function post<T>(path: string, payload: unknown): Promise<T> {
  const resp = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  const body = await resp.json();
  if (!resp.ok) throw new Error((body as { error?: string }).error ?? `HTTP ${resp.status}`);
  return body as T;
}

async function get<T>(path: string): Promise<T> {
  const resp = await fetch(path);
  const body = await resp.json();
  if (!resp.ok) throw new Error((body as { error?: string }).error ?? `HTTP ${resp.status}`);
  return body as T;
}

export const api = {
  health: () => get<{ status: string; aiAvailable: boolean; sessions: number }>('/api/health'),

  newGame: (difficulty?: string) =>
    post<{ sessionId: string; difficulty: string }>('/api/game/new', { difficulty: difficulty ?? '' }),

  creationOptions: (race?: string, clazz?: string) => {
    const q = new URLSearchParams();
    if (race) q.set('race', race);
    if (clazz) q.set('clazz', clazz);
    const qs = q.toString();
    return get<CreationOptions>('/api/creation/options' + (qs ? `?${qs}` : ''));
  },

  /** 一步建档（对齐原 Avalonia「开始冒险」按钮）。 */
  startGame: (req: { name: string; race: string; clazz: string; trait: string; difficulty: string }) =>
    post<CommandResponse>('/api/game/start', req),

  command: (sessionId: string, line: string) =>
    post<CommandResponse>('/api/game/command', { sessionId, line }),

  state: (sessionId: string) => get<GameState>(`/api/game/state?sessionId=${encodeURIComponent(sessionId)}`),

  panel: (sessionId: string, name: string) =>
    get<Record<string, unknown>>(`/api/panel/${name}?sessionId=${encodeURIComponent(sessionId)}`),

  slots: (sessionId: string) =>
    get<{ slots: SaveSlotInfo[] }>(`/api/save/slots?sessionId=${encodeURIComponent(sessionId)}`),

  save: (sessionId: string, slot: number) =>
    post<CommandResponse>(`/api/save/${slot}?sessionId=${encodeURIComponent(sessionId)}`, {}),

  load: (sessionId: string, slot: number) =>
    post<CommandResponse>(`/api/load/${slot}?sessionId=${encodeURIComponent(sessionId)}`, {}),
};
