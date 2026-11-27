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
// 客户当前约定的下次跟进日期（未过期），新记录没单独约定时沿用它。
const nextFollowOn = new Date(Date.now() + 3 * 86400e3 + 8 * 3600e3).toISOString().slice(0, 10);
function workflow(patch = {}) {
  return { workflowId: null, customerId: '42', customerName: '本店客户', companyId: '9', templateVersion: 1,
    status: 'ACTIVE', revision: 0, completedCount: 0, totalSteps: 8, progressPercent: 0, nextFollowOn, canChangeStatus: true,
    currentStepCode: 'INVITATION', nextStepCode: 'INTRODUCTION', canWrite: true,
    steps: model.STEPS.map((s, i) => ({ code: s.code, name: s.name, order: i + 1, status: 'TODO', revision: 0,
      occurredOn: null, note: '', fields: {}, attachments: [], completedAt: null, canWrite: i < 6, canComplete: i < 6,
      canReopen: false, completionErrors: [], source: i < 6 ? 'MANUAL' : i === 6 ? 'CONTRACT' : 'ENGINEERING' })),
    events: [], hasMoreEvents: false, ...patch };
}
function harness(options = {}) {
  let definition, session = 1, post = { postId: '7', orgType: 'store', roleCode: 'sales', brandId: '3', ...options.post }, keyCount = 0;
  const calls = [], requests = [], navigations = [], toasts = [];
  const h = { state: workflow(), calls, requests, navigations, toasts, overrides: options.api || {} };
  const respond = async (name, ...args) => {
    calls.push({ name, args: copy(args) });
    if (h.overrides[name]) return h.overrides[name](...args);
    if (name === 'get') return copy(h.state);
    if (name === 'status') { h.state.revision += 1; h.state.status = args[1].status; h.state.reconsulted = false; return copy(h.state); }
    if (name === 'entryCustomer' || name === 'entryProfile') return null;
    if (name === 'records') return { list: [], total: 0 };
    if (name === 'listQuotes') return [];
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
  };
  // 加载真实 API 模块，只在 HTTP 边界替换服务端；不存在的 API 方法必须直接暴露为测试失败。
  const request = async (method, url, body, headers) => {
    requests.push(copy({ method, url, body, headers }));
    if (method === 'GET' && url === '/platform/profile/mine') return respond('entryProfile');
    const match = /^\/franchise\/customers\/([^/]+)(.*)$/.exec(url);
    assert.ok(match, 'unexpected request: ' + method + ' ' + url);
    const id = decodeURIComponent(match[1]), route = match[2], key = headers?.['Idempotency-Key'];
    if (method === 'GET') {
      if (route === '') return respond('entryCustomer', id);
      if (route === '/workflow') return respond('get', id);
      if (route === '/workflow/quotes') return respond('listQuotes', id);
      if (route === '/workflow/records') return respond('records', id, body.pageNo);
    }
    if (method === 'POST' && route === '/workflow/status') return respond('status', id, body, key);
    const step = /^\/workflow\/steps\/([^/]+)(.*)$/.exec(route);
    if (step) {
      const code = decodeURIComponent(step[1]);
      if (method === 'PUT' && step[2] === '') return respond('save', id, code, body, key);
      if (method === 'POST' && step[2] === '/complete') return respond('complete', id, code, body, key);
      if (method === 'POST' && step[2] === '/skip') return respond('skipVisit', id, body, key);
      if (method === 'POST' && step[2] === '/reopen') return respond('reopen', id, code, body, key);
      const asset = /^\/assets\/([^/]+)\/download-tickets$/.exec(step[2]);
      if (method === 'POST' && asset) return respond('download', id, code, decodeURIComponent(asset[1]));
    }
    throw Error('unexpected request: ' + method + ' ' + url);
  };
  const http = { token: () => 'token', currentUserId: () => options.loginUser || '', currentPostId: () => post?.postId || '',
    get: (url, query) => request('GET', url, query), post: (url, body, headers) => request('POST', url, body, headers),
    put: (url, body, headers) => request('PUT', url, body, headers) };
  const loadApi = (file, dependencies) => {
    const module = { exports: {} };
    vm.runInNewContext(fs.readFileSync(path.join(root, file), 'utf8'), { module, require: name => {
      if (!(name in dependencies)) throw Error(name);
      return dependencies[name];
    } });
    return module.exports;
  };
  const crm = loadApi('miniprogram/api/franchise/crm.js', { '../http': http });
  const api = loadApi('miniprogram/pages/customer-flow/api.js', { '../../api/http': http, '../../api/franchise/crm': crm });
  const requires = {
    '../../api/http': http, '../../api/auth': { currentApiPost: () => post }, '../../utils/api-scope': () => JSON.stringify([session, post?.postId]),
    '../../utils/page-navigation': { open: url => navigations.push({ url }), back: value => navigations.push({ back: value }) },
    './visit-entry': require('../miniprogram/pages/customer-flow/visit-entry'),
    '../../utils/platform-crm': { commandKey: prefix => prefix + '-' + ++keyCount }, './api': api, './model': model,
    './status-sheet': require('../miniprogram/pages/customer-flow/status-sheet'),
    '../../utils/finance-date': require('../miniprogram/utils/finance-date'),
    './upload': { run: options.upload || (async () => ({ assetId: '800', title: '资料.pdf', mimeType: 'application/pdf' })) },
    '../../utils/b-asset-download': { downloadTicketFile: async () => ({ tempFilePath: '/download.pdf', contentType: 'application/pdf' }) }
  };
  const wx = { showToast: value => toasts.push(value), showModal: value => h.modal = value, showActionSheet: value => { h.actionSheet = value; },
    navigateTo: value => navigations.push(value), chooseMessageFile: value => h.filePicker = value,
    chooseMedia: value => h.mediaPicker = value, previewImage: value => h.preview = value, openDocument: value => h.preview = value };
  // 状态弹层模块由 Node 直接加载，不在页面 vm 里；让它看到同一份 wx 桩（小程序运行时 wx 是全局）。
  global.wx = wx;
  vm.runInNewContext(fs.readFileSync(path.join(root, 'miniprogram/pages/customer-flow/index.js'), 'utf8'), {
    Page: value => definition = value, require: name => { if (!(name in requires)) throw Error(name); return requires[name]; }, wx, console
  });
  // 与真实 setData 一致：支持 'statusSheet.reasonCode' 这类路径键。
  const page = h.page = { ...definition, data: copy(definition.data), setData(patch) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); let target = this.data;
      while (parts.length > 1) { const part = parts.shift(); target = target[part] || (target[part] = {}); }
      target[parts[0]] = copy(value);
    }
  } };
  page.onLoad({ customerId: options.customerId || '42', step: options.step || '', status: options.status || '' });
  h.switchSession = () => { session += 1; post = { postId: String(7 + session), roleCode: 'sales', brandId: '4' }; };
  h.open = () => page.onShow();
  h.callsFor = name => calls.filter(call => call.name === name);
  return h;
}

test('第03步外部定制由客户确认实际方向，标准户型和旧外部资料仍可选择', async () => {
  const h = harness({ step: 'HOUSE_SELECTION' }); await h.open();
  const picker = () => h.page.data.fields.find(row => row.key === 'selectionMode');
  assert.ok(picker());
  assert.equal(h.page.data.fields.some(row => row.type === 'reference'), true);
  h.page.chooseReference();
  const originalPicker = h.navigations.at(-1);
  h.page.pick(event('selectionMode', 1));
  assert.equal(h.page.data.fields.some(row => row.type === 'reference'), false);
  assert.ok(h.page.data.fields.some(row => row.key === 'customDirection' && row.type === 'textarea'));
  originalPicker.events.workflowReference({ caseId: '7001', title: '旧目录回调' });
  assert.equal(h.page.data.form.fields.referenceCaseId, '');
  h.page.input(event('customDirection', '一层无台阶，老人房朝南'));
  h.page.toggle(event('confirmed', true));
  assert.match(h.page.data.form.occurredOn, /^\d{4}-\d{2}-\d{2}$/);
  h.page.input(event('customDirection', '一层无台阶，厨房临院'));
  assert.equal(h.page.data.form.fields.confirmed, false);
  h.page.toggle(event('confirmed', true));
  await h.page.submit({currentTarget:{dataset:{action:'complete'}}});
  const body = h.callsFor('save')[0].args[2];
  assert.deepEqual(body.fields, { selectionMode: 'EXTERNAL_CUSTOM', customDirection: '一层无台阶，厨房临院', confirmed: true });
  assert.deepEqual(body.attachments, []);
  const old = harness({ step: 'HOUSE_SELECTION' });
  Object.assign(old.state.steps[2], { status: 'COMPLETED', canWrite: false, fields: { selectionResult: '沿用纸质方案', confirmed: true },
    attachments: [{assetId:'7002',title:'原户型.pdf'}] });
  await old.open();assert.equal(old.page.data.form.attachments[0].assetId, '7002');assert.equal(old.page.data.form.fields.selectionMode, '');
});

test('现场测量可登记朝向和高差，已核对旧单保留空值，历史读取本版数据', async () => {
  const { render } = require('./role-page-runtime.cjs');
  const h = harness({ step: 'MEASUREMENT' });
  await h.open();
  const page = h.page, template = path.join(root,'miniprogram/pages/customer-flow/index.wxml');
  const flatten = nodes => nodes.flatMap(node => [node,...flatten(node.children || [])]);
  const inputs = flatten(render(template,page.data));
  assert.ok(inputs.some(node => node.tag === 'input' && node.attrs['data-key'] === 'orientation'));
  assert.ok(inputs.some(node => node.tag === 'textarea' && node.attrs['data-key'] === 'siteElevationNote'));
  assert.equal(page.data.form.fields.orientation,''); assert.equal(page.data.form.fields.siteElevationNote,'');
  page.toggle(event('checked',true));
  page.input(event('orientation','南偏东 15°')); page.input(event('siteElevationNote','屋后 T 形路口高 0.45 米'));
  assert.equal(page.data.form.fields.checked,false);
  page.toggle(event('checked',true));
  await page.submit({currentTarget:{dataset:{action:'save'}}});
  const body = h.callsFor('save')[0].args[2];
  assert.equal(body.fields.orientation,'南偏东 15°'); assert.equal(body.fields.siteElevationNote,'屋后 T 形路口高 0.45 米');
  assert.equal(body.fields.checked,true);
  page.setData({records:model.records([{id:'81',stepCode:'MEASUREMENT',type:'SAVE',fields:{orientation:'正南',siteElevationNote:'旧测北界高 0.20 米'}}])});
  page.viewRecord({currentTarget:{dataset:{id:'81'}}});
  assert.ok(page.data.activeRecord.details.some(row => row.value === '正南'));
  assert.ok(page.data.activeRecord.details.some(row => row.value === '旧测北界高 0.20 米'));
  assert.equal(h.callsFor('complete').length,0);
});

test('第03步主动切回标准户型才提交清除标记，普通标准记录不带标记', async () => {
  const h = harness({ step: 'HOUSE_SELECTION' });
  Object.assign(h.state.steps[2], { status: 'DRAFT', canWrite: true, occurredOn: '2026-11-25',
    fields: { selectionMode: 'EXTERNAL_CUSTOM', customDirection: '保留老人房朝南', confirmed: true } });
  await h.open();
  h.page.pick(event('selectionMode', 0));
  assert.equal(h.page.data.form.fields.confirmed, false);
  assert.equal(h.page.data.form.occurredOn, '');
  h.page.input(event('selectionResult', '切回标准户型'));
  await h.page.submit({currentTarget:{dataset:{action:'save'}}});
  const body = h.callsFor('save')[0].args[2];
  assert.equal(body.fields.selectionMode, '');
  assert.equal(Object.hasOwn(body.fields, 'customDirection'), false);
  assert.equal(body.fields.confirmed, false);
  assert.equal(body.occurredOn, null);
  const standard = harness({ step: 'HOUSE_SELECTION' }); await standard.open();
  standard.page.input(event('selectionResult', '原本就是标准方案'));
  await standard.page.submit({currentTarget:{dataset:{action:'save'}}});
  assert.equal(Object.hasOwn(standard.callsFor('save')[0].args[2].fields,'selectionMode'), false);
});

test('进度卡与全部步骤按原单状态显示，跨步补录与切换保留草稿', async () => {
  const { render } = require('./role-page-runtime.cjs');
  const h = harness();
  h.state.steps[2].status = 'COMPLETED';
  Object.assign(h.state, { completedCount: 1, progressPercent: 12.5 });
  await h.open();
  const page = h.page;
  const flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children || [])]);
  const controls = () => flatten(render(path.join(root, 'miniprogram/pages/customer-flow/index.wxml'), page.data));
  const named = name => controls().filter(node => (node.attrs?.class || '').split(/\s+/).includes(name));
  assert.equal(named('journey-node').length, 8);
  assert.deepEqual(named('journey-node-done').map(n => n.attrs['data-code']), ['HOUSE_SELECTION']);
  assert.equal(named('journey-connector-done').length, 1);
  assert.equal(page.data.nextStepLabel, model.definition('INTRODUCTION').name);
  page.input(event('note', '到店前再联系'));
  page.showJourney();
  assert.equal(named('journey-row').length, 8);
  page.selectStep({ currentTarget: { dataset: { code: 'CONTRACT' } } });
  assert.equal(page.data.journeyOpen, false);
  assert.equal(page.data.step.code, 'CONTRACT');
  assert.equal(page.data.nextStepLabel, model.definition('INVITATION').name);
  page.selectStep({ currentTarget: { dataset: { code: 'INVITATION' } } });
  assert.equal(page.data.form.note, '到店前再联系');
  assert.equal(page.data.dirty, true);
  assert.equal(h.callsFor('save').length + h.callsFor('complete').length, 0);
  page.data.uncertain = true;
  page.showJourney();
  assert.equal(page.data.journeyOpen, false);
});

test('八步都完成后默认查看开工原单，单一未完成步骤保留待办状态', async () => {
  const h = harness();
  for (const step of h.state.steps) step.status = 'COMPLETED';
  Object.assign(h.state, { completedCount: 8, progressPercent: 100, currentStepCode: null, nextStepCode: null });
  await h.open();
  assert.equal(h.page.data.step.code, 'START');
  assert.equal(h.page.data.nextStepLabel, '');
  h.state.completedCount = 7;
  h.state.currentStepCode = 'START';
  h.state.nextStepCode = null;
  h.state.steps[7].status = 'TODO';
  await h.page.load();
  assert.equal(h.page.data.workflow.completedCount, 7);
  assert.equal(h.page.data.nextStepLabel, '');
  h.page.showJourney();
  h.page.clear();
  assert.equal(h.page.data.journeyOpen, false);
});

test('step six uses an exact quote for this customer and submits a communication without entering amounts or uploading a file', async () => {
  const quote = { id: '900719925474099999', customerId: '42', companyId: '9', totalCents: '1234500',
    budgetVersion: 3, priceVersion: '门店价目', createTime: Date.parse('2026-11-24T16:05:00Z') };
  const h = harness({ step: 'QUOTATION', api: { listQuotes: async () => [
    { ...quote, id: '888', customerId: '43' }, quote, { ...quote, id: '777', companyId: '8' },
    { ...quote, id: '776', totalCents: '0' }
  ] } });
  await h.open();
  await tick();
  const { render } = require('./role-page-runtime.cjs');
  const file = path.join(root, 'miniprogram/pages/customer-flow/index.wxml');
  const flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children || [])]);
  const controls = () => flatten(render(file, h.page.data));
  assert.ok(controls().some(node => node.attrs?.bindchange === 'chooseQuote'));
  assert.ok(controls().some(node => node.attrs?.bindtap === 'openBudget'));
  assert.deepEqual(h.page.data.quoteOptions.map(item => item.id), [quote.id]);
  assert.match(h.page.data.quoteOptions[0].label, /^2026-11-25/);
  const seconds = quote.createTime / 1000 + 0.345;
  assert.match(model.quoteOptions([{ ...quote, createTime: seconds }], '42', '9')[0].label, /^2026-11-25/);
  assert.equal(model.dateTime(seconds), '2026-11-25 00:05');
  h.page.chooseQuote({ detail: { value: '0' } });
  assert.equal(h.page.data.form.fields.amountCents, '12345.00');
  assert.ok(!h.page.data.fields.some(field => field.key === 'amountCents'));
  assert.ok(controls().some(node => node.attrs?.bindtap === 'quoteCommunicated'));
  h.page.openLinkedQuote();
  assert.equal(h.navigations.at(-1).url, '/pages/workflow/quote-export/index?quote-id=' + quote.id + '&source=customer-flow&customerId=42');
  h.page.quoteCommunicated({ currentTarget: { dataset: { result: '已发给客户' } } });
  assert.match(h.page.data.form.occurredOn, /^\d{4}-\d{2}-\d{2}$/);
  await h.page.submit({ currentTarget: { dataset: { action: 'complete' } } });
  const [save] = h.callsFor('save');
  assert.deepEqual(save.args[2].fields, { quoteId: quote.id, communicated: true, result: '已发给客户', designFileAssetId: '', designFileName: '' });
  assert.deepEqual(save.args[2].attachments, []);
  assert.equal(h.callsFor('complete').length, 1);
});

test('更换报价原单清除旧沟通日期和结果，历史查看仍打开当时的原单', async () => {
  const oldId = '900719925474099991', nextId = '900719925474099992';
  const original = { quoteId: oldId, amountCents: '12345', versionLabel: 'V1', communicated: true, result: '客户要求调整' };
  const record = { id: '901', stepCode: 'QUOTATION', type: 'COMPLETE', fields: original, occurredOn: '2026-11-01', attachments: [] };
  const h = harness({ step: 'QUOTATION', api: {
    listQuotes: async () => [{ id: nextId, companyId: '9', customerId: '42', totalCents: 25000, budgetVersion: 2 }],
    records: async () => ({ list: [record], total: 1 })
  } });
  Object.assign(h.state.steps[5], { fields: original, status: 'DRAFT', occurredOn: '2026-11-01' });
  await h.open(); await tick();
  h.page.chooseQuote({ detail: { value: 0 } });
  assert.deepEqual([h.page.data.form.fields.communicated, h.page.data.form.fields.result, h.page.data.form.occurredOn], [false, '', '']);
  h.page.quoteCommunicated({ currentTarget: { dataset: { result: '已向客户讲解' } } });
  assert.equal(h.page.data.form.occurredOn, require('../miniprogram/utils/finance-date').day(Date.now()));
  await h.page.toggleHistory();
  h.page.viewRecord({ currentTarget: { dataset: { id: record.id } } });
  assert.ok(h.page.data.activeRecord.details.some(row => row.value === '客户要求调整'));
  const { render } = require('./role-page-runtime.cjs');
  const flatten = rows => rows.flatMap(row => [row, ...flatten(row.children || [])]);
  const link = flatten(render(path.join(root, 'miniprogram/pages/customer-flow/index.wxml'), h.page.data))
    .find(row => row.attrs?.bindtap === 'openLinkedQuote');
  assert.ok(link, '历史报价须提供原版本的查看入口');
  h.page.openLinkedQuote();
  assert.equal(h.navigations.at(-1).url, '/pages/workflow/quote-export/index?quote-id=' + oldId + '&source=customer-flow&customerId=42');
  h.page.closeRecord();
  h.page.openLinkedQuote();
  assert.equal(h.navigations.at(-1).url, '/pages/workflow/quote-export/index?quote-id=' + nextId + '&source=customer-flow&customerId=42');
});

function exportHarness() {
  const quoteId = '900719925474099991', customerId = '42', rootPath = path.join(root, 'miniprogram/pages/workflow/quote-export');
  const snapshot = { quoteId, customerId, version: quoteId, quoteVersionLabel: 'V3 · 门店价目', name: '客户姓名',
    total: 10800, mainTotal: 10800, decorTotal: 0, main: [{ label: '主体', valueCents: 10800 }], decor: [] };
  const state = { current: '100', urls: [], removed: [], shared: [], get: async () => snapshot };
  const http = { token: () => 'session', currentPostId: () => state.current,
    get: async url => { state.urls.push(url); return state.get(url); } };
  const auth = { currentApiPost: () => ({ postId: state.current, brandId: '1' }) };
  const wx = { env: { USER_DATA_PATH: '/user' },
    getFileSystemManager: () => ({ unlink: arg => state.removed.push(arg.filePath), copyFile: arg => arg.success() }),
    previewImage: arg => state.shared.push(arg.current), shareFileMessage: arg => {state.shared.push(arg.filePath); arg.success();},
    showActionSheet: options => {state.menu=options;} };
  const module = {exports: {}};
  vm.runInNewContext(fs.readFileSync(path.join(rootPath, 'workflow-access.js'), 'utf8'), {
    module, wx, require: dependency => {
      if (dependency === '../../../api/http') return http;
      if (dependency === '../../../api/auth') return auth;
      if (dependency === '../../../utils/api-scope') return () => 'post-' + state.current;
      throw Error(dependency);
    }
  });
  const sheet = require('../miniprogram/pages/workflow/quote-export/sheet');
  let definition;
  vm.runInNewContext(fs.readFileSync(path.join(rootPath, 'index.js'), 'utf8'), { wx, Page: value => { definition=value; },
    require: dependency => ({ './workflow-access': module.exports, './sheet': sheet, '../../../api/http': http })[dependency] || {} });
  const page = { ...definition, data: copy(definition.data), setData(value) { Object.assign(this.data, value); } };
  async function open(source='customer-flow', id=customerId) {
    await page.onLoad({ 'quote-id': quoteId, source, customerId:id }); await page.onShow();
  }
  function seedFiles() {
    page.jpeg='/jpeg'; page.setData({image:'/png',pdfFile:'/pdf'});
    for (const name of ['/jpeg','/png','/pdf']) page.guard.keep(page.ticket(),name);
  }
  return {page, state, open, seedFiles, snapshot, sheet, quoteId, customerId};
}

test('第六步查看与导出使用同一客户的冻结报价版本，交易域原有票据仍可用', async () => {
  for (const source of ['customer-flow', '']) {
    const {page, state, open, sheet, quoteId} = exportHarness(); await open(source);
    const prefix=source ? '/franchise/customers/42/workflow/quotes/' : '/franchise/sales/quotes/';
    assert.deepEqual(state.urls,[prefix+quoteId+'/export-sheet']);
    assert.equal(page.data.allowed,true);
    const expected=source?'正式建造报价 V3 · 门店价目':'建造参考预算 V'+quoteId;
    assert.equal(page.data.label,expected); assert.equal(page.data.total,sheet.money(10800));
    assert.ok(sheet.filename(page.doc,'pdf').includes(source?'V3 · 门店价目':'V'+quoteId));
    const canvasText = [];
    const context = new Proxy({ measureText: text => ({ width: String(text).length * 6 }), fillText: text => canvasText.push(text) },
      {get:(target,key)=>target[key]??(()=>{})});
    require('../miniprogram/pages/workflow/quote-export/render').render(context,page.doc);
    assert.ok(canvasText.includes(expected));
  }
});

test('客户转交后预览、刷新、图片、PDF和转发逐次复核，清掉文档及所有临时文件', async () => {
  for (const action of ['preview','generate','exportImage','exportPdf','sharePdf']) {
    const {page,state,open,seedFiles}=exportHarness(); await open();seedFiles();
    state.get=async()=>{throw Error('客户不存在或无权访问');};
    await page[action]();
    assert.equal(state.urls.length,2,action); assert.equal(page.doc,null); assert.equal(page.data.allowed,false);
    assert.equal(page.data.image,''); assert.equal(page.data.pdfFile,''); assert.equal(page.data.label,'');
    assert.deepEqual(state.removed.sort(),['/jpeg','/pdf','/png']); assert.deepEqual(state.shared,[]);
  }
  const {page,state,open,seedFiles}=exportHarness();await open();seedFiles();await page.exportImage();
  state.get=async()=>{throw Error('已转交');};await state.menu.success({tapIndex:1});
  assert.equal(state.urls.length,3);assert.deepEqual(state.shared,[]);assert.equal(page.doc,null);
});

test('隐藏即清除客户报价，过期响应不能重现旧客户，切岗及不匹配的原单均收口', async () => {
  const {page,state,open,seedFiles,snapshot}=exportHarness();await open();seedFiles();
  let finish;state.get=()=>new Promise(resolve=>{finish=resolve;});const waiting=page.preview();
  await tick();page.onHide();assert.equal(page.doc,null);state.current='101';
  state.get=async()=>({...snapshot,name:'新授权结果'});await page.onShow();finish(snapshot);await waiting;
  assert.equal(page.doc.name,'新授权结果');assert.deepEqual(state.shared,[]);
  state.current='102';await page.sharePdf();assert.equal(page.doc,null);assert.equal(page.data.image,'');
  state.get=async()=>({...snapshot,customerId:'41'});await page.onShow();assert.match(page.data.error,/不匹配/);
  const broken=exportHarness();await broken.open('customer-flow','');assert.equal(broken.state.urls.length,0);
  assert.equal(broken.page.data.allowed,false);assert.match(broken.page.data.error,/链接/);
});

test('外部正式报价改版或替换文件须重新登记沟通，原单选填的附件不影响已有沟通', async () => {
  const h = harness({ step: 'QUOTATION' });
  const oldDate = '2026-11-01';
  const external = { amountCents: '17000', scope: '主体', versionLabel: '外部 V1', communicated: true, result: '已讲解' };
  const step = h.state.steps[5];
  Object.assign(step, { fields: external, occurredOn: oldDate, attachments: [{ assetId: '201', title: '原版报价.pdf' }] });
  await h.open();
  h.page.input(event('note', '补充备注'));
  assert.equal(h.page.data.form.occurredOn, oldDate);
  h.page.input(event('versionLabel', '外部 V2'));
  assert.deepEqual([h.page.data.form.fields.communicated, h.page.data.form.fields.result, h.page.data.form.occurredOn], [false, '', '']);
  await h.page.load({ replace: true });
  await h.page.startUpload({ name: '新版报价.pdf', path: '/local/q.pdf' });
  assert.equal(h.page.data.form.attachments.length, 2);
  assert.equal(h.page.data.form.fields.communicated, false);
  Object.assign(step, { fields: { ...external, quoteId: '7701' } });
  await h.page.load({ replace: true });
  await h.page.startUpload({ name: '补充说明.pdf', path: '/local/n.pdf' });
  assert.equal(h.page.data.form.fields.communicated, true);
  assert.equal(h.page.data.form.occurredOn, oldDate);
  h.page.removeQuote();
  assert.deepEqual(h.page.data.form.attachments, []);
  assert.equal(h.page.data.form.fields.communicated, false);
  Object.assign(step, { fields: external, occurredOn: oldDate, attachments: [] });
  await h.page.load({ replace: true });
  await h.page.startUpload({ name: '首次正式报价.pdf', path: '/local/first.pdf' });
  assert.equal(h.page.data.form.fields.communicated, true);
  assert.equal(h.page.data.form.occurredOn, oldDate);
});

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

test('第07步为合同签约，设计合同归档后待办，签署施工合同后回看原单', async () => {
  const h = harness({ step: 'CONTRACT' });
  await h.open();
  const { render } = require('./role-page-runtime.cjs');
  const file = path.join(root, 'miniprogram/pages/customer-flow/index.wxml');
  const renderedText = node => node.text || (node.children || []).map(renderedText).join('');
  const visible = () => render(file, h.page.data).map(renderedText).join('');
  assert.equal(h.page.data.step.name, '合同签约');
  assert.match(visible(), /设计服务合同可归入设计委托档案/);
  Object.assign(h.state.steps[6], { status: 'COMPLETED', fields: { contractId: '8101', signedDate: '2026-11-25', totalCents: '100000' } });
  Object.assign(h.state, { completedCount: 1, progressPercent: 12.5 });
  h.page.applyWorkflow(h.state);
  assert.equal(h.page.data.step.statusLabel, '已完成');
  assert.match(visible(), /8101/);
  assert.match(visible(), /查看施工合同/);
  assert.doesNotMatch(visible(), /设计服务合同可归入设计委托档案/);
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

test('客户列表使用批量内联摘要，下次跟进日期读客户表，按钮统一进八步页', () => {
  const crm = require('../miniprogram/utils/platform-crm');
  const row = crm.customerRow({ id: '900719925474099999', name: '客户', createTime: 0, lastFollowAt: 1, nextVisitDate: [2026, 12, 1],
    workflow: { status: 'ACTIVE', completedCount: 3, totalSteps: 8, progressPercent: 37.5, currentStepName: '带看工地', nextContactOn: '2026-11-27' } }, { canWrite: true, today: '2026-11-25' });
  assert.equal(row.stageName, '带看工地'); assert.equal(row.appointment, '2026-12-01'); assert.match(row.detail, /12-01/);
  assert.equal(row.workflow.nextContactOn, '2026-11-27', '八步邀约日期保持在原摘要');
  assert.equal(row.progress, '3/8步 · 37.5%'); assert.equal(row.action, '客户跟进'); assert.equal(row.off, false);
  assert.equal(row.workflow.progressPercent, 37.5); assert.equal(row.flowUrl, '/pages/customer-flow/index?customerId=900719925474099999');
  assert.match(row.url, /pages\/workflow\/index\?view=customer/); assert.doesNotMatch(row.url, /modal=follow/);
});

test('流失与暂缓的客户置灰，不进待跟进和预约跟进；再次咨询的流失客户突出显示', () => {
  const crm = require('../miniprogram/utils/platform-crm');
  const day = '2026-11-26';
  const lost = crm.customerRow({ id: '1', name: '刘先生', lastFollowAt: 1, workflow: { status: 'LOST', statusReason: '选了同行', completedCount: 4, totalSteps: 8, progressPercent: 50 } }, { today: day });
  assert.equal(lost.stageName, '已流失'); assert.equal(lost.cue, '选了同行'); assert.equal(lost.off, true); assert.equal(lost.action, '查看 / 恢复');
  const paused = crm.customerRow({ id: '2', name: '陈先生', lastFollowAt: 1, nextVisitDate: [2027, 3, 15], workflow: { status: 'PAUSED', completedCount: 2, totalSteps: 8, progressPercent: 25 } }, { today: day });
  assert.equal(paused.stageName, '暂缓'); assert.equal(paused.cue, '03-15 再联系'); assert.equal(paused.off, true);
  const again = crm.customerRow({ id: '3', name: '周先生', lastFollowAt: 1, workflow: { status: 'LOST', reconsulted: true, completedCount: 2, totalSteps: 8, progressPercent: 25 } }, { today: day });
  assert.equal(again.cue, '再次咨询'); assert.equal(again.cueTone, 'tone-warning');
  const overdue = crm.customerRow({ id: '4', name: '钱女士', lastFollowAt: 1, nextVisitDate: [2026, 11, 24], workflow: { status: 'ACTIVE', completedCount: 2, totalSteps: 8, progressPercent: 25 } }, { today: day });
  assert.equal(overdue.cue, '逾期 2 天');
  assert.deepEqual(crm.tabFilter([overdue, lost, paused, again], 'due', { today: day }).map(row => row.id), ['4']);
  assert.deepEqual(crm.tabFilter([overdue, lost, paused, again], 'appointments', { today: day }).map(row => row.id), ['2']);
  const follow = require('../miniprogram/pages/workflow/customer-follow');
  assert.deepEqual(follow.railView({ currentStepCode: 'HOUSE_SELECTION', steps: [{ code: 'INVITATION', status: 'COMPLETED' }, { code: 'INTRODUCTION', status: 'COMPLETED' }, { code: 'HOUSE_SELECTION', status: 'TODO' }] }).map(row => row.active), [false, false, true]);
  const timeline = follow.timelineView({ list: [{ id: '9', stepCode: 'INTRODUCTION', type: 'CONSULT', note: '想了解两层报价', createdAt: Date.now(), actorName: '李业务', attachments: [] }], total: 9 });
  assert.equal(timeline.timeline[0].title, '店面讲解 · 再次咨询'); assert.equal(timeline.timeline[0].kind, 'lead'); assert.equal(timeline.timelineMore, true); assert.equal(timeline.timelineTotal, 9);
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


test('新办理日取上海当天，快捷到店含具体日期时间，确认与历史留给经办人', async () => {
  const entry = require('../miniprogram/pages/customer-flow/visit-entry');
  const before = Date.parse('2026-11-26T15:59:59.000Z'), after = before + 1000;
  const h = harness(), raw = h.state.steps[0], a = model.form(raw), b = model.form(raw);
  entry.defaultDate(raw, a, before); entry.defaultDate(raw, b, after);
  assert.equal(a.occurredOn, '2026-11-26'); assert.equal(b.occurredOn, '2026-11-27');
  assert.deepEqual(entry.appointmentTimes(before)[0], { date: '2026-11-27', time: '09:00', label: '明天 11-27 09:00' });
  await h.open(); const page = h.page;
  const choose = (kind,index=0) => page.chooseVisitEntry({ currentTarget: { dataset: { kind,index } } });
  assert.equal(page.data.form.fields.invitationResult, ''); assert.equal(page.data.form.appointmentDate, '');
  choose('time'); assert.equal(page.data.form.appointmentDate, page.data.visitTimes[0].date); assert.equal(page.data.form.appointmentTime, page.data.visitTimes[0].time);
  assert.equal(page.data.form.fields.invitationResult, ''); assert.equal(h.callsFor('save').length+h.callsFor('complete').length,0);
  page.input(event('occurredOn','')); page.select('SITE_VISIT'); page.select('INVITATION'); assert.equal(page.data.form.occurredOn,'');
  const historical={...raw,status:'DRAFT',revision:2,occurredOn:'2026-11-15'}; const old=model.form(historical);entry.defaultDate(historical,old,after);assert.equal(old.occurredOn,'2026-11-15');
  const unknown={...historical,occurredOn:null}, blank=model.form(unknown);entry.defaultDate(unknown,blank,after);assert.equal(blank.occurredOn,'');
  assert.ok(!h.callsFor('save').length && !h.callsFor('complete').length);
});

test('讲解仅带同一客户档案与当前任职本人的资料，可编辑反馈且不会保存', async () => {
  const profile=deferred(), customer=deferred(), h=harness({step:'INTRODUCTION',post:{userId:'9007199254740993121',orgId:'9'},loginUser:'9007199254740993121',
    api:{entryProfile:()=>profile.promise,entryCustomer:()=>customer.promise}});
  await h.open();profile.resolve({id:'9007199254740993121',nickname:'孙老师'});customer.resolve({customer:{id:'42',summary:'两层半、老人房在一楼'}});await tick();
  const p=h.page;assert.equal(p.data.form.fields.receptionist,'孙老师');assert.equal(p.data.form.fields.requirements,'两层半、老人房在一楼');assert.equal(p.data.dirty,false);
  p.input(event('feedback','已看展厅'));const feedbackEvent={currentTarget:{dataset:{kind:'feedback',index:0}}};p.chooseVisitEntry(feedbackEvent);p.chooseVisitEntry(feedbackEvent);
  assert.equal(p.data.form.fields.feedback,'已看展厅\n想进一步了解');p.input(event('feedback','家人讨论后再联系'));assert.equal(p.data.form.fields.feedback,'家人讨论后再联系');
  assert.deepEqual(h.callsFor('entryCustomer')[0].args,['42']);assert.equal(h.callsFor('entryProfile').length,1);assert.equal(h.callsFor('save').length+h.callsFor('complete').length,0);
  const mismatch=harness({step:'INTRODUCTION',post:{userId:'55',orgId:'9'},loginUser:'55',api:{entryCustomer:async()=>({customer:{id:'43',summary:'其他客户'}}),entryProfile:async()=>({id:'56',nickname:'他人'})}});
  await mismatch.open();await tick();assert.equal(mismatch.page.data.form.fields.requirements,'');assert.equal(mismatch.page.data.form.fields.receptionist,'');
});

test('切公司、已有历史、用户开始输入后，迟到的默认来源均不覆盖草稿', async () => {
  const source=deferred(), me=deferred();const h=harness({step:'INTRODUCTION',post:{userId:'20',orgId:'9'},loginUser:'20',api:{entryCustomer:()=>source.promise,entryProfile:()=>me.promise}});
  await h.open();h.page.input(event('requirements','手填新的需求'));source.resolve({customer:{id:'42',summary:'过期'}});me.resolve({id:'20',nickname:'自动名'});await tick();
  assert.equal(h.page.data.form.fields.requirements,'手填新的需求');assert.equal(h.page.data.form.fields.receptionist,'');
  const returned=deferred(),p=harness({step:'INTRODUCTION',api:{entryCustomer:()=>returned.promise}});await p.open();p.switchSession();returned.resolve({customer:{id:'42',summary:'旧岗'}});await tick();assert.equal(p.page.data.form.fields.requirements,'');
  const old=harness({step:'INTRODUCTION'});Object.assign(old.state.steps[1],{status:'DRAFT',revision:4,occurredOn:'2026-10-01',fields:{feedback:'先前'}});await old.open();assert.equal(old.callsFor('entryCustomer').length,0);assert.equal(old.page.data.form.fields.receptionist,'');assert.equal(old.page.data.form.occurredOn,'2026-10-01');
  const ro=harness({step:'INTRODUCTION'});ro.state.canWrite=false;await ro.open();assert.equal(ro.callsFor('entryCustomer').length,0);assert.equal(ro.page.data.form.occurredOn,'');assert.deepEqual(ro.page.data.visitFeedback,[]);
});

test('带看仅选本客户已办快照，排除草稿和其他步骤，分页与手填反馈均可用', async () => {
  const first={id:'9007199254740993133',stepCode:'SITE_VISIT',type:'COMPLETE',fields:{siteName:'一号工地',address:'杨柳村'}}, second={...first,id:'9007199254740993134',fields:{siteName:'二号工地',address:'河西'}};
  const h=harness({step:'SITE_VISIT',api:{records:async(id,p)=>{assert.equal(id,'42');assert.equal(p,2);return{total:23,list:[first,second,{...first,customerId:'88',id:'55',fields:{siteName:'他客工地'}}]};}}});
  h.state.events=[first,{...first,id:'20',type:'SAVE',fields:{siteName:'拟带看'}},{...second,stepCode:'MEASUREMENT'}];h.state.hasMoreEvents=true;await h.open();
  assert.deepEqual(h.page.data.visitSites.map(s=>s.name),['一号工地']);await h.page.moreVisitSites();assert.deepEqual(h.page.data.visitSites.map(s=>s.name),['一号工地','二号工地']);
  h.page.chooseVisitEntry({currentTarget:{dataset:{kind:'site',index:1}}});assert.equal(h.page.data.form.fields.siteName,'二号工地');assert.equal(h.page.data.form.fields.address,'河西');
  h.page.chooseVisitEntry({currentTarget:{dataset:{kind:'feedback',index:0}}});h.page.input(event('feedback','业主希望下周再看基础做法'));assert.equal(h.page.data.form.fields.feedback,'业主希望下周再看基础做法');assert.equal(h.callsFor('save').length+h.callsFor('complete').length,0);
  const none=harness({step:'SITE_VISIT'});await none.open();assert.deepEqual(none.page.data.visitSites,[]);none.page.input(event('siteName','手填地点'));assert.equal(none.page.data.form.fields.siteName,'手填地点');
});


test('当前客户读权撤回会清除建议，较早历史失败可重试且不能覆写另一客户', async () => {
  const h=harness({step:'INTRODUCTION',api:{entryCustomer:async()=>{throw {code:1110000001,message:'客户已转交'};}}});
  await h.open();await tick();assert.equal(h.page.data.workflow,null);assert.equal(h.page.data.form,null);assert.deepEqual(h.page.data.visitSites,[]);
  const held=deferred(), history=harness({step:'SITE_VISIT',api:{records:()=>held.promise}});
  history.state.hasMoreEvents=true;await history.open();const pending=history.page.moreVisitSites();history.page.select('INVITATION');
  held.resolve({total:25,list:[{id:'198',stepCode:'SITE_VISIT',type:'COMPLETE',fields:{siteName:'上一步记录'}}]});await pending;assert.deepEqual(history.page.data.visitSites,[]);
  const render = require('./role-page-runtime.cjs').render, file = path.join(root,'miniprogram/pages/customer-flow/index.wxml');
  const flatten=nodes=>nodes.flatMap(n=>[n,...flatten(n.children||[])]);
  assert.ok(flatten(render(file,history.page.data)).some(n=>n.attrs?.['data-kind']==='time' && n.attrs?.bindtap==='chooseVisitEntry'));
  history.state.canWrite=false;await history.open();assert.ok(!flatten(render(file,history.page.data)).some(n=>n.attrs?.bindtap==='chooseVisitEntry'));
});

test('带看由经办人填写原因后主动跳过，续办复用原命令，业务和总部显示原事实', async () => {
  const h = harness({step:'SITE_VISIT'}), page = h.page;
  const reason = '客户已现场看过自家工程，决定本次免带看';
  const createdAt = 1795691044.078718, evidence = {reason, actorUserId:'42', actorName:'张工', actorPostId:'18'};
  const receipt = {id:'9007199254740998123',stepCode:'SITE_VISIT',type:'SKIP',note:reason,createdAt,actorUserId:'42',actorName:'张工',fields:{visitSkip:evidence}};
  let calls = 0;
  h.overrides.skipVisit = async () => {
    if (++calls === 1) throw Error('回执丢失');
    Object.assign(h.state, {revision:1,completedCount:1,progressPercent:12.5,events:[receipt]});
    Object.assign(h.state.steps[3], {status:'SKIPPED',revision:1,note:reason,fields:{visitSkip:evidence},completedAt:createdAt,canWrite:false,canComplete:false,canReopen:true});
    return copy(h.state);
  };
  h.overrides.records = async () => ({list:[receipt],total:1});
  await h.open(); page.skipVisit(); assert.equal(h.modal.confirmText,'跳过带看');
  await h.modal.success({confirm:false,content:reason}); assert.equal(h.callsFor('skipVisit').length,0);
  page.skipVisit(); await h.modal.success({confirm:true,content:'  '}); assert.match(page.data.error,/原因/);
  page.skipVisit(); await h.modal.success({confirm:true,content:reason}); assert.equal(page.data.uncertain,true);
  await page.runPending(); const attempts=h.callsFor('skipVisit');
  assert.deepEqual(attempts[0].args,attempts[1].args);assert.deepEqual(attempts[0].args[1],{revision:0,reason});
  assert.equal(page.data.workflow.completedCount,1); assert.equal(page.data.step.statusLabel,'已跳过');
  assert.deepEqual(page.data.facts.map(f=>f.value),[reason,'张工','2026-11-26 19:04']);
  assert.equal(h.callsFor('complete').length,0);assert.equal(h.callsFor('save').length,0);
  await page.toggleHistory(); page.viewRecord({currentTarget:{dataset:{id:receipt.id}}});
  assert.equal(page.data.activeRecord.actionLabel,'已跳过带看');assert.deepEqual(page.data.activeRecord.details,[]);
  const hq = require('../miniprogram/pages/headquarters/model');
  const readonly = copy(h.state);readonly.canWrite=false;
  for(const step of readonly.steps) Object.assign(step,{canWrite:false,canReopen:false,canComplete:false});
  const view = hq.customer({id:'42',companyId:'9',readOnly:true,workflow:readonly},()=>{throw Error('跳过没有现场原件')});
  assert.equal(view.steps[3].status,'已跳过');assert.equal(view.steps[3].note,reason);
  assert.deepEqual(view.steps[3].fields.map(f=>f.value),['张工','2026-11-26 19:04']);
  assert.equal(hq.records([receipt],'42',()=>{})[0].type,'已跳过带看');
});

test('其他步骤、已实际带看及过期的免带看弹窗均不能发出跳过命令', async () => {
  const other=harness({step:'INTRODUCTION'});await other.open();other.page.skipVisit();assert.equal(other.modal,undefined);
  const h=harness({step:'SITE_VISIT'});await h.open();h.page.skipVisit();const modal=h.modal;
  h.switchSession();await modal.success({confirm:true,content:'原页面'});assert.equal(h.callsFor('skipVisit').length,0);
  const done=harness({step:'SITE_VISIT'});Object.assign(done.state.steps[3],{status:'COMPLETED',canWrite:false,fields:{siteName:'实地看过'}});
  await done.open();done.page.skipVisit();assert.equal(done.modal,undefined);assert.equal(done.page.data.step.statusLabel,'已带看');
  const draft=harness({step:'SITE_VISIT'});await draft.open();draft.page.input(event('siteName','刚输入的现场'));draft.page.skipVisit();
  assert.equal(draft.modal,undefined);assert.equal(draft.page.data.form.fields.siteName,'刚输入的现场');
});

// ===== 下次跟进日期与流程状态（八步流程是客户跟进的唯一事实来源） =====
const dayText = offset => require('../miniprogram/utils/platform-crm').toDate(Date.now() + offset * 86400000);

test('完成本步前必须约定下次跟进日期；仅保存记录可不填，带了就随记录提交', async () => {
  const h = harness(); await h.open();
  // 本步还没单独约定时，沿用客户当前还没过期的约定
  assert.equal(h.page.data.form.nextFollowOn, nextFollowOn);
  h.page.input(event('nextFollowOn', ''));
  await h.page.submit({ currentTarget: { dataset: { action: 'complete' } } });
  assert.match(h.page.data.error, /请先约定下次跟进日期/);
  assert.equal(h.callsFor('save').length, 0, '未约定日期时不发出任何保存或完成请求');
  await h.page.submit({ currentTarget: { dataset: { action: 'save' } } });
  assert.equal(h.callsFor('save')[0].args[2].nextFollowOn, null);
  h.page.input(event('nextFollowOn', '2000-01-01'));
  await h.page.submit({ currentTarget: { dataset: { action: 'save' } } });
  assert.match(h.page.data.error, /下次跟进日期不能早于今天/);
  assert.equal(h.callsFor('save').length, 1);
  h.page.input(event('nextFollowOn', dayText(0)));
  await h.page.submit({ currentTarget: { dataset: { action: 'complete' } } });
  assert.equal(h.callsFor('save').at(-1).args[2].nextFollowOn, dayText(0));
  assert.equal(h.callsFor('complete').length, 1);
  assert.equal(h.page.data.error, '');
});

test('标记流失：必选原因，“其他”须自填；按服务端当前流程版本提交并刷新', async () => {
  const h = harness(); await h.open();
  h.page.journeyState();
  assert.equal(h.actionSheet.itemList.join(','), '暂缓跟进,标记流失');
  h.actionSheet.success({ tapIndex: 1 });
  assert.equal(h.page.data.statusSheet.kind, 'lost');
  await h.page.submitStatus(); assert.match(h.page.data.statusSheet.error, /请选择流失原因/);
  assert.equal(h.callsFor('status').length, 0);
  assert.equal(h.state.status, 'ACTIVE');
  h.page.statusPick({ currentTarget: { dataset: { code: 'COMPETITOR' } } });
  await h.page.submitStatus();
  assert.deepEqual(h.callsFor('status')[0].args.slice(0, 2), ['42', { status: 'LOST', reasonCode: 'COMPETITOR', reason: '', revision: h.state.revision - 1 }]);
  const request = h.requests.find(row => row.method === 'POST');
  assert.equal(request.url, '/franchise/customers/42/workflow/status');
  assert.deepEqual(request.body, h.callsFor('status')[0].args[1]);
  assert.equal(request.headers['Idempotency-Key'], h.callsFor('status')[0].args[2]);
  assert.ok(request.headers['Idempotency-Key']);
  assert.equal(h.state.status, 'LOST'); assert.equal(h.page.data.statusSheet, null);
  assert.ok(h.toasts.some(text => text.title === '已标记流失'));
  assert.equal(h.callsFor('get').length, 3, '首次加载、提交前取版本、成功后刷新各读取一次');

  const other = harness(); await other.open();
  other.page.openStatus({ currentTarget: { dataset: { kind: 'lost' } } });
  other.page.statusPick({ currentTarget: { dataset: { code: 'OTHER' } } });
  await other.page.submitStatus(); assert.match(other.page.data.statusSheet.error, /请填写流失原因/);
  other.page.statusInput({ detail: { value: ' 选了同村施工队 ' } });
  await other.page.submitStatus();
  assert.equal(other.callsFor('status')[0].args[1].reason, '选了同村施工队');
  other.page.closeStatus(); assert.equal(other.page.data.statusSheet, null);
});

test('暂缓须填写原因和晚于今天的再联系日期；已暂缓或已流失时流程状态可恢复', async () => {
  const h = harness(); await h.open();
  h.page.openStatus({ currentTarget: { dataset: { kind: 'paused' } } });
  await h.page.submitStatus(); assert.match(h.page.data.statusSheet.error, /请填写暂缓原因/);
  h.page.statusInput({ detail: { value: '等宅基地批复' } });
  h.page.statusDate({ detail: { value: dayText(0) } });
  await h.page.submitStatus(); assert.match(h.page.data.statusSheet.error, /晚于今天/);
  assert.equal(h.callsFor('status').length, 0);
  h.page.statusDate({ detail: { value: dayText(30) } });
  await h.page.submitStatus();
  assert.deepEqual(h.callsFor('status')[0].args[1], { status: 'PAUSED', reason: '等宅基地批复', nextFollowOn: dayText(30), revision: h.state.revision - 1 });
  assert.equal(h.state.status, 'PAUSED');
  h.page.journeyState(); assert.equal(h.actionSheet.itemList.join(','), '恢复跟进');
  h.actionSheet.success({ tapIndex: 0 });
  assert.equal(h.page.data.statusSheet.kind, 'resume');
  await h.page.submitStatus();
  assert.deepEqual(h.callsFor('status')[1].args[1], { status: 'ACTIVE', revision: h.state.revision - 1 });
  assert.equal(h.state.status, 'ACTIVE');

  const lost = harness(); lost.state.status = 'LOST'; await lost.open();
  lost.page.journeyState(); lost.actionSheet.success({ tapIndex: 0 }); await lost.page.submitStatus();
  assert.equal(lost.callsFor('status')[0].args[1].status, 'ACTIVE');
  assert.equal(lost.page.data.workflow.status, 'ACTIVE');
});

test('带 status 参数进入时自动打开对应弹层，只打开一次；不可办理的客户不打开', async () => {
  const h = harness({ status: 'lost' }); await h.open();
  assert.equal(h.page.data.statusSheet.kind, 'lost');
  await h.page.load({ replace: true }); assert.equal(h.page.data.statusSheet.kind, 'lost', '之后的刷新不再重复弹出或关闭');
  h.page.closeStatus(); await h.page.load({ replace: true }); assert.equal(h.page.data.statusSheet, null);
  const readonly = harness({ status: 'lost' }); Object.assign(readonly.state, { canChangeStatus: false }); await readonly.open();
  assert.equal(readonly.page.data.statusSheet, null);
  readonly.page.openStatus({ currentTarget: { dataset: { kind: 'lost' } } });
  assert.equal(readonly.page.data.statusSheet, null);
});

test('状态提交结果未知时保留原键重试，确定失败时提示原因', async () => {
  const h = harness(); await h.open();
  h.page.openStatus({ currentTarget: { dataset: { kind: 'paused' } } });
  h.page.statusInput({ detail: { value: '等批复' } }); h.page.statusDate({ detail: { value: dayText(9) } });
  h.overrides.status = async () => { h.state.revision += 1; throw { code: -1 }; };
  await h.page.submitStatus();
  assert.equal(h.page.data.statusSheet.busy, false); assert.match(h.page.data.statusSheet.error, /未收到保存确认/);
  const readCount=h.callsFor('get').length;
  h.page.statusInput({ detail: { value: '不能替换旧请求的内容' } });
  h.overrides.status = async () => { throw { code: 409, message: '流程已更新，请刷新后重试' }; };
  await h.page.submitStatus();
  assert.deepEqual(h.callsFor('status')[1].args, h.callsFor('status')[0].args, '回执丢失后只重发原日期、原原因、原版本和原编号');
  assert.equal(h.callsFor('get').length,readCount,'结果未知时不重新取版本');
  assert.match(h.page.data.statusSheet.error, /流程已更新/);
  delete h.overrides.status;
  await h.page.submitStatus();
  assert.notEqual(h.callsFor('status')[2].args[2], h.callsFor('status')[0].args[2], '确定失败后换新的幂等键');
  assert.equal(h.page.data.statusSheet, null);
});

test('状态已保存但回执丢失，重试找回原结果且不再执行变更', async () => {
  const h=harness(); await h.open();
  h.page.openStatus({ currentTarget: { dataset: { kind: 'lost' } } });
  h.page.statusPick({ currentTarget: { dataset: { code: 'PRICE' } } });
  let original, writes=0;
  h.overrides.status=async (...args) => {
    if (!original) {
      original=copy(args);
      h.state.status='LOST'; h.state.revision += 1; writes += 1;
      throw { code: -1 };
    }
    assert.deepEqual(args,original);
    return copy(h.state);
  };
  await h.page.submitStatus();
  assert.equal(h.page.data.statusSheet.pending,true);
  assert.equal(writes,1);
  await h.page.submitStatus();
  assert.equal(writes,1);
  assert.equal(h.page.data.statusSheet,null);
  const requests = h.requests.filter(row => row.method === 'POST');
  assert.equal(requests.length, 2);
  assert.deepEqual(requests[1], requests[0], '真实 HTTP 请求保留首次日期、版本、内容和 Idempotency-Key');
});

test('状态 revision 冲突保留原状态和填写，重试读取最新版本并使用新命令', async () => {
  const h = harness(); await h.open();
  h.page.openStatus({ currentTarget: { dataset: { kind: 'paused' } } });
  h.page.statusInput({ detail: { value: '等审批' } });
  await h.page.submitStatus();
  assert.equal(h.callsFor('status').length, 0, '缺必要日期不提交');
  h.page.statusDate({ detail: { value: dayText(3) } });
  h.overrides.status = async () => { h.state.revision = 4; throw { code: 409, message: '流程已更新，请刷新后重试' }; };
  await h.page.submitStatus();
  assert.equal(h.state.status, 'ACTIVE');
  assert.equal(h.page.data.workflow.status, 'ACTIVE');
  assert.equal(h.page.data.statusSheet.reasonText, '等审批');
  assert.match(h.page.data.statusSheet.error, /流程已更新/);
  assert.equal(h.page.data.statusSheet.pending, false);
  assert.equal(h.page.__statusCommand, null);
  delete h.overrides.status;
  await h.page.submitStatus();
  const [first, retry] = h.callsFor('status');
  assert.equal(first.args[1].revision, 0);
  assert.equal(retry.args[1].revision, 4);
  assert.notEqual(retry.args[2], first.args[2]);
  assert.equal(h.page.data.workflow.status, 'PAUSED');
});

test('状态读取或提交后切换客户/岗位，迟到响应不能污染当前客户或新的弹层', async () => {
  for (const phase of ['get', 'status']) {
    for (const change of ['customer', 'post']) {
      for (const rejected of [false, true]) {
        const h = harness(); await h.open();
        h.page.openStatus({ currentTarget: { dataset: { kind: 'lost' } } });
        h.page.statusPick({ currentTarget: { dataset: { code: 'PRICE' } } });
        const held = deferred(); h.overrides[phase] = () => held.promise;
        const pending = h.page.submitStatus(); await tick();
        delete h.overrides[phase];
        if (change === 'customer') { h.page.clear(); h.page.onLoad({ customerId: '43' }); }
        else h.switchSession();
        h.state = workflow({ customerId: h.page.customerId, customerName: '当前客户' });
        await h.open();
        h.page.openStatus({ currentTarget: { dataset: { kind: 'paused' } } });
        h.page.statusInput({ detail: { value: '当前填写' } });
        const expected = copy(h.page.data);
        if (rejected) held.reject({ code: 409, message: '旧客户的冲突' });
        else held.resolve(workflow({ status: 'LOST', revision: 99, customerName: '旧客户' }));
        await pending;
        assert.deepEqual(copy(h.page.data), expected, [phase, change, rejected].join('/'));
        assert.equal(h.toasts.length, 0);
        assert.equal(h.callsFor('status').length, phase === 'status' ? 1 : 0, '旧客户的迟到读取不得继续提交');
      }
    }
  }
});

test('八步页模板包含下次跟进日期、可点的流程状态和状态弹层', async () => {
  const { render } = require('./role-page-runtime.cjs');
  const h = harness(); await h.open();
  const template = path.join(root, 'miniprogram/pages/customer-flow/index.wxml'), flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children || [])]);
  const nodes = flatten(render(template, h.page.data));
  assert.ok(nodes.some(node => node.tag === 'picker' && node.attrs['data-key'] === 'nextFollowOn'));
  assert.ok(nodes.some(node => node.attrs && node.attrs.bindtap === 'journeyState'));
  assert.equal(nodes.some(node => node.attrs && node.attrs.bindtap === 'statusPick'), false, '未打开时不渲染弹层');
  h.page.openStatus({ currentTarget: { dataset: { kind: 'lost' } } });
  const sheet = flatten(render(template, h.page.data));
  for (const handler of ['statusPick', 'closeStatus', 'submitStatus']) assert.ok(sheet.some(node => node.attrs && node.attrs.bindtap === handler), handler);
  assert.equal(sheet.filter(node => node.attrs && node.attrs.bindtap === 'statusPick').length, h.page.data.lostReasons.length);
  // “其他”和“暂缓”才出现原因输入框；暂缓另有再联系日期
  h.page.statusPick({ currentTarget: { dataset: { code: 'OTHER' } } });
  assert.ok(flatten(render(template, h.page.data)).some(node => node.tag === 'textarea' && node.attrs.bindinput === 'statusInput'));
  h.page.closeStatus(); h.page.openStatus({ currentTarget: { dataset: { kind: 'paused' } } });
  const paused = flatten(render(template, h.page.data));
  assert.ok(paused.some(node => node.tag === 'textarea' && node.attrs.bindinput === 'statusInput'));
  assert.ok(paused.some(node => node.tag === 'picker' && node.attrs.bindchange === 'statusDate'));
});

test('05测量步骤可登记设计合同：上传写入fields、可预览移除，不占通用附件列表', async () => {
  const h = harness({ step: 'MEASUREMENT', upload: async pending => ({ assetId: '800', title: pending.file.name, mimeType: 'application/pdf' }) });
  await h.open();
  h.page.chooseSlotFile({ currentTarget: { dataset: { slot: 'designFile' } } });
  assert.equal(h.filePicker, undefined, '设计文件槽位不属于测量步骤');
  h.page.chooseSlotFile({ currentTarget: { dataset: { slot: 'designContract' } } });
  assert.ok(h.filePicker, '选择器已打开');
  h.filePicker.success({ tempFiles: [{ name: '设计服务合同.pdf', path: '/local/contract.pdf', size: 1024 }] });
  for (let i = 0; i < 10 && h.page.data.form.fields.designContractAssetId !== '800'; i++) await tick();
  assert.equal(h.page.data.form.fields.designContractAssetId, '800');
  assert.equal(h.page.data.form.fields.designContractName, '设计服务合同.pdf');
  assert.equal(h.page.data.form.attachments.length, 0, '槽位文件不进通用附件列表');
  await h.page.submit({ currentTarget: { dataset: { action: 'save' } } });
  const body = h.callsFor('save')[0].args[2];
  assert.equal(body.fields.designContractAssetId, '800');
  assert.equal(body.fields.designContractName, '设计服务合同.pdf');
  assert.equal(body.attachments, undefined || body.attachments.length === 0 ? body.attachments : body.attachments, '通用附件保持为空');
  assert.deepEqual(body.attachments, []);
  const pickerBefore = h.filePicker;
  h.page.chooseSlotFile({ currentTarget: { dataset: { slot: 'designContract' } } });
  assert.equal(h.filePicker, pickerBefore, '已登记后不再打开选择器');
  delete h.page._localFiles['800'];
  await h.page.previewFile({ currentTarget: { dataset: { id: '800' } } });
  assert.deepEqual(h.callsFor('download').at(-1).args, ['42', 'MEASUREMENT', '800'], '槽位文件走本步下载票据');
  h.page.removeSlotFile({ currentTarget: { dataset: { slot: 'designContract' } } });
  assert.equal(h.page.data.form.fields.designContractAssetId, '');
  await h.page.submit({ currentTarget: { dataset: { action: 'save' } } });
  assert.equal(h.callsFor('save')[1].args[2].fields.designContractAssetId, '', '移除后显式提交空值清除');
});

test('06报价设计文件独立于报价原单：换原单与改外部报价都保留，历史查看可回看', async () => {
  const h = harness({ step: 'QUOTATION', upload: async pending => ({ assetId: '800', title: pending.file.name, mimeType: 'application/pdf' }) });
  h.overrides.listQuotes = async () => [{ id: '9001', customerId: '42', companyId: '9', totalCents: 128000, budgetVersion: 2, createTime: '2026-11-20T10:00:00Z' }];
  await h.open(); await tick();
  await h.page.startUpload({ path: '/local/design.pdf', name: '别墅设计方案V1.pdf' }, model.SLOTS.designFile);
  assert.equal(h.page.data.form.fields.designFileAssetId, '800');
  h.page.chooseQuote({ detail: { value: 0 } });
  assert.equal(h.page.data.form.fields.quoteId, '9001');
  assert.equal(h.page.data.form.fields.designFileAssetId, '800', '关联正式报价不清设计文件');
  h.page.removeQuote();
  assert.equal(h.page.data.form.fields.designFileAssetId, '800', '改用外部报价保留设计文件');
  assert.equal(h.page.data.form.attachments.length, 0);
  await h.page.startUpload({ path: '/local/design2.pdf', name: '别墅设计方案V2.pdf' }, model.SLOTS.designFile);
  assert.equal(h.page.data.form.fields.communicated, false, '更换设计文件不重置报价沟通');
  assert.equal(h.page.data.form.fields.designFileName, '别墅设计方案V2.pdf');
  await h.page.submit({ currentTarget: { dataset: { action: 'save' } } });
  const body = h.callsFor('save')[0].args[2];
  assert.equal(body.fields.designFileAssetId, '800');
  assert.equal(body.fields.designFileName, '别墅设计方案V2.pdf');
  h.page.setData({ records: model.records([{ id: '91', stepCode: 'QUOTATION', type: 'SAVE', fields: { quoteId: '9001', designFileAssetId: '801', designFileName: '历史设计文件.pdf' }, attachments: [] }]) });
  h.page.viewRecord({ currentTarget: { dataset: { id: '91' } } });
  assert.ok(h.page.data.activeRecord.details.some(row => row.label === '设计文件' && row.value === '历史设计文件.pdf'));
  assert.ok(h.page.data.activeRecord.attachments.some(file => file.assetId === '801' && file.title === '历史设计文件.pdf'));
});

test('专用资料拍照后按上传识别的类型预览，无需先保存或重新下载', async () => {
  const h = harness({ step: 'QUOTATION', upload: async pending => pending.file.name
    ? { assetId: '801', title: pending.file.name, mimeType: 'application/pdf' }
    : { assetId: '800', title: '现场照片', mimeType: 'image/jpeg' } });
  await h.open();
  h.page.chooseSlotPhoto({ currentTarget: { dataset: { slot: 'designFile' } } });
  h.mediaPicker.success({ tempFiles: [{ tempFilePath: '/local/camera', size: 1024 }] });
  await tick();
  await h.page.previewFile({ currentTarget: { dataset: { id: '800' } } });
  assert.deepEqual(copy(h.preview), { urls: ['/local/camera'] });
  assert.equal(h.callsFor('download').length, 0);

  h.page.removeSlotFile({ currentTarget: { dataset: { slot: 'designFile' } } });
  await h.page.startUpload({ path: '/local/plan.pdf', name: '方案.pdf' }, model.SLOTS.designFile);
  await h.page.previewFile({ currentTarget: { dataset: { id: '801' } } });
  assert.equal(h.preview.filePath, '/local/plan.pdf');
  assert.equal(h.preview.showMenu, true);
  assert.equal(h.callsFor('download').length, 0);
});

test('八步页模板只为测量与报价步骤渲染各自的专用资料槽位', async () => {
  const { render } = require('./role-page-runtime.cjs');
  const template = path.join(root, 'miniprogram/pages/customer-flow/index.wxml'), flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children || [])]);
  const h = harness({ step: 'MEASUREMENT' }); await h.open();
  let nodes = flatten(render(template, h.page.data));
  assert.ok(nodes.some(node => node.attrs && node.attrs.bindtap === 'chooseSlotFile' && node.attrs['data-slot'] === 'designContract'));
  assert.equal(nodes.some(node => node.attrs && node.attrs['data-slot'] === 'designFile'), false, '测量步骤不渲染设计文件槽位');
  h.page.select('QUOTATION');
  nodes = flatten(render(template, h.page.data));
  assert.ok(nodes.some(node => node.attrs && node.attrs.bindtap === 'chooseSlotFile' && node.attrs['data-slot'] === 'designFile'));
  h.page.setData({ form: { ...h.page.data.form, fields: { ...h.page.data.form.fields, designFileAssetId: '801', designFileName: '设计文件.pdf' } } });
  nodes = flatten(render(template, h.page.data));
  assert.ok(nodes.some(node => node.attrs && node.attrs.bindtap === 'removeSlotFile' && node.attrs['data-slot'] === 'designFile'), '已登记后出现移除按钮');
});
