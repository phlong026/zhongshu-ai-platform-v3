const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const make=require('../miniprogram/pages/workflow/commission/payment-flow');
test('all stale cashier handlers reject without paying, reading or changing historical status',async()=>{
 const calls=[];const p={...make({commissions:{simulatePay:()=>calls.push('write'),simulatedReceipt:()=>calls.push('read')}}),action:'commission-simulate-pay',data:{paymentOrder:{status:'PENDING'}},setData(patch){Object.assign(this.data,patch);}};
 for(const name of ['openSimulationPayment','confirmSimulationPayment','retrySimulationPayment','checkSimulationPayment']){await p[name]();assert.equal(p.data.paymentOrder.status,'PENDING');assert.equal(p.data.primary,'');assert.match(p.data.paymentNotice,/外部付款/);}
 assert.deepEqual(calls,[]);
});
test('the transport cannot send the retired physical payment request but preserves history lookup',async()=>{
 let exports;const calls=[];
 const box={module:{exports:{}},require:id=>id.endsWith('/http')?{post:(...x)=>calls.push(['POST',...x]),get:(...x)=>{calls.push(['GET',...x]);return Promise.resolve({status:'PAID'});}}:{},console};
 vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/api/franchise/commission'),'utf8'),box);exports=box.module.exports;
 await assert.rejects(exports.commissions.simulatePay('7',{},'old-key'),/外部付款/);
 await exports.commissions.simulatedReceipt('7');assert.equal(calls.length,1);assert.equal(calls[0][0],'GET');assert.match(calls[0][1],/simulated-payment-receipt$/);
});
test('active commission templates contain no cashier or simulation opt-in',()=>{
 const view=fs.readFileSync('miniprogram/pages/workflow/commission/index.wxml','utf8');assert.doesNotMatch(view,/simulated-cashier|data-key="testPayment"/);
 const history=fs.readFileSync('miniprogram/pages/workflow/commission/simulated-payment.wxml','utf8');assert.match(history,/不计入实际收支/);assert.doesNotMatch(history,/choosePayment|confirmSimulationPayment/);
});
