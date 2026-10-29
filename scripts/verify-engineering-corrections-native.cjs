const fs=require('fs'),path=require('path'),assert=require('assert/strict');

const fixtureFile=path.resolve('qa/engineering-record-corrections-20261023/page-scenes.json');
const directory=path.resolve(process.env.ENGINEERING_CORRECTIONS_NATIVE_OUT||'qa/engineering-record-corrections-20261023/native');
const endpoint=process.env.FRANCHISE_WS;
assert(endpoint,'请由根验收任务统一提供 FRANCHISE_WS');
assert(fs.existsSync(fixtureFile),'请先运行 verify-engineering-record-corrections.cjs 生成独立场景夹具');

const fixture=JSON.parse(fs.readFileSync(fixtureFile,'utf8'));
assert(Array.isArray(fixture.scenes)&&fixture.scenes.length,'工程纠错场景夹具为空');

(async()=>{
 const r=await require('./franchise-native-runtime.cjs').connect('engineering-corrections',{directory,wsEndpoint:endpoint,realEvents:true});
 fs.writeFileSync(path.join(directory,'input-scenes.json'),JSON.stringify(fixture,null,2));
 const checks=[],screens=[],seeded=new Set(),selectors=[];
 const scene=name=>{const row=fixture.scenes.find(s=>s.name===name);assert(row,'缺少独立场景 '+name);assert(row.independentFixture,'场景必须声明为独立前置状态');return row;};
 const pass=async(name,meta={})=>{
  const id=['C06','C01-reduce','C01-cancel','C02','C03','C04-C05','E13-archive'][checks.length],file='fixtures/'+id+'.json';
  fs.mkdirSync(path.join(directory,'fixtures'),{recursive:true});
  fs.writeFileSync(path.join(directory,file),JSON.stringify(await r.snapshot(),null,2));
  checks.push({id,name,result:'passed',fixture:file,...meta});console.log('PASS '+name);
 };
 const project=id=>r.mp.evaluate(id=>wx.getStorageSync('zhongshu-engineering-v1').projects.find(p=>p.id===id),id);
 const account=()=>r.mp.evaluate(()=>{const a=wx.getStorageSync('zhongshu-account-v1');return {name:a.profile?.name,loginId:a.session?.staffId,role:a.session?.role};});
 const data=()=>r.data();
 const until=(fn,label)=>r.until(fn,label,9000);
 async function seed(s,selector='.record-page'){
  assert(!seeded.has(s.name),'每个原生场景只能安装一次初始 seed: '+s.name);seeded.add(s.name);
  await r.replace(s.before);await r.open(s.url,selector);const a=await account();
  assert.equal(a.loginId,s.actor.loginId,'场景登录人必须与夹具一致');assert.equal(a.role,s.actor.role,'场景身份必须与夹具一致');
  if(s.actor.visibleAcceptanceRole)assert.equal(a.name,s.actor.name,'场景人物必须使用六类验收姓名');
  const p=await project(s.project.engineeringId);assert(p,'场景原项目不存在');assert.equal(p.projectId,s.project.projectId);assert.equal(p.sourceCustomerId,s.project.sourceCustomerId);if(s.actor.visibleAcceptanceRole){assert.equal(p.managerId,s.actor.loginId);assert.equal(p.manager,s.actor.name);}return p;
 }
 async function input(selector,value){selectors.push({selector,via:'Element.input'});await r.event(selector,'input',{value:String(value)});}
 async function tap(selector,handler='perform'){selectors.push({selector,via:'Element.tap'});await r.event(selector,handler);}
 async function attachment(key='file'){
  await r.mp.evaluate(key=>{const p=getCurrentPages().at(-1),file={name:'工程纠错原生验收凭据.pdf',sample:true};p.setData({['form.'+key]:file,['fileNames.'+key]:file.name});return true;},key);
 }
 async function shot(name){screens.push(await r.capture(name));}
 async function completeNode(s,note){
  await r.open('/pages/build/record/index?id='+encodeURIComponent(s.project.engineeringId)+'&view=node&nodeId='+encodeURIComponent(s.material?.nodeId||s.node?.nodeId),'.record-page');
  const d=await data();if(d.form.noMaterials===false&&d.fields.some(f=>f.key==='noMaterials'))await tap('button[data-key="noMaterials"]','toggle');
  await input('textarea[data-key="note"]',note);await attachment('file');await tap('button[data-action="node"]');
  await until(async()=>{const p=await project(s.project.engineeringId),id=s.material?.nodeId||s.node?.nodeId;return p.plan.nodes.find(n=>n.id===id)?.status==='已完成';},'节点重新完成');
 }
 try{
  const lead=scene('leader-readonly');await seed(lead,'.engineering-home');const leadBefore=JSON.stringify(await project(lead.project.engineeringId));
  await r.open('/pages/build/projects/index','.projects-page');await r.open('/pages/build/materials/index','.materials-page');
  assert.equal(JSON.stringify(await project(lead.project.engineeringId)),leadBefore,'内部负责人查看列表不得改写张工程项目');
  await pass('内部负责人只读打开团队工程，不代建或改写六类角色项目',{boundary:lead.actor.boundary});

  const reduce=scene('material-adjust-reduce');let p=await seed(reduce);const reducePlan=reduce.material.planId;
  for(const [i,line]of reduce.material.lines.entries())if(line.adjust!=='')await input('input[data-index="'+i+'"][data-key="adjust"]',line.adjust);
  await input('input[data-key="adjustReason"]','现场实际用量减少');await tap('button[data-action="material-adjust"]');
  await until(async()=>{const x=await project(reduce.project.engineeringId),plan=x.recordFlow.materialPlans.find(r=>r.id===reducePlan);return plan?.adjustments?.length===2;},'材料计划减少留痕');
  p=await project(reduce.project.engineeringId);let plan=p.recordFlow.materialPlans.find(r=>r.id===reducePlan);assert.deepEqual(plan.materialIds.map(id=>p.materials.find(m=>m.id===id).quantity),[6,1]);assert.equal((await data()).plan.arrival,'已验收齐全');
  await completeNode(reduce,'调整后材料数量与现场一致，节点完成');p=await project(reduce.project.engineeringId);assert.equal(p.plan.nodes.find(n=>n.id===reduce.material.nodeId).status,'已完成');
  await shot('01-material-adjust-and-node');await pass('张工程真实点击调整材料数量，原材料ID与版本历史保留，节点不再卡住',{projectId:p.projectId,planId:reducePlan,materialIds:reduce.material.lines.map(l=>l.id)});

  const cancel=scene('material-adjust-cancel');p=await seed(cancel);const cancelLine=cancel.material.lines[0];
  await input('input[data-index="0"][data-key="adjust"]','0');await input('input[data-key="adjustReason"]','改用现场旧料');await tap('button[data-action="material-adjust"]');
  await until(async()=>{const x=await project(cancel.project.engineeringId);return x.materials.find(m=>m.id===cancelLine.id)?.quantity===0;},'材料取消');
  p=await project(cancel.project.engineeringId);assert.equal(p.materials.find(m=>m.id===cancelLine.id).id,cancelLine.id);assert.equal(p.supplies.find(s=>s.id===cancelLine.supplyId).status,'已取消');assert.equal((await data()).plan.arrival,'已取消');assert.equal((await data()).plan.payment,'已取消');
  await shot('02-material-cancel');
  await r.open(cancel.url,'.record-page');
  await input('input[data-index="0"][data-key="adjust"]',cancelLine.quantity);
  await input('input[data-key="adjustReason"]','恢复采购，现场重新确认材料需求');
  await tap('button[data-action="material-adjust"]');
  await until(async()=>{const x=await project(cancel.project.engineeringId),m=x.materials.find(m=>m.id===cancelLine.id),s=x.supplies.find(s=>s.id===cancelLine.supplyId);return m?.quantity===cancelLine.quantity&&s?.status!=='已取消';},'材料取消后恢复采购');
  p=await project(cancel.project.engineeringId);const restoredMaterial=p.materials.find(m=>m.id===cancelLine.id),restoredSupply=p.supplies.find(s=>s.id===cancelLine.supplyId);assert.equal(restoredMaterial.id,cancelLine.id);assert.equal(restoredSupply.id,cancelLine.supplyId);assert.equal(restoredMaterial.quantity,cancelLine.quantity);assert.notEqual(restoredSupply.status,'已取消');
  await r.open(cancel.url,'.record-page');
  await input('input[data-index="0"][data-key="adjust"]','0');
  await input('input[data-key="adjustReason"]','再次确认不采购，恢复取消');
  await tap('button[data-action="material-adjust"]');
  await until(async()=>{const x=await project(cancel.project.engineeringId);return x.materials.find(m=>m.id===cancelLine.id)?.quantity===0&&x.supplies.find(s=>s.id===cancelLine.supplyId)?.status==='已取消';},'材料恢复后再次取消');
  p=await project(cancel.project.engineeringId);assert.equal(p.materials.find(m=>m.id===cancelLine.id).id,cancelLine.id);assert.equal(p.supplies.find(s=>s.id===cancelLine.supplyId).id,cancelLine.supplyId);await completeNode(cancel,'再次取消材料后确认本节点无需新增材料');p=await project(cancel.project.engineeringId);assert.equal(p.plan.nodes.find(n=>n.id===cancel.material.nodeId).status,'已完成');
  await pass('张工程真实点击取消、恢复采购、再次取消，原材料与供应ID保持一致且节点可继续',{projectId:p.projectId,materialId:cancelLine.id,supplyId:cancelLine.supplyId,restoredQuantity:cancelLine.quantity});

  const arrival=scene('arrival-correct');p=await seed(arrival);const receiptBefore=p.receipts.find(x=>x.id===arrival.material.receiptId);assert.equal(receiptBefore.actual,arrival.material.from);
  await tap('button[data-id="'+arrival.material.receiptId+'"]','pickArrival');await input('input[data-key="correctActual"]',arrival.material.to);await input('input[data-key="correctReason"]','送货单实为 4 包');await tap('button[data-action="arrival-correct"]');
  await until(async()=>{const x=await project(arrival.project.engineeringId),row=x.receipts.find(r=>r.id===arrival.material.receiptId);return row?.actual===arrival.material.to&&row.corrections?.length===1;},'到货数量更正');
  p=await project(arrival.project.engineeringId);const receipt=p.receipts.find(x=>x.id===arrival.material.receiptId);assert.equal(receipt.supplyId,arrival.material.supplyId);assert.equal(receipt.corrections[0].from,arrival.material.from);
  await shot('03-arrival-correct');await pass('张工程真实点击更正原到货记录，数量、原值和材料关联一致',{receiptId:receipt.id,supplyId:receipt.supplyId});

  const site=scene('site-correct');p=await seed(site);await tap('button[data-id="'+site.node.recordId+'"]','pickSite');await input('textarea[data-key="siteNote"]',site.expect.note);await attachment('siteFile');await input('input[data-key="siteReason"]','原记录写错工序');await tap('button[data-action="site-correct"]');
  await until(async()=>{const x=await project(site.project.engineeringId),row=x.siteRecords.find(r=>r.id===site.node.recordId);return row?.note===site.expect.note&&row.corrections?.length===1;},'普通现场记录更正');
  p=await project(site.project.engineeringId);const siteRow=p.siteRecords.find(r=>r.id===site.node.recordId);assert.equal(siteRow.nodeId,site.node.nodeId);assert.equal(siteRow.corrections[0].note,site.node.originalNote);
  await shot('04-site-correct');await pass('张工程真实点击更正普通现场记录，原节点和历史版本保留',{recordId:siteRow.id,nodeId:siteRow.nodeId});

  const nodeScene=scene('node-correct-reopen-recomplete');p=await seed(nodeScene);const nodeId=nodeScene.node.nodeId;
  await input('textarea[data-key="nodeNote"]',nodeScene.expect.correctedNote);await attachment('nodeFile');await input('input[data-key="nodeReason"]','原照片选错');await tap('button[data-action="node-correct"]');
  await until(async()=>{const x=await project(nodeScene.project.engineeringId),n=x.plan.nodes.find(n=>n.id===nodeId);return n?.corrections?.length===1;},'节点完成记录更正');
  await r.open(nodeScene.url,'.record-page');await input('input[data-key="nodeReason"]','误点完成，基础仍在施工');await tap('button[data-action="node-reopen"]');
  await until(async()=>{const x=await project(nodeScene.project.engineeringId),n=x.plan.nodes.find(n=>n.id===nodeId);return n?.status==='施工中'&&n.reopenHistory?.length===1;},'节点撤回');
  p=await project(nodeScene.project.engineeringId);assert(!p.plan.nodes.find(n=>n.id===nodeId).responsibleId);assert(p.recordFlow&&p.plan.nodes.find(n=>n.id===nodeId).reopenHistory[0].responsibleId);assert((await data()).pending.some(t=>t.title===nodeScene.expect.reopenPendingTitle),'撤回后原项目待办应恢复');
  await completeNode(nodeScene,nodeScene.expect.recompletedNote);p=await project(nodeScene.project.engineeringId);const reopened=p.plan.nodes.find(n=>n.id===nodeId);assert.equal(reopened.status,'已完成');assert.equal(reopened.reopenHistory.length,1);assert.equal(p.siteRecords.filter(r=>r.nodeId===nodeId&&r.completion).at(-1).note,nodeScene.expect.recompletedNote);
  await shot('05-node-correct-reopen-recomplete');await pass('张工程真实点击节点更正、撤回并重新完成，责任历史和待办恢复后闭环',{nodeId,reopenVersions:reopened.reopenHistory.length,correctionVersions:reopened.corrections.length});

  const archive=scene('archive-readonly-close');p=await seed(archive);assert(!(await r.rects(['button[data-view="settlement"]']))[0],'归档页不得渲染已关闭的财务结清动作');assert.equal((await data()).p.id,archive.project.engineeringId);await shot('06-archive-readonly-close');await pass('归档页保留同一项目只读款项与成本入口，不显示关闭流程动作',{projectId:p.projectId});

  assert.equal(seeded.size,fixture.scenes.length,'所有独立夹具应各安装一次');assert.equal(r.errors.length,0,JSON.stringify(r.errors));
  fs.mkdirSync(directory,{recursive:true});fs.writeFileSync(path.join(directory,'results.json'),JSON.stringify({result:'passed',inputScenes:'input-scenes.json',scope:fixture.scope,checks,screens,selectors,seedCounts:Object.fromEntries([...seeded].map(name=>[name,1])),storageRestored:'由 finally 中 runtime.restore 校验'},null,2));
 }catch(error){try{fs.mkdirSync(directory,{recursive:true});fs.writeFileSync(path.join(directory,'failed.json'),JSON.stringify({result:'failed',error:error.stack,checks,selectors,current:await r.current(),exceptions:r.errors},null,2));await shot('failure');}catch(_){}throw error;
 }finally{await r.restore();}
})().then(()=>process.exit(0)).catch(error=>{console.error(error.stack);process.exit(1);});
