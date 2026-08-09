/**
 * narration.ts — 叙事区渲染 + HUD 全量刷新（纯文字版：无符号、无色条，全部文字表达）。
 */
/** 生态枚举 → 中文名。 */
export const BIOME_NAME = {
    PLAINS: '平原', FOREST: '林地', DESERT: '沙漠',
    TUNDRA: '苔原', SWAMP: '沼泽', MOUNTAIN: '山地',
};
/** 难度 → 中文（DifficultyMode 五档）。 */
export const DIFF_CN = {
    CASUAL: '休闲', NORMAL: '普通', HARD: '困难', NIGHTMARE: '梦魇', ABYSS: '深渊',
};
/** 难度描述（对齐 Difficulty.cs 官方文案）。 */
export const DIFF_DESC = {
    CASUAL: '资源充裕，适合体验剧情。',
    NORMAL: '标准 TRPG 挑战。',
    HARD: '生存消耗加剧，敌人更强。',
    NIGHTMARE: '敌人凶悍，资源极缺，步步惊心。',
    ABYSS: '硬核求生：敌人碾压，容错为零。',
};
/** 叙事行增量渲染（行按 NarrationKind 着色，滚动到底）。 */
export function appendNarration(container, lines) {
    for (const line of lines) {
        const div = document.createElement('div');
        div.className = `line k-${line.kind}`;
        div.textContent = line.text;
        container.appendChild(div);
    }
    container.scrollTop = container.scrollHeight;
}
function setText(id, text) {
    const el = document.getElementById(id);
    if (el)
        el.textContent = text;
}
/** HUD 全量刷新：三行状态文字 + 移动方向 + 附近 + 物品快捷区。 */
export function renderHud(hud) {
    setText('hud-time', hud.time ?? '');
    setText('hud-coord', hud.hasPlayer ? `坐标 (${hud.x ?? 0}, ${hud.y ?? 0})` : '');
    setText('hud-difficulty', DIFF_CN[hud.difficulty] ?? hud.difficulty ?? '');
    const overload = document.getElementById('hud-overload');
    if (overload)
        overload.hidden = !hud.overloaded;
    setText('hud-vitals', `生命 ${hud.hp ?? 0}/${hud.maxHp ?? 0}　饱食 ${hud.hunger ?? 0}` +
        `　水分 ${hud.thirst ?? 0}　理智 ${hud.sanity ?? 0}　体温 ${hud.temperature ?? 0}`);
    setText('hud-combat', `负重 ${hud.weight ?? ''}　攻击 ${hud.atk ?? ''}　防御 ${hud.def ?? ''}` +
        `　速度 ${hud.spd ?? ''}　经验 ${hud.exp ?? ''}　金币 ${hud.gold ?? 0}`);
    renderDirections(hud);
    renderNearby(hud);
    renderQuickBar(hud);
}
/** 移动方向：四向文字按钮「北（平原）」，点击经 data-cmd 委托发送。 */
function renderDirections(hud) {
    const wrap = document.getElementById('move-buttons');
    if (!wrap)
        return;
    wrap.innerHTML = '';
    for (const d of hud.directions ?? []) {
        const btn = document.createElement('button');
        const terrain = BIOME_NAME[d.terrain] ?? d.terrain;
        btn.textContent = terrain ? `${d.dir}（${terrain}）` : d.dir;
        btn.dataset.cmd = d.dir;
        wrap.appendChild(btn);
    }
}
/** 附近区：怪物/NPC 文字按钮，点击发送「攻击/交谈 名称」。 */
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
    cards.innerHTML = '';
    for (const name of monsters) {
        const btn = document.createElement('button');
        btn.textContent = `攻击 ${name}`;
        btn.dataset.cmd = `攻击 ${name}`;
        cards.appendChild(btn);
    }
    for (const name of npcs) {
        const btn = document.createElement('button');
        btn.textContent = `交谈 ${name}`;
        btn.dataset.cmd = `交谈 ${name}`;
        cards.appendChild(btn);
    }
}
/** 物品快捷区：背包前几件文字按钮「名称（数量）」，点击发「吃 名称」。 */
function renderQuickBar(hud) {
    const grid = document.getElementById('quick-slots');
    if (!grid)
        return;
    grid.innerHTML = '';
    const slots = hud.quickBar ?? [];
    if (!slots.length) {
        const hint = document.createElement('span');
        hint.className = 'ov-muted';
        hint.textContent = '（行囊中没有物品）';
        grid.appendChild(hint);
        return;
    }
    for (const info of slots) {
        const btn = document.createElement('button');
        btn.textContent = `${info.label}（${info.count}）`;
        btn.dataset.cmd = `吃 ${info.label}`;
        grid.appendChild(btn);
    }
}
