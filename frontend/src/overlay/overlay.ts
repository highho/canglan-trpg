/**
 * overlay.ts — 全屏弹窗面板（开拓者式：.win 结构 + renderXxx() 字符串拼接整块重绘）。
 * 面板：char/bag/skill/recipe/quest/home/codex/settings + map（探索）。
 * 面板内指令按钮统一 data-cmd，由 game.ts 的页面级委托发送（弹窗内点击先关窗）。
 */

import { api } from '../net/api.js';
import { getState, esc } from '../state/store.js';
import { BIOME_NAME, DIFF_CN } from '../state/narration.js';

const TITLES: Record<string, string> = {
  char: '角色状态', bag: '行囊', skill: '技能', recipe: '制造',
  quest: '委托', home: '家园', codex: '图鉴', settings: '系统设置', map: '世界地图',
};

/** 地图标记文字（怪/人/采/建/门/家）。 */
const MARK_TEXT: Record<string, string> = {
  MONSTER_SPAWN: '怪', NPC_SPAWN: '人', GATHER_POINT: '采',
  BUILDING: '建', DUNGEON_ENTRANCE: '门', HOME: '家',
};

const BIOME_BY_CHAR: Record<string, string> = {
  P: 'PLAINS', F: 'FOREST', D: 'DESERT', T: 'TUNDRA', S: 'SWAMP', M: 'MOUNTAIN',
};

let saveHandler: () => void = () => undefined;
let inited = false;

/* 图鉴局部状态（分类切换/关键词过滤用模块级变量，重绘走 drawCodex） */
let codexData: Record<string, unknown> | null = null;
let codexCat = 'monsters';
let codexFilter = '';

export function initOverlay(onSave: () => void): void {
  saveHandler = onSave;
  if (inited) return;
  inited = true;
  document.getElementById('overlay-close')?.addEventListener('click', closeOverlay);
  document.addEventListener('keydown', ev => {
    if (ev.key === 'Escape') closeOverlay();
  });
  // 图鉴分类切换（本地重绘，不发指令）
  document.addEventListener('click', ev => {
    const btn = (ev.target as HTMLElement).closest('[data-cx-cat]') as HTMLElement | null;
    if (btn?.dataset.cxCat) {
      codexCat = btn.dataset.cxCat;
      drawCodex();
    }
  });
}

export function closeOverlay(): void {
  document.getElementById('overlay-mask')?.setAttribute('hidden', '');
  document.getElementById('overlay-panel')?.setAttribute('hidden', '');
}

/** 打开面板：拉数据 → 按类型整块重绘。 */
export function openOverlay(name: string): void {
  const mask = document.getElementById('overlay-mask');
  const panel = document.getElementById('overlay-panel');
  const title = document.getElementById('overlay-title');
  const body = document.getElementById('overlay-body');
  if (!mask || !panel || !title || !body) return;
  title.textContent = TITLES[name] ?? name;
  body.innerHTML = '<div class="ov-muted">正在翻找……</div>';
  mask.removeAttribute('hidden');
  panel.removeAttribute('hidden');
  void (async () => {
    try {
      const data = await api.panel(getState().sessionId, name);
      body.innerHTML = renderPanel(name, data);
      // 图鉴输入框绑定（每次重绘后重建）
      const filterBox = document.getElementById('codex-filter') as HTMLInputElement | null;
      if (filterBox) {
        filterBox.value = codexFilter;
        filterBox.addEventListener('input', () => {
          codexFilter = filterBox.value.trim();
          drawCodex();
        });
      }
    } catch (err) {
      body.innerHTML = `<div class="ov-muted">面板打不开：${esc((err as Error).message)}</div>`;
    }
  })();
}

/** 指令按钮（点击后由 game.ts 委托发送；面板内先关窗）。 */
function cmdBtn(label: string, cmd: string): string {
  return `<button data-cmd="${esc(cmd)}">${esc(label)}</button>`;
}

function row(label: string, value: string): string {
  return `<div class="ov-row"><span class="ov-label">${esc(label)}</span><span class="ov-value">${esc(value)}</span></div>`;
}

function title(text: string): string {
  return `<div class="ov-title">${esc(text)}</div>`;
}

function info(text: string): string {
  return `<div class="ov-muted">${esc(text)}</div>`;
}

function renderPanel(name: string, data: Record<string, unknown>): string {
  switch (name) {
    case 'char': return renderChar(data);
    case 'bag': return renderBag(data);
    case 'skill': return renderList(data, 'unlocked', '已学技能', ['技能', '解锁技能']);
    case 'recipe': return renderRecipe(data);
    case 'quest': return renderQuest(data);
    case 'home': return renderHome(data);
    case 'codex': return renderCodex(data);
    case 'settings': return renderSettings(data);
    case 'map': return renderMap(data);
    default: return info(`没这个面板：${name}`);
  }
}

/* ═══ 角色状态 ═══ */
function renderChar(d: Record<string, unknown>): string {
  const hp = Number(d['hp'] ?? 0), maxHp = Number(d['maxHp'] ?? 1);
  let html = '';
  html += info(`${String(d['name'] ?? '旅人')}，等级 ${String(d['level'] ?? 1)}，${String(d['race'] ?? '')} / ${String(d['clazz'] ?? '')}`);
  html += title('生命');
  html += info(`${hp}/${maxHp}`);
  html += title('属性');
  html += row('攻击', String(d['atk'] ?? ''));
  html += row('防御', String(d['def'] ?? ''));
  html += row('速度', String(d['spd'] ?? ''));
  html += row('经验', String(d['exp'] ?? ''));
  html += title('生存');
  html += row('饱食', String(d['hunger'] ?? ''));
  html += row('水分', String(d['thirst'] ?? ''));
  html += row('体温', String(d['temperature'] ?? ''));
  html += title('标签');
  const tags = (d['tags'] as string[] | undefined) ?? [];
  html += info(tags.length ? tags.join('、') : '（一个标签都没有）');
  const equips = (d['equipped'] as { slot: string; name: string; durability: string }[] | undefined) ?? [];
  if (equips.length) {
    html += title('当前装备');
    for (const e of equips) html += info(`${e.slot}：${e.name}（耐久 ${e.durability}）`);
  }
  html += '<div class="btn-row">';
  html += cmdBtn('完整状态', '状态');
  html += cmdBtn('存档', '存档');
  html += cmdBtn('返回菜单', '__return_menu');
  html += '</div>';
  return html;
}

/* ═══ 行囊 ═══ */
function renderBag(d: Record<string, unknown>): string {
  let html = '';
  html += info(`金币 ${String(d['gold'] ?? 0)}`);
  html += title('物品（点击行快捷使用）');
  const items = (d['items'] as { name: string; count: number }[] | undefined) ?? [];
  if (!items.length) html += info('（兜里一个子儿都没有）');
  for (const it of items) {
    html += `<div class="item-row" data-cmd="${esc(`吃 ${it.name}`)}" title="点击发送「吃 ${esc(it.name)}」">` +
      `<b>${esc(it.name)}</b> <span class="ov-label">数量 ${it.count}</span></div>`;
  }
  return html;
}

/* ═══ 技能（通用列表） ═══ */
function renderList(d: Record<string, unknown>, key: string, head: string, cmds: string[]): string {
  let html = '';
  html += title(head);
  const names = (d[key] as string[] | undefined) ?? [];
  html += info(names.length ? names.join('、') : '（还没学到一招半式）');
  html += '<div class="btn-row">';
  for (const c of cmds) html += cmdBtn(c, c);
  html += '</div>';
  return html;
}

/* ═══ 制造 ═══ */
function renderRecipe(d: Record<string, unknown>): string {
  let html = '';
  html += title('已知配方（点击制造）');
  const recipes = (d['recipes'] as string[] | undefined) ?? [];
  if (!recipes.length) html += info('（脑子里一片空白，还不知道任何配方）');
  html += '<div class="btn-row">';
  for (const r of recipes) html += cmdBtn(r, `制造 ${r}`);
  html += '</div>';
  html += '<div class="btn-row">' + cmdBtn('配方列表', '配方') + '</div>';
  return html;
}

/* ═══ 委托 ═══ */
function renderQuest(d: Record<string, unknown>): string {
  let html = '';
  html += title('可接委托');
  const quests = (d['quests'] as { name: string; description: string; minLevel: number }[] | undefined) ?? [];
  if (!quests.length) html += info('（布告板空空如也——输入「任务」刷新）');
  html += '<div class="btn-row">';
  for (const q of quests) {
    html += `<button data-cmd="${esc(`完成 ${q.name}`)}" title="${esc(q.description)}">` +
      `${esc(q.name)}（需要等级 ${q.minLevel}）</button>`;
  }
  html += '</div>';
  html += '<div class="btn-row">' + cmdBtn('布告板', '任务') + '</div>';
  if (d['hint']) html += info(String(d['hint']));
  return html;
}

/* ═══ 家园 ═══ */
function renderHome(d: Record<string, unknown>): string {
  if (!d['hasHome']) return info('连个窝都没有，先攒点家底吧。');
  let html = '';
  html += info(`家园等级 ${String(d['level'] ?? 1)}${d['nearHome'] ? '（就在跟前）' : '（离家还远）'}`);
  html += title('建筑');
  const buildings = (d['buildings'] as string[] | undefined) ?? [];
  html += info(buildings.length ? buildings.join('、') : '（空荡荡——回村后「建造」放第一栋）');
  html += '<div class="btn-row">';
  html += cmdBtn('建造', '建造');
  html += cmdBtn('扩建家园', '家园升级');
  html += '</div>';
  return html;
}

/* ═══ 图鉴（分类 + 关键词过滤） ═══ */
function renderCodex(d: Record<string, unknown>): string {
  codexData = d;
  const cats: { key: string; label: string }[] = [
    { key: 'monsters', label: '怪物' }, { key: 'items', label: '物品' }, { key: 'recipes', label: '配方' },
  ];
  let html = '';
  html += '<div class="btn-row" id="codex-nav">';
  for (const c of cats) {
    html += `<button data-cx-cat="${c.key}" class="${c.key === codexCat ? 'selected' : ''}">${c.label}</button>`;
  }
  html += '</div>';
  html += `<input id="codex-filter" type="text" placeholder="关键词过滤…" style="width:150px;margin:6px 0" />`;
  html += '<div id="codex-list"></div>';
  drawCodex();
  return html;
}

/** 图鉴列表重绘（分类/过滤变化时调用）。 */
function drawCodex(): void {
  const list = document.getElementById('codex-list');
  const nav = document.getElementById('codex-nav');
  if (!list || !codexData) return;
  if (nav) {
    nav.innerHTML = '';
    const cats: { key: string; label: string }[] = [
      { key: 'monsters', label: '怪物' }, { key: 'items', label: '物品' }, { key: 'recipes', label: '配方' },
    ];
    for (const c of cats) {
      nav.insertAdjacentHTML('beforeend',
        `<button data-cx-cat="${c.key}" class="${c.key === codexCat ? 'selected' : ''}">${c.label}</button>`);
    }
  }
  const rows = (codexData[codexCat] as { name: string; detail: string }[] | undefined) ?? [];
  const shown = rows.filter(r => !codexFilter || r.name.includes(codexFilter) || r.detail.includes(codexFilter));
  let html = '';
  if (!shown.length) {
    html = '<div class="ov-muted">（一条对上的都没有）</div>';
  } else {
    for (const r of shown) {
      html += `<div class="codex-row"><div class="cx-name">${esc(r.name)}</div>` +
        `<div class="cx-detail">${esc(r.detail)}</div></div>`;
    }
  }
  list.innerHTML = html;
}

/* ═══ 设置 ═══ */
function renderSettings(d: Record<string, unknown>): string {
  let html = '';
  html += title('系统');
  html += row('本地 AI', d['aiAvailable'] ? '已连接' : '没连上（规则兜底）');
  html += row('难度', DIFF_CN[String(d['difficulty'] ?? '')] ?? String(d['difficulty'] ?? ''));
  html += row('阶段', String(d['stage'] ?? ''));
  html += '<div class="btn-row">';
  html += cmdBtn('AI 设置', '__ai_settings');
  html += cmdBtn('保存游戏', '存档');
  html += '</div>';
  return html;
}

/* ═══ 地图（50x50 文字字符格：地形首字/标记字/玩家「你」） ═══ */
function renderMap(d: Record<string, unknown>): string {
  let html = '';
  html += `<div class="map-header">${esc(String(d['header'] ?? ''))}</div>`;
  html += '<div class="map-scroll"><div class="map-grid">';
  const cells = (d['cells'] as string[] | undefined) ?? [];
  const px = Number(d['px'] ?? -1), py = Number(d['py'] ?? -1);
  const markMap = new Map<string, string>();
  for (const m of (d['marks'] as { x: number; y: number; type: string }[] | undefined) ?? []) {
    markMap.set(`${m.x},${m.y}`, m.type);
  }
  for (let y = 0; y < cells.length; y++) {
    for (let x = 0; x < cells[y].length; x++) {
      const ch = cells[y][x];
      let cls = 'map-cell', text = '';
      if (x === px && y === py) {
        cls += ' player';
        text = '你';
      } else if (ch === '?') {
        // 未探索迷雾：留白
        cls += ' unexplored';
      } else {
        cls += ' explored';
        const mark = markMap.get(`${x},${y}`);
        if (mark && MARK_TEXT[mark]) {
          text = MARK_TEXT[mark];
        } else {
          const biome = BIOME_BY_CHAR[ch];
          const name = biome ? BIOME_NAME[biome] : '';
          text = name ? name[0] : ch;
        }
      }
      html += `<span class="${cls}">${esc(text)}</span>`;
    }
  }
  html += '</div></div>';
  html += `<div class="map-legend">${esc(String(d['legend'] ?? ''))}</div>`;
  return html;
}

/** 导出给 game.ts 特殊指令用（返回菜单前保存）。 */
export function getSaveHandler(): () => void {
  return saveHandler;
}
