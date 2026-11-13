const assert = require('node:assert/strict');
const crypto = require('node:crypto');

const settle = () => new Promise(resolve => setTimeout(resolve, 20));

async function verifyReferenceUpload() {
  const attach = require('../miniprogram/pages/workflow/explore/platform-bridge');
  const scenarios = [
    { name: 'jpeg-cos', uploadUrl: 'https://cos.example/asset/501.jpg?signature=fake', bytes: Buffer.from([0xff, 0xd8, 0xff, 0xe0]), mime: 'image/jpeg' },
    { name: 'png-cos', uploadUrl: 'https://cos.example/asset/501.png?signature=fake', bytes: Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1]), mime: 'image/png' }
  ];
  for (const scenario of scenarios) {
    const calls = [];
    global.wx = {
      chooseMedia(options) { options.success({ tempFiles: [{ tempFilePath: '/tmp/ref.jpg' }] }); },
      getFileSystemManager() { return { readFile(options) { options.success({ data: scenario.bytes }); } }; },
      showToast() {}
    };
    const page = {
      data: { form: { referenceAssetIds: [] } }, __platGen: 0,
      __http: { sessionGeneration: () => 1 },
      setData(patch) { for (const [key, value] of Object.entries(patch)) {
        if (key === 'form.referenceAssetIds') this.data.form.referenceAssetIds = value;
        else this.data[key] = value;
      } },
      platStale: () => false,
      platFail(message) { throw new Error(message); }
    };
    attach(page, {
      PF: {}, S: {},
      http: { sessionGeneration: () => 1, putTicketBytes: async (url, bytes, mime) => {
        calls.push({ url, bytes: Buffer.from(bytes), mime });
      } },
      api: { assets: {
        uploadTicket: async request => { calls.push({ ticketMime: request.mimeType, sizeBytes: request.sizeBytes, sha256: request.sha256 }); return { assetId: 501, uploadUrl: scenario.uploadUrl }; },
        complete: async id => { calls.push('complete:' + id); return { uploadStatus: 'ACCEPTED' }; }
      } }
    });
    page.__post = { postId: 7 }; page.__postKey = '7';
    page.platAddReference();
    await settle();
    assert.deepEqual(page.data.form.referenceAssetIds, ['501'], scenario.name + ' 上传应保存资产，calls=' + calls.join(',') + ',busy=' + page.data.busy);
    assert.deepEqual(calls, [
      { ticketMime: scenario.mime, sizeBytes: scenario.bytes.length, sha256: crypto.createHash('sha256').update(scenario.bytes).digest('hex') },
      { url: scenario.uploadUrl, bytes: scenario.bytes, mime: scenario.mime },
      'complete:501'
    ]);
    delete global.wx;
  }

  let localCompleted = false;
  global.wx = {
    chooseMedia(options) { options.success({ tempFiles: [{ tempFilePath: '/tmp/ref.png' }] }); },
    getFileSystemManager() { return { readFile(options) { options.success({ data: Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]) }); } }; },
    showToast() {}
  };
  const localPage = { data: { form: { referenceAssetIds: [] } }, __platGen: 0, __http: { sessionGeneration: () => 1 },
    setData(patch) { Object.assign(this.data, patch); }, platStale: () => false };
  attach(localPage, { PF: {}, S: {}, http: { sessionGeneration: () => 1, putTicketBytes: async () => assert.fail('local:// 设计票据不得猜测转发') }, api: { assets: {
    uploadTicket: async () => ({ assetId: 503, uploadUrl: 'local://design/503' }),
    complete: async () => { localCompleted = true; }
  } } });
  localPage.__post = { postId: 7 }; localPage.__postKey = '7'; localPage.platAddReference();
  await settle();
  assert.equal(localCompleted, false);
  assert.match(localPage.data.platformErr, /本地设计存储不支持/);
  delete global.wx;

  let unknownTicketCalls = 0;
  global.wx = {
    chooseMedia(options) { options.success({ tempFiles: [{ tempFilePath: '/tmp/ref.bin' }] }); },
    getFileSystemManager() { return { readFile(options) { options.success({ data: Buffer.from('not-an-image') }); } }; }, showToast() {}
  };
  const unknownPage = { data: { form: { referenceAssetIds: [] } }, __platGen: 0, __http: { sessionGeneration: () => 1 },
    setData(patch) { Object.assign(this.data, patch); }, platStale: () => false };
  attach(unknownPage, { PF: {}, S: {}, http: { sessionGeneration: () => 1 }, api: { assets: {
    uploadTicket: async () => { unknownTicketCalls += 1; }
  } } });
  unknownPage.__post = { postId: 7 }; unknownPage.__postKey = '7'; unknownPage.platAddReference();
  await settle();
  assert.equal(unknownTicketCalls, 0, '未知图片格式不得申请设计上传票据');
  assert.match(unknownPage.data.platformErr, /仅支持 JPEG 或 PNG/);
  delete global.wx;

  let completed = false;
  global.wx = {
    chooseMedia(options) { options.success({ tempFiles: [{ tempFilePath: '/tmp/ref.jpg' }] }); },
    getFileSystemManager() { return { readFile(options) { options.success({ data: Buffer.from([0xff, 0xd8, 0xff, 0xe0]) }); } }; },
    showToast() {}
  };
  const failedPage = {
    data: { form: { referenceAssetIds: [] } }, __platGen: 0, __http: { sessionGeneration: () => 1 },
    setData(patch) { Object.assign(this.data, patch); }, platStale: () => false
  };
  attach(failedPage, { PF: {}, S: {}, http: { sessionGeneration: () => 1, putTicketBytes: async () => { throw Error('参考图上传失败（HTTP 500）'); } }, api: { assets: {
    uploadTicket: async () => ({ assetId: 502, uploadUrl: 'https://cos.example/asset/502' }),
    complete: async () => { completed = true; return { uploadStatus: 'ACCEPTED' }; }
  } } });
  failedPage.__post = { postId: 7 }; failedPage.__postKey = '7';
  failedPage.platAddReference();
  await settle();
  assert.equal(completed, false, 'PUT 非 2xx 时不得 complete');
  assert.match(failedPage.data.platformErr, /上传失败|500/);
  delete global.wx;

  let ticketCalls = 0;
  global.wx = { chooseMedia() { ticketCalls += 100; }, showToast() {} };
  const fullPage = { data: { form: { referenceAssetIds: ['1', '2', '3'] } }, setData(patch) { Object.assign(this.data, patch); } };
  attach(fullPage, { PF: {}, S: {}, http: { sessionGeneration: () => 1 }, api: { assets: {
    uploadTicket: async () => { ticketCalls += 1; }
  } } });
  fullPage.platAddReference();
  assert.equal(ticketCalls, 0, '已有三张参考图时不得再选择或申请上传票据');
  assert.equal(fullPage.data.platformErr, '自传参考最多 3 张');
  delete global.wx;
}

async function verifyTicketTransport() {
  const http = require('../miniprogram/api/http');
  const storage = { 'zs-api-base': 'https://api.test/admin-api', [http.TOKEN_KEY]: 'test-token', [http.POST_KEY]: '77', 'zs-api-tenant-v1': '9' };
  const requests = [];
  global.wx = {
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
    getStorageSync: key => storage[key] || '',
    request(options) { requests.push(options); options.success({ statusCode: 200, data: { code: 0, data: true } }); }
  };
  const bytes = Buffer.from('raw-image');
  await http.putTicketBytes('/admin-api/franchise/assets/601/content?token=fake-ticket', bytes, 'image/jpeg');
  assert.equal(requests[0].url, 'https://api.test/admin-api/franchise/assets/601/content?token=fake-ticket');
  assert.equal(requests[0].method, 'PUT');
  assert.equal(requests[0].data, bytes, '同源代理必须发送原始字节');
  assert.equal(requests[0].header.Authorization, 'Bearer test-token');
  assert.equal(requests[0].header['X-Post-Id'], '77');
  await http.putTicketBytes('https://cos.example/upload?signature=fake', bytes, 'image/jpeg');
  assert.equal(requests[1].header.Authorization, undefined, '外部签名 URL 不得泄漏平台 token');
  const before = requests.length;
  await assert.rejects(() => http.putTicketBytes('local://secret-ticket', bytes, 'image/jpeg'), error => {
    assert.equal(String(error.message).includes('secret-ticket'), false, '错误消息不得回显票据'); return true;
  });
  assert.equal(requests.length, before, '未知票据 URL 不得发请求');
  delete global.wx;
}

async function verifyPublicationFlow() {
  const { savePublicationReceipt } = require('../miniprogram/utils/platform-content');
  for (const [status, expected] of [['publishing', ['verify', 'link']], ['verify', ['link']]]) {
    const calls = [];
    const result = await savePublicationReceipt({ status, url: 'https://example.com/work/1', currentUrl: '',
      verify: async () => calls.push('verify'), link: async () => calls.push('link') });
    assert.equal(result.alreadySaved, false);
    assert.deepEqual(calls, expected, status + ' 状态调用顺序');
  }
  let linked = false;
  await assert.rejects(() => savePublicationReceipt({ status: 'publishing', url: 'https://example.com/work/1', currentUrl: '',
    verify: async () => { throw Error('核验失败'); }, link: async () => { linked = true; } }), /核验失败/);
  assert.equal(linked, false, 'verify 失败不得 link');
  const duplicate = await savePublicationReceipt({ status: 'published', url: 'https://example.com/work/1', currentUrl: 'https://example.com/work/1',
    verify: async () => assert.fail('已发布不得 verify'), link: async () => assert.fail('同链接不得重复写') });
  assert.equal(duplicate.alreadySaved, true);

  const http = require('../miniprogram/api/http');
  const P = require('../miniprogram/utils/platform-content');
  const original = { verify: P.api.publications.verify, link: P.api.publications.link };
  let pageDefinition, resolveVerify, linkCalls = 0;
  const storage = {};
  global.Page = definition => { pageDefinition = definition; };
  global.wx = {
    getStorageSync: key => storage[key] || '', setStorageSync: (key, value) => { storage[key] = value; }, removeStorageSync: key => { delete storage[key]; },
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }), showToast() {}, reLaunch() {}
  };
  const pagePath = require.resolve('../miniprogram/pages/studio/index');
  delete require.cache[pagePath]; require(pagePath);
  P.api.publications.verify = () => new Promise(resolve => { resolveVerify = resolve; });
  P.api.publications.link = async () => { linkCalls += 1; };
  http.saveSession({ token: 'first', postId: '1' });
  const page = { ...pageDefinition, data: { ...pageDefinition.data, recordId: '901', publishedUrlInput: 'https://example.com/work/901', detail: { status: 'publishing', publishedUrl: '' } },
    setData(patch) { Object.assign(this.data, patch); } };
  page.savePublicationLinkOnline();
  await settle();
  assert.equal(typeof resolveVerify, 'function', '真实页面已发起 verify');
  http.saveSession({ token: 'second', postId: '2' });
  resolveVerify({ status: 'verify' });
  await settle();
  assert.equal(linkCalls, 0, 'verify 后切岗不得继续 link');
  P.api.publications.verify = original.verify; P.api.publications.link = original.link;
  delete global.Page; delete global.wx;
}

async function verifyHomeSettlement() {
  const { settleHomeSources } = require('../miniprogram/utils/platform-home-results');
  const result = await settleHomeSources([
    Promise.resolve({ list: [{ id: 1 }] }), Promise.reject(Error('委托失败')),
    Promise.resolve({ leads: 8 }), Promise.resolve([{ workId: 9 }])
  ]);
  assert.deepEqual(result.values[0], { list: [{ id: 1 }] });
  assert.deepEqual(result.values[1], null);
  assert.deepEqual(result.values[2], { leads: 8 });
  assert.equal(result.errors.length, 1);
  assert.equal(result.authError, null);

  const auth = { code: 401, message: '登录失效' };
  const authResult = await settleHomeSources([Promise.reject(auth)]);
  assert.equal(authResult.authError, auth);

  const defer = () => { let resolve, reject; const promise = new Promise((r, j) => { resolve = r; reject = j; }); return { promise, resolve, reject }; };
  const http = require('../miniprogram/api/http'), authApi = require('../miniprogram/api/auth');
  const crmApi = require('../miniprogram/api/franchise/crm'), commissionApi = require('../miniprogram/api/franchise/commission');
  const contentApi = require('../miniprogram/api/franchise/content'), PTPage = require('../miniprogram/utils/platform-todos-page');
  const originals = { token: http.token, post: authApi.currentApiPost, customers: crmApi.customers.page,
    commissions: commissionApi.commissions.page, growth: contentApi.growth.summary, works: contentApi.drafts.works, refresh: PTPage.refresh };
  const customers = defer(), commissions = defer(), growth = defer(), works = defer();
  http.token = () => 'token'; authApi.currentApiPost = () => ({ postId: '1', roleCode: 'boss' });
  crmApi.customers.page = () => customers.promise; commissionApi.commissions.page = () => commissions.promise;
  contentApi.growth.summary = () => growth.promise; contentApi.drafts.works = () => works.promise; PTPage.refresh = () => {};
  let definition; global.Page = value => { definition = value; };
  global.wx = { getStorageSync: () => '', getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }) };
  const businessPath = require.resolve('../miniprogram/pages/business/index'); delete require.cache[businessPath]; require(businessPath);
  const page = { ...definition, data: { ...definition.data }, __platGen: 0,
    setData(patch) { Object.assign(this.data, patch); } };
  page.loadPlatformHome();
  assert.equal(page.data.isBossBiz, true, '请求未完成即显示老板布局');
  assert.equal(page.data.platformStatus, 'loading');
  assert.equal(page.data.bossBiz.commission.value, '—', '加载中不得伪装为0');
  customers.resolve({ list: [] }); commissions.reject(Error('委托失败')); growth.resolve({ leads: 8, publications: 4, converted: 2 }); works.resolve([]);
  await settle();
  assert.equal(page.data.platformStatus, 'degraded');
  assert.equal(page.data.bossBiz.leads, 8, '成功的获客源正常展示');
  assert.equal(page.data.bossBiz.commission.value, '—', '失败的委托源保持未知');
  assert.equal(page.data.bossBiz.commissionCount, '—');
  assert.equal(page.data.bossBiz.customersKnown, true, '成功的空客户列表是已知0，不是未知');
  Object.assign(http, { token: originals.token }); authApi.currentApiPost = originals.post; crmApi.customers.page = originals.customers;
  commissionApi.commissions.page = originals.commissions; contentApi.growth.summary = originals.growth; contentApi.drafts.works = originals.works; PTPage.refresh = originals.refresh;
  delete global.Page; delete global.wx;

  let customerCalls = 0;
  http.token = () => 'token'; authApi.currentApiPost = () => ({ postId: '2', roleCode: 'sales' });
  crmApi.customers.page = () => { customerCalls += 1; return customerCalls === 1 ? Promise.reject(Error('客户失败')) : Promise.resolve({ list: [] }); };
  commissionApi.commissions.page = () => Promise.resolve({ list: [] }); contentApi.growth.summary = () => Promise.resolve({ leads: 0 });
  contentApi.drafts.works = () => Promise.resolve([]); PTPage.refresh = () => {};
  global.Page = value => { definition = value; };
  global.wx = { getStorageSync: () => '', getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }) };
  delete require.cache[businessPath]; require(businessPath);
  const salesPage = { ...definition, data: { ...definition.data }, __platGen: 0, setData(patch) { Object.assign(this.data, patch); } };
  salesPage.loadPlatformHome(); await settle();
  assert.equal(salesPage.data.isSalesHome, true, '销售客户源失败仍保留首页');
  assert.equal(salesPage.data.platformStatus, 'degraded');
  assert.equal(salesPage.data.todayCount, '—', '客户源失败不得伪装今日待跟进为0');
  assert.equal(salesPage.data.overdueCount, '—', '客户源失败不得伪装逾期为0');
  assert.equal(salesPage.data.appointmentCount, '—', '客户源失败不得伪装预约为0');
  assert.equal(salesPage.data.salesCustomersKnown, false, '销售客户数据未知态必须供界面区分');
  assert.equal(typeof salesPage.retryPlatformHome, 'function', '销售失败态必须可重试');
  salesPage.retryPlatformHome(); await settle();
  assert.equal(customerCalls, 2, '销售失败重试必须重新请求客户源');
  assert.equal(salesPage.data.platformStatus, 'ready');
  assert.equal(salesPage.data.todayCount, 0, '成功空结果才显示真实0');
  assert.equal(salesPage.data.salesCustomersKnown, true);
  Object.assign(http, { token: originals.token }); authApi.currentApiPost = originals.post; crmApi.customers.page = originals.customers;
  commissionApi.commissions.page = originals.commissions; contentApi.growth.summary = originals.growth; contentApi.drafts.works = originals.works; PTPage.refresh = originals.refresh;
  delete global.Page; delete global.wx;
}

function verifyReleaseBases() {
  const runtimeEnv = require('../miniprogram/utils/runtime-env');
  const http = require('../miniprogram/api/http');
  const FrLink = require('../miniprogram/utils/fr-web-link');
  const storage = {
    'zs-api-base': 'https://stale-dev.example/admin-api',
    'zs-wallet-base': 'https://stale-dev.example/app-api',
    'zs-fr-host': 'https://stale-fr.example'
  };
  global.wx = {
    getStorageSync: key => storage[key] || '',
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } })
  };
  assert.equal(http.base(), 'https://api2.zszhj.com/admin-api', 'release 必须使用集中 API 配置');
  assert.equal(runtimeEnv.configuredValue('https://api2.zszhj.com/app-api', 'zs-wallet-base'), 'https://api2.zszhj.com/app-api');
  assert.equal(FrLink.resolveHost(storage['zs-fr-host']), '', 'release 不读取旧 Web host 缓存');

  global.wx.getAccountInfoSync = () => ({ miniProgram: { envVersion: 'develop' } });
  assert.equal(http.base(), storage['zs-api-base'], 'develop 保留显式联调覆盖');
  assert.equal(runtimeEnv.configuredValue('', 'zs-wallet-base'), storage['zs-wallet-base']);
  assert.equal(FrLink.resolveHost(storage['zs-fr-host']), storage['zs-fr-host']);

  global.wx.getAccountInfoSync = () => { throw Error('unavailable'); };
  assert.equal(http.base(), 'https://api2.zszhj.com/admin-api', '环境识别异常时安全使用集中配置');
  delete global.wx;
}

(async () => {
  await verifyReferenceUpload();
  await verifyTicketTransport();
  await verifyPublicationFlow();
  await verifyHomeSettlement();
  verifyReleaseBases();
  console.log('verify-joint-review-miniapp ✔  上传、发布状态机、首页部分失败行为通过');
})().catch(error => { console.error(error); process.exit(1); });
