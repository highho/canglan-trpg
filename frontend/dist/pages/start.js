/**
 * start.ts — 页面 1：开始界面（标题 + 四菜单按钮 + AI 状态；「AI 设置」接入供应商配置）。
 */
import { api } from '../net/api.js';
import { setState } from '../state/store.js';
import { initAiSettings } from './ai-settings.js';
export function initStartPage() {
    initAiSettings();
    const status = document.getElementById('start-ai-status');
    api.health()
        .then(h => {
        if (status)
            status.textContent = h.llm
                ? 'AI 供应商已接好（LLM 生成开着），点「AI 设置」能调'
                : '内嵌 AI 管线就绪（二层记忆 + 规则兜底），点「AI 设置」可接本地/云端模型';
    })
        .catch(() => {
        if (status)
            status.textContent = '后端没动静，确认 HttpApiServer 起了没';
    });
    // 仅记录进入模式；拉槽位/页面切换由 main.ts 的 enterSaveSelect 统一负责
    document.getElementById('btn-new-game')?.addEventListener('click', () => setState({ saveMode: 'new' }));
    document.getElementById('btn-load-game')?.addEventListener('click', () => setState({ saveMode: 'load' }));
    document.getElementById('btn-exit')?.addEventListener('click', () => {
        window.close();
        if (status)
            status.textContent = '浏览器不让脚本关窗口，直接关标签页就行。';
    });
}
