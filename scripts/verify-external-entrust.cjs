const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), { createRequire } = require('node:module');
const { render } = require('./role-page-runtime.cjs');
const model = require('../miniprogram/pages/external-entrust/model');
const root = path.resolve(__dirname, '..'), copy = value => JSON.parse(JSON.stringify(value));
const event = (key, value) => ({ currentTarget: { dataset: { key } }, detail: { value } });
const actionEvent = action => ({ currentTarget: { dataset: { action } } });
const tabEvent = tab => ({ currentTarget: { dataset: { tab } } });
const tick = () => new Promise(resolve => setImmediate(resolve));
test('委托编辑可选择当前客户已核对的测量记录', () => {
  const page = fs.readFileSync(path.join(root, 'miniprogram/pages/external-entrust/index.wxml'), 'utf8');
  assert.match(page, /bindtap="loadMeasurements"/);
  assert.match(page, /选择已核对的测量记录/);
});
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
function load(file, mocks, globals = {}) {
  const filename = path.join(root, file), real = createRequire(filename), module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, require: name => name in mocks ? mocks[name] : real(name), ...globals }, { filename });
  return module.exports;
}
const noneRef = () => ({ source: 'NONE', revision: 0, contractId: null, contractPlanVersion: null });
function measured(id, revision, asset, customer = '42') {
  return { eventId: id, revision, customerId: customer, occurredOn: '2026-11-24',
    fields: {checked: true, address: '实测现场', measurer: '陈师傅', width: '10', depth: '12', area: '120'},
    attachments: [{assetId: asset, title: '原测量图.pdf'}] };
}
test('only the selected saved measurement identity is submitted, and editing a past request separates original from manual files', async () => {
  const old = measured('8123456789123456001', 3, '901'), recent = measured('8123456789123456002', 5, '902');
  const h = harness({api: {measurements: async (id, before) => ({customerId: id, list: [before ? old : recent], nextBeforeId: before ? null : recent.eventId})}});
  await h.open(); h.fill();
  await h.page.loadMeasurements();
  await h.page.loadMeasurements({currentTarget: {dataset: {more: '1'}}});
  assert.deepEqual(copy(h.page.data.measurements).map(row => row.eventId), [recent.eventId,old.eventId]);
  h.page.chooseMeasurement({currentTarget:{dataset:{id:old.eventId}}});
  const form = h.page.data.form, context = h.page.data.context;
  assert.equal(form.measurementRef.eventId,old.eventId);
  assert.deepEqual(copy(model.payload('register',form,context,'2026-11-25').measurementRef),{eventId:old.eventId,revision:3});
  assert.deepEqual(model.payload('register',form,context,'2026-11-25').attachments,[]);
  const stored = state('pending'); stored.currentRequest.data.measurementRef = old;
  stored.currentRequest.data.attachments = [...old.attachments,{assetId:'601',title:'外部需求.pdf'}]; stored.requests = [stored.currentRequest];
  const parsed = model.detail(stored,'42','9',{orgType:'store',roleCode:'sales'});
  const edit = model.requestForm(parsed);
  assert.deepEqual(edit.attachments.map(f=>f.assetId),['601']);
  assert.equal(edit.measurementRef.attachments[0].assetId,'901');
  edit.reason='改用手动附件'; edit.measurementRef=null;
  assert.equal(model.payload('register',edit,parsed,'2026-11-25').removeMeasurement,true);
  assert.deepEqual(parsed.request.attachments.map(f=>f.assetId),['901','601']);
});
test('late measurement pages are discarded after switching posts and a foreign customer response cannot become a selection', async () => {
  const slow = deferred(), h = harness({api:{measurements:()=>slow.promise}});
  await h.open();
  const waiting=h.page.loadMeasurements();
  h.switchSession({roleCode:'boss'}); await h.page.onShow();
  slow.resolve({customerId:'42',list:[measured('700',1,'800')],nextBeforeId:null}); await waiting;
  assert.equal(h.page.data.measurements.length,0);
  assert.equal(h.page.data.editing,'');
  const bad=harness({api:{measurements:async()=>({customerId:'42',list:[measured('700',1,'800','99')],nextBeforeId:null})}});
  await bad.open(); await bad.page.loadMeasurements();
  assert.match(bad.page.data.measurementsError,/客户或版本/); assert.equal(bad.page.data.measurements.length,0);
});
function requestData() { return { revision: 0, entrustedOn: '2026-11-24', providerName: '本地设计服务方', providerReference: 'EX-1', text: '三房两厅，带老人房', scope: '平面及施工图',
  area: 260.5, floors: 2, attachments: [{ assetId: '88', title: '测量记录.pdf' }], paymentPlanRef: noneRef(), reason: '' }; }
function draftPlan(revision = 2) { return { customerId: '42', companyId: '9', source: 'DRAFT', revision, totalCents: 100001, contractId: null, contractPlanVersion: null,
  stages: [{ label: '开工前', sequence: 1, amountCents: 50000, ratioBps: 5000, trigger: 'BEFORE_START', dueDate: null },
    { label: '完工验收', sequence: 2, amountCents: 50001, ratioBps: 5000, trigger: 'ON_FINISH', dueDate: null }] }; }
function state(kind = 'empty') {
  const data = { commissionId: null, customerId: '42', customerName: '本店客户', mode: 'EXTERNAL', status: 'NOT_REGISTERED', revision: 0, canWrite: true, canConvert: false,
    allowedActions: ['external-entrust-register'], currentRequest: null, currentResult: null, requests: [], results: [], currentPaymentPlan: null, currentPaymentPlanRef: noneRef() };
  if (kind === 'legacy') return { ...data, commissionId: '91', mode: 'LEGACY', status: 'DESIGNING', revision: 3, canWrite: false, canConvert: true, allowedActions: ['external-entrust-convert'] };
  if (kind !== 'empty') {
    const request = { version: 1, action: 'REGISTER', data: requestData(), paymentPlan: null, previousStatus: null, supersededApprovalIds: [], actorUserId: '7', createdAt: '2026-11-24T15:00:00+08:00' };
    Object.assign(data, { commissionId: '91', status: 'EXTERNAL_PENDING', revision: 1, currentRequest: request, requests: [request], allowedActions: ['external-entrust-register', 'external-results-register'] });
  }
  if (kind === 'ready') {
    const result = { version: 1, data: { revision: 1, requestVersion: 1, deliveredOn: '2026-11-24', drawingVersion: 'V1', resultAssetId: '89', drawingAssetId: '90', reviewAssetId: '92', note: '按需求交付' },
      attachments: [{ assetId: '89', title: '外部设计成果' }, { assetId: '90', title: '外部施工图' }, { assetId: '92', title: '专业审核依据' }], actorUserId: '7', createdAt: '2026-11-24T16:00:00+08:00' };
    Object.assign(data, { status: 'EXTERNAL_READY', revision: 2, currentResult: result, results: [result] }); data.allowedActions.push('external-packet-submit');
  }
  return data;
}
function harness(options = {}) {
  let definition, generation = 1, key = 0, token = 'token', clock = Date.UTC(2026, 10, 25, 1);
  let post = { postId: '7', orgId: '9', orgType: 'store', roleCode: options.role || 'sales', permissions: options.permissions };
  const h = { state: state(options.kind), calls: [], toasts: [], navigations: [], previews: [], overrides: options.api || {} };
  const api = Object.fromEntries(['get', 'resolve', 'register', 'convert', 'results', 'download', 'measurements'].map(name => [name, async (...args) => {
    h.calls.push({ name, args: copy(args) }); if (h.overrides[name]) return h.overrides[name](...args);
    if (name === 'get') return copy(h.state);
    if (name === 'measurements') return { customerId: '42', list: [], nextBeforeId: null };
    if (name === 'resolve') return { commission: { id: '91', customerId: '42' } };
    if (name === 'download') return { downloadUrl: 'https://store.test/file.pdf', expiresAt: 1891234567 };
    const body = copy(args[1]); h.state.revision += 1; h.state.commissionId = '91'; h.state.mode = 'EXTERNAL'; h.state.canWrite = true; h.state.canConvert = false;
    h.state.allowedActions = ['external-entrust-register', 'external-results-register'];
    if (name !== 'results') {
      const record = { version: h.state.requests.length + 1, action: name === 'convert' ? 'CONVERT' : h.state.currentRequest ? 'UPDATE' : 'REGISTER',
        data: body, paymentPlan: copy(h.state.currentPaymentPlan), actorUserId: '7', createdAt: '2026-11-25T10:00:00+08:00' };
      h.state.requests.unshift(record); h.state.currentRequest = record; h.state.currentResult = null; h.state.status = 'EXTERNAL_PENDING';
    } else {
      const record = { version: h.state.results.length + 1, data: body, attachments: model.slots.map(slot => ({ assetId: body[slot.key], title: slot.label })), actorUserId: '7', createdAt: '2026-11-25T11:00:00+08:00' };
      h.state.results.unshift(record); h.state.currentResult = record; h.state.status = 'EXTERNAL_READY'; h.state.allowedActions.push('external-packet-submit');
    }
    return copy(h.state);
  }]));
  const http = { token: () => token, currentPostId: () => post?.postId || '' };
  const globals = { console, Date: class extends Date { static now() { return clock; } }, wx: { showToast: value => h.toasts.push(value), showModal: value => h.modal = value,
    chooseMessageFile: value => h.filePicker = value, chooseMedia: value => h.photoPicker = value, openDocument: value => h.previews.push(value), previewImage: value => h.previews.push(value) } };
  const mocks = { '../../api/http': http, '../../api/auth': { currentApiPost: () => post }, '../../utils/api-scope': () => JSON.stringify([generation, post?.postId, post?.orgId, post?.permissions]),
    '../../utils/page-navigation': { open: (url, options) => h.navigations.push({ url, options }), back: value => h.navigations.push({ back: value }) },
    '../../utils/platform-crm': { commandKey: prefix => prefix + '-' + ++key }, './api': api,
    './upload': { run: options.upload || (async pending => ({ assetId: pending.slot === 'drawingAssetId' ? '101' : pending.slot === 'reviewAssetId' ? '102' : '900719925474099999', fileName: pending.file.name || '现场.pdf', mimeType: 'application/pdf' })) },
    '../../utils/b-asset-download': { downloadTicketFile: options.download || (async () => ({ tempFilePath: '/download.pdf', contentType: 'application/pdf' })) } };
  mocks['./files'] = load('miniprogram/pages/external-entrust/files.js', mocks, globals);
  load('miniprogram/pages/external-entrust/index.js', mocks, { ...globals, Page: value => definition = value });
  h.page = { ...definition, data: copy(definition.data), setData(patch) { Object.assign(this.data, copy(patch)); } };
  h.page.onLoad(options.route || { customerId: '42' }); h.open = () => h.page.onShow();
  h.callsFor = name => h.calls.filter(call => call.name === name);
  h.fill = () => { if (!h.page.data.editing) h.page.startEdit(actionEvent('register'));
    for (const [key, value] of Object.entries(requestData()).filter(([key]) => !['revision', 'attachments', 'paymentPlanRef'].includes(key))) h.page.input(event(key, value)); };
  h.fillResults = async () => { h.page.startEdit(actionEvent('results')); h.page.input(event('deliveredOn', '2026-11-24')); h.page.input(event('drawingVersion', '外部V2'));
    for (const slot of model.slots) await h.page.startUpload({ path: '/external.pdf', name: slot.label + '.pdf', size: 20 }, slot.key); };
  h.switchSession = (next = {}) => { generation += 1; post = { ...post, postId: String(7 + generation), ...next }; };
  h.logout = () => { token = ''; }; h.advanceDay = () => { clock += 24 * 3600000; };
  h.controls = () => { const flat = nodes => nodes.flatMap(n => [n, ...flat(n.children || [])]); return flat(render(path.join(root, 'miniprogram/pages/external-entrust/index.wxml'), h.page.data)); };
  return h;
}

test('API writes preserve frozen body/key and typed customer/version downloads, without legacy budget or price calls', async () => {
  const calls = [], api = load('miniprogram/pages/external-entrust/api.js', { '../../api/http': { get: (...a) => calls.push(['GET', ...a]), post: (...a) => calls.push(['POST', ...a]) } });
  const body = { revision: 7, paymentPlanRef: noneRef() }; await api.get('42'); await api.register('42', body, 'k1'); await api.convert('42', body, 'k2'); await api.results('42', { revision: 8 }, 'k3'); await api.download('42', 'requests', 3, '900719925474099999');
  assert.deepEqual(copy(calls), [['GET', '/franchise/customers/42/external-entrust'], ['POST', '/franchise/customers/42/external-entrust', body, { 'Idempotency-Key': 'k1' }],
    ['POST', '/franchise/customers/42/external-entrust/convert-legacy', body, { 'Idempotency-Key': 'k2' }], ['POST', '/franchise/customers/42/external-entrust/results', { revision: 8 }, { 'Idempotency-Key': 'k3' }],
    ['POST', '/franchise/customers/42/external-entrust/requests/3/assets/900719925474099999/download-tickets']]);
});

test('request validates real dates, bounded dimensions, attachment IDs, exact plan reference and reason rules', () => {
  const context = model.detail(state(), '42', '9', { orgType: 'store', roleCode: 'sales' }), form = { ...requestData(), area: '260.50', floors: '2' };
  const body = model.payload('register', form, context, '2026-11-25'); assert.equal(body.area, 260.5); assert.equal(body.floors, 2); assert.deepEqual(body.paymentPlanRef, noneRef());
  assert.equal(model.id('9223372036854775807'), '9223372036854775807'); assert.equal(model.id('9223372036854775808'), ''); assert.equal(model.id(9007199254740999), '');
  for (const entrustedOn of ['', '2026-02-30', '2026-11-26', '2026-11-24T08:00']) assert.throws(() => model.payload('register', { ...form, entrustedOn }, context, '2026-11-25'), /日期/);
  for (const area of ['0', '-1', '1.005', '1000000', '1e3']) assert.throws(() => model.payload('register', { ...form, area }, context, '2026-11-25'), /面积/);
  for (const floors of ['0', '21', '2.5']) assert.throws(() => model.payload('register', { ...form, floors }, context, '2026-11-25'), /层数/);
  for (const [key, max] of [['providerName',100], ['providerReference',100], ['text',2000], ['scope',500], ['reason',500]]) assert.throws(() => model.payload('register', { ...form, [key]: 'a'.repeat(max+1) }, context, '2026-11-25'));
  assert.throws(() => model.payload('register', { ...form, attachments: [form.attachments[0], form.attachments[0]] }, context, '2026-11-25'), /重复/);
  assert.throws(() => model.payload('register', { ...form, attachments: [{ assetId: 'https://other.test/private', title: 'file' }] }, context, '2026-11-25'), /编号/);
  const existing = model.detail(state('pending'), '42', '9', { orgType: 'store', roleCode: 'sales' }); assert.throws(() => model.payload('register', form, existing, '2026-11-25'), /原因/);
  assert.equal(model.payload('register', { ...form, area: '', floors: '', reason: '调整老人房' }, existing, '2026-11-25').area, null);
});

test('first GET never creates a commission; first register needs no internal budget/HQ policy or payment', async () => {
  const h = harness(); await h.open(); assert.equal(h.page.data.editing, 'register'); assert.equal(h.calls.length, 1); assert.equal(h.calls[0].name, 'get');
  h.fill(); await h.page.submit(); const body = h.callsFor('register')[0].args[1];
  assert.equal(body.revision, 0); assert.equal(body.text, '三房两厅，带老人房'); assert.equal(body.attachments.length, 0); assert.deepEqual(body.paymentPlanRef, noneRef());
  assert.equal(h.page.data.context.status, 'EXTERNAL_PENDING'); assert.equal(h.page.data.editing, '');
  for (const key of ['budgetVersion', 'feeRuleVersion', 'paymentMode', 'companyId', 'tenantId']) assert.equal(key in body, false);
});

test('draft and signed-plan snapshot are shown with all rows; current reference cannot mismatch or cross customer/company', async () => {
  const h = harness(); h.state.currentPaymentPlan = draftPlan(); h.state.currentPaymentPlanRef = { source: 'DRAFT', revision: 2, contractId: null, contractPlanVersion: null }; await h.open();
  assert.equal(h.page.data.context.currentPlan.totalText, '1000.01'); assert.equal(h.page.data.context.currentPlan.rows[1].amountText, '500.01');
  h.fill(); await h.page.submit(); assert.deepEqual(h.callsFor('register')[0].args[1].paymentPlanRef, h.state.currentPaymentPlanRef);
  assert.equal(h.page.data.context.request.plan.version, 2); h.state.currentPaymentPlan = draftPlan(3); h.state.currentPaymentPlanRef.revision = 3; await h.page.load();
  assert.equal(h.page.data.context.request.plan.version, 2); assert.equal(h.page.data.context.currentPlan.version, 3);
  for (const field of ['customerId', 'companyId']) { const value = state(); value.currentPaymentPlan = { ...draftPlan(), [field]: '99' }; value.currentPaymentPlanRef = h.state.currentPaymentPlanRef;
    assert.throws(() => model.detail(value, '42', '9', { orgType: 'store', roleCode: 'sales' })); }
  const value = state(); value.currentPaymentPlan = draftPlan(); value.currentPaymentPlanRef = { source: 'DRAFT', revision: 4, contractId: null, contractPlanVersion: null };
  assert.throws(() => model.detail(value, '42', '9', { orgType: 'store', roleCode: 'sales' }), /不一致/);
});

test('server actions and customer write capability gate every action; boss/finance/HQ and denied permission remain readonly', async () => {
  for (const role of ['boss', 'finance', 'finance-lead', 'hq-admin', 'engineer']) {
    const h = harness({ role, kind: 'ready' }); await h.open(); h.page.startEdit(actionEvent('register')); h.page.startEdit(actionEvent('results')); await h.page.submit();
    assert.equal(h.page.data.editing, ''); assert.equal(h.callsFor('register').length + h.callsFor('results').length, 0);
    assert.equal(h.controls().some(n => n.attrs?.bindtap === 'startEdit' || n.attrs?.bindtap === 'submit'), false);
  }
  const denied = harness({ permissions: [] }); await denied.open(); assert.equal(denied.page.data.editing, '');
  const invisibleAction = harness({ kind: 'pending' }); invisibleAction.state.allowedActions = []; await invisibleAction.open(); invisibleAction.page.startEdit(actionEvent('register')); assert.equal(invisibleAction.page.data.editing, '');
  const readonly = harness({ kind: 'pending' }); readonly.state.canWrite = false; await readonly.open(); readonly.page.startEdit(actionEvent('results')); assert.equal(readonly.page.data.editing, '');
});

test('legacy conversion is explicit, reason required, preserves commission/history and obeys server canConvert', async () => {
  const h = harness({ kind: 'legacy' }); await h.open(); assert.equal(h.page.data.editing, ''); assert.equal(h.callsFor('convert').length, 0);
  h.page.startEdit(actionEvent('convert')); h.fill(); await h.page.submit(); assert.equal(h.callsFor('convert').length, 0); assert.match(h.page.data.error, /原因/);
  h.page.input(event('reason', '客户改由外部设计服务方交付')); await h.page.submit();
  assert.equal(h.callsFor('convert')[0].args[1].revision, 3); assert.equal(h.page.data.context.commissionId, '91'); assert.equal(h.page.data.context.hasLegacy, true);
  h.page.openLegacy(); assert.match(h.navigations.at(-1).url, /history=1&commissionId=91$/);
  const blocked = harness({ kind: 'legacy' }); blocked.state.canConvert = false; blocked.state.allowedActions = []; await blocked.open(); blocked.page.startEdit(actionEvent('convert')); assert.equal(blocked.page.data.editing, '');
});

test('result requires three accepted asset slots, actual delivery and drawing version; correction requires note', async () => {
  const h = harness({ kind: 'pending' }); await h.open(); h.page.startEdit(actionEvent('results')); await h.page.submit(); assert.equal(h.callsFor('results').length, 0);
  await h.fillResults(); await h.page.submit(); const body = h.callsFor('results')[0].args[1];
  assert.deepEqual(body, { revision: 1, requestVersion: 1, deliveredOn: '2026-11-24', drawingVersion: '外部V2', note: '', resultAssetId: '900719925474099999', drawingAssetId: '101', reviewAssetId: '102' });
  assert.equal(h.page.data.context.status, 'EXTERNAL_READY'); assert.equal(h.page.data.context.result.attachments.length, 3);
  h.page.startEdit(actionEvent('results')); await h.page.submit(); assert.equal(h.callsFor('results').length, 1); assert.match(h.page.data.error, /更正原因/);
  h.page.input(event('note', '已按客户确认修正窗位')); await h.page.submit(); assert.equal(h.page.data.context.results.length, 2);
  assert.equal(h.calls.some(call => /contract|workflow|start/.test(call.name)), false);
});

test('changing request leaves old result in history and clears current result; new result binds new request version', async () => {
  const h = harness({ kind: 'ready' }); await h.open(); h.page.startEdit(actionEvent('register')); h.page.input(event('reason', '新增三层需求')); h.page.input(event('floors', '3')); await h.page.submit();
  assert.equal(h.page.data.context.request.version, 2); assert.equal(h.page.data.context.result, null); assert.equal(h.page.data.context.results.length, 1);
  await h.fillResults(); await h.page.submit(); assert.equal(h.callsFor('results')[0].args[1].requestVersion, 2);
  assert.equal(h.page.data.context.result.version, 2); assert.equal(h.page.data.context.results[1].data.requestVersion, 1);
});

test('unknown mutation result freezes exact key/body, prevents edits/leave and retries once original command', async () => {
  let attempts = 0; const h = harness({ api: { register: async (...args) => { if (++attempts === 1) throw Error('response lost'); delete h.overrides.register; return h.state = { ...state('pending'), currentRequest: { ...state('pending').currentRequest, data: args[1] } }; } } });
  await h.open(); h.fill(); await h.page.submit(); assert.equal(h.page.data.uncertain, true); const oldText = h.page.data.form.text;
  h.page.input(event('text', '不能覆盖原请求')); h.page.changeTab(tabEvent('history')); h.page.openPlan(); assert.equal(h.page.data.form.text, oldText); assert.equal(h.page.data.tab, 'request'); assert.equal(h.navigations.length, 0);
  await h.page.runPending(); const commands = h.callsFor('register'); assert.deepEqual(commands[0].args, commands[1].args); assert.equal(h.page.data.uncertain, false);
});

test('revision and payment-plan conflicts preserve form, require explicit reload; old GET cannot roll back saved request', async () => {
  const h = harness(); await h.open(); h.fill(); const read = deferred(); h.overrides.get = () => read.promise; const loading = h.page.load();
  await h.page.submit(); read.resolve(state()); await loading; assert.equal(h.page.data.context.revision, 1); assert.equal(h.page.data.context.request.data.text, '三房两厅，带老人房');
  const plan = harness(); await plan.open(); plan.fill(); plan.state.currentPaymentPlan = draftPlan(); plan.state.currentPaymentPlanRef = { source: 'DRAFT', revision: 2, contractId: null, contractPlanVersion: null }; await plan.page.load();
  assert.equal(plan.page.data.conflict, true); assert.equal(plan.page.data.form.text, '三房两厅，带老人房'); await plan.page.submit(); assert.equal(plan.callsFor('register').length, 0);
  plan.page.reloadLatest(); assert.ok(plan.modal); plan.modal.success({ confirm: true }); await tick(); assert.equal(plan.page.data.conflict, false); assert.equal(plan.page.data.form.text, '');
  const conflict = harness({ api: { register: async () => { throw { code: 1099000001, msg: '版本变化' }; } } }); await conflict.open(); conflict.fill(); await conflict.page.submit();
  assert.equal(conflict.page.data.conflict, true); assert.equal(conflict.page.data.form.text, '三房两厅，带老人房'); assert.equal(conflict.page._pending, null);
});

test('switching identity invalidates chooser, uploads and write callbacks, while unknown requests never move to new company', async () => {
  const pending = deferred(), h = harness({ upload: () => pending.promise }); await h.open(); h.page.chooseFile({ currentTarget: { dataset: {} } });
  h.switchSession(); h.filePicker.success({ tempFiles: [{ path: '/old.pdf' }] }); assert.equal(h.page._pendingFile, null);
  await h.open(); const uploading = h.page.startUpload({ path: '/old.pdf' }); h.switchSession({ orgId: '10' }); await h.open();
  pending.resolve({ assetId: '88', fileName: 'old.pdf', mimeType: 'application/pdf' }); await uploading; assert.deepEqual(copy(h.page._localFiles), {}); assert.equal(h.page.data.form.attachments.length, 0);
  const write = deferred(), w = harness({ api: { register: () => write.promise } }); await w.open(); w.fill(); const saving = w.page.submit();
  w.switchSession(); await w.open(); write.resolve(state('pending')); await saving; assert.equal(w.toasts.length, 0); assert.equal(w.page.data.context.revision, 0); assert.equal(w.page._pending, null);
});

test('CRM access revocation clears all private state, previews, pending uploads and histories; dates refresh on show', async () => {
  for (const code of [401, 403, 404, 1110000000, 1110000001]) {
    const h = harness({ kind: 'ready' }); await h.open(); h.page.startEdit(actionEvent('register')); h.page.input(event('text', 'private')); await h.page.startUpload({ path: '/private.pdf' });
    h.overrides.get = async () => { throw { code, msg: '不可读' }; }; await h.page.load();
    assert.equal(h.page.data.context, null); assert.equal(h.page.data.form, null); assert.deepEqual(copy(h.page._localFiles), {}); assert.equal(h.page._preview, null); assert.equal(h.page._pendingFile, null);
  }
  const h = harness(); await h.open(); assert.equal(h.page.data.today, '2026-11-25'); h.advanceDay(); await h.open(); assert.equal(h.page.data.today, '2026-11-26');
  h.logout(); await h.open(); assert.equal(h.page.data.context, null);
});

test('historical attachments use exact typed version, fresh customer authorization and cannot preview foreign IDs', async () => {
  const h = harness({ kind: 'ready' }); await h.open();
  await h.page.previewFile({ currentTarget: { dataset: { assetId: '89', kind: 'results', version: 1 } } });
  assert.deepEqual(h.callsFor('download')[0].args, ['42', 'results', 1, '89']); assert.equal(h.callsFor('get').length, 2); assert.equal(h.previews.length, 1);
  await h.page.previewFile({ currentTarget: { dataset: { assetId: '999', kind: 'results', version: 1 } } }); assert.equal(h.callsFor('download').length, 1);
  const revoke = harness({ kind: 'pending', api: { download: async () => { revoke.overrides.get = async () => { throw { code: 1110000000, msg: '已转交' }; }; throw { code: 1099000002 }; } } });
  await revoke.open(); await revoke.page.previewFile({ currentTarget: { dataset: { assetId: '88', kind: 'requests', version: 1 } } });
  assert.equal(revoke.page.data.context, null); assert.equal(revoke.previews.length, 0);
  const late = deferred(), stale = harness({ kind: 'ready', download: () => late.promise }); await stale.open(); const preview = stale.page.previewFile({ currentTarget: { dataset: { assetId: '89', kind: 'results', version: 1 } } }); await tick();
  stale.switchSession(); await stale.open(); late.resolve({ tempFilePath: '/late.pdf', contentType: 'application/pdf' }); await preview; assert.equal(stale.previews.length, 0);
});

test('commission-id entry resolves only its authorized customer; matching current commission required', async () => {
  const h = harness({ kind: 'ready', route: { commissionId: '91' } }); await h.open(); assert.equal(h.calls[0].name, 'resolve'); assert.equal(h.calls[1].args[0], '42'); assert.equal(h.page.data.context.commissionId, '91');
  const mismatch = harness({ route: { commissionId: '91' } }); mismatch.state.commissionId = '92'; await mismatch.open(); assert.equal(mismatch.page.data.ready, false);
  const invalid = harness({ route: { customerId: 'https://example.test/' } }); await invalid.open(); assert.equal(invalid.calls.length, 0);
});

test('real WXML has explicit form lengths, date ceilings, typed result slots and readonly expandable history', async () => {
  const h = harness(); await h.open(); const controls = h.controls();
  const requirements = controls.find(n => n.attrs?.['data-key'] === 'text'); assert.equal(requirements.tag, 'textarea'); assert.equal(requirements.attrs.maxlength, 2000);
  assert.equal(controls.find(n => n.tag === 'picker').attrs.end, '2026-11-25'); assert.equal(controls.filter(n => n.attrs?.bindtap === 'chooseFile').length, 1);
  const result = harness({ kind: 'ready' }); await result.open(); result.page.startEdit(actionEvent('results')); assert.equal(result.controls().filter(n => n.attrs?.bindtap === 'chooseFile').length, 3);
  result.page.changeTab(tabEvent('history')); result.page.toggleHistory({ currentTarget: { dataset: { key: 'results-1' } } });
  const history = result.controls(); assert.equal(history.filter(n => n.attrs?.bindtap === 'previewFile').length, 3); assert.equal(history.some(n => n.tag === 'input' || n.tag === 'textarea'), false);
  assert.match(history.filter(n => n.tag === '#text').map(n => n.text).join(''), /外部施工图/);
});

test('legacy business deep links route to new module without loading old prerequisites; history flag cannot perform writes', async () => {
  const opened = [], redirect = load('miniprogram/pages/workflow/commission/external-entry.js', { '../../../utils/page-navigation': { open: (...args) => opened.push(args) } });
  const page = { post: () => ({ orgType: 'store', roleCode: 'sales' }), view: 'submit', customerId: '900719925474099999', id: '' };
  assert.equal(redirect(page), true); assert.deepEqual(copy(opened[0]), ['/pages/external-entrust/index?customerId=900719925474099999', { replace: true }]);
  page.view = 'progress'; page.customerId = ''; page.id = '91'; assert.equal(redirect(page), true); assert.match(opened[1][0], /commissionId=91$/);
  page.historyOnly = true; assert.equal(redirect(page), false); page.historyOnly = false; page.view = 'receipt'; assert.equal(redirect(page), false);
  let definition; load('miniprogram/pages/workflow/commission/index.js', { '../../../utils/view-container': { makeFormHandlers: () => ({}) }, './payment-flow': () => ({}), './external-entry': redirect }, { Page: value => definition = value });
  let legacyRead = 0; const entry = { ...definition, ...page, view: 'submit', customerId: '42', historyOnly: false, refresh: () => legacyRead++ };
  entry.onShow(); assert.equal(legacyRead, 0); assert.equal(opened.at(-1)[0], '/pages/external-entrust/index?customerId=42');
  const historic = { ...definition, data: copy(definition.data), setData(patch) { Object.assign(this.data, patch); } }; historic.onLoad({ view: 'submit', commissionId: '91', history: '1' });
  assert.equal(historic.view, 'progress'); historic.action = 'commission-submit'; await historic.submit(); await historic.returnPacket(); assert.equal(historic.data.busy, false);
});

test('readable GET cannot reuse a newer response from another customer or invalid plan totals', () => {
  const value = state(); value.customerId = '43'; assert.throws(() => model.detail(value, '42', '9', { orgType: 'store', roleCode: 'sales' }), /不匹配/);
  const plan = state(); plan.currentPaymentPlan = draftPlan(); plan.currentPaymentPlan.stages[1].amountCents = 50000; plan.currentPaymentPlanRef = { source: 'DRAFT', revision: 2, contractId: null, contractPlanVersion: null };
  assert.throws(() => model.detail(plan, '42', '9', { orgType: 'store', roleCode: 'sales' }), /合计/);
});

test('upload failure exposes exact-asset confirmation and explicit new-ticket restart without automatic retransmission', async () => {
  let attempts = 0, first; const h = harness({ upload: async pending => {
    if (++attempts === 1) { first = pending; pending.bytes = new ArrayBuffer(8); pending.mimeType = 'application/pdf'; pending.ticket = { assetId: '88' }; pending.restartRequired = true; throw { code: 1071000001, msg: '资产尚未上传完成' }; }
    assert.notEqual(pending, first); assert.equal(pending.bytes, first.bytes); assert.equal(pending.ticket, undefined); assert.equal(pending.slot, 'attachments');
    return { assetId: '89', fileName: 'measure.pdf', mimeType: 'application/pdf' };
  } });
  await h.open(); await h.page.startUpload({ path: '/measure.pdf' }); assert.equal(attempts, 1); assert.ok(h.controls().some(n => n.attrs?.bindtap === 'restartUpload'));
  await h.page.restartUpload(); assert.equal(h.page.data.form.attachments[0].assetId, '89'); assert.equal(h.page.data.uploadFailed, false);
});

test('external results open engineering handover with the same commission; pending results cannot expose submit link', async () => {
  const h = harness({ kind: 'ready' }); await h.open(); h.page.changeTab(tabEvent('result'));
  assert.ok(h.controls().some(node => node.attrs?.bindtap === 'openHandover')); h.page.openHandover();
  assert.equal(h.navigations.at(-1).url, '/pages/external-handover/index?commissionId=91'); assert.equal(h.callsFor('results').length, 0);
  const pending = harness({ kind: 'pending' }); await pending.open(); pending.page.changeTab(tabEvent('result')); pending.page.openHandover();
  assert.equal(pending.controls().some(node => node.attrs?.bindtap === 'openHandover'), false); assert.equal(pending.navigations.length, 0);
  const accepted = harness({ kind: 'ready', role: 'boss' }); accepted.state.status = 'ACCEPTED'; accepted.state.allowedActions = []; await accepted.open(); accepted.page.openHandover();
  assert.equal(accepted.navigations.at(-1).url, '/pages/external-handover/index?commissionId=91');
});

module.exports = { load, harness, state };
