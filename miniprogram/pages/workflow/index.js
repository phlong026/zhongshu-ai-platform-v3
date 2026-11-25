const Job=require('../../utils/organization');
const {routeValue,fail}=require('../../utils/view-container');
const PF=require('../../utils/platform-crm'),http=require('../../api/http');
const V=require('./views');
// 与 utils/platform-design 的 DESIGN_ROLES 保持一致，仅用于控制客户跟进页的设计入口展示。
const DESIGN_ROLES=['sales','sales-lead','hq-design-admin'];
Object.assign(V.titles,{'money-review':'老板金额审批','task-access':'任务动作授权'});
Page({
 data:{view:'customers',title:'我的客户',customer:null,contentSources:[],form:{},sections:[],modal:'',keyword:'',filter:'全部',filters:['全部','待跟进','跟进中','已到店'],filteredCustomers:[],caseTab:0,selectedPlan:0,floor:0,activeCustomerTab:0,showAllProgressRecords:false,requirements:['三代同住','4间卧室','一层老人房'],foundationOptions:['独立基础','条形基础','桩基础'],structureOptions:['框架结构','砖混结构'],roofOptions:['坡屋顶','平屋顶'],styleOptions:['全部风格','新中式','现代简约'],orientationOptions:['南','东','西','北'],resolutionOptions:['2K','4K'],countOptions:['1','2','4'],aspectOptions:['横图','竖图'],methodOptions:['电话','微信','到店'],nextOptions:['电话联系','微信联系','到店沟通'],sourceOptions:['视频号咨询','抖音咨询','朋友介绍','到店咨询','其他'],provinceOptions:['湖北省','广东省','浙江省','其他'],cityOptions:['武汉市','宜昌市','襄阳市','黄冈市','麻城市','红安县','武穴市','蕲春县','罗田县','英山县','黄梅县','团风县','浠水县','其他'],managerOptions:['李经理','王经理'],platformMode:false,platformStatus:'',platformError:'',requirementsStatus:'',requirementsError:'',customerTabs:['概况','跟进','项目进度'],showSizeBlock:false,todayStr:'',requirementStyles:['现代简约','新中式'],error:'',busy:false,preview:null,designFlow:false,selectingCustomer:false,facadeStyleOptions:['新中式','现代简约']},
 onLoad(options={}){this.initFormKeyboard();this.options=options;this.__post=null;this.__postKey='';this.__cmdKeys={};
  const post=(()=>{try{const auth=require('../../api/auth');return http.token()?auth.currentApiPost():null;}catch(e){return null;}})();
  if(PF.pageMode(http.token(),post)!=='api'){// 纯 API 态门禁：无平台会话不进本地演示，回登录页
   const q=Object.keys(options).filter(k=>options[k]).map(k=>k+'='+encodeURIComponent(options[k])).join('&');
   return wx.redirectTo({url:'/pages/account/index?view=login&return='+encodeURIComponent('/pages/workflow/index'+(q?'?'+q:''))});}
  return this.platformLoad(options,post);
 },
 onPageScroll(e){this.__navigationScrollTop=e.scrollTop;},
 onShow(){this.__hidden=false;if(this.__http&&(this.id||this.data.platformMode))this.refresh(false);},
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
 onUnload(){this.__unloaded=true;if(this.__keyboardHandler&&wx.offKeyboardHeightChange)wx.offKeyboardHeightChange(this.__keyboardHandler);},
 onHide(){this.__hidden=true;this.setData({keyboardInset:0});},
 platformSession(){try{const auth=require('../../api/auth');return http.token()?auth.currentApiPost():null;}catch(e){return null;}},
 platformLoad(options,post){
  this.__post=post;this.__pf=PF;this.__http=http;this.__crmApi=this.__crmApi||require('../../api/franchise/crm');
  this.__postKey=String(post.postId);this.__pageSession=http.sessionGeneration();this.id=routeValue(options.id)||'';
  this.homeContract=routeValue(options.contractId)||'';
  const requested=(options.view==='ai-facade'?'facade-config':options.view)||'';
  if(requested==='commission')return require('../../utils/page-navigation').open('/pages/workflow/commission/index?view=submit&customerId='+encodeURIComponent(this.id),{replace:true});
  const allowed=['customers','customer','arrival','transfer','requirement','budget','budget-result','contract','delivery','progress'];
  if(requested&&!allowed.includes(requested))return this.platformBlocked(requested);
  let view=requested?(requested==='arrival'?'customer':requested):'customer';
  if(view==='budget-result')view='budget';// 结果为页面内态；深链直达回参数页
  if(view!=='customers'&&!this.id)view='customers';
  this.setData({platformMode:true,view,title:V.titles[view]||'客户工作区',transferMode:0,customer:null,sections:[],form:{},modal:'',error:'',busy:false,blocked:null,customerTabs:['概况','跟进'],activeCustomerTab:0,todayStr:PF.today(),role:Job.roles[post.roleCode]||post.roleCode,isBossView:['boss','owner'].includes(post.roleCode),saleWrite:PF.can(post,'saleWrite'),canDesign:DESIGN_ROLES.includes(post.roleCode)});
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
   this.setData({platformStatus:'ready',customer:model.customer,schemesList:(model.schemesList||[]).map(s2=>({...s2,formal:!!(this.__promoted[s2.id]||s2.formal)})),showSizeBlock:model.showSizeBlock,blocked:null,
    customerInitial:(model.customer.name||'').slice(0,1),phone:PFc.maskPhone(model.customer.phone)||model.customer.wechat||'',
    projectSummary:model.customer.summary||'',city:(model.customer.city||'').replace(/市$/,''),
    registered:model.customer.status==='已登记',hasDesign:false,simpleBusiness:false});// 成功不清 error：版本冲突等提示保留至用户下次操作
   if(view==='transfer')this.reconcileTransferOwner();
   this.loadContentSources(this.id,gen,sessionGen,postKey);
  }).catch(err=>{
   if(this.handle401(err,sessionGen))return;
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({platformStatus:'degraded',error:PFc.errorMessage(err)});
  });
 },
 loadContentSources(customerId,gen,sessionGen,postKey){
  if(!customerId)return;
  const api=require('../../api/franchise/content');
  api.growth.customerSources(customerId).then(rows=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.setData({contentSources:Array.isArray(rows)?rows:[]});
  }).catch(()=>{if(!this.stale(gen,sessionGen,postKey))this.setData({contentSources:[]});});
 },
 openContentSource(e){
  const id=String(e.currentTarget.dataset.publicationId||'');
  if(!/^\d+$/.test(id))return wx.showToast({title:'发布来源编号无效',icon:'none'});
  wx.navigateTo({url:'/pages/studio/index?view=detail&record='+encodeURIComponent(id)});
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
 openFollowForm(){
  if(!this.data.canFollow)return this.fail('当前岗位不能办理该客户');
  this.__followForm={customerId:this.id,postKey:this.__postKey,sessionGen:this.__http.sessionGeneration(),key:PF.commandKey('follow')};
  this.setData({form:{followContent:'',appointment:'',contactKind:'FOLLOW',occurredOn:PF.today()},modal:'follow',error:''});
 },
 currentFollowForm(form,post){
  return form===this.__followForm&&this.sameCustomerContext(form,post);
 },
 sameCustomerContext(form,post){
  return !this.__unloaded&&form.customerId===this.id&&form.sessionGen===this.__http.sessionGeneration()&&
   !!post&&form.postKey===String(post.postId);
 },
 showSavedFollow(detail){
  this.__followForm=null;
  this.applyDetail(detail);
  this.setData({busy:false,modal:'',form:{},error:'',activeCustomerTab:1});
  wx.showToast({title:'已记录跟进',icon:'none'});
 },
 platformSubmitFollow(){
  if(this.data.busy)return;
  const form=this.__followForm;
  if(!form||!this.currentFollowForm(form,this.platformSession()))return this.refresh(false);
  const payload=PF.followPayload(this.data.form);
  const fail=error=>this.setData({error,busy:false});
  if(!payload.note||payload.note.length>500)return fail('请填写沟通内容（500字以内）');
  const appt=PF.toDate(payload.nextVisitDate);
  if(!appt)return fail('请选择下次回访日期');
  // 回执可能在跨日后补收。沿用原命令与原日期，让后端先判定它是否已保存。
  const signature=JSON.stringify(payload);
  if(signature!==form.lastSubmission&&appt<PF.today())return fail(PF.errorMessage({code:1110000009}));
  form.lastSubmission=signature;
  this.__loadGen=(this.__loadGen||0)+1;
  this.setData({busy:true,error:''});
  return this.__crmApi.customers.follow(form.customerId,payload,form.key).then(detail=>{
   if(this.__unloaded||this.__followForm!==form)return;
   if(!this.currentFollowForm(form,this.platformSession()))return this.refresh(false);
   if(this.__hidden)form.savedDetail=detail;
   else this.showSavedFollow(detail);
  },err=>{
   if(this.__unloaded||this.__followForm!==form)return;
   if(!this.currentFollowForm(form,this.platformSession()))return this.refresh(false);
   if(this.handle401(err,form.sessionGen)){this.__followForm=null;return;}
   const uncertain=err&&(err.code===-1||(err.code>=500&&err.code<600));
   fail(uncertain?'未收到保存确认，可直接重试同一条跟进':PF.errorMessage(err,'follow'));
  });
 },
 platformSubmitRegister(){
  if(this.__customerWrite)return this.runCustomerWrite(this.__customerWrite);
  const f=this.data.form,fail=msg=>this.setData({error:msg,busy:false});
  const name=String(f.name||'').trim();
  if(!name||name.length>64)return fail('请填写客户姓名（64字以内）');
  if(!PF.validPhone(f.phone))return fail('请输入11位有效手机号');
  const summary=String(f.summary||'').trim();
  if(!summary||summary.length>500)return fail('请填写需求摘要（500字以内）');
  const source=String(f.source||'').trim();
  if(!source||source.length>64)return fail('请选择获客来源');
  const addressError=PF.addressError(f,true);if(addressError)return fail(addressError);
  return this.startCustomerWrite('capture',PF.capturePayload(f));
 },
 platformSubmitEdit(){
  if(this.__customerWrite)return this.runCustomerWrite(this.__customerWrite);
  const f=this.data.form,c=this.data.customer,fail=msg=>this.setData({error:msg,busy:false});
  const name=String(f.name||'').trim();
  if(!name||name.length>64)return fail('请填写客户姓名（64字以内）');
  if(!PF.within(f.wechat,64)||!PF.within(f.summary,500))return fail('填写内容超过长度限制');
  const addressError=PF.addressError(f,false);if(addressError)return fail(addressError);
  if(c?.revision==null)return fail('客户资料正在刷新，请稍后重试');
  return this.startCustomerWrite('update',PF.updatePayload(f,c.revision));
 },
 platformSubmitTransfer(){
  if(this.__customerWrite)return this.runCustomerWrite(this.__customerWrite);
  const f=this.data.form,c=this.data.customer,fail=msg=>this.setData({error:msg,busy:false});
  // M04-I04：picker 存的是候选 label（同名已加部门后缀去歧义）——按 label 反查唯一 userId
  const cands=this.__transferCandidates||[];
  const chosen=cands.find(c => c.label === f.newOwner);
  const targetUserId=chosen?chosen.userId:(this.__transferTargets?this.__transferTargets[f.newOwner]:null);
  if(!targetUserId)return fail('请选择接手人');
  const reason=String(f.transferReason||'').trim();
  if(!reason||reason.length>200)return fail('请填写转交原因（200字以内）');
  if(!c||(c.revision===undefined||c.revision===null))return fail('数据已变化，请刷新后重试');
  return this.startCustomerWrite('transfer',PF.transferPayload(targetUserId,f,c.revision));
 },
 startCustomerWrite(action,payload){
  const write={action,payload,key:PF.commandKey(action),customerId:this.id,postKey:this.__postKey,sessionGen:this.__pageSession};
  if(!this.sameCustomerContext(write,this.platformSession()))return this.refresh(false);
  // 旧的详情读取不能盖过本次写入；返回前台也不能换掉这次请求的键、版本或接手人。
  if(action!=='capture')this.__loadGen=(this.__loadGen||0)+1;
  this.__customerWrite=write;
  return this.runCustomerWrite(write);
 },
 customerWriteAlive(write){
  if(this.__unloaded||this.__customerWrite!==write)return false;
  if(this.sameCustomerContext(write,this.platformSession()))return true;
  this.refresh(false);
  return false;
 },
 async runCustomerWrite(write){
  if(this.data.busy||!this.customerWriteAlive(write))return;
  const {action,payload,key,customerId}=write,api=this.__crmApi;
  this.setData({busy:true,error:''});
  let detail;
  try{
   if(action==='capture'){
    if(!write.receipt)write.receipt=await api.leads.capture(payload,key);
    if(!this.customerWriteAlive(write))return;
    if(write.receipt?.result==='LINKED'&&write.receipt.customerId!=null){
     await api.customers.get(String(write.receipt.customerId));
    }
   }else if(action==='update')detail=await api.customers.update(customerId,payload,key);
   else await api.customers.transfer(customerId,payload,key);
  }catch(err){
   if(!this.customerWriteAlive(write))return;
   if(this.handle401(err,write.sessionGen)){this.__customerWrite=null;this.setData({customerRetry:''});return;}
   if(write.receipt){
    if(err?.code===1110000000||err?.code===403){
     this.completeCustomerWrite(write,()=>this.setData({error:'已登记，该客户由同事负责，请联系负责人查看'}));
    }else this.setData({busy:false,customerRetry:'view',error:'已登记并关联原客户，暂时无法查看，可重试查看'});
    return;
   }
   const code=Number(err?.code);
   if(!(code>=1000000||[400,403,404,409,422].includes(code))){
    this.setData({busy:false,customerRetry:'confirm',error:'未收到保存确认，可重试确认本次提交'});
    return;
   }
   this.__customerWrite=null;
   this.setData({busy:false,customerRetry:'',error:PF.errorMessage(err,action)});
   if(code===1099000001&&action!=='capture')this.loadPlatformDetail(this.data.view);
   return;
  }
  if(!this.customerWriteAlive(write))return;
  this.completeCustomerWrite(write,()=>{
   if(action==='update'){this.applyDetail(detail);return wx.showToast({title:'已保存',icon:'none'});}
   if(action==='transfer'){wx.showToast({title:'已转交',icon:'none'});return this.openView('customer',true);}
   const {result,customerId:cid}=write.receipt||{};
   if(cid!=null&&(result==='CREATED'||result==='LINKED'))return this.openView('customer',true,String(cid));
   this.setData({error:result==='LINK_REQUIRED'?'已登记，待负责人关联原客户':'登记结果异常，请刷新核对'});
  });
 },
 completeCustomerWrite(write,show){
  const finish=()=>{
   this.__customerWrite=null;
   this.setData({busy:false,customerRetry:'',modal:'',form:{},error:''});
   show();
  };
  if(this.__hidden)write.onReturn=finish;
  else finish();
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
  this.setData({view:'contract',title:'签订合同',salesContracts:null,contractsError:'',salesQuotes:null,quotesError:'',canRegister:false,canFollow:false,canCollect:PF.can(this.__post,'collect')});// 新建与调整付款计划由独立页面保留填写。
  this.loadTradeCustomerContext(gen,sessionGen,postKey);
  if(!saleWrite)return this.blockTradeLoad();
  const api=this.platformSalesApi();
  api.contracts.list(this.id).then(rows=>{
   if(this.stale(gen,sessionGen,postKey))return;
   const cards=(rows||[]).map(r=>PF.contractRow(r));
   const visible=this.homeContract?cards.filter(c=>String(c.id)===this.homeContract):cards;
   this.setData({salesContracts:visible,contractsError:this.homeContract&&!visible.length?'对应合同已变更，请返回刷新指标明细':'',contractsHasActive:cards.some(c=>c.status!=='VOIDED')});// WXML 不支持方法调用，标志位在 JS 算
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
 openContractForm(){if(this.guardTradeSubmit('contractError'))require('../../utils/page-navigation').open('/pages/workflow/payment-plan/index?customerId='+encodeURIComponent(this.id));},
 openPaymentDraft(){if(this.guardTradeSubmit('contractError'))require('../../utils/page-navigation').open('/pages/workflow/payment-plan/index?draft=1&customerId='+encodeURIComponent(this.id));},
 openPaymentPlan(e){const id=String(e.currentTarget.dataset.id);if((this.data.salesContracts||[]).some(c=>c.id===id))require('../../utils/page-navigation').open('/pages/workflow/payment-plan/index?contractId='+encodeURIComponent(id));},
 platformSubmitContract(){return this.openContractForm();},
 platformCommandFor(action,targetId){this.__cmdKeys=this.__cmdKeys||{};const k=action+':'+targetId;if(!this.__cmdKeys[k])this.__cmdKeys[k]=PF.commandKey(action);return this.__cmdKeys[k];},
 platformDropCommandFor(action,targetId){if(this.__cmdKeys)delete this.__cmdKeys[action+':'+targetId];},
 platformContractSign(e){
  const card=(this.data.salesContracts||[]).find(c=>c.id===String(e.currentTarget.dataset.id));
  if(this.data.busy||!PF.can(this.__post,'saleWrite')||!card||card.status!=='WAIT_SIGN')return;
  return this.openContractEvidence(e);
 },
 openContractEvidence(e){
  const id=String(e.currentTarget.dataset.id);
  if(!this.data.busy&&(this.data.salesContracts||[]).some(c=>c.id===id))require('../../utils/page-navigation').open('/pages/contract-documents/index?contractId='+encodeURIComponent(id));
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
  const returned=(this.data.salesContracts||[]).flatMap(c=>c.declarations||[])
   .find(d=>d.id===String(e.currentTarget.dataset.declarationId)&&d.stageId===String(stageId)&&d.status==='RETURNED');
  return this.setData({modal:'collect',error:'',collectStageId:stageId,
   collectStageText:(ctx.stageText||'期款')+' · 剩余可收 ¥'+(ctx.balanceText||ctx.amountText||'0'),
   collectForm:{method:returned?.method||'bank',accountName:returned?.accountName||'',transactionNo:returned?.transactionNo||'',receivedDate:returned?.date||PF.today(),amount:returned?.amountText||(ctx.balanceText||ctx.amountText||'')}});
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
  this.platformSalesApi().stages.collect(stageId,{amountCents:PF.toCents(form.amount),method:form.method,accountName:(form.accountName||'').trim()||undefined,transactionNo:(form.transactionNo||'').trim()||undefined,receivedDate:form.receivedDate||undefined},key).then((result)=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommandFor('collect',stageId);this.setData({busy:false,modal:'',error:''});
   wx.showToast({title:result&&result.stageStatus==='PENDING_CONFIRMATION'?'已提交，待财务核实':'已核实回款',icon:'none'});
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
  this.loadTradeCustomerContext(gen,sessionGen,postKey);
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
  this.loadTradeCustomerContext(gen,sessionGen,postKey);
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
  const write=this.__customerWrite,form=this.__followForm;
  if(write&&this.sameCustomerContext(write,post)){
   if(write.onReturn&&!this.__hidden)write.onReturn();
   return;
  }
  if(form&&this.currentFollowForm(form,post)){
   if(form.savedDetail&&!this.__hidden)this.showSavedFollow(form.savedDetail);
   return;
  }
  const sessionGen=this.__http.sessionGeneration();
  if(write||form||sessionGen!==this.__pageSession||this.__postKey!==String(post?.postId||'')){
   this.__customerWrite=null;this.__followForm=null;this.__cmdKeys={};
   this.__transferStaff=null;this.__transferCandidates=[];this.__transferTargets={};
   this.__pageSession=sessionGen;this.__post=post;this.__postKey=post?String(post.postId):'';
   this.setData({customer:null,form:{},customers:[],filteredCustomers:[],ownerOptions:[],modal:'',error:'',busy:false,customerRetry:'',canFollow:false,canRegister:false,canEditCustomer:false});
   if(!post||PF.pageMode(http.token(),post)!=='api'){
    this.setData({platformStatus:'degraded',platformError:post?'当前岗位暂无客户查看权限':'请先登录后查看客户'});
    return;
   }
   this.setData({role:Job.roles[post.roleCode]||post.roleCode,isBossView:['boss','owner'].includes(post.roleCode),saleWrite:PF.can(post,'saleWrite')});
  }
  if(!post||PF.pageMode(http.token(),post)!=='api')return;
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
 openDesignTool(e){
  const tool=e.currentTarget.dataset.tool;
  if(!tool)return;
  if(!this.data.platformMode){
   const demo={inspiration:'inspiration',ai:'ai-config',budget:'budget',commission:'request'};
   return this.openView(demo[tool],false,null);
  }
  if(tool==='commission')return this.openView('commission',false,null);
  const routes={inspiration:'/design-migration/pages/library/index',ai:'/design-migration/pages/ai-design/index?line=FLAT',budget:'/design-migration/pages/budget/quick'};
  if(routes[tool])require('../../utils/page-navigation').open(routes[tool]+(this.id?(routes[tool].includes('?')?'&':'?')+'customerId='+encodeURIComponent(this.id):''));
 },
 navTap(e){const url=e.currentTarget.dataset.url;if(url)require('../../utils/page-navigation').open(url);},
 openView(view,replace=false,id,record=''){if(view==='commission')return require('../../utils/page-navigation').open('/pages/workflow/commission/index?view=submit&customerId='+encodeURIComponent(id||this.id||''));if(view==='ai-facade')view='facade-config';if(!['customers','customer','arrival','transfer','requirement','budget','contract','delivery','progress'].includes(view)){require('../../utils/page-navigation').open('/pages/workflow/explore/index');return wx.showToast({title:'请在设计页办理',icon:'none'});}if(view==='arrival')view='customer';const target=id||this.id||'';if(view==='customers'&&!target)return this.platformBlocked('customers');return require('../../utils/page-navigation').open('/pages/workflow/index?view='+view+(view!=='customers'?'&id='+encodeURIComponent(target):''),{replace});},
 openModal(name){if(this.data.busy)return;this.__customerWrite=null;this.setData({customerRetry:''});this.__followForm=null;this.prepareFormSheet();this.__cmdKeys={};if(name==='register')return this.setData({form:{name:'',phone:'',source:'',summary:'',province:'',city:'',district:'',town:'',village:'',regionCode:'',addressDetail:''},modal:'register',error:''});if(name==='edit'){const c=this.data.customer;if(!c)return;return this.setData({form:{name:c.name,phone:c.phone,wechat:c.wechat,source:c.source,...PF.addressPayload(c),summary:c.summary},modal:'edit',error:''});}if(name==='follow')return this.openFollowForm();return this.setData({modal:'',error:''});},
 initFormKeyboard(){
  this.__keyboardHandler=event=>this.formKeyboardChanged(event);
  if(wx.onKeyboardHeightChange)wx.onKeyboardHeightChange(this.__keyboardHandler);
 },
 prepareFormSheet(){
  const info=wx.getWindowInfo?wx.getWindowInfo():wx.getSystemInfoSync?wx.getSystemInfoSync():{windowHeight:700};
  this.__formWindowHeight=info.windowHeight;
  this.__formTopInset=info.statusBarHeight?info.statusBarHeight+44:12;
  this.__focusedFormField='';
  this.setData({keyboardInset:0,sheetHeight:Math.round(info.windowHeight*.9),sheetFocusTarget:'',sheetScrollTop:0});
 },
 formKeyboardChanged(event){
  if(!this.data.modal)return;
  const height=Math.max(0,Number(event.height)||0),base=this.__formWindowHeight||700;
  const info=wx.getWindowInfo?wx.getWindowInfo():{windowHeight:base};
  const resized=Math.max(0,base-info.windowHeight);
  this.setData({keyboardInset:Math.max(0,height-resized),sheetHeight:Math.max(160,Math.min(Math.round(base*.9),base-height-(this.__formTopInset||12)))},()=>this.revealFormField());
 },
 formFieldFocus(event){
  this.__focusedFormField=event.detail?.manual?'customer-address-manual':event.currentTarget.dataset.field||'customer-address-field';
  this.revealFormField();
 },
 formFieldBlur(){this.__focusedFormField='';},
 revealFormField(){
  const target=this.__focusedFormField;if(!target)return;
  if(target==='customer-address-manual'){
   const component=this.selectComponent('#customer-address');
   if(component)component.measureManualField(field=>{
    if(!field||this.__focusedFormField!==target)return;
    this.createSelectorQuery().select('.sheet-fields').boundingClientRect(view=>{
     if(!view)return;
     const delta=field.bottom>view.bottom-12?field.bottom-view.bottom+12:field.top<view.top+12?field.top-view.top-12:0;
     if(delta)this.setData({sheetScrollTop:Math.max(0,(this.__sheetScrollTop||0)+delta)});
    }).exec();
   });
   return;
  }
  this.setData({sheetFocusTarget:''},()=>this.setData({sheetFocusTarget:target}));
 },
 sheetScrolled(event){this.__sheetScrollTop=event.detail.scrollTop;},
 customerAddressChanged(event){
  if(this.data.busy||this.data.customerRetry)return;
  const values={error:''};Object.entries(PF.addressPayload(event.detail.value)).forEach(([key,value])=>{values['form.'+key]=value;});
  this.setData(values);this.saveDraft();
 },
 modal(e){this.openModal(e.currentTarget.dataset.modal);},
 closeModal(){if(this.data.busy)return;this.__customerWrite=null;this.setData({customerRetry:''});this.__followForm=null;if(wx.hideKeyboard)wx.hideKeyboard();this.setData({modal:'',error:'',keyboardInset:0,sheetFocusTarget:''});},
 noop(){},
 input(e){if(this.__customerWrite)return;const key=e.currentTarget.dataset.key;this.setData({['form.'+key]:e.detail.value,error:''});this.saveDraft();},
 pick(e){if(this.__customerWrite)return;const {key,options}=e.currentTarget.dataset;const values=e.currentTarget.dataset.values||this.data[options];this.setData({['form.'+key]:values?values[Number(e.detail.value)]:e.detail.value,error:''});this.saveDraft();},
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
