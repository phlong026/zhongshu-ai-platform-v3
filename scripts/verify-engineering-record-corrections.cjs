// 工程记录的「会报错或卡住」问题回归：工程负责人首页、材料计划调整、记错更正。
// 依赖 verify-engineering-records.cjs 生成的连续办理场景（npm run check:engineering:corrections 会先生成）。
const fs=require('fs'),assert=require('assert/strict');
(async()=>{
 const runtime=await require('./role-page-runtime.cjs').connect();
 const scenes=JSON.parse(fs.readFileSync('qa/engineering-records-20261021/page-scenes.json','utf8'));
 const R=require('../miniprogram/utils/engineering-records'),A=require('../miniprogram/utils/account'),E=require('../miniprogram/pages/build/model'),W=require('../miniprogram/pages/build/workbench'),F=require('../miniprogram/utils/finance-source'),X=require('../miniprogram/utils/access');
 const file={name:'更正凭据.pdf',sample:true},date=A.localDate(),checks=[];let clicks=0;
 const pass=name=>{checks.push(name);console.log('PASS '+name);};
 // 按动作取场景；同名动作取第 n 个
 const scene=(action,n=0)=>scenes.filter(s=>s.action===action)[n];
 const install=rows=>{for(const k of wx.getStorageInfoSync().keys)wx.removeStorageSync(k);for(const [k,v] of rows)wx.setStorageSync(k,v);};
 const raw=()=>wx.getStorageSync(E.KEY).projects.find(p=>p.recordFlow);
 const run=(action,f={})=>{const p=raw();return R.run(p.id,action,f,p.revision||0);};
 const fails=(action,f,re)=>{const before=JSON.stringify(raw());assert.throws(()=>run(action,f),re);assert.equal(JSON.stringify(raw()),before,action+' 失败后不应改动原记录');};
 const open=url=>{wx.reLaunch({url});const page=getCurrentPages().at(-1);assert(!page.data.error,page.data.error);return page;};
 const click=async selector=>{const el=await (await runtime.currentPage()).$(selector);assert(el,'可见按钮 '+selector);await el.tap();clicks++;const page=getCurrentPages().at(-1);assert(!page.data.error,page.data.error);return page;};

 // 1. 工程负责人：团队里有新流程项目时，首页、项目、材料都能打开
 install(scene('node').after);
 const owned=JSON.stringify(raw());
 A.mutate(s=>A.loginDemo(s,'engineering-lead'));
 assert.equal(X.context().role,'project-manager');
 assert.doesNotThrow(()=>W.home());
 assert.equal(JSON.stringify(raw()),owned,'负责人打开首页不应改写他人项目');
 for(const url of ['/pages/build/home/index','/pages/build/projects/index','/pages/build/materials/index'])open(url);
 const all=wx.getStorageSync(E.KEY);wx.setStorageSync(E.KEY,{...all,projects:all.projects.filter(p=>!p.recordFlow)});
 assert.doesNotThrow(()=>W.home());
 assert(!wx.getStorageSync(E.KEY).projects.some(p=>p.recordFlow),'负责人不应替下属建立新流程项目');
 pass('工程负责人在团队有新流程项目时可正常打开工程首页和项目页，不改写也不代建他人项目');

 // 2. 材料计划调整：多报的数量可以减，不需要的可以取消，节点不再被卡住
 install(scene('arrival',0).after);
 let p=raw(),plan=R.plans(p)[0];
 const [cement,sand]=plan.lines;
 assert.equal(cement.received,6);assert.equal(sand.received,1);
 assert.throws(()=>run('node',{nodeId:plan.nodeId,file,note:'现场完成'}),/尚未合格到齐/);
 fails('material-adjust',{planId:plan.id,reason:'',lines:[{id:cement.id,adjust:'6'}]},/调整原因/);
 fails('material-adjust',{planId:plan.id,reason:'现场少用',lines:[{id:cement.id,adjust:'5'}]},/不能少于已到/);
 fails('material-adjust',{planId:plan.id,reason:'现场少用',lines:[]},/需用数量/);
 let page=open(R.link(raw(),'material',{planId:plan.id}));
 page.setData({form:{...page.data.form,adjustReason:'现场实际用量减少',lines:page.data.form.lines.map(l=>({...l,adjust:l.id===cement.id?'6':l.id===sand.id?'1':''}))}});
 await click('[data-action="material-adjust"]');
 p=raw();plan=R.plans(p)[0];
 assert.deepEqual(plan.lines.map(l=>l.quantity),[6,1]);
 assert.equal(plan.arrival,'已验收齐全');
 assert.equal(p.recordFlow.materialPlans[0].adjustments.length,2);
 assert.deepEqual(p.recordFlow.materialPlans[0].adjustments.map(r=>[r.from,r.to]),[[10,6],[2,1]]);
 assert(R.materialReady(p,plan.nodeId));
 assert.equal(R.tasks(p).pending.filter(t=>/材料/.test(t.title)).length,0);
 run('node',{nodeId:plan.nodeId,file,note:'调整后材料齐全，节点完成'});
 assert.equal(raw().plan.nodes[0].status,'已完成');
 pass('材料计划可按实际减少需用数量，不得少于已到货，调整留痕后节点可以完成');

 // 未到货、未定价的材料可取消；取消后不再要求财务补价
 install(scene('material-plan',1).after);
 plan=R.plans(raw()).at(-1);
 const steel=plan.lines[0];
 run('material-adjust',{planId:plan.id,reason:'改用现场旧料',lines:[{id:steel.id,adjust:'0'}]});
 p=raw();plan=R.plans(p).at(-1);
 assert.equal(plan.lines[0].quantity,0);
 assert.equal(plan.arrival,'已取消');assert.equal(plan.payment,'已取消');
 assert(R.materialReady(p,plan.nodeId));
 assert(!R.tasks(p).pending.concat(R.tasks(p).waiting).some(t=>t.id.endsWith(plan.id)),'已取消的计划不再出现在待办');
 A.mutate(s=>A.loginDemo(s,'finance-dual'));A.mutate(s=>A.switchIdentity(s,s.session.companyId,'finance'));
 assert(!R.financialTasks().some(t=>t.id==='material-terms:'+plan.id),'已取消的材料不再要求记录采购金额');
 pass('未到货材料可取消，计划显示已取消，不再生成到货、付款和财务补价待办');

 // 已定价未付款的材料取消后，应付随之消失；已有付款的材料不能调整
 // 财务定价后的状态，但以提交计划的工程人员会话继续办理
 const engineerSession=scene('material-plan',1).after.find(([k])=>k===A.KEY);
 install(scene('material-terms',1).after.map(row=>row[0]===A.KEY?engineerSession:row));
 plan=R.plans(raw()).at(-1);
 const payable='engineering-payable:'+raw().id+':'+plan.lines[0].supplyId;
 const book=()=>F.merge(X.rawBook(raw().companyId));
 assert(book().payables.some(q=>q.id===payable));
 run('material-adjust',{planId:plan.id,reason:'供应商无货，改用其他材料',lines:[{id:plan.lines[0].id,adjust:'0'}]});
 assert(!book().payables.some(q=>q.id===payable),'取消后不再保留该材料应付');
 install(scene('material-plan',1).after);
 const paidPlan=R.plans(raw())[0];
 assert(paidPlan.lines[0].paid>0);
 fails('material-adjust',{planId:paidPlan.id,reason:'多报',lines:[{id:paidPlan.lines[0].id,adjust:'12'}]},/已有付款/);
 pass('已定价未付款的材料可取消并撤下应付，已有付款的材料不能由工程调整');

 // 3. 到货数量记错可更正
 install(scene('arrival',0).after);
 p=raw();plan=R.plans(p)[0];
 const receipt=p.receipts.find(r=>r.planId===plan.id&&r.supplyId===plan.lines[0].supplyId);
 fails('arrival-correct',{receiptId:receipt.id,actual:'4',reason:''},/更正原因/);
 fails('arrival-correct',{receiptId:receipt.id,actual:'11',reason:'多记'},/超过计划/);
 fails('arrival-correct',{receiptId:'missing',actual:'4',reason:'多记'},/到货记录不存在/);
 page=open(R.link(raw(),'material',{planId:plan.id}));
 page.setData({form:{...page.data.form,correctId:receipt.id,correctActual:'4',correctReason:'送货单实为 4 包'}});
 await click('[data-action="arrival-correct"]');
 p=raw();
 const fixed=p.receipts.find(r=>r.id===receipt.id);
 assert.equal(fixed.actual,4);assert.equal(fixed.corrections[0].from,6);assert.equal(fixed.corrections[0].reason,'送货单实为 4 包');
 assert.equal(R.plans(p)[0].lines[0].received,4);
 pass('到货数量记错可在原记录更正，保留原值与原因，累计实收随之更新');

 // 4. 现场记录记错可更正；节点完成记录走节点更正
 install(scene('site').after);
 p=raw();
 const site=p.siteRecords[0];
 fails('site-correct',{recordId:site.id,note:'改正后的现场情况',reason:''},/更正原因/);
 page=open(R.link(raw(),'node',{nodeId:site.nodeId}));
 page.setData({form:{...page.data.form,siteId:site.id,siteNote:'基础开挖完成，已放线',siteFile:file,siteReason:'原记录写错工序'}});
 await click('[data-action="site-correct"]');
 const corrected=raw().siteRecords.find(r=>r.id===site.id);
 assert.equal(corrected.note,'基础开挖完成，已放线');assert.equal(corrected.corrections[0].note,site.note);
 install(scene('node',0).after);
 const completion=raw().siteRecords.find(r=>r.completion);
 assert(completion,'节点完成时的现场记录应带完成标记');
 fails('site-correct',{recordId:completion.id,note:'x',reason:'y'},/节点完成记录/);
 pass('现场记录可在原记录更正并留痕，节点完成记录不被单独改动');

 // 5. 节点完成记录记错：可更正日期/说明/凭据，最近一次完成可撤回
 install(scene('node',0).after);
 p=raw();
 let node=p.plan.nodes[0];
 fails('node-correct',{nodeId:node.id,date,note:'完成',file,reason:''},/更正原因/);
 page=open(R.link(raw(),'node',{nodeId:node.id}));
 page.setData({form:{...page.data.form,nodeDate:date,nodeNote:'基础验收合格，照片补正',nodeFile:file,nodeReason:'原照片选错'}});
 await click('[data-action="node-correct"]');
 p=raw();node=p.plan.nodes[0];
 assert.equal(node.completedAt,date);assert.equal(node.corrections.length,1);
 assert.equal(p.inspections.find(r=>r.nodeId===node.id).date,date);
 assert.equal(p.siteRecords.find(r=>r.completion&&r.nodeId===node.id).note,'基础验收合格，照片补正');
 page=open(R.link(raw(),'node',{nodeId:node.id}));
 page.setData({form:{...page.data.form,nodeReason:'误点完成，基础仍在施工'}});
 await click('[data-action="node-reopen"]');
 p=raw();
 assert.deepEqual(p.plan.nodes.slice(0,2).map(n=>n.status),['施工中','待开工']);
 assert(!p.inspections.some(r=>r.nodeId===p.plan.nodes[0].id),'撤回后本节点不再有通过的验收');
 assert.equal(p.plan.nodes[0].reopenHistory.length,1);
 assert(!p.plan.nodes[0].responsibleId,'撤回后节点不再归属原完成人');assert(p.plan.nodes[0].reopenHistory[0].responsibleId,'原完成人保留在撤回历史中');
 assert(R.tasks(p).pending.some(t=>t.title===p.plan.nodes[0].name+'待记录'));
 run('node',{nodeId:p.plan.nodes[0].id,file,note:'基础重新完成'});
 assert.equal(raw().plan.nodes[0].status,'已完成');
 install(scene('node',1).after);
 fails('node-reopen',{nodeId:raw().plan.nodes[0].id,reason:'误点'},/最近完成/);
 install(scene('node',0).after);
 run('site',{nodeId:raw().plan.nodes[1].id,note:'一层已开始砌筑',file});
 fails('node-reopen',{nodeId:raw().plan.nodes[0].id,reason:'误点'},/下一节点已有现场记录/);
 pass('节点完成记录可更正日期、说明和凭据；最近一次完成且下一节点未开始记录时可撤回');

 // 封顶撤回：未登记封顶款可撤回并取消封顶款待办，已登记则不能撤回
 install(scene('node',3).after);
 p=raw();const top=p.plan.nodes.find(n=>n.milestone==='topping');
 assert.equal(top.status,'已完成');assert(p.recordFlow.topping);
 run('node-reopen',{nodeId:top.id,reason:'封顶尚未浇筑完成'});
 p=raw();
 assert(!p.recordFlow.topping);assert.equal(p.plan.nodes.find(n=>n.id===top.id).status,'施工中');
 assert(!R.tasks(p).pending.concat(R.tasks(p).waiting).some(t=>/封顶款/.test(t.title)));
 // 封顶款由加盟商财务登记，登记后仍以项目工程人员身份尝试撤回
 const toppingEngineer=scene('node',3).after.find(([k])=>k===A.KEY);
 install(scene('collect',2).after.map(row=>row[0]===A.KEY?toppingEngineer:row));
 fails('node-reopen',{nodeId:raw().plan.nodes.find(n=>n.milestone==='topping').id,reason:'误点'},/封顶款/);
 // 封顶款已登记后，封顶完成日期不能更正到收款日之后
 const paidTop=wx.getStorageSync(E.KEY),pt=paidTop.projects.find(x=>x.recordFlow);
 pt.actualStart.date='2026-10-01';for(const n of pt.plan.nodes)if(n.completedAt)n.completedAt='2026-10-05';
 pt.collections.filter(r=>r.receiptStage==='topping').forEach(r=>{r.date='2026-10-10';});wx.setStorageSync(E.KEY,paidTop);
 const topNode=raw().plan.nodes.find(n=>n.milestone==='topping');
 fails('node-correct',{nodeId:topNode.id,date:'2026-10-11',note:'封顶完成',file,reason:'日期补正'},/封顶款/);
 run('node-correct',{nodeId:topNode.id,date:'2026-10-09',note:'封顶完成',file,reason:'日期补正'});
 assert.equal(raw().recordFlow.topping.date,'2026-10-09');
 pass('未登记封顶款时可撤回主体封顶，已登记封顶款后不能撤回，也不能把封顶日期改到收款之后');

 // 6. 审查补充用例
 // 取消未定价材料后，项目成本不再把它算作待核对，财务可以结清
 const Ops=require('../miniprogram/utils/operations');
 const unresolved=()=>{const s=F.merge(X.rawBook(raw().companyId));Ops.init(s);return Ops.cost(s,raw().projectId).unresolved;};
 install(scene('material-plan',1).after);
 plan=R.plans(raw()).at(-1);
 const pending=unresolved();
 run('material-adjust',{planId:plan.id,reason:'改用现场旧料',lines:[{id:plan.lines[0].id,adjust:'0'}]});
 assert.equal(unresolved(),pending-1);
 pass('取消的材料不再计入待核对成本，项目财务结清不被卡住');

 // 早期数据没有完成标记：按最后一条同日同凭据记录认定完成记录，不改到先保存的普通记录
 install(scene('node',0).after);
 const legacy=wx.getStorageSync(E.KEY);for(const x of legacy.projects)for(const r of x.siteRecords||[])delete r.completion;wx.setStorageSync(E.KEY,legacy);
 p=raw();node=p.plan.nodes[0];
 const nodeSites=p.siteRecords.filter(r=>r.nodeId===node.id),saved=nodeSites[0],done=nodeSites.at(-1);
 assert(nodeSites.length>=2&&saved.id!==done.id);
 fails('site-correct',{recordId:done.id,note:'x',reason:'y'},/节点完成记录/);
 run('node-correct',{nodeId:node.id,date:node.completedAt,note:'完成记录更正',file,reason:'补正'});
 assert.equal(raw().siteRecords.find(r=>r.id===done.id).note,'完成记录更正');
 assert.equal(raw().siteRecords.find(r=>r.id===saved.id).note,saved.note);
 pass('早期数据的节点完成记录识别正确，更正不会改到先保存的现场记录');

 // 更正完成日期不能早于实际开工、上一节点完成，也不能晚于下一节点的首条记录
 install(scene('node',1).after);
 const dated=wx.getStorageSync(E.KEY),dp=dated.projects.find(x=>x.recordFlow);
 dp.actualStart.date='2026-10-01';dp.plan.nodes[0].completedAt='2026-10-10';
 for(const r of dp.siteRecords.filter(r=>r.nodeId===dp.plan.nodes[1].id))r.date='2026-10-15';
 wx.setStorageSync(E.KEY,dated);
 p=raw();
 fails('node-correct',{nodeId:p.plan.nodes[0].id,date:'2026-09-30',note:'x',file,reason:'y'},/不能早于实际开工/);
 fails('node-correct',{nodeId:p.plan.nodes[1].id,date:'2026-10-05',note:'x',file,reason:'y'},/不能早于上一节点/);
 fails('node-correct',{nodeId:p.plan.nodes[0].id,date:'2026-10-20',note:'x',file,reason:'y'},/不能晚于下一节点/);
 run('node-correct',{nodeId:p.plan.nodes[0].id,date:'2026-10-12',note:'日期补正',file,reason:'原日期记错'});
 assert.equal(raw().plan.nodes[0].completedAt,'2026-10-12');
 pass('节点完成日期更正限定在实际开工、上一节点完成和下一节点首条记录之间');

 // 已完成节点的材料条件不能被事后打破
 install(scene('node',0).after);
 p=raw();plan=R.plans(p)[0];
 fails('material-adjust',{planId:plan.id,reason:'加量',lines:[{id:plan.lines[0].id,adjust:'12'}]},/节点已完成/);
 const doneReceipt=p.receipts.find(r=>r.planId===plan.id);
 fails('arrival-correct',{receiptId:doneReceipt.id,actual:String(doneReceipt.actual-1),reason:'少记'},/节点已完成/);
 pass('节点已完成后不能加大需用数量或把到货改少，需先撤回节点完成');

 // 取消后恢复数量，运费随之恢复，应付金额完整
 install(scene('material-terms',1).after.map(row=>row[0]===A.KEY?engineerSession:row));
 plan=R.plans(raw()).at(-1);
 const steelLine=plan.lines[0];
 run('material-terms',{planId:plan.id,file,lines:[{id:steelLine.id,supplier:'钢材厂',unitPrice:'4000',freight:'50'}]});
 run('material-adjust',{planId:plan.id,reason:'暂不采购',lines:[{id:steelLine.id,adjust:'0'}]});
 page=open(R.link(raw(),'material',{planId:plan.id}));
 assert(page.data.canAdjust,'整份计划取消后仍可恢复数量');
 assert(!page.data.actions.some(a=>a.action==='arrival'),'已取消的计划不显示确认到货');
 run('material-adjust',{planId:plan.id,reason:'恢复采购',lines:[{id:steelLine.id,adjust:'1'}]});
 const restored=raw().supplies.find(s=>s.id===steelLine.supplyId);
 assert.equal(restored.freight,50);assert.equal(restored.status,'待财务办理');
 assert.equal(book().payables.find(q=>q.id==='engineering-payable:'+raw().id+':'+steelLine.supplyId).total,405000);
 pass('取消后恢复采购，运费和应付金额随之恢复，已取消计划不显示到货操作');

 // 本节点材料全部取消后，完成节点仍需确认无需新增材料
 install(scene('material-plan',1).after);
 plan=R.plans(raw()).at(-1);
 run('material-adjust',{planId:plan.id,reason:'改用现场旧料',lines:[{id:plan.lines[0].id,adjust:'0'}]});
 fails('node',{nodeId:plan.nodeId,file,note:'完成'},/无需新增材料/);
 run('node',{nodeId:plan.nodeId,file,note:'现场材料已核对，节点完成',noMaterials:true});
 pass('本节点材料全部取消后，记录完成仍需确认现场材料已核对');

 // 交付后不能再调整或更正施工记录
 install(scene('deliver').after);
 p=raw();
 fails('node-correct',{nodeId:p.plan.nodes[0].id,date,note:'x',file,reason:'y'},/已交付/);
 fails('material-adjust',{planId:p.recordFlow.materialPlans[0].id,reason:'y',lines:[]},/已交付/);
 pass('工程交付后施工与材料记录不再调整或更正');

 fs.writeFileSync('qa/engineering-records-20261021/corrections.json',JSON.stringify({checks,clicks},null,1));
 console.log('COMPLETE '+checks.length+' checks, '+clicks+' WXML button events');
 process.exit(0);
})().catch(e=>{console.error(e);process.exit(1);});
