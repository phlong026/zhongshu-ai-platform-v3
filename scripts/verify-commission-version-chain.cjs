#!/usr/bin/env node
/**
 * 批次#31 · M05-T04 前端版本展示 + M06-T03 packet UI 行为级验证。
 * 断言：presentation.build 冻结版本链字段（下发序号+冻结需求版本/未确认新版本提示/移交包版本+退回补件链）
 * + commission 页 Node Page/WXML 测试运行器渲染复核（progress 卡下发版本行、handover 包版本行）——该 runner 是确定性 Page/WXML 集成模拟，非微信原生渲染器。
 * 用法：node scripts/verify-commission-version-chain.cjs
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const Presentation = require('../miniprogram/pages/workflow/commission/presentation');
const clone = value => JSON.parse(JSON.stringify(value));

// —— 第一层：presentation 纯函数行为（Node 直调，合成客户） ——
const baseCustomer = () => ({
  id: 'c-version', name: '版本链客户', city: '泉州', projectId: 'p-version', company: 'quanzhou',
  request: { version: 2, text: '需求文本', scope: '交付范围', basis: { area: 120, floors: 2, width: 10, depth: 9 } },
  businessFlow: {
    type: 'sales-offline-v1', submittedAt: '2026-11-01T10:00:00Z',
    dispatch: { version: 2, requestVersion: 1, drawingVersion: 'V2', at: '2026-11-02T10:00:00Z', date: '2026-11-02', recipient: '业主', note: '下发' },
    dispatchHistory: [{ version: 1, requestVersion: 1, drawingVersion: 'V1', at: '2026-11-01T10:00:00Z', date: '2026-11-01' }]
  }
});

const journeyOf = customer => Presentation.build(customer, 'progress', {}, null, { view: 'progress' }, null);

assert.equal(journeyOf(baseCustomer()).dispatchedVersionText,
  '第 2 次下发 · V2（冻结需求 V1）', '下发版本快照文本（下发序号+冻结需求版本）');
assert.ok(journeyOf(baseCustomer()).pendingVersionNotice.includes('V2')
  && journeyOf(baseCustomer()).pendingVersionNotice.includes('未经正式确认不能替换'), '未确认新版本不能替换提示');
assert.deepEqual(journeyOf(baseCustomer()).dispatchHistory,
  ['第 1 次 · V1 · 因修改意见关闭'], '历史下发版本链');

const sameVersion = baseCustomer();
sameVersion.businessFlow.dispatch.requestVersion = 2;
assert.equal(journeyOf(sameVersion).pendingVersionNotice, '', '同版下发不误报（request.version=dispatch.requestVersion）');

const offlineDispatch = baseCustomer();
delete offlineDispatch.businessFlow.dispatch.drawingVersion;
assert.ok(journeyOf(offlineDispatch).dispatchedVersionText.includes('线下成果'), '线下链无图纸版本兜底文案（不渲染 undefined）');

const packetCustomer = baseCustomer();
packetCustomer.businessFlow.packet = {
  version: 2, dispatchVersion: 2, drawingVersion: 'V2', confirmationDate: '2026-11-03', note: '交底',
  resultFile: { name: 'a.pdf', text: 'x', sample: true, meta: { version: 'V2' } },
  drawingFile: { name: 'b.pdf', text: 'x', sample: true, meta: { version: 'V2' } },
  reviewFile: { name: 'c.pdf', text: 'x', sample: true, meta: { version: 'V2' } },
  confirmationFile: { name: 'd.pdf', text: 'x', sample: true, meta: { version: 'V2' } }
};
packetCustomer.handoverHistory = [{ version: 1, drawingVersion: 'V1', status: 'returned' }];
const packetJourney = journeyOf(packetCustomer);
assert.equal(packetJourney.packetVersionText, '第 2 包 · 冻结第 2 次下发 · V2', '移交包冻结版本文本（包版本+冻结下发序号）');
assert.deepEqual(packetJourney.packetHistory, ['第 1 包 · V1 · 已退回补件'], '退回补件重提版本链');
assert.deepEqual(journeyOf(baseCustomer()).packetVersionText, '', '未提交包不出包版本行');
console.log('  ✅ presentation 版本链字段（下发快照/未确认提示/历史链/包版本/补件链）');

// —— 第二层：Node Page/WXML 测试运行器渲染复核（role-page-runtime；packet-submit 场景账本为基座） ——
(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const scenes = JSON.parse(fs.readFileSync(path.join(__dirname, '../qa/business-module-simplification-20261021/page-scenes.json')));
  const scene = scenes.find(row => row.action === 'packet-submit');
  assert(scene, '缺少 packet-submit 回归起点');

  const install = rows => {
    for (const key of wx.getStorageInfoSync().keys) wx.removeStorageSync(key);
    for (const [key, value] of clone(rows)) wx.setStorageSync(key, value);
  };
  const page = () => getCurrentPages().at(-1); // 原始页对象（facade 不暴露 data；$ 与 screenshot 走 runtime）
  const businessKey = 'zhongshu-business-v3';
  const customer = () => clone(wx.getStorageSync(businessKey).customers.find(row => row.id === scene.id));
  const mutate = fn => {
    const root = clone(wx.getStorageSync(businessKey));
    fn(root.customers.find(row => row.id === scene.id));
    wx.setStorageSync(businessKey, root);
  };

  install(scene.before);
  // 场景 A：progress 视图——下发版本快照 + 未确认新版本提示 + 历史下发链
  // （paymentMode 置 newMode 使 journey 渲染链路生效；版本行落 rows 明细区 index>1）
  mutate(c => {
    c.request.version = 2;
    c.businessFlow.paymentMode = 'area-full-demo-v1';
    c.businessFlow.dispatchHistory = [{ version: 1, requestVersion: 1, drawingVersion: 'V1', at: '2026-11-01T10:00:00Z', date: '2026-11-01' }];
  });
  wx.reLaunch({ url: `/pages/workflow/commission/index?view=progress&id=${encodeURIComponent(scene.id)}` });
  assert.equal((await runtime.currentPage()).path, 'pages/workflow/commission/index');
  let current = page();
  assert.equal(current.data.view, 'progress');
  const confirmRow = current.data.rows.find(row => row.label === '确认下发版本');
  assert.ok(confirmRow && String(confirmRow.value).includes('第 1 次下发') && String(confirmRow.value).includes('冻结需求 V1'),
    '委托详情 rows 携带确认下发版本行：' + JSON.stringify(confirmRow));
  assert.ok(current.data.rows.some(row => row.label === '版本提示' && String(row.value).includes('未经正式确认不能替换')),
    '委托详情 rows 携带未确认新版本提示');
  assert.ok(current.data.rows.some(row => row.label === '历史下发' && String(row.value).includes('因修改意见关闭')),
    '委托详情 rows 携带历史下发行');
  const shotA = path.join(os.tmpdir(), 'commission-version-progress.json');
  await runtime.screenshot({ path: shotA });
  const renderedA = JSON.parse(fs.readFileSync(shotA, 'utf8')).renderedText;
  // 注：runner 对 wx:for+wx:if 同元素（明细 rows 区 wx:if index>1）按外层作用域求值——rows 渲染以 data 级断言为准；
  // journey 区（纯 wx:if/wx:for）走本运行器渲染复核（非微信原生渲染）
  for (const text of ['确认下发版本', '第 1 次下发', '冻结需求 V1', '客户需求已更新至 V2', '未经正式确认不能替换', '第 1 次 · V1 · 因修改意见关闭']) {
    assert.ok(renderedA.includes(text), 'progress 运行器渲染缺失「' + text + '」');
  }
  console.log('  ✅ progress 页运行器渲染复核：下发版本快照+未确认新版本提示+历史下发链');

  // 场景 B：handover 视图——移交包冻结版本 + 退回补件重提链
  const file = (name, party) => ({ name, text: name + ' 内容文本', sample: true, meta: { version: 'V2', sourceParty: '总部设计', confirmationParty: party || '' } });
  mutate(c => {
    c.businessFlow.packet = {
      version: 2, dispatchVersion: 1, drawingVersion: 'V2', confirmationDate: '2026-11-03', note: '现场交底说明',
      resultFile: file('成果V2.pdf'), drawingFile: file('施工图V2.pdf'), reviewFile: file('审核V2.pdf'),
      confirmationFile: file('确认V2.pdf', scene.id)
    };
    c.handoverHistory = [{ version: 1, drawingVersion: 'V1', status: 'returned' }];
    c.handover = { version: 2, status: 'returned', recipientRole: 'project-manager', managerId: 'engineering-dual', manager: '工程负责人', note: '交底', date: '2026-11-03', designVersion: 1, drawingVersion: 'V2' };
  });
  wx.reLaunch({ url: `/pages/workflow/commission/index?view=handover&id=${encodeURIComponent(scene.id)}` });
  current = page();
  assert.equal(current.data.view, 'handover');
  assert.ok(current.data.rows.some(row => row.label === '移交包版本' && String(row.value).includes('第 2 包') && String(row.value).includes('冻结第 1 次下发')),
    '委托详情 rows 携带移交包版本行');
  assert.ok(current.data.rows.some(row => row.label === '历史移交包' && String(row.value).includes('已退回补件')),
    '委托详情 rows 携带历史移交包行');
  const shotB = path.join(os.tmpdir(), 'commission-version-handover.json');
  await runtime.screenshot({ path: shotB });
  const renderedB = JSON.parse(fs.readFileSync(shotB, 'utf8')).renderedText;
  for (const text of ['移交包版本', '第 2 包', '冻结第 1 次下发', '第 1 包 · V1 · 已退回补件']) {
    assert.ok(renderedB.includes(text), 'handover 运行器渲染缺失「' + text + '」');
  }
  console.log('  ✅ handover 页运行器渲染复核：移交包冻结版本+退回补件重提版本链');

  console.log('\n批次#31 M05-T04/M06-T03 版本链验证：全部通过');
  process.exit(0);
})().catch(e => { console.error('  ❌ ' + (e && e.message)); process.exit(1); });
