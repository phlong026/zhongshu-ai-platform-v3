const { createDashboard } = require('../../utils/dashboard');
const PTPage = require('../../utils/platform-todos-page');
const http = require('../../api/http');
const CRM = require('../../utils/platform-crm');
const crmApi = require('../../api/franchise/crm');
const commissionApi = require('../../api/franchise/commission');
const contentApi = require('../../api/franchise/content');
const definition=createDashboard(1, {
data: { panel: null, keyword: '', customers: [], filteredCustomers: [],
platCustomers: [], platCommissions: [], platGrowth: null, platformStatus: '', platformError: '', platformTodos: null,
salesShortcuts: [{ title: '登记客户', icon: 'person-filled', action: 'customer' }, { title: '内容创作', icon: 'video-filled', action: 'remix' }, { title: 'AI设计', icon: 'home-design-icon', action: 'aiDesign', url: '/pages/design/index' }, { title: '快速预算', icon: 'contract', action: 'quickBudget', url: '/pages/workflow/explore/index?view=budget' }],
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
this.__platGen=(this.__platGen||0)+1;
if(!token||!post){this.guestHome();return;}
const gen=this.__platGen,sessionGen=http.sessionGeneration();
PTPage.refresh(this,false);
Promise.all([crmApi.customers.page(1,50),commissionApi.commissions.page(1,50),contentApi.growth.summary(),contentApi.drafts.works().catch(()=>[])]).then(([custRes,commRes,growth,works])=>{
if(this.stale(gen,sessionGen))return;
this.applyPlatformHome(post,(custRes&&custRes.list)||[],(commRes&&commRes.list)||[],growth,require('../../utils/platform-content').rowsOf(works));
}).catch(err=>{
if(err&&err.silent)return;
if(err&&err.code===401){if(!this.stale(gen,sessionGen))this.guestHome();return;}
if(this.stale(gen,sessionGen))return;
const bossView=!!(post&&['boss','owner'].includes(post.roleCode));
this.setData({platformStatus:'degraded',platformError:CRM.errorMessage(err),platCustomers:[],platCommissions:[],platGrowth:null,customers:[],filteredCustomers:[],isSalesHome:false,isBossBiz:bossView,bossBiz:null,recentCustomer:null,recentCreation:null,todayCount:0,overdueCount:0,appointmentCount:0,commission:null});
});
};
definition.stale=function(gen,sessionGen){return gen!==this.__platGen||sessionGen!==http.sessionGeneration();};
// 游客/无岗位：只保留登录引导与空态，不注入任何本地演示数据
definition.guestHome=function(){PTPage.refresh(this,false);this.setData({platformStatus:'',platformError:'',platCustomers:[],platCommissions:[],platGrowth:null,customers:[],filteredCustomers:[],isSalesHome:false,isBossBiz:false,bossBiz:null,todayCount:0,overdueCount:0,appointmentCount:0,commission:null,recentCustomer:null,recentCreation:null});};
definition.applyPlatformHome=function(post,custRows,commRows,growth,works){
const day=CRM.today(),period=day.slice(0,7);
const rows=(custRows||[]).map(c=>CRM.customerRow(c,{today:day,canWrite:CRM.can(post,'write')}));
// wxml 客户行原键名适配：desc/button/action/avatar 由 API 行映射补齐
const desk=rows.map(r=>({...r,avatar:'/assets/avatar-wang.jpg',desc:r.detail,button:r.action,action:'customerDetail'}));
const monthRows=(commRows||[]).filter(r=>{const d=CRM.toDate(r.createTime||r.date||r.submittedAt);return d&&d.slice(0,7)===period;});
const cents=r=>{const v=r.totalCents!=null?r.totalCents:r.amountCents!=null?r.amountCents:r.commissionCents!=null?r.commissionCents:0;return Number(v)||0;};
const totalYuan=Math.round(monthRows.reduce((n,r)=>n+cents(r),0)/100);
const commission=totalYuan>=10000?{value:String(Math.round(totalYuan/100)/100),unit:'万元'}:{value:totalYuan.toLocaleString('en-US'),unit:'元'};
const g=growth&&typeof growth==='object'?growth:{};
const due=CRM.tabFilter(rows,'due',{today:day}),upcoming=CRM.tabFilter(rows,'appointments',{today:day});
const overdue=rows.filter(r=>r.appointment&&r.appointment<day);
const following=rows.filter(r=>r.status==='跟进中').length;
const week='日一二三四五六'.charAt(new Date(day+'T12:00:00+08:00').getUTCDay());
const dateText=Number(day.slice(5,7))+'月'+Number(day.slice(8,10))+'日 周'+week;
const patch={platformStatus:'ready',platformError:'',platCustomers:rows,platCommissions:monthRows,platGrowth:g,customers:desk,filteredCustomers:this.filterRows(desk),
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
patch.salesName=require('../../utils/account').load().profile.name||'';
patch.salesDate=dateText;patch.todayCount=due.length;patch.overdueCount=overdue.length;patch.appointmentCount=upcoming.length;patch.commission=commission;
}
this.setData(patch);
};
definition.filterRows=function(rows){const keyword=this.data.keyword||'';if(!keyword)return rows;return rows.filter(c=>((c.name||'')+(c.searchText||'')+(c.desc||'')).includes(keyword));};
definition.filterCustomers=function(){this.setData({filteredCustomers:this.filterRows(this.data.customers||[])});};
// AI design opens the 方案 tab; quick budget starts in the explore page. A customer is chosen when saving.
definition.openSalesShortcut=function(e){const url=e.currentTarget.dataset.url;if(url)return require('../../utils/page-navigation').open(url);this.openAction(e);};
definition.openSalesMetric=function(e){const focus=e.currentTarget.dataset.focus;if(focus==='commission')return require('../../utils/page-navigation').open('/pages/account/index?view=personal-performance');if(['all','due','appointments'].includes(focus))require('../../utils/page-navigation').open('/pages/customers/index'+(focus==='all'?'':'?focus='+focus));};
definition.continueSalesCustomer=function(){if(this.data.recentCustomer)require('../../utils/page-navigation').open('/pages/workflow/index?view=customer&id='+encodeURIComponent(this.data.recentCustomer.id));};
definition.continueSalesCreation=function(){require('../../utils/page-navigation').open('/pages/creation/index');};
definition.openBossLink=function(e){const url=e.currentTarget.dataset.url;if(url)require('../../utils/page-navigation').open(url);};
definition.retryPlatformTodos=function(){PTPage.retry(this);};
definition.openPlatformTodo=function(e){PTPage.open(this,e);};
Page(definition);
