const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'miniprogram/pages/business/index.js'), 'utf8');
// 服务端跟进汇总（/franchise/customers/follow-summary）的测试返回：与客户列表分栏同口径。
const summaryOf = customers => ({ total: customers.length, due: 3, appointments: 1, overdue: 1,
  following: customers.filter(row => row.status === '跟进中').length, lost: 0, paused: 0 });

function deferred() {
  let resolve;
  const promise = new Promise(yes => { resolve = yes; });
  return { promise, resolve };
}

function rows(count, make) {
  return Array.from({ length: count }, (_, index) => make(index));
}

async function waitFor(check, message) {
  for (let index = 0; index < 100; index += 1) {
    await new Promise(resolve => setImmediate(resolve));
    if (check()) return;
  }
  assert.fail(message);
}

function loadBusiness({ customerPage, customerSummary, commissionPage, session }) {
  let definition;
  const http = {
    token: () => 'token',
    get:async()=>({nickname:'业务同事'}),
    sessionGeneration: () => session.generation
  };
  const auth = { currentApiPost: () => session.post };
  const CRM = {
    loadSummaryRows: require('../miniprogram/utils/platform-crm').loadSummaryRows,
    homeSummary: require('../miniprogram/utils/platform-crm').homeSummary,
    today: () => '2026-11-14',
    can: () => true,
    customerRow: row => row,
    tabFilter: () => [],
    toDate: value => value,
    errorMessage: error => error.message || String(error)
  };
  vm.runInNewContext(source, {
    Page: value => { definition = value; },
    require: request => {
      if (request === '../../utils/dashboard') return { createDashboard: (_tab, config) => config };
      if (request === '../../utils/platform-todos-page') return { init() {}, refresh() {} };
      if (request === '../../api/http') return http;
      if (request === '../../utils/platform-crm') return CRM;
      if (request === '../../api/franchise/crm') return { customers: { page: customerPage, followSummary: customerSummary } };
      if (request === '../../api/franchise/commission') return { commissions: { page: commissionPage } };
      if (request === '../../api/franchise/content') return {
        growth: { summary: async () => ({ publications: { published: 30, failed: 10 }, leads: { INVALID: 5, CONVERTED: 111 },
          converted: 80, consultations: 200, publishedCount: 30, linkedCustomers: 80, signedCustomers: 12, netPaidCustomers: 6 }) },
        drafts: { works: async () => [] }
      };
      if (request === '../../api/auth') return auth;
      if (request === '../../utils/platform-content') return { rowsOf: value => value };
      if (request === '../../utils/account') return { load: () => ({ profile: { name: '' } }) };
      throw new Error('未声明的测试依赖：' + request);
    },
    wx: {},
    console
  }, { filename: 'miniprogram/pages/business/index.js' });
  return {
    ...definition,
    data: structuredClone(definition.data),
    setData(patch) { Object.assign(this.data, patch); }
  };
}

function paged(all, calls, failure) {
  return async (pageNo, pageSize) => {
    calls.push([pageNo, pageSize]);
    if (failure && pageNo === failure.page) throw new Error(failure.message);
    const start = (pageNo - 1) * pageSize;
    return { list: all.slice(start, start + pageSize), total: all.length };
  };
}

(async () => {
  const customers = rows(135, index => ({ id: 'c' + index, name: '客户' + index, status: index < 120 ? '跟进中' : '已登记' }));
  const commissions = rows(125, index => ({ id: 'm' + index, createTime: '2026-11-' + String(index % 14 + 1).padStart(2, '0'), totalCents: 10000 }));
  const customerCalls = [], commissionCalls = [], summaryCalls = [];
  const session = { generation: 1, post: { postId: 'boss', orgId: 'store', roleCode: 'boss' } };
  const customerSummary = async () => { summaryCalls.push(1); return summaryOf(customers); };
  const page = loadBusiness({
    customerPage: paged(customers, customerCalls),
    customerSummary,
    commissionPage: paged(commissions, commissionCalls),
    session
  });
  page.loadPlatformHome();
  await waitFor(() => page.data.platformStatus === 'ready', '汇总应完成');
  assert.deepEqual(summaryCalls, [1], '客户数字读一次服务端跟进汇总');
  assert.deepEqual(customerCalls, [[1, 20]], '客户名单只取今日待跟进的第一页，不再把客户全量分页拉回来');
  assert.deepEqual(commissionCalls, [[1, 100], [2, 100]], '委托汇总应按100条分页拉完');
  assert.equal(page.data.bossBiz.following, 120, '跟进数来自服务端汇总');
  assert.equal(page.data.bossBiz.due, 3, '待跟进数来自服务端汇总');
  assert.equal(page.data.bossBiz.appointments, 1, '预约跟进数来自服务端汇总');
  assert.equal(page.data.bossBiz.staleCount, 1, '逾期数来自服务端汇总');
  assert.equal(page.data.bossBiz.leads, 200);
  assert.equal(page.data.bossBiz.signed, 12);
  assert.deepEqual(Array.from(page.data.bossBiz.funnel.slice(3), row => row.label), ['关联客户已签约', '关联客户有净实收']);
  assert.deepEqual(Array.from(page.data.bossBiz.funnel, row => row.value), [30, 200, 80, 12, 6]);
  assert.equal(page.data.bossBiz.commissionCount, 125, '本月委托数不得只算前50条');
  assert.equal(page.data.bossBiz.commission.value, '1.25', '本月委托金额应基于完整结果');
  assert.equal(page.data.bossBiz.commission.unit, '万元');
  assert.equal(page.data.platCustomers.length, 20, '名单只保留今日待跟进的前20位');
  assert.equal(page.data.platCommissions.length, 50, '视图不得承载全量委托');

  const failedSummary = loadBusiness({
    customerPage: paged(customers, []),
    customerSummary: async () => { throw new Error('跟进汇总读取失败'); },
    commissionPage: paged(commissions, []),
    session: { generation: 1, post: session.post }
  });
  failedSummary.loadPlatformHome();
  await waitFor(() => failedSummary.data.platformStatus === 'degraded', '跟进汇总失败应进入明确降级态');
  assert.match(failedSummary.data.platformError, /跟进汇总读取失败/);
  assert.equal(failedSummary.data.bossBiz, null, '跟进汇总失败不得展示假0数字');

  const failed = loadBusiness({
    customerPage: paged(customers, []),
    customerSummary,
    commissionPage: paged(commissions, [], { page: 2, message: '委托第2页失败' }),
    session: { generation: 1, post: session.post }
  });
  failed.loadPlatformHome();
  await waitFor(() => failed.data.platformStatus === 'degraded', '分页失败应进入明确降级态');
  assert.match(failed.data.platformError, /委托第2页失败/);
  assert.equal(failed.data.bossBiz, null, '分页失败不得展示不完整的假0汇总');

  const anomalies = [
    {
      name: '超出上限',
      commissionPage: async () => ({ list: [], total: 10001 }),
      error: /超过10000条/
    },
    {
      name: '分页空洞',
      commissionPage: async pageNo => ({ list: pageNo === 1 ? commissions.slice(0, 100) : [], total: 101 }),
      error: /空洞/
    },
    {
      name: '总数变化',
      commissionPage: async pageNo => ({ list: pageNo === 1 ? commissions.slice(0, 100) : commissions.slice(100, 101), total: pageNo === 1 ? 101 : 102 }),
      error: /总数变化/
    }
  ];
  for (const anomaly of anomalies) {
    const unsafe = loadBusiness({
      customerPage: paged(customers, []),
      customerSummary,
      commissionPage: anomaly.commissionPage,
      session: { generation: 1, post: session.post }
    });
    unsafe.loadPlatformHome();
    await waitFor(() => unsafe.data.platformStatus === 'degraded', anomaly.name + '应阻断汇总');
    assert.match(unsafe.data.platformError, anomaly.error, anomaly.name + '应给出明确错误');
    assert.equal(unsafe.data.bossBiz, null, anomaly.name + '不得显示假汇总');
  }

  const oldSummary = deferred();
  let oldSummaryCalls = 0;
  const lateSession = { generation: 1, post: session.post };
  const late = loadBusiness({
    customerPage: async (pageNo, pageSize) => {
      const current = lateSession.generation === 1 ? customers : customers.slice(0, 1);
      return { list: current.slice((pageNo - 1) * pageSize, pageNo * pageSize), total: current.length };
    },
    customerSummary: () => {
      oldSummaryCalls += 1;
      return lateSession.generation === 1 ? oldSummary.promise : Promise.resolve(summaryOf(customers.slice(0, 1)));
    },
    commissionPage: async (pageNo, pageSize) => {
      const current = lateSession.generation === 1 ? commissions : commissions.slice(0, 1);
      return { list: current.slice((pageNo - 1) * pageSize, pageNo * pageSize), total: current.length };
    },
    session: lateSession
  });
  late.loadPlatformHome();
  await waitFor(() => oldSummaryCalls >= 1, '旧会话应已请求跟进汇总');
  assert.equal(late.data.platformStatus, 'loading', '跟进汇总未返回前保持加载态');
  lateSession.generation = 2;
  lateSession.post = { postId: 'sales', orgId: 'store', roleCode: 'sales' };
  late.loadPlatformHome();
  await waitFor(() => late.data.platformStatus === 'ready' && late.data.isSalesHome, '新岗位汇总应先完成');
  oldSummary.resolve(summaryOf(customers));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(late.data.isSalesHome, true, '切岗后迟到的旧汇总不得覆盖新岗位');
  assert.equal(late.data.customerCount, 1, '切岗后必须保留新会话数据');
  assert.equal(late.data.platCustomers.length, 1, '切岗后必须保留新会话名单');

  console.log('业务首页汇总：服务端跟进汇总、委托完整分页、失败降级与切岗迟到守卫通过');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
