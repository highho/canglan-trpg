/**
 * main.ts — 前端入口：初始化四页面并停在开始界面。
 */
import { setPage } from './state/store.js';
import { initStartPage } from './pages/start.js';
import { initSaveSelectPage, enterSaveSelect } from './pages/save-select.js';
import { initCreationPage } from './pages/creation.js';
import { initGamePage } from './pages/game.js';
function boot() {
    initStartPage();
    initSaveSelectPage();
    initCreationPage();
    initGamePage();
    // 开始页两个入口都先进存档位选择（对应新游戏/读档命令）
    document.getElementById('btn-new-game')?.addEventListener('click', () => void enterSaveSelect());
    document.getElementById('btn-load-game')?.addEventListener('click', () => void enterSaveSelect());
    setPage('start');
}
boot();
