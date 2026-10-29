const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const utils = path.join(root, 'miniprogram', 'utils');
const keys = require(path.join(utils, 'storage-keys'));
const expected = {
  account: 'zhongshu-account-v1',
  business: 'zhongshu-business-v3',
  management: 'zhongshu-management-v1',
  engineering: 'zhongshu-engineering-v1',
  growth: 'zhongshu-growth-v1',
  studio: 'zhongshu-studio',
  creationDraft: 'zhongshu-creation-draft',
  videoDraft: 'zhongshu-video-draft',
  financeView: 'zhongshu-finance-view-v1',
  ownerGoals: 'zhongshu-owner-goals-v1',
  salesDemo: 'zhongshu-sales-demo-v1',
  regionMigration: 'zhongshu-region-hubei-v1',
  collaborationClient: 'zhongshu-collaboration-client-v1',
  localRecords: 'zhongshu-local-records',
  savedDesign: 'zhongshu-saved-design',
  managementSimulationClient: 'zhongshu-management-simulation-client-v1',
  engineeringDraft: 'zhongshu-engineering-new-draft',
  engineeringRecentPrefix: 'zhongshu-engineering-recent:',
  materialProjectPrefix: 'zhongshu-material-project:',
  designFavoritesPrefix: 'zhongshu-design-favorites:',
  designExplorePrefix: 'zhongshu-design-explore:',
  performanceTasks: 'zhongshu-performance-tasks-v1',
};
assert.deepEqual(keys, expected, '存储键词典必须保持既有持久化字符串');

const deferred = new Set();
const files = fs.readdirSync(utils)
  .filter(file => file.endsWith('.js') && file !== 'storage-keys.js' && !deferred.has(file) && !file.startsWith('commission'));
const duplicates = [];
for (const file of files) {
  const source = fs.readFileSync(path.join(utils, file), 'utf8');
  for (const value of Object.values(expected)) {
    if (source.includes(`'${value}'`) || source.includes(`"${value}"`)) duplicates.push(`${file}: ${value}`);
  }
}
const pages = path.join(root, 'miniprogram', 'pages');
function pageScripts(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const target = path.join(dir, entry.name);
    return entry.isDirectory() ? pageScripts(target) : entry.name.endsWith('.js') ? [target] : [];
  });
}
for (const file of pageScripts(pages)) {
  const source = fs.readFileSync(file, 'utf8');
  for (const value of Object.values(expected)) {
    if (source.includes(`'${value}'`) || source.includes(`"${value}"`)) duplicates.push(`${path.relative(root, file)}: ${value}`);
  }
}
assert.deepEqual(duplicates, [], '已登记的存储键不应在 utils 或 pages 重复声明');

const money = require(path.join(utils, 'money'));
assert.equal(money.parseCents('12.34'), 1234);
assert.equal(money.parseCents('0', { zero: true }), 0);
assert.throws(() => money.parseCents('0'), /请填写有效金额/);
assert.throws(() => money.parseCents('1.234'), /请填写有效金额/);
assert.throws(() => money.parseCents('100000000.01'), /请填写有效金额/);

const collection = fs.readFileSync(path.join(utils, 'collection-commission.js'), 'utf8');
const operations = fs.readFileSync(path.join(utils, 'operations.js'), 'utf8');
assert(collection.includes("require('./money').parseCents"), '提成金额应复用统一分解析器');
assert(operations.includes("require('./money').parseCents"), '经营金额应复用统一分解析器');
assert(!/function cents\([\s\S]{0,300}Math\.round\(Number\(/.test(collection), '提成模块不应保留独立分解析器');
assert(!/function cents\([\s\S]{0,300}Math\.round\(Number\(/.test(operations), '经营模块不应保留独立分解析器');
const operationMoney = require(path.join(utils, 'operations'));
assert.equal(operationMoney.cents('12.34'), 1234);
assert.equal(operationMoney.cents('0', true), 0);
assert(Object.is(operationMoney.cents('-0', true), -0), '历史期初余额的 -0 输入语义不应被改写');
assert.throws(() => operationMoney.cents(' 1'), /金额须有效/);
assert.throws(() => operationMoney.cents('1.234'), /金额须有效/);

console.log(`PASS 存储键 ${Object.keys(expected).length} 项单源，金额解析语义保持`);

for (const file of ['miniprogram/utils/sales-flow.js', 'miniprogram/pages/admin/model.js', 'miniprogram/pages/build/model.js']) {
  assert(!fs.readFileSync(path.join(root, file), 'utf8').includes('return Math.round(Number(value)*100);'), file + ' 复用金额解析');
}
