#!/usr/bin/env node
/**
 * 阶段 2 · 工程全周期 v3 接入静态验证（17 号任务书 §七 接入面 / AC14）。
 * 断言：契约层端点齐全（§五 全表）、纯函数层纯性与映射正确、五页 API 分支与降级路径存在、
 * 经典容器页（build/index）recordFlow 重定向保留、check 回归另跑 npm run check。
 * 用法：node scripts/verify-engineering-pages.cjs
 */
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const ROOT = path.join(__dirname, '..');
const read = p => fs.readFileSync(path.join(ROOT, p), 'utf8');
let pass = 0, fail = 0;
function ok(label, cond) {
  if (cond) { pass++; console.log('  ✅ ' + label); }
  else { fail++; console.log('  ❌ ' + label); }
}

console.log('[1] 契约层 api/franchise/engineering.js');
const api = read('miniprogram/api/franchise/engineering.js');
for (const [group, endpoint] of [
  ['projects', '/franchise/engineering/projects'],
  ['detail', '/franchise/engineering/\' + id'],
  ['manager', '/manager'],
  ['prepare', '/prepare'], ['start', '/start'], ['plan save', '/plan\''],
  ['reschedule', '/plan/reschedule'], ['history', '/plan/history'],
  ['complete', '/complete'], ['reopen', '/reopen'], ['correct', '/correct'],
  ['materials', '/materials'], ['adjust', '/franchise/engineering/materials/'],
  ['terms', '/terms'], ['arrival', '/arrival'], ['arrival-correction', 'arrival-correction'],
  ['movements', '/movements'], ['material-followups', '/material-followups'],
  ['summary', '/materials/summary'],
  ['site-records', '/site-records'], ['site correct', '/correct'],
  ['collections', '/collections'], ['dunning', '/dunning'], ['followups', '/followups'],
  ['deliver', '/deliver'], ['settle', '/settle'], ['archive', '/archive'], ['settle-summary', '/settle-summary'],
  ['基-1 资产', '/franchise/assets/upload-tickets']
]) ok(group + ' 端点', api.includes(endpoint));
ok('写命令一律带 Idempotency-Key', (api.match(/idem\(key/g) || []).length >= 20);
ok('金额 cents 注释（红线）', api.includes('金额一律分'));

console.log('[2] 纯函数层 utils/platform-engineering.js');
const P = require(path.join(ROOT, 'miniprogram/utils/platform-engineering.js'));
ok('模块可加载（无 wx 顶层依赖崩溃）', typeof P.statusView === 'function');
ok('状态映射五态', P.statusView('PENDING_START') === '待开工' && P.statusView('IN_PROGRESS') === '在建'
  && P.statusView('READY_DELIVER') === '待交付' && P.statusView('DELIVERED') === '已交付' && P.statusView('ARCHIVED') === '已归档');
ok('日期双形状归一（数组/字符串/null）', P.dateOf([2026, 11, 20]) === '2026-11-20'
  && P.dateOf('2026-11-20') === '2026-11-20' && P.dateOf(null) === null);
ok('分→元文本', P.centsText(4300000) === '¥ 43,000' && P.centsText(null) === '—');
ok('两期汇总视图（含 settled 派生）', (() => {
  const v = P.stagesView([{ stage: 'START', expectedCents: 40000000, confirmedCents: 40000000, remainingCents: 0 },
    { stage: 'TOPPING', expectedCents: 60000000, confirmedCents: 0, remainingCents: 60000000 }]);
  return v[0].name === '开工款' && v[0].settled && v[1].name === '封顶款' && !v[1].settled;
})());
ok('材料计划单派生（到货/付款中文态）', (() => {
  const v = P.plansView({ plans: [{ materialPlanId: 1, arrival: '已验收齐全', totalCents: 100, paidCents: 100,
    lines: [{ materialId: 2, name: '钢筋', quantity: 10, received: 10, remaining: 0, unit: '吨' }] }] });
  return v[0].arrival === '已验收齐全' && v[0].lines[0].name === '钢筋';
})());
ok('结算屏视图（费用 0 占位）', P.settleView({ materialsTotalCents: 1, expensesTotalCents: 0 }).expensesNote === '');
ok('错误码十码映射', P.errorText({ code: 1115000006 }).includes('应收')
  && P.errorText({ code: 1115000008 }).includes('同名项目'));
ok('详情适配（p 形状/脱敏缺省）', (() => {
  const v = P.projectView({ id: 5, name: 'x', status: 'IN_PROGRESS', nodes: [{ id: 9, nodeIndex: 1, name: '开工准备', status: 'PENDING' }] });
  return v.id === '5' && v.plan.nodes[0].status === '待开工' && v.phone === '' && v.contractTotal === 0;
})());
ok('API 态可用性判定（http.token 分流）', typeof P.online === 'function');

console.log('[3] 五页接入与降级路径');
const record = read('miniprogram/pages/build/record/index.js');
ok('record 页 API 分支（refreshOnline）', record.includes('refreshOnline'));
ok('record 页动作映射（performOnline + P.perform）', record.includes('performOnline') && record.includes('P.perform'));
ok('record 页失败降级文案（errorText）', record.includes('P.errorText'));
ok('record 页 funding 视图首批隐藏（17 §七：无 funding-plan 在线映射）', typeof P.perform==='function' && !P.perform.toString().includes("'funding-plan'"));
const projects = read('miniprogram/pages/build/projects/index.js');
ok('projects 页列表 API 态 + 新建 API 态', projects.includes('refreshOnline') && projects.includes('submitCreateOnline'));
ok('projects 页降级本地（P.online() 分流后原逻辑保留）', projects.includes('W.allowed()') && projects.includes('R.create'));
const home = read('miniprogram/pages/build/home/index.js');
ok('home 页统计 API 态 + 平台待办（PTPage）', home.includes('refreshEngCount') && home.includes('PTPage'));
const materials = read('miniprogram/pages/build/materials/index.js');
ok('materials 页 recordFlow 归口 record（API 态在 record 页内办理）', materials.includes('engineering-records') && materials.includes('materials\'),{replace:true}'));
const engineering = read('miniprogram/pages/engineering/index.js');
ok('engineering 聚合入口（工程岗重定向 build/home；入口路由全通）', engineering.includes('build/home/index'));
const buildIndex = read('miniprogram/pages/build/index.js');
ok('build/index 经典容器保留本地演示态（recordFlow 重定向 record）', buildIndex.includes('engineering-records'));

console.log('[4] 在线编排红线');
const pf = read('miniprogram/utils/platform-engineering.js');
ok('凭据上传通道随 COS 批降级（1.5 同款遗留口径）', pf.includes('COS 批'));
ok('Long 字符串不透明（不参与算术）', pf.includes("String(r.id)"));
ok('perform 动作映射覆盖核心链', ['prepare','start','node','collect','material-plan','material-terms','arrival','deliver','settle','archive','dunning','node-reopen','node-correct','site','plan'].every(a => pf.includes("case '" + a + "'")));

console.log('\n结果：' + pass + ' 通过，' + fail + ' 失败');
process.exit(fail ? 1 : 0);
