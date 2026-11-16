const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
function setup(role='sales',people=[]) {
  let definition;const calls=[];let rows=people;
  const api={personas:{page:async()=>rows,create:async body=>{calls.push(['create',body]);const row={...body,id:'101',canManage:true};rows.push(row);return row;},update:async()=>calls.push(['update'])},clones:{list:async()=>[],quote:async()=>({available:true,credits:5})}};
  const modules={api,auth:{currentApiPost:()=>({roleCode:role})},http:{token:()=>true,sessionGeneration:()=>1,requestGuard:()=>()=>true},studio:{guard:()=>true,scope:()=>role,scopedKey:k=>k},platform:{rowsOf:r=>r,degradeMessage:e=>e.message}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/studio/characters/index.js'),'utf8'),{
    require:p=>p.includes('api/franchise')?modules.api:p.endsWith('/auth')?modules.auth:p.endsWith('/http')?modules.http:p.endsWith('/studio')?modules.studio:modules.platform,
    Page:p=>definition=p,wx:{pageScrollTo(){},getStorageSync(){},showToast(){}},setTimeout,clearTimeout
  });
  const page={...definition,data:structuredClone(definition.data),setData(next){for(const [key,value] of Object.entries(next)){const parts=key.split('.');let obj=this.data;for(const p of parts.slice(0,-1))obj=obj[p];obj[parts.at(-1)]=value;}}};
  page.onLoad();page.onShow();return {page,calls};
}
const event=id=>({currentTarget:{dataset:{id}}});
test('sales can open and save the first personal IP in an empty company library',async()=>{
  const {page,calls}=setup();await page.load();assert.equal(page.data.canCreate,true);
  page.edit(event(''));assert.equal(page.data.view,'edit');page.data.form.name='本人IP';await page.save();
  assert.equal(calls[0][0],'create');assert.equal(page.data.digitalPersona.id,'101');assert.equal(page.data.canManage,true);
  page.openDigital({currentTarget:{dataset:{id:'101',kind:'AVATAR'}}});assert.equal(page.data.view,'avatar');
});
test('sales cannot edit or start cloning a colleague, and clone entry starts a new personal IP',async()=>{
  const {page,calls}=setup('sales',[{id:'202',name:'同事',enabled:true,canManage:false}]);await page.load();
  page.edit(event('202'));assert.equal(page.data.view,'home');
  page.detail(event('202'));assert.equal(page.data.canManage,false);
  page.openDigital({currentTarget:{dataset:{id:'202',kind:'VOICE'}}});assert.equal(page.data.view,'resources');
  page.showView('home');page.openCloneEntry({currentTarget:{dataset:{kind:'VOICE'}}});assert.equal(page.data.view,'edit');assert.equal(page.data.editId,'');
  assert.equal(calls.length,0);
});
test('company managers retain access, unrelated roles cannot add IPs',async()=>{
  for(const role of ['boss','owner','sales-lead']){const {page}=setup(role,[{id:'202',name:'同事',enabled:true}]);await page.load();page.edit(event('202'));assert.equal(page.data.view,'edit');}
  const {page}=setup('finance');await page.load();assert.equal(page.data.canCreate,false);page.edit(event(''));assert.equal(page.data.view,'home');
});
