const test = require('node:test');
const assert = require('node:assert/strict');
const XLSX = require('xlsx');
const { importNodes, MAX } = require('../miniprogram/pages/build/schedule-import');

function workbookBuffer(rows) {
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet(rows), '进度计划');
  return XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' });
}

function exactArrayBuffer(buffer) {
  return buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength);
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

test('无表头数据从第一行读取', () => {
  const result = importNodes(workbookBuffer([
    ['开工准备', '2026-11-01', '2026-11-02'],
    ['封顶验收', '2026-11-03', '2026-11-03']
  ]));
  assert.equal(result.nodes.length, 2);
  assert.equal(result.nodes[0].name, '开工准备');
  assert.equal(result.nodes[1].milestone, 'topping');
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
