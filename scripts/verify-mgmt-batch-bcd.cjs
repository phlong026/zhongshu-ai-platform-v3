// 批次BCD收尾验证：放行登记（C2）与月末对账（D1）。运行：node scripts/verify-mgmt-batch-bcd.cjs
const assert = require('assert/strict');
(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  try {
    const A = require('../miniprogram/utils/account');
    const M = require('../miniprogram/pages/admin/model');
    const Ops = require('../miniprogram/utils/operations');
    const F = require('../miniprogram/utils/finance-source');
    const Desk = require('../miniprogram/utils/finance-desk');
    const login = id => A.mutate(state => A.loginDemo(state, id));

    // —— 月末对账（D1）——
    login('sales-dual');
    assert.throws(() => M.mutate(s => M.apply(s, 'month-reconcile', 'company:quanzhou', { checked: true })), /无权|财务或老板/, '业务岗不能完成月末对账');
    login('finance-wang');
    M.mutate(s => M.apply(s, 'month-reconcile', 'company:quanzhou', { checked: true }));
    let s = M.load();
    const period = F.period(s);
    let rows = (s.operations.reconciliations || []).filter(r => r.period === period);
    assert.equal(rows.length, 1, '当月应生成一条对账留痕');
    assert.equal(rows[0].by, '王财务', '留痕应记录对账人');
    assert(Number.isInteger(rows[0].income) && Number.isInteger(rows[0].paid) && Number.isInteger(rows[0].net), '留痕应包含当月收支汇总');
    M.mutate(s => M.apply(s, 'month-reconcile', 'company:quanzhou', { checked: true }));
    assert.equal(((M.load().operations.reconciliations) || []).filter(r => r.period === period).length, 1, '重复对账应替换同月留痕而非累加');
    assert(Desk.flows(M.load(), 'reconcile').some(r => r.id === 'reconcile-' + period), '账本「月末对账」分段应列出对账历史');
    assert.throws(() => M.mutate(b => M.apply(b, 'month-reconcile', 'company:quanzhou', {})), /核对/, '未勾选核对不能留痕');
    console.log('PASS 月末对账：财务一键留痕、同月替换、业务岗被拦、历史进账本分段');

    // —— 放行登记（C2）——
    login('sales-dual');
    const B = require('../miniprogram/utils/business');
    const created = B.add({ name: '放行登记测试', phone: '13900008903', source: '门店到访', city: '武汉市', summary: '两层住宅' });
    // 测试布置：直接写入合同事实（绕过业务页面的签约流程，仅用于构造闸门场景）
    const bs = wx.getStorageSync('zhongshu-business-v3');
    bs.customers.find(x => x.id === created.id).contract = { total: '4000', version: 1, file: { name: '合同.pdf', sample: true } };
    wx.setStorageSync('zhongshu-business-v3', bs);
    const pid = created.projectId;
    M.mutate(st => M.apply(st, 'plan-submit', pid, { installments: [{ name: '开工款', amount: '1600', stage: 'start', due: '2026-10-01' }, { name: '封顶款', amount: '2400', stage: 'topping', due: '2026-11-01' }] }));
    login('boss');
    let st = M.load();
    let g = Ops.gate(st, pid, 'start');
    assert.equal(g.allowed, false, '未收款前开工应被拦截');
    assert.match(g.note, /查实收款|放行登记/, '拦截提示应指向放行登记出口');
    M.mutate(x => M.apply(x, 'credit-exception', pid, { stage: 'start', note: '业主资金周转，微信已同意缓付两周', checked: true }));
    st = M.load();
    g = Ops.gate(st, pid, 'start');
    assert.equal(g.allowed, true, '老板放行登记后本阶段应放行');
    assert.equal(g.exception.by, '张老板', '放行留痕应记录登记人');
    assert.match(g.note, /已登记放行/);
    assert.equal(g.exception.until, null, '放行登记不再强制限额限期');
    assert.throws(() => M.mutate(x => M.apply(x, 'credit-exception', pid, { stage: 'delivery', checked: true })), /放行原因|核对/, '缺少原因不能放行');
    assert.equal(Ops.gate(st, pid, 'topping').allowed, false, '未放行阶段仍按收款拦截');
    console.log('PASS 放行登记：一键留痕放行、未放行阶段继续拦截、缺原因被拦');
  } finally {
    await runtime.disconnect();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
