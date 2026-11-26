// Runs against the production controllers and a disposable PostgreSQL database on the paired backend.
const assert = require('node:assert/strict');
const input = require('node:readline').createInterface({ input: process.stdin });
const replies = input[Symbol.asyncIterator]();
const f = JSON.parse(process.argv[2]);
global.wx = { getStorageSync: () => '', getSystemInfoSync: () => ({}) };
const http = require('../miniprogram/api/http');
const sale = require('../miniprogram/api/franchise/sales');
const pay = require('../miniprogram/pages/workflow/payment-plan/api');
const finance = require('../miniprogram/api/finance');
const engineering = require('../miniprogram/api/franchise/engineering').collections;
const handover = require('../miniprogram/pages/external-handover/api');
const model = require('../miniprogram/pages/external-handover/model');
let actor = 'sales';
async function rpc(method, path, body, headers = {}) {
  console.log(JSON.stringify({ actor, method, path, body, key: headers['Idempotency-Key'] }));
  const next = await replies.next();
  assert.equal(next.done, false, 'Paired backend ended early');
  const response = JSON.parse(next.value);
  if (response.code !== 0) throw Object.assign(Error(response.msg || String(response.code)), { code: response.code });
  return response.data;
}
http.get = path => rpc('GET', path);
http.post = (path, body, headers) => rpc('POST', path, body, headers);
const key = name => 'pair-installments-' + f.customer + '-' + name;
const header = name => ({ 'Idempotency-Key': key(name) });
function read(data, post) { return model.detail(data, f.commission, f.sales.orgId, post); }
async function scenario() {
  const before = read(await handover.get(f.commission), f.sales);
  assert.equal(before.submissionTerms.plan.source, 'DRAFT');
  const form = { ...model.form(before), confirmationDate: f.today, confirmationAssetId: f.confirmationFile,
    managerUserId: f.engineer.userId, note: '按本版客户成果交接资料', confirmed: true };
  await handover.submit(f.commission,model.payload('submit',form,before,[f.engineer],f.today),key('design-only'));
  actor = 'engineer';
  const unsigned = read(await handover.get(f.commission), f.engineer);
  assert.equal(unsigned.canReceive,false); assert.equal(unsigned.canReturn,true); assert.equal(unsigned.projectId,'');
  await assert.rejects(handover.receive(f.commission,{ revision: unsigned.revision,handoverVersion: unsigned.currentPacket.version,
    reason: '尚未签约', confirmed: true }, key('unsigned')), /已签施工合同/);
  await handover.return(f.commission,model.payload('return',{ reason: '请补齐已签施工合同及分期' },unsigned,[],f.today),key('return-design'));
  actor = 'finance';
  const deposit = await finance.registerReceipt({ customerId: f.customer, name: '签约前施工预收',
    amountCents: 10000, method: 'bank', receivedDate: f.today,
    transactionNo: key('prepaid-no'), voucherFileId: f.prepaymentProof }, key('prepayment'));
  assert.ok(model.id(deposit.receiptId));
  actor = 'sales';
  const created = await sale.contracts.create({ customerId: f.customer, totalCents: 100000, paymentStages: [
    { label: '签约款', amountCents: 40000, trigger: 'ON_SIGN' },
    { label: '工程验收款', amountCents: 60000, trigger: 'NODE_COMPLETED', nodeIndex: 2 }
  ] }, key('create'));
  const contract = model.id(created.contractId);
  const original = await pay.get(contract);
  assert.equal(original.contract.status, 'WAIT_SIGN');
  assert.equal((await sale.contracts.sign(contract, { signedDate: f.today, assetId: f.contractFile, revision: original.contract.revision }, key('sign'))).status, 'SIGNED');
  const first = original.stages[0], stage = first.stage;
  actor = 'finance';
  const allocation = '/franchise/sales/stages/' + first.stageId + '/allocate-receipt/' + deposit.receiptId;
  await http.post(allocation, {}, header('allocate'));
  await http.post(allocation, {}, header('allocate'));
  actor = 'sales';
  let context = read(await handover.get(f.commission), f.sales);
  assert.equal(context.submissionTerms.plan.version, 1);
  assert.deepEqual(context.submissionTerms.plan.rows.map(row => [row.paidText, row.remainingText]), [['100.00','300.00'],['0.00','600.00']]);
  form.note = '按正式施工合同逐期交接，首期已登记原预收款';
  const submit = model.payload('submit', form, context, [f.engineer], f.today);
  // A pre-signing client can still see the old design proposal. Never silently substitute it.
  const legacy = { ...submit }; delete legacy.checkedContract;
  await assert.rejects(handover.submit(f.commission, legacy, key('obsolete')), /施工合同|付款计划/);
  const sent = read(await handover.submit(f.commission, submit, key('handover')), f.sales);
  assert.equal(sent.currentPacket.plan.rows[0].paidText, '100.00');
  actor = 'engineer';
  context = read(await handover.get(f.commission), f.engineer);
  const received = read(await handover.receive(f.commission,
    model.payload('receive', { confirmed: true, reason: '核对本版分期及已收' }, context, [], f.today), key('receive')), f.engineer);
  const project = received.projectId;
  async function engineeringBalance(expected) {
    const values = (await engineering.page(project)).stages;
    assert.equal(values[0].expectedCents, 40000);
    assert.equal(values[0].confirmedCents, expected);
    assert.equal(values[0].remainingCents, 40000 - expected);
    assert.equal(values[1].nodeIndex, 2);
  }
  await engineeringBalance(10000);
  const payment = { stage, stageId: model.id(first.stageId), planVersion: 1, amount: '150.00', occurredAt: f.today, transactionNo: key('engineering-no'), fileAssetId: f.engineeringProof };
  actor = 'secondEngineer';
  await assert.rejects(engineering.register(project, payment, key('unauthorized')));
  actor = 'engineer';
  await assert.rejects(engineering.register(project, { ...payment, fileAssetId: f.prepaymentProof }, key('foreign-proof')));
  await assert.rejects(engineering.register(project, { ...payment, planVersion: 2 }, key('changed-installment')));
  await assert.rejects(engineering.register(project, { ...payment, amount: '400.00' }, key('exceed')));
  const pending = await engineering.register(project, payment, key('declare'));
  assert.equal(pending.stageStatus, 'PENDING_CONFIRMATION'); assert.equal(Number(pending.receiptId), 0);
  let declaration = model.id(pending.declarationId); assert.ok(declaration);
  assert.equal(model.id((await engineering.register(project, payment, key('declare'))).declarationId), declaration);
  assert.equal((await engineering.page(project)).declarations[0].status, 'PENDING');
  await assert.rejects(engineering.register(project, { ...payment, transactionNo: key('changed-no') }, key('declare')));
  await engineeringBalance(10000);
  actor = 'finance';
  await http.post('/franchise/sales/collection-declarations/' + declaration + '/return', { reason: '请补清晰到账凭据' }, header('return'));
  actor = 'engineer';
  const returned = (await engineering.page(project)).declarations.find(d => d.id === declaration);
  assert.equal(returned.status,'RETURNED'); assert.match(returned.returnReason,/清晰/);
  const corrected = { ...payment, fileAssetId: f.correctedProof };
  const next = await engineering.register(project,corrected,key('correct'));
  assert.ok(model.id(next.declarationId)); assert.notEqual(next.declarationId,declaration);
  declaration=model.id(next.declarationId);
  assert.equal((await engineering.register(project,corrected,key('correct'))).declarationId,declaration);
  const confirm = '/franchise/sales/collection-declarations/' + declaration + '/confirm';
  await assert.rejects(http.post(confirm, {}, header('no-finance')));
  actor = 'finance';
  const row = (await http.get('/franchise/sales/collection-declarations')).find(r => r.id === declaration);
  assert.equal(row.voucher_file_id, f.correctedProof); assert.equal(row.amount_cents, 15000);
  const receipt = model.id((await http.post(confirm, {}, header('confirm'))).receiptId);
  assert.ok(receipt && receipt !== model.id(deposit.receiptId));
  assert.equal(model.id((await http.post(confirm, {}, header('confirm-again'))).receiptId), receipt);
  actor = 'engineer'; await engineeringBalance(25000);
  const history = (await engineering.page(project)).declarations;
  assert.equal(history.length, 2); assert.equal(history[0].status, 'CONFIRMED'); assert.equal(history[0].receiptId, receipt);
  assert.equal(history[1].status,'SUPERSEDED');
  const reread = read(await handover.get(f.commission), f.engineer);
  assert.equal(reread.currentPacket.plan.rows[0].paidText,'100.00');
  assert.equal(reread.currentContractTerms.rows[0].paidText,'250.00');
  actor = 'sales';
  const progress = await sale.stages.summary(contract);
  assert.deepEqual(progress.map(r => [r.collectedCents,r.balanceCents]), [[25000,15000],[0,60000]]);
  console.log(JSON.stringify({ done: true, contract, project, receipt, declaration }));
}
scenario().catch(error => { console.error(error); process.exitCode = 1; })
  .finally(() => { input.close(); process.stdin.destroy(); });
