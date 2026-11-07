// 设计工作流（灵感库/AI 平面/AI 立面/快速预算）：纯平台态（token+design:use 三岗），demo 分支已移除。
const titles={inspiration:'灵感库',search:'平面检索',case:'灵感详情','ai-config':'AI平面','ai-facade':'AI立面',budget:'快速预算','plan-result':'平面方案','facade-result':'立面方案','budget-result':'预算结果'};
Page({data:{view:'inspiration',title:'灵感库',kind:'plan',form:{},keyword:'',styles:['全部风格','新中式','现代简约'],filterStyle:'全部风格',floorFilters:['全部层数','一层','二层','三层'],floorFilter:'全部层数',caseTab:0,floor:0,styleOptions:['新中式','现代简约'],countOptions:['1','2','4'],resolutions:['2K','4K'],busy:false,error:'',sheet:false,pickingRef:'',platformMode:false,platformErr:'',platCases:[],platStyles:[],platFilter:{},platCursor:'',platLoading:false,platCase:null,platCaseImages:[],platCaseFav:false,platResult:null,platResultVersionId:'',platBudget:null,platRule:null,platPolling:false,platPollTip:'',saleDesign:false,materialOptions:[['WHITE_STUCCO','白墙涂料'],['GREY_STONE','灰石'],['WHITE_COAT','白色涂层']],colorOptions:[['DEEP_WOOD','深木'],['WARM_GREY','暖灰'],['BLACK','黑']],orientationPlatOptions:[['PORTRAIT','竖图'],['LANDSCAPE','横图']],structurePlatOptions:[['BRICK','砖混'],['FRAME','框架'],['STEEL','钢结构']],roofPlatOptions:['坡屋顶','平屋顶'],gradePlatOptions:[['A','A 档'],['B','B 档'],['C','C 档']],saveCustomers:[]},
 onLoad(o){this.options=o||{};
  // 纯平台态门禁：token+design:use 三岗 → API 态；无会话回登录页（不进本地 demo）
  const http=(()=>{try{return require('../../../api/http');}catch(e){return null;}})();
  const post=(()=>{try{const auth=require('../../../api/auth');return http&&http.token()?auth.currentApiPost():null;}catch(e){return null;}})();
  const PFD=require('../../../utils/platform-design');
  if(!post||PFD.pageMode(http?http.token():'',post)!=='api'){const q=o&&o.view?'?view='+encodeURIComponent(o.view):'';return wx.redirectTo({url:'/pages/account/index?view=login&return='+encodeURIComponent('/pages/workflow/explore/index'+q)});}
  this.__post=post;this.__postKey=String(post.postId);this.__http=http;
  this.platformMode=true;// 实例属性：handler 分流依据（data.platformMode 供 wxml）
  require('./platform-bridge')(this,{PF:PFD,http,api:require('../../../api/franchise/design')});
  this.setData({platformMode:true});
  this.platOnLoadInit(o);
 },
 platOnLoadInit(o){
  const v=titles[o.view]?o.view:'inspiration';
  const kind=v.startsWith('facade')||v==='ai-facade'?'facade':v.startsWith('budget')||v==='budget'?'budget':'plan';
  this.setData({view:v,title:titles[v]||'灵感库',kind,form:{},platCases:[],platStyles:[],platFilter:{},platCursor:''});// 表单读写全在页面 state（demo 会话预填随 S 层移除）
  if(['inspiration','search'].includes(v))this.platLoadCases(true);
  if(v==='budget')this.platLoadRule();
 },
 onUnload(){if(this.platOnUnload)this.platOnUnload();},
 onShow(){const post=(()=>{try{const auth=require('../../../api/auth');const http=require('../../../api/http');return http.token()?auth.currentApiPost():null;}catch(e){return null;}})();if(!post||String(post.postId)!==String(this.__postKey||'')){this.platTeardown();return;}},
 run(fn){this.setData({error:''});try{return fn();}catch(e){this.setData({error:e.message});return null;}},
 onPageScroll(e){this.__navigationScrollTop=e.scrollTop;},
 back(){return require('../../../utils/page-navigation').back();},
 go(e){this.run(()=>this.platShow(e.currentTarget.dataset.view));},
 input(e){this.run(()=>this.platFormInput(e));},
 pick(e){this.run(()=>this.platFormPick(e));},
 searchInput(e){this.platSearchInput(e);},
 filterPick(e){this.platFilterPick(e);},
 searchDimension(e){this.platSearchInput(e);},
 searchPlans(){this.platSearch();},
 openSearch(){this.platShow('search');},
 openCase(e){this.platOpenCase(e);},
 caseTab(e){this.platCaseTab(e);},
 favorite(){this.platFavorite();},
 chooseReference(){this.platShow('inspiration');this.setData({pickingRef:'facade'});},
 useCase(e){this.platUseCase(e);},
 generate(){this.run(()=>this.platGenerate(this.data.kind));},
 selectCandidate(e){this.run(()=>this.platSelectCandidate(e));},
 selectFloor(e){this.setData({floor:Number(e.currentTarget.dataset.index)});},
 continueFacade(){this.run(()=>this.platShow('ai-facade'));},
 continueBudget(){this.run(()=>this.platShow('budget'));},
 calculate(){this.run(()=>this.platCalculate());},
 preview(e){const image=e.currentTarget.dataset.image;if(image)wx.previewImage({current:image,urls:[image]});},
 save(){this.run(()=>this.platSave(this.data.kind||'plan'));},
 closeSheet(){this.setData({sheet:false});},noop(){},
 saveCustomer(e){this.run(()=>this.platSaveCustomer(e));},
 viewCustomer(){const saved=this.data.platSaved;if(saved)require('../../../utils/page-navigation').open('/pages/workflow/index?view=customer&id='+encodeURIComponent(saved.customerId));},
 budgetShowForm(){this.platShow('budget');},
 register(){this.setData({sheet:false});require('../../../utils/page-navigation').open('/pages/workflow/index?view=customers&modal=register');}
});
