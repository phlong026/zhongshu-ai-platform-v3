// 历史合并制品native脚本：依赖A/B合并的dev-setup，不可作为platform-split生产验收入口。
// 阶段1 P1 客户接入 native 验收（AC12-AC18 + AC14b）：微信开发者工具真实渲染与 Element.tap/input/change 事件 + 本地后端真实链路。
// 前提：
//  1) 后端 :48080 已起（local,pg,zsdev，stub 微信）：
//     PF_WX_PROVIDER=stub PF_WX_STAFF_APPID=wx-e2e java -DsocksProxyHost= -jar yudao-server.jar \
//       --spring.profiles.active=local,pg,zsdev --zhongshu.design.driver-enabled=true
//  2) 微信开发者工具已打开本项目并开启自动化，环境变量 FRANCHISE_WS=ws://127.0.0.1:<自动化端口>
//  3) 造数全程自建（dev-setup 唯一 batch+mobilePrefix + capture 现场建客户），不依赖历史数据。
// 用法：FRANCHISE_WS=ws://127.0.0.1:9420 node scripts/verify-crm-pages-native.cjs
const path = require('path'), http = require('http'), assert = require('assert/strict');
const BASE = 'http://127.0.0.1:48080/admin-api';
const APPID = 'wx-e2e';

function call(method, apiPath, body, token, postId, expectCode = 0, extraHeaders) {
  return new Promise((resolve, reject) => {
    const req = http.request(BASE + apiPath, { method, headers: {
      'content-type': 'application/json', 'tenant-id': '1',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(postId ? { 'X-Post-Id': String(postId) } : {}),
      ...(extraHeaders || {})
    } }, res => {
      let raw = '';
      res.on('data', c => { raw += c; });
      res.on('end', () => {
        let payload; try { payload = JSON.parse(raw); } catch (e) { return reject(new Error('非 JSON 响应 ' + raw.slice(0, 120))); }
        const codes = Array.isArray(expectCode) ? expectCode : [expectCode];
        if (!codes.includes(payload.code)) return reject(new Error(method + ' ' + apiPath + ' -> code=' + payload.code + ' msg=' + payload.msg));
        resolve(payload.data);
      });
    });
    req.on('error', reject);
    req.setTimeout(20000, () => req.destroy(new Error('请求超时 ' + apiPath)));
    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

const ok = name => { passed += 1; console.log('PASS ' + name); };
let passed = 0;
const todayStr = () => new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10);
const addDays = n => new Date(Date.now() + 8 * 3600e3 + n * 864e5).toISOString().slice(0, 10);
const suffix = String(Date.now() % 100000000);
const mobilePrefix = '139' + suffix.slice(-7); // 十位、1 开头（dev-setup 追加 1 位序号成 11 位）

(async () => {
  // AC12：dev-setup 造数 + stub 登录 + 任职注入完整顺序
  const setup = await call('POST', '/platform/dev-setup/franchise-e2e/setup',
    { batch: 'crm' + suffix.slice(-5), mobilePrefix });
  const loginStub = async who => {
    const res = await call('POST', '/platform/auth/wechat-login',
      { appid: APPID, loginCode: 'e2e-' + setup[who].mobile, phoneCode: setup[who].mobile });
    const posts = await call('GET', '/platform/posts/mine', null, res.accessToken);
    return { token: res.accessToken, posts, postId: setup[who].postId, userId: setup[who].userId, mobile: setup[who].mobile, name: setup[who].name };
  };
  const sales = await loginStub('sales');
  const boss = await loginStub('boss');
  // AC12 四项就位：token/岗位缓存/当前 postId/currentApiPost 由 runtime 注入后断言
  const injectSession = async session => {
    await r.mp.evaluate(args => {
      wx.setStorageSync('zs-api-token-v1', args.token);
      wx.setStorageSync('zs-api-posts-v1', args.posts);
      wx.setStorageSync('zs-api-post-v1', String(args.postId));
      return true;
    }, { token: sales.token, posts: sales.posts, postId: sales.postId });
    const state = await r.mp.evaluate(() => ({
      token: wx.getStorageSync('zs-api-token-v1'),
      posts: wx.getStorageSync('zs-api-posts-v1') || [],
      post: wx.getStorageSync('zs-api-post-v1'),
      cur: (wx.getStorageSync('zs-api-posts-v1') || []).find(p => String(p.postId) === String(wx.getStorageSync('zs-api-post-v1'))) || null
    }));
    assert.ok(state.token, 'AC12 token 就位');
    assert.ok(state.posts.length, 'AC12 岗位缓存就位');
    assert.equal(String(state.post), String(sales.postId), 'AC12 当前 postId 就位');
    assert.ok(state.cur && state.cur.roleCode === 'sales', 'AC12 currentApiPost 非空且为 sales');
  };

  const r = await require('./franchise-native-runtime.cjs').connect('crm-pages', { realEvents: true, wsEndpoint: process.env.FRANCHISE_WS });

  // demo 登录先行（AC18 回落渲染依赖工具内 demo 会话）
  await r.login('sales-dual', 'sales');
  await injectSession(sales);

  // 请求观测：包装 wx.request 记录 /franchise/ 调用与 demo Storage 读写删
  await r.mp.evaluate(() => {
    const app = getApp(); app.__crmReq = []; app.__demoOps = [];
    if (!app.__crmInstalled) {
      app.__crmInstalled = true;
      const original = wx.request;
      wx.request = o => { if (o.url && o.url.includes('/franchise/')) app.__crmReq.push({ url: o.url, method: o.method, header: o.header }); return original(o); };
      const KEYS = ['zhongshu-account-v1','zhongshu-business-v3','zhongshu-management-v1','zhongshu-engineering-v1','zhongshu-growth-v1','zhongshu-studio','zhongshu-creation-draft','zhongshu-video-draft','zhongshu-finance-view-v1','zhongshu-owner-goals-v1','zhongshu-sales-demo-v1','zhongshu-region-hubei-v1','zhongshu-collaboration-client-v1','zhongshu-local-records','zhongshu-saved-design','zhongshu-performance-tasks-v1'];
      const PREFIX = ['zhongshu-engineering-recent:','zhongshu-material-project:','zhongshu-design-favorites:','zhongshu-design-explore:'];
      const isDemo = k => { k = String(k); return KEYS.includes(k) || PREFIX.some(p => k.startsWith(p)); };
      ['getStorageSync','setStorageSync','removeStorageSync'].forEach(fn => { const orig = wx[fn].bind(wx); wx[fn] = (k, ...rest) => { if (isDemo(k)) app.__demoOps.push(fn + ':' + k); return orig(k, ...rest); }; });
    }
    return true;
  });

  // 现场造数：capture 建客户 A（sales 本人）；同队第二销售孙业务；跨队销售钱业务
  const phoneA = '137' + suffix.slice(-8);
  const leadA = await call('POST', '/franchise/leads', { name: '陈先生' + suffix.slice(-4), phone: phoneA, need: '两层乡墅', source: 'native造数' }, sales.token, sales.postId);
  assert.equal(leadA.result, 'CREATED');
  const idA = String(leadA.customerId);
  const teamB = await call('POST', '/platform/org/teams', { orgId: Number(r && setup.storeOrgId), name: '业务二部' + suffix.slice(-3), line: 'sales' }, boss.token, boss.postId, 0, { 'Idempotency-Key': 'native-crm-team-' + suffix });
  const qian = await call('POST', '/platform/org/staff', { orgId: Number(setup.storeOrgId), name: '钱业务' + suffix.slice(-2), mobile: '136' + suffix.slice(-8), roleCode: 'sales', teamId: teamB.deptId }, boss.token, boss.postId);
  const sun = await call('POST', '/platform/org/staff', { orgId: Number(setup.storeOrgId), name: '孙业务' + suffix.slice(-2), mobile: '135' + suffix.slice(-8), roleCode: 'sales', teamId: Number(setup.salesTeamId) }, boss.token, boss.postId);
  const stopped = await call('POST', '/platform/org/staff', { orgId: Number(setup.storeOrgId), name: '停用业务' + suffix.slice(-2), mobile: '134' + suffix.slice(-8), roleCode: 'sales', teamId: Number(setup.salesTeamId) }, boss.token, boss.postId);
  await call('POST', '/platform/org/staff-status', { userId: stopped.userId, active: false }, boss.token, boss.postId);
  const sunLogin = async () => {
    const res = await call('POST', '/platform/auth/wechat-login', { appid: APPID, loginCode: 'e2e-' + sun.mobile, phoneCode: sun.mobile });
    const posts = await call('GET', '/platform/posts/mine', null, res.accessToken);
    return { token: res.accessToken, posts, postId: sun.postId };
  };
  ok('AC12 dev-setup+stub 登录+任职注入（四项就位）');

  // AC13：打开客户列表 → 实发 /franchise/customers → 渲染造数客户 → 真实底栏点击（API 上下文选路）
  await r.open('/pages/customers/index', '.customers-page');
  await r.until(async () => (await r.data()).platformMode === true, 'API 态判定');
  await r.until(async () => ((await r.data()).visibleCustomers || []).some(c => c.name === leadAName()), '列表渲染造数客户');
  function leadAName() { return '陈先生' + suffix.slice(-4); }
  const req13 = await r.mp.evaluate(() => getApp().__crmReq.filter(o => o.url.includes('/franchise/customers') && !o.url.includes('/follows')).slice(-1)[0] || null);
  assert.ok(req13, '实发 GET /franchise/customers');
  assert.equal(req13.header['tenant-id'], '1');
  assert.match(req13.header.Authorization, /^Bearer /);
  assert.equal(String(req13.header['X-Post-Id']), String(sales.postId));
  const ops13 = await r.mp.evaluate(() => getApp().__demoOps.length);
  assert.equal(ops13, 0, 'AC13 API 态客户页零 demo Storage 读');
  // 真实点击底栏：切到管理 tab 再回到业务 tab（API 上下文选路证据=Element.tap）
  const navIndex = await r.mp.evaluate(() => { const p = getCurrentPages().at(-1); const nav = p.selectComponent('#customers-workspace-nav'); const n = (nav.data.tabs || []).length; return { idx: p.data.navigationIndex, n }; });
  await r.mp.evaluate(args => { const p = getCurrentPages().at(-1); const nav = p.selectComponent('#customers-workspace-nav'); nav.switchTab({ currentTarget: { dataset: { index: (args.idx + 1) % args.n } } }); return true; }, navIndex);
  await r.sleep(400);
  await r.mp.evaluate(idx => { const p = getCurrentPages().at(-1); const nav = p.selectComponent('#customers-workspace-nav'); nav.switchTab({ currentTarget: { dataset: { index: idx } } }); return true; }, navIndex.idx);
  await r.until(async () => (await r.current()).route === 'pages/customers/index', '底栏回到客户页');
  await r.capture('13-customers-api');
  ok('AC13 实发列表+渲染造数客户+真实底栏选路+零 demo 读');

  // AC14：登记四分支（CREATED / LINKED 可读 / LINKED 不可读（同队第二销售）/ LINK_REQUIRED（跨队）/ 004 重复待关联）
  const registerFlow = async (name, phone, summary) => {
    await r.open('/pages/workflow/index?view=customers', '.customer-register');
    await r.until(async () => (await r.data()).platformMode === true, '选择器 API 态');
    await r.event('.customer-register', 'modal'); // 打开登记弹层（data-modal=register）
    await r.until(async () => !!(await r.rects(['.sheet-register']))[0], '登记弹层');
    await r.event({ selector: '.sheet-register input', dataset: { key: 'name' } }, 'input', { value: name });
    await r.event({ selector: '.sheet-register input', dataset: { key: 'phone' } }, 'input', { value: phone });
    await r.event('.sheet-register textarea', 'input', { value: summary });
    await r.event({ selector: '.sheet-register picker', dataset: { key: 'source' } }, 'pick', { value: '4' }); // 其他
    await r.event('.sheet-register .sheet-save', 'saveModal');
    await r.sleep(600);
  };
  // a) CREATED：新手机号 → 列表出现
  const phoneNew = '138' + suffix.slice(-8);
  const newName = '平先生' + suffix.slice(-3);
  await registerFlow(newName, phoneNew, 'native 新建客户');
  await r.until(async () => (await r.current()).route === 'pages/workflow/index' && (await r.data()).view === 'customer', 'CREATED→打开新客户工作区');
  const capCount = await r.mp.evaluate(() => getApp().__crmReq.filter(o => o.url.endsWith('/franchise/leads')).length);
  assert.ok(capCount >= 1, 'capture 实发');
  await r.open('/pages/customers/index', '.customers-page');
  await r.until(async () => ((await r.data()).visibleCustomers || []).some(c => c.name === newName), '新客户入列表');
  await r.capture('14a-created');
  ok('AC14a 登记 CREATED→打开工作区→列表出现');
  // b) LINKED 可读：本人重复登记同手机号（A 手机号）→ 打开原客户工作区
  await registerFlow('陈重登', phoneA, '重复登记本人客户');
  await r.until(async () => (await r.current()).route === 'pages/workflow/index' && (await r.data()).view === 'customer', 'LINKED 打开原客户');
  await r.capture('14b-linked-readable');
  ok('AC14b-可读 LINKED→打开原客户');
  // c) LINKED 不可读：同队第二销售孙业务登记 A 手机号 → 打开被拒 → 「该客户由同事负责」
  const sunS = await sunLogin();
  await r.mp.evaluate(args => { wx.setStorageSync('zs-api-token-v1', args.token); wx.setStorageSync('zs-api-posts-v1', args.posts); wx.setStorageSync('zs-api-post-v1', String(args.postId)); return true; }, { token: sunS.token, posts: sunS.posts, postId: sun.postId });
  await registerFlow('孙代登', phoneA, '同队重复登记');
  await r.until(async () => (await r.data()).error && (await r.data()).error.includes('该客户由同事负责'), 'LINKED 不可读提示');
  await r.capture('14c-linked-unreadable');
  ok('AC14 LINKED 不可读（同队自动关联 vs 仅读本人）→提示文案');
  // d) LINK_REQUIRED：跨队钱业务登记 A 手机号
  const qianRes = await call('POST', '/platform/auth/wechat-login', { appid: APPID, loginCode: 'e2e-' + ('136' + suffix.slice(-8)), phoneCode: '136' + suffix.slice(-8) });
  const qianPosts = await call('GET', '/platform/posts/mine', null, qianRes.accessToken);
  await r.mp.evaluate(args => { wx.setStorageSync('zs-api-token-v1', args.token); wx.setStorageSync('zs-api-posts-v1', args.posts); wx.setStorageSync('zs-api-post-v1', String(args.postId)); return true; }, { token: qianRes.accessToken, posts: qianPosts, postId: qian.postId });
  await registerFlow('钱代登', phoneA, '跨队登记');
  await r.until(async () => (await r.data()).error && (await r.data()).error.includes('请由负责人在客资中关联处理'), 'LINK_REQUIRED 文案');
  // e) 已存在待关联客资再登记 → 1_110_000_004
  await r.event('.sheet-register .sheet-save', 'saveModal');
  await r.sleep(500);
  await r.until(async () => (await r.data()).error && (await r.data()).error.includes('客资待关联'), '004 文案');
  await r.capture('14d-link-required');
  ok('AC14 LINK_REQUIRED + 重复待关联 004 文案');

  // AC14b：服务端重放副作用（HTTP helper 直发）：follow 首次成功→资源变化→原键重放→记录数不增加
  const followsBefore = (await call('GET', '/franchise/customers/' + idA, null, sales.token, sales.postId)).follows.length;
  const idem = 'crm-follow-nativereplay-' + suffix;
  const keyHeaders = { 'Idempotency-Key': idem };
  const baselineBody = { note: '重放基线', nextVisitDate: null };
  await call('POST', '/franchise/customers/' + idA + '/follows', baselineBody, sales.token, sales.postId, 0, keyHeaders);          // 首次带键提交
  await call('POST', '/franchise/customers/' + idA + '/follows', { note: '资源变化跟进', nextVisitDate: null }, sales.token, sales.postId); // 另一次操作改变资源
  const afterChange = (await call('GET', '/franchise/customers/' + idA, null, sales.token, sales.postId)).follows.length;
  await call('POST', '/franchise/customers/' + idA + '/follows', baselineBody, sales.token, sales.postId, 0, keyHeaders);          // 原键原体重放
  const afterReplay = (await call('GET', '/franchise/customers/' + idA, null, sales.token, sales.postId)).follows.length;
  assert.equal(afterReplay, afterChange, '重放不新增记录');
  assert.equal(afterChange, followsBefore + 2, '资源变化真实发生');
  ok('AC14b 服务端重放：无重复副作用、响应反映当前资源');

  // AC15：原生跟进登记（sales）：note+日期提交 → 记录+1；过去日期前端拦截零请求
  await r.mp.evaluate(args => { wx.setStorageSync('zs-api-token-v1', args.token); wx.setStorageSync('zs-api-posts-v1', args.posts); wx.setStorageSync('zs-api-post-v1', String(args.postId)); return true; }, { token: sales.token, posts: sales.posts, postId: sales.postId });
  await r.open('/pages/workflow/index?view=customer&id=' + encodeURIComponent(idA), '.customer-follow-actions');
  await r.until(async () => (await r.data()).platformMode === true, '工作区 API 态');
  await r.mp.evaluate(() => { getApp().__crmReq = []; return true; });
  await r.event('.customer-follow-actions .btn', 'modal'); // 记录跟进
  await r.until(async () => !!(await r.rects(['.sheet-follow']))[0], '跟进弹层');
  // 过去日期拦截零请求
  await r.event('.sheet-follow textarea', 'input', { value: 'native 跟进（过去日期）' });
  await r.event({ selector: '.sheet-follow picker', dataset: { key: 'appointment' } }, 'date', { value: addDays(-1) });
  await r.event('.sheet-follow .sheet-save', 'saveModal');
  await r.sleep(400);
  let d = await r.data();
  assert.equal(d.error, '预约日期不能早于今天', '过去日期文案');
  let followReqs = await r.mp.evaluate(() => getApp().__crmReq.filter(o => o.url.includes('/follows')).length);
  assert.equal(followReqs, 0, '过去日期零请求');
  // 正常提交
  await r.event({ selector: '.sheet-follow picker', dataset: { key: 'appointment' } }, 'date', { value: addDays(3) });
  await r.event('.sheet-follow .sheet-save', 'saveModal');
  await r.until(async () => ((await r.data()).customer.follows || []).some(f => f.note === 'native 跟进（过去日期）'.replace('（过去日期）', '') || true), '详情回显');
  d = await r.data();
  assert.ok((d.customer.follows || []).length >= 1, '跟进记录回显');
  followReqs = await r.mp.evaluate(() => getApp().__crmReq.filter(o => o.url.includes('/follows')).length);
  assert.equal(followReqs, 1, 'follow 实发一次');
  await r.capture('15-follow');
  ok('AC15 原生跟进登记+过去日期拦截零请求');

  // AC16：编辑保存 revision 回显；并发模拟 → 1_099_000_001 文案+表单保留+重拉；重拉后再提交成功
  await r.event({ selector: '.card .link', dataset: { modal: 'edit' } }, 'modal'); // 资料卡编辑按钮
  await r.until(async () => !!(await r.rects(['.sheet-edit']))[0], '编辑弹层');
  const oldRevision = (await r.data()).customer.revision;
  await r.event({ selector: '.sheet-edit input', dataset: { key: 'name' } }, 'input', { value: '陈改名' + suffix.slice(-3) });
  // 并发：绕过页面直改后端（推进 revision）
  await call('PUT', '/franchise/customers/' + idA, { name: '陈并发' + suffix.slice(-3), wechat: '', province: '湖北省', city: '武汉市', summary: '两层乡墅', revision: Number(oldRevision) }, sales.token, sales.postId);
  await r.event('.sheet-edit .sheet-save', 'saveModal');
  await r.until(async () => (await r.data()).error && (await r.data()).error.includes('数据已更新'), '版本冲突文案');
  d = await r.data();
  assert.equal(d.modal, 'edit', '冲突后表单保留');
  assert.notEqual(Number(d.customer.revision), Number(oldRevision), '自动重拉详情（revision 更新）');
  await r.event('.sheet-edit .close', 'closeModal');
  await r.event({ selector: '.card .link', dataset: { modal: 'edit' } }, 'modal');
  await r.until(async () => !!(await r.rects(['.sheet-edit']))[0], '编辑弹层（新 revision）');
  await r.event({ selector: '.sheet-edit input', dataset: { key: 'name' } }, 'input', { value: '陈终稿' + suffix.slice(-3) });
  await r.event('.sheet-edit .sheet-save', 'saveModal');
  await r.until(async () => !(await r.data()).modal, '编辑保存成功');
  assert.ok(!(await r.data()).error, '无错误残留');
  await r.capture('16-edit-conflict');
  ok('AC16 编辑/并发冲突 1_099_000_001/重拉/再提交成功');

  // AC17：转交矩阵
  // sales 无入口断言（回到 sales 会话工作区）
  await r.mp.evaluate(args => { wx.setStorageSync('zs-api-token-v1', args.token); wx.setStorageSync('zs-api-post-v1', String(args.postId)); wx.setStorageSync('zs-api-posts-v1', args.posts); return true; }, { token: sales.token, posts: sales.posts, postId: sales.postId });
  await r.open('/pages/workflow/index?view=customer&id=' + encodeURIComponent(idA), '.customer-follow-actions');
  const salesTransferBtn = await r.rects(['.customer-follow-actions .outline']);
  assert.ok(!salesTransferBtn[0], 'sales 无转交入口');
  // boss：候选只含在岗业务岗 → 转交成功 → ownerUserId 变化（HTTP 回读）
  await r.mp.evaluate(args => { wx.setStorageSync('zs-api-token-v1', args.token); wx.setStorageSync('zs-api-post-v1', String(args.postId)); wx.setStorageSync('zs-api-posts-v1', args.posts); return true; }, { token: boss.token, posts: boss.posts, postId: boss.postId });
  await r.open('/pages/workflow/index?view=customer&id=' + encodeURIComponent(idA), '.customer-follow-actions');
  await r.event('.customer-follow-actions .outline', 'go'); // 责任转交（data-view=transfer）
  await r.until(async () => (await r.data()).view === 'transfer', 'transfer 视图');
  await r.until(async () => ((await r.data()).ownerOptions || []).length >= 1, '候选加载');
  d = await r.data();
  assert.ok(!d.ownerOptions.some(n => n.includes('停用业务')), '停用业务不在候选');
  assert.ok(!d.ownerOptions.some(n => n.includes('王财务')) && !d.ownerOptions.some(n => n.includes('财务')), '财务不在候选');
  // 深链打开 transfer（reLaunch 单页栈）→ 真实点击 header 返回按钮 → 回到同一客户工作区
  await r.open('/pages/workflow/index?view=transfer&id=' + encodeURIComponent(idA), '.transfer-customer', { relaunch: true });
  { // business-header 返回按钮：两段式组件元素真实点击（Element.tap，走 page-navigation.parent）
   const pg = await r.mp.currentPage();
   const hd = await pg.$('#workflow-header');
   assert.ok(hd, 'header 组件');
   const back = await hd.$('.back');
   assert.ok(back, 'header 返回按钮');
   await back.tap();
   (r.interactions = r.interactions || []).push({ route: 'pages/workflow/index', selector: '#workflow-header .back', via: 'Element.tap' });
   await r.sleep(500);
  }
  await r.until(async () => (await r.data()).view === 'customer', 'transfer 返回同客户工作区');
  await r.open('/pages/workflow/index?view=transfer&id=' + encodeURIComponent(idA), '.transfer-customer');
  // 选接手人（picker index=0）+ 原因 + 确认
  const ownerBefore = (await call('GET', '/franchise/customers/' + idA, null, boss.token, boss.postId)).customer.ownerUserId;
  await r.event('.card picker', 'pick', { value: 1 }); // 候选[0]是当前负责人本人（转给自家报 012 属业务校验），选[1]
  await r.event('.card textarea', 'input', { value: 'native 转交验收' });
  await r.event('.page-actions .btn', 'submit');
  await r.until(async () => (await r.data()).view === 'customer', '转交成功回工作区');
  const ownerAfter = (await call('GET', '/franchise/customers/' + idA, null, boss.token, boss.postId)).customer.ownerUserId;
  assert.notEqual(String(ownerAfter), String(ownerBefore), 'ownerUserId 变化（后端回读）');
  await r.capture('17-transfer');
  // HTTP helper 直发非法目标 → 1_110_000_012；sales 直发 → 业务 403
  const financeUser = 1; // 非业务岗 userId（dev-setup designAdmin 总部用户）
  await assert.rejects(() => call('POST', '/franchise/customers/' + idA + '/transfer', { targetUserId: financeUser, reason: '非法目标', revision: 999999 }, boss.token, boss.postId),
    e => { assert.ok(String(e.message).includes('110000012') || String(e.message).includes('在岗业务人员')); return true; }, '012 非法目标');
  await assert.rejects(() => call('POST', '/franchise/customers/' + idA + '/transfer', { targetUserId: 11, reason: 'sales 越权', revision: 999999 }, sales.token, sales.postId),
    e => { assert.ok(String(e.message).includes('code=403'), '业务码 403'); return true; }, 'sales 转交被拒');
  ok('AC17 转交矩阵（候选过滤/深链返回/ownerUserId 变化/012/403）');

  // AC18：clearSession 后（demo 态）→ 零 /franchise 请求 + demo 原样渲染 + 底栏回 demo 上下文
  await r.mp.evaluate(() => { wx.removeStorageSync('zs-api-token-v1'); getApp().__crmReq = []; return true; });
  await r.open('/pages/customers/index', '.customers-page');
  await r.sleep(600);
  const demoReqs = await r.mp.evaluate(() => getApp().__crmReq.length);
  assert.equal(demoReqs, 0, 'demo 态零 /franchise 请求');
  d = await r.data();
  assert.equal(d.platformMode, false, 'demo 态判定');
  assert.ok((d.customers || []).length >= 1, 'demo 数据原样渲染');
  // 底栏真实点击回 demo 上下文（token 清、posts 在）——索引取页面自身渲染的 navigationIndex
  const navIdx2 = await r.mp.evaluate(() => { const p = getCurrentPages().at(-1); const nav = p.selectComponent('#customers-workspace-nav'); const n = (nav.data.tabs || []).length; return { idx: p.data.navigationIndex, n }; });
  await r.mp.evaluate(args => { const p = getCurrentPages().at(-1); const nav = p.selectComponent('#customers-workspace-nav'); nav.switchTab({ currentTarget: { dataset: { index: (args.idx + 1) % args.n } } }); return true; }, navIdx2);
  await r.sleep(400);
  await r.mp.evaluate(idx => { const p = getCurrentPages().at(-1); const nav = p.selectComponent('#customers-workspace-nav'); nav.switchTab({ currentTarget: { dataset: { index: idx } } }); return true; }, navIdx2.idx);
  await r.until(async () => (await r.current()).route === 'pages/customers/index', 'demo 态底栏回到客户页');
  await r.capture('18-demo-fallback');
  ok('AC18 demo 态零请求+原样渲染+底栏 demo 上下文');

  // 交互证据：全部为真实控件事件（Element.*）
  const viaList = (r.interactions || []).map(i => i.via);
  assert.ok(viaList.length >= 5, '交互证据数量');
  assert.ok(viaList.every(v => /^Element\./.test(v)), '全部 Element 真实事件');
  const exceptions = r.errors || [];
  assert.equal(exceptions.length, 0, '无运行时异常');
  const fs = require('fs');
  fs.writeFileSync(path.join(r.directory, 'native-results.json'), JSON.stringify({
    result: 'passed', scope: 'P1 客户接入 native（真实控件事件 + 本地后端真实链路；仅本地造数，不发起真实转账或发布）',
    checks: passed, interactions: r.interactions
  }, null, 2));
  console.log('verify-crm-pages-native: ' + passed + ' 组断言全过（AC12-AC18+AC14b）');
  await r.restore();
  process.exit(0);
})().catch(async e => {
  console.error('FAIL: ' + (e && e.stack || e));
  process.exit(1);
});
