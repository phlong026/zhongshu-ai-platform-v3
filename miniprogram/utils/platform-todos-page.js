/**
 * 平台待办的页面接入辅助（可使用 setData/wx；视图映射仍全部在 platform-todos.js 纯函数）。
 * 页面接入三步：init(page) → onShow 时 refresh(page, false) → wxml 绑定 open/retry。
 */
const http = require('../api/http');
const todos = require('../api/todos');
const nav = require('./platform-nav');
const PT = require('./platform-todos');

function init(page) {
  page.__ptLoadGen = 0;
  page.setData({ platformTodos: PT.initialState() });
}

/** onShow 或手动重载入口；manual=true 强制新请求（绕过自动合并） */
function refresh(page, manual) {
  const auth = require('../api/auth'); // 惰性引用，避免打包循环
  const post = auth.currentApiPost();
  const state = page.data.platformTodos || PT.initialState();
  const decision = PT.planLoad(state, post, {
    manual: !!manual,
    sessionGen: () => http.sessionGeneration(),
    nextLoadGen: () => { page.__ptLoadGen = (page.__ptLoadGen || 0) + 1; return page.__ptLoadGen; }
  });
  if (decision.action === 'reset') { page.setData({ platformTodos: PT.initialState() }); return; }
  if (decision.action === 'skip') return;
  const ctx = { loadGen: decision.loadGen, sessionGen: decision.sessionGen, postKey: decision.postKey };
  page.setData({ platformTodos: { ...PT.initialState(), status: 'loading', postKey: decision.postKey, sessionGen: decision.sessionGen } });
  todos.list().then(res => {
    const r = PT.applyResponse(page.data.platformTodos, res, ctx, current(page));
    if (r.changed) page.setData({ platformTodos: r.state });
  }).catch(err => {
    if (err && err.silent) return; // 旧会话的 401 已被 http 层静默丢弃
    if (err && err.code === 401) { // 当前会话 401：http 层已清会话并回调 onUnauthorized，视图直接回 idle
      page.setData({ platformTodos: PT.initialState() });
      return;
    }
    const r = PT.applyFailure(page.data.platformTodos, err, ctx, current(page));
    if (r.changed) page.setData({ platformTodos: r.state });
  });
}

function retry(page) {
  refresh(page, true);
}

function open(page, e) {
  const d = e.detail && e.detail.item || e.currentTarget.dataset;
  if (String(d.clickable) !== 'true') return;
  nav.open(d.path);
}

function current(page) {
  const auth = require('../api/auth');
  const post = auth.currentApiPost();
  return { loadGen: page.__ptLoadGen || 0, sessionGen: http.sessionGeneration(), postKey: post ? PT.postKeyOf(post) : '' };
}

module.exports = { init, refresh, retry, open };
