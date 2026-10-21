const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const directory=path.resolve('qa/engineering-finance-audit-20261021/native-entry-links');process.env.FRANCHISE_NATIVE_OUT=directory;process.env.FRANCHISE_WS=process.env.FRANCHISE_WS||'ws://127.0.0.1:9795';
(async()=>{
 const r=await require('./franchise-native-runtime.cjs').connect('engineering-entry-links'),f=JSON.parse(fs.readFileSync('qa/engineering-finance-audit-20261021/engineering-native-fixtures.json')),fingerprint=require('./engineering-audit-fingerprint.cjs')(),checks=[];
 const cases=[['开工准备继续登记开工','prestart','prepare','.footer .secondary','pages/build/index','start'],['查看业务端图纸','working','documents','.primary-action','pages/workflow/index','documents'],['收款查看原记录','working','collection','.primary-action','pages/build/index','collection-history'],['工程查看财务原单进度','working','collection-review','.primary-action','pages/admin/index','receipt'],['查看业务专业审核','working','review','.primary-action','pages/workflow/index','drawing-review'],['归档查看交付回执','archived','archive','.primary-action','pages/build/index','delivery-record']];
 try{
  for(const [name,stage,view,selector,route,target]of cases){
   await r.replace(f[stage]);await r.open('/pages/build/index?view='+view+'&id='+f.engineeringId);const e=(await r.data()).p;
   if(view==='documents')assert.equal((await r.data()).primary,'查看业务端图纸');r.mp.pageMap.clear();let button;await r.until(async()=>{button=await(await r.mp.currentPage()).$(selector);return !!button;},name+'原生按钮');await button.tap();
   await r.until(async()=>{const p=await r.current();return p.route===route&&p.data.view===target;},name+'真实目标');await r.sleep(450);const p=await r.current();assert(!p.data.error&&!p.data.missing&&p.data.view!=='access',p.data.error||name);
   if(route==='pages/workflow/index'){assert.equal(p.data.customer.id,e.sourceCustomerId);if(view==='documents')assert.equal(p.data.canWriteView,false);}
   if(route==='pages/admin/index')assert((await r.mp.evaluate(()=>getCurrentPages().at(-1).id)).startsWith('engineering-receipt:'+e.id+':'));
   if(route==='pages/build/index')assert.equal(p.data.p.id,e.id);await r.capture(view+'-entry-target');checks.push({name,result:'passed',route,view:target,via:'Element.tap'});console.log('PASS native entry '+name);
  }
  assert.equal(r.errors.length,0,JSON.stringify(r.errors));fs.writeFileSync(path.join(directory,'results.json'),JSON.stringify({result:'passed',checks,fingerprint,exceptions:r.errors},null,2));
 }catch(error){fs.writeFileSync(path.join(directory,'results.json'),JSON.stringify({result:'failed',error:error.stack,checks,fingerprint,current:await r.current()},null,2));throw error;}finally{await r.restore();}
})().then(()=>process.exit(0)).catch(e=>{console.error(e.stack);process.exit(1);});
