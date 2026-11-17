const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const PF = require('../miniprogram/utils/platform-finance');
test('cash net includes both refund directions and excludes personal funding', () => {
 const result=PF.metrics([{status:'已登记',receivedDate:'2026-11-01',amountCents:100000}],
  [{status:'已核实',payDate:'2026-11-01',amountCents:60000},{status:'已核实',payDate:'2026-11-01',amountCents:240000,fundingType:'EMPLOYEE'}],
  [{status:'已核实',date:'2026-11-02',amountCents:5000,refundDirection:'client'},{status:'已核实',date:'2026-11-02',amountCents:2000,refundDirection:'expense'},{status:'已核实',date:'2026-11-02',amountCents:1000,refundDirection:'expense',fundingType:'EMPLOYEE'}],'2026-11');
 assert.equal(result.net,'370.00');assert.equal(result.paid,'600.00');
 assert.equal(PF.metrics([],[{status:'已核实',payDate:'2026-11-01',amountCents:300}],[],'2026-11').net,'-3.00');
});
test('refund to employee reduces reimbursement eligibility without duplicating vendor payable',()=>{
 const rows=PF.payableProgress([{id:'2',sourceType:'reimbursement',sourceRef:'10',totalCents:240000,status:'进行中'}],
 [{id:'10',payableId:'1',status:'已核实',amountCents:240000,fundingType:'EMPLOYEE'},{id:'11',payableId:'2',status:'已核实',amountCents:100000}],
 [{id:'12',paymentId:'10',amountCents:50000,refundDirection:'expense',status:'已核实',fundingType:'EMPLOYEE'}]);
 assert.equal(rows[0].totalCents,190000);assert.equal(rows[0].remainingCents,90000);
});
test('returning an unallocated overpayment does not reopen the settled supplier balance',()=>{
 const row=PF.payableProgress([{id:'q',totalCents:800000,status:'进行中'}],[{id:'p',payableId:'q',amountCents:1000000,pendingLinkCents:200000,status:'已核实'}],[{id:'f',paymentId:'p',amountCents:200000,status:'已核实',refundDirection:'expense'}])[0];
 assert.equal(row.paidCents,800000);assert.equal(row.remainingCents,0);
});
test('the selected month survives loading and the picker reloads the actual selected value', async()=>{
 let spec;const api={receipts:async()=>[],payments:async()=>[],refunds:async()=>[],payables:async()=>[],bankFlows:async()=>[],reconciliation:async month=>({month})};
 vm.runInNewContext(fs.readFileSync('miniprogram/pages/admin/finance-desk/index.js','utf8'),{Page:p=>spec=p,require:n=>n.endsWith('/finance-workbench-view')?require('../miniprogram/utils/finance-workbench-view'):n.endsWith('/api/finance')?api:n.endsWith('/api/auth')?{currentApiPost:()=>({roleCode:'finance'})}:n.endsWith('/api/http')?{requestGuard:()=>()=>true,get:async()=>({})}:n.endsWith('/platform-finance')?PF:n.endsWith('/finance-desk')?{url:()=>''}:{},Date,Promise,wx:{}});
 const p={...spec,data:{...spec.data,view:'desk',selectedMonth:'2026-10'},setData(patch){for(const [k,v]of Object.entries(patch)){const parts=k.split('.');let d=this.data;while(parts.length>1){const f=parts.shift();d=d[f]||(d[f]={});}d[parts[0]]=v;}}};
 p.loadPlatformFinance();await new Promise(r=>setImmediate(r));assert.equal(p.data.platformFinance.month,'2026-10');
 p.month({detail:{value:'2026-09'}});await new Promise(r=>setImmediate(r));assert.equal(p.data.platformFinance.month,'2026-09');
});
test('home keeps confirmed module order and a single month selector',()=>{
 const s=fs.readFileSync('miniprogram/pages/admin/finance-desk/finance-home.wxml','utf8');
 const markers=['fin-home-intro','fin-home-summary','今日待办','常用快捷办理','正在办理'].map(x=>s.indexOf(x));
 assert.ok(markers.every((n,i)=>n>=0&&(!i||n>markers[i-1])));assert.ok(!s.includes('平台账簿'));assert.ok(!s.includes('本月'));assert.equal((s.match(/<month-select /g)||[]).length,1);assert.ok(s.includes('bind:change="financeMonth"'));
 assert.equal(PF.shortcuts(false)[2].label,'报销办理');
});
test('manual entry retries reuse command and uploaded voucher without creating another fact',async()=>{
 const source=fs.readFileSync('miniprogram/pages/admin/finance-desk/workbench.js','utf8'),module={exports:{}};
 let attempt=0,uploads=0;const calls=[];
 const http={sessionGuard:()=>()=>true,post:async(url,body,headers)=>{calls.push({url,body,headers});if(++attempt===1)throw Error('network response lost');},requestGuard:()=>()=>true};
 vm.runInNewContext(source,{module,require:n=>n.endsWith('/api/http')?http:n.endsWith('/platform-finance')?PF:n.endsWith('/account')?{localDate:()=> '2026-11-17'}:{},wx:{showToast(){}},Promise,Number,String});
 const page={data:{wbForm:{type:'expense',scope:'company',name:'现场运输',category:'运输',amount:'860',date:'2026-11-17',accountName:'公司银行',counterparty:'运输商',voucherPath:'/test/voucher.png'}},setData(patch){for(const[k,v]of Object.entries(patch)){const parts=k.split('.');let d=this.data;while(parts.length>1){const k=parts.shift();d=d[k]||(d[k]={});}d[parts[0]]=v;}},options:{},loadPlatformFinance(){}};
 module.exports(page,{idemKey:()=> 'fixed-test-key',uploadVoucher:async()=>{uploads++;return '9007199254740999';}});page.wbKey='fixed-test-key';page.wbGeneration=1;
 page.loadWorkbench=()=>{};await page.submitWorkbench();assert.ok(page.data.wbForm);await page.submitWorkbench();
 assert.equal(uploads,1);assert.equal(calls.length,2);assert.deepEqual(calls[0],calls[1]);assert.equal(page.data.wbForm,null);
});
