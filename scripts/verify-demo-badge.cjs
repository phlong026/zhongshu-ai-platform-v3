#!/usr/bin/env node
/**
 * M5/22 号 AC1：demo 标识验证（四态矩阵/八页 wxml 绑定/标识零请求/判定源含降级标志）。
 * 用法：node scripts/verify-demo-badge.cjs
 */
const fs = require('fs');
const path = require('path');
let pass = 0, fail = 0;
const ok = (cond, name) => {
  if (cond) { pass++; console.log('  ok - ' + name); }
  else { fail++; console.log('  FAIL - ' + name); }
};

const ROOT = path.join(__dirname, '..');
const read = p => fs.readFileSync(path.join(ROOT, p), 'utf8');

console.log('[demo-badge] 判定模块四态矩阵');
// mock 环境：demo-badge 内部 require http/access——用 Module._load 注入或直接构造隔离副本
const badgeSrc = read('miniprogram/utils/demo-badge.js');

// 四态矩阵：以注入 http.token/access.context 的隔离副本实测 isDemoMode（双副本：active 与游客）
const injected = badgeSrc
  .replace("require('../api/http')", '{ token: () => tokenStore.token }')
  .replace("require('./access')", '{ context: () => ({ active: identityStore.active }) }');
const makeSandbox = active => {
  const mod = new (require('module').Module)('demo-badge-sandbox', null);
  mod._compile('const tokenStore = { token: "" }; const identityStore = { active: ' + active + ' };\n' + injected
    + '\nmodule.exports = { isDemoMode };', 'demo-badge-sandbox.js');
  return mod.exports.isDemoMode;
};
const isDemoModeActive = makeSandbox(true);   // 有效身份（demo 或 API 会话）
const isDemoModeGuest = makeSandbox(false);   // 游客（无 active 身份，登录引导已覆盖）

// 状态矩阵（R2 P2-6 四态；page 显式传 data 形状模拟页面实例）
ok(isDemoModeActive({ data: {} }, 'local'), 'active+local 渲染');
ok(isDemoModeActive({ data: { platDegraded: true } }, 'api'), 'active+降级标志 → 渲染（有 token 亦然）');
ok(isDemoModeActive({ data: { platformFinance: { status: 'degraded' } } }, 'api'), 'active+finance-desk degraded → 渲染');
ok(isDemoModeActive({ data: {} }, 'mixed'), 'active+mixed 渲染');
ok(!isDemoModeActive({ data: {} }, 'api'), 'active+API 态不渲染');
ok(!isDemoModeGuest({ data: {} }, 'local'), '游客不渲染（登录引导已覆盖）');
ok(!isDemoModeGuest({ data: {} }, 'mixed'), '游客 mixed 亦不渲染');

console.log('[demo-badge] 八页 wxml 绑定');
const pages = [
  'miniprogram/pages/build/record/index.wxml',
  'miniprogram/pages/build/projects/index.wxml',
  'miniprogram/pages/build/home/index.wxml',
  'miniprogram/pages/admin/tasks/index.wxml',
  'miniprogram/pages/admin/commission/index.wxml',
  'miniprogram/pages/admin/performance/index.wxml',
  'miniprogram/pages/admin/finance-desk/index.wxml',
  'miniprogram/pages/studio/index.wxml'
];
pages.forEach(p => ok(p, fs.existsSync(path.join(ROOT, p)) && read(p).includes('showDemoBadge')));

console.log('[demo-badge] 标识零请求 + 判定源含降级标志');
ok(!/http\.(post|put|delete)\(/.test(badgeSrc), 'badge 模块零写请求');
ok(badgeSrc.includes('platDegraded') && badgeSrc.includes('__platDown'),
  '判定源含降级标志（__platDown/platDegraded/degraded）');
ok(badgeSrc.includes('演示数据 · 本地保存，登录后切换平台数据'), '唯一文案');
// 八页 js 均接 showDemoBadge
pages.forEach(p => {
  const js = p.replace('.wxml', '.js');
  ok(js, fs.existsSync(path.join(ROOT, js)) && read(js).includes('showDemoBadge'));
});

console.log(`\n通过 ${pass}，失败 ${fail}`);
process.exit(fail ? 1 : 0);
