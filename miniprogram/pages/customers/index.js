const B=require('../../utils/business'),Access=require('../../utils/access'),Job=require('../../utils/organization'),Workspace=require('../../utils/sales-workspace');
const PF=require('../../utils/platform-crm'),http=require('../../api/http');
const TABS=[['due','今日待跟进'],['appointments','预约到店'],['all','全部'],['visited','已到店']];
const EMPTY={due:'今天的跟进都办完了',appointments:'暂无预约到店的客户',visited:'暂无已到店的客户',all:'当前没有可见客户，可先登记客户。'};
const EMPTY_API={due:'今天的跟进都办完了',appointments:'暂无预约到店的客户（到店状态以客户「已到店」标记为准，需先在跟进中登记预约并确认到店）',visited:'暂无已到店的客户（到店状态由跟进记录确认到店后自动标记）',all:'当前没有可见客户，可先登记客户。'}; // M04-T05：API 态到店口径入口说明
const tabRows=(rows,tab,readOnly=false)=>{if(tab==='visited')return rows.filter(r=>r.status==='已到店');if(tab==='due'&&readOnly){const ids=new Set(Workspace.focusRows(rows.map(row=>({...row,canFollow:true})),'due').map(row=>row.id));return rows.filter(row=>ids.has(row.id));}return Workspace.focusRows(rows,tab==='all'?'':tab);};
Page({
 data:{keyword:'',tab:'all',tabs:[],customers:[],filteredCustomers:[],visibleCustomers:[],pageSize:10,platformMode:false,platformStatus:'',platformError:''},
 onLoad(o){if(['due','appointments'].includes(o.focus))this.setData({tab:o.focus});},
 onUnload(){this.__unloaded=true;},
 onShow(){
  // 双体系分流（首个 demo 调用之前）：token+销售家族岗位 → API 态；否则 demo 原样零请求
  const post=(()=>{try{const auth=require('../../api/auth');return http.token()?auth.currentApiPost():null;}catch(e){return null;}})();
  if(PF.pageMode(http.token(),post)==='api'){
   this.__post=post;this.__pf=PF;this.__http=http;this.__crmApi=this.__crmApi||require('../../api/franchise/crm');
   const postKey=String(post.postId);
   if(this.__postKey!==postKey){this.__postKey=postKey;this.__pageNo=0;this.setData({keyword:'',tab:this.data.tab,platformMode:true});}
   else if(!this.data.platformMode)this.setData({platformMode:true});
   const isBossView=['boss','owner'].includes(post.roleCode),isLead=post.roleCode==='sales-lead',actor=Job.apiContext(post);
   const navigationIndex=Job.navigation(actor).tabs.find(tab=>tab.key===(isBossView?'owner-business':'customers'))?.index??7;
   this.setData({isLead,isBossView,canRegister:PF.can(post,'capture'),isDemo:false,navigationIndex});
   this.loadPlatformList();
   return;
  }
  this.__post=null;this.__postKey='';
  if(this.data.platformMode)this.setData({platformMode:false,platformStatus:'',platformError:''});
  const actor=Access.context(),isBossView=Access.isBoss(actor);if(!actor.loggedIn){this.setData({customers:[],filteredCustomers:[],visibleCustomers:[]});return wx.redirectTo({url:'/pages/account/index?view=login'});}if(!actor.active||!Job.has(actor.role,'sales')&&!isBossView){this.setData({customers:[],filteredCustomers:[],visibleCustomers:[]});return wx.switchTab({url:'/pages/'+Access.home()+'/index'});}const rows=Workspace.customerRows().map(row=>isBossView&&row.owner?{...row,detail:row.detail+' · 负责人 '+row.owner}:row),navigationIndex=Job.navigation(actor).tabs.find(tab=>tab.key===(isBossView?'owner-business':'customers'))?.index??7;this.setData({customers:rows,counts:Workspace.counts(rows),tabs:TABS.map(([key,label])=>({key,label,count:tabRows(rows,key,isBossView).length,dot:key==='due'&&tabRows(rows,key,isBossView).some(r=>r.rank===0)})),isLead:actor.role==='sales-lead',isBossView,navigationIndex,canRegister:Job.has(actor.role,'sales'),isDemo:!!wx.getStorageSync(require('../../utils/storage-keys').account)?.session?.demo});this.filterCustomers();},
 // ---- API 态：列表/详情加载与乱序隔离（三元组 + 卸载标记；401 按 09 文档 §六四组合表） ----
 stale(gen,sessionGen,postKey){return this.__unloaded||gen!==this.__loadGen||sessionGen!==this.__http.sessionGeneration()||postKey!==this.__postKey;},
 handle401(err,sessionGenAtRequest){
  if(!(err&&err.code===401&&!err.silent))return false;
  // 「迟到」判别依据=token 是否存活（http 层清会话同样推进代次，不能只看代次）：
  // token 存活+代次已变 → 新会话迟到 401，整体丢弃；token 存活+代次未变 → 当前会话业务 401，页面 clearSession；
  // token 已空 → http 层已清（当前会话 HTTP 401），页面只做数据清理。
  if(this.__http.token()){
   if(sessionGenAtRequest!==this.__http.sessionGeneration())return true;// 迟到业务 401：整体丢弃，保护新会话现场
   this.__http.clearSession();
  }
  this.setData({platformMode:false,platformStatus:'',platformError:'',customers:[],filteredCustomers:[],visibleCustomers:[]});
  this.onShow();// 回 guest（demo）渲染
  return true;
 },
 loadPlatformList(append=false){
  const PF=this.__pf,post=this.__post;
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  const pageNo=append?(this.__pageNo||0)+1:1;
  const keyword=(this.data.keyword||'').trim(); // M04-T05：未加载页可检索——服务端按关键词取页
  if(!append)this.setData({platformStatus:'loading',platformError:''});
  this.__crmApi.customers.page(pageNo,50,keyword||undefined).then(res=>{
   if(this.stale(gen,sessionGen,postKey))return;
   const rows=(res&&res.list||[]).map(c=>PF.customerRow(c,{today:PF.today(),canWrite:PF.can(post,'write')}));
   const merged=append?this.data.customers.concat(rows):rows;
   const total=typeof (res&&res.total)==='number'?res.total:merged.length;
   this.__pageNo=pageNo;this.__serverHasMore=merged.length<total&&rows.length>0;
   this.setData({platformStatus:'ready',customers:merged,hasMore:this.__serverHasMore||merged.length>this.data.pageSize});
   this.refreshPlatformTabs();this.filterCustomers();
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;// 401 四组合先判（内部自带迟到判别）
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({platformStatus:'degraded',platformError:PF.errorMessage(err)});
  });
 },
 retryPlatform(){this.loadPlatformList();},
 refreshPlatformTabs(){const PF=this.__pf,ctx={today:PF.today()};const tabs=TABS.map(([key,label])=>{const rows=PF.tabFilter(this.data.customers,key,ctx);return {key,label,count:rows.length,dot:key==='due'&&rows.some(r=>r.rank===0)};});this.setData({tabs});},
 filterCustomers(){const keyword=this.data.keyword.trim();
  if(this.data.platformMode){const PF=this.__pf,rows=PF.sortRows(PF.tabFilter(this.data.customers,this.data.tab,{today:PF.today()}).filter(c=>c.searchText.includes(keyword)));
   this.setData({filteredCustomers:rows,visibleCustomers:rows.slice(0,this.data.pageSize),
    hasMore:rows.length>this.data.pageSize||!!this.__serverHasMore, // M04-T01：隐藏行或服务端更多都亮按钮
    emptyText:keyword?(this.__serverHasMore?'本地已显示匹配客户，点「查看更多客户」取服务端结果':'暂无符合条件的客户'):this.data.isBossView&&this.data.tab==='all'?'当前没有本店客户':(EMPTY_API[this.data.tab]||EMPTY[this.data.tab])});return;} // M04-T05：API 态到店空态带口径说明
  const rows=Workspace.sortRows(tabRows(this.data.customers,this.data.tab,this.data.isBossView).filter(c=>c.searchText.includes(keyword)));this.setData({filteredCustomers:rows,visibleCustomers:rows.slice(0,this.data.pageSize),hasMore:rows.length>this.data.pageSize,emptyText:keyword?'暂无符合条件的客户':this.data.isBossView&&this.data.tab==='all'?'当前没有本店客户':EMPTY[this.data.tab]});},
 selectTab(e){const tab=e.currentTarget.dataset.tab;if(!TABS.some(([key])=>key===tab)||tab===this.data.tab)return;if(this.data.platformMode){this.setData({tab,keyword:'',pageSize:10});this.refreshPlatformTabs();this.filterCustomers();return;}this.setData({tab,keyword:'',pageSize:10});this.filterCustomers();},
 search(e){this.setData({keyword:e.detail.value});this.filterCustomers();},
 more(){if(this.data.platformMode){
   if(this.data.filteredCustomers.length>this.data.pageSize){ // 已加载行未展示完：本地扩页（M04-T01 展示翻页与服务端取页一致）
    this.setData({pageSize:this.data.pageSize+10});this.filterCustomers();return;
   }
   return this.loadPlatformList(true);
  }
  this.setData({pageSize:this.data.pageSize+10});this.filterCustomers();},
 register(){if(this.data.platformMode){if(this.data.canRegister)wx.navigateTo({url:'/pages/workflow/index?view=customers&modal=register'});return;}if(this.data.canRegister&&Access.context().active&&Job.has(Access.context().role,'sales'))wx.navigateTo({url:'/pages/workflow/index?view=customers&modal=register'});},
 open(e){const id=e.currentTarget.dataset.id;
  if(this.data.platformMode){if(this.data.customers.some(c=>String(c.id)===String(id)))wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(id)});return;}
  if(!this.data.customers.some(c=>c.id===id)||!Access.customer(B.customer(id)))return;wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(id)});},
 act(e){const row=this.data.customers.find(c=>String(c.id)===String(e.currentTarget.dataset.id));
  if(this.data.platformMode){if(row)wx.navigateTo({url:row.url});return;}
  if(row&&B.customer(row.id))wx.navigateTo({url:row.url});},
 call(e){const dial=this.data.platformMode?(this.data.customers.find(c=>String(c.id)===String(e.currentTarget.dataset.id))||{}).dial:B.customer(e.currentTarget.dataset.id)?.phone||'';
  if(!/^1\d{10}$/.test(dial||''))return wx.showToast({title:'该号码不可拨打',icon:'none'});wx.makePhoneCall({phoneNumber:dial,fail(){}});},
});
