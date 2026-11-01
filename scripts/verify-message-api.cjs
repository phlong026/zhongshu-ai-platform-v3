/**
 * 基-2 员工消息中心接入 verify：断言 utils/platform-messages 纯函数契约（零 wx 依赖，直接 require）。
 * 对应后端契约：monorepo docs/backend-parity/11-阶段0-基2员工消息中心-需求与验收.md。
 * 用法：node scripts/verify-message-api.cjs
 */
const assert = require('node:assert/strict');
const M = require('../miniprogram/utils/platform-messages');

// ===== formatTime：ISO 含时区 → 北京时间 'YYYY-MM-DD HH:mm' =====
assert.equal(M.formatTime('2026-11-01T09:20:00+08:00'), '2026-11-01 09:20', '+08:00 偏移归一');
assert.equal(M.formatTime('2026-11-01T01:20:00Z'), '2026-11-01 09:20', 'UTC 归一到北京');
assert.equal(M.formatTime(''), '', '空串安全');
assert.equal(M.formatTime('not-a-date'), '', '非法日期安全');
assert.equal(M.formatTime(null), '', 'null 安全');

// ===== toLocalMessage：MessageView → 蓝本形态 =====
const row = {
  id: '1234567890123456789', type: 'BUSINESS', route: 'customer', objectId: 987,
  title: '客户跟进提醒', description: '林女士的回访即将到期', read: false,
  createTime: '2026-11-01T09:20:00+08:00'
};
const local = M.toLocalMessage(row);
assert.deepEqual(local, {
  id: '1234567890123456789', type: '业务', title: '客户跟进提醒', description: '林女士的回访即将到期',
  date: '2026-11-01 09:20', read: false, route: 'customer', objectId: 987
}, '字段映射与中文 type');
assert.equal(M.toLocalMessage({ ...row, type: 'LEARNING' }).type, '学习');
assert.equal(M.toLocalMessage({ ...row, type: 'SYSTEM' }).type, '系统');
assert.equal(M.toLocalMessage({ ...row, type: 'UNKNOWN' }).type, '系统', '未知 type 兜底系统');
assert.equal(M.toLocalMessage({ ...row, objectId: null }).objectId, '', 'objectId 空→空串');
assert.equal(M.toLocalMessage({ ...row, read: 1 }).read, true, 'read 真值归一');
for (const bad of [null, undefined, {}, { id: 'msg-x' }, { id: 12.5 }, { id: '' }]) {
  assert.equal(M.toLocalMessage(bad), null, '结构非法拒绝：' + JSON.stringify(bad));
}
// id 数字型输入（后端偶尔落安全整数区间）同样归一为字符串
assert.equal(M.toLocalMessage({ ...row, id: 987654 }).id, '987654');

// ===== isApiMessage：纯数字雪花串 vs 本地/派生 id =====
assert.equal(M.isApiMessage(local), true);
assert.equal(M.isApiMessage({ id: 987654 }), true);
for (const id of ['msg-follow', 'design-20261018-1', 'perf-2026-09', '']) {
  assert.equal(M.isApiMessage({ id }), false, '本地/派生 id 非API：' + id);
}
assert.equal(M.isApiMessage(null), false);

// ===== mergeSources：三源合入、去重、date 倒序 =====
const storedRow = { id: 'msg-follow', type: '业务', title: '本地', date: '2026-10-18 09:20', read: false };
const derivedRow = { id: 'perf-2026-09', type: '业务', title: '派生', date: '2026-10-18 08:00', read: false };
const apiNew = { ...local }; // 2026-11-01 09:20 最新
const apiOld = M.toLocalMessage({ ...row, id: '100', title: '更早API', createTime: '2026-10-17T10:00:00+08:00' });
const merged = M.mergeSources([storedRow], [derivedRow], [apiNew, apiOld]);
assert.deepEqual(merged.map(m => m.id), [apiNew.id, 'msg-follow', 'perf-2026-09', '100'], '按 date 倒序合入');
assert.equal(merged.length, 4, '四源各保留一条');
assert.deepEqual(
  M.mergeSources([storedRow, { ...storedRow }], [], []).map(m => m.title),
  ['本地'], '同 id 去重先见优先'
);
assert.deepEqual(M.mergeSources(null, null, null), [], '全空安全');
assert.deepEqual(M.mergeSources([null, storedRow], [], [{ id: '' }]).map(m => m.id), ['msg-follow'], '空元素过滤');

// ===== totalUnread：本地 + API 合计 =====
assert.equal(M.totalUnread(2, 3), 5);
assert.equal(M.totalUnread(0, 0), 0);
assert.equal(M.totalUnread(2, null), 2, 'apiCount null 按 0');
assert.equal(M.totalUnread(null, 3), 3, 'localUnread null 按 0');
assert.equal(M.totalUnread(-1, 3), 3, '负数按 0');
assert.equal(M.totalUnread(NaN, 3), 3, 'NaN 按 0');

console.log('verify-message-api ✔  全部断言通过');
