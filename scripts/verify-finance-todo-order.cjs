const assert = require('node:assert/strict');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const Account = require('../miniprogram/utils/account');
  const Finance = require('../miniprogram/utils/finance-ledger');
  const OwnerHome = require('../miniprogram/pages/admin/owner-home');
  const OwnerModel = require('../miniprogram/pages/admin/owner-model');
  const Commercial = require('../miniprogram/utils/commercial-approval');
  const Material = require('../miniprogram/utils/material-finance');
  const Operations = require('../miniprogram/utils/operations');
  const keys = require('../miniprogram/utils/storage-keys');

  try {
    Account.mutate(state => Account.loginDemo(state, 'finance-wang'));
    const financeTasks = Finance.tasks({
      companyId: 'quanzhou',
      receipts: [{
        id: 'old-receipt', companyId: 'quanzhou', projectId: 'old-project', customer: '旧客户',
        name: '工程款', amount: 10000, status: '待核算', registeredAt: '2026-10-30T08:00:00+08:00',
        source: { module: 'business', id: 'old-customer' }
      }],
      payables: [{
        id: 'new-payable', companyId: 'quanzhou', projectId: 'new-project', customer: '新客户',
        name: '材料款', stage: '材料采购', total: 20000, status: '待办理',
        triggeredAt: '2026-10-30T10:00:00+08:00'
      }],
      payments: [], refunds: [], operations: {}
    });
    assert.deepEqual(financeTasks.slice(0, 2).map(row => row.id), ['new-payable', 'old-receipt']);
    assert.equal(financeTasks[0].triggeredAt, '2026-10-30T10:00:00+08:00');

    const customer = {
      id: 'customer-new', name: '新客户', companyId: 'quanzhou', ownerId: 'zhang',
      quotes: [{ version: 1, total: 30000, at: '2026-10-30T12:00:00+08:00' }]
    };
    assert.equal(Commercial.tasks(customer)[0].triggeredAt, '2026-10-30T12:00:00+08:00');

    const originalEngineering = wx.getStorageSync(keys.engineering);
    wx.setStorageSync(keys.engineering, { projects: [{
      id: 'engineering-direct', projectId: 'project-direct', companyId: 'quanzhou', customer: '直付客户', managerId: 'wang-manager',
      supplies: [{ id: 'supply-direct', quantity: 1, unitPrice: 100, freight: 0, payer: '业主直接支付', supplier: '材料商', basis: { name: '材料依据.pdf' }, termsAt: '2026-10-30T11:00:00+08:00' }]
    }] });
    const directTask = Material.tasks({ companyId: 'quanzhou', operations: { direct: [], directApprovals: [] } })[0];
    assert.equal(directTask.triggeredAt, '2026-10-30T11:00:00+08:00');
    wx.setStorageSync(keys.engineering, originalEngineering);

    Account.mutate(state => Account.loginDemo(state, 'boss'));
    const expenseTask = Operations.pending({
      companyId: 'quanzhou', managementSettings: { approvals: { expenseLine: 0 } }, staff: [], payables: [], receipts: [], payments: [], refunds: [],
      operations: { expenses: [{ id: 'expense-new', projectId: 'company:quanzhou', name: '业务垫付报销', amount: 10000, date: '2026-10-01', status: 'pending', at: '2026-10-30T11:30:00+08:00' }], direct: [], directApprovals: [] }
    }).find(row => row.record === 'expense-new');
    assert.equal(expenseTask.triggeredAt, '2026-10-30T11:30:00+08:00');

    const originalBusiness = wx.getStorageSync(keys.business);
    const serviceOrder = { version: 1, requestVersion: 1, total: 10000, first: 5000, tail: 5000, recipient: '设计中心', file: { name: '设计委托.pdf' }, at: '2026-10-01T08:00:00+08:00' };
    const designCustomer = {
      id: 'customer-design-order', projectId: 'project-design-order', companyId: 'quanzhou', designOrganizationId: 'quanzhou', name: '设计客户', ownerId: 'zhang',
      request: { version: 1, at: '2026-10-01T08:00:00+08:00' }, serviceOrders: [serviceOrder],
      businessFlow: { type: 'sales-offline-v1', requestVersion: 1, submittedAt: '2026-10-01T08:00:00+08:00', completed: { at: '2026-10-30T12:00:00+08:00' } },
      designPayments: [{ id: 'first-paid', stage: 'first', serviceVersion: 1, amount: 500000, status: '已核实', reviewerId: 'finance-lead', registeredAt: '2026-10-20T08:00:00+08:00' }]
    };
    designCustomer.moneyApprovals = [{
      id: 'money-service-1', kind: 'service', signature: Commercial.signature(Commercial.source(designCustomer, 'service')),
      status: 'approved', approverRole: 'boss', reviewedAt: '2026-10-30T10:00:00+08:00'
    }];
    const approvalCustomer = {
      ...designCustomer, id: 'customer-approved-order', projectId: 'project-approved-order', name: '新批准客户',
      businessFlow: { type: 'sales-offline-v1', requestVersion: 1, submittedAt: '2026-10-01T08:00:00+08:00' }, designPayments: []
    };
    approvalCustomer.moneyApprovals = [{
      id: 'money-service-approved', kind: 'service', signature: Commercial.signature(Commercial.source(approvalCustomer, 'service')),
      status: 'approved', approverRole: 'boss', reviewedAt: '2026-10-30T10:00:00+08:00'
    }];
    wx.setStorageSync(keys.business, { customers: [designCustomer, approvalCustomer] });
    const designLedger = Finance.merge({ companyId: 'quanzhou', staff: [], receipts: [], payables: [], payments: [], refunds: [], operations: {} });
    const firstPayable = designLedger.payables.find(row => row.id === 'business-design:customer-approved-order:1:first');
    const tailPayable = designLedger.payables.find(row => row.id === 'business-design:customer-design-order:1:tail');
    assert.equal(firstPayable.triggeredAt, '2026-10-30T10:00:00+08:00');
    assert.equal(tailPayable.triggeredAt, '2026-10-30T12:00:00+08:00');
    Account.mutate(state => Account.loginDemo(state, 'finance-wang'));
    assert.deepEqual(Finance.tasks(designLedger).filter(row => [tailPayable.id, firstPayable.id].includes(row.id)).map(row => row.id), [tailPayable.id, firstPayable.id]);
    wx.setStorageSync(keys.business, originalBusiness);

    const ownerRows = OwnerHome.todoRows({
      approvals: [{ key: 'money:old', name: '旧审批', amount: '¥ 1', status: '待老板审批', triggeredAt: '2026-10-30T08:00:00+08:00' }],
      reviews: [{ key: 'review:new', name: '新复核', status: '待复核', triggeredAt: '2026-10-30T10:00:00+08:00' }],
      state: {
        staff: [{ id: 'former', name: '原负责人', active: false }, { id: 'disabled-latest', name: '刚停用负责人', active: false, disabledAt: '2026-10-30T13:00:00+08:00' }],
        work: [
          { id: 'handover-latest', kind: 'project-responsibility', customer: '客户', name: '工程事项', owner: 'former', stage: '办理中', handoverRequired: true, handoverRequiredAt: '2026-10-30T12:00:00+08:00' },
          { id: 'disabled-handover', kind: 'customer-responsibility', customer: '新客户', name: '客户跟进', owner: 'disabled-latest', stage: '办理中' }
        ],
        transfers: []
      },
      staff: []
    });
    assert.deepEqual(ownerRows.map(row => row.key), ['handover:disabled-handover', 'handover:handover-latest', 'review:new', 'money:old']);
    assert.equal(ownerRows[0].triggeredAt, '2026-10-30T13:00:00+08:00');

    Account.mutate(state => Account.loginDemo(state, 'boss'));
    assert(!OwnerModel.snapshot().reviews.some(row => row.key === 'review:2026-09'));

    console.log('PASS 财务、老板跨类型待办按实际触发时间倒序，旧薪酬复核不进入今日待办');
  } finally {
    await runtime.disconnect();
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
