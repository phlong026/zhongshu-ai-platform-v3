const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const out=path.resolve('qa/boss-workbench-20261020');
async function fixture(){
 await require('./role-page-runtime.cjs').connect();
 const A=require('../miniprogram/utils/account'),B=require('../miniprogram/utils/business'),M=require('../miniprogram/pages/admin/model'),W=require('../miniprogram/pages/admin/owner-model'),N=require('../miniprogram/utils/organization'),X=require('../miniprogram/utils/access');
 const base=JSON.parse(fs.readFileSync('qa/business-followups-20261019/policy-same-person/intermediate-state.json','utf8')),copy=x=>JSON.parse(JSON.stringify(x)),today=A.localDate(),month=today.slice(0,7),file={name:'本轮验证凭据.pdf',sample:true};
 wx.setStorageSync(A.KEY,copy(base.account));const state=M.seed();state.period=month;state.events=[];state.refunds=[];state.transfers=[];
 const customers=[];
 for(let i=0;i<24;i++){
  const c=copy(base.customer);Object.assign(c,{id:'boss-customer-'+i,projectId:'boss-project-'+i,name:i===0?'陈先生':i===1?'林女士':i===2?'王先生':'客户'+i,companyId:'quanzhou',city:['黄陂区','蔡甸区','江夏区'][i%3],ownerId:i===1?'liu':'zhang',createdAt:today,contract:null,businessFlow:i<2?{type:'sales-offline-v1',submittedAt:new Date().toISOString()}:undefined,quotes:i<3?[{version:1,total:i===0?500000:i===1?360000:560000,date:today,file}]:[],serviceOrders:i<2?[{version:1,total:i?360000:500000,first:i?180000:250000,tail:i?180000:250000,date:today,file,recipient:'设计服务中心'}]:[],request:null,tailApplication:null,firstPayment:null,tailPayment:null,receipts:[],moneyApprovals:[],handover:null,acceptance:null,changes:[]});
  if(i===2)c.serviceOrders=[{version:1,total:12000,first:6000,tail:6000,date:today,file,recipient:'设计服务中心'}];customers.push(c);
 }
 const p=copy(base.engineering.projects[0]);Object.assign(p,{id:'boss-project-0',projectId:'boss-project-0',sourceCustomerId:'boss-customer-0',companyId:'quanzhou',name:'陈先生 · 黄陂乡墅',customer:'陈先生',managerId:'li',manager:'李经理',archived:false,delivery:null,actualStart:{date:today},collections:[],payments:[],rectifications:[],events:[]});
 p.plan.nodes=Array.from({length:8},(_,i)=>({id:'boss-node-'+i,name:'施工节点'+(i+1),start:today,end:today,status:i<6?'已完成':'施工中'}));
 p.supplies=[{id:'boss-supply',materialId:p.materials[0].id,quantity:240,unitPrice:100,payer:'本店',basis:file,supplier:'本地材料商',applicantId:'li',date:today}];
 state.receipts=[12000000,10600000,6000000].map((amount,i)=>({id:'boss-receipt-'+i,projectId:'boss-project-'+i,customer:customers[i].name,name:'工程收款',amount,status:'已核算',date:today,file,revisions:[],registrarId:'zhang'}));
 state.payables=[6800000,6000000,4000000].map((total,i)=>({id:'boss-payable-'+i,projectId:'boss-project-'+i,customer:customers[i].name,name:'工程支出',stage:'历史材料款',total,agreement:file,recipient:'本地材料商',applicantId:'li'}));
 state.payments=[6800000,6000000,4000000].map((amount,i)=>({id:'boss-payment-'+i,payableId:'boss-payable-'+i,projectId:'boss-project-'+i,customer:customers[i].name,name:'材料款',amount,status:'已核实',date:today,file,registrarId:'wang-finance'}));
 state.receipts.push(...[0,1].map(i=>({id:'boss-receipt-pending-'+i,projectId:'boss-project-'+i,customer:customers[i].name,name:'待核算工程款',amount:5000000,status:'待核算',date:today,file,revisions:[],registrarId:'zhang'})));
 state.payables.push({id:'boss-review-payable',projectId:'boss-project-2',customer:'王先生',name:'设计款',stage:'设计尾款',total:600000,agreement:file,recipient:'设计服务中心',applicantId:'zhang'});
 state.payments.push({id:'boss-pending-payment',payableId:'boss-review-payable',projectId:'boss-project-2',customer:'王先生',name:'设计尾款',amount:600000,status:'待复核',date:today,file,registrarId:'wang-finance'});
 wx.setStorageSync(M.KEY,state);wx.setStorageSync(B.KEY,{version:1,customers});wx.setStorageSync('zhongshu-engineering-v1',{version:1,projects:[p]});
 A.mutate(s=>A.loginDemo(s,'boss'));
 W.saveGoals(month,{sales:'1200000',customers:'40',nodes:'8',staff:{zhang:'600000',liu:'600000',li:'8'}});
 return {A,B,M,W,N,X,today,month,customers,project:p};
}
async function main(){
 fs.mkdirSync(out,{recursive:true});const ctx=await fixture(),{A,B,M,W,N,X,month}=ctx,checks=[];
 const pass=name=>{checks.push({name,result:'passed'});console.log('PASS '+name);};
 const open=url=>{wx.reLaunch({url});const p=getCurrentPages().at(-1);assert(!p.data.error,p.data.error);return p;};
 let s=W.snapshot();assert.deepEqual(N.navigation(X.context()).tabs.map(t=>t.text),['首页','财务','绩效','我的']);
 let p=open('/pages/management/index');assert.equal(p.route,'pages/admin/home/index');assert(p.data.ready);p=open('/pages/profile/index');assert.equal(p.route,'pages/admin/profile/index');pass('老板登录默认首页与我的跳转到四栏目模块');
 assert.equal(s.income,'28.6');assert.equal(s.paid,'16.8');assert.equal(s.balance,'11.8');assert.equal(s.reconcileCount,2);assert.equal(s.reviewCount,1);assert.equal(s.approvalCount,7);pass('有效收支统一口径，待核算与待复核不提前计入');
 assert.equal(s.metrics[0].value,'86');assert.equal(s.metrics[0].percent,71.7);assert.equal(s.metrics[1].value,24);assert.equal(s.metrics[2].value,6);assert.equal(s.metrics[2].percent,75);assert.equal(s.team.find(p=>p.id==='zhang').percent,83.3);pass('签约、客资、工程节点和人员目标按原记录及月份计算');
 p=open('/pages/admin/home/index');const task=p.data.desk.approvals.find(t=>t.record==='quote');p.go({currentTarget:{dataset:{key:task.key}}});p=getCurrentPages().at(-1);assert.equal(p.route,'pages/workflow/index');assert.equal(p.data.view,'money-review');assert.equal(p.id||p.data.id,task.id);p.setData({form:{...p.data.form,moneyDecision:'批准',moneyChecked:true,moneyNote:'核对当前正式报价与依据，批准本版'}});p.submit();assert(!p.data.error,p.data.error);assert(!W.snapshot().approvals.some(t=>t.key===task.key));pass('首页审批携带原客户和当前版本，批准后待办回写消失');
 p=open('/pages/admin/finance/index');const row=p.data.projects.find(r=>r.id==='boss-project-0');p.go({currentTarget:{dataset:{key:row.key}}});p=getCurrentPages().at(-1);assert.equal(p.data.view,'finance');assert.equal(p.id,'boss-project-0');assert.equal(p.data.title,'项目收支明细');pass('项目账目入口保留项目ID，准确进入原项目账簿');
 p=open('/pages/admin/finance/index');p.search({detail:{value:'没有这个项目'}});assert.equal(p.data.projects.length,0);p.clear();assert(p.data.projects.length);p.month({detail:{value:'2026-01'}});assert.equal(p.data.desk.income,'0');assert.equal(p.data.desk.metrics[0].percent,null);p.month({detail:{value:month}});assert.equal(p.data.desk.income,'28.6');pass('项目搜索、无结果、月份切换与无目标状态');
 p=open('/pages/admin/performance/index');const person=p.data.desk.team.find(t=>t.id==='zhang');p.go({currentTarget:{dataset:{key:person.key}}});p=getCurrentPages().at(-1);assert.equal(p.route,'pages/admin/detail/index');assert(p.data.rows.some(r=>r.name.includes('陈先生')));assert(!p.data.rows.some(r=>r.name.includes('林女士')));pass('团队绩效按人员追溯本月已签合同，不串用他人来源');
 const before=JSON.stringify(wx.getStorageSync(W.KEY));assert.throws(()=>W.saveGoals(month,{sales:'-1'}),/正数/);assert.equal(JSON.stringify(wx.getStorageSync(W.KEY)),before);pass('无效目标不落盘，目标与实际业务数据分开保存');
 p=open('/pages/admin/detail/index?view=reviews');assert(p.data.rows.length);const review=p.data.rows.find(r=>r.key.startsWith('review:'));p.go({currentTarget:{dataset:{key:review.key}}});p=getCurrentPages().at(-1);assert.equal(p.data.view,'review');assert(!p.data.missing);pass('结果复核进入获授权周期，当前人员不能复核本人周期');
 p=open('/pages/admin/detail/index?view=handover');assert(p.data.rows.some(r=>r.name.includes('陈先生')));p.go({currentTarget:{dataset:{key:p.data.rows[0].key}}});p=getCurrentPages().at(-1);assert.equal(p.data.view,'transfer');assert(!p.data.missing);pass('我的项目经理交接接入原工程责任交接表单');
 A.mutate(s=>A.loginDemo(s,'zhang'));B.add({name:'日期验证客户',phone:'13800128976',source:'门店自然到访',city:'武汉市'});A.mutate(s=>A.loginDemo(s,'boss'));assert.equal(W.snapshot().metrics[1].value,25);pass('真实新增客户即使沿用旧版时间戳ID，也能计入本月登记统计');
 A.mutate(s=>A.loginDemo(s,'zhang'));assert(!W.allowed());assert.throws(()=>W.snapshot(),/授权/);assert.throws(()=>W.saveGoals(month,{sales:'10'}),/仅本组织/);assert.equal(N.navigation(X.context()).tabs.length,5);A.mutate(s=>A.loginDemo(s,'finance-wang'));assert(!W.allowed());pass('非老板岗位不能读取老板聚合或更改目标，原业务导航保留');
 await fixture();fs.writeFileSync(path.join(out,'preview-storage.json'),JSON.stringify(wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')).map(k=>[k,wx.getStorageSync(k)]),null,2));
 fs.writeFileSync(path.join(out,'model-results.json'),JSON.stringify({result:'passed',checks},null,2));console.log(JSON.stringify({result:'passed',checks:checks.length}));
}
module.exports={fixture};if(require.main===module)main().catch(e=>{console.error(e.stack);process.exitCode=1;});
