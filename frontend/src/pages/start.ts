/**
 * start.ts — 页面 1：开始界面（对齐 MainView.axaml 开始页：标题 + 三菜单按钮 + AI 状态）。
 */

import { api } from '../net/api.js';
import { setState } from '../state/store.js';

export function initStartPage(): void {
  const status = document.getElementById('start-ai-status');
  api.health()
    .then(h => {
      if (status) status.textContent = h.aiAvailable
        ? '本地 AI 已连接，自由对话与动态遭遇已启用'
        : '本地 AI 未启动（localhost:8000），已降级为规则兜底叙事';
    })
    .catch(() => {
      if (status) status.textContent = '后端未响应，请确认 HttpApiServer 已启动';
    });

  // 仅记录进入模式；拉槽位/页面切换由 main.ts 的 enterSaveSelect 统一负责
  document.getElementById('btn-new-game')?.addEventListener('click', () => setState({ saveMode: 'new' }));
  document.getElementById('btn-load-game')?.addEventListener('click', () => setState({ saveMode: 'load' }));
  document.getElementById('btn-exit')?.addEventListener('click', () => {
    window.close();
    if (status) status.textContent = '浏览器安全策略不允许脚本关闭窗口，直接关闭标签页即可。';
  });
}
