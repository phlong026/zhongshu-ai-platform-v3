const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
test('the current page contains no retired cashier handlers and cannot construct a simulation command',()=>{
 let page;
 vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/workflow/commission/index'),'utf8'),{
  Page:value=>page=value,require:id=>id.endsWith('/view-container')?{makeFormHandlers:()=>({})}:{}
 });
 for(const name of ['openSimulationPayment','confirmSimulationPayment','retrySimulationPayment','checkSimulationPayment'])assert.equal(page[name],undefined);
 assert.throws(()=>page.commandPayload('commission-simulate-pay'),/旧设计办理已停用/);
});
test('the transport cannot send the retired physical payment request but preserves history lookup',async()=>{
 let exports;const calls=[];
 const box={module:{exports:{}},require:id=>id.endsWith('/http')?{post:(...x)=>calls.push(['POST',...x]),get:(...x)=>{calls.push(['GET',...x]);return Promise.resolve({status:'PAID'});}}:{},console};
 vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/api/franchise/commission'),'utf8'),box);exports=box.module.exports;
 assert.equal(exports.commissions.simulatePay,undefined);
 assert.equal(exports.commissions.feeDecide,undefined);
 assert.equal(exports.policies.save,undefined);
 assert.equal(exports.pricing.save,undefined);
 await exports.commissions.simulatedReceipt('7');assert.equal(calls.length,1);assert.equal(calls[0][0],'GET');assert.match(calls[0][1],/simulated-payment-receipt$/);
});
test('active commission templates contain no cashier or simulation opt-in',()=>{
 const view=fs.readFileSync('miniprogram/pages/workflow/commission/index.wxml','utf8');assert.doesNotMatch(view,/simulated-cashier|data-key="testPayment"/);
 const history=fs.readFileSync('miniprogram/pages/workflow/commission/simulated-payment.wxml','utf8');assert.match(history,/不计入实际收支/);assert.doesNotMatch(history,/choosePayment|confirmSimulationPayment/);
});
