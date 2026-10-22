const assert = require('node:assert/strict');
(async () => {
  await require('./verify-owner-workbench.cjs').fixture();
  const P = require('../miniprogram/utils/performance');
  const business = { customers:[{id:'current-design',projectId:'design-project',companyId:'store',designOrganizationId:'hq',name:'设计测试',request:{version:2},businessFlow:{type:'sales-offline-v1',completed:{actorId:'original-admin',date:'2026-10-20',at:'2026-10-20T02:00:00Z',requestVersion:2}}}] };
  wx.setStorageSync('zhongshu-business-v3',business);
  const state = { companyId:'hq',period:'2026-10',receipts:[],payments:[] };
  let rows = P.sourceRows(state).filter(row=>row.kind==='design');
  assert.equal(rows.length,1,'当前线下审核完成流程必须纳入设计事实');
  assert.equal(rows[0].person,'original-admin');
  assert.equal(rows[0].onTime,null,'没有原约定日期不能猜按期或逾期');
  business.customers[0].request.version=3;
  wx.setStorageSync('zhongshu-business-v3',business);
  assert.equal(P.sourceRows(state).filter(row=>row.kind==='design').length,0,'旧需求版本完成不能冒充当前版本');
  console.log('PASS 当前V3设计完成按发生时行政、需求版本与真实期限派生');
})().catch(error=>{console.error(error.stack);process.exitCode=1;});
