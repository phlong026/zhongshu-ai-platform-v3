import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash} from 'node:crypto'
import {fileDigest,parseGrant,sendFile,validateFile} from './transfer.js'

test('the maximum file stays file-backed while hashing and retrying a signed PUT',async()=>{
  const chunk=Buffer.alloc(1024*1024, 83), expected=createHash('sha256')
  const blocks=Array.from({length:20},()=>{expected.update(chunk);return chunk})
  const file=new File(blocks,'sample.wav')
  file.arrayBuffer=()=>{throw Error('whole-file buffering')}
  const slice=file.slice.bind(file)
  file.slice=(start,end)=>{assert.ok(end-start<=256*1024);return slice(start,end)}
  assert.equal(await fileDigest(file,()=>true),expected.digest('hex'))
  const grant={extension:'wav',uploadId:'88',contentType:'audio/wav',expiresAt:Date.now()+60000,uploadUrl:'https://upload.hifly.cc/test'}
  assert.equal(parseGrant(encodeURIComponent(JSON.stringify(grant))).uploadId,'88')
  const original=globalThis.fetch
  globalThis.window=new EventTarget()
  let puts=0,retries=0
  globalThis.fetch=async(url,opts)=>{
    assert.equal(url,grant.uploadUrl);assert.equal(opts.method,'PUT');assert.equal(opts.body,file)
    assert.equal(opts.headers['Content-Type'],grant.contentType);assert.equal(opts.redirect,'error')
    assert.equal(opts.credentials,'omit');puts++
    return new Response(null,{status:puts===1?503:200})
  }
  try {await sendFile(file,grant,()=>true,()=>retries++)} finally {globalThis.fetch=original;delete globalThis.window}
  assert.equal(puts,2);assert.equal(retries,1)
  assert.throws(()=>validateFile(new File([file,'a'],'large.wav'),grant),/20 MiB/)
})

test('expired grants and changes of upload host or file format are rejected',()=>{
  const valid={uploadId:'9',expiresAt:Date.now()+60000,extension:'mp4',contentType:'video/mp4',uploadUrl:'https://upload.hifly.cc/a'}
  for(const change of [{expiresAt:1},{uploadUrl:'https://upload.hifly.cc.evil.test/a'},{contentType:'audio/mp3'}]){
    assert.throws(()=>parseGrant(encodeURIComponent(JSON.stringify({...valid,...change}))))
  }
})
