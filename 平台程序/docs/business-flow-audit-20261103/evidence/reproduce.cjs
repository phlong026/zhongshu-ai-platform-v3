// Read-only reproductions. All wx operations use synthetic in-memory data.
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const crypto = require('crypto');
const root = process.argv[2];
if (!root) throw Error('Usage: node reproduce.cjs <frontend-root>');
const storage = new Map();
let request;
global.wx = {
 getStorageSync: k => storage.get(k), setStorageSync: (k,v) => storage.set(k,v), removeStorageSync:k=>storage.delete(k),
 request: o => {request=o;},
};
const http = require(path.join(root,'miniprogram/api/http.js'));
const auth = require(path.join(root,'miniprogram/api/auth.js'));
const fin = require(path.join(root,'miniprogram/api/finance.js'));
(async()=>{
 const out={scope:'E2, real source with synthetic wx; no real network, DB or filesystem mutation',cases:[]};
 http.saveSession({token:'synthetic-A',postId:'11'});
 let unauthorized=0;http.onUnauthorized(()=>unauthorized++);
 const pending=http.get('/synthetic-probe').catch(e=>e);
 request.success({statusCode:200,data:{code:401,msg:'synthetic expired'}});
 const error=await pending;
 out.cases.push({id:'M02-I02',errorCode:error.code,tokenRetained:!!http.token(),unauthorizedCallbacks:unauthorized,defectObserved:!!http.token()&&unauthorized===0});
 auth.savePosts([{postId:'11',orgId:'org-A',roleCode:'finance'}]);
 auth.saveLogin({accessToken:'synthetic-B'});
 out.cases.push({id:'M02-I03',scenario:'B token saved before posts/mine succeeds',currentPost:auth.currentApiPost(),headerPostId:http.currentPostId(),defectObserved:auth.currentApiPost().orgId==='org-A'});
 let syncError;try{fin.confirmBank('synthetic-flow','synthetic-receipt');}catch(e){syncError=e.message;}
 out.cases.push({id:'M08-I01',syncError,defectObserved:!!syncError});
 const raw=Buffer.from('%PDF-1.4\nfinance receipt\n');let claimed;
 wx.getFileSystemManager=()=>({readFile:o=>o.success({data:raw.toString('base64')}),statSync:()=>({size:raw.length})});
 http.post=async(p,data)=>{if(p.includes('upload-tickets')){claimed=data.sha256;return{assetId:'synthetic-asset',uploadUrl:'https://synthetic.invalid/put'};}return{uploadStatus:'ACCEPTED'};};
 let uploadPath;wx.request=o=>{uploadPath=o.url;o.success({statusCode:200,data:{code:0}});};
 await fin.uploadVoucher('/synthetic.pdf');
 const correct=crypto.createHash('sha256').update(raw).digest('hex');
 out.cases.push({id:'M08-I02',rawByteSha256:correct,claimedSha256:claimed,usedDevEndpoint:uploadPath.includes('/platform/dev/'),defectObserved:correct!==claimed});
 const badgeScript=path.join(root,'scripts/verify-demo-badge.cjs');const messages=[];let exitCode,mutationCount=0;
 const fakeFs=Object.create(fs);
 fakeFs.readFileSync=(file,...args)=>{let v=fs.readFileSync(file,...args);if(/miniprogram\/pages\/.+\/index\.(js|wxml)$/.test(String(file))&&typeof v==='string'&&v.includes('showDemoBadge')){v=v.replace(/showDemoBadge/g,'removedByReadOnlyProbe');mutationCount++;}return v;};
 vm.runInNewContext(fs.readFileSync(badgeScript,'utf8'),{require:n=>n==='fs'?fakeFs:require(n),__dirname:path.dirname(badgeScript),console:{log:(...a)=>messages.push(a.join(' '))},process:{exit:n=>exitCode=n}});
 out.cases.push({id:'M12-I01',mutationCount,exitCode,summary:messages.at(-1),defectObserved:mutationCount===16&&exitCode===0});
 console.log(JSON.stringify(out,null,2));
 if(out.cases.some(x=>!x.defectObserved)) process.exitCode=2;
})().catch(e=>{console.error(e);process.exitCode=1;});
