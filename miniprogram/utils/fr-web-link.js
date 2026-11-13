/**
 * /fr Web 门户协同链接（P1 §5.4）· 纯函数（零 wx / 零 Storage）。
 *
 * 安全契约（r5 收口：严格白名单 + 单一代码路径）：
 * - 主机一次性校验，**不做 URL 解析、不保留 URL/手工双分支**，只用下面这一条正则：
 *     ^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:(0|[1-9][0-9]{0,4}))?$
 *   不匹配即返回空串——`%`、`\`、空格、控制字符、`@`、路径、query、hash、下划线、IPv6 方括号、
 *   非 ASCII（含全角/中文）全部结构性拒绝，不存在「解析器比白名单宽松」的漂移面。
 * - 端口：**端口不允许前导零**（正则已排除）；数值校验 1..65535；443（https 默认）一律省略；
 *   其余输出 `:<port>`。
 * - 归一：trim → 去掉一个尾斜杠（`https://host/` 等价 `https://host`）→ 小写 → 正则比对。
 * - 主机名小写化后使用；`localhost` 允许；`xn--` punycode 允许
 *   （IDN 需先转 punycode，编码/非 ASCII 一律拒绝）。
 * - 链接用同一条拼接：`https://{host}{port}/fr/finance?next=%2Ffr%2Ffinance`；
 *   next 白名单仍只允许 `/fr/finance`（query/hash 剥离，外链/协议相对回退）。
 * - 凭据兜底：对最终链接做大小写不敏感 `token|access[-_]?refresh|authorization|cookie|session|secret`
 *   检查（白名单下主机不可能含这些组合，保留为兜底）。decodeFully/NFKC 仅服务于该扫描，不参与主机解析。
 * - 主机来源：storage `zs-fr-host`（联调覆写）→ config/env.js FR_HOST（集中配置项）；两者都空=未配置，
 *   调用方显式提示，**不得内置生产域名**。
 */
const ALLOWED_NEXT = ['/fr/finance'];
const DEFAULT_NEXT = '/fr/finance';
const STORAGE_KEY = 'zs-fr-host';
const NOT_CONFIGURED = 'Web 门户地址未配置：请在 config/env.js 填入 FR_HOST，或经 storage zs-fr-host 覆写';
const CREDENTIAL_PATTERN = /token|access[-_]?refresh|authorization|cookie|session|secret/i;
const HOST_PATTERN = /^https:\/\/([A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)(?::(0|[1-9][0-9]{0,4}))?$/;
const HOST_MAX = 253;
const runtimeEnv = require('./runtime-env');

/** 迭代解码（仅凭据兜底扫描用；最多 3 层；任一层失败返回 null=可疑） */
function decodeFully(value) {
  let current = String(value == null ? '' : value);
  for (let round = 0; round < 3; round += 1) {
    let next;
    try {
      next = decodeURIComponent(current);
    } catch (e) {
      return null;
    }
    if (next === current) return next;
    current = next;
  }
  return current;
}

/** 凭据样片段检测（解码 → NFKC → 不区分大小写匹配；仅用于最终链接兜底） */
function containsCredential(value) {
  const text = decodeFully(value);
  if (text === null) return true; // 解码失败=可疑
  const normalized = typeof String.prototype.normalize === 'function' ? text.normalize('NFKC') : text;
  return CREDENTIAL_PATTERN.test(normalized);
}

/**
 * 归一化主机：返回 `https://host[:port]` 或空串。
 * 唯一路径：trim + 小写 → 严格正则 → 端口数值校验/443 省略 → 字符串拼接。
 */
function normalizeHost(raw) {
  const value = String(raw == null ? '' : raw).trim().replace(/\/$/, '').toLowerCase();
  if (!value || value.length > HOST_MAX + 32) return '';
  const match = HOST_PATTERN.exec(value);
  if (!match) return '';
  const host = match[1];
  if (host.length > HOST_MAX) return '';
  const portText = match[2] || '';
  let suffix = '';
  if (portText) {
    const port = Number(portText);
    if (!(port >= 1 && port <= 65535)) return '';
    if (port !== 443) suffix = ':' + port; // 白名单已排除前导零；443 省略
  }
  return 'https://' + host + suffix;
}

/** 解析主机：storage 覆写优先，其次集中配置（require 放在函数内，便于纯函数测试注入） */
function resolveHost(override) {
  const fromOverride = normalizeHost(runtimeEnv.storageOverridesAllowed() ? override : '');
  if (fromOverride) return fromOverride;
  let configured = '';
  try {
    configured = require('../config/env').FR_HOST;
  } catch (e) {
    configured = '';
  }
  return normalizeHost(configured);
}

/** next 白名单化：丢弃 query/hash，仅保留允许的站内路径，其余回退 /fr/finance */
function sanitizeNext(next) {
  const raw = String(next == null ? '' : next).trim();
  if (!raw) return DEFAULT_NEXT;
  const path = raw.split(/[?#]/)[0];
  if (path.indexOf('//') >= 0) return DEFAULT_NEXT; // 协议相对或外链
  if (ALLOWED_NEXT.indexOf(path) < 0) return DEFAULT_NEXT;
  return path;
}

/** 生成 /fr/finance 协同链接（主机非法/未配置 → 空串） */
function buildFrFinanceLink(input) {
  const source = input || {};
  const host = normalizeHost(source.host);
  if (!host) return '';
  const next = sanitizeNext(source.next);
  const link = host + '/fr/finance?next=' + encodeURIComponent(next);
  if (containsCredential(link)) return ''; // 兜底（白名单下不应触发）
  return link;
}

/** 页面用便捷入口：override=storage 覆写值 */
function buildFromContext(override, next) {
  return buildFrFinanceLink({ host: resolveHost(override), next });
}

module.exports = {
  ALLOWED_NEXT,
  DEFAULT_NEXT,
  STORAGE_KEY,
  NOT_CONFIGURED,
  normalizeHost,
  resolveHost,
  sanitizeNext,
  decodeFully,
  containsCredential,
  buildFrFinanceLink,
  buildFromContext
};
