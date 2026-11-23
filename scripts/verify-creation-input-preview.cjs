const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const keyboard=require('../miniprogram/utils/creation-keyboard');
const resizer=require('../miniprogram/utils/creation-text-resize');
const linkDraft=require('../miniprogram/utils/creation-link-draft');
test('text resize drags, clamps and toggles without changing text or toggling after a drag',()=>{
  global.wx={getWindowInfo:()=>({windowHeight:667})};
  const p={...resizer.handlers,data:{original:'保留正在输入的原文'},setData(patch){for(const [key,value]of Object.entries(patch)){const parts=key.split('.');if(parts.length===2)(this.data[parts[0]]||=( {}))[parts[1]]=value;else this.data[key]=value;}}};
  const event=y=>({currentTarget:{dataset:{field:'original',normal:160}},touches:[{clientY:y}]});
  p.startTextResize(event(100));p.moveTextResize(event(220));p.endTextResize();assert.equal(p.data.textHeights.original,280);
  p.toggleTextResize(event(220));assert.equal(p.data.textHeights.original,280);
  p.toggleTextResize(event(220));assert.equal(p.data.textHeights.original,160);
  p.startTextResize(event(100));p.moveTextResize(event(2000));assert.equal(p.data.textHeights.original,533);
  p.moveTextResize(event(-1000));assert.equal(p.data.textHeights.original,80);
  assert.equal(p.data.original,'保留正在输入的原文');delete global.wx;
});
test('failed ASR retries preserve completed link parsing, while uncertain requests keep both keys',()=>{
  const initial=linkDraft.attempt('retry-test',1,'https://v.douyin.com/test',()=> 'first');
  assert.equal(linkDraft.attempt('retry-test',1,initial.url,()=> 'ignored'),initial);
  linkDraft.failed('retry-test',1,initial,'asr',()=> 'second');
  const retry=linkDraft.attempt('retry-test',1,initial.url,()=> 'ignored');
  assert.equal(retry.parseKey,initial.parseKey);assert.equal(retry.asrKey,'second-asr');
  linkDraft.failed('retry-test',1,initial,'asr',()=> 'stale');
  assert.equal(linkDraft.attempt('retry-test',1,initial.url,()=> 'ignored'),retry);
  const changed=linkDraft.attempt('retry-test',2,initial.url,()=> 'new-account');
  linkDraft.failed('retry-test',1,retry,'parse',()=> 'old-account');
  assert.equal(linkDraft.attempt('retry-test',2,initial.url,()=> 'ignored'),changed);
});
test('editor clears keyboard insets and compensates only the unresized viewport',()=>{
  const ios=keyboard.layout(667,667,300),android=keyboard.layout(667,367,300);
  assert.equal(ios.editorKeyboardBottom,300);assert.equal(android.editorKeyboardBottom,0);
  assert.equal(ios.editorSheetHeight,347);assert.equal(android.editorSheetHeight,347);
  assert.ok(ios.editorInputHeight>=48);assert.equal(keyboard.layout(667,667,0).editorKeyboardBottom,0);
  for(const base of [568,667,844]){const x=keyboard.layout(base,base,320);assert.ok(x.editorSheetHeight+320<=base);}
});
function setup(){
  let definition,chosen,resolveUpload,rejectUpload,onProgress,timer,now=1000;const callbacks=[],patches=[];let active=true;
  const wx={showActionSheet:o=>o.success({tapIndex:0}),chooseMedia:o=>{chosen=o.success;},previewMedia:()=>{throw Error('必须使用可返回的页内预览');},createVideoContext:id=>({pause:()=>callbacks.push([id,'pause']),stop:()=>callbacks.push([id,'stop'])}),pageScrollTo(){},getStorageSync(){},removeStorageSync(){}};
  const http={tenantId:()=>1,token:()=>true,sessionGeneration:()=>1,requestGuard:()=>()=>active,sessionGuard:()=>()=>active};
  wx.navigateTo=opt=>opt.success({eventChannel:{emit:(event,source)=>callbacks.push([opt.url,event,source])}});
  const modules={};
  const upload=(kind,person,alive,progress)=>{onProgress=progress;return new Promise((resolve,reject)=>{resolveUpload=resolve;rejectUpload=reject;});};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/studio/characters/index.js'),'utf8'),{Page:p=>definition=p,wx,Date:class extends Date {static now(){return now;}},
    setInterval(fn){timer=fn;return 1;},clearInterval(){timer=null;},setTimeout,clearTimeout,
    require:p=>p.endsWith('/http')?http:p.endsWith('/studio')?{scope:()=>1,guard:()=>true}:p.endsWith('/auth')?{currentApiPost:()=>({orgId:1,userId:2,postId:3})}:p.endsWith('/clone-media')?{checkVideo:async()=>({duration:18,width:1080,height:1920})}:p.endsWith('/clone-upload')?upload:p.endsWith('/platform-content')?{degradeMessage:e=>e.message}:modules});
  const page={...definition,data:structuredClone(definition.data),__scope:1,__generation:1,setData(p){Object.assign(this.data,p);patches.push(p);}};
  Object.assign(page.data,{canManage:true,digitalPersona:{id:'42'},cloneKind:'AVATAR',cloneQuote:{available:true},cloneUploadId:'old',cloneConsented:true});
  return {page,upload,patches,callbacks,advance:ms=>{now+=ms;timer?.();},running:()=>!!timer,phase:(n,text,state)=>onProgress(n,text,state),
    resolve:()=>resolveUpload({id:'new-upload',name:'person.mp4',size:1048576,preview:{uploadId:'new-upload'}}),reject:()=>rejectUpload(Error('上传中断')),cancel:()=>rejectUpload({cancelled:true}),inactive:()=>active=false};
}
test('return from the embedded picker requires a new consent for the new file',async()=>{
  const h=setup();const done=h.page.uploadClone();
  assert.equal(h.page.data.cloneUploadId,'');assert.equal(h.page.data.cloneConsented,false);
  h.resolve();await done;assert.equal(h.page.data.cloneUploadId,'new-upload');assert.equal(h.page.data.cloneFileName,'person.mp4');
  assert.equal(h.page.data.cloneUploading,false);
  h.page.replayUpload();
  assert.deepEqual(h.callbacks.at(-1),['/pages/studio/characters/transfer?mode=replay','preview',{uploadId:'new-upload'}]);
  const count=h.callbacks.length;
  h.page.data.cloneUploadId='other';h.page.replayUpload();
  h.page.data.cloneUploadId='new-upload';h.page.__closed=true;h.page.replayUpload();
  assert.equal(h.callbacks.length,count);
});
test('failed replacement cannot submit the previous file',async()=>{
  const h=setup();const done=h.page.uploadClone();h.reject();await done;
  assert.equal(h.page.data.cloneUploadId,'');assert.match(h.page.data.cloneError,/上传中断/);
  assert.equal(h.running(),false);
});
test('opening the picker and waiting for file selection never starts upload activity or elapsed time',async()=>{
  const h=setup(),done=h.page.uploadClone();
  assert.equal(h.page.data.cloneUploadPhase,0);
  assert.equal(h.page.data.cloneSelecting,true);assert.equal(h.page.data.cloneUploading,false);assert.equal(h.running(),false);
  h.phase(1,'正在申请直传凭证…');h.advance(6000);
  h.phase(2,'等待选择素材…');h.advance(6000);
  assert.equal(h.page.data.cloneUploading,false);assert.equal(h.running(),false);assert.equal(h.page.data.cloneUploadElapsed,0);
  assert.ok(h.patches.every(p=>p.cloneUploading!==true));
  h.cancel();await done;assert.equal(h.page.data.cloneSelecting,false);assert.equal(h.page.data.cloneError,'');
});
test('cancelled selection preserves the previous confirmed file and its consent',async()=>{
  for(const phase of [0,2]) {
    const h=setup(),preview={uploadId:'old',previewKey:'old-preview'};
    h.page.__clonePreview=preview;
    Object.assign(h.page.data,{cloneFileName:'原素材.mp4',cloneFileMeta:'1 MiB',canReplayUpload:true,cloneVideoPath:'wxfile://old.mp4'});
    const done=h.page.uploadClone();
    if(phase)h.phase(phase,'等待选择素材…');
    h.cancel();await done;
    assert.equal(h.page.data.cloneUploadId,'old');assert.equal(h.page.data.cloneConsented,true);
    assert.equal(h.page.data.cloneFileName,'原素材.mp4');assert.equal(h.page.data.cloneVideoPath,'wxfile://old.mp4');
    assert.equal(h.page.data.canReplayUpload,true);assert.equal(h.page.__clonePreview,preview);
    assert.equal(h.page.data.cloneError,'');assert.equal(h.running(),false);
  }
});
test('receipt confirmation starts its own elapsed time and stops on completion',async()=>{
  const h=setup(),done=h.page.uploadClone();
  h.phase(2,'等待选择素材…');h.advance(20000);
  h.phase(3,'正在确认上传回执…');
  assert.equal(h.page.data.cloneUploadElapsed,0);assert.equal(h.page.data.cloneUploading,true);assert.equal(h.page.data.cloneSelecting,false);
  h.page.__hidden=true;h.advance(6200);
  h.page.canEditPersona=()=>true;h.page.onShow();
  assert.equal(h.page.data.cloneUploadElapsed,6);
  assert.equal(h.page.data.cloneUploadPhase,3);assert.match(h.page.data.cloneUploadStatus,/确认上传回执/);
  h.advance(2300);assert.equal(h.page.data.cloneUploadElapsed,8);
  h.resolve();await done;
  assert.equal(h.running(),false);assert.equal(h.page.data.cloneUploading,false);
  h.advance(9000);assert.equal(h.page.data.cloneUploadElapsed,8);
});
test('a selected native file starts activity during upload and format processing',async()=>{
  const h=setup(),done=h.page.uploadClone();
  assert.equal(h.page.data.cloneUploading,false);
  h.phase(0,'正在读取素材…',{selected:true});
  assert.equal(h.page.data.cloneUploading,true);assert.equal(h.page.data.cloneSelecting,false);
  h.advance(2000);h.phase(2,'正在处理素材格式…',{selected:true});
  assert.equal(h.page.data.cloneUploadElapsed,2);assert.equal(h.page.data.cloneUploadStatus,'正在处理素材格式…');
  h.resolve();await done;assert.equal(h.running(),false);
});
test('returning after an account switch cannot restore previous media',async()=>{
  const h=setup();const done=h.page.uploadClone();h.inactive();const count=h.patches.length;h.resolve();await done;
  assert.equal(h.patches.length,count);
  assert.equal(h.running(),false);
});
test('an in-flight native binding preserves its accepted asset for explicit retry',async()=>{
  const h=setup(),source={assetId:'51',personaId:'42',kind:'AVATAR',name:'phone.mov',sizeBytes:1000};
  const key=h.page.receiptKey('42','AVATAR'),calls=[];
  h.page.rememberReceipt(key,source);
  h.upload.confirmReceipt=async(pending,alive,progress)=>{
    calls.push(pending);assert.equal(alive(),true);progress(3,'正在确认上传素材…',{selected:true});
    if(calls.length===1)throw {code:1114000012,message:'素材正在处理中，请稍后重试确认'};
    return {id:'90071992547409931',name:pending.name,size:800,preview:{...pending,uploadId:'90071992547409931'}};
  };
  await h.page.uploadClone();
  assert.equal(h.page.data.cloneReceiptPending,true);assert.equal(h.page.data.cloneUploadId,'');
  assert.equal(h.page.pendingReceipt(key).assetId,'51');assert.match(h.page.data.cloneError,/处理中/);
  await h.page.uploadClone();
  assert.equal(calls.length,2);assert.equal(calls[0],calls[1]);assert.equal(h.page.data.cloneUploadId,'90071992547409931');
  assert.equal(h.page.data.cloneReceiptPending,false);assert.equal(h.page.pendingReceipt(key),null);
  assert.equal(h.page.data.cloneConsented,false);
});
test('replaying a native upload uses the normalized source and ignores a stale preview request',async()=>{
  const h=setup(),source={assetId:'51',kind:'AVATAR',uploadId:'native-upload'};
  h.page.__clonePreview=source;h.page.data.cloneUploadId=source.uploadId;
  h.upload.previewSource=async()=> 'https://cdn.test/canonical.mp4';
  await h.page.replayUpload();
  assert.equal(h.page.data.cloneVideoPath,'https://cdn.test/canonical.mp4');assert.equal(h.page.data.clonePreviewOpen,true);
  let resolve;
  h.upload.previewSource=()=>new Promise(done=>{resolve=done;});
  const late=h.page.replayUpload();
  h.page.__clonePreview={assetId:'52',kind:'AVATAR',uploadId:'newer'};h.page.data.cloneUploadId='newer';
  resolve('https://cdn.test/stale.mp4');await late;
  assert.equal(h.page.data.cloneVideoPath,'https://cdn.test/canonical.mp4');
});

test('lost confirmations survive reopening the correct persona and resume creation without a second transfer',async()=>{
  const storage=new Map(), requests=[], creations=[], receipts=new Map();
  let post={orgId:'1',userId:'2',postId:'3'},tenant='11',online=false,tickets=0,transfers=0,picks=0,rejection;
  const id='90071992547409931',sha='a'.repeat(64);
  const api={clones:{
    fileTicket:async()=>{tickets++;return {uploadId:tickets===1?id:String(tickets),uploadUrl:'https://upload.hifly.cc/one',contentType:'video/mp4',expiresAt:Date.now()+60000};},
    fileReceipt:async(uploadId,body)=>{
      requests.push([uploadId,body.sizeBytes,body.sha256]);
      if(rejection)throw rejection;
      receipts.set(uploadId,body.sha256);
      if(!online)throw Object.assign(Error('response lost'),{code:-1});
      return {status:'UPLOADED',uploadId};
    },
    create:async(person,body)=>{creations.push([person,body.sourceUploadId]);return {status:'RUNNING'};}
  }};
  const wx={getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,structuredClone(v)),removeStorageSync:k=>storage.delete(k),
    showActionSheet:o=>{picks++;o.success({tapIndex:0});},
    navigateTo:o=>{o.success({eventChannel:{emit:(_event,grant)=>{transfers++;o.events.transferred({uploadId:grant.uploadId,sizeBytes:75,sha256:sha,name:'样本.mp4'});}}});}};
  const mod={exports:{}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/studio/characters/hifly-upload.js'),'utf8'),{
    module:mod,wx,require:()=>api
  });
  function enter(person='42',kind='AVATAR') {
    let definition;
    const http={tenantId:()=>tenant,token:()=>true,sessionGeneration:()=>1,requestGuard:()=>()=>true};
    const studio={scope:()=>post.postId,scopedKey:k=>k};
    const deps={'../../../api/http':http,'../../../api/auth':{currentApiPost:()=>post},'../../../api/franchise/content':api,
      '../../../utils/studio':studio,'./clone-upload':mod.exports,'../../../utils/platform-content':{degradeMessage:e=>e.message}};
    vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/studio/characters/index.js'),'utf8'),{
      Page:p=>definition=p,wx,require:key=>deps[key]||{},setInterval:()=>1,clearInterval(){}
    });
    const page={...definition,data:structuredClone(definition.data),__scope:post.postId,__generation:1,
      setData(patch){Object.assign(this.data,patch);},loadClones(){},loadQuote(){this.setData({cloneQuote:{available:true,credits:3,consentVersion:'v1'}});}};
    page.data.rows=[{id:person,name:'人物',enabled:true,canManage:true}];
    page.openDigital({currentTarget:{dataset:{id:person,kind}}});
    return page;
  }
  const first=enter();
  await first.uploadClone();
  assert.equal(first.data.cloneReceiptPending,true);
  assert.equal(first.data.cloneUploadId,'');
  assert.equal(requests.length,2);
  assert.equal(receipts.size,1);
  for(const field of ['userId','postId','orgId']){
    const saved=post;post={...saved,[field]:'other'};
    assert.equal(enter().data.cloneReceiptPending,false);
    post=saved;
  }
  tenant='12';assert.equal(enter().data.cloneReceiptPending,false);tenant='11';
  assert.equal(enter('43').data.cloneReceiptPending,false);
  assert.equal(enter('42','VOICE').data.cloneReceiptPending,false);
  online=true;
  const same=enter();
  assert.equal(same.data.cloneReceiptPending,true);
  same.data.cloneQuote={available:false,message:'网络中断'};
  await Promise.all([same.uploadClone(),same.uploadClone()]);
  assert.equal(same.data.cloneReceiptPending,false);
  assert.equal(same.data.cloneUploadId,id);
  assert.equal(same.data.cloneConsented,false);
  assert.equal(same.data.cloneQuote.available,true);
  same.consent({detail:{value:['authorized']}});
  await same.createClone();
  assert.deepEqual(creations,[['42',id]]);
  assert.deepEqual(requests,Array.from({length:3},()=>[id,75,sha]));
  assert.deepEqual([picks,tickets,transfers,receipts.size],[1,1,1,1]);
  assert.equal(storage.size,0);
  online=false;
  await first.uploadClone();
  assert.equal(first.data.cloneReceiptPending,true);
  online=true;
  await first.replaceCloneFile();
  assert.equal(first.data.cloneReceiptPending,false);
  assert.equal(first.data.cloneUploadId,'3');
  assert.deepEqual([picks,tickets,transfers],[3,3,3]);
  for(const denied of [{code:403,message:'当前岗位没有权限'},{code:1114000001,message:'上传凭证过期或文件已变化'}]){
    online=false;await first.uploadClone();
    const count=picks;
    rejection=denied;await first.uploadClone();
    assert.equal(first.data.cloneError,denied.message);
    assert.equal(first.data.cloneReceiptPending,false);
    assert.equal(picks,count);
    assert.equal(enter().data.cloneReceiptPending,false);
    rejection=null;online=true;await first.uploadClone();
    assert.equal(picks,count+1);
    assert.equal(first.data.cloneUploadId,String(count+1));
  }
  const xml=fs.readFileSync('miniprogram/pages/studio/characters/index.wxml','utf8');
  assert.match(xml,/wx:if="{{cloneReceiptPending[^"]*}}"[^>]+bindtap="replaceCloneFile">放弃本次，换一个文件/);
});
test('both native and compatibility manual editors use explicit keyboard avoidance and a bounded input',()=>{
  for(const file of ['templates/sales-creation.wxml','pages/creation/index.wxml','pages/studio/index.wxml']){
    const xml=fs.readFileSync('miniprogram/'+file,'utf8');assert.match(xml,/bindkeyboardheightchange="editorKeyboardChange"/);assert.match(xml,/adjust-position="\{\{false\}\}"/);assert.match(xml,/editorKeyboardBottom/);
  }
});

function nativeUploadHarness({kind='AVATAR',file={tempFilePath:'wxfile://original',name:'IMG_1234.MOV',size:1200000},choice=0,bindError,changedAfterUpload=false}={}) {
  const events=[],progress=[],remembered=[];
  let select,valid=true,failures=bindError?1:0;
  const wx={showActionSheet:options=>options.success({tapIndex:choice}),
    chooseMedia:options=>{assert.deepEqual(Array.from(options.mediaType),['video']);select=options;},
    chooseMessageFile:options=>{assert.equal(options.type,'all');assert.equal(options.extension,undefined);select=options;},
    navigateTo:()=>assert.fail('new uploads must not require an embedded upload domain')};
  const api={clones:{assetUpload:async(personaId,body)=>{
    events.push(['bind',personaId,JSON.parse(JSON.stringify(body))]);
    if(failures-- > 0)throw bindError;
    return {uploadId:'90071992547409931',assetId:'51',status:'UPLOADED',mimeType:kind==='AVATAR'?'video/mp4':'audio/mpeg',sizeBytes:750000};
  }},assets:{downloadUrl:async id=>({downloadUrl:'https://cdn.test/normalized/'+id})}};
  const deps={'../../../api/franchise/content':api,'../../../api/http':{base:()=> 'https://platform.test/admin-api'},
    '../../../utils/content-media':{upload:async(path,alive,opts)=>{
      assert.equal(alive(),true);events.push(['upload',path,opts.type,opts.name]);
      for(const stage of ['reading','uploading','processing','complete'])opts.onStage(stage);
      if(changedAfterUpload)valid=false;
      return '51';
    }},'./hifly-upload':{confirmReceipt:async source=>{events.push(['legacy',source.uploadId]);return {id:source.uploadId};}}};
  const module={exports:{}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/studio/characters/clone-upload.js'),'utf8'),{
    module,wx,require:key=>deps[key]
  });
  return {module,api,deps,events,progress,remembered,
    run:()=>module.exports(kind,'42',()=>valid,(...args)=>progress.push(args),source=>{remembered.push(source);events.push(['remember',source.assetId]);}),
    choose:()=>select.success({tempFiles:file?[file]:[]}),cancel:()=>select.fail({errMsg:'chooseMedia:fail cancel'}),
    change:()=>valid=false,alive:()=>valid};
}

test('native clone upload waits for a real file and forwards original phone metadata to normalization',async()=>{
  const h=nativeUploadHarness(),done=h.run();
  await Promise.resolve();
  assert.equal(h.events.length,0);assert.equal(h.progress.length,0);
  h.choose();const result=await done;
  assert.deepEqual(h.events[0],['upload','wxfile://original','video','IMG_1234.MOV']);
  assert.deepEqual(h.events.map(event=>event[0]),['upload','remember','bind']);
  assert.equal(h.remembered[0].assetId,'51');assert.equal(h.remembered[0].kind,'AVATAR');
  assert.equal(result.id,'90071992547409931');assert.equal(result.preview.assetId,'51');assert.equal(result.size,750000);
  assert.ok(h.progress.every(([, , state])=>state.selected));
  assert.ok(h.progress.some(([phase])=>phase===2));
});
test('native voice and chat video selection do not filter out common phone recording formats',async()=>{
  for(const [kind,name,choice]of [['VOICE','录音.AAC',0],['VOICE','录音.AMR',0],['AVATAR','人物.MKV',1]]) {
    const h=nativeUploadHarness({kind,choice,file:{path:'wxfile://extensionless',name,size:1000}}),done=h.run();
    await Promise.resolve();h.choose();await done;
    assert.deepEqual(h.events[0],['upload','wxfile://extensionless',kind==='VOICE'?'audio':'video',name]);
  }
});
test('cancelled, empty, oversized or stale selections cannot upload or bind a source',async()=>{
  for(const scenario of ['cancel','empty','oversize','stale']) {
    const h=nativeUploadHarness({file:scenario==='empty'?null:{path:'wxfile://video',size:scenario==='oversize'?50*1024*1024+1:100}}),done=h.run();
    await Promise.resolve();
    if(scenario==='cancel')h.cancel();else{if(scenario==='stale')h.change();h.choose();}
    await assert.rejects(done);assert.equal(h.events.length,0);assert.equal(h.progress.length,0);
  }
  const h=nativeUploadHarness({changedAfterUpload:true}),done=h.run();await Promise.resolve();h.choose();
  await assert.rejects(done);assert.deepEqual(h.events.map(e=>e[0]),['upload']);assert.equal(h.remembered.length,0);
});
test('native receipt recovery binds the same accepted asset without selecting or uploading again',async()=>{
  const h=nativeUploadHarness({bindError:{code:-1,message:'lost response'}}),done=h.run();
  await Promise.resolve();h.choose();const result=await done;
  assert.equal(result.preview.assetId,'51');assert.equal(h.events.filter(e=>e[0]==='upload').length,1);
  const binds=h.events.filter(e=>e[0]==='bind');assert.equal(binds.length,2);assert.deepEqual(binds[0],binds[1]);
  h.events.length=0;
  await h.module.exports.confirmReceipt(h.remembered[0],h.alive);
  assert.deepEqual(h.events.map(e=>e[0]),['bind']);
  await h.module.exports.confirmReceipt({uploadId:'legacy-9',sha256:'a'.repeat(64)},h.alive);
  assert.deepEqual(h.events.at(-1),['legacy','legacy-9']);
});
test('native binding rejects mismatched results and exposes the normalized preview',async()=>{
  const h=nativeUploadHarness(),done=h.run();await Promise.resolve();h.choose();const result=await done;
  assert.equal(await h.module.exports.previewSource(result.preview,h.alive),'https://cdn.test/normalized/51');
  h.api.clones.assetUpload=async()=>({uploadId:'3',assetId:'999',status:'UPLOADED'});
  await assert.rejects(h.module.exports.confirmReceipt(h.remembered[0],h.alive),/尚未确认/);
  h.change();await assert.rejects(h.module.exports.previewSource(result.preview,h.alive),e=>e.cancelled===true);
});
