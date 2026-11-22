#!/usr/bin/env node
/**
 * M11-T02 消息/资料请求代次守卫（页面级，Codex R1 P2-2 重写）：role-page-runtime 真实驱动 account 页方法。
 * - 同会话重叠刷新：页面 syncApiMessages(A 挂起) → 再发起(B 即时) → B 应用 → 释放 A → 旧响应被序号守卫丢弃
 * - 换会话：saveLogin gen+1 → A 迟到响应被会话+序号双守卫丢弃
 * - 退出：clearSession 后无 token 分支清空
 * - 资料：syncApiProfile 同款分域守卫（A 挂起 → B 应用 → A 释放不污染页面与存储）
 * mock 按「请求发起顺序」弹出 handler；挂起 handler 捕获 resolve 供测试释放。
 * 用法：node scripts/verify-message-session-guard.cjs
 */
const assert = require('node:assert/strict');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  let pass = 0, fail = 0;
  const ok = (cond, name) => { if (cond) { pass++; console.log('  ok - ' + name); } else { fail++; console.log('  FAIL - ' + name); } };
  const wait = ms => new Promise(r => setTimeout(r, ms || 5));

  const handlers = []; // 按请求发起顺序弹出
  const holds = [];    // 挂起的 {resolve, data}
  global.wx.request = options => {
    const h = handlers.shift();
    if (!h) { options.fail && options.fail({ errMsg: 'request:fail no-handler ' + options.url }); return; }
    h(options);
  };
  const holdHandler = data => { const h = options => { holds.push({ resolve: d => options.success({ statusCode: 200, data: { code: 0, data: d } }), data }); }; handlers.push(h); };
  const replyHandler = data => { handlers.push(options => options.success({ statusCode: 200, data: { code: 0, data } })); };
  const MSG = (id, title) => ({items:[{ id, type: 'BUSINESS', title, description: '', read: false, route: '', objectId: null, createTime: '2026-11-01T09:00:00+08:00' }],unread:2,pending:0,snapshotAt:'2026-11-17T09:00:00+08:00'});
  const ID_A = '9007199254740999002', ID_B = '9007199254740999001';

  const http = require('../miniprogram/api/http');
  // 切生产环境批后 API_BASE 默认为空——http 层无基址直接拒绝请求；脚本覆写联调基址（与 zs-api-base 机制一致）
  wx.setStorageSync('zs-api-base', 'https://api.verify.test');
  const auth = require('../miniprogram/api/auth');
  const Account = require('../miniprogram/utils/account');

  // 切生产收尾批（Codex R1 P2-3 恢复）：演示登录已下线——改为直接构造最小本地态（等价 loginDemo('sales-dual')）
  const MKEY = require('../miniprogram/utils/storage-keys').management;
  Account.mutate(s => {
    Object.assign(s.session, { loggedIn: true, companyId: 'quanzhou', staffId: 'sales-dual', userId: 'sales-dual', role: 'sales' });
    s.companies = [{ id: 'quanzhou', name: '门店', type: 'store', roles: ['sales'] }];
  });
  wx.setStorageSync(MKEY, { version: 1, companyId: 'quanzhou', staff: [{ id: 'sales-dual', personId: 'sales-dual', name: '双岗业务员', roles: ['sales'], teamIds: { sales: 'sales' } }] });
  auth.saveLogin({ accessToken: 'token-A', userId: 7 });
  auth.savePosts([{ postId: 11, userId: 7, orgId: 31, roleCode: 'sales', orgType: 'store' }]);
  http.saveSession({ postId: 11 });

  const open = url => { wx.reLaunch({ url }); return getCurrentPages().at(-1); };
  let page = open('/pages/account/index?view=messages');
  await wait(30);
  handlers.length = 0; holds.length = 0; // 清掉 onShow 自发的请求残留

  console.log('[msg-guard] 同会话重叠刷新：A 挂起 → B 即时 → 释放 A → 旧响应丢弃');
  holdHandler(MSG(ID_A, 'A-消息'));           // 列表#1（A 数据，挂起）
  page.syncApiMessages();
  replyHandler(MSG(ID_B, 'B-消息'));           // 列表#2（B 数据，即时）
  page.syncApiMessages();
  await wait(30);
  ok((page.apiMessages || []).some(m => m.title === 'B-消息'), '后发起的 B 刷新已应用');
  const hold = holds.shift();
  ok(!!hold, 'A 刷新确实被挂起（Codex R1 P2-2 修复前提）');
  hold.resolve(MSG(ID_A, 'A-消息'));
  await wait(30);
  ok((page.apiMessages || []).every(m => m.title !== 'A-消息') && (page.apiMessages || []).some(m => m.title === 'B-消息'), 'A 迟到响应被序号守卫丢弃（仍为 B）');

  console.log('[msg-guard] 换会话：saveLogin gen+1 → 挂起的 A 响应被双守卫丢弃');
  holds.length = 0; handlers.length = 0;
  holdHandler(MSG(ID_A, 'A-旧消息'));
  page.syncApiMessages();
  auth.saveLogin({ accessToken: 'token-B', userId: 8 }); // 换 B（gen+1，M02-T02 隔离）
  auth.savePosts([{ postId: 22, userId: 8, orgId: 31, roleCode: 'sales', orgType: 'store' }]);
  http.saveSession({ postId: 22 });
  replyHandler(MSG(ID_B, 'B-消息'));
  page.syncApiMessages();
  await wait(30);
  ok((page.apiMessages || []).some(m => m.title === 'B-消息'), 'B 会话消息应用');
  const hold2 = holds.shift();
  if (hold2) { hold2.resolve(MSG(ID_A, 'A-旧消息')); }
  await wait(30);
  ok((page.apiMessages || []).every(m => m.title !== 'A-旧消息'), '换会话后 A 迟到响应被双守卫丢弃');

  console.log('[msg-guard] 换会话/退出后无新刷新：挂起响应被组合守卫拦截（Codex R2 P2 场景）');
  // N1: A 消息挂起 → 换 B（同域不发新刷新）→ 释放 A → 会话守卫拦截
  auth.saveLogin({ accessToken: 'token-A3', userId: 7 });
  auth.savePosts([{ postId: 11, userId: 7, orgId: 31, roleCode: 'sales', orgType: 'store' }]);
  http.saveSession({ postId: 11 });
  page = getCurrentPages().at(-1);
  holds.length = 0; handlers.length = 0;
  holdHandler(MSG(ID_A, 'A-挂起消息'));
  page.syncApiMessages();
  const h1 = holds.shift();
  ok(!!h1, 'N1 A 消息挂起确认');
  auth.saveLogin({ accessToken: 'token-B3', userId: 8 }); // 换 B，同域不发新刷新
  auth.savePosts([{ postId: 22, userId: 8, orgId: 31, roleCode: 'sales', orgType: 'store' }]);
  http.saveSession({ postId: 22 });
  await wait(30);
  ok((page.apiMessages || []).every(m => m.title !== 'A-挂起消息'), 'N1 换会话后无新刷新：释放前不写入');
  handlers.length = 0;
  if (h1) h1.resolve(MSG(ID_A, 'A-挂起消息'));
  await wait(30);
  ok((page.apiMessages || []).every(m => m.title !== 'A-挂起消息'), 'N1 换会话后无新刷新：旧消息响应被组合守卫拦截');
  // N2: 资料挂起 → 退出 → 释放 → 不回填
  holds.length = 0; handlers.length = 0;
  holdHandler({ id: 8, nickname: 'A-挂起资料', mobile: '13800000000', avatar: null });
  page.syncApiProfile();
  const h2 = holds.shift();
  ok(!!h2, 'N2 A 资料挂起确认');
  auth.logout(); // 退出（gen+1），同域不发新刷新
  await wait(30);
  ok((page.data.profile || {}).name !== 'A-挂起资料', 'N2 退出后释放前不写入');
  handlers.length = 0;
  if (h2) h2.resolve({ id: 8, nickname: 'A-挂起资料', mobile: '13800000000', avatar: null });
  await wait(30);
  ok((page.data.profile || {}).name !== 'A-挂起资料' && (Account.load().profile || {}).name !== 'A-挂起资料', 'N2 退出后挂起资料响应被组合守卫拦截');

  console.log('[msg-guard] 退出后无 token 分支清空');
  auth.logout();
  page.syncApiMessages();
  await wait(30);
  ok((page.apiMessages || []).length === 0, '退出后消息清空');

  console.log('[msg-guard] syncApiProfile 分域守卫：A 挂起 → B 应用 → A 释放不污染');
  auth.saveLogin({ accessToken: 'token-A2', userId: 7 }); // 回到 A2 会话
  auth.savePosts([{ postId: 11, userId: 7, orgId: 31, roleCode: 'sales', orgType: 'store' }]);
  http.saveSession({ postId: 11 });
  page = getCurrentPages().at(-1);
  holds.length = 0; handlers.length = 0;
  // A/B 指同一用户的先后两次请求；完整 DTO 的 id 均与当前登录用户一致。
  holdHandler({ id: 7, nickname: 'A-资料', mobile: '13800000000', avatar: null });
  page.syncApiProfile();
  replyHandler({ id: 7, nickname: 'B-资料', mobile: '13900000000', avatar: null });
  page.syncApiProfile();
  await wait(30);
  ok(page.data.profile && page.data.profile.name === 'B-资料', '后发起的 B 资料已应用');
  const holdP = holds.shift();
  ok(!!holdP, 'A 资料请求确实被挂起');
  holdP.resolve({ id: 7, nickname: 'A-资料', mobile: '13800000000', avatar: null });
  await wait(30);
  ok(page.data.profile.name === 'B-资料', 'A 迟到资料被分域序号守卫丢弃（页面不回退）');
  ok((Account.load().profile || {}).name === 'B-资料', '存储侧资料同样不回退');

  console.log(`\n通过 ${pass}，失败 ${fail}`);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('脚本异常:', e); process.exit(1); });
