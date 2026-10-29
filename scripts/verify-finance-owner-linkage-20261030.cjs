const assert = require('node:assert/strict');
const path = require('node:path');
const { render } = require('./role-page-runtime.cjs');

const root = path.resolve(__dirname, '..');
const Desk = require('../miniprogram/utils/finance-desk');

const material = Desk.displayAdvance({
  advanced: 120000,
  repaid: 80000,
  received: 50000,
  remaining: 70000,
  storeRemaining: 40000,
  inTransit: 30000
});

assert.equal(material.receivedText, '¥ 500.00', '总部已核收金额必须来自总部有效收款');
assert.equal(material.pendingText, '¥ 300.00', '待核金额必须等于门店已还与总部已核收的差额');

const nodes = render(path.join(root, 'miniprogram/pages/admin/finance-desk/index.wxml'), {
  ready:true, view:'partner', title:'加盟商往来', stats:[],
  partner:{
    name:'湖北店', status:'待核收', tone:'warn', simulatedDesign:false,
    designText:'¥ 0.00', advanceText:material.remainingText, design:[],
    material:[{ id:'advance-1', name:'周先生 · 主材代垫', ...material, advancedText:'¥ 1,200.00', repaidText:'¥ 800.00', remainingText:'¥ 700.00', canOpen:false }]
  }
});
const text = node => (node.text || '') + (node.children || []).map(text).join('');
const rendered = text({ children:nodes });
assert(rendered.includes('总部材料代垫'), '真实 WXML 渲染必须包含总部材料代垫卡片');
assert(rendered.includes('总部已核收 ¥ 500.00 · 待核 ¥ 300.00'),
  '真实 WXML 渲染应明确区分总部已核收和待核差额');
assert(!rendered.includes('undefined'), '渲染金额不得来自不存在的 paidText');

console.log(JSON.stringify({ result:'passed', checks:3 }));
