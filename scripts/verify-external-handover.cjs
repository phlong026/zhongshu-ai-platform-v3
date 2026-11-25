const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), { createRequire } = require('node:module');
const { render } = require('./role-page-runtime.cjs');
const model = require('../miniprogram/pages/external-handover/model');
const root = path.resolve(__dirname, '..'), copy = value => JSON.parse(JSON.stringify(value));
const event = (key, value) => ({ currentTarget: { dataset: { key } }, detail: { value } });
const action = action => ({ currentTarget: { dataset: { action } } });
const fileEvent = file => ({ currentTarget: { dataset: file } });
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
function load(file, mocks, globals = {}) {
  const filename = path.join(root, file), real = createRequire(filename), module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, require: name => name in mocks ? mocks[name] : real(name), ...globals }, { filename }); return module.exports;
}
function plan() { return { customerId: '42', companyId: '9', source: 'DRAFT', revision: 2, totalCents: '100001', contractId: null, contractPlanVersion: null,
  stages: [{ sequence: 1, label: '开工前', amountCents: '50000', ratioBps: 5000, trigger: 'BEFORE_START' }, { sequence: 2, label: '完工验收', amountCents: '50001', ratioBps: 5000, trigger: 'ON_FINISH' }] }; }
function packet(version = 1, status = 'PENDING') { return { version, resultVersion: 1, status, drawingVersion: '外部V2', confirmationDate: '2026-11-24', note: '客户确认窗位，注意现场坡差',
  managerUserId: '8', managerName: '本公司工程师', files: model.slots.map((slot, i) => ({ slot: slot.slot, assetId: ['900719925474099999', '101', '102', '103'][i], title: slot.label })),
  paymentPlan: plan(), submittedByUserId: '7', createdAt: '2026-11-24T10:00:00+08:00', decisionNote: status === 'RETURNED' ? '请补充边界确认' : '', decidedByUserId: status !== 'PENDING' ? '8' : null, decidedAt: status !== 'PENDING' ? '2026-11-25T09:00:00+08:00' : null }; }
function state(kind = 'ready') {
  const value = { commissionId: '91', customerId: '42', customerName: '本店客户', mode: 'EXTERNAL', status: 'EXTERNAL_READY', revision: 2, resultVersion: 1,
    projectId: null, canSubmit: true, canDecide: false, allowedActions: ['external-packet-submit'], currentPacket: null, packets: [] };
  if (kind !== 'ready') {
    value.currentPacket = packet(1, ({ pending: 'PENDING', returned: 'RETURNED', accepted: 'ACCEPTED' })[kind]); value.packets = [copy(value.currentPacket)];
    Object.assign(value, { status: { pending: 'HANDOVER_SUBMITTED', returned: 'HANDOVER_RETURNED', accepted: 'ACCEPTED' }[kind], revision: 3,
      canSubmit: kind === 'returned', canDecide: kind === 'pending', allowedActions: kind === 'pending' ? ['external-packet-return', 'external-packet-receive'] : kind === 'returned' ? ['external-packet-submit'] : [] });
    if (kind === 'accepted') value.projectId = '55';
  }
  return value;
}
function source() { return { commissionId: '91', customerId: '42', mode: 'EXTERNAL', currentRequest: { version: 2, paymentPlan: plan() },
  currentResult: { version: 1, data: { requestVersion: 2, drawingVersion: '外部V2', deliveredOn: '2026-11-24', resultAssetId: '900719925474099999', drawingAssetId: '101', reviewAssetId: '102' },
    attachments: model.slots.slice(0, 3).map((slot, i) => ({ assetId: ['900719925474099999', '101', '102'][i], title: slot.label })) } }; }
function harness(options = {}) {
  let definition, generation = 1, key = 0, token = 'token';
  let post = { postId: '70', userId: options.role === 'engineer' || options.role === 'project-manager' ? '8' : '7', orgId: '9', orgType: options.orgType || 'store', roleCode: options.role || 'sales', permissions: options.permissions };
  const h = { state: state(options.kind), source: source(), calls: [], toasts: [], navigations: [], previews: [], overrides: options.api || {} };
  const api = Object.fromEntries(['get', 'source', 'receivers', 'submit', 'return', 'receive', 'download', 'sourceDownload'].map(name => [name, async (...args) => {
    h.calls.push({ name, args: copy(args) }); if (h.overrides[name]) return h.overrides[name](...args);
    if (name === 'get') return copy(h.state); if (name === 'source') return copy(h.source);
    if (name === 'receivers') return [{ userId: '7', name: '自己', roleCode: 'engineer' }, { userId: '8', name: '工程师', roleCode: 'engineer' }, { userId: '9', name: '项目经理', roleCode: 'project-manager' }, { userId: '10', name: '财务', roleCode: 'finance' }, { userId: '11', roleCode: 'engineer', active: false }];
    if (name === 'download' || name === 'sourceDownload') return { downloadUrl: 'https://store.test/authorized-file', expiresAt: 1891234567 };
    const body = copy(args[1]); h.state.revision += 1;
    if (name === 'submit') {
      const value = packet(h.state.packets.length + 1); Object.assign(value, { resultVersion: body.resultVersion, note: body.note, confirmationDate: body.confirmationDate, managerUserId: body.managerUserId });
      value.files[3].assetId = body.confirmationAssetId; h.state.currentPacket = value; h.state.packets.unshift(copy(value));
      Object.assign(h.state, { status: 'HANDOVER_SUBMITTED', canSubmit: false, canDecide: true, allowedActions: ['external-packet-return', 'external-packet-receive'] });
    } else {
      Object.assign(h.state.currentPacket, { status: name === 'receive' ? 'ACCEPTED' : 'RETURNED', decisionNote: body.reason, decidedByUserId: '8', decidedAt: '2026-11-25T09:00:00+08:00' });
      h.state.packets = h.state.packets.map(value => value.version === body.handoverVersion ? copy(h.state.currentPacket) : value);
      Object.assign(h.state, { status: name === 'receive' ? 'ACCEPTED' : 'HANDOVER_RETURNED', projectId: name === 'receive' ? '55' : null,
        canSubmit: name === 'return', canDecide: false, allowedActions: name === 'return' ? ['external-packet-submit'] : [] });
    }
    return copy(h.state);
  }]));
  const globals = { Date: class extends Date { static now() { return Date.UTC(2026, 10, 25, 1); } }, wx: { showToast: value => h.toasts.push(value), showModal: value => h.modal = value,
    chooseMessageFile: value => h.filePicker = value, chooseMedia: value => h.photoPicker = value, openDocument: value => h.previews.push(value), previewImage: value => h.previews.push(value) } };
  const mocks = { '../../api/http': { token: () => token, currentPostId: () => post?.postId || '' }, '../../api/auth': { currentApiPost: () => post },
    '../../utils/api-scope': () => JSON.stringify([generation, post]), '../../utils/page-navigation': { open: (url, options) => h.navigations.push({ url, options }), back: value => h.navigations.push({ back: value }) },
    '../../utils/platform-crm': { commandKey: prefix => prefix + '-' + ++key }, './api': api,
    './upload': { run: options.upload || (async pending => ({ assetId: '103', fileName: pending.file.name || '客户确认.pdf', mimeType: 'application/pdf' })) },
    '../../utils/b-asset-download': { downloadTicketFile: options.download || (async () => ({ tempFilePath: '/download.pdf', contentType: 'application/pdf' })) } };
  mocks['./files'] = load('miniprogram/pages/external-handover/files.js', mocks, globals);
  load('miniprogram/pages/external-handover/index.js', mocks, { ...globals, Page: value => definition = value });
  h.page = { ...definition, data: copy(definition.data), setData(patch) { Object.assign(this.data, copy(patch)); } };
  h.page.onLoad(options.route || { commissionId: '91' }); h.open = () => h.page.onShow(); h.callsFor = name => h.calls.filter(call => call.name === name);
  h.fill = async () => { h.page.input(event('confirmationDate', '2026-11-24')); h.page.input(event('note', '已与客户核对窗位及现场坡差'));
    h.page.pickReceiver({ detail: { value: 0 } }); h.page.toggleConfirmed(); await h.page.startUpload({ path: '/confirmation.pdf', name: '客户签字.pdf' }); };
  h.switchSession = (next = {}) => { generation++; post = { ...post, postId: String(70 + generation), ...next }; };
  h.logout = () => { token = ''; };
  h.controls = () => { const flat = nodes => nodes.flatMap(node => [node, ...flat(node.children || [])]); return flat(render(path.join(root, 'miniprogram/pages/external-handover/index.wxml'), h.page.data)); };
  return h;
}

test('API uses exact external handover endpoints and original keys; source files have R09 result scope', async () => {
  const calls = [], api = load('miniprogram/pages/external-handover/api.js', { '../../api/http': { get: (...args) => calls.push(['GET', ...args]), post: (...args) => calls.push(['POST', ...args]) } });
  const body = { revision: 2, managerUserId: '900719925474099999' }; await api.get('91'); await api.submit('91', body, 'a'); await api.return('91', body, 'b'); await api.receive('91', body, 'c'); await api.download('91', 2, 'CONFIRMATION'); await api.sourceDownload('42', 1, '900719925474099999');
  assert.deepEqual(copy(calls), [['GET', '/franchise/commissions/91/external-handover'], ['POST', '/franchise/commissions/91/external-handover', body, { 'Idempotency-Key': 'a' }],
    ['POST', '/franchise/commissions/91/external-handover/return', body, { 'Idempotency-Key': 'b' }], ['POST', '/franchise/commissions/91/external-handover/receive', body, { 'Idempotency-Key': 'c' }],
    ['POST', '/franchise/commissions/91/external-handover/versions/2/assets/CONFIRMATION/download-tickets'], ['POST', '/franchise/customers/42/external-entrust/results/1/assets/900719925474099999/download-tickets']]);
});

test('submit validates real customer confirmation, bounded handover note, receiver and explicit confirmation', () => {
  const context = model.detail(state(), '91', '9', { orgType: 'store', roleCode: 'sales' }), people = [{ userId: '8' }];
  const form = { confirmationDate: '2026-11-24', confirmationAssetId: '900719925474099999', note: '真实交底', managerUserId: '8', confirmed: true };
  assert.deepEqual(model.payload('submit', form, context, people, '2026-11-25'), { revision: 2, resultVersion: 1, ...form });
  for (const confirmationDate of ['', '2026-02-30', '2026-11-26', '2026-11-24T00:00']) assert.throws(() => model.payload('submit', { ...form, confirmationDate }, context, people, '2026-11-25'), /日期/);
  for (const note of ['', 'a'.repeat(1001)]) assert.throws(() => model.payload('submit', { ...form, note }, context, people, '2026-11-25'), /说明/);
  assert.throws(() => model.payload('submit', { ...form, managerUserId: '99' }, context, people, '2026-11-25'), /接收人/);
  assert.throws(() => model.payload('submit', { ...form, confirmed: false }, context, people, '2026-11-25'), /确认/);
  for (const confirmationAssetId of ['https://fake.test/file', 900719925474099999, '9223372036854775808']) assert.throws(() => model.payload('submit', { ...form, confirmationAssetId }, context, people, '2026-11-25'), /凭据/);
});

test('first submit shows frozen request plan and three files, then adds confirmation; never creates contract or starts project', async () => {
  const h = harness(); await h.open(); assert.equal(h.callsFor('submit').length, 0); assert.equal(h.page.data.source.files.length, 3);
  assert.equal(h.page.data.source.plan.rows[1].amountText, '500.01'); assert.deepEqual(h.page.data.receivers.map(row => row.userId), ['8', '9']);
  await h.fill(); await h.page.submit(action('submit')); const body = h.callsFor('submit')[0].args[1];
  assert.deepEqual(body, { revision: 2, resultVersion: 1, confirmationAssetId: '103', confirmationDate: '2026-11-24', note: '已与客户核对窗位及现场坡差', managerUserId: '8', confirmed: true });
  assert.equal(h.page.data.context.currentPacket.files.length, 4); assert.equal(h.page.data.context.status, 'HANDOVER_SUBMITTED'); assert.equal(h.page.data.context.projectId, '');
  assert.equal(h.calls.some(call => /contract|start|complete|workflow/.test(call.name)), false); assert.equal(h.navigations.length, 0);
});

test('engineering receiver reads only frozen packet, requires confirmation to receive and opens actual project after success', async () => {
  const h = harness({ kind: 'pending', role: 'engineer' }); await h.open(); assert.equal(h.callsFor('source').length + h.callsFor('receivers').length, 0);
  await h.page.submit(action('receive')); assert.equal(h.callsFor('receive').length, 0); assert.match(h.page.data.error, /确认/);
  h.page.toggleConfirmed(); h.page.input(event('reason', '资料核对完成')); await h.page.submit(action('receive'));
  assert.deepEqual(h.callsFor('receive')[0].args[1], { revision: 3, handoverVersion: 1, reason: '资料核对完成', confirmed: true });
  assert.equal(h.page.data.context.status, 'ACCEPTED'); h.page.openProject(); assert.equal(h.navigations.at(-1).url, '/pages/build/record/index?id=55&view=overview');
  assert.equal(h.calls.some(call => /start|complete/.test(call.name)), false);
});

test('return needs specific reason; returned package remains immutable and sales resubmits a new version with fresh confirmation', async () => {
  const engineer = harness({ kind: 'pending', role: 'project-manager' }); await engineer.open(); await engineer.page.submit(action('return')); assert.equal(engineer.callsFor('return').length, 0);
  engineer.page.input(event('reason', '请补充边界确认')); await engineer.page.submit(action('return')); assert.equal(engineer.callsFor('return')[0].args[1].confirmed, false);
  const h = harness({ kind: 'returned' }); await h.open(); assert.equal(h.page.data.form.confirmationAssetId, ''); assert.equal(h.page.data.form.confirmed, false);
  const old = copy(h.page.data.context.currentPacket); await h.fill(); await h.page.submit(action('submit'));
  assert.equal(h.page.data.context.currentPacket.version, 2); assert.deepEqual(h.page.data.context.packets.find(row => row.version === 1), old);
});

test('boss, finance and headquarters remain readonly; server flags alone do not authorize an unrelated engineer', async () => {
  for (const [role, orgType] of [['boss', 'store'], ['finance', 'store'], ['finance-lead', 'store'], ['hq-admin', 'headquarters']]) {
    const h = harness({ role, orgType, kind: 'pending' }); await h.open(); h.page.toggleConfirmed(); await h.page.submit(action('receive')); await h.page.submit(action('submit'));
    assert.equal(h.page.data.editing, false, role); assert.equal(h.controls().some(node => node.attrs?.bindtap === 'submit'), false, role); assert.equal(h.callsFor('source').length, 0);
  }
  const other = harness({ role: 'engineer', kind: 'pending' }); other.state.currentPacket.managerUserId = '9'; other.state.packets[0].managerUserId = '9'; await other.open(); assert.equal(other.page.data.editing, false);
  const noPermission = harness({ permissions: [] }); await noPermission.open(); assert.equal(noPermission.page.data.context.canSubmit, false);
});

test('sales read accepted handover and project ID without entering engineering domain; authorized engineering/finance may open it', async () => {
  for (const role of ['sales', 'sales-lead']) {
    const h = harness({ role, kind: 'accepted' }); await h.open(); assert.equal(h.page.data.context.projectId, '55'); h.page.openProject();
    assert.equal(h.navigations.length, 0); assert.equal(h.controls().some(node => node.attrs?.bindtap === 'openProject'), false);
  }
  for (const role of ['engineer', 'finance', 'boss']) {
    const allowed = harness({ role, kind: 'accepted', permissions: ['franchise:engineering:query'] }); await allowed.open(); allowed.page.openProject(); assert.equal(allowed.navigations.length, 1);
  }
  const missing = harness({ role: 'engineer', kind: 'accepted', permissions: ['franchise:commission:receive'] }); await missing.open(); missing.page.openProject(); assert.equal(missing.navigations.length, 0);
});

test('cross-response request or result changes refuse to mix source and handover versions', async () => {
  for (const change of [value => value.currentResult.version++, value => value.currentResult.data.requestVersion++, value => value.customerId = '43', value => value.commissionId = '92', value => value.currentRequest.paymentPlan.companyId = '10']) {
    const h = harness(); change(h.source); await h.open(); assert.equal(h.page.data.ready, false); assert.equal(h.page.data.source, null); await h.page.submit(action('submit')); assert.equal(h.callsFor('submit').length, 0);
  }
});

test('failed receiver list can retry without losing selected files or draft; stale inactive/self choices are excluded', async () => {
  const h = harness({ api: { receivers: async () => { throw Error('network'); } } }); await h.open(); h.page.input(event('note', '保留我的交底'));
  await h.page.startUpload({ path: '/customer.pdf' }); assert.equal(h.page.data.receiverError, 'network'); delete h.overrides.receivers; await h.page.load();
  assert.equal(h.page.data.form.note, '保留我的交底'); assert.equal(h.page.data.form.confirmationAssetId, '103'); assert.deepEqual(h.page.data.receivers.map(row => row.userId), ['8', '9']);
});

test('file chooser return/onShow preserves the original edit, while identity change ignores old chooser and upload', async () => {
  const h = harness(); await h.open(); const original = h.page._edit; h.page.chooseFile(); h.page.onHide(); await h.open();
  assert.equal(h.page._edit, original); h.filePicker.success({ tempFiles: [{ path: '/chosen.pdf' }] }); await tick(); assert.equal(h.page.data.form.confirmationAssetId, '103');
  h.page.chooseFile(); h.switchSession(); h.filePicker.success({ tempFiles: [{ path: '/old.pdf' }] }); assert.equal(h.page._pendingFile, null);
  const late = deferred(), pending = harness({ upload: () => late.promise }); await pending.open(); const uploading = pending.page.startUpload({ path: '/old.pdf' }); pending.switchSession(); await pending.open();
  late.resolve({ assetId: '104', fileName: 'old.pdf', mimeType: 'application/pdf' }); await uploading; assert.equal(pending.page.data.form.confirmationAssetId, '');
});

test('unknown result freezes immutable body/key and prevents edits, navigation and resubmission with another key', async () => {
  const h = harness({ api: { submit: async () => { throw Error('response lost'); } } }); await h.open(); await h.fill(); await h.page.submit(action('submit'));
  assert.equal(h.page.data.uncertain, true); const note = h.page.data.form.note; h.page.input(event('note', 'wrong new request')); h.page.changeTab({ currentTarget: { dataset: { tab: 'history' } } }); h.page.navigationBack();
  assert.equal(h.page.data.form.note, note); assert.equal(h.page.data.tab, 'current'); assert.equal(h.navigations.length, 0);
  delete h.overrides.submit; await h.page.runPending(); assert.deepEqual(h.callsFor('submit')[0].args, h.callsFor('submit')[1].args); assert.equal(h.page.data.uncertain, false);
});

test('write invalidates late GET and refuses lower revisions; explicit conflict reload replaces draft only after confirmation', async () => {
  const h = harness(); await h.open(); await h.fill(); const pending = deferred(); h.overrides.get = () => pending.promise; const reading = h.page.load();
  await h.page.submit(action('submit')); pending.resolve(state()); await reading; assert.equal(h.page.data.context.revision, 3); assert.equal(h.page.data.context.currentPacket.version, 1);
  h.overrides.get = async () => state(); await h.page.load(); assert.equal(h.page.data.context.revision, 3);
  const conflict = harness({ api: { submit: async () => { throw { code: 1099000001, msg: '版本更新' }; } } }); await conflict.open(); await conflict.fill(); await conflict.page.submit(action('submit'));
  assert.equal(conflict.page.data.conflict, true); assert.ok(conflict.page.data.form.note); conflict.page.reloadLatest(); assert.ok(conflict.modal); conflict.modal.success({ confirm: true }); await tick();
  assert.equal(conflict.page.data.conflict, false); assert.equal(conflict.page.data.form.note, ''); assert.equal(conflict.page._localFile, null);
});

test('revocation clears customer, local files and all pending commands; late writes cannot refill a switched session', async () => {
  for (const code of [401, 403, 404, 1110000000, 1110000001, 1112000000]) {
    const h = harness(); await h.open(); await h.fill(); h.overrides.get = async () => { throw { code, msg: '权限已变化' }; }; await h.page.load();
    assert.equal(h.page.data.context, null); assert.equal(h.page.data.form, null); assert.equal(h.page._localFile, null); assert.equal(h.page._pending, null);
  }
  const late = deferred(), h = harness({ api: { submit: () => late.promise } }); await h.open(); await h.fill(); const writing = h.page.submit(action('submit'));
  h.switchSession({ roleCode: 'boss', userId: '15' }); await h.open(); late.resolve(state('pending')); await writing;
  assert.equal(h.page.data.context.revision, 2); assert.equal(h.page.data.editing, false); assert.equal(h.toasts.length, 0);
});

test('frozen files require exact slot/version and fresh authorization after download; source files use only R09 route', async () => {
  const h = harness({ kind: 'pending', role: 'engineer' }); await h.open(); const file = h.page.data.context.currentPacket.files[0]; await h.page.previewFile(fileEvent(file));
  assert.deepEqual(h.callsFor('download')[0].args, ['91', 1, 'RESULT']); assert.equal(h.callsFor('get').length, 2); assert.equal(h.previews.length, 1);
  await h.page.previewFile(fileEvent({ ...file, assetId: '999' })); assert.equal(h.callsFor('download').length, 1);
  const sales = harness(); await sales.open(); await sales.page.previewFile(fileEvent(sales.page.data.source.files[0])); assert.deepEqual(sales.callsFor('sourceDownload')[0].args, ['42', 1, '900719925474099999']); assert.equal(sales.callsFor('download').length, 0);
  const revoke = harness({ kind: 'pending', role: 'engineer', download: async () => { revoke.overrides.get = async () => { throw { code: 404, msg: '接收人已变更' }; }; return { tempFilePath: '/private.pdf', contentType: 'application/pdf' }; } });
  await revoke.open(); await revoke.page.previewFile(fileEvent(revoke.page.data.context.currentPacket.files[0])); assert.equal(revoke.previews.length, 0); assert.equal(revoke.page.data.context, null);
});

test('late file response after role switch never opens bytes; asset denial first clears old view and rechecks access', async () => {
  const late = deferred(), h = harness({ kind: 'pending', role: 'engineer', download: () => late.promise }); await h.open(); const preview = h.page.previewFile(fileEvent(h.page.data.context.currentPacket.files[0])); await tick();
  h.switchSession({ roleCode: 'finance' }); await h.open(); late.resolve({ tempFilePath: '/old.pdf', contentType: 'application/pdf' }); await preview; assert.equal(h.previews.length, 0);
  let sawCleared = false; const denied = harness({ kind: 'pending', role: 'engineer', api: { download: async () => { denied.overrides.get = async () => { sawCleared = denied.page.data.context === null && denied.page._localFile === null; throw { code: 404, msg: '不可读' }; }; throw { code: 1099000002 }; } } });
  await denied.open(); await denied.page.previewFile(fileEvent(denied.page.data.context.currentPacket.files[0])); assert.equal(sawCleared, true); assert.equal(denied.page.data.context, null);
});

test('real WXML shows four packet files, exact form limits, readonly history and decision buttons only for current receiver', async () => {
  const submit = harness(); await submit.open(); const controls = submit.controls(); assert.equal(controls.find(node => node.tag === 'picker' && node.attrs.mode === 'date').attrs.end, '2026-11-25');
  assert.equal(Number(controls.find(node => node.attrs?.['data-key'] === 'note').attrs.maxlength), 1000); assert.equal(controls.filter(node => node.attrs?.bindtap === 'previewFile').length, 3);
  const h = harness({ kind: 'pending', role: 'engineer' }); await h.open(); assert.equal(h.controls().filter(node => node.attrs?.bindtap === 'previewFile').length, 4);
  assert.equal(Number(h.controls().find(node => node.attrs?.['data-key'] === 'reason').attrs.maxlength), 500); assert.equal(h.controls().filter(node => node.attrs?.bindtap === 'submit').length, 2);
  h.page.changeTab({ currentTarget: { dataset: { tab: 'history' } } }); h.page.toggleHistory({ currentTarget: { dataset: { version: 1 } } });
  assert.equal(h.controls().filter(node => node.attrs?.bindtap === 'previewFile').length, 4); assert.equal(h.controls().some(node => node.tag === 'textarea' || node.attrs?.bindtap === 'submit'), false);
});

test('new-ticket recovery is explicit, retains bytes and does not associate an unconfirmed confirmation file', async () => {
  let attempts = 0, first; const h = harness({ upload: async pending => {
    if (++attempts === 1) { first = pending; pending.bytes = new ArrayBuffer(8); pending.mimeType = 'application/pdf'; pending.ticket = { assetId: '103' }; pending.restartRequired = true; throw { code: 1071000001, msg: '资产尚未上传完成' }; }
    assert.notEqual(pending, first); assert.equal(pending.ticket, undefined); assert.equal(pending.bytes, first.bytes); return { assetId: '104', fileName: 'confirmation.pdf', mimeType: 'application/pdf' };
  } }); await h.open(); await h.page.startUpload({ path: '/confirmation.pdf' }); assert.equal(h.page.data.form.confirmationAssetId, ''); assert.equal(h.page.data.uploadRestartAllowed, true);
  await h.page.restartUpload(); assert.equal(h.page.data.form.confirmationAssetId, '104'); assert.equal(h.page.data.uploadFailed, false);
});

test('invalid deep link never requests private data; modal confirmation during a new submission cannot discard it', async () => {
  const invalid = harness({ route: { commissionId: 'https://fake.test/' } }); await invalid.open(); assert.equal(invalid.calls.length, 0);
  const h = harness(); await h.open(); await h.fill(); h.page.reloadLatest(); h.page.data.busy = true; h.modal.success({ confirm: true }); await tick(); assert.equal(h.callsFor('get').length, 1);
  h.page.data.busy = false; h.page.navigationBack(); h.page.data.uncertain = true; h.modal.success({ confirm: true }); assert.equal(h.navigations.length, 0);
});

test('legacy handover/receive deep links redirect EXTERNAL after authorized GET without invoking old command renderer', async () => {
  for (const view of ['handover', 'receive']) {
    let definition; const opened = [], read = [];
    load('miniprogram/pages/workflow/commission/index.js', { '../../../utils/view-container': { makeFormHandlers: () => ({}) }, './payment-flow': () => ({}),
      '../../../utils/page-navigation': { open: (...args) => opened.push(args) }, '../../../api/franchise/commission': { commissions: { get: async id => { read.push(id); return { commission: { id, workflowMode: 'EXTERNAL' } }; } } },
      './presentation': { normalize: () => { throw Error('external must not render legacy action'); } } }, { Page: value => definition = value });
    const page = { ...definition, id: '91', view, data: {}, setData(value) { Object.assign(this.data, value); }, failState: value => { page.cleared = value; } };
    await page.refreshCommission(() => true, false); assert.deepEqual(read, ['91']); assert.deepEqual(copy(opened), [['/pages/external-handover/index?commissionId=91', { replace: true }]]);
    assert.ok(page.cleared);
  }
});

module.exports = { load, harness, state, packet, plan, deferred, tick };
