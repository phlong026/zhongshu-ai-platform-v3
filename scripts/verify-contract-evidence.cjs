const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const { createRequire } = require('node:module');
const { render } = require('./role-page-runtime.cjs');
const model = require('../miniprogram/pages/contract-documents/model');
const root = path.resolve(__dirname, '..'), copy = value => JSON.parse(JSON.stringify(value));
const event = (key, value) => ({ currentTarget: { dataset: { key } }, detail: { value } });
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
function load(file, mocks, globals = {}) {
  const source = path.join(root, file), real = createRequire(source), module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(source, 'utf8'), { module, require: name => name in mocks ? mocks[name] : real(name), ...globals }, { filename: source });
  return module.exports;
}
function state(status = 'WAIT_SIGN') {
  return { customerName: '本店客户', canEdit: true, contract: { id: '91', customerId: '42', companyId: '9', status,
    totalCents: 100000, revision: 3, signedDate: '', assetId: null }, stages: [] };
}
function harness(options = {}) {
  let definition, generation = 1, key = 0, token = 'token', clock = Date.UTC(2026, 10, 25, 1);
  let post = { postId: '7', orgId: '9', orgType: 'store', roleCode: options.role || 'sales', permissions: options.permissions };
  const h = { state: state(options.status), calls: [], toasts: [], navigations: [], previews: [], overrides: options.api || {} };
  const api = Object.fromEntries(['get', 'sign', 'evidence', 'download'].map(name => [name, async (...args) => {
    h.calls.push({ name, args: copy(args) }); if (h.overrides[name]) return h.overrides[name](...args);
    if (name === 'get') return copy(h.state);
    if (name === 'download') return { downloadUrl: 'https://store.test/contract.pdf', expiresAt: 1891234567 };
    const body = args[1]; Object.assign(h.state.contract, { status: 'SIGNED', revision: h.state.contract.revision + 1, signedDate: body.signedDate, assetId: body.assetId });
    return { contractId: '91', status: 'SIGNED', revision: h.state.contract.revision, signedDate: body.signedDate, assetId: body.assetId };
  }]));
  const http = { token: () => token, currentPostId: () => post?.postId || '' };
  const upload = options.upload || (async () => ({ assetId: '900719925474099999', fileName: '已签合同.pdf', mimeType: 'application/pdf' }));
  load('miniprogram/pages/contract-documents/index.js', {
    '../../api/http': http, '../../api/auth': { currentApiPost: () => post }, '../../utils/api-scope': () => JSON.stringify([generation, post?.postId, post?.orgId]),
    '../../utils/page-navigation': { open: url => h.navigations.push({ url }), back: value => h.navigations.push({ back: value }) },
    '../../utils/platform-crm': { commandKey: prefix => prefix + '-' + ++key }, './api': api, './upload': { run: upload },
    '../../utils/b-asset-download': { downloadTicketFile: options.download || (async () => ({ tempFilePath: '/download.pdf', contentType: 'application/pdf' })) }
  }, { Page: value => definition = value, console, Date: class extends Date { static now() { return clock; } },
    wx: { showToast: value => h.toasts.push(value), showModal: value => h.modal = value,
      chooseMessageFile: value => h.filePicker = value, chooseMedia: value => h.photoPicker = value,
      openDocument: value => h.previews.push(value), previewImage: value => h.previews.push(value) }
  });
  h.page = { ...definition, data: copy(definition.data), setData(patch) { Object.assign(this.data, copy(patch)); } };
  h.page.onLoad({ contractId: options.contractId || '91' }); h.open = () => h.page.onShow();
  h.callsFor = name => h.calls.filter(call => call.name === name);
  h.fill = async () => { if (!h.page.data.editing) h.page.startEdit(); h.page.input(event('signedDate', '2026-11-24')); await h.page.startUpload({ path: '/signed.pdf', name: '已签合同.pdf', size: 20 }); };
  h.switchSession = (next = {}) => { generation += 1; post = { ...post, postId: String(7 + generation), ...next }; };
  h.logout = () => { token = ''; }; h.advanceDay = () => { clock += 24 * 3600000; };
  h.visibleControls = () => { const flatten = nodes => nodes.flatMap(n => [n, ...flatten(n.children || [])]); return flatten(render(path.join(root, 'miniprogram/pages/contract-documents/index.wxml'), h.page.data)); };
  return h;
}

test('实际签约日期、长资产ID与已签补录原因校验，不把本地勾选当完成', () => {
  const context = { canWrite: true, status: 'WAIT_SIGN', revision: 3 }, form = { signedDate: '2026-11-24', assetId: '900719925474099999' };
  assert.deepEqual(model.payload(form, context, '2026-11-25'), { revision: 3, signedDate: '2026-11-24', assetId: '900719925474099999' });
  for (const date of ['', '2026-02-30', '2026-11-24T08:00', '2026-11-26']) assert.throws(() => model.payload({ ...form, signedDate: date }, context, '2026-11-25'), /日期/);
  assert.throws(() => model.payload({ ...form, assetId: '' }, context, '2026-11-25'), /已签合同/);
  context.status = 'SIGNED'; assert.throws(() => model.payload(form, context, '2026-11-25'), /原因/);
  assert.throws(() => model.payload({ ...form, reason: 'a'.repeat(501) }, context, '2026-11-25'), /500/);
  assert.equal(model.payload({ ...form, reason: ' 补录纸质合同 ' }, context, '2026-11-25').reason, '补录纸质合同');
});

test('付款计划canEdit不能授予财务签约权限；总部、作废和缺写许可均只读', async () => {
  for (const role of ['finance', 'finance-lead', 'owner', 'hq-admin']) {
    const h = harness({ role }); await h.open(); h.page.startEdit(); await h.page.submit();
    assert.equal(h.page.data.context.canWrite, false); assert.equal(h.page.data.editing, false); assert.equal(h.callsFor('sign').length, 0);
    assert.equal(h.visibleControls().some(n => n.attrs?.bindtap === 'submit' || n.attrs?.bindtap === 'startEdit'), false);
  }
  const revokedPermission = harness({ permissions: [] }); await revokedPermission.open(); assert.equal(revokedPermission.page.data.context.canWrite, false);
  const voided = harness({ status: 'VOIDED' }); await voided.open(); assert.equal(voided.page.data.context.canWrite, false);
  const sales = harness(); sales.state.canEdit = false; await sales.open(); assert.equal(sales.page.data.context.canWrite, true);
});

test('待签合同需要日期和已验收文件，签约只提交证据及版本，不重建付款计划', async () => {
  const h = harness(); await h.open(); await h.page.submit(); assert.equal(h.callsFor('sign').length, 0);
  await h.fill(); await h.page.submit();
  const call = h.callsFor('sign')[0]; assert.deepEqual(call.args.slice(0, 2), ['91', { revision: 3, signedDate: '2026-11-24', assetId: '900719925474099999' }]);
  assert.equal(h.callsFor('evidence').length, 0); assert.equal(h.page.data.context.status, 'SIGNED'); assert.equal(h.page.data.editing, false);
  assert.equal(h.page.data.context.signedDate, '2026-11-24'); assert.equal(h.page._localFile, null);
});

test('旧SIGNED缺日期文件保持原状态，补录必须说明原因且不发送金额或期次', async () => {
  const h = harness({ status: 'SIGNED' }); await h.open(); assert.equal(h.page.data.context.status, 'SIGNED'); assert.equal(h.page.data.context.hasEvidence, false);
  await h.fill(); await h.page.submit(); assert.equal(h.callsFor('evidence').length, 0);
  h.page.input(event('reason', '补录客户签字扫描件')); await h.page.submit();
  const body = h.callsFor('evidence')[0].args[1]; assert.deepEqual(Object.keys(body).sort(), ['assetId', 'reason', 'revision', 'signedDate']);
  assert.equal(body.reason, '补录客户签字扫描件'); assert.equal(h.callsFor('sign').length, 0); assert.equal(h.page.data.context.hasEvidence, true);
});

test('上传失败保留文件以重试；验收前不能签约，重试后可提交返回的资产', async () => {
  let attempts = 0, original;
  const h = harness({ upload: async pending => { attempts += 1; if (!original) original = pending; else assert.equal(pending, original); if (attempts === 1) throw Error('网络超时'); return { assetId: '88', fileName: '签字.pdf', mimeType: 'application/pdf' }; } });
  await h.open(); h.page.input(event('signedDate', '2026-11-24')); await h.page.startUpload({ path: '/signed.pdf' });
  assert.equal(h.page.data.uploadFailed, true); await h.page.submit(); assert.equal(h.callsFor('sign').length, 0);
  await h.page.retryUpload(); assert.equal(h.page.data.form.assetId, '88'); await h.page.submit(); assert.equal(h.callsFor('sign')[0].args[1].assetId, '88');
});

test('未知办理结果冻结原日期、资产和原幂等键；重试不重复上传或换成补证命令', async () => {
  const h = harness({ api: { sign: async () => { throw { code: -1 }; } } }); await h.open(); await h.fill(); await h.page.submit();
  assert.equal(h.page.data.uncertain, true); h.page.input(event('signedDate', '2026-11-20')); h.page.chooseFile();
  assert.equal(h.filePicker, undefined); h.page.navigationBack(); assert.equal(h.navigations.length, 0);
  await h.page.submit(); assert.equal(h.callsFor('sign').length, 1);
  delete h.overrides.sign; await h.page.runPending(); assert.deepEqual(h.callsFor('sign')[1].args, h.callsFor('sign')[0].args);
  assert.equal(h.callsFor('evidence').length, 0); assert.equal(h.page.data.uncertain, false);
});

test('签约结果未知时切回页面可见原请求重试，保持签约正文与幂等键', async () => {
  const h = harness({ api: { sign: async () => { throw { code: -1 }; } } });
  await h.open(); await h.fill(); await h.page.submit();
  const original = h.callsFor('sign')[0], pending = h.page._pending;
  h.page.onHide(); await h.page.onShow();
  assert.equal(h.page.data.uncertain, true); assert.equal(h.page._pending, pending);
  assert.equal(h.page.data.error, '');
  const controls = h.visibleControls(), retry = controls.find(node => node.attrs?.bindtap === 'runPending');
  assert.ok(retry, '读取最新合同成功不会隐藏尚未确认签约的重试入口');
  assert.match(controls.map(node => node.text || '').join(''), /办理结果尚未确认/);
  assert.equal(retry.attrs.disabled, false);
  delete h.overrides.sign; await h.page[retry.attrs.bindtap]();
  assert.deepEqual(h.callsFor('sign')[1].args, original.args);
  assert.equal(h.callsFor('evidence').length, 0);
  assert.equal(h.page.data.uncertain, false); assert.equal(h.page._pending, null);
});

test('版本或状态冲突保留草稿，重载服务器SIGNED状态后才进入补证', async () => {
  const h = harness({ api: { sign: async () => { throw { code: 1099000001, msg: '版本变化' }; } } }); await h.open(); await h.fill(); await h.page.submit();
  assert.equal(h.page.data.conflict, true); assert.equal(h.page.data.dirty, true); assert.equal(h.page.data.uncertain, false);
  Object.assign(h.state.contract, { revision: 4, status: 'SIGNED', assetId: '89', signedDate: '2026-11-25' });
  h.page.reloadLatest(); h.modal.success({ confirm: true }); await tick();
  assert.equal(h.page.data.form.assetId, '89'); assert.equal(h.page.data.editing, false); assert.equal(h.page.data.conflict, false);
  h.page.startEdit(); h.page.input(event('reason', '更正签约日期')); await h.page.submit(); assert.equal(h.callsFor('evidence')[0].args[1].revision, 4);
});

test('资产明确拒绝会移除不可用选择，保留日期并允许重新上传；幂等键冲突不自动换键', async () => {
  const h = harness({ api: { sign: async () => { throw { code: 1099000002, msg: '附件不可用' }; } } }); await h.open(); await h.fill(); await h.page.submit();
  assert.equal(h.page.data.form.assetId, ''); assert.equal(h.page.data.form.signedDate, '2026-11-24'); assert.equal(h.page._localFile, null); assert.equal(h.page.data.uncertain, false);
  await h.fill(); h.overrides.sign = async () => { throw { code: 1099000000, msg: '请求键冲突' }; }; await h.page.submit();
  const first = h.callsFor('sign').at(-1); await h.page.runPending(); assert.deepEqual(h.callsFor('sign').at(-1).args, first.args); assert.equal(h.page.data.uncertain, true);
});

test('合同文件使用受控下载，未保存新文件可本地查看，切岗或隐藏后下载不打开', async () => {
  const h = harness({ status: 'SIGNED' }); Object.assign(h.state.contract, { assetId: '88', signedDate: '2026-11-24' }); await h.open(); await h.page.previewFile();
  assert.deepEqual(h.callsFor('download')[0].args, ['91', '88']); assert.equal(h.previews[0].filePath, '/download.pdf');
  await h.fill(); await h.page.previewFile(); assert.equal(h.callsFor('download').length, 1); assert.equal(h.previews[1].filePath, '/signed.pdf');
  h.page._localFile = null; const delayed = deferred(); h.overrides.download = () => delayed.promise;
  const reading = h.page.previewFile(); h.page.onHide(); delayed.resolve({ downloadUrl: 'https://store.test/old.pdf' }); await reading; assert.equal(h.previews.length, 2);
});

test('同会话迟到GET不能撤回成功签约，低版本响应也不能覆盖新事实', async () => {
  const h = harness(); await h.open(); const delayed = deferred(), old = copy(h.state);
  h.overrides.get = () => delayed.promise; const reading = h.page.load(); await h.fill(); delete h.overrides.get;
  await h.page.submit(); assert.equal(h.page.data.context.revision, 4); delayed.resolve(old); await reading;
  assert.equal(h.page.data.context.status, 'SIGNED'); h.overrides.get = async () => old; await h.page.load(); assert.equal(h.page.data.context.revision, 4);
});

test('合同下载1099000002先清客户和文件，再重新核对对象权限，转交后不保留旧信息', async () => {
  const h = harness({ status: 'SIGNED', api: { download: async () => { throw { code: 1099000002, msg: '无权读取原文件' }; } } });
  Object.assign(h.state.contract, { assetId: '88', signedDate: '2026-11-24' }); await h.open();
  const refresh = deferred(); h.overrides.get = () => refresh.promise;
  const viewing = h.page.previewFile(); await tick();
  assert.equal(h.page.data.context, null); assert.equal(h.page.data.form, null); assert.equal(h.page._localFile, null); assert.equal(h.page._preview, null);
  refresh.reject({ code: 1110000000, msg: '客户已转交' }); await viewing;
  assert.equal(h.page.data.ready, false); assert.equal(h.page.data.context, null); assert.equal(h.previews.length, 0);
});

test('合同仍可读但旧文件失效时，只显示重新授权的上下文并要求有效新文件', async () => {
  const h = harness({ status: 'SIGNED', api: { download: async () => { throw { code: 1099000002 }; } } });
  Object.assign(h.state.contract, { assetId: '88', signedDate: '2026-11-24' }); await h.open(); h.state.customerName = '刷新后的客户名';
  await h.page.previewFile(); assert.equal(h.page.data.context.customerName, '刷新后的客户名'); assert.equal(h.page.data.form.assetId, '');
  assert.equal(h.page.data.context.hasEvidence, false); h.page.startEdit(); assert.equal(h.page.data.form.assetId, '');
});

test('下载期间原附件已替换，重新授权后采用最新合同文件，不要求重复上传', async () => {
  const h = harness({ status: 'SIGNED', api: { download: async () => { throw { code: 1099000002 }; } } });
  Object.assign(h.state.contract, { assetId: '88', signedDate: '2026-11-24' }); await h.open();
  h.state.contract.assetId = '89'; h.state.contract.revision = 4; await h.page.previewFile();
  assert.equal(h.page.data.context.revision, 4); assert.equal(h.page.data.form.assetId, '89'); assert.match(h.page.data.error, /文件已更新/);
});

test('切岗使文件选择、上传及签约响应失效，不向新身份回填合同', async () => {
  const upload = deferred(), h = harness({ upload: () => upload.promise }); await h.open(); h.page.chooseFile();
  h.switchSession(); h.filePicker.success({ tempFiles: [{ path: '/stale.pdf' }] }); assert.equal(h.page._pendingFile, null);
  await h.open(); h.page.input(event('signedDate', '2026-11-24')); const uploading = h.page.startUpload({ path: '/signed.pdf' });
  h.switchSession({ orgId: '10' }); const read = deferred(); h.overrides.get = () => read.promise; const opening = h.open();
  upload.resolve({ assetId: '88', fileName: 'other.pdf' }); await uploading; assert.equal(h.page.data.form, null); assert.equal(h.page._localFile, null);
  read.resolve(copy(h.state)); await opening; assert.equal(h.page.data.ready, false);
  const write = deferred(), signing = harness({ api: { sign: () => write.promise } }); await signing.open(); await signing.fill(); const pending = signing.page.submit();
  signing.switchSession(); await signing.open(); write.resolve({ contractId: '91', status: 'SIGNED', revision: 4 }); await pending;
  assert.equal(signing.toasts.length, 0); assert.equal(signing.page.data.context.status, 'WAIT_SIGN');
});

test('注销、对象撤权或跨公司响应清除客户、草稿、文件与预览；再次显示更新日期上限', async () => {
  for (const code of [401, 403, 404, 1110000000, 1110000001, 1113000004]) {
    const h = harness(); await h.open(); await h.fill(); h.overrides.get = async () => { throw { code, msg: '不可读' }; }; await h.page.load();
    assert.equal(h.page.data.context, null); assert.equal(h.page.data.form, null); assert.equal(h.page._localFile, null); assert.equal(h.page._preview, null);
  }
  const other = harness(); other.state.contract.companyId = '10'; await other.open(); assert.equal(other.page.data.ready, false);
  const h = harness(); await h.open(); assert.equal(h.page.data.today, '2026-11-25'); h.advanceDay(); await h.open(); assert.equal(h.page.data.today, '2026-11-26');
  h.logout(); await h.open(); assert.equal(h.page.data.context, null);
});

test('证据API严格使用签约、补证和合同当前资产下载路径', async () => {
  const calls = [], http = Object.fromEntries(['get', 'post'].map(method => [method, async (...args) => calls.push({ method, args: copy(args) })]));
  const api = load('miniprogram/pages/contract-documents/api.js', { '../../api/http': http });
  await api.get('91'); await api.sign('91', { revision: 3, signedDate: '2026-11-24', assetId: '88' }, 'sign-key');
  await api.evidence('91', { revision: 4, reason: '补证' }, 'evidence-key'); await api.download('91', '900719925474099999');
  assert.equal(calls[0].args[0], '/franchise/sales/contracts/91/payment-plan');
  assert.equal(calls[1].args[0], '/franchise/sales/contracts/91/sign'); assert.deepEqual(calls[1].args[2], { 'Idempotency-Key': 'sign-key' });
  assert.equal(calls[2].args[0], '/franchise/sales/contracts/91/evidence'); assert.deepEqual(calls[2].args[2], { 'Idempotency-Key': 'evidence-key' });
  assert.equal(calls[3].args[0], '/franchise/sales/contracts/91/assets/900719925474099999/download-tickets');
});

function uploadHarness(options = {}) {
  const files = require('../miniprogram/api/finance'), posts = [], data = options.data || Buffer.from('%PDF-1.4\ncontract fixture');
  let reads = 0, puts = 0, current = true;
  const http = { post: async (url, body) => { posts.push({ url, body }); return options.post ? options.post(url, body)
    : url.endsWith('upload-tickets') ? { assetId: '900719925474099999', uploadUrl: 'https://upload.test/contract' } : { uploadStatus: 'ACCEPTED' }; } };
  const api = load('miniprogram/pages/contract-documents/upload.js', { '../../api/http': http,
    '../../api/finance': { readFileBase64: async () => { reads++; return data.toString('base64'); }, voucherMime: files.voucherMime, sha256Hex: files.sha256Hex },
    '../../utils/b-asset-upload': { putTicketBytes: async args => { puts++; if (options.put) await options.put(args); } }
  }, { wx: { base64ToArrayBuffer: value => Uint8Array.from(Buffer.from(value, 'base64')).buffer } });
  return { api, posts, alive: () => current, revoke: () => current = false, counts: () => ({ reads, puts }) };
}

test('合同上传使用CONTRACT_ATTACHMENT及真实MIME/摘要，complete超时复用同资产和已上传字节', async () => {
  let completes = 0;
  const h = uploadHarness({ post: async url => { if (url.endsWith('upload-tickets')) return { assetId: '900719925474099999', uploadUrl: 'https://upload.test/contract' }; if (++completes === 1) throw { code: -1 }; return { uploadStatus: 'ACCEPTED' }; } });
  const pending = { file: { path: '/contract.pdf', name: '已签合同.pdf' } }; await assert.rejects(h.api.run(pending, h.alive));
  const result = await h.api.run(pending, h.alive); assert.equal(result.assetId, '900719925474099999'); assert.equal(result.mimeType, 'application/pdf');
  assert.equal(h.posts[0].body.assetType, 'CONTRACT_ATTACHMENT'); assert.match(h.posts[0].body.sha256, /^[a-f0-9]{64}$/);
  assert.deepEqual(h.counts(), { reads: 1, puts: 1 }); assert.equal(h.posts.filter(p => p.url.endsWith('upload-tickets')).length, 1);
});

test('PUT已经存储但响应丢失时先complete核对成功，不二次消费一次性上传票据', async () => {
  let stored = false, complete = 0;
  const h = uploadHarness({ put: async () => { if (stored) throw Error('一次性票据已消费'); stored = true; throw Error('PUT response lost'); },
    post: async url => url.endsWith('upload-tickets') ? { assetId: '88', uploadUrl: 'https://upload.test/one-use' }
      : (++complete, assert.equal(stored, true), { uploadStatus: 'ACCEPTED' }) });
  const result = await h.api.run({ file: { path: '/contract.pdf' } }, h.alive);
  assert.equal(result.assetId, '88'); assert.equal(complete, 1); assert.deepEqual(h.counts(), { reads: 1, puts: 1 });
});

test('PUT真正未存储时只核对原资产，明确未上传后可显式取新票重传，保留原字节', async () => {
  let tickets = 0, uploaded = false;
  const h = uploadHarness({ put: async () => { if (tickets === 1) throw Error('network offline'); uploaded = true; },
    post: async url => { if (url.endsWith('upload-tickets')) return { assetId: String(87 + ++tickets), uploadUrl: 'https://upload.test/' + tickets };
      if (!uploaded) throw { code: 1071000001, msg: '资产尚未上传完成，请重试' }; return { uploadStatus: 'ACCEPTED' }; } });
  const pending = { file: { path: '/contract.pdf' } }; await assert.rejects(h.api.run(pending, h.alive));
  assert.equal(pending.restartRequired, true); await assert.rejects(h.api.run(pending, h.alive));
  assert.equal(tickets, 1); assert.equal(h.counts().puts, 1);
  const replacement = { file: pending.file, bytes: pending.bytes, mimeType: pending.mimeType };
  assert.equal((await h.api.run(replacement, h.alive)).assetId, '89');
  assert.deepEqual(h.counts(), { reads: 1, puts: 2 }); assert.equal(tickets, 2);
});

test('页面仅在明确未上传后显示重新上传，点击保留文件并丢弃旧票，不自动发第二次PUT', async () => {
  let calls = 0, first;
  const h = harness({ upload: async pending => {
    if (++calls === 1) { first = pending; pending.ticket = { assetId: '88' }; pending.bytes = new ArrayBuffer(8); pending.mimeType = 'application/pdf'; pending.restartRequired = true; throw { code: 1071000001, msg: '资产尚未上传完成，请重试' }; }
    assert.notEqual(pending, first); assert.equal(pending.ticket, undefined); assert.equal(pending.bytes, first.bytes); assert.equal(pending.file.path, '/signed.pdf');
    return { assetId: '89', fileName: '已签.pdf', mimeType: 'application/pdf' };
  } });
  await h.open(); await h.page.startUpload({ path: '/signed.pdf' }); assert.equal(calls, 1); assert.equal(h.page.data.uploadRestartAllowed, true);
  assert.ok(h.visibleControls().some(n => n.attrs?.bindtap === 'restartUpload')); await h.page.restartUpload();
  assert.equal(calls, 2); assert.equal(h.page.data.form.assetId, '89'); assert.equal(h.page.data.uploadFailed, false);
});

test('合同文件20MiB上限、空文件、不支持的图片和REJECTED资产都不能关联，失效会话不complete', async () => {
  const large = uploadHarness(); await assert.rejects(large.api.run({ file: { path: '/large.pdf', size: 20 * 1024 * 1024 + 1 } }, large.alive), /20MB/); assert.equal(large.posts.length, 0);
  const empty = uploadHarness({ data: Buffer.alloc(0) }); await assert.rejects(empty.api.run({ file: { path: '/empty.pdf' } }, empty.alive), /大于0/); assert.equal(empty.posts.length, 0);
  const invalid = uploadHarness({ data: Buffer.from('not an image') }); await assert.rejects(invalid.api.run({ file: { path: '/fake.png' } }, invalid.alive)); assert.equal(invalid.posts.length, 0);
  const rejected = uploadHarness({ post: async url => url.endsWith('upload-tickets') ? { assetId: '88', uploadUrl: 'https://upload.test/contract' } : { uploadStatus: 'REJECTED' } });
  const pending = { file: { path: '/contract.pdf' } }; await assert.rejects(rejected.api.run(pending, rejected.alive), /未通过/); assert.equal(pending.rejected, true);
  await assert.rejects(rejected.api.run(pending, rejected.alive), /重新选择/); assert.equal(rejected.posts.length, 2);
  const stale = uploadHarness({ put: async () => stale.revoke() }); await assert.rejects(stale.api.run({ file: { path: '/contract.pdf' } }, stale.alive), /身份/); assert.equal(stale.posts.length, 1);
});

test('原签约按钮进入证据页，不再以仅revision的命令直接签约', () => {
  let definition; const navigations = [];
  load('miniprogram/pages/workflow/index.js', {
    '../../utils/organization': { roles: {} }, '../../utils/view-container': { routeValue: x => x, fail() {} },
    '../../utils/platform-crm': { can: post => post.roleCode === 'sales' }, '../../api/http': { token: () => 'token' }, './views': { titles: {} },
    '../../utils/page-navigation': { open: url => navigations.push(url) }
  }, { Page: value => definition = value, console, wx: {} });
  const page = { ...definition, __post: { roleCode: 'sales' }, data: { salesContracts: [{ id: '91', status: 'WAIT_SIGN' }] }, platformSalesApi() { throw Error('旧直接签约不可调用'); } };
  page.platformContractSign({ currentTarget: { dataset: { id: '91' } } }); assert.deepEqual(navigations, ['/pages/contract-documents/index?contractId=91']);
  page.data.salesContracts[0].status = 'SIGNED'; page.platformContractSign({ currentTarget: { dataset: { id: '91' } } }); assert.equal(navigations.length, 1);
  page.openContractEvidence({ currentTarget: { dataset: { id: '91' } } }); assert.equal(navigations.length, 2);
});
