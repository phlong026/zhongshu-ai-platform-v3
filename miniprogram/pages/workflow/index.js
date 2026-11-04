const Job=require('../../utils/organization');
const {routeValue,fail}=require('../../utils/view-container');
// Shared business records remain in the main package; this page and its imagery load on demand.
const B=require('../../utils/business'),Access=require('../../utils/access'),SF=require('../../utils/sales-flow');
const PF=require('../../utils/platform-crm'),http=require('../../api/http');
const V=require('./views'),Coord=require('../../utils/design-coordination'),ProjectProgress=require('../../utils/customer-project-progress');
const Files=require('../../utils/sample-files');
Object.assign(V.titles,{'money-review':'老板金额审批','task-access':'任务动作授权'});
const designViews=['inspiration','search','case','case-plan','ai-config','ai-results','result','facade-config','facade-candidates','facade-result','budget','budget-result','progress'];
const cases=require('../../utils/design-cases'),Design=require('./design-adapter');
Page({
 markSalesReady(){try{B.write(this.id,n=>{n.salesIntent={ready:true,actorId:Access.context().staffId,at:new Date().toISOString()};},'客户需求明确','sales-ready',this.data.customer.revision);this.openView('budget',true);}catch(e){this.fail(e.message);}},
 data:{view:'customers',title:'我的客户',customer:null,form:{},sections:[],modal:'',keyword:'',filter:'全部',filters:['全部','待跟进','跟进中','已到店'],cases,filteredCases:cases,filteredCustomers:[],selectedCase:cases[0],caseTab:0,selectedPlan:0,floor:0,activeCustomerTab:0,showAllProgressRecords:false,requirements:['三代同住','4间卧室','一层老人房'],foundationOptions:['独立基础','条形基础','桩基础'],structureOptions:['框架结构','砖混结构'],roofOptions:['坡屋顶','平屋顶'],styleOptions:['全部风格','新中式','现代简约'],floorOptions:Design.labels,orientationOptions:['南','东','西','北'],resolutionOptions:['2K','4K'],countOptions:['1','2','4'],aspectOptions:['横图','竖图'],methodOptions:['电话','微信','到店'],nextOptions:['电话联系','微信联系','到店沟通'],sourceOptions:['视频号咨询','抖音咨询','朋友介绍','到店咨询','其他'],provinceOptions:['湖北省','广东省','浙江省','其他'],cityOptions:['武汉市','宜昌市','襄阳市','黄冈市','麻城市','红安县','武穴市','蕲春县','罗田县','英山县','黄梅县','团风县','浠水县','其他'],managerOptions:['李经理','王经理'],platformMode:false,platformStatus:'',platformError:'',customerTabs:['概况','跟进','项目进度'],showSizeBlock:false,todayStr:'',requirementStyles:['现代简约','新中式'],error:'',busy:false,preview:null,designFlow:false,selectingCustomer:false,facadeStyleOptions:['新中式','现代简约']},
 onLoad(options={}){this.options=options;this.__post=null;this.__postKey='';this.__cmdKeys={};
  const post=(()=>{try{const auth=require('../../api/auth');return http.token()?auth.currentApiPost():null;}catch(e){return null;}})();
  if(PF.pageMode(http.token(),post)==='api')return this.platformLoad(options,post);
  const sid=routeValue(options.id)||(B.load().customers[0]||{}).id||'',target=SF.entry(sid&&B.customer(sid),options.view);if(target){wx.redirectTo({url:target});return;}this.setData({managerOptions:(Access.rawBook(Access.context().companyId)?.staff||[]).filter(p=>p.active!==false&&(p.roles||[p.roleId]).includes('project-manager')).map(p=>p.name)});this.id=routeValue(options.id)||(B.load().customers[0]||{}).id||''; const requested=options.view==='ai-facade'?'facade-config':options.view;const view=V.titles[requested]?requested:routeValue(options.id)&&B.customer(routeValue(options.id))?'customer':'customers';const selectingCustomer=view==='customers'&&['search','inspiration','case','ai-config','ai-facade','budget'].includes(options.destination);this.setData({view,designFlow:selectingCustomer||designViews.includes(view),selectingCustomer,title:selectingCustomer?'选择客户':V.titles[view],ownerOptions:(Access.rawBook(Access.context().companyId)?.staff||[]).filter(p=>p.active!==false&&Job.staffHas(p,'sales')).map(p=>p.name),resultOptions:['已联系','未接通','待回复','已到店'],transferMode:0,selectedCustomers:[this.id],selectedFlags:{[this.id]:true}});this.refresh();if(options.modal)this.openModal(options.modal);},
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
  this.setData({platformMode:false,platformStatus:'',platformError:'',customer:null,sections:[],form:{},filteredCustomers:[],modal:'',error:'',busy:false});
  this.onShow();// 回 guest（demo）渲染
  return true;
 },
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
  this.setData({platformMode:true,view,title:V.titles[view]||'客户工作区',transferMode:0,customer:null,sections:[],form:{},modal:'',error:'',busy:false,blocked:null,customerTabs:['概况','跟进'],activeCustomerTab:0,todayStr:PF.today(),isBossView:['boss','owner'].includes(post.roleCode)});
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
  this.setData({platformStatus:'loading',error:this.data.error||'正在加载…',customer:null,canEditCustomer:canW,canFollow:canW});// 保留已有文案（如版本冲突提示）
  if(view==='transfer')this.loadTransferTargets(sessionGen);
  this.__crmApi.customers.get(this.id).then(detail=>{
   if(this.stale(gen,sessionGen,postKey))return;
   const model=PFc.detailView(detail,{today:PFc.today(),canWrite:canW});
   this.__promoted=this.__promoted||{};
   this.setData({platformStatus:'ready',customer:model.customer,schemesList:(model.schemesList||[]).map(s2=>({...s2,formal:this.__promoted[s2.id]||false})),showSizeBlock:model.showSizeBlock,blocked:null,
    customerInitial:(model.customer.name||'').slice(0,1),phone:PFc.maskPhone(model.customer.phone)||model.customer.wechat||'',
    projectSummary:model.customer.summary||'',city:(model.customer.city||'').replace(/市$/,''),
    registered:model.customer.status==='已登记',hasDesign:false,simpleBusiness:false});// 成功不清 error：版本冲突等提示保留至用户下次操作
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
   const targets=(rows||[]).filter(r=>['sales','sales-lead'].includes(r.roleCode)&&r.active===true);
   // M04-I04：同名员工都保留（按 userId 区分），展示名加部门后缀去歧义；映射按 picker 序→userId
   const seen={};
   const candidates=targets.map(r=>{
    const dup=seen[r.name];seen[r.name]=true;
    return {userId:r.userId,label:dup||targets.some(o=>o!==r&&o.name===r.name&&o.userId!==r.userId)?r.name+'（'+(r.teamName||r.roleCode)+'）':r.name};
   });
   this.__transferCandidates=candidates;
   this.setData({ownerOptions:candidates.map(c=>c.label)});
  }).catch(err=>{
   if(this.stale(gen,sessionGen,postKey))return;
   if(this.handle401(err,sessionGenAtRequest))return;
   this.setData({platformError:'接手人列表加载失败：'+PFc.errorMessage(err)});
  });
 },
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
    return this.__crmApi.customers.get(cid).then(()=>this.openView('customer',true,cid)).catch(err=>{
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
 platformTradeFrame(){this.setData({platformMode:true,customerNameText:this.data.customerNameText||this.customerNameFallback(),saleWrite:PF.can(this.__post,'saleWrite'),todayStr:PF.today()});},
 fetchLatestRequirement(gen,sessionGen,postKey){
  return this.platformSalesApi().requirements.list(this.id).then(rows=>{
   if(this.stale(gen,sessionGen,postKey))return null;
   const latest=(rows||[])[0]||null;
   this.setData({salesRequirements:(rows||[]).map(r=>PF.requirementRow(r))});
   return latest;
  }).catch(()=>null);
 },
 prepareRequirement(){
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.platformTradeFrame();
  this.setData({view:'requirement',title:'登记需求',form:{width:'',depth:'',floors:'',style:'现代简约',prompt:'',note:''},error:'',busy:false});
  this.fetchLatestRequirement(gen,sessionGen,postKey).then(latest=>{
   if(!latest||this.stale(gen,sessionGen,postKey))return;
   this.setData({form:{width:latest.width!=null?String(latest.width):'',depth:latest.depth!=null?String(latest.depth):'',floors:latest.floors!=null?String(latest.floors):'',style:latest.style||'现代简约',prompt:latest.prompt||'',note:latest.note||''}});
  });
 },
 loadPlatformBudget(){
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.platformTradeFrame();
  this.setData({view:'budget',title:'报价测算',error:'',busy:false});
  const fill=f=>{if(this.stale(gen,sessionGen,postKey))return;this.setData({form:Object.assign({budgetChecked:false,exterior:{window:true,paint:true,stone:true,light:true,waterproof:true,lightning:true,insurance:false}},this.data.form,f)});};
  this.fetchLatestRequirement(gen,sessionGen,postKey).then(latest=>{
   if(this.stale(gen,sessionGen,postKey))return;
   if(latest&&latest.width!=null){fill({width:String(latest.width),depth:String(latest.depth),floors:String(latest.floors||''),roofArea:String(Math.round(Number(latest.width)*1.1))});return;}
   this.__crmApi.customers.get(this.id).then(detail=>{if(!this.stale(gen,sessionGen,postKey)){const c=detail.customer||{};fill({width:c.width!=null?String(c.width):'',depth:c.depth!=null?String(c.depth):'',floors:c.floors!=null?String(c.floors):'',roofArea:c.width?String(Math.round(Number(c.width)*1.1)):''});}}).catch(()=>{});
  });
 },
 budgetShowResult(){if(this.data.budgetResult)return this.setData({view:'budget-result'});wx.showToast({title:'请先完成报价测算',icon:'none'});},
 budgetShowForm(){return this.setData({view:'budget'});},
 platformSubmitQuote(){
  const f=this.data.form,fail=msg=>this.setData({error:msg,busy:false});
  const dims={width:Number(f.width),depth:Number(f.depth),floors:Number(f.floors)};
  if(!dims.width||dims.width<3||dims.width>40||!/^\d{1,2}(\.\d)?$/.test(String(f.width||'')))return fail('面宽须为3–40米，最多一位小数');
  if(!dims.depth||dims.depth<3||dims.depth>40||!/^\d{1,2}(\.\d)?$/.test(String(f.depth||'')))return fail('进深须为3–40米，最多一位小数');
  if(!Number.isInteger(dims.floors)||dims.floors<1||dims.floors>6)return fail('建筑层数须为1–6层整数');
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
   if(this.stale(gen,sessionGen,postKey))return;
   if(this.handle401(err,sessionGen))return;
   this.setData({busy:false});
   if((err&&err.code)!==-1)this.platformDropCommand('quote');
   fail(PF.errorMessageSales(err));
  });
 },
 loadPlatformContract(){
  const PFc=this.__pf;
  const gen=this.__loadGen=(this.__loadGen||0)+1,sessionGen=this.__http.sessionGeneration(),postKey=this.__postKey;
  this.platformTradeFrame();
  this.setData({view:'contract',title:'签订合同',salesContracts:null,contractsError:'',salesQuotes:null,quotesError:'',canRegister:false,canFollow:false,canCollect:PF.can(this.__post,'collect')});// contractForm 保留（S1-21 表单保留协议）
  const api=this.platformSalesApi();
  api.contracts.list(this.id).then(rows=>{
   if(this.stale(gen,sessionGen,postKey))return;
   const cards=(rows||[]).map(r=>PF.contractRow(r));
   this.setData({salesContracts:cards,contractsHasActive:cards.some(c=>c.status!=='VOIDED')});// WXML 不支持方法调用，标志位在 JS 算
  }).catch(err=>{
   if(this.stale(gen,sessionGen,postKey))return;
   if(this.handle401(err,sessionGen))return;
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
 openContractForm(){const q=(this.data.salesQuotes||[])[0];this.setData({contractForm:{total:(q&&q.totalText)||'',first:'',signedDate:''},contractError:''});},
 contractInput(e){this.setData({['contractForm.'+e.currentTarget.dataset.key]:e.detail.value,contractError:''});this.saveDraft();},
 contractDate(e){this.setData({'contractForm.signedDate':e.detail.value,contractError:''});this.saveDraft();},
 platformSubmitContract(){
  const fm=this.data.contractForm,fail=msg=>this.setData({contractError:msg,busy:false});
  const total=PF.toCents(fm.total),first=PF.toCents(fm.first);
  if(!total||!first)return fail('请填写合同总额与首款（最多两位小数）');
  if(first>total)return fail('首款不能大于合同总额');
  const body={customerId:this.id,totalCents:total,firstCents:first};
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
   if(this.stale(gen,sessionGen,postKey))return;
   if(this.handle401(err,sessionGen))return;
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
   if(this.stale(gen,sessionGen,postKey))return;
   if(this.handle401(err,sessionGen))return;
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
    if(this.stale(gen,sessionGen,postKey))return;
    if(this.handle401(err,sessionGen))return;
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
  return this.setData({modal:'collect',error:'',collectStageId:stageId,collectForm:{method:'bank',transactionNo:'',receivedDate:PF.today()}});
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
  this.platformSalesApi().stages.collect(stageId,{method:form.method,transactionNo:(form.transactionNo||'').trim()||undefined,receivedDate:form.receivedDate||undefined},key).then(()=>{
   if(this.stale(gen,sessionGen,postKey))return;
   this.platformDropCommandFor('collect',stageId);this.setData({busy:false,modal:'',error:''});
   wx.showToast({title:'已办理回款',icon:'none'});
   this.loadPlatformContract();
  }).catch(err2=>{
   if(this.stale(gen,sessionGen,postKey))return;
   if(this.handle401(err2,sessionGen))return;
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
    if(this.stale(gen,sessionGen,postKey))return;
    if(this.handle401(err,sessionGen))return;
    if(err&&err.code===1113000004)return this.setData({noDelivery:true,delivery:null});// R8 已确认归属 → 004=尚未创建交付
    this.setData({deliveryError:PFc.errorMessageSales(err)});
   });
  }).catch(err=>{
   if(this.stale(gen,sessionGen,postKey))return;
   if(this.handle401(err,sessionGen))return;
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
   if(this.stale(gen,sessionGen,postKey))return;
   if(this.handle401(err,sessionGen))return;
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
   if(this.stale(gen,sessionGen,postKey))return;
   if(this.handle401(err,sessionGen))return;
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
   if(this.stale(gen,sessionGen,postKey))return;
   if(this.handle401(err,sessionGen))return;
   this.setData({busy:false});
   if((err&&err.code)!==-1)this.platformDropCommandFor('promote',schemeId);
   wx.showToast({title:PF.errorMessageSales(err),icon:'none'});
  });
 },
 platformSubmitRequirement(){
  const f=this.data.form,fail=msg=>this.setData({error:msg,busy:false});
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
   if(this.stale(gen,sessionGen,postKey))return;
   if(this.handle401(err,sessionGen))return;
   this.setData({busy:false});
   if((err&&err.code)!==-1)this.platformDropCommand('requirement');
   fail(PF.errorMessageSales(err));
  });
 },
 refresh(reset=true){
  if(this.data.platformMode){// API 态刷新：会话失效回退 guest 渲染；切岗清数据；否则按视图重拉
   const post=this.platformSession();
   if(PF.pageMode(http.token(),post)!=='api'){this.setData({platformMode:false,platformStatus:'',platformError:'',customer:null,sections:[],form:{},filteredCustomers:[]});return;}
   if(this.__postKey!==String(post.postId)){this.__post=post;this.__postKey=String(post.postId);this.__cmdKeys={};this.setData({customer:null,form:{},filteredCustomers:[],modal:''});}
   const v=this.data.view;
   if(v==='customers')this.loadPlatformList();
   else if(v==='budget')this.loadPlatformBudget();
   else if(v==='contract')this.loadPlatformContract();
   else if(v==='delivery')this.loadPlatformDelivery();
   else if(v==='requirement')this.prepareRequirement();
   else if(v==='progress')this.loadPlatformProgress();
   else if(this.id)this.loadPlatformDetail(v);
   return;
  }
  const actor=Access.context(),viewName=this.data.view;if(!actor.active){this.setData({customer:null,sections:[],form:{},filteredCustomers:[]});wx.redirectTo({url:'/pages/account/index?view=login&return='+encodeURIComponent('/pages/workflow/index?view='+viewName+(this.id?'&id='+this.id:''))});return;}if(viewName==='customers'){this.setData({form:Object.assign({},B.clone(V.defaults)),canRegister:Job.has(actor.role,'sales'),role:Access.roles[actor.role]});this.filterCustomers();return;}const c=B.customer(this.id),target=SF.entry(c,viewName);if(target){wx.redirectTo({url:target});return;}if(!c||!Access.workflow(viewName,c,false)){this.deny();return;}
  this.setData({simpleBusiness:!SF.legacy(c)});
  const view=this.data.view;this.setData({});let form=this.data.form;const savedDraft=c.drafts[B.draftKey(view)]||{};const validDraft=savedDraft;
  if(reset||!form.width){form=Object.assign({},B.clone(V.defaults),{width:c.width,depth:c.depth,floors:c.floors,paymentDate:B.date(),signedDate:B.date(),receiveDate:B.date(),confirmationDate:B.date(),receiptDate:B.date(),dueDate:B.date()},view==='ai-results'?c.drafts[B.draftKey('ai-config')]||{}:view==='facade-candidates'?c.drafts[B.draftKey('facade-config')]||{}:{},validDraft);if(view==='budget'&&!c.drafts[B.draftKey(view)]){form.footprint=String(Math.round(Number(c.width)*Number(c.depth)*10)/10);form.area=String(Math.round(Number(form.footprint)*Number(c.floors)*10)/10);form.roofArea=String(Math.round(Number(form.footprint)*1.1));}}
  if(this.options.case){form.referenceCaseId=this.options.case;}const referenceQuery=JSON.stringify([this.options.case,this.options.refWidth,this.options.refDepth]);if(B.dimension(this.options.refWidth)&&B.dimension(this.options.refDepth)&&validDraft.__referenceQuery!==referenceQuery){form.width=this.options.refWidth;form.depth=this.options.refDepth;}form.__referenceQuery=referenceQuery;const referenceCase=cases.find(r=>r.id===form.referenceCaseId);const count=Math.max(1,Math.min(4,Number(form.count)||2));const candidateIndexes=Array.from({length:count},(_,i)=>i);const selectedPlan=Math.min(this.data.selectedPlan,count-1);const config=V.config(view,c,form);require('./organization-views').decorate(view,c,form,config,this.options.record);require('./project-views').decorate(view,c,form,config);config.blocked=B.blockingStep(view,c);const activeCustomerTab=view==='progress'?2:[1].includes(Number(this.options.tab))?1:0;if(config.primary==='返回客户工作区'&&!Access.workflow('customer',c,false))config.primary='返回工作台';config.role=Access.roles[Access.context().role];config.canEditCustomer=Access.workflow('edit',c,true);config.canFollow=Access.workflow('follow',c,true);config.canWriteView=Access.workflow(view,c,true);if(['ai-config','ai-results','facade-config','facade-candidates','budget'].includes(view)&&!config.canWriteView)config.blocked={message:'请由客户负责业务人员或获授权协作者办理本客户方案。',target:'customer',label:'返回客户工作区'};config.large=!['inspiration','search','case','case-plan','ai-config','ai-results','result','facade-config','facade-candidates','facade-result'].includes(view);
  // Merge the two design steps for display while preserving the model lifecycle.
  const stages=B.STAGES.filter(stage=>stage!=='设计与预算'),progressInfo=view==='progress'?ProjectProgress.build(c,wx.getStorageSync(require('../../utils/storage-keys').engineering)||{},actor):null,journeyIndex=progressInfo?.journeyIndex??B.stageIndex(c),stageIndex=progressInfo?.engineering?(progressInfo.delivered?stages.length:stages.indexOf('施工阶段')):journeyIndex>2?journeyIndex-1:journeyIndex;
  for(const section of config.sections||[])for(const item of section.items||[])if(['steps','links','record-list'].includes(item.type))item.values=(item.values||[]).filter(step=>!step.view||['receipt-supplement','pay-supplement'].includes(step.view)||Access.workflow(step.view,c,false));config.sections=(config.sections||[]).map(section=>({...section,items:(section.items||[]).filter(item=>!['steps','links','record-list'].includes(item.type)||item.values?.length)})).filter(section=>section.items.length);if(!config.canWriteView){const visibility=require('../../utils/page-visibility');if(config.sections.some(section=>section.items.some(item=>visibility.controls.has(item.type)))){config.primary='';config.secondary='';}config.sections=visibility.readonly(config.sections);}config.sections=Files.displaySections(config.sections);const customer={...c,events:(c.events||[]).map(event=>({...event,title:Files.displayText(event.title) }))};this.setData(Object.assign({},config,{customer,form,fileNames:Files.displayNames(form),coordination:Coord.next(c),hasDesign:!!c.request,requirements:form.requirements||['三代同住','4间卧室','一层老人房'],engineeringStatus:B.engineeringStatus(c),stage:B.stage(c),stageIndex,stages,progressInfo,activeCustomerTab,customerInitial:c.name.slice(0,1),phone:c.phone,city:c.city.replace(/市$/,''),registered:c.status==='已登记',customerTabs:['概况','跟进','项目进度'],showSizeBlock:c.status==='已到店',candidateIndexes,planCandidates:candidateIndexes.map(index=>({index,images:Design.images(index,form.floors)})),selectedPlan,referenceCase,referenceImage:referenceCase?.floorPlans[0]?.image||B.latest(c.plans)?.images[0]||'/pages/workflow/assets/biz-plan-1.jpg',floorLabels:Array.from({length:Math.max(1,Math.min(6,Number(B.latest(c.plans)?.floors||form.floors)||2))},(_,i)=>(i+1)+'层'),currentPlan:B.latest(c.plans),currentFacade:B.latest(c.facades||[]),currentBudget:B.latest(c.budgets)?{...B.latest(c.budgets),bodyRows:B.latest(c.budgets).bodyRows||[{label:'主体工程（历史记录）',value:B.latest(c.budgets).main}],exteriorRows:B.latest(c.budgets).exteriorRows||[{label:'外装工程（历史记录）',value:B.latest(c.budgets).exterior}],priceVersion:B.latest(c.budgets).priceVersion||'历史记录'}:null,budgetPresentation:require('../../utils/budget-presentation').present(B.latest(c.budgets)),currentQuote:B.latest(c.quotes),currentDrawing:B.latest(c.drawings),currentDelivery:B.latest(c.deliverables),currentCopy:B.latest(c.copies),favorite:(wx.getStorageSync(require('../../utils/storage-keys').designFavoritesPrefix+actor.companyId+':'+actor.personId)||[]).includes(this.options.case||form.referenceCaseId||B.load().caseId),caseTab:view==='case-plan'?1:this.data.caseTab,selectedCase:cases.find(x=>x.id===(this.options.case||form.referenceCaseId||B.load().caseId))||cases[0],caseTabs:['立面效果',...(cases.find(x=>x.id===(this.options.case||form.referenceCaseId||B.load().caseId))||cases[0]).floorPlans.map(p=>p.floor+'层平面')],error:''}));this.filterCustomers();if(['inspiration','search'].includes(view))this.filterCases();
  if(view==='progress'){const limit=this.data.showAllProgressRecords?(this.data.progressRecordLimit||20):20;this.setData({progressRecordLimit:limit,visibleProgressRecords:this.data.showAllProgressRecords?(progressInfo?.records||[]).slice(0,limit):[]});}
 },
 filterCustomers(){if(this.data.platformMode){const kw=this.data.keyword,filter=this.data.filter,rows=this.data.customers||[];return this.setData({filteredCustomers:rows.filter(c=>((c.name||'')+String(c.phoneText||'')+c.searchText).includes(kw)&&(filter==='全部'||(filter==='待跟进'?c.status==='已登记':c.status===filter)))});}const state=B.load();const kw=this.data.keyword;const filter=this.data.filter;this.setData({filteredCustomers:state.customers.filter(c=>(c.name+c.phone+c.city).includes(kw)&&(filter==='全部'||(filter==='待跟进'?c.status==='已登记':c.status===filter)))});},
 searchCustomers(e){this.setData({keyword:e.detail.value.trim()});this.filterCustomers();},
 setFilter(e){this.setData({filter:e.currentTarget.dataset.value});this.filterCustomers();},
 chooseCustomer(e){const id=e.currentTarget.dataset.id;if(this.data.platformMode)return this.openView('customer',false,id);this.openView(this.options.destination||'customer',false,id);},
 deny(){this.setData({customer:null,sections:[],form:{},filteredCustomers:[],modal:''});wx.redirectTo({url:'/pages/account/index?view=access&content='+encodeURIComponent(V.titles[this.data.view]||'客户业务')});},
 go(e){const d=e.currentTarget.dataset;if(!d.view)return;if(['receipt-supplement','pay-supplement'].includes(d.view))return require('../../utils/page-navigation').open('/pages/admin/index?view='+d.view+'&id='+encodeURIComponent(d.record));this.openView(d.view,false,null,d.record||'');},
 navTap(e){const url=e.currentTarget.dataset.url;if(url)require('../../utils/page-navigation').open(url);},
 openView(view,replace=false,id,record=''){if(this.data.platformMode){if(view==='ai-facade')view='facade-config';if(!['customers','customer','arrival','transfer','requirement','budget','contract','delivery','progress'].includes(view)){require('../../utils/page-navigation').open('/pages/workflow/explore/index');return wx.showToast({title:'请在设计页办理',icon:'none'});}if(view==='arrival')view='customer';const target=id||this.id||'';if(view==='customers'&&!target)return this.platformBlocked('customers');return require('../../utils/page-navigation').open('/pages/workflow/index?view='+view+(view!=='customers'?'&id='+encodeURIComponent(target):''),{replace});}if(view==='ai-facade')view='facade-config';if(view==='customers'&&!B.customer(id||this.id))id=B.load().customers[0].id;const referenceQuery=['case','refWidth','refDepth'].filter(k=>this.options[k]).map(k=>'&'+k+'='+encodeURIComponent(this.options[k])).join('');const url='/pages/workflow/index?view='+view+'&id='+encodeURIComponent(id||this.id)+(record?'&record='+encodeURIComponent(record):'')+referenceQuery; require('../../utils/page-navigation').open(url,{replace});},
 openModal(name){if(this.data.platformMode){this.__cmdKeys={};if(name==='register')return this.setData({form:{name:'',phone:'',source:'视频号咨询',summary:''},modal:'register',error:''});if(name==='edit'){const c=this.data.customer;if(!c)return;return this.setData({form:{name:c.name,phone:c.phone,wechat:c.wechat,source:c.source,province:c.province,city:c.city,summary:c.summary},modal:'edit',error:''});}if(name==='follow'){if(!this.data.canFollow)return this.fail('当前岗位不能办理该客户');return this.setData({form:Object.assign({},this.data.form,{followContent:'',appointment:''}),modal:'follow',error:''});}return this.setData({modal:'',error:''});}
 if(name==='register'&&!Job.has(Access.context().role,'sales'))return this.fail('当前身份无权登记客户');if(name==='register')this.setData({form:Object.assign({},this.data.form,{name:'',phone:'',wechat:'',summary:''})});if(name==='edit'){const c=this.data.customer;this.setData({form:Object.assign({},this.data.form,{name:c.name,phone:c.phone,wechat:c.wechat,source:c.source,province:c.province,city:c.city,summary:c.summary})});}this.setData({modal:name,error:''});},
 modal(e){this.openModal(e.currentTarget.dataset.modal);},
 closeModal(){this.setData({modal:'',error:''});},
 noop(){},
 input(e){const key=e.currentTarget.dataset.key;this.setData({['form.'+key]:e.detail.value,error:''});this.saveDraft();},
 pick(e){const {key,options}=e.currentTarget.dataset;const values=e.currentTarget.dataset.values||this.data[options];this.setData({['form.'+key]:values?values[Number(e.detail.value)]:e.detail.value,error:''});if(key==='filterStyle'||key==='floorFilter')this.filterCases();this.saveDraft();},
 date(e){this.setData({['form.'+e.currentTarget.dataset.key]:e.detail.value,error:''});this.saveDraft();},
 select(e){const key=e.currentTarget.dataset.key;this.setData({['form.'+key]:e.currentTarget.dataset.value,error:''});if(key==='collabRole'){this.setData({'form.collaborator':'','form.collabAction':''});this.refresh(false);}this.saveDraft();},
 toggle(e){const key=e.currentTarget.dataset.key;this.setData({['form.'+key]:!this.data.form[key],error:''});this.saveDraft();},
 exteriorChange(e){this.setData({['form.exterior.'+e.currentTarget.dataset.key]:e.detail.value});this.saveDraft();},
 transferMode(e){this.setData({transferMode:Number(e.currentTarget.dataset.index)});},
 toggleCustomer(e){const id=e.currentTarget.dataset.id;const ids=this.data.selectedCustomers;const selected=ids.includes(id)?ids.filter(x=>x!==id):ids.concat(id);const flags={};selected.forEach(x=>{flags[x]=true;});this.setData({selectedCustomers:selected,selectedFlags:flags});},
 saveDraft(){if(this.data.platformMode){this.__cmdKeys={};if(this.data.view==='budget'&&this.data.budgetResult&&!this.data.budgetResult.stale)this.setData({'budgetResult.stale':true});return;}// API 态：草稿不落 demo 存储；表单变更即作废命令快照/标记上次报价
 if(!this.data.customer||this.data.modal==='register')return;const f=this.data.form;this.setData({paymentBlocked:!this.data.customer.firstPayment&&(!B.positive(f.amount)||!f.paymentDate||!String(f.transaction||'').trim()||!f.paymentFile||!f.verified)});try{const saved=B.write(this.id,c=>{c.drafts[B.draftKey(this.data.view)]=this.data.form;},'', 'draft:'+this.data.view,this.data.customer.revision||0);this.setData({'customer.revision':saved.revision});}catch(e){this.setData({error:'暂存失败，请重试，当前输入仍在页面中。'});}},
 customerTab(e){const i=Number(e.currentTarget.dataset.index);if(i===2){this.openView('progress',true);return;}if(this.data.view==='progress'){require('../../utils/page-navigation').open('/pages/workflow/index?view=customer&id='+encodeURIComponent(this.id)+(i?'&tab='+i:''),{replace:true});return;}this.options.tab=i;this.setData({activeCustomerTab:i});},
  toggleProgressRecords(){const show=!this.data.showAllProgressRecords;this.setData({showAllProgressRecords:show,progressRecordLimit:20,visibleProgressRecords:show?(this.data.progressInfo?.records||[]).slice(0,20):[]});},
  loadMoreProgressRecords(){const limit=(this.data.progressRecordLimit||20)+20;this.setData({progressRecordLimit:limit,visibleProgressRecords:(this.data.progressInfo?.records||[]).slice(0,limit)});},
 filterCases(){this.setData({filteredCases:Design.filter(this.data.form,this.data.caseKeyword||'',this.data.searched?this.data.searchTarget:null)});},searchCases(e){this.setData({caseKeyword:e.detail.value.trim()});this.filterCases();},
 applySearch(){if(!this.validateDimensions())return;const f=this.data.form;this.setData({searched:true,searchTarget:{width:Number(f.width),depth:Number(f.depth)},error:''});this.filterCases();},
 openCase(e){const selectedCase=cases.find(x=>x.id===e.currentTarget.dataset.id)||cases[0];this.setData({selectedCase});this.options.case=selectedCase.id;if(this.data.view==='search'){this.options.refWidth=this.data.form.width;this.options.refDepth=this.data.form.depth;}const state=B.load();state.caseId=selectedCase.id;B.persist(state);this.openView(this.data.view==='search'?'case-plan':'case');},
 caseTab(e){const index=Number(e.currentTarget.dataset.index);this.setData({caseTab:index,casePlanUnavailable:false});},
 favorite(){const key=require('../../utils/storage-keys').designFavoritesPrefix+Access.context().companyId+':'+Access.context().personId,ids=wx.getStorageSync(key)||[],id=this.data.selectedCase.id,next=ids.includes(id)?ids.filter(x=>x!==id):ids.concat(id);wx.setStorageSync(key,next);this.setData({favorite:next.includes(id)});wx.showToast({title:this.data.favorite?'已收藏':'已取消收藏',icon:'none'});},
 continueBudget(){if(this.update(x=>{x.status='跟进中';x.budgetSaved=true;},'参考预算回存客户'))this.openView('request');},
 planSelect(e){this.setData({selectedPlan:Number(e.currentTarget.dataset.index)});},
 floorTab(e){this.setData({floor:Number(e.currentTarget.dataset.index)});},
 requirement(e){const value=e.currentTarget.dataset.value;const requirements=this.data.requirements.filter(x=>x!==value);this.setData({requirements,'form.requirements':requirements});this.saveDraft();},
 addRequirement(){wx.showModal({title:'添加家庭需求',editable:true,placeholderText:'例如：独立书房',success:r=>{if(r.confirm&&r.content.trim()){const requirements=this.data.requirements.concat(r.content.trim());this.setData({requirements,'form.requirements':requirements});this.saveDraft();}}});},
 validateDimensions(){const f=this.data.form;if(!B.dimension(f.width)||!B.dimension(f.depth))return this.fail('面宽和进深须为3–40米，最多一位小数。');if(!Number.isInteger(Number(f.floors))||Number(f.floors)<1||Number(f.floors)>6)return this.fail('建筑层数须为1–6层整数。');return true;},
 fail,
 update(change,event,next){try{B.write(this.id,x=>{change(x);},event,this.data.view,this.data.customer.revision||0);this.setData({error:'',busy:false});if(next)this.openView(next,true);else this.refresh(false);return true;}catch(e){return this.fail(e.message||'保存失败，请保留当前页面后重试。');}},
 upload(e){const key=e.currentTarget.dataset.key;wx.showActionSheet({itemList:require('../../utils/sample-files').items(['选择本机文件','使用参考附件']),success:r=>{if(r.tapIndex===1){this.setFile(key,{name:key==='drawingFile'?'施工图_'+this.data.form.drawingVersion+'.pdf':key==='signedFile'?'建造合同_双方已签.pdf':'参考附件.pdf',sample:true});}else wx.chooseMessageFile({count:1,type:'file',success:res=>{const f=res.tempFiles[0];wx.saveFile({tempFilePath:f.path,success:s=>this.setFile(key,{name:f.name,path:s.savedFilePath,sample:false}),fail:()=>this.fail('文件未能持久保存，请重新选择。')});},fail:()=>this.fail('未选择文件，可重试或使用参考附件。')});}});},
 setFile(key,file){this.setData({['form.'+key]:file,['fileNames.'+key]:Files.displayFile(file).name,error:''});if(key==='drawingFile')this.setData({'form.uploadDate':B.date()});this.saveDraft();},
 previewFile(e){if(e.currentTarget.dataset.name==='暂无附件')return this.fail('该记录尚未留存附件。');const key=e.currentTarget.dataset.key;const c=this.data.customer;const file=Files.displayFile(e.currentTarget.dataset.file||this.data.form[key]||c[key]);if(file?.text){this.setData({preview:{name:file.name,description:file.text}});return;}if(!file)return this.fail('该项尚未留存文件，请在原单补齐。');if(file.path&&/\.(png|jpe?g|webp)$/i.test(file.name||file.path))return wx.previewImage({current:file.path,urls:[file.path]});if(file.path){wx.openDocument({filePath:file.path,showMenu:true,fail:()=>this.fail('文件无法读取，请重新补传。')});return;}this.setData({preview:{name:file.name||e.currentTarget.dataset.name||'参考附件',image:'/pages/workflow/assets/biz-formal-plan.jpg',description:'当前为参考附件预览，实际交接请核对所选文件内容。'}});},
 closePreview(){this.setData({preview:null});},
 previewImage(e){wx.previewImage({urls:[e.currentTarget.dataset.src],current:e.currentTarget.dataset.src});},
 exportQuote(e){const kind=e?.currentTarget?.dataset?.kind||'budget',c=B.customer(this.id),r=B.latest(c?.budgets);if(!r)return this.fail('请先保存本次报价');require('../../utils/page-navigation').open('/pages/workflow/quote-export/index?id='+encodeURIComponent(this.id)+'&kind='+kind+'&version='+r.version);},
 secondary(){if(this.data.view==='task-access')return this.taskAuthorization('revoke');if(['budget-result','ai-results'].includes(this.data.view))return this.openView(this.data.view==='budget-result'?'budget':'ai-config',true);if(this.data.view==='result')return this.saveAlbum();},
 saveAlbum(){const src=this.data.view==='facade-result'?(this.data.currentFacade?this.data.currentFacade.image:'/pages/workflow/assets/biz-facade-result.jpg'):this.data.currentPlan?this.data.currentPlan.images[this.data.floor]:this.data.floor?'/pages/workflow/assets/biz-plan-2.jpg':'/pages/workflow/assets/biz-plan-result.jpg';wx.getImageInfo({src,success:info=>wx.saveImageToPhotosAlbum({filePath:info.path,success:()=>wx.showToast({title:'已保存到相册'}),fail:()=>this.fail('未能保存相册，可查看大图后手动保存。')}),fail:()=>this.fail('无法读取方案图片，请重试。')});},
 submit(){if(this.data.busy)return;if(this.data.platformMode){const v=this.data.view;
  if(v==='transfer')return this.platformSubmitTransfer();
  if(v==='requirement')return this.platformSubmitRequirement();
  if(v==='budget')return this.platformSubmitQuote();
  if(v==='contract')return this.platformSubmitContract();
  return;}// API 态表单提交分派
 if(this.data.primaryUrl)return require('../../utils/page-navigation').open(this.data.primaryUrl);if(this.data.primaryView)return this.openView(this.data.primaryView);if(this.data.primary==='返回工作台')return wx.switchTab({url:'/pages/'+Access.home()+'/index'});
  const c=B.customer(this.id),f=this.data.form,v=this.data.view;if(!c)return this.fail('客户不存在');if(this.data.busy)return;this.setData({busy:true});
  if(v==='money-review')return this.update(x=>require('../../utils/commercial-approval').review(x,this.options.record||'quote',f),'老板审批本版金额');if(v==='task-access'){this.setData({busy:false});return this.taskAuthorization('grant');}
  if(v==='transfer'){const ids=this.data.transferMode?this.data.selectedCustomers:[this.id];try{B.reassign(ids,f.newOwner,f.transferReason);this.setData({busy:false});this.openView('customer',true);return;}catch(e){return this.fail(e.message||'转交失败，请重试。');}}
  if(v==='facade-config'){this.setData({busy:false});if(!Number.isInteger(Number(f.floors))||Number(f.floors)<1||Number(f.floors)>6)return this.fail('建筑层数须为1–6层整数。');if(!B.positive(f.facadeArea)||!/^\d+(\.\d{1,2})?$/.test(String(f.facadeArea))||Number(f.facadeArea)>10000000)return this.fail('请填写有效项目建筑面积，最多两位小数。');if(!this.data.facadeStyleOptions.includes(f.style)||!String(f.palette||'').trim())return this.fail('请选择具体建筑风格并填写主色调。');this.saveDraft();return this.openModal('generate');}
  if(v==='facade-candidates')return this.update(x=>{x.facades=x.facades||[];x.facades.push({version:x.facades.length+1,selected:this.data.selectedPlan,...Design.reference(f),planVersion:B.latest(x.plans)?.version||null,width:f.width,depth:f.depth,image:this.data.selectedPlan?'/pages/workflow/assets/biz-facade-2.jpg':'/pages/workflow/assets/biz-facade-1.jpg',style:f.style,palette:f.palette,area:f.facadeArea,floors:f.floors,date:B.date(),sample:true});},'选用并保存立面方案','facade-result');
  if(v==='facade-result'){this.setData({busy:false});return this.openView('budget');}
  if(v==='budget'){if(!this.validateDimensions()||!['area','footprint','roofArea'].every(k=>/^\d+(\.\d{1,2})?$/.test(String(f[k]))&&B.positive(f[k])&&Number(f[k])<=10000000)||!f.budgetChecked)return this.fail('请核对建筑面积、占地和屋顶面积。');return this.update(x=>{const version=x.budgets.length+1;const area=Number(f.area),cost=Design.budget(f);if(!x.request&&!x.contract)Object.assign(x,{width:f.width,depth:f.depth,floors:f.floors});x.budgets.push({version,...cost,area,...Design.reference(f),facadeVersion:B.latest(x.facades||[])?.version||null,planVersion:B.latest(x.plans)?B.latest(x.plans).version:null,parameters:B.clone(f),date:B.date()});},'完成参考预算测算','budget-result');}
  if(v==='budget-result')return this.update(x=>{x.status='跟进中';x.budgetSaved=true;},'参考预算回存客户',c.request?'delivery':'request');
  if(v==='ai-config'||v==='ai-facade'){this.setData({busy:false});if(!this.validateDimensions())return;this.saveDraft();return this.openModal('generate');}
  if(v==='ai-results')return this.update(x=>{const version=x.plans.length+1;x.plans.push({version,selected:this.data.selectedPlan,width:f.width,depth:f.depth,floors:f.floors,requirements:this.data.requirements,...Design.reference(f),images:Design.images(this.data.selectedPlan,f.floors),date:B.date(),sample:true});x.width=f.width;x.depth=f.depth;x.floors=f.floors;x.status='跟进中';},'选用并保存平面方案','result');
  if(v==='result'){this.setData({busy:false});return this.openView('budget');}
  this.setData({busy:false});this.openView('customer',true);
 },
 taskAuthorization(action){try{const c=this.data.customer,a=Access.context(),module=a.orgType==='headquarters'?'design':a.role==='project-manager'?'engineering':'business',f=this.data.form;if(action==='revoke'){const grant=(c.collaborations||[]).find(g=>g.id===f.grantId);B.authorize(this.id,module,'revoke',{grantId:grant?.id},c.revision||0);}else{const role=module==='engineering'?'engineer':'sales',staffId=Access.staffIdFor(f.collaborator,a.companyId,role);B.authorize(this.id,module,'grant',{staffId,role,actions:[Object.keys(require('./task-views').names).find(k=>require('./task-views').names[k]===f.collabAction)||f.collabAction],expiresAt:(f.expiresAt||'')+'T23:59:59+08:00'},c.revision||0);}this.refresh(false);}catch(e){this.fail(e.message);}},
 saveModal(){const f=this.data.form,c=this.data.customer,modal=this.data.modal;
  if(this.data.platformMode){if(this.data.busy)return;if(modal==='register')return this.platformSubmitRegister();if(modal==='edit')return this.platformSubmitEdit();if(modal==='follow')return this.platformSubmitFollow();return;}
  if(modal==='generate'){this.setData({modal:''});this.openView(this.data.view==='facade-config'?'facade-candidates':'ai-results',true);return;}
  if(modal==='register'||modal==='edit'){if(!f.name.trim()||(!f.phone.trim()&&!f.wechat.trim()))return this.fail('请填写客户称呼，手机号或微信号至少填一项。');if(f.phone&&!/^1\d{10}$/.test(f.phone)&&!(modal==='edit'&&f.phone===c.phone))return this.fail('请输入11位有效手机号。');const values={name:f.name.trim(),phone:f.phone.trim(),wechat:f.wechat.trim(),source:f.source,province:f.province,city:f.city,summary:f.summary.trim()};try{if(modal==='register'){const saved=B.add(values);this.openView('customer',true,saved.id);}else{B.write(this.id,x=>Object.assign(x,values),'更新客户资料');this.closeModal();this.refresh(false);}}catch(e){this.fail('客户保存失败，请重试。');}return;}
  if(modal==='follow'){if(!f.followContent.trim()||!f.appointment)return this.fail('请填写沟通内容及下次安排时间。');this.update(x=>{x.follows.push({method:f.method,result:f.method==='到店'?'已到店':f.followResult,author:x.owner||'我',content:f.followContent,next:f.next,appointment:f.appointment,date:B.date()});x.status=f.method==='到店'?'已到店':'跟进中';},'记录客户跟进');this.closeModal();return;}
 },
});

