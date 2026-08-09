/**
 * api.ts — REST 客户端（对齐 HttpApiServer 端点全集；P8 补 creation/options 与 game/start）。
 */
async function post(path, payload) {
    const resp = await fetch(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    const body = await resp.json();
    if (!resp.ok)
        throw new Error(body.error ?? `HTTP ${resp.status}`);
    return body;
}
async function get(path) {
    const resp = await fetch(path);
    const body = await resp.json();
    if (!resp.ok)
        throw new Error(body.error ?? `HTTP ${resp.status}`);
    return body;
}
export const api = {
    health: () => get('/api/health'),
    newGame: (difficulty) => post('/api/game/new', { difficulty: difficulty ?? '' }),
    creationOptions: (race, clazz) => {
        const q = new URLSearchParams();
        if (race)
            q.set('race', race);
        if (clazz)
            q.set('clazz', clazz);
        const qs = q.toString();
        return get('/api/creation/options' + (qs ? `?${qs}` : ''));
    },
    /** 一步建档（对齐原 Avalonia「开始冒险」按钮）。 */
    startGame: (req) => post('/api/game/start', req),
    command: (sessionId, line) => post('/api/game/command', { sessionId, line }),
    state: (sessionId) => get(`/api/game/state?sessionId=${encodeURIComponent(sessionId)}`),
    panel: (sessionId, name) => get(`/api/panel/${name}?sessionId=${encodeURIComponent(sessionId)}`),
    slots: (sessionId) => get(`/api/save/slots?sessionId=${encodeURIComponent(sessionId)}`),
    save: (sessionId, slot) => post(`/api/save/${slot}?sessionId=${encodeURIComponent(sessionId)}`, {}),
    load: (sessionId, slot) => post(`/api/load/${slot}?sessionId=${encodeURIComponent(sessionId)}`, {}),
};
