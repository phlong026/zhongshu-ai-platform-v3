const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const Draft=require('../miniprogram/pages/ai-video/video-draft');
const tick=()=>new Promise(r=>setImmediate(r));
const later=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject};};
const moduleP={exports:{}};
vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/utils/platform-content'),'utf8'),{module:moduleP,require:()=>({}),setTimeout,Date});
const P=moduleP.exports;
const taskInput=(taskId,description,extra={})=>({taskId,kind:'ai_video',mode:'frames',description,firstFrameAssetId:'',lastFrameAssetId:'',references:[],parameters:{duration:8,resolution:'768P',ratio:'9:16'},...extra});
function harness({disk=new Map(),cloud=new Map(),read,save,task,input,upload,download,preview,create}={}){
  let definition,valid=true;const writes=[],submissions=[],uploads=[],downloads=[],toasts=[],inputReads=[],draftReads=[];
  let nextTimer=0;const timers=new Map();
  const state={tasks:[]};
  const originals=new Map([['101',taskInput('101','history A')],['102',taskInput('102','history B')]]);
  const api={drafts:{videoDraft:async key=>{draftReads.push(key);return read?read(key):structuredClone(cloud.get(key)||{exists:false,revision:0});},saveVideoDraft:async body=>{
    writes.push(structuredClone(body));if(save)return save(body);
    const prior=cloud.get(body.draftKey)||{revision:0};if(body.revision!==prior.revision)throw{code:1114000011};
    const result={...prior,...body,exists:true,revision:prior.revision+1};cloud.set(body.draftKey,result);return {id:'draft',mode:body.mode,revision:result.revision};
  }},assets:{downloadUrl:async id=>{downloads.push(id);return download?download(id):{downloadUrl:'https://signed.example/'+id};}},tasks:{editableInput:async id=>{inputReads.push(id);if(input)return input(id);if(!originals.has(id))throw Error('任务不存在或无权读取');return structuredClone(originals.get(id));},create:async (body,key)=>{submissions.push(JSON.parse(JSON.stringify(body)));return create?create(body,key):{id:'task'};}}};
  const media={upload:async(path,alive)=>{uploads.push(path);return upload?upload(path,alive):String(100+uploads.length);},source:async(id,alive,options={})=>{await api.assets.downloadUrl(id);if(!alive())throw Error('身份已切换');return preview?preview(id,options):options.type==='image'?(options.localPath||'/authorized/'+id+'.png'):'';}};
  const modules={'../../utils/creation':{assets:[]},'../../utils/studio':{guard:()=>true,allowed:()=>valid,scope:()=>valid?'actor':'other',scopedKey:k=>'actor:'+k,read:()=>state,write:()=>{}},'../../utils/platform-content':P,'../../api/franchise/content':api,'../../api/http':{token:()=>true,sessionGuard:()=>()=>valid},'../../utils/content-media':media,'../../utils/storage-keys':{videoDraft:'legacy'},'../../utils/creation-text-resize':require('../miniprogram/utils/creation-text-resize'),'./video-draft':Draft};
  const wx={getStorageSync:k=>disk.get(k),setStorageSync:(k,v)=>disk.set(k,structuredClone(v)),showToast:o=>toasts.push(o.title),navigateTo(){},redirectTo(){}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/ai-video/index'),'utf8'),{Page:d=>definition=d,require:p=>{assert(p in modules,p);return modules[p]},wx,getCurrentPages:()=>[],console,Date,setTimeout:fn=>{const id=++nextTimer;timers.set(id,fn);return id;},clearTimeout:id=>timers.delete(id)});
  const page={...definition,data:structuredClone(definition.data),setData(values){for(const[k,v]of Object.entries(values)){if(k.startsWith('parameters[')){const i=Number(k.match(/\d+/)[0]);this.data.parameters[i].value=v;}else this.data[k]=v;}}};
  page.onLoad(task?{task}:{});
  return{page,disk,cloud,writes,submissions,uploads,downloads,toasts,inputReads,draftReads,state,wx,invalidate:()=>valid=false,flushAutosave:async()=>{const jobs=[...timers.values()];timers.clear();jobs.forEach(job=>job());await tick();}};
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
test('an unversioned service keeps a task edit on this device and never overwrites a shared draft',async()=>{
  for(const legacy of [{exists:false},{exists:true,revision:null,description:'other draft'}]){
    const h=harness({read:async()=>legacy,task:'101'});await tick();
    assert.match(h.page.data.draftStatus,/仅存本机/);h.page.inputDescription({detail:{value:'现场取景'}});
    h.page.onHide();await tick();assert.equal(h.writes.length,0);
    assert.equal(h.disk.get('actor:zs-video-draft-v2:task:101').form.description,'现场取景');
  }
});
test('parameter-only edits during a slow load are preserved with a conflict',async()=>{
  const read=later(),h=harness({read:()=>read.promise});h.page.inputDuration({detail:{value:'9'}});
  read.resolve({exists:true,revision:2,description:'remote',parameters:{duration:10}});await tick();h.page.onHide();
  assert.equal(h.page.data.parameters[1].value,'9 秒');assert.equal(h.page.__draft.conflicted(),true);assert.equal(h.writes.length,0);
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
  for(const duration of [4,5,8,9,10,12,15,'5','10 秒']){
    const h=harness({cloud:new Map([['new',{exists:true,revision:1,description:'scene',parameters:{duration,resolution:'2K',ratio:'16:9'}}]])});await tick();
    const expected=Number(String(duration).replace(' 秒',''));const option=h.page.data.parameters[1];assert.equal(option.value,expected+' 秒');assert.equal(h.page.data.durationInput,String(expected));
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

test('local draft reopen retains library IDs even when preview paths are absent',async()=>{
  const cloud=new Map(),h=harness({cloud});await tick();
  h.page.setData({firstFrameAssetId:'9007199254740993',references:[{id:'material-101',assetId:'101',type:'audio',mimeType:'audio/mpeg',name:'公司录音',path:''}]});
  await h.page.saveDraft();h.page.onUnload();
  const reopened=harness({cloud,disk:h.disk});await tick();
  assert.equal(reopened.page.data.firstFrameAssetId,'9007199254740993');
  assert.equal(reopened.page.data.references[0].assetId,'101');
  assert.equal(reopened.page.data.ratioAutomatic,true);
});

test('fresh device restores all media from the real asset-ID draft DTO and generates without reupload',async()=>{
  const cloud=new Map(),first=harness({cloud});await tick();
  first.page.setData({description:'跨设备完整输入',firstFrame:'/saved/first.png',lastFrame:'/saved/last.png',references:[
    {id:'r-image',type:'image',name:'参考图',path:'/saved/ref.png'},
    {id:'r-audio',type:'audio',name:'参考音频',path:'/saved/ref.mp3'}]});
  await first.page.saveDraft();
  const body=first.writes[0];
  assert.equal(first.writes.length,1,'upload ID backfill must not add an unnecessary draft revision');
  assert.equal(body.firstFrameAssetId,'101');assert.equal(body.lastFrameAssetId,'102');
  assert.deepEqual(body.references.map(r=>r.assetId),['103','104']);assert.equal('path' in body.references[0],false);
  assert.equal(first.disk.get('actor:zs-video-draft-v2:new').dirty,false);
  const second=harness({cloud});await tick();
  assert.equal(second.page.data.firstFrameAssetId,'101');assert.equal(second.page.data.firstFrame,'/authorized/101.png');
  assert.equal(second.page.data.lastFrameAssetId,'102');assert.equal(second.page.data.references.length,2);
  assert.equal(second.page.data.references[0].type,'image');assert.equal(second.page.data.references[1].type,'audio');
  second.page.onHide();await tick();assert.equal(second.writes.length,0,'preview tickets must not dirty a cloud draft');
  await second.page.generateOnline();
  assert.deepEqual(second.submissions[0].references.map(r=>r.assetId),['101','102']);
  second.page.setData({mode:'reference'});await second.page.generateOnline();
  assert.deepEqual(second.submissions[1].references.map(r=>r.assetId),['103','104']);assert.equal(second.uploads.length,0);
});

test('clearing frames and references sends explicit null/empty values and removes them on a fresh device',async()=>{
  const cloud=new Map([['new',{exists:true,revision:1,description:'已保存',firstFrameAssetId:'901',lastFrameAssetId:'902',references:[{assetId:'903',role:'reference_image',name:'图'}],parameters:{duration:8}}]]);
  const h=harness({cloud});await tick();
  h.page.setData({firstFrame:'',lastFrame:'',firstFrameAssetId:'',lastFrameAssetId:'',references:[]});await h.page.saveDraft();
  assert.equal(h.writes[0].firstFrameAssetId,null);assert.equal(h.writes[0].lastFrameAssetId,null);assert.deepEqual(h.writes[0].references,[]);
  const next=harness({cloud});await tick();assert.equal(next.page.data.firstFrameAssetId,'');assert.equal(next.page.data.lastFrameAssetId,'');assert.equal(next.page.data.references.length,0);
});

test('numeric zero frame IDs and historical empty reference object normalize to empty media',async()=>{
  const h=harness({cloud:new Map([['new',{exists:true,revision:1,description:'无媒体',firstFrameAssetId:0,lastFrameAssetId:'0',references:{},parameters:{duration:8}}]])});await tick();
  assert.equal(h.page.data.firstFrameAssetId,'');assert.equal(h.page.data.lastFrameAssetId,'');assert.equal(h.page.data.references.length,0);assert.equal(h.downloads.length,0);
  h.page.inputDescription({detail:{value:'修改文字'}});await h.page.saveDraft();assert.equal(h.writes[0].lastFrameAssetId,null);
});

test('upload failure stays on this device and retry saves the same media instead of a partial cloud draft',async()=>{
  let failed=true;const h=harness({upload:async()=>{if(failed)throw Error('上传网络失败');return '9007199254740993';}});await tick();
  h.page.setData({firstFrame:'/saved/retry.png'});await h.page.saveDraft();
  assert.equal(h.writes.length,0);assert.match(h.page.data.draftStatus,/本机/);assert.equal(h.disk.get('actor:zs-video-draft-v2:new').form.firstFrame,'/saved/retry.png');
  failed=false;await h.page.saveDraft();assert.equal(h.writes[0].firstFrameAssetId,'9007199254740993');assert.equal(h.page.data.firstFrameAssetId,'9007199254740993');
});

test('late uploaded ID cannot replace a later library selection even when its preview path is identical',async()=>{
  const pending=later(),h=harness({upload:()=>pending.promise});await tick();
  h.page.setData({firstFrame:'/saved/same.png'});const saving=h.page.saveDraft();await tick();
  h.page.setData({firstFrame:'/saved/same.png',firstFrameAssetId:'9007199254740995'});
  pending.resolve('101');await saving;
  assert.equal(h.page.data.firstFrameAssetId,'9007199254740995');
  assert.equal(h.writes[0].firstFrameAssetId,'101');assert.equal(h.writes.at(-1).firstFrameAssetId,'9007199254740995');
  assert.equal(h.disk.get('actor:zs-video-draft-v2:new').dirty,false);
});

test('reference uploads backfill by row identity; intervening reorder, replacement and removal stay dirty',async()=>{
  const pending=later(),h=harness({upload:()=>pending.promise});await tick();
  h.page.setData({references:[{id:'a',type:'image',path:'/saved/a.png',name:'A'}]});
  const saving=h.page.saveDraft();await tick();
  h.page.setData({references:[{id:'b',type:'image',assetId:'202',path:'/saved/a.png',name:'B'}]});
  pending.resolve('101');await saving;
  assert.equal(h.page.data.references[0].id,'b');assert.equal(h.page.data.references[0].assetId,'202');
  assert.equal(h.writes[0].references[0].assetId,'101');assert.equal(h.writes.at(-1).references[0].assetId,'202');
});

test('unreadable restored media keeps its asset ID, reports the failure and cannot become an empty paid request',async()=>{
  const h=harness({cloud:new Map([['new',{exists:true,revision:1,description:'库素材',firstFrameAssetId:'901',references:[],parameters:{duration:8}}]]),download:async()=>{throw {code:403,message:'素材已移除或无权限'};}});await tick();
  assert.equal(h.page.data.firstFrameAssetId,'901');assert.match(h.page.data.mediaError,/素材|权限/);
  await h.page.generateOnline();assert.equal(h.submissions.length,0);assert.equal(h.uploads.length,0);
  assert.equal(h.page.data.firstFrameAssetId,'901');assert.match(h.toasts.at(-1),/素材|权限/);
});

test('late preview and upload responses cannot write into a new identity or a changed media selection',async()=>{
  const ticket=later(),h=harness({cloud:new Map([['new',{exists:true,revision:1,firstFrameAssetId:'901',references:[],parameters:{duration:8}}]]),download:()=>ticket.promise});await tick();
  h.page.setData({firstFrameAssetId:'902',firstFrame:'/user/new.png'});ticket.resolve({downloadUrl:'https://signed.example/901'});await tick();
  assert.equal(h.page.data.firstFrame,'/user/new.png');
  const upload=later(),other=harness({upload:()=>upload.promise});await tick();other.page.setData({firstFrame:'/saved/old.png'});
  const saving=other.page.saveDraft();await tick();other.invalidate();upload.resolve('101');await saving;
  assert.equal(other.writes.length,0);assert.equal(other.page.data.firstFrameAssetId,'');
});

test('legacy cloud references without asset IDs remain visible as unavailable and cannot silently disappear on save',async()=>{
  const h=harness({cloud:new Map([['new',{exists:true,revision:1,mode:'reference',description:'历史草稿',references:[{role:'reference_image',name:'旧参考图'}],parameters:{duration:8}}]])});await tick();
  assert.equal(h.page.data.references.length,1);assert.equal(h.page.data.references[0].name,'旧参考图');assert.match(h.page.data.mediaError,/缺少资产/);
  h.page.inputDescription({detail:{value:'继续编辑'}});await h.page.saveDraft();await h.page.generateOnline();
  assert.equal(h.writes.length,0);assert.equal(h.submissions.length,0);assert.equal(h.page.data.references.length,1);
});

test('frame generation ignores unavailable reference-mode drafts without deleting them',async()=>{
  const h=harness({cloud:new Map([['new',{exists:true,revision:1,mode:'frames',description:'首帧模式',firstFrameAssetId:'901',references:[{role:'reference_image',name:'旧参考素材'}],parameters:{duration:8}}]])});await tick();
  await h.page.generateOnline();assert.equal(h.submissions.length,1);assert.deepEqual(h.submissions[0].references,[{role:'first_frame',assetId:'901'}]);
  assert.equal(h.page.data.references.length,1);assert.equal(h.page.data.references[0].name,'旧参考素材');assert.equal(h.uploads.length,0);
});

test('reference generation ignores an expired frame-mode file while retaining that unfinished draft',async()=>{
  const h=harness({upload:async()=>{throw Error('旧帧本地文件已失效');}});await tick();
  h.page.setData({mode:'reference',description:'参考模式',firstFrame:'/expired/old.png',references:[{id:'r',type:'video',name:'当前视频',assetId:'902',path:''}]});
  await h.page.generateOnline();assert.equal(h.submissions.length,1);assert.deepEqual(h.submissions[0].references,[{role:'reference_video',assetId:'902',name:'当前视频'}]);
  assert.equal(h.page.data.firstFrame,'/expired/old.png');assert.equal(h.uploads.length,0);
});

test('a fresh device with no local tasks restores the authorized original input and creates a separate task',async()=>{
  const id='9007199254740993',original=taskInput(id,'原始庭院镜头',{firstFrameAssetId:'9007199254740995',lastFrameAssetId:'9007199254740997',parameters:{duration:9,resolution:'2K',ratio:'adaptive'}});
  const h=harness({task:id,input:async()=>structuredClone(original)});await tick();
  assert.equal(h.state.tasks.length,0);assert.deepEqual(h.inputReads,[id]);assert.deepEqual(h.draftReads,['task:'+id]);
  assert.equal(h.page.data.invalidMessage,'');assert.equal(h.page.data.taskLoading,false);
  assert.equal(h.page.data.description,original.description);assert.equal(h.page.data.firstFrameAssetId,original.firstFrameAssetId);
  assert.equal(h.page.data.lastFrameAssetId,original.lastFrameAssetId);assert.equal(h.page.data.firstFrame,'/authorized/'+original.firstFrameAssetId+'.png');
  assert.deepEqual(JSON.parse(JSON.stringify(P.mapVideoParameters(h.page.data.parameters))),original.parameters);
  h.page.onHide();await tick();assert.equal(h.writes.length,0,'reading the original input is not a draft edit');
  h.page.inputDescription({detail:{value:'改为傍晚镜头'}});await h.page.saveDraft();await h.page.generate();
  assert.equal(h.writes[0].draftKey,'task:'+id);assert.equal(h.submissions[0].description,'改为傍晚镜头');
  assert.deepEqual(h.submissions[0].references,[{role:'first_frame',assetId:original.firstFrameAssetId},{role:'last_frame',assetId:original.lastFrameAssetId}]);
  assert.equal('taskId' in h.submissions[0],false,'create a new task instead of modifying the original');
  assert.equal(original.description,'原始庭院镜头');assert.equal(h.uploads.length,0);
  const reopened=harness({task:id,cloud:h.cloud,input:async()=>structuredClone(original)});await tick();
  assert.equal(reopened.page.data.description,'改为傍晚镜头');assert.equal(reopened.page.data.firstFrameAssetId,original.firstFrameAssetId);
});

test('reference and pure-text tasks restore the exact supported roles and generation parameters',async()=>{
  const cases=[taskInput('201','参考镜头',{mode:'reference',references:[
    {role:'reference_image',assetId:'901',name:'图'},{role:'reference_video',assetId:'902',name:'视频'},{role:'reference_audio',assetId:'903',name:'音频'}],parameters:{duration:12,resolution:'2K',ratio:'adaptive'}}),
    taskInput('202','纯文生视频',{parameters:{duration:5,resolution:'768P',ratio:'21:9'}})];
  for(const original of cases){
    const h=harness({task:original.taskId,input:async()=>structuredClone(original)});await tick();await h.page.generate();
    assert.equal(h.page.data.mode,original.mode);assert.equal(h.submissions[0].duration,original.parameters.duration);
    assert.equal(h.submissions[0].ratio,original.parameters.ratio);assert.equal(h.submissions[0].resolution,original.parameters.resolution);
    assert.deepEqual(h.submissions[0].references||[],original.references);assert.equal(h.uploads.length,0);
  }
});

test('an explicitly cleared task draft overrides original media and never falls back to another draft',async()=>{
  const cloud=new Map([['new',{exists:true,revision:8,description:'新建草稿'}],['task:102',{exists:true,revision:2,description:'其他任务'}],
    ['task:101',{exists:true,revision:3,mode:'frames',description:'',firstFrameAssetId:0,lastFrameAssetId:0,references:[],parameters:{duration:4,resolution:'768P',ratio:'1:1'}}]]);
  const h=harness({task:'101',cloud,input:async()=>taskInput('101','原始画面',{firstFrameAssetId:'901'})});await tick();
  assert.equal(h.page.data.description,'');assert.equal(h.page.data.firstFrameAssetId,'');assert.equal(h.page.data.references.length,0);
  assert.equal(P.mapVideoParameters(h.page.data.parameters).ratio,'1:1');
  h.page.inputDescription({detail:{value:'新方向'}});await h.page.saveDraft();
  assert.equal(h.writes[0].revision,3);assert.equal(h.writes[0].draftKey,'task:101');
  assert.equal(cloud.get('new').description,'新建草稿');assert.equal(cloud.get('task:102').description,'其他任务');
});

test('task authorization failure cannot expose a cached form or submit its contents; retry reloads from the server',async()=>{
  let denied=true;
  const disk=new Map([['actor:zs-video-draft-v2:task:101',{form:taskInput('101','缓存中的旧作者内容'),revision:0,dirty:false}]]);
  const h=harness({task:'101',disk,input:async()=>{if(denied)throw Error('任务不存在或无权读取');return taskInput('101','本人原任务');}});
  await h.page.generate();await tick();await h.page.generateOnline();h.page.onHide();
  assert.equal(h.page.data.description,'');assert.match(h.page.data.invalidMessage,/无权读取/);
  assert.equal(h.draftReads.length,0);assert.equal(h.submissions.length,0);assert.equal(h.writes.length,0);
  disk.clear();denied=false;await h.page.restoreTaskInput();
  assert.equal(h.page.data.invalidMessage,'');assert.equal(h.page.data.description,'本人原任务');assert.equal(h.inputReads.length,2);
});

test('task input loading blocks empty generation and ignores responses after unload or identity change',async()=>{
  for(const leave of ['unload','identity']){
    const pending=later(),h=harness({task:'101',input:()=>pending.promise});
    assert.equal(h.page.data.taskLoading,true);await h.page.generateOnline();h.page.onHide();
    assert.equal(h.submissions.length,0);assert.equal(h.writes.length,0);
    if(leave==='unload')h.page.onUnload();else h.invalidate();
    pending.resolve(taskInput('101','迟到内容',{firstFrameAssetId:'901'}));await tick();
    assert.equal(h.page.data.description,'');assert.equal(h.draftReads.length,0);assert.equal(h.downloads.length,0);
  }
});

test('mismatched or unsupported task input remains blocked instead of becoming a blank editable form',async()=>{
  for(const original of [taskInput('102','其他任务'),{...taskInput('101','数字人'),kind:'digital'},taskInput('101','不支持时长',{parameters:{duration:99,resolution:'768P',ratio:'9:16'}}),{taskId:'101',kind:'ai_video'}]){
    const h=harness({task:'101',input:async()=>original});await tick();await h.page.generateOnline();
    assert.ok(h.page.data.invalidMessage);assert.equal(h.draftReads.length,0);assert.equal(h.submissions.length,0);
  }
  const invalid=harness({task:'101/../../secret'});await tick();assert.equal(invalid.inputReads.length,0);assert.ok(invalid.page.data.invalidMessage);
});

test('editable-input API uses the authorized task route without changing a large task identifier',async()=>{
  const calls=[],module={exports:{}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/api/franchise/content'),'utf8'),{module,require:p=>{assert.equal(p,'../http');return{get:async url=>{calls.push(url);return taskInput('9007199254740993','原任务');}}},Date,Math});
  const result=await module.exports.tasks.editableInput('9007199254740993');
  assert.equal(result.taskId,'9007199254740993');assert.equal(calls[0],'/franchise/content/tasks/9007199254740993/editable-input');
  await module.exports.tasks.editableInput('101/../../secret');assert.equal(calls[1],'/franchise/content/tasks/101%2F..%2F..%2Fsecret/editable-input');
});

test('task draft read failure blocks ordinary generation until retry loads the latest edit',async()=>{
  let offline=true;
  const latest={...taskInput('101','另一设备保存的修改稿'),exists:true,revision:3};
  const h=harness({task:'101',read:async()=>{if(offline)throw Error('timeout');return structuredClone(latest);}});await tick();
  assert.equal(h.page.data.taskLoading,false);assert.equal(h.page.data.invalidMessage,'');
  assert.match(h.page.data.draftStatus,/读取失败/);
  await h.page.generate();await h.page.generateOnline();
  assert.equal(h.submissions.length,0,'a failed draft read must not submit the original task text');
  offline=false;await h.page.retryDraft();await tick();
  assert.equal(h.page.data.description,latest.description);assert.equal(h.page.data.taskDraftBlocked,false);
  await h.page.generate();assert.equal(h.submissions.length,1);assert.equal(h.submissions[0].description,latest.description);
});

for(const task of [undefined,'101'])for(const useLocal of [false,true])test('post-load conflict blocks generation until '+(useLocal?'local':'cloud')+' is selected for '+(task||'new'),async()=>{
  const cloud=new Map(),h=harness({cloud,task});await tick();
  const key=task?'task:'+task:'new';h.page.inputDescription({detail:{value:'本机修改稿'}});
  cloud.set(key,{...taskInput('101','云端修改稿'),exists:true,revision:1});
  await h.page.saveDraft();assert.equal(h.page.__draft.conflicted(),true);
  await h.page.generate();await h.page.generateOnline();await h.page.useOriginalTask();
  assert.equal(h.submissions.length,0);
  const {render}=require('./role-page-runtime.cjs'),flat=nodes=>nodes.flatMap(n=>[n,...flat(n.children||[])]);
  const nodes=flat(render(require.resolve('../miniprogram/pages/ai-video/index.wxml'),h.page.data));
  assert.equal(nodes.some(n=>n.tag==='button'&&['generate','useOriginalTask'].includes(n.attrs?.bindtap)),false);
  assert.ok(nodes.some(n=>n.tag==='button'&&n.attrs?.bindtap==='retryDraft'));
  h.wx.showActionSheet=options=>options.success({tapIndex:useLocal?0:1});
  await h.page.retryDraft();
  if(useLocal){assert.equal(h.page.__draft.conflicted(),true);await h.page.generate();assert.equal(h.submissions.length,0);await h.page.retryDraft();}
  assert.equal(h.page.__draft.conflicted(),false);assert.equal(h.page.data.draftConflicted,false);
  await h.page.generate();assert.equal(h.submissions.length,1);
  assert.equal(h.submissions[0].description,useLocal?'本机修改稿':'云端修改稿');
});

test('generation waits for draft save so a newly reported revision conflict cannot submit a paid task',async()=>{
  const saving=later(),h=harness({save:()=>saving.promise});await tick();
  h.page.inputDescription({detail:{value:'尚未确认版本'}});
  const attempt=h.page.generate();await tick();assert.equal(h.writes.length,1);
  saving.reject({code:1114000011});await attempt;
  assert.equal(h.submissions.length,0);assert.equal(h.page.data.draftConflicted,true);
});

test('repeated generate taps during draft save create only one paid task',async()=>{
  const saving=later(),h=harness({save:()=>saving.promise});await tick();
  h.page.inputDescription({detail:{value:'保存中连续点击'}});
  const first=h.page.generate(),second=h.page.generate();await tick();
  saving.resolve({revision:1});await Promise.all([first,second]);
  assert.equal(h.submissions.length,1);
});

test('a draft conflict reported during material preparation blocks the final submission',async()=>{
  const permission=later(),cloud=new Map(),h=harness({cloud,download:()=>permission.promise});await tick();
  h.page.setData({description:'准备提交',firstFrameAssetId:'901'});
  const attempt=h.page.generate();await tick();
  cloud.set('new',{...taskInput('101','另一设备'),exists:true,revision:2});
  h.page.inputDescription({detail:{value:'准备时又有修改'}});await h.page.saveDraft();
  permission.resolve({downloadUrl:'https://signed.example/901'});await attempt;
  assert.equal(h.page.__draft.conflicted(),true);assert.equal(h.submissions.length,0);assert.equal(h.page.__videoSubmission,undefined);
});

test('using the original task after a draft read failure requires confirmation and preserves the unread draft',async()=>{
  const cloud=new Map([['task:101',{...taskInput('101','最新云端修改稿'),exists:true,revision:7}]]);
  const h=harness({task:'101',cloud,read:async()=>{throw Error('timeout');}});await tick();
  h.wx.showModal=options=>options.success({confirm:false});
  await h.page.useOriginalTask();assert.equal(h.submissions.length,0);
  h.wx.showModal=options=>options.success({confirm:true});
  await h.page.useOriginalTask();
  assert.equal(h.submissions.length,1);assert.equal(h.submissions[0].description,'history A');
  assert.equal(h.writes.length,0);assert.equal(cloud.get('task:101').description,'最新云端修改稿');
  assert.equal(h.page.data.taskDraftBlocked,true,'the failed draft read stays unresolved after explicit original-only generation');
});

test('an original-task confirmation cannot submit after the working identity changes',async()=>{
  const h=harness({task:'101',read:async()=>{throw Error('timeout');}});await tick();let confirm;
  h.wx.showModal=options=>{confirm=options.success;};
  const attempt=h.page.useOriginalTask();h.invalidate();confirm({confirm:true});await attempt;
  assert.equal(h.submissions.length,0);assert.equal(h.writes.length,0);
});

test('an unversioned task draft response stays blocked while a confirmed missing draft enables the original form',async()=>{
  const legacy=harness({task:'101',read:async()=>({exists:true,description:'unknown revision'})});await tick();
  await legacy.page.generate();assert.equal(legacy.submissions.length,0);
  assert.equal(legacy.page.data.taskDraftBlocked,true);
  const missing=harness({task:'101',read:async()=>({exists:false,revision:0})});await tick();
  assert.equal(missing.page.data.taskDraftBlocked,false);await missing.page.generate();
  assert.equal(missing.submissions[0].description,'history A');
});

test('a lost task receipt is confirmed with the frozen request before checking revoked material access',async()=>{
  let revoked=false;const ledger=new Map(),attempts=[],navigations=[];
  const h=harness({download:async()=>{if(revoked)throw {code:403,message:'素材已撤回'};return{downloadUrl:'https://signed.example/901'};},create:async(body,key)=>{
    attempts.push({body:structuredClone(body),key});
    if(ledger.has(key))return ledger.get(key);
    if(revoked)throw{code:403,message:'素材已撤回'};
    const task={id:'301'};ledger.set(key,task);throw Error('timeout');
  }});await tick();h.wx.navigateTo=options=>navigations.push(options.url);h.wx.showModal=options=>options.success({confirm:true});
  h.page.setData({description:'原始文案',firstFrameAssetId:'901'});await h.page.generate();
  assert.equal(ledger.size,1);revoked=true;await h.page.generate();
  assert.equal(attempts.length,2);assert.equal(attempts[0].key,attempts[1].key);assert.deepEqual(attempts[0].body,attempts[1].body);
  assert.deepEqual(h.downloads,['901']);assert.equal(ledger.size,1);assert.equal(h.page.__videoSubmission,null);
  assert.deepEqual(navigations,['/pages/studio/index?view=tasks']);
});

test('an unconfirmed request is recovered even after the current description and duration become invalid',async()=>{
  const attempts=[];const h=harness({create:async(body,key)=>{attempts.push({body:structuredClone(body),key});if(attempts.length===1)throw Error('timeout');return{id:'302'};}});await tick();h.wx.showModal=options=>options.success({confirm:true});
  h.page.inputDescription({detail:{value:'已提交文案'}});await h.page.generate();
  h.page.inputDescription({detail:{value:''}});h.page.inputDuration({detail:{value:'99'}});await h.page.generate();
  assert.equal(attempts.length,2);assert.deepEqual(attempts[0],attempts[1]);assert.equal(h.page.data.description,'');
});

test('new tasks still check material access and uncertain unaccepted requests keep their original key',async()=>{
  const denied=harness({download:async()=>{throw{code:403,message:'素材已撤回'};}});await tick();
  denied.page.setData({description:'新任务',firstFrameAssetId:'901'});await denied.page.generate();
  assert.equal(denied.submissions.length,0);
  const keys=[];const h=harness({create:async(body,key)=>{keys.push(key);if(keys.length===1)throw Error('timeout');throw{code:403,message:'素材已撤回'};}});await tick();h.wx.showModal=options=>options.success({confirm:true});
  h.page.inputDescription({detail:{value:'尚未确认'}});await h.page.generate();
  h.page.inputDescription({detail:{value:'后续修改'}});await h.page.generate();await h.page.generate();
  assert.equal(keys.length,3);assert.equal(new Set(keys).size,1);assert.ok(h.page.__videoSubmission);
  assert.equal(h.submissions[1].description,'尚未确认');
});

test('an explicit initial task rejection allows correcting the form while an identity change forbids replay',async()=>{
  const attempts=[];const h=harness({create:async(body,key)=>{attempts.push({body:structuredClone(body),key});if(attempts.length===1)throw{code:1114000001,message:'输入无效'};return{id:'303'};}});await tick();
  h.page.inputDescription({detail:{value:'被拒绝的文案'}});await h.page.generate();
  assert.equal(h.page.__videoSubmission,null);h.page.inputDescription({detail:{value:'更正后的文案'}});await h.page.generate();
  assert.equal(attempts[1].body.description,'更正后的文案');assert.notEqual(attempts[0].key,attempts[1].key);
  const stale=harness({create:async()=>{throw Error('timeout');}});await tick();stale.page.inputDescription({detail:{value:'待确认'}});await stale.page.generate();
  stale.invalidate();await stale.page.generateOnline();assert.equal(stale.submissions.length,1);
});

for(const accepted of [false,true])test('retry consent protects the original draft when the first request was '+(accepted?'accepted':'never received'),async()=>{
  const ledger=new Map(),attempts=[],modals=[];
  const h=harness({create:async(body,key)=>{
    attempts.push({body:structuredClone(body),key});
    if(attempts.length===1){if(accepted)ledger.set(key,{id:'301'});throw Error('timeout');}
    if(!ledger.has(key))ledger.set(key,{id:'301'});return ledger.get(key);
  }});await tick();h.page.inputDescription({detail:{value:'首次原文案'}});await h.page.generate();
  const original=h.page.__videoSubmission;
  h.page.inputDescription({detail:{value:'当前新文案'}});
  h.wx.showModal=options=>{modals.push(options);options.success({confirm:false});};
  await h.page.generate();assert.equal(attempts.length,1);assert.equal(ledger.size,accepted?1:0);
  assert.equal(h.page.__videoSubmission,original);assert.equal(h.page.data.submissionPending,true);
  assert.equal(modals[0].title,'按原内容重试');assert.match(modals[0].content,/创建.*计费/);assert.match(modals[0].content,/首次原文案/);
  const {render}=require('./role-page-runtime.cjs'),flat=nodes=>nodes.flatMap(n=>[n,...flat(n.children||[])]);
  const nodes=flat(render(require.resolve('../miniprogram/pages/ai-video/index.wxml'),h.page.data));
  assert.ok(nodes.some(n=>n.text==='按原内容重试'));
  h.wx.showModal=options=>options.success({confirm:true});await h.page.generate();
  assert.equal(attempts.length,2);assert.equal(ledger.size,1);assert.deepEqual(attempts[0],attempts[1]);
  assert.equal(attempts[1].body.description,'首次原文案');assert.equal(h.page.data.description,'当前新文案');
});

test('original-task fallback also requires fresh retry consent after an uncertain response',async()=>{
  const attempts=[],titles=[],h=harness({task:'101',read:async()=>{throw Error('draft timeout');},create:async(body,key)=>{attempts.push({body:structuredClone(body),key});if(attempts.length===1)throw Error('timeout');return{id:'301'};}});await tick();
  h.wx.showModal=options=>{titles.push(options.title);options.success({confirm:titles.length===1});};
  await h.page.useOriginalTask();await h.page.useOriginalTask();
  assert.equal(attempts.length,1);assert.deepEqual(titles,['用原任务另建','按原内容重试']);
  h.wx.showModal=options=>options.success({confirm:true});await h.page.useOriginalTask();
  assert.equal(attempts.length,2);assert.deepEqual(attempts[0],attempts[1]);
});

for(const leave of ['identity','unload'])test('retry confirmation cannot submit after '+leave+' and repeated taps open only one dialog',async()=>{
  const h=harness({create:async()=>{throw Error('timeout');}});await tick();
  h.page.inputDescription({detail:{value:'首次文案'}});await h.page.generate();
  let confirm,count=0;h.wx.showModal=options=>{count++;confirm=options.success;};
  const attempt=h.page.generate();await h.page.generateOnline();
  assert.equal(count,1);assert.equal(h.submissions.length,1);
  if(leave==='identity')h.invalidate();else h.page.onUnload();
  confirm({confirm:true});await attempt;assert.equal(h.submissions.length,1);
});

test('a draft conflict arriving during retry confirmation prevents submitting the frozen draft',async()=>{
  const cloud=new Map(),h=harness({cloud,create:async()=>{throw Error('timeout');}});await tick();
  h.page.inputDescription({detail:{value:'首次文案'}});await h.page.generate();
  let confirm;h.wx.showModal=options=>{confirm=options.success;};const attempt=h.page.generate();
  cloud.set('new',{...taskInput('101','另一设备'),exists:true,revision:2});
  h.page.inputDescription({detail:{value:'本机新版本'}});await h.page.saveDraft();
  confirm({confirm:true});await attempt;assert.equal(h.submissions.length,1);assert.equal(h.page.__draft.conflicted(),true);
});

test('pausing after edits autosaves the latest form and coalesces consecutive input',async()=>{
  const h=harness();await tick();h.page.inputDescription({detail:{value:'第一版'}});h.page.inputDescription({detail:{value:'最新一版'}});
  assert.equal(h.writes.length,0);await h.flushAutosave();assert.equal(h.writes.length,1);assert.equal(h.writes[0].description,'最新一版');
  h.page.inputDuration({detail:{value:'11'}});await h.flushAutosave();assert.equal(h.writes[1].parameters.duration,11);
});

test('direct unload saves the latest edit for a fresh device without updating the closed page',async()=>{
  const h=harness();await tick();h.page.inputDescription({detail:{value:'直接返回前的修改'}});
  const setData=h.page.setData;h.page.setData=function(values){assert.equal(this.__closed,false,'closed pages must not receive save callbacks');setData.call(this,values);};
  await h.page.onUnload();await tick();assert.equal(h.writes.length,1);assert.equal(h.writes[0].description,'直接返回前的修改');
  const fresh=harness({cloud:h.cloud});await tick();assert.equal(fresh.page.data.description,'直接返回前的修改');
});

test('unload drains an in-flight save and then writes the final newer snapshot exactly once',async()=>{
  const ack=later(),cloud=new Map();let saves=0;
  const h=harness({cloud,save:async body=>{if(++saves===1)await ack.promise;const revision=body.revision+1;cloud.set(body.draftKey,{...body,exists:true,revision});return{revision};}});await tick();
  h.page.inputDescription({detail:{value:'保存中的旧文案'}});const first=h.page.saveDraft();
  h.page.inputDescription({detail:{value:'退出时的最新文案'}});const closing=h.page.onUnload();ack.resolve();await first;await closing;await tick();
  assert.equal(h.writes.length,2);assert.deepEqual(h.writes.map(write=>write.revision),[0,1]);
  assert.equal(cloud.get('new').description,'退出时的最新文案');
});

test('a media upload started before unload finishes the cloud draft without late page writes',async()=>{
  const upload=later(),h=harness({upload:()=>upload.promise});await tick();
  h.page.setData({description:'含图片的最终稿',firstFrame:'/saved/new.png'});h.page.onHide();
  const setData=h.page.setData;h.page.setData=function(values){assert.equal(this.__closed,false);setData.call(this,values);};
  const closing=h.page.onUnload();upload.resolve('9007199254740993');await closing;await tick();
  assert.equal(h.uploads.length,1);assert.equal(h.writes.length,1);assert.equal(h.writes[0].firstFrameAssetId,'9007199254740993');
  assert.equal(h.disk.get('actor:zs-video-draft-v2:new').form.firstFrameAssetId,'9007199254740993');
  const fresh=harness({cloud:h.cloud});await tick();assert.equal(fresh.page.data.firstFrameAssetId,'9007199254740993');assert.equal(fresh.page.data.description,'含图片的最终稿');
});

test('final draft reuses a generation upload while the initial cloud read finishes after unload',async()=>{
  const remote=later(),upload=later();
  const h=harness({read:()=>remote.promise,upload:async(path,alive)=>{await upload.promise;if(!alive())throw Error('上传已失效');return '9007199254740993';}});
  h.page.setData({firstFrame:'/saved/generating.png'});h.page.inputDescription({detail:{value:'生成前最后填写'}});
  const generating=h.page.generate();await tick();assert.equal(h.uploads.length,1);assert.equal(h.writes.length,0);
  const setData=h.page.setData;h.page.setData=function(values){assert.equal(this.__closed,false,'closed pages must not receive upload callbacks');setData.call(this,values);};
  const closing=h.page.onUnload();remote.resolve({exists:false,revision:0});await tick();upload.resolve();
  await generating;await closing;await tick();
  assert.equal(h.uploads.length,1);assert.equal(h.submissions.length,0,'closing cancels generation even though the final draft can finish');
  assert.equal(h.writes.length,1);assert.equal(h.writes[0].firstFrameAssetId,'9007199254740993');
  assert.equal(h.writes[0].description,'生成前最后填写');
  const fresh=harness({cloud:h.cloud});await tick();assert.equal(fresh.page.data.description,'生成前最后填写');assert.equal(fresh.page.data.firstFrameAssetId,'9007199254740993');
});

test('unload can finish a deliberate edit after its initial draft read without overwriting a newer remote draft',async()=>{
  for(const exists of [false,true]){
    const remote=later(),h=harness({read:()=>remote.promise});h.page.inputDescription({detail:{value:'退出前的新填写'}});
    const closing=h.page.onUnload();remote.resolve({exists,revision:exists?3:0,mode:'frames',description:'已有云端新版',parameters:{duration:8,resolution:'768P',ratio:'9:16'}});await closing;await tick();
    assert.equal(h.writes.length,exists?0:1);assert.equal(h.disk.get('actor:zs-video-draft-v2:new').form.description,'退出前的新填写');
    if(exists)assert.equal(h.disk.get('actor:zs-video-draft-v2:new').conflict,true);
  }
});

test('identity changes cancel a final upload or any remaining queued cloud save',async()=>{
  const upload=later(),h=harness({upload:()=>upload.promise});await tick();h.page.setData({description:'旧身份草稿',firstFrame:'/saved/new.png'});
  const closing=h.page.onUnload();h.invalidate();upload.resolve('901');await closing;await tick();assert.equal(h.writes.length,0);
  const ack=later(),pending=harness({save:()=>ack.promise});await tick();pending.page.inputDescription({detail:{value:'已发送的旧版本'}});
  const first=pending.page.saveDraft();pending.page.inputDescription({detail:{value:'不得用新身份保存'}});const finishing=pending.page.onUnload();pending.invalidate();ack.resolve({revision:1});
  await first;await finishing;await tick();assert.equal(pending.writes.length,1);
});
