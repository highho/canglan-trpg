/**
 * game.ts — 页面 4：游戏主界面（开拓者式：顶栏属性网格 + 局部地图 + 方向键盘 + 日志条 + 行动区 + 底部菜单）。
 * 事件委托：页面内所有带 data-cmd 的元素（方向键盘/地图格/附近/物品/面板指令）统一在此发送；
 * 面板弹窗内点击先关窗；特殊指令 __return_menu / __ai_settings 在此处理。
 */

import { api } from '../net/api.js';
import { getState, setPage, setState, esc } from '../state/store.js';
import { appendNarration, renderHud } from '../state/narration.js';
import { closeOverlay, initOverlay, openOverlay } from '../overlay/overlay.js';
import { openAiSettings } from './ai-settings.js';

/** 地图标记字（与 overlay 探索面板同款：怪/人/采/建/门/家）。 */
const MARK_TEXT: Record<string, string> = {
  MONSTER_SPAWN: '怪', NPC_SPAWN: '人', GATHER_POINT: '采',
  BUILDING: '建', DUNGEON_ENTRANCE: '门', HOME: '家',
};

/** 标记类型 → 过滤选项卡分组（建/门/家不分组，常显）。 */
const FILTER_GROUP: Record<string, string> = {
  MONSTER_SPAWN: '怪物', NPC_SPAWN: 'NPC', GATHER_POINT: '资源',
};

/** 地形字符 → 中文首字（与探索面板同款映射）。 */
const BIOME_CN: Record<string, string> = {
  P: '平', F: '林', D: '沙', T: '苔', S: '沼', M: '山',
};

/** 局部地图窗口半径（9×9 视野）。 */
const VIEW_R = 4;

/** 相对偏移 → 方向指令（与方向键盘一致的中文命令）。 */
const DIR_BY_DELTA: Record<string, string> = {
  '0,-1': '北', '1,0': '东', '0,1': '南', '-1,0': '西',
};

/** 地图元素过滤状态（资源/怪物/NPC 选项卡 + 文字筛选）。 */
let filterState = { resource: true, monster: true, npc: true, text: '' };

/** 最近一次地图数据缓存（小地图/重绘用，免重复请求）。 */
let mapCache: Record<string, unknown> | null = null;

/** 行动区固定按钮（移动四向由方向按钮承担，此处只留环境行动）。 */
const ACTIONS: { label: string; cmd: string }[] = [
  { label: '环顾四周', cmd: '查看' },
  { label: '探索此处', cmd: '探索' },
  { label: '等到天亮', cmd: '等待' },
  { label: '听听传闻', cmd: '传闻' },
  { label: '喝口水', cmd: '喝水' },
];

/** 快捷话题（自由语句，走 AI 自由对话/规则兜底）。 */
const DIALOG_TOPICS: { label: string; say: string }[] = [
  { label: '寒暄', say: '打个招呼' },
  { label: '聊聊附近', say: '聊聊这附近有什么' },
  { label: '有什么麻烦', say: '最近有什么麻烦事吗' },
  { label: '告辞', say: '我先告辞了' },
];

let inited = false;

export function initGamePage(): void {
  if (inited) return;
  inited = true;

  // 行动固定按钮（静态骨架，直接绑定）
  const actionWrap = document.getElementById('action-buttons');
  if (actionWrap) {
    for (const a of ACTIONS) {
      const btn = document.createElement('button');
      btn.textContent = a.label;
      btn.addEventListener('click', () => void send(a.cmd));
      actionWrap.appendChild(btn);
    }
  }

  // 对话快捷话题
  const dialogWrap = document.getElementById('dialog-buttons');
  if (dialogWrap) {
    for (const t of DIALOG_TOPICS) {
      const btn = document.createElement('button');
      btn.textContent = t.label;
      btn.addEventListener('click', () => void send(t.say));
      dialogWrap.appendChild(btn);
    }
  }

  // 页面级事件委托：所有带 data-cmd 的动态按钮（移动方向/附近/物品快捷/面板指令）
  document.getElementById('page-game')?.addEventListener('click', ev => {
    const target = (ev.target as HTMLElement).closest('[data-cmd]') as HTMLElement | null;
    if (!target?.dataset.cmd) return;
    const cmd = target.dataset.cmd;
    // 面板弹窗里的指令：先关窗再发送
    if (target.closest('.win-body')) closeOverlay();
    if (cmd === '__return_menu') {
      void saveAndMenu();
      return;
    }
    if (cmd === '__ai_settings') {
      void openAiSettings();
      return;
    }
    void send(cmd);
  });

  // 顶栏：菜单（存档并返回菜单）/ 状态（打开角色面板）
  document.getElementById('btn-menu')?.addEventListener('click', () => void saveAndMenu());
  document.getElementById('btn-status')?.addEventListener('click', () => openOverlay('char'));

  // 底部八菜单（静态骨架，直接绑定）
  document.querySelectorAll<HTMLButtonElement>('.menus .menu').forEach(btn => {
    btn.addEventListener('click', () => openOverlay(btn.dataset.panel ?? ''));
  });

  initOverlay(() => void send('存档'));

  // 日志条：点标题展开/收起
  document.getElementById('log-toggle')?.addEventListener('click', () => {
    const bar = document.getElementById('log-bar');
    if (!bar) return;
    const open = bar.classList.toggle('open');
    const toggle = document.getElementById('log-toggle');
    if (toggle) toggle.textContent = open ? '事件日志（点此收起）' : '事件日志（点此展开）';
  });

  // 小地图：打开（本地缓存渲染）/ 关闭
  document.getElementById('btn-mini-map')?.addEventListener('click', () => {
    const mask = document.getElementById('mini-mask');
    const panel = document.getElementById('mini-panel');
    if (!mask || !panel) return;
    mask.removeAttribute('hidden');
    panel.removeAttribute('hidden');
    const body = document.getElementById('mini-map-body');
    if (body) body.innerHTML = mapCache ? renderMiniMap(mapCache) : '<div class="ov-muted">地图还没铺开，稍后再看。</div>';
  });
  document.getElementById('mini-close')?.addEventListener('click', () => {
    document.getElementById('mini-mask')?.setAttribute('hidden', '');
    document.getElementById('mini-panel')?.setAttribute('hidden', '');
  });

  // 地图元素过滤条：勾选/输入即重绘局部地图
  const filterBar = document.querySelector('.body-head-filter');
  if (filterBar) {
    filterBar.addEventListener('change', ev => {
      const box = (ev.target as HTMLElement).closest('input[type="checkbox"]') as HTMLInputElement | null;
      if (!box) return;
      if (box.value === '资源') filterState.resource = box.checked;
      if (box.value === '怪物') filterState.monster = box.checked;
      if (box.value === 'NPC') filterState.npc = box.checked;
      redrawLocalMap();
    });
    filterBar.addEventListener('input', ev => {
      const box = (ev.target as HTMLElement).closest('input[type="text"]') as HTMLInputElement | null;
      if (!box) return;
      filterState.text = box.value.trim();
      redrawLocalMap();
    });
  }
}

/** 标记在当前过滤下是否显示。 */
function markVisible(mark: string): boolean {
  const group = FILTER_GROUP[mark];
  if (!group) return true;   // 建/门/家不分组，常显
  if (group === '资源') return filterState.resource;
  if (group === '怪物') return filterState.monster;
  return filterState.npc;
}

/** 格子文字是否匹配筛选输入（空 = 全匹配）。 */
function textMatch(text: string): boolean {
  const q = filterState.text;
  if (!q) return true;
  return q.split(/[，,]/).some(k => k && text.includes(k));
}

/** 用缓存数据重绘局部地图（过滤条变化时）。 */
function redrawLocalMap(): void {
  if (mapCache) renderLocalMap(mapCache);
}

/** 局部地图：以玩家为中心渲染 9×9 格子，四正相邻格可点击移动（按过滤条显示）。 */
function renderLocalMap(d: Record<string, unknown>): void {
  const grid = document.getElementById('local-map');
  if (!grid) return;
  const cells = (d['cells'] as string[] | undefined) ?? [];
  const px = Number(d['px'] ?? -1), py = Number(d['py'] ?? -1);
  const markMap = new Map<string, string>();
  for (const m of (d['marks'] as { x: number; y: number; type: string }[] | undefined) ?? []) {
    markMap.set(`${m.x},${m.y}`, m.type);
  }
  let html = '';
  for (let dy = -VIEW_R; dy <= VIEW_R; dy++) {
    for (let dx = -VIEW_R; dx <= VIEW_R; dx++) {
      const x = px + dx, y = py + dy;
      // 地图外 / 越界
      const row = cells[y];
      const ch = row ? row[x] : undefined;
      if (x < 0 || y < 0 || !ch) {
        html += '<span class="lm-cell unknown">·</span>';
        continue;
      }
      // 玩家自身
      if (dx === 0 && dy === 0) {
        html += '<span class="lm-cell player">你</span>';
        continue;
      }
      const mark = markMap.get(`${x},${y}`);
      let cls = 'lm-cell';
      let text: string;
      if (mark && MARK_TEXT[mark] && markVisible(mark)) {
        cls += ` explored m-${mark}`;
        text = MARK_TEXT[mark];
      } else if (ch === '?') {
        cls += ' unknown';
        text = '·';
      } else {
        cls += ' explored';
        text = BIOME_CN[ch] ?? ch;
      }
      // 筛选输入不匹配 → 淡化显示
      if (!textMatch(text)) cls += ' unknown';
      // 四正相邻格可点击移动（data-cmd 走页面级委托）
      const dir = DIR_BY_DELTA[`${dx},${dy}`];
      if (dir && ch !== '?') {
        cls += ' moveable';
        html += `<span class="${cls}" data-cmd="${dir}" title="向${dir}走">${text}</span>`;
      } else {
        cls += ' locked';
        html += `<span class="${cls}">${text}</span>`;
      }
    }
  }
  grid.innerHTML = html;
}

/** 小地图：50×50 缩略网格 + 玩家位置 + 生存信息（开拓者式）。 */
function renderMiniMap(d: Record<string, unknown>): string {
  const hud = getState().hud;
  const cells = (d['cells'] as string[] | undefined) ?? [];
  const px = Number(d['px'] ?? -1), py = Number(d['py'] ?? -1);
  const markMap = new Set<string>();
  for (const m of (d['marks'] as { x: number; y: number; type: string }[] | undefined) ?? []) {
    markMap.add(`${m.x},${m.y}`);
  }
  let html = '';
  html += '<div class="mini-info">';
  html += `<span>时间 <b>${esc(hud?.time ?? '')}</b></span>`;
  html += `<span>坐标 <b>(${px}, ${py})</b></span>`;
  html += `<span>饥饿 <b>${hud?.hunger ?? 0}</b></span>`;
  html += `<span>水分 <b>${hud?.thirst ?? 0}</b></span>`;
  html += `<span>负重 <b>${hud?.weight ?? ''}</b></span>`;
  html += '</div>';
  html += '<div class="mini-grid">';
  for (let y = 0; y < cells.length; y++) {
    for (let x = 0; x < cells[y].length; x++) {
      let cls = 'mini-cell';
      if (x === px && y === py) cls += ' player';
      else if (markMap.has(`${x},${y}`)) cls += ' mark';
      else if (cells[y][x] !== '?') cls += ' explored';
      html += `<span class="${cls}"></span>`;
    }
  }
  html += '</div>';
  html += '<div class="mini-legend">蓝底 = 你 · 浅蓝 = 有东西（怪/人/采） · 深底 = 已探索</div>';
  return html;
}

/** 拉取世界地图数据并刷新局部视野（探索面板同款接口）。 */
async function refreshLocalMap(): Promise<void> {
  try {
    const d = await api.panel(getState().sessionId, 'map');
    mapCache = d;
    renderLocalMap(d);
  } catch {
    // 地图拉不到就保持现状，不打断叙事
  }
}

/** 进入游戏页：恢复全量叙事日志 + HUD。 */
export async function enterGame(): Promise<void> {
  initGamePage();
  setPage('game');
  const narration = document.getElementById('narration');
  if (narration) narration.innerHTML = '';
  try {
    const state = await api.state(getState().sessionId);
    if (narration) appendNarration(narration, state.log);
    setState({ hud: state.hud });
    renderHud(state.hud);
  } catch (err) {
    if (narration) appendNarration(narration, [{ text: `恢复游戏状态失败：${(err as Error).message}`, kind: 'ERROR' }]);
  }
  await refreshLocalMap();
}

/** 发送一条指令 → 追加叙事 + 刷新 HUD + 刷新局部地图（面板指令也经此注入叙事流）。 */
export async function send(line: string): Promise<void> {
  const narration = document.getElementById('narration');
  try {
    const resp = await api.command(getState().sessionId, line);
    setState({ hud: resp.hud });
    if (narration) appendNarration(narration, resp.narration);
    renderHud(resp.hud);
  } catch (err) {
    if (narration) appendNarration(narration, [{ text: `指令失败：${(err as Error).message}`, kind: 'ERROR' }]);
  }
  await refreshLocalMap();
}

/** 菜单：存档到所选槽位 → 返回开始页（存档失败不阻塞）。 */
async function saveAndMenu(): Promise<void> {
  try {
    await api.save(getState().sessionId, getState().saveSlot);
  } catch {
    // 存档失败不阻塞返回菜单
  }
  setState({ sessionId: '', hud: null });
  setPage('start');
}
