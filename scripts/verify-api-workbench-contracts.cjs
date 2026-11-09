const assert = require('node:assert/strict');

const storage = new Map();
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
  removeStorageSync: key => storage.delete(key),
  switchTab() {},
  showToast() {}
};

const engineering = require('../miniprogram/utils/platform-engineering');
const ownerHome = require('../miniprogram/utils/platform-owner-home');

const cards = engineering.listCards([
  { id: '101', name: '在建项目', status: 'IN_PROGRESS', actualStartAt: '2026-11-01' },
  { id: '102', name: '待开工项目', status: 'PENDING_START' }
]);
assert.equal(cards[0].statusKey, 'IN_PROGRESS');
assert.equal(cards[0].statusText, '在建');
assert.equal(cards[0].url, '/pages/build/record/index?id=101&view=overview');
assert.equal(cards[1].progress, 0);

let pageDefinition;
global.Page = definition => { pageDefinition = definition; };
require('../miniprogram/pages/build/home/index');
const originalPage = engineering.api.projects.page;
engineering.api.projects.page = () => Promise.resolve([
  { id: '101', name: '在建项目', status: 'IN_PROGRESS', actualStartAt: '2026-11-01' },
  { id: '102', name: '待开工项目', status: 'PENDING_START' }
]);
const page = {
  ...pageDefinition,
  data: structuredClone(pageDefinition.data),
  setData(patch) { this.data = { ...this.data, ...patch }; }
};

async function main() {
  await page.loadOnline();
  assert.deepEqual(page.data.eng.stats, [
    { label: '在建', value: 1, filter: '在建' },
    { label: '待开工', value: 1, filter: '待开工' },
    { label: '待交付', value: 0, filter: '待交付' },
    { label: '全部项目', value: 2, filter: '全部' }
  ]);
  assert.equal('tasks' in page.data.eng, false, '不伪造本人待办空数组');
  assert.equal(page.data.eng.recent.url, '/pages/build/record/index?id=101&view=overview');
  assert.equal(page.data.eng.recent.date, '2026-11-01');
  assert.equal(page.data.eng.recent.progressKnown, false);

  const view = ownerHome.view({
    period: '2026-11',
    post: { orgName: '测试门店', roleCode: 'boss' },
    name: '测试老板',
    summary: { departments: {
      business: { 'auto:collectionsAmount': 1234500, 'auto:contract': 3000000, 'metric:effectiveCustomers': 300, 'metric:activityTotal': 700 },
      engineering: { 'auto:completedNodes': 400, 'auto:onTimeNodes': 300, 'auto:qualifiedDeliveries': 200 },
      finance: { 'auto:financeRegistrations': 500 }
    } },
    operating: {
      trend: [{ period: '2026-11', incomeCents: 1234500, paidCents: 234500, netCents: 1000000 }],
      totals: { receivableOutstandingCents: 500000, payableOutstandingCents: 250000 }
    }
  });
  assert.equal(view.company, '测试门店');
  assert.equal(view.name, '测试老板');
  assert.equal(view.home.six.length, 6);
  assert.equal(view.home.six[0].value, '12,345');
  assert.equal(view.ledger.inTotal, '¥12,345.00');
  assert.equal(view.ledger.payTotal, '¥2,345.00');
  assert.equal(view.ledger.receivableTotal, '¥5,000.00');
  assert.equal(view.ledger.payableTotal, '¥2,500.00');
  assert.equal(view.home.engineering.counts[0].value, '—', '未取项目时不用绩效节点冒充在建工地');
  assert.equal(view.home.business.appointments.value, '—');
  assert.equal(view.home.business.acquisition.value, 7);

  const degraded = ownerHome.view({ period: '2026-11', post: { orgName: '测试门店' } });
  assert.ok(degraded.home.six.every(row => row.value === '—'));
  assert.equal(degraded.ledger.inTotal, '—');

  const uniqueKeys = view.home.six.map(row => row.key);
  assert.equal(new Set(uniqueKeys).size, uniqueKeys.length, '老板首页六宫格 key 不重复');
  assert.ok(view.home.six.every(row => row.action || row.url), '六宫格每项有独立跳转目标');

  const missingMonth = ownerHome.view({
    period: '2026-10',
    operating: { trend: [{ period: '2026-11', incomeCents: 999999 }], totals: {} }
  });
  assert.equal(missingMonth.ledger.inTotal, '¥0.00', '不能用上一条趋势冒充选中月');

  const projectOverview = ownerHome.view({
    period: '2026-11',
    projectsStatus: 'ready',
    projects: [
      { id: 'p1', name: '在建项目', status: 'IN_PROGRESS', actualStartAt: '2026-11-01' },
      { id: 'p2', name: '待开工项目', status: 'PENDING_START' },
      { id: 'p3', name: '待交付项目', status: 'READY_DELIVER' }
    ]
  });
  assert.deepEqual(projectOverview.home.engineering.counts.map(row => row.value), [1, 1, 1]);
  assert.equal(projectOverview.home.engineering.sites.length, 3);
  assert.equal(projectOverview.home.engineering.sites[0].url, '/pages/build/record/index?id=p1&view=overview');
  assert.equal(ownerHome.view({ period: '2026-11', projectsStatus: 'error' }).home.engineering.available, false);

  const http = require('../miniprogram/api/http');
  const auth = require('../miniprogram/api/auth');
  const performance = require('../miniprogram/utils/platform-performance');
  const todosPage = require('../miniprogram/utils/platform-todos-page');
  const navigation = require('../miniprogram/utils/page-navigation');
  http.saveSession({ token: 'workbench-test', postId: '42' });
  auth.savePosts([{ postId: '42', orgId: '7', orgName: '测试门店', roleCode: 'boss' }]);
  const originals = {
    summary: performance.api.tasks.summary,
    goals: performance.api.goals.get,
    analytics: performance.api.operating.analytics,
    profile: http.get,
    refreshTodos: todosPage.refresh,
    open: navigation.open
  };
  performance.api.tasks.summary = () => Promise.resolve({ departments: {
    business: { 'auto:collectionsAmount': 1234500 }, engineering: {}, finance: {}
  } });
  performance.api.goals.get = () => Promise.resolve({});
  performance.api.operating.analytics = () => Promise.resolve({
    trend: [{ period: performance.currentPeriod(), incomeCents: 1234500, paidCents: 0 }],
    totals: { receivableOutstandingCents: 0, payableOutstandingCents: 0 }
  });
  http.get = path => path === '/platform/profile/mine' ? Promise.resolve({ nickname: '测试老板' }) : Promise.reject(new Error(path));
  let todoRefreshes = 0;
  todosPage.refresh = ownerPage => { todoRefreshes += 1; ownerPage.setData({ 'platformTodos.status': 'ready' }); };
  let opened = '';
  navigation.open = url => { opened = url; };

  const ownerDefinition = require('../miniprogram/pages/admin/owner-page')('home');
  const ownerPage = {
    ...ownerDefinition,
    data: structuredClone(ownerDefinition.data),
    setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.');
        let target = this.data;
        for (const part of parts.slice(0, -1)) target = target[part] ??= {};
        target[parts.at(-1)] = value;
      }
    }
  };
  ownerPage.onLoad({});
  ownerPage.onShow();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(todoRefreshes, 1, '老板首页 onShow 拉取平台待办');
  assert.equal(ownerPage.data.desk.name, '测试老板');
  assert.equal(ownerPage.data.desk.company, '测试门店');
  assert.equal(ownerPage.data.desk.home.six[0].value, '12,345');
  assert.equal(ownerPage.data.platformTodos.status, 'ready');
  ownerPage.month({ detail: { value: '2026-10' } });
  assert.equal(ownerPage.platPeriod, '2026-10');
  assert.ok(ownerPage.data.desk.home.six.every(row => row.value === '—'), '切月立即清除旧月汇总');
  ownerPage.go({ currentTarget: { dataset: { key: 'engineering' } } });
  assert.equal(opened, '/pages/engineering/index');

  let resolveFirst;
  let resolveSecond;
  engineering.api.projects.page = () => new Promise(resolve => {
    if (!resolveFirst) resolveFirst = resolve;
    else resolveSecond = resolve;
  });
  const racePage = {
    ...pageDefinition,
    data: structuredClone(pageDefinition.data),
    setData(patch) { this.data = { ...this.data, ...patch }; }
  };
  const firstLoad = racePage.loadOnline();
  const secondLoad = racePage.loadOnline();
  resolveSecond([{ id: 'new', name: '新请求项目', status: 'IN_PROGRESS' }]);
  await secondLoad;
  resolveFirst([{ id: 'old', name: '迟到旧项目', status: 'IN_PROGRESS' }]);
  await firstLoad;
  assert.equal(racePage.data.eng.recent.name, '新请求项目', '迟到列表响应不覆盖新页面');

  engineering.api.projects.page = () => Promise.reject(new Error('网络异常'));
  await racePage.loadOnline();
  assert.ok(racePage.data.eng, '加载失败保留工作台结构');
  assert.equal(racePage.data.eng.stats.length, 4);
  assert.match(racePage.data.error, /网络异常/);

  engineering.api.projects.page = () => Promise.reject(Object.assign(new Error('登录已失效'), { statusCode: 401 }));
  await racePage.loadOnline();
  assert.equal(racePage.data.eng.recent, null, '401 不保留旧岗位项目');
  assert.ok(racePage.data.eng.stats.every(row => row.value === '—'));

  performance.api.tasks.summary = originals.summary;
  performance.api.goals.get = originals.goals;
  performance.api.operating.analytics = originals.analytics;
  http.get = originals.profile;
  todosPage.refresh = originals.refreshTodos;
  navigation.open = originals.open;
  console.log('API 工作台字段契约检查通过');
}

main().finally(() => { engineering.api.projects.page = originalPage; });
