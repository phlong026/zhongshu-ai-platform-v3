// Presentation only. All access, state transitions and writes remain in existing models.
const B=require('./business'),Access=require('./access'),Job=require('./organization');
const last=rows=>Array.isArray(rows)?rows[rows.length-1]:null;
const today=()=>require('./account').localDate();
function maskPhone(phone){return String(phone||'').replace(/^(\d{3})\d{4}(\d{4})$/,'$1****$2');}
function category(c){if(c.status==='已到店')return '已到店';return (c.follows||[]).length||c.status!=='已登记'&&c.status!=='待跟进'?'跟进中':'待跟进';}
const md=d=>String(d||'').slice(5,10),days=(from,to)=>Math.round((Date.parse(to+'T00:00:00')-Date.parse(from+'T00:00:00'))/864e5);
const FLOORS=['','一层','两层','三层','四层','五层'];
// Customer-list cue: time hint on the right, the next/last contact line and an urgency rank (0 overdue … 5 handed on / read-only).
function listCue(c,follow,{status,canFollow,stageIndex,mine,isLead}){
 const day=today(),appointment=String(follow?.appointment||''),date=appointment.slice(0,10),time=appointment.slice(11,16),plan=follow?.next||'客户回访';
 const reg=(String(c.id).match(/^customer-(\d{13})-/)||[])[1],regDate=reg?require('./account').localDate(new Date(Number(reg))):'';
 let cue,tone='',rank,key=appointment;
 if(canFollow&&date&&date<day){cue='逾期 '+days(date,day)+' 天';tone='tone-danger';rank=0;}
 else if(date===day){cue='今天 '+time;tone='tone-warning';rank=1;}
 else if(date>day){cue=md(date)+(/到店/.test(plan)?' 到店':' 回访');rank=3;}
 else if(!follow){cue='新客户';rank=2;key=String(9e15-Number(reg||0));}
 else{const n=follow.date?days(follow.date,day):0;cue=n>0?n+' 天未跟进':'今天已跟进';tone='customer-cue-muted';rank=4;key=follow.date||'';}
 if(rank>=2&&(status==='已到店'||stageIndex>=3||!canFollow))rank=5;
 const detail=!follow?'待首次沟通'+(regDate?' · '+(regDate===day?'今天':md(regDate))+'登记':''):date?'下次：'+(date===day?'今天':md(date))+' '+time+' '+plan:follow.date?'上次：'+md(follow.date)+' '+(follow.method||'沟通'):B.stage(c);
 return {cue,cueTone:tone,rank,sortKey:key,detail:detail+(isLead&&!mine&&c.owner&&c.owner!=='我'?' · '+c.owner:'')};
}
function customerRows(){
 const actor=Access.context(),isLead=actor.role==='sales-lead';
 return B.load().customers.filter(c=>c.ownership!=='public'||c.ownerId).map(c=>{
  const follow=last(c.follows),appointment=String(follow?.appointment||''),status=category(c),canFollow=Access.workflow('follow',c,true),date=appointment.slice(0,10),dateText=date===today()?'今天':date||'';
  const next=follow?(appointment?dateText+' '+appointment.slice(11,16)+' · '+(follow.next||'客户回访'):B.stage(c)):'待首次沟通';
  const stageIndex=B.stageIndex(c),mine=Access.ownerId(c)===actor.staffId,floors=FLOORS[Number(c.floors)]||'',size=c.width&&c.depth?c.width+'×'+c.depth+'m':'',source=c.acquisitionChannel||c.source;
  return {id:c.id,name:c.name,initial:c.name.slice(0,1),status,phone:maskPhone(c.phone)||c.wechat||'暂无联系方式',searchText:[c.name,c.phone,c.wechat,c.city,c.summary,c.owner].filter(Boolean).join(' '),city:c.city,summary:c.summary,stage:B.stage(c),appointment,next,canFollow,action:canFollow?'去跟进':'看记录',owner:c.owner||'',view:'customer',url:'/pages/workflow/index?view=customer'+(!follow&&canFollow?'&modal=follow':'')+'&id='+encodeURIComponent(c.id),hasDesign:!!c.request,hasPlan:!!c.plans?.length,hasVisit:c.status==='已到店',fresh:!follow,
   stageName:B.STAGES[Math.min(stageIndex,B.STAGES.length-1)],brief:[c.city,[floors,size].filter(Boolean).join(' '),source!=='未记录'&&source].filter(Boolean).join(' · '),hasPhone:!!c.phone,...listCue(c,follow,{status,canFollow,stageIndex,mine,isLead})};
 });
}
function sortRows(rows){return rows.slice().sort((a,b)=>a.rank-b.rank||String(a.sortKey).localeCompare(String(b.sortKey)));}
function counts(rows){return {all:rows.length,pending:rows.filter(r=>r.status==='待跟进').length,following:rows.filter(r=>r.status==='跟进中').length,visited:rows.filter(r=>r.status==='已到店').length};}
function focusRows(rows,focus){const day=today();if(focus==='due')return rows.filter(r=>r.canFollow&&(r.status==='待跟进'||r.fresh||r.appointment&&r.appointment.slice(0,10)<=day)).sort((a,b)=>(a.appointment||day).localeCompare(b.appointment||day));if(focus==='appointments')return rows.filter(r=>r.appointment&&r.appointment.slice(0,10)>=day&&r.status!=='已到店'&&/到店/.test(r.next));return rows;}
// Same figure as the boss home "本月签约": design commissions submitted this month (latest service order total, yuan), over the customers this person can see.
function monthCommission(){const period=today().slice(0,7);const total=B.load().customers.filter(c=>c.businessFlow?.type==='sales-offline-v1'&&c.serviceOrders?.length&&String(c.businessFlow.submittedAt||'').slice(0,7)===period).reduce((n,c)=>n+Number(c.serviceOrders.at(-1).total||0),0);return total>=10000?{value:String(Math.round(total/100)/100),unit:'万元'}:{value:total.toLocaleString('en-US'),unit:'元'};}
// Home to-do board: one list (actionable, in progress, staff approvals) filtered by tab; only the first rows show until expanded.
const TODO_TABS=[['all','全部'],['customer','客户'],['creation','创作'],['commission','委托款项']],TODO_LIMIT=3;
function todoBoard(pending=[],waiting=[],staff=[],tab='all',expanded=false){
 const rows=[...pending,...staff.map(t=>({id:'staff:'+t.key,staffKey:t.key,name:t.name,state:t.status,action:'去办理',actionable:true,group:'commission',urgency:3,staff:true,view:t.view,targetId:t.id,area:t.area,record:t.record})),...waiting].sort((x,y)=>(x.urgency??8)-(y.urgency??8));
 const inGroup=key=>rows.filter(r=>key==='all'||r.group===key),count=key=>inGroup(key).filter(r=>r.actionable).length;
 const tabs=TODO_TABS.filter(([key])=>key!=='commission'||inGroup(key).length).map(([key,label])=>({key,label,count:count(key)}));
 if(!tabs.some(t=>t.key===tab))tab='all';
 const inTab=rows.filter(r=>tab==='all'||r.group===tab),first=inTab.find(r=>r.actionable);
 const shown=new Set(inTab.slice(0,expanded?inTab.length:TODO_LIMIT).map(r=>r.id));
 return {todoTab:tab,todoExpanded:expanded,todoTabs:tabs,todoMore:inTab.length>TODO_LIMIT,todoActionable:rows.filter(r=>r.actionable).length,
  todoRows:rows.map(r=>({...r,show:shown.has(r.id),primary:!!first&&r.id===first.id,tone:r.urgency<=1?'tone-danger':r.urgency===2?'tone-warning':''}))};
}
function home(){
 const actor=Access.context();if(!actor.active||!Job.has(actor.role,'sales'))return {isSalesHome:false};
 const rows=customerRows(),day=today(),due=focusRows(rows,'due'),upcoming=focusRows(rows,'appointments');
 const state=require('./studio').read(),recent=require('./studio').recentItems(state),draft=recent.find(r=>r.id==='draft'||r.id==='video-draft'),work=recent.find(r=>r!==draft);
 const project=rows.find(r=>r.hasDesign)||rows.find(r=>r.hasPlan)||rows.find(r=>r.status!=='待跟进');
 const date=new Date(day+'T12:00:00+08:00'),week=['周日','周一','周二','周三','周四','周五','周六'][date.getUTCDay()];
 const overdue=rows.filter(r=>r.canFollow&&r.appointment&&r.appointment.slice(0,10)<day).length;
 return {isSalesHome:true,salesName:require('./account').load().profile.name||actor.name||'',overdueCount:overdue,commission:monthCommission(),salesDate:Number(day.slice(5,7))+'月'+Number(day.slice(8,10))+'日 '+week,customerLabel:actor.role==='sales-lead'?'可见客户':'我的客户',salesCounts:counts(rows),todayCount:due.length,appointmentCount:upcoming.length,
  ...(()=>{const t=require('./sales-todos').list();return {flowPending:t.pending,flowWaiting:t.waiting};})(),
  salesShortcuts:[{title:'登记客户',icon:'person-filled',action:'customer'},{title:'内容创作',icon:'video-filled',action:'remix'},{title:'AI设计',icon:'home-design-icon',action:'aiDesign',url:'/pages/design/index'},{title:'快速预算',icon:'contract',action:'quickBudget',url:'/pages/workflow/explore/index?view=budget'}],
  recentCustomer:project||null,recentCreation:draft||work||null,isDemo:!!wx.getStorageSync('zhongshu-account-v1')?.session?.demo};
}
module.exports={customerRows,sortRows,counts,category,maskPhone,focusRows,home,todoBoard,monthCommission};
