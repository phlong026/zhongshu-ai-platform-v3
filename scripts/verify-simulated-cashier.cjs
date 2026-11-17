const test=require('node:test'),assert=require('node:assert/strict');
const make=require('../miniprogram/pages/workflow/commission/payment-flow');
const pending={commissionId:'9007199254740999',number:'SIM-QA-V1',status:'PENDING',amountCents:240000};
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};
function fixture({pay=async()=>{},read=async()=>({...pending,status:'PAID'}),delay=async()=>{}}={}){
 let current=true;const calls=[],keys={};
 const p={...make({http:{sessionGuard:()=>()=>current},commissions:{simulatePay:async(...args)=>{calls.push(args);return pay(...args);},simulatedReceipt:read},paymentView:o=>({...o}),delay}),id:pending.commissionId,view:'pay',action:'commission-simulate-pay',data:{paymentOrder:{...pending},paymentChannel:'WECHAT'},__commission:{fee:{recipient:'验收服务主体'}},setData(patch){Object.assign(this.data,patch);},cmdKey:k=>keys[k]||(keys[k]='same-attempt-key'),dropCmdKey:k=>delete keys[k]};
 return {p,calls,invalidate:()=>current=false};
}
test('opening and cancelling cashier never pays or changes the original pending order',()=>{
 const {p,calls}=fixture();p.openSimulationPayment();assert.equal(p.data.simulationPhase,'confirm');assert.equal(p.data.paymentMerchant,'验收服务主体');p.cancelSimulationPayment();assert.equal(p.data.simulationPhase,'');assert.equal(p.data.paymentOrder.status,'PENDING');assert.equal(calls.length,0);
});
test('confirmation is single flight, freezes channel and waits for server receipt and visible processing',async()=>{
 const network=deferred(),visible=deferred(),{p,calls}=fixture({pay:()=>network.promise,delay:()=>visible.promise});p.openSimulationPayment();const first=p.confirmSimulationPayment();await p.confirmSimulationPayment();p.data.paymentChannel='ALIPAY';assert.equal(p.data.busy,true);assert.equal(p.data.simulationPhase,'processing');assert.equal(calls.length,1);assert.deepEqual(calls[0][1],{channel:'WECHAT',confirmed:true});network.resolve();await new Promise(setImmediate);assert.equal(p.data.paymentOrder.status,'PENDING');visible.resolve();await first;assert.equal(p.data.paymentOrder.status,'PAID');assert.equal(p.data.busy,false);assert.equal(p.data.simulationPhase,'');assert.equal(p.action,'');
});
test('lost payment response is reconciled as success only when server receipt is paid',async()=>{
 const {p}=fixture({pay:async()=>{throw Error('网络中断');}});p.openSimulationPayment();await p.confirmSimulationPayment();assert.equal(p.data.paymentOrder.status,'PAID');assert.equal(p.data.simulationUncertain,false);
});
test('rejected payment with a pending receipt shows failure and retries the same order and key',async()=>{
 const {p,calls}=fixture({pay:async()=>{throw Error('模拟服务暂不可用');},read:async()=>pending});p.openSimulationPayment();await p.confirmSimulationPayment();assert.equal(p.data.simulationPhase,'failed');assert.equal(p.data.paymentOrder.status,'PENDING');p.retrySimulationPayment();assert.equal(p.data.simulationPhase,'confirm');await p.confirmSimulationPayment();assert.equal(calls[0][0],calls[1][0]);assert.equal(calls[0][2],calls[1][2]);
});
test('unknown payment outcome cannot issue another payment until an authoritative pending read',async()=>{
 let unavailable=true;const {p,calls}=fixture({pay:async()=>{throw Error('timeout');},read:async()=>{if(unavailable)throw Error('unavailable');return pending;}});p.openSimulationPayment();await p.confirmSimulationPayment();assert.equal(p.data.simulationPhase,'unknown');p.cancelSimulationPayment();assert.equal(p.data.simulationUncertain,true);await p.openSimulationPayment();assert.equal(calls.length,1);assert.equal(p.data.simulationPhase,'unknown');unavailable=false;await p.retrySimulationPayment();assert.equal(p.data.simulationPhase,'confirm');assert.equal(p.data.simulationUncertain,false);assert.equal(calls.length,1);
});
test('late payment response never updates a changed session or an unloaded page',async()=>{
 for(const mode of ['session','unloaded']){const wait=deferred(),{p,invalidate}=fixture({pay:()=>wait.promise});p.openSimulationPayment();const task=p.confirmSimulationPayment();if(mode==='session')invalidate();else p.disposed=true;wait.resolve();await task;assert.equal(p.data.paymentOrder.status,'PENDING');assert.equal(p.action,'commission-simulate-pay');}
});
test('a paid receipt for a different commission cannot mark this order paid',async()=>{
 const {p}=fixture({read:async()=>({...pending,commissionId:'other',status:'PAID'})});p.openSimulationPayment();await p.confirmSimulationPayment();assert.equal(p.data.paymentOrder.status,'PENDING');assert.equal(p.data.simulationPhase,'unknown');
});
