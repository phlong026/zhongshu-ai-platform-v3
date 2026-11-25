const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const root=path.join(__dirname,'../miniprogram');
function harness(role='boss',orgType='store'){
 let spec,gen=1,postId='original-post';const urls=[],notices=[];
 const http={token:()=> 'TEST ONLY',currentPostId:()=>postId,sessionGeneration:()=>gen,requestGuard:()=>()=>true,sessionGuard:()=>{const g=gen;return()=>g===gen;}};
 const post={roleCode:role,orgType,postId};
 vm.runInNewContext(fs.readFileSync(path.join(root,'pages/build/record/index.js'),'utf8'),{
  Page:p=>{spec=p;},wx:{showToast:o=>notices.push(o)},getApp:()=>({globalData:{}}),
  require:n=>n==='../payment-plan'?{read:async()=>undefined,apply:()=>{}}:n.endsWith('/platform-engineering')?{online:()=>true,loadProject:async()=>({}),recordView:()=>({collections:[{id:'receipt-1',fileAssetId:'voucher-1',amountText:'¥ 100'}]}),errorText:e=>String(e)}:n.endsWith('/page-navigation')?{open:u=>urls.push(u)}:n.endsWith('/api/http')?http:n.endsWith('/api/auth')?{currentApiPost:()=>post}:n.endsWith('/view-container')?{makeFormHandlers:()=>({})}:n.endsWith('/organization')?{has:()=>false}:n.endsWith('/account')?{localDate:()=> '2026-11-17'}:{},
 });
 const page={...spec,id:'9007199254740999',__identity:'original-post:1',data:{...spec.data,p:{id:'9007199254740999',projectId:'9007199254740999'},view:'payments'},setData(p){Object.assign(this.data,p);}};
 // Dispatch the event through the actual rendered button's binding, not a fabricated route.
 const xml=fs.readFileSync(path.join(root,'pages/build/record/index.wxml'),'utf8');
 const match=xml.match(/<button[^>]*data-view="payables"[^>]*bindtap="([^"]+)"[^>]*>[^<]*材料及费用付款记录[^<]*<\/button>/);
 assert.ok(match,'existing visible project payment button remains bound');
 return{page,urls,notices,click:(view='payables')=>page[match[1]]({currentTarget:{dataset:{view}}}),switchPost(){postId='new-post';gen++;},relogin(){gen++;}};
}
for(const role of ['boss','owner','finance','finance-lead'])test(role+' actual payment-button binding retains the current engineering project in finance ledger route',()=>{
 const h=harness(role);h.click();assert.deepEqual(h.urls,['/pages/admin/finance-desk/index?view=project&id=9007199254740999']);
});
test('old session/post click and mismatched project never open a ledger',()=>{
 for(const mutate of [h=>h.switchPost(),h=>h.relogin(),h=>{h.page.id='other-project';},h=>{h.page.data.p.projectId='different';},h=>{h.page.__inactive=true;}]){const h=harness();mutate(h);h.click();assert.equal(h.urls.length,0);assert.ok(h.notices.length);}
});
test('headquarters role does not gain a store project ledger shortcut',()=>{
 const h=harness('boss','headquarters');h.click();assert.ok(h.urls.every(u=>!u.includes('/finance-desk/')));
});

test('engineer payment shortcut stays within authorized project material progress',()=>{const h=harness('engineer');h.click();assert.deepEqual(h.urls,['/pages/build/record/index?id=9007199254740999&view=materials']);});
test('cost shortcut preserves project and role permissions',()=>{for(const role of ['boss','finance','finance-lead','engineer']){const h=harness(role);h.click('costs');assert.deepEqual(h.urls,[role==='engineer'?'/pages/build/record/index?id=9007199254740999&view=settlement':'/pages/admin/finance-desk/index?view=project&id=9007199254740999']);}});
test('loaded receipt records retain amounts and only finance roles expose financial vouchers and the current project ledger',async()=>{for(const role of ['boss','owner','finance','finance-lead','engineer','project-manager']){const h=harness(role);await h.page.refreshOnline();assert.equal(h.page.data.error,'');const row=h.page.data.collections[0],allowed=['boss','owner','finance','finance-lead'].includes(role);assert.equal(row.amountText,'¥ 100');assert.equal(row.canPreviewFile,allowed);assert.equal(row.url,allowed?'/pages/admin/finance-desk/index?view=project&id=9007199254740999':'');}});

function managerHarness({eligible=true,candidates,send}={}) {
 let session=0;
 const calls=[], modals=[], module={exports:{}};
 const http={currentPostId:()=> 'original',sessionGeneration:()=>session,sessionGuard:()=>{const captured=session;return ()=>captured===session;}};
 const projects={managerCandidates:id=>{calls.push(['read',id]);return candidates?.()||Promise.resolve([{id:'9007199254741111',name:'张工（编号 1111）'},{id:'9007199254742222',name:'张工（编号 2222）'}]);},
  changeManager:(...args)=>{calls.push(['send',...args]);return send?.(...args)||Promise.resolve({id:args[0]});}};
 vm.runInNewContext(fs.readFileSync(path.join(root,'pages/build/record/manager-transfer.js'),'utf8'),{module,
  wx:{showModal:o=>modals.push(o),showToast(){}},
  require:n=>n.endsWith('/http')?http:n.endsWith('/engineering')?{projects}:{commandKey:()=> 'original-confirmed-command'}});
 const page={...module.exports.handlers,__identity:'original:0',id:'9007199254740999',data:{view:'project',busy:false,canTransferManager:eligible},
  setData(patch){Object.assign(this.data,patch);},refresh(){}};
 const wxml=fs.readFileSync(path.join(root,'pages/build/record/index.wxml'),'utf8');
 for(const handler of ['openManagerTransfer','pickManagerTransfer','confirmManagerTransfer','retryManagerTransfer'])assert(wxml.includes('="'+handler+'"'));
 return {page,calls,modals,switchIdentity(){session++;module.exports.clear(page);}};
}
test('转交不默认选择同名工程师，取消不写入，确认按原长编号办理',async()=>{
 const h=managerHarness();
 await h.page.openManagerTransfer();
 h.page.confirmManagerTransfer();assert.equal(h.modals.length,0);
 h.page.pickManagerTransfer({detail:{value:'1'}});
 h.page.confirmManagerTransfer();h.modals[0].success({confirm:false});
 assert.equal(h.calls.length,1);
 h.page.confirmManagerTransfer();h.modals[1].success({confirm:true});
 await new Promise(resolve=>setImmediate(resolve));
 assert.deepEqual(h.calls[1],['send','9007199254740999','9007199254742222','','original-confirmed-command']);
 assert.equal(h.page.data.canTransferManager,false);
});
test('弱网保留同一次转交供核对，重试时不能改人或新发请求',async()=>{
 let attempts=0;const h=managerHarness({send:async id=>{if(!attempts++)throw {code:-1};return {id};}});
 await h.page.openManagerTransfer();h.page.pickManagerTransfer({detail:{value:'0'}});
 h.page.confirmManagerTransfer();h.modals[0].success({confirm:true});await new Promise(resolve=>setImmediate(resolve));
 assert.equal(h.page.data.managerTransferPending,true);
 h.page.pickManagerTransfer({detail:{value:'1'}});h.page.confirmManagerTransfer();assert.equal(h.modals.length,1);
 await h.page.retryManagerTransfer();assert.deepEqual(h.calls[1],h.calls[2]);assert.equal(h.page.data.managerTransferPending,false);
});
test('换岗或非原负责人不提交转交',async()=>{
 const denied=managerHarness({eligible:false});await denied.page.openManagerTransfer();assert.equal(denied.calls.length,0);
 const stale=managerHarness();await stale.page.openManagerTransfer();stale.page.pickManagerTransfer({detail:{value:'0'}});
 stale.page.confirmManagerTransfer();stale.switchIdentity();stale.modals[0].success({confirm:true});assert.equal(stale.calls.length,1);
});
