/**
 * 基-4 工具 verify：断言 utils/platform-loader 纯函数契约（零 wx 依赖，直接 require）。
 * 契约：monorepo docs/backend-parity/15-阶段0-基4v3接入模式模板化-需求与验收.md §五。
 * 断言强度（Codex 补审 P2）：代次用非零递增值贯穿验证递增与保留；transform 输入输出明显不同；
 * 非数组返回拒绝；reset 递增；完整链路串联（加载→降级→重试→乱序）。
 * 用法：node scripts/verify-platform-loader.cjs
 */
const assert = require('node:assert/strict');
const L = require('../miniprogram/utils/platform-loader');

// ===== 初始态 =====
assert.deepEqual(L.initialState(),
  { status: 'idle', rows: [], loadGen: 0, actorKey: '', sessionGen: 0, message: '' }, '初始态形状');

// ===== planLoad：reset 返回递增 loadGen 的新状态（Codex 补审 P1：清态后代次复用会让旧响应击穿守卫）=====
const s0 = L.initialState();
const resetFromBusy = L.planLoad({ status: 'loading', rows: [], loadGen: 5, actorKey: 'k1', sessionGen: 2, message: '' }, '', {});
assert.equal(resetFromBusy.action, 'reset');
assert.equal(resetFromBusy.state.loadGen, 6, 'reset 须递增 loadGen（防旧响应击穿）');
assert.equal(resetFromBusy.state.sessionGen, 2, 'reset 保留会话代次');
assert.equal(resetFromBusy.state.status, 'idle');
assert.deepEqual(L.planLoad(s0, '', {}), { action: 'reset', state: { status: 'idle', rows: [], loadGen: 1, actorKey: '', sessionGen: 0, message: '' } });

// ===== planLoad：skip 合并 / load 递增（非零代次贯穿）=====
const sBusy = { status: 'loading', rows: [], loadGen: 7, actorKey: 'k1', sessionGen: 3, message: '' };
assert.equal(L.planLoad(sBusy, 'k1', { sessionGen: 3 }).action, 'skip', '同键同代次 loading 中合并');
assert.equal(L.planLoad(sBusy, 'k1', { sessionGen: 3, manual: true }).action, 'load', 'manual 不合并');
assert.equal(L.planLoad(sBusy, 'k2', { sessionGen: 3 }).action, 'load', 'actorKey 变化不合并');
assert.equal(L.planLoad(sBusy, 'k1', { sessionGen: 4 }).action, 'load', '会话代次变化不合并');
const load1 = L.planLoad(s0, 'k1', { sessionGen: 3 });
assert.equal(load1.loadGen, 1, '首次 load=1');
const load2 = L.planLoad({ ...s0, loadGen: 1 }, 'k1', { sessionGen: 3 });
assert.equal(load2.loadGen, 2, '二次 load=2（递增保留）');

// ===== applyResponse：三重守卫（乱序响应隔离）+ transform 注入生效 =====
const mark = rows => rows.map(r => ({ id: 'T-' + r.id })); // 输入输出明显不同
const ctx1 = { loadGen: 7, sessionGen: 3, actorKey: 'k1', transform: mark };
const applied = L.applyResponse(sBusy, [{ id: 'a' }], ctx1, sBusy);
assert.equal(applied.changed, true, '守卫命中应用');
assert.deepEqual(applied.state.rows, [{ id: 'T-a' }], 'transform 输入输出明显不同（输入 {id:a} → 输出 {id:T-a}）');
assert.equal(applied.state.status, 'ready');
assert.equal(applied.state.loadGen, 7, 'loadGen 保留非零值');
assert.equal(applied.state.sessionGen, 3);

// 守卫逐维丢弃（宽松比较的变异实现必须在此失败）
assert.equal(L.applyResponse(sBusy, [{ id: 'a' }], { ...ctx1, loadGen: 8 }, sBusy).changed, false, 'loadGen 严格守卫');
assert.equal(L.applyResponse(sBusy, [{ id: 'a' }], { ...ctx1, sessionGen: 4 }, sBusy).changed, false, 'sessionGen 严格守卫');
assert.equal(L.applyResponse(sBusy, [{ id: 'a' }], { ...ctx1, actorKey: 'k2' }, sBusy).changed, false, 'actorKey 严格守卫');
// 混型守卫：数字 1 vs 字符串 '1' 不得相等
const mixedCtx = { loadGen: '7', sessionGen: 3, actorKey: 'k1', transform: mark };
assert.equal(L.applyResponse(sBusy, [{ id: 'a' }], mixedCtx, sBusy).changed, false, '类型混型拒绝');

// ===== applyResponse：形状非法 / transform 返回非数组 → degrade（禁入 ready）=====
for (const bad of [null, undefined, {}, { items: 'no' }]) {
  const r = L.applyResponse(sBusy, bad, ctx1, sBusy);
  assert.equal(r.state.status, 'degraded', '形状非法 degrade：' + JSON.stringify(bad));
  assert.equal(r.changed, true);
}
for (const badTransform of [rows => undefined, rows => ({ no: 1 }), async rows => rows, rows => {
  rows.map(r => r); return undefined; // 漏 return
}]) {
  const r = L.applyResponse(sBusy, [{ id: 'a' }], { ...ctx1, transform: badTransform }, sBusy);
  assert.equal(r.state.status, 'degraded', 'transform 非数组返回须 degrade');
  assert.equal(r.changed, true);
}
const throwing = L.applyResponse(sBusy, [{ id: 'a' }], { ...ctx1, transform: () => { throw new Error('boom'); } }, sBusy);
assert.equal(throwing.state.status, 'degraded', 'transform 抛错 degrade');
// 数组直给与 {items:[...]} 包装均接受；无 transform 恒等
assert.equal(L.applyResponse(sBusy, [], ctx1, sBusy).state.status, 'ready');
assert.equal(L.applyResponse(sBusy, { items: [] }, ctx1, sBusy).state.status, 'ready');
const identity = L.applyResponse(sBusy, { items: [{ raw: 1 }] },
  { loadGen: 7, sessionGen: 3, actorKey: 'k1' }, sBusy);
assert.deepEqual(identity.state.rows, [{ raw: 1 }]);

// ===== applyFailure：degrade 保留会话 + 守卫丢弃 =====
const failed = L.applyFailure(sBusy, { message: '网络异常' }, ctx1, sBusy);
assert.equal(failed.state.status, 'degraded');
assert.equal(failed.state.message, '网络异常');
assert.equal(failed.state.sessionGen, 3, 'degrade 保留会话代次（不登出）');
assert.equal(failed.state.loadGen, 7, 'degrade 保留请求代次（不归零）');
const staleFail = L.applyFailure(sBusy, { message: 'x' }, { ...ctx1, loadGen: 99 }, sBusy);
assert.equal(staleFail.changed, false, '失败守卫未命中丢弃');

// ===== 完整链路串联：加载 → 降级 → 重试 → 乱序迟到响应丢弃 → 清态恢复迟到响应击穿防护 =====
let st = L.initialState();
let gen = 0;
// 1) 发起（sessionGen=2 模拟已登录）
st = { ...st, status: 'loading', loadGen: ++gen, actorKey: 'org:u:role', sessionGen: 2 };
// 2) 失败降级
st = L.applyFailure(st, { message: '超时' }, { loadGen: gen, sessionGen: 2, actorKey: 'org:u:role' }, st).state;
assert.equal(st.status, 'degraded');
// 3) 手动重试成功
st = { ...st, status: 'loading', loadGen: ++gen };
st = L.applyResponse(st, { items: [{ id: 'new' }] },
  { loadGen: gen, sessionGen: 2, actorKey: 'org:u:role', transform: r => r }, st).state;
assert.deepEqual(st.rows, [{ id: 'new' }]);
// 4) 第 gen-1 批迟到成功响应：必须被丢弃（乱序隔离）
const late = L.applyResponse(st, { items: [{ id: 'stale' }] },
  { loadGen: gen - 1, sessionGen: 2, actorKey: 'org:u:role', transform: r => r }, st);
assert.equal(late.changed, false, '迟到旧代次响应丢弃');
// 5) 清态恢复（actorKey 暂空）→ loadGen 递增；随后恢复同 actorKey 再发起；旧批（gen/gen-1）迟到响应均击穿不了
const rst = L.planLoad(st, '', {}).state;
assert.equal(rst.loadGen, gen + 1, '清态递增');
st = { ...rst, status: 'loading', loadGen: ++gen, actorKey: 'org:u:role' };
for (const oldGen of [gen - 2, gen - 1]) {
  const lateAfterReset = L.applyResponse(st, { items: [{ id: 'stale-' + oldGen }] },
    { loadGen: oldGen, sessionGen: 2, actorKey: 'org:u:role', transform: r => r }, st);
  assert.equal(lateAfterReset.changed, false, '清态恢复后旧代次响应丢弃（gen=' + oldGen + '）');
}
const fresh = L.applyResponse(st, { items: [{ id: 'fresh' }] },
  { loadGen: gen, sessionGen: 2, actorKey: 'org:u:role', transform: r => r }, st);
assert.deepEqual(fresh.state.rows, [{ id: 'fresh' }], '当前批正常写入');

console.log('verify-platform-loader ✔  全部断言通过');
