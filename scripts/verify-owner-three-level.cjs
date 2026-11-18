const assert = require('node:assert/strict');

const customer = {
  id:'customer-stale', projectId:'project-late', companyId:'quanzhou', name:'超期客户', ownerId:'sales-a', owner:'李业务',
  createdAt:'2026-10-01', status:'跟进中', follows:[], quotes:[], serviceOrders:[], businessFlow:{}
};
const project = {
  id:'engineering-late', projectId:'project-late', companyId:'quanzhou', name:'延期工地', customer:'超期客户',
  actualStart:{date:'2026-10-01'}, archived:false, delivery:null,
  recordFlow:{contract:{version:1,total:10000000,first:6000000,file:'contract.pdf'}},
  plan:{nodes:[{id:'node-1',name:'主体施工',start:'2026-10-01',end:'2026-10-20',status:'施工中'}]},
  supplies:[], receipts:[], reships:[], payments:[], collections:[], materialMovements:[], inspections:[], rectifications:[]
};
const storage = {
  'zhongshu-business-v3': {customers:[customer]},
  'zhongshu-engineering-v1': {projects:[project]},
  'zhongshu-growth-v1': {contents:[],leads:[]}
};
global.wx = {
  getStorageSync(key){ return storage[key] || {}; },
  setStorageSync(key,value){ storage[key]=value; }
};

const state = {
  companyId:'quanzhou', period:'2026-10',
  receipts:[], payments:[], refunds:[],
  payables:[{id:'payable-soon',projectId:'project-late',customer:'超期客户',name:'材料款',total:200000,due:'2026-11-03',approvalValid:true,status:'待办理'}],
  operations:{
    plans:[{id:'plan-1',projectId:'project-late',status:'approved',signature:JSON.stringify(['project-late',1,10000000,6000000,'contract.pdf']),installments:[{id:'start',name:'开工款',amount:6000000,stage:'start',due:'2026-10-15'}]}],
    expenses:[],costChecks:[],exceptions:[],funds:[],events:[],direct:[],directApprovals:[],payrollPolicies:[],payrollSettlements:[],periodSnapshots:[],projectClosures:[]
  },
  sources:[{id:'perf-material:engineering-late:supply-1',kind:'material',person:'engineer-a',date:'2026-10-18',onTime:true,url:'/pages/build/index?view=batches&id=engineering-late&supplyId=supply-1'}],
  archives:[], staff:[], work:[], transfers:[]
};
const snapshot = {
  period:'2026-10', today:'2026-10-29', actor:{companyId:'quanzhou'}, state,
  customers:[customer], projects:[project], commissions:[], newCustomers:[customer],
  sales:'0', income:'0', reconcileCount:0, reviewCount:0, metrics:[{percent:null},{percent:null},{percent:null}],
  operating:{
    cash:null,
    payableDue:200000,
    rows:[{id:'project-late',name:'延期工地',total:10000000,operatingOnly:false,cost:{complete:false,forecast:null,committed:0,total:0,profit:null}}]
  },
  projectRows:[], focus:[{key:'engineering-late',risk:false}], approvals:[], reviews:[], staff:[], team:[], goals:{}
};

const model = require('../miniprogram/pages/admin/owner-business').business(snapshot);
assert.deepEqual(model.levels.map(level=>level.key), ['company','business','engineering'], '经营页必须按公司、业务、工程三层组织');
assert.equal(model.levels[2].cards[0].key, 'materials', '工程层第一张卡必须是材料事实');
assert.equal(model.levels[2].cards[0].value, '1');
assert.match(model.levels[2].cards[0].note, /足量合格 1 批 · 按期 1 批/);
assert.deepEqual(model.alerts.map(alert=>alert.key), ['schedule','cost','cash'], '预警中心必须组织工期、成本和七日现金覆盖三类定稿事实');
assert.equal(model.alerts[0].count, 1, '超过计划结束日三天的未完成节点必须进入工期预警');
assert.equal(model.alerts[1].value, '待完善', '成本事实不完整时不得显示零风险');
assert.equal(model.alerts[1].tone, 'pending');
assert.equal(model.alerts[2].value, '待完善', '现金未核对时不得显示七日应付已覆盖');
assert.equal(model.alerts[2].tone, 'pending');
assert(model.alerts.every(alert=>alert.url && alert.url.startsWith('/pages/')), '每类预警必须保留下钻路由');
assert.deepEqual(model.headline.map(item=>item.label), ['本月有效回款','项目预计毛利','经营预警']);
assert.equal(model.headline[2].value, '待完善', '任一核心预警证据缺失时首屏不得汇总为零');

const known = require('../miniprogram/pages/admin/owner-business').business({
  ...snapshot,
  operating:{
    cash:100000,
    payableDue:200000,
    rows:[{id:'project-late',name:'延期工地',total:10000000,operatingOnly:false,cost:{complete:true,forecast:10100000,committed:9000000,total:8000000,profit:-100000}}]
  }
});
assert.deepEqual(known.alerts.map(alert=>alert.count), [1,1,1], '证据完整时三类风险按原事实计数');
assert.match(known.alerts[1].note, /预计成本.*已承诺.*实际成本/);
assert.match(known.alerts[1].url, /view=costs.*id=project-late/, '单项目成本超支应下钻原项目成本单');
assert.match(known.alerts[2].note, /未来7天应付.*可用现金.*缺口/);
assert.match(known.alerts[2].url, /view=payables/, '现金覆盖不足应下钻原应付事项');

// The retained model above and the online finance page have different data sources.
const {render} = require('./role-page-runtime.cjs');
const {view} = require('../miniprogram/utils/platform-owner-home');
const template = require('node:path').join(__dirname, '../miniprogram/pages/admin/finance/index.wxml');
function renderedText(operating) {
  const desk = view({period:'2026-11', post:{roleCode:'boss'}, operating});
  const textOf = nodes => nodes.map(node => (node.text || '') + textOf(node.children)).join(' ');
  return textOf(render(template, {desk, ready:true}));
}

const online = renderedText({
  trend:[{period:'2026-10',incomeCents:900,paidCents:200,netCents:700},
         {period:'2026-11',incomeCents:123456,paidCents:45000,netCents:78456}],
  totals:{receivableCents:100000,payableOutstandingCents:80000,collectionRate:0.625},
  incomeComposition:[{kind:'design',cents:23456}],
  paidComposition:[{kind:'expense-refund',cents:-500}],
  receivableAging:{d1_30:6900},payableAging:{undated:8100}
});
for (const label of ['本月实收','¥1,234.56','62.5%','近 6 个月收支趋势','¥9.00','收支构成','设计服务','¥234.56','支出退回','-¥5.00','逾期 1-30 天','¥69.00','未约定日期','¥81.00']) {
  assert.ok(online.includes(label), '经营页面缺少对应的线上数据或栏目：' + label);
}
const unavailable = renderedText(null);
assert.match(unavailable, /经营分析暂不可用/);
assert.doesNotMatch(unavailable, /¥0\.00|近 6 个月收支趋势/);
console.log('PASS 历史经营模型的三层与预警；在线经营页的金额、趋势、构成、账龄与不可用态');
