const assert = require('node:assert/strict');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const B = require('../miniprogram/utils/business');
  const R = require('../miniprogram/utils/engineering-records');
  const W = require('../miniprogram/pages/build/workbench');
  const E = require('../miniprogram/pages/build/model');
  const M = require('../miniprogram/pages/admin/model');
  const Keys = require('../miniprogram/utils/storage-keys');
  const Seed = require('../miniprogram/utils/engineering-seed');
  const day = A.localDate();
  const at = hour => day + 'T' + String(hour).padStart(2, '0') + ':00:00+08:00';
  const login = (id, role) => {
    A.mutate(s => A.loginDemo(s, id));
    if (role) A.mutate(s => A.switchIdentity(s, s.session.companyId, role));
  };
  const own = () => login('engineering-dual', 'engineer');
  own();
  const actor = require('../miniprogram/utils/access').context();
  wx.setStorageSync(B.KEY, { ...B.initial(), customers: [] });
  M.load();
  const project = (id, hour) => {
    const p = Seed.project({ id, projectId: id, companyId: actor.companyId,
      name: id, customer: id, city: '武汉', managerId: actor.staffId,
      manager: actor.name, accepted: day, acceptedAt: at(hour), sample: true });
    R.init(p);
    return p;
  };
  const save = projects => wx.setStorageSync(E.KEY, { version: 1, projects });
  const old = project('order-old', 8), newer = project('order-new', 9);
  save([old, newer]);
  assert.equal(W.home().previewTasks[0].projectId, newer.id,
    '新接收项目的开工准备必须排在旧项目之前');
  assert.equal(W.home().previewTasks[0].triggeredAt, at(9));
  old.events.push({ title: '补充无关备注', time: at(12) });
  save([old, newer]);
  assert.equal(W.home().previewTasks[0].projectId, newer.id,
    '无关项目事件不能把旧待办重新置顶');
  assert.deepEqual(W.snapshot().tasks, W.snapshot().tasks, '刷新不改变待办时间与顺序');

  const funded = project('order-funded', 7); save([funded]);
  const file = { name: '排序验证凭据.pdf', sample: true };
  R.run(funded.id, 'prepare', { total: '100', first: '60', file,
    note: '开工准备测试', nodes: funded.plan.nodes.map(n => ({ ...n, start: day, end: day })) });
  const prepared = R.funds(R.get(funded.id)).plan.at;
  login('finance-dual', 'finance');
  assert.equal(R.financialTasks(M.load()).find(t => t.id === funded.id + ':collection:start').triggeredAt,
    prepared, '开工收款待办使用合同收款约定实际生效时间');
  R.run(funded.id, 'collect', { stage: 'start', amount: '60', date: day,
    transaction: 'ORDER-START', file, receiver: '本公司' });
  own();
  const collected = R.get(funded.id).collections[0].at;
  assert.equal(R.tasks(R.get(funded.id)).pending.find(t => t.id.endsWith(':start')).triggeredAt,
    collected, '开工款足额登记触发工程开工待办');

  const p = project('order-stages', 7);
  p.actualStart = { date: day, at: at(8) };
  p.plan.nodes[0].status = '已完成';
  p.plan.nodes[0].completedAt = day;
  p.inspections.push({ nodeId: p.plan.nodes[0].id, result: '通过', at: at(10) });
  const material = { id: 'material-1', nodeId: p.plan.nodes[1].id,
    name: '水泥', quantity: 1, unit: '包', submitted: true };
  p.materials.push(material);
  p.supplies.push({ id: 'supply-1', materialId: material.id, quantity: 1,
    unitPrice: 0, payer: '本店', status: '采购信息待记录' });
  p.recordFlow.materialPlans.push({ id: 'plan-1', nodeId: material.nodeId,
    materialIds: [material.id], date: day, due: day, at: at(11) });
  save([p]);
  let tasks = R.tasks(R.get(p.id));
  assert.equal(tasks.pending[0].id, p.id + ':record:arrival-plan-1');
  assert.equal(tasks.pending[0].triggeredAt, at(11), '到货待办使用计划实际提交时间');
  assert.equal(tasks.pending.find(t => t.id.endsWith(':node-n2')).triggeredAt, at(10),
    '下一施工节点使用上一节点实际完成记录时间');
  assert.equal(tasks.waiting.find(t => t.id.endsWith(':payment-plan-1')).triggeredAt, at(11));
  const yesterday = new Date(Date.parse(day + 'T12:00:00+08:00') - 86400000).toISOString().slice(0, 10);
  p.recordFlow.materialPlans[0].due = yesterday;
  p.recordFlow.materialFollowups = [{ planId: 'plan-1', date: yesterday,
    at: yesterday + 'T08:00:00+08:00', promiseDate: yesterday }];
  p.recordFlow.materialPlans[0].at = yesterday + 'T07:00:00+08:00';
  save([p]);
  const reminder = R.tasks(R.get(p.id)).pending.find(t => t.id.endsWith(':material-followup-plan-1'));
  assert.equal(Date.parse(reminder.triggeredAt), Date.parse(day + 'T00:00:00+08:00'),
    '承诺到货日过期的催办按到期次日触发，不用每次刷新时间');
  p.recordFlow.materialFollowups = [];
  p.recordFlow.materialPlans[0].due = day;
  p.recordFlow.materialPlans[0].at = at(11);
  save([p]);
  login('finance-dual', 'finance');
  assert.equal(R.financialTasks(M.load()).find(t => t.id === 'material-terms:plan-1').triggeredAt,
    at(11), '材料采购资料待办与工程计划使用同一触发时间');
  own();
  p.materials = []; p.supplies = []; p.recordFlow.materialPlans = [];
  p.plan.nodes.forEach((node, i) => {
    node.status = '已完成'; node.completedAt = day;
    p.inspections.push({ nodeId: node.id, result: '通过', at: at(i === p.plan.nodes.length - 1 ? 13 : 10) });
  });
  save([p]);
  assert.equal(R.tasks(R.get(p.id)).pending[0].triggeredAt, at(13), '全部节点完成触发交付');
  p.delivery = { date: day, at: at(14) }; save([p]);
  assert.equal(R.tasks(R.get(p.id)).pending[0].triggeredAt, at(14), '交付触发结算');
  p.settlement = { date: day, at: at(15) }; save([p]);
  assert.equal(R.tasks(R.get(p.id)).pending[0].triggeredAt, at(15), '结算触发归档');
  p.archived = true; save([p]);
  assert.equal(R.tasks(R.get(p.id)).pending.length, 0, '归档关闭工程待办');

  // Exercise real source writes: purchase terms must carry the time when finance can pay.
  const live = project('order-live', 7); save([live]);
  R.run(live.id, 'material-plan', { nodeId: live.plan.nodes[0].id, due: day,
    lines: [{ name: '测试材料', quantity: '1', unit: '件' }] });
  const plan = R.get(live.id).recordFlow.materialPlans[0];
  login('finance-dual', 'finance');
  R.run(live.id, 'material-terms', { planId: plan.id, file,
    lines: [{ id: plan.materialIds[0], supplier: '测试供应商', unitPrice: '10' }] });
  const supply = wx.getStorageSync(Keys.engineering).projects[0].supplies[0];
  assert(supply.termsAt, '采购金额登记必须保存实际时间以接续财务付款待办');
  assert.equal(supply.at, plan.at, '填写采购金额不改写材料计划最初提交时间');
  own();
  const legacy = project('order-legacy', 7);
  delete legacy.recordFlow;
  legacy.materials = [{ id: 'legacy-material', name: '材料', submitted: true, quantity: 1 }];
  legacy.supplies = [{ id: 'legacy-supply', materialId: 'legacy-material', quantity: 1,
    at: at(12), date: '2099-01-01', unitPrice: 0, payer: '' }];
  save([newer, legacy]);
  const legacyHome = W.home();
  assert.equal(legacyHome.previewTasks[0].projectId, legacy.id,
    '旧工程实际新建供应事项按发生时间置顶，不因承诺到货日尚未来临而隐藏');
  assert(legacyHome.tasks.filter(t => t.projectId === legacy.id).every(t =>
    !t.triggeredAt || Date.parse(t.triggeredAt) <= Date.parse(at(12))),
  '计划到货日期不能冒充实际触发时间');
  console.log('PASS 工程待办按真实触发时间排序，覆盖跨项目、节点、材料、交付结算与刷新稳定性');
  await runtime.disconnect();
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
