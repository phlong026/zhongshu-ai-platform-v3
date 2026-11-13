/**
 * 老板经营简报 · 纯函数整形（零 wx / Storage / 网络）。
 *
 * 数据源（端点已存在，本批只做小程序接线）：
 * - 当月=实时：GET /franchise/performance/operating/analytics?period=YYYY-MM（不落快照）
 * - 历史月可归档：POST /franchise/performance/operating/snapshot（服务端仅接受过去月，1_116_000_008）
 * - 快照列表：GET /franchise/performance/operating/snapshots?period=（version/supersedes/asOf/note）
 *
 * 口径：analytics 金额一律为「分」，展示统一换元（¥ 前缀两位小数），与 Web /fr/dashboard 一致；
 * 账龄六桶固定顺序与 Web AGING_ORDER 对齐（未知桶忽略），付费构成负值原样保留（支出退回=冲减）。
 */
const DASH = '—';

const AGING_ORDER = ['notDue', 'd1_30', 'd31_60', 'd61_90', 'd90Plus', 'undated'];
const AGING_LABEL = {
  notDue: '未到期',
  d1_30: '逾期 1-30 天',
  d31_60: '逾期 31-60 天',
  d61_90: '逾期 61-90 天',
  d90Plus: '逾期 90 天以上',
  undated: '未约定'
};
const INCOME_KIND_LABEL = { manual: '手工登记', design: '设计款', business: '业务回款', intercompany: '集团往来' };
const PAID_KIND_LABEL = { payment: '付款', refund: '客户退款', 'expense-refund': '支出退回' };

/** 归档截止说明（快照卡常驻文案，产品契约） */
const CUTOFF_NOTE = '归档截止说明：仅可归档已结束月份（当月为实时简报，不生成快照）；快照数据截止日=该月最后一天（asOf）；同月数据修订后再次归档将生成新版本（version+1），并以 supersedes 指向上一版本。';

const validPeriod = period => /^\d{4}-(0[1-9]|1[0-2])$/.test(String(period || ''));

function periodLabel(period) {
  const value = String(period || '');
  if (!validPeriod(value)) return value;
  return value.slice(0, 4) + '年' + Number(value.slice(5, 7)) + '月';
}

/** 分 → 元文本（¥ 前缀、千分位、两位小数；null/非法 → 占位符，负值保留负号） */
function centsText(cents) {
  if (cents === null || cents === undefined) return DASH;
  const n = Number(cents);
  if (!Number.isFinite(n)) return DASH;
  return (n < 0 ? '-' : '') + '¥' + (Math.abs(n) / 100).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
}

/** 0~1 比率 → 百分比文本（一位小数） */
function percentText(rate) {
  if (rate === null || rate === undefined) return DASH;
  const n = Number(rate);
  if (!Number.isFinite(n)) return DASH;
  return (n * 100).toFixed(1) + '%';
}

/** 倍数文本（资金覆盖） */
function ratioText(value) {
  if (value === null || value === undefined) return DASH;
  const n = Number(value);
  if (!Number.isFinite(n)) return DASH;
  return n.toFixed(2) + '×';
}

/** 仅过去月份可归档（与后端 PerfOperatingService.snapshot 的 008 校验同口径） */
function isArchiveable(period, currentPeriod) {
  if (!validPeriod(period) || !validPeriod(currentPeriod)) return false;
  return String(period) < String(currentPeriod);
}

/** 归档按钮状态：当月禁用（实时口径），过去月可用；已有快照提示生成新版本 */
function archivePlan(input) {
  const source = input || {};
  const period = String(source.period || '');
  const currentPeriod = String(source.currentPeriod || '');
  const archiveable = isArchiveable(period, currentPeriod);
  if (!validPeriod(period)) {
    return { visible: true, disabled: true, label: '归档月度快照', reason: '月份不合法，无法归档' };
  }
  if (!archiveable) {
    return {
      visible: true,
      disabled: true,
      label: '归档月度快照',
      reason: '当月为实时简报，仅过去月份可归档'
    };
  }
  return {
    visible: true,
    disabled: false,
    label: source.hasSnapshot ? '重新归档（生成新版本）' : '归档本月快照',
    reason: ''
  };
}

/** 1_116 归档错误码 → 中文文案（未识别透传 msg） */
function archiveErrorText(err) {
  const raw = err && (err.data || err);
  const code = raw && raw.code;
  const map = {
    1116000008: '只能归档过去月份：当月为实时简报，不生成快照',
    1116000002: '数据无修订，已有快照（未生成新版本）',
    1116000000: '当前岗位无权归档经营快照',
    1116000001: '归档内容不合法，请刷新后重试'
  };
  if (map[code]) return map[code];
  return (raw && raw.msg) || (err && err.message) || '归档失败，请稍后重试';
}

function trendRowText(row, maxCents) {
  const income = Number(row.incomeCents) || 0;
  const paid = Number(row.paidCents) || 0;
  const net = Number(row.netCents) || 0;
  const width = cents => (maxCents > 0 ? Math.max(0, Math.min(100, Math.round(Math.abs(cents) / maxCents * 100))) : 0);
  const month = validPeriod(row.period) ? Number(String(row.period).slice(5, 7)) + '月' : String(row.period || '');
  return {
    key: String(row.period || ''),
    period: String(row.period || ''),
    label: month,
    incomeText: centsText(income),
    paidText: centsText(paid),
    netText: centsText(net),
    incomeWidth: width(income),
    paidWidth: width(paid),
    netTone: net < 0 ? 'negative' : ''
  };
}

/** 收支构成（收入按合计占比；支出含负值，负项不参与占比只展示金额） */
function compositionBlock(rows, labels, withPercent) {
  const list = Array.isArray(rows) ? rows : [];
  const total = list.reduce((sum, row) => sum + (Number(row.cents) || 0), 0);
  return {
    rows: list.map((row, index) => {
      const cents = Number(row.cents) || 0;
      const kind = String(row.kind || '');
      return {
        key: kind + '-' + index,
        label: labels[kind] || kind || '未分类',
        valueText: centsText(cents),
        percentText: withPercent && total > 0 && cents >= 0 ? (cents / total * 100).toFixed(1) + '%' : '',
        negative: cents < 0
      };
    }),
    totalText: centsText(total),
    empty: list.length === 0
  };
}

function agingBlock(receivableAging, payableAging) {
  const receivable = receivableAging || {};
  const payable = payableAging || {};
  const rows = AGING_ORDER.map(key => ({
    key,
    label: AGING_LABEL[key],
    receivableText: centsText(Number(receivable[key]) || 0),
    payableText: centsText(Number(payable[key]) || 0)
  }));
  const sum = buckets => AGING_ORDER.reduce((total, key) => total + (Number(buckets[key]) || 0), 0);
  return {
    rows,
    receivableTotalText: centsText(sum(receivable)),
    payableTotalText: centsText(sum(payable))
  };
}

/** 快照列表：版本倒序、supersedes 雪花 id 解析为版本号、asOf/备注/截止说明 */
function snapshotRows(list) {
  const rows = (Array.isArray(list) ? list.slice() : []).sort((a, b) => Number(b.version) - Number(a.version));
  const versionById = {};
  for (const row of rows) versionById[String(row.id)] = Number(row.version);
  return rows.map(row => {
    const supersedes = row.supersedes === null || row.supersedes === undefined ? '' : String(row.supersedes);
    return {
      key: 'snap-' + String(row.id),
      id: String(row.id),
      versionText: 'v' + (Number(row.version) || 0),
      asOfText: row.asOf ? String(row.asOf) : DASH,
      relationText: supersedes ? ('替代 ' + (versionById[supersedes] ? 'v' + versionById[supersedes] : '#' + supersedes)) : '首版',
      noteText: String(row.note || '').trim() || DASH
    };
  });
}

function snapshotSection(input) {
  const source = input || {};
  const status = source.snapshotsStatus || 'idle';
  const rows = snapshotRows(source.snapshots);
  return {
    status,
    message: status === 'error' ? '快照列表加载失败，可下拉重试' : (status === 'loading' ? '快照加载中…' : ''),
    rows,
    empty: status === 'ready' && rows.length === 0,
    countText: status === 'ready' ? '共 ' + rows.length + ' 个版本' : '',
    cutoffNote: CUTOFF_NOTE
  };
}

/** 指标卡六项（与 Web /fr/dashboard 同字段，金额按元展示） */
function metricCards(operating, period) {
  const trend = Array.isArray(operating.trend) ? operating.trend : [];
  const current = trend.find(row => String(row.period) === String(period)) || { incomeCents: 0, paidCents: 0, netCents: 0 };
  const totals = operating.totals || {};
  return [
    { key: 'net', label: '当月净流入', value: centsText(current.netCents), sub: '实收 − 支出' },
    { key: 'receivable', label: '应收余额', value: centsText(totals.receivableCents), sub: '工程分期未收' },
    { key: 'payable', label: '应付余额', value: centsText(totals.payableOutstandingCents), sub: '未付应付' },
    { key: 'collection', label: '回款率', value: percentText(totals.collectionRate), sub: '已收 ÷ 应收' },
    { key: 'coverage', label: '资金覆盖', value: ratioText(totals.coverage), sub: '近6月净流入 ÷ 未付应付' },
    { key: 'net6', label: '近 6 月净流入', value: centsText(totals.net6MonthsCents), sub: '趋势合计' }
  ];
}

/**
 * 简报视图模型。
 * input: { period, currentPeriod, operating, snapshots, snapshotsStatus }
 * operating=null 表示 analytics 拉取失败（降级态：不伪造指标，保留月份/归档规则/快照说明）。
 */
function briefView(input) {
  const source = input || {};
  const period = String(source.period || '');
  const currentPeriod = String(source.currentPeriod || '');
  const operating = source.operating || null;
  const mode = period === currentPeriod ? 'live' : (isArchiveable(period, currentPeriod) ? 'archive' : 'future');
  const snapshots = snapshotSection(source);
  const view = {
    period,
    periodLabel: periodLabel(period),
    mode,
    modeText: mode === 'live' ? '当月 · 实时简报' : (mode === 'archive' ? '历史月 · 可归档' : '未开始月份'),
    modeNote: mode === 'live'
      ? '当月为实时口径：直读经营分析，不生成快照。'
      : (mode === 'archive'
        ? '历史月为归档口径：可按需归档快照，数据修订后再次归档生成新版本。'
        : '该月份尚未开始，暂无经营数据。'),
    degraded: !operating,
    degradedText: operating ? '' : '经营数据加载失败，请稍后重试',
    metrics: [],
    trend: [],
    income: compositionBlock([], INCOME_KIND_LABEL, true),
    paid: compositionBlock([], PAID_KIND_LABEL, false),
    aging: agingBlock({}, {}),
    archive: archivePlan({ period, currentPeriod, hasSnapshot: snapshots.rows.length > 0 }),
    snapshots
  };
  if (!operating) return view;

  const trend = Array.isArray(operating.trend) ? operating.trend : [];
  const maxCents = trend.reduce((max, row) => Math.max(max, Math.abs(Number(row.incomeCents) || 0), Math.abs(Number(row.paidCents) || 0)), 0);
  view.metrics = metricCards(operating, period);
  view.trend = trend.map(row => trendRowText(row, maxCents));
  view.income = compositionBlock(operating.incomeComposition, INCOME_KIND_LABEL, true);
  view.paid = compositionBlock(operating.paidComposition, PAID_KIND_LABEL, false);
  view.aging = agingBlock(operating.receivableAging, operating.payableAging);
  return view;
}

module.exports = {
  DASH,
  AGING_ORDER,
  AGING_LABEL,
  CUTOFF_NOTE,
  validPeriod,
  periodLabel,
  centsText,
  percentText,
  ratioText,
  isArchiveable,
  archivePlan,
  archiveErrorText,
  snapshotRows,
  briefView
};
