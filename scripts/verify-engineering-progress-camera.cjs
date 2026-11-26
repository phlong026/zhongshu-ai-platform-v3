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
 require('../miniprogram/pages/build/record/delay').prepare(result,opts.handlers);
 assert.equal(result.node.delayOwnerName,'李琦');assert.equal(result.node.delayOwnerUserId,'148');
 assert.equal(result.form.delayOwnerUserId,'');assert.equal(result.form.delayOwnerName,'');assert.equal(result.form.recoveryDate,'2026-11-20');
});
test('links for removed nodes keep the original node identifier and show both plan dates without inventing a baseline',()=>{
 const {P}=harness(),b=bundle();
 for(const view of ['delay','node'])assert.throws(()=>P.recordView(b,{view,nodeId:'404',writeRole:true,userId:'148'}),/原施工节点/);
 const labels=nodes=>nodes.map(n=>(n.text||'')+labels(n.children||[])).join('');
 const screen=()=>{
  const d=P.recordView(b,{view:'node',nodeId:'102'});
  return labels(require('./role-page-runtime.cjs').render(path.resolve('miniprogram/pages/build/record/index.wxml'),{...d,view:'node',expanded:{}}));
 };
 assert.match(screen(),/原计划完成 2026-11-15/);assert.match(screen(),/当前排期 2026-11-22/);
 delete b.detail.nodes[1].baselineEndDate;
 assert.match(screen(),/原计划完成 未留存/);assert.match(screen(),/当前排期 2026-11-22/);
});
test('a pending delay command survives returning to its page, retries the same payload, and discards another role’s receipt',async()=>{
 const runtime=await require('./role-page-runtime.cjs').connect({lifecycles:true});
 const auth=require('../miniprogram/api/auth'),project='100',node='102',b=bundle(),base='/franchise/engineering/'+project;
 const post={postId:'77',userId:'148',tenantId:'1',orgId:'741',orgType:'store',roleCode:'project-manager'};
 const observer={...post,postId:'78',roleCode:'finance'};
 auth.saveLogin({accessToken:'test',userId:'148'});auth.savePosts([post,observer]);auth.switchPost('77');
 const writes=[],toasts=[];wx.showToast=({title})=>toasts.push(title);
 const ticks=async()=>{for(let i=0;i<12;i++)await new Promise(r=>setImmediate(r));};
 wx.request=req=>{
  const url=new URL(req.url).pathname.replace('/admin-api','');
  if(req.method==='POST'){writes.push(req);return {};}
  const sources={[base]:b.detail,[base+'/collections']:b.collections,[base+'/materials/summary']:b.materials,
   [base+'/settle-summary']:b.settle,[base+'/followups']:[],[base+'/progress/handlers']:[{id:'148',name:'李琦'}],'/platform/posts/mine':[post,observer]};
  assert.ok(Object.hasOwn(sources,url),url);setImmediate(()=>req.success({statusCode:200,data:{code:0,data:sources[url]}}));return {};
 };
 await runtime.callWxMethod('navigateTo',{url:'/pages/build/record/index?id=100&view=delay&nodeId=102'});await ticks();
 const page=getCurrentPages().at(-1);
 const save=()=>page.perform({currentTarget:{dataset:{action:'delay'}}});
 const edit=value=>page.input({currentTarget:{dataset:{key:'delayReason'}},detail:{value}});
 const first=save();await ticks();assert.equal(writes.length,1);
 page.onHide();page.onShow();await ticks();assert.equal(page.data.busy,true);
 edit('未经确认的新输入');const duplicate=save();await ticks();assert.equal(writes.length,1);
 writes[0].fail({errMsg:'网络回执丢失'});await Promise.all([first,duplicate]);
 edit('再次修改');const retry=save();await ticks();assert.equal(writes.length,2);
 assert.deepEqual(writes[0].data,writes[1].data);assert.equal(writes[0].header['Idempotency-Key'],writes[1].header['Idempotency-Key']);
 page.onHide();auth.switchPost('78');page.onShow();await ticks();
 const before=toasts.length;writes[1].success({statusCode:200,data:{code:0,data:{}}});await retry;await ticks();
 assert.equal(toasts.slice(before).includes('已在线保存'),false);assert.equal(page.data.actions.some(a=>a.action==='delay'),false);
});
