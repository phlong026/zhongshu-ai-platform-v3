const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const base='../miniprogram/design-migration/';
function harness(){
 const storage=new Map(),routes=[],counts={projects:0,jobs:0,quotes:0};let page,jobFails=true,navFails=true;
 const drafts={sessionScope:()=> 'actor-a',id:s=>/^\d+$/.test(s)?s:''};
 const wx={getStorageSync:k=>storage.has(k)?JSON.parse(storage.get(k)):null,setStorageSync:(k,v)=>storage.set(k,JSON.stringify(v)),removeStorageSync:k=>storage.delete(k),showToast(){},redirectTo:o=>{routes.push(o.url);navFails?o.fail():o.success();}};
 const load=(file,deps)=>{const module={exports:{}};vm.runInNewContext(fs.readFileSync(require.resolve(base+file),'utf8'),{module,require:p=>deps[p],wx,Uint8Array,unescape,encodeURIComponent});return module.exports;};
 const attempts=load('utils/generation-attempt',{'./budget-draft':drafts,'./sha256':require(base+'utils/sha256')});
 const context=load('utils/customer-context',{'./budget-draft':drafts});
 const dependencies={'../../utils/access':{protectedPage:p=>page=p},'../../utils/context':{getApp:()=>({globalData:{}})},'../../utils/api':{createProject:async()=>{counts.projects++;return{projectId:'42'};}},'../../utils/generation-attempt':attempts,'../../utils/generation-options':require(base+'utils/generation-options'),'../../utils/design-inputs':require(base+'utils/design-inputs'),'../../utils/generation-price':{confirm:async()=>{counts.quotes++;if(!page.data.quoteReady)throw Error('quote unavailable');return{};}},'../../utils/budget-draft':drafts,'../../utils/customer-context':context};
 load('pages/ai-design/index',dependencies);page.setData=p=>Object.assign(page.data,p);page.data.quoteReady=true;page._sourceScope='actor-a';
 const spec={slot:'initial-flat',stage:'FLAT',count:1,body:{},imageOptions:{resolution:'2K',orientation:'LANDSCAPE'},redirectUrl:'/design-migration/pages/ai-design/generating?stage=plane&count=1',createJob:async()=>{counts.jobs++;if(jobFails)throw Error('submission not confirmed');return{jobId:'71'};}};
 return{page,spec,attempts,context,storage,counts,routes,accept:()=>jobFails=false,navigate:()=>navFails=false};
}
test('customer A remains frozen when customer B resumes an identical unconfirmed project',async()=>{
 const h=harness();h.page._sourceCustomer='701';await h.page.submitAttempt(h.spec);
 assert.equal(h.attempts.read(h.spec.slot).sourceCustomerId,'701');assert.equal(h.context.read('project','42'),'701');
 h.page._sourceCustomer='702';h.page.recoverAttempt(h.attempts.read(h.spec.slot));assert.equal(h.page._sourceCustomer,'701');
 h.accept();await h.page.submitAttempt(h.spec);assert.equal(h.counts.projects,1);assert.equal(h.context.read('project','42'),'701');
 h.context.bind('project','42','702');assert.equal(h.context.read('project','42'),'701');
});
test('legacy attempts without provenance cannot acquire customer B during recovery',async()=>{
 const h=harness();const saved=h.attempts.begin(h.spec.slot,{body:{},line:'FLAT',count:1,imageOptions:h.spec.imageOptions});
 delete saved.sourceCustomerId;saved.projectId='42';h.attempts.save(h.spec.slot,saved);
 h.page._sourceCustomer='702';h.page.recoverAttempt(saved);assert.equal(h.page._sourceCustomer,'');
 h.accept();await h.page.submitAttempt(h.spec);assert.equal(h.context.read('project','42'),'');
});
module.exports={harness};

test('accepted job opens through the real button handler after navigation failure and failed quote reload',async()=>{
 const h=harness();h.accept();await h.page.submitAttempt(h.spec);
 const saved=h.attempts.read(h.spec.slot);assert.equal(saved.jobId,'71');assert.equal(h.page.data.acceptedGeneration,true);
 h.page.recoverAttempt(saved);h.page.data.quoteReady=false;h.page.data.faceWidth='invalid';h.page.data.candidateConfirming=true;
 h.navigate();await h.page.generate();
 assert.deepEqual(h.counts,{projects:1,jobs:1,quotes:1});assert.equal(h.routes.length,2);assert.equal(h.routes[0],h.routes[1]);assert.equal(h.attempts.read(h.spec.slot),null);
});
