#!/usr/bin/env node
/**
 * M02-T01 认证失效与岗位失效契约（I02/I05）：
 * - HTTP401 与 HTTP200/body.code=401 同契约（I02：后端认证入口以 HTTP200 写 CommonResult(401)）
 * - 旧会话 401 静默；钱包 skipSessionGuard 隔离（含 body401）
 * - 岗位失效 1_103_000_002（token 仍有效）：postInvalid 回调+恢复流；恢复期去抖；禁止切回失效岗
 * - 403 不误判账号退出
 * 用法：node scripts/verify-auth-session-contract.cjs
 */
const assert = require('node:assert/strict');

// ---- 内存 wx 环境：真实 http.js / auth.js 直接运行 ----
const storage = new Map();
let requestPlans = []; // 每次请求弹出一个计划：{statusCode, data, delayMs?} 或 fn(options)；按请求发起顺序消费
const requests = [];   // 已发起请求记录（waitUntil 等待恢复流 myPosts 发起用）
let reLaunchCalls = 0;
global.wx = {
  getStorageSync: k => (storage.has(k) ? storage.get(k) : ''),
  setStorageSync: (k, v) => storage.set(k, v),
  removeStorageSync: k => storage.delete(k),
  getAccountInfoSync: () => ({ miniProgram: { appId: 'wx-test' } }),
  login: o => o.fail && o.fail({ errMsg: 'test-no-login' }),
  reLaunch: o => { reLaunchCalls++; },
  showModal: o => { if (o && o.success) o.success({ confirm: true }); },
  request(options) {
    requests.push(options);
    const plan = requestPlans.shift();
    setTimeout(() => {
      if (!plan) { options.fail && options.fail({ errMsg: 'request:fail no-plan ' + options.url }); return; }
      const res = typeof plan === 'function' ? plan(options) : plan;
      options.success(res);
    }, (plan && plan.delayMs) || 0);
  }
};

const http = require('../miniprogram/api/http');
const auth = require('../miniprogram/api/auth');

let pass = 0, fail = 0;
const ok = (cond, name) => { if (cond) { pass++; console.log('  ok - ' + name); } else { fail++; console.log('  FAIL - ' + name); } };
const reset = () => { storage.clear(); requestPlans = []; };
const wait = ms => new Promise(r => setTimeout(r, ms || 5));
// 等待谓词成立（恢复流 myPosts 发起确认；Codex R2：竞态用例须先确认在途挂起再改变会话）
async function waitUntil(pred, timeoutMs) {
  const deadline = Date.now() + (timeoutMs || 1000);
  while (Date.now() < deadline) { if (pred()) return true; await wait(5); }
  return pred();
}

const TOKEN_KEY = http.TOKEN_KEY, POST_KEY = http.POST_KEY;
const POST_INVALID_CODE = 1103000002; // PlatformAuthzErrorCodes.POST_NOT_USABLE 1_103_000_002

(async () => {
  let unauthorizedCalls = 0;
  let postInvalidCalls = [];
  // 复刻 auth.js 模块级生产注册（清会话后 reLaunch 登录页）；参数观察 + 导航复刻
  http.onUnauthorized(() => { unauthorizedCalls++; wx.reLaunch({ url: '/pages/account/index' }); });
  // 复刻 auth.js 模块级注册的生产链条目（参数观察 + 真实恢复流；auth 自身注册在此被覆盖但逻辑同构）
  http.onPostInvalid(postId => {
    postInvalidCalls.push(postId);
    auth.recoverPostInvalid(postId).catch(() => {});
  });

  console.log('[auth-contract] HTTP 401（既有契约回归锁定）');
  reset();
  storage.set(TOKEN_KEY, 't-old'); storage.set(POST_KEY, '11');
  requestPlans.push({ statusCode: 401, data: {} });
  let err = await http.get('/x').then(() => null, e => e);
  await wait();
  ok(err && err.code === 401, 'HTTP401 reject code=401');
  ok(!storage.has(TOKEN_KEY) && !storage.has(POST_KEY), 'HTTP401 清 token+岗位缓存');
  ok(unauthorizedCalls === 1, 'HTTP401 触发 onUnauthorized 引导登录');

  console.log('[auth-contract] HTTP200/body401 与 HTTP401 同契约（I02）');
  reset(); unauthorizedCalls = 0;
  storage.set(TOKEN_KEY, 't-b'); storage.set(POST_KEY, '12');
  requestPlans.push({ statusCode: 200, data: { code: 401, msg: '账号未登录' } });
  err = await http.get('/x').then(() => null, e => e);
  await wait();
  ok(err && err.code === 401, 'body401 reject code=401');
  ok(!storage.has(TOKEN_KEY) && !storage.has(POST_KEY), 'body401 清 token+岗位缓存');
  ok(unauthorizedCalls === 1, 'body401 触发 onUnauthorized');

  console.log('[auth-contract] 旧会话 401 不破坏新会话');
  reset(); unauthorizedCalls = 0;
  storage.set(TOKEN_KEY, 't-A'); storage.set(POST_KEY, '21');
  requestPlans.push({ statusCode: 401, data: {} });
  const p1 = http.get('/x').then(() => null, e => e);
  http.saveSession({ token: 't-B', postId: '22' }); // 新会话（代次+1），旧响应随后才到
  err = await p1; await wait();
  ok(err && err.silent === true, '旧会话 HTTP401 静默丢弃');
  ok(storage.get(TOKEN_KEY) === 't-B' && storage.get(POST_KEY) === '22' && unauthorizedCalls === 0, '旧会话 HTTP401 不清新会话');
  reset();
  storage.set(TOKEN_KEY, 't-A2'); storage.set(POST_KEY, '31');
  requestPlans.push({ statusCode: 200, data: { code: 401 } });
  const p2 = http.get('/x').then(() => null, e => e);
  http.saveSession({ token: 't-B2', postId: '32' });
  err = await p2; await wait();
  ok(err && err.silent === true, '旧会话 body401 静默丢弃');
  ok(storage.get(TOKEN_KEY) === 't-B2' && unauthorizedCalls === 0, '旧会话 body401 不清新会话');

  console.log('[auth-contract] 钱包 skipSessionGuard 隔离');
  reset(); unauthorizedCalls = 0;
  storage.set(TOKEN_KEY, 't-p'); storage.set(POST_KEY, '41');
  requestPlans.push({ statusCode: 401, data: {} });
  err = await http.request('POST', '/wallet/x', null, {}, { skipSessionGuard: true }).then(() => null, e => e);
  ok(err && err.code === 401 && storage.get(TOKEN_KEY) === 't-p' && unauthorizedCalls === 0, '钱包 HTTP401 不动平台会话');
  requestPlans.push({ statusCode: 200, data: { code: 401 } });
  err = await http.request('POST', '/wallet/x', null, {}, { skipSessionGuard: true }).then(() => null, e => e);
  ok(err && err.code === 401 && storage.get(TOKEN_KEY) === 't-p' && unauthorizedCalls === 0, '钱包 body401 不动平台会话');

  console.log('[auth-contract] 岗位失效 1_103_000_002（I05：token 仍有效，区别于账号失效）');
  reset(); unauthorizedCalls = 0; postInvalidCalls = []; reLaunchCalls = 0;
  storage.set(TOKEN_KEY, 't-v'); storage.set(POST_KEY, '11');
  requestPlans.push({ statusCode: 200, data: { code: POST_INVALID_CODE, msg: '工作身份无效或已被撤销，请重新选择' } }); // 触发请求
  requestPlans.push({ statusCode: 200, data: { code: 0, data: [{ postId: 11 }, { postId: 22 }] } }); // myPosts：恢复切到 22
  err = await http.get('/biz').then(() => null, e => e);
  await wait();
  ok(err && err.postInvalid === true && err.code === POST_INVALID_CODE, '岗位失效 reject 带 postInvalid 标记');
  ok(JSON.stringify(postInvalidCalls) === '["11"]', 'postInvalid 回调携带失效 postId');
  ok(storage.get(TOKEN_KEY) === 't-v' && unauthorizedCalls === 0, '岗位失效保留 token、不触发账号登出');
  ok(String(storage.get(POST_KEY)) === '22', '恢复流已自动切到有效岗位');
  // 钱包域岗位码同样不触发平台恢复流
  reset(); postInvalidCalls = [];
  storage.set(TOKEN_KEY, 't-w');
  requestPlans.push({ statusCode: 200, data: { code: POST_INVALID_CODE } });
  err = await http.request('POST', '/wallet/x', null, {}, { skipSessionGuard: true }).then(() => null, e => e);
  await wait();
  ok(err && err.postInvalid === true && postInvalidCalls.length === 0, '钱包域岗位失效不触发平台恢复回调');

  console.log('[auth-contract] 恢复期去抖（并发失效只触发一次恢复流）');
  http.endPostInvalidRecovery(); // 场景隔离：G 场景触发后由恢复流复位的标志在此显式复位（生产中 auth.recoverPostInvalid 两路复位）
  reset(); postInvalidCalls = [];
  storage.set(TOKEN_KEY, 't-v'); storage.set(POST_KEY, '11');
  requestPlans.push({ statusCode: 200, data: { code: POST_INVALID_CODE } });
  requestPlans.push({ statusCode: 200, data: { code: POST_INVALID_CODE } });
  await http.get('/biz1').catch(() => {});
  await http.get('/biz2').catch(() => {});
  await wait();
  ok(postInvalidCalls.length === 1, '恢复未结束前后续失效不再触发回调');
  ok(postInvalidCalls[0] === '11', '去抖窗口内回调参数正确');

  console.log('[auth-contract] 403 不误判账号退出（回归锁定）');
  reset(); unauthorizedCalls = 0; postInvalidCalls = []; reLaunchCalls = 0;
  storage.set(TOKEN_KEY, 't-3'); storage.set(POST_KEY, '13');
  requestPlans.push({ statusCode: 403, data: { code: 403, msg: '无权限' } });
  err = await http.get('/x').then(() => null, e => e);
  requestPlans.push({ statusCode: 200, data: { code: 403, msg: '无权限' } });
  const err2 = await http.get('/x').then(() => null, e => e);
  ok(err && err.code === 403 && err2 && err2.code === 403, 'HTTP/body 403 普通 reject');
  ok(storage.get(TOKEN_KEY) === 't-3' && storage.get(POST_KEY) === '13' && unauthorizedCalls === 0 && postInvalidCalls.length === 0, '403 不清会话、不触发登出/岗位恢复');

  console.log('[auth-contract] auth.recoverPostInvalid 四态恢复');
  // J1 多岗：切到与失效岗不同的首个有效岗（禁止循环切回）
  reset(); postInvalidCalls = []; unauthorizedCalls = 0; reLaunchCalls = 0;
  storage.set(TOKEN_KEY, 't-r'); storage.set(POST_KEY, '11');
  auth.savePosts([{ postId: 11, orgId: 1, roleCode: 'sales' }, { postId: 22, orgId: 1, roleCode: 'finance' }]);
  requestPlans.push({ statusCode: 200, data: { code: 0, data: [{ postId: 11, roleCode: 'sales' }, { postId: 22, roleCode: 'finance' }] } });
  const r1 = await auth.recoverPostInvalid('11');
  ok(r1 && r1.status === 'switched' && String(auth.currentApiPost().postId) === '22', '多岗：自动切到另一有效岗');
  ok(String(storage.get(POST_KEY)) === '22', '当前岗位已更新为服务端真源（不回切失效岗）');
  ok(storage.get(TOKEN_KEY) === 't-r', '恢复流不清 token');
  requestPlans.push({ statusCode: 200, data: { code: POST_INVALID_CODE } });
  await http.get('/biz').catch(() => {});
  await wait();
  ok(postInvalidCalls.length === 1, '恢复完成后去抖复位，可再次触发');

  // J2 仅剩失效岗
  reset(); unauthorizedCalls = 0; postInvalidCalls = []; reLaunchCalls = 0;
  storage.set(TOKEN_KEY, 't-r2'); storage.set(POST_KEY, '11');
  requestPlans.push({ statusCode: 200, data: { code: 0, data: [{ postId: 11 }] } });
  const r2 = await auth.recoverPostInvalid('11');
  ok(r2 && r2.status === 'none' && !storage.has(TOKEN_KEY) && !storage.has(POST_KEY), '仅剩失效岗：无替代→登出引导重登');
  ok(unauthorizedCalls === 1, '无替代岗位触发登录引导链');
  ok(reLaunchCalls === 1, '登录引导真实跳转登录页（生产注册链）');

  // J3 全部撤销
  reset(); unauthorizedCalls = 0; postInvalidCalls = []; reLaunchCalls = 0;
  storage.set(TOKEN_KEY, 't-r3'); storage.set(POST_KEY, '11');
  requestPlans.push({ statusCode: 200, data: { code: 0, data: [] } });
  const r3 = await auth.recoverPostInvalid('11');
  ok(r3 && r3.status === 'none' && !storage.has(TOKEN_KEY), '全部撤销：登出');

  // J4 组织停用：posts/mine 对停用任职返回 null 元素，须过滤后选有效岗
  reset(); unauthorizedCalls = 0; postInvalidCalls = []; reLaunchCalls = 0;
  storage.set(TOKEN_KEY, 't-r4'); storage.set(POST_KEY, '11');
  requestPlans.push({ statusCode: 200, data: { code: 0, data: [null, { postId: 33, roleCode: 'finance' }] } });
  const r4 = await auth.recoverPostInvalid('11');
  ok(r4 && r4.status === 'switched' && String(auth.currentApiPost().postId) === '33', '组织停用 null 元素被过滤，切到有效岗');

  // J5 失败路径复位（Codex R1 P2-4：经生产链路——岗位失效请求触发恢复流→myPosts 失败→复位）
  reset(); unauthorizedCalls = 0; postInvalidCalls = []; reLaunchCalls = 0;
  storage.set(TOKEN_KEY, 't-r5'); storage.set(POST_KEY, '11');
  requestPlans.push({ statusCode: 200, data: { code: POST_INVALID_CODE } }); // 触发请求（先发起先消费）
  requestPlans.push({ statusCode: 500, data: {} }); // myPosts 刷新失败（恢复流随后发起）
  await http.get('/biz').catch(() => {});
  await wait();
  ok(JSON.stringify(postInvalidCalls) === '["11"]', '失败场景经生产链路触发一次恢复');
  ok(!storage.has(TOKEN_KEY) && unauthorizedCalls === 1, '刷新失败：登出并引导重登');
  // 复位验证：随后再次岗位失效必须能再次触发（若失败分支漏复位则此处不再增加）
  storage.set(TOKEN_KEY, 't-r5b'); storage.set(POST_KEY, '11');
  requestPlans.push({ statusCode: 200, data: { code: POST_INVALID_CODE } });
  requestPlans.push({ statusCode: 500, data: {} });
  await http.get('/biz').catch(() => {});
  await wait();
  ok(JSON.stringify(postInvalidCalls) === '["11","11"]', '失败路径复位去抖：后续失效可再次触发');

  console.log('[auth-contract] 恢复流会话代次护栏（真实在途竞态）');
  // K1 恢复期间手动切岗：确认 myPosts 已发起挂起后再切岗，迟到恢复不覆盖手动选择
  reset(); unauthorizedCalls = 0; postInvalidCalls = []; reLaunchCalls = 0; reLaunchCalls = 0; requests.length = 0;
  storage.set(TOKEN_KEY, 't-k1'); storage.set(POST_KEY, '11');
  auth.savePosts([{ postId: 11 }, { postId: 22 }, { postId: 99 }]);
  requestPlans.push({ statusCode: 200, data: { code: POST_INVALID_CODE } }); // biz 触发
  requestPlans.push({ statusCode: 200, delayMs: 80, data: { code: 0, data: [{ postId: 11 }, { postId: 22 }, { postId: 99 }] } }); // myPosts 挂起
  http.get('/biz').catch(() => {});
  ok(await waitUntil(() => requests.some(r => String(r.url).includes('/platform/posts/mine'))), 'K1 恢复流已发起 myPosts');
  auth.switchPost(99); // 在途挂起期间手动切岗（代次+1）
  await wait(120);
  ok(String(storage.get(POST_KEY)) === '99' && String(auth.currentApiPost().postId) === '99', 'K1 迟到恢复不覆盖手动切岗');
  ok(storage.get(TOKEN_KEY) === 't-k1', 'K1 token 不受迟到恢复影响');

  // K2 恢复期间 401 清场：清场态不被迟到恢复回填
  reset(); unauthorizedCalls = 0; postInvalidCalls = []; reLaunchCalls = 0; reLaunchCalls = 0; requests.length = 0;
  storage.set(TOKEN_KEY, 't-k2'); storage.set(POST_KEY, '11');
  requestPlans.push({ statusCode: 200, data: { code: POST_INVALID_CODE } });
  requestPlans.push({ statusCode: 200, delayMs: 80, data: { code: 0, data: [{ postId: 11 }, { postId: 22 }] } });
  http.get('/biz').catch(() => {});
  ok(await waitUntil(() => requests.some(r => String(r.url).includes('/platform/posts/mine'))), 'K2 恢复流已发起 myPosts');
  http.clearSession(); // 在途挂起期间另一请求 401 已清场（代次+1）
  await wait(120);
  ok(!storage.has(TOKEN_KEY) && !storage.has(POST_KEY), 'K2 清场态不被迟到恢复回填');
  ok(unauthorizedCalls === 0 && reLaunchCalls === 0, 'K2 恢复流不重复触发登录引导');

  // K3 恢复失败期间登录新账号：失败分支不登出新会话、不按约定抛错（返回 stale）
  reset(); unauthorizedCalls = 0; postInvalidCalls = []; reLaunchCalls = 0; reLaunchCalls = 0; requests.length = 0;
  storage.set(TOKEN_KEY, 't-k3'); storage.set(POST_KEY, '11');
  requestPlans.push({ statusCode: 200, data: { code: POST_INVALID_CODE } });
  requestPlans.push({ statusCode: 500, delayMs: 80, data: {} }); // myPosts 失败
  const k3res = await (async () => { // 捕获恢复流返回值（模块级注册吞掉结果，这里直接观察会话状态+经生产链路）
    http.get('/biz').catch(() => {});
    ok(await waitUntil(() => requests.some(r => String(r.url).includes('/platform/posts/mine'))), 'K3 恢复流已发起 myPosts');
    http.saveSession({ token: 't-B', postId: '77' }); // 在途挂起期间登录账号 B（代次+1）
    storage.set(TOKEN_KEY, 't-B');
    await wait(120);
    return null;
  })();
  ok(storage.get(TOKEN_KEY) === 't-B' && storage.get(POST_KEY) === '77', 'K3 恢复失败不登出期间登录的新会话');
  ok(unauthorizedCalls === 0 && reLaunchCalls === 0, 'K3 不对新账号触发登录引导');

  console.log('[auth-contract] 批次#5：saveLogin 岗位上下文隔离（M02-T02/I03）');
  // L1: A 登录态 → B saveLogin → 旧岗位缓存全清
  reset(); reLaunchCalls = 0; unauthorizedCalls = 0; postInvalidCalls = [];
  storage.set(TOKEN_KEY, 't-A'); storage.set(POST_KEY, '11');
  auth.savePosts([{ postId: 11, roleCode: 'finance' }]);
  auth.saveLogin({ accessToken: 't-B', posts: [] }); // B 登录且响应无 posts
  ok(storage.get(TOKEN_KEY) === 't-B', 'L1 新 token 已保存');
  ok(!storage.get(POST_KEY) && !(auth.apiPosts() || []).length, 'L1 旧岗位 postId 与列表缓存全部隔离');
  ok(auth.currentApiPost() === null, 'L2 新 token 不搭旧岗位缓存（currentApiPost 为空）');
  // L3: B 的 posts 到达后重建（正常链路）
  auth.savePosts([{ postId: 55, roleCode: 'sales' }]);
  http.saveSession({ postId: 55 });
  ok(String(auth.currentApiPost().postId) === '55', 'L3 B 任职列表到达后重建岗位上下文');

  // L4: postLoadGuard 守卫语义
  reset();
  const g1 = auth.postLoadGuard();
  http.saveSession({ token: 'x' });
  ok(g1() === false, 'L4 会话变化后守卫返回 false（迟到任职结果应丢弃）');
  const g2 = auth.postLoadGuard();
  ok(g2() === true, 'L4 同会话内守卫返回 true');

  console.log('[auth-contract] 批次#5：统一退出清理范围（M02-T03/I04）');
  // M1: 纯 API 登录（本地无演示会话）→ 通用退出清 API token
  reset(); reLaunchCalls = 0;
  storage.set(TOKEN_KEY, 't-api'); storage.set(POST_KEY, '31');
  const SA = require('../miniprogram/utils/session-actions');
  SA.logout(); // showModal mock 直接 confirm
  await wait(10);
  ok(!storage.has(TOKEN_KEY) && !storage.has(POST_KEY), 'M1 纯 API 账号：通用退出清 API 令牌与岗位');
  ok(reLaunchCalls === 1, 'M1 退出后回个人页');
  // M2: 当前 API 多岗位会话退出 → 令牌/岗位全清，本地业务记录保留
  reset(); reLaunchCalls = 0;
  http.saveSession({ token: 't-api-multi', postId: '41' });
  auth.savePosts([{ postId: 41, roleCode: 'sales' }, { postId: 42, roleCode: 'sales-lead' }]);
  const A2 = require('../miniprogram/utils/account');
  A2.mutate(st => { st.customers = [{ id: 'retained-local-record', name: '保留的本地记录' }]; });
  const customersBefore = JSON.stringify(A2.load().customers || []);
  SA.logout();
  await wait(10);
  ok(!storage.has(TOKEN_KEY) && !storage.has(POST_KEY) && auth.apiPosts().length === 0, 'M2 多岗位 API 账号：令牌与岗位上下文被清');
  ok(!A2.load().session.loggedIn, 'M2 API 退出不创建本地登录态');
  ok(JSON.stringify(A2.load().customers || []) === customersBefore, 'M2 本地业务记录（客户列表）保留');
  // M3: 本地未登录且无 API token → 退出仍安全（不再直接 return）
  reset(); reLaunchCalls = 0;
  SA.logout();
  await wait(10);
  ok(reLaunchCalls === 1, 'M3 纯净状态退出仍正常走完流程');

  console.log(`\n通过 ${pass}，失败 ${fail}`);  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('脚本异常:', e); process.exit(1); });
