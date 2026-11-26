// The PostgreSQL/MockMvc driver supplies real fixtures and answers JSONL requests.
// Only HTTP transport is replaced; request validation and response models are production code.
const assert = require('node:assert/strict');
const { createInterface } = require('node:readline');
const fixture = JSON.parse(process.argv[2] || '{}');
const input = createInterface({ input: process.stdin });
const replies = input[Symbol.asyncIterator]();
let actor = 'sales';
global.wx = { getStorageSync: () => '', getSystemInfoSync: () => ({}) };

const http = require('../miniprogram/api/http');
async function request(method, path, body, headers = {}) {
  process.stdout.write(JSON.stringify({ actor, method, path, ...(body === undefined ? {} : { body }),
    ...(headers['Idempotency-Key'] ? { key: headers['Idempotency-Key'] } : {}) }) + '\n');
  const response = await replies.next();
  assert.equal(response.done, false, 'Backend closed the bridge before replying');
  const result = JSON.parse(response.value);
  assert.equal(typeof result.code, 'number', 'Backend must return a CommonResult code');
  if (result.code !== 0) throw Object.assign(new Error(result.msg || 'Backend rejected the request'), { code: result.code });
  return result.data;
}
http.get = path => request('GET', path);
http.post = (path, body, headers) => request('POST', path, body, headers);

const entrust = require('../miniprogram/pages/external-entrust/api');
const Entrust = require('../miniprogram/pages/external-entrust/model');
const handover = require('../miniprogram/pages/external-handover/api');
const Handover = require('../miniprogram/pages/external-handover/model');

async function verify() {
  const customerId = Entrust.id(fixture.customerId), companyId = Entrust.id(fixture.companyId);
  assert.ok(customerId && companyId, 'Fixture requires exact customer/company IDs');
  const sales = fixture.sales, engineer = fixture.engineer;
  for (const [post, role] of [[sales, 'sales'], [engineer, 'engineer']]) {
    assert.ok(Entrust.id(post?.userId) && Entrust.id(post?.postId));
    assert.equal(Entrust.id(post.orgId), companyId);
    assert.equal(post.orgType, 'store'); assert.equal(post.roleCode, role);
  }
  assert.notEqual(Entrust.id(sales.userId), Entrust.id(engineer.userId));
  const expectedFiles = Object.fromEntries(Handover.slots.map(slot => {
    const assetId = Entrust.id(fixture[slot.key]); assert.ok(assetId, 'Missing ' + slot.key);
    return [slot.slot, assetId];
  }));
  assert.equal(new Set(Object.values(expectedFiles)).size, 4, 'Fixture needs four distinct accepted assets');
  const key = action => 'paired-external-' + action + '-' + customerId;
  const readEntrust = value => Entrust.detail(value, customerId, companyId, sales);

  let context = readEntrust(await entrust.get(customerId));
  assert.equal(context.status, 'NOT_REGISTERED'); assert.equal(context.commissionId, '');
  assert.equal(context.canRegister, true); assert.equal(context.requests.length, 0);
  assert.equal(context.currentPlan.source, 'CONTRACT'); assert.equal(context.currentPlan.rows.length, 2);
  const formalPlan = context.currentPlan;
  const requestFields = { entrustedOn: fixture.today,
    providerName: '配对验收外部设计方', providerReference: 'paired-' + customerId,
    text: '按客户已确认的测量资料完成外部设计', scope: '设计成果、施工图及专业审核依据', area: '260', floors: '2' };
  if (fixture.measurementEventId) {
    context = readEntrust(await require('./paired-measurement-request.cjs')(fixture, requestFields, entrust));
  } else {
    const registerBody = Entrust.payload('register', { ...Entrust.requestForm(context), ...requestFields }, context, fixture.today);
    context = readEntrust(await entrust.register(customerId, registerBody, key('register')));
  }
  const commissionId = context.commissionId;
  assert.ok(commissionId); assert.equal(context.status, 'EXTERNAL_PENDING');
  assert.equal(context.requests.length, 1); assert.equal(context.request.plan.source, 'CONTRACT');
  assert.equal(context.request.plan.version, formalPlan.version); assert.deepEqual(context.request.plan.rows, formalPlan.rows);

  const resultBody = Entrust.payload('results', { ...Entrust.resultForm(context), deliveredOn: fixture.today,
    drawingVersion: '外部正式图纸 V1', note: '已核对外部成果、施工图及专业审核依据',
    resultAssetId: expectedFiles.RESULT, drawingAssetId: expectedFiles.DRAWING, reviewAssetId: expectedFiles.REVIEW }, context, fixture.today);
  context = readEntrust(await entrust.results(customerId, resultBody, key('results')));
  assert.equal(context.commissionId, commissionId); assert.equal(context.status, 'EXTERNAL_READY'); assert.equal(context.canHandover, true);
  assert.equal(context.results.length, 1); assert.equal(context.result.data.requestVersion, context.request.version);

  const readHandover = value => Handover.detail(value, commissionId, companyId, actor === 'sales' ? sales : engineer);
  let transfer = readHandover(await handover.get(commissionId));
  assert.equal(transfer.customerId, customerId); assert.equal(transfer.canSubmit, true); assert.equal(transfer.projectId, '');
  const source = Handover.source(await handover.source(customerId), transfer, companyId);
  assert.equal(source.requestVersion, context.request.version); assert.equal(source.resultVersion, context.result.version);
  assert.equal(source.plan.source, 'CONTRACT'); assert.equal(source.plan.rows.length, 2); assert.equal(source.plan.version, formalPlan.version);
  for (const file of source.files) assert.equal(file.assetId, expectedFiles[file.slot]);
  const receivers = Handover.receivers([{ ...engineer, name: '配对工程接收人', active: true }], sales);
  assert.equal(receivers.length, 1);
  const submitBody = Handover.payload('submit', { ...Handover.form(transfer), confirmationDate: fixture.today,
    confirmationAssetId: expectedFiles.CONFIRMATION, managerUserId: Entrust.id(engineer.userId),
    note: '客户已确认本版成果、施工图及正式合同付款约定，提交工程接收', confirmed: true }, transfer, receivers, fixture.today);
  transfer = readHandover(await handover.submit(commissionId, submitBody, key('submit')));
  assert.equal(transfer.status, 'HANDOVER_SUBMITTED'); assert.equal(transfer.currentPacket.status, 'PENDING');
  assert.equal(transfer.projectId, '');
  const handoverVersion = transfer.currentPacket.version;

  actor = 'engineer';
  transfer = readHandover(await handover.get(commissionId));
  assert.equal(transfer.canReceive, true); assert.equal(transfer.currentPacket.version, handoverVersion);
  const receiveBody = Object.freeze(Handover.payload('receive', { ...Handover.form(transfer),
    reason: '工程已核对本版四份资料和正式付款约定', confirmed: true }, transfer, [], fixture.today));
  const receiveKey = key('receive');
  const received = readHandover(await handover.receive(commissionId, receiveBody, receiveKey));
  const projectId = received.projectId;
  assert.ok(projectId, 'Receive must return the actual engineering project');
  function accepted(value) {
    assert.equal(value.projectId, projectId); assert.equal(value.status, 'ACCEPTED'); assert.equal(value.canReceive, false);
    assert.equal(value.packets.length, 1); assert.equal(value.currentPacket.version, handoverVersion);
    assert.equal(value.currentPacket.status, 'ACCEPTED'); assert.equal(value.currentPacket.resultVersion, source.resultVersion);
    assert.equal(value.currentPacket.managerUserId, Entrust.id(engineer.userId));
    assert.deepEqual(Object.fromEntries(value.currentPacket.files.map(file => [file.slot, file.assetId])), expectedFiles);
    assert.equal(value.currentPacket.paymentPlan.source, 'CONTRACT'); assert.equal(value.currentPacket.paymentPlan.stages.length, 2);
    assert.equal(Entrust.id(value.currentPacket.paymentPlan.contractId), formalPlan.contractId);
    assert.deepEqual(value.currentPacket.plan, source.plan);
  }
  accepted(received);
  // Deliberately reuse the original revision/body/key after acceptance, as an uncertain-result retry would.
  accepted(readHandover(await handover.receive(commissionId, receiveBody, receiveKey)));
  accepted(readHandover(await handover.get(commissionId)));
  process.stdout.write(JSON.stringify({ done: true, commissionId, projectId, measurementEventId: context.request.data.measurementRef?.eventId }) + '\n');
}

verify().catch(error => { console.error(error); process.exitCode = 1; })
  .finally(() => { input.close(); process.stdin.destroy(); });
