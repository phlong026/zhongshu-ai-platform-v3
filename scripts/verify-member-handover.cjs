#!/usr/bin/env node
/**
 * 批次#32 · M01-T04/M04-T04 离职交接 v3 前端接线验证（role-page-runtime + wx.request spy）。
 * AC1 成员管理视图渲染交接卡（离职成员/接收人 picker+两动作）
 * AC2 正常交接：请求体 {orgId,fromUserId,toUserId} + 交接清单渲染（逐域 inFlight/transferred/status）
 * AC3 紧急撤权：确认弹窗+请求体 {orgId,userId} + 待分配清单刷新
 * AC4 待分配行补交接：回填离职成员后发起 handover
 * AC5 无接收人（单人组织）不得发起交接+请求记录反证+AC5b 非管理岗直链门禁
 * AC6 api 层三方法契约（org.js → /platform/org/staff-handover|staff-emergency-revoke|handover-pending）
 * 用法：node scripts/verify-member-handover.cjs
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const root = path.join(__dirname, '..');

(async () => {
  // ===== AC6：api 层契约（connect 后 wx.request spy——http.get/post 闭包内部 request，覆盖 exports 无效）=====
  const runtime = await require('./role-page-runtime.cjs').connect();
  const auth = require(path.join(root, 'miniprogram/api/auth.js'));
  const httpPage = require(path.join(root, 'miniprogram/api/http.js'));
  const orgApi = require(path.join(root, 'miniprogram/api/org.js'));

  const requests = [];
  const origWxRequest = global.wx.request;
  global.wx.request = options => {
    requests.push(options);
    if (typeof origWxRequest === 'function') origWxRequest(options);
    if (options.success) options.success({ statusCode: 200, data: { code: 0, data: [] } });
  };
  httpPage.saveSession({ token: 'tk-ac6' });
  auth.savePosts([{ postId: 30, roleCode: 'boss', orgId: 9, orgType: 'store', lead: false }]);
  httpPage.saveSession({ postId: 30 });
  requests.length = 0;
  await orgApi.staffHandover({ orgId: 9, fromUserId: 3, toUserId: 5 });
  await orgApi.staffEmergencyRevoke({ orgId: 9, userId: 3 });
  await orgApi.handoverPending(9);
  assert.equal(requests[0].url.endsWith('/platform/org/staff-handover'), true, 'handover 端点');
  assert.equal(requests[0].method, 'POST', 'handover POST（R9 P3：方法断言）');
  assert.deepEqual(requests[0].data, { orgId: 9, fromUserId: 3, toUserId: 5 });
  assert.ok(requests[1].url.endsWith('/platform/org/staff-emergency-revoke'));
  assert.equal(requests[1].method, 'POST', 'emergency POST');
  assert.deepEqual(requests[1].data, { orgId: 9, userId: 3 });
  assert.ok(requests[2].url.endsWith('/platform/org/handover-pending'));
  assert.equal(requests[2].method, 'GET', 'pending GET');
  assert.deepEqual(requests[2].data, { orgId: 9 });
  console.log('  ✅ AC6 org.js 三方法契约（端点+请求体）');

  // ===== AC1-AC5：页面集成 =====
  const respond = options => {
    const url = options.url || '';
    const ok = data => ({ statusCode: 200, data: { code: 0, data } });
    if (url.endsWith('/platform/org/teams')) return ok([{ deptId: 1, name: '门店团队' }]);
    if (url.endsWith('/platform/org/staff')) return ok([
      { postId: 11, userId: 3, name: '李业务', mobile: '13800000003', roleCode: 'sales', teamName: '门店团队', active: true },
      { postId: 12, userId: 4, name: '王工程', mobile: '13800000004', roleCode: 'engineer', teamName: '门店团队', active: true },
      { postId: 13, userId: 5, name: '赵财务', mobile: '13800000005', roleCode: 'finance', teamName: '门店团队', active: true }
    ]);
    if (url.endsWith('/platform/org/handover-pending')) return ok([
      { id: 1, orgId: 9, fromUserId: 9, domain: 'franchise-crm', domainLabel: '客户', inFlight: 3, createTime: '2026-11-04 10:00' }
    ]);
    if (url.endsWith('/platform/org/staff-handover')) return ok({
      mode: 'HANDOVER', orgId: 9, fromUserId: options.data.fromUserId, toUserId: options.data.toUserId,
      revokedPostIds: [11], items: [
        { domain: 'franchise-crm', label: '客户', inFlight: 3, transferred: 3, status: 'HANDED_OVER' },
        { domain: 'franchise-engineering', label: '工程任务', inFlight: 2, transferred: 0, status: 'PENDING_UNASSIGNED' }
      ]
    });
    if (url.endsWith('/platform/org/staff-emergency-revoke')) return ok({
      mode: 'EMERGENCY', orgId: 9, fromUserId: options.data.userId, toUserId: null,
      revokedPostIds: [12], items: [{ domain: 'franchise-crm', label: '客户', inFlight: 1, transferred: 0, status: 'PENDING_UNASSIGNED' }]
    });
    return ok(null);
  };
  global.wx.request = options => { requests.push(options); options.success && options.success(respond(options)); options.complete && options.complete(); };

  // API 登录：boss 岗（可管理成员）
  httpPage.saveSession({ token: 'tk-boss' });
  auth.savePosts([{ postId: 21, roleCode: 'boss', orgId: 9, orgType: 'store', lead: false }]);
  httpPage.saveSession({ postId: 21 });

  wx.reLaunch({ url: '/pages/account/index?view=api-members' });
  const page = () => getCurrentPages().at(-1);
  await new Promise(r => setTimeout(r, 30));

  // AC1：交接卡渲染（离职成员/接收人 picker + 正常交接/紧急撤权两动作）
  assert.equal(page().data.view, 'api-members');
  assert.equal(page().data.apiLeavers.length, 3, '在岗三人进入离职成员选项');
  assert.equal(page().data.apiPendingRows.length, 1, '待分配清单加载');
  assert.equal(page().data.apiPendingRows[0].fromName, '用户9', '待分配行补 fromName（无在岗匹配兜底）');
  assert.equal(page().data.handoverSource, null, '初始未选离职成员（来源与在岗列表分离）');
  page().pickHandoverLeaver({ detail: { value: 0 } });
  assert.equal(page().data.handoverSource.userId, 3, '选离职成员→source=在岗成员');
  assert.deepEqual(page().data.apiReceivers.map(r => r.userId), [4, 5], '接收人=真实在岗排除来源');
  assert.equal(page().data.handoverTargetIndex, -1, '初始未选接收人（R4 P1：按 userId 保留，未选=-1）');
  page().pickHandoverTarget({ detail: { value: 0 } });
  assert.equal(page().data.handoverTargetUserId, 4, '选接收人→记录 userId');
  const file = path.join(root, 'miniprogram/pages/account/index.wxml');
  const wxml = require('fs').readFileSync(file, 'utf8');
  for (const text of ['离职交接', '发起正常交接', '紧急撤权（不等待交接）', '待分配在途事项', '补交接']) {
    assert.ok(wxml.includes(text), 'api-members 视图缺失「' + text + '」');
  }
  console.log('  ✅ AC1 交接卡/撤权/待分配清单渲染元素齐全');

  // AC2：正常交接——请求体+结果清单渲染（清 AC6 残留请求，防 find 命中旧调用）
  requests.length = 0;
  page().confirmHandover();
  await new Promise(r => setTimeout(r, 20));
  const handoverReq = requests.find(r => r.url.endsWith('/platform/org/staff-handover'));
  assert.ok(handoverReq, '发起正常交接请求');
  assert.equal(handoverReq.data.orgId, 9);
  assert.equal(handoverReq.data.fromUserId, 3, 'fromUserId=首名离职成员');
  assert.equal(handoverReq.data.toUserId, 4, 'toUserId=接收人（排除本人）');
  assert.equal(page().data.handoverResult.title, '李业务 → 王工程 交接清单');
  assert.deepEqual(page().data.handoverResult.items.map(i => i.label + ':' + i.statusText), ['客户:已交接', '工程任务:转待分配'], 'R2 P1-1 五态映射（HANDED_OVER/PENDING_UNASSIGNED）');
  assert.equal(page().data.handoverResult.openCount, 1, '转待分组计数');
  console.log('  ✅ AC2 正常交接请求体与交接清单渲染');

  // AC3：紧急撤权——确认弹窗后请求体（AC2 交接后 loadApiMembers 对回 source=真实条目）
  assert.equal(page().data.handoverSource.userId, 3, '刷新后 source 按 userId 对回真实在岗条目（不换人）');
  let modal;
  const origModal = wx.showModal;
  wx.showModal = o => { modal = o; o.success && o.success({ confirm: true }); };
  requests.length = 0;
  page().confirmEmergency();
  await new Promise(r => setTimeout(r, 20));
  assert.ok(modal && modal.title === '紧急撤权', '紧急撤权确认弹窗');
  const revokeReq = requests.find(r => r.url.endsWith('/platform/org/staff-emergency-revoke'));
  assert.ok(revokeReq, '紧急撤权请求');
  assert.deepEqual(revokeReq.data, { orgId: 9, userId: 3 });
  assert.equal(page().data.handoverResult.title, '李业务 紧急撤权清单');
  wx.showModal = origModal;
  console.log('  ✅ AC3 紧急撤权确认弹窗+请求体+结果清单');

  // AC4：待分配行补交接——回填离职成员再发起
  page().reassignRow({ currentTarget: { dataset: { userId: 9, name: '用户9' } } });
  assert.ok(page().data.reassignHint.includes('全部在途事项'), '补交接提示回填（R4 P2：全部在途语义）');
  page().pickHandoverTarget({ detail: { value: 0 } });
  page().confirmHandover();
  await new Promise(r => setTimeout(r, 20));
  const reassignReq = requests.filter(r => r.url.endsWith('/platform/org/staff-handover')).at(-1);
  assert.equal(reassignReq.data.fromUserId, 9, '补交接 fromUserId=待分配行原离职人');
  console.log('  ✅ AC4 待分配行补交接回填与发起');

  // AC5：单人组织不得发起交接（无接收人）
  global.wx.request = options => {
    const url = options.url || '';
    const ok = data => ({ statusCode: 200, data: { code: 0, data } });
    let res = ok(null);
    if (url.endsWith('/platform/org/teams')) res = ok([{ deptId: 1, name: '门店团队' }]);
    else if (url.endsWith('/platform/org/staff')) res = ok([{ postId: 11, userId: 3, name: '李业务', mobile: '13800000003', roleCode: 'sales', teamName: '门店团队', active: true }]);
    else if (url.endsWith('/platform/org/handover-pending')) res = ok([]);
    requests.push(options); // R1 P2-6：AC5 stub 同样记录请求（防「未发送」断言恒真）
    options.success && options.success(res);
  };
  // http.js 在 success 回调 settle——stub 必须回调 success
  requests.length = 0;
  page().setData({ error: '' });
  page().loadApiMembers();
  await new Promise(r => setTimeout(r, 20));
  assert.equal(page().data.apiLeavers.length, 1, '单人组织在岗一人');
  assert.equal(page().data.handoverSource, null, 'R6 P2：来源已撤权且无待分配行→刷新清空（不保留无待办的撤权来源）');
  page().pickHandoverLeaver({ detail: { value: 0 } });
  assert.equal(page().data.apiReceivers.length, 0, '来源切换为唯一在岗成员后无接收人');
  page().confirmHandover();
  assert.ok(page().data.error.includes('无其他在岗成员'), '单人组织拦截：' + page().data.error);
  assert.ok(!requests.some(r => r.url.endsWith('/platform/org/staff-handover')), '单人组织不发交接请求（紧跟调用断言，清空前取证——R5 P2-2）');
  // R4 P1：接收人 userId 保留回归——多人在岗时选用户5，刷新移除用户4后仍指向用户5（不换人）
  global.wx.request = options => {
    const url = options.url || '';
    const ok = data => ({ statusCode: 200, data: { code: 0, data } });
    let res = ok(null);
    if (url.endsWith('/platform/org/teams')) res = ok([{ deptId: 1, name: '门店团队' }]);
    else if (url.endsWith('/platform/org/staff')) res = ok([
      { postId: 14, userId: 5, name: '赵财务', mobile: '13800000005', roleCode: 'finance', teamName: '门店团队', active: true },
      { postId: 15, userId: 6, name: '钱工程', mobile: '13800000006', roleCode: 'engineer', teamName: '门店团队', active: true }
    ]);
    else if (url.endsWith('/platform/org/handover-pending')) res = ok([{ id: 2, orgId: 9, fromUserId: 9, domain: 'franchise-crm', domainLabel: '客户', inFlight: 3, createTime: '2026-11-04' }]);
    requests.push(options);
    options.success && options.success(res);
  };
  requests.length = 0;
  page().setData({ handoverTargetUserId: 6, handoverSource: { userId: 9, name: '用户9', roleName: '已撤权' }, error: '' });
  page().loadApiMembers();
  await new Promise(r => setTimeout(r, 20));
  assert.deepEqual(page().data.apiReceivers.map(r => r.userId), [5, 6], '接收人列表重算（source=原李业务3 已撤权保留，不进接收人排除）');
  assert.equal(page().data.handoverTargetIndex, 1, 'userId=6 保留→新列表索引 1（R4 P1：不换人）');
  assert.equal(page().data.handoverTargetUserId, 6, 'userId 保留');
  assert.equal(page().data.handoverSource && page().data.handoverSource.userId, 9, 'R1 P1+R6 P2：补交接来源（已撤权 9）仍待分配→按 userId 保留不换人');
  // 反证：请求记录通道有效——故意直发一次交接请求应被记录（断言非恒真的机制证明）
  await orgApi.staffHandover({ orgId: 9, fromUserId: 1, toUserId: 2 });
  assert.ok(requests.some(r => r.url.endsWith('/platform/org/staff-handover')), '反证：故意发送能被记录（断言非恒真）');
  console.log('  ✅ AC5 单人组织拦截（无接收人+请求记录反证）');

  // AC5b：非管理岗直链 api-members——交接卡隐藏+处理函数拒绝
  httpPage.saveSession({ token: 'tk-sales' });
  auth.savePosts([{ postId: 31, roleCode: 'sales', orgId: 9, orgType: 'store', lead: false }]);
  httpPage.saveSession({ postId: 31 });
  requests.length = 0;
  wx.reLaunch({ url: '/pages/account/index?view=api-members' });
  await new Promise(r => setTimeout(r, 30));
  const salesPage = getCurrentPages().at(-1);
  assert.equal(salesPage.data.apiCanManage, false, 'sales 岗无管理权限');
  salesPage.confirmHandover();
  assert.match(salesPage.data.error, /老板|负责人/, '非管理岗拦截');
  assert.ok(!requests.some(r => r.url.endsWith('/platform/org/staff-handover')), '非管理岗不发交接请求');
  const shotC = path.join(os.tmpdir(), 'member-handover-nomanage.json');
  await runtime.screenshot({ path: shotC });
  const renderedC = JSON.parse(fs.readFileSync(shotC, 'utf8')).renderedText;
  assert.ok(!renderedC.includes('发起正常交接'), '非管理岗渲染不含交接按钮');
  assert.equal(salesPage.data.view, 'access', '非管理岗直达应进入权限提示页');
  assert.ok(!renderedC.includes('在岗成员'), '非管理岗不可通过管理直链读取成员列表');
  console.log('  ✅ AC5b 非管理岗直链：卡片隐藏+处理拒绝+不发请求');

  // AC7：延迟响应回归（R2 P1-2）——序号守卫丢弃旧刷新；busy 由交接链持有不被旧刷新绕过
  httpPage.saveSession({ token: 'tk-boss2' });
  auth.savePosts([{ postId: 22, roleCode: 'boss', orgId: 9, orgType: 'store', lead: false }]);
  httpPage.saveSession({ postId: 22 });
  const pendingDispatch = [];
  const delayed = (options, ms) => { pendingDispatch.push({ options, at: Date.now() + ms }); };
  const flushAt = cutoff => {
    const due = pendingDispatch.filter(item => item.at <= cutoff);
    for (let i = pendingDispatch.length - 1; i >= 0; i--) if (pendingDispatch[i].at <= cutoff) pendingDispatch.splice(i, 1);
    for (const item of due) { item.options.success && item.options.success(respondSync(item.options)); item.options.complete && item.options.complete(); }
    return due.length;
  };
  let staffLoadCount = 0; // R6 P2：两轮加载不同快照（旧后完成）——序号守卫变异敏感
  const respondSync = options => {
    const url = options.url || '';
    const ok = data => ({ statusCode: 200, data: { code: 0, data } });
    if (url.endsWith('/platform/org/teams')) return ok([{ deptId: 1, name: '门店团队' }]);
    if (url.endsWith('/platform/org/staff')) {
      const isFirstSnapshot = options.__firstLoad === true; // 快照按请求期标记（响应乱序不串）
      const rows = isFirstSnapshot
        ? [{ postId: 11, userId: 3, name: '李业务', mobile: '13800000003', roleCode: 'sales', teamName: '门店团队', active: true },
           { postId: 12, userId: 4, name: '王工程', mobile: '13800000004', roleCode: 'engineer', teamName: '门店团队', active: true },
           { postId: 13, userId: 5, name: '赵财务', mobile: '13800000005', roleCode: 'finance', teamName: '门店团队', active: true }]
        : [{ postId: 11, userId: 3, name: '李业务', mobile: '13800000003', roleCode: 'sales', teamName: '门店团队', active: true },
           { postId: 12, userId: 4, name: '王工程', mobile: '13800000004', roleCode: 'engineer', teamName: '门店团队', active: true }];
      return ok(rows);
    }
    if (url.endsWith('/platform/org/handover-pending')) return ok([]);
    if (url.endsWith('/platform/org/staff-handover')) return ok({ mode: 'NORMAL', orgId: 9, items: [{ domain: 'franchise-crm', label: '客户', inFlight: 1, transferred: 1, status: 'HANDED_OVER' }] });
    return ok(null);
  };
  const flushDue = () => {
    const now = Date.now();
    const due = pendingDispatch.filter(item => item.at <= now);
    for (let i = pendingDispatch.length - 1; i >= 0; i--) if (pendingDispatch[i].at <= now) pendingDispatch.splice(i, 1);
    for (const item of due) { item.options.success && item.options.success(respondSync(item.options)); item.options.complete && item.options.complete(); }
    return due.length;
  };
  const flushAll = () => { while (pendingDispatch.length) { pendingDispatch.splice(0).forEach(item => { item.options.success && item.options.success(respondSync(item.options)); item.options.complete && item.options.complete(); }); } };
  // 时间窗分层：初始两轮加载 +60ms；handover +150ms；交接链刷新 +60ms（自其发起时刻）——三阶段互不重叠
  const handoverAt = { v: 0 };
  let nonHandoverCount = 0;
  global.wx.request = options => {
    requests.push(options);
    let ms;
    if (options.url.endsWith('staff-handover')) { ms = 150; handoverAt.v = Date.now() + ms; }
    else { nonHandoverCount++; options.__firstLoad = nonHandoverCount <= 3; ms = nonHandoverCount <= 3 ? 150 : 60; } // load#1（旧快照，请求期标记）后完成、load#2 先完成——序号守卫变异敏感
    delayed(options, ms);
  };
  wx.reLaunch({ url: '/pages/account/index?view=api-members' });
  await new Promise(r => setTimeout(r, 80));
  const p7 = getCurrentPages().at(-1);
  // R4 P2-1：初始加载在途（apiMembersBusy=true，flush 前窗口）——双条件静默拦截
  requests.length = 0;
  p7.setData({ handoverSource: { userId: 3, name: '李业务', roleName: '业务员' } });
  p7.confirmHandover();
  assert.ok(p7.data.apiMembersBusy, 'AC7 加载在途（apiMembersBusy=true）');
  assert.ok(!requests.some(r => r.url.endsWith('/platform/org/staff-handover')), 'AC7 刷新在途（apiMembersBusy）不发交接请求');
  assert.ok(!p7.data.error, 'AC7 在途拦截静默（不误报错误）：' + p7.data.error + ' apiMembersBusy=' + p7.data.apiMembersBusy);
  flushAt(Date.now());
  await new Promise(r => setTimeout(r, 10));
  assert.equal(p7.data.apiLeavers.length, 2, 'AC7 初始加载完成（新快照胜出）');
  // R7 P2：单独释放旧快照（load#1 +150 到期）——旧快照后到不得覆盖新状态（删守卫则此处覆盖为 3 → 红）
  await new Promise(r => setTimeout(r, 120));
  const staleQueued = pendingDispatch.filter(item => item.options.url.endsWith('/platform/org/staff'));
  assert.ok(staleQueued.length >= 1, 'AC7 旧快照仍在队列待释放');
  flushAt(Date.now());
  await new Promise(r => setTimeout(r, 10));
  assert.equal(p7.data.apiLeavers.length, 2, 'AC7 旧快照单独释放后被序号守卫丢弃（R6 P2 变异敏感：删守卫则变 3）');
  // R5 P2-1：紧急撤权弹窗确认回调复查双锁——空闲期开弹窗、确认时刷新已在途 → 拦截
  p7.setData({ handoverSource: { userId: 3, name: '李业务', roleName: '业务员' } });
  let emergencyModal = null;
  const prevModal = wx.showModal;
  wx.showModal = o => { emergencyModal = o; };
  p7.confirmEmergency();
  assert.ok(emergencyModal && emergencyModal.title === '紧急撤权', 'AC7 空闲期弹窗已打开');
  p7.loadApiMembers(); // 弹窗打开期间启动成员刷新（在途 apiMembersBusy=true）
  await new Promise(r => setTimeout(r, 20));
  assert.ok(p7.data.apiMembersBusy, 'AC7 确认时刷新已在途');
  emergencyModal.success({ confirm: true });
  assert.ok(!requests.some(r => r.url.endsWith('/platform/org/staff-emergency-revoke')), 'AC7 弹窗确认回调双锁拦截（刷新在途不发生撤权请求）');
  wx.showModal = prevModal;
  await new Promise(r => setTimeout(r, 80)); // 等 emergency 交错的刷新响应到期
  flushAt(Date.now()); // 放行刷新响应，恢复空闲态再进提交流程
  await new Promise(r => setTimeout(r, 10));
  assert.equal(p7.data.apiMembersBusy, false, 'AC7 交错刷新完成后恢复空闲');
  p7.setData({ handoverSource: null });
  // 提交交接——handover +150ms 未回期间 busy 恒 true（旧刷新无法提前释放）
  p7.pickHandoverLeaver({ detail: { value: 0 } });
  p7.pickHandoverTarget({ detail: { value: 0 } });
  p7.confirmHandover();
  assert.equal(p7.data.handoverBusy, true, 'AC7 提交即持锁');
  await new Promise(r => setTimeout(r, 20));
  assert.equal(p7.data.handoverBusy, true, 'AC7 handover 响应未回期间旧刷新不能释放 busy（R1 P1-2 回归）');
  // 阶段 1：等 handover 响应（+150ms）到期并只放行它——交接链刷新（+60ms 自发起≈+240ms 后才到期）仍压在队列
  await new Promise(r => setTimeout(r, 160));
  flushAt(Date.now());
  await new Promise(r => setTimeout(r, 20)); // 交接回调微任务创建刷新请求入队
  assert.equal(p7.data.handoverBusy, true, 'AC7 刷新未完成 busy 仍持有（交接链 return loadApiMembers 真实等待）');
  assert.ok(p7.data.apiMembersBusy, 'AC7 刷新请求已由交接链发起（在途）');
  assert.ok(pendingDispatch.length >= 3, 'AC7 刷新请求压在队列未放行（R3 P2-1：确认刷新完成而非只看锁）');
  const before = requests.filter(r => r.url.endsWith('/platform/org/staff-handover')).length;
  p7.confirmHandover();
  assert.equal(p7.data.handoverBusy, true, 'AC7 锁持有期重复提交被拒（不发第二请求）');
  assert.equal(requests.filter(r => r.url.endsWith('/platform/org/staff-handover')).length, before, 'AC7 锁持有期无第二 handover 请求');
  // 阶段 2：放行全部刷新响应——交接链完成释放
  flushAll();
  await new Promise(r => setTimeout(r, 20));
  assert.equal(pendingDispatch.length, 0, 'AC7 队列已清空（刷新真实完成——R3 P2-1 补强断言）');
  assert.equal(p7.data.handoverBusy, false, 'AC7 交接链完成后释放');
  assert.equal(p7.data.handoverResult.title, '李业务 → 王工程 交接清单', 'AC7 结果清单渲染');
  console.log('  ✅ AC7 延迟响应回归：序号守卫+busy 交接链持有（分阶段释放+中间窗口断言）');

  // AC8：R7 P1 切岗跨组织——组织/岗位变化后 loadApiMembers 清空旧交接双方，提交被 ctx 失配拦截
  requests.length = 0;
  p7.setData({ handoverSource: { userId: 3, name: '李业务', roleName: '业务员' }, handoverTargetUserId: 4, error: '' });
  httpPage.saveSession({ token: 'tk-org10' });
  auth.savePosts([{ postId: 41, roleCode: 'boss', orgId: 10, orgType: 'store', lead: false }]);
  httpPage.saveSession({ postId: 41 });
  p7.loadApiMembers();
  await new Promise(r => setTimeout(r, 80)); // AC7 延迟 spy 仍在——等响应到期
  flushAt(Date.now());
  await new Promise(r => setTimeout(r, 10));
  assert.equal(p7.data.handoverSource, null, 'AC8 切岗后刷新清空旧来源（不按 userId 保留到新组织）');
  assert.equal(p7.data.handoverTargetUserId, null, 'AC8 切岗后清空旧接收人');
  p7.confirmHandover();
  assert.ok(p7.data.error.includes('请选择离职成员'), 'AC8 清空后提交被空选拦截：' + p7.data.error);
  assert.ok(!requests.some(r => r.url.endsWith('/platform/org/staff-handover')), 'AC8 清空后不发交接请求');
  // ctx 缺失路径（加载未完成即提交）——assertManage ctx 失配清空+拦截
  p7.setData({ handoverSource: { userId: 3, name: '李业务', roleName: '业务员' }, handoverTargetUserId: 4, error: '' });
  p7.handoverCtx = null;
  p7.confirmHandover();
  assert.ok(p7.data.error.includes('岗位或组织已切换'), 'AC8 ctx 失配提交拦截：' + p7.data.error);
  assert.equal(p7.data.handoverSource, null, 'AC8 失配时清空残留选择');
  assert.ok(!requests.some(r => r.url.endsWith('/platform/org/staff-handover')), 'AC8 失配不发交接请求');
  // R8 P2-1：切组织同步清旧组织结果清单/补交接提示（真实切到 org11——ctxChanged 才清，同组织刷新保留）
  p7.setData({ handoverResult: { title: '旧组织清单', items: [], openCount: 0 }, reassignHint: '旧组织提示' });
  httpPage.saveSession({ token: 'tk-org11' });
  auth.savePosts([{ postId: 42, roleCode: 'boss', orgId: 11, orgType: 'store', lead: false }]);
  httpPage.saveSession({ postId: 42 });
  p7.loadApiMembers();
  await new Promise(r => setTimeout(r, 80));
  const queuedBefore = pendingDispatch.length;
  flushAt(Date.now());
  await new Promise(r => setTimeout(r, 10));
  assert.equal(p7.data.handoverResult, null, 'AC8 切组织清旧结果清单（R8 P2-1）');
  assert.equal(p7.data.reassignHint, '', 'AC8 切组织清补交接提示（R8 P2-1）');
  // R8 P2-2：紧急撤权弹窗打开后来源被刷新清空→确认拦截（沿用失效上下文不可发请求）
  p7.setData({ handoverSource: { userId: 5, name: '赵财务', roleName: '财务' } });
  let staleModal = null;
  const staleOrig = wx.showModal;
  wx.showModal = o => { staleModal = o; };
  p7.confirmEmergency();
  assert.ok(staleModal, 'AC8 弹窗已打开');
  p7.setData({ handoverSource: null, error: '' }); // 弹窗打开期间刷新清源（模拟）
  staleModal.success({ confirm: true });
  assert.ok(p7.data.error.includes('交接选择已变化'), 'AC8 弹窗确认核对失效来源：' + p7.data.error);
  assert.ok(!requests.some(r => r.url.endsWith('/platform/org/staff-emergency-revoke')), 'AC8 失效上下文不发生撤权请求');
  wx.showModal = staleOrig;
  console.log('  ✅ AC8 切岗跨组织防护：刷新清空双方+ctx 失配拦截');

  console.log('\n批次#32 M01-T04/M04-T04 交接接线验证：全部通过');
  process.exit(0);
})().catch(e => { console.error('  ❌ ' + (e && e.message)); process.exit(1); });
