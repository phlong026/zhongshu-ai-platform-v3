const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

const Notifications = require('../miniprogram/utils/design-notifications');

const order = {
  id: 'design-order-customer-demo-v1',
  amountCents: 800000,
  area: 400,
  rateCents: 2000,
  pricingVersion: 2,
  status: 'paid',
  channel: 'wechat',
  paidAt: '2026-10-23T03:20:00.000Z',
  transactionId: 'DEMO-WECHAT-001',
  paidById: 'zhang',
  requestVersion: 1,
  serviceVersion: 1,
  companyId: 'quanzhou',
  projectId: 'project-customer-demo',
  customerId: 'customer-demo'
};

const customer = {
  id: 'customer-demo',
  projectId: order.projectId,
  companyId: order.companyId,
  designOrganizationId: 'headquarters',
  name: '陈先生',
  businessFlow: {
    type: 'sales-offline-v1',
    paymentMode: 'area-full-demo-v1',
    paymentOrder: order
  }
};

const checkout = {
  is: row => row.businessFlow?.paymentMode === 'area-full-demo-v1',
  paid: row => row.businessFlow?.paymentOrder?.status === 'paid',
  order: row => row.businessFlow?.paymentOrder || null
};

const actors = {
  store: {
    active: true,
    role: 'finance',
    orgType: 'store',
    companyId: 'quanzhou',
    staffId: 'finance-dual',
    personId: 'person-finance-dual',
    userId: 'demo-finance-dual'
  },
  headquarters: {
    active: true,
    role: 'finance',
    orgType: 'headquarters',
    companyId: 'headquarters',
    staffId: 'hq-finance',
    personId: 'person-hq-finance',
    userId: 'demo-hq-finance'
  }
};

for (const actor of Object.values(actors)) {
  const messages = Notifications.list({ messageReads: {} }, {
    actor,
    customers: [customer],
    checkout,
    canView: () => false
  });
  assert.equal(messages.length, 1, '支付成功后门店与总部财务都应收到知会');
  assert.equal(messages[0].route, 'messages');
  assert.equal(messages[0].objectId, customer.id);
  assert.match(messages[0].title, /模拟支付已完成/);
  assert.match(messages[0].description, /¥8,000/);
  assert.match(messages[0].description, /微信支付/);
  assert.match(messages[0].description, /400㎡ × ¥20\/㎡/);
  assert.doesNotMatch(JSON.stringify(messages[0]), /DEMO-WECHAT-001/);
}

const storeMessage = Notifications.list({ messageReads: {} }, {
  actor: actors.store,
  customers: [customer],
  checkout,
  canView: () => false
})[0];
const readState = { messageReads: {} };
Notifications.markRead(readState, storeMessage.id, {
  actor: actors.store,
  customers: [customer],
  checkout,
  canView: () => false
});
assert(Notifications.list(readState, {
  actor: actors.store,
  customers: [customer],
  checkout,
  canView: () => false
})[0].read, '财务消息已读状态应按人员保存');

assert.equal(Notifications.list({}, {
  actor: { ...actors.store, companyId: 'other-store' },
  customers: [customer], checkout, canView: () => false
}).length, 0, '其他门店财务不可见');
assert.equal(Notifications.list({}, {
  actor: { ...actors.headquarters, role: 'hq-design-admin' },
  customers: [customer], checkout, canView: () => false
}).length, 0, '支付抄送不应混入设计行政的成果下发消息');
assert.equal(Notifications.list({}, {
  actor: actors.store,
  customers: [{ ...customer, businessFlow: { ...customer.businessFlow, paymentOrder: { ...order, status: 'pending' } } }],
  checkout, canView: () => false
}).length, 0, '待支付订单不得提前抄送付款成功');

const Finance = require('../miniprogram/utils/finance-source');
const oldPayable = stage => ({
  id: `business-design:${customer.id}:1:${stage}`,
  source: { module: 'business', id: customer.id, kind: 'design', stage },
  total: 400000,
  status: '待办理'
});
const storeLedger = Finance.checkoutRecords({
  companyId: 'quanzhou',
  payables: [oldPayable('first'), oldPayable('tail')],
  payments: [{
    id: 'old-first-payment',
    source: { module: 'business', id: customer.id, kind: 'design', stage: 'first' },
    amount: 400000,
    status: '已核实'
  }],
  receipts: []
}, { checkout, customers: [customer] });
assert.equal(storeLedger.payables.length, 0, '新模式必须屏蔽旧首款、尾款付款待办');
assert.equal(storeLedger.payments.length, 1, '门店财务只保留一笔模拟全额付款事实');
assert.equal(storeLedger.payments[0].amount, order.amountCents);
assert.equal(storeLedger.payments[0].status, '体验模拟');
assert.equal(storeLedger.payments[0].affectsFinance, false);
assert.equal(storeLedger.payments[0].registrationMode, 'simulated-online-v1');
assert.equal(storeLedger.payments[0].readOnly, true);
assert.match(storeLedger.payments[0].name, /体验模拟/);
assert.doesNotMatch(JSON.stringify(storeLedger.payments[0]), /DEMO-WECHAT-001/);
assert.equal(Finance.summary({ ...storeLedger, period: '2026-10', refunds: [] }).paid, 0, '体验模拟付款不计入正式实付');

const headquartersLedger = Finance.checkoutRecords({
  companyId: 'headquarters',
  payables: [],
  payments: [],
  receipts: [{
    id: 'old-tail-incoming',
    source: { module: 'intercompany', customerId: customer.id, kind: 'design', stage: 'tail' },
    amount: 400000,
    status: '待登记'
  }]
}, { checkout, customers: [customer] });
assert.equal(headquartersLedger.receipts.length, 1, '总部财务只展示一笔模拟全额收款抄送');
assert.equal(headquartersLedger.receipts[0].status, '体验模拟');
assert.equal(headquartersLedger.receipts[0].affectsFinance, false);
assert.equal(headquartersLedger.receipts[0].amount, order.amountCents);
assert.equal(headquartersLedger.receipts[0].readOnly, true);
assert.equal(headquartersLedger.receipts[0].source.fromCompanyId, 'quanzhou');
assert.equal(headquartersLedger.receipts[0].source.toCompanyId, 'headquarters');
assert.equal(Finance.summary({ ...headquartersLedger, period: '2026-10', refunds: [] }).income, 0, '体验模拟收款不计入总部正式实收');

Finance.checkoutRecords(storeLedger, { checkout, customers: [customer] });
assert.equal(storeLedger.payments.length, 1, '重复刷新财务台不得重复生成抄送流水');

console.log('PASS 一次性设计费模拟支付：双方财务抄送、旧首尾款屏蔽、只读流水、组织隔离与隐私契约');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  try {
    const A = require('../miniprogram/utils/account');
    const B = require('../miniprogram/utils/business');
    const SF = require('../miniprogram/utils/sales-flow');
    const Pay = require('../miniprogram/utils/design-checkout');
    const M = require('../miniprogram/pages/admin/model');
    const Desk = require('../miniprogram/utils/finance-desk');
    const login = (id, role) => {
      A.mutate(state => A.loginDemo(state, id));
      if (role) A.mutate(state => A.switchIdentity(state, state.session.companyId, role));
    };

    login('hq-design-admin');
    Pay.savePricing({ rate: '20', scope: '全套施工图', recipient: '众墅之家总部', active: true });
    const price = Pay.pricing();
    login('sales-dual', 'sales-lead');
    const created = B.add({ name: '财务抄送测试', phone: '13900008902', source: '门店到访', city: '武汉市', summary: '两层住宅' });
    B.write(created.id, row => row.budgets.push({
      version: 1,
      area: 400,
      parameters: { width: 12.5, depth: 16, floors: 2, area: 400 },
      date: A.localDate()
    }), '保存参考预算', 'budget');
    SF.run(created.id, 'commission-submit', {
      paymentMode: 'area-full-demo-v1',
      text: '两层住宅',
      scope: '全套施工图',
      checked: true,
      __budgetVersion: 1,
      __pricingVersion: price.version
    });
    const paymentOrder = Pay.order(B.customer(created.id));
    SF.run(created.id, 'commission-pay', { channel: 'alipay', outcome: 'success', orderId: paymentOrder.id });

    login('finance-dual', 'finance');
    const store = M.load();
    const storePayment = store.payments.find(row => row.source?.orderId === paymentOrder.id);
    assert(storePayment?.simulated && storePayment.readOnly, '门店财务应看到只读模拟付款流水');
    assert(!store.payables.some(row => row.source?.id === created.id && row.source?.kind === 'design'), '新模式不得再生成首款或尾款付款单');
    assert(store.payables.some(row => row.id === 'business-reimburse:' + created.id + ':' + paymentOrder.serviceVersion && row.stage === '业务报销' && row.status === '待办理'), '模拟支付后应自动生成业务报销应付（免审待办理）');
    assert(!Finance.tasks(store).some(row => row.id.startsWith('business-design:' + created.id)), '门店财务不得出现旧首尾款付款办理待办');
    assert(Finance.tasks(store).some(row => row.id === 'business-reimburse:' + created.id + ':' + paymentOrder.serviceVersion && row.view === 'pay'), '报销应付应进入财务付款待办');
    const storeServices = Desk.services(store).filter(row => row.customerId === created.id);
    assert.equal(storeServices.length, 1, '门店财务项目账只显示一条一次性设计费');
    assert(storeServices[0].simulated && storeServices[0].readOnly);
    assert.match(Desk.displayService(storeServices[0]).label, /一次性设计费/);
    assert.match(Desk.displayService(storeServices[0]).status, /体验模拟支付已完成/);
    const storeProjectRow = Desk.projectRows(store).find(row => row.id === created.projectId);
    assert.equal(storeProjectRow.design.length, 1);
    assert.equal(storeProjectRow.simulatedDesign, true);
    const storeProject = Desk.project(store, created.projectId);
    assert.equal(storeProject.simulatedDesign, true);
    assert.doesNotMatch(JSON.stringify(storeProject.design), /设计首款|设计尾款|待老板审批|总部实际到账/);
    wx.reLaunch({ url: '/pages/admin/finance-desk/index?view=project&id=' + encodeURIComponent(created.projectId) });
    const projectPage = getCurrentPages().at(-1);
    assert(projectPage.data.detail.simulatedDesign, '真实财务项目页应进入体验模拟文案分支');
    const storeAccount = A.load();
    assert(A.scoped(storeAccount, storeAccount.messages).some(row => row.objectId === created.id && row.simulated), '门店财务应收到支付成功知会');

    login('hq-finance', 'finance');
    const headquarters = M.load();
    assert(!Desk.home(headquarters).shortcuts.some(row => row.label === '付款登记'), '总部财务不应出现付款登记入口');
    const headquartersReceipt = headquarters.receipts.find(row => row.source?.orderId === paymentOrder.id);
    assert(headquartersReceipt?.simulated && headquartersReceipt.readOnly, '总部财务应看到只读模拟收款抄送');
    assert(!Finance.tasks(headquarters).some(row => String(row.id).includes(created.id)), '总部财务不得再出现人工到账登记待办');
    const headquartersServices = Desk.services(headquarters).filter(row => row.customerId === created.id);
    assert.equal(headquartersServices.length, 1, '总部财务往来账只显示一条一次性设计费');
    assert(headquartersServices[0].simulated && headquartersServices[0].readOnly);
    assert.match(Desk.displayService(headquartersServices[0]).status, /体验模拟支付已完成/);
    const partner = Desk.partners(headquarters).find(row => row.design.some(item => item.customerId === created.id));
    assert(partner?.simulatedDesign, '总部加盟商往来页应进入体验模拟文案分支');
    wx.reLaunch({ url: '/pages/admin/finance-desk/index?view=partner&id=' + encodeURIComponent(created.companyId) });
    const partnerPage = getCurrentPages().at(-1);
    assert(partnerPage.data.partner.simulatedDesign, '真实总部往来页应进入体验模拟文案分支');
    const headquartersAccount = A.load();
    assert(A.scoped(headquartersAccount, headquartersAccount.messages).some(row => row.objectId === created.id && row.simulated), '总部财务应收到支付成功知会');
    console.log('PASS 真实委托集成：一次性模拟支付后双方财务可见，旧首尾款与人工核收待办为零');
    const template = fs.readFileSync(path.resolve('miniprogram/pages/admin/finance-desk/index.wxml'), 'utf8');
    assert(template.includes('detail.simulatedDesign') && template.includes('partner.simulatedDesign'), '项目账和总部往来页须对体验模拟文案做显式分支');
    assert(template.includes('体验模拟支付记录不计入正式实收实付'), '页面应明确体验数据的账务边界');
    assert(template.includes('首尾款足额到账且设计完成后') && template.includes('总部实际到账'), '历史设计费页面文案须继续保留');
  } finally {
    await runtime.disconnect();
  }
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
