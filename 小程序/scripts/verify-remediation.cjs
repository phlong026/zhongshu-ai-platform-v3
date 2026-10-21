const fs = require('fs');
const vm = require('vm');
const path = require('path');
const assert = require('assert/strict');
const root = path.resolve(__dirname, '..');
const checks = [];
const copy = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
function fixture() {
  const storage = {}, navigation = [], toasts = [];
  let confirm = true;
  const wx = {
    getStorageSync: key => copy(storage[key]) || '', setStorageSync: (key, value) => storage[key] = copy(value), removeStorageSync: key => delete storage[key],
    showToast: options => toasts.push(options.title), showModal: options => { if (options.success) options.success({ confirm, cancel: !confirm }); },
    navigateTo: options => navigation.push(options.url), redirectTo: options => navigation.push(options.url), switchTab: options => navigation.push(options.url),
    getFileSystemManager: () => ({ saveFile: options => options.success({ savedFilePath: 'wxfile://usr/saved-image.jpg' }) })
  };
  function load(file, requireFn) { const module = { exports: {} }; vm.runInNewContext(fs.readFileSync(path.join(root, file), 'utf8'), { module, exports: module.exports, wx, Date, Promise, require: requireFn || (n => n.startsWith('.') ? require(path.resolve(root, path.dirname(file), n)) : undefined), getCurrentPages: () => [1] }); return module.exports; }
  const studio = load('miniprogram/utils/studio.js');
  const creation = load('miniprogram/utils/creation.js');
  function page(name, options = {}) {
    let definition;
    vm.runInNewContext(fs.readFileSync(path.join(root, 'miniprogram/pages', name, 'index.js'), 'utf8'), { Page: p => definition = p, require: n => n.endsWith('/studio') ? studio : creation, wx, Date, Promise, getCurrentPages: () => [1] });
    const instance = Object.assign({}, definition, { data: copy(definition.data), setData(values) {
      for (const [key, value] of Object.entries(values)) { const m = key.match(/^parameters\[(\d+)\]\.value$/); if (m) this.data.parameters[+m[1]].value = value; else this.data[key] = value; }
    } });
    if (instance.onLoad) instance.onLoad(options);
    return instance;
  }
  return { studio, creation, wx, page, navigation, toasts, setConfirm: value => confirm = value };
}
function check(name, fn) { fn(); checks.push({ name, result: 'passed' }); console.log('PASS ' + name); }
const event = (key, value) => ({ currentTarget: { dataset: { [key]: value } } });
check('P1: all studio views tolerate empty tasks, works and records', () => {
  for (const missing of ['tasks', 'records', 'all']) {
    const f = fixture(), state = f.studio.initial();
    if (missing === 'all') { state.tasks = []; state.records = []; state.works = []; } else state[missing] = [];
    f.studio.write(state);
    for (const view of ['remix', 'digital', 'tasks', 'result', 'works', 'library', 'recent', 'records', 'detail', 'publish']) assert.doesNotThrow(() => f.page('studio', { view }));
  }
});
check('P1: missing task never saves another task; generating task cannot be saved', () => {
  const f = fixture(); assert.throws(() => f.studio.saveWork('missing'), /不存在/); assert.throws(() => f.studio.saveWork('task-progress'), /尚未完成/); assert.equal(f.studio.read().works.length, 0);
});
check('P1: missing receipt is an invalid state, never a successful receipt', () => {
  const f = fixture(), p = f.page('studio', { view: 'detail', record: 'missing' }); assert.equal(p.data.detail, null); assert(p.data.invalidMessage); assert.equal(p.data.timeline.length, 0);
});
check('P1: missing publication work cannot submit or create fallback work', () => {
  const f = fixture(), p = f.page('studio', { view: 'publish', work: 'missing' }); assert(p.data.invalidMessage); assert.doesNotThrow(() => p.submitPublish()); assert.equal(f.studio.read().works.length, 0); assert.equal(f.studio.read().records.length, 4);
});
check('P1: historical AI editor restores A while preserving current draft B', () => {
  const f = fixture(), a = f.studio.createDemoTask('ai', '历史A', { mode: 'reference', firstFrame: '/assets/creation-house.jpg', references: [{ id: 'a', type: 'image', path: '/assets/creation-house.jpg' }], parameters: [{ key: 'resolution', value: '1080P' }, { key: 'ratio', value: '16:9' }, { key: 'count', value: '1 个' }] }); f.studio.completeTask(a.id);
  f.wx.setStorageSync('zhongshu-video-draft', { description: '当前B', mode: 'frames', parameters: [] });
  const result = f.page('studio', { view: 'result', task: a.id }); result.editResult(); assert(f.navigation[0].includes('?task=' + a.id));
  const editor = f.page('ai-video', { task: a.id }); assert.equal(editor.data.description, '历史A'); assert.equal(editor.data.mode, 'reference'); assert.equal(editor.data.parameters[0].value, 1); assert.equal(editor.data.parameters[2].value, 1); assert.equal(editor.data.references[0].id, 'a');
  editor.data.description = 'A新版本'; editor.saveDraft(); assert.equal(f.wx.getStorageSync('zhongshu-video-draft').description, '当前B'); assert.equal(f.page('ai-video', { task: a.id }).data.description, 'A新版本'); assert.equal(f.studio.read().tasks[0].script, '历史A');
});
check('P1: publication stores full immutable payload and work snapshot', () => {
  const f = fixture(), w = f.studio.saveWork('task-complete'), p = f.page('studio', { view: 'publish', work: w.id }); Object.assign(p.data, { publishText: '提交的文案', tags: '#测试', cover: '/assets/creation-courtyard.jpg' }); p.submitPublish();
  let record = f.studio.read().records[0]; assert.equal(record.publishText, '提交的文案'); assert.equal(record.tags, '#测试'); assert.equal(record.cover, '/assets/creation-courtyard.jpg'); assert.equal(record.kind, 'digital'); assert.equal(record.workSnapshot.id, w.id); assert(record.submittedAt);
  const state = f.studio.read(); state.works[0].script = '之后的修改'; f.studio.write(state); record = f.studio.read().records[0]; assert.notEqual(record.workSnapshot.script, '之后的修改');
});
check('P1: draft A and B both survive alternating publication editing', () => {
  const f = fixture(), a = f.studio.saveWork('task-complete'), b = f.studio.saveWork('task-retry'); const pa = f.page('studio', { view: 'publish', work: a.id }); pa.data.publishText = 'A编辑'; pa.persistEditor(); const pb = f.page('studio', { view: 'publish', work: b.id }); pb.data.publishText = 'B编辑'; pb.persistEditor(); assert.equal(f.page('studio', { view: 'publish', work: a.id }).data.publishText, 'A编辑'); assert.equal(f.page('studio', { view: 'publish', work: b.id }).data.publishText, 'B编辑');
});
check('P1: old single draft migrates without overwriting per-work drafts', () => {
  const f = fixture(), state = f.studio.initial(); state.publishDraft = { workId: 'old', publishText: '旧草稿' }; f.studio.write(state); assert.equal(f.studio.read().publishDrafts.old.publishText, '旧草稿'); const migrated = f.studio.read(); migrated.publishDrafts.old.publishText = '新草稿'; f.studio.write(migrated); assert.equal(f.studio.read().publishDrafts.old.publishText, '新草稿');
});
check('P1: expired, invalid and near-future scheduling rejected on submit', () => {
  const f = fixture(), w = f.studio.saveWork('task-complete'), p = f.page('studio', { view: 'publish', work: w.id });
  for (const scheduled of ['2000-01-01 10:30', '2099-02-31 10:30', 'not-a-date']) { p.data.scheduled = scheduled; p.submitPublish(); assert.equal(f.studio.read().records.length, 4); assert.throws(() => f.studio.createPublication(w.id, { title: '标题', publishText: '正文', account: 0, scheduled })); }
  assert(f.studio.scheduleError('2099-10-17 10:30', new Date(2099, 9, 17, 10, 29, 30).getTime())); assert.equal(f.studio.scheduleError('2099-10-17 10:30', new Date(2099, 9, 17, 10, 28).getTime()), '');
});
check('publication revalidates schedule after confirmation, before saving', () => {
  const f = fixture(), w = f.studio.saveWork('task-complete'); const p = f.page('studio', { view: 'publish', work: w.id }); p.data.scheduled = '2099-10-17 10:30';
  const show = f.wx.showModal; f.wx.showModal = o => { p.data.scheduled = '2000-01-01 10:30'; show(o); }; p.submitPublish(); assert.equal(f.studio.read().records.length, 4);
});
check('cross-platform publication allowed; same target remains idempotent', () => {
  const f = fixture(), w = f.studio.saveWork('task-complete'), form = { title: '标题', publishText: '正文', tags: '#话题', account: 0 };
  assert.equal(f.studio.createPublication(w.id, form).duplicate, false); assert.equal(f.studio.createPublication(w.id, form).duplicate, true); assert.equal(f.studio.createPublication(w.id, Object.assign({}, form, { account: 1 })).duplicate, false); assert.equal(f.studio.read().records.filter(r => r.workId === w.id).length, 2);
});
check('cancelled publication and generation have no side effects', () => {
  const f = fixture(), w = f.studio.saveWork('task-complete'); f.setConfirm(false); f.page('studio', { view: 'publish', work: w.id }).submitPublish(); assert.equal(f.studio.read().records.length, 4); f.page('studio', { view: 'digital' }).generateDigital(); assert.equal(f.studio.read().tasks.length, 3);
});
check('manual editor input survives close, reopen and a new page instance', () => {
  const f = fixture(), p = f.page('creation'); p.openEditor(); p.inputEditor({ detail: { value: '暂存中的输入' } }); p.closeSheet(); p.openEditor(); assert.equal(p.data.editorText, '暂存中的输入'); const reopened = f.page('creation'); reopened.openEditor(); assert.equal(reopened.data.editorText, '暂存中的输入');
});
check('script edit buffer survives close without overwriting committed script', () => {
  const f = fixture(), p = f.page('studio', { view: 'digital' }), before = p.data.script; p.editScript(); p.inputEdit({ detail: { value: '待保存的修改' } }); p.closeSheet(); p.editScript(); assert.equal(p.data.editingText, '待保存的修改'); assert.equal(f.studio.read().script, before); p.saveScript(); assert.equal(f.studio.read().script, '待保存的修改'); assert.equal(f.studio.read().editorBuffer, undefined);
});
check('using sample resets source copy and preserves manual draft', () => {
  const f = fixture(); f.wx.setStorageSync('zhongshu-creation-draft', { text: '手动稿' }); const state = f.studio.initial(); state.script = '旧稿'; state.original = '旧稿'; f.studio.write(state); const p = f.page('creation'); p.data.link = 'https://example.com/new'; p.extract(); assert.equal(f.studio.read().script, f.studio.script); assert.equal(f.studio.read().original, f.studio.original); assert.equal(f.wx.getStorageSync('zhongshu-creation-draft').text, '手动稿');
});
check('recent example title and exact result ID agree; all includes drafts', () => {
  const f = fixture(), p = f.page('creation'); p.onShow(); const row = p.data.recent[0]; p.editRecent(event('id', row.id)); assert(f.navigation[0].endsWith('task=' + row.taskId)); assert.equal(row.title, f.page('studio', { view: 'result', task: row.taskId }).data.activeTask.title);
  f.wx.setStorageSync('zhongshu-creation-draft', { text: '草稿', updatedAt: 1 }); f.wx.setStorageSync('zhongshu-video-draft', { description: '视频草稿', updatedAt: 2 }); const recent = f.page('studio', { view: 'recent' }).data.recent; assert(recent.some(r => r.id === 'draft')); assert(recent.some(r => r.id === 'video-draft'));
});
check('AI kind, cover and timestamp survive result, work and publication detail', () => {
  const f = fixture(), t = f.studio.createDemoTask('ai', 'AI作品', {}); f.studio.completeTask(t.id); const w = f.studio.saveWork(t.id); const { record } = f.studio.createPublication(w.id, { title: 'AI标题', publishText: '发布文案', cover: '/assets/creation-courtyard.jpg', account: 0 }); const detail = f.page('studio', { view: 'detail', record: record.id }); assert.equal(detail.data.detail.kindLabel, 'AI视频'); assert.equal(detail.data.detail.cover, '/assets/creation-courtyard.jpg'); assert(!detail.data.timeline.some(r => r.time === '10:30')); detail.advancePublication(); const saved = f.studio.read().records[0]; assert(saved.publishedAt); assert.equal(saved.status, 'published');
});
check('batch count cannot silently create one result; restored drafts clamp count', () => {
  const f = fixture(); assert.throws(() => f.studio.createDemoTask('ai', '批量', { parameters: [{ key: 'count', value: '3 个' }] }), /每次生成1/); f.wx.setStorageSync('zhongshu-video-draft', { description: '旧草稿', parameters: [{ key: 'count', options: ['1 个', '2 个', '3 个'], value: 2 }] }); const p = f.page('ai-video'); assert.equal(p.data.parameters[3].value, 0); assert.equal(p.data.parameters[3].options.length, 1);
});
check('repeated saves and same-millisecond IDs preserve distinct works', () => {
  const f = fixture(), a = f.studio.saveWork('task-complete'), same = f.studio.saveWork('task-complete'), b = f.studio.saveWork('task-retry'); assert.equal(a.id, same.id); assert.notEqual(a.id, b.id); assert.equal(f.studio.read().works.length, 2);
});
check('manual rewrite can be recovered after switching to sample content', () => {
  const f = fixture(), p = f.page('creation'); p.inputEditor({ detail: { value: '手动原文' } }); p.saveDraft(); const remix = f.page('studio', { view: 'remix' }); remix.data.editingText = '保留的二创文案'; remix.saveScript(); p.data.link = 'https://example.com/video'; p.extract(); assert.equal(f.studio.read().script, f.studio.script); assert.equal(f.studio.restoreManualDraft(), true); assert.equal(f.studio.read().script, '保留的二创文案');
});
check('all creations includes historical task edit drafts without changing current draft', () => {
  const f = fixture(), task = f.studio.createDemoTask('ai', '历史任务', {}); const editor = f.page('ai-video', { task: task.id }); editor.data.description = '历史编辑草稿'; editor.saveDraft(); const recent = f.studio.recentItems(f.studio.read()); assert(recent.some(item => item.editTask === task.id && item.title === '历史编辑草稿')); assert.equal(f.wx.getStorageSync('zhongshu-video-draft'), '');
});
check('missing historical AI task does not overwrite current draft', () => {
  const f = fixture(); f.wx.setStorageSync('zhongshu-video-draft', { description: 'B' }); const p = f.page('ai-video', { task: 'missing' }); assert(p.data.invalidMessage); p.saveDraft(); p.generate(); assert.equal(f.wx.getStorageSync('zhongshu-video-draft').description, 'B');
});
async function finish() {
  const f = fixture();
  assert.equal(await f.creation.persistMedia('/assets/creation-house.jpg'), '/assets/creation-house.jpg');
  assert.equal(await f.creation.persistMedia('wxfile://tmp/selected.jpg'), 'wxfile://usr/saved-image.jpg');
  checks.push({ name: 'phone media uses a persistent saved path; bundled assets remain unchanged', result: 'passed' });
  f.wx.getFileSystemManager = () => ({ saveFile: options => options.fail({ errMsg: 'quota exceeded' }) });
  await assert.rejects(f.creation.persistMedia('wxfile://tmp/selected.jpg'), /素材保存失败/);
  checks.push({ name: 'failed media persistence rejects the selection instead of storing a temporary path', result: 'passed' });
  fs.mkdirSync(path.join(root, 'qa/remediation-20261017'), { recursive: true });
  fs.writeFileSync(path.join(root, 'qa/remediation-20261017/logic-results.json'), JSON.stringify({ result: 'passed', runAt: new Date().toISOString(), method: 'Current source in isolated JavaScript contexts; no live API calls.', checks }, null, 2));
  console.log(checks.length + ' remediation logic checks passed.');
}
finish().catch(error => { console.error(error); process.exitCode = 1; });
