/**
 * 平台客资视图模型（阶段 1 · P1 客户接入）：客户列表/工作区/登记/转交。
 * 纯函数，零 wx/Storage 依赖——隔离验收（AC9 执行链 spy）依赖此约束。
 * 契约见 docs/backend-parity/09-阶段1-P1客户接入-需求与验收.md（V6，Codex R5 PASS）。
 * 序列化事实：nextVisitDate / nextFollowOn=[年,月,日] 数组；createTime/lastFollowAt=毫秒时间戳；
 * Long 超出 ±(2^53-1) 输出字符串（边界值本身也是字符串）——ID 一律不透明值，禁止数值化。
 * 客户跟进只有八步流程一个事实来源：下次跟进日期读客户表的 nextVisitDate（由八步服务同步）。
 */
const CRM_ROLES = ['sales', 'sales-lead', 'boss', 'owner'];
const DESIGN_STATUS_TEXT = {EXTERNAL_PENDING:'等待外部成果',EXTERNAL_READY:'外部成果已登记',FEE_PENDING:'委托待审核',FEE_RETURNED:'委托待修改',DESIGNING:'设计中',DESIGN_COMPLETED:'设计完成',DISPATCHED:'成果已下发',HANDOVER_SUBMITTED:'待工程接收',HANDOVER_RETURNED:'移交待补充',ACCEPTED:'已移交工程'};
const customerStatus = c => DESIGN_STATUS_TEXT[c.designStatus] || statusText(c.status);
const STATUS_TEXT = { REGISTERED: '已登记', FOLLOWING: '跟进中' };
/** 八步流程状态里需要单独显示的三种；进行中（ACTIVE）显示当前步骤。 */
const FLOW_STATE = { PAUSED: '暂缓', LOST: '已流失', COMPLETED: '已开工' };
const CRM_ROLESTEPS = { INVITATION: '邀约进店', INTRODUCTION: '店面讲解', HOUSE_SELECTION: '户型选择', SITE_VISIT: '带看工地', MEASUREMENT: '现场测量', QUOTATION: '别墅报价', CONTRACT: '施工签约', START: '开工大吉' };
const TIMELINE_TYPES = { SAVE: '保存记录', COMPLETE: '完成步骤', SKIP: '已跳过带看', REOPEN: '重新办理', STATUS: '更新状态', SUPPLEMENT: '补充记录', CONSULT: '再次咨询', LEGACY: '跟进记录', VISIT: '实际接待' };

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

/** 回款办理表单校验（S1-14/M06-T02 分笔）：method 必填；金额>0；银行方式须流水号；receivedDate 必填且不晚于今天（后端为最终裁决）。返回错误文案或 '' */
function collectError(form) {
  const f = form || {};
  const method = String(f.method || '').trim();
  if (!method) return '请选择收款方式';
  // R1：与提交转换统一用 toCents（Number 会放行 0.001/1.234 等超过两位小数金额——toCents 判 null）
  const amountCents = toCents(f.amount);
  if (!amountCents) return '请填写本次回款金额（大于 0，最多两位小数）';
  if (method === 'bank' && !String(f.transactionNo || '').trim()) return '银行收款须填流水号';
  if (!f.receivedDate || !/^\d{4}-\d{2}-\d{2}$/.test(String(f.receivedDate))) return '请填写到账日期（YYYY-MM-DD，不晚于今天）';
  else if (String(f.receivedDate) > toDate(Date.now())) return '到账日期不能晚于今天';
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
  const flow = (c && c.workflow) || null;
  // 与客户列表分栏同源，只认客户的下次跟进日期；八步邀约自己的日期不参与（#287）。
  const appointment = toDate(c.nextVisitDate);
  const fresh = !c.lastFollowAt;
  const lastDate = toDate(c.lastFollowAt);
  const name = c.name || '';
  const phoneText = c.phone ? maskPhone(c.phone) : (c.wechat || '暂无联系方式');
  const searchText = [c.name, c.phone, c.province, c.city, c.district, c.town, c.village, c.addressDetail].filter(Boolean).join(' ');
  // 已流失不再提醒；暂缓的客户到再联系日期才回到待跟进，之前按日期排在后面。
  const lost = !!flow && flow.status === 'LOST';
  const paused = !!flow && flow.status === 'PAUSED';
  const waiting = paused && !!appointment && appointment > day;
  let cue, cueTone = '', rank, sortKey;
  if (lost) {
    cue = flow.reconsulted ? '再次咨询' : (flow.statusReason || FLOW_STATE.LOST);
    cueTone = flow.reconsulted ? 'tone-warning' : 'customer-cue-muted';
    rank = 5; sortKey = (flow.reconsulted ? '0' : '9') + String(c.lastFollowAt || 0);
  } else if (waiting) {
    cue = shortDate(appointment) + ' 再联系'; cueTone = 'customer-cue-muted'; rank = 5; sortKey = '8' + appointment;
  } else if (appointment && appointment < day) {
    cue = '逾期 ' + daysBetween(appointment, day) + ' 天'; cueTone = 'tone-danger'; rank = 0; sortKey = appointment;
  } else if (appointment && appointment === day) {
    cue = '今天跟进'; cueTone = 'tone-warning'; rank = 1; sortKey = appointment;
  } else if (appointment && appointment > day) {
    cue = shortDate(appointment) + ' 跟进'; rank = 3; sortKey = appointment;
  } else if (fresh) {
    cue = '新客户'; rank = 2; sortKey = String(c.createTime || 0);
  } else {
    const n = daysBetween(lastDate, day);
    cue = n > 0 ? n + ' 天未跟进' : '今天已跟进'; cueTone = 'customer-cue-muted'; rank = 4; sortKey = String(c.lastFollowAt || 0);
  }
  const detail = lost ? '已流失' + (flow.statusReason ? ' · ' + flow.statusReason : '') + '，可恢复'
    : waiting ? '暂缓 · ' + shortDate(appointment) + ' 再联系'
    : appointment ? '下次：' + shortDate(appointment) + ' 跟进'
    : fresh ? '待首次跟进' : '上次：' + shortDate(lastDate);
  const id = String(c.id);
  return {
    id, name, initial: name.slice(0, 1),
    status: customerStatus(c),
    stageName: lost || paused ? FLOW_STATE[flow.status] : flow ? workflowProgress(flow).currentStepName : customerStatus(c),
    engineeringHint: workflowProgress(flow).engineeringHint,
    workflow: flow || null,
    progress: flow ? (Number(flow.completedCount) || 0) + '/' + (Number(flow.totalSteps) || 8) + '步 · ' + (flow.progressPercent == null ? 0 : flow.progressPercent) + '%' : '',
    lost, paused, off: lost || paused, reconsulted: !!(flow && flow.reconsulted),
    phone: phoneText, phoneText, dial: c.phone || '',
    searchText, city: c.city || '', summary: c.summary || '',
    brief: [c.city, c.source && c.source !== '未记录' ? c.source : ''].filter(Boolean).join(' · '),
    fresh, appointment, next: appointment ? '客户跟进' : '',
    cue, cueTone, rank, sortKey, detail,
    canFollow: canW,
    action: lost ? '查看 / 恢复' : '客户跟进',
    url: '/pages/workflow/index?view=customer&id=' + encodeURIComponent(id),
    flowUrl: '/pages/customer-flow/index?customerId=' + encodeURIComponent(id),
    hasPhone: !!c.phone,
    hasDesign: !!DESIGN_STATUS_TEXT[c.designStatus], hasPlan: false, hasVisit: !!ctx?.visited,
    ownerUserId: c.ownerUserId == null ? '' : String(c.ownerUserId),
    ownerName: c.ownerName || '', ownerDisplay: c.ownerName || '负责人待核', ownerRoleName: '',
    view: 'customer'
  };
}

/** 列表、工作区与八步页共用服务端只读事实；选中的表单页不参与当前业务进度。 */
function workflowProgress(flow) {
  if (!flow) return { currentStepName: '待办理', engineeringHint: '' };
  const currentStepName = CRM_ROLESTEPS[flow.currentStepCode] || (flow.completedCount === 8 ? '业务八步完成' : flow.currentStepName || '待办理');
  const start = flow.start || (flow.steps || []).find(step => step.code === 'START');
  const f = start?.fields || {};
  let hint = '';
  if (start?.status === 'COMPLETED') hint = '已实际开工';
  else if (f.projectId) hint = f.actualStartAt ? '已登记开工日期，开工凭据待核对'
    : f.commissionStatus === 'ACCEPTED' ? '工程已接收，待实际开工' : '主工程已建档，待实际开工';
  else if (f.commissionStatus === 'ACCEPTED') hint = '工程已接收，但缺少关联主工程';
  else if (f.commissionStatus === 'HANDOVER_SUBMITTED') hint = f.handoverStatus === 'PENDING' ? '已移交，待指定工程人员接收' : '移交资料待核对';
  else if (f.commissionStatus === 'HANDOVER_RETURNED') hint = f.handoverStatus === 'RETURNED' ? '移交已退回，待补齐重提' : '移交资料待核对';
  else if (f.commissionStatus === 'EXTERNAL_PENDING') hint = '待登记外部成果';
  else if (['EXTERNAL_READY', 'DISPATCHED'].includes(f.commissionStatus)) hint = f.contractSigned === false ? '待提交工程移交，缺正式施工合同' : '待提交工程移交';
  else if (f.commissionId) hint = '历史委托待核对';
  const version = Number(f.handoverVersion);
  return { currentStepName, engineeringHint: hint + (hint && Number.isInteger(version) && version > 0 ? ' · 移交 V' + version : '') };
}

/** 行排序：rank 升序 → sortKey 字典序 → id 字符串字典序稳定兜底 */
function sortRows(rows) {
  return rows.slice().sort((a, b) => a.rank - b.rank ||
    String(a.sortKey).localeCompare(String(b.sortKey)) || String(a.id).localeCompare(String(b.id)));
}

/**
 * 首页汇总使用与客户列表查询一致的上海业务日期。
 * 口径与后端 followFilter 同源：已流失不进任何跟进分栏；暂缓的客户到再联系日期才回到待跟进。
 */
function tabFilter(rows, tab, ctx) {
  const day = (ctx && ctx.today) || today();
  if (tab === 'visited') return rows.filter(r => r.hasVisit);
  if (tab === 'due') return rows.filter(r => !r.lost && (r.fresh || (r.appointment && r.appointment <= day)));
  if (tab === 'appointments') return rows.filter(r => !r.lost && r.appointment && r.appointment > day);
  return rows;
}

// 业务首页与老板首页共用全量分页及统计口径；列表预览的条数不影响指标。
async function loadSummaryRows(fetchPage, isStale, label) {
  const pageSize = 100, limit = 10000, result = [];
  let expectedTotal = null;
  for (let pageNo = 1; pageNo <= Math.ceil(limit / pageSize); pageNo++) {
    if (isStale()) throw { silent: true };
    const response = await fetchPage(pageNo, pageSize);
    if (isStale()) throw { silent: true };
    const rows = response && Array.isArray(response.list) ? response.list : null;
    const total = Number(response && response.total);
    if (!rows || !Number.isInteger(total) || total < 0) throw new Error(label + '汇总分页返回无效');
    if (total > limit) throw new Error(label + '记录超过' + limit + '条，汇总暂不可用');
    if (expectedTotal === null) expectedTotal = total;
    else if (total !== expectedTotal) throw new Error(label + '汇总期间总数变化，请重试');
    if (rows.length > pageSize || result.length + rows.length > expectedTotal) throw new Error(label + '汇总分页数据异常');
    if (rows.length === 0 && result.length < expectedTotal) throw new Error(label + '汇总分页存在空洞，请重试');
    result.push(...rows);
    if (result.length === expectedTotal) return result;
    if (rows.length < pageSize) throw new Error(label + '汇总分页不完整，请重试');
  }
  throw new Error(label + '汇总超出安全分页范围');
}

function homeSummary(customers, commissions, post, day) {
  day = day || today();
  const rows = (customers || []).map(c => customerRow(c, { today: day, canWrite: can(post, 'write') }));
  const monthRows = (commissions || []).filter(row => {
    const date = toDate(row.createTime || row.date || row.submittedAt);
    return date && date.slice(0, 7) === day.slice(0, 7);
  });
  const yuan = Math.round(monthRows.reduce((total, row) => total + (Number(
    row.totalCents != null ? row.totalCents : row.amountCents != null ? row.amountCents : row.commissionCents
  ) || 0), 0) / 100);
  const commission = yuan >= 10000
    ? { value: String(Math.round(yuan / 100) / 100), unit: '万元' }
    : { value: yuan.toLocaleString('en-US'), unit: '元' };
  // 统计待跟进客户不受老板的只读权限影响，客户原单仍按 canFollow 控制办理权限。
  const due = tabFilter(rows, 'due', { today: day });
  const upcoming = tabFilter(rows, 'appointments', { today: day });
  return {
    rows, monthRows, commission, due, upcoming,
    following: rows.filter(row => row.status === '跟进中' && !row.off),
    overdue: rows.filter(row => !row.lost && row.appointment && row.appointment < day),
    metrics: [
      { key: 'due', label: '今日待跟进', value: due.length, unit: '人' },
      { key: 'appointments', label: '预约跟进', value: upcoming.length, unit: '人' },
      { key: 'commission', label: '委托', value: commission.value, unit: commission.unit },
      { key: 'all', label: '全部客户', value: rows.length, unit: '人' }
    ]
  };
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
  const flow = (c && c.workflow) || null;
  const size = latestSchemeSize(detail.schemes);
  // 升级前登记的普通跟进：只读保留在详情里，新版本在“跟进”页显示八步时间线，不再写入。
  const follows = (detail.follows || []).slice().reverse().map(f => ({
    kind: 'api', method: f.contactKind==='VISIT'?'实际接待':f.contactKind==='FOLLOW'?'普通跟进':'', result: '',
    date: toDate(f.occurredOn||f.createTime), appointment: f.nextVisitDate ? toDate(f.nextVisitDate) : '',
    content: f.note || '', note: f.note || ''
  }));
  // 流失、暂缓、已开工在状态位显示；进行中沿用“已登记 / 跟进中”。
  const status = flow && FLOW_STATE[flow.status] ? FLOW_STATE[flow.status] : customerStatus(c);
  const id = String(c.id);
  return {
    customer: {
      id, name: c.name || '', phone: c.phone || '', wechat: c.wechat || '',
      source: c.source || '', province: c.province || '', city: c.city || '',
      district: c.district || '', town: c.town || '', village: c.village || '',
      regionCode: c.regionCode || '', addressDetail: c.addressDetail || '',
      address: [c.province,c.city,c.district,c.town,c.village,c.addressDetail].filter(Boolean).join(' '),
      summary: c.summary || '', status, nextVisitDate: toDate(c.nextVisitDate), lastFollowAt: c.lastFollowAt || null,
      flow: flow && typeof flow === 'object' ? flow : null,
      width: size ? size.width : '', depth: size ? size.depth : '', floors: size ? size.floors : '',
      ownerUserId: c.ownerUserId == null ? '' : String(c.ownerUserId),
      ownerName: c.ownerName || '', ownerDisplay: c.ownerName || '负责人待核', ownerRoleName: '',
      revision: c.revision, api: true,
      follows
    },
    schemesList: (detail.schemes || []).map(sc => ({
      id: String(sc.id), kind: sc.kind || '', version: sc.version,
      title: sc.title || '', kindText: sc.kind === 'PLAN' ? '平面方案' : sc.kind === 'FACADE' ? '立面方案' : sc.kind === 'BUDGET' ? '参考预算' : (sc.kind || ''),
      date: toDate(sc.createTime),
      totalText: sc.totalCents != null ? (sc.totalCents / 100).toFixed(2) : '',
      formal: sc.stage === 'formal'
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

const ADDRESS_FIELDS = ['province','city','district','town','village','regionCode','addressDetail'];
function addressPayload(form) {
  return ADDRESS_FIELDS.reduce((result,key)=>{result[key]=String(form[key]||'').trim();return result;},{});
}
function addressError(form, required) {
  const address=addressPayload(form);
  if(ADDRESS_FIELDS.some(key=>address[key].length>(key==='addressDetail'?200:key==='regionCode'?12:64)))return '地址内容超过长度限制';
  if(required||address.regionCode||address.district||address.town||address.village){
    if(!address.province||!address.city||!address.district||!address.town)return '请选择地址至乡镇／街道';
    if(!address.village)return '请选择或填写村／社区';
    if(!/^(\d{9}|\d{12})$/.test(address.regionCode))return '请重新选择乡镇／街道和村／社区';
  }
  return '';
}

/** 登记客户：需求摘要与五级地址随客资一起保存。 */
function capturePayload(form) {
  return {
    name: String(form.name || '').trim(),
    phone: String(form.phone || '').trim(),
    need: String(form.summary || '').trim(),
    source: String(form.source || '').trim(),
    ...addressPayload(form)
  };
}

/** 编辑客户：{name,wechat,province,city,summary,revision}（phone/source 不可改） */
function updatePayload(form, revision) {
  return {
    name: String(form.name || '').trim(),
    wechat: String(form.wechat || '').trim(),
    province: String(form.province || '').trim(),
    city: String(form.city || '').trim(),
    ...addressPayload(form),
    summary: String(form.summary || '').trim(),
    revision
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
/** M06-T02：三期=签约后/基础完成后/封顶后（first/tail 为存量两期兼容文案） */
const STAGE_TEXT = { SIGN_AFTER: '签约后', BASE_DONE: '基础完成后', CAP_DONE: '封顶后', first: '首款', tail: '尾款' };
const STAGE_STATUS_TEXT = { PENDING: '待回款', OPEN: '部分已收', SETTLED: '已收满', PAID: '已回款' };

/** R8 行 {contract,stages} → 合同卡模型（M06-T02：stages=stageSummary 统一视图行，含分笔累计/余额） */
function contractRow(row) {
  const c = row.contract || {};
  return {
    id: String(c.id), status: c.status, statusText: SALE_STATUS_TEXT[c.status] || c.status || '',
    totalText: c.totalCents != null ? (c.totalCents / 100).toFixed(2) : '',
    signedDate: toDate(c.signedDate), revision: c.revision,
    declarations: (row.declarations || []).map(d => ({id:String(d.id),stageId:String(d.stage_id),amountText:(Number(d.amount_cents)/100).toFixed(2),date:toDate(d.received_date),status:d.status,reason:d.return_reason||'',transactionNo:d.transaction_no||'',method:d.method,accountName:d.account_name||''})),
    stages: (row.stages || []).map(s => ({
      id: String(s.stageId != null ? s.stageId : s.id), stage: s.stage,
      stageText: s.label || STAGE_TEXT[s.stage] || s.stage,
      amountText: s.amountCents != null ? (s.amountCents / 100).toFixed(2) : '',
      collectedText: s.collectedCents != null ? (s.collectedCents / 100).toFixed(2) : '',
      balanceText: s.balanceCents != null ? (s.balanceCents / 100).toFixed(2) : '',
      status: s.status, statusText: s.refundedCents > 0 && s.balanceCents > 0
        ? '退款待补收' : STAGE_STATUS_TEXT[s.status] || s.status || '',
      // M06-T02 契约冻结（R1）：owing 只信 stageSummary 的 balanceCents（后端 list 已换 summary；
      // 缺字段=旧结构/部署不同步，保守不可收——前后端须同版本发布，登记 18 号）
      owing: s.balanceCents != null && s.balanceCents > 0,
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
    '1110000003': '请检查客户姓名、11位手机号、地址、需求和来源',
    '1110000004': '已有该手机号客资待关联，请由负责人处理',
    '1110000009': '预约日期不能早于今天',
    '1110000010': '请检查客户姓名与地址，填写内容不能超长',
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
  CRM_ROLES, pageMode, can, collectError, toDate, dateToArray, today, daysBetween, maskPhone, statusText,
  customerRow, workflowProgress, sortRows, tabFilter, loadSummaryRows, homeSummary, latestSchemeSize, detailView,
  commandKey, capturePayload, updatePayload, addressPayload, addressError, transferPayload,
  validPhone, within, errorMessage,
  // P2 交易流（契约=docs/backend-parity/12）
  toCents, compileQuoteItems, requirementRow, quoteRow, contractRow, deliveryView, DELIVERY_NODES, errorMessageSales
};
