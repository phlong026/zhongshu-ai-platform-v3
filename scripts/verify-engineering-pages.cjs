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

console.log('[5] 行为级防回归（13 轮评审 A/C 项锁定）');
ok('stagesView 文本化（元转换非裸 cents）+ status/url', (() => {
  const v = P.stagesView([{ stage: 'START', expectedCents: 40000000, confirmedCents: 1000000, remainingCents: 39000000 }], s => 'u-' + s);
  return v[0].expected === '¥ 400,000' && v[0].confirmed === '¥ 10,000' && v[0].remaining === '¥ 390,000'
    && v[0].status === '待财务登记收款' && v[0].url === 'u-start';
})());
ok('stagesView 收齐态', P.stagesView([{ stage: 'TOPPING', expectedCents: 1, confirmedCents: 1, remainingCents: 0 }])[0].status === '已收齐');
ok('createTime 归一函数存在且毫秒数字输入不抛错', (() => {
  if (typeof P.timestampDate !== 'function') return false;
  const ms = Date.parse('2026-11-03T10:00:00+08:00');
  return P.timestampDate(ms) === '2026-11-03' && P.timestampDate(String(ms)) === '2026-11-03'
    && P.timestampDate('2026-11-03T02:00:00Z') === '2026-11-03' && P.timestampDate(null) === '' && P.timestampDate('乱串') === '';
})());
ok('pageData 数字 createTime 直通（催收日期不再调 slice 崩页）', (() => {
  const out = P.pageData({ detail: { id: 1, name: 'x', status: 'IN_PROGRESS', nodes: [] }, followups: [{ id: 7, kind: 'COLLECTION', stage: 'START', createTime: Date.parse('2026-11-03T10:00:00+08:00'), note: 'n' }] });
  return out.dunning[0].date === '2026-11-03' && out.dunning[0].id === '7';
})());
ok('recordView 两态同构（fields/actions/form/各区块键全量输出）', (() => {
  const bundle = { detail: { id: 5, name: 'x', status: 'IN_PROGRESS', nodes: [{ id: 9, nodeIndex: 1, name: 'n1', status: 'PENDING' }], documents: [] },
    collections: { stages: [{ stage: 'START', expectedCents: 100, confirmedCents: 0, remainingCents: 100 }], records: [] },
    materials: { plans: [{ materialPlanId: 3, arrival: '部分到货', totalCents: 100, paidCents: 0, remainingCents: 100,
      lines: [{ materialId: 8, name: '钢筋', quantity: 10, received: 4, remaining: 6, unit: '吨', requiredDate: '2026-11-05' }] }] },
    settle: {}, followups: [] };
  const rv = P.recordView(bundle, { view: 'overview', stage: 'start', planId: '', nodeId: '', form: {}, initialized: false, today: '2026-11-03', writeRole: true });
  const keys = ['p','form','fields','actions','installments','plans','settle','dunning','materialFollowups','collections','docs','events','nodeRows','nodePlans','nodeMoney','inspections','arrivals','siteRecords','pending','waiting','engineers','templates','links','next','node','plan','nodeIndex','nodeMaterialUrl','nodeFundingUrl','nodeRecordUrl','nodeUrl','settlementUrl','paceDone','paceTotal','paceOverdue','stage','stageText','partLabel','own','canPay','finance','canAdjust','canTerms','termsLocked','canCorrectNode','canReopen','demo','fileNames'];
  if (!keys.every(k => k in rv)) return false;
  const rm = P.recordView(bundle, { ...{ view: 'material', planId: '3', writeRole: true }, stage: 'start', nodeId: '', form: {}, initialized: false, today: '2026-11-03' });
  return rm.plan && rm.plan.url === '/pages/build/record/index?id=5&view=material&planId=3' && rm.plan.due === '2026-11-05'
    && rm.actions[0].action === 'material-followup' && rm.canAdjust === true;
})());
ok('recordView 只读身份不出动作（finance 线查看不误显可写表单）', (() => {
  const bundle = { detail: { id: 5, name: 'x', status: 'IN_PROGRESS', nodes: [] }, collections: { stages: [{ stage: 'START', expectedCents: 100, confirmedCents: 0, remainingCents: 100 }], records: [] }, materials: { plans: [] }, settle: {}, followups: [] };
  const rv = P.recordView(bundle, { view: 'collection', stage: 'start', writeRole: false });
  return rv.own === false && rv.actions.length === 0 && rv.fields.length === 0;
})());
ok('凭据类动作 perform 前置拦截（不让用户填完表单才失败）', (() => {
  const gated = ['prepare','start','node','site','node-correct','site-correct','collect','arrival','deliver','material-terms'];
  if (!Array.isArray(P.VOUCHER_ACTIONS) || gated.some(a => !P.VOUCHER_ACTIONS.includes(a))) return false;
  return P.perform('collect', '5', { stage: 'start', amount: '1', transaction: 'x', file: null }, null, 'k')
    .then(() => false, e => String(e.msg).includes('COS'));
})());
ok('material-terms basis 只送文件名字符串（不再 JSON.stringify）', (() => {
  const terms = pf.slice(pf.indexOf("case 'material-terms'"));
  return /basis: \(f\.file && f\.file\.name\) \|\| '采购凭据'/.test(terms) && !terms.includes('JSON.stringify(f.file');
})());
ok('listCards 含 progress/next/pace（wxml 消费键补齐）', (() => {
  const cards = P.listCards([{ id: 1, name: 'a', status: 'PENDING_START' }, { id: 2, name: 'b', status: 'IN_PROGRESS', actualStartAt: '2026-10-01' }, { id: 3, name: 'c', status: 'DELIVERED', actualStartAt: '2026-10-01' }]);
  return cards.every(c => 'progress' in c && 'next' in c && 'pace' in c && 'image' in c)
    && cards[0].progress === 0 && cards[0].next === '核查开工条件'
    && cards[1].progress === -1 && cards[1].next === '现场检查与节点验收'
    && cards[2].progress === 100 && cards[2].next === '交付资料与款项收尾';
})());
ok('errorText 兼容 {code,message}（api/http reject 形状）', P.errorText({ code: -1, message: '网络异常，请稍后重试' }) === '网络异常，请稍后重试');
ok('performOnline busy 防重（双击不再双笔）', (() => {
  const m = record.match(/async performOnline\(action,f,node\)\{[\s\S]{0,120}if\(this\.data\.busy\)return/);
  return !!m && /finally\{this\.setData\(\{busy:false\}\);?\}/.test(record);
})());
ok('performOnline 一次性幂等键（__idemKeys 成功释放/失败复用）', record.includes('__idemKeys') && record.includes('delete this.__idemKeys[action]') && record.includes('this.__idemKeys[action]'));
ok('refreshOnline 设 canCreate（projects，856 write+isBoss 口径）', (() => {
  const m = projects.match(/async refreshOnline\(\)\{[\s\S]*?canCreate:[\s\S]*?Access\.isBoss\(a\)[\s\S]*?Job\.has\(a\.role,'engineer'\)[\s\S]*?Job\.has\(a\.role,'project-manager'\)/);
  return !!m;
})());
ok('projects refreshOnline 失败回退离线（__platDown+refresh+toast）', projects.includes('this.__platDown=true') && /refreshOnline\(\)\{[\s\S]*?this\.refresh\(\)/.test(projects));
ok('record refreshOnline 失败回退离线（__platDown+refresh+toast）', record.includes('this.__platDown=true') && /refreshOnline\(reset\)\{[\s\S]*?this\.refresh\(reset\)/.test(record));
ok('API 态隐藏「待验收」筛选（API 恒空）', projects.includes("concat(P.online()&&!this.__platDown?[]:['待验收'])"));
ok('openCreate 父项目选项走在线数据（__platRows）', projects.includes('P.listCards(this.__platRows)'));
ok('record wxml 模板/导入按钮 API 态隐藏（本地演示态功能）', read('miniprogram/pages/build/record/index.wxml').includes("view==='prepare'&&own&&!platEng"));
ok('projects wxml 进度护栏（progress<0 不渲染百分比）', read('miniprogram/pages/build/projects/index.wxml').includes("item.progress>=0"));
ok('lock：engineering-records settle 待办不挂已归档', (() => {
  const rec = read('miniprogram/utils/engineering-records.js');
  return rec.includes("else if(!p.settlement&&!p.archived)add('settle'");
})());
ok('lock：operations plan-submit 角色=engineer/project-manager（856 种子）', (() => {
  const ops = read('miniprogram/utils/operations.js');
  return ops.includes("action==='plan-submit'){assert(X.isBoss(a)||O.has(a.role,'engineer')||O.has(a.role,'project-manager')");
})());

console.log('[6] M07-T02 三期接线核对（在线 prepare 三期表单 + BASE_DONE 收款入口 + 三期文案）');
const emptyBundle = { detail: { id: 5, name: 'x', status: 'PENDING_START', nodes: [] },
  collections: { stages: [], records: [] }, materials: { plans: [] }, settle: {}, followups: [] };
ok('recordView prepare 在线三期表单（六字段+≥0 标签；R1 P1：在线不出保存按钮，提交随 COS 批恢复）', (() => {
  const rv = P.recordView(emptyBundle, { view: 'prepare', stage: 'start', nodeId: '', planId: '', form: {}, initialized: false, today: '2026-11-05', writeRole: true });
  const keys = rv.fields.map(f => f.key);
  return String(keys) === String(['total', 'signAfter', 'baseDone', 'capDone', 'file', 'note'])
    && rv.fields[2].label.indexOf('≥0') >= 0 && rv.actions.length === 0;
})());
ok('prepare 在线提示行（wxml platEng 填写前告知渠道限制）', read('miniprogram/pages/build/record/index.wxml').includes('在线保存随凭据通道开放后可用'));
ok('prepare Σ 前端校验纯函数（字符串精确转分：Σ=总额放行/差 1 元报差额/三位小数拒绝）', (() => {
  if (typeof P.threeStageSumError !== 'function') return false;
  const good = P.threeStageSumError({ total: '10', signAfter: '6', baseDone: '2', capDone: '2' });
  const bad = P.threeStageSumError({ total: '10', signAfter: '6', baseDone: '2', capDone: '1' });
  const threeDp = P.threeStageSumError({ total: '1.01', signAfter: '1.005', baseDone: '', capDone: '' });
  const threeDp2 = P.threeStageSumError({ total: '10', signAfter: '6', baseDone: '2.001', capDone: '2' });
  return good === null && !!bad && String(bad.msg).indexOf('1.00 元') >= 0
    && !!threeDp && !!threeDp2
    && P.threeStageSumError({ total: '10', signAfter: '6', baseDone: '0', capDone: '4' }) === null;
})());
ok('perform prepare 在 VOUCHER 门禁前调 Σ 校验（Σ 错先报差额，Σ 对才报 COS）', (() => {
  const head = pf.slice(0, pf.indexOf('switch (action)'));
  return /if \(action === 'prepare'\)\s*\{\s*const sumErr = threeStageSumError/.test(head);
})());
ok('prepare 输入即校验（R2 P2：四项金额齐后 Σ≠总额 input 即报差额，无保存入口也可达）', (() => {
  const recordJs = read('miniprogram/pages/build/record/index.js');
  return /input\(e\)\{baseHandlers\.input\.call\(this,e\);if\(this\.data\.view==='prepare'&&this\.data\.platEng&&\['total','signAfter','baseDone','capDone'\]\.every/.test(recordJs)
    && recordJs.includes('const err=P.threeStageSumError(this.data.form)');
})());
ok('perform collect/dunning 三期规范枚举（start→SIGN_AFTER/base→BASE_DONE/topping→CAP_DONE）', (() => {
  const collect = pf.slice(pf.indexOf("case 'collect'"), pf.indexOf("case 'dunning'"));
  const dunning = pf.slice(pf.indexOf("case 'dunning'"));
  const map = /f\.stage === 'topping' \? 'CAP_DONE' : f\.stage === 'base' \? 'BASE_DONE' : 'SIGN_AFTER'/;
  return map.test(collect) && map.test(dunning);
})());
ok('record 页 base 期 onLoad 归一（start/base/topping 三期）', /this\.stage=o\.stage==='topping'\?'topping':o\.stage==='base'\?'base':'start'/.test(record));
ok('recordView BASE_DONE 收款入口（collection 放开 base 期出催收+installments 补期次 url）', (() => {
  const baseBundle = { ...emptyBundle, collections: { stages: [{ stage: 'BASE_DONE', expectedCents: 100, confirmedCents: 0, remainingCents: 100 }], records: [] } };
  const rv = P.recordView(baseBundle, { view: 'collection', stage: 'base', nodeId: '', planId: '', form: {}, initialized: true, today: '2026-11-05', writeRole: true });
  const ov = P.recordView(baseBundle, { view: 'overview', stage: 'start', nodeId: '', planId: '', form: {}, initialized: true, today: '2026-11-05', writeRole: true });
  return rv.actions.length === 1 && rv.actions[0].action === 'dunning' && rv.fields.length === 2
    && ov.installments.length === 1 && String(ov.installments[0].url).indexOf('collection&stage=base') > 0;
})());
ok('stagesView 三期 URL 映射（BASE_DONE→base 收款入口可达）', (() => {
  const v = P.stagesView([{ stage: 'BASE_DONE', expectedCents: 100, confirmedCents: 0, remainingCents: 100 }], s => 'u-' + s);
  return v[0].stage === 'base' && v[0].name === '基础完成后期款' && v[0].url === 'u-base';
})());
ok('收款期次三期中性文案（wxml 去两期硬编码）', (() => {
  const wxml = read('miniprogram/pages/build/record/index.wxml');
  return wxml.includes('工程收款期次') && !wxml.includes('两次工程收款') && !wxml.includes('两期收款约定');
})());

console.log('\n结果：' + pass + ' 通过，' + fail + ' 失败');
process.exit(fail ? 1 : 0);
