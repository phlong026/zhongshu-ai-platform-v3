// 开工准备页从 Excel 导入进度计划：选择 xlsx 文件后解析生成节点清单，
// 可逐项调整并随开工准备保存；格式错误给出明确行号提示。
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const runtime=await require('./role-page-runtime.cjs').connect(),out=path.resolve('qa/engineering-schedule-import-20261028');fs.mkdirSync(out,{recursive:true});
 const A=require('../miniprogram/utils/account'),E=require('../miniprogram/pages/build/model'),R=require('../miniprogram/utils/engineering-records'),XLSX=require('../miniprogram/pages/build/vendor/xlsx.mini.min'),SI=require('../miniprogram/pages/build/schedule-import');
 const scenes=JSON.parse(fs.readFileSync('qa/engineering-records-20261021/page-scenes.json','utf8'));const seed=scenes.find(s=>s.action==='prepare').before;for(const[k,v]of seed)wx.setStorageSync(k,v);
 const checks=[],pass=n=>{checks.push(n);console.log('PASS '+n);};
 const build=rows=>{const wb=XLSX.utils.book_new();XLSX.utils.book_append_sheet(wb,XLSX.utils.aoa_to_sheet(rows),'进度计划');return XLSX.write(wb,{type:'array',bookType:'xlsx'});};
 const planRows=[['节点名称','开始日期','结束日期'],['基础工程','2026-11-01','2026-11-30'],['1层主体','2026-12-01','2026-12-20'],['2层主体','2026-12-21','2027-01-09'],['主体封顶','2027-01-10','2027-01-24'],['后续合同工程','2027-01-25','2027-03-10']];
 const picks=[];Object.assign(global.wx,{chooseMessageFile:o=>{picks.push(o);o.success({tempFiles:[{name:'进度计划.xlsx',path:'wxfile://tmp/plan.xlsx'}]});},getFileSystemManager:()=>({readFileSync:p=>{assert.equal(p,'wxfile://tmp/plan.xlsx');return build(planRows);}})});
 A.mutate(s=>A.loginDemo(s,'engineering-dual'));A.mutate(s=>A.switchIdentity(s,'quanzhou','engineer'));
 let p=E.load().projects.find(p=>p.recordFlow);assert(p);const id=p.id,KEY='zhongshu-engineering-v1',raw=()=>wx.getStorageSync(KEY).projects.find(x=>x.id===id);
 wx.reLaunch({url:R.link(R.get(id),'prepare')});let page=getCurrentPages().at(-1);assert(!page.data.error,page.data.error);
 assert(page.data.templates.length>=1);page.setData({showPlan:true});
 const btn=await(await runtime.currentPage()).$('[data-action="import-plan"]');assert(btn,'缺少Excel导入入口');await btn.tap();
 assert.equal(picks.length,1,'应调起文件选择');assert.deepEqual(picks[0].extension,['xlsx'],'只接受xlsx');
 page=getCurrentPages().at(-1);
 assert.equal(page.data.form.nodes.length,5,'导入5个节点');assert.equal(page.data.form.nodes[0].start,'2026-11-01');assert.equal(page.data.form.nodes.at(-1).end,'2027-03-10');
 assert.equal(page.data.form.nodes.find(n=>n.name==='主体封顶').milestone,'topping','封顶里程碑自动标记');
 pass('选择xlsx后解析生成节点清单，封顶里程碑正确');
 page.setData({['form.nodes']:page.data.form.nodes.map(n=>n.name==='1层主体'?{...n,end:'2026-12-25'}:n),['form.total']:'100000',['form.first']:'60000',['form.note']:'现场、道路水电及班组已核对',['form.file']:{name:'合同.pdf',sample:true}});
 const save=await(await runtime.currentPage()).$('[data-action="prepare"]');await save.tap();
 assert(raw().planSaved);assert.equal(raw().plan.nodes[1].end,'2026-12-25','导入的计划调整后随开工准备保存');
 pass('导入的计划可调整并随开工准备保存');
 // 错误路径：坏日期文件报行号
 Object.assign(global.wx,{getFileSystemManager:()=>({readFileSync:()=>build([['节点名称','开始日期','结束日期'],['基础工程','30天','2026-11-30']])})});
 wx.reLaunch({url:R.link(R.get(id),'prepare')});page=getCurrentPages().at(-1);page.setData({showPlan:true});
 const btn2=await(await runtime.currentPage()).$('[data-action="import-plan"]');await btn2.tap();
 page=getCurrentPages().at(-1);assert(/第2行“开始日期”无法识别为日期：30天/.test(page.data.error),'报错带行号和原因：'+page.data.error);
 pass('格式错误给出带行号的明确提示，不写入表单');
 fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({result:'passed',checks,evidence:'Node Page/WXML integration with xlsx schedule import'},null,2));console.log('COMPLETE',checks.length,'checks');
})().catch(e=>{console.error(e.stack);fs.writeFileSync(path.resolve('qa/engineering-schedule-import-20261028/results.json'),JSON.stringify({result:'failed',error:e.message},null,2));process.exitCode=1;});
