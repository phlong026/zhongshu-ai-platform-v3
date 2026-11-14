const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const OwnerHome = require('../miniprogram/utils/platform-owner-home');

const period = '2026-11';
const view = OwnerHome.view({
  period,
  post: { roleCode: 'boss', orgName: '联调门店' },
  summary: { departments: {
    business: { 'auto:collectionsAmount': 1250000, 'auto:contract': 3000000, 'metric:effectiveCustomers': 400, 'metric:activityTotal': 600 },
    engineering: { 'auto:completedNodes': 300, 'auto:qualifiedDeliveries': 200 },
    finance: { 'auto:financeRegistrations': 500 }
  } },
  operating: {
    trend: [
      { period: '2026-10', incomeCents: 900000, paidCents: 400000, netCents: 500000 },
      { period, incomeCents: 1250000, paidCents: 250000, netCents: 1000000 }
    ],
    incomeComposition: [{ kind: 'engineering', cents: 1250000 }],
    paidComposition: [{ kind: 'payment', cents: 300000 }, { kind: 'expense-refund', cents: -50000 }],
    totals: { receivableCents: 500000, payableOutstandingCents: 250000, collectionRate: 0.714, net6MonthsCents: 1500000, coverage: 6 },
    receivableAging: { notDue: 100000, d1_30: 200000, d31_60: 0, d61_90: 0, d90Plus: 150000, undated: 50000 },
    payableAging: { notDue: 100000, d1_30: 0, d31_60: 0, d61_90: 50000, d90Plus: 0, undated: 100000 }
  }
});

const collections = view.home.six.find(row => row.key === 'collections');
const registrations = view.home.six.find(row => row.key === 'finance-registrations');
assert.equal(collections.url, '/pages/admin/brief/index?period=2026-11', '老板本月回款应进入只读经营分析');
assert.equal(registrations.url, '/pages/admin/tasks/index?view=team&period=2026-11', '财务登记指标应进入绩效任务明细');
assert.ok(!JSON.stringify([collections, registrations]).includes('finance-desk'), '老板指标不得进入 finance-only 页面');

assert.equal(view.business.available, true);
assert.equal(view.business.summary.find(row => row.key === 'income').value, '¥12,500.00');
assert.equal(view.business.summary.find(row => row.key === 'receivable').value, '¥5,000.00');
assert.equal(view.business.summary.find(row => row.key === 'collection-rate').value, '71.4%');
assert.equal(view.business.trend.length, 2);
assert.equal(view.business.incomeComposition[0].value, '¥12,500.00');
assert.equal(view.business.paidComposition[1].value, '-¥500.00');
assert.equal(view.business.aging.find(row => row.key === 'd90Plus').receivable, '¥1,500.00');
assert.equal(view.business.aging.find(row => row.key === 'undated').payable, '¥1,000.00');

const degraded = OwnerHome.view({ period, post: { roleCode: 'boss' } });
assert.equal(degraded.business.available, false);
assert.ok(degraded.business.summary.every(row => row.value === '—'));

const ownerSource = fs.readFileSync(path.join(root, 'miniprogram/pages/admin/owner-page.js'), 'utf8');
assert.ok(!ownerSource.includes("receivables:flows('receivables')"), '老板只读入口不得映射财务应收办理页');
assert.ok(!ownerSource.includes("financeProgress:flows('receivables')"), '财务登记指标不得映射财务办理页');
assert.ok(!ownerSource.includes('/pages/admin/finance-desk/index'), '老板的同类入口都不得映射财务专属办理页');
assert.match(ownerSource, /receivableCents:\s*\(operating\.totals \|\| \{\}\)\.receivableCents/);

const financeWxml = fs.readFileSync(path.join(root, 'miniprogram/pages/admin/finance/index.wxml'), 'utf8');
for (const oldField of ['desk.business.levels', 'desk.business.alerts', 'desk.business.receivable.rows', 'desk.business.payable.rows', 'desk.business.pending']) {
  assert.ok(!financeWxml.includes(oldField), '旧经营页不得继续消费本地空字段：' + oldField);
}
for (const actualField of ['desk.business.summary', 'desk.business.trend', 'desk.business.incomeComposition', 'desk.business.paidComposition', 'desk.business.aging']) {
  assert.ok(financeWxml.includes(actualField), '经营页应渲染 analytics 映射字段：' + actualField);
}

console.log('LIVE-09 老板只读经营入口与 analytics 模板契约通过');
