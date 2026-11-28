const dash = '—';
const Engineering = require('./platform-engineering');
const CRM = require('./platform-crm');

function numberText(value) {
  return Number(value || 0).toLocaleString('en-US', { maximumFractionDigits: 2 });
}

function moneyText(cents) {
  if (cents == null) return dash;
  const amount = Number(cents) / 100;
  return (amount < 0 ? '-' : '') + '¥' + Math.abs(amount).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
}

const compositionLabels = {
  engineering: '工程回款', design: '设计服务', payment: '实际付款',
  refund: '客户退款', 'expense-refund': '支出退回'
};
const agingLabels = {
  notDue: '未到期', d1_30: '逾期 1-30 天', d31_60: '逾期 31-60 天',
  d61_90: '逾期 61-90 天', d90Plus: '逾期 90 天以上', undated: '未约定日期'
};

function ratioText(value) {
  if (value == null || !Number.isFinite(Number(value))) return dash;
  return (Number(value) * 100).toLocaleString('en-US', { maximumFractionDigits: 1 }) + '%';
}

function operatingBusiness(operating, period) {
  const available = !!operating;
  const trend = available && Array.isArray(operating.trend) ? operating.trend : [];
  const current = trend.find(row => row.period === period) || null;
  const totals = available && operating.totals || {};
  const summary = [
    { key: 'income', label: '本月实收', value: current ? moneyText(current.incomeCents) : dash, note: '已登记回款' },
    { key: 'paid', label: '本月实付', value: current ? moneyText(current.paidCents) : dash, note: '已核实付款及退款净额' },
    { key: 'net', label: '本月净流入', value: current ? moneyText(current.netCents) : dash, note: '实收减实付' },
    { key: 'receivable', label: '待收余额', value: available ? moneyText(totals.receivableCents) : dash, note: '工程收款约定未收' },
    { key: 'payable', label: '待付余额', value: available ? moneyText(totals.payableOutstandingCents) : dash, note: '应付扣除有效付款' },
    { key: 'collection-rate', label: '累计回款率', value: available ? ratioText(totals.collectionRate) : dash, note: '实收占收款约定' },
    { key: 'coverage', label: '资金覆盖', value: available && totals.coverage != null ? Number(totals.coverage).toLocaleString('en-US', { maximumFractionDigits: 2 }) + '倍' : dash, note: '近 6 月净流入 / 待付' }
  ];
  const composition = key => (available && Array.isArray(operating[key]) ? operating[key] : []).map((row, index) => ({
    key: String(row.kind || index), label: compositionLabels[row.kind] || String(row.kind || '其他'), value: moneyText(row.cents)
  }));
  const receivableAging = available && operating.receivableAging || {};
  const payableAging = available && operating.payableAging || {};
  return {
    available,
    message: available ? '' : '经营分析暂不可用，请稍后重试',
    summary,
    trend: trend.map(row => ({
      period: String(row.period || ''), label: periodLabel(row.period),
      income: moneyText(row.incomeCents), paid: moneyText(row.paidCents), net: moneyText(row.netCents)
    })),
    incomeComposition: composition('incomeComposition'),
    paidComposition: composition('paidComposition'),
    aging: Object.keys(agingLabels).map(key => ({
      key, label: agingLabels[key], receivable: moneyText(receivableAging[key] || 0), payable: moneyText(payableAging[key] || 0)
    }))
  };
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
      loading: source.projectsStatus === 'loading',
      message: source.projectsStatus === 'loading' ? '项目概览加载中…' : '项目概览暂不可用',
      counts: Engineering.homeStats([], false),
      metrics: Engineering.homeStats([], false).map(row => ({ ...row, key: row.filter, unit: '' })),
      panels: [],
      sites: []
    };
  }
  const cards = Engineering.listCards(source.projects || []);
  const counts = Engineering.homeStats(cards);
  return {
    available: true,
    message: cards.length ? '' : '当前没有工程项目',
    counts,
    metrics: counts.map(row => ({ ...row, key: row.filter, unit: '个' })),
    panels: counts.map(stat => {
      const rows = cards.filter(card => Engineering.statusMatches(card.statusKey, stat.filter));
      return {
        key: stat.filter, title: stat.label === '在建' ? '在建项目' : stat.label === '全部项目' ? stat.label : stat.label + '项目',
        rows: rows.slice(0, 8).map(card => ({ key: String(card.id), name: card.name, detail: card.statusText, url: card.url })),
        total: rows.length, hasMore: rows.length > 8,
        url: '/pages/build/projects/index' + (stat.filter === '全部' ? '' : '?filter=' + encodeURIComponent(stat.filter)),
        emptyText: '暂无' + (stat.label === '全部项目' ? '工程项目' : stat.label + '项目')
      };
    }),
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

function businessView(source) {
  const available = source.businessStatus === 'ready';
  const definitions = [
    { key: 'due', label: '今日待跟进', url: '/pages/customers/index?focus=due', emptyText: '暂无待跟进客户' },
    { key: 'appointments', label: '预约跟进', url: '/pages/customers/index?focus=appointments', emptyText: '暂无预约跟进客户' },
    { key: 'commission', label: '委托', url: '/pages/workflow/commission/index?view=projects', emptyText: '暂无委托记录' },
    { key: 'all', label: '全部客户', url: '/pages/customers/index', emptyText: '暂无客户记录' }
  ];
  if (!available) return {
    available: false, loading: source.businessStatus === 'loading', panels: [],
    message: source.businessStatus === 'loading' ? '业务指标加载中…' : '业务指标暂不可用，请重试',
    metrics: definitions.map(row => ({ key: row.key, label: row.label, value: dash, unit: '' }))
  };
  const summary = CRM.homeSummary(source.customers, source.commissions, source.post, source.today);
  const customerRows = rows => rows.map(row => ({ key: row.id, name: row.name, detail: row.detail, url: row.url }));
  const groups = {
    due: summary.due, appointments: summary.upcoming, all: summary.rows
  };
  return {
    available: true, message: '', metrics: summary.metrics,
    panels: definitions.map(definition => {
      const rows = definition.key === 'commission'
        ? summary.monthRows.slice(0, 8).map(row => ({
          key: String(row.id), name: row.customerName || row.name || '设计委托',
          detail: row.statusText || row.status || '设计委托',
          amount: moneyText(row.totalCents != null ? row.totalCents : row.amountCents != null ? row.amountCents : row.commissionCents || 0),
          url: '/pages/workflow/commission/index?view=progress&commissionId=' + encodeURIComponent(row.id)
        })) : customerRows(groups[definition.key].slice(0, 8));
      const total = definition.key === 'commission' ? summary.monthRows.length : groups[definition.key].length;
      return { ...definition, title: definition.label, rows, total, hasMore: total > 8 };
    })
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
  const six = [
    { key: 'collections', url: '/pages/admin/brief/index?period=' + encodeURIComponent(period), label: '本月回款', value: business.collections, unit: business.collections === dash ? '' : '元', sub: '财务实收' },
    { key: 'contracts', action: 'business', label: '本月签约', value: business.contracts, unit: business.contracts === dash ? '' : '元', sub: '已签合同' },
    { key: 'effective-customers', action: 'business', label: '有效客户', value: business.customers, unit: business.customers === dash ? '' : '人', sub: '本月有效' },
    { key: 'completed-nodes', action: 'engineering', label: '完成节点', value: engineeringMetrics.completed, unit: engineeringMetrics.completed === dash ? '' : '个', sub: '本月完成' },
    { key: 'qualified-deliveries', action: 'engineering', label: '合格到货', value: engineeringMetrics.deliveries, unit: engineeringMetrics.deliveries === dash ? '' : '批', sub: '本月登记' },
    { key: 'finance-registrations', url: '/pages/admin/tasks/index?view=team&period=' + encodeURIComponent(period), label: '财务登记', value: financeRegistrations, unit: financeRegistrations === dash ? '' : '笔', sub: '本月登记' }
  ];
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
    business: operatingBusiness(operating, period),
    home: {
      six,
      cards: six.slice(0, 4).map(row => ({ ...row, label: row.label.replace(/^本月/, ''), sub: '' })),
      extraMetrics: six.slice(4),
      business: {
        ...businessView(source),
        stages: [
          { label: '本月回款', value: business.collections, unit: business.collections === dash ? '' : '元' },
          { label: '本月签约', value: business.contracts, unit: business.contracts === dash ? '' : '元' },
          { label: '有效客户', value: business.customers, unit: business.customers === dash ? '' : '人' }
        ],
        appointments: { label: '预约跟进', value: dash, unit: '', url: '/pages/customers/index?focus=appointments' },
        acquisition: { label: '内容获客', value: business.activity, unit: business.activity === dash ? '' : '条', url: '/pages/business/index' },
        notes: []
      },
      engineering: projectOverview
    }
  };
}

module.exports = { view, moneyText, periodLabel, operatingBusiness };
