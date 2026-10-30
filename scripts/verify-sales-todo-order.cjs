const assert = require('node:assert/strict');
const Workspace = require('../miniprogram/utils/sales-workspace');
const old = { id: 'old-overdue', actionable: true, group: 'customer', urgency: 0, triggeredAt: '2026-10-29T00:00:00Z' };
const recent = { id: 'new-video', actionable: true, group: 'creation', urgency: 5, triggeredAt: '2026-10-30T00:00:00Z' };
const latest = { key: 'new-approval', triggeredAt: '2026-10-30T01:00:00Z' };
const waiting = { id: 'waiting', actionable: false, triggeredAt: '2026-10-30T02:00:00Z' };
const board = Workspace.todoBoard([old, recent], [waiting], [latest]);
assert.deepEqual(board.todoRows.map(row => row.id), ['staff:new-approval', 'new-video', 'old-overdue', 'waiting']);
assert(board.todoRows[0].primary);
assert(!board.todoRows.at(-1).show, '等待事项不得挤掉可办事项');
const filtered = Workspace.todoBoard([old, recent], [], [], 'creation');
assert.deepEqual(filtered.todoRows.filter(row => row.show).map(row => row.id), ['new-video']);
console.log('PASS 业务今日待办跨类别最新置顶、筛选保序、等待事项不挤占首页办理入口');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const B = require('../miniprogram/utils/business');
  const S = require('../miniprogram/utils/studio');
  const Todos = require('../miniprogram/utils/sales-todos');
  try {
    A.mutate(state => A.loginDemo(state, 'sales-dual'));
    wx.setStorageSync(B.KEY, { version: 1, customers: [] });
    const customer = B.add({ name: '排序客户', phone: '13900008861', source: '到店', city: '武汉' });
    assert(customer.createdAt, '新客户首次沟通待办必须保留建档时间');
    const state = wx.getStorageSync(B.KEY);
    state.customers[0].createdAt = '2020-01-01T00:00:00Z';
    wx.setStorageSync(B.KEY, state);
    const studio = S.read();
    studio.tasks = [{ id: 'order-video', title: '新完成视频', status: 'completed', completedAt: Date.now(), createdAt: 1 }];
    studio.works = []; studio.records = [];
    wx.setStorageSync(S.scope(), studio);
    let pending = Todos.list().pending;
    assert.equal(pending[0].id, 'generation:order-video', '较新的生成完成不能被旧客户跟进优先级压住');
    const work = S.saveWork('order-video');
    pending = Todos.list().pending;
    assert.equal(pending[0].id, 'work:' + work.id, '保存完成接续发布待办');
    assert.equal(pending[0].triggeredAt, work.savedAt);
    assert(!pending.some(row => row.id === 'generation:order-video'), '上一步完成立即退出今日待办');
    const before = JSON.stringify(wx.getStorageSync(B.KEY));
    assert.deepEqual(Todos.list(), Todos.list());
    assert.equal(JSON.stringify(wx.getStorageSync(B.KEY)), before, '读取待办不能改写业务历史');
    console.log('PASS 实际业务模型：新客户时间、生成完成到保存发布接续、刷新不重建');
    const legacyState = wx.getStorageSync(B.KEY);
    const legacy = legacyState.customers[0];
    Object.assign(legacy, {
      request: { at: '2026-01-01T00:00:00Z' },
      paymentConditions: { first: { ready: true }, tail: { ready: false } },
      assignment: { designer: '设计师', at: '2026-01-02T00:00:00Z' },
      intake: { at: '2026-01-03T00:00:00Z' },
      designStarted: { at: '2026-01-04T00:00:00Z' },
      deliverables: [{ version: 1, at: '2026-10-29T09:00:00Z' }],
      deliverableReviews: [{ version: 1, status: 'passed', at: '2026-10-30T09:30:00Z' }]
    });
    wx.setStorageSync(B.KEY, legacyState);
    let legacyTask = Todos.list().pending.find(row => row.id === 'legacy:' + legacy.id);
    assert(legacyTask.url.includes('view=tail-apply'));
    assert.equal(legacyTask.triggeredAt, legacy.deliverableReviews[0].at, '成果审核通过按审核时间接续尾款申请');
    legacy.request.status = 'returned';
    legacy.returns = [{ status: 'pending', at: '2026-10-30T10:00:00Z' }];
    wx.setStorageSync(B.KEY, legacyState);
    legacyTask = Todos.list().pending.find(row => row.id === 'legacy:' + legacy.id);
    assert.equal(legacyTask.triggeredAt, legacy.returns[0].at, '退回补充按退回时间生成待办');
    console.log('PASS 历史设计审核与退回使用实际前置事件时间');
  } finally {
    await runtime.disconnect();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
