const { createDashboard } = require('../../utils/dashboard');
const PTPage = require('../../utils/platform-todos-page');
const http = require('../../api/http');
const CRM = require('../../utils/platform-crm');
const crmApi = require('../../api/franchise/crm');
const commissionApi = require('../../api/franchise/commission');
const contentApi = require('../../api/franchise/content');
const loadSummaryRows=CRM.loadSummaryRows;
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
const request=Promise.all([loadSummaryRows(crmApi.customers.page,current,'客户'),loadSummaryRows(commissionApi.commissions.page,current,'委托'),contentApi.growth.summary(),contentApi.drafts.works().catch(()=>[])]).then(([custRows,commRows,growth,works])=>{
if(this.stale(gen,sessionGen))return;
this.applyPlatformHome(post,custRows,commRows,growth,require('../../utils/platform-content').rowsOf(works));
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
definition.applyPlatformHome=function(post,custRows,commRows,growth,works){
const day=CRM.today();
const summary=CRM.homeSummary(custRows,commRows,post,day);
const {rows:summaryRows,monthRows,commission,due,upcoming,overdue}=summary,rows=summaryRows.slice(0,50);
// wxml 客户行原键名适配：desc/button/action/avatar 由 API 行映射补齐
const desk=rows.map(r=>({...r,avatar:'/assets/avatar-wang.jpg',desc:r.detail,button:r.action,action:'customerDetail'}));
const g=growth&&typeof growth==='object'?growth:{};
const following=summaryRows.filter(r=>r.status==='跟进中').length;
const week='日一二三四五六'.charAt(new Date(day+'T12:00:00+08:00').getUTCDay());
const dateText=Number(day.slice(5,7))+'月'+Number(day.slice(8,10))+'日 周'+week;
const patch={platformStatus:'ready',platformError:'',platCustomers:rows,platCommissions:monthRows.slice(0,50),platGrowth:g,customers:desk,filteredCustomers:this.filterRows(desk),
recentCustomer:desk[0]?{id:desk[0].id,initial:desk[0].initial,name:desk[0].name,stage:desk[0].stageName}:null,
recentCreation:(works&&works[0])?{id:'work:'+(works[0].workId!=null?works[0].workId:works[0].id),title:String(works[0].title||'我的作品'),badge:'我的作品',image:'/assets/business-video.jpg'}:null};
if(['boss','owner'].includes(post.roleCode)){
const leads=Number(g.leads)||0,pubs=Number(g.publications)||0,converted=Number(g.converted)||0,max=Math.max(leads,pubs,converted,monthRows.length,1);
const width=v=>Math.max(6,Math.round(v/max*100));
const funnel=[{label:'新增线索',value:leads,source:'',width:width(leads),strong:false},{label:'内容发布',value:pubs,source:'',width:width(pubs),strong:false},{label:'转化客户',value:converted,source:'',width:width(converted),strong:false},{label:'设计委托',value:monthRows.length,source:'',width:width(monthRows.length),strong:true}];
patch.isSalesHome=false;patch.isBossBiz=true;patch.bizDate=dateText;
patch.bossBiz={commission,commissionCount:monthRows.length,leads,following,converted,conversion:leads?Math.round(monthRows.length/leads*100):null,funnel,staleCount:overdue.length,staleNames:overdue.slice(0,2).map(r=>r.name).join('、'),due:due.length,appointments:upcoming.length};
}else{
patch.isSalesHome=true;patch.isBossBiz=false;
patch.customerCount=summaryRows.length;
patch.salesDate=dateText;patch.todayCount=due.length;patch.overdueCount=overdue.length;patch.appointmentCount=upcoming.length;patch.commission=commission;
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
