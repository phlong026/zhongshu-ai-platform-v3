const test = require('node:test'), assert = require('node:assert/strict');
const model = require('../miniprogram/pages/workflow/payment-plan/model');
function ratioForm(total, ratios) { return { total, mode: 'RATIO', reason: '双方已确认', rows: ratios.map((ratio, i) => ({ ...model.emptyRow('new-' + i), label: '第' + (i + 1) + '期', ratio })) }; }

test('比例统一使用基点，按整数分计算，只有最后一期吸收尾差', () => {
  const form = ratioForm('100.01', ['33.33', '33.33', '33.34']);
  assert.deepEqual(model.preview(form).rows.map(r => r.amountCents), [3333, 3333, 3335]);
  assert.equal(model.preview(form).valid, true);
  const body = model.payload(form, { customerId: '900719925474099999' });
  assert.deepEqual(body.paymentStages.map(s => s.ratioBps), [3333, 3333, 3334]);
  assert.ok(body.paymentStages.every(s => s.amountCents === undefined && s.stageId === null));
});

test('合同金额上限附近的比例计算仍精确，不依赖乘法浮点取整或真机BigInt', () => {
  for (const total of [999999999999, 1000000000000, 900719925499]) for (const ratio of [1, 3333, 4999, 9999]) {
    assert.equal(model.ratioAmount(total, ratio), Number(BigInt(total) * BigInt(ratio) / 10000n));
  }
});

test('比例必须100%，每期至少一分，金额计划和比例计划不能混用', () => {
  assert.equal(model.preview(ratioForm('100.00', ['50', '49.99'])).valid, false);
  assert.equal(model.preview(ratioForm('0.01', ['50', '50'])).valid, false);
  const form = ratioForm('100.00', ['30', '70']); form.mode = 'AMOUNT';
  form.rows[0].amount = '30.00'; form.rows[1].amount = '70.00';
  const body = model.payload(form, { customerId: '42' });
  assert.deepEqual(body.paymentStages.map(s => s.amountCents), [3000, 7000]);
  assert.ok(body.paymentStages.every(s => s.ratioBps === undefined));
  for (const value of ['1.001', '-1', '1e4', '10000000000.01']) { form.total = value; assert.equal(model.preview(form).valid, false); }
});

test('调整保留既有stageId与revision，已收款限额和必填原因不能绕过', () => {
  const form = ratioForm('100', ['100']); form.rows[0].stageId = '900719925474099999'; form.rows[0].collectedCents = 5000;
  const body = model.payload(form, { contractId: '91', revision: 8 });
  assert.equal(body.stages[0].stageId, '900719925474099999'); assert.equal(body.revision, 8); assert.equal(body.reason, '双方已确认');
  assert.equal(model.preview(form).rows[0].removable, false);
  form.total = '40'; assert.throws(() => model.payload(form, { contractId: '91', revision: 8 }), /净收款/);
  form.total = '100'; form.reason = ' '; assert.throws(() => model.payload(form, { contractId: '91', revision: 8 }), /调整原因/);
});

test('工程节点条件与指定日期必须有效，其他条件不会夹带工程节点编号', () => {
  const form = ratioForm('100', ['100']); form.rows[0].trigger = 'NODE_COMPLETED';
  assert.equal(model.preview(form).valid, false); form.rows[0].nodeIndex = '3';
  assert.equal(model.payload(form, { customerId: '42' }).paymentStages[0].nodeIndex, 3);
  form.rows[0].trigger = 'DATE'; form.rows[0].dueDate = '2026-02-30'; assert.equal(model.preview(form).valid, false);
  form.rows[0].dueDate = '2026-12-01'; assert.equal(model.payload(form, { customerId: '42' }).paymentStages[0].nodeIndex, null);
});

test('合同详情核对公司与合同，长编号原样保留，服务端不授权时只读', () => {
  const result = { contract: { id: '91', companyId: '9', customerId: '42', totalCents: 10000, revision: 2, status: 'SIGNED', paymentPlanVersion: 1 },
    canEdit: false, stages: [{ stageId: '900719925474099999', label: '开工前款', amountCents: 10000, collectedCents: 100, ratioBps: 10000, trigger: 'BEFORE_START', sequence: 1 }] };
  const detail = model.detail(result, '91', '9');
  assert.equal(detail.canEdit, false); assert.equal(detail.form.rows[0].stageId, '900719925474099999'); assert.equal(detail.form.mode, 'RATIO');
  assert.throws(() => model.detail(result, '92', '9'), /不匹配/); assert.throws(() => model.detail(result, '91', '10'), /不匹配/);
});
