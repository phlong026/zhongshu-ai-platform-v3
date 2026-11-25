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
