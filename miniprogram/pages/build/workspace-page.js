const home=require('./home/page'),projects=require('./projects/page');
const scope=require('./workspace-scope');
const auth=require('../../api/auth'),http=require('../../api/http');
const panelKey=destination=>destination==='materials'?'materials':'projects';
const panelFields=['filter','keyword','expandedProjects','hasMoreProjects','error','loading'];
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
  this.__projectPanels={};
  if(view==='projects'){this.applyProjectEntry(options);this.__projectPanel=panelKey(this.data.destination);}
 },
 syncWorkspaceScope(){
  const identity=scope();
  if(this.__workspaceScope===identity)return;
  this.__workspaceScope=identity;
  const view=this.data.workspaceView,destination=this.data.destination==='materials'?'materials':'';
  this.invalidateHome();this.hideProjectList();
  this.__platRows=[];this.__projectPage=0;this.__projectQuery='';this.__workspaceScroll={};this.__projectPanels={};this.__projectPanel=view==='projects'?panelKey(destination):null;
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
  this.__platRows=[];this.__projectPage=0;this.__projectQuery='';this.__workspaceScroll={};this.__projectPanels={};
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
 activateProjectPanel(next) {
  const previous=this.__projectPanel;
  if(previous===next)return false;
  this.__projectPanel=next;
  if(!previous)return !this.__projectsVisited;
  this.__projectPanels=this.__projectPanels||{};
  const current=Object.fromEntries(panelFields.map(key=>[key,this.data[key]]));
  const rows=this.__platRows||[],page=this.__projectPage||0,query=this.__projectQuery||'';
  const pending=!!this.__projectFlight;
  this.__projectPanels[previous]={data:current,rows,page,query,needsPull:pending||!page};
  const stored=this.__projectPanels[next];
  const target=stored?.data||{filter:'全部',keyword:'',expandedProjects:false,hasMoreProjects:false,error:'',loading:true};
  const sameFilter=target.filter===current.filter&&target.keyword.trim()===current.keyword.trim();
  // Share only an equivalent, currently available query; filters and expansion belong to each tab.
  const share=sameFilter&&(!stored?.page||page>=stored.page)&&(page||pending);
  if(!share){
   this.hideProjectList();this.__inactive=false;
   this.__platRows=stored?.rows||[];this.__projectPage=stored?.page||0;this.__projectQuery=stored?.query||'';
  }
  this.setData({...target,...(share?{hasMoreProjects:current.hasMoreProjects,error:current.error,loading:current.loading}:{})});
  this.applyProjectFilters();
  return share?!pending&&!page:!stored||stored.needsPull;
 },
 hasWorkspaceSubpage(){return this.data.workspaceView==='projects'&&!!(this.data.mode||(this.data.destination&&this.data.destination!=='materials'));},
 switchWorkspaceTab(tab){
  this.syncWorkspaceScope();
  if(!engineering()||!['engineering','projects','materials'].includes(tab.key))return false;
  if(tab.key==='engineering'){
   this.setData({workspaceView:'home',view:'home',homeMounted:true,navigationIndex:2});
   if(!this.__homeVisited){this.__homeVisited=true;this.showHome();}
  }else{
   const materials=tab.key==='materials',needsPull=this.activateProjectPanel(materials?'materials':'projects');
   this.setData({workspaceView:'projects',view:'projects',projectsMounted:true,destination:materials?'materials':'',selectedLabel:materials?'材料':'',mode:'',navigationIndex:materials?9:8});
   if(needsPull||!this.__projectsVisited){this.__projectsVisited=true;this.showProjectList();}
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
  const needsPull=this.activateProjectPanel(panelKey(options.destination));
  const previousQuery=this.data.filter+'\n'+this.data.keyword;
  this.applyProjectEntry(options);
  this.setData({workspaceView:'projects',view:'projects',projectsMounted:true,keyword:'',expandedProjects:false});
  this.applyProjectFilters();
  if(needsPull||!this.__projectsVisited||previousQuery!==this.data.filter+'\n'+this.data.keyword){this.__projectsVisited=true;this.showProjectList();}
  this.restoreWorkspaceScroll();return true;
 },
 restoreNavigationState(){
  this.syncWorkspaceScope();
  if(this.data.workspaceView==='home')this.showHome();
  else{if(this.data.workspaceEnabled)this.setData({navigationIndex:this.data.destination==='materials'?9:8});this.showProjectList();}
 }
});
