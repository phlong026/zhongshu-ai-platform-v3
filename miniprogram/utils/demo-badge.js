/**
 * demo 标识判定唯一源（22 号收尾批 / D-1 三态定稿）：
 * pageState：'api'=整页平台数据（不渲染）；'local'=整页本地（渲染）；'mixed'=混合（本地区块如实标识，渲染）。
 * 游客（无 active 身份）不渲染——登录引导/权限卡已覆盖。
 * 唯一文案：「演示数据 · 本地保存，登录后切换平台数据」。标识零请求。
 */
const http = require('../api/http');

const BADGE_TEXT = '演示数据 · 本地保存，登录后切换平台数据';

/** 有 active 身份（本地 session 有效人员岗位；游客=false） */
function hasActiveIdentity() {
  try {
    const X = require('./access');
    return !!(X.context() && X.context().active);
  } catch (_) {
    return false;
  }
}

/** API 态（有 token 即视为后端会话；降级标志单独判定） */
function hasApiToken() {
  return !!http.token();
}

/**
 * 三态 pageState 判定：
 * - 无 token → 'local'（整页本地，含有效 demo 身份与游客两种，游客由调用方先拦）
 * - 页面降级标志（__platDown/platDegraded/platformFinance.status==='degraded'）→ 'local'
 * - 其余 → 'api'（混合页由页面显式传 'mixed'）
 */
function pageStateOf(page) {
  if (!hasApiToken()) return 'local';
  if (!page) return 'api';
  // setData 后字段在 page.data；兼容实例直挂（refreshPlatView 两处均走 setData，此处兜底双读）
  const d = page.data || page;
  if (page.__platDown || d.platDegraded || page.platDegraded) return 'local';
  const pf = d.platformFinance || page.platformFinance;
  if (pf && pf.status === 'degraded') return 'local';
  return 'api';
}

/**
 * 标识渲染判定（四态矩阵）：
 * 游客 → false；active+local → true；active+mixed → true；active+api → false。
 * @param page 页面实例（读降级标志；可省）
 * @param state 显式 pageState（'mixed' 由混合页调用方传入；缺省按 page 自动判定）
 */
function isDemoMode(page, state) {
  if (!hasActiveIdentity()) return false;
  let ps = state || (page ? pageStateOf(page) : 'local');
  // 降级标志优先于显式 state（API 失败回退本地展示时如实标识——R3 P1-A 伴生修正）
  const d = (page && page.data) || page || {};
  if (page && (page.__platDown || d.platDegraded
      || (d.platformFinance && d.platformFinance.status === 'degraded')
      || (page.platformFinance && page.platformFinance.status === 'degraded'))) {
    ps = 'local';
  }
  return ps !== 'api';
}

const badgeText = () => BADGE_TEXT;

module.exports = { BADGE_TEXT, hasActiveIdentity, hasApiToken, pageStateOf, isDemoMode, badgeText };
