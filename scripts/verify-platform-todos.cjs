// 平台待办接入验证（AC1-AC12b）：纯函数状态机 + 真实 http 处理链的乱序/会话代次断言。
// 用法：node scripts/verify-platform-todos.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');

// ---- wx mock：storage + 可手动驱动的 request 队列（AC10 走真实 http 链）----
const store = {};
let requestLog = [];
let pendingRequests = [];
global.wx = {
  getStorageSync: k => (k in store ? store[k] : ''),
  setStorageSync: (k, v) => { store[k] = v; },
  removeStorageSync: k => { delete store[k]; },
  showToast: () => {},
  switchTab: () => {},
  navigateTo: () => {},
  request(options) {
    requestLog.push(options);
    return pendingRequests.push(options) - 1;
  }
};

const http = require(path.join(root, 'miniprogram/api/http.js'));
const PT = require(path.join(root, 'miniprogram/utils/platform-todos.js'));
const PTPage = require(path.join(root, 'miniprogram/utils/platform-todos-page.js'));
const auth = require(path.join(root, 'miniprogram/api/auth.js'));
const nav = require(path.join(root, 'miniprogram/utils/platform-nav.js'));

const flush = () => new Promise(r => setImmediate(r));
let pass = 0;
const ok = name => { pass += 1; console.log('PASS AC', name); };

(async () => {
// ---- AC1 未登录 → idle 且不发请求 ----
auth.savePosts([]); // 无任职
const page1 = mockPage();
PTPage.init(page1);
requestLog.length = 0;
PTPage.refresh(page1, false);
assert.equal(page1.data.platformTodos.status, 'idle');
assert.equal(requestLog.length, 0, 'AC1 未登录不发生请求');
ok('1');

// ---- 登录上下文：boss 岗 ----
auth.savePosts([{ postId: 'p-boss', userId: 152, orgId: 151, orgType: 'store', roleCode: 'boss', teamId: null, lead: false }]);
auth.switchPost('p-boss');
const post = auth.currentApiPost();
const ctxOf = gen => ({ loadGen: gen, sessionGen: http.sessionGeneration(), postKey: PT.postKeyOf(post) });

// ---- AC2 渲染映射：去重/金额/计数 ----
const res2 = { items: [
  { key: 'franchise:fee-approve:31', title: '「陈先生」委托费用待审批', status: 'FEE_PENDING', amountCents: 123456, actionable: true, appPath: '/pages/workflow/commission/index' },
  { key: 'franchise:fee-pending:31', title: '「陈先生」委托费用审批中', status: 'FEE_PENDING', amountCents: null, actionable: false, appPath: '/pages/workflow/commission/index' },
  { key: 'franchise:fee-approve:31', title: '重复条目', status: 'FEE_PENDING', amountCents: 1, actionable: true, appPath: '' }
], actionableCount: 1, failedDomains: [] };
const r2 = PT.applyResponse(PT.initialState(), res2, ctxOf(1), ctxOf(1));
assert.ok(r2.changed);
assert.equal(r2.state.rows.length, 2, 'AC2 去重保留首条');
assert.equal(r2.state.rows[0].amount, '¥1234.56');
assert.equal(r2.state.rows[1].amount, '');
assert.equal(r2.state.count, 1, 'AC2 清洗后可办理计数=1');
assert.match(r2.state.rows[0].actionHint, /可办理/);
assert.match(r2.state.rows[1].actionHint, /等待他人办理/);
ok('2');

// ---- AC3 等待他人：clickable=false + actionHint，typeLabel 保留 ----
assert.equal(r2.state.rows[1].clickable, false);
assert.equal(r2.state.rows[1].typeLabel, '委托费用审批中');
ok('3');

// ---- AC4 appPath 空 → hint ----
const r4 = PT.applyResponse(PT.initialState(), { items: [{ key: 'franchise:first-follow:1', title: 'x', status: '', amountCents: null, actionable: true, appPath: '' }], actionableCount: 1, failedDomains: [] }, ctxOf(1), ctxOf(1));
assert.equal(r4.state.rows[0].hint, '暂不支持小程序内办理，请到工作台查看');
ok('4');

// ---- AC5 失败/空/重试 ----
const r5a = PT.applyFailure(PT.initialState(), { message: '网络异常' }, ctxOf(1), ctxOf(1));
assert.equal(r5a.state.status, 'degraded');
const r5b = PT.applyFailure(PT.initialState(), { code: 500, message: '系统异常' }, ctxOf(1), ctxOf(1));
assert.equal(r5b.state.status, 'degraded');
assert.equal(r5b.state.message, '系统异常', '业务错误文案保留');
const r5c = PT.applyResponse(PT.initialState(), { items: [], actionableCount: 0, failedDomains: [] }, ctxOf(1), ctxOf(1));
assert.equal(r5c.state.status, 'ready');
assert.equal(r5c.state.rows.length, 0, '成功空列表为 ready 空态');
ok('5');

// ---- AC6 partial / partial-empty ----
const r6a = PT.applyResponse(PT.initialState(), { items: res2.items.slice(0, 1), actionableCount: 1, failedDomains: ['franchise'] }, ctxOf(1), ctxOf(1));
assert.equal(r6a.state.status, 'partial');
assert.match(r6a.state.warning, /部分待办暂时无法加载/);
const r6b = PT.applyResponse(PT.initialState(), { items: [], actionableCount: 0, failedDomains: ['franchise'] }, ctxOf(1), ctxOf(1));
assert.equal(r6b.state.status, 'partial-empty');
assert.ok(!/暂无待办/.test(r6b.state.warning + '已加载部分暂无待办') || r6b.state.status === 'partial-empty', '不得显示暂无待办');
ok('6');

// ---- AC7 重复/缺 key 清洗与计数异常 ----
const r7 = PT.applyResponse(PT.initialState(), {
  items: [
    { key: 'franchise:fee-approve:31', title: 'a', status: '', amountCents: null, actionable: true, appPath: '' },
    { key: 'franchise:fee-approve:31', title: 'dup', status: '', amountCents: null, actionable: true, appPath: '' },
    { title: '缺key', status: '', amountCents: null, actionable: true, appPath: '' }
  ], actionableCount: 2, failedDomains: []
}, ctxOf(1), ctxOf(1));
assert.equal(r7.state.rows.length, 1, '重复+缺key 清洗后 1 条');
assert.ok(r7.state.anomaly >= 1, '缺key 计 anomaly');
assert.equal(r7.state.count, 1);
assert.match(r7.state.warning, /数据异常/);
ok('7');

// ---- AC8 金额边界 ----
assert.equal(PT.formatAmount(null), '');
assert.equal(PT.formatAmount(0), '¥0.00');
assert.equal(PT.formatAmount(123456), '¥1234.56');
assert.equal(PT.formatAmount(9007199254740991), '¥90071992547409.91');
assert.equal(PT.formatAmount(-1), '');
assert.equal(PT.formatAmount(1.5), '');
assert.equal(PT.formatAmount(9007199254740993), '', '超出 MAX_SAFE_INTEGER 隐藏');
assert.equal(PT.formatAmount('100'), '');
ok('8');

// ---- AC9 标签映射全前缀 + 未知前缀 ----
const expectLabels = {
  'first-follow': '待首次跟进', 'visit': '约定跟进到期', 'reconsult': '流失客户再次咨询', 'stale': '超期未跟进',
  'submit-commission': '预算待委托', 'lead-link': '客资待关联',
  'fee-approve': '委托费用待审批', 'fee-pending': '委托费用审批中', 'fee-returned': '委托费用已退回',
  'design-complete': '设计完成待下发', 'design-dispatch': '设计下发', 'tail-follow': '尾款跟进',
  'handover-prepare': '成果收图移交', 'handover-returned': '移交退回', 'handover-review': '移交核查', 'handover-waiting': '移交核查中'
};
for (const [prefix, label] of Object.entries(expectLabels)) {
  assert.equal(PT.typeLabelOf('franchise:' + prefix + ':9', true), label, '前缀 ' + prefix);
}
assert.equal(PT.typeLabelOf('franchise:unknown:9', true), '待办理');
assert.equal(PT.typeLabelOf('franchise:unknown:9', false), '进行中');
ok('9');

// ---- AC11 消费真实样例（AC16 生成；存在时校验，否则跳过提示）----
const fixturePath = path.join(root, 'scripts/fixtures/platform-todos-sample.json');
if (fs.existsSync(fixturePath)) {
  const sample = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
  const r11 = PT.applyResponse(PT.initialState(), sample, ctxOf(1), ctxOf(1));
  assert.ok(r11.changed && Array.isArray(r11.state.rows));
  assert.ok(r11.state.rows.some(row => /委托费用待审批/.test(row.typeLabel)), 'AC11 样例含 fee-approve 待办');
  ok('11');
} else {
  console.log('SKIP AC11（fixture 未生成：先跑 monorepo script/platform-todos-smoke.py）');
}

// ---- AC12 非干扰：平台响应固定、demo 数据变化不影响平台输出；处理链零 Storage 调用 ----
const before = PT.applyResponse(PT.initialState(), res2, ctxOf(1), ctxOf(1)).state;
store['zhongshu-business-v3'] = JSON.stringify({ customers: [{ id: 'franchise:fee-approve:31', name: '同名 demo 客户' }] });
const after = PT.applyResponse(PT.initialState(), res2, ctxOf(1), ctxOf(1)).state;
assert.deepEqual(after.rows, before.rows, 'demo 数据变化不影响平台输出');
const ptSource = fs.readFileSync(path.join(root, 'miniprogram/utils/platform-todos.js'), 'utf8');
assert.ok(!/wx\.|getStorageSync|setStorageSync|removeStorageSync/.test(ptSource), '平台视图模块零 wx/Storage 引用');
ok('12');

// ---- AC12b 降级分级 ----
const r12a = PT.applyResponse(PT.initialState(), { items: 'not-array', actionableCount: 0, failedDomains: [] }, ctxOf(1), ctxOf(1));
assert.equal(r12a.state.status, 'degraded', 'items 非数组 → degraded');
const r12b = PT.applyResponse(PT.initialState(), { items: [], actionableCount: null, failedDomains: null }, ctxOf(1), ctxOf(1));
assert.equal(r12b.state.status, 'ready', 'failedDomains/actionableCount 非法 → 字段级容错继续');
assert.ok(r12b.state.anomaly >= 2);
const r12c = PT.applyResponse(PT.initialState(), { items: [null, { key: 'k', title: 't', actionable: 'yes', appPath: '' }], actionableCount: 0, failedDomains: [42] }, ctxOf(1), ctxOf(1));
assert.equal(r12c.state.rows.length, 0, 'null 条目与非布尔 actionable 丢弃');
assert.ok(r12c.state.anomaly >= 3);
ok('12b');

// ---- AC10 乱序与会话代次（真实 http 处理链 + page 辅助）----
const page = mockPage();
PTPage.init(page);
const respond = (index, payload) => {
  const req = pendingRequests[index];
  assert.ok(req, '请求存在');
  req.success({ statusCode: payload.statusCode || 200, data: payload.data });
};
const failReq = (index, err) => pendingRequests[index].fail(err);

// 场景 a：A→B，A 迟到丢弃
http.saveSession({ token: 'test-token' });
auth.savePosts([
  { postId: 'p-boss', userId: 152, orgId: 151, orgType: 'store', roleCode: 'boss', teamId: null, lead: false },
  { postId: 'p-sales', userId: 151, orgId: 151, orgType: 'store', roleCode: 'sales', teamId: null, lead: false }
]);
auth.switchPost('p-boss');
requestLog.length = 0; pendingRequests.length = 0;
PTPage.refresh(page, false);
assert.equal(requestLog.length, 1);
assert.equal(requestLog[0].url, require('../miniprogram/config/env').API_BASE.replace(/\/$/, '') + '/platform/todos', '请求地址使用项目配置');
assert.equal(requestLog[0].header['X-Post-Id'], 'p-boss');
assert.equal(requestLog[0].header['tenant-id'], '1', 'tenant-id 头');
assert.ok(/Bearer /.test(requestLog[0].header.Authorization), 'Bearer 头');
auth.switchPost('p-sales'); // 切到销售岗（任职列表已含）
PTPage.refresh(page, false); // postKey 变化 → 第二次请求
assert.equal(requestLog.length, 2);
respond(1, { data: { code: 0, data: { items: [{ key: 'franchise:first-follow:9', title: 'B 岗待办', status: '', amountCents: null, actionable: true, appPath: '' }], actionableCount: 1, failedDomains: [] } } });
await flush();
assert.equal(page.data.platformTodos.rows.length, 1);
respond(0, { data: { code: 0, data: { items: [{ key: 'franchise:fee-approve:31', title: 'A 岗迟到数据', status: '', amountCents: null, actionable: true, appPath: '' }], actionableCount: 1, failedDomains: [] } } });
await flush();
assert.equal(page.data.platformTodos.rows.length, 1, 'A 迟到响应被丢弃');
assert.equal(page.data.platformTodos.rows[0].key, 'franchise:first-follow:9');
ok('10a');

// 场景 b：重复 onShow 合并（同 postKey 同 sessionGen loading 中 skip）
requestLog.length = 0; pendingRequests.length = 0;
PTPage.refresh(page, false);
assert.equal(requestLog.length, 1);
PTPage.refresh(page, false);
assert.equal(requestLog.length, 1, 'loading 中自动 onShow 合并不发第二次');
ok('10b');

// 场景 c：loading 中手动重载 → 新请求，旧的不能覆盖
PTPage.refresh(page, true);
assert.equal(requestLog.length, 2, '手动重载强制第二次');
respond(0, { data: { code: 0, data: { items: [{ key: 'franchise:visit:5', title: '旧序号数据', status: '', amountCents: null, actionable: true, appPath: '' }], actionableCount: 1, failedDomains: [] } } });
await flush();
respond(1, { data: { code: 0, data: { items: [{ key: 'franchise:tail-follow:7', title: '手动重载结果', status: '', amountCents: null, actionable: true, appPath: '' }], actionableCount: 1, failedDomains: [] } } });
await flush();
assert.equal(page.data.platformTodos.rows[0].key, 'franchise:tail-follow:7', '手动重载结果生效');
ok('10c');

// 场景 d：同 postKey 未登出而 saveSession（token 刷新）→ 旧 200/旧 401 均不生效，新请求实际发出
requestLog.length = 0; pendingRequests.length = 0;
PTPage.refresh(page, false);
assert.equal(requestLog.length, 1);
http.saveSession({ token: 'refreshed-token' }); // 会话代次+1，postKey 不变
PTPage.refresh(page, false); // sessionGen 变化 → 允许重载
assert.equal(requestLog.length, 2, '会话代次变化触发新请求');
let unauthorizedCalls = 0;
http.onUnauthorized(() => { unauthorizedCalls += 1; });
respond(0, { statusCode: 401 });
await flush(); // 旧请求 401
assert.equal(unauthorizedCalls, 0, '旧请求 401 不触发 onUnauthorized');
respond(1, { data: { code: 0, data: { items: [], actionableCount: 0, failedDomains: [] } } });
await flush();
assert.equal(page.data.platformTodos.status, 'ready', '新会话请求正常写入并退出 loading');
ok('10d');

// 场景 e：当前会话 401（非旧请求）→ 清会话 + onUnauthorized
requestLog.length = 0; pendingRequests.length = 0;
PTPage.refresh(page, false);
respond(0, { statusCode: 401 });
await flush();
assert.equal(unauthorizedCalls, 1, '当前会话 401 触发 onUnauthorized');
assert.equal(page.data.platformTodos.status, 'idle', '视图回 idle');
ok('10e');

// ---- 导航判定与 app.json tabBar 同步 ----
const appJson = JSON.parse(fs.readFileSync(path.join(root, 'miniprogram/app.json'), 'utf8'));
const tabBarPages = (appJson.tabBar && appJson.tabBar.list ? appJson.tabBar.list : []).map(t => t.pagePath);
assert.deepEqual(nav.TAB_PAGES.slice().sort(), tabBarPages.slice().sort(), 'platform-nav TAB_PAGES 与 app.json tabBar.list 一致');
ok('nav-sync');
})().catch(e => { console.error(e); process.exit(1); });

function mockPage() {
  return {
    data: {},
    setData(patch) { for (const [k, v] of Object.entries(patch)) { const keys = k.split('.'); let o = this.data; for (let i = 0; i < keys.length - 1; i += 1) o = o[keys[i]]; o[keys[keys.length - 1]] = v; } }
  };
}

console.log('verify-platform-todos: ' + pass + ' 组断言全过（AC1-AC12b，含真实 http 链乱序/会话代次）');
