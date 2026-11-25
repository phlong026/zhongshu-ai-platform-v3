const { formatTime } = require('../../../utils/platform-messages');

// The platform uses UUIDs; numeric legacy IDs also remain strings without rounding.
function id(value) {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) return String(value);
  return typeof value === 'string' && (/^[1-9]\d*$/.test(value)
    || /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) ? value : '';
}

function row(value, detail = false) {
  const key = value && id(value.id);
  if (!key || value.status !== 'PUBLISHED' || typeof value.title !== 'string' || !value.title.trim()) {
    throw Error('公告内容暂时不可用，请重新载入');
  }
  // The API supplies UTC ISO timestamps; formatTime always displays Beijing time.
  const date = typeof value.publishedAt === 'string' && /(?:Z|[+-]\d{2}:\d{2})$/i.test(value.publishedAt)
    ? formatTime(value.publishedAt) : '';
  if (!date || (detail && typeof value.content !== 'string')) throw Error('公告内容暂时不可用，请重新载入');
  return { id: key, title: value.title, summary: typeof value.summary === 'string' ? value.summary : '',
    pinned: value.pinned === true, date, ...(detail ? { content: value.content } : {}) };
}

function page(value) {
  const total = value && Number(value.total);
  if (!value || !Array.isArray(value.list) || !Number.isSafeInteger(total) || total < 0) {
    throw Error('公告列表暂时不可用，请重新载入');
  }
  return { rows: value.list.map(item => row(item)), total };
}

function denied(error) {
  return !!error && (error.postInvalid || [401, 403].includes(Number(error.code)));
}

// Login may resume only these two internal read-only routes, never an arbitrary URL.
function returnPath(value) {
  if (typeof value !== 'string' || value.length > 300) return '';
  let destination;
  try { destination = decodeURIComponent(value); } catch (_) { return ''; }
  const root = '/pages/account/announcements/';
  if (destination === root + 'index') return destination;
  const match = destination.match(/^\/pages\/account\/announcements\/detail\?id=([^&#]+)$/);
  const key = match && id(match[1]);
  return key ? root + 'detail?id=' + encodeURIComponent(key) : '';
}

function errorText(error, detail = false) {
  if (Number(error && error.code) === 404) return detail ? '公告已撤回或当前工作身份无权查看' : '公告模块尚未开通，请稍后重试';
  if (denied(error)) return error && error.postInvalid ? '工作身份已失效，请重新选择' : Number(error.code) === 401 ? '登录已过期，请重新登录' : '当前工作身份无权查看公告';
  return '公告加载失败，请重试';
}

module.exports = { id, row, page, denied, errorText, returnPath };
