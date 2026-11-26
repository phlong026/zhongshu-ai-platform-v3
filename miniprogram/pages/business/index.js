const { createDashboard } = require('../../utils/dashboard');
const PTPage = require('../../utils/platform-todos-page');
const http = require('../../api/http');
const CRM = require('../../utils/platform-crm');
const crmApi = require('../../api/franchise/crm');
const commissionApi = require('../../api/franchise/commission');
const contentApi = require('../../api/franchise/content');
const loadSummaryRows=CRM.loadSummaryRows;
// 首页的客户数字读服务端汇总（与客户列表分栏同源），不再把客户全量拉回来到端上累加；
// 名单只取今日待跟进的前 20 位（服务端按约定日期排序，最靠前的最逾期），用于“最近办理”和老板首页的逾期提示。
function customerHome(isStale){
 const take=value=>{if(isStale())throw {silent:true};return value;};
 return Promise.all([crmApi.customers.followSummary().then(take),crmApi.customers.page(1,20,undefined,'DUE').then(take)])
  .then(([counts,due])=>({counts:counts||{},due:((due&&due.list)||[]).map(c=>CRM.customerRow(c,{today:CRM.today()}))}));
}
const definition=createDashboard(1, {
data: { panel: null, keyword: '', customers: [], filteredCustomers: [],
platCustomers: [], platCommissions: [], platGrowth: null, platformStatus: '', platformError: '', platformTodos: null,
salesShortcuts: [{ title: '登记客户', icon: 'person-filled', action: 'customer' }, { title: '灵感检索', icon: 'home-design-icon', action: 'inspiration', url: '/design-migration/pages/library/index' }, { title: '快速预算', icon: 'plan-file', action: 'quickBudget', url: '/pages/workflow/explore/index?view=budget' }, { title: '提交委托', icon: 'contract', action: 'commission', url: '/pages/customers/index?intent=commission' }],
creation: [{ title: '爆款二创', icon: 'fire', action: 'remix' }, { title: '数字人口播', icon: 'person-filled', action: 'digital' }, { title: 'AI视频', icon: 'video-filled', action: 'video' }]
},
onLoad() { PTPage.init(this); },
search(e) {
const keyword = e.detail.value.trim();
this.setData({keyword});this.filterCustomers();
}
});
const dashboardShow=definition.onShow;
definition.onShow=function(){const a=require('../../utils/access').context(),auth=require('../../api/auth'),post=(()=>{try{return http.token()?auth.currentApiPost():null;}catch(e){return null;}})();if(post?post.roleCode==='hq-design-admin':a.role==='hq-design-admin'){wx.reLaunch({url:'/pages/workflow/commission/index?view=admin'});return;}dashboardShow.call(this);this.setData({active:a.active||!!post});this.loadPlatformHome();};
// API 态工作台：客资/本月委托/获客漏斗并行拉取；平台待办走 PTPage。失败显示错误态，绝不回落本地演示数据。
definition.loadPlatformHome=function(){
const auth=require('../../api/auth');
const token=http.token(),post=(()=>{try{return token?auth.currentApiPost():null;}catch(e){return null;}})();
if(!token||!post){this.guestHome();return;}
const sessionGen=http.sessionGeneration(),scope=[sessionGen,post.orgId,post.postId].join(':');
if(this.__homeRequest&&this.__homeRequest.scope===scope)return this.__homeRequest.promise;
const sameScope=this.__homeScope===scope;this.__homeScope=scope;
const gen=this.__platGen=(this.__platGen||0)+1;
const salesHome=['sales','sales-lead'].includes(post.roleCode);
const context={isSalesHome:salesHome,isBossBiz:['boss','owner'].includes(post.roleCode),platformError:''};
if(!sameScope)Object.assign(context,{salesName:post.name||post.nickname||'同事',salesDate:CRM.today(),platformStatus:'loading',todayCount:'—',appointmentCount:'—',customerCount:'—',overdueCount:0,commission:{value:'—',unit:'元'},platCustomers:[],platCommissions:[],platGrowth:null,customers:[],filteredCustomers:[],bossBiz:null,recentCustomer:null,recentCreation:null});
this.setData(context);
http.get('/platform/profile/mine').then(profile=>{if(!this.stale(gen,sessionGen)&&profile&&profile.nickname)this.setData({salesName:profile.nickname});}).catch(()=>{});
PTPage.refresh(this,false);
const current=()=>this.stale(gen,sessionGen);
const request=Promise.all([customerHome(current),loadSummaryRows(commissionApi.commissions.page,current,'委托'),contentApi.growth.summary(),contentApi.drafts.works().catch(()=>[])]).then(([home,commRows,growth,works])=>{
if(this.stale(gen,sessionGen))return;
this.applyPlatformHome(post,home,commRows,growth,require('../../utils/platform-content').rowsOf(works));
}).catch(err=>{
if(err&&err.silent)return;
if(err&&err.code===401){if(!this.stale(gen,sessionGen))this.guestHome();return;}
if(this.stale(gen,sessionGen))return;
const bossView=!!(post&&['boss','owner'].includes(post.roleCode));
this.setData({platformStatus:'degraded',platformError:CRM.errorMessage(err),platCustomers:[],platCommissions:[],platGrowth:null,customers:[],filteredCustomers:[],isSalesHome:['sales','sales-lead'].includes(post.roleCode),isBossBiz:bossView,bossBiz:null,recentCustomer:null,recentCreation:null,todayCount:'—',overdueCount:0,appointmentCount:'—',customerCount:'—',commission:{value:'—',unit:'元'}});
});
const pending={scope,promise:null};
pending.promise=request.finally(()=>{if(this.__homeRequest===pending)this.__homeRequest=null;});
this.__homeRequest=pending;return pending.promise;
};
definition.stale=function(gen,sessionGen){return gen!==this.__platGen||sessionGen!==http.sessionGeneration();};
// 游客/无岗位：只保留登录引导与空态，不注入任何本地演示数据
definition.guestHome=function(){this.__platGen=(this.__platGen||0)+1;this.__homeScope='';this.__homeRequest=null;PTPage.refresh(this,false);this.setData({platformStatus:'',platformError:'',platCustomers:[],platCommissions:[],platGrowth:null,customers:[],filteredCustomers:[],isSalesHome:false,isBossBiz:false,bossBiz:null,todayCount:0,overdueCount:0,appointmentCount:0,commission:null,recentCustomer:null,recentCreation:null});};
definition.applyPlatformHome=function(post,home,commRows,growth,works){
const day=CRM.today(),counts=(home&&home.counts)||{},dueRows=(home&&home.due)||[];
const count=(value,fallback)=>Number.isFinite(Number(value))?Number(value):fallback;
// 委托汇总沿用现有纯函数（客户传空名单）；客户数字读服务端 follow-summary，口径与客户列表分栏一致。
const {monthRows,commission}=CRM.homeSummary([],commRows,post,day);
const overdue = dueRows.filter(row => row.appointment && row.appointment < day);
// wxml 客户行原键名适配：desc/button/action/avatar 由 API 行映射补齐
const desk=dueRows.map(r=>({...r,avatar:'/assets/avatar-wang.jpg',desc:r.detail,button:r.action,action:'customerDetail'}));
const g=growth&&typeof growth==='object'?growth:{};
const following=count(counts.following,0);
const week='日一二三四五六'.charAt(new Date(day+'T12:00:00+08:00').getUTCDay());
const dateText=Number(day.slice(5,7))+'月'+Number(day.slice(8,10))+'日 周'+week;
const patch={platformStatus:'ready',platformError:'',platCustomers:desk,platCommissions:monthRows.slice(0,50),platGrowth:g,customers:desk,filteredCustomers:this.filterRows(desk),
recentCustomer:desk[0]?{id:desk[0].id,initial:desk[0].initial,name:desk[0].name,stage:desk[0].stageName}:null,
recentCreation:(works&&works[0])?{id:'work:'+(works[0].workId!=null?works[0].workId:works[0].id),title:String(works[0].title||'我的作品'),badge:'我的作品',image:'/assets/business-video.jpg'}:null};
if(['boss','owner'].includes(post.roleCode)){
const leads=g.consultations??'—',converted=g.linkedCustomers??'—',signed=g.signedCustomers??'—';
const metrics=[['已发布',g.publishedCount],['有效咨询',leads],['关联客户',converted],['关联客户已签约',signed],['关联客户有净实收',g.netPaidCustomers]];
const max=Math.max(1,...metrics.map(([,v])=>Number(v)||0));
const funnel=metrics.map(([label,v])=>({label,value:v??'—',width:Math.round(100*(Number(v)||0)/max)}));
patch.isSalesHome=false;patch.isBossBiz=true;patch.bizDate=dateText;
patch.bossBiz={commission,commissionCount:monthRows.length,leads,following,converted,signed,funnel,staleCount:count(counts.overdue,overdue.length),staleNames:overdue.slice(0,2).map(r=>r.name).join('、'),due:count(counts.due,0),appointments:count(counts.appointments,0)};
}else{
patch.isSalesHome=true;patch.isBossBiz=false;
patch.customerCount=count(counts.total,0);
patch.salesDate=dateText;patch.todayCount=count(counts.due,0);patch.overdueCount=count(counts.overdue,overdue.length);patch.appointmentCount=count(counts.appointments,0);patch.commission=commission;
}
this.setData(patch);
};
definition.filterRows=function(rows){const keyword=this.data.keyword||'';if(!keyword)return rows;return rows.filter(c=>((c.name||'')+(c.searchText||'')+(c.desc||'')).includes(keyword));};
definition.filterCustomers=function(){this.setData({filteredCustomers:this.filterRows(this.data.customers||[])});};
// Inspiration is a secondary tool. Existing budget behavior stays unchanged pending its scope decision.
definition.openSalesShortcut=function(e){const url=e.currentTarget.dataset.url;if(url)return require('../../utils/page-navigation').open(url);this.openAction(e);};
definition.openSalesMetric=function(e){const focus=e.currentTarget.dataset.focus;if(focus==='commission')return require('../../utils/page-navigation').open('/pages/account/index?view=personal-performance');if(['all','due','appointments'].includes(focus))require('../../utils/page-navigation').open('/pages/customers/index?focus='+focus);};
definition.continueSalesCustomer=function(){if(this.data.recentCustomer)require('../../utils/page-navigation').open('/pages/workflow/index?view=customer&id='+encodeURIComponent(this.data.recentCustomer.id));};
definition.continueSalesCreation=function(){require('../../utils/page-navigation').open('/pages/creation/index');};
definition.openBossLink=function(e){const url=e.currentTarget.dataset.url;if(url)require('../../utils/page-navigation').open(url);};
definition.retryPlatformTodos=function(){PTPage.retry(this);};
definition.openPlatformTodo=function(e){PTPage.open(this,e);};
Page(definition);
