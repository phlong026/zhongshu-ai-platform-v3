/**
 * 员工消息中心视图模型（纯函数，零 wx/Storage 依赖——verify 依赖此约束）。
 * 契约：monorepo docs/backend-parity/11-阶段0-基2员工消息中心-需求与验收.md（GET /platform/messages）。
 * 后端 MessageView：id 为雪花 id（超 JS 安全整数，序列化为字符串）、type=BUSINESS/LEARNING/SYSTEM、
 * route/objectId 指向后端域对象（与本地演示数据字符串 id 不同域，跳转口径见 11 §十）。
 * 本地派生通知照旧（D-基2-j），三源在前端合入，摘除随对应域后端化批次。
 */
const TYPE_LABELS = { BUSINESS: '业务', LEARNING: '学习', SYSTEM: '系统' };

/** createTime（ISO，含时区）→ 蓝本展示 date（'YYYY-MM-DD HH:mm' 北京时间；同 account.js displayTime 口径） */
function formatTime(value) {
  if (typeof value !== 'string' || !value) return '';
  const ms = Date.parse(value);
  if (!Number.isFinite(ms)) return '';
  return new Date(ms + 8 * 3600000).toISOString().slice(0, 16).replace('T', ' ');
}

/** 后端 MessageView → 本地消息蓝本形态；结构非法返回 null（调用方过滤） */
function toLocalMessage(row) {
  if (!row || typeof row !== 'object') return null;
  const id = row.id == null ? '' : String(row.id);
  if (!/^\d+$/.test(id)) return null; // 后端 id 恒为雪花数字串
  return {
    id,
    type: TYPE_LABELS[row.type] || '系统',
    title: typeof row.title === 'string' ? row.title : '',
    description: typeof row.description === 'string' ? row.description : '',
    date: formatTime(row.createTime),
    read: !!row.read,
    route: typeof row.route === 'string' ? row.route : '',
    objectId: row.objectId == null ? '' : row.objectId
  };
}

/** API 消息判定：id 为纯数字雪花串；本地/派生通知 id 均带字母前缀（msg-、design-、perf- 等） */
function isApiMessage(message) {
  return !!message && /^\d+$/.test(String(message.id));
}

/**
 * 三源合入：本地存储消息 + 派生通知 + API 消息（调用方可把前两者预合并传入）。
 * 按 id 去重（先见优先），date 倒序（蓝本 date 'YYYY-MM-DD HH:mm' 字典序即时间序，乱序输入容忍）。
 */
function mergeSources(stored, derived, remote) {
  const seen = new Set();
  return [].concat(stored || [], derived || [], remote || [])
    .filter(m => m && m.id != null && m.id !== '' && !seen.has(m.id) && seen.add(m.id))
    .sort((a, b) => String(b.date || '').localeCompare(String(a.date || '')));
}

/** 未读合计：本地未读 + API 未读（任一非有限正数按 0） */
function totalUnread(localUnread, apiCount) {
  const local = Number.isFinite(localUnread) && localUnread > 0 ? localUnread : 0;
  const api = Number.isFinite(apiCount) && apiCount > 0 ? apiCount : 0;
  return local + api;
}

module.exports = { TYPE_LABELS, formatTime, toLocalMessage, isApiMessage, mergeSources, totalUnread };
