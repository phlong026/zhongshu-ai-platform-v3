const navigate=options=>require('../../../utils/page-navigation').open(options.url);
const P=require('../../../utils/platform-engineering'); // 工程域 API 门（在线判定/列表/新建；本地回落已下线）
const Access=require('../../../utils/access'),Job=require('../../../utils/organization');
const {routeValue}=require('../../../utils/view-container');
Page({
 data:{ navigationIndex:8,filter:'全部',keyword:'',destination:'',selectedLabel:'',mode:'',cards:[],visibleCards:[],expandedProjects:false,incoming:[],tasks:[],error:'',loading:true,filters:['全部','在建','待交付'],creating:false,createForm:{name:'',customer:'',city:'',phone:'',floors:'2',area:'',parentIndex:0},parentOptions:[],busy:false},
 onLoad(o={}){const labels={prepare:'开工准备',schedule:'施工计划',documents:'项目资料',payments:'项目款项',site:'现场记录',inspection:'节点验收',materials:'材料'};const destination=labels[o.destination]?o.destination:'';this.setData({filter:routeValue(o.filter)||(destination==='prepare'?'待开工':'全部'),destination,selectedLabel:labels[destination]||'',navigationIndex:destination==='materials'?9:8,mode:o.mode==='tasks'?'tasks':''});},
 restoreNavigationState(){this.refresh();},
 onShow(){this.__disposed=false;this.__inactive=false;const h=require('../../../api/http'),identity=String(h.currentPostId())+':'+h.sessionGeneration();if(this.__identity!==identity){this.__identity=identity;this.__platRows=[];this.setData({cards:[],visibleCards:[],expandedProjects:false,incoming:[],tasks:[],creating:false,createForm:{name:'',customer:'',city:'',phone:'',floors:'2',area:'',parentIndex:0},canCreate:false,readOnly:true,busy:false,error:'',loading:true});}this.refresh();
  },
 onHide(){this.__inactive=true;},
 onUnload(){this.__disposed=true;},
 refresh(){this.refreshOnline();}, // 本地快照分支已删除：无条件在线拉取，失败显示错误态
 search(e){this.setData({keyword:e.detail.value,expandedProjects:false});this.applyProjectFilters();},clear(){this.setData({keyword:'',expandedProjects:false});this.applyProjectFilters();},
 filter(e){this.setData({filter:e.currentTarget.dataset.value,mode:'',expandedProjects:false});this.applyProjectFilters();},
 more(){const itemList=['待接收','待开工','已交付','已归档']; // 恒为固定四项（「待验收」为本地口径，随本地分支下线）
  wx.showActionSheet({itemList,success:r=>{this.setData({filter:itemList[r.tapIndex],mode:'',expandedProjects:false});this.applyProjectFilters();}});},
 showProjects(){this.setData({mode:'',filter:'全部',keyword:'',expandedProjects:false});this.applyProjectFilters();},
 applyProjectFilters(){const keyword=this.data.keyword.trim().toLowerCase();const cards=P.listCards(this.__platRows||[]).filter(c=>P.statusMatches(c.statusRaw||c.status,this.data.filter)&&(!keyword||[c.name,c.customer,c.city].some(value=>String(value||'').toLowerCase().includes(keyword))));this.setData({cards,visibleCards:this.data.expandedProjects?cards:cards.slice(0,1),allCount:(this.__platRows||[]).length,moreSelected:!this.data.filters.includes(this.data.filter)});},
 toggleProjects(){this.setData({expandedProjects:!this.data.expandedProjects});this.applyProjectFilters();},
 open(e){const d=e.currentTarget.dataset;if(d.url){navigate({url:d.url});return;}const p=this.data.cards.find(p=>p.id===d.id);if(!p)return this.refresh();
  const view=({prepare:'prepare',schedule:'project',documents:'documents',payments:'payments',site:'node',inspection:'node',node:'node',materials:'materials'})[d.view||this.data.destination]||'overview';
  navigate({url:'/pages/build/record/index?id='+encodeURIComponent(String(p.id))+'&view='+view});},
 openCreate(){if(!this.data.canCreate)return;
  // 父项目选项固定在线：listCards(在线列表缓存)，本地演示快照分支已下线
  const projects=P.listCards(this.__platRows||[]).map(p=>({id:p.id,name:p.name}));
  this.setData({creating:true,error:'',parentOptions:[{id:'',name:'不关联，新建独立项目'}].concat(projects),createForm:{name:'',customer:'',city:'',phone:'',floors:'2',area:'',parentIndex:0}});},
 cancelCreate(){this.setData({creating:false,error:''});},
 createInput(e){const d=e.currentTarget.dataset;this.setData({['createForm.'+d.key]:e.detail.value,error:''});},
 pickCreateFloors(e){this.setData({'createForm.floors':['1','2','3','4','5'][Number(e.detail.value)]});},
 pickCreateParent(e){this.setData({'createForm.parentIndex':Number(e.detail.value)});},
 submitCreate(){if(!P.online()){wx.showToast({title:'请先登录后端账号',icon:'none'});return;}return this.submitCreateOnline();},
 async refreshOnline(){ // API 态：工程列表在线（工程岗仅本人/财务线全量）；失败显示错误态，不回落本地演示数据
  const h=require('../../../api/http'),current=h.requestGuard('engineering-projects'),post=h.currentPostId();
  const alive=()=>current()&&!this.__disposed&&!this.__inactive&&String(h.currentPostId())===String(post);
  try{
   const rows=await P.api.projects.page();if(!alive())return;this.__platRows=rows;
   const a=Access.context();
   // 页头只读导航字段：后端任职 → apiContext → navigation（utils/workbench-nav 同款用法）
   const post=(()=>{try{return P.online()?require('../../../api/auth').currentApiPost():null;}catch(e){return null;}})();
   const navCtx=post?Job.apiContext(post):null;
   const readOnly=navCtx?['boss','owner'].includes(navCtx.role):Access.isBoss(a);
   const tabKey=readOnly?'owner-engineering':this.data.destination==='materials'?'materials':'projects';
   const navigationIndex=((navCtx&&Job.roles[navCtx.role]?Job.navigation(navCtx):Job.navigation(a)).tabs.find(tab=>tab.key===tabKey)?.index)??8;
   this.setData({allowed:true,readOnly,navigationIndex,incoming:[],tasks:[],taskCount:0,error:'',loading:false,platEng:true,
    // 新建入口对齐 856 write=engineer/project-manager 口径 + v3 全局 isBoss 惯例
    canCreate:!!navCtx&&(['boss','owner'].includes(navCtx.role)||Job.has(navCtx.role,'engineer'))});
   this.applyProjectFilters();
  }catch(e){if(!alive())return; // 失败：错误态+空列表（不白屏、不回落本地数据），平台错误以 toast 保留
   const msg=P.errorText(e&&e.data?e.data:e);
   this.__platRows=[];
   this.setData({error:msg,cards:[],visibleCards:[],incoming:[],tasks:[],allCount:0,taskCount:0,loading:false});
   wx.showToast({title:msg,icon:'none'});
  }
 },
 async submitCreateOnline(){ // API 态新建（查重 1_115_000_008 → 文案提示）
  if(this.data.busy)return;const h=require('../../../api/http'),current=h.sessionGuard(),post=h.currentPostId();
  const alive=()=>current()&&!this.__disposed&&!this.__inactive&&String(h.currentPostId())===String(post);
  this.setData({busy:true});
  try{
   const f=this.data.createForm,parent=this.data.parentOptions[Number(f.parentIndex)||0];
   const created=await P.api.projects.create({name:f.name,customer:f.customer,city:f.city,phone:f.phone||undefined,floors:Number(f.floors)||2,area:f.area?Number(f.area):undefined,parentProjectId:parent&&parent.id?String(parent.id):undefined});
   if(!alive())return;this.setData({creating:false,busy:false});
   wx.showToast({title:'项目已创建',icon:'success'});
   navigate({url:'/pages/build/record/index?id='+encodeURIComponent(String(created.id))+'&view=prepare'});
  }catch(e){if(!alive())return;this.setData({busy:false});wx.showToast({title:P.errorText(e.data||e),icon:'none'});}
 },
 home(){wx.switchTab({url:'/pages/engineering/index'});}
});
