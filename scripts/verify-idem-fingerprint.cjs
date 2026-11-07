#!/usr/bin/env node
/**
 * 幂等命令指纹行为验证（Codex R6 P2）：直接执行 platform-engineering.idemFingerprint 纯函数，
 * 覆盖 R4-R6 评审全部场景——不匹配源码字符串，跑真实判定。
 *  1) 到货（R5）：响应丢失→刷新余量回流 6→4→同内容重试 ⇒ 指纹稳定（复用键）
 *  2) 调整（R6）：adjust 8→7 ⇒ 指纹变（新键，防后端回放旧数量）；行集合变化 ⇒ 指纹变
 *  3) 材料计划（R6）：行 name/quantity 改 ⇒ 指纹变；余量/到货态回流 ⇒ 指纹稳定
 *  4) 施工计划（R6）：节点日期/名称改 ⇒ 指纹变
 *  5) 费用登记（R4）：名称/金额改 ⇒ 指纹变；同内容 ⇒ 指纹稳定
 *  6) 通用：supplyLabel/supplyOptions 展示回流 ⇒ 全部动作指纹稳定
 * 用法：node scripts/verify-idem-fingerprint.cjs
 */
const assert = require('node:assert/strict');
const { idemFingerprint } = require('../miniprogram/utils/platform-engineering');

const arrivalForm = () => ({ supplyId: 's1', supplyLabel: '砂 · 余 6', supplyOptions: [{ id: 's1', label: '砂 · 余 6' }],
  lines: [{ id: 'm1', name: '砂', supplyId: 's1', remainingQty: 6, unit: 'm³' }], actual: '4', date: '2026-11-08', transaction: 'SN001', note: '' });
const adjustForm = (qty) => ({ lines: [{ id: 'm1', name: '砂', remainingQty: 6, adjust: qty }], adjustReason: '现场复核' });
const planForm = () => ({ lines: [{ name: '砂 中砂', spec: '中砂', unit: 'm³', quantity: 10, remainingQty: 8, received: 2 }], due: '2026-11-10' });
const nodesForm = (end) => ({ nodes: [{ nodeIndex: 0, name: '基础施工', start: '2026-11-01', end }] });
const expenseForm = (name, amount) => ({ expenseName: name, purposeCode: 'LABOR', expenseAmount: amount, note: '' });

let pass = 0;
const ok = (cond, name) => { assert.ok(cond, name); pass++; console.log('  ok - ' + name); };

// 1) 到货（R5 复现场景：余量回流变化不改指纹）
{
  const a = arrivalForm(), b = arrivalForm();
  b.supplyLabel = '砂 · 余 4'; b.supplyOptions = [{ id: 's1', label: '砂 · 余 4' }];
  b.lines = [{ id: 'm1', name: '砂', supplyId: 's1', remainingQty: 4, unit: 'm³' }];
  ok(idemFingerprint('arrival', a) === idemFingerprint('arrival', b), 'R5：到货响应丢失→刷新余量 6→4→同内容重试指纹稳定（复用同键）');
  const c = arrivalForm(); c.actual = '5';
  ok(idemFingerprint('arrival', a) !== idemFingerprint('arrival', c), 'R5：到货实收数量改 ⇒ 新命令（新键）');
}
// 2) 调整（R6 复现场景：改 adjust 不得复用旧键）
{
  ok(idemFingerprint('material-adjust', adjustForm('8')) !== idemFingerprint('material-adjust', adjustForm('7')),
    'R6：调整量 8→7 ⇒ 指纹变（防后端回放旧数量 8）');
  const a = adjustForm('8'), b = adjustForm('8'); b.lines[0].remainingQty = 4;
  ok(idemFingerprint('material-adjust', a) === idemFingerprint('material-adjust', b), 'R6：调整同数量、余量回流变化 ⇒ 指纹稳定');
  const c = adjustForm('8'); c.lines.push({ id: 'm2', adjust: '2' });
  ok(idemFingerprint('material-adjust', a) !== idemFingerprint('material-adjust', c), 'R6：调整行集合变化（增行）⇒ 新命令');
}
// 3) 材料计划（R6）
{
  const a = planForm(), b = planForm();
  b.lines[0].remainingQty = 5; b.lines[0].received = 5; // 回流统计
  ok(idemFingerprint('material-plan', a) === idemFingerprint('material-plan', b), 'R6：材料计划余量/到货回流 ⇒ 指纹稳定');
  const c = planForm(); c.lines[0].quantity = '12';
  ok(idemFingerprint('material-plan', a) !== idemFingerprint('material-plan', c), 'R6：材料计划数量改 ⇒ 新命令');
  const d = planForm(); d.lines[0].name = '粗砂';
  ok(idemFingerprint('material-plan', a) !== idemFingerprint('material-plan', d), 'R6：材料计划名称改 ⇒ 新命令');
}
// 4) 施工计划（R6：nodes 仅 plan 动作入指纹且保用户输入）
{
  ok(idemFingerprint('plan', nodesForm('2026-11-20')) !== idemFingerprint('plan', nodesForm('2026-11-25')),
    'R6：计划节点日期改 ⇒ 新命令');
  const a = nodesForm('2026-11-20'), b = nodesForm('2026-11-20'); b.lines = [{ id: 'm1', remainingQty: 3 }];
  ok(idemFingerprint('plan', a) === idemFingerprint('plan', b), 'R6：plan 动作 lines 回流不入指纹 ⇒ 稳定');
  const idle = { nodes: [{ name: 'x' }] };
  ok(idemFingerprint('arrival', idle) === idemFingerprint('arrival', {}), 'R6：非 plan 动作 nodes 不入指纹');
}
// 5) 费用登记（R4 场景基线）
{
  ok(idemFingerprint('expense-register', expenseForm('人工', '300')) === idemFingerprint('expense-register', expenseForm('人工', '300')),
    'R4：同内容费用 ⇒ 指纹稳定（失败重试复用键）');
  ok(idemFingerprint('expense-register', expenseForm('人工', '300')) !== idemFingerprint('expense-register', expenseForm('运输', '300')),
    'R4：改费用名 ⇒ 新命令（响应丢失后新登记不回放旧命令）');
  ok(idemFingerprint('expense-register', expenseForm('人工', '300')) !== idemFingerprint('expense-register', expenseForm('人工', '350')),
    'R4：改金额 ⇒ 新命令');
}
// 6) 通用展示回流
{
  const base = { note: 'x' }, ref = { note: 'x', supplyLabel: 'lbl', supplyOptions: [{ id: 1 }] };
  for (const action of ['expense-register', 'collect', 'node', 'prepare', 'deliver']) {
    ok(idemFingerprint(action, base) === idemFingerprint(action, ref), `通用：${action} 展示字段回流 ⇒ 指纹稳定`);
  }
}

console.log(`verify-idem-fingerprint ✔  ${pass} 项断言通过（R4-R6 幂等指纹行为级验证）`);
