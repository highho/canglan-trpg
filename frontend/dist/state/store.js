/**
 * store.ts — 极简发布订阅状态（禁止框架；对齐原 Avalonia 四页状态机）。
 */
const state = {
    page: 'start',
    sessionId: '',
    hud: null,
    saveMode: 'new',
    saveSlot: 1,
};
const listeners = new Set();
export function getState() {
    return state;
}
export function setState(patch) {
    Object.assign(state, patch);
    for (const fn of listeners)
        fn();
}
export function subscribe(fn) {
    listeners.add(fn);
    return () => listeners.delete(fn);
}
const PAGE_ID = {
    start: 'page-start',
    saveSelect: 'page-save-select',
    creation: 'page-creation',
    game: 'page-game',
};
/** 四页面互斥显隐（对应 Avalonia IsStartPage/IsSavePage/IsCreationPage/IsGamePage）。 */
export function setPage(page) {
    state.page = page;
    for (const [key, id] of Object.entries(PAGE_ID)) {
        const el = document.getElementById(id);
        if (el)
            el.classList.toggle('active', key === page);
    }
    for (const fn of listeners)
        fn();
}
/** HTML 转义（所有用户/服务端文本进 DOM 前必过）。 */
export function esc(s) {
    return s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
