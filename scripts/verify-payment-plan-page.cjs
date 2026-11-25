const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const { createRequire } = require('node:module');
const model = require('../miniprogram/pages/workflow/payment-plan/model');
const { render } = require('./role-page-runtime.cjs');
const root = path.resolve(__dirname, '..'), copy = value => JSON.parse(JSON.stringify(value));
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
const event = (key, value, row) => ({ currentTarget: { dataset: { key, row } }, detail: { value } });
const tap = dataset => ({ currentTarget: { dataset } });
const flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children || [])]);
function plan(patch = {}) {
  return { contract: { id: '91', companyId: '9', customerId: '42', totalCents: 10000, revision: 3, status: 'WAIT_SIGN', paymentPlanVersion: 1 },
    customerName: '本店客户', canEdit: true, stages: [
      { stageId: '900719925474099991', label: '备料款', amountCents: 6000, collectedCents: 3000, trigger: 'BEFORE_START', sequence: 1 },
      { stageId: '900719925474099992', label: '完工款', amountCents: 4000, collectedCents: 0, trigger: 'ON_FINISH', sequence: 2 }
    ], ...patch };
}
function harness(options = {}) {
  let definition, generation = 1, token = 'token', key = 0;
  let post = { postId: '7', orgId: '9', orgType: 'store', roleCode: options.role || 'finance' };
  const h = { state: plan(options.state), calls: [], navigations: [], toasts: [], overrides: options.api || {} };
  const api = Object.fromEntries(['get', 'prepare', 'save', 'create', 'versions', 'draft', 'saveDraft'].map(name => [name, async (...args) => {
    h.calls.push({ name, args: copy(args) });
    if (h.overrides[name]) return h.overrides[name](...args);
    if (name === 'get') return copy(h.state);
    if (name === 'draft') return copy(h.draftState || { customerId: '42', revision: 0, totalCents: null, stages: [], note: '' });
    if (name === 'saveDraft') {
      const body = args[1]; let allocated = 0;
      h.draftState = { customerId: args[0], revision: body.revision + 1, totalCents: body.totalCents, note: body.note,
        stages: body.stages.map((row, index) => { const amountCents = row.amountCents ?? (index === body.stages.length - 1 ? body.totalCents - allocated : model.ratioAmount(body.totalCents, row.ratioBps)); allocated += amountCents; return { ...row, amountCents, sequence: index + 1 }; }) };
      return copy(h.draftState);
    }
    if (name === 'prepare') return { contracts: [], requirements: [{ id: '31' }], quotes: [{ totalCents: 10000, customerName: '本店客户' }] };
    if (name === 'versions') return [{ version: 1, contract_revision: 3, total_cents: 10000, reason: '创建', created_at: '2026-11-25',
      stages: [{ id: '900719925474099991', label: '原约定', amount_cents: 10000, ratio_bps: 10000, trigger_type: 'ON_SIGN', sequence: 1 }] }];
    h.state.contract.revision += 1;
    return { contractId: '91' };
  }]));
  const http = { token: () => token, currentPostId: () => post?.postId || '' };
  const file = path.join(root, 'miniprogram/pages/workflow/payment-plan/index.js'), real = createRequire(file);
  const mocks = {
    '../../../api/http': http, '../../../api/auth': { currentApiPost: () => post },
    '../../../utils/api-scope': () => JSON.stringify([generation, post?.postId, post?.orgId]),
    '../../../utils/page-navigation': { open: url => h.navigations.push({ url }), back: options => h.navigations.push({ back: options }) },
    '../../../utils/platform-crm': { commandKey: prefix => prefix + '-' + ++key }, './api': api
  };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: value => definition = value,
    require: name => name in mocks ? mocks[name] : real(name), console,
    wx: { showToast: value => h.toasts.push(value), showModal: value => h.modal = value }
  }, { filename: file });
  const page = h.page = { ...definition, data: copy(definition.data), setData(patch) { Object.assign(this.data, copy(patch)); } };
  page.onLoad(options.route || (options.create ? { customerId: '42' } : { contractId: '91' }));
  h.open = () => page.onShow(); h.callsFor = name => h.calls.filter(call => call.name === name);
  h.switchSession = (next = {}) => { generation += 1; post = { ...post, postId: String(7 + generation), ...next }; };
  h.logout = () => { token = ''; };
  h.edit = () => { page.startEdit(); page.input(event('reason', '已与客户确认新付款安排')); };
  h.nodes = () => flatten(render(file.replace(/\.js$/, '.wxml'), page.data));
  return h;
}

test('财务按合同读取付款计划，不触发客户列表、需求、预算；服务端只读不能编辑', async () => {
  const h = harness({ state: { canEdit: false } }); await h.open();
  assert.deepEqual(h.calls.map(c => c.name), ['get']); assert.equal(h.page.data.customerName, '本店客户');
  h.edit(); await h.page.submit();
  assert.equal(h.page.data.editing, false); assert.equal(h.callsFor('save').length, 0);
  assert.equal(h.nodes().some(n => n.attrs?.bindtap === 'startEdit'), false);
  assert.equal(h.nodes().some(n => n.tag === 'input'), false);
  h.state.contract.status = 'VOIDED'; h.state.canEdit = true; await h.page.load();
  assert.equal(h.page.data.context.canEdit, false);
});

test('创建合同按任意期数提交统一比例，保留需求引用且不发送固定三期字段', async () => {
  const h = harness({ create: true, role: 'sales' }); await h.open();
  h.page.input(event('ratio', '30', 'new-1')); h.page.addRow();
  h.page.input(event('ratio', '70', 'new-2')); h.page.input(event('label', '主体封顶款', 'new-2'));
  h.page.trigger({ currentTarget: { dataset: { row: 'new-2' } }, detail: { value: '2' } });
  h.page.input(event('nodeIndex', '3', 'new-2')); await h.page.submit();
  const body = h.callsFor('create')[0].args[0];
  assert.equal(body.customerId, '42'); assert.equal(body.requirementId, '31'); assert.equal(body.totalCents, 10000);
  assert.deepEqual(body.paymentStages.map(s => [s.stageId, s.ratioBps]), [[null, 3000], [null, 7000]]);
  assert.equal(body.paymentStages[1].trigger, 'NODE_COMPLETED'); assert.equal(body.paymentStages[1].nodeIndex, 3);
  assert.ok(['signAfterCents', 'baseDoneCents', 'capDoneCents'].every(key => !(key in body)));
  assert.equal(h.page.data.editing, false); assert.equal(h.page.contractId, '91');
});

test('创建前资料加载失败和非销售岗位均不能提交空上下文合同', async () => {
  const h = harness({ create: true, role: 'sales', api: { prepare: async () => { throw { code: 400, msg: '需求资料不可用' }; } } });
  await h.open(); await h.page.submit(); assert.equal(h.page.data.ready, false); assert.equal(h.callsFor('create').length, 0);
  for (const role of ['finance', 'owner', 'engineering', 'hq-admin']) {
    const blocked = harness({ create: true, role }); await blocked.open(); await blocked.page.submit();
    assert.equal(blocked.calls.length, 0); assert.equal(blocked.page.data.ready, false);
  }
});

test('已有有效合同转到原计划，避免重复创建；合同和公司不匹配时拒绝展示', async () => {
  const h = harness({ create: true, role: 'sales', api: { prepare: async () => ({ contracts: [{ contract: { id: '91', customerId: '42', status: 'WAIT_SIGN' } }], requirements: [], quotes: [] }) } });
  await h.open(); assert.equal(h.page.contractId, '91'); assert.equal(h.page.data.creating, false); assert.equal(h.callsFor('create').length, 0);
  for (const patch of [{ id: '92' }, { companyId: '10' }, { customerId: 'not-a-customer' }]) {
    const denied = harness(); Object.assign(denied.state.contract, patch); await denied.open();
    assert.equal(denied.page.data.ready, false); assert.equal(denied.page.data.context, null); assert.match(denied.page.data.error, /不匹配/);
  }
});

test('调整和重排保留长stageId，已收节点不能删除，最多20期', async () => {
  const h = harness(); await h.open(); h.edit();
  h.page.removeRow(tap({ row: '900719925474099991' })); assert.equal(h.page.data.form.rows.length, 2);
  h.page.moveRow(tap({ row: '900719925474099992', direction: -1 })); await h.page.submit();
  const body = h.callsFor('save')[0].args[1];
  assert.equal(body.revision, 3); assert.deepEqual(body.stages.map(s => s.stageId), ['900719925474099992', '900719925474099991']);
  assert.deepEqual(body.stages.map(s => s.amountCents), [4000, 6000]); assert.ok(body.stages.every(s => s.ratioBps === undefined));
  h.edit(); for (let i = 0; i < 30; i += 1) h.page.addRow(); assert.equal(h.page.data.form.rows.length, 20);
  h.page.removeRow(tap({ row: h.page.data.form.rows.at(-1).key })); assert.equal(h.page.data.form.rows.length, 19);
});

test('保存结果未知时冻结原请求和幂等键，重试不接受已改输入或重复命令', async () => {
  const h = harness({ api: { save: async () => { throw { code: -1, msg: 'timeout' }; } } }); await h.open(); h.edit();
  h.page.input(event('label', '客户确认的备料款', '900719925474099991')); await h.page.submit();
  assert.equal(h.page.data.uncertain, true); h.page.input(event('total', '999')); h.page.addRow();
  h.page.navigationBack(); assert.equal(h.navigations.length, 0);
  await h.page.submit(); assert.equal(h.callsFor('save').length, 1);
  h.overrides.save = async () => ({ contractId: '91' }); await h.page.runPending();
  assert.deepEqual(h.callsFor('save')[1].args, h.callsFor('save')[0].args);
  assert.equal(h.callsFor('save')[1].args[1].totalCents, 10000); assert.equal(h.page.data.uncertain, false);
});

test('全额退款后的原收款节点仍有财务引用，不因净已收为零而允许删除', async () => {
  const h = harness(); h.state.stages[0].collectedCents = 0; h.state.stages[0].grossCents = 3000;
  h.state.stages[0].refundedCents = 3000; await h.open(); h.edit();
  assert.equal(h.page.data.summary.rows[0].removable, false);
  h.page.removeRow(tap({ row: '900719925474099991' }));
  assert.equal(h.page.data.form.rows.length, 2); assert.equal(h.page.data.form.rows[0].stageId, '900719925474099991');
});

test('明确拒绝保留可修正草稿；版本冲突载入最新后才可继续', async () => {
  const h = harness({ api: { save: async () => { throw { code: 400, msg: '此节点已有到账登记，不能删除' }; } } }); await h.open(); h.edit();
  await h.page.submit(); assert.equal(h.page.data.uncertain, false); assert.equal(h.page.data.dirty, true); assert.match(h.page.data.error, /到账登记/);
  h.overrides.save = async () => { throw { code: 1099000001, msg: '版本已变化' }; }; await h.page.submit();
  assert.equal(h.page.data.conflict, true); h.page.input(event('total', '200')); assert.equal(h.page.data.form.total, '100.00');
  h.state.contract.revision = 4; h.page.reloadLatest(); h.modal.success({ confirm: true }); await tick();
  assert.equal(h.page.data.conflict, false); assert.equal(h.page.data.context.revision, 4); assert.equal(h.page.data.dirty, false);
});

test('写开始前挂起的同会话GET不能覆盖已保存结果或重置新版本', async () => {
  const h = harness(); await h.open(); const old = deferred(), stale = copy(h.state);
  h.overrides.get = () => old.promise; const reading = h.page.load(); h.edit();
  delete h.overrides.get; await h.page.submit(); assert.equal(h.page.data.context.revision, 4);
  old.resolve(stale); await reading; assert.equal(h.page.data.context.revision, 4);
  h.overrides.get = async () => stale; await h.page.load(); assert.equal(h.page.data.context.revision, 4);
});

test('同岗位刷新出现新版本保留草稿并要求重载，失去写权清除编辑状态', async () => {
  const h = harness(); await h.open(); h.edit(); h.page.input(event('label', '未保存的约定', '900719925474099991'));
  h.state.contract.revision = 4; await h.page.load();
  assert.equal(h.page.data.form.rows[0].label, '未保存的约定'); assert.equal(h.page.data.conflict, true);
  h.state.canEdit = false; await h.page.load();
  assert.equal(h.page.data.editing, false); assert.equal(h.page.data.dirty, false); assert.equal(h.page.data.form.rows[0].label, '备料款');
});

test('切岗后的迟到读取和写入不回填客户信息或触发另一身份刷新', async () => {
  const h = harness(), waiting = deferred(); await h.open(); h.edit();
  h.overrides.save = () => waiting.promise; const saving = h.page.submit(); h.switchSession({ orgId: '10' });
  const next = deferred(); h.overrides.get = () => next.promise; const opening = h.open();
  assert.equal(h.page.data.customerName, ''); assert.equal(h.page.data.form, null);
  waiting.resolve({ contractId: '91' }); await saving; assert.equal(h.callsFor('get').length, 2); assert.equal(h.toasts.length, 0);
  h.switchSession({ orgId: '11' }); next.resolve(copy(h.state)); await opening;
  assert.equal(h.page.data.customerName, ''); assert.equal(h.page.data.ready, false);
});

test('退出登录、领域撤权和合同不可读会清理草稿、历史及待确认命令', async () => {
  for (const code of [401, 403, 404, 1110000000, 1110000001, 1113000004]) {
    const h = harness(); await h.open(); h.edit(); await h.page.toggleHistory();
    h.overrides.get = async () => { throw { code, msg: '当前合同不可读' }; }; await h.page.load();
    assert.equal(h.page.data.customerName, ''); assert.equal(h.page.data.form, null); assert.equal(h.page.data.history.length, 0); assert.equal(h.page._pending, null);
  }
  const h = harness(); await h.open(); h.edit(); h.logout(); await h.open(); assert.equal(h.page.data.context, null); assert.equal(h.page.data.form, null);
});

test('变更记录只读，离开后的历史响应失效，返回会重载而不留下永久加载态', async () => {
  const h = harness(); await h.open(); await h.page.toggleHistory();
  assert.equal(h.page.data.history[0].rows[0].stageId, '900719925474099991');
  h.page.viewVersion(tap({ version: 1 })); assert.equal(h.page.data.selectedVersion.rows[0].label, '原约定');
  assert.equal(h.nodes().some(n => n.tag === 'input'), false);
  const delayed = deferred(); h.overrides.versions = () => delayed.promise; const reading = h.page.loadHistory(); h.page.onHide();
  delayed.resolve([]); await reading; assert.equal(h.page.data.historyLoading, false);
  delete h.overrides.versions; await h.open(); await tick(); assert.equal(h.page.data.history.length, 1); assert.equal(h.page.data.historyLoading, false);
  h.overrides.versions = async () => { throw { code: 403, msg: '权限已撤销' }; }; await h.page.loadHistory();
  assert.equal(h.page.data.context, null); assert.equal(h.page.data.selectedVersion, null);
});

test('原基础/封顶条件可保留或改新条件，新增节点不能选择旧专用条件，0元行不编造比例', async () => {
  const h = harness(); h.state.stages[0].trigger = 'LEGACY_BASE_DONE'; h.state.stages[0].stage = 'BASE_DONE';
  h.state.stages[1].trigger = 'LEGACY_TOPPING'; h.state.stages[1].stage = 'CAP_DONE';
  await h.open(); h.edit(); assert.equal(h.page.data.form.mode, 'AMOUNT');
  assert.equal(h.page.data.summary.rows[0].triggerLabel, '基础完工（原约定）');
  await h.page.submit(); assert.equal(h.callsFor('save')[0].args[1].stages[1].trigger, 'LEGACY_TOPPING');
  h.edit(); h.page.trigger({ currentTarget: { dataset: { row: '900719925474099991' } }, detail: { value: '2' } });
  h.page.input(event('nodeIndex', '3', '900719925474099991')); h.page.addRow();
  assert.equal(h.page.data.summary.rows.at(-1).triggerChoices.length, 6);
  h.state.stages[1].amountCents = 0; await h.page.load({ replace: true }); h.edit();
  assert.equal(h.page.data.form.rows[1].amount, '0.00'); assert.equal(h.page.data.form.rows[1].ratio, ''); assert.equal(h.page.data.summary.valid, false);
  assert.ok(h.nodes().some(n => n.text?.includes('原计划未约定此期金额')));
});

test('付款计划API使用合同专用详情、历史和更新路径及原幂等键', async () => {
  const calls = [], file = path.join(root, 'miniprogram/pages/workflow/payment-plan/api.js'), module = { exports: {} };
  const http = { get: async (...args) => { calls.push(['get', ...args]); return []; }, post: async (...args) => { calls.push(['post', ...args]); return {}; } };
  const sales = Object.fromEntries(['contracts', 'requirements', 'quotes'].map(name => [name, { list: async id => { calls.push([name, id]); return []; } }]));
  sales.contracts.create = async (...args) => calls.push(['create', ...args]);
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { module, require: name => name.endsWith('/http') ? http : sales });
  const api = module.exports; await api.get('91'); await api.versions('91'); await api.save('91', { revision: 3 }, 'original-key');
  await api.create({ paymentStages: [] }, 'create-key'); await api.prepare('42');
  assert.deepEqual(copy(calls.slice(0, 3)), [['get', '/franchise/sales/contracts/91/payment-plan'], ['get', '/franchise/sales/contracts/91/payment-plan/versions'], ['post', '/franchise/sales/contracts/91/payment-plan', { revision: 3 }, { 'Idempotency-Key': 'original-key' }]]);
  assert.deepEqual(copy(calls.slice(-4)), [['contracts', '42'], ['requirements', '42'], ['quotes', '42'], ['get', '/franchise/sales/customers/42/payment-plan-draft']]);
  sales.requirements.list = async () => null; await assert.rejects(api.prepare('42'), /不完整/);
});

function financeHarness() {
  const navigations = [], requests = [], dir = path.join(root, 'miniprogram/pages/admin/finance-desk');
  const http = { get: async url => { requests.push(url); return url.endsWith('/financial-stages') ? [
    { id: '900719925474099991', contract_id: '91', stage: 'STAGE_900719925474099991', label: '主体结构验收款', customer_name: '本店客户', amount_cents: 10000, collected_cents: 5000 }
  ] : url.endsWith('/collection-declarations') ? [{ id: '51', stage_id: '900719925474099991', contract_id: '91', stage: 'STAGE_900719925474099991', customer_name: '本店客户', amount_cents: 5000 }] : []; },
  requestGuard: () => () => true, sessionGuard: () => () => true, post: async (...args) => { requests.push(args); } };
  const auth = { currentApiPost: () => ({ roleCode: 'finance', orgType: 'store' }) }, api = { receipts: async () => [], idemKey: () => 'key' };
  const page = { data: { journeyEnabled: true, view: 'flows', financialStages: [], workbenchBooks: [], collectionDeclarations: [], reimbursementRows: [] }, options: {},
    setData(patch) { for (const [key, value] of Object.entries(patch)) { const bits = key.split('.'); let out = this.data; for (const bit of bits.slice(0, -1)) out = out[bit] || (out[bit] = {}); out[bits.at(-1)] = copy(value); } }, loadPlatformFinance() {}, refresh() {}, syncPlatformTasks() {}, loadDesignFees() {}, paySourceRow() {} };
  for (const name of ['workbench', 'journey']) {
    const file = path.join(dir, name + '.js'), real = createRequire(file), module = { exports: {} };
    vm.runInNewContext(fs.readFileSync(file, 'utf8'), { module, console, wx: { showToast() {}, pageScrollTo() {} }, require: n => n.endsWith('/api/http') ? http : n.endsWith('/api/auth') ? auth : n.endsWith('/account') ? { localDate: () => '2026-11-25' } : n.endsWith('/page-navigation') ? { open: url => navigations.push(url) } : real(n) });
    module.exports(page, api);
  }
  return { page, navigations, requests };
}

test('财务真实入口打开同一个计划页，不创建固定三期表单；收款和申报显示服务端节点名称', async () => {
  const h = financeHarness(); await h.page.loadWorkbench();
  assert.equal(h.page.data.financialStages[0].stageLabel, '主体结构验收款');
  assert.match(h.page.data.financialStages[0].label, /主体结构验收款/); assert.match(h.page.data.journeyDeclarations[0].name, /主体结构验收款/);
  const count = h.requests.length; h.page.openContractChange(tap({ id: '91' }));
  assert.deepEqual(h.navigations, ['/pages/workflow/payment-plan/index?contractId=91']); assert.equal(h.page.data.wbForm, undefined); assert.equal(h.page.__entryOrigin, undefined);
  h.page.openContractChange(tap({ id: '92' })); assert.equal(h.navigations.length, 1); assert.match(h.page.data.wbError, /刷新/);
  h.page.data.wbForm = { type: 'contractchange', name: '旧入口', contractId: '91' }; await h.page.submitWorkbench();
  assert.equal(h.requests.length, count); assert.equal(h.navigations.length, 2);
  h.page.collectJourneyStage(tap({ id: '900719925474099991' })); assert.equal(h.page.data.wbForm.stageLabel, '主体结构验收款');
});

test('原合同页新建与已有计划入口均指向动态页，保留原需求加载与业务权限校验', () => {
  let definition; const nav = [], file = path.join(root, 'miniprogram/pages/workflow/index.js');
  const mocks = { '../../utils/organization': { roles: {} }, '../../utils/view-container': { routeValue: x => x, fail() {} },
    '../../utils/platform-crm': { can: post => post.roleCode === 'sales' }, '../../api/http': { token: () => 'token' }, './views': { titles: {} },
    '../../utils/page-navigation': { open: url => nav.push(url) } };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: p => definition = p, require: name => { if (!(name in mocks)) throw Error(name); return mocks[name]; }, console, wx: {} });
  const page = { ...definition, id: '42', __post: { roleCode: 'sales' }, data: { requirementsStatus: 'ready', salesContracts: [{ id: '91' }] }, setData(patch) { Object.assign(this.data, patch); } };
  page.openContractForm(); page.openPaymentPlan(tap({ id: '91' })); page.openPaymentPlan(tap({ id: '92' }));
  assert.deepEqual(nav, ['/pages/workflow/payment-plan/index?customerId=42', '/pages/workflow/payment-plan/index?contractId=91']);
  page.data.requirementsStatus = 'loading'; page.platformSubmitContract(); assert.equal(nav.length, 2);
  page.data.requirementsStatus = 'ready'; page.__post.roleCode = 'owner'; page.openContractForm(); assert.equal(nav.length, 2);
});

test('业务草案模式单独读取客户草案，保存不创建合同，说明必填且不展示签约日期', async () => {
  const h = harness({ role: 'sales', route: { customerId: '42', draft: '1' } }); await h.open();
  assert.deepEqual(h.calls.map(c => c.name), ['draft']); assert.equal(h.page.data.drafting, true);
  assert.equal(h.page.data.creating, false); assert.equal(h.page.data.form.total, '');
  assert.equal(h.nodes().some(n => n.attrs?.['data-key'] === 'signedDate'), false);
  assert.equal(h.nodes().some(n => n.attrs?.bindtap === 'toggleHistory'), false);
  h.page.input(event('total', '100.01')); await h.page.submit();
  assert.equal(h.callsFor('saveDraft').length, 0); assert.match(h.page.data.error, /草案说明/);
  h.page.input(event('reason', '按客户协商记录')); await h.page.submit();
  const saved = h.callsFor('saveDraft')[0]; assert.equal(saved.args[0], '42');
  assert.equal(saved.args[1].revision, 0); assert.equal(saved.args[1].totalCents, 10001);
  assert.equal(h.callsFor('create').length, 0); assert.equal(h.callsFor('save').length, 0);
  assert.equal(h.page.data.context.revision, 1); assert.equal(h.page.data.dirty, false); assert.equal(h.page.contractId, '');
});

test('草案保存未知结果锁定原说明与幂等键，低版本和写前迟到读取不能回退结果', async () => {
  const h = harness({ role: 'sales', route: { customerId: '42', draft: '1' } }); await h.open();
  h.page.input(event('total', '100')); h.page.input(event('reason', '初次沟通约定'));
  const read = deferred(); h.overrides.draft = () => read.promise; const loading = h.page.load();
  h.overrides.saveDraft = async () => { throw { code: -1, msg: 'timeout' }; };
  await h.page.submit(); assert.equal(h.page.data.uncertain, true);
  h.page.input(event('reason', '不能覆盖原请求')); await h.page.submit(); assert.equal(h.callsFor('saveDraft').length, 1);
  delete h.overrides.saveDraft; await h.page.runPending();
  assert.deepEqual(h.callsFor('saveDraft')[0].args, h.callsFor('saveDraft')[1].args);
  assert.equal(h.page.data.context.revision, 1); assert.equal(h.page.data.form.reason, '初次沟通约定');
  read.resolve({ customerId: '42', revision: 0, totalCents: null, stages: [], note: '' }); await loading;
  assert.equal(h.page.data.context.revision, 1); assert.equal(h.page.data.form.total, '100.00');
  await h.page.load(); assert.equal(h.page.data.context.revision, 1);
});

test('草案版本冲突保留填写，撤权/切岗清空；财务和总部不能创建客户草案', async () => {
  const h = harness({ role: 'sales', route: { customerId: '42', draft: '1' } }); await h.open();
  h.page.input(event('total', '100')); h.page.input(event('reason', '待保存说明'));
  h.overrides.saveDraft = async () => { throw { code: 1099000001, msg: '草案已有更新' }; };
  await h.page.submit(); assert.equal(h.page.data.conflict, true); assert.equal(h.page.data.form.reason, '待保存说明');
  await h.page.submit(); assert.equal(h.callsFor('saveDraft').length, 1);
  h.overrides.draft = async () => { throw { code: 1110000000, msg: '客户已转交' }; };
  await h.page.load(); assert.equal(h.page.data.context, null); assert.equal(h.page.data.form, null);
  for (const role of ['finance', 'hq-admin']) {
    const denied = harness({ role, route: { customerId: '42', draft: '1' } }); await denied.open();
    assert.equal(denied.calls.length, 0); assert.equal(denied.page.data.ready, false);
  }
  const stale = deferred(), switched = harness({ role: 'sales', route: { customerId: '42', draft: '1' }, api: { draft: () => stale.promise } });
  const old = switched.open(); switched.switchSession({ orgId: '10', roleCode: 'finance' }); await switched.open();
  stale.resolve({ customerId: '42', revision: 0, stages: [], totalCents: null, note: '' }); await old;
  assert.equal(switched.page.data.context, null); assert.equal(switched.page.data.form, null);
});

test('新建合同优先带入已保存客户草案，保留草案本身并仍需显式创建合同', async () => {
  const h = harness({ role: 'sales', create: true, api: { prepare: async () => ({ contracts: [], requirements: [], quotes: [{ totalCents: 9999 }], draft: {
    customerId: '42', revision: 3, totalCents: 20000, note: '委托沟通', stages: [
      { label: '开工前材料款', amountCents: 20000, ratioBps: 10000, trigger: 'BEFORE_START', sequence: 1 }
    ]
  } }) } });
  await h.open(); assert.equal(h.page.data.form.total, '200.00'); assert.equal(h.page.data.form.rows[0].label, '开工前材料款');
  assert.equal(h.page.data.draftSource, 3); assert.equal(h.callsFor('create').length, 0);
  await h.page.submit(); assert.equal(h.callsFor('create')[0].args[0].paymentStages[0].stageId, null);
  assert.equal(h.callsFor('create')[0].args[0].totalCents, 20000); assert.equal(h.callsFor('saveDraft').length, 0);
});

test('草案API使用独立GET/PUT与原幂等键，不发送合同命令', async () => {
  const calls = [], file = path.join(root, 'miniprogram/pages/workflow/payment-plan/api.js'), module = { exports: {} };
  const http = Object.fromEntries(['get', 'put', 'post'].map(method => [method, async (...args) => { calls.push([method, ...copy(args)]); return {}; }]));
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { module, require: name => name.endsWith('/http') ? http : {} });
  await module.exports.draft('900719925474099999'); await module.exports.saveDraft('900719925474099999', { revision: 2, note: '约定' }, 'draft-same-key');
  assert.deepEqual(calls, [['get', '/franchise/sales/customers/900719925474099999/payment-plan-draft'], ['put', '/franchise/sales/customers/900719925474099999/payment-plan-draft', { revision: 2, note: '约定' }, { 'Idempotency-Key': 'draft-same-key' }]]);
});
