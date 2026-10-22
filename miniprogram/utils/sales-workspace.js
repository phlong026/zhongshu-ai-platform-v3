// Presentation only. All access, state transitions and writes remain in existing models.
const B=require('./business'),Access=require('./access'),Job=require('./organization');
const last=rows=>Array.isArray(rows)?rows[rows.length-1]:null;
const today=()=>require('./account').localDate();
function maskPhone(phone){return String(phone||'').replace(/^(\d{3})\d{4}(\d{4})$/,'$1****$2');}
function category(c){if(c.status==='已到店')return '已到店';return (c.follows||[]).length||c.status!=='已登记'&&c.status!=='待跟进'?'跟进中':'待跟进';}
function customerRows(){
 return B.load().customers.filter(c=>c.ownership!=='public'||c.ownerId).map(c=>{
  const follow=last(c.follows),appointment=String(follow?.appointment||''),status=category(c),canFollow=Access.workflow('follow',c,true),date=appointment.slice(0,10),dateText=date===today()?'今天':date||'';
  const next=follow?(appointment?dateText+' '+appointment.slice(11,16)+' · '+(follow.next||'客户回访'):B.stage(c)):'待首次沟通';
  return {id:c.id,name:c.name,initial:c.name.slice(0,1),status,phone:maskPhone(c.phone)||c.wechat||'暂无联系方式',searchText:[c.name,c.phone,c.wechat,c.city,c.summary,c.owner].filter(Boolean).join(' '),city:c.city,summary:c.summary,stage:B.stage(c),appointment,next,canFollow,action:canFollow?'去跟进':'看记录',owner:c.owner||'',view:'customer',url:'/pages/workflow/index?view=customer'+(!follow&&canFollow?'&modal=follow':'')+'&id='+encodeURIComponent(c.id),hasDesign:!!c.request,hasPlan:!!c.plans?.length,hasVisit:c.status==='已到店'};
 });
}
function counts(rows){return {all:rows.length,pending:rows.filter(r=>r.status==='待跟进').length,following:rows.filter(r=>r.status==='跟进中').length,visited:rows.filter(r=>r.status==='已到店').length};}
function focusRows(rows,focus){const day=today();if(focus==='due')return rows.filter(r=>r.canFollow&&(r.status==='待跟进'||r.appointment&&r.appointment.slice(0,10)<=day)).sort((a,b)=>(a.appointment||day).localeCompare(b.appointment||day));if(focus==='appointments')return rows.filter(r=>r.appointment&&r.appointment.slice(0,10)>=day&&r.status!=='已到店'&&/到店/.test(r.next));return rows;}
// Same figure as the boss home "本月签约": design commissions submitted this month (latest service order total, yuan), over the customers this person can see.
function monthCommission(){const period=today().slice(0,7);const total=B.load().customers.filter(c=>c.businessFlow?.type==='sales-offline-v1'&&c.serviceOrders?.length&&String(c.businessFlow.submittedAt||'').slice(0,7)===period).reduce((n,c)=>n+Number(c.serviceOrders.at(-1).total||0),0);return total>=10000?{value:String(Math.round(total/100)/100),unit:'万元'}:{value:total.toLocaleString('en-US'),unit:'元'};}
// Home to-do board: one list (actionable, in progress, staff approvals) filtered by tab; only the first rows show until expanded.
const TODO_TABS=[['all','全部'],['customer','客户'],['creation','创作'],['commission','委托款项']],TODO_LIMIT=3;
function todoBoard(pending=[],waiting=[],staff=[],tab='all',expanded=false){
 const rows=[...pending,...staff.map(t=>({id:'staff:'+t.key,staffKey:t.key,name:t.name,state:t.status,action:'去办理',actionable:true,group:'commission',urgency:3,staff:true,view:t.view,targetId:t.id,area:t.area,record:t.record})),...waiting].sort((x,y)=>(x.urgency??8)-(y.urgency??8));
 const count=key=>key==='all'?rows.length:rows.filter(r=>r.group===key).length;
 const tabs=TODO_TABS.filter(([key])=>key!=='commission'||count(key)).map(([key,label])=>({key,label,count:count(key)}));
 if(!tabs.some(t=>t.key===tab))tab='all';
 const inTab=rows.filter(r=>tab==='all'||r.group===tab),first=inTab.find(r=>r.actionable);
 const shown=new Set(inTab.slice(0,expanded?inTab.length:TODO_LIMIT).map(r=>r.id));
 return {todoTab:tab,todoExpanded:expanded,todoTabs:tabs,todoTotal:inTab.length,todoMore:inTab.length>TODO_LIMIT,todoActionable:rows.filter(r=>r.actionable).length,
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
  salesShortcuts:[{title:'内容创作',icon:'video-filled',action:'remix'},{title:'方案设计',icon:'home-design-icon',action:'salesDesign'},{title:'参考预算',icon:'contract',action:'budget'},{title:'登记客户',icon:'person-filled',action:'customer'}],
  recentCustomer:project||null,recentCreation:draft||work||null,isDemo:!!wx.getStorageSync('zhongshu-account-v1')?.session?.demo};
}
module.exports={customerRows,counts,category,maskPhone,focusRows,home,todoBoard,monthCommission};
