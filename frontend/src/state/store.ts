/**
 * store.ts — 极简状态（页面/会话/HUD），无框架。
 * 对齐开拓者式全局状态思路：一个地方记状态，页面切换只翻显隐。
 */

import type { Hud } from '../net/api.js';

export type Page = 'start' | 'saveSelect' | 'creation' | 'game';

/** 存档页进入模式：new=新游戏（选位后去创建页）；load=读档（选位后直接读）。 */
export type SaveMode = 'new' | 'load';

interface State {
  page: Page;
  sessionId: string;
  hud: Hud | null;
  saveMode: SaveMode;
  /** 新游戏选中的存档位（之后「存档」沿用） */
  saveSlot: number;
}

const state: State = {
  page: 'start',
  sessionId: '',
  hud: null,
  saveMode: 'new',
  saveSlot: 1,
};

const listeners = new Set<() => void>();

export function getState(): Readonly<State> {
  return state;
}

export function setState(patch: Partial<State>): void {
  Object.assign(state, patch);
  for (const fn of listeners) fn();
}

export function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

const PAGE_ID: Record<Page, string> = {
  start: 'page-start',
  saveSelect: 'page-save-select',
  creation: 'page-creation',
  game: 'page-game',
};

/** 四页面互斥显隐。 */
export function setPage(page: Page): void {
  state.page = page;
  for (const [key, id] of Object.entries(PAGE_ID)) {
    const el = document.getElementById(id);
    if (el) el.classList.toggle('active', key === page);
  }
  for (const fn of listeners) fn();
}

/** HTML 转义：所有用户/服务端文本进 innerHTML 前必过。 */
export function esc(s: string): string {
  return s.replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c] as string));
}
