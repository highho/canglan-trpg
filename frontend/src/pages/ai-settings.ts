/**
 * ai-settings.ts — AI 供应商接入设置（起始页「AI 设置」与游戏内设置面板共用）。
 * 供应商统一为 OpenAI 兼容端点：本地模型服务（Ollama/llama.cpp）与云端模型（OpenAI/DeepSeek 等）同构，
 * 差异仅在服务地址/密钥/模型名。保存立即生效并持久化（后端 saveDir/ai-config.json）。
 */

import { api, AiProviderConfig } from '../net/api.js';

interface Preset { url: string; model: string; }

/** 供应商预设：前两项为本地模型服务，其余为云端模型。 */
const PRESETS: Record<string, Preset> = {
  '本地 Ollama': { url: 'http://127.0.0.1:11434/v1', model: 'qwen2' },
  '本地 llama.cpp': { url: 'http://127.0.0.1:8080', model: 'local-model' },
  'DeepSeek': { url: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  'OpenAI': { url: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  '月之暗面 Kimi': { url: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
  '智谱 GLM': { url: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
  '通义千问': { url: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
};

let inited = false;

export function initAiSettings(): void {
  if (inited) return;
  inited = true;
  document.getElementById('ai-settings-close')?.addEventListener('click', closeAiSettings);
  document.getElementById('btn-ai-settings')?.addEventListener('click', () => void openAiSettings());
}

export function closeAiSettings(): void {
  document.getElementById('ai-mask')?.setAttribute('hidden', '');
  document.getElementById('ai-panel')?.setAttribute('hidden', '');
}

/** 打开设置面板：渲染表单 → 拉取当前配置回填。 */
export async function openAiSettings(): Promise<void> {
  const mask = document.getElementById('ai-mask');
  const panel = document.getElementById('ai-panel');
  const body = document.getElementById('ai-settings-body');
  if (!mask || !panel || !body) return;
  mask.removeAttribute('hidden');
  panel.removeAttribute('hidden');
  renderBody(body);
  setStatus('加载中…', '');
  try {
    const cfg = await api.aiConfig();
    fill(cfg);
    setStatus(cfg.enabled && cfg.baseUrl
      ? '当前已启用供应商，修改后点「保存」立即生效。'
      : '当前未启用 LLM 生成（内嵌记忆+规则兜底）。选择供应商并保存即可接入。', '');
  } catch (err) {
    setStatus(`读取配置失败：${(err as Error).message}`, 'err');
  }
}

function renderBody(body: HTMLElement): void {
  body.innerHTML = '';

  const hint = document.createElement('div');
  hint.className = 'ov-muted';
  hint.textContent = '供应商统一为 OpenAI 兼容端点：本地模型服务免密钥，云端模型需 API 密钥。不启用时走内嵌记忆+规则兜底，游戏不受影响。';
  body.appendChild(hint);

  const enabledRow = document.createElement('label');
  enabledRow.className = 'form-row';
  const cb = document.createElement('input');
  cb.type = 'checkbox';
  cb.id = 'ai-enabled';
  enabledRow.appendChild(cb);
  enabledRow.appendChild(document.createTextNode(' 启用 LLM 生成（关闭则仅用内嵌记忆与规则兜底）'));
  body.appendChild(enabledRow);

  const presets = document.createElement('div');
  presets.id = 'ai-presets';
  presets.className = 'btn-row';
  for (const name of [...Object.keys(PRESETS), '自定义']) {
    const btn = document.createElement('button');
    btn.textContent = name;
    btn.addEventListener('click', () => {
      const p = PRESETS[name];
      if (p) {
        setVal('ai-base-url', p.url);
        setVal('ai-model', p.model);
      }
      presets.querySelectorAll('button').forEach(b => b.classList.toggle('selected', b === btn));
    });
    presets.appendChild(btn);
  }
  body.appendChild(presets);

  body.appendChild(field('ai-base-url', '服务地址（OpenAI 兼容，云端通常以 /v1 结尾）', 'http://127.0.0.1:11434/v1'));
  body.appendChild(field('ai-api-key', 'API 密钥（本地模型服务可留空）', 'sk-…'));
  body.appendChild(field('ai-model', '模型名', 'qwen2'));

  const ops = document.createElement('div');
  ops.className = 'btn-row';
  const testBtn = document.createElement('button');
  testBtn.textContent = '测试连接';
  testBtn.addEventListener('click', () => void onTest());
  const saveBtn = document.createElement('button');
  saveBtn.textContent = '保存';
  saveBtn.addEventListener('click', () => void onSave());
  const closeBtn = document.createElement('button');
  closeBtn.textContent = '关闭';
  closeBtn.addEventListener('click', closeAiSettings);
  ops.append(testBtn, saveBtn, closeBtn);
  body.appendChild(ops);

  const status = document.createElement('p');
  status.id = 'ai-settings-status';
  status.className = 'hint';
  body.appendChild(status);
}

function fill(cfg: AiProviderConfig): void {
  setVal('ai-enabled', cfg.enabled);
  setVal('ai-base-url', cfg.baseUrl);
  setVal('ai-api-key', cfg.apiKey);
  setVal('ai-model', cfg.model);
  // 高亮与当前地址匹配的预设，否则「自定义」
  let matched = '自定义';
  for (const [name, p] of Object.entries(PRESETS)) {
    if (cfg.baseUrl === p.url) { matched = name; break; }
  }
  document.querySelectorAll<HTMLButtonElement>('#ai-presets button').forEach(btn => {
    btn.classList.toggle('selected', btn.textContent === matched);
  });
}

function collect(): AiProviderConfig {
  return {
    enabled: (document.getElementById('ai-enabled') as HTMLInputElement | null)?.checked ?? false,
    baseUrl: getVal('ai-base-url'),
    apiKey: getVal('ai-api-key'),
    model: getVal('ai-model'),
  };
}

async function onTest(): Promise<void> {
  const cfg = collect();
  if (!cfg.baseUrl) { setStatus('请先填写服务地址。', 'err'); return; }
  setStatus('正在试连供应商…', '');
  try {
    const r = await api.testAi(cfg);
    if (r.ok) setStatus(`连接成功，模型回复：${r.reply ?? ''}`, 'ok');
    else setStatus(`试连失败：${r.error ?? '未知错误'}`, 'err');
  } catch (err) {
    setStatus(`试连失败：${(err as Error).message}`, 'err');
  }
}

async function onSave(): Promise<void> {
  const cfg = collect();
  if (cfg.enabled && !cfg.baseUrl) { setStatus('启用前必须填写服务地址。', 'err'); return; }
  try {
    await api.saveAiConfig(cfg);
    setStatus(cfg.enabled
      ? `已保存并启用（${cfg.baseUrl}）。自由对话即刻走所选供应商；失败自动回退规则兜底。`
      : '已保存：关闭 LLM 生成，回到内嵌记忆+规则兜底。', 'ok');
  } catch (err) {
    setStatus(`保存失败：${(err as Error).message}`, 'err');
  }
}

function field(id: string, label: string, placeholder: string): HTMLLabelElement {
  const row = document.createElement('label');
  row.className = 'form-row-col';
  const span = document.createElement('span');
  span.textContent = label;
  const input = document.createElement('input');
  input.type = 'text';
  input.id = id;
  input.placeholder = placeholder;
  row.append(span, input);
  return row;
}

function setVal(id: string, v: string | boolean): void {
  const el = document.getElementById(id) as HTMLInputElement | null;
  if (!el) return;
  if (typeof v === 'boolean') el.checked = v;
  else el.value = v;
}

function getVal(id: string): string {
  return ((document.getElementById(id) as HTMLInputElement | null)?.value ?? '').trim();
}

function setStatus(text: string, cls: string): void {
  const el = document.getElementById('ai-settings-status');
  if (!el) return;
  el.textContent = text;
  el.className = 'hint' + (cls ? ` ${cls}` : '');
}
