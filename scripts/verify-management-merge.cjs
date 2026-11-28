// Legacy admin fixture checks preserve historical behavior; they do not prove the current online workflow.
const assert = require('node:assert/strict');

const storage = {
  'zhongshu-growth-v1': {
    contents: [{ id:'content-oct', companyId:'quanzhou', authorId:'sales-a', status:'演示已发布', publishedAt:'2026-10-05T09:00:00.000Z', pack:{ title:'十月内容' } }],
    leads: [
      { id:'lead-open', companyId:'quanzhou', ownerId:'sales-a', name:'未转客户线索', source:'视频号', status:'跟进中', at:'2026-10-06T09:00:00.000Z', contentId:'content-oct' },
      { id:'lead-invalid', companyId:'quanzhou', ownerId:'sales-a', name:'无效线索', status:'无效', at:'2026-10-07T09:00:00.000Z' }
    ]
  }
};

global.wx = {
  getStorageSync(key) { return storage[key] || {}; },
  setStorageSync(key, value) { storage[key] = value; },
  removeStorageSync(key) { delete storage[key]; }
};

const Home = require('./fixtures/legacy-admin/owner-home');
const clone = value => JSON.parse(JSON.stringify(value));

const customers = [
  {
    id:'commission', projectId:'project-commission', companyId:'quanzhou', name:'委托客户', ownerId:'sales-a', owner:'张业务', createdAt:'2026-10-01',
    follows:[{ at:'2026-10-03T09:00:00.000Z', method:'微信', content:'确认建房需求' }], quotes:[],
    businessFlow:{ submittedAt:'2026-10-08T09:00:00.000Z' }, serviceOrders:[{ id:'order-commission', total:20000 }]
  },
  {
    id:'visit-type', projectId:'project-visit-type', companyId:'quanzhou', name:'明确到店客户', ownerId:'sales-a', owner:'张业务', createdAt:'2026-10-02', status:'跟进中', quotes:[],
    follows:[{ date:'2026-10-09', method:'电话', content:'确认到店时间', appointment:'2026-10-25 10:00', appointmentType:'到店', next:'核对建房需求' }]
  },
  {
    id:'visit-next', projectId:'project-visit-next', companyId:'quanzhou', name:'到店下一步客户', ownerId:'sales-a', owner:'张业务', createdAt:'2026-10-03', status:'跟进中', quotes:[],
    follows:[{ date:'2026-10-10', method:'微信', content:'确认行程', appointment:'2026-10-26 10:00', appointmentMethod:'电话', next:'到店沟通方案' }]
  },
  {
    id:'phone-only', projectId:'project-phone', companyId:'quanzhou', name:'电话预约客户', ownerId:'sales-a', owner:'张业务', createdAt:'2026-10-04', status:'跟进中', quotes:[],
    follows:[{ date:'2026-10-11', method:'电话', content:'电话回访', appointment:'2026-10-27 10:00', appointmentType:'电话', next:'电话回访' }]
  }
];

const snapshot = {
  period:'2026-10', today:'2026-10-23', sales:'2', income:'1', reconcileCount:0,
  customers, commissions:[customers[0]], newCustomers:customers, metrics:[{ percent:null }],
  approvals:[], reviews:[], projects:[], staff:[
    { id:'sales-a', name:'张业务', active:true },
    { id:'sales-b', name:'刘业务', active:true },
    { id:'left', name:'离岗业务', active:false }
  ],
  state:{
    companyId:'quanzhou', receipts:[{ id:'receipt-1', customer:'委托客户', name:'工程到账', amount:100000, status:'已核算', bankDate:'2026-10-12', registrar:'王财务' }],
    payments:[], payables:[], refunds:[], operations:{ funds:[] }, sources:[], transfers:[],
    work:[
      { id:'work-left', kind:'customer-responsibility', customer:'离岗客户', name:'客户跟进', stage:'需求沟通', owner:'left' },
      { id:'work-marked', kind:'project-responsibility', customer:'明确交接项目', name:'工程负责', stage:'施工中', owner:'sales-a', handoverRequired:true, handoverRequiredAt:'2026-10-20T09:00:00.000Z' },
      { id:'work-active', kind:'project-responsibility', customer:'在岗项目', name:'工程负责', stage:'施工中', owner:'sales-a' },
      { id:'work-legacy-content', customer:'秋季建房口播', name:'内容任务', stage:'待发布', owner:'left' },
      { id:'work-legacy-follow', customer:'历史客户', name:'客户跟进', stage:'需求沟通', owner:'left' },
      { id:'work-legacy-engineering', customer:'历史工程', name:'工程移交', stage:'待接收', owner:'left' },
      { id:'engineering-work:biz-customer', kind:'engineering-handover', customer:'业务转工程', name:'工程移交', stage:'待接收', owner:'left' },
      { id:'work-archived', kind:'project-responsibility', customer:'归档项目', name:'工程负责', stage:'已归档', owner:'left' }
    ]
  }
};

const home = Home.home(snapshot);
assert.deepEqual(home.six.map(row => row.key), [
  'stat:commission-amount', 'stat:income', 'stat:commission-count',
  'stat:new-leads', 'stat:effective-customers', 'stat:visited-customers'
]);
assert(home.six.every(row => row.url.startsWith('/pages/admin/detail/index?view=stat&id=')));
assert.equal(home.six.find(row => row.metric === 'new-leads').value, '1', '未转客户的真实线索也进入新增线索统计');
assert(Home.rows('stat', snapshot, 'new-leads').some(row => row.name === '未转客户线索'));
assert(Home.rows('stat', snapshot, 'income')[0].status.includes('receipt-1'), '新版明细保留原单号');
assert.equal(Home.rows('stat', snapshot, 'effective-customers').length, 4, '新版统计识别只有 at 的真实跟进');
assert.equal(home.six.find(row => row.metric === 'effective-customers').value, '4');
const performance = require('./fixtures/legacy-admin/owner-business').performance({...snapshot,goals:{}});
assert.equal(performance.targets.find(row => row.label === '有效客户').value, '4', '绩效与首页对仅含 at 的真实跟进口径一致');
const performancePage = require('./fixtures/legacy-admin/owner-performance').build({...snapshot,staff:[],goals:{}});
assert.equal(performancePage.targets.find(row => row.label === '有效客户').value, '4', '实际绩效页面也沿用相同有效客户口径');

const oldSales = Home.rows('metric', snapshot, { metric:'sales' });
assert.equal(oldSales.reduce((sum, row) => sum + row.amount, 0), 2000000, '旧入口继续按整数分返回设计委托金额');
assert.equal(Home.rows('metric', snapshot, { metric:'income' })[0].amount, 100000, '旧回款入口金额不丢失');
assert.equal(Home.rows('metric', snapshot, { metric:'effective' }).length, 4, '仅含 at 的真实跟进也进入历史有效客户入口');

assert.deepEqual(Home.rows('appointments', snapshot).map(row => row.name), ['明确到店客户', '到店下一步客户']);
assert.equal(home.business.appointments.value, 2, '普通电话预约不能算预约到店');
assert(home.business.notes.every(row => row.key && row.url.includes('view=stat')));

assert.deepEqual(Home.todoRows(snapshot, 'handover').map(row => row.key), [
  'handover:work-marked', 'handover:work-left', 'handover:work-legacy-content', 'handover:work-legacy-follow'
]);
assert(!Home.todoRows(snapshot, 'handover').some(row => row.key.includes('engineering-work')), '业务转工程不能制造责任交接审批');
assert(!Home.todoRows(snapshot, 'handover').some(row => row.key.includes('legacy-engineering')), '无类型的工程移交也不能制造责任交接审批');

const transferred = clone(snapshot);
transferred.state.work.find(row => row.id === 'work-left').owner = 'sales-b';
transferred.state.work.find(row => row.id === 'work-marked').owner = 'sales-b';
transferred.state.work.find(row => row.id === 'work-legacy-content').owner = 'sales-b';
transferred.state.work.find(row => row.id === 'work-legacy-follow').owner = 'sales-b';
transferred.state.transfers = [
  { id:'transfer-left', workId:'work-left', from:'left', to:'sales-b', effectiveAt:'2026-10-23T09:00:00.000Z' },
  { id:'transfer-marked', workId:'work-marked', from:'sales-a', to:'sales-b', effectiveAt:'2026-10-23T09:01:00.000Z' },
  { id:'transfer-content', workId:'work-legacy-content', from:'left', to:'sales-b', effectiveAt:'2026-10-23T09:02:00.000Z' },
  { id:'transfer-follow', workId:'work-legacy-follow', from:'left', to:'sales-b', effectiveAt:'2026-10-23T09:03:00.000Z' }
];
assert.equal(Home.todoRows(transferred, 'handover').length, 0, '转交到在岗负责人后交接关闭');

const blankBankDate = clone(snapshot);
blankBankDate.state.period = blankBankDate.period;
blankBankDate.state.receipts = [{id:'empty-bank-date',customer:'历史日期原单',name:'工程到账',amount:123400,status:'已核算',bankDate:'',date:'2026-10-18'}];
assert.equal(require('../miniprogram/utils/finance-source').summary(blankBankDate.state).income,123400);
assert.equal(Home.rows('metric',blankBankDate,{metric:'income'}).length,1);
const blankBankRows = Home.statRows(blankBankDate,'income');
assert.equal(blankBankRows.length,1,'回款卡已计入的原单不能因 bankDate 为空而在下钻明细中丢失');
assert.equal(blankBankRows[0].key,'income:empty-bank-date');
assert.equal(blankBankRows[0].amount,'¥ 1,234');
assert(blankBankRows[0].status.includes('2026-10-18'));

console.log('PASS 历史夹具（非当前线上验收）：管理首页合并兼容：新版统计、旧入口、预约口径、线索与责任交接');
