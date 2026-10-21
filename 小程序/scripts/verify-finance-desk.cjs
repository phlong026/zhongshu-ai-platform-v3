const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const directory=path.resolve('qa/finance-implementation-20261021');fs.mkdirSync(directory,{recursive:true});
const clone=v=>JSON.parse(JSON.stringify(v));
// Generate prerequisites through current business commands, so acceptance/version
// facts stay current instead of borrowing a stale cached design snapshot.
const sourceTrace=path.join(directory,'source-trace.json');
const generation=require('child_process').spawnSync(process.execPath,[path.resolve('scripts/verify-role-same-person-main.cjs')],{encoding:'utf8',env:{...process.env,DEMO_CAPTURE:sourceTrace,DESIGN_FIRST_QA_DIR:path.join(directory,'source-main'),BUSINESS_INLINE_ASSIGN:'1',BUSINESS_BUDGET_ONLY:'1'}});
if(generation.status!==0)throw Error('Current business prerequisites failed: '+generation.stdout+'\n'+generation.stderr);
const trace=JSON.parse(fs.readFileSync(sourceTrace,'utf8')).steps;
const tail=trace.find(r=>r.area==='admin'&&new URL(r.url,'http://local').searchParams.get('view')==='pay'&&decodeURIComponent(r.url).endsWith(':tail'));
const mark=[],fixtures={},file={name:'财务流程验收示例凭据.pdf',sample:true};
(async()=>{
 const runtime=await require('./role-page-runtime.cjs').connect();
 const A=require('../miniprogram/utils/account'),Demo=require('../miniprogram/utils/demo-users'),M=require('../miniprogram/pages/admin/model'),D=require('../miniprogram/utils/finance-desk'),F=require('../miniprogram/utils/finance-source'),T=require('../miniprogram/utils/intercompany'),X=require('../miniprogram/utils/access');
 const p=()=>getCurrentPages().at(-1),open=url=>{wx.reLaunch({url});return p();},click=async selector=>{const e=await(await runtime.currentPage()).$(selector);assert(e,'Visible control '+selector);await e.tap();},check=(name,fn)=>{fn();mark.push({name,result:'passed'});console.log('PASS '+name);};
 const install=rows=>{wx.getStorageInfoSync().keys.forEach(k=>wx.removeStorageSync(k));for(const[k,v]of clone(rows))wx.setStorageSync(k,v);};
 const role=(id,wanted)=>{const a=A.load();Demo.login(a,id);wx.setStorageSync(A.KEY,a);if(wanted&&a.session.role!==wanted)A.mutate(s=>A.switchIdentity(s,s.session.companyId,wanted));};
 const snap=name=>{fixtures[name]=wx.getStorageInfoSync().keys.map(k=>[k,wx.getStorageSync(k)]);};
 const perform=(url,form)=>{open(url);assert(!p().data.missing,'Original page accessible '+url);p().setData({form:{...p().data.form,...form}});p().submit();if(p().data.modal?.type==='confirm')p().confirm();assert(!p().data.error,p().data.error);};
 try {
 assert(tail,'Tail-payment prerequisites from existing workflow trace');install(tail.before);const c=wx.getStorageSync('zhongshu-business-v3').customers.find(c=>tail.url.includes(encodeURIComponent(c.id))),id=c.id,pid=c.projectId,expected=Math.round(Number(c.serviceOrders.at(-1).tail)*100),part=Math.floor(expected/2),rest=expected-part;
 role('hq-finance-dual');let s=D.load(),partner=D.partners(s).find(p=>p.id===c.companyId);check('总部应收从老板已批准服务约定产生，门店未付款前已有完整尾款应收',()=>{assert(partner);assert(partner.design.some(r=>r.customerId===id&&r.stage==='tail'&&r.remaining===expected));});
 role('finance-dual','finance');snap('store-staff');open('/pages/management/index');check('加盟商专员首页四项指标和真实办理待办，导航为首页收支项目我的',()=>{assert(p().data.financeDesk);assert.equal(p().data.financeDesk.metrics.length,4);assert.deepEqual(require('../miniprogram/utils/organization').navigation(X.context()).tabs.map(t=>t.text),['首页','收支','项目','我的']);assert(p().data.financeDesk.tasks.some(t=>t.view==='pay'));});
 open(D.url('flows','',{tab:'payables'}));check('应付页载入真实原单与可登记余额',()=>{assert(p().data.ready,p().data.error);assert(p().data.rows.some(r=>r.name.includes(c.name)));});await click('.fin-search input').catch(()=>{});p().search({detail:{value:'绝不存在的财务单号'}});check('收支搜索与清空使用真实列表',()=>{assert.equal(p().data.rows.length,0);p().clear();assert(p().data.rows.length);});
 p().switchWorkspaceTab({key:'finance-books'});check('收支与项目同页切换，独立设计阶段已建立项目账',()=>{assert.equal(p().data.view,'projects');assert(p().data.rows.some(r=>r.id===pid&&r.phase==='design'));});
 const storeStatement=()=>D.statement(D.load(),id,'tail');let st=storeStatement();perform(st.primary.url,{amount:String(part/100),date:A.localDate(),transaction:'FIN-CHAIN-STORE-TAIL-1',file,checked:true});
 check('门店从新对账页进入原付款单，分笔登记后累计有效已付且总部未自动到账',()=>{assert.equal(storeStatement().paid,part);assert.equal(storeStatement().received,0);assert.equal(T.condition(wx.getStorageSync('zhongshu-business-v3').customers.find(c=>c.id===id),'tail').ready,false);});
 role('hq-finance-dual');st=D.statement(D.load(),id,'tail');assert(st.primary);perform(st.primary.url,{amount:String(part/100),date:A.localDate(),transaction:'FIN-CHAIN-HQ-TAIL-1',file,checked:true,note:'查实本组织银行到账，匹配原付款分笔'});
 check('总部单独登记本笔实际到账，同人配置自动核对，仅核销对应付款分笔',()=>{const r=D.statement(D.load(),id,'tail');assert.equal(r.received,part);assert(!r.ready);assert.equal(r.records.length,1);});
 role('finance-wang');st=storeStatement();perform(st.primary.url,{amount:String(rest/100),date:A.localDate(),transaction:'FIN-CHAIN-STORE-TAIL-2',file,checked:true});
 check('分人配置登记第二笔后保留占用，不计有效已付且不能重复登记',()=>{const state=D.load(),q=state.payables.find(q=>q.id==='business-design:'+id+':'+c.serviceOrders.at(-1).version+':tail');assert.equal(F.paid(state,q.id),part);assert.equal(F.reserved(state,q.id),expected);assert.equal(storeStatement().paid,part);assert(!D.taskRows(state).some(t=>t.view==='pay'&&t.id===q.id));});
 role('finance-lead');snap('store-lead');open('/pages/management/index');check('财务负责人有独立复核待办、资金提醒及完整收付办理入口',()=>{assert(p().data.financeDesk.lead);assert(p().data.financeDesk.previewTasks.some(t=>t.view==='payment-review'));assert(p().data.financeDesk.shortcuts.some(r=>r.label==='收付登记'));});
 st=storeStatement();assert(st.primary);perform(st.primary.url,{checked:true,file,note:'另一实际人员独立核对原付款金额、流水与凭证'});
 check('门店独立复核回写同一笔付款，原项目累计金额正确',()=>{assert.equal(storeStatement().paid,expected);assert.equal(storeStatement().received,part);assert.equal(storeStatement().records.length,2);});
 role('hq-finance');snap('hq-staff');snap('statement-before-register');st=D.statement(D.load(),id,'tail');assert(st.primary);perform(st.primary.url,{amount:String(rest/100),date:A.localDate(),transaction:'FIN-CHAIN-HQ-TAIL-2',file,checked:true,note:'实际查实第二笔到账，待独立核对'});
 check('总部登记第二笔后仍待独立核算，不提前释放设计尾款条件',()=>{const r=D.statement(D.load(),id,'tail');assert.equal(r.received,part);assert(!r.ready);assert(r.primary===null||r.primary.label!=='去核算');});
 role('hq-finance-lead');snap('hq-lead');open('/pages/management/index');check('总部负责人首页展示往来风险，四 Tab 使用加盟商并收到独立核对',()=>{assert(p().data.financeDesk.hq);assert(p().data.financeDesk.previewTasks.some(t=>t.view==='reconcile'));assert.deepEqual(require('../miniprogram/utils/organization').navigation(X.context()).tabs.map(t=>t.text),['首页','收支','加盟商','我的']);});
 st=D.statement(D.load(),id,'tail');perform(st.primary.url,{checked:true});check('总部独立核对完成后，原设计尾款条件与负责人下发待办同步更新',()=>{const r=D.statement(D.load(),id,'tail');assert.equal(r.received,expected);assert(r.ready);assert.equal(r.records.length,2);assert(r.steps.every(v=>v.done));});snap('completed');
 role('hq-design-dual','hq-design-lead');open('/pages/business/index');check('总部设计四 Tab 和尾款足额后的原业务入口保持畅通',()=>{assert.deepEqual(require('../miniprogram/utils/organization').navigation(X.context()).tabs.map(t=>t.text),['首页','项目','图纸','我的']);assert(p().data.designHomeTasks.some(t=>t.action.includes('下发')));});
 role('hq-finance-lead');open(D.url('partners'));check('加盟商列表可筛选、搜索并进入有权限的原往来',()=>{assert(p().data.ready,p().data.error);assert(p().data.rows.some(r=>r.id===c.companyId));p().search({detail:{value:'绝不存在的加盟商'}});assert.equal(p().data.rows.length,0);p().clear();assert(p().data.rows.length);});
 open(D.url('partner','foreign-company'));check('任意拼接加盟商或项目 ID 不泄露跨组织数据',()=>{assert(!p().data.ready);assert.equal(p().data.partner,null);assert.throws(()=>D.statement(D.load(),'foreign-customer','tail'),/无此/);});
 role('finance-lead');open(D.url('partners'));check('门店财务不能访问总部加盟商台账',()=>{assert(!p().data.ready);assert.equal(p().data.rows.length,0);});
 role('finance-dual','finance');open(D.url('flows'));const original=F.period(D.load());D.setPeriod('2025-01');open(D.url('flows','',{tab:'ledger'}));check('月份仅筛选本人视图，不改写原账簿及其他组织数据',()=>{assert.equal(p().data.rows.length,0);assert.equal(F.period(X.rawBook('quanzhou')),original);assert.equal(p().data.period,'2025-01');});D.setPeriod(original);
 role('sales-dual','sales');open(D.url('flows'));check('业务人员不能跨岗进入财务新页面，拒绝时清空全部页面数据',()=>{assert(!p().data.ready);assert.equal(p().data.rows.length,0);assert.throws(()=>D.load(),/财务岗位/);});
 role('finance-dual','finance');open(D.url('project',pid));check('项目账继续接通合同分期、材料应付、成本、费用、业主直付与原单凭证',()=>{assert(p().data.ready,p().data.error);for(const key of ['contractUrl','payablesUrl','costUrl','expenseUrl','directUrl','advanceUrl','sourceUrl'])assert(p().data.detail[key]);});
 check('设计金额审批仍由老板办理，关闭事项未因新入口重新开放',()=>{assert(!D.taskRows(D.load()).some(t=>/refund|correction|verify/.test(t.view)));assert(!D.home(D.load()).shortcuts.some(t=>/审批|退款/.test(t.label)));});
 const originalBusiness=clone(wx.getStorageSync('zhongshu-business-v3'));const localBusiness=clone(originalBusiness);localBusiness.customers.find(r=>r.id===id).designOrganizationId=c.companyId;wx.setStorageSync('zhongshu-business-v3',localBusiness);
 check('本店设计已付不显示总部在途或虚构内部往来',()=>{const rows=D.services(D.load()).filter(r=>r.customerId===id);assert(rows.length);assert(rows.every(r=>!r.external&&r.inTransit===0));assert(!D.home(D.load()).focus.some(r=>r.status==='总部待到账核对'));});wx.setStorageSync('zhongshu-business-v3',originalBusiness);
 fs.writeFileSync(path.join(directory,'fixtures.json'),JSON.stringify({customerId:id,projectId:pid,expected,part,rest,fixtures},null,2));
 fs.writeFileSync(path.join(directory,'page-model-results.json'),JSON.stringify({result:'passed',scope:'真实 Page/WXML 处理器与原财务命令；同一原单连续分笔付款、双方登记、分人核对及总部设计回写。',checks:mark},null,2));console.log(JSON.stringify({result:'passed',checks:mark.length}));
 } catch(e){fs.writeFileSync(path.join(directory,'page-model-failure.json'),JSON.stringify({error:e.stack,page:{route:p()?.route,data:p()?.data},checks:mark},null,2));throw e;}
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
