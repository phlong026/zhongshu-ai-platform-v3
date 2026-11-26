const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { connect, render } = require('./role-page-runtime.cjs');
const auth = require('../miniprogram/api/auth');
const http = require('../miniprogram/api/http');
const O = require('../miniprogram/utils/organization');
const api = require('../miniprogram/pages/headquarters/api');
const Files = require('../miniprogram/pages/headquarters/files');
const S = require('../miniprogram/pages/headquarters/session');
const ROOT = path.resolve(__dirname, '../miniprogram');
const HOME = '/pages/headquarters/index', DETAIL = '/pages/headquarters/detail', PREFIX = '/platform/headquarters';
const LONG = '9007199254740999123';
const tick = () => new Promise(resolve => setImmediate(resolve));
async function flush() { for (let i = 0; i < 9; i++) await tick(); }
const allText = nodes => nodes.map(node => (node.text || '') + allText(node.children || [])).join('');
const pageResult = (list, total = list.length, pageNo = 1) => ({ list, total, pageNo, pageSize: 20, readOnly: true });
const row = (id = LONG, extra = {}) => ({ id, name: '甲客户', companyId: '31', companyName: '甲公司', brandId: '1', brandName: '合家美宅', brandState: 'RESOLVED',
  phone: '13800000001', ownerName: '业务甲', status: '跟进中', amountCents: 12345, workflow: { currentStepName: '户型选择', progressPercent: 25 }, ...extra });
const context = post => ({ tenantId: '1', headquartersOrgId: '7', postId: '42', roleCode: post.roleCode, readOnly: true,
  brands: [{ id: '1', name: '合家美宅' }, { id: '2', name: '金宅子' }],
  companies: [{ id: '31', name: '甲公司', orgType: 'store', brandId: '1', brandName: '合家美宅', brandState: 'RESOLVED' },
    { id: '32', name: '乙公司', orgType: 'store', brandId: '2', brandName: '金宅子', brandState: 'RESOLVED' },
    { id: '33', name: '待核公司', orgType: 'store', brandId: null, brandState: 'AMBIGUOUS' }] });
const customer = () => row(LONG, { readOnly: true, workflow: { customerId: LONG, companyId: '31', canWrite: false, completedCount: 2, progressPercent: 25,
  steps: api.STEPS.map((code, index) => ({ code, name: ['邀约进店', '店面讲解', '户型选择', '带看工地', '现场测量', '别墅报价', '合同签约', '开工大吉'][index],
    order: index + 1, status: index < 2 ? 'COMPLETED' : 'DRAFT', canWrite: false, canComplete: false, canReopen: false, source: index === 6 ? 'CONTRACT' : index === 7 ? 'ENGINEERING' : 'MANUAL',
    fields: code === 'HOUSE_SELECTION' ? { referenceCaseName: '新中式合院', confirmed: true } : code === 'QUOTATION' ? { amountCents: '100001', scope: '主体与外装' }
      : code === 'CONTRACT' ? { contractId: '55', assetId: '66', totalCents: '300000', signedDate: '2026-11-25' } : code === 'START' ? { projectId: '77', actualStartAt: '2026-11-25' } : {},
    attachments: code === 'HOUSE_SELECTION' ? [{ assetId: '65', title: '外部设计软件的户型图' }] : [] })) } });
const externalVersion = (version = 2) => ({ version, actorName: '业务甲', createdAt: '2026-11-25T10:00:00',
  data: { providerName: '外部设计方', entrustedOn: '2026-11-24', text: '两层正式方案', drawingVersion: '图纸B版', deliveredOn: '2026-11-25' },
  attachments: [{ assetId: LONG, title: '外部正式图纸' }],
  paymentPlan: { source: 'DRAFT', revision: 3, totalCents: 100000, stages: [{ label: '约定备料款', amountCents: 100000, ratioBps: 10000, trigger: 'BEFORE_START' }] }
});
const project = () => row('77', { name: '甲公司乡墅工程', readOnly: true, contractTotalCents: 300000, contractAssetId: '66',
  nodes: [{ id: '70', name: '基础验收', status: '已完成', startDate: '2026-11-01', completedAt: '2026-11-08' }],
  saleContract: { id: '55', totalCents: 300000, signedDate: '2026-10-20', status: 'SIGNED' },
  paymentStages: Array.from({ length: 5 }, (_, i) => ({ stageId: String(80 + i), label: '实际付款 ' + (i + 1), sequence: i + 1, amountCents: 60000,
    collectedCents: 15000, refundedCents: 5000, balanceCents: 45000, trigger: i === 4 ? 'NODE_COMPLETED' : 'ON_SIGN', nodeIndex: 4, ratioBps: 2000 })),
  documents: pageResult([{ id: '91', name: '结构图', kind: 'DRAWING', version: 1, downloadAvailable: true }, { id: '92', name: '历史外链', downloadAvailable: false, fileRef: 'https://evil.invalid/drawing.pdf' }], 21),
  siteRecords: pageResult([{ id: '93', occurredAt: '2026-11-20', note: '现场已验收', completion: true, assetIds: ['94'] }], 21), closure: { recipient: '项目业主', ownerAssetId: '95' } });
function send(request, value, code = 0, statusCode = 200) { request.success({ statusCode, data: { code, data: value, msg: code ? '访问被拒绝' : '' } }); }

async function setup(role = 'hq-admin', orgType = 'headquarters') {
  const runtime = await connect({ lifecycles: true });
  const env = { runtime, requests: [], downloads: [], files: new Map(), removed: [], opened: [], shared: [], handler: () => undefined };
  env.post = { postId: '42', userId: '701', tenantId: '1', orgId: '7', roleCode: role, orgType, orgName: '众墅总部' };
  auth.saveLogin({ accessToken: 'hq-token', userId: '701' }); auth.savePosts([env.post]); auth.switchPost('42');
  wx.getFileSystemManager = () => ({
    statSync(file) { const bytes = env.files.get(file); if (!bytes) throw Error('missing file'); return { size: bytes.length }; },
    readFileSync(file, encoding, offset = 0, length) { const bytes = env.files.get(file); if (!bytes) throw Error('missing file'); const result = bytes.slice(offset, length == null ? undefined : offset + length); return encoding ? Buffer.from(result).toString(encoding) : result.buffer; },
    writeFileSync(file, data) { env.files.set(file, new Uint8Array(data).slice()); },
    unlinkSync(file) { env.removed.push(file); env.files.delete(file); }
  });
  wx.openDocument = options => { env.opened.push(options); options.success?.({}); };
  wx.previewImage = options => { env.opened.push(options); options.success?.({}); };
  wx.shareFileMessage = options => { env.shared.push(options); options.success?.({}); };
  wx.downloadFile = request => {
    let headersListener;
    const item = { request, headers: fn => { headersListener = fn; }, reply(response, mime = 'application/pdf') { headersListener?.({ header: { 'Content-Type': mime } }); request.success({ statusCode: 200, ...response }); } };
    env.downloads.push(item);
    if (env.downloadHandler) env.downloadHandler(item);
    else setImmediate(() => { env.files.set('/test/attachment.pdf', new Uint8Array(Buffer.from('%PDF-1.7 fixture'))); item.reply({ tempFilePath: '/test/attachment.pdf' }); });
    return { onHeadersReceived: item.headers, abort() { item.aborted = true; } };
  };
  wx.request = request => {
    const url = new URL(request.url), route = url.pathname.replace('/admin-api', '');
    const item = { route, request, query: Object.fromEntries(url.searchParams) }; env.requests.push(item);
    const result = env.handler(route, request, item.query);
    if (result?.pending) return { abort() { item.aborted = true; } };
    if (result?.raw) { setImmediate(() => request.success(result.raw)); return {}; }
    const defaults = route === PREFIX + '/context' ? context(env.post) : route === PREFIX + '/summary' ? { customers: 3, projects: 2, readOnly: true }
      : route === PREFIX + '/customers/' + LONG ? customer() : route === PREFIX + '/projects/77' ? project()
      : /\/workflow-records$/.test(route) ? { list: [], total: 0 } : /\/books$/.test(route) ? []
      : /\/ledger$/.test(route) ? { summary: {}, entries: [], truncated: false }
      : route === '/platform/posts/mine' ? [env.post] : route === '/platform/profile/mine' ? { id: '701', nickname: '总部用户' }
      : route.startsWith(PREFIX) ? pageResult([]) : [];
    setImmediate(() => send(request, result === undefined ? defaults : result)); return {};
  };
  env.open = async (url, method = 'navigateTo') => { await runtime.callWxMethod(method, { url }); await flush(); return getCurrentPages().at(-1); };
  env.text = page => allText(render(path.join(ROOT, page.route + '.wxml'), page.data));
  env.tap = async selector => { const button = await (await runtime.currentPage()).$(selector); assert.ok(button, selector); await button.tap(); await flush(); };
  env.reads = () => env.requests.filter(item => item.route.startsWith(PREFIX));
  return env;
}

test('总部三种实际岗位进入只读工作台，门店财务和旧设计岗位不扩权', async () => {
  for (const role of ['hq-admin', 'finance', 'finance-lead']) {
    const env = await setup(role), nav = O.navigation(O.apiContext(env.post));
    assert.equal(nav.tabs[0].url, HOME + '?section=' + (role === 'hq-admin' ? 'overview' : 'finance'));
    const page = await env.open(HOME, 'reLaunch');
    assert.equal(page.data.ready, true); assert.equal(page.data.section, role === 'hq-admin' ? 'overview' : 'finance');
    assert.equal(page.data.roleName, O.roles[role]);
    assert.ok(env.text(page).includes('全品牌 · 只读'));
    assert.equal(env.reads()[0].request.header['X-Post-Id'], '42');
    assert.equal(env.reads()[0].request.header['tenant-id'], '1');
  }
  for (const [role, type] of [['finance', 'store'], ['sales', 'store'], ['hq-design-admin', 'headquarters'], ['boss', 'headquarters']]) {
    const env = await setup(role, type), page = await env.open(HOME);
    assert.equal(page.data.ready, false); assert.equal(env.reads().length, 0);
    assert.equal(O.navigation(O.apiContext(env.post)).tabs.some(tab => tab.url.startsWith(HOME)), false);
  }
});

test('context 必须同时匹配当前 tenant、组织、岗位和只读标志', async () => {
  for (const patch of [{ tenantId: '2' }, { headquartersOrgId: '9' }, { postId: '99' }, { roleCode: 'sales' }, { readOnly: false }]) {
    const env = await setup(); env.handler = route => route.endsWith('/context') ? { ...context(env.post), ...patch } : undefined;
    const page = await env.open(HOME);
    assert.equal(page.data.ready, false); assert.deepEqual(page.data.companies, []); assert.equal(env.reads().length, 1);
  }
});

test('品牌和公司筛选跟随真实目录，品牌冲突公司不进入某一品牌结果', async () => {
  const env = await setup(); env.handler = (route, request) => route === PREFIX + '/customers' ? pageResult([row()]) : undefined;
  const page = await env.open(HOME + '?section=customers');
  assert.deepEqual(page.data.companies.map(row => row.id), ['31', '32', '33']);
  await page.selectBrand({ detail: { value: 1 } }); await flush();
  assert.deepEqual(page.data.companies.map(row => row.id), ['31']);
  assert.equal(env.reads().at(-1).request.data.brandId, '1');
  await page.selectCompany({ detail: { value: 1 } }); await flush();
  assert.equal(env.reads().at(-1).request.data.companyId, '31');
  await page.selectBrand({ detail: { value: 2 } }); await flush();
  assert.equal(page.data.companyIndex, 0); assert.equal(env.reads().at(-1).request.data.companyId, undefined);
  assert.equal(env.reads().at(-1).request.data.brandId, '2');
});

test('分页失败保留已读结果，重试仍请求原来的下一页', async () => {
  const env = await setup(); let pending;
  env.handler = (route, request) => {
    if (route !== PREFIX + '/customers') return;
    if (request.data.pageNo === 2) { pending = request; return { pending: true }; }
    return pageResult(Array.from({ length: 20 }, (_, i) => row(String(i + 1))), 21);
  };
  const page = await env.open(HOME + '?section=customers');
  const more = page.more(); await flush(); send(pending, null, 500); await more;
  assert.equal(page.data.rows.length, 20); assert.equal(page.data.pageNo, 1); assert.ok(page.data.error);
  const retry = page.retry(); await flush(); assert.equal(pending.data.pageNo, 2);
  send(pending, pageResult([row('21')], 21, 2)); await retry;
  assert.equal(page.data.rows.length, 21); assert.equal(page.data.hasMore, false);
});

test('品牌切换、离页与岗位改变都拒绝旧的在途响应', async () => {
  const env = await setup(); const pending = [];
  env.handler = (route, request) => route === PREFIX + '/customers' ? (pending.push(request), { pending: true }) : undefined;
  const page = await env.open(HOME + '?section=customers');
  page.selectBrand({ detail: { value: 2 } }); await flush();
  send(pending[1], pageResult([row('2', { name: '新品牌客户' })])); await flush();
  send(pending[0], pageResult([row('1', { name: '过期客户秘密' })])); await flush();
  assert.equal(page.data.rows[0].name, '新品牌客户');
  page.loadContent(); await flush(); page.onHide();
  send(pending[2], pageResult([row('3', { name: '已离页秘密' })])); await flush();
  assert.deepEqual(page.data.rows, []); assert.equal(page.data.ready, false);
  page.onShow(); await flush();
  auth.savePosts([{ ...env.post, roleCode: 'sales', orgType: 'store' }]);
  send(pending[3], pageResult([row('4', { name: '旧总部数据' })])); await flush();
  assert.equal(page.data.ready, false); assert.deepEqual(page.data.rows, []);
});

test('客户八步展示实际字段与外部成果，合同和开工按真实来源只读', async () => {
  const env = await setup(), page = await env.open(DETAIL + '?kind=customers&id=' + LONG);
  assert.equal(page.data.detail.steps.length, 8);
  for (const code of ['HOUSE_SELECTION', 'QUOTATION', 'CONTRACT', 'START']) {
    await page.expandStep({ currentTarget: { dataset: { code } } });
    const text = env.text(page);
    assert.equal(/保存步骤|完成步骤|重新打开步骤|发起设计|确认收款/.test(text), false);
    if (code === 'HOUSE_SELECTION') assert.ok(text.includes('新中式合院') && text.includes('外部设计软件的户型图'));
    if (code === 'QUOTATION') assert.ok(text.includes('1,000.01'));
    if (code === 'CONTRACT') assert.ok(text.includes('已签合同附件') && text.includes('以已签合同为准'));
  }
  assert.ok(env.reads().every(item => item.request.method === 'GET'));
  const action = Array.from(page._fileActions.values()).find(item => item.step === 'HOUSE_SELECTION');
  assert.equal(api.attachmentPath(action), PREFIX + '/customers/' + LONG + '/workflow/HOUSE_SELECTION/assets/65/content');
});

test('总部户型选择展示本次及历史版本各自确认的定制方向', async () => {
  const env = await setup();
  const detail = customer();
  const selection = detail.workflow.steps.find(step => step.code === 'HOUSE_SELECTION');
  selection.fields = { selectionMode: 'EXTERNAL_CUSTOM', customDirection: 'T 形布局，两户共用院子', confirmed: true };
  selection.attachments = [];
  env.handler = route => {
    if (route === PREFIX + '/customers/' + LONG) return detail;
    if (route.endsWith('/workflow-records')) return pageResult([{ id: '100', stepCode: 'HOUSE_SELECTION', type: 'COMPLETE',
      fields: { selectionMode: 'EXTERNAL_CUSTOM', customDirection: '原版沿街留院', confirmed: true }, attachments: [] }]);
  };
  const page = await env.open(DETAIL + '?kind=customers&id=' + LONG);
  await page.expandStep({ currentTarget: { dataset: { code: 'HOUSE_SELECTION' } } });
  assert.ok(env.text(page).includes('外部定制'));
  assert.ok(env.text(page).includes('T 形布局，两户共用院子'));
  await page.toggleHistory(); await flush();
  assert.ok(env.text(page).includes('原版沿街留院'));
  assert.ok(env.reads().every(call => call.request.method === 'GET'));
});

test('总部区分本客户当前测量、历史原单和委托冻结版本的朝向与高差', async () => {
  const env = await setup(), detail = customer();
  detail.workflow.steps.find(step => step.code === 'MEASUREMENT').fields =
    {orientation:'当前正南',siteElevationNote:'当前 T 形道路比地块高 0.32 米'};
  const record = externalVersion(2);
  record.data.measurementRef={customerId:LONG,eventId:'9113372036854775805',revision:9,
    fields:{orientation:'委托南偏东',siteElevationNote:'委托 T 形道路高 0.28 米'},note:'留档原文：门前有台阶。\n具体测点见图。'};
  env.handler = route => {
    if(route === PREFIX+'/customers/'+LONG) return detail;
    if(route.endsWith('/workflow-records')) return pageResult([{id:'100',stepCode:'MEASUREMENT',type:'SAVE',
      fields:{orientation:'历史正东',siteElevationNote:'旧版现场比路面高 0.40 米'},attachments:[]}]);
    if(route.endsWith('/external-entrust/requests')) return pageResult([record]);
  };
  const page=await env.open(DETAIL+'?kind=customers&id='+LONG);
  await page.expandStep({currentTarget:{dataset:{code:'MEASUREMENT'}}});
  assert.ok(env.text(page).includes('当前 T 形道路比地块高 0.32 米'));
  await page.toggleHistory(); await flush();
  assert.ok(env.text(page).includes('历史正东') && env.text(page).includes('旧版现场比路面高 0.40 米'));
  await page.toggleExternal();
  assert.ok(env.text(page).includes('委托南偏东') && env.text(page).includes('委托 T 形道路高 0.28 米'));
  assert.ok(env.text(page).includes('原测量说明') && env.text(page).includes(record.data.measurementRef.note));
  assert.ok(env.text(page).includes('9113372036854775805 · 流程版本 9'));
  assert.ok(env.reads().every(call=>call.request.method==='GET'));
});

test('总部外部委托需求与成果只读分页，保存的付款版本和附件均对应真实版本', async () => {
  const env = await setup();
  env.handler = (route, request) => /\/external-entrust\/(requests|results)$/.test(route)
    ? pageResult([externalVersion(request.data.pageNo === 1 ? 2 : 1)], 21, request.data.pageNo) : undefined;
  const page = await env.open(DETAIL + '?kind=customers&id=' + LONG);
  assert.equal(env.reads().some(r => r.route.includes('/external-entrust/')), false);
  await page.toggleExternal(); assert.equal(page.data.externalRows[0].version, 2); assert.equal(page.data.externalMore, true);
  assert.ok(env.text(page).includes('两层正式方案') && env.text(page).includes('约定备料款'));
  assert.equal(/登记成果|提交移交|保存委托/.test(env.text(page)), false);
  await page.moreExternal(); assert.equal(page.data.externalRows.length, 2); assert.equal(page.data.externalMore, false);
  const action = Array.from(page._fileActions.values()).find(a => a.type === 'external');
  assert.equal(api.attachmentPath(action), PREFIX + '/customers/' + LONG + '/external-entrust/requests/2/assets/' + LONG + '/content');
  await page.selectExternal({ currentTarget: { dataset: { kind: 'results' } } });
  assert.equal(page.data.externalPage, 1); assert.equal(page.data.externalRows[0].title, '外部成果 V2');
  assert.ok(env.text(page).includes('图纸B版')); assert.ok(env.reads().every(r => r.request.method === 'GET'));
  for (const patch of [{ kind: 'other' }, { version: '../1' }, { version: 0 }, { assetId: Number(LONG) }])
    assert.throws(() => api.attachmentPath({ ...action, ...patch }));
});

test('总部委托分页失败重试原页，切换类别与刷新会废弃旧响应', async () => {
  const env = await setup(); let pending;
  env.handler = (route, request) => {
    if (!/\/external-entrust\/(requests|results)$/.test(route)) return;
    if (request.data.pageNo === 2) { pending = request; return { pending: true }; }
    return pageResult([externalVersion(2)], 21);
  };
  const page = await env.open(DETAIL + '?kind=customers&id=' + LONG); await page.toggleExternal();
  const loading = page.moreExternal(); await flush(); send(pending, null, 500); await loading;
  assert.equal(page.data.externalPage, 1); assert.equal(page.data.externalRows.length, 1);
  const retry = page.moreExternal(); await flush(); assert.equal(pending.data.pageNo, 2);
  await page.selectExternal({ currentTarget: { dataset: { kind: 'results' } } });
  send(pending, pageResult([externalVersion(1)], 21, 2)); await retry;
  assert.equal(page.data.externalRows.length, 1); assert.equal(page.data.externalRows[0].title, '外部成果 V2');
  const stale = page.moreExternal(); await flush(); await page.reload();
  send(pending, pageResult([externalVersion(1)], 21, 2)); await stale;
  assert.equal(page.data.externalOpen, false); assert.deepEqual(page.data.externalRows, []);
});

test('正式合同快照显示付款计划版本而非合同修订号，历史附件按 title 和所属版本查看', async () => {
  const env = await setup(), record = externalVersion(7);
  record.paymentPlan = { source: 'CONTRACT', revision: 12, contractId: '55', contractPlanVersion: 4, totalCents: 100000,
    stages: [{ label: '原基础款', amountCents: 100000, ratioBps: 10000, trigger: 'LEGACY_BASE_DONE' }] };
  env.handler = route => /\/external-entrust\/requests$/.test(route) ? pageResult([record]) : undefined;
  const page = await env.open(DETAIL + '?kind=customers&id=' + LONG); await page.toggleExternal();
  assert.equal(page.data.externalRows[0].payment.version, '4');
  assert.ok(env.text(page).includes('合同约定 V4') && !env.text(page).includes('合同约定 V12'));
  assert.ok(env.text(page).includes('基础完工（原约定）') && env.text(page).includes('外部正式图纸'));
  await page.openFile({ currentTarget: { dataset: { key: page.data.externalRows[0].files[0].key } } });
  assert.ok(env.downloads[0].request.url.endsWith('/customers/' + LONG + '/external-entrust/requests/7/assets/' + LONG + '/content'));
  assert.equal(env.downloads[0].request.header['X-Post-Id'], '42'); assert.equal(env.opened.length, 1);
  assert.equal(env.reads().some(item => item.request.method !== 'GET'), false);
});

test('总部委托读权丢失清空客户资料，离页后成果响应不能恢复敏感内容', async () => {
  for (const mode of ['missing', 'hide']) {
    const env = await setup(); let pending;
    env.handler = (route, request) => /\/external-entrust\//.test(route) ? (pending = request, { pending: true }) : undefined;
    const page = await env.open(DETAIL + '?kind=customers&id=' + LONG), loading = page.toggleExternal(); await flush();
    if (mode === 'hide') page.onHide();
    send(pending, mode === 'missing' ? null : pageResult([externalVersion()]), mode === 'missing' ? 404 : 0); await loading;
    assert.equal(page.data.ready, false); assert.equal(page.data.detail, null); assert.deepEqual(page.data.externalRows, []);
  }
});

test('总部工资入口仅携带已核验公司，切岗或未选择公司时不能进入', async () => {
  const navigation = require('../miniprogram/utils/page-navigation'), original = navigation.open, opened = [];
  try {
    navigation.open = url => opened.push(url);
    const env = await setup('finance'), page = await env.open(HOME + '?section=finance');
    page.openPayroll(); assert.deepEqual(opened, []);
    await page.selectCompany({ detail: { value: 2 } }); page.openPayroll();
    assert.deepEqual(opened, ['/pages/admin/payroll/index?headquarters=1&companyId=32']);
    assert.ok(env.text(page).includes('底薪与提成核算'));
    page._filters.companyId = '999'; page.openPayroll(); assert.equal(opened.length, 1);
    auth.savePosts([{ ...env.post, orgId: '31', orgType: 'store' }]); page.openPayroll(); assert.equal(opened.length, 1);
  } finally { navigation.open = original; }
});

test('工资入口只允许门店公司，总部公司继续可作其他业务筛选', async () => {
  const navigation = require('../miniprogram/utils/page-navigation'), original = navigation.open, opened = [];
  try {
    navigation.open = url => opened.push(url);
    const env = await setup('finance');
    env.handler = route => route.endsWith('/context') ? { ...context(env.post), companies: [
      ...context(env.post).companies, { id: '7', name: '众墅总部', orgType: 'headquarters', brandState: 'UNASSIGNED' }
    ] } : undefined;
    const page = await env.open(HOME + '?section=finance');
    await page.selectCompany({ detail: { value: 4 } });
    assert.equal(page.data.companyName, '众墅总部'); assert.equal(page.data.canOpenPayroll, false);
    assert.ok(env.text(page).includes('在上方选择门店公司后查看工资记录'));
    page.openPayroll(); assert.deepEqual(opened, []);
    assert.equal(env.reads().at(-1).request.data.companyId, '7');
    await page.selectCompany({ detail: { value: 1 } }); page.openPayroll();
    assert.equal(page.data.canOpenPayroll, true);
    assert.deepEqual(opened, ['/pages/admin/payroll/index?headquarters=1&companyId=31']);
  } finally { navigation.open = original; }
});

test('历史记录独立分页，旧附件仍通过所属步骤读取', async () => {
  const env = await setup();
  env.handler = (route, request) => /workflow-records$/.test(route) ? { total: 21, list: [{ id: String(100 + request.data.pageNo), stepCode: 'HOUSE_SELECTION',
    type: request.data.pageNo === 1 ? 'SAVE' : 'STATUS', actorName: '业务甲', note: '原始方案说明', fields: {}, attachments: [{ assetId: LONG, title: '历史图纸' }] }] } : undefined;
  const page = await env.open(DETAIL + '?kind=customers&id=' + LONG);
  await page.toggleHistory(); await flush(); assert.equal(page.data.historyMore, true);
  await page.loadHistory(); assert.equal(page.data.historyPage, 2); assert.equal(page.data.historyMore, false);
  assert.equal(page.data.history.length, 2); assert.ok(env.text(page).includes('原始方案说明'));
  assert.equal(page.data.history[0].type, '保存记录'); assert.equal(page.data.history[1].type, '更新跟进状态');
  const action = Array.from(page._fileActions.values()).find(item => item.assetId === LONG);
  assert.equal(api.attachmentPath(action), PREFIX + '/customers/' + LONG + '/workflow/HOUSE_SELECTION/assets/' + LONG + '/content');
});

test('工程显示全部真实付款期次与净收款，资料分页且不暴露历史外链', async () => {
  const env = await setup();
  env.handler = route => route === PREFIX + '/projects/77/documents' ? pageResult([{ id: '96', name: '第二页施工图', downloadAvailable: true }], 21, 2)
    : route === PREFIX + '/projects/77/site-records' ? pageResult([{ id: '97', occurredAt: '2026-11-24', completion: false, assetIds: ['98'] }], 21, 2) : undefined;
  const page = await env.open(DETAIL + '?kind=projects&id=77');
  assert.equal(page.data.detail.stages.length, 5); assert.equal(page.data.detail.stages[4].trigger, '工程节点 4 完成');
  assert.ok(env.text(page).includes('实际付款 5') && env.text(page).includes('150.00'));
  assert.equal(JSON.stringify(page.data).includes('evil.invalid'), false);
  await page.moreProjectItems({ currentTarget: { dataset: { kind: 'documents' } } });
  await page.moreProjectItems({ currentTarget: { dataset: { kind: 'site-records' } } });
  assert.equal(page.data.documents.length, 3); assert.equal(page.data.siteRecords.length, 2); assert.equal(page.data.documentsMore, false);
  assert.ok(env.text(page).includes('节点验收记录') && env.text(page).includes('现场进度记录'));
  assert.equal(/true%|false%/.test(env.text(page)), false);
  const file = Array.from(page._fileActions.values()).find(action => action.documentId === '96');
  assert.equal(api.attachmentPath(file), PREFIX + '/projects/77/documents/96/content');
});

test('公司账本选择不套用总部公司，项目账本明确提示 500 条明细边界', async () => {
  const env = await setup();
  env.handler = route => route === PREFIX + '/companies/31/books' ? [{ scope: 'project:77', name: '甲公司工程', status: '进行中' }]
    : route === PREFIX + '/companies/31/ledger' ? { summary: { received_cents: 12345, paid_cents: 1000, refunded_cents: 100, expense_refunded_cents: 50, committed_cents: 3000, outstanding_cents: 2000, receivable_cents: 456 },
      entries: [{ id: '99', type: 'RECEIPT', name: '签约款', amount_cents: 12345, date: '2026-11-25', status: '已登记' }], truncated: true } : undefined;
  let page = await env.open(HOME + '?section=books');
  assert.equal(env.reads().some(item => item.route.endsWith('/books')), false);
  await page.chooseBookCompany({ currentTarget: { dataset: { index: 0 } } }); await page.openBook({ currentTarget: { dataset: { index: 0 } } }); await flush();
  page = getCurrentPages().at(-1); assert.equal(page.data.companyName, '甲公司');
  assert.ok(env.text(page).includes('最近 500 条明细')); assert.equal(env.text(page).includes('最终盈亏'), false);
  assert.equal(env.reads().at(-1).request.data.scope, 'project:77');
  const foreign = await env.open(DETAIL + '?kind=ledger&companyId=999&scope=company');
  assert.equal(foreign.data.ready, false); assert.equal(env.reads().some(item => item.route.includes('/companies/999/ledger')), false);
});

test('总部账本接受原生onLoad的编码scope，只解码一次且拒绝无效范围', async () => {
  const env = await setup(), page = await env.open(DETAIL + '?kind=ledger&companyId=31&scope=company');
  for (const [raw, expected] of [['project%3A' + LONG, 'project:' + LONG], ['book%3A88', 'book:88'], ['project:77', 'project:77'], ['company', 'company']]) {
    page.onHide(); env.requests.length = 0;
    // Pass native route options directly: URLSearchParams in the shared runtime decodes these prematurely.
    page.onLoad({ kind: 'ledger', companyId: '31', scope: raw }); await page.onShow(); await flush();
    assert.equal(page.data.ready, true, raw);
    assert.equal(env.reads().find(item => item.route.endsWith('/ledger')).request.data.scope, expected);
  }
  for (const raw of ['project%253A77', 'project%3A0', 'project%3Anot-id', '%E0%A4%A']) {
    page.onHide(); env.requests.length = 0;
    page.onLoad({ kind: 'ledger', companyId: '31', scope: raw }); await page.onShow(); await flush();
    assert.equal(page.data.ready, false, raw); assert.match(page.data.error, /账本范围无效/);
    assert.equal(env.reads().some(item => item.route.endsWith('/ledger')), false);
  }
});

test('财务原单与凭证保持准确的字符串 ID 和类别', async () => {
  const env = await setup();
  env.handler = route => route === PREFIX + '/finance/payments/' + LONG ? row(LONG, { readOnly: true, assetId: '88', payableId: '99', status: '已核实' }) : undefined;
  const page = await env.open(DETAIL + '?kind=payments&id=' + LONG);
  assert.equal(page.data.detail.id, LONG); assert.ok(env.text(page).includes('123.45'));
  const action = Array.from(page._fileActions.values())[0];
  assert.equal(api.attachmentPath(action), PREFIX + '/finance/payments/' + LONG + '/assets/88/content');
  assert.throws(() => api.id(Number(LONG))); assert.throws(() => api.attachmentPath({ ...action, kind: 'payables' }));
  assert.throws(() => api.attachmentPath({ ...action, assetId: 'https://evil.invalid' }));
});

test('附件只向受控路径携带当前岗位头，验收 PDF 后才打开', async () => {
  const env = await setup(), page = await env.open(DETAIL + '?kind=customers&id=' + LONG);
  const key = Array.from(page._fileActions.keys())[0];
  await page.openFile({ currentTarget: { dataset: { key } } });
  assert.equal(env.downloads.length, 1); assert.ok(env.downloads[0].request.url.includes('/workflow/HOUSE_SELECTION/assets/65/content'));
  assert.equal(env.downloads[0].request.header.Authorization, 'Bearer hq-token');
  assert.equal(env.downloads[0].request.header['X-Post-Id'], '42'); assert.equal(env.opened.length, 1);
  assert.equal(env.opened[0].fileType, 'pdf'); assert.equal(env.opened[0].showMenu, false);
  page.onHide(); assert.equal(page.data.detail, null);
});

test('附件下载后撤岗、离页或返回 JSON 错误时不打开并清理临时文件', async () => {
  for (const mode of ['post', 'hide', 'json']) {
    const env = await setup(), page = await env.open(DETAIL + '?kind=customers&id=' + LONG);
    env.downloadHandler = () => {};
    const pending = page.openFile({ currentTarget: { dataset: { key: Array.from(page._fileActions.keys())[0] } } }); await flush();
    if (mode === 'post') auth.savePosts([{ ...env.post, orgId: '88' }]);
    if (mode === 'hide') page.onHide();
    env.files.set('/test/rejected.pdf', new Uint8Array(Buffer.from(mode === 'json' ? JSON.stringify({ code: 403, msg: '拒绝' }) : '%PDF-1.7')));
    env.downloads[0].reply({ tempFilePath: '/test/rejected.pdf' }, mode === 'json' ? 'application/json' : 'application/pdf'); await pending;
    assert.equal(env.opened.length, 0); assert.ok(env.removed.includes('/test/rejected.pdf'));
    if (mode === 'json') assert.equal(page.data.ready, false);
  }
});

test('CSV 导出使用已查询条件和审计 POST，只有用户点击才调用文件分享', async () => {
  const env = await setup();
  env.handler = route => route === PREFIX + '/exports' ? { raw: { statusCode: 200, header: { 'content-type': 'text/csv;charset=UTF-8' }, data: Uint8Array.from(Buffer.from('\ufeff公司,客户\r\n甲公司,张先生\r\n')).buffer } } : undefined;
  const page = await env.open(HOME + '?section=customers');
  page.input({ currentTarget: { dataset: { field: 'keyword' } }, detail: { value: '姓名 %_ & 测试' } });
  await page.applyFilters(); await page.exportRows();
  const request = env.reads().find(item => item.route.endsWith('/exports'));
  assert.equal(request.request.method, 'POST'); assert.equal(request.query.keyword, '姓名 %_ & 测试');
  assert.equal(request.query.dataset, 'customers'); assert.equal(request.request.responseType, 'arraybuffer');
  assert.equal(page.data.exportReady, true); assert.equal(env.shared.length, 0); assert.equal(env.files.size, 1);
  await page.shareExport(); assert.equal(env.shared.length, 1); assert.equal(env.shared[0].fileName, '总部客户记录.csv');
});

test('导出期间改筛选或换身份不能保存和分享过期字节', async () => {
  for (const mode of ['filter', 'post']) {
    const env = await setup(); let pending;
    env.handler = (route, request) => route.endsWith('/exports') ? (pending = request, { pending: true }) : undefined;
    const page = await env.open(HOME + '?section=customers'), exported = page.exportRows(); await flush();
    if (mode === 'filter') page.input({ currentTarget: { dataset: { field: 'keyword' } }, detail: { value: '新的查询' } });
    else auth.savePosts([{ ...env.post, tenantId: '2' }]);
    pending.success({ statusCode: 200, header: { 'content-type': 'text/csv' }, data: Uint8Array.from(Buffer.from('\ufeff秘密客户')).buffer }); await exported;
    assert.equal(env.files.size, 0); assert.equal(page.data.exportReady, false); assert.equal(env.shared.length, 0);
  }
});

test('导出错误和附件伪装不被当作成功文件', async () => {
  const env = await setup();
  env.handler = route => route.endsWith('/exports') ? { raw: { statusCode: 200, header: { 'content-type': 'application/json' }, data: Uint8Array.from(Buffer.from('{"code":403,"msg":"岗位已撤销"}')).buffer } } : undefined;
  let page = await env.open(HOME + '?section=customers'); await page.exportRows();
  assert.equal(page.data.ready, false); assert.equal(page.data.exportReady, false); assert.equal(env.files.size, 0);
  page = await env.open(DETAIL + '?kind=customers&id=' + LONG); env.downloadHandler = () => {};
  const pending = Files.download(page, Array.from(page._fileActions.values())[0], S.ticket(page)); await flush();
  env.files.set('/test/fake.pdf', new Uint8Array(Buffer.from('<html>not pdf</html>')));
  env.downloads[0].reply({ tempFilePath: '/test/fake.pdf' });
  await assert.rejects(pending, /附件格式校验/); assert.equal(env.opened.length, 0);
});

test('深链接的错误编号或已切换身份不会发起业务详情请求', async () => {
  const env = await setup();
  const invalid = await env.open(DETAIL + '?kind=customers&id=9223372036854775808');
  assert.equal(invalid.data.ready, false); assert.equal(env.reads().length, 0);
  const page = await env.open(DETAIL + '?kind=customers&id=' + LONG), count = env.reads().length;
  page.onHide(); auth.savePosts([{ ...env.post, postId: '43' }]); auth.switchPost('43'); await page.onShow();
  assert.equal(page.data.ready, false); assert.equal(env.reads().length, count); assert.equal(page.data.detail, null);
});

test('任一请求返回撤权后，同一旧页面的并发资料请求不能再填回数据', async () => {
  const env = await setup(), page = await env.open(DETAIL + '?kind=projects&id=77');
  let pending;
  env.handler = (route, request) => route.endsWith('/documents') ? (pending = request, { pending: true }) : undefined;
  const documents = page.moreProjectItems({ currentTarget: { dataset: { kind: 'documents' } } }); await flush();
  const ticket = S.ticket(page); S.fail(page, ticket, { code: 403 });
  send(pending, pageResult([{ id: '99', name: '撤权后的私有图纸', downloadAvailable: true }], 21, 2)); await documents;
  assert.equal(page.data.ready, false); assert.equal(page.data.detail, null); assert.deepEqual(page.data.documents, []);
});

test('导出后公司移出租户时，岗位未变化也不能分享旧 CSV 并删除文件', async () => {
  const env = await setup(); let revoked = false;
  env.handler = route => route.endsWith('/exports') ? { raw: { statusCode: 200, header: { 'content-type': 'text/csv' }, data: Uint8Array.from(Buffer.from('\ufeff公司,客户\r\n甲公司,客户')).buffer } }
    : route.endsWith('/context') && revoked ? { ...context(env.post), companies: context(env.post).companies.filter(row => row.id !== '31') } : undefined;
  const page = await env.open(HOME + '?section=customers'); await page.exportRows();
  assert.equal(page.data.exportReady, true); assert.equal(env.files.size, 1);
  revoked = true; await page.shareExport();
  assert.equal(env.shared.length, 0); assert.equal(env.files.size, 0); assert.equal(page.data.exportReady, false); assert.equal(page.data.ready, false);
});

test('原始导出响应返回前公司集合变化时不落盘，即使仍是同一总部岗位', async () => {
  const env = await setup(); let revoked = false;
  env.handler = route => {
    if (route.endsWith('/exports')) { revoked = true; return { raw: { statusCode: 200, header: { 'content-type': 'text/csv' }, data: Uint8Array.from(Buffer.from('\ufeff客户')).buffer } }; }
    if (route.endsWith('/context') && revoked) return { ...context(env.post), companies: context(env.post).companies.filter(row => row.id !== '31') };
  };
  const page = await env.open(HOME + '?section=customers'); await page.exportRows();
  assert.equal(env.files.size, 0); assert.equal(page.data.exportReady, false); assert.equal(page.data.ready, false);
});
