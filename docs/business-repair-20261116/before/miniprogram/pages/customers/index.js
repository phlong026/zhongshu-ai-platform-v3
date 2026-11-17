const Job=require('../../utils/organization');
const PF=require('../../utils/platform-crm'),http=require('../../api/http');
const TABS=[['due','今日待跟进'],['appointments','预约到店'],['all','全部'],['visited','已到店']];
const EMPTY={due:'今天的跟进都办完了',appointments:'暂无预约到店的客户',visited:'暂无已到店的客户',all:'当前没有可见客户，可先登记客户。'};
const EMPTY_API={due:'今天的跟进都办完了',appointments:'暂无预约到店的客户（到店状态以客户「已到店」标记为准，需先在跟进中登记预约并确认到店）',visited:'暂无已到店的客户（到店状态由跟进记录确认到店后自动标记）',all:'当前没有可见客户，可先登记客户。'}; // M04-T05：API 态到店口径入口说明
Page({
 data:{keyword:'',tab:'all',tabs:[],customers:[],filteredCustomers:[],visibleCustomers:[],pageSize:10,platformMode:false,platformStatus:'',platformError:'',authError:false},
 onLoad(o){if(['due','appointments'].includes(o.focus))this.setData({tab:o.focus});},
 onUnload(){this.__unloaded=true;this.cancelSearch();},
 cancelSearch(){if(this.__searchTimer){clearTimeout(this.__searchTimer);this.__searchTimer=null;}},
 onShow(){
  this.cancelSearch();
  // 双体系分流（首个 demo 调用之前）：token+销售家族岗位 → API 态；否则 demo 原样零请求
  const post=(()=>{try{const auth=require('../../api/auth');return http.token()?auth.currentApiPost():null;}catch(e){return null;}})();
  if(PF.pageMode(http.token(),post)==='api'){
   this.__post=post;this.__pf=PF;this.__http=http;this.__crmApi=this.__crmApi||require('../../api/franchise/crm');
   const postKey=String(post.postId);
   if(this.__postKey!==postKey){this.__postKey=postKey;this.__pageNo=0;this.__serverHasMore=false;this.__total=0;this.setData({keyword:'',tab:this.data.tab,platformMode:true,customers:[],filteredCustomers:[],visibleCustomers:[],tabs:[]});}
   else if(!this.data.platformMode)this.setData({platformMode:true});
   const isBossView=['boss','owner'].includes(post.roleCode),isLead=post.roleCode==='sales-lead',actor=Job.apiContext(post);
   const navigationIndex=Job.navigation(actor).tabs.find(tab=>tab.key===(isBossView?'owner-business':'customers'))?.index??7;
   this.setData({isLead,isBossView,canRegister:PF.can(post,'capture'),isDemo:false,navigationIndex});
   this.loadPlatformList();
   return;
  }
  // 非 API 态（无 token / 无销售岗位）：不再回落本地演示数据，显示错误态 + 登录引导
  this.__post=null;this.__postKey='';
  this.setData({platformMode:true,platformStatus:'degraded',platformError:http.token()?'当前身份暂无客户查看权限':'请先登录后查看客户',authError:!http.token(),customers:[],filteredCustomers:[],visibleCustomers:[],tabs:[],canRegister:false});
 },
 goLogin(){wx.redirectTo({url:'/pages/account/index?view=login'});},
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
  this.setData({platformStatus:'degraded',platformError:'登录已过期，请重新登录',authError:true,customers:[],filteredCustomers:[],visibleCustomers:[]});
  return true;
 },
 loadPlatformList(append=false){
  const PF=this.__pf,post=this.__post;
  if(!PF||!post)return this.setData({platformStatus:'degraded',platformError:'请先登录后查看客户',authError:true}); // 未具备在线上下文（无 token/岗位）时不再回落本地数据
  if(append&&(this.__searchTimer||this.data.platformStatus==='loading'))return;
  const keyword=(this.data.keyword||'').trim();
  if(append&&keyword!==this.__loadedKeyword)append=false;
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  const pageNo=append?(this.__pageNo||0)+1:1;
  if(!append)this.setData({platformStatus:'loading',platformError:''});
  this.__crmApi.customers.page(pageNo,50,keyword||undefined).then(res=>{
   if(this.stale(gen,sessionGen,postKey))return;
   const rows=(res&&res.list||[]).map(c=>PF.customerRow(c,{today:PF.today(),canWrite:PF.can(post,'write')}));
   const merged=append?this.data.customers.concat(rows):rows;
   const total=typeof (res&&res.total)==='number'?res.total:merged.length;
   this.__pageNo=pageNo;this.__loadedKeyword=keyword;this.__total=total;this.__serverHasMore=merged.length<total&&rows.length>0;
   this.setData({platformStatus:'ready',customers:merged,hasMore:this.__serverHasMore||merged.length>this.data.pageSize});
   this.refreshPlatformTabs();this.filterCustomers();
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;// 401 四组合先判（内部自带迟到判别）
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({platformStatus:'degraded',platformError:PF.errorMessage(err)});
  });
 },
 retryPlatform(){this.cancelSearch();this.loadPlatformList();},
 refreshPlatformTabs(){const PF=this.__pf,ctx={today:PF.today()};const tabs=TABS.map(([key,label])=>{const rows=PF.tabFilter(this.data.customers,key,ctx);const count=key==='all'?this.__total:this.__serverHasMore?(rows.length?'≥'+rows.length:'—'):rows.length;return {key,label,count,dot:key==='due'&&rows.some(r=>r.rank===0)};});this.setData({tabs});},
 filterCustomers(){const keyword=this.data.keyword.trim();const PF=this.__pf;if(!PF)return; // 无在线上下文（未登录/无岗位）时列表已清空，跳过装配
  const rows=PF.sortRows(PF.tabFilter(this.data.customers,this.data.tab,{today:PF.today()}).filter(c=>c.searchText.includes(keyword)));
  this.setData({filteredCustomers:rows,visibleCustomers:rows.slice(0,this.data.pageSize),
   hasMore:rows.length>this.data.pageSize||!!this.__serverHasMore, // M04-T01：隐藏行或服务端更多都亮按钮
   emptyText:this.__serverHasMore?'已加载的客户中暂无匹配，请点「查看更多客户」继续查找。':keyword?'暂无符合条件的客户':this.data.isBossView&&this.data.tab==='all'?'当前没有本店客户':(EMPTY_API[this.data.tab]||EMPTY[this.data.tab])});},
 selectTab(e){const tab=e.currentTarget.dataset.tab;if(!TABS.some(([key])=>key===tab)||tab===this.data.tab)return;const hadSearch=!!(this.data.keyword||'').trim();this.cancelSearch();this.setData({tab,keyword:'',pageSize:10});if(hadSearch){this.loadPlatformList();return;}this.refreshPlatformTabs();this.filterCustomers();},
 search(e){
  const keyword=String(e.detail.value||'');if(keyword===this.data.keyword)return;
  this.cancelSearch();this.setData({keyword,pageSize:10});
  if(!this.__post||!this.__http){this.filterCustomers();return;}
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({platformStatus:'loading',platformError:''});this.filterCustomers();
  this.__searchTimer=setTimeout(()=>{this.__searchTimer=null;if(!this.stale(gen,sessionGen,postKey))this.loadPlatformList();},250);
 },
 more(){
  if(this.__searchTimer||this.data.platformStatus==='loading')return;
  if(this.data.filteredCustomers.length>this.data.pageSize){ // 已加载行未展示完：本地扩页（M04-T01 展示翻页与服务端取页一致）
   this.setData({pageSize:this.data.pageSize+10});this.filterCustomers();return;
  }
  return this.loadPlatformList(true);
 },
 register(){if(this.data.canRegister)wx.navigateTo({url:'/pages/workflow/index?view=customers&modal=register'});},
 open(e){const id=e.currentTarget.dataset.id;
  if(this.data.customers.some(c=>String(c.id)===String(id)))wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(id)});},
 act(e){const row=this.data.customers.find(c=>String(c.id)===String(e.currentTarget.dataset.id));
  if(row)wx.navigateTo({url:row.url});},
 call(e){const dial=(this.data.customers.find(c=>String(c.id)===String(e.currentTarget.dataset.id))||{}).dial;
  if(!/^1\d{10}$/.test(dial||''))return wx.showToast({title:'该号码不可拨打',icon:'none'});wx.makePhoneCall({phoneNumber:dial,fail(){}});},
});
