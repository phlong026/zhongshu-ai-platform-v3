const TYPE_LABELS = { BUSINESS: '业务', LEARNING: '学习', SYSTEM: '系统' };

function formatTime(value) {
  const ms = require('./epoch-ms').epochMsOf(value);
  if (!Number.isFinite(ms)) return '';
  return new Date(ms + 8 * 3600000).toISOString().slice(0, 16).replace('T', ' ');
}

function toLocalMessage(row) {
  if (!row || typeof row !== 'object') return null;
  const id = row.id == null ? '' : String(row.id);
  if (!/^\d+$/.test(id)) return null;
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

function isApiMessage(message) {
  return !!message && /^\d+$/.test(String(message.id));
}

function mergeSources(stored, derived, remote) {
  const seen = new Set();
  return [].concat(stored || [], derived || [], remote || [])
    .filter(m => m && m.id != null && m.id !== '' && !seen.has(m.id) && seen.add(m.id))
    .sort((a, b) => String(b.date || '').localeCompare(String(a.date || '')));
}

function totalUnread(localUnread, apiCount) {
  const local = Number.isFinite(localUnread) && localUnread > 0 ? localUnread : 0;
  const api = Number.isFinite(apiCount) && apiCount > 0 ? apiCount : 0;
  return local + api;
}

module.exports = { TYPE_LABELS, formatTime, toLocalMessage, isApiMessage, mergeSources, totalUnread };
