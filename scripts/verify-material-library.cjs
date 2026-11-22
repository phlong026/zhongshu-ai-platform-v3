const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const Audio=require('../miniprogram/pages/studio/characters/audio-preview');
const flush=()=>new Promise(r=>setImmediate(r));
const event=id=>({currentTarget:{dataset:{id}}});
function harness({get=async()=>({items:[],total:0}),post=async()=>({}),store=new Map()}={}) {
  let scope='tenant1-org1-user1',generation=1;const calls=[],video=[],toasts=[];const mod={exports:{}};
  const http={token:()=>true,base:()=> 'https://platform.example/admin-api',sessionGeneration:()=>generation,get:(path,body)=>{calls.push({path,body,method:'GET'});return get(path,body);},post:(path,body)=>{calls.push({path,body,method:'POST'});return post(path,body);},put:async(path,body)=>calls.push({path,body,method:'PUT'}),del:async(path)=>calls.push({path,method:'DELETE'})};
  const wx={getStorageSync:k=>store.get(k),setStorageSync:(k,v)=>store.set(k,v),removeStorageSync:k=>store.delete(k),showToast:o=>toasts.push(o.title),createVideoContext:()=>({pause:()=>video.push('pause'),stop:()=>video.push('stop')}),previewImage:o=>o.success({}),getFileSystemManager:()=>({statSync:()=>({size:4}),readFile:o=>o.success({data:Uint8Array.from([255,216,0,0]).buffer})})};
  const apiModule={exports:{}};vm.runInNewContext(fs.readFileSync('miniprogram/api/franchise/content.js','utf8'),{module:apiModule,require:p=>{assert.equal(p,'../http');return http;}});
  vm.runInNewContext(fs.readFileSync('miniprogram/pages/studio/material-library.js','utf8'),{module:mod,wx,setTimeout,clearTimeout,Uint8Array,require:p=>{
    if(p.endsWith('/api/franchise/content'))return apiModule.exports;
    if(p.endsWith('/http'))return http;if(p.endsWith('/studio'))return {scope:()=>scope};if(p.endsWith('/sha256-stream'))return {hashFile:async()=> 'a'.repeat(64)};
    if(p.endsWith('/b-asset-upload'))return {putTicketBytes:async()=>calls.push({method:'UPLOAD'})};
    if(p.endsWith('/b-asset-download'))return {downloadTicketFile:async()=>({tempFilePath:'/local/media'})};
    if(p.endsWith('/audio-preview'))return Audio;throw Error(p);
  }});
  const page={...mod.exports.handlers,data:structuredClone(mod.exports.data),actorScope:scope,actorSessionGeneration:generation,setData(patch){for(const [key,value]of Object.entries(patch)){const parts=key.replace(/\[(\d+)\]/g,'.$1').split('.');let target=this.data;for(const key of parts.slice(0,-1))target=target[key];target[parts.at(-1)]=value;}}};
  return {page,calls,wx,store,video,toasts,change(){scope='tenant2-org2-user2';generation++;},present:mod.exports.present};
}
test('scope and search responses cannot overwrite a newer selection',async()=>{
  const pending=[];const h=harness({get:(path,query)=>new Promise(resolve=>pending.push({query,resolve}))});
  const first=h.page.loadMaterials();h.page.materialScopeChange({currentTarget:{dataset:{scope:'COMPANY'}}});
  assert.equal(pending[1].query.scope,'COMPANY');
  pending[1].resolve({items:[{id:'2',name:'公司音频',mimeType:'audio/mpeg'}],total:1});await flush();
  pending[0].resolve({items:[{id:'1',name:'旧个人数据',mimeType:'audio/mpeg'}],total:1});await first;
  assert.equal(h.page.data.materialItems[0].id,'2');
  const third=h.page.loadMaterials();h.change();pending[2].resolve({items:[{id:'3',name:'离职账号',mimeType:'audio/mpeg'}],total:1});await third;
  assert.notEqual(h.page.data.materialItems[0]?.id,'3');
});
test('filter, keyword and pagination are sent to server; errors never produce sample rows',async()=>{
  let fail=false;const h=harness({get:async(path,q)=>{if(fail)throw Error('列表故障');return {items:[{id:String(q.page),name:'音频',mimeType:'audio/mpeg'}],total:2};}});
  h.page.setData({materialType:'AUDIO',materialQuery:'录音'});await h.page.loadMaterials();await h.page.materialMore();
  assert.deepEqual(Array.from(h.page.data.materialItems,r=>r.id),['1','2']);assert.equal(h.calls[1].body.page,2);assert.equal(h.calls[1].body.keyword,'录音');assert.equal(h.calls[1].body.type,'AUDIO');
  fail=true;await h.page.loadMaterials();assert.equal(h.page.data.materialItems.length,0);assert.equal(h.page.data.materialError,'列表故障');
});
test('completed upload with failed final acknowledgement resumes same ID after page restart',async()=>{
  let fail=true;const store=new Map();const post=async path=>path.endsWith('/upload-tickets')?{assetId:'9223372036854770000',uploadUrl:'https://cos.example/upload'}:path.endsWith('/complete')&&fail?Promise.reject(Error('保存网络失败')):{};
  const h=harness({post,store});await assert.rejects(h.page.materialUploadFile({path:'/photo'},'image','图','PERSONAL',()=>true),/保存网络失败/);
  assert.equal(store.size,1);assert.equal(h.page.data.materialPending,true);fail=false;
  const restarted=harness({post,store});await restarted.page.materialInit();await restarted.page.materialRetryComplete();
  assert.equal(store.size,0);assert.equal(restarted.page.data.materialPending,false);
  assert.equal(restarted.calls.filter(c=>c.path?.endsWith('/upload-tickets')).length,0);
  assert.equal(restarted.calls.filter(c=>c.path?.endsWith('/9223372036854770000/complete')).length,1);
});
test('explicit video close retains list and ignores late media tickets',async()=>{
  let resolve;const h=harness({post:()=>new Promise(r=>resolve=r)});h.page.setData({materialItems:[{id:'1',name:'视频',type:'video'}]});
  const opening=h.page.openMaterial(event('1'));assert.equal(h.page.data.materialPreviewLoading,true);h.page.closeMaterialPreview();
  resolve({downloadUrl:'https://cos.example/video.mp4'});await opening;assert.equal(h.page.data.materialPreview,null);assert.equal(h.page.data.materialPreviewUrl,'');assert.equal(h.page.data.materialItems.length,1);assert.deepEqual(h.video,['stop']);
});
test('manage mode blocks changes to other authors and invokes owner commands',async()=>{
  const h=harness();h.page.setData({materialManage:true,materialItems:[{id:'1',name:'同事素材',canManage:false},{id:'2',name:'我的图',canManage:true}]});
  let menus=0;h.wx.showActionSheet=o=>{menus++;o.success({tapIndex:0});};h.wx.showModal=o=>o.success({confirm:true,content:'新名称'});
  await h.page.openMaterial(event('1'));assert.equal(menus,0);await h.page.openMaterial(event('2'));
  assert.equal(menus,1);assert.equal(h.calls.find(c=>c.method==='PUT').body.name,'新名称');
});
test('storage recovery never crosses the active account scope',async()=>{
  const store=new Map([['zs-material-pending-v1:tenant2-org2-user2',{id:'other',name:'私有素材'}]]);const h=harness({store});await h.page.materialInit();assert.equal(h.page.data.materialPending,false);
});
test('load more does not invalidate delayed first-page covers and updates both pages by ID',async()=>{
  const downloads=new Map();const h=harness({get:async(path,q)=>({items:[{id:String(q.page),name:'图片'+q.page,mimeType:'image/jpeg'}],total:2}),post:path=>new Promise(resolve=>downloads.set(path.split('/').at(-2),resolve))});
  const first=h.page.loadMaterials();await flush();assert.equal(h.page.data.materialLoading,false);
  const second=h.page.materialMore();await flush();assert.equal(h.page.data.materialItems.length,2);
  downloads.get('2')({downloadUrl:'https://cos.example/2.jpg'});await second;
  downloads.get('1')({downloadUrl:'https://cos.example/1.jpg'});await first;
  assert.equal(h.page.data.materialItems[0].coverUrl,'https://cos.example/1.jpg');assert.equal(h.page.data.materialItems[1].coverUrl,'https://cos.example/2.jpg');
});
for(const type of ['audio','image'])test('hidden page ignores delayed '+type+' preview and can reopen after return',async()=>{
  let resolve;const h=harness({post:()=>new Promise(r=>resolve=r)});let previews=0,players=0;
  h.wx.previewImage=o=>{previews++;o.success({});};h.wx.createInnerAudioContext=()=>{players++;throw Error('background playback');};
  h.page.setData({materialItems:[{id:'1',name:'素材',type}]});const pending=h.page.openMaterial(event('1'));h.page.materialHide();
  resolve({downloadUrl:'https://cos.example/media'});await pending;assert.equal(previews,0);assert.equal(players,0);assert.equal(h.page.data.materialPreview,null);
  // A fresh foreground request may open again; old responses never revive themselves.
  if(type==='image'){const next=h.page.openMaterial(event('1'));resolve({downloadUrl:'https://cos.example/media'});await next;assert.equal(previews,1);}
  else {
    h.wx.createInnerAudioContext=()=>{players++;let playing;return {onCanplay(){},onPlay(fn){playing=fn;},onPause(){},onEnded(){},onTimeUpdate(){},onError(){},play(){playing();},destroy(){},seek(){}};};
    const next=h.page.openMaterial(event('1'));resolve({downloadUrl:'https://cos.example/media'});await next;
    assert.equal(players,1);assert.equal(h.page.data.previewAudioPlaying,true);h.page.closeMaterialPreview();
  }
});

test('picker reuses personal/company lists and emits a shared asset ID without requiring management rights',async()=>{
  const selected=[],back=[],id='9007199254740993';
  const h=harness({get:async(path,q)=>({items:[{id,name:q.scope==='COMPANY'?'公司视频':'个人视频',mimeType:'video/mp4',visibility:q.scope,canManage:false}],total:1}),post:async()=>({downloadUrl:'https://cos.example/video.mp4'})});
  h.page.getOpenerEventChannel=()=>({emit:(event,item)=>selected.push({event,item})});h.wx.navigateBack=()=>back.push(true);
  h.page.materialPickerInit('ALL');await h.page.materialInit();
  h.page.materialScopeChange({currentTarget:{dataset:{scope:'COMPANY'}}});await flush();
  await h.page.openMaterial(event(id));
  assert.equal(h.calls.filter(c=>c.method==='GET').at(-1).body.scope,'COMPANY');
  assert.equal(selected[0].event,'materialSelected');assert.equal(selected[0].item.assetId,id);
  assert.equal(selected[0].item.type,'video');assert.equal(selected[0].item.mimeType,'video/mp4');
  assert.equal(selected[0].item.visibility,'COMPANY');assert.equal(selected[0].item.canManage,false);
  assert.equal(back.length,1);assert.equal(h.calls.filter(c=>c.method==='UPLOAD').length,0);
  await h.page.openMaterial(event('private-unlisted'));assert.equal(selected.length,1);
});

test('frame picker only accepts images and rechecks current permission before returning an asset',async()=>{
  let revoked=false;const selected=[];
  const h=harness({get:async(path,q)=>{assert.equal(q.type,'IMAGE');return {items:[{id:'1',name:'图片',mimeType:'image/png',visibility:'PERSONAL'}],total:1};},post:async()=>{if(revoked)throw {code:403,message:'素材已删除或无权访问'};return {downloadUrl:'https://cos.example/frame.png'};}});
  h.page.getOpenerEventChannel=()=>({emit:(event,item)=>selected.push(item)});h.wx.navigateBack=()=>{};
  h.page.materialPickerInit('IMAGE');await h.page.materialInit();
  h.page.materialTypeChange({currentTarget:{dataset:{type:'VIDEO'}}});assert.equal(h.page.data.materialType,'IMAGE');
  h.page.data.materialItems.push({id:'2',name:'视频',mimeType:'video/mp4',type:'video'});
  await h.page.openMaterial(event('2'));assert.equal(selected.length,0);assert.match(h.toasts.at(-1),/图片/);
  revoked=true;await h.page.openMaterial(event('1'));assert.equal(selected.length,0);assert.match(h.toasts.at(-1),/删除|无权/);
  revoked=false;await h.page.openMaterial(event('1'));assert.equal(selected[0].assetId,'1');assert.equal(selected[0].type,'image');
});

test('leaving or switching identity while selecting does not return a late authorized ticket',async()=>{
  for(const leave of ['materialHide','identity']){
    let resolve;const selected=[],h=harness({post:()=>new Promise(r=>resolve=r)});
    h.page.materialPickerInit('ALL');h.page.data.materialItems=[{id:'1',name:'录音',mimeType:'audio/mpeg',type:'audio'}];
    h.page.getOpenerEventChannel=()=>({emit:(event,item)=>selected.push(item)});h.wx.navigateBack=()=>assert.fail('late selection navigated');
    const pending=h.page.openMaterial(event('1'));if(leave==='identity')h.change();else h.page.materialHide();
    resolve({downloadUrl:'https://cos.example/audio.mp3'});await pending;
    assert.equal(selected.length,0);assert.equal(h.toasts.length,0);
  }
});
