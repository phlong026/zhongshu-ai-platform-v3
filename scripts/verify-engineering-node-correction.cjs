const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { render } = require('./role-page-runtime.cjs');

const ids = ['9007199254740994', '9007199254740995', '9007199254740996'];
const recordId = '9007199254740997';
const bodyOf = value => JSON.parse(JSON.stringify(value));
const bundle = () => ({
  detail: { id: '100', managerUserId: '7', actualStartAt: '2026-11-17', nodes: [
    { id: '101', nodeIndex: 1, name: '基础工程', status: 'DONE', completedAt: '2026-11-18' },
    { id: '102', nodeIndex: 2, name: '主体封顶', status: 'IN_PROGRESS', milestone: 'TOPPING' }
  ], siteRecords: [{ id: recordId, node_id: '101', completion: true,
    occurred_at: '2026-11-18', note: '原验收说明', author_user_id: '7', file_asset_ids: JSON.stringify(ids), corrections: '[]' }] },
  collections: { stages: [] }, materials: { plans: [] }, settle: {}, followups: []
});
function harness() {
  const calls = [], uploads = [], module = { exports: {} };
  const api = {
    plan: { correct: async (id, nodeId, body, key) => { calls.push({ id, nodeId, body: bodyOf(body), key }); return bundle().detail; } },
    uploadVoucher: async (file, type) => { uploads.push({ file, type }); if (!file) throw Error('凭据未选择'); return '9007199254740999'; }
  };
  vm.runInNewContext(fs.readFileSync('miniprogram/utils/platform-engineering.js', 'utf8'), {
    module, require: name => name === '../api/franchise/engineering' ? api : name === './epoch-ms'
      ? require('../miniprogram/utils/epoch-ms') : {},
  });
  return { P: module.exports, calls, uploads, api };
}
function pageHarness(P) {
  let definition;
  const http = { currentPostId: () => 'post-1', sessionGuard: () => () => true };
  const setData = function(patch) { for (const [key, value] of Object.entries(patch)) {
    const parts = key.split('.'); let target = this.data;
    for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {});
    target[parts.at(-1)] = value;
  } };
  const native = {};
  vm.runInNewContext(fs.readFileSync('miniprogram/pages/build/record/index.js', 'utf8'), {
    Page: page => { definition = page; }, wx: native,
    require: name => name.endsWith('/platform-engineering') ? P
      : name.endsWith('/view-container') ? { makeFormHandlers: () => ({ fail(message) { this.setData({ error: message }); } }) }
      : name.endsWith('/sample-files') ? { displayFile: file => file, release: () => true }
      : name.endsWith('/http') ? http : {},
  });
  const page = { ...definition, id: '100', data: { ...P.recordView(bundle(), { view: 'node', nodeId: '101', writeRole: true }),
    view: 'node', fileNames: {}, expanded: {}, error: '', busy: false }, setData };
  return { page, native };
}

test('date-only correction submits no upload or replacement and preserves the three-photo readback', async () => {
  const { P, calls, uploads } = harness();
  const detail = await P.perform('node-correct', '100', { nodeDate: '2026-11-19', nodeReason: '日期填写有误' }, { id: '101' }, 'date-only');
  assert.deepEqual(uploads, []);
  assert.deepEqual(calls, [{ id: '100', nodeId: '101', body: { completedAt: '2026-11-19', reason: '日期填写有误' }, key: 'date-only' }]);
  const saved = P.recordView({ ...bundle(), detail }, { view: 'node', nodeId: '101', writeRole: true });
  assert.deepEqual(bodyOf(saved.inspections[0].files.map(file => file.assetId)), ids);
  assert.equal(saved.inspections[0].result, '原验收说明');
});

test('replacing one selected photo sends exact long identifiers and leaves the selection explicit', async () => {
  const { P, calls, uploads } = harness();
  await P.perform('node-correct', '100', { nodeDate: '2026-11-19', nodeReason: '第二张选错',
    nodeRecordId: recordId, nodeReplaceAssetId: ids[1], nodeFile: { path: 'replacement.jpg' }, nodeNote: '更正后的说明' }, { id: '101' }, 'replace-one');
  assert.deepEqual(uploads, [{ file: 'replacement.jpg', type: 'ENG_SITE_PHOTO' }]);
  assert.deepEqual(calls[0].body, { completedAt: '2026-11-19', reason: '第二张选错', note: '更正后的说明',
    replacementRecordId: recordId, replaceFileAssetId: ids[1], fileAssetId: '9007199254740999' });
});

test('partial replacement selection and missing required correction inputs never upload or submit', async () => {
  const { P, calls, uploads } = harness();
  for (const form of [
    { nodeDate: '2026-11-19' }, { nodeReason: '日期更正' },
    { nodeDate: '2026-11-19', nodeReason: '换图', nodeFile: { path: 'new.jpg' } },
    { nodeDate: '2026-11-19', nodeReason: '换图', nodeRecordId: recordId, nodeReplaceAssetId: ids[1] }
  ]) await assert.rejects(P.perform('node-correct', '100', form, { id: '101' }, 'invalid'));
  assert.deepEqual(calls, []); assert.deepEqual(uploads, []);
});

test('the correction form defaults to keeping all evidence and switching or cancelling a target clears only the replacement', () => {
  const { P } = harness(), { page } = pageHarness(P);
  assert.equal(page.data.nodeEvidenceIndex, 0);
  assert.deepEqual(bodyOf(page.data.nodeEvidenceOptions.slice(1).map(option => [option.recordId, option.assetId])), ids.map(id => [recordId, id]));
  page.data.form.nodeDate = '2026-11-19'; page.data.form.nodeReason = '保留填写内容';
  page.selectNodeReplacement({ detail: { value: '2' } });
  assert.equal(page.data.form.nodeRecordId, recordId); assert.equal(page.data.form.nodeReplaceAssetId, ids[1]);
  page.setFile('nodeFile', { path: 'new.jpg', name: 'new.jpg' });
  page.selectNodeReplacement({ detail: { value: '0' } });
  assert.equal(page.data.form.nodeRecordId, ''); assert.equal(page.data.form.nodeReplaceAssetId, '');
  assert.equal(page.data.form.nodeFile, null); assert.equal(page.data.fileNames.nodeFile, '');
  assert.equal(page.data.form.nodeDate, '2026-11-19'); assert.equal(page.data.form.nodeReason, '保留填写内容');
});

test('late selected bytes cannot attach to a different replacement target', () => {
  const { P } = harness(), { page, native } = pageHarness(P);
  let choose, save;
  native.showActionSheet = options => options.success({ tapIndex: 0 });
  native.chooseMedia = options => { choose = options; };
  native.saveFile = options => { save = options; };
  page.selectNodeReplacement({ detail: { value: '1' } });
  page.upload({ currentTarget: { dataset: { key: 'nodeFile' } } });
  choose.success({ tempFiles: [{ tempFilePath: 'old-target.jpg' }] });
  page.selectNodeReplacement({ detail: { value: '2' } });
  save.success({ savedFilePath: 'saved-old-target.jpg' });
  assert.equal(page.data.form.nodeReplaceAssetId, ids[1]); assert.equal(page.data.form.nodeFile, null);
});

test('server history keeps all old photos and renders usable preview actions without replacing current evidence', () => {
  const { P } = harness(), b = bundle();
  b.detail.siteRecords[0].file_asset_ids = JSON.stringify([ids[0], '9007199254740999', ids[2]]);
  b.detail.siteRecords[0].corrections = JSON.stringify([{ occurredAt: '2026-11-18', note: '原验收说明',
    fileAssetIds: ids, reason: '第二张选错', date: '2026-11-20', actorUserId: '7' }]);
  const view = P.recordView(b, { view: 'node', nodeId: '101', writeRole: true });
  assert.deepEqual(bodyOf(view.inspections[0].corrections[0].files.map(file => file.assetId)), ids);
  assert.equal(view.inspections[0].corrections[0].date, '2026-11-18');
  assert.equal(view.inspections[0].corrections[0].note, '原验收说明');
  const flat = rows => rows.flatMap(row => [row, ...flat(row.children || [])]);
  const nodes = flat(render(path.resolve('miniprogram/pages/build/record/index.wxml'), {
    ...view, view: 'node', expanded: { inspections: true }, inspectionHistoryId: recordId
  }));
  const previews = nodes.filter(row => row.tag === 'button' && row.attrs?.bindtap === 'preview');
  assert.equal(previews.filter(row => row.attrs?.class === 'eng-link correction-evidence').length, 3);
  assert.ok(previews.some(row => row.attrs?.['data-file']?.assetId === ids[1]));
  assert.ok(previews.some(row => row.attrs?.['data-file']?.assetId === '9007199254740999'));
});

test('editing only the correction note keeps existing photos and distinguishes retry intents', async () => {
  const { P, calls, uploads } = harness();
  const form = { nodeDate: '2026-11-18', nodeReason: '补正说明', nodeNote: '已按图纸完成' };
  await P.perform('node-correct', '100', form, { id: '101' }, 'note-only');
  assert.deepEqual(uploads, []); assert.equal(calls[0].body.note, '已按图纸完成');
  assert.equal(calls[0].body.fileAssetId, undefined);
  assert.notEqual(P.idemFingerprint('node-correct', form), P.idemFingerprint('node-correct', { ...form, nodeReason: '另一原因' }));
});

test('the completed-node edit entry renders one explicit correction form with no untargeted upload', () => {
  const { P } = harness(), { page } = pageHarness(P);
  const flat = rows => rows.flatMap(row => [row, ...flat(row.children || [])]);
  const visible = () => flat(render(path.resolve('miniprogram/pages/build/record/index.wxml'), page.data));
  assert.ok(visible().some(row => row.attrs?.['data-section'] === 'nodeEdit'));
  page.toggleSection({ currentTarget: { dataset: { section: 'nodeEdit' } } });
  let nodes = visible();
  assert.equal(nodes.filter(row => row.attrs?.bindchange === 'selectNodeReplacement').length, 1,
    'the real completed-node edit entry must expose the explicit replacement selector');
  assert.equal(nodes.filter(row => row.attrs?.bindtap === 'upload' && row.attrs?.['data-key'] === 'nodeFile').length, 0,
    'keeping evidence must not expose the old upload-without-target control');
  assert.equal(nodes.filter(row => row.attrs?.['data-action'] === 'node-correct').length, 1);
  assert.equal(nodes.filter(row => row.attrs?.['data-key'] === 'nodeDate').length, 1);
  page.selectNodeReplacement({ detail: { value: '2' } });
  nodes = visible();
  assert.equal(nodes.filter(row => row.attrs?.bindtap === 'upload' && row.attrs?.['data-key'] === 'nodeFile').length, 1);
  assert.equal(nodes.filter(row => row.attrs?.['data-action'] === 'node-correct').length, 1);
  assert.equal(page.data.form.nodeReplaceAssetId, ids[1]);
});

test('the correction entry follows write and lifecycle permissions while incomplete nodes keep their completion form', () => {
  const { P } = harness();
  const editable = P.recordView(bundle(), { view: 'node', nodeId: '101', writeRole: true });
  assert.equal(editable.canCorrectNode, true); assert.equal(editable.canReopen, true);
  assert.equal(editable.fields.length, 0, 'completed-node correction uses its dedicated evidence form');
  const readOnly = P.recordView(bundle(), { view: 'node', nodeId: '101', writeRole: false });
  assert.equal(readOnly.canCorrectNode, false); assert.equal(readOnly.canReopen, false);
  const started = bundle(); started.detail.nodes[0].status = 'IN_PROGRESS';
  const completion = P.recordView(started, { view: 'node', nodeId: '101', writeRole: true });
  assert.equal(completion.canCorrectNode, false); assert.equal(completion.canReopen, false);
  assert.ok(completion.fields.some(field => field.key === 'file'));
  assert.ok(completion.actions.some(action => action.action === 'node'));
});
