const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto'),vm=require('node:vm'),fs=require('node:fs');
const media=require('../miniprogram/pages/studio/characters/clone-media'),{Sha256,hashFile}=require('../miniprogram/utils/sha256-stream');
test('incremental SHA agrees with independent crypto across blocks, contexts and partial chunks',async()=>{
 for(const size of [0,1,55,56,63,64,65,127,128,1024*1024+37]){
  const data=Buffer.alloc(size);for(let i=0;i<size;i++)data[i]=i%251;
  const h=new Sha256();for(let p=0;p<size;p+=997)h.update(data.subarray(p,p+997));
  assert.equal(h.hex(),crypto.createHash('sha256').update(data).digest('hex'));assert.equal(h.hex(),h.hex());
 }
 const bytes=vm.runInNewContext('new Uint8Array([97,98,99]).buffer');assert.equal(new Sha256().update(bytes).hex(),crypto.createHash('sha256').update('abc').digest('hex'));
 const data=Buffer.alloc(2*1024*1024+33,7),reads=[];
 const manager={readFile:o=>{reads.push(o.length);o.success({data:data.subarray(o.position,o.position+o.length)})}};
 assert.equal(await hashFile(manager,'test',data.length,()=>true),crypto.createHash('sha256').update(data).digest('hex'));assert.ok(reads.every(n=>n<=1024*1024));
 await assert.rejects(hashFile(manager,'test',10,()=>false),e=>e.cancelled===true);
});
test('all five official formats use their own MIME and reject unrelated kinds',()=>{
 for(const [ext,kind,mime] of [['mp4','AVATAR','video/mp4'],['mov','AVATAR','video/mov'],['mp3','VOICE','audio/mpeg'],['m4a','VOICE','audio/m4a'],['wav','VOICE','audio/wav']])assert.equal(media.formatOf('/tmp/no-extension',kind,'Sample.'+ext.toUpperCase()).mimeType,mime);
 assert.throws(()=>media.formatOf('a.mov','VOICE'),/声音/);assert.throws(()=>media.formatOf('a.avi','AVATAR'),/MP4/);
});
test('video duration, portrait/landscape bounds and known HEVC are checked',()=>{
 for(const info of [{duration:5,width:640,height:360},{duration:1800,width:2160,height:3840},{duration:60,width:4096,height:2160}])media.validateVideo(info);
 for(const info of [{duration:4,width:640,height:360},{duration:1801,width:640,height:360},{duration:60,width:320,height:240},{duration:60,width:5000,height:2500},{duration:60,width:640,height:360,type:'h265'},{duration:0,width:640,height:360}])assert.throws(()=>media.validateVideo(info));
});
test('500MiB file digest stays chunked and agrees with independent SHA without a full-size allocation',async()=>{
 const chunk=Buffer.alloc(1024*1024,3),reference=crypto.createHash('sha256');for(let i=0;i<500;i++)reference.update(chunk);
 let reads=0;const fs={readFile:o=>{assert.equal(o.length,chunk.length);assert.equal(o.position,reads*chunk.length);reads++;o.success({data:chunk});}};
 assert.equal(await hashFile(fs,'large-test.mov',500*chunk.length,()=>true),reference.digest('hex'));assert.equal(reads,500);
});
