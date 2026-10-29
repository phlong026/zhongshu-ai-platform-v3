// 金额单源工具：分（存储单位）↔ 元（展示单位）。
// 语义约定：money() 只做「分 → 展示字符串」；「元字符串 → 分」一律用 parseCents()。
// 历史教训：engineering-records 曾有名为 money 实为分解析器的反向实现，禁止复现。
const NA = '待完善';

// 分 → '¥ 1,234.00'；null/undefined → 占位
function money(cents, opts = {}) {
  const n = Number(cents);
  if (!Number.isFinite(n)) return opts.na === undefined ? NA : opts.na;
  const yuan = (n / 100).toLocaleString('en-US', {
    minimumFractionDigits: opts.cases === false ? 0 : 2,
    maximumFractionDigits: opts.cases === false ? 0 : 2,
  });
  return (opts.symbol === false ? '' : '¥ ') + yuan;
}

// 元（数字或字符串）→ 分；校验最多两位小数、0~1亿
function parseCents(value, { zero = false } = {}) {
  const s = String(value == null ? '' : value).trim();
  const ok = /^\d+(\.\d{1,2})?$/.test(s) && Number(s) >= 0 && Number(s) <= 100000000;
  if (!ok || (!zero && Number(s) === 0)) throw Error('请填写有效金额');
  return Math.round(Number(s) * 100);
}

// 分 → '¥12.5万'（聚合指标用，>=100万分之一单位）
function wan(cents) {
  const n = Number(cents || 0);
  if (Math.abs(n) >= 1000000) return '¥' + Math.round(n / 1000000 * 100) / 100 + '万';
  return money(n);
}

module.exports = { money, parseCents, wan, NA };
