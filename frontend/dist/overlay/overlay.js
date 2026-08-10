/**
 * overlay.ts — 覆盖层面板（对齐 MainView.axaml：遮罩 + 居中弹窗）。
 * 面板：char/bag/skill/recipe/quest/home/codex/settings + map（探索）。
 */
import { api } from '../net/api.js';
import { getState, esc, setPage, setState } from '../state/store.js';
import { BIOME_NAME, DIFF_CN } from '../state/narration.js';
import { openAiSettings } from '../pages/ai-settings.js';
const TITLES = {
    char: '角色状态', bag: '行囊', skill: '技能', recipe: '制造',
    quest: '委托', home: '家园', codex: '图鉴', settings: '系统设置', map: '世界地图',
};
/** 地图标记文字（对应原图例：怪/人/采/建/门/家）。 */
const MARK_TEXT = {
    MONSTER_SPAWN: '怪', NPC_SPAWN: '人', GATHER_POINT: '采',
    BUILDING: '建', DUNGEON_ENTRANCE: '门', HOME: '家',
};
const BIOME_BY_CHAR = {
    P: 'PLAINS', F: 'FOREST', D: 'DESERT', T: 'TUNDRA', S: 'SWAMP', M: 'MOUNTAIN',
};
let saveHandler = () => undefined;
let inited = false;
export function initOverlay(onSave) {
    saveHandler = onSave;
    if (inited)
        return;
    inited = true;
    document.getElementById('overlay-close')?.addEventListener('click', closeOverlay);
    // 遮罩仅作视觉暗层（pointer-events:none），不拦截点击——对齐原 Avalonia：
    // 覆盖层打开时页面其余区域（含底部 Tab）仍可操作，点其他 Tab 直接切换面板。
    document.addEventListener('keydown', ev => {
        if (ev.key === 'Escape')
            closeOverlay();
    });
}
export function closeOverlay() {
    document.getElementById('overlay-mask')?.setAttribute('hidden', '');
    document.getElementById('overlay-panel')?.setAttribute('hidden', '');
}
/** 打开面板：拉数据 → 按类型渲染。 */
export function openOverlay(name) {
    const mask = document.getElementById('overlay-mask');
    const panel = document.getElementById('overlay-panel');
    const title = document.getElementById('overlay-title');
    const body = document.getElementById('overlay-body');
    if (!mask || !panel || !title || !body)
        return;
    title.textContent = TITLES[name] ?? name;
    body.innerHTML = '<div class="ov-muted">加载中…</div>';
    mask.removeAttribute('hidden');
    panel.removeAttribute('hidden');
    void (async () => {
        try {
            const data = await api.panel(getState().sessionId, name);
            body.innerHTML = '';
            renderPanel(name, data, body);
        }
        catch (err) {
            body.innerHTML = `<div class="ov-muted">面板加载失败：${esc(err.message)}</div>`;
        }
    })();
}
function cmdBtn(label, cmd) {
    const btn = document.createElement('button');
    btn.textContent = label;
    btn.dataset.cmd = cmd;
    btn.addEventListener('click', () => { closeOverlay(); void sendAndRefresh(cmd); });
    return btn;
}
/** 面板指令：经游戏页 send 注入叙事流（通过全局事件避免循环依赖）。 */
async function sendAndRefresh(cmd) {
    window.dispatchEvent(new CustomEvent('canglan-cmd', { detail: cmd }));
}
function row(label, value) {
    const div = document.createElement('div');
    div.className = 'ov-row';
    div.innerHTML = `<span class="ov-label">${esc(label)}</span><span class="ov-value">${esc(value)}</span>`;
    return div;
}
function title(text) {
    const div = document.createElement('div');
    div.className = 'ov-title';
    div.textContent = text;
    return div;
}
function renderPanel(name, data, body) {
    switch (name) {
        case 'char':
            renderChar(data, body);
            break;
        case 'bag':
            renderBag(data, body);
            break;
        case 'skill':
            renderList(data, body, 'unlocked', '已学技能', ['技能', '解锁技能']);
            break;
        case 'recipe':
            renderRecipe(data, body);
            break;
        case 'quest':
            renderQuest(data, body);
            break;
        case 'home':
            renderHome(data, body);
            break;
        case 'codex':
            renderCodex(data, body);
            break;
        case 'settings':
            renderSettings(data, body);
            break;
        case 'map':
            renderMap(data, body);
            break;
        default: body.innerHTML = `<div class="ov-muted">未知面板：${esc(name)}</div>`;
    }
}
/* ═══ 角色状态 ═══ */
function renderChar(d, body) {
    const hp = Number(d['hp'] ?? 0), maxHp = Number(d['maxHp'] ?? 1);
    body.appendChild(info(`${String(d['name'] ?? '旅人')}，等级 ${String(d['level'] ?? 1)}，${String(d['race'] ?? '')} / ${String(d['clazz'] ?? '')}`));
    body.appendChild(title('生命'));
    body.appendChild(info(`${hp}/${maxHp}`));
    body.appendChild(title('属性'));
    body.appendChild(row('攻击', String(d['atk'] ?? '')));
    body.appendChild(row('防御', String(d['def'] ?? '')));
    body.appendChild(row('速度', String(d['spd'] ?? '')));
    body.appendChild(row('经验', String(d['exp'] ?? '')));
    body.appendChild(title('生存'));
    body.appendChild(row('饱食', String(d['hunger'] ?? '')));
    body.appendChild(row('水分', String(d['thirst'] ?? '')));
    body.appendChild(row('体温', String(d['temperature'] ?? '')));
    body.appendChild(title('标签'));
    const tags = d['tags'] ?? [];
    body.appendChild(info(tags.length ? tags.join('、') : '（无）'));
    const equips = d['equipped'] ?? [];
    if (equips.length) {
        body.appendChild(title('当前装备'));
        for (const e of equips)
            body.appendChild(info(`${e.slot}：${e.name}（耐久 ${e.durability}）`));
    }
    const ops = document.createElement('div');
    ops.className = 'btn-row';
    ops.appendChild(cmdBtn('完整状态', '状态'));
    const saveBtn = document.createElement('button');
    saveBtn.textContent = '存档';
    saveBtn.addEventListener('click', () => { closeOverlay(); saveHandler(); });
    ops.appendChild(saveBtn);
    const menuBtn = document.createElement('button');
    menuBtn.textContent = '返回菜单';
    menuBtn.addEventListener('click', () => {
        closeOverlay();
        saveHandler();
        setState({ sessionId: '', hud: null });
        setPage('start');
    });
    ops.appendChild(menuBtn);
    body.appendChild(ops);
}
/* ═══ 行囊 ═══ */
function renderBag(d, body) {
    body.appendChild(info(`金币 ${String(d['gold'] ?? 0)}`));
    body.appendChild(title('物品（点击行快捷使用）'));
    const items = d['items'] ?? [];
    if (!items.length)
        body.appendChild(info('（背包空空如也）'));
    for (const it of items) {
        const div = document.createElement('div');
        div.className = 'item-row';
        div.innerHTML = `<b>${esc(it.name)}</b> <span class="ov-label">数量 ${it.count}</span>`;
        div.title = '点击发送「吃 ' + it.name + '」';
        div.addEventListener('click', () => { closeOverlay(); void sendAndRefresh(`吃 ${it.name}`); });
        body.appendChild(div);
    }
}
/* ═══ 技能（通用列表） ═══ */
function renderList(d, body, key, head, cmds) {
    body.appendChild(title(head));
    const names = d[key] ?? [];
    body.appendChild(info(names.length ? names.join('、') : '（暂无）'));
    const ops = document.createElement('div');
    ops.className = 'btn-row';
    for (const c of cmds)
        ops.appendChild(cmdBtn(c, c));
    body.appendChild(ops);
}
/* ═══ 制造 ═══ */
function renderRecipe(d, body) {
    body.appendChild(title('已知配方（点击制造）'));
    const recipes = d['recipes'] ?? [];
    if (!recipes.length)
        body.appendChild(info('（还不知道任何配方）'));
    const ops = document.createElement('div');
    ops.className = 'btn-row';
    for (const r of recipes)
        ops.appendChild(cmdBtn(r, `制造 ${r}`));
    body.appendChild(ops);
    body.appendChild(cmdBtn('配方列表', '配方'));
}
/* ═══ 委托 ═══ */
function renderQuest(d, body) {
    body.appendChild(title('可接委托'));
    const quests = d['quests'] ?? [];
    if (!quests.length)
        body.appendChild(info('（布告板空空如也——输入「任务」刷新）'));
    const ops = document.createElement('div');
    ops.className = 'btn-row';
    for (const q of quests) {
        const btn = cmdBtn(`${q.name}（需要等级 ${q.minLevel}）`, `完成 ${q.name}`);
        btn.title = q.description;
        ops.appendChild(btn);
    }
    body.appendChild(ops);
    body.appendChild(cmdBtn('布告板', '任务'));
    if (d['hint'])
        body.appendChild(info(String(d['hint'])));
}
/* ═══ 家园 ═══ */
function renderHome(d, body) {
    if (!d['hasHome']) {
        body.appendChild(info('还没有家园。'));
        return;
    }
    body.appendChild(info(`家园等级 ${String(d['level'] ?? 1)}${d['nearHome'] ? '（就在附近）' : '（离家较远）'}`));
    body.appendChild(title('建筑'));
    const buildings = d['buildings'] ?? [];
    body.appendChild(info(buildings.length ? buildings.join('、') : '（空置——回村后「建造」放置第一栋）'));
    const ops = document.createElement('div');
    ops.className = 'btn-row';
    ops.appendChild(cmdBtn('建造', '建造'));
    ops.appendChild(cmdBtn('扩建家园', '家园升级'));
    body.appendChild(ops);
}
/* ═══ 图鉴（分类 + 关键词过滤） ═══ */
function renderCodex(d, body) {
    const cats = [
        { key: 'monsters', label: '怪物' }, { key: 'items', label: '物品' }, { key: 'recipes', label: '配方' },
    ];
    let current = 'monsters';
    let filter = '';
    const nav = document.createElement('div');
    nav.className = 'btn-row';
    const filterBox = document.createElement('input');
    filterBox.type = 'text';
    filterBox.placeholder = '关键词过滤…';
    filterBox.style.width = '150px';
    filterBox.addEventListener('input', () => { filter = filterBox.value.trim(); draw(); });
    const list = document.createElement('div');
    body.appendChild(nav);
    body.appendChild(filterBox);
    body.appendChild(list);
    function draw() {
        nav.innerHTML = '';
        for (const c of cats) {
            const btn = document.createElement('button');
            btn.className = c.key === current ? 'selected' : '';
            btn.textContent = c.label;
            btn.addEventListener('click', () => { current = c.key; draw(); });
            nav.appendChild(btn);
        }
        list.innerHTML = '';
        const rows = d[current] ?? [];
        const shown = rows.filter(r => !filter || r.name.includes(filter) || r.detail.includes(filter));
        if (!shown.length)
            list.innerHTML = '<div class="ov-muted">（无匹配条目）</div>';
        for (const r of shown) {
            const div = document.createElement('div');
            div.className = 'codex-row';
            div.innerHTML = `<div class="cx-name">${esc(r.name)}</div><div class="cx-detail">${esc(r.detail)}</div>`;
            list.appendChild(div);
        }
    }
    draw();
}
/* ═══ 设置 ═══ */
function renderSettings(d, body) {
    body.appendChild(title('系统'));
    body.appendChild(row('本地 AI', d['aiAvailable'] ? '已连接' : '未连接（规则兜底）'));
    body.appendChild(row('难度', DIFF_CN[String(d['difficulty'] ?? '')] ?? String(d['difficulty'] ?? '')));
    body.appendChild(row('阶段', String(d['stage'] ?? '')));
    const ops = document.createElement('div');
    ops.className = 'btn-row';
    const aiBtn = document.createElement('button');
    aiBtn.textContent = 'AI 设置';
    aiBtn.addEventListener('click', () => { closeOverlay(); void openAiSettings(); });
    ops.appendChild(aiBtn);
    const saveBtn = document.createElement('button');
    saveBtn.textContent = '保存游戏';
    saveBtn.addEventListener('click', () => { closeOverlay(); saveHandler(); });
    ops.appendChild(saveBtn);
    body.appendChild(ops);
}
/* ═══ 地图（50x50 文字字符格，无底色：地形首字/标记字/玩家「你」） ═══ */
function renderMap(d, body) {
    const head = document.createElement('div');
    head.className = 'map-header';
    head.textContent = String(d['header'] ?? '');
    body.appendChild(head);
    const scroll = document.createElement('div');
    scroll.className = 'map-scroll';
    const grid = document.createElement('div');
    grid.className = 'map-grid';
    const cells = d['cells'] ?? [];
    const px = Number(d['px'] ?? -1), py = Number(d['py'] ?? -1);
    const markMap = new Map();
    for (const m of d['marks'] ?? []) {
        markMap.set(`${m.x},${m.y}`, m.type);
    }
    for (let y = 0; y < cells.length; y++) {
        for (let x = 0; x < cells[y].length; x++) {
            const ch = cells[y][x];
            const cell = document.createElement('span');
            cell.className = 'map-cell';
            if (x === px && y === py) {
                cell.classList.add('player');
                cell.textContent = '你';
            }
            else if (ch === '?') {
                // 未探索迷雾：留白
            }
            else {
                cell.classList.add('explored');
                const mark = markMap.get(`${x},${y}`);
                if (mark && MARK_TEXT[mark]) {
                    cell.textContent = MARK_TEXT[mark];
                }
                else {
                    const biome = BIOME_BY_CHAR[ch];
                    const name = biome ? BIOME_NAME[biome] : '';
                    cell.textContent = name ? name[0] : ch;
                }
            }
            grid.appendChild(cell);
        }
    }
    scroll.appendChild(grid);
    body.appendChild(scroll);
    const legend = document.createElement('div');
    legend.className = 'map-legend';
    legend.textContent = String(d['legend'] ?? '');
    body.appendChild(legend);
}
function info(text) {
    const div = document.createElement('div');
    div.className = 'ov-muted';
    div.textContent = text;
    return div;
}
