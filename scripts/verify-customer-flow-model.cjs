const test = require('node:test');
const assert = require('node:assert/strict');
const model = require('../miniprogram/pages/customer-flow/model');

test('金额用十进制字符串传输，草稿允许留空，非法金额不能被四舍五入成有效报价', () => {
  assert.equal(model.toCents('1234567890.01'), '123456789001');
  assert.equal(model.toCents('0.10'), '10');
  assert.equal(model.toCents(''), '');
  assert.equal(model.fromCents('900719925474099999'), '9007199254740999.99');
  for (const value of ['1.001', '-1', '1e5', '￥100', 'NaN']) assert.throws(() => model.toCents(value));
});

test('八步表单只序列化本步字段，报价和参考资料不会串到其他步骤', () => {
  const step = { code: 'INVITATION', fields: { contactMethod: '微信', invitationResult: 'PENDING', amountCents: '9999' }, attachments: [] };
  const form = model.form(step);
  form.appointmentDate = '2026-11-25'; form.appointmentTime = '10:30';
  form.fields.unexpected = 'hidden';
  const body = model.payload('INVITATION', form, 4);
  assert.equal(body.fields.appointmentAt, '2026-11-25T10:30');
  assert.equal(body.fields.invitationResult, 'PENDING');
  assert.equal(body.fields.amountCents, undefined);
  assert.equal(body.fields.unexpected, undefined);
  assert.equal(body.revision, 4);
  assert.equal(body.completed, undefined, '保存草稿不能冒充完成命令');
  assert.equal(body.fields.nextContactOn, undefined, '新记录不带升级前的“下次联系”');
  // 升级前保存的第 1 步“下次联系”不再是表单项，保存时原样带回，不影响客户的下次跟进日期
  const legacy = model.form({ code: 'INVITATION', nextFollowOn: '2026-12-03', fields: { contactMethod: '微信', nextContactOn: '2026-11-25' }, attachments: [] }, '2026-12-01');
  assert.equal(legacy.fields.nextContactOn, undefined, '旧日期不进入表单项');
  assert.equal(legacy.nextFollowOn, '2026-12-03');
  const kept = model.payload('INVITATION', legacy, 5).fields;
  assert.equal(kept.nextContactOn, '2026-11-25');
  assert.equal(model.payload('INTRODUCTION', { ...model.form({ code: 'INTRODUCTION', fields: { nextContactOn: '2026-11-25' } }) }, 5).fields.nextContactOn, undefined);
});

test('客户和资产ID保持字符串，拒绝不安全数字和任意URL附件', () => {
  const id = '900719925474099999';
  assert.equal(model.id(id), id);
  assert.equal(model.id(Number(id)), '');
  const f = model.form({ code: 'HOUSE_SELECTION', fields: {}, attachments: [{ assetId: id, title: '方案.pdf' }] });
  assert.equal(model.payload('HOUSE_SELECTION', f, 0).attachments[0].assetId, id);
  f.attachments.push({ url: 'https://outside.test/private.pdf', title: '伪引用' });
  assert.throws(() => model.payload('HOUSE_SELECTION', f, 0), /资料/);
});

test('流程响应必须属于当前客户，缺失步骤或重复步骤不能显示为正常流程', () => {
  const response = { customerId: '42', totalSteps: 8, revision: 0, completedCount: 0, progressPercent: 0,
    status: 'ACTIVE', canWrite: true, steps: model.STEPS.map((s, i) => ({ code: s.code, name: s.name, order: i + 1, status: 'TODO', fields: {}, attachments: [], source: i > 5 ? i === 6 ? 'CONTRACT' : 'ENGINEERING' : 'MANUAL' })) };
  assert.equal(model.workflow(response, '42').steps.length, 8);
  assert.throws(() => model.workflow(response, '43'), /客户/);
  assert.throws(() => model.workflow({ ...response, steps: response.steps.slice(1) }, '42'), /步骤/);
  assert.throws(() => model.workflow({ ...response, steps: Array(8).fill(response.steps[0]) }, '42'), /步骤/);
});

test('合同和开工保持事实展示，客户端不能序列化为手动完成记录', () => {
  for (const code of ['CONTRACT', 'START']) assert.throws(() => model.payload(code, model.form({ code }), 1), /事实/);
});

test('实际开工是业务日期，不把日期字段显示成虚构的北京时间08:00', () => {
  const facts = model.facts({ code: 'START', fields: { projectId: '91', actualStartAt: '2026-11-25' } });
  assert.equal(facts.find(f => f.label === '实际开工').value, '2026-11-25');
});

const { customerRow, tabFilter } = require('../miniprogram/utils/platform-crm');
test('客户列表的预约跟进与八步邀约日期分开，筛选和卡片使用同一日期', () => {
  const today='2026-11-26';
  const common={id:'1',name:'测试客户',lastFollowAt:'2026-11-25',workflow:{nextContactOn:'2026-11-25',currentStepName:'邀约确认'}};
  const booked=customerRow({...common,nextVisitDate:[2026,12,1]},{today});
  assert.equal(booked.appointment,'2026-12-01');
  assert.doesNotMatch(booked.cue,/逾期/);
  assert.match(booked.cue,/跟进/);
  assert.equal(booked.workflow.nextContactOn,'2026-11-25');
  assert.deepEqual(tabFilter([booked],'appointments',{today}),[booked]);
  const cleared=customerRow({...common,nextVisitDate:null},{today});
  assert.equal(cleared.appointment,'');
  assert.doesNotMatch(cleared.cue,/逾期|回访/);
  assert.deepEqual(tabFilter([cleared],'appointments',{today}),[]);
  const due=customerRow({...common,workflow:{nextContactOn:'2026-12-02'},nextVisitDate:[2026,11,25]},{today});
  assert.equal(due.cue,'逾期 1 天');
  assert.deepEqual(tabFilter([due],'due',{today}),[due]);
});
