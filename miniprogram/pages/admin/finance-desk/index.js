const {money:displayFinanceMoney}=require('../../../utils/finance-workbench-view');
const D=require('../../../utils/finance-desk'),X=require('../../../utils/access'),N=require('../../../utils/page-navigation');
const auth=require('../../../api/auth');
const financeApi=require('../../../api/finance');
const httpApi=require('../../../api/http'); // M08-T02：loadPlatformFinance 乱序守卫用
const PAGE_SIZE=100; // M5 F3：台账本地分页（指标/选择器仍全量——口径无损）
const PF=require('../../../utils/platform-finance');
const PTPage=require('../../../utils/platform-todos-page'); // M+：API 态 tasks 视图/工作台待办接平台待办
const perfApi=require('../../../api/franchise/performance'); // M+：月末对账改走平台 operating.reconcile
const FrLink=require('../../../utils/fr-web-link'); // P1 §5.4：/fr Web 协同链接（复制不携凭据）
const views={desk:'财务工作台',flows:'收支',profile:'我的',projects:'项目账本',book:'项目账本',reimbursements:'报销办理',progress:'正在办理',partners:'加盟商往来',project:'项目账目',partner:'加盟商往来',statement:'设计款到账进度',tasks:'财务办理'};
const clean=()=>({rows:[],detail:null,partner:null,statement:null,tasks:[],filters:[],stats:[],ready:false,error:''});
const displayMetricValue=value=>value==='—'?'—':displayFinanceMoney(Math.round(Number(value||0)*100));
const platformMetricCards=(m,taskCount=0)=>[{label:'已登记收款',value:displayMetricValue(m.received),url:D.url('flows','',{tab:'ledger'})},{label:'已登记付款',value:displayMetricValue(m.paid),url:D.url('flows','',{tab:'ledger'})},{label:'收支净额',value:displayMetricValue(m.net),url:D.url('flows','',{tab:'ledger'})},{label:'待办事项',value:taskCount,taskMetric:true,url:D.url('tasks')}];
// P1 批6：台账行来源前缀（r-=收款 p-=付款 f-=退款）→ 撤销动作按来源判定
const sourceKindOf=key=>key&&key.indexOf('r-')===0?'receipt':(key&&key.indexOf('p-')===0?'payment':'refund');
const {routeValue}=require('../../../utils/view-container');
Page({
 data:{ platformNotice:'',voidNotice:'',ledgerError:'',...clean(),view:'flows',title:'收支',keyword:'',filter:'all',category:'全部类别',statusFilter:'全部状态',due:'',flowTab:'receivables',flowScope:'period',sort:'到期优先',statusOptions:['全部状态'],categoryOptions:['全部类别']},
 onLoad(o={}){PTPage.init(this);this.__ledgerRouteIdentity=String(httpApi.currentPostId())+':'+httpApi.sessionGeneration();this.options={...o,bookScope:routeValue(o.bookScope)};this.id=routeValue(o.id);const post=httpApi.token()?auth.currentApiPost():null,actor=post?{role:post.roleCode,orgType:post.orgType}:X.context(),financeRole=['finance','finance-lead'].includes(actor.role);this.setData({selectedMonth:/^\d{4}-(0[1-9]|1[0-2])$/.test(o.month||'')?o.month:'',view:views[o.view]?o.view:(financeRole?'desk':'flows'),flowTab:['receivables','payables','ledger','reconcile'].includes(o.tab)?o.tab:actor.orgType==='headquarters'?'payables':'receivables',flowScope:['all','current'].includes(o.scope)?o.scope:'period',filter:routeValue(o.filter)||'all',category:routeValue(o.category)||'全部类别',due:o.due||'',taskType:o.taskType||'',stage:o.stage==='first'?'first':'tail',version:o.version||''});},
 beginFinanceBusy(){
  if(this.data.busy)return null;
  const token={};this.__financeBusyOwner=token;this.setData({busy:true});return token;
 },
 ownsFinanceBusy(token){return !!token&&this.__financeBusyOwner===token;},
 finishFinanceBusy(token,patch={}){
  if(!this.ownsFinanceBusy(token))return false;
  this.__financeBusyOwner=null;this.setData({busy:false,...patch});return true;
 },
 syncFinanceIdentity(financeIdentity){
  if(this.__financeIdentity===financeIdentity)return false;
  this.__financeIdentity=financeIdentity;this.__messageSourceOpened=false;this.__homeStageOpened=false;this.__designReceipt=null;this.__financeLoadedIdentity='';this.__workbenchLoadedIdentity='';
  // 身份变化使旧锁owner与登记代次同时失效；旧Promise只能清自己的token，不能覆盖新岗位状态。
  this.__financeBusyOwner=null;this.bankConfirmIdem=null;this.bankConfirmIdemCtx=null;
  this.wbGeneration=(this.wbGeneration||0)+1;this.setData({wbForm:null,wbBusy:false,workbenchBooks:[],reimbursementRows:[],workbenchStaff:[],collectionDeclarations:[],financialStages:[],bookResult:null,bookEntries:[],bookSummary:[],bookScope:''});this.registerIdem=null;this._registerSeq=(this._registerSeq||0)+1;this._correctionCtx=null;
  this.__financeFacts=null;this.__journeyCash=null;this.__entryOrigin=null;this.__entryOptions=null;this.setData({journeyEnabled:false,journeyResult:null,journeyChoice:null,journeyCashRows:[],journeyPayables:[],journeyDeclaration:null,platformFinance:null,platformRegister:null,reconciliation:null,deskHome:null,busy:false,designFeeRows:[],designReceiptForm:null,designReceiptBusy:false,financeProjects:[],projectLedgerEntries:[],projectLedgerSummary:[],projectLedgerTotal:0,ledgerProjectLoading:false,registerType:'',registerForm:{},registerBusy:false});
  return true;
 },
 onShow(){
  this.__disposed=false;if(!this.__designFinance){require('./design-finance')(this,financeApi);this.__designFinance=true;}
  require('../../../utils/finance-keyboard').start(this);
  const activePost=auth.currentApiPost();this.setData({hq:!!activePost&&activePost.orgType==='headquarters',company:activePost&&activePost.orgName||''});
  const financeIdentity=String(httpApi.currentPostId())+':'+httpApi.sessionGeneration();this.syncFinanceIdentity(financeIdentity);
  if(this.data.view==='profile'&&activePost&&['finance','finance-lead'].includes(activePost.roleCode))return this.showFinanceProfile();
  const boss=activePost&&['boss','owner'].includes(activePost.roleCode);
  if(boss||this.__readOnlyLedgerIdentity){
   if(!this.__readOnlyLedgerIdentity)this.__readOnlyLedgerIdentity=this.__ledgerRouteIdentity;
   const allowed=boss&&activePost.orgType==='store'&&this.data.view==='project'&&/^[1-9]\d*$/.test(this.id)&&this.__readOnlyLedgerIdentity===financeIdentity;
   this.setData({...clean(),projectLedgerReadOnly:true,platformApiMode:allowed,platformFinance:null,platformRegister:null,reconciliation:null,refundVoidable:false,ready:allowed,title:views.project,error:allowed?'':'当前岗位或项目入口无访问权限',platformNotice:''});
   if(allowed)this.loadProjectLedger();
   return;
  }
  this.setData({projectLedgerReadOnly:false});
  if(this.data.view==='project'&&activePost&&['finance','finance-lead'].includes(activePost.roleCode)){this.options={...this.options,view:'book',bookScope:'project:'+this.id};this.setData({view:'book'});}
  if(!this.__platformActions)require('./platform-actions')(this,financeApi,PF),this.__platformActions=true;if(!this.__workbench){require('./workbench')(this,financeApi);this.__workbench=true;}this.installWorkbenchActions();if(!this.__journey){require('./journey')(this,financeApi);this.__journey=true;}this.setData({journeyEnabled:activePost&&activePost.orgType==='store'&&['finance','finance-lead'].includes(activePost.roleCode)&&this.options.messageType!=='design_fee'});
  if(!this.__ptSyncHook){const rawSetData=this.setData.bind(this);this.setData=(obj,cb)=>{rawSetData(obj,()=>{if(obj&&obj.platformTodos)this.syncPlatformTasks();if(cb)cb();});};this.__ptSyncHook=true;} // 平台待办到达后同步 tasks 视图行与工作台预览
  this.loadWorkbench();
  this.loadPlatformFinance(); // M+：先置 platformApiMode，本地分支按态跳过（财务岗不再触达本地账簿）
  if(this.data.platformApiMode)this.loadDesignFees();
  if(this.data.platformApiMode&&this.data.view==='project')this.loadProjectLedger();
  if(this.data.platformApiMode&&this.data.view==='projects')this.loadFinanceProjects();
  if(this.data.platformApiMode&&['project','projects','statement'].includes(this.data.view))this.setData({platformNotice:''});
  if(this.data.platformApiMode&&this.data.view==='flows')this.loadFinanceSourceRows();
  if(!this.data.platformApiMode)this.refresh();
  if(this.data.platformApiMode&&this.data.view==='book')this.loadBookLedger();
  PTPage.refresh(this,false);this.syncPlatformTasks();
  },
 onHide(){this.__registerValidation=null;this._registerSeq=(this._registerSeq||0)+1;if(this.data.registerBusy)this.setData({registerBusy:false});require('../../../utils/finance-keyboard').stop(this);},
 onUnload(){this.__disposed=true;require('../../../utils/finance-keyboard').stop(this);},
 showFinanceProfile(){
  this.setData({view:'profile',ready:true,title:'我的',platformApiMode:true,error:''});
  require('../../profile/profile-page')(4).onShow.call(this);
 },
 open(e){if(this.data.view==='profile')return require('../../profile/profile-page')(4).open.call(this,e);},
 logout(){if(this.data.view==='profile')return require('../../profile/profile-page')(4).logout.call(this);},
 canLoadProjectLedger(){
  const post=auth.currentApiPost(),identity=String(httpApi.currentPostId())+':'+httpApi.sessionGeneration();
  if(!httpApi.token()||!post||this.__disposed||this.data.view!=='project'||!/^[1-9]\d*$/.test(this.id)||identity!==this.__financeIdentity)return false;
  if(this.__readOnlyLedgerIdentity)return identity===this.__readOnlyLedgerIdentity&&post.orgType==='store'&&['boss','owner'].includes(post.roleCode);
  return ['finance','finance-lead'].includes(post.roleCode);
 },
 syncPlatformTasks(){ // M+：API 态待办渲染——tasks 视图行与工作台预览均来自平台待办，不读本地任务表
  if(!this.data.platformApiMode)return;
  const view=this.data.view;
  if(view!=='tasks'&&view!=='desk')return;
  const st=this.data.platformTodos||{rows:[]};
  const rows=(st.rows||[]).map(r=>({key:r.key,name:r.title,clickable:r.clickable,amount:String(r.amount||'').replace(/\B(?=(\d{3})+(?!\d))/g,','),status:r.actionHint||'',label:r.clickable?'去办理':'查看',url:r.path||'',icon:'/assets/management-receipt.jpg',...(this.presentFinanceTask?this.presentFinanceTask(r):null)}));
  if(view==='tasks')return this.setData({ready:true,error:'',title:views.tasks,rows,tasks:rows,allCount:rows.length,taskCount:rows.length,platformNotice:st.status==='degraded'||st.status==='partial-empty'?(st.message||'待办暂时无法加载，请下拉重试'):''});
  this.setData({'deskHome.tasks':rows,'deskHome.previewTasks':rows.slice(0,3),'deskHome.taskCount':rows.length,'deskHome.metrics[3].value':rows.length});
 },
 renderPlatformShell(){ // M+：平台 API 态渲染——本地账簿/项目/加盟商分支全部跳过；无平台接口的视图显示空态提示
  const view=this.data.view;
  if(view==='book'){this.setData({...clean(),ready:true,title:views.book});return this.loadBookLedger();}
  if(view==='reimbursements'||view==='progress'){this.setData({...clean(),ready:true,title:views[view]});return this.loadWorkbench();}

  if(view==='tasks')return this.syncPlatformTasks();
  if(view==='project'){this.setData({...clean(),ready:true,title:views.project,platformNotice:''});this.loadProjectLedger();return;}
  if(view==='projects'){this.setData({...clean(),ready:true,title:views.projects,platformNotice:''});this.loadFinanceProjects();return;}
  if(this.data.journeyEnabled&&view==='flows'){this.setData({...clean(),ready:true,title:views.flows});return this.refreshJourney();}
  if(view==='flows'&&['payables','receivables'].includes(this.data.flowTab)){this.setData({...clean(),ready:true,title:views.flows,platformNotice:''});this.loadFinanceSourceRows();return;}
  const notice={projects:'项目收支暂无平台视图，请前往「收支 · 对账」查看',project:'项目账目暂无平台视图，请前往「收支 · 对账」查看',partners:'加盟商往来即将上线',partner:'加盟商往来即将上线',statement:'设计款到账进度即将上线'}[view]||(view==='flows'&&!['ledger','reconcile'].includes(this.data.flowTab)?'该列表暂无平台数据，登记与台账请使用「流水」与登记入口':'');
  this.setData({...clean(),ready:true,error:'',title:views[view],platformNotice:notice});
 },
 loadPlatformFinance(){const post=auth.currentApiPost();if(!post||!['finance','finance-lead'].includes(post.roleCode)){this.setData({platformFinance:null,platformApiMode:false,registerType:'',refundVoidable:false,reconciliation:null,voidNotice:'',ledgerError:''});return;}this.__financeLoadedIdentity=this.__financeIdentity;const month=this.data.selectedMonth||((new Date().getFullYear())+'-'+('0'+(new Date().getMonth()+1)).slice(-2));const view=this.data.view,hq=post.orgType==='headquarters',emptyMetrics=PF.metrics([],[],[],month);this.setData({platformApiMode:true,refundVoidable:['finance-lead','boss','owner'].includes(post.roleCode),platformFinance:{status:'loading',month,metrics:emptyMetrics,ledger:{rows:[],allCount:0},bankFlows:[]},platformRegister:null,reconciliation:{status:'loading'}, // M+：平台态自带渲染态——不再依赖本地 refresh 提供 ready/title/deskHome
  ...clean(),ready:true,title:views[view]||views.desk,platformNotice:{projects:'项目收支暂无平台视图，请前往「收支 · 对账」查看',project:'项目账目暂无平台视图，请前往「收支 · 对账」查看',partners:'加盟商往来即将上线',partner:'加盟商往来即将上线',statement:'设计款到账进度即将上线'}[view]||(view==='flows'&&!['ledger','reconcile'].includes(this.data.flowTab)?'该列表暂无平台数据，登记与台账请使用「流水」与登记入口':''),deskHome:{allowed:true,hq,lead:post.lead===true,name:post.nickname||post.userName||'财务同事',title:'财务工作台',company:post.orgName||'',date:'',sample:false,period:month,periodText:month,metrics:platformMetricCards(emptyMetrics),tasks:[],previewTasks:this.data.deskHome&&this.data.deskHome.previewTasks||[],taskCount:this.data.deskHome&&this.data.deskHome.taskCount||0,tasksUrl:D.url('tasks'),shortcuts:PF.shortcuts(hq),focus:this.data.deskHome&&this.data.deskHome.focus||[],focusUrl:D.url(hq?'partners':'progress')}});this.syncPlatformTasks();const pgAlive=httpApi.requestGuard('platformFinance'); // M08-T02/I03：A/B换岗乱序、退出、连续刷新——旧响应不得写当前页（sessionGen+序号双校验）
const profileAlive=httpApi.requestGuard('financeProfile');httpApi.get('/platform/profile/mine').then(profile=>{if(profileAlive()&&profile&&profile.nickname)this.setData({'deskHome.name':profile.nickname});}).catch(error=>{if(profileAlive())console.warn('[finance-desk] profile lookup failed',{code:error&&error.code});});
Promise.all([financeApi.receipts(),financeApi.payments(),financeApi.refunds(),financeApi.bankFlows(),financeApi.payables(),financeApi.receipts('待登记')]).then(([receipts,payments,refunds,flows,payables,pendingReceipts])=>{if(!pgAlive())return;this.__financeFacts={receipts,payments,refunds,payables};const m=PF.metrics(receipts,payments,refunds,month);const led=PF.ledgerRows(receipts,payments,refunds,this.data.flowScope==='all'?'':month);const refundedByReceipt=(refunds||[]).filter(r=>r.status==='已核实'&&r.refundDirection!=='expense').reduce((map,r)=>{map[r.receiptId]=(map[r.receiptId]||0)+(r.amountCents||0);return map;},{});const refundable=(receipts||[]).filter(r=>r.status==='已登记').map(r=>({id:r.id,label:r.name+' ¥'+PF.yuan(r.amountCents),amountCents:r.amountCents,refundableCents:r.amountCents-(refundedByReceipt[r.id]||0)}));const expenseRefundedByPayment=(refunds||[]).filter(r=>r.status==='已核实'&&r.refundDirection==='expense').reduce((map,r)=>{map[r.paymentId]=(map[r.paymentId]||0)+(r.amountCents||0);return map;},{});const expenseRefundable=(payments||[]).filter(p=>p.status==='已核实').map(p=>({id:p.id,label:'付款#'+p.id+' ¥'+PF.yuan(p.amountCents)+(p.payDate?' · '+p.payDate:'')+(p.transactionNo?' · '+p.transactionNo:''),amountCents:p.amountCents,refundableCents:p.amountCents-(expenseRefundedByPayment[p.id]||0)}));const payableOptions=PF.payableProgress(payables,payments,refunds).filter(p=>p.canPay).map(p=>({id:p.id,sourceType:p.sourceType,label:(p.name||({FULL:'设计费',FIRST:'设计首款',TAIL:'设计尾款',engineering:'材料采购'}[p.stage])||p.stage||p.recipient||'费用付款')+' 未付 ¥'+(PF.yuan(p.remainingCents)||'0.00'),totalCents:p.totalCents,paidCents:p.paidCents,remainingCents:p.remainingCents}));const flowRows=(led.rows||[]).map(r=>{const sourceKind=sourceKindOf(r.key);return{id:r.key,name:r.name,category:r.type,sign:r.sign,amountCents:r.amountCents,amountText:(r.sign==='+'?'':'-')+'¥'+PF.yuan(r.amountCents),status:r.status,date:r.date,dateText:r.date,detail:r.type,detailText:r.type+' '+r.status,tone:r.sign==='+'?'':'negative',revisionVersion:r.revisionVersion,sourceKind,canVoid:PF.canVoidPayment(post.roleCode,sourceKind,r.status)}});this.ledgerSource=flowRows;this._refundRows=refunds||[];this.setData({platformFinance:{status:'ready',month,metrics:m,ledger:led,bankFlows:(flows||[]).filter(f=>f.status==='待匹配')},platformRegister:{payables:payableOptions,refundable:refundable,expenseRefundable:expenseRefundable,pendingReceipts:(pendingReceipts||[]).map(r=>({id:r.id,label:r.name+' ¥'+PF.yuan(r.amountCents),amountCents:r.amountCents||0}))},platformLedger:flowRows.slice(0,PAGE_SIZE),ledgerAllCount:flowRows.length,ledgerPage:1,'deskHome.metrics':platformMetricCards(m,this.data.deskHome&&this.data.deskHome.taskCount||0)});if(this.refreshJourney)this.refreshJourney();}).catch(e=>{if(!pgAlive())return;this.setData({platformFinance:{status:'degraded',message:PF.errorMessage(e)},'deskHome.metrics':platformMetricCards({received:'—',paid:'—',refunded:'—',net:'—'},this.data.deskHome&&this.data.deskHome.taskCount||0),ledgerAllCount:0,ledgerPage:1});});// M08-T08 批次#34/R1 P2-2：对账独立于台账六接口并行启动（不串行依赖、台账失败仍拉取），同 pgAlive 守卫
financeApi.reconciliation(month).then(rec=>{if(!pgAlive())return;const fx=c=>displayFinanceMoney(c);this.setData({reconciliation:{status:'ready',month:rec.month,grossText:fx(rec.grossReceiptsCents),refundsText:fx(rec.clientRefundsCents),netText:fx(rec.netReceivedCents),abnormalCents:rec.abnormalPendingCents||0,abnormalText:fx(rec.abnormalPendingCents),abnormalCount:rec.abnormalPendingCount||0,datePendingText:fx(rec.abnormalDatePendingCents),datePendingCount:rec.abnormalDatePendingCount||0}});}).catch(err=>{if(!pgAlive())return;this.setData({reconciliation:{status:'degraded',message:PF.errorMessage(err)}});});},
 refreshFinanceTasks(){PTPage.refresh(this,true);},
 retryPlatformFinance(){this.loadPlatformFinance();},
 openFinanceShortcut(e){const type=e.currentTarget.dataset.type;if(type&&this.openRegister){if(this.rememberEntryOrigin)this.rememberEntryOrigin();this.setData({view:'flows',title:views.flows,flowTab:'ledger'});return this.openRegister({currentTarget:{dataset:{type}}});}return this.go(e);},
 dismissVoidNotice(){this.setData({voidNotice:'',ledgerError:''});},
 copyFrLink(){ // P1 §5.4：复制 /fr/finance 站内链接（主机=config/env.js FR_HOST 或 storage 覆写；不含任何 token）
  const link=FrLink.buildFromContext(wx.getStorageSync(FrLink.STORAGE_KEY),'/fr/finance');
  if(!link)return wx.showToast({title:FrLink.NOT_CONFIGURED,icon:'none'});
  wx.setClipboardData({data:link,success:()=>wx.showToast({title:'链接已复制，请在 PC 浏览器登录后打开',icon:'none'})});
 },
 loadMoreLedger(){ // M5 F3：滚动追加下一页（本地切片）
   const all=this.data.platformLedger||[];const page=(this.data.ledgerPage||1)+1;
   const src=this.ledgerSource||[];const next=src.slice(0,page*PAGE_SIZE);
   if(next.length<=all.length)return;
   this.setData({platformLedger:next,ledgerPage:page});
 },
 bankPick(e){const flowId=e.currentTarget.dataset.flowId;this.setData({['bankPick'+flowId]:Number(e.detail.value)});},
 confirmBankSubmit(e){if(!this.confirmBankSubmitInner)return;this.confirmBankSubmitInner(e);},
 restoreNavigationState(){this.refresh();},
 hasWorkspaceSubpage(){return !!this.data.journeyEnabled&&(!!this.data.wbForm||!!this.data.registerType||!!this.data.journeyResult||!!this.data.journeyDeclaration||['book','flows','reimbursements','progress'].includes(this.data.view));},
 onPageScroll(e){this.__navigationScrollTop=e.scrollTop;},
 refresh(){if(this.data.platformApiMode)return this.renderPlatformShell(); // M+：平台 API 态跳过全部本地数据分支
  // 切生产收口：本地账簿渲染体（M.load/D.flows/D.projectRows 本地链）已下线——非平台态统一错误态
  return this.setData({...clean(),error:auth.currentApiPost()?'当前岗位无财务权限，请从财务岗进入':'请先登录后端账号'});
 },
 go(e){if(this.data.projectLedgerReadOnly)return;const destination=e.currentTarget.dataset.url;if(destination)N.open(destination+(destination.includes('view=flows')&&this.data.selectedMonth?'&month='+this.data.selectedMonth:''));},
 search(e){this.setData({keyword:e.detail.value});if(this.data.journeyEnabled&&this.data.view==='flows')return this.refreshJourney();this.refresh();},clear(){this.setData({keyword:'',filter:'all',statusFilter:'全部状态',category:['all','current'].includes(this.data.flowScope)?'实收':'全部类别',due:''});this.refresh();},
 segment(e){const tab=e.currentTarget.dataset.tab;if(tab==='books'){const next=X.context().orgType==='headquarters'?'partners':'projects';this.tabStates=this.tabStates||{};if(this.data.view==='flows')this.tabStates.flows={keyword:this.data.keyword,filter:this.data.filter,flowTab:this.data.flowTab,flowScope:this.data.flowScope,category:this.data.category,statusFilter:this.data.statusFilter};const saved=this.tabStates[next]||{};this.options={view:next};this.id='';this.setData({...clean(),view:next,filter:'all',statusFilter:'全部状态',due:'',...saved});this.refresh();wx.pageScrollTo({scrollTop:0,duration:0});return;}if(this.data.view!=='flows'){this.tabStates=this.tabStates||{};this.tabStates[this.data.view]={keyword:this.data.keyword,filter:this.data.filter};const saved=this.tabStates.flows||{};this.options={view:'flows'};this.id='';this.setData({...clean(),view:'flows',filter:'all',statusFilter:'全部状态',due:'',...saved,flowTab:tab});this.refresh();wx.pageScrollTo({scrollTop:0,duration:0});return;}this.setData({flowTab:tab,flowScope:'period',keyword:'',category:'全部类别',statusFilter:'全部状态',filter:'all',due:''});this.refresh();},
 filter(e){this.setData({filter:e.currentTarget.dataset.filter});this.refresh();},
 pick(e){const type=e.currentTarget.dataset.type,choices=type==='sort'?['到期优先','金额优先','最新优先']:type==='category'?this.data.categoryOptions:this.data.statusOptions;this.setData({[type==='status'?'statusFilter':type]:choices[Number(e.detail.value)]});this.refresh();},
 month(e){if(this.data.platformApiMode){const value=e&&e.detail&&e.detail.value;if(!/^\d{4}-(0[1-9]|1[0-2])$/.test(value||''))return;this.setData({selectedMonth:value,period:value,periodText:value});return this.loadPlatformFinance();} // M+：平台态账期固定当月，不写本地月份偏好
  // 切生产收口：本地月份偏好写入（D.setPeriod 写 storage）下线
  wx.showToast({title:'请先登录财务岗位后操作',icon:'none'});},financeMonth(e){this.month(e);},
 reconcileBook(){ // M+：本地对账留痕（M.mutate）已下线，改走平台 operating.reconcile；失败仅提示不回落本地
  if(this.data.busy)return;
  const month=(this.data.reconciliation&&this.data.reconciliation.month)||new Date().getFullYear()+'-'+('0'+(new Date().getMonth()+1)).slice(-2);
  const busyToken=this.beginFinanceBusy();if(!busyToken)return;
  perfApi.operating.reconcile(month).then(()=>{if(!this.ownsFinanceBusy(busyToken))return;wx.showToast({title:'本月对账已完成并留痕',icon:'none'});this.loadPlatformFinance();}).catch(e=>{if(this.ownsFinanceBusy(busyToken))wx.showToast({title:(e&&(e.message||e.msg))||'对账失败，请稍后重试',icon:'none'});}).finally(()=>this.finishFinanceBusy(busyToken));
 },
 switchWorkspaceTab(tab){if(!['finance-home','finance-books','profile'].includes(tab.key))return false;const post=auth.currentApiPost();if(!post||!['finance','finance-lead'].includes(post.roleCode))return false;if(this.data.wbBusy||this.data.registerBusy||this.data.busy)return true;this.wbGeneration=(this.wbGeneration||0)+1;this._registerSeq=(this._registerSeq||0)+1;this.__entryOrigin=null;this.__entryOptions=null;this.setData({wbForm:null,registerType:'',journeyResult:null,journeyDeclaration:null,bookEntryDetail:null});const next=tab.key==='profile'?'profile':tab.key==='finance-home'?'desk':post.orgType==='headquarters'?'flows':'projects';this.tabStates=this.tabStates||{};this.tabStates[this.data.view]={keyword:this.data.keyword,filter:this.data.filter,flowTab:this.data.flowTab,flowScope:this.data.view==='flows'?this.data.flowScope:'period',category:this.data.category,statusFilter:this.data.statusFilter};const saved=this.tabStates[next]||{};this.options={view:next};this.id='';this.setData({...clean(),ready:true,view:next,keyword:'',filter:'all',category:'全部类别',statusFilter:'全部状态',due:'',...saved,flowScope:next==='flows'?(saved.flowScope||'period'):'period',...(next==='flows'?{flowTab:saved.flowTab||'ledger'}:{})});if(next==='profile')this.showFinanceProfile();else if(!this.__platformActions||this.__financeLoadedIdentity!==this.__financeIdentity)this.onShow();else this.refresh();wx.pageScrollTo({scrollTop:0,duration:0});return true;},
 back(){N.back({skipLocal:true,fallback:D.url(this.data.hq?'partners':'projects')});},home(){N.home();}


});
