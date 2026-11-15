const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const packageJson = require('../package.json');
const lock = require('../package-lock.json');
const vendor = require('../miniprogram/pages/build/vendor/xlsx.mini.min');
const XLSX = require('xlsx');
const automator = require('miniprogram-automator');
const Jimp = require('jimp');

const SHEETJS_URL = 'https://cdn.sheetjs.com/xlsx-0.20.3/xlsx-0.20.3.tgz';
const SHEETJS_INTEGRITY = 'sha512-oLDq3jw7AcLqKWH2AhCpVTZl8mf6X2YReP+Neh0SJUzV/BdZYjth94tG5toiMB1PPrYtxOCfaoUCkvtuH+3AJA==';
const VENDOR_SHA256 = '0cb353f830d7288385492c83d277b058ddeac664ca51cf1393aa1fd3e2b70939';

function encode(image, mime) {
  return new Promise((resolve, reject) => image.getBuffer(mime, (error, buffer) => error ? reject(error) : resolve(buffer)));
}

test('package 与 lock 精确锁定已验证依赖来源和叶子覆盖', () => {
  assert.deepEqual(packageJson.devDependencies, {
    'miniprogram-automator': '0.13.0',
    xlsx: SHEETJS_URL
  });
  assert.deepEqual(packageJson.overrides, {
    'miniprogram-automator': { debug: '4.4.3' },
    'follow-redirects': '1.16.3',
    'jpeg-js': '0.4.4',
    minimist: '0.2.4'
  });
  assert.equal(lock.packages[''].devDependencies.xlsx, SHEETJS_URL);
  assert.equal(lock.packages['node_modules/xlsx'].version, '0.20.3');
  assert.equal(lock.packages['node_modules/xlsx'].resolved, SHEETJS_URL);
  assert.equal(lock.packages['node_modules/xlsx'].integrity, SHEETJS_INTEGRITY);

  for (const [name, version] of Object.entries({
    'miniprogram-automator': '0.13.0', debug: '4.4.3', 'follow-redirects': '1.16.3', 'jpeg-js': '0.4.4', minimist: '0.2.4', phin: '2.9.3'
  })) {
    assert.equal(lock.packages[`node_modules/${name}`].version, version, `${name} lock version`);
  }
});

test('automator 离线模块保留 connect 与 launch 契约', () => {
  assert.equal(typeof automator.connect, 'function');
  assert.equal(typeof automator.launch, 'function');
});

test('Jimp 兼容二维码 Buffer 路径及 PNG/JPEG 编解码', async () => {
  const source = await new Promise((resolve, reject) => new Jimp(2, 2, 0xff3366ff, (error, image) => error ? reject(error) : resolve(image)));
  for (const mime of [Jimp.MIME_PNG, Jimp.MIME_JPEG]) {
    const encoded = await encode(source, mime);
    const decoded = await Jimp.read(encoded);
    assert.equal(decoded.bitmap.width, 2);
    assert.equal(decoded.bitmap.height, 2);
    const reencoded = await encode(decoded, mime);
    const reread = await Jimp.read(reencoded);
    assert.equal(reread.bitmap.width, 2);
    assert.equal(reread.bitmap.height, 2);
  }
});

for (const [label, library] of [['Node XLSX', XLSX], ['mini vendor', vendor]]) {
  test(`${label} 保留 workbook array roundtrip`, () => {
    assert.equal(library.version, '0.20.3');
    const workbook = library.utils.book_new();
    library.utils.book_append_sheet(workbook, library.utils.aoa_to_sheet([
      ['节点名称', '开始日期', '结束日期'],
      ['主体封顶', '2026-11-01', '2026-11-02']
    ]), '进度计划');
    const bytes = library.write(workbook, { type: 'array', bookType: 'xlsx' });
    const reopened = library.read(bytes, { type: 'array', cellDates: true });
    assert.deepEqual(library.utils.sheet_to_json(reopened.Sheets['进度计划'], {
      header: 1, raw: true, defval: ''
    }), [
      ['节点名称', '开始日期', '结束日期'],
      ['主体封顶', '2026-11-01', '2026-11-02']
    ]);
  });
}

test('mini vendor 固定为官方 0.20.3 文件与许可证头', () => {
  const file = fs.readFileSync(path.join(root, 'miniprogram/pages/build/vendor/xlsx.mini.min.js'));
  assert.equal(crypto.createHash('sha256').update(file).digest('hex'), VENDOR_SHA256);
  assert.match(file.subarray(0, 100).toString(), /SheetJS/);
  assert.equal(vendor.version, '0.20.3');
  assert.equal(lock.packages['node_modules/xlsx'].license, 'Apache-2.0');
});
