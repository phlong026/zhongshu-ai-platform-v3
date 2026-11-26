const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { connect, render } = require('./role-page-runtime.cjs');
const auth = require('../miniprogram/api/auth');
const http = require('../miniprogram/api/http');
const model = require('../miniprogram/pages/admin/payroll/model');
const root = path.resolve(__dirname, '../miniprogram'), pagePath = '/pages/admin/payroll/index';
const base = '/franchise/finance/payroll', hqBase = '/platform/headquarters/companies/31/payroll';
const period = require('../miniprogram/utils/finance-date').day(Date.now()).slice(0, 7);
const project = '9007199254740999123', stage = '9007199254740999111';
const clone = value => JSON.parse(JSON.stringify(value));
const tick = () => new Promise(resolve => setImmediate(resolve));
async function flush() { for (let i = 0; i < 8; i++) await tick(); }
const allText = nodes => nodes.map(node => (node.text || '') + allText(node.children || [])).join('');
const rule = (version = 1, patch = {}) => ({ id: String(7000 + version), companyId: '31', family: 'BUSINESS', version,
  effectiveMonth: period, baseSalaryNote: '底薪按管理层确认表逐人核对', assessmentNote: '按月完成客户跟进记录',
  commissionNote: '按实际约定逐项人工确认提成', evidenceNote: '管理层会议确认单', calculationMode: 'MANUAL_ONLY', createdAt: period + '-10T10:00:00Z', ...patch });
function record(version = 1, patch = {}, facts = {}) {
  const row = { id: String(8000 + version), companyId: '31', userId: '801', family: 'BUSINESS', month: period, version,
    note: '本月人工核算已核对', createdAt: period + '-20T10:00:00Z', ...patch };
  row.snapshot = { companyId: row.companyId, userId: row.userId, userName: '业务同事', family: row.family, month: row.month,
    baseSalaryCents: '300000', commissionCents: '0', adjustmentCents: '0', totalCents: '300000',
    ruleStatus: 'UNCONFIGURED', rule: null, commissions: [], calculationMode: 'MANUAL_ONLY', cashFact: false, ...facts };
  return row;
}
const stageView = () => ({ projectId: project, projectName: '甲公司乡墅工程', stages: [{ stageId: stage, contractId: '51', stageKey: 'STAGE_CUSTOM',
  label: '外墙验收', sequence: 4, trigger: 'NODE_COMPLETED', nodeIndex: 6, planVersion: 3, expectedCents: '2000000', netCollectedCents: '750000' }] });
function send(request, value, code = 0) { request.success({ statusCode: 200, data: { code, data: value, msg: code ? '请求范围或版本不匹配' : '' } }); }
async function setup(role = 'finance', orgType = 'store') {
  const runtime = await connect({ lifecycles: true });
  const env = { runtime, requests: [], rules: [], records: [], handler: () => undefined };
  env.post = { postId: '42', userId: '701', tenantId: '1', orgId: orgType === 'headquarters' ? '10' : '31', roleCode: role, orgType, orgName: '甲公司' };
  env.hq = { readOnly: true, tenantId: '1', headquartersOrgId: '10', postId: '42', roleCode: role, companies: [{ id: '31', name: '甲公司', brandId: '2' }] };
  auth.saveLogin({ accessToken: 'payroll-token', userId: '701' }); auth.savePosts([env.post]); auth.switchPost('42');
  env.context = month => ({ companyId: '31', month, manualOnly: true, canWrite: true,
    rules: model.FAMILIES.map(({ key }) => {
      const rules = env.rules.filter(row => row.family === key), effective = rules.filter(row => row.effectiveMonth <= month)
        .sort((a, b) => b.effectiveMonth.localeCompare(a.effectiveMonth) || b.version - a.version)[0];
      return { family: key, latestVersion: Math.max(0, ...rules.map(row => row.version)), status: effective ? 'CONFIGURED' : 'UNCONFIGURED', effectiveRule: effective || null };
    }), staff: [{ id: '801', name: '业务同事', family: 'BUSINESS' }, { id: '802', name: '工程同事', family: 'ENGINEERING' }], projects: [{ id: project, name: '甲公司乡墅工程' }] });
  env.save = body => {
    const applied = env.rules.find(row => row.id === body.ruleId), commissions = body.commissions.map(line => ({ ...line, amountCents: String(line.amountCents),
      projectName: '甲公司乡墅工程', stage: line.stageId ? stageView().stages.find(row => row.stageId === line.stageId) : null }));
    const commission = body.commissions.reduce((total, line) => total + line.amountCents, 0);
    const row = record(body.expectedVersion + 1, { userId: body.userId, family: body.family, month: body.month, note: body.note },
      { baseSalaryCents: String(body.baseSalaryCents), adjustmentCents: String(body.adjustmentCents), commissionCents: String(commission),
        totalCents: String(body.baseSalaryCents + body.adjustmentCents + commission), commissions, rule: applied || null, ruleStatus: applied ? 'CONFIGURED' : 'UNCONFIGURED' });
    env.records.unshift(row); return row;
  };
  wx.request = request => {
    const route = new URL(request.url).pathname.replace('/admin-api', ''); env.requests.push({ route, request });
    const custom = env.handler(route, request); if (custom?.pending) return {};
    let value = custom;
    if (value === undefined) {
      const local = route.startsWith(hqBase) ? route.slice(hqBase.length) : route.startsWith(base) ? route.slice(base.length) : route;
      const data = request.data || {};
      if (route === '/platform/headquarters/context') value = env.hq;
      else if (route === '/platform/posts/mine') value = [env.post];
      else if (local === '/context') value = env.context(data.month);
      else if (local === '/records' && request.method === 'POST') value = env.save(data);
      else if (local === '/rules' && request.method === 'POST') { value = rule(data.expectedVersion + 1, data); env.rules.unshift(value); }
      else if (local === '/latest') value = { record: env.records.filter(row => row.userId === data.userId && row.family === data.family && row.month === data.month).sort((a, b) => b.version - a.version)[0] || null };
      else if (local === '/projects/' + project + '/stages') value = stageView();
      else if (local === '/records' || local === '/rules' || /^\/records\/\d+\/(versions|history)$/.test(local)) {
        let rows;
        if (local === '/rules') rows = env.rules.filter(row => !data.family || row.family === data.family).sort((a, b) => b.version - a.version);
        else if (local === '/records') {
          const found = new Map(); for (const row of [...env.records].sort((a, b) => b.version - a.version)) {
            if (data.family && row.family !== data.family || data.month && row.month !== data.month) continue;
            const key = row.userId + ':' + row.family + ':' + row.month; if (!found.has(key)) found.set(key, row);
          } rows = [...found.values()];
        } else {
          const anchor = env.records.find(row => row.id === local.split('/')[2]);
          rows = env.records.filter(row => row.userId === anchor?.userId && row.family === anchor?.family && row.month === anchor?.month).sort((a, b) => b.version - a.version);
        }
        value = { list: rows.slice((data.pageNo - 1) * 20, data.pageNo * 20), total: rows.length, pageNo: data.pageNo, pageSize: 20, readOnly: true };
      } else value = [];
    }
    setImmediate(() => send(request, clone(value))); return {};
  };
  env.open = async (query = '') => { await runtime.callWxMethod('navigateTo', { url: pagePath + query }); await flush(); return getCurrentPages().at(-1); };
  env.text = page => allText(render(path.join(root, page.route + '.wxml'), page.data));
  env.posts = () => env.requests.filter(row => row.request.method === 'POST' && row.route.startsWith(base));
  env.fill = async page => {
    page.newRecord(); await page.chooseStaff({ detail: { value: 0 } });
    for (const [key, value] of Object.entries({ base: '3000.01', adjustment: '-0.01', note: '已逐项核对管理层确认的月度金额' })) page.input({ currentTarget: { dataset: { key } }, detail: { value } });
    page.check({ detail: { value: ['confirmed'] } });
  };
  return env;
}

test('金额使用精确的分，规则与记录严格绑定公司、岗位和月份', () => {
  assert.equal(model.cents('3000.01'), 300001); assert.equal(model.cents('-0.01', true), -1);
  for (const value of ['', '1.001', '1e4', '-2', '10000000000.01']) assert.equal(model.cents(value), null);
  assert.equal(model.money('9223372036854775807'), '92,233,720,368,547,758.07');
  assert.equal(model.record(record(), '31').ruleLabel, '规则待配置 · 人工确认');
  assert.throws(() => model.record(record(1, { companyId: '32' }), '31'), /不匹配/);
  assert.throws(() => model.record(record(1, {}, { cashFact: true }), '31'), /不匹配/);
  assert.throws(() => model.record(record(1, {}, { ruleStatus: 'CONFIGURED', rule: rule(1, { family: 'ENGINEERING' }) }), '31'), /生效范围/);
  assert.throws(() => model.rule(rule(1, { calculationMode: 'AUTO' }), '31'), /不匹配/);
});

test('核算详情按当前核算和财务原单分别展示应发、实际已发、未发及历史边界', async () => {
  const env = await setup(), row = record();
  env.records = [row];
  env.handler = route => route === base + '/records/' + row.id ? { ...row, settlement: {
    currentRecordId: row.id, dueCents: '300000', paidCents: '120000', unpaidCents: '180000', overpaidCents: '0',
    grossCents: '150000', refundedCents: '30000', registrationCount: 1, paymentCount: 1 } } : undefined;
  const page = await env.open(); await page.openRecord({ currentTarget: { dataset: { id: row.id } } }); await flush();
  const view = env.text(page); assert.match(view, /应发[\s\S]*3,000\.00/); assert.match(view, /实际已发[\s\S]*1,200\.00/);
  assert.match(view, /未发[\s\S]*1,800\.00/); assert.match(view, /历史付款/);
  assert.match(view, /登记薪酬付款/); assert.equal(env.posts().length, 0);
});

test('实际页面明确规则待配置与人工核算，没有自动工资或发款动作', async () => {
  const env = await setup(), page = await env.open(); assert.equal(page.data.ready, true);
  assert.match(env.text(page), /薪酬规则待配置/); assert.match(env.text(page), /不自动生成工资/); assert.match(env.text(page), /不代表真实发款/);
  page.newRecord(); assert.equal(page.data.form.base, ''); assert.equal(page.data.totalText, '—');
  await page.submit(); assert.equal(env.posts().length, 0);
  const request = env.requests.find(row => row.route === base + '/context').request;
  assert.equal(request.header['X-Post-Id'], '42'); assert.equal(request.header['tenant-id'], '1');
});

const amounts = row => ({ currentRecordId: row.id, dueCents: '300000', paidCents: '0', unpaidCents: '300000', overpaidCents: '0',
  grossCents: '0', refundedCents: '0', registrationCount: 0, paymentCount: 0 });
test('财务由核算记录进入原支出表，固定员工和月份，只登记实际输入金额，双击只出一张原单', async () => {
  const env = await setup(), row = record(), resultId = '9007199254740999881'; env.records = [row];
  const recordPath = base + '/records/' + row.id, payPath = recordPath + '/payments';
  let pending;
  env.handler = (route, req) => route === recordPath ? { ...row, settlement: amounts(row) } :
    route === payPath ? (pending = req, { pending: true }) : undefined;
  const payroll = await env.open(); await payroll.openRecord({ currentTarget: { dataset: { id: row.id } } });
  await payroll.openPayment(); await flush();
  const desk = getCurrentPages().at(-1); assert.equal(desk.route, 'pages/admin/finance-desk/index');
  assert.equal(desk.data.wbForm.payrollRecordId, row.id); assert.equal(desk.data.wbForm.counterparty, row.snapshot.userName);
  assert.equal(desk.data.wbForm.scope, 'company'); assert.equal(desk.data.wbForm.category, '绩效薪酬'); assert.ok(!desk.data.wbForm.amount);
  desk.setData({ 'wbForm.amount': '123.45', 'wbForm.accountName': '公司账户' });
  const save = desk.submitJourneyEntry(); desk.submitJourneyEntry(); await flush();
  assert.equal(env.requests.filter(r => r.route === payPath).length, 1); assert.equal(env.requests.filter(r => r.route.endsWith('/workbench/entries')).length, 0);
  assert.equal(pending.data.amountCents, 12345); assert.equal(pending.data.expectedRegistrationCount, 0); assert.ok(pending.header['Idempotency-Key']);
  assert.equal(pending.data.counterparty, undefined); assert.equal(pending.data.payrollRecordId, undefined);
  send(pending, { id: resultId, payrollRecordId: row.id }); await save; await flush();
  assert.equal(desk.data.journeyResult.id, resultId); assert.equal(desk.data.paymentPending, false); assert.equal(desk.data.wbForm, null);
});

test('旧版本、只读岗位、原单口径缺失不能登记；切岗后迟到的原单信息被丢弃', async () => {
  for (const role of ['boss', 'finance']) {
    const env = await setup(role), row = record(), latest = record(2); env.records = [row];
    const route = base + '/records/' + row.id;
    env.handler = p => p === route ? { ...row, settlement: amounts(role === 'boss' ? row : latest) } : undefined;
    const page = await env.open(); await page.openRecord({ currentTarget: { dataset: { id: row.id } } });
    assert.doesNotMatch(env.text(page), /登记薪酬付款/); page.openPayment(); assert.equal(getCurrentPages().at(-1), page);
    env.handler = p => p === route ? { ...row } : undefined; await page.loadSettlement();
    assert.equal(page.data.settlement, null); assert.match(page.data.settlementError, /原单/);
    let read; env.handler = (p, req) => p === route ? (read = req, { pending: true }) : undefined;
    const loading = page.loadSettlement(); await flush(); page.onHide(); send(read, { ...row, settlement: amounts(row) }); await loading;
    assert.equal(page.data.settlement, null); assert.equal(page.data.detail, null);
  }
});

test('财务付款丢失回执后重进用原登记核验，另一员工回执不能解锁或重复入账', async () => {
  const env = await setup(), row = record(), recordPath = base + '/records/' + row.id, resultId = '9007199254740999892'; env.records = [row];
  let sent, wrong = true; const lookup = [];
  env.handler = (url, request) => {
    if (url === recordPath) return { ...row, settlement: amounts(row) };
    if (url === recordPath + '/payments') { sent = request; return { pending: true }; }
    if (url === recordPath + '/payments/registration-result') { lookup.push(request.data.key);
      return { id: resultId, payrollRecordId: wrong ? '99001' : row.id }; }
  };
  const payroll = await env.open(); await payroll.openRecord({ currentTarget: { dataset: { id: row.id } } });
  await payroll.openPayment(); await flush(); const desk = getCurrentPages().at(-1);
  desk.setData({ 'wbForm.amount': '20.50', 'wbForm.accountName': '公司账户' });
  const save = desk.submitJourneyEntry(); await flush(); sent.fail({ errMsg: '网络中断' }); await save;
  assert.equal(desk.data.paymentPending, true); const key = sent.header['Idempotency-Key'];
  desk.onHide(); desk.onShow(); await flush(); assert.equal(desk.data.paymentPending, true); assert.equal(desk.data.journeyResult, null);
  wrong = false; await desk.retryPendingPayment(); await flush(); assert.equal(desk.data.journeyResult.id, resultId);
  assert.equal(desk.data.paymentPending, false); assert.ok(lookup.every(k => k === key)); assert.equal(env.posts().length, 1);
});

test('财务可录入，老板只读，总部必须走独立只读模式', async () => {
  for (const [role, type, ready, write] of [['finance-lead', 'store', true, true], ['boss', 'store', true, false], ['owner', 'store', true, false], ['sales', 'store', false, false], ['finance', 'headquarters', false, false]]) {
    const env = await setup(role, type), page = await env.open(); assert.equal(page.data.ready, ready);
    page.newRecord(); assert.equal(!!page.data.form, write); page.newRule(); assert.equal(!!page.data.ruleForm, write);
    await page.submit(); assert.equal(env.posts().length, 0);
    if (!ready) assert.equal(env.requests.filter(row => row.route.startsWith(base)).length, 0);
  }
});

test('未配置规则仍需逐人填写并确认，仅提交人工金额且保留幂等键', async () => {
  const env = await setup(), page = await env.open(); await env.fill(page);
  page.check({ detail: { value: [] } }); await page.submit(); assert.equal(env.posts().length, 0);
  page.check({ detail: { value: ['confirmed'] } }); await page.submit(); await flush();
  const request = env.posts()[0].request;
  assert.equal(request.data.baseSalaryCents, 300001); assert.equal(request.data.adjustmentCents, -1);
  assert.equal(request.data.ruleId, null); assert.equal(request.data.userId, '801'); assert.equal(request.data.manuallyConfirmed, true);
  assert.equal(request.data.expectedVersion, 0); assert.deepEqual(request.data.commissions, []); assert.ok(request.header['Idempotency-Key']);
  assert.equal(page.data.rows[0].totalText, '3,000.00'); assert.match(env.text(page), /规则待配置/);
});

test('已有员工月份核算使用专门 latest 读取，修正保留真实版本和新说明', async () => {
  const env = await setup(); env.records = [record(5)];
  env.handler = route => route === base + '/records' ? { list: [], total: 0, pageNo: 1 } : undefined;
  const page = await env.open(); page.newRecord(); await page.chooseStaff({ detail: { value: 0 } });
  assert.equal(page.data.form.expectedVersion, 5); assert.equal(page.data.form.base, '3000.00'); assert.equal(page.data.form.note, '');
  assert.equal(page.data.form.confirmed, false); assert.equal(env.requests.filter(row => row.route === base + '/latest').length, 1);
});

test('管理层规则须完整填写并再次确认，生成可查询的新版本', async () => {
  const env = await setup(), page = await env.open(); page.newRule(); await page.submit(); assert.equal(env.posts().length, 0);
  for (const [key, value] of Object.entries({ baseSalaryNote: '管理层底薪约定', assessmentNote: '月度考核表', commissionNote: '逐项目人工确认', evidenceNote: '会议确认记录' })) page.input({ currentTarget: { dataset: { key } }, detail: { value } });
  page.check({ detail: { value: ['confirmed'] } }); await page.submit(); await flush();
  assert.equal(env.posts()[0].request.data.expectedVersion, 0); assert.equal(env.posts()[0].request.data.managementConfirmed, true);
  assert.equal(page.data.activeRule.status, 'CONFIGURED');
  await page.changeKind({ currentTarget: { dataset: { kind: 'rules' } } }); await flush();
  assert.match(env.text(page), /管理层底薪约定/); assert.match(env.text(page), /规则 V1/);
});

test('提成可关联动态付款节点及净回款，长 ID 不舍入、金额仍由人工填写', async () => {
  const env = await setup(), page = await env.open(); await env.fill(page); page.addLine(); const key = page.data.form.commissions[0].key;
  await page.chooseProject({ currentTarget: { dataset: { key } }, detail: { value: 0 } });
  page.chooseStage({ currentTarget: { dataset: { key } }, detail: { value: 1 } });
  for (const [field, value] of Object.entries({ amount: '120.00', basis: '按管理层确认的外墙验收提成表' })) page.lineInput({ currentTarget: { dataset: { key, field } }, detail: { value } });
  assert.match(env.text(page), /外墙验收/); assert.match(env.text(page), /7,500.00/); await page.submit(); await flush();
  const line = env.posts()[0].request.data.commissions[0]; assert.equal(line.projectId, project); assert.equal(line.stageId, stage); assert.equal(line.amountCents, 12000);
  assert.equal(page.data.rows[0].commissionText, '120.00');
  await page.openRecord({ currentTarget: { dataset: { id: page.data.rows[0].id } } });
  assert.match(env.text(page), /核算时该节点净回款/); assert.match(env.text(page), /7,500.00/);
});

test('其他公司的项目、未读取的付款节点以及转岗员工不能从编辑模型提交', async () => {
  const env = await setup(), ctx = model.context(env.context(period), '31', period);
  const form = { userId: '801', base: '1', adjustment: '0', note: '核对', confirmed: true, expectedVersion: 0, commissions: [] };
  assert.throws(() => model.recordPayload({ ...form, userId: '999' }, ctx, 'BUSINESS'), /员工/);
  assert.throws(() => model.recordPayload(form, ctx, 'ENGINEERING'), /员工/);
  for (const line of [{ projectId: '9', amount: '1', basis: '确认' }, { projectId: project, stageId: stage, stages: [], amount: '1', basis: '确认' }]) {
    assert.throws(() => model.recordPayload({ ...form, commissions: [line] }, ctx, 'BUSINESS'), /提成/);
  }
});

test('不确定保存结果冻结原键原金额，离页回来先复核公司再重试', async () => {
  const env = await setup(); let pending, block = true;
  env.handler = (route, request) => route === base + '/records' && request.method === 'POST' && block ? (pending = request, { pending: true }) : undefined;
  const page = await env.open(); await env.fill(page); const first = page.submit(); await flush(); pending.fail({ errMsg: 'timeout' }); await first;
  assert.equal(page.data.uncertain, true); const body = clone(env.posts()[0].request.data), key = env.posts()[0].request.header['Idempotency-Key'];
  page.input({ currentTarget: { dataset: { key: 'base' } }, detail: { value: '999' } }); assert.notEqual(page.data.form.base, '999');
  page.onHide(); assert.equal(page.data.context, null); assert.equal(page.data.form, null); await page.onShow(); await flush();
  assert.equal(page.data.uncertain, true); assert.match(env.text(page), /用原请求重试/);
  block = false; await page.retry(); await flush(); assert.equal(page.data.uncertain, false);
  assert.deepEqual(env.posts()[1].request.data, body); assert.equal(env.posts()[1].request.header['Idempotency-Key'], key);
});

test('版本冲突禁止覆盖，重新读取规则和员工版本后才能录入', async () => {
  const env = await setup(); let pending;
  env.handler = (route, request) => route === base + '/records' && request.method === 'POST' ? (pending = request, { pending: true }) : undefined;
  const page = await env.open(); await env.fill(page); const saving = page.submit(); await flush(); send(pending, null, 409); await saving;
  assert.equal(page.data.conflict, true); await page.submit(); assert.equal(env.posts().length, 1);
  await page.load(); await flush(); assert.equal(page.data.form, null); assert.equal(page.data.conflict, false);
});

test('核算历史引用当时规则，查询新规则不改写旧版本，版本分页按员工与月份校验', async () => {
  const env = await setup(); env.rules = [rule(2)]; env.records = Array.from({ length: 21 }, (_, i) => record(21 - i, {}, { ruleStatus: 'CONFIGURED', rule: rule(1) }));
  const page = await env.open(); await page.openRecord({ currentTarget: { dataset: { id: page.data.rows[0].id } } });
  assert.equal(page.data.versions.length, 20); assert.equal(page.data.detail.rule.version, 1); assert.match(env.text(page), /本版本引用的规则 V1/);
  env.handler = (route, request) => route.endsWith('/versions') && request.data.pageNo === 2 ? { list: [record(1, { userId: '999' })], total: 21, pageNo: 2 } : undefined;
  await page.moreVersions(); assert.equal(page._versionPage, 1); assert.equal(page.data.versions.length, 20); assert.match(page.data.versionsError, /范围不匹配/);
  env.handler = () => undefined; await page.moreVersions(); assert.equal(page.data.versions.length, 21);
  page.viewVersion({ currentTarget: { dataset: { id: '8001' } } }); assert.equal(page.data.detail.version, 1);
});

test('刷新列表废弃旧在途分页，刷新读取期间点击更多不会跳过第一页', async () => {
  const env = await setup(); env.records = Array.from({ length: 41 }, (_, i) => record(1, { id: String(9000 + i), userId: String(1000 + i) }));
  const page = await env.open(); assert.equal(page.data.rows.length, 20); let oldPage, context;
  env.handler = (route, request) => route === base + '/records' && request.data.pageNo === 2 ? (oldPage = request, { pending: true }) : undefined;
  const old = page.more(); await flush();
  env.handler = (route, request) => route === base + '/context' ? (context = request, { pending: true }) : undefined;
  const loading = page.load(); await flush(); await page.more();
  assert.equal(env.requests.filter(row => row.route === base + '/records' && row.request.data.pageNo === 2).length, 1);
  send(context, env.context(period)); await loading; await flush();
  send(oldPage, { list: env.records.slice(20, 40), total: 41, pageNo: 2 }); await old;
  assert.equal(page._pageNo, 1); assert.equal(page.data.rows.length, 20); assert.equal(page.data.rows[0].id, '9000');
});

test('离页或切岗后的旧响应不恢复工资，服务端撤权清空核算与编辑内容', async () => {
  const env = await setup(); env.records = [record()]; let pending;
  env.handler = (route, request) => route === base + '/context' ? (pending = request, { pending: true }) : undefined;
  const page = await env.open(); page.onHide(); send(pending, env.context(period)); await flush(); assert.equal(page.data.context, null);
  const showing = page.onShow(); await flush(); const other = { ...env.post, postId: '43', orgId: '32' }; auth.savePosts([env.post, other]); auth.switchPost('43');
  send(pending, env.context(period)); await showing; assert.equal(page.data.ready, false); assert.deepEqual(page.data.rows, []);
  auth.switchPost('42'); env.handler = () => undefined; await page.onShow(); await flush(); assert.equal(page.data.rows.length, 1);
  env.handler = (route, request) => route === base + '/context' ? (pending = request, { pending: true }) : undefined;
  const refresh = page.load(); await flush(); send(pending, null, 403); await refresh;
  assert.equal(page.data.ready, false); assert.equal(page.data.context, null); assert.deepEqual(page.data.rows, []); assert.equal(page._pending, null);
});

test('总部管理员和财务复用页面只读，全部读取走目标公司总部接口', async () => {
  for (const role of ['hq-admin', 'finance', 'finance-lead']) {
    const env = await setup(role, 'headquarters'); env.records = [record()]; env.rules = [rule()]; const page = await env.open('?headquarters=1&companyId=31');
    assert.equal(page.data.ready, true); assert.equal(page.data.context.canWrite, false); assert.deepEqual(page.data.staff, []);
    assert.match(env.text(page), /总部只读 · 甲公司/); assert.doesNotMatch(env.text(page), /登记核算|登记规则/);
    await page.openRecord({ currentTarget: { dataset: { id: '8001' } } }); assert.equal(page.data.versions.length, 1);
    assert.ok(env.requests.some(row => row.route === hqBase + '/records/8001/history'));
    page.newRecord(); page.newRule(); await page.chooseStaff({ detail: { value: 0 } }); await page.loadStages('1', project, stage); await page.submit(); await page.retry();
    assert.equal(page.data.form, null); assert.equal(page.data.ruleForm, null); assert.equal(env.requests.filter(row => row.route.startsWith(base)).length, 0);
    page.closeDetail(); await page.changeKind({ currentTarget: { dataset: { kind: 'rules' } } }); assert.equal(page.data.rows[0].version, 1);
  }
});

test('总部链接必须重新校验当前租户、任职和目标公司，旧设计行政无权使用', async () => {
  for (const patch of [{ tenantId: '2' }, { postId: '99' }, { headquartersOrgId: '32' }, { roleCode: 'finance' }, { companies: [] }, { readOnly: false }]) {
    const env = await setup('hq-admin', 'headquarters'); Object.assign(env.hq, patch); const page = await env.open('?headquarters=1&companyId=31');
    assert.equal(page.data.ready, false); assert.equal(env.requests.filter(row => row.route.startsWith(hqBase)).length, 0);
  }
  const env = await setup('hq-design-admin', 'headquarters'), page = await env.open('?headquarters=1&companyId=31');
  assert.equal(page.data.ready, false); assert.equal(env.requests.filter(row => row.route.startsWith('/platform/headquarters')).length, 0);
});

test('总部公司撤权后重新进入即清空历史，不能继续用普通写口或员工候选', async () => {
  const env = await setup('finance', 'headquarters'); env.records = [record()]; const page = await env.open('?headquarters=1&companyId=31');
  await page.openRecord({ currentTarget: { dataset: { id: '8001' } } }); page.onHide(); env.hq.companies = [];
  await page.onShow(); await flush(); assert.equal(page.data.ready, false); assert.equal(page.data.detail, null); assert.deepEqual(page.data.versions, []);
  assert.equal(env.requests.filter(row => row.route.startsWith(base)).length, 0);
});

test('既有财务和老板页面提供真实薪酬入口，总部财务不混入门店写页面', async () => {
  const env = await setup();
  const financeFile = path.join(root, 'pages/admin/finance-desk/index.wxml');
  const data = { view: 'desk', ready: true, platformApiMode: true, financeRefreshing: false, platformFinance: {}, platformTodos: {},
    deskHome: { hq: false, name: '财务', period, title: '财务工作台', metrics: [], shortcuts: [], tasks: [], focus: [] } };
  const rendered = render(financeFile, data); assert.match(allText(rendered), /薪酬核算/);
  data.deskHome.hq = true; assert.doesNotMatch(allText(render(financeFile, data)), /业务与工程 · 规则版本及人工核算记录/);
  const boss = render(path.join(root, 'pages/admin/finance/index.wxml'), { ready: true, desk: { period, periods: [], business: { available: false } } });
  assert.match(allText(boss), /薪酬核算记录/);
  assert.equal(env.requests.length, 0);
});
