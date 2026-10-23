const assert = require('assert/strict');

global.wx = { getStorageSync() { return {}; } };

const Receipts = require('../miniprogram/utils/design-receipts');
const SalesFlow = require('../miniprogram/utils/sales-flow');
const DesignDesk = require('../miniprogram/utils/design-admin-workbench');
const CommercialApproval = require('../miniprogram/utils/commercial-approval');

const actor = {
  active: true,
  role: 'hq-design-admin',
  orgType: 'headquarters',
  companyId: 'headquarters',
  staffId: 'hq-design-admin',
  name: '林设计'
};

function customer() {
  const row = {
    id: 'customer-chen-jian-guo',
    projectId: 'project-chen-jian-guo',
    companyId: 'quanzhou',
    designOrganizationId: 'headquarters',
    name: '陈建国',
    city: '泉州市',
    owner: '李业务',
    request: { version: 1, scope: '全案设计', text: '两层乡墅', budgetVersion: 1 },
    budgets: [{ version: 1, total: 548800 }],
    serviceOrders: [{
      id: 'service-1', version: 1, requestVersion: 1,
      total: 12000, first: 6000, tail: 6000,
      recipient: '总部设计服务中心', basis: '全案设计'
    }],
    businessFlow: {
      type: 'sales-offline-v1',
      requestVersion: 1,
      authorization: { kind: 'exception', serviceVersion: 1 },
      completed: { requestVersion: 1, actorId: 'hq-design-admin', date: '2026-10-23' },
      events: []
    }
  };
  const source = CommercialApproval.source(row, 'service');
  row.moneyApprovals = [{
    id: 'money-service-1', kind: 'service', version: 1,
    signature: CommercialApproval.signature(source), status: 'approved', approverRole: 'boss'
  }];
  return row;
}

function stage(expected, paid, received) {
  return { expected, paid, received, ready: paid >= expected && received >= expected };
}

function release(first, tail) {
  return { first, tail, ready: first.ready && tail.ready };
}

function verify(label, receipt, expected) {
  const row = customer();
  const before = JSON.stringify(row);
  Receipts.release = () => receipt;
  const next = SalesFlow.nextRecord(row);
  assert.equal(next.state, expected.state, label + ' 页头状态应保持简短可读');
  assert.equal(next.detail || '', expected.detail || '', label + ' 应在独立明细中展示金额');
  assert.equal(next.owner, expected.owner);
  assert.equal(next.view, expected.view || 'progress');
  assert.equal(next.actionable, expected.actionable || false);
  assert.equal(JSON.stringify(row), before, label + ' 的进度读取不能改写费用或原版本');
  assert.deepEqual(SalesFlow.nextRecord(row), next, label + ' 重复读取不能生成重复费用或新状态');
  return { row, next };
}

const firstReady = stage(600000, 600000, 600000);

verify('门店尚未支付尾款', release(firstReady, stage(600000, 0, 0)), {
  state: '设计尾款待门店付款',
  detail: '应收 ¥6,000.00 · 门店已付 ¥0.00 · 总部实收 ¥0.00 · 差额 ¥6,000.00',
  owner: '门店财务'
});

const partial = verify('门店部分支付且总部已核收', release(firstReady, stage(600000, 200000, 200000)), {
  state: '设计尾款待门店付款',
  detail: '应收 ¥6,000.00 · 门店已付 ¥2,000.00 · 总部实收 ¥2,000.00 · 差额 ¥4,000.00',
  owner: '门店财务'
});

verify('门店足额支付但总部未核收', release(firstReady, stage(600000, 600000, 0)), {
  state: '设计尾款待总部核收',
  detail: '应收 ¥6,000.00 · 门店已付 ¥6,000.00 · 总部实收 ¥0.00 · 差额 ¥6,000.00',
  owner: '总部财务'
});

verify('首尾款都足额核收', release(firstReady, stage(600000, 600000, 600000)), {
  state: '待正式成果下发',
  owner: '设计行政',
  view: 'dispatch',
  actionable: true
});

const desk = DesignDesk.build([partial.row], actor, {
  nextFor: () => partial.next,
  feeApproved: () => true,
  companyNameFor: () => '泉州加盟店'
});
assert.equal(desk.counts['待到账确认'], 1, '稳定保留原有项目筛选语义');
assert.equal(
  desk.rows[0].detail,
  '应收 ¥6,000.00 · 门店已付 ¥2,000.00 · 总部实收 ¥2,000.00 · 差额 ¥4,000.00 · 下一位：门店财务'
);
assert.equal(desk.rows[0].nextOwner, '门店财务');
assert.equal(desk.rows[0].actionable, false, '正常等待不得被误判为行政可下发');

console.log('PASS 设计等待指引：门店付款、总部核收、金额差额、责任人与下发门槛');
