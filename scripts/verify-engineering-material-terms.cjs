const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const {createRequire}=require('node:module');
function harness(hooks={}){
 const calls=[],filename=path.resolve(__dirname,'../miniprogram/utils/platform-engineering.js'),module={exports:{}},real=createRequire(filename);
 const storage=Object.create(null);
 global.wx={getStorageSync:k=>storage[k]||'',setStorageSync:(k,v)=>{storage[k]=v;},removeStorageSync:k=>{delete storage[k];},getSystemInfoSync:()=>({})};
 const http=real('../api/http');http.saveSession({token:'TEST-ONLY-session',postId:'original-finance',tenantId:'1'});
 // Stub models the existing uploadVoucher contract: same session/file reuses completed asset.
 const api={materials:{terms:async(id,body,key)=>{calls.push({id,body,key});if(hooks.terms)await hooks.terms({id,body,key,http});return{id};}},uploadVoucher:async(p,type)=>{calls.push({upload:p,type});if(hooks.upload)await hooks.upload(http);return'9007199254740999';}};
 vm.runInNewContext(fs.readFileSync(filename,'utf8'),{module,require:n=>n==='../api/franchise/engineering'?api:real(n),wx:{getStorageSync:()=>'',getSystemInfoSync:()=>({})}});
 return{P:module.exports,calls,http,storage};
}
function bundle(){return{detail:{id:'91',nodes:[]},collections:{stages:[],records:[]},settle:{},materials:{plans:[{materialPlanId:'92',arrival:'待到货',lines:[{materialId:'93',supplyId:'94',name:'TEST ONLY 水泥',quantity:10,remaining:10,paidCents:0,totalCents:0,termsLocked:false}]}]},followups:[]};}
test('actual unpaid supply exposes procurement only to finance terms role, not engineer/read-only',()=>{
 const{P}=harness();assert.equal(P.recordView(bundle(),{view:'material',planId:'92',financeRole:true}).canTerms,true);
 assert.equal(P.recordView(bundle(),{view:'material',planId:'92',writeRole:true}).canTerms,false);
 assert.equal(P.recordView(bundle(),{view:'material',planId:'92'}).canTerms,false);
 assert.equal(P.recordView(bundle(),{view:'material',planId:'92',financeRole:true,termsRole:false}).canTerms,false);
 assert.equal(P.recordView(bundle(),{view:'material',planId:'92',termsRole:true}).canTerms,true);
 const b=bundle();b.materials.plans[0].lines[0].termsLocked=true;
 assert.equal(P.recordView(b,{view:'material',planId:'92',financeRole:true}).canTerms,false);
});
test('active payment locks procurement after net-zero refund; missing lock projection fails closed; voided payment explicitly unlocks',()=>{
 const{P}=harness(),b=bundle(),line=b.materials.plans[0].lines[0],ctx={view:'material',planId:'92',financeRole:true};
 line.paidCents=0;line.termsLocked=true;
 assert.equal(P.recordView(b,ctx).canTerms,false);assert.equal(P.recordView(b,ctx).form.lines[0].termsLocked,true);
 delete line.termsLocked;assert.equal(P.recordView(b,ctx).canTerms,false);assert.equal(P.recordView(b,ctx).form.lines[0].termsLocked,true);
 line.termsLocked='false';assert.equal(P.recordView(b,ctx).canTerms,false);
 line.termsLocked=false;assert.equal(P.recordView(b,ctx).canTerms,true);assert.equal(P.recordView(b,ctx).form.lines[0].termsLocked,false);
 line.cancelled=true;assert.equal(P.recordView(b,ctx).canTerms,false);
 line.cancelled=false;delete line.supplyId;assert.equal(P.recordView(b,ctx).canTerms,false);
});
test('real HTTP session switch after first line stops the old invocation before its second write',async()=>{
 const committed=new Map();let switchAfterFirst=true;
 const{P,calls}=harness({terms:async({id,body,key,http})=>{
  if(committed.has(key))assert.deepEqual(body,committed.get(key));else committed.set(key,body);
  if(id==='94'&&switchAfterFirst){switchAfterFirst=false;http.saveSession({postId:'different-finance'});}
 }});
 const f={lines:[{id:'93',supplyId:'94',supplier:'TEST ONLY',unitPrice:'2'},{id:'95',supplyId:'96',supplier:'TEST ONLY',unitPrice:'3'}],file:{path:'/real.pdf',name:'TEST.pdf'}};
 await assert.rejects(P.perform('material-terms','91',f,null,'stable'),e=>/身份已切换/.test(e.msg||e.message));
 assert.deepEqual(calls.filter(c=>c.id).map(c=>c.id),['94']);assert.equal(committed.size,1);
});
test('same live session network retry preserves each body/key and does not duplicate a previously successful payable',async()=>{
 const committed=new Map();let failSecond=true;
 const{P,calls}=harness({terms:async({id,body,key})=>{
  if(id==='96'&&failSecond){failSecond=false;throw Error('TEST ONLY network failure');}
  if(committed.has(key))assert.deepEqual(body,committed.get(key));else committed.set(key,body);
 }});
 const f={lines:[{supplyId:'94',supplier:'TEST',unitPrice:'2'},{supplyId:'96',supplier:'TEST',unitPrice:'3'}],file:{path:'/real.pdf',name:'TEST.pdf'}};
 await assert.rejects(P.perform('material-terms','91',f,null,'stable'),/network failure/);assert.equal(committed.size,1);
 await P.perform('material-terms','91',f,null,'stable');assert.equal(committed.size,2,'stubbed backend models two idempotent payable effects');
 const rows=calls.filter(c=>c.id);assert.deepEqual(rows.map(r=>r.key),['stable-s94','stable-s96','stable-s94','stable-s96']);assert.deepEqual(rows[0].body,rows[2].body);assert.deepEqual(rows[1].body,rows[3].body);
});
test('post changed during upload, even without a session-generation increment, prevents all terms writes',async()=>{
 const{P,calls}=harness({upload:async()=>{global.wx.setStorageSync('zs-api-post-v1','different-finance');}});
 await assert.rejects(P.perform('material-terms','91',{lines:[{supplyId:'94',supplier:'TEST',unitPrice:'2'}],file:{path:'/real.pdf',name:'TEST.pdf'}},null,'stable'),e=>/身份已切换/.test(e.msg||e.message));
 assert.equal(calls.filter(c=>c.id).length,0);
});
test('relogin during upload rejects the old invocation even when its post ID stays unchanged',async()=>{
 const{P,calls}=harness({upload:async http=>{http.saveSession({token:'TEST-ONLY-new-login',postId:'original-finance'});}}),f={lines:[{supplyId:'94',supplier:'TEST',unitPrice:'2'}],file:{path:'/real.pdf',name:'TEST.pdf'}};
 await assert.rejects(P.perform('material-terms','91',f,null,'stable'),e=>/身份已切换/.test(e.msg||e.message));
 assert.equal(calls.filter(c=>c.id).length,0);assert.equal(calls.filter(c=>c.upload).length,1);
});
test('money edits change command fingerprint; refreshed line statistics do not; row retry keys fit eighty chars',async()=>{
 const{P,calls}=harness(),f={lines:[{id:'93',supplyId:'94',supplier:'TEST ONLY',unitPrice:'2',freight:'0'}],file:{path:'/real.pdf',name:'TEST.pdf'}};
 const fp=P.idemFingerprint('material-terms',f);
 assert.equal(P.idemFingerprint('material-terms',{...f,lines:[{...f.lines[0],received:3,remaining:7}]}),fp);
 assert.notEqual(P.idemFingerprint('material-terms',{...f,lines:[{...f.lines[0],unitPrice:'3'}]}),fp);
 await P.perform('material-terms','91',f,null,'k'.repeat(80));await P.perform('material-terms','91',f,null,'k'.repeat(80));
 const terms=calls.filter(c=>c.id);assert.equal(terms[0].key.length,80);assert.equal(terms[1].key,terms[0].key);assert.deepEqual(terms[1].body,terms[0].body);
});
test('line inputs preserve actual supply IDs and locked money; finance receives real line terms and stable per-supply key',async()=>{
 const{P,calls}=harness(),v=P.recordView(bundle(),{view:'material',planId:'92',financeRole:true});
 assert.equal(v.form.lines[0].supplyId,'94');
 await P.perform('material-terms','91',{lines:[{id:'93',supplyId:'94',supplier:'TEST ONLY 供应商',unitPrice:'12.50',freight:'3',locked:false}],file:{path:'/real-temp.pdf',name:'TEST ONLY.pdf'}},null,'stable');
 const term=calls.find(c=>c.id);assert.equal(term.id,'94');assert.equal(term.body.unitPrice,12.5);assert.equal(term.body.freight,3);assert.match(term.body.basis,/9007199254740999/);assert.equal(term.key,'stable-s94');assert.equal(calls[0].type,'FIN_VOUCHER');
});
test('invalid later line or missing real evidence prevents every procurement write',async()=>{
 const{P,calls}=harness();await assert.rejects(P.perform('material-terms','91',{lines:[{supplyId:'1',supplier:'TEST',unitPrice:'1'},{supplyId:'2',supplier:'',unitPrice:'2'}],file:{path:'/temp.pdf',name:'TEST.pdf'}},null,'k'));assert.equal(calls.length,0);
 await assert.rejects(P.perform('material-terms','91',{lines:[{supplyId:'1',supplier:'TEST',unitPrice:'1'}]},null,'k'));assert.equal(calls.length,0);
});
