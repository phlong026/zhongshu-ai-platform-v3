const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { connect, render } = require('./role-page-runtime.cjs');
const auth = require('../miniprogram/api/auth');
const http = require('../miniprogram/api/http');
const model = require('../miniprogram/pages/admin/accounting/model');
const root = path.resolve(__dirname, '../miniprogram'), pagePath = '/pages/admin/accounting/index';
const project = '9007199254740999123', hash = 'a'.repeat(64), nextHash = 'b'.repeat(64);
const base = '/franchise/finance/workbench/projects/' + project + '/accounting';
const clone = value => JSON.parse(JSON.stringify(value));
const tick = () => new Promise(resolve => setImmediate(resolve));
async function flush() { for (let i = 0; i < 8; i++) await tick(); }
const allText = nodes => nodes.map(node => (node.text || '') + allText(node.children || [])).join('');
const fixture = patch => ({ projectId: project, projectName: '甲公司乡墅', basisCode: 'CONTRACT_CONFIRMED_COST_V1',
  basisLabel: '合同金额减已确认项目费用；员工垫付与报销只计一次费用，不含未归属费用和公司公共费用分摊',
  contractId: '51', contractRevenueCents: '10000000', confirmedCostCents: '7000000', netReceivedCents: '6000000',
  companyNetPaidCents: '4000000', cashNetCents: '2000000', grossProfitCents: '3000000', receivableCents: '4000000', payableOutstandingCents: '1234500',
  engineeringSettled: true, sourceHash: hash, latestSnapshot: null, version: 0, state: 'UNCONFIRMED', ready: true, canConfirm: true, ...patch });
function snapshot(row, version = 1) {
  const facts = clone(row); delete facts.latestSnapshot;
  return { id: String(8100 + version), version, sourceHash: row.sourceHash, snapshot: facts, note: '已核对全部原账', confirmedAt: '2026-11-25T15:00:00Z' };
}
function send(request, value, code = 0) { request.success({ statusCode: 200, data: { code, data: value, msg: code ? '核算请求未完成' : '' } }); }
async function setup(role = 'finance', orgType = 'store') {
  const runtime = await connect({ lifecycles: true }), env = { runtime, requests: [], current: fixture(), rows: [], handler: () => undefined };
  env.post = { postId: '42', userId: '701', tenantId: '1', orgId: '31', roleCode: role, orgType, orgName: '甲公司' };
  auth.saveLogin({ accessToken: 'accounting-token', userId: '701' }); auth.savePosts([env.post]); auth.switchPost('42');
  wx.request = request => {
    const route = new URL(request.url).pathname.replace('/admin-api', ''); env.requests.push({ route, request });
    const custom = env.handler(route, request);
    if (custom?.pending) return {};
    let value = custom;
    if (value === undefined) {
      if (route === base + '/confirm') {
        const record = snapshot(env.current, request.data.expectedVersion + 1); env.rows.unshift(record);
        env.current = { ...env.current, version: record.version, latestSnapshot: record, state: 'CURRENT' }; value = record;
      } else if (route === base) value = env.current;
      else if (route === base + '/snapshots') value = { list: env.rows.slice((request.data.pageNo - 1) * 20, request.data.pageNo * 20), total: env.rows.length, pageNo: request.data.pageNo, pageSize: 20 };
      else if (route === '/platform/posts/mine') value = [env.post];
      else value = [];
    }
    setImmediate(() => send(request, clone(value))); return {};
  };
  env.open = async () => { await runtime.callWxMethod('navigateTo', { url: pagePath + '?projectId=' + project }); await flush(); return getCurrentPages().at(-1); };
  env.text = page => allText(render(path.join(root, page.route + '.wxml'), page.data));
  env.posts = () => env.requests.filter(row => row.route === base + '/confirm');
  env.fill = page => { page.input({ detail: { value: '已核对合同、所有材料与人工费用' } }); page.check({ detail: { value: ['complete'] } }); };
  return env;
}

test('分字符串精确展示，不能把现金结余作为项目毛利或缺省为零', () => {
  assert.equal(model.money('9223372036854775807'), '92,233,720,368,547,758.07');
  assert.equal(model.money('-105'), '-1.05'); assert.equal(model.money(null), '—');
  assert.equal(model.money(Number.MAX_SAFE_INTEGER + 1), '—');
  assert.equal(model.id('9223372036854775808'), '');
  const view = model.view(fixture(), project); assert.equal(view.cashText, '20,000.00'); assert.equal(view.profitText, '30,000.00');
  assert.throws(() => model.view(fixture({ projectId: '9' }), project), /不匹配/);
  assert.throws(() => model.view(fixture({ confirmedCostCents: 'garbage' }), project), /金额/);
  assert.equal(model.view(fixture({ contractRevenueCents: null, grossProfitCents: null, contractId: null, ready: false, canConfirm: false }), project).profitText, '—');
});

test('真实页面展示现金、毛利和口径，并有原账与确认记录入口', async () => {
  const env = await setup(), page = await env.open(), text = env.text(page);
  assert.equal(page.data.ready, true); assert.match(text, /30,000\.00/); assert.match(text, /20,000\.00/);
  assert.match(text, /现金结余不表示项目利润/); assert.match(text, /核算确认记录/);
  assert.equal(env.requests.find(row => row.route === base).request.header['X-Post-Id'], '42');
  assert.equal(env.requests.find(row => row.route === base).request.header['tenant-id'], '1');
});

test('项目待付款使用后端金额，历史版本独立保留，旧版本显示未记录', async () => {
  const env = await setup();
  env.rows = [snapshot(fixture({ payableOutstandingCents: '501' }), 2), snapshot(fixture({ payableOutstandingCents: undefined }), 1)];
  const page = await env.open(), text = env.text(page);
  assert.equal(page.data.row.metrics.find(row => row.key === 'payableOutstandingCents')?.value, '12,345.00');
  assert.deepEqual(page.data.history.map(row => row.outstandingText), ['5.01', '—']);
  assert.match(text, /项目待付款/);
  assert.match(text, /12,345\.00/);
  assert.match(text, /5\.01/);
  assert.match(text, /当时待付款[^¥]*¥\s*—/);
  const old = model.view(fixture({ payableOutstandingCents: undefined }), project);
  assert.equal(old.metrics.find(row => row.key === 'payableOutstandingCents')?.value, '—');
  assert.throws(() => model.view(fixture({ payableOutstandingCents: 'broken' }), project), /金额/);
});

test('门店财务可确认，老板和业主只读，总部和业务岗位不能通过门店页面写入', async () => {
  for (const [role, type, readable] of [['boss', 'store', true], ['finance-lead', 'store', true], ['owner', 'store', true], ['finance', 'headquarters', false], ['sales', 'store', false]]) {
    const env = await setup(role, type), page = await env.open(); assert.equal(page.data.ready, readable);
    if (role === 'owner' || role === 'boss') { assert.equal(page.data.row.canConfirm, false); env.fill(page); await page.submit(); assert.equal(env.posts().length, 0); }
    if (!readable) assert.equal(env.requests.filter(row => row.route.startsWith(base)).length, 0);
  }
});

test('老板和业主从核算查看原账，进入同项目只读账本并只读取项目接口', async () => {
  const ledger = '/franchise/finance/projects/' + project + '/ledger';
  for (const role of ['boss', 'owner']) {
    const env = await setup(role);
    env.handler = route => route === ledger ? { projectName: '甲公司乡墅', summary: {}, entries: { list: [], total: 0 } } : undefined;
    await env.open();
    const start = env.requests.length, page = await env.runtime.currentPage();
    await (await page.$('button[bindtap="ledger"]')).tap(); await flush();
    const target = getCurrentPages().at(-1);
    assert.equal(target.route, 'pages/admin/finance-desk/index');
    assert.equal(target.id, project); assert.equal(target.data.view, 'project');
    assert.equal(target.data.ready, true); assert.equal(target.data.projectLedgerReadOnly, true);
    assert.equal(target.openRegister, undefined);
    assert.deepEqual(env.requests.slice(start).map(row => row.route), [ledger]);
  }
});

test('财务核算原账入口保持项目账本，切换身份后旧核算不能打开原账', async () => {
  for (const role of ['finance', 'finance-lead']) {
    const env = await setup(role), page = await env.open(), urls = [];
    wx.navigateTo = request => { urls.push(request.url); request.success?.({}); request.complete?.({}); };
    page.ledger();
    assert.deepEqual(urls, ['/pages/admin/finance-desk/index?view=book&bookScope=' + encodeURIComponent('project:' + project)]);
    auth.savePosts([env.post, { ...env.post, postId: '43', orgId: '32' }]); auth.switchPost('43');
    page.ledger(); assert.equal(urls.length, 1);
  }
});

test('费用完整确认和说明必填，只提交来源版本，不提交客户端计算金额', async () => {
  const env = await setup(), page = await env.open(); await page.submit(); assert.equal(env.posts().length, 0);
  env.fill(page); await page.submit(); await flush();
  const request = env.posts()[0].request;
  assert.deepEqual(Object.keys(request.data).sort(), ['costsComplete', 'expectedVersion', 'note', 'sourceHash']);
  assert.equal(request.data.sourceHash, hash); assert.equal(request.data.expectedVersion, 0);
  assert.ok(request.header['Idempotency-Key']); assert.equal(page.data.row.state, 'CURRENT');
  assert.equal(page.data.history.length, 1); assert.match(env.text(page), /确认版本 V1/);
  await page.submit(); assert.equal(env.posts().length, 1);
});

test('不确定结果冻结原请求，重试保持同一键和版本', async () => {
  const env = await setup(); let pending, fail = true;
  env.handler = (route, request) => route === base + '/confirm' && fail ? (pending = request, { pending: true }) : undefined;
  const page = await env.open(); env.fill(page); const first = page.submit(); await flush(); pending.fail({ errMsg: 'timeout' }); await first;
  assert.equal(page.data.uncertain, true); const original = clone(env.posts()[0].request.data), key = env.posts()[0].request.header['Idempotency-Key'];
  page.input({ detail: { value: '不能修改' } }); assert.notEqual(page.data.note, '不能修改');
  fail = false; await page.retry(); await flush();
  assert.deepEqual(env.posts()[1].request.data, original); assert.equal(env.posts()[1].request.header['Idempotency-Key'], key);
  assert.equal(page.data.row.state, 'CURRENT');
});

test('返回页面会复核当前原账，同时保留未知请求的原键重试入口', async () => {
  const env = await setup(); let pending;
  env.handler = (route, request) => route === base + '/confirm' ? (pending = request, { pending: true }) : undefined;
  const page = await env.open(); env.fill(page); const saving = page.submit(); await flush(); page.onHide();
  assert.equal(page.data.row, null); assert.equal(page.data.ready, false);
  const recorded = snapshot(env.current); env.current = { ...env.current, state: 'CURRENT', version: 1, latestSnapshot: recorded }; env.rows = [recorded];
  send(pending, recorded); await saving; await page.onShow(); await flush();
  assert.equal(page.data.uncertain, true); assert.match(env.text(page), /用原请求重试/);
  env.handler = route => route === base + '/confirm' ? recorded : undefined;
  await page.retry(); await flush(); assert.equal(page.data.uncertain, false);
});

test('版本冲突阻止覆盖，刷新必须重新勾选费用完整确认', async () => {
  const env = await setup(); let pending;
  env.handler = (route, request) => route === base + '/confirm' ? (pending = request, { pending: true }) : undefined;
  const page = await env.open(); env.fill(page); const save = page.submit(); await flush(); send(pending, null, 409); await save;
  assert.equal(page.data.conflict, true); await page.submit(); assert.equal(env.posts().length, 1);
  env.current = fixture({ sourceHash: nextHash }); env.handler = () => undefined;
  await page.load(); await flush(); assert.equal(page.data.checked, false); assert.equal(page.data.row.sourceHash, nextHash);
  await page.submit(); assert.equal(env.posts().length, 1);
});

test('历史分页失败仍重试原页，其他项目的历史不能混入', async () => {
  const env = await setup(); env.rows = Array.from({ length: 21 }, (_, i) => snapshot(env.current, 21 - i)); let pending;
  env.handler = (route, request) => route === base + '/snapshots' && request.data.pageNo === 2 ? (pending = request, { pending: true }) : undefined;
  const page = await env.open(); assert.equal(page.data.history.length, 20);
  const next = page.moreHistory(); await flush(); send(pending, null, 500); await next; assert.equal(page._pageNo, 1);
  const retry = page.moreHistory(); await flush(); assert.equal(pending.data.pageNo, 2);
  send(pending, { list: [snapshot(fixture({ projectId: '9' }))], total: 21, pageNo: 2 }); await retry;
  assert.equal(page.data.history.length, 20); assert.match(page.data.historyError, /不匹配/);
});

test('切岗和离页后旧异步响应不能恢复项目金额，撤权清空全部核算', async () => {
  const env = await setup(); let pending;
  env.handler = (route, request) => route === base ? (pending = request, { pending: true }) : undefined;
  const page = await env.open(); page.onHide(); send(pending, fixture()); await flush(); assert.equal(page.data.row, null);
  const show = page.onShow(); await flush();
  const switched = { ...env.post, postId: '43', orgId: '32' }; auth.savePosts([env.post, switched]); auth.switchPost('43');
  send(pending, fixture()); await show; await flush(); assert.equal(page.data.row, null);
  env.post = switched; env.handler = () => undefined; await page.onShow(); await flush(); assert.equal(page.data.ready, true);
  env.handler = (route, request) => route === base ? (pending = request, { pending: true }) : undefined;
  const denied = page.load(); await flush(); send(pending, null, 403); await denied;
  assert.equal(page.data.row, null); assert.deepEqual(page.data.history, []); assert.equal(page.data.ready, false);
});

test('刷新当前核算废弃旧历史分页，从第一页重新加载且迟到旧页不能插入', async () => {
  const env = await setup(); env.rows = Array.from({ length: 41 }, (_, i) => snapshot(env.current, 41 - i)); let pending;
  env.handler = (route, request) => route === base + '/snapshots' && request.data.pageNo === 2 ? (pending = request, { pending: true }) : undefined;
  const page = await env.open(); assert.equal(page.data.history[0].version, 41);
  const oldPage = page.moreHistory(); await flush(); assert.equal(page.data.historyLoading, true);
  await page.load(); await flush(); assert.equal(page._pageNo, 1); assert.equal(page.data.history.length, 20);
  send(pending, { list: env.rows.slice(20, 40), total: 41, pageNo: 2, pageSize: 20 }); await oldPage;
  assert.equal(page._pageNo, 1); assert.equal(page.data.history[0].version, 41); assert.equal(page.data.history.length, 20);
  assert.equal(env.requests.filter(row => row.route.endsWith('/snapshots') && row.request.data.pageNo === 1).length, 2);
});

test('刷新当前核算在途期间，点击更多不能启动旧页分页', async () => {
  const env = await setup(); env.rows = Array.from({ length: 41 }, (_, i) => snapshot(env.current, 41 - i));
  const page = await env.open(); let pending;
  env.handler = (route, request) => route === base ? (pending = request, { pending: true }) : undefined;
  const loading = page.load(); await flush(); await page.moreHistory();
  assert.equal(env.requests.filter(row => row.route.endsWith('/snapshots') && row.request.data.pageNo === 2).length, 0);
  send(pending, env.current); await loading; await flush();
  assert.equal(page._pageNo, 1); assert.equal(page.data.history[0].version, 41); assert.equal(page.data.history.length, 20);
});

test('现有项目账本接入核算入口，切到公司账时清除项目核算', async () => {
  const env = await setup();
  env.handler = route => route.endsWith('/ledger') ? { summary: {}, entries: [], accounting: fixture(), scope: 'project:' + project }
    : route.endsWith('/books') ? [{ scope: 'project:' + project, name: '甲公司乡墅' }] : undefined;
  const page = { options: { bookScope: 'project:' + project }, data: {wbForm:null,registerType:'',journeyEnabled:true,projectLedgerReadOnly:false,designReceiptForm:null,collectionDeclarations:[],financialStages:[],reimbursementRows:[],workbenchStaff:[],allocationReceipts:[]}, setData(patch) { Object.assign(this.data, patch); } };
  require('../miniprogram/pages/admin/finance-desk/workbench')(page, {}); await page.loadBookLedger();
  assert.equal(page.data.bookAccounting.profitText, '30,000.00'); assert.equal(page.data.bookAccountingUrl, pagePath + '?projectId=' + project);
  const file = path.join(root, 'pages/admin/finance-desk/index.wxml');
  const rendered = allText(render(file, { ...page.data, view: 'book', platformApiMode: true, ready: true }));
  assert.match(rendered, /核算与确认记录/);
  env.handler = route => route.endsWith('/ledger') ? { summary: {}, entries: [], scope: 'company' } : route.endsWith('/books') ? [] : undefined;
  page.options.bookScope = 'company'; await page.loadBookLedger(); assert.equal(page.data.bookAccounting, null); assert.equal(page.data.bookAccountingUrl, '');
});

test('总部只读账本展示同口径且拒绝带写权限或项目不匹配的核算', () => {
  const hq = require('../miniprogram/pages/headquarters/model'), row = fixture({ canConfirm: false });
  row.latestSnapshot = snapshot({ ...row, payableOutstandingCents: '501' }); row.version = 1; row.state = 'STALE';
  const ledger = hq.ledger({ summary: {}, entries: [], accounting: row }, 'project:' + project);
  assert.equal(ledger.accounting.profit, '¥ 30,000.00'); assert.equal(ledger.accounting.latest.profit, '¥ 30,000.00');
  assert.equal(ledger.accounting.latest.outstanding, '¥ 5.01');
  const detail = allText(render(path.join(root, 'pages/headquarters/detail.wxml'), { ready: true, kind: 'ledger', ledger }));
  assert.match(detail, /当时待付款 ¥ 5\.01/);
  delete row.latestSnapshot.snapshot.payableOutstandingCents;
  assert.equal(hq.ledger({ summary: {}, entries: [], accounting: row }, 'project:' + project).accounting.latest.outstanding, '—');
  assert.throws(() => hq.ledger({ summary: {}, entries: [], accounting: fixture() }, 'project:' + project), /不匹配/);
  assert.throws(() => hq.ledger({ summary: {}, entries: [], accounting: row }, 'project:99'), /不匹配/);
});
