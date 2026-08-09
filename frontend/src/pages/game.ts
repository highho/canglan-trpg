/**
 * game.ts — 页面 4：游戏主界面（纯文字纵向流：
 * HUD 文字行 + 叙事区 + 移动/行动/附近/物品 + 八 Tab 功能栏）。
 */

import { api } from '../net/api.js';
import { getState, setPage, setState } from '../state/store.js';
import { appendNarration, renderHud } from '../state/narration.js';
import { initOverlay, openOverlay } from '../overlay/overlay.js';

/** 行动区固定按钮（移动四向由 HUD 方向按钮承担，此处只留环境行动）。 */
const ACTIONS: { label: string; cmd: string }[] = [
  { label: '环顾四周', cmd: '查看' },
  { label: '探索此处', cmd: '探索' },
  { label: '等到天亮', cmd: '等待' },
  { label: '听听传闻', cmd: '传闻' },
  { label: '喝口水', cmd: '喝水' },
];

/** 快捷话题（参数为自由语句，走 AI 自由对话/规则兜底）。 */
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

  // 行动固定按钮
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

  // 事件委托：所有带 data-cmd 的动态按钮（移动方向/附近怪物与NPC/物品快捷区）
  document.getElementById('page-game')?.addEventListener('click', ev => {
    const target = (ev.target as HTMLElement).closest('[data-cmd]') as HTMLElement | null;
    if (target?.dataset.cmd) void send(target.dataset.cmd);
  });

  // 顶栏：菜单（存档并返回菜单）/ 状态（打开角色面板）
  document.getElementById('btn-menu')?.addEventListener('click', () => void saveAndMenu());
  document.getElementById('btn-status')?.addEventListener('click', () => openOverlay('char'));

  // 底部八 Tab
  document.querySelectorAll<HTMLButtonElement>('.tab-bar .tab-btn').forEach(btn => {
    btn.addEventListener('click', () => openOverlay(btn.dataset.panel ?? ''));
  });

  initOverlay(() => void send('存档'));

  // 覆盖层面板指令回注（overlay.ts 通过 CustomEvent 避免循环依赖）
  window.addEventListener('canglan-cmd', ev => {
    const cmd = (ev as CustomEvent<string>).detail;
    if (cmd) void send(cmd);
  });
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
}

/** 发送一条指令 → 追加叙事 + 刷新 HUD（覆盖层面板指令经此注入叙事流）。 */
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
}

/** 菜单：存档到所选槽位 → 返回开始页（对应 SaveAndMenu）。 */
async function saveAndMenu(): Promise<void> {
  try {
    await api.save(getState().sessionId, getState().saveSlot);
  } catch {
    // 存档失败不阻塞返回菜单
  }
  setState({ sessionId: '', hud: null });
  setPage('start');
}
