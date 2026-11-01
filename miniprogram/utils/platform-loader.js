/**
 * 平台数据拉取执行器（基-4 工具，M3 乱序/串台隔离的通用骨架——纯函数，零 wx/Storage 依赖）。
 * 契约：monorepo docs/backend-parity/15-阶段0-基4v3接入模式模板化-需求与验收.md §五（与 platform-todos 同名异型对照见该节）。
 * 供新页面接入后端数据时采用；既有实现（platform-todos 等）不动。
 */
const STATUSES = ['idle', 'loading', 'ready', 'degraded'];

/** 初始状态 */
function initialState() {
  return { status: 'idle', rows: [], loadGen: 0, actorKey: '', sessionGen: 0, message: '' };
}

/**
 * 触发决策：
 * - actorKey 空 → {action:'reset'}（未就绪/已登出，调用方清态）；
 * - 同 actorKey + 同会话代次 + loading 中 + 非 manual → {action:'skip'}（合并重复触发）；
 * - 其余 → {action:'load', loadGen, actorKey, sessionGen}（发起请求，携带三重上下文快照）。
 */
function planLoad(state, actorKey, ctx) {
  if (!actorKey) return { action: 'reset' };
  const sessionGen = ctx.sessionGen;
  const merged = ctx.manual !== true && state.status === 'loading'
    && state.actorKey === actorKey && state.sessionGen === sessionGen;
  if (merged) return { action: 'skip' };
  return { action: 'load', loadGen: state.loadGen + 1, actorKey, sessionGen };
}

/** 守卫：三重上下文（loadGen/sessionGen/actorKey）与发起时一致才应用，否则静默丢弃（乱序响应隔离） */
function valid(ctx, now) {
  return ctx.loadGen === now.loadGen && ctx.sessionGen === now.sessionGen && ctx.actorKey === now.actorKey;
}

/**
 * 请求成功：守卫命中才应用 ctx.transform(rows)（调用方注入的行映射；缺省恒等）并置 ready；
 * 响应形状非法（非对象/非数组）→ degrade；守卫未命中 → 原样返回（changed:false）。
 */
function applyResponse(state, res, ctx, now) {
  if (!valid(ctx, now)) return { state, changed: false };
  const rows = Array.isArray(res && res.items) ? res.items : (Array.isArray(res) ? res : null);
  if (rows === null) {
    return { state: degrade(state, '数据暂时无法加载，请稍后刷新'), changed: true };
  }
  const transform = typeof ctx.transform === 'function' ? ctx.transform : (rows => rows);
  return {
    changed: true,
    state: { status: 'ready', rows: transform(rows), loadGen: ctx.loadGen, actorKey: ctx.actorKey,
      sessionGen: ctx.sessionGen, message: '' }
  };
}

/**
 * 请求失败：守卫命中 → degrade（保留会话，不登出，message=err.message）；守卫未命中 → 原样返回。
 */
function applyFailure(state, err, ctx, now) {
  if (!valid(ctx, now)) return { state, changed: false };
  return { changed: true, state: degrade(state, (err && err.message) || '网络异常，请稍后重试') };
}

function degrade(state, message) {
  return { status: 'degraded', rows: [], loadGen: state.loadGen, actorKey: state.actorKey,
    sessionGen: state.sessionGen, message };
}

module.exports = { STATUSES, initialState, planLoad, applyResponse, applyFailure };
