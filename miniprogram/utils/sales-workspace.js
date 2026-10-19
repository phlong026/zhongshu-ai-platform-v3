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
  return {id:c.id,name:c.name,initial:c.name.slice(0,1),status,phone:maskPhone(c.phone)||c.wechat||'暂无联系方式',city:c.city,summary:c.summary,stage:B.stage(c),appointment,next,canFollow,action:canFollow?'去跟进':'看记录',owner:c.owner||'',view:'customer',url:'/pages/workflow/index?view=customer'+(!follow&&canFollow?'&modal=follow':'')+'&id='+encodeURIComponent(c.id),hasPlan:!!c.plans?.length,hasVisit:c.status==='已到店'};
 });
}
function counts(rows){return {all:rows.length,pending:rows.filter(r=>r.status==='待跟进').length,following:rows.filter(r=>r.status==='跟进中').length,visited:rows.filter(r=>r.status==='已到店').length};}
function home(){
 const actor=Access.context();if(!actor.active||!Job.has(actor.role,'sales'))return {isSalesHome:false};
 const rows=customerRows(),day=today(),due=rows.filter(r=>r.canFollow&&(r.status==='待跟进'||r.appointment&&r.appointment.slice(0,10)<=day)).sort((a,b)=>(a.appointment||day).localeCompare(b.appointment||day));
 const upcoming=rows.filter(r=>r.appointment&&r.appointment.slice(0,10)>=day&&r.status!=='已到店'&&/到店/.test(r.next));
 const state=require('./studio').read(),recent=require('./studio').recentItems(state),draft=recent.find(r=>r.id==='draft'||r.id==='video-draft'),work=recent.find(r=>r!==draft);
 const project=rows.find(r=>r.hasPlan)||rows.find(r=>r.status!=='待跟进');
 const date=new Date(day+'T12:00:00+08:00'),week=['周日','周一','周二','周三','周四','周五','周六'][date.getUTCDay()];
 return {isSalesHome:true,salesName:require('./account').load().profile.name,salesDate:Number(day.slice(5,7))+'月'+Number(day.slice(8,10))+'日 '+week,customerLabel:actor.role==='sales-lead'?'可见客户':'我的客户',salesCounts:counts(rows),todayCount:due.length,appointmentCount:upcoming.length,salesTodos:due.slice(0,2),hasMoreTodos:due.length>2,
  salesShortcuts:[{title:'登记客户',icon:'person-filled',action:'customer'},{title:'内容创作',icon:'video-filled',action:'remix'},{title:'方案设计',icon:'home-design-icon',action:'salesDesign'},{title:'报价测算',icon:'contract',action:'budget'}],
  recentCustomer:project||null,recentCreation:draft||work||null,isDemo:!!wx.getStorageSync('zhongshu-account-v1')?.session?.demo};
}
module.exports={customerRows,counts,category,maskPhone,home};
