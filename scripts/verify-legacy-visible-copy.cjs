const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const storage = new Map([['legacy-cache', { untouched: true }]]);
global.wx = {
  getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
};

const beforeStorage = JSON.parse(JSON.stringify([...storage]));
const Files = require('../miniprogram/utils/sample-files');
const Account = require('../miniprogram/utils/account');

const legacyFiles = {
  '示例附件.pdf': '参考附件.pdf',
  '图稿示例凭据.pdf': '图稿参考凭据.pdf',
  '工程记录示例凭据.pdf': '工程记录参考凭据.pdf',
  '闭环流程示例凭据.pdf': '闭环流程参考凭据.pdf',
  '模拟供应方报价.pdf': '参考供应方报价.pdf',
};

for (const [legacyName, displayName] of Object.entries(legacyFiles)) {
  const cached = { name: legacyName, sample: true, path: '/cached/' + legacyName };
  const original = JSON.parse(JSON.stringify(cached));
  const displayed = Files.displayFile(cached);
  assert.equal(displayed.name, displayName);
  assert.equal(displayed.sample, true);
  assert.equal(displayed.path, cached.path);
  assert.deepEqual(cached, original, 'display mapping must not mutate cached files');
}

assert.equal(Files.displayFile({ name: '示例附件.pdf', sample: false }).name, '示例附件.pdf');
assert.equal(Files.displayFile({ name: '客户自定义示例附件.pdf', sample: true }).name, '客户自定义示例附件.pdf');
assert.deepEqual(Files.displayNames({ file: { name: '示例附件.pdf', sample: true } }), { file: '参考附件.pdf' });
assert.equal(Files.displayText('需求已提交（本地演示）'), '需求已提交');
assert.equal(Files.displayText('预置本地模拟跟进'), '预置客户跟进记录');
assert.equal(Files.displayText('本地演示已记录'), '发送记录已保存');
assert.equal(Files.displayText('客户自定义模拟说明'), '客户自定义模拟说明');
assert.equal(Files.displayText('toString'), 'toString');
assert.equal(Files.displayFile({ name: 'constructor', sample: true }).name, 'constructor');
const legacySections = [{ title: '附件', items: [
  { type: 'file', name: '示例附件.pdf', file: { name: '示例附件.pdf', sample: true } },
  { type: 'file', name: 'constructor', file: { name: 'constructor', sample: true } },
] }];
const originalSections = JSON.parse(JSON.stringify(legacySections));
const displayedSections = Files.displaySections(legacySections);
assert.equal(displayedSections[0].items[0].name, '参考附件.pdf');
assert.equal(displayedSections[0].items[0].file.name, '参考附件.pdf');
assert.equal(displayedSections[0].items[1].name, 'constructor');
assert.deepEqual(legacySections, originalSections, 'displaySections must not mutate cached view models');

const cachedOrder = {
  id: 'legacy-order',
  events: [{ text: '需求已提交（本地演示）', at: '2026-10-18 10:00' }],
  plan: { file: { name: '图稿示例凭据.pdf', sample: true } },
  delivery: { files: [{ name: '示例附件.pdf', sample: true }], at: '2026-10-18 11:00' },
  cancel: { history: [{ file: { name: '闭环流程示例凭据.pdf', sample: true } }] },
};
const originalOrder = JSON.parse(JSON.stringify(cachedOrder));
const displayedOrder = Account.displayOrder(cachedOrder);
assert.equal(displayedOrder.events[0].text, '需求已提交');
assert.equal(displayedOrder.plan.file.name, '图稿参考凭据.pdf');
assert.equal(displayedOrder.delivery.files[0].name, '参考附件.pdf');
assert.equal(displayedOrder.cancel.history[0].file.name, '闭环流程参考凭据.pdf');
assert.deepEqual(cachedOrder, originalOrder, 'displayOrder must not mutate the cached order');
assert.deepEqual([...storage], beforeStorage, 'rendering legacy values must not write storage');

const integrations = {
  'miniprogram/pages/build/record/index.js': 'displayFile',
  'miniprogram/pages/build/index.js': 'displayFile',
  'miniprogram/pages/workflow/index.js': 'displayFile',
  'miniprogram/pages/admin/index.js': 'displayFile',
  'miniprogram/pages/admin/operations/index.js': 'displayNames',
  'miniprogram/pages/account/index.js': 'displayFile',
};
for (const [file, marker] of Object.entries(integrations)) {
  assert.ok(fs.readFileSync(path.join(root, file), 'utf8').includes(marker), `${file} must apply ${marker} at its display boundary`);
}

console.log(`legacy visible copy: ${Object.keys(legacyFiles).length} file labels and 2 event labels verified`);
