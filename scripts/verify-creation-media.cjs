const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
function uploadHarness({size=32,url='/admin-api/franchise/assets/101/content?token=fixture',status='ACCEPTED',alive=()=>true}={}){
  const calls=[];const api={assets:{uploadTicket:async body=>{calls.push(['ticket',body]);return {assetId:'101',uploadUrl:url};},complete:async id=>{calls.push(['complete',id]);return {status};}}};
  const context={module:{exports:{}},require:name=>name.includes('/franchise/content')?api:name.includes('/http')?{base:()=> 'https://platform.example/admin-api',token:()=> 'private-test-token',currentPostId:()=> '21',tenantId:()=> '11'}:{sha256Hex:()=> 'a'.repeat(64)},wx:{getFileSystemManager:()=>({readFile:o=>o.success({data:new ArrayBuffer(size)})}),request:o=>{calls.push(['put',o]);o.success({statusCode:200});}}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/utils/content-upload.js'),'utf8'),context);
  return {calls,run:(mime='audio/mpeg')=>context.module.exports('/local/sample',mime,alive)};
}
test('content upload sends raw bytes, signed B headers and completes before exposing an asset ID',async()=>{
  const h=uploadHarness();assert.equal(await h.run(),'101');assert.deepEqual(h.calls.map(c=>c[0]),['ticket','put','complete']);
  const request=h.calls[1][1];assert.equal(request.method,'PUT');assert.equal(request.data.byteLength,32);assert.equal(request.header.Authorization,'Bearer private-test-token');assert.equal(request.header['X-Post-Id'],'21');assert.equal(h.calls[0][1].sizeBytes,32);
});
test('external signed upload never receives platform credentials',async()=>{
  const h=uploadHarness({url:'https://storage.example/signed'});await h.run();assert.equal(h.calls[1][1].header.Authorization,undefined);assert.equal(h.calls[1][1].header['X-Post-Id'],undefined);
});
test('voice over 20MB, empty bytes and video over 50MB fail before ticket or transfer',async()=>{
  for(const [size,mime] of [[20*1024*1024+1,'audio/mpeg'],[0,'audio/mpeg'],[50*1024*1024+1,'video/mp4']]){const h=uploadHarness({size});await assert.rejects(h.run(mime),/素材大小/);assert.equal(h.calls.length,0);}
});
test('an unaccepted upload cannot create a usable asset and stale sessions never transfer bytes',async()=>{
  const rejected=uploadHarness({status:'PENDING'});await assert.rejects(rejected.run(),/尚未通过校验/);
  const stale=uploadHarness({alive:()=>false});await assert.rejects(stale.run(),e=>e.cancelled===true);assert.equal(stale.calls.length,0);
});
function voiceHarness(duration,alive=()=>true){let callback,destroyed=false,played=false;const audio={duration,onCanplay:fn=>callback=fn,onError(){},destroy(){destroyed=true;},play(){played=true;},set src(_){queueMicrotask(()=>callback());}};const context={module:{exports:{}},setTimeout,clearTimeout,wx:{createInnerAudioContext:()=>audio}};vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/pages/studio/characters/clone-media.js'),'utf8'),context);return {run:()=>context.module.exports.checkVoice('/local/voice.mp3',1024,alive),destroyed:()=>destroyed,played:()=>played};}
test('voice duration accepts 5 seconds through 3 minutes without playback and destroys its reader',async()=>{
  for(const duration of [5,60,180]){const h=voiceHarness(duration);await h.run();assert.equal(h.destroyed(),true);assert.equal(h.played(),false);}
});
test('voice duration rejects short, long and canceled-session samples',async()=>{
  for(const duration of [4.9,180.1]){const h=voiceHarness(duration);await assert.rejects(h.run(),/5秒至3分钟/);assert.equal(h.destroyed(),true);}
  const h=voiceHarness(60,()=>false);await assert.rejects(h.run(),e=>e.cancelled===true);assert.equal(h.destroyed(),true);
});
