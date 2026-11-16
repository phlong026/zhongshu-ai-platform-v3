const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function harness({size=64,url='https://upload.hifly.cc/sample.mp3?Signature=fixture',contentType='audio/mp3',putStatus=200,alive=()=>true}={}) {
  const calls=[];const api={clones:{uploadTicket:async(person,body)=>{calls.push(['ticket',person,body]);return {uploadId:'90071992547409931',uploadUrl:url,contentType};},completeUpload:async id=>{calls.push(['complete',id]);return {status:'UPLOADED'};}}};
  const ctx={module:{exports:{}},require:p=>p.endsWith('/content')?api:{sha256Hex:()=> 'a'.repeat(64)},wx:{getFileSystemManager:()=>({readFile:o=>o.success({data:new ArrayBuffer(size)})}),request:o=>{calls.push(['put',o]);o.success({statusCode:putStatus});}}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/utils/hifly-upload.js'),'utf8'),ctx);
  return {calls,run:(kind='VOICE')=>ctx.module.exports('/local/owned.mp3',kind,'21',alive)};
}
test('owned media goes directly to Hifly using its exact MIME and no platform credentials',async()=>{
  const h=harness();assert.equal(await h.run(),'90071992547409931');assert.deepEqual(h.calls.map(c=>c[0]),['ticket','put','complete']);
  assert.equal(h.calls[0][1],'21');const req=h.calls[1][1];assert.equal(req.method,'PUT');assert.equal(req.data.byteLength,64);
  assert.deepEqual(Object.keys(req.header),['Content-Type']);assert.equal(req.header['Content-Type'],'audio/mp3');assert.match(req.url,/^https:\/\/upload\.hifly\.cc\//);
});
test('foreign hosts, expired PUTs and stale sessions never become completed uploads',async()=>{
  for(const url of ['https://storage.example/ours','https://upload.hifly.cc.evil.example/a','/admin-api/franchise/assets/1/content']){const h=harness({url});await assert.rejects(h.run(),/直传通道/);assert.equal(h.calls.length,1);}
  const h=harness({putStatus:403});await assert.rejects(h.run(),/上传失败/);assert.equal(h.calls.filter(c=>c[0]==='complete').length,0);
  const stale=harness({alive:()=>false});await assert.rejects(stale.run(),e=>e.cancelled===true);assert.equal(stale.calls.length,0);
});
test('size and type checks run before requesting a vendor grant',async()=>{
  for(const size of [0,20*1024*1024+1]){const h=harness({size});await assert.rejects(h.run(),/素材大小/);assert.equal(h.calls.length,0);}
  const video=harness({url:'https://upload.hifly.cc/a.mp4',contentType:'video/mp4'});await video.run('AVATAR');
});
test('clone page uses direct uploads and freezes the local upload receipt in retries',()=>{
  const source=fs.readFileSync(require.resolve('../miniprogram/pages/studio/characters/index.js'),'utf8');
  assert.match(source,/utils\/hifly-upload/);assert.doesNotMatch(source,/utils\/content-upload/);assert.match(source,/sourceUploadId:this.data.cloneUploadId/);
});
