const assert = require('node:assert/strict');
const Order = require('../miniprogram/utils/task-order');

const old = { id: 'old', triggeredAt: '2026-10-30T08:00:00+08:00', urgency: 0 };
const newer = { id: 'new', triggeredAt: '2026-10-30T01:00:00Z', urgency: 5 };
const tied = { id: 'tie', triggeredAt: Date.parse(newer.triggeredAt) };
const rows = [{ id: 'missing' }, old, newer, tied, { id: 'invalid', triggeredAt: 'bad-date' }];
assert.deepEqual(Order.newest(rows).map(row => row.id), ['new', 'tie', 'old', 'missing', 'invalid']);
assert.equal(rows[0].id, 'missing', '排序不得改写原始数组');
assert.deepEqual(Order.newest(rows), Order.newest(rows), '刷新不得生成新的触发时间');
assert.equal(Order.latest(old.triggeredAt, newer.triggeredAt, 'bad-date'), newer.triggeredAt);
assert.equal(Order.time('2026-10-30'), Date.parse('2026-10-30T00:00:00+08:00'), '历史日期使用业务时区');
assert.equal(Order.time(''), 0);
console.log('PASS 待办按触发时间倒序、同时间稳定、缺时间置底且不改写原单');
