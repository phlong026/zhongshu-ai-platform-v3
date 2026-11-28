// Legacy admin fixture checks preserve historical behavior; they do not prove the current online workflow.
const assert = require('node:assert/strict');
const fs = require('node:fs');

const read = path => fs.readFileSync(path, 'utf8');
const clone = require('../miniprogram/utils/clone');
const source = { nested:{ value:1 }, rows:[{ id:'a' }], text:'原值', empty:null };
const copied = clone(source);
copied.nested.value = 2;
copied.rows[0].id = 'b';
assert.deepEqual(source, { nested:{ value:1 }, rows:[{ id:'a' }], text:'原值', empty:null });
assert.deepEqual(clone(source), source);
assert.equal(clone(undefined), undefined);

const isIsoDate = require('../miniprogram/utils/iso-date');
for (const value of ['2024-02-29', '2026-01-01', '9999-12-31']) assert.equal(isIsoDate(value), true, value);
for (const value of ['', null, undefined, '2023-02-29', '2026-02-30', '2026-13-01', '2026-1-01', '2026-01-1', '2026-01-01T00:00:00Z']) {
  assert.equal(isIsoDate(value), false, String(value));
}

global.wx = { getStorageSync() { return undefined; } };
const Business = require('../miniprogram/utils/business');
assert.equal(Business.validDate('2999-12-31'), true, '通用业务日期只校验严格格式，不擅自限制为今天以前');

const cloneConsumers = [
  'miniprogram/utils/finance-ledger.js',
  'miniprogram/utils/commission-history.js',
  'miniprogram/utils/commercial-approval.js',
  'miniprogram/utils/transfers-v2.js',
  'miniprogram/utils/cross-evidence.js',
  'miniprogram/utils/growth.js',
  'miniprogram/utils/project-settlement.js',
  'scripts/fixtures/legacy-admin/finance-save.js',
  'miniprogram/pages/admin/organization-actions-v2.js'
];
for (const path of cloneConsumers) {
  const code = read(path);
  assert(!/JSON\.parse\(JSON\.stringify/.test(code), path + ' 不得回流局部 JSON 深拷贝');
  assert(/require\(['"][^'"]*clone['"]\)/.test(code), path + ' 应复用 clone 单源');
}

const dateConsumers = [
  'miniprogram/utils/operations.js',
  'miniprogram/utils/engineering-records.js',
  'scripts/fixtures/legacy-admin/finance-actions.js',
  'miniprogram/utils/business.js',
  'scripts/fixtures/legacy-commission-transition.cjs'
];
for (const path of dateConsumers) {
  const code = read(path);
  assert(!code.includes('/^\\d{4}-\\d{2}-\\d{2}$/'), path + ' 不得回流局部严格日期正则');
  assert(/require\(['"][^'"]*iso-date['"]\)/.test(code), path + ' 应复用严格日期单源');
}
assert.equal(require('../miniprogram/utils/engineering-records').date, isIsoDate, '工程历史与财务共享的日期读取仍使用严格日期单源');
const salesFlow = read('scripts/fixtures/legacy-commission-transition.cjs');
assert(/isIsoDate\(v\)\s*&&\s*v\s*<=\s*date\(\)/.test(salesFlow), '客户确认日期仍须保留不能晚于今天的业务边界');
const financeActions = read('scripts/fixtures/legacy-admin/finance-actions.js');
assert(/isIsoDate\(f\.date\)\s*&&\s*f\.date\s*<=\s*require\(['"]\.\.\/\.\.\/\.\.\/miniprogram\/utils\/account['"]\)\.localDate\(\)/.test(financeActions), '历史财务夹具的实收实付日期仍须保留不能晚于今天的边界');

console.log('PASS JSON 深拷贝与严格日期复用单源，原业务日期范围保持不变');
