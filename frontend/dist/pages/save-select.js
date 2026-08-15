/**
 * save-select.ts — 页面 2：存档位选择（3 个槽位卡 + 返回）。
 * new 模式：选位 → 创建页；load 模式：选位 → 读档进游戏。
 * 槽位卡 html 拼接整块重绘；「选择此位」data-slot 委托。
 */
import { api } from '../net/api.js';
import { getState, setPage, setState, esc } from '../state/store.js';
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
    wrap.innerHTML = '<div class="ov-muted">正在翻存档位……</div>';
    try {
        if (!getState().sessionId) {
            const resp = await api.newGame();
            setState({ sessionId: resp.sessionId });
        }
        const { slots } = await api.slots(getState().sessionId);
        const bySlot = new Map(slots.map(s => [s.slot, s]));
        let html = '';
        for (let i = 1; i <= SLOT_COUNT; i++) {
            const info = bySlot.get(i);
            const text = info
                ? `存档位 ${i}：等级 ${info.level}，${info.location}\n${formatTimestamp(info.timestamp)}，已游玩 ${info.playTime}`
                : `存档位 ${i}：（空）`;
            const disabled = mode === 'load' && !info ? ' disabled' : '';
            html += `<div class="save-slot"><div class="save-slot-text">${esc(text)}</div>` +
                `<button data-slot="${i}"${disabled}>选择此位</button></div>`;
        }
        wrap.innerHTML = html;
        // 槽位点击委托（每轮渲染后重建）
        wrap.querySelectorAll('[data-slot]').forEach(btn => {
            btn.addEventListener('click', () => void pickSlot(Number(btn.dataset.slot), !!bySlot.get(Number(btn.dataset.slot))));
        });
    }
    catch (err) {
        wrap.innerHTML = `<div class="ov-muted">读存档位失败：${esc(err.message)}</div>`;
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
