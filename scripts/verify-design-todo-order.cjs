const assert = require('node:assert/strict');
const W = require('../miniprogram/utils/design-admin-workbench');
const SF = require('../miniprogram/utils/sales-flow');
const actor = { active: true, role: 'hq-design-admin', orgType: 'headquarters', companyId: 'headquarters', staffId: 'admin' };
const customer = (id, paidAt, feedbackAt) => ({
  id, name: id, companyId: 'store', designOrganizationId: 'headquarters',
  request: { version: 1 },
  businessFlow: { type: 'sales-offline-v1', paymentMode: 'area-full-demo-v1',
    submittedAt: '2026-01-01T00:00:00Z',
    paymentOrder: { paidAt }, feedbacks: feedbackAt ? [{ status: 'pending', at: feedbackAt }] : [] }
});
const old = customer('old-feedback', '2026-10-29T01:00:00Z', '2026-10-30T01:00:00Z');
const newer = customer('new-payment-on-old-customer', '2026-10-30T02:00:00Z');
const rows = [old, ...Array.from({ length: 5 }, (_, i) => customer('old-' + i, '2026-10-29T01:00:00Z')), newer];
const build = () => W.build(rows, actor, {
  feeApproved: () => true, companyNameFor: id => id,
  nextFor: c => ({ state: c.businessFlow.feedbacks.length ? '修改中' : '设计中', view: 'complete', actionable: true })
});
assert.equal(build().todos[0].id, newer.id, '最新付款触发设计待办应在旧修改待办及首页截断之前');
assert.equal(build().taskCount, rows.length);
assert.deepEqual(build().todos, build().todos, '刷新排序稳定');
const handover = customer('handover', '2026-10-28T00:00:00Z');
handover.handover = { status: 'pending', date: '2026-10-30' };
handover.businessFlow.events = [{ action: 'packet-submit', at: '2026-10-30T03:00:00Z' }];
assert.equal(SF.triggeredAt(handover, 'receive'), '2026-10-30T03:00:00Z');
handover.handover.status = 'returned';
handover.returns = [{ view: 'receive', status: 'pending', at: '2026-10-30T04:00:00Z' }];
assert.equal(SF.triggeredAt(handover, 'handover'), '2026-10-30T04:00:00Z');
console.log('PASS 设计付款、修改、移交与退回按实际触发时间排序并在首页截断前置顶');
