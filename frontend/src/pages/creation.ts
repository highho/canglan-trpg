/**
 * creation.ts — 页面 3：创建角色（对齐 MainView.axaml 创建页：
 * 角色名 + 血脉/道路/特质/难度 四组同屏选卡 + 摘要 + 「开始冒险」一步建档）。
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
}

/** 进入创建页：拉选项（特质随血脉+道路过滤）→ 渲染四组卡片。 */
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

/** 渲染一组选卡（选中态深底白字；descs 提供时写入 title 提示）。 */
function renderGroup(containerId: string, names: string[], selected: string,
                     onPick: (name: string) => void, descs?: string[]): void {
  const wrap = document.getElementById(containerId);
  if (!wrap) return;
  wrap.innerHTML = '';
  names.forEach((label, i) => {
    const btn = document.createElement('button');
    btn.className = 'choice' + (label === selected ? ' selected' : '');
    btn.textContent = label;
    if (descs && descs[i]) btn.title = descs[i];
    btn.addEventListener('click', () => onPick(label));
    wrap.appendChild(btn);
  });
  if (names.length === 0) {
    const hint = document.createElement('span');
    hint.className = 'ov-muted';
    hint.textContent = containerId === 'trait-choices' ? '（先选择血脉与道路）' : '（无可用选项）';
    wrap.appendChild(hint);
  }
}

/** 难度组：普通文字按钮，选中态加粗下划线（.selected）。 */
function renderDifficulty(difficulties: string[]): void {
  const wrap = document.getElementById('difficulty-choices');
  const text = document.getElementById('difficulty-text');
  if (!wrap) return;
  wrap.innerHTML = '';
  for (const mode of difficulties) {
    const btn = document.createElement('button');
    btn.className = mode === sel.difficulty ? 'selected' : '';
    btn.textContent = DIFF_CN[mode] ?? mode;
    btn.addEventListener('click', () => { sel.difficulty = mode; void refresh(); });
    wrap.appendChild(btn);
  }
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
    if (summary) summary.textContent = '请先选齐血脉、道路与出身特质';
    return;
  }
  try {
    const resp = await api.startGame({
      name, race: sel.race, clazz: sel.clazz, trait: sel.trait, difficulty: sel.difficulty,
    });
    if (!resp.sessionId) throw new Error('服务器未返回会话');
    setState({ sessionId: resp.sessionId, hud: resp.hud });
    // 建档自动存档落在槽位 1；选了其他槽位则补一次显式存档
    const slot = getState().saveSlot;
    if (slot !== 1) await api.save(resp.sessionId, slot).catch(() => undefined);
    await enterGame();
  } catch (err) {
    if (summary) summary.textContent = `建档失败：${(err as Error).message}`;
  }
}
