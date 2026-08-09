/**
 * save-select.ts — 页面 2：存档位选择（对齐 MainView.axaml：3 个槽位卡 + 返回）。
 * new 模式：选位 → 创建页；load 模式：选位 → 读档进游戏。
 */
import { api } from '../net/api.js';
import { getState, setPage, setState } from '../state/store.js';
import { enterCreation } from './creation.js';
import { enterGame } from './game.js';
const SLOT_COUNT = 3; // 开始流程固定 3 个存档位
/** 存档时间戳：毫秒数字时格式化为日期时间，其余原样展示。 */
function formatTimestamp(ts) {
    if (/^\d+$/.test(ts)) {
        const d = new Date(Number(ts));
        if (!isNaN(d.getTime())) {
            const pad = (n) => String(n).padStart(2, '0');
            return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
                `${pad(d.getHours())}:${pad(d.getMinutes())}`;
        }
    }
    return ts;
}
export function initSaveSelectPage() {
    document.getElementById('btn-save-back')?.addEventListener('click', () => setPage('start'));
}
/** 进入存档页：按需建会话 → 拉槽位列表 → 渲染 3 张槽位卡。 */
export async function enterSaveSelect() {
    const title = document.getElementById('save-page-title');
    const wrap = document.getElementById('save-slots');
    if (!wrap)
        return;
    const mode = getState().saveMode;
    if (title)
        title.textContent = mode === 'new' ? '选择存档位' : '读取存档';
    wrap.innerHTML = '<div class="ov-muted">正在读取存档位…</div>';
    try {
        if (!getState().sessionId) {
            const resp = await api.newGame();
            setState({ sessionId: resp.sessionId });
        }
        const { slots } = await api.slots(getState().sessionId);
        const bySlot = new Map(slots.map(s => [s.slot, s]));
        wrap.innerHTML = '';
        for (let i = 1; i <= SLOT_COUNT; i++) {
            const info = bySlot.get(i);
            const card = document.createElement('div');
            card.className = 'save-slot';
            const text = document.createElement('div');
            text.className = 'save-slot-text';
            text.textContent = info
                ? `存档位 ${i}：等级 ${info.level}，${info.location}\n${formatTimestamp(info.timestamp)}，已游玩 ${info.playTime}`
                : `存档位 ${i}：（空）`;
            const btn = document.createElement('button');
            btn.textContent = '选择此位';
            btn.disabled = mode === 'load' && !info;
            btn.addEventListener('click', () => void pickSlot(i, !!info));
            card.appendChild(text);
            card.appendChild(btn);
            wrap.appendChild(card);
        }
    }
    catch (err) {
        wrap.innerHTML = '';
        const msg = document.createElement('div');
        msg.className = 'ov-muted';
        msg.textContent = `读取存档位失败：${err.message}`;
        wrap.appendChild(msg);
    }
    setPage('saveSelect');
}
async function pickSlot(slot, hasSave) {
    const mode = getState().saveMode;
    if (mode === 'new') {
        setState({ saveSlot: slot });
        enterCreation();
        return;
    }
    if (!hasSave)
        return;
    try {
        const resp = await api.load(getState().sessionId, slot);
        setState({ hud: resp.hud, saveSlot: slot });
        await enterGame();
    }
    catch (err) {
        alert(`读档失败：${err.message}`);
    }
}
