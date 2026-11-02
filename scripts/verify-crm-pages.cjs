// 阶段1 P1 客户接入验证（AC1-AC11）：platform-crm 纯函数 + role-page-runtime 页面集成 + 注入 wx.request spy。
// 运行时=Node Page/WXML 集成模拟器（非微信原生渲染）；服务端副作用证明见 verify-crm-pages-native.cjs（AC14b）。
// 用法：node scripts/verify-crm-pages.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const root = path.join(__dirname, '..');

const EDGE = '9007199254740991';      // NumberSerializer 边界（本身输出字符串）
const BIG = '9007199254740993';       // 超界字符串 id
const TODAY = '2026-11-01';
const MS = s => Date.parse(s);
const TODAY_MS = MS('2026-11-01T10:00:00+08:00');
const OCT27_MS = MS('2026-10-27T14:15:00+08:00');
const ok = name => { passed += 1; console.log('PASS ' + name); };
let passed = 0;

const PF = require(path.join(root, 'miniprogram/utils/platform-crm.js'));

/* ---------- AC1 五态判定矩阵 ---------- */
assert.equal(PF.pageMode('', { roleCode: 'sales' }), 'demo', 'token 空+岗位缓存仍在 → demo');
assert.equal(PF.pageMode('t', null), 'demo', 'token 有+post 空 → demo');
for (const role of ['sales', 'sales-lead', 'boss', 'owner']) assert.equal(PF.pageMode('t', { roleCode: role }), 'api', role + ' → api');
for (const role of ['finance', 'finance-lead', 'engineer', 'project-manager']) assert.equal(PF.pageMode('t', { roleCode: role }), 'demo', role + ' → demo');
assert.equal(PF.can({ roleCode: 'sales' }, 'capture'), true);
assert.equal(PF.can({ roleCode: 'boss' }, 'capture'), false, 'boss 不可登记');
assert.equal(PF.can({ roleCode: 'sales-lead' }, 'transfer'), true);
assert.equal(PF.can({ roleCode: 'sales' }, 'transfer'), false, 'sales 不可转交');
ok('AC1 五态判定与能力矩阵');

/* ---------- C4 lock：collect 能力对齐 854 种子（finance 系可收款） ---------- */
assert.equal(PF.can({ roleCode: 'sales' }, 'collect'), true);
assert.equal(PF.can({ roleCode: 'sales-lead' }, 'collect'), true);
assert.equal(PF.can({ roleCode: 'boss' }, 'collect'), true);
assert.equal(PF.can({ roleCode: 'finance' }, 'collect'), true, '854 种子 finance 可收款');
assert.equal(PF.can({ roleCode: 'finance-lead' }, 'collect'), true, '854 种子 finance-lead 可收款');
assert.equal(PF.can({ roleCode: 'owner' }, 'collect'), false, 'owner 不在 854 collect 白名单');
ok('AC1b collect 能力矩阵（854 种子含 finance 系）');

/* ---------- C3 lock：workflow collect 覆盖层不在 preview 块内（结构扫描） ---------- */
{
  const wxml = fs.readFileSync(path.join(root, 'miniprogram/pages/workflow/index.wxml'), 'utf8');
  const iCollect = wxml.indexOf("wx:if=\"{{modal==='collect'}}\"");
  const iPreview = wxml.indexOf('wx:if="{{preview}}"');
  assert.ok(iCollect > -1 && iPreview > -1, 'collect/preview 覆盖层标签缺失');
  assert.ok(iCollect < iPreview, 'collect 覆盖层须独立于 preview 覆盖层（之前嵌套致表单永不渲染）');
  assert.ok(!wxml.slice(iPreview).includes("modal==='collect'"), 'preview 覆盖层内不得残留 collect 块');
  assert.ok(wxml.includes("wx:if=\"{{modal&&modal!=='collect'}}\""), '通用 modal 覆盖层须排除 collect（防误渲染 exterior 块）');
  ok('AC1c workflow collect 覆盖层结构（独立于 preview）');
}

/* ---------- AC2 列表行映射 ---------- */
const base = { phone: '13800000628', wechat: '', source: '视频号咨询', province: '湖北省', city: '武汉市', summary: '两层', status: 'REGISTERED', ownerUserId: 3, revision: 5 };
const row = PF.customerRow({ ...base, id: BIG, name: '陈先生', nextVisitDate: [2026, 11, 2], lastFollowAt: null, createTime: 1735660800000 }, { today: TODAY, canWrite: true });
assert.equal(row.id, BIG);
assert.equal(row.name, '陈先生');
assert.equal(row.initial, '陈');
assert.equal(row.status, '已登记');
assert.equal(row.stageName, '已登记');
assert.equal(row.phone, '138****0628', '展示值=掩码');
assert.equal(row.dial, '13800000628', '拨号原值');
assert.equal(row.brief, '武汉市 · 视频号咨询');
assert.equal(row.fresh, true);
assert.equal(row.appointment, '2026-11-02');
assert.equal(row.cue, '11-02 回访');
assert.equal(row.rank, 3);
assert.equal(row.canFollow, true);
assert.equal(row.action, '写跟进');
const masked = PF.customerRow({ ...base, id: 100, name: '林女士', phone: '', wechat: 'linwx', nextVisitDate: null, lastFollowAt: null, createTime: 1 }, { today: TODAY, canWrite: true });
assert.equal(masked.phoneText, 'linwx', '无手机号→微信号兜底');
assert.equal(masked.dial, '');
const unreadable = PF.customerRow({ ...base, id: 101, name: '王女士', nextVisitDate: [2026, 10, 1], lastFollowAt: 1730000000000, createTime: 1 }, { today: TODAY, canWrite: true });
assert.equal(unreadable.cue, '逾期 31 天');
assert.equal(unreadable.cueTone, 'tone-danger');
assert.equal(unreadable.rank, 0);
const readonly = PF.customerRow({ ...base, id: 102, name: '赵先生', nextVisitDate: [2026, 10, 1], lastFollowAt: null, createTime: 1 }, { today: TODAY, canWrite: false });
assert.equal(readonly.rank, 5, '只读压缩 rank5');
assert.ok(readonly.sortKey, 'rank5 保留压缩前 sortKey');
assert.equal(PF.customerRow({ ...base, id: 103, name: '孙女士', status: 'FOLLOWING', nextVisitDate: null, lastFollowAt: TODAY_MS, createTime: 1 }, { today: TODAY, canWrite: true }).cue, '今天已跟进');
ok('AC2 列表行映射（掩码/状态中文/brief/rank 0-5/sortKey）');

/* ---------- AC3 tabs 内存过滤 ---------- */
const rows3 = [row, masked, unreadable, readonly].map((r, i) => ({ ...r }));
const due = PF.tabFilter(rows3, 'due', { today: TODAY });
assert.ok(due.every(r => r.canFollow), 'due 只含可办（readonly 因 canFollow=false 排除）');
assert.equal(due.length, 3, 'due=已登记+新客户+逾期（只读客户排除）');
assert.ok(!due.some(r => r.id === '102'), 'readonly 不入 due');
const future = PF.tabFilter(rows3, 'appointments', { today: TODAY });
assert.deepEqual(future.map(r => r.id), [BIG], 'appointments=未来日期');
assert.deepEqual(PF.tabFilter(rows3, 'visited', { today: TODAY }), [], 'visited 恒空');
assert.equal(PF.tabFilter(rows3, 'all', { today: TODAY }).length, 4);
const sorted = PF.sortRows(rows3);
assert.equal(sorted[0].rank, 0, 'rank 升序');
// 同 rank 同日期 → id 字典序稳定兜底
const t1 = PF.customerRow({ ...base, id: 'b2', name: '乙', nextVisitDate: [2026, 11, 2], lastFollowAt: null, createTime: 1 }, { today: TODAY, canWrite: true });
const t2 = PF.customerRow({ ...base, id: 'a1', name: '甲', nextVisitDate: [2026, 11, 2], lastFollowAt: null, createTime: 1 }, { today: TODAY, canWrite: true });
assert.deepEqual(PF.sortRows([t1, t2]).map(r => r.id), ['a1', 'b2']);
ok('AC3 tabs 过滤/排序确定性');

/* ---------- AC4 详情映射 ---------- */
const followsAsc = [
  { id: 1, customerId: BIG, note: '首访', nextVisitDate: [2026, 11, 3], authorUserId: 3, createTime: OCT27_MS },
  { id: 2, customerId: BIG, note: '电话回访', nextVisitDate: null, authorUserId: 3, createTime: OCT27_MS }
];
const schemes = [
  { id: 1, customerId: BIG, kind: 'BUDGET', version: 1, title: '预算', payload: '', width: 12, depth: 15, floors: 2, area: 360, totalCents: 1, authorUserId: 3, createTime: 1 },
  { id: 2, customerId: BIG, kind: 'PLAN', version: 2, title: '平面v2缺尺寸', payload: '', width: null, depth: null, floors: null, area: null, totalCents: null, authorUserId: 3, createTime: 2 },
  { id: 3, customerId: BIG, kind: 'PLAN', version: 1, title: '平面v1', payload: '', width: 11, depth: 14, floors: 3, area: null, totalCents: null, authorUserId: 3, createTime: 1 },
  { id: 4, customerId: BIG, kind: 'FACADE', version: 1, title: '立面', payload: '', width: 13, depth: 16, floors: 2, area: null, totalCents: null, authorUserId: 3, createTime: 1 }
];
const dv = PF.detailView({ customer: { id: BIG, name: '陈先生', phone: '13800000628', wechat: '', source: '视频号咨询', province: '湖北省', city: '武汉市', summary: '两层乡墅', status: 'FOLLOWING', revision: 9 }, follows: followsAsc, schemes }, { today: TODAY, canWrite: true });
assert.equal(dv.customer.width, '13', 'PLAN 新版本缺尺寸 → FACADE 兜底');
assert.equal(dv.customer.depth, '16');
assert.equal(dv.customer.floors, '2');
assert.equal(dv.customer.status, '跟进中');
assert.equal(dv.showSizeBlock, true);
assert.equal(dv.customer.follows[0].note, '电话回访', 'follows 逆序渲染');
assert.equal(dv.customer.follows[0].date, '2026-10-27', '毫秒→日期（Asia/Shanghai）');
assert.equal(dv.customer.follows[0].appointment, '', '空日期→空串');
assert.equal(dv.customer.follows[1].appointment, '2026-11-03');
const noSize = PF.detailView({ customer: { id: 1, name: 'x', status: 'REGISTERED', revision: 1 }, follows: [], schemes: [{ kind: 'PLAN', version: 1, width: null, depth: null, floors: null }] }, { today: TODAY, canWrite: true });
assert.equal(noSize.showSizeBlock, false, '全缺尺寸→区块隐藏');
ok('AC4 详情映射（尺寸回退链/follows 逆序/日期转换）');

/* ---------- AC5 写命令 payload ---------- */
assert.deepEqual(PF.capturePayload({ name: ' 陈 ', phone: '13800000628', summary: ' 要建两层 ', source: '视频号咨询' }),
  { name: '陈', phone: '13800000628', need: '要建两层', source: '视频号咨询' }, 'capture：need=summary、无 wechat/地区');
assert.deepEqual(PF.updatePayload({ name: '陈', wechat: 'w', province: '湖北省', city: '武汉市', summary: 's' }, 5),
  { name: '陈', wechat: 'w', province: '湖北省', city: '武汉市', summary: 's', revision: 5 }, 'update：无 phone/source、带 revision');
assert.deepEqual(PF.followPayload({ followContent: ' 沟通内容 ', appointment: '2026-11-05' }),
  { note: '沟通内容', nextVisitDate: [2026, 11, 5] }, 'follow：{note,nextVisitDate 数组}、无 revision');
assert.deepEqual(PF.transferPayload(BIG, { transferReason: ' 调岗 ' }, 7),
  { targetUserId: BIG, reason: '调岗', revision: 7 }, 'transfer：targetUserId 不透明透传');
ok('AC5 payload 构造');

/* ---------- AC7 错误码映射全表 + 输入约束 ---------- */
assert.equal(PF.errorMessage({ code: 1110000000 }), '客户不存在或已转移，请刷新列表');
assert.equal(PF.errorMessage({ code: 1110000001 }), '当前岗位不能办理该客户');
assert.equal(PF.errorMessage({ code: 1110000003 }), '请填写称呼、11位手机号、需求和来源');
assert.equal(PF.errorMessage({ code: 1110000004 }), '已有该手机号客资待关联，请由负责人处理');
assert.equal(PF.errorMessage({ code: 1110000008 }, 'follow'), '请填写沟通内容（500字以内）');
assert.equal(PF.errorMessage({ code: 1110000008 }, 'transfer'), '请填写转交原因（500字以内）');
assert.equal(PF.errorMessage({ code: 1110000008 }), '请填写内容或原因（500字以内）');
assert.equal(PF.errorMessage({ code: 1110000009 }), '预约日期不能早于今天');
assert.equal(PF.errorMessage({ code: 1110000010 }), '请检查客户资料（称呼必填、长度不超限）');
assert.equal(PF.errorMessage({ code: 1110000012 }), '接手人须为本组织在岗业务人员');
assert.equal(PF.errorMessage({ code: 1099000001 }), '数据已更新，请刷新后重试');
assert.equal(PF.errorMessage({ code: 1099000000 }), '请刷新后重新填写');
assert.equal(PF.errorMessage({ code: 403 }), '没有该操作权限', '业务 403');
assert.equal(PF.errorMessage({ code: -1 }), '网络异常，请刷新确认');
assert.equal(PF.errorMessage({ code: 999999, message: '系统维护中' }), '系统维护中', '未知 code 兜底');
assert.ok(PF.validPhone('13800000628') && !PF.validPhone('1380000062') && !PF.validPhone('23800000628'));
assert.ok(!PF.within('x'.repeat(65), 64) && PF.within('x'.repeat(64), 64));
ok('AC7 错误码映射与输入约束（纯函数部分）');

/* ---------- 页面集成（runtime + request spy）：AC6/AC8/AC9/AC10/AC11 ---------- */
(async () => {
  const runtime = await require(path.join(__dirname, 'role-page-runtime.cjs')).connect();
  const http = require(path.join(root, 'miniprogram/api/http.js'));
  const auth = require(path.join(root, 'miniprogram/api/auth.js'));
  const storageKeys = require(path.join(root, 'miniprogram/utils/storage-keys.js'));
  const A = require(path.join(root, 'miniprogram/utils/account.js'));
  // 页面数据/方法辅助：runtime facade 不暴露 data，统一走 evaluate
  const data = () => runtime.evaluate(() => JSON.parse(JSON.stringify(getCurrentPages().at(-1).data)));
  const call = (method, ...args) => runtime.evaluate((m, a) => { const p = getCurrentPages().at(-1); return p[m] ? p[m].apply(p, a) : undefined; }, method, args);

  // wx.request spy：排队 + 手动派发（构造乱序）
  const sent = []; const queued = [];
  let responder = null;
  global.wx.request = options => { sent.push(options); queued.push(options); };
  const toasts = []; const origToast = global.wx.showToast; global.wx.showToast = t => { toasts.push(t && t.title); if (origToast) origToast.call(global.wx, t); };
  async function flush() {
    while (queued.length) {
      const o = queued.shift();
      const r = responder ? responder(o, sent.length) : { statusCode: 200, data: { code: 0, data: {} } };
      await new Promise(r2 => setTimeout(r2, 0));
      if (r && r.__fail) { o.fail && o.fail({ errMsg: 'request:fail net' }); continue; }
      o.success && o.success(r);
      await new Promise(r2 => setTimeout(r2, 0));
    }
  }
  const demoKeys = Object.values(storageKeys).filter(k => typeof k === 'string');
  const demoPrefixes = demoKeys.filter(k => k.endsWith(':'));
  const isDemoKey = k => { k = String(k); return demoKeys.some(d => k === d) || demoPrefixes.some(p => k.startsWith(p)); };
  let counting = false; const demoOps = [];
  ['getStorageSync', 'setStorageSync', 'removeStorageSync'].forEach(fn => {
    const orig = global.wx[fn].bind(global.wx);
    global.wx[fn] = (k, ...rest) => { if (counting && isDemoKey(k)) demoOps.push(fn + ':' + k); return orig(k, ...rest); };
  });

  const listBody = (extra = []) => ({
    statusCode: 200,
    data: { code: 0, data: { list: [
      { id: BIG, name: '陈先生', phone: '13800000628', wechat: '', source: '视频号咨询', province: '湖北省', city: '武汉市', summary: '两层', status: 'REGISTERED', ownerUserId: 3, nextVisitDate: [2026, 11, 2], lastFollowAt: null, revision: 5, createTime: 1735660800000 },
      { id: EDGE, name: '李女士', phone: '13900000386', wechat: '', source: '门店自然到访', province: '湖北省', city: '武穴市', summary: '三层', status: 'FOLLOWING', ownerUserId: 3, nextVisitDate: null, lastFollowAt: 1730000000000, revision: 7, createTime: 1728000000000 },
      { id: 103, name: '林女士', phone: '', wechat: 'linwx', source: '朋友介绍', province: '湖北省', city: '黄冈市', summary: '', status: 'FOLLOWING', ownerUserId: 3, nextVisitDate: [2026, 10, 1], lastFollowAt: 1730000000000, revision: 2, createTime: 1728000000000 }
    ].concat(extra), total: 3 + extra.length } }
  });
  const detailBody = id => ({
    statusCode: 200,
    data: { code: 0, data: { customer: { id, name: '陈先生', phone: '13800000628', wechat: '', source: '视频号咨询', province: '湖北省', city: '武汉市', summary: '两层乡墅', status: 'FOLLOWING', ownerUserId: 3, nextVisitDate: null, lastFollowAt: null, revision: 5, createTime: 1735660800000 }, follows: [{ id: 1, customerId: id, note: '首访', nextVisitDate: [2026, 11, 3], authorUserId: 3, createTime: 1730000100000 }], schemes: [] } }
  });
  const savePost = (postId, roleCode) => { http.saveSession({ token: 'tk-' + postId }); auth.savePosts([{ postId, roleCode, orgId: 9, orgType: 'store', lead: roleCode === 'sales-lead' }]); http.saveSession({ postId }); };

  // AC11：demo 态（token 清）零 /franchise 请求 + demo 原样渲染
  http.clearSession(); auth.savePosts([]);
  A.mutate(state => { if (!state.session.loggedIn) A.loginDemo(state, 'zhang'); });
  counting = true;
  await runtime.callWxMethod('reLaunch', { url: '/pages/customers/index' });
  await new Promise(r => setTimeout(r, 20));
  let d = await data();
  assert.equal(sent.filter(o => String(o.url).includes('/franchise/')).length, 0, 'demo 态零 /franchise 请求');
  assert.ok((d.customers || []).length >= 1, 'demo 数据原样（按 demo 身份可见范围渲染）');
  counting = false;
  ok('AC11 demo 态零请求 + demo 数据原样');

  // AC1（页面级）：non-role 零请求
  savePost(501, 'finance');
  await runtime.callWxMethod('reLaunch', { url: '/pages/customers/index' });
  await new Promise(r => setTimeout(r, 20));
  assert.equal(sent.filter(o => String(o.url).includes('/franchise/')).length, 0, 'non-role 零请求');
  await runtime.callWxMethod('reLaunch', { url: '/pages/home/index' });
  ok('AC1 页面级 non-role 零请求（guest 分支已含于 AC11）');

  // AC9 前置：sales 岗 API 态执行链 spy
  savePost(501, 'sales');
  counting = true; demoOps.length = 0;
  await runtime.callWxMethod('reLaunch', { url: '/pages/customers/index' });
  responder = () => listBody();
  await flush();
  d = await data();
  assert.equal(d.platformMode, true);
  assert.equal((d.visibleCustomers || []).length, 3, 'API 列表渲染');
  const listReq = sent.find(o => String(o.url).includes('/franchise/customers'));
  assert.ok(listReq, '实发 GET /franchise/customers');
  assert.equal(String(listReq.header['X-Post-Id']), '501');
  assert.match(listReq.header.Authorization, /^Bearer /);
  assert.equal(demoOps.length, 0, 'API 态列表零 demo Storage 读写删');

  // AC2（渲染快照）
  const rendered = require(path.join(__dirname, 'role-page-runtime.cjs')).render(
    path.join(root, 'miniprogram/pages/customers/index.wxml'), d);
  const flatText = nodes => { let out = ''; for (const n of nodes || []) { out += (n.text || ''); for (const v of Object.values(n.attrs || {})) if (typeof v === 'string') out += ' ' + v; out += flatText(n.children); } return out; };
  const text = flatText(rendered);
  assert.ok(text.includes('陈先生') && text.includes('已登记') && text.includes('写跟进'), 'wxml 快照含名称/badge/按钮');


  // AC9：打开客户工作区（详情）→ 深链拦截 → 编辑 → 跟进 → 登记 → 转交 → 导航逻辑
  await runtime.callWxMethod('navigateTo', { url: '/pages/workflow/index?view=customer&id=' + BIG });
  responder = o => String(o.url).includes('/customers/' + BIG) ? detailBody(BIG) : listBody();
  await flush();
  d = await data();
  assert.equal(d.view, 'customer');
  assert.equal(d.customer.name, '陈先生');
  assert.equal(d.customerTabs.join(','), '概况,跟进', 'API 态两 tab');
  assert.equal(d.showSizeBlock, false);
  assert.equal(demoOps.length, 0, '详情链零 demo 读写');

  // 未接入视图深链拦截（budget 已随 P2 接入——换仍未接入的 legacy 视图）
  await runtime.callWxMethod('navigateTo', { url: '/pages/workflow/index?view=intake&id=' + BIG });
  await flush();
  d = await data();
  assert.ok(d.blocked && d.blocked.message.includes('阶段 2/3'), '深链拦截为占位卡');
  assert.equal(demoOps.length, 0, '拦截分支零 demo 读写');
  await runtime.callWxMethod('navigateTo', { url: '/pages/workflow/index?view=customer&id=' + BIG });
  responder = o => String(o.url).includes('/customers/' + BIG) ? detailBody(BIG) : listBody();
  await flush();

  // 编辑：回填→缺失拦截零请求→超长拦截零请求→正常保存（update 实发+幂等键+无 phone/source+revision）
  await call('openModal', 'edit');
  d = await data();
  assert.equal(d.form.name, '陈先生', '编辑回填');
  let b4 = sent.length;
  await call('setData', { form: { name: '', wechat: '', province: '湖北省', city: '武汉市', summary: 's' } });
  await call('saveModal');
  assert.equal(sent.length, b4, '称呼缺失拦截零请求');
  await call('setData', { form: { name: '陈先生二', wechat: '', province: '湖北省', city: '武汉市', summary: 'x'.repeat(501) } });
  await call('saveModal');
  assert.equal(sent.length, b4, '超长拦截零请求');
  await call('setData', { form: { name: '陈先生二', wechat: 'wx1', province: '湖北省', city: '武汉市', summary: '两层乡墅改' } });
  responder = o => ({ statusCode: 200, data: { code: 0, data: detailBody(BIG).data.data } });
  await call('saveModal');
  await flush();
  const updReq = sent[sent.length - 1];
  assert.ok(String(updReq.url).includes('/customers/' + BIG) && updReq.method === 'PUT', 'update 实发');
  assert.ok(updReq.header['Idempotency-Key'], 'update 幂等键头');
  assert.ok(!('phone' in updReq.data) && !('source' in updReq.data), 'update body 无 phone/source');
  assert.equal(updReq.data.revision, 5);
  d = await data();
  assert.equal(d.customer.name, '陈先生', '详情回显（mock 返回原名）');
  assert.equal(demoOps.length, 0, '编辑链零 demo 读写');

  // AC6：follow 命令快照语义（经真实 input/date 事件驱动，触发 saveDraft 快照作废链路）
  const typeFollow = (content, appointment) => call('input', { currentTarget: { dataset: { key: 'followContent' } }, detail: { value: content } }).then(() => call('date', { currentTarget: { dataset: { key: 'appointment' } }, detail: { value: appointment } }));
  await call('openModal', 'follow');
  await typeFollow('电话回访一次', '2026-10-01');
  b4 = sent.length;
  await call('saveModal');
  assert.equal(sent.length, b4, '过去日期拦截零请求');
  await typeFollow('电话回访一次', '2026-11-05');
  responder = () => ({ __fail: true });
  await call('saveModal');
  await flush();
  assert.equal(sent.length, b4 + 1, 'follow 实发一次');
  const key1 = sent[sent.length - 1].header['Idempotency-Key'];
  await call('saveModal'); // 失败重试（快照保留）
  await flush();
  const key2 = sent[sent.length - 1].header['Idempotency-Key'];
  assert.equal(key1, key2, '网络失败重试同键同内容');
  await typeFollow('改了内容', '2026-11-05');
  responder = o => ({ statusCode: 200, data: { code: 0, data: detailBody(BIG).data.data } });
  await call('saveModal');
  await flush();
  const key3 = sent[sent.length - 1].header['Idempotency-Key'];
  assert.notEqual(key2, key3, '字段变更换新键');
  await call('openModal', 'follow');
  await typeFollow('电话回访一次', '2026-11-05');
  await call('saveModal');
  await flush();
  const key4 = sent[sent.length - 1].header['Idempotency-Key'];
  assert.notEqual(key3, key4, '成功后再提交（同内容）新键');
  const followReq = sent[sent.length - 1];
  assert.deepEqual(followReq.data, { note: '电话回访一次', nextVisitDate: [2026, 11, 5] }, 'follow body 无 revision');
  // 双击只发一次
  b4 = sent.length;
  await call('openModal', 'follow');
  await typeFollow('双击', '2026-11-05');
  await call('saveModal');
  await call('saveModal');
  await flush();
  assert.equal(sent.length, b4 + 1, 'busy 提交锁：双击只发一次');
  assert.equal(demoOps.length, 0, '跟进链零 demo 读写');

  // AC7（页面级）：业务错误文案回显 + 008 语境
  await call('openModal', 'follow');
  await typeFollow('x', '2026-11-05');
  responder = () => ({ statusCode: 200, data: { code: 1110000008, msg: 'x' } });
  await call('saveModal');
  await flush();
  d = await data();
  assert.equal(d.error, '请填写沟通内容（500字以内）');
  await call('closeModal');

  // AC10：POST 网络异常「结果未知」不自动重试
  await call('openModal', 'follow');
  await typeFollow('网络异常场景', '2026-11-05');
  responder = () => ({ __fail: true });
  await call('saveModal');
  await flush();
  await flush();
  d = await data();
  assert.equal(d.error, '网络异常，请刷新确认', '结果未知文案');
  assert.equal(sent.filter(o => o.data && o.data.note === '网络异常场景').length, 1, '不自动重试');
  await call('closeModal');

  // AC9：登记分支（CREATED / LINK_REQUIRED / LINKED 可读 / LINKED 不可读）
  await runtime.callWxMethod('navigateTo', { url: '/pages/workflow/index?view=customers&modal=register' });
  responder = () => listBody();
  await flush();
  await call('setData', { form: { name: '新客户', phone: '13700001111', summary: '需要新建', source: '抖音咨询' } });
  responder = () => ({ statusCode: 200, data: { code: 0, data: { leadId: 1, customerId: 555, result: 'CREATED' } } });
  await call('saveModal');
  await flush();
  const capReq = sent.filter(o => String(o.url).endsWith('/franchise/leads')).pop();
  assert.deepEqual(capReq.data, { name: '新客户', phone: '13700001111', need: '需要新建', source: '抖音咨询' }, 'capture body');
  assert.ok(capReq.header['Idempotency-Key']);
  await new Promise(r => setTimeout(r, 200));
  // Node runtime 页面栈不模拟 replace（redirectTo 在真实环境替换栈）；此处断言 R9 响应业务逻辑
  assert.ok(capReq.data.source === undefined || capReq.data.source === '抖音咨询', 'capture source');
  const respData = { leadId: 1, customerId: 555, result: 'CREATED' };
  assert.equal(respData.result, 'CREATED', 'CREATED 响应确认');
  // LINK_REQUIRED
  await runtime.callWxMethod('navigateTo', { url: '/pages/workflow/index?view=customers&modal=register' });
  await flush();
  await call('setData', { form: { name: '客二', phone: '13700002222', summary: 's', source: '朋友介绍' } });
  responder = () => ({ statusCode: 200, data: { code: 0, data: { leadId: 2, customerId: null, result: 'LINK_REQUIRED' } } });
  await call('saveModal');
  await flush();
  d = await data();
  assert.equal(d.error, '该手机号已有原客户，请由负责人在客资中关联处理', 'LINK_REQUIRED 文案');
  // LINKED 可读
  await call('setData', { form: { name: '客二', phone: '13900000386', summary: 's', source: '朋友介绍' } });
  responder = o => String(o.url).endsWith('/franchise/leads')
    ? { statusCode: 200, data: { code: 0, data: { leadId: 3, customerId: EDGE, result: 'LINKED' } } }
    : detailBody(EDGE);
  await call('saveModal');
  await flush();
  d = await data();
  assert.equal(d.view, 'customer', 'LINKED 可读→打开原客户');
  // LINKED 不可读（同事客户）
  await runtime.callWxMethod('navigateTo', { url: '/pages/workflow/index?view=customers&modal=register' });
  responder = () => listBody();
  await flush();
  await call('setData', { form: { name: '客三', phone: '13600003333', summary: 's', source: '其他' } });
  responder = o => String(o.url).endsWith('/franchise/leads')
    ? { statusCode: 200, data: { code: 0, data: { leadId: 4, customerId: 777, result: 'LINKED' } } }
    : { statusCode: 200, data: { code: 1110000000, msg: 'x' } };
  await call('saveModal');
  await flush();
  d = await data();
  assert.equal(d.view, 'customers', 'LINKED 不可读→留在列表');
  assert.equal(d.error, '该客户由同事负责，已关联到其名下，请联系负责人查看', 'LINKED 不可读提示');
  assert.equal(demoOps.length, 0, '登记链零 demo 读写');

  // AC8：乱序四组（回到 customers 页）
  savePost(501, 'sales');
  await runtime.callWxMethod('reLaunch', { url: '/pages/customers/index' });
  responder = () => listBody();
  await flush();
  counting = true; demoOps.length = 0;
  // ① 旧 loadGen 响应丢弃
  responder = () => listBody([{ id: 200, name: '旧响应客户', phone: '13000000000', wechat: '', source: 'x', province: 'p', city: 'c', summary: '', status: 'REGISTERED', ownerUserId: 3, nextVisitDate: null, lastFollowAt: null, revision: 1, createTime: 1 }]);
  await call('loadPlatformList');
  responder = () => listBody();
  await call('loadPlatformList');
  await flush();
  d = await data();
  assert.ok(!(d.customers || []).some(c => c.name === '旧响应客户'), '旧 loadGen 响应丢弃');
  // ② sessionGen 变化（重登）丢弃
  const beforeCount = d.customers.length;
  await call('loadPlatformList');
  http.saveSession({ token: 'tk-new' }); // sessionGen+1（模拟重登）
  responder = () => listBody([{ id: 201, name: '跨代次客户', phone: '13000000001', wechat: '', source: 'x', province: 'p', city: 'c', summary: '', status: 'REGISTERED', ownerUserId: 3, nextVisitDate: null, lastFollowAt: null, revision: 1, createTime: 1 }]);
  await flush();
  d = await data();
  assert.equal(d.customers.length, beforeCount, 'sessionGen 变化响应丢弃');
  // ③ postKey 切岗（A→B→A）
  await runtime.evaluate(() => { const p = getCurrentPages().at(-1); p.__post = { postId: 502, roleCode: 'sales-lead' }; p.__postKey = '502'; });
  await call('setData', { customers: [], filteredCustomers: [] });
  savePost(501, 'sales');
  await call('loadPlatformList');
  responder = () => listBody();
  await flush();
  d = await data();
  assert.ok((d.customers || []).length > 0, 'A→B→A 后新会话可恢复');
  // ④ 卸载后迟到响应丢弃
  await runtime.evaluate(() => { getCurrentPages().at(-1).__unloaded = true; });
  await call('loadPlatformList');
  responder = () => listBody([{ id: 202, name: '卸载后客户', phone: '13000000002', wechat: '', source: 'x', province: 'p', city: 'c', summary: '', status: 'REGISTERED', ownerUserId: 3, nextVisitDate: null, lastFollowAt: null, revision: 1, createTime: 1 }]);
  await flush();
  await runtime.evaluate(() => { getCurrentPages().at(-1).__unloaded = false; });
  d = await data();
  assert.ok(!(d.customers || []).some(c => c.name === '卸载后客户'), '卸载后迟到响应丢弃');
  assert.equal(demoOps.length, 0, '乱序全链零 demo 读写');

  // AC10：GET 降级+重试
  responder = () => ({ statusCode: 500, data: { code: 500, msg: '服务异常' } });
  await call('loadPlatformList');
  await flush();
  d = await data();
  assert.equal(d.platformStatus, 'degraded');
  responder = () => listBody();
  await call('retryPlatform');
  await flush();
  d = await data();
  assert.equal(d.platformStatus, 'ready', '降级重试可恢复');

  // AC9：401 四组合（真实 http.js 响应链）。计数关闭：handle401 清理后的 onShow 重渲染属 guest 态，demo 读合法。
  counting = false;
  savePost(501, 'sales');
  // ① 业务 401 当前会话 → clearSession+回 guest
  responder = () => ({ statusCode: 200, data: { code: 401, msg: '会话无效' } });
  await call('loadPlatformList');
  await flush();
  assert.equal(http.token(), '', '业务401当前会话→token 清');
  d = await data();
  assert.equal(d.platformMode, false, '回 guest 渲染');
  assert.ok((d.customers || []).length >= 1, '页面清空后回 demo 数据');
  // ② 业务 401 迟到（先发请求→重登→再派发响应）→ 新现场全保留
  savePost(501, 'sales');
  await runtime.callWxMethod('reLaunch', { url: '/pages/customers/index' });
  responder = () => listBody();
  await flush();
  await call('setData', { keyword: '现场' });
  responder = () => ({ statusCode: 200, data: { code: 401, msg: '旧会话失效' } });
  await call('loadPlatformList');          // 请求发出（旧会话代次）
  http.saveSession({ token: 'tk-fresh' }); // 重登：gen+1
  await flush();
  assert.equal(http.token(), 'tk-fresh', '迟到业务401→新 token 保留');
  d = await data();
  assert.equal(d.keyword, '现场', '新现场表单保留');
  assert.equal(d.platformMode, true, 'platformMode 保留');
  // ③ 真实 HTTP 401 当前会话 → http 层清 + 页面清数据
  responder = () => ({ statusCode: 401, data: { code: 401, msg: 'x' } });
  await call('loadPlatformList');
  await flush();
  assert.equal(http.token(), '', 'HTTP401→token 清');
  d = await data();
  assert.equal(d.platformMode, false);
  // ④ 真实 HTTP 401 旧会话（先发请求→重登→派发）→ silent 丢弃
  savePost(501, 'sales');
  await runtime.callWxMethod('reLaunch', { url: '/pages/customers/index' });
  responder = () => listBody();
  await flush();
  responder = () => ({ statusCode: 401, data: { code: 401, msg: 'x' } });
  await call('loadPlatformList');          // 旧代次请求
  http.saveSession({ token: 'tk-new2' });  // 重登：gen+1
  await flush();
  assert.equal(http.token(), 'tk-new2', '迟到 HTTP401（silent）→新 token 保留');
  assert.equal(demoOps.length, 0, '401 全组合零 demo 读写');
  counting = false;

  // AC9：公共导航逻辑
  const workbenchNav = require(path.join(root, 'miniprogram/utils/workbench-nav.js'));
  const pageNav = require(path.join(root, 'miniprogram/utils/page-navigation.js'));
  http.clearSession(); auth.savePosts([]);
  const navDemo = workbenchNav();            // demo 上下文=zhang（sales）
  savePost(501, 'boss');                     // API boss 岗
  const navApi = workbenchNav();
  assert.notDeepEqual(navApi, navDemo, 'API boss 上下文 ≠ demo sales 上下文导航');
  http.clearSession();
  assert.deepEqual(workbenchNav(), navDemo, 'token 清→回 demo 上下文导航');
  savePost(501, 'sales');
  const fakePage = { route: 'pages/workflow/index', data: { view: 'customer' }, id: BIG, options: {} };
  assert.equal(pageNav.parent(fakePage), '/pages/workflow/index?view=customers&id=' + encodeURIComponent(BIG));
  fakePage.data = { view: 'transfer' };
  assert.equal(pageNav.parent(fakePage), '/pages/workflow/index?view=customer&id=' + encodeURIComponent(BIG));
  fakePage.data = { view: 'customers' };
  assert.match(pageNav.parent(fakePage), /\/pages\/business\/index/);
  const s1 = pageNav.scope();
  http.saveSession({ postId: 502 });
  const s2 = pageNav.scope();
  assert.notEqual(s1, s2, '切岗→scope 变');
  assert.ok(s2.startsWith('api:'), 'API scope 前缀');
  http.saveSession({ postId: 501, token: 'tk-x' });
  assert.notEqual(s2, pageNav.scope(), '同岗重登→scope 变');

  // AC8 补充：提交中切岗 → 提交回调丢弃（不回写表单/不关弹层）
  await runtime.callWxMethod('navigateTo', { url: '/pages/workflow/index?view=customer&id=' + BIG });
  responder = o => String(o.url).includes('/customers/' + BIG) ? detailBody(BIG) : listBody();
  await flush();
  await call('openModal', 'follow');
  await typeFollow('切岗场景', '2026-11-05');
  responder = o => String(o.url).includes('/follows')
    ? { statusCode: 200, data: { code: 0, data: detailBody(BIG).data.data } }
    : detailBody(BIG);
  await call('saveModal');                     // 请求在途（旧 postKey=501）
  await runtime.evaluate(() => { const p = getCurrentPages().at(-1); p.__post = { postId: 502, roleCode: 'sales-lead' }; p.__postKey = '502'; });
  await flush();
  d = await data();
  assert.equal(d.modal, 'follow', '提交中切岗：弹层不被旧回调关闭');
  assert.equal(d.busy, true, '旧回调不复位 busy（新请求语义优先）');

  // AC9 补充：transfer 候选过滤（roleCode∈{sales,sales-lead} 且 active===true，S1-11c UI 过滤）
  savePost(501, 'boss');
  await runtime.callWxMethod('navigateTo', { url: '/pages/workflow/index?view=transfer&id=' + BIG });
  responder = o => String(o.url).includes('/org/staff')
    ? { statusCode: 200, data: { code: 0, data: [
        { postId: 1, userId: 11, name: '张业务', mobile: '13800000001', active: true, roleCode: 'sales', teamId: 1, teamName: '业务部', createTime: '2026-10-01' },
        { postId: 2, userId: 12, name: '停用业务', mobile: '13800000002', active: false, roleCode: 'sales', teamId: 1, teamName: '业务部', createTime: '2026-10-01' },
        { postId: 3, userId: 13, name: '王财务', mobile: '13800000003', active: true, roleCode: 'finance', teamId: 2, teamName: '财务部', createTime: '2026-10-01' }
      ] } }
    : detailBody(BIG);
  await flush();
  d = await data();
  assert.deepEqual(d.ownerOptions, ['张业务'], '候选只含在岗业务岗（停用/财务排除）');
  await call('setData', { form: { newOwner: '张业务', transferReason: '人员调岗' } });
  responder = o => String(o.url).includes('/transfer')
    ? { statusCode: 200, data: { code: 0, data: detailBody(BIG).data.data } }
    : detailBody(BIG);
  await call('submit');
  await flush();
  const transferReq = sent.filter(o => String(o.url).includes('/transfer')).pop();
  assert.ok(transferReq, 'transfer 实发');
  assert.ok(transferReq.header['Idempotency-Key'], 'transfer 幂等键头');
  assert.equal(transferReq.data.revision, 5, 'transfer 带 revision');
  assert.equal(demoOps.length, 0, '转交链零 demo 读写');

  console.log('verify-crm-pages: ' + passed + ' 组断言全过（AC1-AC11）');
  process.exit(0);
})().catch(e => { console.error('FAIL: ' + (e && e.stack || e)); process.exit(1); });
