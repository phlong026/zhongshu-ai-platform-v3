const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const Audio=require('../miniprogram/utils/audio-preview');
function audioHarness(){
 const players=[],patches=[];let valid=true;
 const preview=Audio.create({valid:()=>valid,onChange:p=>patches.push(p),createAudio(){
  const events={},calls=[];const p={events,calls,duration:20,currentTime:0,play(){calls.push('play');events.Play();},pause(){calls.push('pause');events.Pause();},seek(v){calls.push(['seek',v]);},destroy(){calls.push('destroy');}};
  for(const e of ['Canplay','Play','Pause','Ended','TimeUpdate','Error'])p['on'+e]=f=>events[e]=f;
  players.push(p);return p;
 }});return {preview,players,patches,invalidate:()=>valid=false};
}
test('audio preview plays, pauses, seeks and restarts without opening another page',()=>{
 const h=audioHarness();h.preview.toggle('upload','local.mp3');const p=h.players[0];
 p.currentTime=5;p.events.TimeUpdate();assert.equal(h.patches.at(-1).previewAudioProgress,25);
 assert.equal(h.patches.at(-1).previewAudioPosition,'00:05');
 h.preview.toggle('upload','local.mp3');assert.equal(h.patches.at(-1).previewAudioPlaying,false);
 h.preview.seek('upload',75);assert.deepEqual(p.calls.at(-1),['seek',15]);
 p.events.Ended();h.preview.toggle('upload','local.mp3');assert.deepEqual(p.calls.slice(-2),[['seek',0],'play']);
});
test('switching audio destroys the prior player and ignores stale and signed-out callbacks',()=>{
 const h=audioHarness();h.preview.toggle('one','one.mp3');const first=h.players[0];
 h.preview.toggle('two','two.mp3');assert.equal(first.calls.at(-1),'destroy');let n=h.patches.length;
 first.events.Error();first.events.TimeUpdate();assert.equal(h.patches.length,n);
 h.invalidate();h.players[1].events.Error();assert.equal(h.patches.length,n);
 h.preview.destroy();assert.equal(h.players[1].calls.at(-1),'destroy');
});
test('audio errors are recoverable and a retry clears the prior error',()=>{
 const h=audioHarness();h.preview.toggle('upload','one.mp3');h.players[0].events.Error();
 assert.ok(h.patches.at(-1).previewAudioError);h.preview.toggle('upload','one.mp3');
 assert.ok(h.patches.some(p=>p.previewAudioLoading===true&&p.previewAudioError===''));
 assert.equal(h.patches.at(-1).previewAudioPlaying,true);
});
function clonePage(){
 let definition;const rows=[],requests=[],storage=new Map();let reject=false;
 const person={id:'42',name:'李丽',enabled:true,canManage:true};
 const api={clones:{list:async()=>rows,quote:async()=>({available:true,credits:5,consentVersion:'v1'}),create:async(id,body,key)=>{
  requests.push({id,body:structuredClone(body),key});if(reject){reject=false;throw Error('network');}
  const row={...body,id:String(rows.length+1),ready:false,status:'RUNNING'};rows.push(row);return row;
 }}};
 vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/studio/characters/index.js'),'utf8'),{
  Page:p=>definition=p,require:p=>p.endsWith('/api/franchise/content')?api:p.endsWith('/http')?{token:()=>true,sessionGeneration:()=>1,requestGuard:()=>()=>true}:p.endsWith('/studio')?{scope:()=>1,scopedKey:k=>k}:p.endsWith('/auth')?{currentApiPost:()=>({roleCode:'sales'})}:{rowsOf:r=>r,degradeMessage:e=>e.message},
  wx:{getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,structuredClone(v)),removeStorageSync:k=>storage.delete(k),pageScrollTo(){}},setTimeout:()=>1,clearTimeout(){}
 });
 const page={...definition,__scope:1,__generation:1,data:structuredClone(definition.data),setData:p=>Object.assign(page.data,p)};
 Object.assign(page.data,{rows:[person],digitalPeople:[person],digitalPersona:person,canManage:true});
 return {page,rows,requests,rejectNext:()=>reject=true};
}
const event=kind=>({currentTarget:{dataset:{kind}}}),flush=()=>new Promise(r=>setImmediate(r));
test('one persona can create two avatars and two voices without overwriting or reusing submissions',async()=>{
 const h=clonePage();
 for(const kind of ['AVATAR','AVATAR','VOICE','VOICE']){
  h.page.openDigital(event(kind));await flush();
  Object.assign(h.page.data,{cloneUploadId:'upload-'+h.rows.length,cloneConsented:true});
  await h.page.createClone();await flush();assert.equal(h.page.data.view,'resources');
 }
 assert.equal(h.rows.length,4);assert.equal(new Set(h.requests.map(r=>r.key)).size,4);
 assert.equal(new Set(h.rows.map(r=>r.title)).size,4);assert.equal(h.page.data.avatarCount,2);assert.equal(h.page.data.voiceCount,2);
 h.page.changeResourceFilter(event('AVATAR'));assert.equal(h.page.data.visibleCloneRows.length,2);
 h.page.changeResourceFilter(event('ALL'));assert.equal(h.page.data.visibleCloneRows.length,4);
});
test('uncertain clone retries preserve the exact request and do not become an accidental second resource',async()=>{
 const h=clonePage();h.page.openDigital(event('VOICE'));await flush();
 Object.assign(h.page.data,{cloneUploadId:'upload',cloneConsented:true});h.rejectNext();
 await h.page.createClone();assert.equal(h.page.data.cloneRetry,true);await h.page.createClone();
 assert.equal(h.requests[0].key,h.requests[1].key);assert.deepEqual(h.requests[0].body,h.requests[1].body);assert.equal(h.rows.length,1);
});
