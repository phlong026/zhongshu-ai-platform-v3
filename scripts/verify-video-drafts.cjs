const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const Draft=require('../miniprogram/pages/ai-video/video-draft');
const tick=()=>new Promise(r=>setImmediate(r));
const later=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject};};
const moduleP={exports:{}};
vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/utils/platform-content'),'utf8'),{module:moduleP,require:()=>({}),setTimeout,Date});
const P=moduleP.exports;
const taskInput=(taskId,description,extra={})=>({taskId,kind:'ai_video',mode:'frames',description,firstFrameAssetId:'',lastFrameAssetId:'',references:[],parameters:{duration:8,resolution:'768P',ratio:'9:16'},...extra});
function harness({disk=new Map(),cloud=new Map(),read,save,task,input,upload,download,preview}={}){
  let definition,valid=true;const writes=[],submissions=[],uploads=[],downloads=[],toasts=[],inputReads=[],draftReads=[];
  const state={tasks:[]};
  const originals=new Map([['101',taskInput('101','history A')],['102',taskInput('102','history B')]]);
  const api={drafts:{videoDraft:async key=>{draftReads.push(key);return read?read(key):structuredClone(cloud.get(key)||{exists:false,revision:0});},saveVideoDraft:async body=>{
    writes.push(structuredClone(body));if(save)return save(body);
    const prior=cloud.get(body.draftKey)||{revision:0};if(body.revision!==prior.revision)throw{code:1114000011};
    const result={...prior,...body,exists:true,revision:prior.revision+1};cloud.set(body.draftKey,result);return {id:'draft',mode:body.mode,revision:result.revision};
  }},assets:{downloadUrl:async id=>{downloads.push(id);return download?download(id):{downloadUrl:'https://signed.example/'+id};}},tasks:{editableInput:async id=>{inputReads.push(id);if(input)return input(id);if(!originals.has(id))throw Error('任务不存在或无权读取');return structuredClone(originals.get(id));},create:async body=>{submissions.push(JSON.parse(JSON.stringify(body)));return{id:'task'};}}};
  const media={upload:async(path,alive)=>{uploads.push(path);return upload?upload(path,alive):String(100+uploads.length);},source:async(id,alive,options={})=>{await api.assets.downloadUrl(id);if(!alive())throw Error('身份已切换');return preview?preview(id,options):options.type==='image'?(options.localPath||'/authorized/'+id+'.png'):'';}};
  const modules={'../../utils/creation':{assets:[]},'../../utils/studio':{guard:()=>true,allowed:()=>valid,scope:()=>valid?'actor':'other',scopedKey:k=>'actor:'+k,read:()=>state,write:()=>{}},'../../utils/platform-content':P,'../../api/franchise/content':api,'../../api/http':{token:()=>true,sessionGuard:()=>()=>valid},'../../utils/content-media':media,'../../utils/storage-keys':{videoDraft:'legacy'},'../../utils/creation-text-resize':require('../miniprogram/utils/creation-text-resize'),'./video-draft':Draft};
  const wx={getStorageSync:k=>disk.get(k),setStorageSync:(k,v)=>disk.set(k,structuredClone(v)),showToast:o=>toasts.push(o.title),navigateTo(){},redirectTo(){}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/ai-video/index'),'utf8'),{Page:d=>definition=d,require:p=>{assert(p in modules,p);return modules[p]},wx,getCurrentPages:()=>[],console,Date});
  const page={...definition,data:structuredClone(definition.data),setData(values){for(const[k,v]of Object.entries(values)){if(k.startsWith('parameters[')){const i=Number(k.match(/\d+/)[0]);this.data.parameters[i].value=v;}else this.data[k]=v;}}};
  page.onLoad(task?{task}:{});
  return{page,disk,cloud,writes,submissions,uploads,downloads,toasts,inputReads,draftReads,state,wx,invalidate:()=>valid=false};
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
