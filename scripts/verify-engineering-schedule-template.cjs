// 进度计划模板：工程接收建档后，按公司模板和计划开工日一键生成各节点
// 计划日期，生成结果仍可逐项调整后随开工准备保存。
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const runtime=await require('./role-page-runtime.cjs').connect(),out=path.resolve('qa/engineering-schedule-template-20261028');fs.mkdirSync(out,{recursive:true});
 const A=require('../miniprogram/utils/account'),E=require('../miniprogram/pages/build/model'),R=require('../miniprogram/utils/engineering-records');
 const scenes=JSON.parse(fs.readFileSync('qa/engineering-records-20261021/page-scenes.json','utf8'));const seed=scenes.find(s=>s.action==='prepare').before;for(const[k,v]of seed)wx.setStorageSync(k,v);
 const checks=[],pass=n=>{checks.push(n);console.log('PASS '+n);};
 A.mutate(s=>A.loginDemo(s,'engineering-dual'));A.mutate(s=>A.switchIdentity(s,'quanzhou','engineer'));
 let p=E.load().projects.find(p=>p.recordFlow);assert(p);const id=p.id;
 const KEY='zhongshu-engineering-v1',raw=()=>wx.getStorageSync(KEY).projects.find(x=>x.id===id);
 const templates=R.scheduleTemplates('quanzhou');assert(templates.length>=1,'提供公司进度计划模板');const tpl=templates[0];
 assert(tpl.nodes.some(s=>s.perFloor),'模板按层数展开主体阶段');assert(tpl.nodes.some(s=>s.name==='主体封顶'),'模板保留封顶里程碑');
 pass('公司进度计划模板可用，主体阶段按层数展开');
 const nodes=R.applyTemplate(R.get(id),'2026-11-01',tpl);
 assert.equal(nodes.length,5,'两层项目展开为5个节点');assert.deepEqual(nodes.map(n=>n.name),['基础工程','1层主体','2层主体','主体封顶','后续合同工程']);
 assert.equal(nodes[0].start,'2026-11-01');assert.equal(nodes[0].end,'2026-11-30','基础工程30天');
 assert.equal(nodes[1].start,'2026-12-01');assert.equal(nodes[1].end,'2026-12-20','每层主体20天');
 assert.equal(nodes[2].start,'2026-12-21');assert.equal(nodes[2].end,'2027-01-09','二层主体紧接一层');assert.equal(nodes[3].start,'2027-01-10');assert.equal(nodes[3].end,'2027-01-24','主体封顶15天');
 assert.equal(nodes[3].milestone,'topping');assert(nodes.every(n=>n.status==='待开工'));
 pass('按计划开工日顺序生成各节点日期，阶段天数与层数正确');
 wx.reLaunch({url:R.link(R.get(id),'prepare')});let page=getCurrentPages().at(-1);assert(!page.data.error,page.data.error);
 assert(page.data.templates.length>=1,'开工准备页展示模板');page.setData({showPlan:true});assert(await(await runtime.currentPage()).$('[data-action="apply-template"]'),'提供模板生成入口');
 page.setData({['form.templateStart']:'2026-11-01'});const btn=await(await runtime.currentPage()).$('[data-action="apply-template"]');await btn.tap();
 assert.equal(page.data.form.nodes[0].start,'2026-11-01','生成结果写入计划表单');assert.equal(page.data.form.nodes[3].milestone,'topping');
 pass('开工准备页可按模板一键生成节点计划');
 page.setData({['form.total']:'100000',['form.first']:'60000',['form.note']:'现场、道路水电及班组已核对',['form.file']:{name:'合同.pdf',sample:true}});
 const save=await(await runtime.currentPage()).$('[data-action="prepare"]');await save.tap();
 assert(raw().planSaved&&raw().plan.nodes[0].start==='2026-11-01','模板计划随开工准备保存');assert.equal(raw().plan.nodes[1].end,'2026-12-20');
 pass('生成的计划随开工准备一并保存');
 wx.reLaunch({url:R.link(R.get(id),'project')});page=getCurrentPages().at(-1);
 const adjusted=page.data.form.nodes.map((n,i)=>i===1?{...n,end:'2026-12-25'}:n);page.setData({['form.nodes']:adjusted,['form.note']:'一层主体顺延5天'});
 const adjust=await(await runtime.currentPage()).$('[data-action="project"]');await adjust.tap();
 assert.equal(raw().plan.nodes[1].end,'2026-12-25','模板计划可逐项调整后保存');
 pass('生成后的计划仍可逐项调整');
 fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({result:'passed',checks,projectId:id,evidence:'Node Page/WXML integration with schedule template generation'},null,2));console.log('COMPLETE',checks.length,'checks');
})().catch(e=>{console.error(e.stack);fs.writeFileSync(path.resolve('qa/engineering-schedule-template-20261028/results.json'),JSON.stringify({result:'failed',error:e.message},null,2));process.exitCode=1;});
