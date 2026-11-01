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

/** 能力判定（后端权限种子 V20261022.851：query×4 / capture,write=sales,lead / transfer=lead,boss,owner） */
function can(post, ability) {
  if (!post) return false;
  const role = post.roleCode;
  const isBoss = role === 'boss' || role === 'owner';
  if (ability === 'query') return CRM_ROLES.includes(role);
  if (ability === 'capture' || ability === 'write') return role === 'sales' || role === 'sales-lead';
  if (ability === 'transfer') return role === 'sales-lead' || isBoss;
  return false;
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
  CRM_ROLES, pageMode, can, toDate, dateToArray, today, maskPhone, statusText,
  customerRow, sortRows, tabFilter, latestSchemeSize, detailView,
  commandKey, capturePayload, updatePayload, followPayload, transferPayload,
  validPhone, within, errorMessage
};
