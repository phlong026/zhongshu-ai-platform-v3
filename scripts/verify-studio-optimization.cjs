const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const P = require('../miniprogram/utils/platform-content');
const plain = value => JSON.parse(JSON.stringify(value));
const flush = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const input = value => ({ detail: { value } });

// Use the real scoped draft store and API adapter: storage reads are copies, as on WeChat.
function fixture({ submit = async () => ({ taskId: '9007199254740993', status: 'QUEUED' }), poll } = {}) {
  let generation = 1, postId = 'one', failStorage = false;
  const storage = new Map(), requests = [], polls = [], toasts = [], seq = {};
  const wx = {
    getStorageSync: key => storage.has(key) ? plain(storage.get(key)) : '',
    setStorageSync(key, value) { if (failStorage) throw Error('存储空间不足'); storage.set(key, plain(value)); },
    showToast: value => toasts.push(value.title), redirectTo() {}, nextTick: fn => fn()
  };
  const http = {
    token: () => 'token', sessionGeneration: () => generation,
    requestGuard(domain) { const g = generation, n = seq[domain] = (seq[domain] || 0) + 1; return () => generation === g && seq[domain] === n; },
    post(url, body, headers) { requests.push(plain({ url, body, headers })); return submit(body, headers['Idempotency-Key']); }
  };
  const studioModule = { exports: {} }, apiModule = { exports: {} };
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/utils/studio'), 'utf8'), {
    module: studioModule, wx,
    require(name) {
      if (name === './runtime-compat') return {};
      if (name === './organization') return { has: () => false };
      if (name === './access') return { context: () => ({ active: false }) };
      if (name === './storage-keys') return { studio: 'studio', account: 'account', management: 'management' };
      if (name === './clone') return plain;
      if (name === '../api/http') return http;
      if (name === '../api/auth') return { currentApiPost: () => ({ postId, roleCode: 'sales' }) };
      throw Error(name);
    }
  });
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/api/franchise/content'), 'utf8'), {
    module: apiModule, require: name => { assert.equal(name, '../http'); return http; }
  });
  const studio = studioModule.exports, api = { ...apiModule.exports, personas: { page: async () => [] } };
  const utility = { ...P, api, pollTask(id) { polls.push(id); return poll ? poll(id) : Promise.resolve({ id, status: 'SUCCEEDED', result: { text: '优化后的稿件' } }); } };
  studio.write({ ...studio.read(), original: '建房原文', script: '已有二创稿', scriptEdited: true });
  wx.setStorageSync(studio.scopedKey('creation-draft'), { text: '建房原文', rewrittenText: '已有二创稿' });
  function page() {
    let value;
    vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/studio/index'), 'utf8'), {
      Page: item => { value = item; }, wx,
      require(name) {
        if (name === '../../utils/studio') return studio;
        if (name === '../../utils/platform-content') return utility;
        if (name === '../../api/http') return http;
        if (name === '../../utils/storage-keys') return { creationDraft: 'creation-draft' };
        if (name === '../../utils/creation-keyboard') return { open() {}, close() {}, stop() {} };
        if (name === '../../utils/creation-text-resize' || name === './material-library') return { data: {}, handlers: {} };
        if (name === './content-task-result' || name === './task-display') return {};
        throw Error(name);
      }
    });
    value.data = plain(value.data);
    value.setData = patch => Object.assign(value.data, patch);
    value.onLoad({ view: 'remix' });
    return value;
  }
  return { page, studio, wx, storage, requests, polls, toasts,
    failStorage: value => { failStorage = value; },
    changeSession: () => { generation++; }, changePost: () => { postId = 'two'; generation++; }
  };
}

test('ten optimization taps create one paid operation; success saves text/history and releases its key', async () => {
  const pending = deferred(), f = fixture({ submit: () => pending.promise }), page = f.page();
  page.openOptimization();
  const run = page.optimizeScript();
  for (let i = 0; i < 9; i++) page.optimizeScript();
  page.regenerate();
  assert.equal(f.requests.length, 1);
  assert.equal(page.data.optimizing, true);
  const saved = f.studio.read().optimizationAttempt;
  assert.equal(saved.key, f.requests[0].headers['Idempotency-Key']);
  assert.deepEqual(plain(saved.payload), { sourceText: '已有二创稿', requirement: '口语自然' });
  pending.resolve({ taskId: '9007199254740993', status: 'QUEUED' });
  await run;
  const state = f.studio.read();
  assert.equal(state.script, '优化后的稿件');
  assert.equal(state.optimizationAttempt, undefined);
  assert.equal(state.copyOptimizations.length, 1);
  assert.equal(state.copyOptimizations[0].taskId, '9007199254740993');
  assert.equal(f.wx.getStorageSync(f.studio.scopedKey('creation-draft')).rewrittenText, state.script);
  assert.equal(page.data.scriptLength, state.script.length);
  assert.equal(page.data.optimizing, false);
  assert.equal(page.data.optimizationRetry, false);
  page.openOptimization(); await page.optimizeScript();
  assert.notEqual(f.requests[1].headers['Idempotency-Key'], saved.key);
});

test('lost create response survives unload; retry keeps exact body/key despite edits and blocks regenerate', async () => {
  let submitted = 0;
  const f = fixture({ submit: async () => { if (++submitted === 1) throw { code: -1, message: '网络异常' }; return { taskId: 'accepted-before-disconnect', status: 'RUNNING' }; } });
  const first = f.page(); first.openOptimization(); await first.optimizeScript(); first.onUnload();
  const page = f.page();
  assert.equal(page.data.optimizationRetry, true);
  page.editScript(); page.inputEdit(input('用户的新稿件')); page.saveScript();
  page.openOptimization(); page.inputOptimization({ currentTarget: { dataset: { value: '精简表达' } } });
  page.regenerate(); await flush();
  assert.equal(f.requests.length, 1);
  await page.optimizeScript();
  assert.deepEqual(f.requests[1], f.requests[0]);
  assert.deepEqual(f.polls, ['accepted-before-disconnect']);
  assert.equal(f.studio.read().script, '用户的新稿件');
  assert.equal(f.studio.read().copyOptimizations[0].after, '优化后的稿件');
  assert.equal(f.studio.read().optimizationAttempt, undefined);
});

test('known task survives polling failure and re-entry; retry polls it without another POST', async () => {
  let count = 0;
  const f = fixture({ poll: async id => { if (++count === 1) throw { code: -1, message: '网络异常' }; return { id, status: 'SUCCEEDED', result: { text: '原任务的结果' } }; } });
  const first = f.page(); first.openOptimization(); await first.optimizeScript();
  assert.equal(f.studio.read().optimizationAttempt.taskId, '9007199254740993');
  first.onUnload();
  const next = f.page(); next.openOptimization(); await next.optimizeScript();
  assert.equal(f.requests.length, 1);
  assert.deepEqual(f.polls, ['9007199254740993', '9007199254740993']);
  assert.equal(f.studio.read().script, '原任务的结果');
});

for (const [label, edit] of [
  ['saved edit reverted to the original text', p => { p.editScript(); p.inputEdit(input('临时修改')); p.inputEdit(input('已有二创稿')); p.saveScript(); }],
  ['unsaved editor buffer', p => { p.editScript(); p.inputEdit(input('还没保存的改稿')); }],
  ['new source', p => { p.inputOriginal(input('另一篇原文')); }]
]) test('late optimization cannot overwrite ' + label, async () => {
  const pending = deferred(), f = fixture({ poll: () => pending.promise }), page = f.page();
  page.openOptimization(); const run = page.optimizeScript(); await flush();
  const beforeRevision = f.studio.read().copyRevision;
  edit(page);
  const edited = plain(f.studio.read()), draft = f.wx.getStorageSync(f.studio.scopedKey('creation-draft'));
  assert.ok(edited.copyRevision > beforeRevision);
  pending.resolve({ status: 'SUCCEEDED', result: { text: '迟到优化稿' } }); await run;
  const after = f.studio.read();
  assert.equal(after.original, edited.original); assert.equal(after.script, edited.script);
  assert.deepEqual(after.editorBuffer, edited.editorBuffer);
  assert.deepEqual(f.wx.getStorageSync(f.studio.scopedKey('creation-draft')), draft);
  assert.equal(after.copyOptimizations[0].after, '迟到优化稿');
  assert.equal(after.copyOptimizations[0].applied, false);
  if (edited.editorBuffer) assert.equal(page.data.sheet, 'editor');
});

for (const leave of ['onHide', 'onUnload']) test(leave + ' ignores the old create response; re-entry resumes the original operation', async () => {
  const oldResponse = deferred(); let count = 0;
  const f = fixture({ submit: () => ++count === 1 ? oldResponse.promise : Promise.resolve({ taskId: 'same-task', status: 'QUEUED' }) });
  const first = f.page(); first.openOptimization(); const run = first.optimizeScript();
  first[leave]();
  const next = leave === 'onHide' ? first : f.page(); if (leave === 'onHide') next.onShow();
  next.openOptimization(); await next.optimizeScript();
  oldResponse.resolve({ taskId: 'same-task', status: 'QUEUED' }); await run;
  assert.deepEqual(f.requests[1], f.requests[0]);
  assert.deepEqual(f.polls, ['same-task']);
  assert.equal(f.studio.read().copyOptimizations.length, 1);
  assert.equal(f.toasts.filter(t => t === '文案优化已完成').length, 1);
});

test('leaving during polling cancels it and retains the task for recovery', async () => {
  const pending = deferred(); let cancelled = 0;
  pending.promise.cancel = () => { cancelled++; pending.reject({ cancelled: true }); };
  const f = fixture({ poll: () => pending.promise }), page = f.page();
  page.openOptimization(); const run = page.optimizeScript(); await flush();
  page.onHide(); await run;
  assert.equal(cancelled, 1);
  assert.equal(page.data.optimizing, false);
  assert.equal(f.studio.read().optimizationAttempt.taskId, '9007199254740993');
  assert.deepEqual(f.toasts, []);
});

for (const change of ['changeSession', 'changePost']) test(change + ' makes late results silent and prevents writes', async () => {
  const pending = deferred(), f = fixture({ poll: () => pending.promise }), page = f.page();
  page.openOptimization(); const run = page.optimizeScript(); await flush();
  const before = plain(Array.from(f.storage));
  f[change](); pending.resolve({ status: 'SUCCEEDED', result: { text: '旧会话结果' } }); await run; await flush();
  assert.deepEqual(plain(Array.from(f.storage)), before);
  assert.deepEqual(f.toasts, []);
  assert.equal(page.data.script, '已有二创稿');
});

test('definite input rejection allows a new operation; unknown failures retain the same operation', async () => {
  let count = 0;
  const f = fixture({ submit: async () => { throw ++count === 1 ? { code: 1114000001, message: '提交内容不合法' } : { code: 500, message: '暂时不可用' }; } });
  const page = f.page(); page.openOptimization(); await page.optimizeScript();
  assert.equal(f.studio.read().optimizationAttempt, undefined);
  page.openOptimization(); await page.optimizeScript();
  assert.notEqual(f.requests[0].headers['Idempotency-Key'], f.requests[1].headers['Idempotency-Key']);
  await page.optimizeScript();
  assert.deepEqual(f.requests[1], f.requests[2]);
  assert.equal(page.data.optimizationRetry, true);
});

for (const saveRetryable of [false, true]) test('terminal failure ' + (saveRetryable ? 'of result saving keeps the original task' : 'of generation releases the attempt'), async () => {
  const f = fixture({ poll: async id => ({ id, status: 'FAILED', saveRetryable, generationStatus: saveRetryable ? 'SUCCEEDED' : 'FAILED', failedReason: '测试失败' }) });
  const page = f.page(); page.openOptimization(); await page.optimizeScript();
  assert.equal(!!f.studio.read().optimizationAttempt, saveRetryable);
  if (saveRetryable) { await page.optimizeScript(); assert.equal(f.requests.length, 1); }
});

test('a failed durable write blocks the paid HTTP call', async () => {
  const f = fixture(), page = f.page(); await flush(); page.openOptimization(); f.failStorage(true);
  await page.optimizeScript();
  assert.equal(f.requests.length, 0);
  assert.equal(page.data.optimizing, false);
  assert.match(page.data.operationError, /存储|保存/);
});

test('rewrite in progress blocks optimization and WXML disables both entry and submit controls', async () => {
  const f = fixture(), page = f.page(); page.data.rewriting = true; page.optimizeScript();
  assert.equal(f.requests.length, 0);
  const source = fs.readFileSync(require.resolve('../miniprogram/pages/studio/index').replace(/\.js$/, '.wxml'), 'utf8');
  assert.match(source, /copy-optimize"[^>]*disabled="\{\{[^}]*optimizing/);
  assert.match(source, /rewrite-generate"[^>]*disabled="\{\{[^}]*optimizationRetry/);
  assert.match(source, /sheet-confirm"[^>]*bindtap="optimizeScript"[^>]*loading="\{\{optimizing\}\}"[^>]*disabled="\{\{optimizing/);
});
