const S=require('../design-session'),D=require('../design-adapter'),B=require('../../../utils/business'),X=require('../../../utils/access'),cases=require('../../../utils/design-cases');
const titles={inspiration:'灵感库',search:'平面检索',case:'灵感详情','ai-config':'AI平面','ai-facade':'AI立面',budget:'快速预算','plan-result':'平面方案','facade-result':'立面方案','budget-result':'预算结果'};
Page({data:{view:'inspiration',title:'灵感库',kind:'plan',form:{},keyword:'',styles:['全部风格','新中式','现代简约'],filterStyle:'全部风格',floorFilters:D.labels,floorFilter:'全部层数',filteredCases:cases,caseTab:0,floor:0,caseItem:cases[0],caseImage:cases[0].image,cases,styleOptions:['新中式','现代简约'],countOptions:['1','2','4'],resolutions:['2K','4K'],orientations:['南','东','西','北'],foundations:['独立基础','条形基础','桩基础'],structures:['框架结构','砖混结构'],roofs:['坡屋顶','平屋顶'],exteriors:[{key:'window',label:'门窗'},{key:'paint',label:'墙漆'},{key:'stone',label:'文化石'},{key:'light',label:'灯光'},{key:'waterproof',label:'防水'},{key:'lightning',label:'防雷'},{key:'insurance',label:'保险'}],busy:false,error:'',sheet:false,pickingRef:'',result:null,saved:null,platformMode:false,platformErr:'',platCases:[],platStyles:[],platFilter:{},platCursor:'',platLoading:false,platCase:null,platCaseImages:[],platCaseFav:false,platResult:null,platResultVersionId:'',platBudget:null,platRule:null,platPolling:false,platPollTip:'',saleDesign:false,materialOptions:[['WHITE_STUCCO','白墙涂料'],['GREY_STONE','灰石'],['WHITE_COAT','白色涂层']],colorOptions:[['DEEP_WOOD','深木'],['WARM_GREY','暖灰'],['BLACK','黑']],orientationPlatOptions:[['PORTRAIT','竖图'],['LANDSCAPE','横图']],structurePlatOptions:[['BRICK','砖混'],['FRAME','框架'],['STEEL','钢结构']],roofPlatOptions:[['GABLE_ROOF','坡屋顶'],['FLAT_ROOF','平屋顶']],gradePlatOptions:[['A','A 档'],['B','B 档'],['C','C 档']],saveCustomers:[]},
 onLoad(o){this.options=o||{};this.viewHistory=[];
  // 双体系分流（首个 demo 调用之前）：token+design:use 三岗 → API 态
  const post=(()=>{try{const auth=require('../../../api/auth');const http=require('../../../api/http');return http.token()?auth.currentApiPost():null;}catch(e){return null;}})();
  const PFD=require('../../../utils/platform-design');
  const http=(()=>{try{return require('../../../api/http');}catch(e){return null;}})();
  if(PFD.pageMode(http?http.token():'',post)==='api'){
   this.__post=post;this.__postKey=String(post.postId);this.__http=http;
   this.platformMode=true;// 实例属性：handler 分流依据（data.platformMode 供 wxml）
   require('./platform-bridge')(this,{PF:PFD,http,api:require('../../../api/franchise/design'),S});
   this.setData({platformMode:true});
   this.platOnLoadInit(o);return;}
  try{this.scope=S.key();this.setData({caseItem:cases.find(c=>c.id===o.case)||cases[0]});this.show(titles[o.view]?o.view:'inspiration');}catch(e){wx.redirectTo({url:'/pages/account/index?view=login'});}},
 platOnLoadInit(o){
  const v=titles[o.view]?o.view:'inspiration';
  const kind=v.startsWith('facade')||v==='ai-facade'?'facade':v.startsWith('budget')||v==='budget'?'budget':'plan';
  let platForm={};try{platForm=S.read().forms[kind]||{};}catch(e){platForm={};}// API 用户无 demo 会话时 S 不可用 → 空表单
  this.setData({view:v,title:titles[v]||'灵感库',kind,form:platForm,platCases:[],platStyles:[],platFilter:{},platCursor:''});
  if(['inspiration','search'].includes(v))this.platLoadCases(true);
  if(v==='budget')this.platLoadRule();
 },
 onUnload(){if(this.platOnUnload)this.platOnUnload();},
 onShow(){if(this.platformMode){const post=(()=>{try{const auth=require('../../../api/auth');const http=require('../../../api/http');return http.token()?auth.currentApiPost():null;}catch(e){return null;}})();if(!post||String(post.postId)!==String(this.__postKey||'')){this.platTeardown();return;}return;}
  if(!this.scope)return;try{if(S.key()!==this.scope)throw Error('身份已切换');}catch(_){this.setData({result:null,sheet:false,form:{},filteredCases:[]});wx.redirectTo({url:'/pages/account/index?view=login'});}},
 run(fn){if(this.platformMode){this.setData({error:''});try{return fn();}catch(e){this.setData({error:e.message});return null;}}try{if(S.key()!==this.scope)throw Error('身份已切换，请重新进入');this.setData({error:''});return fn();}catch(e){this.setData({error:e.message});return null;}},
 adjustBudget(){this.show('budget');},
 show(view,restoring=false){if(!restoring&&this.data.view!==view&&this.data.form&&Object.keys(this.data.form).length){this.viewHistory=(this.viewHistory||[]).concat([{view:this.data.view,state:Object.fromEntries(['keyword','filterStyle','floorFilter','caseTab','caseItem','floor','pickingRef','searchWidth','searchDepth'].map(k=>[k,this.data[k]])),scrollTop:this.__navigationScrollTop||0}]).slice(-20);}const kind=view.startsWith('facade')||view==='ai-facade'?'facade':view.startsWith('budget')||view==='budget'?'budget':'plan',state=S.read(),result=state[kind];if(view.endsWith('-result')&&!result)view=kind==='plan'?'ai-config':kind==='facade'?'ai-facade':'budget';const saved=state.lastSaved?.kind===kind&&state.lastSaved.outputId===result?.id?state.lastSaved:null;this.setData({view,title:titles[view],kind,form:state.forms[kind],result,saved,budgetPresentation:require('../../../utils/budget-presentation').present(kind==='budget'&&saved?B.customer(saved.customerId)?.budgets.find(b=>b.explorationId===saved.outputId)||result:result),sourcePlanRef:state.facadeSourcePlan?.id||'',floor:0,error:'',sheet:false});if(['inspiration','search'].includes(view))this.filterCases();if(view==='case')this.renderCase();},
 onPageScroll(e){this.__navigationScrollTop=e.scrollTop;},
 back(){return require('../../../utils/page-navigation').back();},
 navigationBack(){const prev=(this.viewHistory||[]).pop();if(prev){this.show(prev.view,true);this.setData(prev.state);if(['inspiration','search'].includes(prev.view))this.filterCases();if(prev.view==='case')this.renderCase();return wx.pageScrollTo({scrollTop:prev.scrollTop,duration:0});}if(this.data.view.endsWith('-result'))return this.show(this.data.kind==='plan'?'ai-config':this.data.kind==='facade'?'ai-facade':'budget',true);if(this.data.view==='case')return this.show('inspiration',true);return require('../../../utils/page-navigation').back({fallback:'/pages/design/index',skipLocal:true});},
 go(e){if(this.platformMode)return this.run(()=>this.platShow(e.currentTarget.dataset.view));this.run(()=>this.show(e.currentTarget.dataset.view));},
 input(e){if(this.platformMode)return this.run(()=>this.platFormInput(e));this.run(()=>{const patch={[e.currentTarget.dataset.key]:e.detail.value};S.form(this.data.kind,patch);this.setData({form:S.read().forms[this.data.kind]});});},
 pick(e){if(this.platformMode)return this.run(()=>this.platFormPick(e));this.run(()=>{const {key,list}=e.currentTarget.dataset;S.form(this.data.kind,{[key]:this.data[list][Number(e.detail.value)]});this.setData({form:S.read().forms[this.data.kind]});});},
 toggleExterior(e){this.run(()=>{const exterior={...this.data.form.exterior},key=e.currentTarget.dataset.key;exterior[key]=!exterior[key];S.form('budget',{exterior});this.setData({form:S.read().forms.budget});});},
 searchInput(e){if(this.platformMode)return this.platSearchInput(e);this.setData({keyword:e.detail.value});this.filterCases();},
 filterPick(e){if(this.platformMode)return this.platFilterPick(e);this.setData({[e.currentTarget.dataset.key]:this.data[e.currentTarget.dataset.list][Number(e.detail.value)]});this.filterCases();},
 searchDimension(e){if(this.platformMode)return this.platSearchInput(e);this.setData({[e.currentTarget.dataset.key]:e.detail.value});},
 filterCases(){const f=this.data,target=this.data.view==='search'&&f.searchWidth&&f.searchDepth?{width:Number(f.searchWidth),depth:Number(f.searchDepth)}:null;this.setData({filteredCases:D.filter(f,f.keyword,target)});},
 searchPlans(){if(this.platformMode)return this.platSearch();this.run(()=>{if(!B.dimension(this.data.searchWidth)||!B.dimension(this.data.searchDepth))throw Error('请填写有效的开间与进深');this.setData({error:''});this.filterCases();});},
 openSearch(){const f=S.read().forms.plan;this.setData({searchWidth:f.width,searchDepth:f.depth});this.show('search');},
 openCase(e){if(this.platformMode)return this.platOpenCase(e);const c=cases.find(c=>c.id===e.currentTarget.dataset.id);if(!c)return;this.caseOrigin=this.data.view;this.setData({caseItem:c,caseTab:0});this.show('case');},
 renderCase(){const c=this.data.caseItem,a=X.context(),favorites=wx.getStorageSync(require('../../../utils/storage-keys').designFavoritesPrefix+a.companyId+':'+a.personId)||[];this.setData({caseTabs:['立面效果',...c.floorPlans.map(p=>p.floor+'层平面')],caseImage:this.data.caseTab?c.floorPlans[this.data.caseTab-1].image:c.image,favorite:favorites.includes(c.id)});},
 caseTab(e){if(this.platformMode)return this.platCaseTab(e);this.setData({caseTab:Number(e.currentTarget.dataset.index)});this.renderCase();},
 favorite(){if(this.platformMode)return this.platFavorite();this.run(()=>{const a=X.context(),key=require('../../../utils/storage-keys').designFavoritesPrefix+a.companyId+':'+a.personId,old=wx.getStorageSync(key)||[],id=this.data.caseItem.id;wx.setStorageSync(key,old.includes(id)?old.filter(x=>x!==id):old.concat(id));this.renderCase();});},
 chooseReference(){this.show('inspiration');this.setData({pickingRef:'facade'});},
 useCase(e){if(this.platformMode)return this.platUseCase(e);this.run(()=>{const kind=this.data.pickingRef||e.currentTarget.dataset.kind||'plan';S.useCase(this.data.caseItem.id,kind);if(this.caseOrigin==='search'){S.form(kind,{width:this.data.searchWidth,depth:this.data.searchDepth});}this.setData({pickingRef:''});this.show(kind==='plan'?'ai-config':'ai-facade');});},
 generate(){if(this.platformMode)return this.run(()=>this.platGenerate(this.data.kind));this.run(()=>{this.setData({busy:true});try{S.generate(this.data.kind);this.show(this.data.kind+'-result');}finally{this.setData({busy:false});}});},
 selectCandidate(e){if(this.platformMode)return this.run(()=>this.platSelectCandidate(e));this.run(()=>{S.choose(this.data.kind,Number(e.currentTarget.dataset.index));this.setData({result:S.read()[this.data.kind],floor:0});});},
 selectFloor(e){this.setData({floor:Number(e.currentTarget.dataset.index)});},
 continueFacade(){if(this.platformMode)return this.run(()=>this.platShow('ai-facade'));this.run(()=>{S.continueFacade();this.show('ai-facade');});},
 continueBudget(){if(this.platformMode)return this.run(()=>{if(this.data.platResultVersionId)this.setData({platResultVersionId:this.data.platResultVersionId});this.platShow('budget');});this.run(()=>{S.continueBudget(this.data.kind);this.show('budget');});},
 calculate(){if(this.platformMode)return this.run(()=>this.platCalculate());this.run(()=>{S.estimate();this.show('budget-result');});},
 preview(e){const image=e.currentTarget.dataset.image;if(image)wx.previewImage({current:image,urls:[image]});},
 save(){if(this.platformMode)return this.run(()=>this.platSave(this.data.kind||'plan'));this.run(()=>{const rows=S.customers(this.data.kind).map(c=>({id:c.id,name:c.name,city:c.city,revision:c.revision||0}));this.setData({sheet:true,saveCustomers:rows});});},
 closeSheet(){this.setData({sheet:false});},noop(){},
 saveCustomer(e){if(this.platformMode)return this.run(()=>this.platSaveCustomer(e));this.run(()=>{const c=this.data.saveCustomers.find(c=>c.id===e.currentTarget.dataset.id);if(!c)throw Error('请选择有效客户');S.save(this.data.kind,c.id,c.revision);this.show(this.data.view);wx.showToast({title:'已保存到客户',icon:'success'});});},
 viewCustomer(){if(this.data.saved)require('../../../utils/page-navigation').open('/pages/workflow/index?view=customer&id='+encodeURIComponent(this.data.saved.customerId));},
 exportQuote(){this.run(()=>{const saved=this.data.saved;if(!saved)return this.save();const c=B.customer(saved.customerId),r=B.latest(c?.budgets);if(!r||r.explorationId!==saved.outputId)throw Error('客户预算已更新，请在客户档案核对后导出');require('../../../utils/page-navigation').open('/pages/workflow/quote-export/index?id='+encodeURIComponent(c.id)+'&kind=budget&version='+r.version);});},
 continueFormal(){this.run(()=>{const saved=this.data.saved;if(!saved)return;const c=B.customer(saved.customerId);if(!c||B.latest(c.budgets)?.explorationId!==saved.outputId)throw Error('客户预算已更新，请在客户档案核对后继续');B.write(c.id,n=>{n.status='跟进中';n.budgetSaved=true;},'参考预算发起设计委托','budget-result',c.revision);require('../../../utils/page-navigation').open('/pages/workflow/index?view='+(c.request?'delivery':'request')+'&id='+encodeURIComponent(c.id));});},
 register(){this.setData({sheet:false});require('../../../utils/page-navigation').open('/pages/workflow/index?view=customers&modal=register');}
});
