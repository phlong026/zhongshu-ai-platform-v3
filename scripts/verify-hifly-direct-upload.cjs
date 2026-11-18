const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function harness({size=64,url='https://upload.hifly.cc/sample.mp3?Signature=fixture',contentType='audio/mp3',putStatus=200,putTimeout=false,confirmTimeout=false,alive=()=>true}={}) {
  const calls=[];let puts=0,confirms=0;const api={clones:{uploadTicket:async(person,body)=>{calls.push(['ticket',person,body]);return {uploadId:'90071992547409931',uploadUrl:url,contentType};},completeUpload:async id=>{calls.push(['complete',id]);if(confirmTimeout&&++confirms===1)throw {code:-1};return {status:'UPLOADED'};}}};
  const ctx={module:{exports:{}},require:p=>p.endsWith('/content')?api:p.endsWith('/clone-media')?require('../miniprogram/pages/studio/characters/clone-media'):p.endsWith('/sha256-stream')?require('../miniprogram/utils/sha256-stream'):{sha256Hex:()=> 'a'.repeat(64)},wx:{getFileSystemManager:()=>({stat:o=>o.success({stats:{size}}),readFile:o=>o.success({data:new ArrayBuffer(size)})}),request:o=>{calls.push(['put',o]);if(putTimeout&&++puts===1)return o.fail();o.success({statusCode:putStatus});}}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/studio/characters/hifly-upload.js'),'utf8'),ctx);
  return {calls,run:(kind='VOICE')=>ctx.module.exports('/local/owned.'+(kind==='AVATAR'?'mp4':'mp3'),kind,'21',alive)};
}
test('owned media goes directly to Hifly using its exact MIME and no platform credentials',async()=>{
  const h=harness();assert.equal(await h.run(),'90071992547409931');assert.deepEqual(h.calls.map(c=>c[0]),['ticket','put','complete']);
  assert.equal(h.calls[0][1],'21');const req=h.calls[1][1];assert.equal(req.method,'PUT');assert.equal(req.data.byteLength,64);
  assert.deepEqual(Object.keys(req.header),['Content-Type']);assert.equal(req.header['Content-Type'],'audio/mp3');assert.match(req.url,/^https:\/\/upload\.hifly\.cc\//);
});
test('foreign hosts, expired PUTs and stale sessions never become completed uploads',async()=>{
  for(const url of ['https://storage.example/ours','https://upload.hifly.cc.evil.example/a','/admin-api/franchise/assets/1/content']){const h=harness({url});await assert.rejects(h.run(),/直传通道/);assert.equal(h.calls.length,1);}
  const h=harness({putStatus:403});await assert.rejects(h.run(),/凭证已失效/);assert.equal(h.calls.filter(c=>c[0]==='complete').length,0);
  const stale=harness({alive:()=>false});await assert.rejects(stale.run(),e=>e.cancelled===true);assert.equal(stale.calls.length,0);
});
test('size and type checks run before requesting a vendor grant',async()=>{
  for(const size of [0,20*1024*1024+1]){const h=harness({size});await assert.rejects(h.run(),/素材大小/);assert.equal(h.calls.length,0);}
  const video=harness({url:'https://upload.hifly.cc/a.mp4',contentType:'video/mp4'});await video.run('AVATAR');
});
test('clone page uses direct uploads and freezes the local upload receipt in retries',()=>{
  const source=fs.readFileSync(require.resolve('../miniprogram/pages/studio/characters/index.js'),'utf8');
  assert.match(source,/require\('\.\/hifly-upload'\)/);assert.doesNotMatch(source,/utils\/content-upload/);assert.match(source,/sourceUploadId:this.data.cloneUploadId/);
});

test('weak network retries use the same PUT and the same completion receipt',async()=>{
  const h=harness({putTimeout:true,confirmTimeout:true});
  await h.run();
  assert.deepEqual(h.calls.map(c=>c[0]),['ticket','put','put','complete','complete']);
  assert.equal(h.calls[1][1].url,h.calls[2][1].url);
  assert.equal(h.calls[1][1].data,h.calls[2][1].data);
  assert.equal(h.calls[3][1],h.calls[4][1]);
});
