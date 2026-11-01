/**
 * 基-4 工具 verify：断言 utils/platform-loader 纯函数契约（零 wx 依赖，直接 require）。
 * 契约：monorepo docs/backend-parity/15-阶段0-基4v3接入模式模板化-需求与验收.md §五。
 * 用法：node scripts/verify-platform-loader.cjs
 */
const assert = require('node:assert/strict');
const L = require('../miniprogram/utils/platform-loader');

// ===== 初始态 =====
assert.deepEqual(L.initialState(),
  { status: 'idle', rows: [], loadGen: 0, actorKey: '', sessionGen: 0, message: '' }, '初始态形状');

// ===== planLoad：reset / skip 合并 / load 递增 =====
const s0 = L.initialState();
assert.equal(L.planLoad(s0, '').action, 'reset', 'actorKey 空 → reset');
assert.deepEqual(L.planLoad(s0, '', {}), { action: 'reset' });
assert.equal(L.planLoad(s0, 'k1', { sessionGen: 0 }).action, 'load', '就绪态发起 load');
const load1 = L.planLoad(s0, 'k1', { sessionGen: 0 });
assert.equal(load1.loadGen, 1, 'loadGen 递增');
assert.equal(load1.sessionGen, 0, '携带会话代次快照');
const loading1 = { status: 'loading', rows: [], loadGen: 1, actorKey: 'k1', sessionGen: 0, message: '' };
assert.equal(L.planLoad(loading1, 'k1', { sessionGen: 0 }).action, 'skip', '同键同代次 loading 中合并');
assert.equal(L.planLoad(loading1, 'k1', { sessionGen: 0, manual: true }).action, 'load', 'manual 不合并');
assert.equal(L.planLoad(loading1, 'k2', { sessionGen: 0 }).action, 'load', 'actorKey 变化不合并');
assert.equal(L.planLoad(loading1, 'k1', { sessionGen: 1 }).action, 'load', '会话代次变化不合并');

// ===== applyResponse：三重守卫（乱序响应隔离）=====
const ctx1 = { loadGen: 1, sessionGen: 0, actorKey: 'k1', transform: rows => rows.map(r => ({ id: r.id })) };
const applied = L.applyResponse(loading1, [{ id: 'a' }], ctx1, loading1);
assert.equal(applied.changed, true, '守卫命中应用');
assert.deepEqual(applied.state.rows, [{ id: 'a' }], 'transform 注入生效');
assert.equal(applied.state.status, 'ready');
assert.equal(applied.state.loadGen, 1, '状态携带上下文');

const staleGen = L.applyResponse(loading1, [{ id: 'a' }], { ...ctx1, loadGen: 2 }, loading1);
assert.equal(staleGen.changed, false, 'loadGen 不一致丢弃');
const staleSession = L.applyResponse(loading1, [{ id: 'a' }], { ...ctx1, sessionGen: 5 }, loading1);
assert.equal(staleSession.changed, false, 'sessionGen 不一致丢弃');
const staleActor = L.applyResponse(loading1, [{ id: 'a' }], { ...ctx1, actorKey: 'k2' }, loading1);
assert.equal(staleActor.changed, false, 'actorKey 不一致丢弃');

// ===== applyResponse：形状非法 → degrade =====
for (const bad of [null, undefined, {}, { items: 'no' }]) {
  const r = L.applyResponse(loading1, bad, ctx1, loading1);
  assert.equal(r.state.status, 'degraded', '形状非法 degrade：' + JSON.stringify(bad));
  assert.match(r.state.message, /暂时无法加载/);
}
// 数组直给与 {items:[...]} 包装均接受
assert.equal(L.applyResponse(loading1, [], ctx1, loading1).state.status, 'ready');
assert.equal(L.applyResponse(loading1, { items: [] }, ctx1, loading1).state.status, 'ready');
// 无 transform → 恒等
const identity = L.applyResponse(loading1, { items: [{ raw: 1 }] },
  { loadGen: 1, sessionGen: 0, actorKey: 'k1' }, loading1);
assert.deepEqual(identity.state.rows, [{ raw: 1 }]);

// ===== applyFailure：degrade 保留会话 + 守卫丢弃 =====
const failed = L.applyFailure(loading1, { message: '网络异常' }, ctx1, loading1);
assert.equal(failed.state.status, 'degraded');
assert.equal(failed.state.message, '网络异常');
assert.equal(failed.state.sessionGen, 0, 'degrade 保留会话代次（不登出）');
assert.equal(failed.state.actorKey, 'k1');
const staleFail = L.applyFailure(loading1, { message: 'x' }, { ...ctx1, loadGen: 9 }, loading1);
assert.equal(staleFail.changed, false, '失败守卫未命中丢弃');
const failNoMsg = L.applyFailure(loading1, null, ctx1, loading1);
assert.match(failNoMsg.state.message, /网络异常/, 'err 缺失兜底文案');

// ===== actorKey 变化：旧状态不被新键误用 =====
const otherKeyState = { status: 'ready', rows: [{ old: true }], loadGen: 3, actorKey: 'k9', sessionGen: 0, message: '' };
assert.equal(L.planLoad(otherKeyState, 'k1', { sessionGen: 0 }).action, 'load', '换 actorKey 重新 load');
const appliedToOther = L.applyResponse(otherKeyState, [{ id: 'b' }],
  { loadGen: 1, sessionGen: 0, actorKey: 'k1', transform: r => r }, otherKeyState);
assert.equal(appliedToOther.changed, false, '旧键响应不得写入新键状态');

console.log('verify-platform-loader ✔  全部断言通过');
