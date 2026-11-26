const test=require('node:test'),assert=require('node:assert/strict'),fs=require('fs'),vm=require('vm'),path=require('path'),{createRequire}=require('module');
function harness(){const f=path.resolve('miniprogram/utils/platform-engineering.js'),m={exports:{}},real=createRequire(f),calls=[];global.wx={getStorageSync:()=>'',getSystemInfoSync:()=>({})};const api={progress:{save:async(...a)=>calls.push(a)},cameras:{save:async(...a)=>calls.push(a)}};vm.runInNewContext(fs.readFileSync(f,'utf8'),{module:m,require:n=>n==='../api/franchise/engineering'?api:real(n),wx:global.wx});return {P:m.exports,calls};}
function bundle(){return {detail:{id:'100',managerName:'李琦',managerUserId:'148',nodes:[{id:'101',nodeIndex:0,name:'正式开工',milestone:'START',status:'DONE',completedAt:'2026-11-18',deviationDays:2},{id:'102',nodeIndex:1,name:'基础工程',status:'IN_PROGRESS',baselineEndDate:'2026-11-15',endDate:'2026-11-22',deviationDays:3,delayOwnerUserId:'148',delayOwnerName:'李琦',delayReason:'连续降雨',recoveryDate:'2026-11-20',recoveryAction:'增派人员排水'}]},collections:{stages:[]},materials:{plans:[]},settle:{},followups:[]};}
test('START always opens its own milestone and cannot use normal node correction',()=>{const {P}=harness(),b=bundle();assert.equal(P.recordView(b,{view:'start'}).node.id,'101');assert.equal(P.recordView(b,{view:'node',nodeId:'101',writeRole:true}).actions.length,0);assert.match(P.recordView(b,{}).nodeRows[0].url,/view=start$/);});
test('backfilled milestone IDs remain opaque across view selection and delay links',()=>{const {P}=harness(),b=bundle();b.detail.nodes[0].id='-2122707166095204353';const v=P.recordView(b,{view:'delay',nodeId:b.detail.nodes[0].id});assert.equal(v.node.id,b.detail.nodes[0].id);assert.match(v.delayUrl,/nodeId=-2122707166095204353$/);});
test('deviation survives recovery rescheduling and produces a node-scoped todo',()=>{const {P}=harness(),v=P.recordView(bundle(),{writeRole:true,today:'2026-11-18'});assert.equal(v.paceOverdue,1);assert.equal(v.nodeRows[1].pace,'延期 3天');assert.match(v.pending[0].url,/view=delay&nodeId=102/);assert.equal(v.nodeRows[0].pace,'延期完成 2天');});
test('delay form persists existing fields and completion removes the todo while retaining history',()=>{const {P}=harness(),b=bundle();let v=P.recordView(b,{view:'delay',writeRole:true,userId:'148'});assert.equal(v.form.delayReason,'连续降雨');assert.equal(v.actions[0].action,'delay');b.detail.nodes[1].status='DONE';v=P.recordView(b,{view:'delay',writeRole:true});assert.equal(v.actions.length,0);assert.equal(v.pending.some(t=>t.id==='delay-102'),false);assert.equal(v.node.delayReason,'连续降雨');});
test('non-owner and finance can read facts but cannot edit delay/cameras',()=>{const {P}=harness();for(const ctx of [{writeRole:true,userId:'149'},{financeRole:true}]){const v=P.recordView(bundle(),{view:'delay',...ctx});assert.equal(v.actions.length,0);assert.equal(v.canManageCameras,false);}});
test('camera and delay commands retain IDs and ordinary names without changing payment APIs',async()=>{const {P,calls}=harness();await P.perform('delay','100',{delayReason:'连续降雨',delayOwnerUserId:'148',recoveryDate:'2026-11-20',recoveryAction:'增派人员排水'},{id:'102'},'k1');await P.perform('camera-save','100',{cameraId:'301',cameraName:'东侧施工区',cameraLocation:'入口立杆',cameraEnabled:false},null,'k2');assert.equal(calls[0][1],'102');assert.equal(calls[0][2].ownerUserId,'148');assert.equal(calls[1][1].cameraId,'301');assert.equal(calls[1][1].enabled,false);assert.equal(calls[1][1].url,'');});
test('date-array history is normalized and archived camera management is closed',()=>{const {P}=harness(),b=bundle();b.detail.nodes[1].delayHistory=JSON.stringify([{reason:'连续降雨',recoveryDate:[2026,11,20],ownerUserId:'148'}]);b.detail.closure={archivedAt:'2026-11-18'};const v=P.recordView(b,{view:'delay',writeRole:true});assert.equal(v.delayHistory[0].recoveryDate,'2026-11-20');assert.equal(v.canManageCameras,false);assert.equal(v.actions.length,0);});
test('new delay forms require explicit responsibility and recovery date, while later changes keep the recorded person',async()=>{
 const {P,calls}=harness(),b=bundle(),node=b.detail.nodes[1];
 delete node.delayOwnerUserId;delete node.recoveryDate;
 const opts={view:'delay',nodeId:'102',writeRole:true,userId:'148',handlers:[{id:'149',name:'现场责任工程师'}]};
 const fresh=P.recordView(b,opts);
 assert.equal(fresh.form.delayOwnerUserId,'');assert.equal(fresh.form.delayOwnerName,'');assert.equal(fresh.form.recoveryDate,'');
 await assert.rejects(P.perform('delay','100',{...fresh.form,delayReason:'现场雨水',recoveryAction:'排水后复测'},{id:'102'},'incomplete'),/责任人|预计完成/);
 assert.equal(calls.length,0);
 node.delayOwnerUserId='149';delete node.delayOwnerName;node.recoveryDate='2026-12-05';
 const saved=P.recordView(b,opts);
 assert.equal(saved.form.delayOwnerUserId,'149');assert.equal(saved.form.delayOwnerName,'现场责任工程师');assert.equal(saved.form.recoveryDate,'2026-12-05');
});
test('delay history renders the first three recorded updates and expands to include older entries',()=>{
 const {P}=harness(),b=bundle();
 b.detail.nodes[1].delayHistory=[1,2,3,4].map(n=>({reason:'现场复核'+n,action:'分段排水'+n,ownerName:'李琦',recoveryDate:'2026-11-20'}));
 const view=P.recordView(b,{view:'delay',nodeId:'102',writeRole:true,userId:'148'});
 const text=nodes=>nodes.map(n=>(n.text||'')+text(n.children||[])).join('');
 const history=expanded=>text(require('./role-page-runtime.cjs').render(path.resolve('miniprogram/pages/build/record/index.wxml'),
   {...view,view:'delay',delayHistory:[...view.delayHistory].reverse(),expanded:{delay:expanded}}));
 assert.match(history(false),/现场复核4/);assert.match(history(false),/分段排水2/);assert.doesNotMatch(history(false),/现场复核1/);
 assert.match(history(true),/现场复核1/);
});
test('a former delay owner stays in the record while the next editable command needs a current participant',()=>{
 const {P}=harness(),b=bundle();
 const opts={view:'delay',nodeId:'102',writeRole:true,userId:'148',handlers:[{id:'149',name:'现任工程师'}]};
 const result=P.recordView(b,opts);
 assert.equal(result.node.delayOwnerName,'李琦');assert.equal(result.node.delayOwnerUserId,'148');
 assert.equal(result.form.delayOwnerUserId,'');assert.equal(result.form.delayOwnerName,'');assert.equal(result.form.recoveryDate,'2026-11-20');
});
