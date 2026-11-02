const navigate=options=>require('../../../utils/page-navigation').open(options.url);
const W=require('../workbench');
const R=require('../../../utils/engineering-records');
const P=require('../../../utils/platform-engineering'); // 阶段 2 API 态（列表/新建在线；失败降级本地）
const Access=require('../../../utils/access'),Job=require('../../../utils/organization');
Page({
 data:{filter:'全部',keyword:'',destination:'',mode:'',cards:[],incoming:[],tasks:[],error:'',filters:['全部','在建','待交付'],creating:false,createForm:{name:'',customer:'',city:'',phone:'',floors:'2',area:'',parentIndex:0},parentOptions:[],busy:false},
 onLoad(o){this.setData({filter:o.filter||(o.destination==='prepare'?'待开工':'全部'),destination:['prepare','schedule','documents','payments','site','inspection','materials'].includes(o.destination)?o.destination:'',mode:o.mode==='tasks'?'tasks':''});},
 restoreNavigationState(){this.refresh();},
 onShow(){this.refresh();},
 refresh(){if(P.online()&&!this.__platDown)return this.refreshOnline();if(!W.allowed()){this.setData({allowed:false,cards:[],incoming:[],tasks:[]});return;}try{const actor=Access.context(),readOnly=Access.isBoss(actor),navigationIndex=Job.navigation(actor).tabs.find(tab=>tab.key===(readOnly?'owner-engineering':'projects'))?.index??8,s=W.snapshot(),kw=this.data.keyword.trim().toLowerCase(),match=p=>(p.name+p.city+(p.stage||'')+(p.title||'')).toLowerCase().includes(kw),related=new Set(s.tasks.filter(t=>/验收|复验|整改/.test(t.title)).map(t=>t.projectId));let cards=s.projects.map(W.card).filter(match);const f=this.data.filter;if(f==='待接收')cards=[];else if(f==='待验收')cards=cards.filter(p=>related.has(p.id));else if(f!=='全部')cards=cards.filter(p=>p.status===f);this.setData({allowed:true,readOnly,navigationIndex,canCreate:Job.has(actor.role,'engineer'),moreSelected:!this.data.filters.includes(this.data.filter),cards,incoming:s.incoming.filter(match),tasks:s.tasks.filter(match),allCount:s.projects.length+s.incoming.length,taskCount:s.tasks.length,error:'',selectedLabel:this.data.destination?{prepare:'开工准备',schedule:'施工计划',documents:'图纸资料',payments:'节点款项',site:'现场记录',inspection:'节点验收',materials:'施工材料'}[this.data.destination]:''});}catch(e){this.setData({error:e.message,cards:[],tasks:[]});}},
 search(e){this.setData({keyword:e.detail.value});this.refresh();},clear(){this.setData({keyword:''});this.refresh();},
 filter(e){this.setData({filter:e.currentTarget.dataset.value,mode:''});this.refresh();},
 more(){const itemList=['待接收','待开工','已交付','已归档'].concat(P.online()&&!this.__platDown?[]:['待验收']); // API 态待验收恒空，隐藏该项
  wx.showActionSheet({itemList,success:r=>{this.setData({filter:itemList[r.tapIndex],mode:''});this.refresh();}});},
 showProjects(){this.setData({mode:'',filter:'全部'});this.refresh();},
 open(e){const d=e.currentTarget.dataset;if(d.url){const s=W.snapshot();if(!s.tasks.concat(s.incoming).some(t=>t.url===d.url))return this.refresh();navigate({url:d.url});return;}const p=W.snapshot().projects.find(p=>p.id===d.id);if(!p)return this.refresh();W.remember(p.id);const view=d.view||this.data.destination||'overview';if(view==='materials'){navigate({url:'/pages/build/materials/index?id='+encodeURIComponent(p.id)});return;}navigate({url:W.build(view,p,['node','inspection','site','rectify'].includes(view)?{nodeId:W.current(p)?.id}:{})});},
 openCreate(){if(!this.data.canCreate)return;
  // 父项目选项：API 态取在线列表（listCards），离线取本地演示快照
  const online=this.__platRows&&!this.__platDown;
  const projects=online?P.listCards(this.__platRows).map(p=>({id:p.id,name:p.name})):W.snapshot().projects.map(p=>({id:p.id,name:p.name}));
  this.setData({creating:true,error:'',parentOptions:[{id:'',name:'不关联，新建独立项目'}].concat(projects),createForm:{name:'',customer:'',city:'',phone:'',floors:'2',area:'',parentIndex:0}});},
 cancelCreate(){this.setData({creating:false,error:''});},
 createInput(e){const d=e.currentTarget.dataset;this.setData({['createForm.'+d.key]:e.detail.value,error:''});},
 pickCreateFloors(e){this.setData({'createForm.floors':['1','2','3','4','5'][Number(e.detail.value)]});},
 pickCreateParent(e){this.setData({'createForm.parentIndex':Number(e.detail.value)});},
 submitCreate(){if(P.online())return this.submitCreateOnline();if(this.data.busy||!this.data.canCreate)return;this.setData({busy:true});try{const f=this.data.createForm,parent=this.data.parentOptions[Number(f.parentIndex)||0];const p=R.create({name:f.name,customer:f.customer,city:f.city,phone:f.phone,floors:Number(f.floors)||2,area:f.area,parentId:parent?parent.id:''});this.setData({creating:false,busy:false});wx.showToast({title:'项目已创建',icon:'success'});navigate({url:R.link(p,'prepare')});}catch(e){this.setData({busy:false});wx.showToast({title:e.message,icon:'none'});}},
 async refreshOnline(){ // API 态：工程列表在线（工程岗仅本人/财务线全量；失败降级本地演示）
  try{
   const rows=await P.api.projects.page();this.__platRows=rows;
   const cards=P.listCards(rows).filter(c=>P.statusMatches(c.statusRaw||c.status,this.data.filter));
   const a=Access.context();
   this.setData({allowed:true,cards,incoming:[],tasks:[],allCount:cards.length,taskCount:0,error:'',platEng:true,
    // 新建入口对齐 856 write=engineer/project-manager 口径 + v3 全局 isBoss 惯例
    canCreate:Access.isBoss(a)||Job.has(a.role,'engineer')||Job.has(a.role,'project-manager')});
  }catch(e){ // 失败降级：回退离线本地渲染（不白屏），平台错误以 toast 保留
   const msg=P.errorText(e&&e.data?e.data:e);
   this.__platDown=true;
   this.refresh();
   wx.showToast({title:msg,icon:'none'});
  }
 },
 async submitCreateOnline(){ // API 态新建（查重 1_115_000_008 → 文案提示）
  if(this.data.busy)return;this.setData({busy:true});
  try{
   const f=this.data.createForm,parent=this.data.parentOptions[Number(f.parentIndex)||0];
   const created=await P.api.projects.create({name:f.name,customer:f.customer,city:f.city,phone:f.phone||undefined,floors:Number(f.floors)||2,area:f.area?Number(f.area):undefined,parentProjectId:parent&&parent.id?String(parent.id):undefined});
   this.setData({creating:false,busy:false});
   wx.showToast({title:'项目已创建',icon:'success'});
   navigate({url:'/pages/build/record/index?id='+encodeURIComponent(String(created.id))+'&view=prepare'});
  }catch(e){this.setData({busy:false});wx.showToast({title:P.errorText(e.data||e),icon:'none'});}
 },
 home(){wx.switchTab({url:'/pages/engineering/index'});}
});
