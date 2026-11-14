import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import * as runtime from '../src/runtime.mjs';

// 固定基线（2026-10-19 主模型切到 imageModel，sunburst 仅作 503 备用）：四种风格×历史/2K/4K 的 plan/generate 请求体摘要，仅内存 fetch。
test('首批平面整改保持24个立面请求体与基线完全一致', async () => {
const settings={base:'https://fixture.invalid/v1',key:'fixture',model:'text-fixture',temperature:0,imageModel:'image-fixture',imageModel2K:'image-2k',imageSizeFlat:'1024x1536',imageSizeElevation:'1536x1024'};
const journal={once:async(_k,op)=>op(),read:()=>undefined,write:()=>{}};
const requests=[];
const png=Buffer.alloc(33);Buffer.from([137,80,78,71,13,10,26,10]).copy(png);png.writeUInt32BE(13,8);png.write('IHDR',12);png.writeUInt32BE(1536,16);png.writeUInt32BE(1024,20);png[24]=8;png[25]=2;
const p=new runtime.Provider(settings,journal,async(url,init)=>{requests.push({url,body:JSON.parse(init.body)});return new Response(JSON.stringify(url.endsWith('/chat/completions')?{choices:[{message:{content:'fixed-plan'}}]}:{data:[{b64_json:png.toString('base64')}]}),{status:200});});
for(const styleCode of ['NEW_CHINESE','MODERN','CHINESE','EUROPEAN'])for(const options of [undefined,{resolution:'2K',orientation:'LANDSCAPE'},{resolution:'4K',orientation:'PORTRAIT'}]){
 const job={jobId:'1',phase:'ELEVATION',payload:{requestedCount:1}};const requirements={floorCount:2,styleCode};
 await p.plan(job,{phase:'ELEVATION',requirements},[],new AbortController().signal);
 await p.generate(job,1,'fixed-plan',[],new AbortController().signal,options,runtime.outputRules('ELEVATION',requirements));
}
assert.equal(requests.length, 24);
assert.equal(createHash('sha256').update(JSON.stringify(requests)).digest('hex'), '573607fd4fc38b51271c9c0dfda98d79ef5564b06289af42a1459d5fb123f93e');

});
