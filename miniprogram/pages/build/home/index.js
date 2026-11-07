const navigate=options=>require('../../../utils/page-navigation').open(options.url);
const P=require('../../../utils/platform-engineering');
const auth=require('../../../api/auth');
const PTPage=require('../../../utils/platform-todos-page');
// 工程工作台首页（纯 API 态）：项目统计/最近项目来自 engineering projects 接口，待办走平台待办中心；
// 本地账簿视图（build/workbench.home）已随生产剔除下线。
Page({data:{eng:null,error:'',platformTodos:null},onLoad(){PTPage.init(this);},onShow(){
  if(!auth.currentApiPost()||!P.online()){wx.switchTab({url:'/pages/profile/index'});return;}
  this.loadOnline();
  PTPage.refresh(this,false);
},
loadOnline(){
  P.api.projects.page().then(rows=>{
    const cards=P.listCards(rows);
    const stat=(key,label)=>({key,label,count:cards.filter(c=>c.statusKey===key).length});
    const recent=cards[0]||null;
    this.setData({error:'',eng:{
      name:(auth.currentApiPost()||{}).orgName||'工程工作台',
      date:'',
      stats:[stat('IN_PROGRESS','在建'),stat('PENDING_START','待开工'),stat('READY_DELIVER','待交付'),{key:'ALL',label:'全部项目',count:cards.length}],
      recent:recent?{name:recent.name,stage:recent.statusText||'',image:recent.image||''}:null,
      waiting:[]
    }});
  }).catch(e=>{
    this.setData({eng:null,error:(e&&e.message)||'工程数据加载失败，请下拉重试'});
  });
},
retryPlatformTodos(){PTPage.retry(this);},openPlatformTodo(e){PTPage.open(this,e);},openStat(e){this.openEngineering({currentTarget:{dataset:{filter:e.detail.filter==='ALL'?'':e.detail.filter,action:e.detail.filter}}});},
 openEngineering(e){const d=e.currentTarget.dataset,urls={projects:'/pages/build/projects/index',prepare:'/pages/build/projects/index?destination=prepare',schedule:'/pages/build/projects/index?destination=schedule',materials:'/pages/build/materials/index',store:'/pages/account/index?view=selection',todos:'/pages/build/projects/index?mode=tasks'};const url=d.url||urls[d.action]||(d.filter?'/pages/build/projects/index?filter='+encodeURIComponent(d.filter):'');if(url)navigate({url});}});
