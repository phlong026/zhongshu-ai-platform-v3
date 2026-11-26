const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const model = require('../miniprogram/pages/customer-flow/model');
const root = path.join(__dirname, '..');
const copy = value => JSON.parse(JSON.stringify(value));
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const event = (key, value) => ({ currentTarget: { dataset: { key } }, detail: { value } });
function workflow(patch = {}) {
  return { workflowId: null, customerId: '42', customerName: '本店客户', companyId: '9', templateVersion: 1,
    status: 'ACTIVE', revision: 0, completedCount: 0, totalSteps: 8, progressPercent: 0,
    currentStepCode: 'INVITATION', nextStepCode: 'INTRODUCTION', canWrite: true,
    steps: model.STEPS.map((s, i) => ({ code: s.code, name: s.name, order: i + 1, status: 'TODO', revision: 0,
      occurredOn: null, note: '', fields: {}, attachments: [], completedAt: null, canWrite: i < 6, canComplete: i < 6,
      canReopen: false, completionErrors: [], source: i < 6 ? 'MANUAL' : i === 6 ? 'CONTRACT' : 'ENGINEERING' })),
    events: [], hasMoreEvents: false, ...patch };
}
function harness(options = {}) {
  let definition, session = 1, post = { postId: '7', orgType: 'store', roleCode: 'sales', brandId: '3', ...options.post }, keyCount = 0;
  const calls = [], navigations = [], toasts = [];
  const h = { state: workflow(), calls, navigations, toasts, overrides: options.api || {} };
  const api = Object.fromEntries(['get', 'save', 'complete', 'reopen', 'records', 'download'].map(name => [name, async (...args) => {
    calls.push({ name, args: copy(args) });
    if (h.overrides[name]) return h.overrides[name](...args);
    if (name === 'get') return copy(h.state);
    if (name === 'records') return { list: [], total: 0 };
    if (name === 'download') return { downloadUrl: 'https://storage.test/file', expiresAt: '2026-11-25T12:00:00Z' };
    const step = h.state.steps.find(s => s.code === args[1]);
    h.state.revision += 1;
    if (name === 'save') Object.assign(step, copy(args[2]), { status: 'DRAFT' });
    if (name === 'complete') {
      Object.assign(step, { status: 'COMPLETED', canWrite: false, canComplete: false, canReopen: true });
      h.state.completedCount += 1; h.state.progressPercent = h.state.completedCount * 12.5;
    }
    if (name === 'reopen') Object.assign(step, { status: 'DRAFT', canWrite: true, canReopen: false });
    return copy(h.state);
  }]));
  const http = { token: () => 'token', currentPostId: () => post?.postId || '' };
  const requires = {
    '../../api/http': http, '../../api/auth': { currentApiPost: () => post }, '../../utils/api-scope': () => JSON.stringify([session, post?.postId]),
    '../../utils/page-navigation': { open: url => navigations.push({ url }), back: value => navigations.push({ back: value }) },
    '../../utils/platform-crm': { commandKey: prefix => prefix + '-' + ++keyCount }, './api': api, './model': model,
    './upload': { run: options.upload || (async () => ({ assetId: '800', title: '资料.pdf', mimeType: 'application/pdf' })) },
    '../../utils/b-asset-download': { downloadTicketFile: async () => ({ tempFilePath: '/download.pdf', contentType: 'application/pdf' }) }
  };
  const wx = { showToast: value => toasts.push(value), showModal: value => h.modal = value,
    navigateTo: value => navigations.push(value), chooseMessageFile: value => h.filePicker = value,
    chooseMedia: value => h.mediaPicker = value, previewImage: value => h.preview = value, openDocument: value => h.preview = value };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'miniprogram/pages/customer-flow/index.js'), 'utf8'), {
    Page: value => definition = value, require: name => { if (!(name in requires)) throw Error(name); return requires[name]; }, wx, console
  });
  const page = h.page = { ...definition, data: copy(definition.data), setData(patch) { Object.assign(this.data, copy(patch)); } };
  page.onLoad({ customerId: options.customerId || '42', step: options.step || '' });
  h.switchSession = () => { session += 1; post = { postId: String(7 + session), roleCode: 'sales', brandId: '4' }; };
  h.open = () => page.onShow();
  h.callsFor = name => calls.filter(call => call.name === name);
  return h;
}

test('05测量和06报价能进入外部委托，06保留预算并提供付款草案，跳转不保存或完成步骤', async () => {
  const { render } = require('./role-page-runtime.cjs'), flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children || [])]);
  for (const step of ['MEASUREMENT', 'QUOTATION']) {
    const h = harness({ step }); await h.open();
    const controls = flatten(render(path.join(root, 'miniprogram/pages/customer-flow/index.wxml'), h.page.data));
    assert.equal(controls.some(node => node.attrs?.bindtap === 'openEntrust'), true);
    h.page.openEntrust(); assert.equal(h.navigations.at(-1).url, '/pages/external-entrust/index?customerId=42');
    if (step === 'QUOTATION') {
      assert.equal(controls.some(node => node.attrs?.bindtap === 'openBudget'), true);
      assert.equal(controls.some(node => node.attrs?.bindtap === 'openPaymentDraft'), true);
      h.page.openPaymentDraft(); assert.equal(h.navigations.at(-1).url, '/pages/workflow/payment-plan/index?customerId=42&draft=1');
    }
    assert.equal(h.callsFor('save').length + h.callsFor('complete').length, 0);
  }
  const start = harness({ step: 'START' }); await start.open(); start.page.openSource(); assert.match(start.navigations.at(-1).url, /external-entrust\/index\?customerId=42/);
  start.state.steps[7].fields.projectId = '77'; await start.page.load(); start.page.openSource(); assert.equal(start.navigations.at(-1).url, '/pages/external-entrust/index?customerId=42');
});

test('第08步业务岗保留真实开工事实，无工程查询权限时进入当前客户委托与移交', async () => {
  const { render } = require('./role-page-runtime.cjs'), flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children || [])]);
  for (const post of [{ roleCode: 'sales' }, { roleCode: 'sales-lead' }, { roleCode: 'boss', permissions: [] }]) {
    const h = harness({ step: 'START', post }); h.state.steps[7].fields = { projectId: '77', actualStartAt: '2026-11-24', status: 'STARTED' }; await h.open();
    h.page.openSource(); assert.equal(h.navigations.at(-1).url, '/pages/external-entrust/index?customerId=42');
    assert.ok(h.page.data.facts.some(fact => fact.value === '2026-11-24')); assert.equal(h.page.data.step.canWrite, false);
    const button = flatten(render(path.join(root, 'miniprogram/pages/customer-flow/index.wxml'), h.page.data)).find(node => node.attrs?.bindtap === 'openSource');
    assert.match(button.children.map(node => node.text || '').join(''), /查看委托与移交/);
  }
});

test('第08步具有工程查询权限的门店岗位可进入真实工程，只有项目编号不代表工程读权', async () => {
  for (const roleCode of ['boss', 'engineer', 'finance']) {
    const h = harness({ step: 'START', post: { roleCode, permissions: ['franchise:engineering:query'] } }); h.state.steps[7].fields.projectId = '77'; await h.open();
    h.page.openSource(); assert.equal(h.navigations.at(-1).url, '/pages/build/index?view=overview&id=77'); assert.equal(h.page.data.step.canReadEngineering, true);
    assert.equal(h.callsFor('save').length + h.callsFor('complete').length, 0);
  }
});

test('相关办理入口对未保存填写给出提示，保存结果未知或切岗后不跳转', async () => {
  const h = harness({ step: 'QUOTATION' }); await h.open(); h.page.input(event('note', '测量资料尚未保存'));
  h.page.openEntrust(); assert.equal(h.navigations.length, 0); assert.ok(h.modal); h.modal.success({ confirm: true }); assert.equal(h.navigations.length, 1);
  h.page.data.uncertain = true; h.page.openPaymentDraft(); assert.equal(h.navigations.length, 1);
  h.page.data.uncertain = false; h.page.openPaymentDraft(); h.switchSession(); h.modal.success({ confirm: true }); assert.equal(h.navigations.length, 1);
  assert.equal(h.callsFor('save').length + h.callsFor('complete').length, 0);
});

test('未保存提示打开后进入提交或上传状态，确认不跳转', async () => {
  for (const state of ['busy', 'uploading', 'uncertain']) {
    const h = harness({ step: 'QUOTATION' }); await h.open(); h.page.input(event('note', '尚未保存'));
    h.page.openEntrust(); assert.ok(h.modal); h.page.data[state] = true; h.modal.success({ confirm: true });
    assert.equal(h.navigations.length, 0, state);
  }
});

test('保存未齐资料只产生草稿，不本地推进百分比；完成先保存再交服务端验证', async () => {
  const h = harness(); await h.open();
  h.page.input(event('note', '本次仅联系，待确定到店时间'));
  await h.page.submit({ currentTarget: { dataset: { action: 'save' } } });
  assert.equal(h.callsFor('save').length, 1); assert.equal(h.callsFor('complete').length, 0);
  assert.equal(h.page.data.workflow.progressPercent, 0); assert.equal(h.page.data.step.status, 'DRAFT');
  h.overrides.complete = async () => { throw { code: 400, message: '请补充约定到店时间' }; };
  await h.page.submit({ currentTarget: { dataset: { action: 'complete' } } });
  assert.equal(h.callsFor('save').length, 2); assert.equal(h.callsFor('complete').length, 1);
  assert.equal(h.page.data.step.status, 'DRAFT'); assert.equal(h.page.data.workflow.progressPercent, 0);
  assert.match(h.page.data.error, /约定到店/); assert.equal(h.page.data.uncertain, false);
});

test('完成成功采用服务端进度和revision，不要求按顺序办理', async () => {
  const h = harness({ step: 'QUOTATION' }); await h.open();
  h.page.input(event('amountCents', '123456.78')); h.page.input(event('versionLabel', 'V2'));
  await h.page.submit({ currentTarget: { dataset: { action: 'complete' } } });
  const save = h.callsFor('save')[0], complete = h.callsFor('complete')[0];
  assert.equal(save.args[1], 'QUOTATION'); assert.equal(save.args[2].fields.amountCents, '12345678');
  assert.equal(save.args[2].revision, 0); assert.equal(complete.args[2].revision, 1);
  assert.notEqual(save.args[3], complete.args[3]);
  assert.equal(h.page.data.workflow.progressPercent, 12.5); assert.equal(h.page.data.step.status, 'COMPLETED');
});

test('完成请求超时后只重试同一完成命令，不重复保存或允许修改其内容', async () => {
  const h = harness(); await h.open(); let attempts = 0;
  h.overrides.complete = async () => {
    if (++attempts === 1) throw { code: -1, message: 'timeout' };
    const result = copy(h.state); result.revision = 2; result.completedCount = 1; result.progressPercent = 12.5;
    Object.assign(result.steps[0], { status: 'COMPLETED', canWrite: false }); return result;
  };
  await h.page.submit({ currentTarget: { dataset: { action: 'complete' } } });
  assert.equal(h.page.data.uncertain, true);
  h.page.input(event('note', '不可悄悄更改')); assert.equal(h.page.data.form.note, '');
  await h.page.runPending();
  assert.equal(h.callsFor('save').length, 1); assert.equal(h.callsFor('complete').length, 2);
  assert.deepEqual(h.callsFor('complete')[0].args, h.callsFor('complete')[1].args);
  assert.equal(h.page.data.workflow.progressPercent, 12.5); assert.equal(h.page.data.uncertain, false);
});

test('保存超时原body和幂等键重试；确定的领域错误不会错误锁为未知结果', async () => {
  const h = harness(); await h.open(); let attempts = 0;
  h.page.input(event('note', '固定的首次填写'));
  h.overrides.save = async () => { if (++attempts === 1) throw { code: 503, message: 'gateway timeout' }; return workflow({ revision: 1 }); };
  await h.page.submit(); assert.equal(h.page.data.uncertain, true);
  h.page.input(event('note', '不应进入重试内容')); await h.page.runPending();
  assert.deepEqual(h.callsFor('save')[0].args, h.callsFor('save')[1].args);
  h.overrides.save = async () => { throw { code: 1099000000, message: '幂等键已被使用' }; };
  await h.page.submit(); assert.equal(h.page.data.uncertain, false); assert.match(h.page.data.error, /幂等键/);
});

test('保存或完成结果未知时切回页面仍可点击重试，保留原命令且不再保存一次', async () => {
  const { render } = require('./role-page-runtime.cjs');
  const flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children || [])]);
  for (const action of ['save', 'complete']) {
    const h = harness(); await h.open();
    h.page.input(event('note', '待核对的原始记录'));
    h.overrides[action] = async () => { throw { code: -1, message: 'timeout' }; };
    await h.page.submit({ currentTarget: { dataset: { action } } });
    const original = h.callsFor(action)[0], pending = h.page._pending;
    h.page.onHide(); await h.page.onShow();
    assert.equal(h.page.data.uncertain, true); assert.equal(h.page._pending, pending);
    assert.equal(h.page.data.error, '', '正常刷新可清除旧读取错误');
    const nodes = flatten(render(path.join(root, 'miniprogram/pages/customer-flow/index.wxml'), h.page.data));
    const retry = nodes.find(node => node.attrs?.bindtap === 'runPending');
    assert.ok(retry, '未确认命令的重试入口不能依赖读取错误');
    assert.match(nodes.map(node => node.text || '').join(''), /保存结果尚未确认/);
    assert.equal(retry.attrs.disabled, false);
    delete h.overrides[action]; await h.page[retry.attrs.bindtap]();
    assert.deepEqual(h.callsFor(action)[1].args, original.args);
    assert.equal(h.callsFor('save').length, action === 'save' ? 2 : 1);
    assert.equal(h.page.data.uncertain, false); assert.equal(h.page._pending, null);
  }
});

test('版本冲突保留填写并停止写入，确认载入最新后才能继续', async () => {
  const h = harness(); await h.open(); h.page.input(event('note', '仍需保留的填写'));
  h.overrides.save = async () => { throw { code: 409, message: '记录已更新' }; };
  await h.page.submit();
  assert.equal(h.page.data.conflict, true); assert.equal(h.page.data.form.note, '仍需保留的填写');
  await h.page.submit(); assert.equal(h.callsFor('save').length, 1);
  h.state.revision = 3; h.state.steps[0].note = '另一同事的记录';
  h.page.reloadLatest(); h.modal.success({ confirm: true }); await tick();
  assert.equal(h.page.data.conflict, false); assert.equal(h.page.data.dirty, false);
  assert.equal(h.page.data.form.note, '另一同事的记录');
});

test('切岗清空旧客户数据与草稿，迟到读取和写入都不能覆盖新会话', async () => {
  const oldGet = deferred(), h = harness({ api: { get: () => oldGet.promise } });
  const first = h.open(); h.switchSession(); h.overrides.get = async () => workflow({ customerName: '新品牌客户' });
  await h.open(); oldGet.resolve(workflow({ customerName: '旧品牌客户' })); await first;
  assert.equal(h.page.data.workflow.customerName, '新品牌客户');
  const oldSave = deferred(); h.overrides.save = () => oldSave.promise;
  h.page.input(event('note', '旧身份草稿')); const saving = h.page.submit();
  h.switchSession(); await h.open(); oldSave.resolve(workflow({ customerName: '旧写入响应', revision: 1 })); await saving;
  assert.equal(h.page.data.workflow.customerName, '新品牌客户'); assert.equal(h.page.data.form.note, '');
  assert.equal(h.page.data.dirty, false); assert.equal(h.page.data.busy, false);
});

test('同会话写入使既有读取失效，迟到GET和旧revision不能覆盖已保存内容', async () => {
  const h = harness(); await h.open(); const pendingGet = deferred();
  h.overrides.get = () => pendingGet.promise; const refreshing = h.page.load();
  h.page.input(event('note', '刚保存的新记录')); await h.page.submit();
  pendingGet.resolve(workflow()); await refreshing;
  assert.equal(h.page.data.workflow.revision, 1); assert.equal(h.page.data.form.note, '刚保存的新记录');
  h.overrides.get = async () => workflow(); await h.page.load();
  assert.equal(h.page.data.workflow.revision, 1); assert.equal(h.page.data.form.note, '刚保存的新记录');
  assert.equal(h.page.data.refreshing, false);
});

for (const code of [1110000000, 1110000001]) {
  test('客户转交/撤写权限领域码' + code + '清除旧数据、草稿与本地附件，拒绝迟到历史', async () => {
    const h = harness(); await h.open(); h.page.input(event('note', '之前有权查看的资料'));
    h.page._localFiles = { '88': '/private.pdf' };
    const pendingHistory = deferred(); h.overrides.records = () => pendingHistory.promise;
    const history = h.page.loadHistory(true);
    h.overrides.get = async () => { throw { code, message: '客户已转交或权限已撤销' }; };
    await h.page.load();
    pendingHistory.resolve({ list: [{ id: '1', stepCode: 'INVITATION', fields: {}, attachments: [], note: '旧历史' }], total: 1 });
    await history;
    assert.equal(h.page.data.workflow, null); assert.equal(h.page.data.form, null); assert.equal(h.page.data.records.length, 0);
    assert.deepEqual(copy(h.page._drafts), {}); assert.deepEqual(copy(h.page._localFiles), {});
    const writing = harness(); await writing.open(); writing.page.input(event('note', '待提交草稿'));
    writing.overrides.save = async () => { throw { code, message: '禁止编辑该客户' }; }; await writing.page.submit();
    assert.equal(writing.page.data.workflow, null); assert.equal(writing.page.data.uncertain, false); assert.equal(writing.page._pending, null);
  });
}

test('403读取或历史查询失败清除敏感内容，失效的深链接不请求客户', async () => {
  const invalid = harness({ customerId: 'not-a-customer' }); await invalid.open();
  assert.equal(invalid.calls.length, 0); assert.match(invalid.page.data.error, /链接无效/);
  const h = harness(); await h.open(); h.page.input(event('note', '私有草稿'));
  h.overrides.records = async () => { throw { code: 403, message: '访问已撤销' }; };
  await h.page.toggleHistory(); assert.equal(h.page.data.workflow, null); assert.equal(h.page.data.form, null);
  assert.deepEqual(copy(h.page._drafts), {}); assert.match(h.page.data.error, /撤销/);
});

test('07/08只读真实事实且没有完成按钮；预算入口仍携带客户上下文', async () => {
  const h = harness(); await h.open();
  h.state.steps[6].fields = { contractId: '81', signedDate: '2026-11-25', totalCents: '30000000', assetId: '90' };
  h.state.steps[7].fields = { projectId: '91', actualStartAt: '2026-11-25T02:00:00Z', managerUserId: '18' };
  h.page.applyWorkflow(h.state);
  const { render } = require('./role-page-runtime.cjs'), file = path.join(root, 'miniprogram/pages/customer-flow/index.wxml');
  const flatten = nodes => nodes.flatMap(n => [n, ...flatten(n.children || [])]);
  for (const code of ['CONTRACT', 'START']) {
    h.page.select(code); await h.page.submit({ currentTarget: { dataset: { action: 'complete' } } });
    assert.equal(h.callsFor('save').length, 0); assert.equal(h.callsFor('complete').length, 0);
    assert.ok(!flatten(render(file, h.page.data)).some(n => n.attrs?.bindtap === 'submit'));
    h.page.openSource();
  }
  assert.equal(h.navigations[0].url, '/pages/workflow/index?view=contract&id=42');
  assert.equal(h.navigations[1].url, '/pages/external-entrust/index?customerId=42');
  h.page.select('QUOTATION'); h.page.openBudget();
  assert.equal(h.navigations[2].url, '/design-migration/pages/budget/quick?customerId=42');
  h.page.openSalesQuote(); assert.equal(h.navigations.at(-1).url, '/pages/workflow/index?view=budget&id=42');
});

test('文本输入长度和后端契约一致，避免保存时才发现已超限', async () => {
  const h = harness({ step: 'QUOTATION' }); await h.open();
  const { render } = require('./role-page-runtime.cjs'), file = path.join(root, 'miniprogram/pages/customer-flow/index.wxml');
  const flatten = nodes => nodes.flatMap(n => [n, ...flatten(n.children || [])]);
  const controls = () => flatten(render(file, h.page.data));
  assert.equal(controls().find(n => n.attrs?.['data-key'] === 'scope').attrs.maxlength, 1000);
  assert.equal(controls().find(n => n.attrs?.['data-key'] === 'result').attrs.maxlength, 1000);
  h.page.select('INTRODUCTION');
  assert.equal(controls().find(n => n.attrs?.['data-key'] === 'requirements').attrs.maxlength, 4000);
  assert.equal(controls().find(n => n.attrs?.['data-key'] === 'receptionist').attrs.maxlength, 1000);
});

test('查看完整历史版本和历史附件使用节点受控下载，分页失败可重试', async () => {
  const record = { id: '10', stepCode: 'QUOTATION', type: 'SAVE', fields: { amountCents: '123456', versionLabel: 'V1', scope: '主体工程' },
    attachments: [{ assetId: '88', title: 'V1报价.pdf' }], createdAt: '2026-11-25T01:00:00Z', actorName: '经办甲' };
  let page2Attempts = 0;
  const h = harness({ api: { records: async (_id, pageNo) => {
    if (pageNo === 1) return { list: [record], total: 2 };
    if (++page2Attempts === 1) throw { code: -1, message: '加载未完成' };
    return { list: [record, { ...record, id: '11' }], total: 2 };
  } } });
  await h.open(); await h.page.toggleHistory(); await h.page.moreHistory();
  assert.equal(h.page.data.records.length, 1); assert.match(h.page.data.historyError, /加载/);
  await h.page.retryHistory(); assert.equal(h.page.data.records.length, 2); assert.equal(h.page.data.historyMore, false);
  h.page.viewRecord({ currentTarget: { dataset: { id: '10' } } });
  assert.equal(h.page.data.activeRecord.details.find(f => f.label === '正式报价金额').value, '1234.56');
  await h.page.previewFile({ currentTarget: { dataset: { id: '88' } } });
  assert.deepEqual(h.callsFor('download')[0].args, ['42', 'QUOTATION', '88']); assert.equal(h.preview.filePath, '/download.pdf');
});

test('上传失败不能切步或提交，重试成功关联资产后才能保存', async () => {
  let attempts = 0;
  const h = harness({ upload: async () => { if (++attempts === 1) throw Error('上传网络中断'); return { assetId: '800', title: '外部方案.pdf' }; } });
  await h.open(); h.page.select('HOUSE_SELECTION'); await h.page.startUpload({ path: '/local.pdf', name: '外部方案.pdf' });
  assert.equal(h.page.data.uploadFailed, true); await h.page.submit(); assert.equal(h.callsFor('save').length, 0);
  h.page.selectStep({ currentTarget: { dataset: { code: 'INVITATION' } } }); assert.equal(h.page.selectedCode, 'HOUSE_SELECTION');
  await h.page.retryUpload(); assert.equal(h.page.data.form.attachments[0].assetId, '800');
  await h.page.submit(); assert.equal(h.callsFor('save')[0].args[2].attachments[0].assetId, '800');
});

test('灵感选择回传保留其他填写，切岗后的选择回传被拒绝', async () => {
  const h = harness({ step: 'HOUSE_SELECTION' }); await h.open();
  h.page.input(event('selectionResult', '客户希望增加卧室')); h.page.chooseReference();
  const picker = h.navigations[0]; assert.match(picker.url, /pick=workflow&customerId=42/);
  picker.events.workflowReference({ caseId: '801', title: '庭院三层' });
  assert.equal(h.page.data.form.fields.referenceCaseId, '801'); assert.equal(h.page.data.form.fields.selectionResult, '客户希望增加卧室');
  h.switchSession(); await h.open(); picker.events.workflowReference({ caseId: '802', title: '旧身份回传' });
  assert.equal(h.page.data.form.fields.referenceCaseId, '');
});

test('客户列表使用批量内联摘要，旧详情仍保留，按钮携带不透明客户ID', () => {
  const crm = require('../miniprogram/utils/platform-crm');
  const row = crm.customerRow({ id: '900719925474099999', name: '客户', createTime: 0, nextVisitDate: [2026, 12, 1],
    workflow: { completedCount: 3, totalSteps: 8, progressPercent: 37.5, currentStepName: '带看工地', nextContactOn: '2026-11-27' } }, { canWrite: true, today: '2026-11-25' });
  assert.equal(row.stageName, '带看工地'); assert.equal(row.appointment, '2026-11-27'); assert.match(row.detail, /11-27/);
  assert.equal(row.workflow.progressPercent, 37.5); assert.equal(row.flowUrl, '/pages/customer-flow/index?customerId=900719925474099999');
  assert.match(row.url, /pages\/workflow\/index\?view=customer/);
});

test('明确未上传后页面允许换票重传，保留文件字节并清除原票据；切岗后不能重传', async () => {
  let attempts = 0, original;
  const bytes = new ArrayBuffer(4);
  const h = harness({ upload: async pending => {
    if (++attempts === 1) {
      original = pending;
      Object.assign(pending, { bytes, mimeType: 'application/pdf', ticket: { assetId: '811' }, putAttempted: true, restartRequired: true });
      throw { code: 1071000001, msg: '资产尚未上传完成，请重试' };
    }
    assert.notEqual(pending, original); assert.equal(pending.bytes, bytes); assert.equal(pending.file, original.file);
    assert.equal(pending.ticket, undefined); assert.equal(pending.putAttempted, undefined);
    return { assetId: '812', title: '现场测量.pdf', mimeType: 'application/pdf' };
  } });
  await h.open(); await h.page.startUpload({ path: '/file.pdf', name: '现场测量.pdf' });
  assert.equal(h.page.data.uploadFailed, true); assert.equal(h.page.data.uploadRestartAllowed, true);
  await h.page.restartUpload();
  assert.equal(attempts, 2); assert.equal(h.page.data.form.attachments[0].assetId, '812');
  assert.equal(h.page.data.uploadRestartAllowed, false); assert.equal(h.page.data.uploadFailed, false);
  h.page._pendingFile = original; h.switchSession(); await h.page.restartUpload(); assert.equal(attempts, 2);
  const wxml = fs.readFileSync(path.join(root, 'miniprogram/pages/customer-flow/index.wxml'), 'utf8');
  assert.match(wxml, /wx:if="\{\{uploadRestartAllowed\}\}"[^>]+bindtap="restartUpload"/);
  assert.match(wxml, /wx:if="\{\{uploadRetryable\}\}"[^>]+bindtap="retryUpload"/);
});
