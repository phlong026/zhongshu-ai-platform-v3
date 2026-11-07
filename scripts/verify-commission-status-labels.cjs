const assert = require('node:assert/strict');

global.wx = { getStorageSync() { return {}; } };

const Labels = require('../miniprogram/utils/status-labels').commission;
const SalesFlow = require('../miniprogram/utils/sales-flow');
const DesignDesk = require('../miniprogram/utils/design-admin-workbench');
const CommercialApproval = require('../miniprogram/utils/commercial-approval');
const Intercompany = require('../miniprogram/utils/intercompany');
const Receipts = require('../miniprogram/utils/design-receipts');

function customer() {
  const row = {
    id:'status-customer', projectId:'status-project', companyId:'quanzhou', designOrganizationId:'headquarters', name:'状态口径客户',
    request:{ version:1, scope:'全案设计', text:'两层乡墅', budgetVersion:1 }, budgets:[{ version:1, total:548800 }],
    serviceOrders:[{ id:'service-1', version:1, requestVersion:1, total:12000, first:6000, tail:6000, recipient:'总部设计服务中心', basis:'全案设计' }],
    businessFlow:{ type:'sales-offline-v1', requestVersion:1, authorization:{ kind:'exception', serviceVersion:1 }, events:[] }
  };
  const source = CommercialApproval.source(row, 'service');
  row.moneyApprovals = [{ id:'approval-1', kind:'service', version:1, signature:CommercialApproval.signature(source), status:'approved', approverRole:'boss' }];
  return row;
}

const stage = (expected, paid, received) => ({ expected, paid, received, ready:paid >= expected && received >= expected });
const readyFirst = stage(600000, 600000, 600000);
let first = stage(600000, 0, 0);
let tail = stage(600000, 0, 0);
Intercompany.condition = (row, name) => name === 'first' ? first : tail;
Receipts.release = () => ({ first, tail, ready:first.ready && tail.ready });

function verify(label, mutate, expected) {
  const row = customer();
  mutate(row);
  const next = SalesFlow.nextRecord(row);
  assert.equal(next.state, expected.state, label + ' 业务主链状态');
  assert.equal(DesignDesk.statusOf(row, next), expected.state, label + ' 设计工作台应使用同一核心词');
  if (expected.paymentStage) assert.equal(next.paymentStage, expected.paymentStage, label + ' 保留首尾款阶段');
  if (expected.roleState) assert.match(next.roleState, expected.roleState, label + ' 角色说明单独保留');
  return next;
}

first = stage(600000, 0, 0); tail = stage(600000, 0, 0);
const firstPending = verify('首款未登记', () => {}, { state:Labels.pay, paymentStage:'首款', roleState:/首款/ });

first = readyFirst;
verify('设计办理', () => {}, { state:Labels.designing });
verify('修改办理', row => { row.businessFlow.feedbacks = [{ status:'pending' }]; }, { state:Labels.modifying });

const tailPending = verify('尾款未支付', row => { row.businessFlow.completed = { requestVersion:1 }; }, { state:Labels.pay, paymentStage:'尾款', roleState:/尾款/ });
tail = stage(600000, 600000, 0);
verify('尾款待总部核收', row => { row.businessFlow.completed = { requestVersion:1 }; }, { state:Labels.receiptWaiting, paymentStage:'尾款', roleState:/尾款/ });
tail = stage(600000, 600000, 600000);
verify('待下发', row => { row.businessFlow.completed = { requestVersion:1 }; }, { state:Labels.dispatch });
verify('已下发待移交', row => { row.businessFlow.completed = { requestVersion:1 }; row.businessFlow.dispatch = { version:1 }; }, { state:Labels.handover });
verify('已移交待接收', row => { row.businessFlow.completed = { requestVersion:1 }; row.businessFlow.dispatch = { version:1 }; row.handover = { status:'pending', manager:'李工' }; }, { state:Labels.receive });

assert.equal(firstPending.paymentStage, '首款');
assert.equal(tailPending.paymentStage, '尾款');
assert.doesNotMatch(firstPending.roleState + tailPending.roleState, /一次性付清/, '历史首尾款不得被压成一次性支付');

console.log('PASS 委托核心状态跨角色同词，首尾款阶段与角色说明保留');
