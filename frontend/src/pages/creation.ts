/**
 * creation.ts — 页面 3：创建角色（角色名 + 血脉/道路/特质/难度 四组选卡 + 摘要 + 一步建档）。
 * 选卡整块重绘：html 字符串拼接 + innerHTML，选中态 .selected（蓝底白字）。
 */

import { api } from '../net/api.js';
import { getState, setPage, setState } from '../state/store.js';
import { DIFF_CN, DIFF_DESC } from '../state/narration.js';
import { enterGame } from './game.js';

interface Selection {
  race: string;       // 选中种族名（空=未选）
  clazz: string;
  trait: string;
  difficulty: string; // DifficultyMode 枚举名
}

let sel: Selection = { race: '', clazz: '', trait: '', difficulty: 'NORMAL' };

export function initCreationPage(): void {
  document.getElementById('btn-creation-back')?.addEventListener('click', () => setPage('start'));
  document.getElementById('btn-begin')?.addEventListener('click', () => void beginAdventure());
  document.getElementById('creation-name')?.addEventListener('input', renderSummary);
}

/** 进入创建页：拉选项（特质随血脉+道路过滤）→ 渲染四组选卡。 */
export function enterCreation(): void {
  sel = { race: '', clazz: '', trait: '', difficulty: 'NORMAL' };
  const name = document.getElementById('creation-name') as HTMLInputElement | null;
  if (name) name.value = '';
  setPage('creation');
  void refresh();
}

async function refresh(): Promise<void> {
  try {
    const opts = await api.creationOptions(sel.race || undefined, sel.clazz || undefined);
    renderGroup('race-choices', opts.races.map(o => o.name), sel.race, pickRace);
    renderGroup('class-choices', opts.classes.map(o => o.name), sel.clazz, pickClass);
    renderGroup('trait-choices',
      opts.traits.map(o => o.name), sel.trait, pickTrait,
      opts.traits.map(o => o.description ?? ''));
    renderDifficulty(opts.difficulties);
    renderSummary();
  } catch (err) {
    const summary = document.getElementById('creation-summary');
    if (summary) summary.textContent = `选项加载失败：${(err as Error).message}`;
  }
}

function pickRace(name: string): void { sel.race = name; sel.trait = ''; void refresh(); }
function pickClass(name: string): void { sel.clazz = name; sel.trait = ''; void refresh(); }
function pickTrait(name: string): void { sel.trait = name; void refresh(); }

/**
 * 渲染一组选卡（html 拼接整块重绘；descs 提供时写入 title 提示）。
 * 选卡点击：数据挂 data-pick 走容器委托（页面级，见下方 initCreationPage 注册）。
 */
function renderGroup(containerId: string, names: string[], selected: string,
                     onPick: (name: string) => void, descs?: string[]): void {
  const wrap = document.getElementById(containerId);
  if (!wrap) return;
  if (names.length === 0) {
    wrap.innerHTML = `<span class="ov-muted">${containerId === 'trait-choices' ? '（先选血脉与道路）' : '（没得选）'}</span>`;
    return;
  }
  let html = '';
  names.forEach((label, i) => {
    const cls = label === selected ? ' selected' : '';
    const tip = descs && descs[i] ? ` title="${escAttr(descs[i])}"` : '';
    html += `<button class="choice${cls}" data-pick="${escAttr(label)}"${tip}>${esc(label)}</button>`;
  });
  wrap.innerHTML = html;
  // 绑定本轮选卡点击（每轮重绘后重建）
  wrap.querySelectorAll<HTMLButtonElement>('[data-pick]').forEach(btn => {
    btn.addEventListener('click', () => onPick(btn.dataset.pick ?? ''));
  });
}

/** 难度组：普通按钮，选中态蓝底白字（.selected）。 */
function renderDifficulty(difficulties: string[]): void {
  const wrap = document.getElementById('difficulty-choices');
  const text = document.getElementById('difficulty-text');
  if (!wrap) return;
  let html = '';
  for (const mode of difficulties) {
    const cls = mode === sel.difficulty ? ' selected' : '';
    html += `<button class="choice${cls}" data-pick="${mode}">${esc(DIFF_CN[mode] ?? mode)}</button>`;
  }
  wrap.innerHTML = html;
  wrap.querySelectorAll<HTMLButtonElement>('[data-pick]').forEach(btn => {
    btn.addEventListener('click', () => { sel.difficulty = btn.dataset.pick ?? 'NORMAL'; void refresh(); });
  });
  if (text) text.textContent = `难度：${DIFF_CN[sel.difficulty] ?? sel.difficulty}。${DIFF_DESC[sel.difficulty] ?? ''}`;
}

/** 摘要行（全文字描述）。 */
function renderSummary(): void {
  const summary = document.getElementById('creation-summary');
  if (!summary) return;
  const name = (document.getElementById('creation-name') as HTMLInputElement | null)?.value.trim() || '旅人';
  const parts = [`名字：${name}`];
  if (sel.race) parts.push(`血脉：${sel.race}`);
  if (sel.clazz) parts.push(`道路：${sel.clazz}`);
  if (sel.trait) parts.push(`特质：${sel.trait}`);
  parts.push(`难度：${DIFF_CN[sel.difficulty] ?? sel.difficulty}`);
  summary.textContent = parts.join('，');
}

/** 「开始冒险」：一步建档 → 进游戏（非默认存档位时补一次显式存档）。 */
async function beginAdventure(): Promise<void> {
  const name = (document.getElementById('creation-name') as HTMLInputElement | null)?.value.trim() || '旅人';
  const summary = document.getElementById('creation-summary');
  if (!sel.race || !sel.clazz || !sel.trait) {
    if (summary) summary.textContent = '血脉、道路和出身特质得选齐了才能上路';
    return;
  }
  try {
    const resp = await api.startGame({
      name, race: sel.race, clazz: sel.clazz, trait: sel.trait, difficulty: sel.difficulty,
    });
    if (!resp.sessionId) throw new Error('服务器没给会话');
    setState({ sessionId: resp.sessionId, hud: resp.hud });
    // 建档自动存档落在槽位 1；选了其他槽位则补一次显式存档
    const slot = getState().saveSlot;
    if (slot !== 1) await api.save(resp.sessionId, slot).catch(() => undefined);
    await enterGame();
  } catch (err) {
    if (summary) summary.textContent = `建档失败：${(err as Error).message}`;
  }
}

/** 属性值转义（title/按钮内容用）。 */
function escAttr(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

/** 通用转义（文案进 HTML）。 */
function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
