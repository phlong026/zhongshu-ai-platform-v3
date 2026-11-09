const navigate=options=>require('../../../utils/page-navigation').open(options.url);
const P=require('../../../utils/platform-engineering');
const auth=require('../../../api/auth');
const http=require('../../../api/http');
const PTPage=require('../../../utils/platform-todos-page');
// 工程工作台首页（纯 API 态）：项目统计/最近项目来自 engineering projects 接口，待办走平台待办中心；
// 本地账簿视图（build/workbench.home）已随生产剔除下线。
const emptyDesk=()=>({name:(auth.currentApiPost()||{}).orgName||'工程工作台',date:'',
  stats:[{label:'在建',value:'—',filter:'在建'},{label:'待开工',value:'—',filter:'待开工'},
    {label:'待交付',value:'—',filter:'待交付'},{label:'全部项目',value:'—',filter:'全部'}],recent:null});
Page({data:{eng:null,error:'',platformTodos:null},onLoad(){PTPage.init(this);},onShow(){
  if(!auth.currentApiPost()||!P.online()){wx.switchTab({url:'/pages/profile/index'});return;}
  if(!this.data.eng)this.setData({eng:emptyDesk(),error:''});
  this.loadOnline();
  PTPage.refresh(this,false);
},
onHide(){this.loadGen=(this.loadGen||0)+1;},onUnload(){this.loadGen=(this.loadGen||0)+1;},
loadOnline(){
  const loadGen=(this.loadGen=(this.loadGen||0)+1);
  const postId=String((auth.currentApiPost()||{}).postId||'');
  const sessionGen=http.sessionGeneration();
  const current=()=>loadGen===this.loadGen
    && String((auth.currentApiPost()||{}).postId||'')===postId
    && http.sessionGeneration()===sessionGen;
  if(!this.data.eng||this.deskPostId!==postId||this.deskSessionGen!==sessionGen){
    this.deskPostId=postId;this.deskSessionGen=sessionGen;
    this.setData({eng:emptyDesk(),error:''});
  }
  return P.api.projects.page().then(rows=>{
    if(!current())return;
    const cards=P.listCards(rows);
    const stat=(key,label,filter)=>({label,value:cards.filter(c=>c.statusKey===key).length,filter});
    const recent=cards[0]||null;
    this.setData({error:'',eng:{
      name:(auth.currentApiPost()||{}).orgName||'工程工作台',date:'',
      stats:[stat('IN_PROGRESS','在建','在建'),stat('PENDING_START','待开工','待开工'),stat('READY_DELIVER','待交付','待交付'),{label:'全部项目',value:cards.length,filter:'全部'}],
      recent:recent?{name:recent.name,stage:recent.statusText,image:recent.image,status:recent.statusText,
        date:recent.actualStart||recent.date||'',progress:recent.progress>=0?recent.progress:null,
        progressKnown:recent.progress>=0,url:recent.url,overdue:0}:null
    }});
  }).catch(e=>{
    if(!current())return;
    const unauthorized=e&&(e.code===401||e.statusCode===401||e.status===401);
    this.setData({eng:unauthorized?emptyDesk():(this.data.eng||emptyDesk()),error:(e&&e.message)||'工程数据加载失败，请重试'});
  });
},
retryLoad(){this.loadOnline();},retryPlatformTodos(){PTPage.retry(this);},openPlatformTodo(e){PTPage.open(this,e);},openStat(e){this.openEngineering({currentTarget:{dataset:{filter:e.detail.filter==='全部'?'':e.detail.filter,action:e.detail.filter==='全部'?'projects':''}}});},
 openEngineering(e){const d=e.currentTarget.dataset,urls={projects:'/pages/build/projects/index',prepare:'/pages/build/projects/index?destination=prepare',schedule:'/pages/build/projects/index?destination=schedule',materials:'/pages/build/materials/index',store:'/pages/account/index?view=selection',todos:'/pages/build/projects/index?mode=tasks'};const url=d.url||urls[d.action]||(d.filter?'/pages/build/projects/index?filter='+encodeURIComponent(d.filter):'');if(url)navigate({url});}});
