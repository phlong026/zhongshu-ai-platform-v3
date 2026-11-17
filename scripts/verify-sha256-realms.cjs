const test=require('node:test'),assert=require('node:assert/strict'),fs=require('fs'),vm=require('vm'),crypto=require('node:crypto');
function finance(){const context={module:{exports:{}},require:()=>({})};vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/api/finance.js'),'utf8'),context);return context.module.exports;}
test('native file buffers from another JS context hash their complete bytes',()=>{
  const hash=finance().sha256Hex;
  for(const size of [0,3,63,64,65,4096]){
    const buffer=vm.runInNewContext(`Uint8Array.from({length:${size}},(_,i)=>i%256).buffer`);
    assert.equal(hash(buffer),crypto.createHash('sha256').update(Buffer.from(buffer)).digest('hex'));
  }
  const view=vm.runInNewContext('new DataView(new Uint8Array([0,97,98,99,0]).buffer,1,3)');
  assert.equal(hash(view),crypto.createHash('sha256').update('abc').digest('hex'));
  assert.throws(()=>hash({byteLength:3,[Symbol.toStringTag]:'ArrayBuffer'}),/二进制字节/);
});
test('Hifly upload uses the real hash implementation across file API realms',async()=>{
  const bytes=vm.runInNewContext('new Uint8Array([97,98,99]).buffer'),calls=[];
  const api={clones:{uploadTicket:async(_,body)=>{calls.push(body);return{uploadId:'1',uploadUrl:'https://upload.hifly.cc/test.mp3',contentType:'audio/mp3'}},completeUpload:async()=>({status:'UPLOADED'})}};
  const context={module:{exports:{}},require:name=>name.endsWith('/finance')?finance():name.endsWith('/clone-media')?require('../miniprogram/utils/clone-media'):name.endsWith('/sha256-stream')?require('../miniprogram/utils/sha256-stream'):api,wx:{getFileSystemManager:()=>({readFile:o=>o.success({data:bytes})}),request:o=>o.success({statusCode:200})}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/utils/hifly-upload.js'),'utf8'),context);
  assert.equal(await context.module.exports('/test.mp3','VOICE','person'),'1');
  assert.equal(calls[0].sha256,crypto.createHash('sha256').update('abc').digest('hex'));
});
