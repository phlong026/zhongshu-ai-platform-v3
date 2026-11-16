const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
function read(raw){
  const module={exports:{}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/utils/studio.js'),'utf8'),{
    module,exports:module.exports,wx:{getStorageSync:key=>key==='studio:api:one'?raw:''},
    require(name){
      if(name==='./runtime-compat')return {};
      if(name==='./organization')return {has:()=>false};
      if(name==='./access')return {context:()=>({active:false})};
      if(name==='./storage-keys')return {studio:'studio',account:'account',management:'management'};
      if(name==='./clone')return value=>JSON.parse(JSON.stringify(value));
      if(name==='../api/http')return {token:()=>true};
      if(name==='../api/auth')return {currentApiPost:()=>({postId:'one',roleCode:'sales'})};
      throw Error(name);
    }
  });return module.exports.read();
}
test('legacy demo tasks and dependent works are hidden while real work and user text survive',()=>{
  const state=read({version:3,script:'我自己填写的口播稿',scriptEdited:true,tasks:[{id:'task-complete',script:'旧演示文案'},{id:'real-task',script:'真实稿件'}],
    works:[{id:'demo-work',taskId:'task-complete'},{id:'real-work',taskId:'real-task'}],records:[{id:'fake-record',mock:true},{id:'real-record'}]});
  assert.deepEqual(Array.from(state.tasks,row=>row.id),['real-task']);
  assert.deepEqual(Array.from(state.works,row=>row.id),['real-work']);
  assert.deepEqual(Array.from(state.records,row=>row.id),['real-record']);
  assert.equal(state.script,'我自己填写的口播稿');
});
test('unedited text copied from an identified demo task is cleared, but API generated text is retained',()=>{
  const raw={version:3,script:'旧演示文案',tasks:[{id:'mock-video',demo:true,script:'旧演示文案'}]};
  assert.equal(read(structuredClone(raw)).script,'');
  assert.equal(read({...raw,rewriteHistory:[{online:true,script:'旧演示文案'}]}).script,'旧演示文案');
});
