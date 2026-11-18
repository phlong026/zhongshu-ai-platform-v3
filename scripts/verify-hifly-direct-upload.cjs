const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), vm = require('node:vm')
const source = fs.readFileSync(require.resolve('../miniprogram/pages/studio/characters/hifly-upload'), 'utf8')

function harness({index=0, type='audio/mp3', url='https://upload.hifly.cc/test', size=100, retry=false, cancel=false, changed=false}={}) {
  const events=[], id='90071992547409931'
  let valid=true, confirms=0
  const receipt={uploadId:id,name:'原件',sizeBytes:size,sha256:'a'.repeat(64),previewKey:'b'.repeat(32)}
  const api={clones:{
    fileTicket:async (_, data)=>{events.push(['ticket',data]);return {uploadId:id,uploadUrl:url,expiresAt:Date.now()+30000,contentType:type}},
    fileReceipt:async (key, data)=>{events.push(['receipt',key,data]);if(retry && !confirms++) throw {code:-1};return {status:'UPLOADED',uploadId:id}}
  }}
  const wx={showActionSheet:o=>o.success({tapIndex:index}),navigateTo:o=>{
    o.success({eventChannel:{emit:(event,grant)=>events.push([event,grant])}})
    if(changed) valid=false
    if(cancel) o.events.transferCancelled(); else o.events.transferred(receipt)
  }}
  const module={exports:{}}
  vm.runInNewContext(source,{module,require:()=>api,wx,Date})
  return {events, run:(kind,progress)=>module.exports(kind,'7',()=>valid,progress)}
}
test('all supported formats transfer only the signed grant to the embedded picker', async()=>{
  for(const [kind,index,type,extension] of [['VOICE',0,'audio/mp3','mp3'],['VOICE',1,'audio/mp4','m4a'],['VOICE',2,'audio/x-wav','wav'],['AVATAR',0,'video/mp4','mp4'],['AVATAR',1,'video/quicktime','mov']]){
    const h=harness({index,type}), result=await h.run(kind)
    assert.equal(result.id,'90071992547409931')
    assert.deepEqual({...result.preview},{uploadId:result.id,name:'原件',sizeBytes:100,sha256:'a'.repeat(64),previewKey:'b'.repeat(32),extension})
    assert.deepEqual(h.events.map(e=>e[0]),['ticket','grant','receipt'])
    assert.equal(h.events[1][1].extension,extension);assert.equal(h.events[1][1].contentType,type)
    assert.deepEqual(Object.keys(h.events[1][1]).sort(),['contentType','expiresAt','extension','uploadId','uploadUrl'])
  }
})
test('untrusted destinations and mismatched types fail before navigation',async()=>{
  for(const opts of [{url:'https://upload.hifly.cc.example/test'},{url:'/admin-api/assets'},{type:'video/mp4'}]){
    const h=harness(opts);await assert.rejects(h.run('VOICE'),/直传通道/);assert.equal(h.events.length,1)
  }
})
test('cancellation, role change and oversized receipts cannot confirm a file',async()=>{
  for(const opts of [{cancel:true},{changed:true},{size:20*1024*1024+1}]){
    const h=harness(opts);await assert.rejects(h.run('VOICE'));assert.equal(h.events.filter(e=>e[0]==='receipt').length,0)
  }
})
test('a lost receipt response reuses the original key and frozen digest',async()=>{
  const h=harness({retry:true}), phases=[]
  await h.run('VOICE',(phase,status)=>phases.push([phase,status,h.events.length]))
  assert.deepEqual(h.events[2],h.events[3])
  assert.deepEqual(phases.map(p=>[p[0],p[2]]),[[1,0],[2,1],[3,2],[3,3]])
  assert.match(phases.at(-1)[1],/重新确认/)
})

test('the upload and read-only replay routes receive separate payloads and expire on session change',()=>{
  const transfer=fs.readFileSync(require.resolve('../miniprogram/pages/studio/characters/transfer.js'),'utf8')
  let definition,active=true,backs=0
  const listeners={}
  const wx={setNavigationBarTitle(){},navigateBack(){backs++}}
  vm.runInNewContext(transfer,{wx,Page:p=>definition=p,require:key=>key.endsWith('/http')?
    {sessionGuard:()=>()=>active}:{API_BASE:'https://platform.test/admin-api'}})
  function enter(mode){
    const page={...definition,data:{},getOpenerEventChannel:()=>({on:(name,cb)=>listeners[name]=cb}),setData(p){Object.assign(this.data,p)}}
    page.onLoad({mode})
    return page
  }
  const upload=enter(),grant={uploadId:'12',uploadUrl:'https://upload.hifly.cc/test'}
  listeners.grant(grant)
  assert.match(upload.data.url,/clone-upload\.html\?keepPreview=1#/)
  const page=enter('replay'),preview={uploadId:'12',previewKey:'b'.repeat(32)}
  listeners.preview(preview)
  const url=new URL(page.data.url)
  assert.equal(url.pathname,'/admin/clone-upload.html')
  assert.deepEqual(JSON.parse(decodeURIComponent(url.hash.slice('#replay='.length))),preview)
  active=false;page.onShow();assert.equal(page.data.url,'');assert.equal(backs,1)
})
