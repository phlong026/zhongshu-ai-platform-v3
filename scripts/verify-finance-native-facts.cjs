// Legacy admin fixture checks preserve historical behavior; they do not prove the current online workflow.
// Read the stored outcome of actual native form submissions; never perform them here.
const fs=require('fs'),assert=require('assert/strict');
(async()=>{
 await require('./role-page-runtime.cjs').connect();
 const rows=JSON.parse(fs.readFileSync('qa/finance-implementation-20261021/menus-native/final-state.json','utf8')),fixture=require('../qa/finance-implementation-20261021/menu-fixtures.json');
 for(const[k,v]of rows)wx.setStorageSync(k,v);
 wx.setStorageSync('zhongshu-account-v1',fixture.accounts['finance-lead']);
 const M=require('./fixtures/legacy-admin/model'),F=require('../miniprogram/utils/finance-source'),Ops=require('../miniprogram/utils/operations'),s=M.load(),checks=[];
 for(const[transaction,amount]of [['FIN-MENUS-COMPANY-PAID',15000],['FIN-MENUS-PAYROLL-PAID',30000]]){
  const p=s.payments.find(p=>p.transaction===transaction);assert(p,transaction);assert.equal(p.status,'已核实');assert.equal(p.amount,amount);assert.equal(F.paid(s,p.payableId),amount);checks.push({name:transaction,amount,status:p.status});
 }
 const r=s.receipts.find(r=>r.bankTransaction==='FIN-MENUS-BANK-COLLECTION'||r.transaction==='FIN-MENUS-BANK-COLLECTION');assert(r);assert.equal(r.id,fixture.collectionId);assert.equal(r.status,'已核算');assert.equal(r.amount,fixture.collectionAmount);assert(r.reviewerId&&r.financialRegistrarId!==r.reviewerId);checks.push({name:'原工程收款独立核算',amount:r.amount,status:r.status});
 const payroll=require('../miniprogram/utils/payroll').rows(s).find(r=>r.id===fixture.cycleId);assert.equal(payroll.settlement.status,'approved');assert.equal(payroll.amount,30000);assert(s.operations.expenses.some(e=>e.name==='菜单验收公司通讯费'&&e.status==='approved'&&e.amount===15000));checks.push({name:'原公司费用与已归档绩效结算',status:'approved'});
 assert.equal(Ops.overview(s).overhead,57000);checks.push({name:'公司费用及绩效成本汇总',amount:57000});
 const result={result:'passed',scope:'只读核对历史本地微信原生提交记录；使用已退休 admin 夹具，不作为当前线上验收',evidenceScope:'historical-local-model',currentOnlineAcceptance:false,checks};fs.writeFileSync('qa/finance-implementation-20261021/native-final-facts.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
