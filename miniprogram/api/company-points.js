/** 公司积分请求绑定当前会话和岗位。换身份后丢弃旧响应。 */
const http = require('./http');

const STALE = 'COMPANY_POINTS_STALE';
const BASE = '/franchise/company-points';
const inFlightReads = new Map();

const fingerprint = require('../utils/api-scope');

function guarded(request) {
  const expected = fingerprint();
  return request().then((value) => {
    if (fingerprint() !== expected) throw stale();
    return value;
  }, (error) => {
    if (fingerprint() !== expected) throw stale();
    throw error;
  });
}

function summary(options = {}) {
  return read(BASE + '/summary', options.fresh);
}

// Only share pending reads in the same session/post. Settled balances are never cached.
function read(path, fresh) {
  const key = JSON.stringify([fingerprint(), path]);
  if (!fresh && inFlightReads.has(key)) return inFlightReads.get(key);
  const pending = guarded(() => http.get(path));
  inFlightReads.set(key, pending);
  const forget = () => { if (inFlightReads.get(key) === pending) inFlightReads.delete(key); };
  pending.then(forget, forget);
  return pending;
}

function myUsage(options) {
  const input = options || {};
  const query = [];
  if (input.month) query.push('month=' + encodeURIComponent(input.month));
  query.push('page=' + encodeURIComponent(input.page || 1));
  query.push('size=' + encodeURIComponent(input.size || 20));
  return read(BASE + '/my-usage?' + query.join('&'), input.fresh);
}

/** 超出安全整数的 BIGINT 须以十进制字符串传输。 */
function formatCredits(value) {
  let text;
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value)) throw invalidCreditValue();
    text = String(value);
  } else if (typeof value === 'string' && /^-?\d+$/.test(value)) {
    text = value;
  } else {
    throw invalidCreditValue();
  }
  let negative = text.charAt(0) === '-';
  let digits = negative ? text.slice(1) : text;
  digits = digits.replace(/^0+(?=\d)/, '');
  if (digits === '0') negative = false;
  const limit = negative ? '9223372036854775808' : '9223372036854775807';
  if (digits.length > limit.length || (digits.length === limit.length && digits > limit)) {
    throw invalidCreditValue();
  }
  return (negative ? '-' : '') + digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

function invalidCreditValue() {
  const error = new Error('INVALID_CREDIT_VALUE');
  error.code = 'INVALID_CREDIT_VALUE';
  return error;
}

function stale() {
  return { code: STALE, silent: true, message: '工作身份已切换，本次积分响应已忽略' };
}

function isStale(error) {
  return !!error && error.code === STALE;
}

function rechargeCatalog() { return read(BASE + '/recharge/catalog', true); }
function packages() { return rechargeCatalog().then(c=>c&&c.enabled===true&&Array.isArray(c.plans)?c.plans:[]); }
function purchase() { return Promise.reject({code:403,message:'模拟充值已停用，请使用公司额度充值'}); }

module.exports = { rechargeCatalog, packages, purchase, STALE, fingerprint, isStale, summary, myUsage, formatCredits };
