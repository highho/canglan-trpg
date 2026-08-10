// run-regress.js — 依次运行七套回归测试并统计 PASS/FAIL（中文输出乱码不影响计数）
const { spawnSync } = require('child_process');

const suites = [
  ['Bootstrap', 'com.canglan.data.BootstrapSmokeTest', ['data']],
  ['World', 'com.canglan.world.WorldSmokeTest', ['data']],
  ['Battle', 'com.canglan.world.battle.BattleSmokeTest', ['data']],
  ['Save', 'com.canglan.save.SaveRoundTripSmokeTest', ['data', 'reg-saves']],
  ['Api', 'com.canglan.api.ApiSmokeTest', ['data', 'reg-api-saves']],
  ['Ai', 'com.canglan.ai.AiSmokeTest', ['data']],
  ['FullFlow', 'com.canglan.api.FullFlowSmokeTest', ['data', 'reg-flow-saves']],
];

let totalPass = 0, totalFail = 0, failed = [];
for (const [name, cls, args] of suites) {
  const r = spawnSync('java', ['-Dfile.encoding=UTF-8', '-cp', 'build-all', cls, ...args], {
    encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, timeout: 300000,
  });
  const out = (r.stdout || '') + (r.stderr || '');
  const pass = (out.match(/PASS/g) || []).length;
  const fail = (out.match(/FAIL/g) || []).length;
  totalPass += pass; totalFail += fail;
  console.log(`${name.padEnd(9)} PASS=${pass} FAIL=${fail} exit=${r.status}`);
  if (fail > 0 || r.status !== 0) {
    failed.push(name);
    const failLines = out.split(/\r?\n/).filter(l => l.includes('FAIL'));
    failLines.slice(0, 10).forEach(l => console.log('    ' + l));
  }
}
console.log(`TOTAL PASS=${totalPass} FAIL=${totalFail}` + (failed.length ? `  失败套件: ${failed.join(',')}` : '  全绿'));
process.exit(totalFail > 0 ? 1 : 0);
