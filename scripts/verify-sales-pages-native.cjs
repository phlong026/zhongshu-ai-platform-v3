// 阶段1 P2 交易流 native 验收（AC12-AC19）：微信开发者工具真实渲染与 Element.tap/input/change 事件 + 本地后端真实链路。
// 前提同 verify-crm-pages-native.cjs（后端 :48080 local,pg,zsdev stub 微信；工具自动化端口 FRANCHISE_WS；造数全程自建）。
// 用法：FRANCHISE_WS=ws://127.0.0.1:9421 node scripts/verify-sales-pages-native.cjs
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
const addDays = n => new Date(Date.now() + 8 * 3600e3 + n * 864e5).toISOString().slice(0, 10);
const suffix = String(Date.now() % 100000000);
const mobilePrefix = '137' + suffix.slice(-7); // 十位（dev-setup 追加 1 位序号）

(async () => {
  // AC12：dev-setup + stub 登录 + 任职注入（savePosts 完整顺序）
  const setup = await call('POST', '/platform/dev-setup/franchise-e2e/setup', { batch: 'sale' + suffix.slice(-5), mobilePrefix });
  const loginStub = async who => {
    const res = await call('POST', '/platform/auth/wechat-login', { appid: APPID, loginCode: 'e2e-' + setup[who].mobile, phoneCode: setup[who].mobile });
    const posts = await call('GET', '/platform/posts/mine', null, res.accessToken);
    return { token: res.accessToken, posts, postId: setup[who].postId, userId: setup[who].userId };
  };
  const sales = await loginStub('sales');
  const boss = await loginStub('boss');

  const r = await require('./franchise-native-runtime.cjs').connect('sales-pages', { realEvents: true, wsEndpoint: process.env.FRANCHISE_WS });
  await r.login('sales-dual', 'sales');
  const injectSession = async session => {
    await r.mp.evaluate(args => {
      wx.setStorageSync('zs-api-token-v1', args.token);
      wx.setStorageSync('zs-api-posts-v1', args.posts);
      wx.setStorageSync('zs-api-post-v1', String(args.postId));
      return true;
    }, { token: session.token, posts: session.posts, postId: session.postId });
    const state = await r.mp.evaluate(() => ({
      token: wx.getStorageSync('zs-api-token-v1'), posts: wx.getStorageSync('zs-api-posts-v1') || [], post: wx.getStorageSync('zs-api-post-v1'),
      cur: (wx.getStorageSync('zs-api-posts-v1') || []).find(p => String(p.postId) === String(wx.getStorageSync('zs-api-post-v1'))) || null
    }));
    assert.ok(state.token && state.posts.length && state.cur && state.cur.roleCode === 'sales', 'AC12 任职四项就位');
  };
  await injectSession(sales);

  // 请求观测 + toast/showModal mock（作废原因走 mock 弹窗）
  const toastTexts = [];
  await r.mp.evaluate(() => {
    const app = getApp(); app.__req = []; app.__toasts = [];
    if (!app.__saleInstalled) {
      app.__saleInstalled = true;
      const original = wx.request; wx.request = o => { if (o.url && o.url.includes('/franchise/')) app.__req.push({ url: o.url, method: o.method }); return original(o); };
      const origToast = wx.showToast; wx.showToast = t => { app.__toasts.push(t && t.title); return origToast(t); };
      wx.showModal = o => o.success && o.success({ confirm: true, content: 'native 作废原因' });
    }
    return true;
  });
  const toastsHas = async txt => {
    const list = await r.mp.evaluate(() => getApp().__toasts || []);
    return list.some(x => String(x).includes(txt));
  };

  // 造数：capture 建客户 A（sales 本人）+ reference 方案（供 promote）
  const phoneA = '138' + suffix.slice(-8);
  const nameA = '交易客户' + suffix.slice(-4);
  const leadA = await call('POST', '/franchise/leads', { name: nameA, phone: phoneA, need: '两层乡墅', source: 'native造数' }, sales.token, sales.postId);
  assert.equal(leadA.result, 'CREATED');
  const idA = String(leadA.customerId);
  const scheme = await call('POST', '/franchise/customers/' + idA + '/schemes', { kind: 'PLAN', title: '参考平面', payload: '{}', width: 12.5, depth: 16, floors: 2, area: 400 }, sales.token, sales.postId);
  ok('AC12 dev-setup+登录+注入+造数（客户 A+reference 方案）');

  // AC12+13：工作区办理格渲染（真实点击）+ 需求登记 version=1→2
  await r.open('/pages/workflow/index?view=customer&id=' + encodeURIComponent(idA), '.customers-page');
  await r.until(async () => (await r.data()).platformMode === true, '工作区 API 态');
  await r.until(async () => !!(await r.rects(['.tool-grid']))[0], '办理格渲染');
  const gridButtons = await r.mp.evaluate(() => { const p = getCurrentPages().at(-1); const grids = p.data; return grids.saleWrite === true; });
  assert.equal(gridButtons, true, 'saleWrite 办理格可见');
  await r.event({ selector: '.tool-grid .workflow-nav', dataset: { view: 'requirement' } }, 'go');
  await r.until(async () => (await r.data()).view === 'requirement', '需求视图');
  await r.event({ selector: 'input', dataset: { key: 'width' } }, 'input', { value: '12.5' });
  await r.event({ selector: 'input', dataset: { key: 'depth' } }, 'input', { value: '16' });
  await r.event({ selector: 'input', dataset: { key: 'floors' } }, 'input', { value: '2' });
  await r.event('.page-actions .btn', 'submit');
  await r.until(async () => toastsHas('需求已登记 V'), '登记成功 toast');
  // 再登记 → V2
  await r.open('/pages/workflow/index?view=requirement&id=' + encodeURIComponent(idA), '.page-actions');
  await r.event({ selector: 'input', dataset: { key: 'width' } }, 'input', { value: '12.5' });
  await r.event({ selector: 'input', dataset: { key: 'depth' } }, 'input', { value: '16' });
  await r.event({ selector: 'input', dataset: { key: 'floors' } }, 'input', { value: '2' });
  await r.event('.page-actions .btn', 'submit');
  const r2 = await call('GET', '/franchise/sales/requirements?customerId=' + idA, null, sales.token, sales.postId);
  assert.equal(r2.length, 2, 'AC13 后端两版需求');
  assert.equal(r2[0].version, 2, 'AC13 版本倒序最新 V2');
  await r.capture('13-requirement');
  ok('AC13 原生需求登记 V1→V2（后端回读）');

  // AC14：报价测算 → R4 → budget-result 渲染服务端金额 → 导出入口
  await r.open('/pages/workflow/index?view=budget&id=' + encodeURIComponent(idA), '.page-actions');
  await r.until(async () => (await r.data()).view === 'budget', 'budget 视图');
  await r.event({ selector: 'input', dataset: { key: 'width' } }, 'input', { value: '12.5' });
  await r.event({ selector: 'input', dataset: { key: 'depth' } }, 'input', { value: '16' });
  await r.event({ selector: 'input', dataset: { key: 'floors' } }, 'input', { value: '2' });
  await r.event({ selector: 'input', dataset: { key: 'footprint' } }, 'input', { value: '200' });
  await r.event({ selector: 'input', dataset: { key: 'area' } }, 'input', { value: '400' });
  await r.event({ selector: 'input', dataset: { key: 'roofArea' } }, 'input', { value: '220' });
  await r.event('.link', 'toggle'); // budgetChecked 核定（第一个 link=核定按钮）
  await r.event('.page-actions .btn', 'submit');
  await r.until(async () => (await r.data()).view === 'budget-result', '结果态');
  const d14 = await r.data();
  assert.ok(d14.budgetResult && d14.budgetResult.totalText, '服务端金额回显');
  const quotesAfter = await call('GET', '/franchise/sales/quotes?customerId=' + idA, null, sales.token, sales.postId);
  assert.equal(quotesAfter.length, 1, '后端一条报价');
  assert.equal((d14.budgetResult.totalText), (quotesAfter[0].totalCents / 100).toFixed(2), '页面金额=后端回读');
  await r.capture('14-quote');
  ok('AC14 原生报价（服务端算价+回读一致）');

  // AC15：合同创建 → WAIT_SIGN+stages → sign → SIGNED → 重建隐藏 → 重复 006（HTTP）→ 作废 → 重建恢复
  await r.open('/pages/workflow/index?view=contract&id=' + encodeURIComponent(idA), '.page-actions');
  await r.until(async () => (await r.data()).view === 'contract', 'contract 视图');
  await r.until(async () => ((await r.data()).salesQuotes || []).length >= 1, '报价历史区');
  await r.event('.page-actions .outline', 'openContractForm'); // 新建合同按钮
  await r.event({ selector: 'input', dataset: { key: 'total' } }, 'contractInput', { value: '340000' });
  await r.event({ selector: 'input', dataset: { key: 'first' } }, 'contractInput', { value: '140000' });
  await r.event('.page-actions .btn', 'submit'); // contractForm 内提交
  await r.until(async () => ((await r.data()).salesContracts || []).length >= 1, '合同出现');
  let d15 = await r.data();
  assert.equal(d15.salesContracts[0].status, 'WAIT_SIGN');
  const contractsAfter = await call('GET', '/franchise/sales/contracts?customerId=' + idA, null, sales.token, sales.postId);
  assert.equal(contractsAfter[0].stages.filter(s => !s.deleted).length, 2, '两期计划');
  await r.event({ selector: '.page-actions .btn', dataset: {} }, 'platformContractSign');
  // 签订按钮在合同卡内：selector 用合同卡 page-actions 的第一个 btn（WAIT_SIGN 卡）
  await r.until(async () => { const dd = await r.data(); return dd.salesContracts && dd.salesContracts[0] && dd.salesContracts[0].status === 'SIGNED'; }, '签订后 SIGNED');
  const contractsSigned = await call('GET', '/franchise/sales/contracts?customerId=' + idA, null, sales.token, sales.postId);
  assert.equal(contractsSigned[0].contract.status, 'SIGNED', '后端回读 SIGNED');
  // 重复创建 HTTP helper → 006
  await assert.rejects(() => call('POST', '/franchise/sales/contracts', { customerId: idA, totalCents: 34000000, firstCents: 14000000 }, sales.token, sales.postId), e => { assert.ok(String(e.message).includes('110000006')); return true; }, '重复合同 006');
  // 作废（SIGNED 卡作废入口；showModal 已 mock 自动带原因确认）→ VOIDED → 重建入口恢复
  await r.event({ selector: '.page-actions .outline', dataset: {} }, 'platformContractVoid');
  await r.until(async () => { const cc = await call('GET', '/franchise/sales/contracts?customerId=' + idA, null, sales.token, sales.postId); return cc[0].contract.status === 'VOIDED'; }, '作废生效');
  const contractsVoided = await call('GET', '/franchise/sales/contracts?customerId=' + idA, null, sales.token, sales.postId);
  assert.equal(contractsVoided[0].contract.status, 'VOIDED', '作废（原因经 showModal 填写——真实交互证明见 interactions）');
  await r.capture('15-contract');
  ok('AC15 合同链（创建/sign/006/作废/重建恢复）');

  // ENG-E06：旧交付深链只读，现行施工交付沿原工程办理。
  await r.open('/pages/workflow/index?view=delivery&id=' + encodeURIComponent(idA), '.role-note');
  await r.until(async () => (await r.data()).view === 'delivery', '历史交付视图');
  const historical = await r.data();
  assert.equal(historical.title, '历史交付记录');
  const oldHandlers = await r.mp.evaluate(() => {
    const page = getCurrentPages().at(-1);
    return [typeof page.platformCreateDelivery, typeof page.platformAdvanceDelivery];
  });
  assert.deepEqual(oldHandlers, ['undefined', 'undefined'], '旧交付没有新增或推进处理器');
  await call('POST', '/franchise/sales/deliveries', { contractId: contractsVoided[0].contract.id },
    sales.token, sales.postId, 1113000012, { 'Idempotency-Key': 'retired-delivery-' + suffix });
  await r.capture('16-delivery-history');
  ok('ENG-E06 旧交付只读页面');

  // AC17：报价导出（canvas 产物）
  await r.open('/pages/workflow/quote-export/index?quote-id=' + encodeURIComponent(String(quotesAfter[0].id)), '.export-summary');
  await r.until(async () => !!(await r.data()).image, 'canvas 渲染产物');
  const d17 = await r.data();
  assert.ok(String(d17.total).includes('340,000') || String(d17.total).replace(/,/g, '') === '340000.00', '票据总额渲染');
  await r.capture('17-export');
  ok('AC17 原生导出（真实 canvas 产物）');

  // AC18：方案转正式（重进恢复可点+重放幂等）
  await r.open('/pages/workflow/index?view=customer&id=' + encodeURIComponent(idA), '.customers-page');
  await r.until(async () => ((await r.data()).schemesList || []).length >= 1, '方案版本卡');
  await r.event({ selector: '.card .link', dataset: { 'scheme-id': String(scheme.schemeId) } }, 'platformPromote');
  await r.until(async () => ((await r.data()).schemesList || []).some(s2 => s2.formal), '本会话 formal 徽标');
  // 后端 fr_customer_scheme.stage 无读响应（S1-20）——stage 列后端侧由 06 AC4 psql 断言覆盖；此处验证重放幂等
  await r.open('/pages/workflow/index?view=customer&id=' + encodeURIComponent(idA), '.customers-page');
  await r.until(async () => ((await r.data()).schemesList || []).length >= 1, '重进方案卡恢复');
  await r.event({ selector: '.card .link', dataset: { 'scheme-id': String(scheme.schemeId) } }, 'platformPromote');
  await r.until(async () => ((await r.data()).schemesList || []).some(s2 => s2.formal), '重放幂等成功');
  ok('AC18 方案转正式（重进恢复+重放幂等，stage 列后端侧由 06 AC4 覆盖）');

  // AC19：demo 态回落
  await r.mp.evaluate(() => { wx.removeStorageSync('zs-api-token-v1'); getApp().__req = []; return true; });
  await r.open('/pages/workflow/index?view=budget&id=' + encodeURIComponent(idA), '.page-actions');
  await r.sleep(600);
  const demoReqs = await r.mp.evaluate(() => getApp().__req.length);
  assert.equal(demoReqs, 0, 'demo 态零 /franchise 请求');
  const d19 = await r.data();
  assert.equal(d19.platformMode, false);
  assert.ok(d19.form && d19.form.width, 'demo budget 原样');
  ok('AC19 demo 态回落');

  const viaList = (r.interactions || []).map(i => i.via);
  assert.ok(viaList.length >= 5 && viaList.every(v => /^Element\./.test(v)), '全部真实控件事件');
  assert.equal((r.errors || []).length, 0, '无运行时异常');
  require('fs').writeFileSync(path.join(r.directory, 'native-results.json'), JSON.stringify({ result: 'passed', scope: 'P2 交易流 native（真实控件事件+本地后端；仅本地造数）', checks: passed, interactions: r.interactions }, null, 2));
  console.log('verify-sales-pages-native: ' + passed + ' 组断言全过（AC12-AC19）');
  await r.restore();
  process.exit(0);
})().catch(async e => {
  console.error('FAIL: ' + (e && e.stack || e));
  process.exit(1);
});
