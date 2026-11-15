const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { spawnSync } = require('node:child_process');
const XLSX = require('xlsx');
const scheduleImport = require('../miniprogram/pages/build/schedule-import');
const { importNodes, MAX, MAX_FILE_BYTES, MAX_EXPANDED_BYTES, MAX_ZIP_ENTRIES } = scheduleImport;

const root = path.resolve(__dirname, '..');

function workbookBuffer(rows) {
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet(rows), '进度计划');
  return XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' });
}

function exactArrayBuffer(buffer) {
  return buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength);
}

function endOfCentralDirectory(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  for (let offset = bytes.byteLength - 22; offset >= Math.max(0, bytes.byteLength - 22 - 65535); offset -= 1) {
    if (view.getUint32(offset, true) === 0x06054b50) return offset;
  }
  throw new Error('test fixture has no ZIP end record');
}

test('合法三列表同时支持日期单元格、文本日期和 ArrayBuffer', () => {
  const result = importNodes(exactArrayBuffer(workbookBuffer([
    ['节点名称', '开始日期', '结束日期'],
    ['开工准备', new Date(2026, 10, 1, 12), new Date(2026, 10, 2, 12)],
    ['主体封顶', '2026年11月3日', '2026/11/05']
  ])));

  assert.equal(result.notice, '');
  assert.deepEqual(result.nodes, [
    { id: 'n1', name: '开工准备', start: '2026-11-01', end: '2026-11-02', status: '待开工', milestone: '' },
    { id: 'n2', name: '主体封顶', start: '2026-11-03', end: '2026-11-05', status: '待开工', milestone: 'topping' }
  ]);
});

test('带偏移的 Uint8Array 仍按视图边界解析', () => {
  const workbook = workbookBuffer([
    ['节点名称', '开始日期', '结束日期'],
    ['主体封顶', '2026-11-01', '2026-11-02']
  ]);
  const wrapped = new Uint8Array(workbook.length + 17);
  wrapped.set(workbook, 9);
  const result = importNodes(wrapped.subarray(9, 9 + workbook.length));
  assert.equal(result.nodes.length, 1);
  assert.equal(result.nodes[0].milestone, 'topping');
});

test('无表头数据从第一行读取', () => {
  const result = importNodes(workbookBuffer([
    ['开工准备', '2026-11-01', '2026-11-02'],
    ['封顶验收', '2026-11-03', '2026-11-03']
  ]));
  assert.equal(result.nodes.length, 2);
  assert.equal(result.nodes[0].name, '开工准备');
  assert.equal(result.nodes[1].milestone, 'topping');
});

test('支持上限窗口内的空白行，不静默截断窗口后的真实数据', () => {
  const withinWindow = importNodes(workbookBuffer([
    ['节点名称', '开始日期', '结束日期'],
    [],
    ['封顶验收', '2026-11-03', '2026-11-03']
  ]));
  assert.equal(withinWindow.nodes.length, 1);

  const afterWindow = [
    ['节点名称', '开始日期', '结束日期'],
    ['开工准备', '2026-11-01', '2026-11-02']
  ];
  while (afterWindow.length < MAX + 3) afterWindow.push([]);
  afterWindow.push(['封顶验收', '2026-11-03', '2026-11-03']);
  assert.throws(() => importNodes(workbookBuffer(afterWindow)), /首张工作表在读取窗口后仍有内容/);
});

test('缺少封顶节点时在最后一天之后自动补充', () => {
  const result = importNodes(workbookBuffer([
    ['节点名称', '开始日期', '结束日期'],
    ['开工准备', '2026-11-01', '2026-11-02']
  ]));
  assert.equal(result.notice, '已自动补充封顶验收节点，请核对其日期');
  assert.deepEqual(result.nodes.at(-1), {
    id: 'n2', name: '封顶验收', start: '2026-11-03', end: '2026-11-03', status: '待开工', milestone: 'topping'
  });
});

test('超过20行数据时拒绝导入', () => {
  const rows = [['节点名称', '开始日期', '结束日期']];
  for (let index = 0; index < MAX + 1; index += 1) {
    rows.push([`节点${index + 1}`, '2026-11-01', '2026-11-02']);
  }
  assert.throws(() => importNodes(workbookBuffer(rows)), /数据最多20行，当前21行/);
});

test('SheetJS 只解析首表并用表头加超限行作为哨兵', () => {
  const source = fs.readFileSync(path.join(root, 'miniprogram/pages/build/schedule-import.js'), 'utf8');
  const bytes = workbookBuffer([
    ['节点名称', '开始日期', '结束日期'],
    ['主体封顶', '2026-11-01', '2026-11-02']
  ]);
  let options;
  const realVendor = require('../miniprogram/pages/build/vendor/xlsx.mini.min');
  const mockVendor = {
    ...realVendor,
    read(input, opts) {
      options = opts;
      return realVendor.read(input, opts);
    }
  };
  const module = { exports: {} };
  vm.runInNewContext(source, {
    module,
    exports: module.exports,
    require: request => request === './vendor/xlsx.mini.min' ? mockVendor : require(request),
    Uint8Array,
    ArrayBuffer,
    Date,
    Error,
    String,
    Number,
    Math
  }, { filename: 'schedule-import.js' });

  module.exports.importNodes(bytes);
  assert.equal(options.sheets, 0);
  assert.equal(options.sheetRows, MAX + 2);
  assert.equal(options.cellDates, true);
  assert.equal(options.type, 'array');
});

test('超压缩文件大小上限在读取前拒绝', () => {
  assert.equal(MAX_FILE_BYTES, 2 * 1024 * 1024);
  assert.throws(() => importNodes(new Uint8Array(MAX_FILE_BYTES + 1)), /进度表文件不能超过2MB/);
});

test('高压缩无关工作表在有界子进程内按声明展开预算拒绝', () => {
  assert.equal(MAX_EXPANDED_BYTES, 8 * 1024 * 1024);
  assert.equal(MAX_ZIP_ENTRIES, 128);
  const probe = spawnSync(process.execPath, ['--max-old-space-size=128', '-e', `
    const XLSX=require('xlsx');
    const rows=[],value='A'.repeat(2000);
    for(let i=0;i<5000;i+=1)rows.push([value]);
    const workbook=XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook,XLSX.utils.aoa_to_sheet([['节点名称','开始日期','结束日期'],['主体封顶','2026-11-01','2026-11-02']]),'进度计划');
    XLSX.utils.book_append_sheet(workbook,XLSX.utils.aoa_to_sheet(rows),'无关大表');
    const bytes=XLSX.write(workbook,{type:'buffer',bookType:'xlsx',compression:true});
    try{require('./miniprogram/pages/build/schedule-import').importNodes(bytes);process.exit(2);}
    catch(error){if(!/展开内容不能超过8MB/.test(error.message))throw error;console.log(bytes.length);}
  `], { cwd: root, encoding: 'utf8', timeout: 5000, maxBuffer: 1024 * 1024 });
  assert.equal(probe.signal, null, `probe signal: ${probe.signal}`);
  assert.equal(probe.status, 0, probe.stderr || probe.stdout);
  assert.ok(Number(probe.stdout.trim()) < MAX_FILE_BYTES, 'fixture 必须证明压缩体积小于入口上限');
});

test('ZIP 条目预算在 SheetJS 解析前拒绝', () => {
  const bytes = new Uint8Array(workbookBuffer([
    ['节点名称', '开始日期', '结束日期'],
    ['主体封顶', '2026-11-01', '2026-11-02']
  ]));
  const end = endOfCentralDirectory(bytes);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  view.setUint16(end + 8, MAX_ZIP_ENTRIES + 1, true);
  view.setUint16(end + 10, MAX_ZIP_ENTRIES + 1, true);
  assert.throws(() => importNodes(bytes), /进度表内部文件过多/);
});

test('结束日期早于开始日期时给出所在行和节点', () => {
  assert.throws(() => importNodes(workbookBuffer([
    ['节点名称', '开始日期', '结束日期'],
    ['错误节点', '2026-11-03', '2026-11-02']
  ])), /第2行“错误节点”结束日期早于开始日期/);
});

test('损坏文件和伪 xlsx 内容都拒绝为格式错误', () => {
  for (const invalid of [Buffer.from([0, 1, 2, 3, 4]), Buffer.from('not an xlsx workbook')]) {
    assert.throws(() => importNodes(invalid), /文件无法解析，请确认为 \.xlsx 格式/);
  }
});

test('数字日期拒绝，避免把工期误当 Excel 序列日期', () => {
  assert.throws(() => importNodes(workbookBuffer([
    ['节点名称', '开始日期', '结束日期'],
    ['错误节点', 30, '2026-11-02']
  ])), /第2行“开始日期”是数字/);
});

function loadRecordPage({ tempFiles, chooseError } = {}) {
  const source = fs.readFileSync(path.join(root, 'miniprogram/pages/build/record/index.js'), 'utf8');
  let definition;
  const calls = { read: 0, imported: 0, failures: [] };
  const wx = {
    chooseMessageFile(options) {
      if (chooseError) options.fail(chooseError);
      else options.success({ tempFiles: tempFiles || [] });
    },
    getFileSystemManager: () => ({ readFileSync: () => { calls.read += 1; return workbookBuffer([
      ['节点名称', '开始日期', '结束日期'],
      ['主体封顶', '2026-11-01', '2026-11-02']
    ]); } }),
    showToast() {}
  };
  const empty = {};
  vm.runInNewContext(source, {
    Page: page => { definition = page; },
    wx,
    getApp: () => ({ globalData: {} }),
    require(request) {
      if (request === '../schedule-import') return {
        MAX_FILE_BYTES: 2 * 1024 * 1024,
        importNodes(buffer) {
          calls.imported += 1;
          return importNodes(buffer);
        }
      };
      if (request === '../../../utils/view-container') return { makeFormHandlers: () => ({}) };
      if (request === '../../../utils/platform-engineering') return empty;
      return empty;
    },
    Date,
    String,
    Number,
    Math,
    Promise,
    encodeURIComponent
  }, { filename: 'record/index.js' });
  const page = {
    data: { form: {} },
    setData() {},
    fail(message) { calls.failures.push(message); }
  };
  return { definition, page, calls };
}

test('文件元数据超限时在 readFileSync 前拒绝', () => {
  const fixture = loadRecordPage({ tempFiles: [{ path: '/tmp/large.xlsx', size: 2 * 1024 * 1024 + 1 }] });
  fixture.definition.importPlan.call(fixture.page);
  assert.equal(fixture.calls.read, 0);
  assert.equal(fixture.calls.imported, 0);
  assert.deepEqual(fixture.calls.failures, ['进度表文件不能超过2MB']);

  const accepted = loadRecordPage({ tempFiles: [{ path: '/tmp/valid.xlsx', size: 2 * 1024 * 1024 }] });
  accepted.definition.importPlan.call(accepted.page);
  assert.equal(accepted.calls.read, 1);
  assert.equal(accepted.calls.imported, 1);
  assert.deepEqual(accepted.calls.failures, []);
});

test('文件选择失败区分用户取消与真实错误', () => {
  const cancelled = loadRecordPage({ chooseError: { errMsg: 'chooseMessageFile:fail cancel' } });
  cancelled.definition.importPlan.call(cancelled.page);
  assert.deepEqual(cancelled.calls.failures, []);

  const failed = loadRecordPage({ chooseError: { errMsg: 'chooseMessageFile:fail permission denied' } });
  failed.definition.importPlan.call(failed.page);
  assert.deepEqual(failed.calls.failures, ['选择进度表失败，请重试']);
});
