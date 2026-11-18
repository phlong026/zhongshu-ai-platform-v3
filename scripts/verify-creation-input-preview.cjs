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
  let definition,chosen,resolveUpload,rejectUpload,onProgress;const callbacks=[],patches=[];let active=true;
  const wx={showActionSheet:o=>o.success({tapIndex:0}),chooseMedia:o=>{chosen=o.success;},previewMedia:()=>{throw Error('必须使用可返回的页内预览');},createVideoContext:id=>({pause:()=>callbacks.push([id,'pause']),stop:()=>callbacks.push([id,'stop'])}),pageScrollTo(){},getStorageSync(){},removeStorageSync(){}};
  const http={token:()=>true,sessionGeneration:()=>1,requestGuard:()=>()=>active};
  const modules={};
  const upload=(kind,person,alive)=>{return new Promise((resolve,reject)=>{resolveUpload=resolve;rejectUpload=reject;});};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/studio/characters/index.js'),'utf8'),{Page:p=>definition=p,wx,setInterval:()=>1,clearInterval(){},setTimeout,clearTimeout,
    require:p=>p.endsWith('/http')?http:p.endsWith('/studio')?{scope:()=>1}:p.endsWith('/clone-media')?{checkVideo:async()=>({duration:18,width:1080,height:1920})}:p.endsWith('/hifly-upload')?upload:p.endsWith('/platform-content')?{degradeMessage:e=>e.message}:modules});
  const page={...definition,data:structuredClone(definition.data),__scope:1,__generation:1,setData(p){Object.assign(this.data,p);patches.push(p);}};
  Object.assign(page.data,{canManage:true,digitalPersona:{id:'42'},cloneKind:'AVATAR',cloneQuote:{available:true},cloneUploadId:'old',cloneConsented:true});
  return {page,patches, resolve:()=>resolveUpload({id:'new-upload',name:'person.mp4',size:1048576}),reject:()=>rejectUpload(Error('上传中断')),inactive:()=>active=false};
}
test('return from the embedded picker requires a new consent for the new file',async()=>{
  const h=setup();const done=h.page.uploadClone();
  assert.equal(h.page.data.cloneUploadId,'');assert.equal(h.page.data.cloneConsented,false);
  h.resolve();await done;assert.equal(h.page.data.cloneUploadId,'new-upload');assert.equal(h.page.data.cloneFileName,'person.mp4');
  assert.equal(h.page.data.cloneUploading,false);
});
test('failed replacement cannot submit the previous file',async()=>{
  const h=setup();const done=h.page.uploadClone();h.reject();await done;
  assert.equal(h.page.data.cloneUploadId,'');assert.match(h.page.data.cloneError,/上传中断/);
});
test('returning after an account switch cannot restore previous media',async()=>{
  const h=setup();const done=h.page.uploadClone();h.inactive();const count=h.patches.length;h.resolve();await done;
  assert.equal(h.patches.length,count);
});
test('both native and compatibility manual editors use explicit keyboard avoidance and a bounded input',()=>{
  for(const file of ['templates/sales-creation.wxml','pages/creation/index.wxml','pages/studio/index.wxml']){
    const xml=fs.readFileSync('miniprogram/'+file,'utf8');assert.match(xml,/bindkeyboardheightchange="editorKeyboardChange"/);assert.match(xml,/adjust-position="\{\{false\}\}"/);assert.match(xml,/editorKeyboardBottom/);
  }
});
