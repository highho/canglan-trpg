/**
 * store.ts — 极简状态（页面/会话/HUD），无框架。
 * 对齐开拓者式全局状态思路：一个地方记状态，页面切换只翻显隐。
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
/** 四页面互斥显隐。 */
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
/** HTML 转义：所有用户/服务端文本进 innerHTML 前必过。 */
export function esc(s) {
    return s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
