/**
 * B 公司积分池 API。
 *
 * 使用平台 Bearer + 当前岗位身份，不经过旧个人钱包会话。每次请求冻结会话代次和岗位，
 * 切岗或换号后的迟到响应统一丢弃，避免把上一家公司的积分写进当前页面。
 */
const http = require('./http');

const STALE = 'COMPANY_POINTS_STALE';
const BASE = '/franchise/company-points';
const inFlightReads = new Map();

function fingerprint() {
  return http.sessionGeneration() + ':' + (http.currentPostId() || '');
}

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

function summary() {
  return read(BASE + '/summary');
}

// Only share pending reads in the same session/post. Settled balances are never cached.
function read(path) {
  const key = JSON.stringify([fingerprint(), path]);
  if (inFlightReads.has(key)) return inFlightReads.get(key);
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
  return read(BASE + '/my-usage?' + query.join('&'));
}

/**
 * 精确格式化后端 BIGINT。超过 JS 安全整数的 number 已不可恢复原值，必须拒绝；
 * 后端应以十进制字符串传输该范围的值。
 */
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

function packages() { return read(BASE + '/simulated-packages'); }
function purchase(plan, key) {
  return guarded(() => http.post(BASE + '/simulated-purchases', { planId: plan.id, version: plan.version }, { 'Idempotency-Key': key }));
}

module.exports = { packages, purchase, STALE, fingerprint, isStale, summary, myUsage, formatCredits };
