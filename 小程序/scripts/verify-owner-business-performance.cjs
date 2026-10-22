// 老板端经营页：金额、分组和入口都必须来自原记录，缺数据时写“待登记/待设置”。
const assert=require('assert/strict'),{render}=require('./role-page-runtime.cjs');
const flat=nodes=>nodes.flatMap(n=>[n,...flat(n.children||[])]),text=n=>(n.text||'')+(n.children||[]).map(text).join('');
const pageText=p=>render(p.file,p.data).map(text).join('');
const dataset=n=>Object.fromEntries(Object.entries(n.attrs).filter(([k,v])=>k.startsWith('data-')&&v!==undefined).map(([k,v])=>[k.slice(5).replace(/-([a-z])/g,(_,x)=>x.toUpperCase()),v]));
function tap(p,label){const n=flat(render(p.file,p.data)).find(n=>n.attrs?.bindtap&&text(n).includes(label));assert(n,'找不到可点击的「'+label+'」');p[n.attrs.bindtap]({currentTarget:{dataset:dataset(n)}});return getCurrentPages().at(-1);}
async function setup(){
 const ctx=await require('./verify-owner-workbench.cjs').fixture(),{A,M}=ctx,day=n=>A.localDate(Date.now()+n*86400000),file={name:'本轮验证凭据.pdf',sample:true};
 const O=require('../miniprogram/utils/operations'),st=wx.getStorageSync(M.KEY),prev=day(-40).slice(0,7)===ctx.month?day(-62):day(-40);
 st.operations=st.operations||{};O.init(st);
 // 林女士：开工款已到期未收足（逾期2天），封顶款20天后到期；陈先生没有已批准的付款期次。
 st.operations.plans.push({id:'plan-boss-1',projectId:'boss-project-1',signature:O.contract(O.project('boss-project-1',st)).signature,version:1,status:'approved',installments:[{id:'part-1',name:'开工款',amount:15000000,stage:'start',due:day(-2)},{id:'part-2',name:'封顶款',amount:21000000,stage:'topping',due:day(20)}]});
 st.payables.find(q=>q.id==='boss-review-payable').due=day(3);
 st.payables.push({id:'boss-later-payable',projectId:'boss-project-1',customer:'林女士',name:'屋面班组',stage:'屋面班组',total:580000,agreement:file,recipient:'屋面班组',applicantId:'li',due:day(20)});
 // 上月一笔有效收款，只用于“较上月”对比；放在未签约项目上，不影响应收。
 st.receipts.push({id:'boss-receipt-prev',projectId:'boss-project-2',customer:'王先生',name:'设计定金',amount:22000000,status:'已核算',date:prev+'-15',file,revisions:[],registrarId:'zhang'});
 wx.setStorageSync(M.KEY,st);
 return {...ctx,day,prev};
}
async function main(){
 const ctx=await setup(),{W,month,day}=ctx,checks=[];
 const pass=name=>{checks.push(name);console.log('PASS '+name);};
 const open=url=>{wx.reLaunch({url});const p=getCurrentPages().at(-1);assert(!p.data.error,p.data.error);assert(p.data.ready);return p;};

 // 第二批：经营页
 let p=open('/pages/admin/finance/index'),b=p.data.desk.business;assert(b,'经营页缺少经营视图');
 assert.deepEqual([b.summary.income,b.summary.paid,b.summary.net],['28.6','16.8','11.8']);assert.equal(b.summary.incomeChange,'较'+Number(ctx.prev.slice(5,7))+'月 +30%');assert.equal(b.summary.paidChange,'上月无记录');
 pass('收入、支出、结余按有效核算统计，并与上月对比');
 assert.equal(b.balance.value,null);assert.equal(b.balance.label,'待财务登记');let t=pageText(p);assert(t.includes('待财务登记'));assert(t.includes('按有效核算统计'));assert(!t.includes('应收应付与成本'));
 pass('未登记账户余额时显示待财务登记，不推算余额');
 assert.equal(b.receivable.total,'63.4');assert.equal(b.receivable.overdue,'4.4');assert.equal(b.receivable.count,3);
 assert.deepEqual(b.receivable.rows.map(r=>[r.name,r.amount,r.tag]),[['林女士 · 开工款','4.4','逾期 2 天'],['林女士 · 封顶款','21','未到期'],['陈先生 · 合同未收','38','计划待确认']]);
 assert.equal(b.receivable.rows[0].tone,'overdue');assert(t.includes('逾期 2 天'));
 pass('应收按客户·款项列出，金额取自合同与有效收款，逾期排在最前');
 assert.equal(b.payable.total,'3.58');assert.equal(b.payable.dueSoon,'0.6');assert.equal(b.payable.count,3);
 assert.deepEqual(b.payable.rows.map(r=>[r.name,r.amount,r.tag]),[['王先生 · 设计尾款','0.6','待付款复核'],['林女士 · 屋面班组','0.58','待审批'],['陈先生 · 材料采购','2.4','待审批']]);
 assert.equal(b.payable.rows[2].due,'到期日待登记');
 pass('应付列出未付余额、7天内到期金额、到期日与状态');
 assert.deepEqual([b.pending.reconcile,b.pending.review],[2,1]);assert(t.includes('待核算 2 笔'));
 p=tap(p,'林女士 · 开工款');assert.equal(p.route,'pages/admin/index');assert.equal(p.data.view,'finance');assert.equal(p.id,'boss-project-1');
 p=open('/pages/admin/finance/index');p=tap(p,'王先生 · 设计尾款');assert.equal(p.id,'boss-project-2');
 p=open('/pages/admin/finance/index');p=tap(p,'财务待处理');assert.equal(p.route,'pages/admin/detail/index');assert.equal(p.data.view,'finance-progress');
 pass('应收、应付明细进入原项目账簿，财务待处理进入办理进度');
 const st=wx.getStorageSync(ctx.M.KEY);st.operations.funds.push({id:'funds-test',date:day(-5),amount:50000000,file:{name:'对账单.pdf',sample:true},note:'月度核对',by:'王财务',at:new Date().toISOString()});wx.setStorageSync(ctx.M.KEY,st);
 p=open('/pages/admin/finance/index');b=p.data.desk.business;assert.equal(b.balance.value,'61.8');assert(b.balance.note.includes('王财务'));
 pass('财务登记期初余额后，账户余额按登记后的有效收付推算');

 console.log(JSON.stringify({result:'passed',checks:checks.length}));
}
main().catch(e=>{console.error(e.stack);process.exitCode=1;});
