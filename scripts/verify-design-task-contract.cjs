const assert = require('node:assert/strict');
const { bootstrapStorage, createRuntime } = require('../server/design-collaboration/domain-runtime.cjs');

const { storage, customerId } = bootstrapStorage();
const runtime = createRuntime(storage);
runtime.login('hq-finance', 'finance');
const M = runtime.requireMini('pages/admin/model.js');
const A = runtime.requireMini('utils/account.js');
for (const stage of ['first', 'tail']) {
  const row = M.load().receipts.find(receipt => receipt.source?.customerId === customerId && receipt.source.stage === stage && receipt.status === '待登记');
  M.mutate(state => M.apply(state, 'incoming-register', row.id, {
    amount: String(row.amount / 100), date: A.localDate(), transaction: 'TASK-CONTRACT-' + stage,
    file: { name: 'fixture.pdf', sample: true }, checked: true,
    expectedSource: JSON.stringify(row.source), expectedAmount: String(row.amount)
  }));
}
runtime.login('hq-design-admin');
const task = runtime.requireMini('utils/sales-flow.js').tasks().find(row => row.customerId === customerId);
assert(task?.actionable, '足额到账后保留下发待办');
assert.equal(task.command, 'design-dispatch', '服务端依据稳定命令识别待办，展示话术可独立调整');
assert.equal(task.action, '下发设计成果');
console.log('PASS 下发待办稳定命令与展示话术分离');
