const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const {createRequire}=require('node:module');
function harness(){
 const calls=[],filename=path.resolve(__dirname,'../miniprogram/utils/platform-engineering.js'),module={exports:{}},real=createRequire(filename);
 const api={materials:{terms:async(id,body,key)=>{calls.push({id,body,key});return{id};}},uploadVoucher:async(p,type)=>{calls.push({upload:p,type});return'9007199254740999';}};
 vm.runInNewContext(fs.readFileSync(filename,'utf8'),{module,require:n=>n==='../api/franchise/engineering'?api:real(n),wx:{getStorageSync:()=>'',getSystemInfoSync:()=>({})}});
 return{P:module.exports,calls};
}
function bundle(){return{detail:{id:'91',nodes:[]},collections:{stages:[],records:[]},settle:{},materials:{plans:[{materialPlanId:'92',arrival:'待到货',lines:[{materialId:'93',supplyId:'94',name:'TEST ONLY 水泥',quantity:10,remaining:10,paidCents:0,totalCents:0}]}]},followups:[]};}
test('actual unpaid supply exposes procurement only to finance terms role, not engineer/read-only',()=>{
 const{P}=harness();assert.equal(P.recordView(bundle(),{view:'material',planId:'92',financeRole:true}).canTerms,true);
 assert.equal(P.recordView(bundle(),{view:'material',planId:'92',writeRole:true}).canTerms,false);
 assert.equal(P.recordView(bundle(),{view:'material',planId:'92'}).canTerms,false);
 assert.equal(P.recordView(bundle(),{view:'material',planId:'92',financeRole:true,termsRole:false}).canTerms,false);
 assert.equal(P.recordView(bundle(),{view:'material',planId:'92',termsRole:true}).canTerms,true);
 const b=bundle();b.materials.plans[0].lines[0].paidCents=1;
 assert.equal(P.recordView(b,{view:'material',planId:'92',financeRole:true}).canTerms,false);
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
