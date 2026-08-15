/**
 * ai-settings.ts — AI 供应商接入设置（起始页「AI 设置」与游戏内设置面板共用）。
 * 供应商统一为 OpenAI 兼容端点：本地模型服务（Ollama/llama.cpp）与云端模型同构，
 * 差异仅在服务地址/密钥/模型名。保存立即生效并持久化（后端 saveDir/ai-config.json）。
 * 表单 html 拼接渲染；预设/按钮静态绑定（每轮打开重建）。
 */
import { api } from '../net/api.js';
import { esc } from '../state/store.js';
/** 供应商预设：前两项为本地模型服务，其余为云端模型。 */
const PRESETS = {
    '本地 Ollama': { url: 'http://127.0.0.1:11434/v1', model: 'qwen2' },
    '本地 llama.cpp': { url: 'http://127.0.0.1:8080', model: 'local-model' },
    'DeepSeek': { url: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
    'OpenAI': { url: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
    '月之暗面 Kimi': { url: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
    '智谱 GLM': { url: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
    '通义千问': { url: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
};
let inited = false;
export function initAiSettings() {
    if (inited)
        return;
    inited = true;
    document.getElementById('ai-settings-close')?.addEventListener('click', closeAiSettings);
    document.getElementById('btn-ai-settings')?.addEventListener('click', () => void openAiSettings());
}
export function closeAiSettings() {
    document.getElementById('ai-mask')?.setAttribute('hidden', '');
    document.getElementById('ai-panel')?.setAttribute('hidden', '');
}
/** 打开设置面板：渲染表单 → 拉取当前配置回填。 */
export async function openAiSettings() {
    const mask = document.getElementById('ai-mask');
    const panel = document.getElementById('ai-panel');
    const body = document.getElementById('ai-settings-body');
    if (!mask || !panel || !body)
        return;
    mask.removeAttribute('hidden');
    panel.removeAttribute('hidden');
    renderBody(body);
    setStatus('加载中……', '');
    try {
        const cfg = await api.aiConfig();
        fill(cfg);
        setStatus(cfg.enabled && cfg.baseUrl
            ? '供应商开着呢，改完点「保存」立刻生效。'
            : '没启用 LLM（内嵌记忆 + 规则兜底）。选个供应商保存就接上了。', '');
    }
    catch (err) {
        setStatus(`读配置失败：${err.message}`, 'err');
    }
}
function renderBody(body) {
    let html = '';
    html += '<div class="ov-muted">供应商统一走 OpenAI 兼容端点：本地模型服务免密钥，云端要 API 密钥。不启用就走内嵌记忆 + 规则兜底，游戏照玩。</div>';
    html += `<label class="form-row"><input type="checkbox" id="ai-enabled" /> 启用 LLM 生成（关了只用内嵌记忆与规则兜底）</label>`;
    html += '<div class="btn-row" id="ai-presets">';
    for (const name of [...Object.keys(PRESETS), '自定义']) {
        html += `<button data-preset="${esc(name)}">${esc(name)}</button>`;
    }
    html += '</div>';
    html += `<label class="form-row-col"><span>服务地址（OpenAI 兼容，云端通常以 /v1 结尾）</span>` +
        `<input type="text" id="ai-base-url" placeholder="http://127.0.0.1:11434/v1" /></label>`;
    html += `<label class="form-row-col"><span>API 密钥（本地模型服务可留空）</span>` +
        `<input type="text" id="ai-api-key" placeholder="sk-…" /></label>`;
    html += `<label class="form-row-col"><span>模型名</span>` +
        `<input type="text" id="ai-model" placeholder="qwen2" /></label>`;
    html += '<div class="btn-row">' +
        '<button id="ai-test-btn">测试连接</button>' +
        '<button id="ai-save-btn">保存</button>' +
        '<button id="ai-close-btn">关闭</button></div>';
    html += '<p id="ai-settings-status" class="hint"></p>';
    body.innerHTML = html;
    // 静态绑定（每轮重建）
    body.querySelectorAll('[data-preset]').forEach(btn => {
        btn.addEventListener('click', () => {
            const p = PRESETS[btn.dataset.preset ?? ''];
            if (p) {
                setVal('ai-base-url', p.url);
                setVal('ai-model', p.model);
            }
            body.querySelectorAll('[data-preset]').forEach(b => b.classList.toggle('selected', b === btn));
        });
    });
    document.getElementById('ai-test-btn')?.addEventListener('click', () => void onTest());
    document.getElementById('ai-save-btn')?.addEventListener('click', () => void onSave());
    document.getElementById('ai-close-btn')?.addEventListener('click', closeAiSettings);
}
function fill(cfg) {
    setVal('ai-enabled', cfg.enabled);
    setVal('ai-base-url', cfg.baseUrl);
    setVal('ai-api-key', cfg.apiKey);
    setVal('ai-model', cfg.model);
    // 高亮与当前地址匹配的预设，否则「自定义」
    let matched = '自定义';
    for (const [name, p] of Object.entries(PRESETS)) {
        if (cfg.baseUrl === p.url) {
            matched = name;
            break;
        }
    }
    document.querySelectorAll('#ai-presets [data-preset]').forEach(btn => {
        btn.classList.toggle('selected', btn.dataset.preset === matched);
    });
}
function collect() {
    return {
        enabled: document.getElementById('ai-enabled')?.checked ?? false,
        baseUrl: getVal('ai-base-url'),
        apiKey: getVal('ai-api-key'),
        model: getVal('ai-model'),
    };
}
async function onTest() {
    const cfg = collect();
    if (!cfg.baseUrl) {
        setStatus('先把服务地址填上。', 'err');
        return;
    }
    setStatus('正在试连供应商……', '');
    try {
        const r = await api.testAi(cfg);
        if (r.ok)
            setStatus(`连上了，模型回话：${r.reply ?? ''}`, 'ok');
        else
            setStatus(`试连没成：${r.error ?? '不知道啥毛病'}`, 'err');
    }
    catch (err) {
        setStatus(`试连没成：${err.message}`, 'err');
    }
}
async function onSave() {
    const cfg = collect();
    if (cfg.enabled && !cfg.baseUrl) {
        setStatus('要启用就得先填服务地址。', 'err');
        return;
    }
    try {
        await api.saveAiConfig(cfg);
        setStatus(cfg.enabled
            ? `已保存并启用（${cfg.baseUrl}）。自由对话走这供应商；挂了自动回退规则兜底。`
            : '已保存：关掉 LLM 生成，回到内嵌记忆 + 规则兜底。', 'ok');
    }
    catch (err) {
        setStatus(`保存失败：${err.message}`, 'err');
    }
}
function setVal(id, v) {
    const el = document.getElementById(id);
    if (!el)
        return;
    if (typeof v === 'boolean')
        el.checked = v;
    else
        el.value = v;
}
function getVal(id) {
    return (document.getElementById(id)?.value ?? '').trim();
}
function setStatus(text, cls) {
    const el = document.getElementById('ai-settings-status');
    if (!el)
        return;
    el.textContent = text;
    el.className = 'hint' + (cls ? ` ${cls}` : '');
}
