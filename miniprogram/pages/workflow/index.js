const Job=require('../../utils/organization');
const {routeValue,fail}=require('../../utils/view-container');
const PF=require('../../utils/platform-crm'),http=require('../../api/http');
const V=require('./views');
Object.assign(V.titles,{'money-review':'老板金额审批','task-access':'任务动作授权'});
Page({
 data:{view:'customers',title:'我的客户',customer:null,form:{},sections:[],modal:'',keyword:'',filter:'全部',filters:['全部','待跟进','跟进中','已到店'],filteredCustomers:[],caseTab:0,selectedPlan:0,floor:0,activeCustomerTab:0,showAllProgressRecords:false,requirements:['三代同住','4间卧室','一层老人房'],foundationOptions:['独立基础','条形基础','桩基础'],structureOptions:['框架结构','砖混结构'],roofOptions:['坡屋顶','平屋顶'],styleOptions:['全部风格','新中式','现代简约'],orientationOptions:['南','东','西','北'],resolutionOptions:['2K','4K'],countOptions:['1','2','4'],aspectOptions:['横图','竖图'],methodOptions:['电话','微信','到店'],nextOptions:['电话联系','微信联系','到店沟通'],sourceOptions:['视频号咨询','抖音咨询','朋友介绍','到店咨询','其他'],provinceOptions:['湖北省','广东省','浙江省','其他'],cityOptions:['武汉市','宜昌市','襄阳市','黄冈市','麻城市','红安县','武穴市','蕲春县','罗田县','英山县','黄梅县','团风县','浠水县','其他'],managerOptions:['李经理','王经理'],platformMode:false,platformStatus:'',platformError:'',requirementsStatus:'',requirementsError:'',customerTabs:['概况','跟进','项目进度'],showSizeBlock:false,todayStr:'',requirementStyles:['现代简约','新中式'],error:'',busy:false,preview:null,designFlow:false,selectingCustomer:false,facadeStyleOptions:['新中式','现代简约']},
 onLoad(options={}){this.options=options;this.__post=null;this.__postKey='';this.__cmdKeys={};
  const post=(()=>{try{const auth=require('../../api/auth');return http.token()?auth.currentApiPost():null;}catch(e){return null;}})();
  if(PF.pageMode(http.token(),post)!=='api'){// 纯 API 态门禁：无平台会话不进本地演示，回登录页
   const q=Object.keys(options).filter(k=>options[k]).map(k=>k+'='+encodeURIComponent(options[k])).join('&');
   return wx.redirectTo({url:'/pages/account/index?view=login&return='+encodeURIComponent('/pages/workflow/index'+(q?'?'+q:''))});}
  return this.platformLoad(options,post);
 },
 onPageScroll(e){this.__navigationScrollTop=e.scrollTop;},
 onShow(){if(this.id||this.data.platformMode)this.refresh(false);},
 // ---- 平台（API）态：分流、加载与乱序隔离（契约=docs/backend-parity/09 阶段1 P1） ----
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
  this.setData({platformStatus:'degraded',platformError:'登录已过期，请重新登录',customer:null,sections:[],form:{},filteredCustomers:[],modal:'',error:'',busy:false});// 纯 API 态：会话过期落错误态，不回本地渲染
  return true;
 },
 retryPlatform(){this.refresh();},
 onUnload(){this.__unloaded=true;},
 platformSession(){try{const auth=require('../../api/auth');return http.token()?auth.currentApiPost():null;}catch(e){return null;}},
 platformLoad(options,post){
  this.__post=post;this.__pf=PF;this.__http=http;this.__crmApi=this.__crmApi||require('../../api/franchise/crm');
  this.__postKey=String(post.postId);this.id=routeValue(options.id)||'';
  const requested=(options.view==='ai-facade'?'facade-config':options.view)||'';
  const allowed=['customers','customer','arrival','transfer','requirement','budget','budget-result','contract','delivery','progress'];
  if(requested&&!allowed.includes(requested))return this.platformBlocked(requested);
  let view=requested?(requested==='arrival'?'customer':requested):'customer';
  if(view==='budget-result')view='budget';// 结果为页面内态；深链直达回参数页
  if(view!=='customers'&&!this.id)view='customers';
  this.setData({platformMode:true,view,title:V.titles[view]||'客户工作区',transferMode:0,customer:null,sections:[],form:{},modal:'',error:'',busy:false,blocked:null,customerTabs:['概况','跟进'],activeCustomerTab:0,todayStr:PF.today(),role:Job.roles[post.roleCode]||post.roleCode,isBossView:['boss','owner'].includes(post.roleCode),saleWrite:PF.can(post,'saleWrite')});
  // 加载统一由 onShow→refresh 触发（onLoad→onShow 连续生命周期，避免重复请求）
  if(options.modal&&view==='customers')this.openModal(options.modal);
 },
 platformBlocked(v){this.setData({platformMode:true,view:'customers',title:'我的客户',blocked:{message:'「'+(V.titles[v]||v)+'」功能随阶段 2/3 接入后开放',target:'customers',label:'返回客户列表'},customer:null,sections:[],form:{},modal:'',error:''});},
 platformCommand(action){this.__cmdKeys=this.__cmdKeys||{};if(!this.__cmdKeys[action])this.__cmdKeys[action]=PF.commandKey(action);return this.__cmdKeys[action];},
 platformDropCommand(action){if(this.__cmdKeys)delete this.__cmdKeys[action];},
 loadPlatformList(){
  const PFc=this.__pf,post=this.__post;
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({platformStatus:'loading',platformError:'',filteredCustomers:[],canRegister:PFc.can(post,'capture'),role:Job.roles[post.roleCode]||''});
  this.__crmApi.customers.page(1,50).then(res=>{
   if(this.stale(gen,sessionGen,postKey))return;
   const rows=((res&&res.list)||[]).map(c=>PFc.customerRow(c,{today:PFc.today(),canWrite:PFc.can(post,'write')}));
   this.setData({platformStatus:'ready',customers:rows,filteredCustomers:rows});
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;// 401 四组合先判（内部自带迟到判别）
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({platformStatus:'degraded',platformError:PFc.errorMessage(err),customers:[],filteredCustomers:[]});
  });
 },
 loadPlatformDetail(view){
  const PFc=this.__pf,post=this.__post;
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  const canW=PFc.can(post,'write');
  this.setData({platformStatus:'loading',error:this.data.error==='正在加载…'?'':this.data.error,customer:null,canEditCustomer:canW,canFollow:canW,saleWrite:PFc.can(post,'saleWrite')});// 加载态由 platformStatus 表达，error 只保留实际业务提示
  if(view==='transfer')this.loadTransferTargets(sessionGen);
  this.__crmApi.customers.get(this.id).then(detail=>{
   if(this.stale(gen,sessionGen,postKey))return;
   const model=PFc.detailView(detail,{today:PFc.today(),canWrite:canW});
   this.__promoted=this.__promoted||{};
   this.setData({platformStatus:'ready',customer:model.customer,schemesList:(model.schemesList||[]).map(s2=>({...s2,formal:this.__promoted[s2.id]||false})),showSizeBlock:model.showSizeBlock,blocked:null,
    customerInitial:(model.customer.name||'').slice(0,1),phone:PFc.maskPhone(model.customer.phone)||model.customer.wechat||'',
    projectSummary:model.customer.summary||'',city:(model.customer.city||'').replace(/市$/,''),
    registered:model.customer.status==='已登记',hasDesign:false,simpleBusiness:false});// 成功不清 error：版本冲突等提示保留至用户下次操作
   if(view==='transfer')this.reconcileTransferOwner();
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({platformStatus:'degraded',error:PFc.errorMessage(err)});
  });
 },
 loadTransferTargets(sessionGenAtRequest){
  const PFc=this.__pf,post=this.__post;
  const gen=this.__candGen=(this.__candGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  require('../../api/org').orgStaff(post.orgId).then(rows=>{
   if(this.__unloaded||gen!==this.__candGen||sessionGen!==this.__http.sessionGeneration()||postKey!==this.__postKey)return;
   this.__transferStaff={postKey,rows:rows||[]};
   const targets=(rows||[]).filter(r=>['sales','sales-lead'].includes(r.roleCode)&&r.active===true);
   // M04-I04：同名员工都保留（按 userId 区分），展示名加部门后缀去歧义；映射按 picker 序→userId
   const candidates=targets.map(r=>({userId:r.userId,label:r.name+' · '+(r.teamName||r.roleCode||'')})); // M04-I04：label 恒含 team 后缀——同名/同部门/缺 teamName 均不产生相同 label（Codex R1 P1-1）
   this.__transferCandidates=candidates;
   this.__transferTargets=candidates.reduce((m,c2)=>{m[c2.label]=c2.userId;return m;},{});
   this.setData({ownerOptions:candidates.map(c=>c.label)});
   this.reconcileTransferOwner();
  }).catch(err=>{
   if(this.handle401(err,sessionGenAtRequest))return;
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({platformError:'接手人列表加载失败：'+PFc.errorMessage(err)});
  });
 },
 reconcileTransferOwner(){const customer=this.data.customer,staff=this.__transferStaff;if(!customer||!staff||staff.postKey!==this.__postKey)return;const ownerId=String(customer.ownerUserId||''),rows=(staff.rows||[]).filter(r=>r.active===true&&String(r.userId||'')===ownerId),canonical=['sales','sales-lead'].map(role=>rows.find(r=>r.roleCode===role)).find(Boolean),named=rows.find(r=>String(r.name||'').trim()),name=canonical&&String(canonical.name||named&&named.name||'').trim();this.setData({customer:{...customer,ownerDisplay:name||'负责人待核',ownerRoleName:name?(Job.roles[canonical.roleCode]||canonical.roleCode):''}});},
 applyDetail(detail){
  const model=PF.detailView(detail,{today:PF.today(),canWrite:this.data.canFollow});
  this.setData({customer:model.customer,showSizeBlock:model.showSizeBlock,blocked:null,
   customerInitial:(model.customer.name||'').slice(0,1),phone:PF.maskPhone(model.customer.phone)||model.customer.wechat||'',
   projectSummary:model.customer.summary||'',error:'',platformStatus:'ready'});
 },
 platformSubmitFollow(){
  const f=this.data.form,fail=msg=>this.setData({error:msg,busy:false});
  const note=String(f.followContent||'').trim();
  if(!note||note.length>500)return fail('请填写沟通内容（500字以内）');
  const appt=PF.toDate(PF.dateToArray(f.appointment));
  if(!appt)return fail('请选择下次回访日期');
  if(appt<PF.today())return fail(PF.errorMessage({code:1110000009}));
  const key=this.platformCommand('follow');
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({busy:true,error:''});
  this.__crmApi.customers.follow(this.id,PF.followPayload(f),key).then(detail=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommand('follow');this.setData({busy:false,modal:'',error:''});
   this.applyDetail(detail);wx.showToast({title:'已记录跟进',icon:'none'});
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;// 401 四组合先判（内部自带迟到判别）
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({busy:false});
   if((err&&err.code)!==-1)this.platformDropCommand('follow');// 网络失败保留快照重试
   fail(PF.errorMessage(err,'follow'));
  });
 },
 platformSubmitRegister(){
  const f=this.data.form,fail=msg=>this.setData({error:msg,busy:false});
  const name=String(f.name||'').trim();
  if(!name||name.length>64)return fail('请填写客户称呼（64字以内）');
  if(!PF.validPhone(f.phone))return fail('请输入11位有效手机号');
  const summary=String(f.summary||'').trim();
  if(!summary||summary.length>500)return fail('请填写需求摘要（500字以内）');
  const source=String(f.source||'').trim();
  if(!source||source.length>64)return fail('请选择获客来源');
  const key=this.platformCommand('capture');
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({busy:true,error:''});
  this.__crmApi.leads.capture(PF.capturePayload(f),key).then(res=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommand('capture');this.setData({busy:false});
   const result=res&&res.result,cid=res&&res.customerId!=null?String(res.customerId):'';
   if(result==='CREATED'&&cid)return this.openView('customer',true,cid);
   if(result==='LINKED'&&cid){// 先探测可读性（同团队自动关联但 sales 仅读本人客户，S1-13）
    wx.showToast({title:'已关联同手机号原客户',icon:'none'});
    this.setData({busy:false});
    return this.__crmApi.customers.get(cid).then(()=>{if(!this.stale(gen,sessionGen,postKey))this.openView('customer',true,cid);}).catch(err=>{
     if(this.handle401(err,sessionGen))return;
     if(this.stale(gen,sessionGen,postKey))return;
     if(err&&(err.code===1110000000||err.code===403))return this.setData({error:'该客户由同事负责，已关联到其名下，请联系负责人查看'});
     this.setData({error:PF.errorMessage(err)});
    });
   }
   if(result==='LINKED'&&!cid)return this.setData({error:'客资状态已变化，请刷新'});
   if(result==='LINK_REQUIRED')return this.setData({error:'该手机号已有原客户，请由负责人在客资中关联处理'});
   this.setData({error:'登记结果异常，请刷新重试'});
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;// 401 四组合先判（内部自带迟到判别）
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({busy:false});
   if((err&&err.code)!==-1)this.platformDropCommand('capture');
   fail(PF.errorMessage(err));
  });
 },
 platformSubmitEdit(){
  const f=this.data.form,c=this.data.customer,fail=msg=>this.setData({error:msg,busy:false});
  const name=String(f.name||'').trim();
  if(!name||name.length>64)return fail('请填写客户称呼（64字以内）');
  if(!PF.within(f.wechat,64)||!PF.within(f.province,32)||!PF.within(f.city,32)||!PF.within(f.summary,500))return fail('填写内容超过长度限制');
  const key=this.platformCommand('update');
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({busy:true,error:''});
  this.__crmApi.customers.update(this.id,PF.updatePayload(f,c.revision),key).then(detail=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommand('update');this.setData({busy:false,modal:'',error:''});
   this.applyDetail(detail);wx.showToast({title:'已保存',icon:'none'});
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;// 401 四组合先判（内部自带迟到判别）
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({busy:false});
   if((err&&err.code)!==-1)this.platformDropCommand('update');
   if(err&&err.code===1099000001){// 版本冲突：丢弃快照、重拉详情，不自动重提
    this.loadPlatformDetail(this.data.view);
   }
   fail(PF.errorMessage(err));
  });
 },
 platformSubmitTransfer(){
  const f=this.data.form,c=this.data.customer,fail=msg=>this.setData({error:msg,busy:false});
  // M04-I04：picker 存的是候选 label（同名已加部门后缀去歧义）——按 label 反查唯一 userId
  const cands=this.__transferCandidates||[];
  const chosen=cands.find(c => c.label === f.newOwner);
  const targetUserId=chosen?chosen.userId:(this.__transferTargets?this.__transferTargets[f.newOwner]:null);
  if(!targetUserId)return fail('请选择接手人');
  const reason=String(f.transferReason||'').trim();
  if(!reason||reason.length>200)return fail('请填写转交原因（200字以内）');
  if(!c||(c.revision===undefined||c.revision===null))return fail('数据已变化，请刷新后重试');
  const key=this.platformCommand('transfer');
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({busy:true,error:''});
  this.__crmApi.customers.transfer(this.id,PF.transferPayload(targetUserId,f,c.revision),key).then(()=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommand('transfer');this.setData({busy:false});
   wx.showToast({title:'已转交',icon:'none'});
   this.openView('customer',true);
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;// 401 四组合先判（内部自带迟到判别）
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({busy:false});
   if((err&&err.code)!==-1)this.platformDropCommand('transfer');
   if(err&&err.code===1099000001)this.loadPlatformDetail('transfer');
   fail(PF.errorMessage(err,'transfer'));
  });
 },
 // ---- P2 交易流（契约=docs/backend-parity/12）----
 platformSalesApi(){return this.__crmSales=this.__crmSales||require('../../api/franchise/sales');},
 exportPlatformQuote(e){const quoteId=e&&e.currentTarget&&e.currentTarget.dataset.quoteId;if(!quoteId)return this.fail('请先完成报价测算');require('../../utils/page-navigation').open('/pages/workflow/quote-export/index?quote-id='+encodeURIComponent(quoteId));},
 customerNameFallback(){const q=(this.data.salesQuotes||[])[0],c=(this.data.salesContracts||[])[0];return (q&&q.customerName)||(c&&c.customerName)||('客户'+String(this.id||'').slice(-4));},
 platformTradeFrame(){const saleWrite=PF.can(this.__post,'saleWrite');this.setData({platformMode:true,customerNameText:this.data.customerNameText||this.customerNameFallback(),saleWrite,todayStr:PF.today()});return saleWrite;},
 blockTradeLoad(){const message='当前岗位无交易业务办理权限';this.setData({requirementsStatus:'blocked',requirementsError:message,error:message});},
 guardTradeSubmit(errorKey='error'){
  const fail=message=>{this.setData({[errorKey]:message,busy:false});return false;};
  if(!PF.can(this.__post,'saleWrite'))return fail('当前岗位无交易业务办理权限');
  if(this.data.requirementsStatus==='loading')return fail('历史需求正在加载，请稍候');
  if(this.data.requirementsStatus!=='ready')return fail('历史需求加载失败，请先重试');
  return true;
 },
 retryRequirements(){const view=this.data.view;if(view==='requirement')return this.prepareRequirement();if(view==='budget')return this.loadPlatformBudget();if(view==='contract')return this.loadPlatformContract();},
 loadTradeCustomerContext(gen,sessionGen,postKey){
  const PFc=this.__pf,canW=PFc.can(this.__post,'write');
  this.setData({platformStatus:'loading',platformError:'',customer:null,error:''});
  return this.__crmApi.customers.get(this.id).then(detail=>{
   if(this.stale(gen,sessionGen,postKey))return null;
   const model=PFc.detailView(detail,{today:PFc.today(),canWrite:canW}),customer=model.customer;
   this.setData({platformStatus:'ready',platformError:'',customer,showSizeBlock:model.showSizeBlock,blocked:null,
    customerInitial:(customer.name||'').slice(0,1),phone:PFc.maskPhone(customer.phone)||customer.wechat||'',
    projectSummary:customer.summary||'',city:(customer.city||'').replace(/市$/,''),customerNameText:customer.name||this.customerNameFallback()});
   return customer;
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return null;
   if(this.stale(gen,sessionGen,postKey))return null;
   const message=PFc.errorMessage(err);
   this.setData({platformStatus:'degraded',platformError:message,error:message,customer:null});
   return null;
  });
 },
 fetchLatestRequirement(gen,sessionGen,postKey){
  this.setData({requirementsStatus:'loading',requirementsError:''});
  return this.platformSalesApi().requirements.list(this.id).then(rows=>{
   if(this.stale(gen,sessionGen,postKey))return null;
   const latest=(rows||[])[0]||null;
   this.setData({requirementsStatus:'ready',requirementsError:'',salesRequirements:(rows||[]).map(r=>PF.requirementRow(r))});
   return latest;
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return null;
   if(this.stale(gen,sessionGen,postKey))return null;
   const message='需求加载失败：'+PF.errorMessageSales(err);
   this.setData({requirementsStatus:'error',requirementsError:message,error:message});
   return null;
  });
 },
 prepareRequirement(){
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  const saleWrite=this.platformTradeFrame(),editGen=this.__formEditGen||0;
  this.setData({view:'requirement',title:'登记需求',form:{width:'',depth:'',floors:'',style:'现代简约',prompt:'',note:''},error:'',busy:false});
  this.loadTradeCustomerContext(gen,sessionGen,postKey);
  if(!saleWrite)return this.blockTradeLoad();
  this.fetchLatestRequirement(gen,sessionGen,postKey).then(latest=>{
   if(!latest||this.stale(gen,sessionGen,postKey)||editGen!==(this.__formEditGen||0))return;
   this.setData({form:{width:latest.width!=null?String(latest.width):'',depth:latest.depth!=null?String(latest.depth):'',floors:latest.floors!=null?String(latest.floors):'',style:latest.style||'现代简约',prompt:latest.prompt||'',note:latest.note||''}});
  });
 },
 loadPlatformBudget(){
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  const saleWrite=this.platformTradeFrame(),editGen=this.__formEditGen||0;
  this.setData({view:'budget',title:'报价测算',error:'',busy:false});
  const fill=f=>{if(this.stale(gen,sessionGen,postKey)||editGen!==(this.__formEditGen||0))return;this.setData({form:Object.assign({budgetChecked:false,roofArea:'',exterior:{window:true,paint:true,stone:true,light:true,waterproof:true,lightning:true,insurance:false}},this.data.form,f)});};
  const customerRequest=this.loadTradeCustomerContext(gen,sessionGen,postKey);
  if(!saleWrite)return this.blockTradeLoad();
  Promise.all([this.fetchLatestRequirement(gen,sessionGen,postKey),customerRequest]).then(([latest,customer])=>{
   if(this.stale(gen,sessionGen,postKey))return;
   if(latest&&latest.width!=null){fill({width:String(latest.width),depth:String(latest.depth),floors:String(latest.floors||'')});return;}
   const c=customer||{};
   fill({width:c.width!=null?String(c.width):'',depth:c.depth!=null?String(c.depth):'',floors:c.floors!=null?String(c.floors):''});
  });
 },
 budgetShowResult(){if(this.data.budgetResult)return this.setData({view:'budget-result'});wx.showToast({title:'请先完成报价测算',icon:'none'});},
 budgetShowForm(){return this.setData({view:'budget'});},
 platformSubmitQuote(){
  const f=this.data.form,fail=msg=>this.setData({error:msg,busy:false});
  if(!this.guardTradeSubmit())return;
  const dims={width:Number(f.width),depth:Number(f.depth),floors:Number(f.floors)};
  if(!dims.width||dims.width<3||dims.width>40||!/^\d{1,2}(\.\d)?$/.test(String(f.width||'')))return fail('面宽须为3–40米，最多一位小数');
  if(!dims.depth||dims.depth<3||dims.depth>40||!/^\d{1,2}(\.\d)?$/.test(String(f.depth||'')))return fail('进深须为3–40米，最多一位小数');
  if(!Number.isInteger(dims.floors)||dims.floors<1||dims.floors>6)return fail('建筑层数须为1–6层整数');
  if(!String(f.footprint==null?'':f.footprint).trim())return fail('请填写占地面积');
  if(!String(f.area==null?'':f.area).trim())return fail('请填写建筑面积');
  for(const k of ['footprint','area','roofArea'])if(f[k]!==''&&f[k]!=null&&(!/^\d+(\.\d{1,2})?$/.test(String(f[k]))||Number(f[k])<=0))return fail('请填写有效的面积数值');
  if(!f.budgetChecked)return fail('请先人工核定屋顶面积');
  const compiled=PF.compileQuoteItems(f);
  if(compiled.error)return fail(compiled.error);
  const key=this.platformCommand('quote');
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({busy:true,error:''});
  this.platformSalesApi().quotes.create({customerId:this.id,items:compiled.items,priceVersion:'sales-v1-表单口径',width:f.width,depth:f.depth,floors:dims.floors,area:f.area?Number(f.area):undefined},key).then(res=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommand('quote');
   const it=res.items||{},yuan=c=>(c/100).toFixed(2);
   const mainRows=(it.main||[]).map(r=>({label:r.label,money:yuan(r.valueCents)})),decorRows=(it.decor||[]).map(r=>({label:r.label,money:yuan(r.valueCents)}));
   const mainTotal=(it.main||[]).reduce((n,r)=>n+(r.valueCents||0),0)+(it.insuranceCents||0);
   const decorTotal=(it.decor||[]).reduce((n,r)=>n+(r.valueCents||0),0)+(it.managementCents||0);
   this.setData({busy:false,budgetResult:{quoteId:String(res.quoteId),totalText:yuan(res.totalCents),mainTotalText:yuan(mainTotal),decorTotalText:yuan(decorTotal),mainRows,decorRows,priceVersion:res.priceVersion||'sales-v1-表单口径',stale:false},error:''});
   this.setData({view:'budget-result'});
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({busy:false});
   if((err&&err.code)!==-1)this.platformDropCommand('quote');
   fail(PF.errorMessageSales(err));
  });
 },
 loadPlatformContract(){
  const PFc=this.__pf;
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  const saleWrite=this.platformTradeFrame();
  this.setData({view:'contract',title:'签订合同',salesContracts:null,contractsError:'',salesQuotes:null,quotesError:'',canRegister:false,canFollow:false,canCollect:PF.can(this.__post,'collect')});// contractForm 保留（S1-21 表单保留协议）
  this.loadTradeCustomerContext(gen,sessionGen,postKey);
  if(!saleWrite)return this.blockTradeLoad();
  const api=this.platformSalesApi();
  api.contracts.list(this.id).then(rows=>{
   if(this.stale(gen,sessionGen,postKey))return;
   const cards=(rows||[]).map(r=>PF.contractRow(r));
   this.setData({salesContracts:cards,contractsHasActive:cards.some(c=>c.status!=='VOIDED')});// WXML 不支持方法调用，标志位在 JS 算
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({contractsError:PFc.errorMessageSales(err)});
  });
  api.quotes.list(this.id).then(rows=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({salesQuotes:(rows||[]).map(q=>PF.quoteRow(q)),customerNameText:this.data.customerNameText||((rows||[])[0]&&rows[0].customerName)||this.data.customerNameText});
  }).catch(err=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({quotesError:PFc.errorMessage(err)});
  });
  this.fetchLatestRequirement(gen,sessionGen,postKey).then(latest=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({latestRequirementId:latest?latest.id:''});
  });
 },
 openContractForm(){const q=(this.data.salesQuotes||[])[0];this.setData({contractForm:{total:(q&&q.totalText)||'',signAfter:(q&&q.totalText)||'',baseDone:'0',capDone:'0',signedDate:''},contractError:''});},
 contractInput(e){this.setData({['contractForm.'+e.currentTarget.dataset.key]:e.detail.value,contractError:''});this.saveDraft();},
 contractDate(e){this.setData({'contractForm.signedDate':e.detail.value,contractError:''});this.saveDraft();},
 platformSubmitContract(){
  const fail=msg=>this.setData({contractError:msg,busy:false});
  if(!this.guardTradeSubmit('contractError'))return;
  const fm=this.data.contractForm;
  // M06-T02 三期：Σ三期=总额；签约后>0；基础完成后/封顶后≥0（0=未约定）
  const total=PF.toCents(fm.total),signAfter=PF.toCents(fm.signAfter),baseDone=PF.toCents(fm.baseDone||'0'),capDone=PF.toCents(fm.capDone||'0');
  if(!total)return fail('请填写合同总额（最多两位小数）');
  if(!signAfter)return fail('签约后期金额必填且大于 0');
  if(baseDone==null||capDone==null)return fail('基础完成后/封顶后金额须为不小于 0 的数字（未约定填 0）');
  if(signAfter+baseDone+capDone!==total)return fail('三期金额之和必须等于合同总额');
  const body={customerId:this.id,totalCents:total,signAfterCents:signAfter,baseDoneCents:baseDone,capDoneCents:capDone};
  const sd=PF.dateToArray(fm.signedDate);if(sd)body.signedDate=sd;
  if(this.data.latestRequirementId)body.requirementId=this.data.latestRequirementId;
  const key=this.platformCommand('contract');
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({busy:true,contractError:''});
  this.platformSalesApi().contracts.create(body,key).then(()=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommand('contract');this.setData({busy:false,contractForm:null});
   this.loadPlatformContract();
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({busy:false});// S1-21：网络失败保留快照原键；重拉 R8 供核对（006 同样进入核对）
   this.loadPlatformContract();
   fail(PF.errorMessageSales(err));
  });
 },
 platformCommandFor(action,targetId){this.__cmdKeys=this.__cmdKeys||{};const k=action+':'+targetId;if(!this.__cmdKeys[k])this.__cmdKeys[k]=PF.commandKey(action);return this.__cmdKeys[k];},
 platformDropCommandFor(action,targetId){if(this.__cmdKeys)delete this.__cmdKeys[action+':'+targetId];},
 platformContractSign(e){
  const id=e.currentTarget.dataset.id,card=(this.data.salesContracts||[]).find(c=>c.id===String(id));
  const fail=msg=>this.setData({contractError:msg,busy:false});
  if(!card||card.revision==null)return fail('数据已变化，请刷新后重试');
  const key=this.platformCommandFor('sign',id);
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({busy:true,contractError:''});
  this.platformSalesApi().contracts.sign(id,{revision:card.revision},key).then(res=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommandFor('sign',id);this.setData({busy:false});
   if(res&&res.status==='SIGNED')wx.showToast({title:'已签订',icon:'none'});else wx.showToast({title:'合同状态已变化，已刷新',icon:'none'});
   this.loadPlatformContract();
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({busy:false});
   if((err&&err.code)!==-1)this.platformDropCommandFor('sign',id);
   fail(PF.errorMessageSales(err));
  });
 },
 platformContractVoid(e){
  const id=e.currentTarget.dataset.id,card=(this.data.salesContracts||[]).find(c=>c.id===String(id));
  if(this.data.busy)return;
  if(!card||card.revision==null)return this.setData({contractError:'数据已变化，请刷新后重试'});
  wx.showModal({title:'作废合同',editable:true,placeholderText:'请填写作废原因',success:r=>{
   if(!r.confirm)return;
   const reason=(r.content||'').trim();
   if(!reason)return this.setData({contractError:'请填写作废原因'});
   const key=this.platformCommandFor('void',id);
   const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
   this.setData({busy:true,contractError:''});
   this.platformSalesApi().contracts.void(id,{reason,revision:card.revision},key).then(()=>{
    if(this.stale(gen,sessionGen,postKey))return;
    this.platformDropCommandFor('void',id);this.setData({busy:false});
    wx.showToast({title:'已作废',icon:'none'});
    this.loadPlatformContract();
   }).catch(err=>{
    if(this.handle401(err,sessionGen))return;
    if(this.stale(gen,sessionGen,postKey))return;
    this.setData({busy:false});
    if((err&&err.code)!==-1)this.platformDropCommandFor('void',id);
    this.setData({contractError:PF.errorMessageSales(err)});
   });
  }});
 },
 // S1-14 回款办理（R11 collect）：PENDING 期款弹层表单，业务岗（collect={sales,sales-lead,boss}）办理，写通 B1 账簿
 platCollect(e){
  if(!this.data.canCollect)return this.fail('当前岗位不能办理回款');
  const stageId=e.currentTarget.dataset.stageId;
  if(!stageId)return this.fail('期款标识缺失，请刷新后重试');
  // M06-T02：带期上下文（期名+剩余可收额），分笔金额默认预填剩余额。
  // R1：合同页渲染源=salesContracts（progressContracts 是另一视图）——两处都查，取首个命中
  const ctx=(this.data.salesContracts||[]).concat(this.data.progressContracts||[])
   .flatMap(c=>c.stages||[]).find(st=>String(st.id)===String(stageId))||{};
  return this.setData({modal:'collect',error:'',collectStageId:stageId,
   collectStageText:(ctx.stageText||'期款')+' · 剩余可收 ¥'+(ctx.balanceText||ctx.amountText||'0'),
   collectForm:{method:'bank',transactionNo:'',receivedDate:PF.today(),amount:(ctx.balanceText||ctx.amountText||'')}});
 },
 platCollectInput(e){const key=e.currentTarget.dataset.key;this.setData({['collectForm.'+key]:e.detail.value,error:''});},
 platCollectPick(e){const values=['bank','cash'];this.setData({['collectForm.method']:values[Number(e.detail.value)]||'bank',error:''});},
 platCollectSubmit(){
  if(this.data.busy)return;
  const form=this.data.collectForm||{},stageId=this.data.collectStageId;
  const fail=msg=>this.setData({error:msg});
  const err=PF.collectError(form);
  if(err)return fail(err);
  const key=this.platformCommandFor('collect',stageId);
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({busy:true,error:''});
  // M06-T02 分笔：amountCents=本次回款金额（≤期剩余可收额，后端限额裁决）
  this.platformSalesApi().stages.collect(stageId,{amountCents:PF.toCents(form.amount),method:form.method,transactionNo:(form.transactionNo||'').trim()||undefined,receivedDate:form.receivedDate||undefined},key).then(()=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommandFor('collect',stageId);this.setData({busy:false,modal:'',error:''});
   wx.showToast({title:'已办理回款',icon:'none'});
   this.loadPlatformContract();
  }).catch(err2=>{
   if(this.handle401(err2,sessionGen))return;
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({busy:false});
   if((err2&&err2.code)!==-1)this.platformDropCommandFor('collect',stageId);
   fail(PF.errorMessageSales(err2));
  });
 },
 loadPlatformDelivery(){
  const PFc=this.__pf;
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.platformTradeFrame();
  this.setData({view:'delivery',title:'交付验收',salesContracts:null,delivery:null,noSignedContract:false,noDelivery:false,deliveryError:'',signedContractId:''});
  const api=this.platformSalesApi();
  api.contracts.list(this.id).then(rows=>{
   if(this.stale(gen,sessionGen,postKey))return;
   const signed=(rows||[]).map(r=>PF.contractRow(r)).find(c=>c.status==='SIGNED');
   if(!signed)return this.setData({noSignedContract:true});
   this.setData({noSignedContract:false,signedContractId:signed.id});
   return api.deliveries.get(signed.id).then(d=>{
    if(this.stale(gen,sessionGen,postKey))return;
    this.__deliveryId=d.id;
    this.setData({delivery:PF.deliveryView(d),noDelivery:false});
   }).catch(err=>{
    if(this.handle401(err,sessionGen))return;
    if(this.stale(gen,sessionGen,postKey))return;
    if(err&&err.code===1113000004)return this.setData({noDelivery:true,delivery:null});// R8 已确认归属 → 004=尚未创建交付
    this.setData({deliveryError:PFc.errorMessageSales(err)});
   });
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({deliveryError:PFc.errorMessageSales(err)});
  });
 },
 platformCreateDelivery(){
  if(this.data.busy||!this.data.signedContractId)return;
  const key=this.platformCommandFor('delivery-create',this.data.signedContractId);
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({busy:true,deliveryError:''});
  this.platformSalesApi().deliveries.create({contractId:this.data.signedContractId},key).then(()=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommandFor('delivery-create',this.data.signedContractId);
   this.setData({busy:false});
   this.loadPlatformDelivery();
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({busy:false});
   if((err&&err.code)!==-1)this.platformDropCommandFor('delivery-create',this.data.signedContractId);
   this.setData({deliveryError:PF.errorMessageSales(err)});
  });
 },
 platformAdvanceDelivery(e){
  const node=e.currentTarget.dataset.node;
  if(this.data.busy||!this.data.delivery||!this.__deliveryId)return;
  const idx=this.data.delivery.nodes.findIndex(n=>n.key===node);
  if(idx>this.data.delivery.nextIndex)return wx.showToast({title:'请按顺序推进交付节点',icon:'none'});
  const key=this.platformCommandFor('dv-'+node,this.data.signedContractId);
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({busy:true,deliveryError:''});
  this.platformSalesApi().deliveries.advance(this.__deliveryId,node,key).then(()=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommandFor('dv-'+node,this.data.signedContractId);
   this.setData({busy:false});
   this.loadPlatformDelivery();
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({busy:false});
   if((err&&err.code)!==-1)this.platformDropCommandFor('dv-'+node,this.data.signedContractId);
   this.setData({deliveryError:PF.errorMessageSales(err)});
  });
 },
 loadPlatformProgress(){
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.platformTradeFrame();
  this.setData({view:'progress',title:'客户工作区',customerTabs:['概况','跟进','业务进度'],activeCustomerTab:2,progressRequirements:[],progressQuotes:[],progressContracts:[],progressDelivery:null});
  const api=this.platformSalesApi();
  api.requirements.list(this.id).then(rows=>{if(!this.stale(gen,sessionGen,postKey))this.setData({progressRequirements:(rows||[]).map(r=>PF.requirementRow(r))});}).catch(()=>{});
  api.quotes.list(this.id).then(rows=>{if(!this.stale(gen,sessionGen,postKey))this.setData({progressQuotes:(rows||[]).map(q=>PF.quoteRow(q))});}).catch(()=>{});
  api.contracts.list(this.id).then(rows=>{
   if(this.stale(gen,sessionGen,postKey))return;
   const cards=(rows||[]).map(r=>PF.contractRow(r));
   this.setData({progressContracts:cards});
   const signed=cards.find(c=>c.status==='SIGNED');
   if(!signed)return;
   api.deliveries.get(signed.id).then(d=>{if(!this.stale(gen,sessionGen,postKey)){this.__deliveryId=d.id;this.setData({progressDelivery:PF.deliveryView(d)});}}).catch(()=>{});
  }).catch(()=>{});
 },
 platformPromote(e){
  const schemeId=e.currentTarget.dataset.schemeId;
  if(this.data.busy)return;
  const key=this.platformCommandFor('promote',schemeId);
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({busy:true});
  this.platformSalesApi().schemes.promote(schemeId,key).then(()=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommandFor('promote',schemeId);
   this.__promoted=this.__promoted||{};this.__promoted[String(schemeId)]=true;// S1-20：本会话 formal 标记
   this.setData({busy:false});
   wx.showToast({title:'已转为正式方案',icon:'none'});
   if(this.data.schemesList)this.setData({schemesList:this.data.schemesList.map(s2=>({...s2,formal:this.__promoted[s2.id]||s2.formal}))});
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({busy:false});
   if((err&&err.code)!==-1)this.platformDropCommandFor('promote',schemeId);
   wx.showToast({title:PF.errorMessageSales(err),icon:'none'});
  });
 },
 platformSubmitRequirement(){
  const f=this.data.form,fail=msg=>this.setData({error:msg,busy:false});
  if(!this.guardTradeSubmit())return;
  const w=Number(f.width),d=Number(f.depth),fl=Number(f.floors);
  if(!w||w<3||w>40||!/^\d{1,2}(\.\d)?$/.test(String(f.width||'')))return fail('面宽须为3–40米，最多一位小数');
  if(!d||d<3||d>40||!/^\d{1,2}(\.\d)?$/.test(String(f.depth||'')))return fail('进深须为3–40米，最多一位小数');
  if(!Number.isInteger(fl)||fl<1||fl>6)return fail('建筑层数须为1–6层整数');
  if(!PF.within(f.note,500)||!PF.within(f.prompt,500))return fail('补充说明不超过500字');
  const body={customerId:this.id,width:f.width,depth:f.depth,floors:fl,style:f.style};
  if((f.prompt||'').trim())body.prompt=f.prompt.trim();
  if((f.note||'').trim())body.note=f.note.trim();
  const key=this.platformCommand('requirement');
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.setData({busy:true,error:''});
  this.platformSalesApi().requirements.create(body,key).then(res=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommand('requirement');this.setData({busy:false});
   wx.showToast({title:'需求已登记 V'+res.version,icon:'none'});
   this.openView('customer',true);
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({busy:false});
   if((err&&err.code)!==-1)this.platformDropCommand('requirement');
   fail(PF.errorMessageSales(err));
  });
 },
 refresh(reset=true){
  // API 态刷新：无会话保持当前态（onLoad 门禁与 401 兜底），不回落本地；切岗清数据；按视图重拉
  const post=this.platformSession();
  if(!post||PF.pageMode(http.token(),post)!=='api')return;
  if(this.__postKey!==String(post.postId)){this.__post=post;this.__postKey=String(post.postId);this.__cmdKeys={};this.__transferStaff=null;this.setData({customer:null,form:{},filteredCustomers:[],modal:'',role:Job.roles[post.roleCode]||post.roleCode,isBossView:['boss','owner'].includes(post.roleCode),saleWrite:PF.can(post,'saleWrite')});}
  const v=this.data.view;
  if(v==='customers')this.loadPlatformList();
  else if(v==='budget')this.loadPlatformBudget();
  else if(v==='contract')this.loadPlatformContract();
  else if(v==='delivery')this.loadPlatformDelivery();
  else if(v==='requirement')this.prepareRequirement();
  else if(v==='progress')this.loadPlatformProgress();
  else if(this.id)this.loadPlatformDetail(v);
 },
 filterCustomers(){const kw=this.data.keyword,filter=this.data.filter,rows=this.data.customers||[];return this.setData({filteredCustomers:rows.filter(c=>((c.name||'')+String(c.phoneText||'')+c.searchText).includes(kw)&&(filter==='全部'||(filter==='待跟进'?c.status==='已登记':c.status===filter)))});},
 searchCustomers(e){this.setData({keyword:e.detail.value.trim()});this.filterCustomers();},
 setFilter(e){this.setData({filter:e.currentTarget.dataset.value});this.filterCustomers();},
 chooseCustomer(e){this.openView('customer',false,e.currentTarget.dataset.id);},
 deny(){this.setData({platformStatus:'degraded',platformError:'当前岗位暂无「'+(V.titles[this.data.view]||'客户业务')+'」办理权限',customer:null,sections:[],form:{},filteredCustomers:[],modal:''});// API 态权限不足落错误态，不跳 demo 引导
 },
 go(e){const d=e.currentTarget.dataset;if(!d.view)return;if(['receipt-supplement','pay-supplement'].includes(d.view))return require('../../utils/page-navigation').open('/pages/admin/index?view='+d.view+'&id='+encodeURIComponent(d.record));this.openView(d.view,false,null,d.record||'');},
 navTap(e){const url=e.currentTarget.dataset.url;if(url)require('../../utils/page-navigation').open(url);},
 openView(view,replace=false,id,record=''){if(view==='ai-facade')view='facade-config';if(!['customers','customer','arrival','transfer','requirement','budget','contract','delivery','progress'].includes(view)){require('../../utils/page-navigation').open('/pages/workflow/explore/index');return wx.showToast({title:'请在设计页办理',icon:'none'});}if(view==='arrival')view='customer';const target=id||this.id||'';if(view==='customers'&&!target)return this.platformBlocked('customers');return require('../../utils/page-navigation').open('/pages/workflow/index?view='+view+(view!=='customers'?'&id='+encodeURIComponent(target):''),{replace});},
 openModal(name){this.__cmdKeys={};if(name==='register')return this.setData({form:{name:'',phone:'',source:'视频号咨询',summary:''},modal:'register',error:''});if(name==='edit'){const c=this.data.customer;if(!c)return;return this.setData({form:{name:c.name,phone:c.phone,wechat:c.wechat,source:c.source,province:c.province,city:c.city,summary:c.summary},modal:'edit',error:''});}if(name==='follow'){if(!this.data.canFollow)return this.fail('当前岗位不能办理该客户');return this.setData({form:Object.assign({},this.data.form,{followContent:'',appointment:''}),modal:'follow',error:''});}return this.setData({modal:'',error:''});},
 modal(e){this.openModal(e.currentTarget.dataset.modal);},
 closeModal(){this.setData({modal:'',error:''});},
 noop(){},
 input(e){const key=e.currentTarget.dataset.key;this.setData({['form.'+key]:e.detail.value,error:''});this.saveDraft();},
 pick(e){const {key,options}=e.currentTarget.dataset;const values=e.currentTarget.dataset.values||this.data[options];this.setData({['form.'+key]:values?values[Number(e.detail.value)]:e.detail.value,error:''});this.saveDraft();},
 date(e){this.setData({['form.'+e.currentTarget.dataset.key]:e.detail.value,error:''});this.saveDraft();},
 select(e){const key=e.currentTarget.dataset.key;this.setData({['form.'+key]:e.currentTarget.dataset.value,error:''});if(key==='collabRole'){this.setData({'form.collaborator':'','form.collabAction':''});this.refresh(false);}this.saveDraft();},
 toggle(e){const key=e.currentTarget.dataset.key;this.setData({['form.'+key]:!this.data.form[key],error:''});this.saveDraft();},
 exteriorChange(e){this.setData({['form.exterior.'+e.currentTarget.dataset.key]:e.detail.value});this.saveDraft();},
 saveDraft(){// API 态：草稿不落本地存储；表单变更即作废命令快照/标记上次报价
  this.__formEditGen=(this.__formEditGen||0)+1;this.__cmdKeys={};if(this.data.view==='budget'&&this.data.budgetResult&&!this.data.budgetResult.stale)this.setData({'budgetResult.stale':true});},
 customerTab(e){const i=Number(e.currentTarget.dataset.index);if(i===2){this.openView('progress',true);return;}if(this.data.view==='progress'){require('../../utils/page-navigation').open('/pages/workflow/index?view=customer&id='+encodeURIComponent(this.id)+(i?'&tab='+i:''),{replace:true});return;}this.options.tab=i;this.setData({activeCustomerTab:i});},
  toggleProgressRecords(){const show=!this.data.showAllProgressRecords;this.setData({showAllProgressRecords:show,progressRecordLimit:20,visibleProgressRecords:show?(this.data.progressInfo?.records||[]).slice(0,20):[]});},
  loadMoreProgressRecords(){const limit=(this.data.progressRecordLimit||20)+20;this.setData({progressRecordLimit:limit,visibleProgressRecords:(this.data.progressInfo?.records||[]).slice(0,limit)});},
 planSelect(e){this.setData({selectedPlan:Number(e.currentTarget.dataset.index)});},
 floorTab(e){this.setData({floor:Number(e.currentTarget.dataset.index)});},
 fail,
 closePreview(){this.setData({preview:null});},
 previewImage(e){wx.previewImage({urls:[e.currentTarget.dataset.src],current:e.currentTarget.dataset.src});},
 submit(){if(this.data.busy)return;const v=this.data.view;
  if(v==='transfer')return this.platformSubmitTransfer();
  if(v==='requirement')return this.platformSubmitRequirement();
  if(v==='budget')return this.platformSubmitQuote();
  if(v==='contract')return this.platformSubmitContract();
 },
 saveModal(){const modal=this.data.modal;
  if(this.data.busy)return;
  if(modal==='register')return this.platformSubmitRegister();
  if(modal==='edit')return this.platformSubmitEdit();
  if(modal==='follow')return this.platformSubmitFollow();
 },
});

