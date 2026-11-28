const Job=require('./organization');
const PF=require('./platform-crm'),http=require('../api/http');
const SERVER_PAGE_SIZE=50,MAX_WINDOW_PAGES=4;
const TABS=[['due','今日待跟进'],['appointments','预约跟进'],['all','全部'],['visited','已到店']];
const EMPTY={due:'今天的跟进都办完了',appointments:'暂无预约跟进的客户',visited:'暂无已到店的客户',all:'当前没有可见客户，可先登记客户。'};
const EMPTY_API={due:'今天的跟进都办完了',appointments:'暂无预约跟进客户',visited:'暂无已到店客户',all:'当前没有可见客户，可先登记客户。'};
module.exports=()=>({
 data:{keyword:'',tab:'all',tabs:[],customers:[],filteredCustomers:[],visibleCustomers:[],pageSize:10,platformMode:false,platformStatus:'',platformError:'',platformRefreshing:false,authError:false},
 onLoad(o={}){this.applyEntryIntent(o);},
 applyEntryIntent(o={},refresh=false){this.cancelSearch();this.setData({intent:o.intent==='entrust'||o.intent==='commission'?'entrust':'',...(TABS.some(([key])=>key===o.focus)?{tab:o.focus,keyword:'',pageSize:10}:{})});if(refresh)return this.loadPlatformList();},
 onHide(){this.__loadGen=(this.__loadGen||0)+1;this.__listFlight=null;this.cancelSearch();this.setData({platformRefreshing:false});},
 onUnload(){this.__unloaded=true;this.cancelSearch();},
 cancelSearch(){if(this.__searchTimer){clearTimeout(this.__searchTimer);this.__searchTimer=null;}},
 onShow(){
  this.cancelSearch();
  const post=(()=>{try{const auth=require('../api/auth');return http.token()?auth.currentApiPost():null;}catch(e){return null;}})();
  if(PF.pageMode(http.token(),post)==='api'){
   this.__post=post;this.__pf=PF;this.__http=http;this.__crmApi=this.__crmApi||require('../api/franchise/crm');
   const postKey=String(post.postId),scope=JSON.stringify([http.sessionGeneration(),post.tenantId,post.orgId,postKey,post.roleCode,post.orgType]);
   if(this.__customerScope!==scope){this.__customerScope=scope;this.__postKey=postKey;this.__pageNo=0;this.__prefix=null;this.__serverHasMore=false;this.__total=0;this.__loadedQuery='';this.__listFlight=null;this.__listRetry=null;this.setData({keyword:'',tab:this.data.tab,pageSize:10,platformMode:true,platformRefreshing:false,authError:false,customers:[],filteredCustomers:[],visibleCustomers:[],tabs:[]});}
   else if(!this.data.platformMode)this.setData({platformMode:true});
   const isBossView=['boss','owner'].includes(post.roleCode),isLead=post.roleCode==='sales-lead',actor=Job.apiContext(post);
   const navigationIndex=Job.navigation(actor).tabs.find(tab=>tab.key===(isBossView?'owner-business':'customers'))?.index??7;
   this.setData({isLead,isBossView,canRegister:PF.can(post,'capture'),isDemo:false,navigationIndex});
   return this.loadPlatformList();
  }
  this.__post=null;this.__postKey='';this.__customerScope='';this.__loadedQuery='';this.__listFlight=null;this.__listRetry=null;
  this.setData({platformMode:true,platformStatus:'degraded',platformRefreshing:false,platformError:http.token()?'当前身份暂无客户查看权限':'请先登录后查看客户',authError:!http.token(),customers:[],filteredCustomers:[],visibleCustomers:[],tabs:[],hasMore:false,canRegister:false});
 },
 goLogin(){wx.redirectTo({url:'/pages/account/index?view=login'});},
 stale(gen,sessionGen,postKey){return this.__unloaded||gen!==this.__loadGen||sessionGen!==this.__http.sessionGeneration()||postKey!==this.__postKey;},
 handle401(err,sessionGenAtRequest){
  if(!(err&&err.code===401&&!err.silent))return false;
  // A late 401 must not clear the new session.
  if(this.__http.token()){
   if(sessionGenAtRequest!==this.__http.sessionGeneration())return true;
   this.__http.clearSession();
  }
  this.__loadedQuery='';this.__listRetry=null;
  this.setData({platformStatus:'degraded',platformRefreshing:false,platformError:'登录已过期，请重新登录',authError:true,customers:[],filteredCustomers:[],visibleCustomers:[],tabs:[],hasMore:false});
  return true;
 },
 loadPlatformList(append=false){
  const PF=this.__pf,post=this.__post;
  if(!PF||!post)return this.setData({platformStatus:'degraded',platformError:'请先登录后查看客户',authError:true});
  if(append&&(this.__searchTimer||this.data.platformStatus==='loading'||this.data.platformRefreshing))return;
  const keyword=(this.data.keyword||'').trim(),tab=this.data.tab;
  if(append&&(keyword!==this.__loadedKeyword||tab!==this.__loadedTab))append=false;
  const sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  const query=JSON.stringify([sessionGen,post.tenantId,post.orgId,postKey,post.roleCode,post.orgType,keyword,tab]);
  const retry=this.__listRetry?.query===query?this.__listRetry:null;
  if(append&&retry)append=false;
  if(this.__listFlight&&this.__listFlight.query===query&&this.__listFlight.append===append&&this.__listFlight.gen===this.__loadGen)return this.__listFlight.promise;
  const gen=this.__loadGen=(this.__loadGen||0)+1,retain=this.__loadedQuery===query;
  const lastPage=append?(this.__pageNo||0)+1:retain?Math.max(1,this.__pageNo||1,retry?.lastPage||1):1;
  const displaySize=append?this.data.pageSize+10:retry?.displaySize??this.data.pageSize;
  const windowed=lastPage<=MAX_WINDOW_PAGES,mergeAppend=append&&!windowed;
  const firstPage=mergeAppend?lastPage:1;
  const flight={query,gen,append,promise:null};this.__listFlight=flight;
  if(!append&&!retain)this.__loadedQuery='';
  if(!append||windowed)this.setData(retain?{platformStatus:'ready',platformRefreshing:true,platformError:'',authError:false}:{platformStatus:'loading',platformRefreshing:false,platformError:'',authError:false,customers:[],visibleCustomers:[],filteredCustomers:[],hasMore:false,emptyText:'正在加载客户…'});
  const drift={},prefixChanged={};
  flight.promise=(async()=>{
   const ids=new Set(mergeAppend?this.data.customers.map(row=>String(row.id)):[]);
   let list=[],total=0,loadedPage=firstPage,lastCount=0,expectedTotal=mergeAppend?this.__total:null,prefix=mergeAppend?this.__prefix:null;
   const batchSize=windowed?lastPage*SERVER_PAGE_SIZE:SERVER_PAGE_SIZE;
   for(let pageNo=firstPage;pageNo<=(windowed?1:lastPage);pageNo++){
    const res=await this.__crmApi.customers.page(pageNo,batchSize,keyword||undefined,tab==='all'?undefined:tab.toUpperCase(),true,prefix);
    if(this.stale(gen,sessionGen,postKey))return;
    if(res?.changed||(prefix&&!res?.prefix))throw prefixChanged;
    prefix=res?.prefix||null;
    const rows=res&&res.list||[];
    total=typeof(res&&res.total)==='number'?res.total:ids.size+rows.length;
    if(expectedTotal!==null&&expectedTotal!==total)throw drift;
    expectedTotal=total;
    for(const row of rows){
     const id=String(row.id);if(ids.has(id))throw drift;ids.add(id);
    }
    list=list.concat(rows);lastCount=rows.length;loadedPage=windowed?Math.max(1,Math.ceil(rows.length/SERVER_PAGE_SIZE)):pageNo;
    if(windowed&&typeof res?.total==='number'&&ids.size!==Math.min(total,batchSize))throw drift;
    if(!rows.length||pageNo*batchSize>=total){
     if(typeof res?.total==='number'&&ids.size!==total)throw drift;
     break;
    }
   }
   return {list,total,loadedPage,lastCount,prefix};
  })().then(res=>{
   if(this.stale(gen,sessionGen,postKey))return;
   const rows=res.list.map(c=>PF.customerRow(c,{today:PF.today(),canWrite:PF.can(post,'write'),visited:tab==='visited'}));
   const merged=mergeAppend?this.data.customers.concat(rows):rows;
   this.__prefix=res.prefix;this.__pageNo=res.loadedPage;this.__loadedKeyword=keyword;this.__loadedTab=tab;this.__loadedQuery=query;this.__total=res.total;this.__serverHasMore=merged.length<res.total&&res.lastCount>0;
   this.__listRetry=null;
   const pageSize=displaySize;
   this.setData({platformStatus:'ready',platformRefreshing:false,platformError:'',customers:merged,pageSize,tabs:this.platformTabs(merged),...this.customerListPatch(merged,pageSize)});
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;
   if(this.stale(gen,sessionGen,postKey))return;
   if(err===prefixChanged){
    this.__prefix=null;this.__pageNo=0;this.__loadedQuery='';this.__listRetry=null;this.__serverHasMore=false;
    this.setData({platformStatus:'degraded',platformRefreshing:false,platformError:'客户名单已变化，请重试',pageSize:10,customers:[],filteredCustomers:[],visibleCustomers:[],tabs:[],hasMore:false});
    return;
   }
   if(err===drift){
    this.__listRetry={query,lastPage,displaySize};
    this.setData({platformStatus:retain?'ready':'degraded',platformRefreshing:false,platformError:'客户名单正在变化，请重试'});
    return;
   }
   const denied=[403,1103000002].includes(Number(err&&err.code));
   if(denied){this.__loadedQuery='';this.__listRetry=null;}
   this.setData({platformStatus:retain&&!denied?'ready':'degraded',platformRefreshing:false,platformError:PF.errorMessage(err),...(denied?{customers:[],visibleCustomers:[],filteredCustomers:[],tabs:[],hasMore:false,canRegister:false}:{})});
  }).finally(()=>{if(this.__listFlight===flight)this.__listFlight=null;});
  return flight.promise;
 },
 retryPlatform(){this.cancelSearch();return this.loadPlatformList();},
 platformTabs(customers=this.data.customers){return TABS.map(([key,label])=>({key,label,count:key===this.data.tab?this.__total:'—',dot:key==='due'&&customers.some(r=>r.rank===0)}));},
 refreshPlatformTabs(){this.setData({tabs:this.platformTabs()});},
 customerListPatch(customers=this.data.customers,pageSize=this.data.pageSize){const keyword=this.data.keyword.trim(),rows=this.__pf.sortRows(customers);
  return {filteredCustomers:rows,visibleCustomers:rows.slice(0,pageSize),
   hasMore:rows.length>pageSize||!!this.__serverHasMore,
   emptyText:keyword?'暂无符合条件的客户':this.data.isBossView&&this.data.tab==='all'?'当前没有本店客户':(EMPTY_API[this.data.tab]||EMPTY[this.data.tab])};},
 filterCustomers(){if(this.__pf)this.setData(this.customerListPatch());},
 selectTab(e){const tab=e.currentTarget.dataset.tab;if(!TABS.some(([key])=>key===tab)||tab===this.data.tab)return;this.cancelSearch();this.setData({tab,keyword:'',pageSize:10});return this.loadPlatformList();},
 search(e){
  const keyword=String(e.detail.value||'');if(keyword===this.data.keyword)return;
  this.cancelSearch();this.setData({keyword,pageSize:10});
  if(!this.__post||!this.__http){this.filterCustomers();return;}
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({platformStatus:'loading',platformRefreshing:false,platformError:'',visibleCustomers:[],filteredCustomers:[],hasMore:false,emptyText:'正在加载客户…'});
  this.__searchTimer=setTimeout(()=>{this.__searchTimer=null;if(!this.stale(gen,sessionGen,postKey))this.loadPlatformList();},250);
 },
 more(){
  if(this.__searchTimer||this.data.platformStatus==='loading'||this.data.platformRefreshing)return;
  if(this.data.filteredCustomers.length>this.data.pageSize){
   this.setData({pageSize:this.data.pageSize+10});this.filterCustomers();return;
  }
  return this.loadPlatformList(true);
 },
 register(){if(this.data.canRegister)wx.navigateTo({url:'/pages/workflow/index?view=customers&modal=register'});},
 open(e){const id=e.currentTarget.dataset.id;
  if(this.data.customers.some(c=>String(c.id)===String(id)))wx.navigateTo({url:this.data.intent==='entrust'&&this.data.canRegister?'/pages/external-entrust/index?customerId='+encodeURIComponent(id):'/pages/workflow/index?view=customer&id='+encodeURIComponent(id)});},
 act(e){const row=this.data.customers.find(c=>String(c.id)===String(e.currentTarget.dataset.id));
  if(row){if(this.data.intent==='entrust'&&this.data.canRegister)return this.open(e);wx.navigateTo({url:row.lost?row.url:(row.flowUrl||row.url)});}},
 call(e){const dial=(this.data.customers.find(c=>String(c.id)===String(e.currentTarget.dataset.id))||{}).dial;
  if(!/^1\d{10}$/.test(dial||''))return wx.showToast({title:'该号码不可拨打',icon:'none'});wx.makePhoneCall({phoneNumber:dial,fail(){}});},
});
