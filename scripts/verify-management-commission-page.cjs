const assert = require('node:assert/strict');
const path = require('node:path');
const { render } = require('./role-page-runtime.cjs');

const file = path.resolve('miniprogram/pages/admin/commission/index.wxml');
const flat = nodes => nodes.flatMap(node => [node, ...flat(node.children || [])]);
const text = node => (node.text || '') + (node.children || []).map(text).join('');
const controls = (nodes, action) => flat(nodes).filter(node => node.attrs?.['data-action'] === action);
const base = {
  ready:true, error:'', busy:false,
  commissionProjects:[{ id:'project-1', name:'周先生 · 自建房' }], commissionProjectIndex:0,
  commissionStaff:[{ id:'sales-1', name:'业务甲' }], commissionStaffIndex:0,
  commissionForm:{ amount:'100.01', assignee:'sales-1', note:'', file:null, checked:false, expectedVersion:1 },
  commissionRows:[{
    id:'policy-1', projectId:'project-1', project:'周先生 · 自建房', totalText:'¥ 100.01', assignee:'业务甲', version:1,
    stages:[
      { stage:'start', label:'开工款', status:'可生成应付', requiredText:'¥ 30,000.00', receivedText:'¥ 30,000.00', amountText:'¥ 50.00', paidText:'¥ 0.00', outstandingText:'¥ 50.00', canRelease:true, canPay:false, canReconcile:false, reason:'阶段款已足额，可由财务生成本期应付。' },
      { stage:'topping', label:'封顶款', status:'待人工核对', payableId:'payable-1', requiredText:'¥ 30,000.00', receivedText:'¥ 30,000.00', amountText:'¥ 50.01', paidText:'¥ 20.00', outstandingText:'¥ 30.01', canRelease:false, canPay:false, canReconcile:true, reviewDue:true, reason:'原收款已变化，未付部分暂停；已付差额只待人工核对，不代表已追回。' }
    ]
  }]
};

const roleRows = changes => base.commissionRows.map(row => ({ ...row, stages:row.stages.map(stage => ({ ...stage, ...changes[stage.stage] })) }));

let nodes = render(file, { ...base, actor:{ role:'boss' }, canEditPolicy:true, commissionRows:roleRows({
  start:{ canRelease:false, canPay:false, canReconcile:false },
  topping:{ canRelease:false, canPay:false, canReconcile:true }
}) });
assert.equal(controls(nodes, 'commission-policy').length, 1, '实际老板显示规则编辑');
assert.equal(controls(nodes, 'commission-reconcile').length, 1, '实际老板显示足额后的人工核对');
assert.equal(controls(nodes, 'commission-release').length, 0, '老板不能生成财务应付');

nodes = render(file, { ...base, actor:{ role:'owner' }, canEditPolicy:false, commissionRows:base.commissionRows.map(row => ({ ...row, stages:row.stages.map(stage => ({ ...stage, canRelease:false, canPay:false, canReconcile:false })) })) });
assert.equal(controls(nodes, 'commission-policy').length, 0);
assert.equal(controls(nodes, 'commission-release').length, 0);
assert.equal(controls(nodes, 'commission-reconcile').length, 0);
assert(text({ children:nodes }).includes('只读'));

nodes = render(file, { ...base, actor:{ role:'finance' }, canEditPolicy:false, commissionRows:[{ ...base.commissionRows[0], stages:[
  { ...base.commissionRows[0].stages[0], canRelease:true, canReconcile:false },
  { ...base.commissionRows[0].stages[1], status:'待付款', reviewDue:false, canPay:true, canReconcile:false, reason:'原应付已生成，请从原单登记实际付款。' }
] }] });
assert.equal(controls(nodes, 'commission-policy').length, 0, '财务不能保存提成规则');
assert.equal(controls(nodes, 'commission-release').length, 1, '财务只对足额且未生成应付阶段显示生成入口');
assert.equal(flat(nodes).filter(node => node.attrs?.bindtap === 'commissionPay').length, 1, '已有可支付应付进入原付款登记');
assert(!/\d+(?:\.\d+)?分/.test(text({ children:nodes })), '产品页面金额不得显示整数分实现细节');

const source = require('node:fs').readFileSync('miniprogram/pages/admin/commission/index.js','utf8');
assert(source.includes("['boss','owner','finance','finance-lead']"));
assert(source.includes("actor.role === 'boss'"));
assert(source.includes("O.has(actor.role, 'finance')"));
assert(!/setData\([^)]*(state|book|receipts|payments|phone)/s.test(source), '页面不得把完整账簿、收付款或电话传入 setData');
console.log('PASS 提成独立页面：老板编辑、门店负责人只读、财务生成及原单付款入口');
