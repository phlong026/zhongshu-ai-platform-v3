const home=require('./home/page'),projects=require('./projects/page');
const scope=require('./workspace-scope');
const auth=require('../../api/auth'),http=require('../../api/http');
const engineering=()=>!!http.token()&&['engineer','project-manager'].includes(auth.currentApiPost()?.roleCode);
const defaults=view=>({...home.data,...projects.data,workspaceView:view,view,workspaceEnabled:false,
 homeMounted:view==='home',projectsMounted:view==='projects',navigationIndex:view==='home'?2:8});

// Both historical entry routes host this same build-subpackage workbench.
// Public Page lifecycles run only on native navigation, never on local tab changes.
module.exports=initial=>({
 ...home,...projects,data:defaults(initial),
 onLoad(options={}){
  const view=['home','projects'].includes(options.view)?options.view:initial;
  this.__workspaceScope=scope();this.__workspaceScroll={};
  this.setData({workspaceView:view,view,workspaceEnabled:engineering(),homeMounted:view==='home',projectsMounted:view==='projects',navigationIndex:view==='home'?2:8});
  this.initHome();
  if(view==='projects')this.applyProjectEntry(options);
 },
 syncWorkspaceScope(){
  const identity=scope();
  if(this.__workspaceScope===identity)return;
  this.__workspaceScope=identity;
  const view=this.data.workspaceView,destination=this.data.destination==='materials'?'materials':'';
  this.invalidateHome();this.hideProjectList();
  this.__platRows=[];this.__projectPage=0;this.__workspaceScroll={};
  this.__homeVisited=false;this.__projectsVisited=false;this.homeProfileName='';
  this.setData({...defaults(view),workspaceEnabled:engineering(),
   destination,selectedLabel:destination?'材料':'',navigationIndex:view==='home'?2:destination?9:8});
  const todoGeneration=this.__ptLoadGen||0;
  this.initHome();this.__ptLoadGen=todoGeneration+1;
  this.__disposed=false;this.__inactive=false;
 },
 clearWorkspaceAccess(){
  this.__workspaceAccessGen=(this.__workspaceAccessGen||0)+1;
  this.invalidateHome();this.__projectLoadGen=(this.__projectLoadGen||0)+1;this.__projectFlight=null;
  this.__platRows=[];this.__projectPage=0;this.__projectQuery='';this.__workspaceScroll={};
  this.__homeVisited=false;this.__projectsVisited=false;this.homeProfileName='';
  const {navigationIndex,destination,selectedLabel,mode}=this.data;
  this.setData({...projects.data,navigationIndex,destination,selectedLabel,mode,eng:null,homeError:'',loading:false});
  const todoGeneration=this.__ptLoadGen||0;
  this.initHome();this.__ptLoadGen=todoGeneration+1;
 },
 onShow(){
  this.__workspaceVisible=true;this.__disposed=false;this.__inactive=false;
  this.syncWorkspaceScope();
  if(this.data.workspaceView==='home'){this.__homeVisited=true;this.showHome();}
  else{this.__projectsVisited=true;this.showProjectList();}
  this.restoreWorkspaceScroll();
 },
 onHide(){this.__workspaceVisible=false;this.__homeVisited=false;this.__projectsVisited=false;this.invalidateHome();this.hideProjectList();},
 onUnload(){this.__workspaceVisible=false;this.invalidateHome();this.disposeProjectList();this.__ptLoadGen=(this.__ptLoadGen||0)+1;},
 workspaceKey(){return this.data.workspaceView==='home'?'home':this.data.destination==='materials'?'materials':'projects';},
 onPageScroll(e){this.__workspaceScroll=this.__workspaceScroll||{};this.__workspaceScroll[this.workspaceKey()]=e.scrollTop||0;},
 restoreWorkspaceScroll(){
  const key=this.workspaceKey(),identity=scope();
  const revision=this.__scrollRevision=(this.__scrollRevision||0)+1;
  wx.nextTick(()=>{if(this.__workspaceVisible&&revision===this.__scrollRevision&&scope()===identity)
   wx.pageScrollTo({scrollTop:this.__workspaceScroll?.[key]||0,duration:0});});
 },
 hasWorkspaceSubpage(){return this.data.workspaceView==='projects'&&!!(this.data.mode||(this.data.destination&&this.data.destination!=='materials'));},
 switchWorkspaceTab(tab){
  this.syncWorkspaceScope();
  if(!engineering()||!['engineering','projects','materials'].includes(tab.key))return false;
  if(tab.key==='engineering'){
   this.setData({workspaceView:'home',view:'home',homeMounted:true,navigationIndex:2});
   if(!this.__homeVisited){this.__homeVisited=true;this.showHome();}
  }else{
   const materials=tab.key==='materials';
   this.setData({workspaceView:'projects',view:'projects',projectsMounted:true,destination:materials?'materials':'',selectedLabel:materials?'材料':'',mode:'',navigationIndex:materials?9:8});
   if(!this.__projectsVisited){this.__projectsVisited=true;this.showProjectList();}
  }
  this.restoreWorkspaceScroll();return true;
 },
 openWorkspaceUrl(url){
  this.syncWorkspaceScope();
  if(!engineering()||url.split('?')[0]!=='/pages/build/projects/index')return false;
  const options={};
  (url.split('?')[1]||'').split('&').filter(Boolean).forEach(pair=>{
   const [key,...value]=pair.split('=');if(['filter','destination','mode'].includes(key))options[key]=value.join('=');
  });
  const previousQuery=this.data.filter+'\n'+this.data.keyword;
  this.applyProjectEntry(options);
  this.setData({workspaceView:'projects',view:'projects',projectsMounted:true,keyword:'',expandedProjects:false});
  this.applyProjectFilters();
  if(!this.__projectsVisited||previousQuery!==this.data.filter+'\n'+this.data.keyword){this.__projectsVisited=true;this.showProjectList();}
  this.restoreWorkspaceScroll();return true;
 },
 restoreNavigationState(){
  this.syncWorkspaceScope();
  if(this.data.workspaceView==='home')this.showHome();
  else{if(this.data.workspaceEnabled)this.setData({navigationIndex:this.data.destination==='materials'?9:8});this.showProjectList();}
 }
});
