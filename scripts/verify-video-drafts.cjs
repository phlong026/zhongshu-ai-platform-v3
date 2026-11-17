const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const Draft=require('../miniprogram/pages/ai-video/video-draft');
const tick=()=>new Promise(r=>setImmediate(r));
const later=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject};};
const moduleP={exports:{}};
vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/utils/platform-content'),'utf8'),{module:moduleP,require:()=>({}),setTimeout,Date});
const P=moduleP.exports;
function harness({disk=new Map(),cloud=new Map(),read,save,task}={}){
  let definition,valid=true;const writes=[],submissions=[];
  const state={tasks:[{id:'101',kind:'ai',script:'history A',configuration:{}},{id:'102',kind:'ai',script:'history B',configuration:{}}]};
  const api={drafts:{videoDraft:read||(async key=>structuredClone(cloud.get(key)||{exists:false,revision:0})),saveVideoDraft:async body=>{
    writes.push(structuredClone(body));if(save)return save(body);
    const prior=cloud.get(body.draftKey)||{revision:0};if(body.revision!==prior.revision)throw{code:1114000011};
    const result={...body,exists:true,revision:prior.revision+1};cloud.set(body.draftKey,result);return structuredClone(result);
  }},tasks:{create:async body=>{submissions.push(body);return{id:'task'};}}};
  const modules={'../../utils/creation':{assets:[]},'../../utils/studio':{guard:()=>true,allowed:()=>valid,scope:()=>valid?'actor':'other',scopedKey:k=>'actor:'+k,read:()=>state,write:()=>{}},'../../utils/platform-content':P,'../../api/franchise/content':api,'../../api/http':{token:()=>true,sessionGuard:()=>()=>valid},'../../utils/content-media':{upload:async()=>1},'../../utils/storage-keys':{videoDraft:'legacy'},'./video-draft':Draft};
  const wx={getStorageSync:k=>disk.get(k),setStorageSync:(k,v)=>disk.set(k,structuredClone(v)),showToast(){},navigateTo(){},redirectTo(){}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/ai-video/index'),'utf8'),{Page:d=>definition=d,require:p=>{assert(p in modules,p);return modules[p]},wx,getCurrentPages:()=>[],console,Date});
  const page={...definition,data:structuredClone(definition.data),setData(values){for(const[k,v]of Object.entries(values)){if(k.startsWith('parameters[')){const i=Number(k.match(/\d+/)[0]);this.data.parameters[i].value=v;}else this.data[k]=v;}}};
  page.onLoad(task?{task}:{});
  return{page,disk,cloud,writes,submissions,invalidate:()=>valid=false};
}
test('exit before cloud load makes no default write and ignores late response',async()=>{
  const read=later(),h=harness({read:()=>read.promise});h.page.onHide();h.page.onUnload();
  assert.equal(h.writes.length,0);read.resolve({exists:true,revision:2,description:'cloud',parameters:{duration:10}});await tick();
  assert.equal(h.writes.length,0);assert.equal(h.page.data.description,'');
});
test('unchanged cloud draft does not write on hide or unload',async()=>{
  const h=harness({cloud:new Map([['new',{exists:true,revision:3,description:'cloud',parameters:{duration:5}}]])});await tick();
  h.page.onHide();h.page.onUnload();await tick();assert.equal(h.writes.length,0);
});
test('history edits are scoped by task and duplicate lifecycle saves coalesce',async()=>{
  const ack=later(),cloud=new Map([['new',{revision:2,description:'new draft'}],['task:102',{revision:1,description:'B draft'}]]);
  const h=harness({cloud,task:'101',save:()=>ack.promise});await tick();h.page.inputDescription({detail:{value:'A edited'}});
  h.page.onHide();h.page.onUnload();assert.equal(h.writes.length,1);assert.equal(h.writes[0].draftKey,'task:101');
  ack.resolve({revision:1});await tick();assert.equal(cloud.get('new').description,'new draft');assert.equal(cloud.get('task:102').description,'B draft');
});
test('a failed load preserves typing locally without cloud writes',async()=>{
  const h=harness({read:async()=>{throw Error('offline');}});await tick();h.page.inputDescription({detail:{value:'offline edit'}});h.page.onHide();await tick();
  assert.equal(h.writes.length,0);assert.equal(h.disk.get('actor:zs-video-draft-v2:new').form.description,'offline edit');
});
test('parameter-only edits during a slow load are preserved with a conflict',async()=>{
  const read=later(),h=harness({read:()=>read.promise});h.page.changeParameter({currentTarget:{dataset:{index:1}},detail:{value:1}});
  read.resolve({exists:true,revision:2,description:'remote',parameters:{duration:10}});await tick();h.page.onHide();
  assert.equal(h.page.data.parameters[1].value,1);assert.equal(h.page.__draft.conflicted(),true);assert.equal(h.writes.length,0);
});
test('cloud conflict survives reopen; a newer cloud revision needs another confirmation',async()=>{
  const cloud=new Map(),h=harness({cloud});await tick();h.page.inputDescription({detail:{value:'local'}});
  cloud.set('new',{exists:true,revision:1,description:'remote',parameters:{duration:10}});await h.page.saveDraft();h.page.onUnload();
  const reopened=harness({cloud,disk:h.disk});await tick();assert.equal(reopened.page.__draft.conflicted(),true);assert.equal(reopened.writes.length,0);
  cloud.set('new',{exists:true,revision:2,description:'remote2',parameters:{duration:10}});
  await assert.rejects(reopened.page.__draft.resolve(true));assert.equal(reopened.writes.length,0);
  await reopened.page.__draft.resolve(false);assert.equal(reopened.page.data.description,'remote2');assert.equal(reopened.writes.length,0);
});
test('each supported numeric or legacy duration round-trips through restore and generation',async()=>{
  for(const duration of [5,8,10,'5','10 秒']){
    const h=harness({cloud:new Map([['new',{exists:true,revision:1,description:'scene',parameters:{duration,resolution:'2K',ratio:'16:9'}}]])});await tick();
    const expected=Number(String(duration).replace(' 秒',''));const option=h.page.data.parameters[1];assert.equal(option.options[option.value],expected+' 秒');
    await h.page.generateOnline();assert.equal(h.submissions[0].duration,expected);assert.equal(h.submissions[0].resolution,'2K');
    h.page.inputDescription({detail:{value:'edited'}});await h.page.saveDraft();assert.equal(h.writes[0].parameters.duration,expected);
  }
});
test('legacy option-index drafts restore the same duration; unsupported durations are not silently defaulted',()=>{
  const template=[{key:'duration',label:'时长',options:['8 秒','5 秒','10 秒'],value:0}];
  assert.equal(P.restoreVideoParameters([{key:'duration',options:['8 秒','5 秒','10 秒'],value:2}],template)[0].value,2);
  assert.throws(()=>P.restoreVideoParameters({duration:9},template),/不受支持/);
});
test('switching identity rejects the late restore',async()=>{
  const read=later(),h=harness({read:()=>read.promise});h.invalidate();read.resolve({exists:true,revision:3,description:'other',parameters:{duration:10}});await tick();assert.equal(h.page.data.description,'');assert.equal(h.writes.length,0);
});

test('same-revision cloud restore keeps local media file paths',async()=>{
  const cloud=new Map(),h=harness({cloud});await tick();h.page.data.firstFrame='/saved/frame.jpg';h.page.inputDescription({detail:{value:'scene'}});await h.page.saveDraft();h.page.onUnload();
  const reopened=harness({cloud,disk:h.disk});await tick();assert.equal(reopened.page.data.firstFrame,'/saved/frame.jpg');assert.equal(reopened.writes.length,0);
});
