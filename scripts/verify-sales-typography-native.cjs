const fs=require('fs'),path=require('path'),assert=require('assert/strict');
process.env.FRANCHISE_WS=process.env.FRANCHISE_WS||'ws://127.0.0.1:9430';
process.env.FRANCHISE_NATIVE_OUT=path.resolve('qa/business-followups-20261019/final-native');
const {connect}=require('./franchise-native-runtime.cjs');
(async()=>{const r=await connect('typography'),styles={},metrics={},screens=[];
 async function measure(name,selector){r.mp.pageMap.clear();const el=await(await r.mp.currentPage()).$(selector);assert(el,name+' visible title');const value={};for(const key of ['font-family','font-size','font-weight','line-height','color'])value[key]=await el.style(key);styles[name]=value;for(const key of ['font-family','font-size','font-weight','line-height'])if(styles.home)assert.equal(value[key],styles.home[key],name+' '+key);}
 async function capture(name){screens.push(await r.capture(name));}
 try{
  await r.replace([]);await r.login('sales-dual','sales');let customerId;
  for(const[area,route]of [['home','business'],['customers','customers'],['creation','creation'],['design','design']]){
   await r.open('/pages/'+route+'/index','.sales-page');await r.sleep(450);
   if(area==='customers')customerId=(await r.data()).customers[0].id;
   if(area==='design')await r.until(async()=>(await r.data()).imagesReady,'case images');
   await measure(area,'.section-title');
   if(area==='home'||area==='customers'){r.mp.pageMap.clear();const el=await(await r.mp.currentPage()).$('.sales-stat-value');metrics[area]={size:await el.style('font-size'),lineHeight:await el.style('line-height'),weight:await el.style('font-weight')};if(area==='customers')assert.deepEqual(metrics.customers,metrics.home,'Shared metric typography');}
   if(area==='home'){const[first,second,action]=await r.rects(['.sales-task','.sales-task-compact','.sales-task-compact .sales-task-action']);assert(second.height<first.height,'Secondary task is compact');assert(action.left>=second.left&&action.right<=second.right+1,'Secondary action stays in its row');}
   await capture(area+'-top');await r.mp.pageScrollTo(10000);await r.sleep(150);await capture(area+'-bottom');
  }
  for(const[name,url,selector]of [
   ['customer-detail','/pages/workflow/index?view=customer&id='+customerId,'.heading'],
   ['budget','/pages/workflow/index?view=budget&id='+customerId,'.heading'],
   ['studio','/pages/studio/index?view=remix','.studio-heading'],
   ['video','/pages/ai-video/index','.creation-heading-title'],
   ['growth','/pages/growth/index?view=home','.section-title']
  ]){await r.open(url,selector);await measure(name,selector);await capture(name);}
  await r.open('/pages/studio/index?view=works','.saved-work');const task=(await r.data()).works[0].taskId;await r.event({selector:'.saved-work',dataset:{task}},'openWork');await r.until(async()=>(await r.data()).view==='result','video result');await r.event('.result-actions .studio-primary','publishResult');await r.until(async()=>(await r.data()).view==='publish','publish settings');await r.event('.publish-submit','submitPublish');await r.until(async()=>(await r.data()).view==='receipt','link receipt');await r.sleep(200);await measure('receipt','.studio-heading');const[url,footer,info]=await r.rects(['.receipt-url','.page-actions','.receipt-work']);assert(url.top>=0&&url.bottom<footer.top,'Required URL is fully above the fixed action');assert(url.top<info.top,'Required URL appears before automatic information');await capture('receipt');const publicationId=(await r.data()).recordId;assert((await r.data()).publishedUrlInput.startsWith('https://example.com/published/'));await r.event('.receipt-url','inputPublishedUrl',{value:''});await r.event('.receipt-save','savePublicationLink');assert((await r.data()).receiptError);await r.event('.receipt-url','inputPublishedUrl',{value:'https://example.com/published/typography-preview'});await r.until(async()=>(await r.data()).publishedUrlInput==='https://example.com/published/typography-preview','URL input applied');await r.event('.receipt-save','savePublicationLink');await r.until(async()=>{const d=await r.data();if(d.receiptError)throw Error(d.receiptError);return (await r.current()).route==='pages/business/index';},'publication saved and returned home',12000);await r.open('/pages/studio/index?view=detail&record='+publicationId,'.detail-home');assert.equal((await r.data()).detail.collection.status,'pending');await capture('publication-detail-top');await r.mp.pageScrollTo(10000);await r.sleep(150);await capture('publication-detail-bottom');
  assert.equal(r.errors.length,0);fs.writeFileSync(path.join(r.directory,'results.json'),JSON.stringify({result:'passed',system:await r.mp.systemInfo(),styles,metrics,screens,exceptions:r.errors},null,2));console.log(JSON.stringify({result:'passed',styles,metrics,screens:screens.length}));
 }catch(error){fs.writeFileSync(path.join(r.directory,'failed.json'),JSON.stringify({error:error.stack,current:await r.current()},null,2));await capture('failed');throw error;}finally{await r.restore();}
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
