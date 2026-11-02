/**
 * 平台客资视图模型（阶段 1 · P1 客户接入）：客户列表/工作区/跟进/登记/转交。
 * 纯函数，零 wx/Storage 依赖——隔离验收（AC9 执行链 spy）依赖此约束。
 * 契约见 docs/backend-parity/09-阶段1-P1客户接入-需求与验收.md（V6，Codex R5 PASS）。
 * 序列化事实：nextVisitDate=[年,月,日] 数组；createTime/lastFollowAt=毫秒时间戳；
 * Long 超出 ±(2^53-1) 输出字符串（边界值本身也是字符串）——ID 一律不透明值，禁止数值化。
 */
const CRM_ROLES = ['sales', 'sales-lead', 'boss', 'owner'];
const STATUS_TEXT = { REGISTERED: '已登记', FOLLOWING: '跟进中' };

/** 五态判定归并：guest/no-post/non-role → demo；仅 token+销售家族岗位 → api（岗位缓存不作为依据） */
function pageMode(token, post) {
  return token && post && CRM_ROLES.includes(post.roleCode) ? 'api' : 'demo';
}

/** 能力判定（后端权限种子 V20261022.851：query×4 / capture,write=sales,lead / transfer=lead,boss,owner；854：saleWrite=sales,lead,boss） */
function can(post, ability) {
  if (!post) return false;
  const role = post.roleCode;
  const isBoss = role === 'boss' || role === 'owner';
  if (ability === 'query') return CRM_ROLES.includes(role);
  if (ability === 'capture' || ability === 'write') return role === 'sales' || role === 'sales-lead';
  if (ability === 'transfer') return role === 'sales-lead' || isBoss;
  if (ability === 'saleWrite') return role === 'sales' || role === 'sales-lead' || role === 'boss';
  // 854 种子：collect={sales,sales-lead,boss,finance,finance-lead}（S1-14 回款办理入口；finance 系可收款）
  if (ability === 'collect') return role === 'sales' || role === 'sales-lead' || role === 'boss' || role === 'finance' || role === 'finance-lead';
  return false;
}

/** 回款办理表单校验（S1-14）：method 必填；银行方式须流水号；receivedDate 合法或空（后端为最终裁决）。返回错误文案或 '' */
function collectError(form) {
  const f = form || {};
  const method = String(f.method || '').trim();
  if (!method) return '请选择收款方式';
  if (method === 'bank' && !String(f.transactionNo || '').trim()) return '银行收款须填流水号';
  if (f.receivedDate && !/^\d{4}-\d{2}-\d{2}$/.test(String(f.receivedDate))) return '到账日期格式不正确';
  return '';
}

/** 日期统一口径（Asia/Shanghai 墙钟）：数组/毫秒/字符串 → 'YYYY-MM-DD'；空 → '' */
function toDate(v) {
  const pad = n => (Number(n) < 10 ? '0' + Number(n) : String(n));
  if (Array.isArray(v) && v.length >= 3) return pad(v[0]) + '-' + pad(v[1]) + '-' + pad(v[2]);
  if (typeof v === 'number' && Number.isFinite(v)) return new Date(v + 8 * 3600 * 1000).toISOString().slice(0, 10);
  if (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}/.test(v)) return v.slice(0, 10);
  return '';
}

/** 'YYYY-MM-DD' → [年,月,日]（请求体 nextVisitDate 形态）；非法 → null */
function dateToArray(s) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(s || ''));
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
}

/** 客户端当日（Asia/Shanghai） */
function today() {
  return toDate(Date.now());
}

function maskPhone(phone) {
  return String(phone || '').replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2');
}

function statusText(s) {
  return STATUS_TEXT[s] || s || '';
}

function daysBetween(from, to) {
  return Math.round((Date.parse(to + 'T00:00:00') - Date.parse(from + 'T00:00:00')) / 864e5);
}

const shortDate = d => String(d || '').slice(5, 10);

/**
 * 列表行映射（Customer → 行；字段清单=09 文档 §五，与 pages/customers/index.wxml 消费对齐）。
 * ctx={today, canWrite}；phone=拨号原值、dialText 同 phone（拨打用）、phoneText=展示值。
 */
function customerRow(c, ctx) {
  const day = (ctx && ctx.today) || today();
  const canW = !!(ctx && ctx.canWrite);
  const appointment = toDate(c.nextVisitDate);
  const fresh = !c.lastFollowAt;
  const lastDate = toDate(c.lastFollowAt);
  const name = c.name || '';
  const phoneText = c.phone ? maskPhone(c.phone) : (c.wechat || '暂无联系方式');
  const searchText = [c.name, c.phone, c.city, c.summary].filter(Boolean).join(' ');
  let cue, cueTone = '', rank, sortKey;
  if (canW && appointment && appointment < day) {
    cue = '逾期 ' + daysBetween(appointment, day) + ' 天'; cueTone = 'tone-danger'; rank = 0; sortKey = appointment;
  } else if (appointment && appointment === day) {
    cue = '今天 回访'; cueTone = 'tone-warning'; rank = 1; sortKey = appointment;
  } else if (appointment && appointment > day) {
    cue = shortDate(appointment) + ' 回访'; rank = 3; sortKey = appointment;
  } else if (fresh) {
    cue = '新客户'; rank = 2; sortKey = String(c.createTime || 0);
  } else {
    const n = daysBetween(lastDate, day);
    cue = n > 0 ? n + ' 天未跟进' : '今天已跟进'; cueTone = 'customer-cue-muted'; rank = 4; sortKey = String(c.lastFollowAt || 0);
  }
  // 只读（无 write 能力）压缩为 rank5，保留压缩前 sortKey
  if (!canW && rank >= 2) rank = 5;
  const detail = fresh ? '待首次沟通' : appointment ? '下次：' + shortDate(appointment) + ' 客户回访' : '上次：' + shortDate(lastDate);
  const id = String(c.id);
  return {
    id, name, initial: name.slice(0, 1),
    status: statusText(c.status), stageName: statusText(c.status),
    phone: phoneText, phoneText, dial: c.phone || '',
    searchText, city: c.city || '', summary: c.summary || '',
    brief: [c.city, c.source && c.source !== '未记录' ? c.source : ''].filter(Boolean).join(' · '),
    fresh, appointment, next: appointment ? '客户回访' : '',
    cue, cueTone, rank, sortKey, detail,
    canFollow: canW,
    action: canW ? '写跟进' : '看记录',
    url: '/pages/workflow/index?view=customer&id=' + encodeURIComponent(id) + (fresh && canW ? '&modal=follow' : ''),
    hasPhone: !!c.phone,
    hasDesign: false, hasPlan: false, hasVisit: false, owner: '', view: 'customer'
  };
}

/** 行排序：rank 升序 → sortKey 字典序 → id 字符串字典序稳定兜底 */
function sortRows(rows) {
  return rows.slice().sort((a, b) => a.rank - b.rank ||
    String(a.sortKey).localeCompare(String(b.sortKey)) || String(a.id).localeCompare(String(b.id)));
}

/** tabs 前端内存过滤：due/appointments/visited(恒空)/all */
function tabFilter(rows, tab, ctx) {
  const day = (ctx && ctx.today) || today();
  if (tab === 'visited') return [];
  if (tab === 'due') {
    return rows.filter(r => r.canFollow && (r.status === '已登记' || r.fresh || (r.appointment && r.appointment <= day)));
  }
  if (tab === 'appointments') return rows.filter(r => r.appointment && r.appointment > day);
  return rows;
}

/**
 * 详情映射（CustomerDetail → 工作区渲染模型，键与 workflow wxml view=customer 块对齐）。
 * 尺寸回退链：PLAN > FACADE > BUDGET，同 kind 取 version 最大；缺任一尺寸跳下一优先级。
 */
function latestSchemeSize(schemes) {
  const list = Array.isArray(schemes) ? schemes : [];
  const byKind = kind => list.filter(s => s.kind === kind).sort((a, b) => (b.version || 0) - (a.version || 0))[0];
  for (const kind of ['PLAN', 'FACADE', 'BUDGET']) {
    const s = byKind(kind);
    if (s && s.width != null && s.depth != null && s.floors != null) {
      return { width: String(s.width), depth: String(s.depth), floors: String(s.floors), scheme: s };
    }
  }
  return null;
}

function detailView(detail, ctx) {
  const canW = !!(ctx && ctx.canWrite);
  const c = detail.customer || {};
  const size = latestSchemeSize(detail.schemes);
  const follows = (detail.follows || []).slice().reverse().map(f => ({
    kind: 'api', method: '', result: '',
    date: toDate(f.createTime), appointment: f.nextVisitDate ? toDate(f.nextVisitDate) : '',
    content: f.note || '', note: f.note || ''
  }));
  const status = statusText(c.status);
  const id = String(c.id);
  return {
    customer: {
      id, name: c.name || '', phone: c.phone || '', wechat: c.wechat || '',
      source: c.source || '', province: c.province || '', city: c.city || '',
      summary: c.summary || '', status,
      width: size ? size.width : '', depth: size ? size.depth : '', floors: size ? size.floors : '',
      owner: '', revision: c.revision, api: true,
      follows
    },
    schemesList: (detail.schemes || []).map(sc => ({
      id: String(sc.id), kind: sc.kind || '', version: sc.version,
      title: sc.title || '', kindText: sc.kind === 'PLAN' ? '平面方案' : sc.kind === 'FACADE' ? '立面方案' : sc.kind === 'BUDGET' ? '参考预算' : (sc.kind || ''),
      date: toDate(sc.createTime),
      totalText: sc.totalCents != null ? (sc.totalCents / 100).toFixed(2) : ''
    })),
    showSizeBlock: !!size,
    canEditCustomer: canW, canFollow: canW,
    sizeScheme: size ? size.scheme : null
  };
}

/** 命令快照键：crm-{动作}-{16位随机hex}（随机命令身份，跨客户/页面重建无碰撞） */
function commandKey(action) {
  let hex = '';
  for (let i = 0; i < 16; i += 1) hex += Math.floor(Math.random() * 16).toString(16);
  return 'crm-' + action + '-' + hex;
}

/* ---- 写命令 payload 构造（§六；前端输入约束拦截由页面调用这些函数前完成） ---- */

/** 登记客户：{name,phone,need,source}（need=需求摘要；wechat/地区不采集） */
function capturePayload(form) {
  return {
    name: String(form.name || '').trim(),
    phone: String(form.phone || '').trim(),
    need: String(form.summary || '').trim(),
    source: String(form.source || '').trim()
  };
}

/** 编辑客户：{name,wechat,province,city,summary,revision}（phone/source 不可改） */
function updatePayload(form, revision) {
  return {
    name: String(form.name || '').trim(),
    wechat: String(form.wechat || '').trim(),
    province: String(form.province || '').trim(),
    city: String(form.city || '').trim(),
    summary: String(form.summary || '').trim(),
    revision
  };
}

/** 跟进：{note,nextVisitDate:[y,m,d]}（客户端不带 revision） */
function followPayload(form) {
  return {
    note: String(form.followContent || '').trim(),
    nextVisitDate: dateToArray(form.appointment)
  };
}

/** 转交：{targetUserId,reason,revision}（targetUserId 不透明值透传） */
function transferPayload(targetUserId, form, revision) {
  return { targetUserId, reason: String(form.transferReason || '').trim(), revision };
}

/* ---- 输入约束（提交前拦截，非法零请求） ---- */
function validPhone(v) { return /^1\d{10}$/.test(String(v || '')); }
function within(v, max) { return String(v || '').length <= max; }

/* ---- P2 交易流：报价编译与渲染模型（契约=docs/backend-parity/12 §四/§五） ---- */

const BUDGET_UNIT = { foundation: { 独立基础: 180, 条形基础: 220, 桩基础: 320 }, structure: { 框架结构: 850, 砖混结构: 680 }, roof: { 坡屋顶: 240, 平屋顶: 180 } };
const DECOR_UNIT = [['门窗', 'window', 120], ['墙漆', 'paint', 55], ['文化石', 'stone', 45], ['灯光', 'light', 20], ['防水', 'waterproof', 25], ['防雷', 'lightning', 10]];
const MAX_ROW_CENTS = 1_000_000_000; // 10^9 分=行金额上限 1000 万元（SaleQuoteMath）

/** 元（数字/字符串）→ 分（整数）；非法 → null（复用严格口径，逗号/两位小数以上/科学计数法拒绝） */
function toCents(input) {
  const str = String(input == null ? '' : input).trim();
  if (!/^\d+(\.\d{1,2})?$/.test(str)) return null;
  const cents = Math.round(parseFloat(str) * 100);
  if (!Number.isSafeInteger(cents) || cents < 0) return null;
  return cents;
}

/**
 * budget 表单 → R4 items（确定性编译，§四）：
 * 行金额=demo 公式 quantity×unitPrice 乘积，quantity=1、unitPriceCents=round(行金额×100)，
 * 行内 qtyText/unitText 展示字段（后端 items 透传，导出渲染原始工程量/单价）。
 * 返回 {items, error}——error 非空=越界（零请求）。
 */
function compileQuoteItems(f) {
  const area = Number(f.area), footprint = Number(f.footprint);
  const roofArea = f.roofArea != null && f.roofArea !== '' ? Number(f.roofArea) : Math.round(footprint * 1.1);
  const fUnit = BUDGET_UNIT.foundation[f.foundation], sUnit = BUDGET_UNIT.structure[f.structure], rUnit = BUDGET_UNIT.roof[f.roof];
  if (!fUnit || !sUnit || !rUnit) return { error: '请选择已定义的基础、结构及屋顶配置' };
  const line = (label, qty, unitPrice) => {
    const value = Math.round(qty * unitPrice * 100) / 100; // 与 demo round 同口径
    const unitPriceCents = Math.round(value * 100);
    return { label, quantity: 1, unitPriceCents, qtyText: String(qty), unitText: unitPrice.toFixed(2), valueCents: unitPriceCents };
  };
  const main = [
    line('基础 · ' + f.foundation, footprint, fUnit),
    line('结构 · ' + f.structure, area, sUnit),
    line('屋顶 · ' + f.roof, roofArea, rUnit)
  ];
  const exterior = (f.exterior || {});
  const decor = DECOR_UNIT.filter(([, key]) => exterior[key]).map(([label, , unitPrice]) => line(label, area, unitPrice));
  const insuranceLine = exterior.insurance ? line('保险', area, 25) : null;
  const items = { main, decor, insuranceCents: insuranceLine ? insuranceLine.unitPriceCents : 0, managementCents: 0 };
  for (const row of main.concat(decor)) {
    if (!within(row.label, 32) || row.unitPriceCents < 0 || row.unitPriceCents > MAX_ROW_CENTS) {
      return { error: '单项金额超过上限，请核对面积与配置' };
    }
  }
  const total = main.concat(decor).reduce((n, r) => n + r.unitPriceCents, 0) + items.insuranceCents;
  if (total <= 0 || total > 1_000_000_000_000) return { error: '单项金额超过上限，请核对面积与配置' };
  return { items };
}

/** 需求 DO → 资料区行 */
function requirementRow(r) {
  return { id: String(r.id), version: r.version, style: r.style || '', sizeText: [r.width, r.depth].filter(v => v != null).join('×') + (r.floors ? ' · ' + r.floors + '层' : ''), note: r.note || r.prompt || '', date: toDate(r.createTime) };
}

/** 报价 DO → 历史行（items 为 JSON 字符串） */
function quoteRow(q) {
  let items = null;
  try { items = typeof q.items === 'string' ? JSON.parse(q.items) : q.items; } catch (e) { items = null; }
  return { id: String(q.id), priceVersion: q.priceVersion || '表单口径', totalText: q.totalCents != null ? (q.totalCents / 100).toFixed(2) : '', date: toDate(q.createTime), customerName: q.customerName || '', itemsParsed: items };
}

const SALE_STATUS_TEXT = { WAIT_SIGN: '待签订', SIGNED: '已签订', VOIDED: '已作废' };
const STAGE_TEXT = { first: '首款', tail: '尾款' };
const STAGE_STATUS_TEXT = { PENDING: '待回款', PAID: '已回款' };

/** R8 行 {contract,stages} → 合同卡模型（stages 过滤 deleted） */
function contractRow(row) {
  const c = row.contract || {};
  return {
    id: String(c.id), status: c.status, statusText: SALE_STATUS_TEXT[c.status] || c.status || '',
    totalText: c.totalCents != null ? (c.totalCents / 100).toFixed(2) : '',
    firstText: c.firstCents != null ? (c.firstCents / 100).toFixed(2) : '',
    tailCents: c.totalCents != null && c.firstCents != null ? c.totalCents - c.firstCents : null,
    tailText: c.totalCents != null && c.firstCents != null ? ((c.totalCents - c.firstCents) / 100).toFixed(2) : '',
    signedDate: toDate(c.signedDate), revision: c.revision,
    stages: (row.stages || []).filter(s => !s.deleted).map(s => ({
      id: String(s.id), stage: s.stage, stageText: STAGE_TEXT[s.stage] || s.stage,
      amountText: s.amountCents != null ? (s.amountCents / 100).toFixed(2) : '',
      status: s.status, statusText: STAGE_STATUS_TEXT[s.status] || s.status || '',
      paidDate: s.paidAt ? toDate(s.paidAt) : ''
    }))
  };
}

const DELIVERY_NODES = [
  ['design-delivered', '方案交付', 'designDeliveredAt'],
  ['design-confirmed', '方案确认', 'designConfirmedAt'],
  ['construction-delivered', '施工交付', 'constructionDeliveredAt'],
  ['construction-accepted', '施工验收', 'constructionAcceptedAt']
];

/** R14 DO 或 R13 响应 → 四节点模型（nextIndex=首个未完成节点序） */
function deliveryView(source) {
  const src = source || {};
  const nodes = DELIVERY_NODES.map(([key, label, field]) => ({
    key, label, at: src[field] || null,
    atText: src[field] ? new Date(src[field] + 8 * 3600e3).toISOString().slice(5, 16).replace('T', ' ') : ''
  }));
  let nextIndex = nodes.findIndex(n => !n.at);
  if (nextIndex < 0) nextIndex = nodes.length;
  return { nodes: nodes.map(n => ({ ...n, done: !!n.at })), nextIndex, allDone: nodes.every(n => !!n.at) };
}

/** 交易流错误码映射（action 仅影响语境；复用 P1 平台段） */
function errorMessageSales(err) {
  const code = err && err.code;
  const map = {
    '1113000001': '报价明细不合法，请核对金额与行数（单项不超过一千万元）',
    '1113000002': '该期回款已办理且内容不一致，请刷新后核对',
    '1113000003': '交付节点须按顺序登记',
    '1113000004': '引用对象不存在或不属于本客户',
    '1113000005': '合同尚未签订，不能办理回款',
    '1113000006': '该客户已有一份有效主合同，作废后方可重签'
  };
  if (code != null && map[String(code)]) return map[String(code)];
  return errorMessage(err);
}

/**
 * 错误码映射（§六；action 仅影响 1_110_000_008 语境）。
 * 403=业务码（CommonResult body）；code:-1=网络异常。
 */
function errorMessage(err, action) {
  const code = err && err.code;
  const map = {
    '1110000000': '客户不存在或已转移，请刷新列表',
    '1110000001': '当前岗位不能办理该客户',
    '1110000003': '请填写称呼、11位手机号、需求和来源',
    '1110000004': '已有该手机号客资待关联，请由负责人处理',
    '1110000009': '预约日期不能早于今天',
    '1110000010': '请检查客户资料（称呼必填、长度不超限）',
    '1110000011': '请填写方案名称与有效尺寸',
    '1110000012': '接手人须为本组织在岗业务人员',
    '1099000001': '数据已更新，请刷新后重试',
    '1099000000': '请刷新后重新填写'
  };
  if (code === 1110000008) {
    if (action === 'follow') return '请填写沟通内容（500字以内）';
    if (action === 'transfer') return '请填写转交原因（500字以内）';
    return '请填写内容或原因（500字以内）';
  }
  if (code != null && map[String(code)]) return map[String(code)];
  if (code === 403) return '没有该操作权限';
  if (code === -1) return '网络异常，请刷新确认';
  return (err && err.message) || '操作失败，请重试';
}

module.exports = {
  CRM_ROLES, pageMode, can, collectError, toDate, dateToArray, today, maskPhone, statusText,
  customerRow, sortRows, tabFilter, latestSchemeSize, detailView,
  commandKey, capturePayload, updatePayload, followPayload, transferPayload,
  validPhone, within, errorMessage,
  // P2 交易流（契约=docs/backend-parity/12）
  toCents, compileQuoteItems, requirementRow, quoteRow, contractRow, deliveryView, DELIVERY_NODES, errorMessageSales
};
