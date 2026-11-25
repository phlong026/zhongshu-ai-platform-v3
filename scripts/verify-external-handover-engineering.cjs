const test = require('node:test'), assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), { createRequire } = require('node:module');
global.wx = { getStorageSync: () => '', getSystemInfoSync: () => ({}) };
const { projectView } = require('../miniprogram/utils/platform-engineering');
const root = path.resolve(__dirname, '..'), copy = value => JSON.parse(JSON.stringify(value));
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
function load(file, mocks, globals = {}) { const filename = path.join(root, file), real = createRequire(filename), module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, require: name => name in mocks ? mocks[name] : real(name), ...globals }, { filename }); return module.exports; }
function bundle() { return { detail: { id: '55', name: '本店工程', handoverMode: 'EXTERNAL', canReadHandover: true, sourceCommissionId: '91', externalHandoverVersion: 2, contractAssetId: '700', nodes: [],
  documents: ['900719925474099999', '101', '102', '103'].map((fileRef, index) => ({ name: ['外部设计成果', '外部施工图', '专业审核依据', '客户确认凭据'][index], fileRef })) } }; }
function response() { return { commissionId: '91', projectId: '55', mode: 'EXTERNAL', packets: [{ version: 2, status: 'ACCEPTED', files: ['RESULT', 'DRAWING', 'REVIEW', 'CONFIRMATION'].map((slot, index) => ({ slot, assetId: ['900719925474099999', '101', '102', '103'][index], title: slot })) }] }; }
function harness(options = {}) {
  let definition, generation = 1, sequence = 0; const h = { calls: [], previews: [], notices: [], state: response(), bundle: bundle(), overrides: options };
  const post = { postId: '8', userId: '8', orgId: '9', orgType: 'store', roleCode: 'engineer' };
  const http = { get: async url => { h.calls.push(['GET', url]); return options.get ? options.get(url) : copy(h.state); },
    post: async (...args) => { h.calls.push(['POST', ...args]); return options.post ? options.post(...args) : { downloadUrl: 'https://store.test/authorized' }; },
    token: () => 'token', currentPostId: () => post.postId, sessionGeneration: () => generation,
    sessionGuard: () => { const g = generation; return () => g === generation; }, requestGuard: () => { const s = ++sequence, g = generation; return () => s === sequence && g === generation; } };
  const globals = { wx: { showToast: value => h.notices.push(value), previewImage: value => h.previews.push(value), openDocument: value => h.previews.push(value) } };
  const helper = load('miniprogram/pages/build/external-handover.js', { '../../api/http': http, '../../utils/api-scope': () => 'scope-' + generation,
    '../../utils/b-asset-download': { downloadTicketFile: options.download || (async () => ({ tempFilePath: '/scope-file.pdf', contentType: 'application/pdf' })) } }, globals);
  const P = { loadProject: () => options.loadProject ? options.loadProject() : Promise.resolve(copy(h.bundle)),
    recordView: value => { const p = projectView(value.detail); return { p, docs: p.documents, form: {}, fields: [], actions: [], links: [], events: [], collections: [] }; },
    online: () => true, errorText: error => String(error.msg || error.message || 'failed'), idemFingerprint: () => 'stable', perform: options.perform || (async () => ({})) };
  const mocks = { '../../../utils/platform-engineering': P, '../external-handover': helper, '../../../api/http': http,
    '../../../api/auth': { currentApiPost: () => post }, '../../../utils/view-container': { makeFormHandlers: () => ({}) },
    '../../../api/franchise/engineering': { cameras: { list: async () => [] }, assets: { downloadTicket: async id => { h.calls.push(['GENERIC', id]); return { downloadUrl: 'https://store.test/generic' }; } } },
    '../../../utils/organization': { has: () => false }, '../../../utils/account': { localDate: () => '2026-11-25' } };
  load('miniprogram/pages/build/record/index.js', mocks, { ...globals, Page: value => definition = value });
  h.page = { ...definition, id: '55', data: copy(definition.data), setData(value) { Object.assign(this.data, copy(value)); } };
  h.page.data.view = 'documents'; h.open = () => h.page.refreshOnline(true); h.helper = helper; h.switchSession = () => { generation++; }; return h;
}

test('real projectView numeric fileRef is rebound through authorized accepted packet without numeric coercion or generic URL', async () => {
  const h = harness(); assert.equal(projectView(h.bundle.detail).documents[0].file.assetId, undefined); await h.open();
  const docs = h.page.data.docs; assert.equal(docs.length, 5); assert.equal(docs[0].file.assetId, '900719925474099999');
  assert.equal(docs[0].file.externalVersion, 2); assert.equal(docs[0].file.slot, 'RESULT'); assert.equal(docs[0].file.commissionId, '91');
  assert.equal(docs[4].file.assetId, '700'); assert.equal(docs[4].file.externalHandover, undefined); assert.equal(h.page.data.externalHandoverVersion, 2);
  await h.page.preview({ currentTarget: { dataset: { file: docs[0].file } } });
  assert.deepEqual(h.calls.filter(row => row[0] === 'POST'), [['POST', '/franchise/commissions/91/external-handover/versions/2/assets/RESULT/download-tickets']]);
  assert.equal(h.calls.some(row => row[0] === 'GENERIC'), false); assert.equal(h.calls.filter(row => row[0] === 'GET').length, 2); assert.equal(h.previews.length, 1);
});

test('mismatched project/version/file scope clears the old project and refuses any fallback download', async () => {
  for (const mutate of [h => h.state.projectId = '56', h => h.state.packets[0].version = 3, h => h.bundle.detail.documents[0].fileRef = '999', h => h.bundle.detail.documents[0].fileRef = 900719925474099999]) {
    const h = harness(); h.page.data.p = { name: 'old private project' }; h.page.data.form = { note: 'old draft' }; mutate(h); await h.open();
    assert.equal(h.page.data.p, null); assert.deepEqual(h.page.data.form, {}); assert.equal(h.page.data.docs?.length || 0, 0); assert.equal(h.calls.some(row => row[0] === 'POST' || row[0] === 'GENERIC'), false);
  }
  const h = harness(); await h.open(); await h.page.previewAsset({ currentTarget: { dataset: { assetId: '900719925474099999' } } }); assert.equal(h.calls.some(row => row[0] === 'GENERIC' || row[0] === 'POST'), false);
});

test('engineering transfer while bytes are downloading causes fresh authorization to clear the entire private page', async () => {
  const h = harness({ download: async () => { h.overrides.get = async () => { throw { code: 404, msg: '工程已转交' }; }; return { tempFilePath: '/old.pdf', contentType: 'application/pdf' }; } });
  await h.open(); h.page.data.expenseRows = [{ amount: 'private' }]; h.page.data.samplePreview = { text: 'old private note' };
  await h.page.previewAsset({ currentTarget: { dataset: h.page.data.docs[1].file } });
  assert.equal(h.previews.length, 0); assert.equal(h.page.data.p, null); assert.equal(h.page.data.docs.length, 0); assert.equal(h.page.data.expenseRows.length, 0); assert.equal(h.page.data.samplePreview, null); assert.equal(h.page.data.externalHandoverUrl, '');
});

test('late project read and write cannot repopulate cleared data after same-session scope revocation', async () => {
  const h = harness(); await h.open(); const reading = deferred(); h.overrides.loadProject = () => reading.promise; const request = h.page.refreshOnline(false);
  h.helper.clear(h.page, '工程资料权限已变化'); reading.resolve(copy(h.bundle)); await request;
  assert.equal(h.page.data.p, null); assert.equal(h.page.data.docs.length, 0);
  const write = deferred(), writer = harness({ perform: () => write.promise }); await writer.open(); const pending = writer.page.performOnline('expense-register', { note: 'old' });
  writer.helper.clear(writer.page, '工程已转交'); write.resolve({}); await pending; assert.equal(writer.page.data.p, null); assert.equal(writer.notices.length, 0); assert.equal(writer.page.data.busy, false);
});

test('switching roles or hiding before authorized bytes arrive never opens the previous file', async () => {
  for (const hide of [false, true]) {
    const download = deferred(), h = harness({ download: () => download.promise }); await h.open(); const pending = h.page.previewAsset({ currentTarget: { dataset: h.page.data.docs[0].file } }); await tick();
    if (hide) h.page.onHide(); else h.switchSession(); download.resolve({ tempFilePath: '/late.pdf', contentType: 'application/pdf' }); await pending;
    assert.equal(h.previews.length, 0); assert.equal(h.calls.filter(row => row[0] === 'GET').length, 1);
  }
});

test('legacy engineering projects do not request external handover or change dispatch-scoped files', async () => {
  const h = harness(); h.bundle.detail.handoverMode = 'LEGACY'; h.bundle.detail.documents = [{ name: '历史施工图', fileRef: JSON.stringify({ assetId: '11', commissionId: '91', dispatchSeq: 2, slot: 'DRAWING' }) }];
  await h.open(); assert.equal(h.calls.length, 0); assert.equal(h.page.data.docs[0].file.dispatchSeq, 2); assert.equal(h.page.data.externalHandoverUrl, '');
});

test('limited engineering detail keeps basic project visible while missing/false canReadHandover never reads or shows packet files', async () => {
  for (const permission of [false, undefined]) {
    const h = harness(); h.bundle.detail.canReadHandover = permission; h.bundle.detail.contractAssetId = null;
    await h.open(); assert.equal(h.page.data.p.name, '本店工程'); assert.equal(h.page.data.docs.length, 0); assert.equal(h.page.data.p.documents.length, 0);
    assert.equal(h.page.data.externalHandoverUrl, ''); assert.equal(h.calls.length, 0);
  }
});
