const navigate=options=>require('../../../utils/page-navigation').open(options.url);
const W=require('../workbench');
const R=require('../../../utils/engineering-records');
require('../../../utils/load-local-fonts')(require('../../../utils/dashboard-font-sources'),'dashboard');
Page({
 data:{filter:'全部',keyword:'',destination:'',mode:'',cards:[],incoming:[],tasks:[],error:'',filters:['全部','在建','待交付'],creating:false,createForm:{name:'',customer:'',city:'',phone:'',floors:'2',area:'',parentIndex:0},parentOptions:[],busy:false},
 onLoad(o){this.setData({filter:o.filter||(o.destination==='prepare'?'待开工':'全部'),destination:['prepare','schedule','documents','payments','site','inspection','materials'].includes(o.destination)?o.destination:'',mode:o.mode==='tasks'?'tasks':''});},
 restoreNavigationState(){this.refresh();},
 onShow(){this.refresh();},
 refresh(){if(!W.allowed()){this.setData({allowed:false,cards:[],incoming:[],tasks:[]});return;}try{const s=W.snapshot(),kw=this.data.keyword.trim().toLowerCase(),match=p=>(p.name+p.city+(p.stage||'')+(p.title||'')).toLowerCase().includes(kw),related=new Set(s.tasks.filter(t=>/验收|复验|整改/.test(t.title)).map(t=>t.projectId));let cards=s.projects.map(W.card).filter(match);const f=this.data.filter;if(f==='待接收')cards=[];else if(f==='待验收')cards=cards.filter(p=>related.has(p.id));else if(f!=='全部')cards=cards.filter(p=>p.status===f);this.setData({allowed:true,moreSelected:!this.data.filters.includes(this.data.filter),cards,incoming:s.incoming.filter(match),tasks:s.tasks.filter(match),allCount:s.projects.length+s.incoming.length,taskCount:s.tasks.length,error:'',selectedLabel:this.data.destination?{prepare:'开工准备',schedule:'施工计划',documents:'图纸资料',payments:'节点款项',site:'现场记录',inspection:'节点验收',materials:'施工材料'}[this.data.destination]:''});}catch(e){this.setData({error:e.message,cards:[],tasks:[]});}},
 search(e){this.setData({keyword:e.detail.value});this.refresh();},clear(){this.setData({keyword:''});this.refresh();},
 filter(e){this.setData({filter:e.currentTarget.dataset.value,mode:''});this.refresh();},
 more(){const itemList=['待接收','待开工','已交付','已归档','待验收'];wx.showActionSheet({itemList,success:r=>{this.setData({filter:itemList[r.tapIndex],mode:''});this.refresh();}});},
 showProjects(){this.setData({mode:'',filter:'全部'});this.refresh();},
 open(e){const d=e.currentTarget.dataset;if(d.url){const s=W.snapshot();if(!s.tasks.concat(s.incoming).some(t=>t.url===d.url))return this.refresh();navigate({url:d.url});return;}const p=W.snapshot().projects.find(p=>p.id===d.id);if(!p)return this.refresh();W.remember(p.id);const view=d.view||this.data.destination||'overview';if(view==='materials'){navigate({url:'/pages/build/materials/index?id='+encodeURIComponent(p.id)});return;}navigate({url:W.build(view,p,['node','inspection','site','rectify'].includes(view)?{nodeId:W.current(p)?.id}:{})});},
 openCreate(){const s=W.snapshot();this.setData({creating:true,error:'',parentOptions:[{id:'',name:'不关联，新建独立项目'}].concat(s.projects.map(p=>({id:p.id,name:p.name}))),createForm:{name:'',customer:'',city:'',phone:'',floors:'2',area:'',parentIndex:0}});},
 cancelCreate(){this.setData({creating:false,error:''});},
 createInput(e){const d=e.currentTarget.dataset;this.setData({['createForm.'+d.key]:e.detail.value,error:''});},
 pickCreateFloors(e){this.setData({'createForm.floors':['1','2','3','4','5'][Number(e.detail.value)]});},
 pickCreateParent(e){this.setData({'createForm.parentIndex':Number(e.detail.value)});},
 submitCreate(){if(this.data.busy)return;this.setData({busy:true});try{const f=this.data.createForm,parent=this.data.parentOptions[Number(f.parentIndex)||0];const p=R.create({name:f.name,customer:f.customer,city:f.city,phone:f.phone,floors:Number(f.floors)||2,area:f.area,parentId:parent?parent.id:''});this.setData({creating:false,busy:false});wx.showToast({title:'项目已创建',icon:'success'});navigate({url:R.link(p,'prepare')});}catch(e){this.setData({busy:false});wx.showToast({title:e.message,icon:'none'});}},
 home(){wx.switchTab({url:'/pages/engineering/index'});}
});
