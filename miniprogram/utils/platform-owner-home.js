const dash = '—';
const Engineering = require('./platform-engineering');

function numberText(value) {
  return Number(value || 0).toLocaleString('en-US', { maximumFractionDigits: 2 });
}

function moneyText(cents) {
  return cents == null ? dash : '¥' + (Number(cents) / 100).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
}

function metric(summary, department, key) {
  if (!summary) return null;
  const departments = summary.departments || {};
  return Number((departments[department] || {})[key] || 0);
}

function count(summary, department, key) {
  const raw = metric(summary, department, key);
  return raw == null ? dash : Math.round(raw / 100 * 100) / 100;
}

function money(summary, department, key) {
  const raw = metric(summary, department, key);
  return raw == null ? dash : numberText(raw / 100);
}

function currentTrend(operating, period) {
  if (!operating) return null;
  const rows = operating.trend || [];
  return rows.find(row => row.period === period) || {
    incomeCents: 0, paidCents: 0, netCents: 0
  };
}

function projectsView(source) {
  const available = source.projectsStatus === 'ready';
  if (!available) {
    return {
      available: false,
      message: source.projectsStatus === 'loading' ? '项目概览加载中…' : '项目概览暂不可用',
      counts: ['在建', '待开工', '待交付'].map(label => ({ label, value: dash })),
      sites: []
    };
  }
  const cards = Engineering.listCards(source.projects || []);
  const count = (statusKey, label) => ({ label, value: cards.filter(card => card.statusKey === statusKey).length });
  return {
    available: true,
    message: cards.length ? '' : '当前没有工程项目',
    counts: [count('IN_PROGRESS', '在建'), count('PENDING_START', '待开工'), count('READY_DELIVER', '待交付')],
    sites: cards.filter(card => !['DELIVERED', 'ARCHIVED'].includes(card.statusKey)).slice(0, 3).map(card => ({
      key: 'project-' + card.id,
      name: card.name,
      stage: card.statusText,
      date: card.actualStart,
      image: card.image,
      url: card.url
    }))
  };
}

function periodLabel(period) {
  const parts = String(period || '').split('-');
  return parts.length === 2 ? parts[0] + '年' + Number(parts[1]) + '月' : String(period || '');
}

function view(input) {
  const source = input || {};
  const summary = source.summary || null;
  const operating = source.operating || null;
  const period = source.period || '';
  const post = source.post || {};
  const trend = currentTrend(operating, period);
  const totals = operating && operating.totals || null;
  const received = trend ? trend.incomeCents : null;
  const paid = trend ? trend.paidCents : null;
  const business = {
    collections: money(summary, 'business', 'auto:collectionsAmount'),
    contracts: money(summary, 'business', 'auto:contract'),
    customers: count(summary, 'business', 'metric:effectiveCustomers'),
    activity: count(summary, 'business', 'metric:activityTotal')
  };
  const engineeringMetrics = {
    completed: count(summary, 'engineering', 'auto:completedNodes'),
    deliveries: count(summary, 'engineering', 'auto:qualifiedDeliveries')
  };
  const financeRegistrations = count(summary, 'finance', 'auto:financeRegistrations');
  const projectOverview = projectsView(source);
  return {
    name: source.name || '',
    company: post.orgName || '',
    date: periodLabel(period),
    period,
    periodLabel: periodLabel(period),
    sample: false,
    ledger: {
      inTotal: moneyText(received),
      payTotal: moneyText(paid),
      // 后端 totals 实际字段=receivableCents（PerfOperatingService.analytics）；旧键仅兼容既有 mock
      receivableTotal: moneyText(totals && (totals.receivableCents !== undefined ? totals.receivableCents : totals.receivableOutstandingCents)),
      payableTotal: moneyText(totals && totals.payableOutstandingCents)
    },
    home: {
      six: [
        { key: 'collections', action: 'receivables', label: '本月回款', value: business.collections, unit: business.collections === dash ? '' : '元', sub: '财务实收' },
        { key: 'contracts', action: 'business', label: '本月签约', value: business.contracts, unit: business.contracts === dash ? '' : '元', sub: '已签合同' },
        { key: 'effective-customers', action: 'business', label: '有效客户', value: business.customers, unit: business.customers === dash ? '' : '人', sub: '本月有效' },
        { key: 'completed-nodes', action: 'engineering', label: '完成节点', value: engineeringMetrics.completed, unit: engineeringMetrics.completed === dash ? '' : '个', sub: '本月完成' },
        { key: 'qualified-deliveries', action: 'engineering', label: '合格到货', value: engineeringMetrics.deliveries, unit: engineeringMetrics.deliveries === dash ? '' : '批', sub: '本月登记' },
        { key: 'finance-registrations', action: 'financeProgress', label: '财务登记', value: financeRegistrations, unit: financeRegistrations === dash ? '' : '笔', sub: '本月登记' }
      ],
      business: {
        stages: [
          { label: '本月回款', value: business.collections, unit: business.collections === dash ? '' : '元' },
          { label: '本月签约', value: business.contracts, unit: business.contracts === dash ? '' : '元' },
          { label: '有效客户', value: business.customers, unit: business.customers === dash ? '' : '人' }
        ],
        appointments: { label: '预约到店', value: dash, unit: '', url: '/pages/customers/index?focus=appointments' },
        acquisition: { label: '内容获客', value: business.activity, unit: business.activity === dash ? '' : '条', url: '/pages/business/index' },
        notes: []
      },
      engineering: projectOverview
    }
  };
}

module.exports = { view, moneyText, periodLabel };
