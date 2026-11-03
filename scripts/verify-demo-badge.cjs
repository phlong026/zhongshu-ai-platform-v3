#!/usr/bin/env node
/**
 * M12-T01：demo 标识验证门禁（修复 M12-I01 断言反参假绿后重构）。
 * - 四态矩阵：隔离副本实测 isDemoMode（沿用既有 Module._compile 沙箱）。
 * - 八页静态检查（root 参数化）：WXML 绑定 + JS 接线 + 全部字面 require 可解析（错误路径在此红）。
 * - 八页运行时检查：真实模块加载 + onLoad/onShow 执行，无 MODULE_NOT_FOUND；本地态 onShow 后 showDemoBadge=true。
 * - 坏样本变异自检：隔离副本注入「删接线/错误路径/丢WXML绑定」三类坏样本，静态核心必须检红。
 * 阶段A：门禁对当前五页坏路径输出红（exit 1）；阶段B（M11-T01 修路径后）：全绿且坏样本仍红。
 * 用法：node scripts/verify-demo-badge.cjs
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
let pass = 0, fail = 0;
// M12-I01 修复点：条件在前、名称在后（旧脚本 ok(path, boolean) 路径字符串恒真 → 假绿）
const ok = (cond, name) => {
  if (cond) { pass++; console.log('  ok - ' + name); }
  else { fail++; console.log('  FAIL - ' + name); }
};

const ROOT = path.join(__dirname, '..');
const MINI = 'miniprogram';
const PAGES = [
  'pages/build/record/index',
  'pages/build/projects/index',
  'pages/build/home/index',
  'pages/admin/tasks/index',
  'pages/admin/commission/index',
  'pages/admin/performance/index',
  'pages/admin/finance-desk/index',
  'pages/studio/index'
];
const read = p => fs.readFileSync(path.join(ROOT, p), 'utf8');

console.log('[demo-badge] 判定模块四态矩阵');
// mock 环境：demo-badge 内部 require http/access——用注入副本实测（双副本：active 与游客）
const badgeSrc = read('miniprogram/utils/demo-badge.js');
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

// 状态矩阵（page 显式传 data 形状模拟页面实例）
ok(isDemoModeActive({ data: {} }, 'local'), 'active+local 渲染');
ok(isDemoModeActive({ data: { platDegraded: true } }, 'api'), 'active+降级标志 → 渲染（有 token 亦然）');
ok(isDemoModeActive({ data: { platformFinance: { status: 'degraded' } } }, 'api'), 'active+finance-desk degraded → 渲染');
ok(isDemoModeActive({ data: {} }, 'mixed'), 'active+mixed 渲染');
ok(!isDemoModeActive({ data: {} }, 'api'), 'active+API 态不渲染');
ok(!isDemoModeGuest({ data: {} }, 'local'), '游客不渲染（登录引导已覆盖）');
ok(!isDemoModeGuest({ data: {} }, 'mixed'), '游客 mixed 亦不渲染');

console.log('[demo-badge] 判定模块零请求与文案');
ok(!/http\.(post|put|delete)\(/.test(badgeSrc), 'badge 模块零写请求');
ok(badgeSrc.includes('platDegraded') && badgeSrc.includes('__platDown'),
  '判定源含降级标志（__platDown/platDegraded/degraded）');
ok(badgeSrc.includes('演示数据 · 本地保存，登录后切换平台数据'), '唯一文案');

// ---------- 注释剥离（Codex P2-3/P2-4：注释与字符串中的 require/接线不得参与判定） ----------
// 字符串感知的轻量状态机：保留字符串字面量原样，移除 // 行注释与 /* */ 块注释
const stripJsComments = src => {
  let out = '', i = 0;
  while (i < src.length) {
    const c = src[i], n = src[i + 1];
    if (c === "'" || c === '"' || c === '`') {
      const q = c; out += c; i++;
      while (i < src.length) {
        if (src[i] === '\\') { out += src[i] + (src[i + 1] || ''); i += 2; continue; }
        out += src[i];
        if (src[i] === q) { i++; break; }
        i++;
      }
      continue;
    }
    if (c === '/' && n === '/') { while (i < src.length && src[i] !== '\n') i++; continue; }
    if (c === '/' && n === '*') { i += 2; while (i < src.length && !(src[i] === '*' && src[i + 1] === '/')) i++; i += 2; continue; }
    out += c; i++;
  }
  return out;
};
const stripWxmlComments = src => src.replace(/<!--[\s\S]*?-->/g, '');

// ---------- 八页静态检查核心（root 参数化：真实根与变异副本共用同一套断言） ----------
const resolveRequire = base => {
  for (const cand of [base, base + '.js', path.join(base, 'index.js')]) {
    try { if (fs.statSync(cand).isFile()) return true; } catch (_) { /* 继续尝试下一候选 */ }
  }
  return false;
};

function staticChecks(root) {
  const readIn = p => fs.readFileSync(path.join(root, p), 'utf8');
  const res = { pass: 0, fail: 0, failures: [] };
  const okIn = (cond, name) => {
    if (cond) res.pass++;
    else { res.fail++; res.failures.push(name); }
  };
  for (const p of PAGES) {
    const js = path.join(MINI, p) + '.js';
    const wxml = path.join(MINI, p) + '.wxml';
    let wxmlOk = false, jsOk = false, code = '';
    try { wxmlOk = stripWxmlComments(readIn(wxml)).includes('showDemoBadge'); } catch (_) { /* 文件缺失即红 */ }
    try {
      code = stripJsComments(readIn(js));
      // 接线=onShow 内 setData({showDemoBadge: isDemoMode(...)}) 真实赋值语句（Codex P2-3：硬编码 true 不算接线）；仅 data 初始字段不算（删接线样本必须红）
      jsOk = /setData\(\s*\{\s*showDemoBadge\s*:/.test(code)
        && /setData\(\s*\{\s*showDemoBadge\s*:[^;]*?isDemoMode\s*\(/.test(code);
    } catch (_) { /* 文件缺失即红 */ }
    okIn(wxmlOk, 'WXML 绑定 ' + p);
    okIn(jsOk, 'JS 接线 ' + p);
    for (const m of code.matchAll(/require\(\s*(['"])([^'"]+)\1\s*\)/g)) {
      const spec = m[2];
      let target = null;
      if (spec.startsWith('.')) target = path.resolve(path.dirname(path.join(root, js)), spec);
      else if (spec.startsWith('/')) target = path.join(root, MINI, spec.replace(/^\//, ''));
      if (target === null) { okIn(false, 'require 说明符非法 ' + p + ' → ' + spec); continue; }
      // 相对路径深度错误（多退/少退一级）在此检红——M11-I01 五页即此模式
      okIn(resolveRequire(target), 'require 可解析 ' + p + ' → ' + spec);
    }
  }
  return res;
}

console.log('[demo-badge] 八页静态检查（真实根）');
const staticRes = staticChecks(ROOT);
pass += staticRes.pass;
for (const name of staticRes.failures) { fail++; console.log('  FAIL - ' + name); }

console.log('[demo-badge] 坏样本变异自检（隔离副本三类注入必红）');
(() => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'zs-demo-badge-mut-'));
  try {
    const cloneRoot = path.join(tmp, 'repo');
    fs.mkdirSync(path.join(cloneRoot, MINI), { recursive: true });
    fs.cpSync(path.join(ROOT, MINI), path.join(cloneRoot, MINI), { recursive: true });
    const t = PAGES[3]; // pages/admin/tasks/index（本地正确页作样本底版）
    const tJs = path.join(cloneRoot, MINI, t + '.js');
    const tWxml = path.join(cloneRoot, MINI, t + '.wxml');
    const bJs = path.join(cloneRoot, MINI, PAGES[1] + '.js'); // pages/build/projects（require 深度正确的页）
    const jsOrig = fs.readFileSync(tJs, 'utf8');
    const wxmlOrig = fs.readFileSync(tWxml, 'utf8');
    const bOrig = fs.readFileSync(bJs, 'utf8');
    // 名称差分：副本继承真实根当前状态（阶段A基线含五页红）；注入后必须出现基线没有的新失败项。
    // 不用计数差分——如删接线会连带移除该页唯一坏 require，新旧失败总数可能持平。
    const baseFailures = staticChecks(cloneRoot).failures;
    const isNew = (res, prefix) => res.failures.some(n => n.startsWith(prefix)) && !baseFailures.some(n => n.startsWith(prefix));
    // 样本一：删除 JS 接线语句 → 「JS 接线」红
    const mutatedJs = jsOrig.replace(/this\.setData\s*&&\s*this\.setData\(\{\s*showDemoBadge:[\s\S]*?\}\)\s*;?/, '');
    fs.writeFileSync(tJs, mutatedJs);
    let res = staticChecks(cloneRoot);
    ok(isNew(res, 'JS 接线 ' + t), '坏样本「删接线」检红（删后必须非零退出）');
    fs.writeFileSync(tJs, jsOrig);
    // 样本二：require 错误路径（正确深度 → 少退一级）→ 「require 可解析」红
    fs.writeFileSync(bJs, bOrig.replace("require('../../../utils/demo-badge')", "require('../../utils/demo-badge')"));
    res = staticChecks(cloneRoot);
    ok(res.failures.some(n => n.includes('require 可解析 ' + PAGES[1])), '坏样本「错误路径」检红（深度错误必须非零退出）');
    fs.writeFileSync(bJs, bOrig);
    // 样本三：丢 WXML 绑定（改名不得含原子串，否则 includes 检查漏检）→ 「WXML 绑定」红
    fs.writeFileSync(tWxml, wxmlOrig.split('showDemoBadge').join('showBadgeGone'));
    res = staticChecks(cloneRoot);
    ok(isNew(res, 'WXML 绑定 ' + t), '坏样本「丢WXML绑定」检红（绑定缺失必须非零退出）');
    fs.writeFileSync(tWxml, wxmlOrig);
    // 样本四（Codex P2-1）：注释保留标识条——注释内残留不得通过 → 「WXML 绑定」红
    fs.writeFileSync(tWxml, wxmlOrig.replace(/<view wx:if="\{\{showDemoBadge\}\}"[\s\S]*?<\/view>/, '<!-- $& -->'));
    res = staticChecks(cloneRoot);
    ok(isNew(res, 'WXML 绑定 ' + t), '坏样本「注释保留绑定」检红（注释残留必须非零退出）');
    fs.writeFileSync(tWxml, wxmlOrig);
    // 样本五（Codex P2-3）：硬编码 true 绕过 isDemoMode 调用 → 「JS 接线」红
    fs.writeFileSync(tJs, jsOrig.replace(/isDemoMode\(this\)/, 'true'));
    res = staticChecks(cloneRoot);
    ok(isNew(res, 'JS 接线 ' + t), '坏样本「硬编码true」检红（绕过判定源必须非零退出）');
    fs.writeFileSync(tJs, jsOrig);
    // 复原后副本应回到基线——证明检红来自注入本身而非副本环境自身致红
    res = staticChecks(cloneRoot);
    ok(res.fail === baseFailures.length && res.failures.every(n => baseFailures.includes(n)), '坏样本复原后副本回到基线（排除副本环境自身致红）');
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
})();

// ---------- 八页运行时检查：真实模块加载 + onLoad/onShow（role-page-runtime 既有沙箱） ----------
console.log('[demo-badge] 八页模块加载 + onShow 真实执行');
process.on('unhandledRejection', e => {
  fail++;
  console.log('  FAIL - 迟到未处理拒绝: ' + String((e && e.message) || e).split('\n')[0]);
});
(async () => {
  const badgeRuntime = require('./role-page-runtime.cjs');
  let runtime;
  try {
    runtime = await badgeRuntime.connect();
  } catch (e) {
    ok(false, '运行时初始化 → ' + String((e && e.message) || e).split('\n')[0]);
    console.log(`\n通过 ${pass}，失败 ${fail}`);
    process.exit(1);
  }
  const Account = require('../miniprogram/utils/account');
  // 渲染级断言（Codex P2-1：注释保留绑定/仅数据字段时，数据检查发现不了渲染缺失）
  const renderedText = nodes => nodes.flatMap(n => n.tag === '#text' ? [n.text] : renderedText(n.children || [])).join('');
  const visit = (p, query, loginId) => {
    Account.mutate(s => Account.loginDemo(s, loginId));
    let error = null;
    try { wx.reLaunch({ url: '/' + p + (query ? '?' + query : '') }); } catch (e) { error = e; }
    if (error) {
      const msg = String((error && error.message) || error).split('\n')[0];
      const kind = error.code === 'MODULE_NOT_FOUND' || /Cannot find module/.test(msg) ? 'MODULE_NOT_FOUND' : '异常';
      ok(false, '模块加载+onLoad/onShow ' + p + ' → ' + kind + ': ' + msg);
      return;
    }
    const page = getCurrentPages().at(-1);
    ok(page && page.route === p, '页面保持栈顶（未被守卫接管） ' + p);
    ok(page && page.data && page.data.showDemoBadge === true, 'onShow 后 showDemoBadge=true（本地态标识接线生效） ' + p);
    let rendered = '';
    try { rendered = renderedText(badgeRuntime.render(page.file, page.data)); }
    catch (e) { ok(false, 'WXML 渲染 ' + p + ' → ' + String((e && e.message) || e).split('\n')[0]); }
    ok(rendered.includes('演示数据 · 本地保存'), '标识条真实渲染唯一文案（本地态） ' + p);
  };
  // boss（boss+owner 双角色）：build 工作台与 admin 各页守卫均放行；studio 需 sales → sales-dual
  visit('pages/build/home/index', '', 'boss');
  visit('pages/build/record/index', '', 'boss');
  visit('pages/build/projects/index', '', 'boss');
  visit('pages/admin/tasks/index', 'view=team', 'boss');
  visit('pages/admin/commission/index', '', 'boss');
  visit('pages/admin/finance-desk/index', '', 'boss');
  visit('pages/admin/performance/index', 'history=1', 'boss'); // 缺 history=1 会重定向 tasks，onShow 不在本页执行
  visit('pages/studio/index', '', 'sales-dual');
  // Codex P2-2：留出异步生命周期错误冒泡窗口（unhandledRejection 已计入 fail），再汇总退出
  await new Promise(r => setTimeout(r, 300));
  console.log(`\n通过 ${pass}，失败 ${fail}`);
  process.exit(fail ? 1 : 0);
})();
