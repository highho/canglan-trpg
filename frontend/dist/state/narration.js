/**
 * narration.ts — 叙事渲染 + HUD 刷新（开拓者式：html 字符串拼接整块重绘）。
 * HUD 顶栏属性：单一 attrs 网格容器，4 列排布（开拓者式）；方向键盘为静态骨架，仅刷可用态。
 */
import { esc } from './store.js';
/** 生态枚举 → 中文名。 */
export const BIOME_NAME = {
    PLAINS: '平原', FOREST: '林地', DESERT: '沙漠',
    TUNDRA: '苔原', SWAMP: '沼泽', MOUNTAIN: '山地',
};
/** 难度 → 中文（DifficultyMode 五档）。 */
export const DIFF_CN = {
    CASUAL: '休闲', NORMAL: '普通', HARD: '困难', NIGHTMARE: '梦魇', ABYSS: '深渊',
};
/** 难度描述（口语化）。 */
export const DIFF_DESC = {
    CASUAL: '粮草充足，躺着也能玩。',
    NORMAL: '标准的 TRPG 挑战，不欺负人。',
    HARD: '饿得快、敌人狠，日子不好过。',
    NIGHTMARE: '资源抠搜，敌人凶悍，一步一个坎。',
    ABYSS: '纯硬核求生：被碰一下就残，别犯错。',
};
/** 叙事行追加（html 拼接 + 转义，滚动到底）。 */
export function appendNarration(container, lines) {
    let html = '';
    for (const line of lines) {
        html += `<div class="line k-${esc(line.kind)}">${esc(line.text)}</div>`;
    }
    container.insertAdjacentHTML('beforeend', html);
    container.scrollTop = container.scrollHeight;
}
function setHtml(id, html) {
    const el = document.getElementById(id);
    if (el)
        el.innerHTML = html;
}
/** 属性小格：`生命 <b>80/100</b>`。 */
function attr(label, value) {
    return `<span class="attr">${label} <b>${esc(value)}</b></span>`;
}
/** HUD 全量刷新：顶栏属性网格 + 方向键盘 + 附近 + 物品快捷区。 */
export function renderHud(hud) {
    setHtml('hud-time', esc(hud.time ?? ''));
    setHtml('hud-coord', hud.hasPlayer
        ? `${esc(hud.name ?? '旅人')} 在 (${hud.x ?? 0}, ${hud.y ?? 0})`
        : '');
    setHtml('hud-difficulty', esc(DIFF_CN[hud.difficulty] ?? hud.difficulty ?? ''));
    const overload = document.getElementById('hud-overload');
    if (overload)
        overload.hidden = !hud.overloaded;
    setHtml('hud-vitals', attr('生命', `${hud.hp ?? 0}/${hud.maxHp ?? 0}`) +
        attr('饱食', String(hud.hunger ?? 0)) +
        attr('水分', String(hud.thirst ?? 0)) +
        attr('理智', String(hud.sanity ?? 0)) +
        attr('体温', String(hud.temperature ?? 0)) +
        attr('负重', hud.weight ?? '') +
        attr('攻击', String(hud.atk ?? '')) +
        attr('防御', String(hud.def ?? '')) +
        attr('速度', String(hud.spd ?? '')) +
        attr('经验', String(hud.exp ?? '')) +
        attr('金币', String(hud.gold ?? 0)));
    renderMovePad(hud);
    renderNearby(hud);
    renderQuickBar(hud);
}
/** 方向键盘可用态：按 hud.directions 禁用走不通的方向。 */
function renderMovePad(hud) {
    const dirs = hud.directions ?? [];
    document.querySelectorAll('.move-pad .pad-btn[data-cmd]').forEach(btn => {
        const cmd = btn.dataset.cmd ?? '';
        btn.disabled = cmd !== '探索' && !dirs.some(d => d.dir === cmd);
    });
}
/** 附近区：怪物/NPC 按钮「攻击 X / 交谈 X」。 */
function renderNearby(hud) {
    const block = document.getElementById('nearby-block');
    const cards = document.getElementById('nearby-cards');
    const dialogBlock = document.getElementById('dialog-block');
    if (!block || !cards || !dialogBlock)
        return;
    const monsters = hud.nearby?.monsters ?? [];
    const npcs = hud.nearby?.npcs ?? [];
    block.hidden = monsters.length + npcs.length === 0;
    dialogBlock.hidden = npcs.length === 0;
    let html = '';
    for (const name of monsters) {
        html += `<button data-cmd="${esc(`攻击 ${name}`)}">攻击 ${esc(name)}</button>`;
    }
    for (const name of npcs) {
        html += `<button data-cmd="${esc(`交谈 ${name}`)}">交谈 ${esc(name)}</button>`;
    }
    cards.innerHTML = html;
}
/** 物品快捷区：背包前几件「名称（数量）」，点击发「吃 名称」。 */
function renderQuickBar(hud) {
    const grid = document.getElementById('quick-slots');
    if (!grid)
        return;
    const slots = hud.quickBar ?? [];
    if (!slots.length) {
        grid.innerHTML = '<span class="ov-muted">（行囊里空得能跑马）</span>';
        return;
    }
    let html = '';
    for (const info of slots) {
        html += `<button data-cmd="${esc(`吃 ${info.label}`)}">${esc(info.label)}（${info.count}）</button>`;
    }
    grid.innerHTML = html;
}
