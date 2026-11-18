const http=require('../api/http');
const labels={signed:'本月施工签约',customerNet:'本月净回款',newCustomers:'本月新增',visits:'实际接待',publications:'发布内容',opportunities:'跟进商机',budgets:'待确认预算',designs:'设计办理中',followups:'待跟进',handover:'待移交',building:'在建项目',preparing:'待开工',receiving:'待接收',nextNodes:'近7天节点',toDeliver:'待交付',delivered:'本月交付',overdueNodes:'逾期节点',materials:'待到货材料',rectifications:'待整改',projectDue:'到期款未收',net:'收支净额',received:'已登记收款',paid:'已登记付款',clientRefund:'客户退款',expenseRefund:'支出退回',receivable:'待收余额',payable:'待付余额',verify:'待核实',reimbursements:'待报销',incomplete:'待完善',commissions:'设计委托',attention:'需关注工程',dueReceipt:'到期待收',soonPayable:'近期需付'};
const moneyKeys=['signed','customerNet','net','received','paid','clientRefund','expenseRefund','receivable','payable','dueReceipt','soonPayable','reimbursements'];
const groups={business:{primary:['signed','customerNet'],secondary:['newCustomers','visits','publications','opportunities','budgets','designs'],alerts:['followups','handover']},engineering:{primary:['building','preparing','receiving'],secondary:['nextNodes','toDeliver','delivered'],alerts:['overdueNodes','materials','rectifications','projectDue']},owner:{primary:['signed','customerNet'],secondary:['newCustomers','commissions','paid'],engineering:['building','delivered','attention'],funds:['dueReceipt','soonPayable']},finance:{primary:['received','paid'],balances:['receivable','payable'],secondary:['verify','reimbursements','incomplete']}};
const currentPeriod=()=>require('./account').localDate().slice(0,7);
function money(cents,compact=true){
 const n=Number(cents);if(!Number.isSafeInteger(n))return {value:'—',unit:'元'};
 if(compact&&Math.abs(n)>=1000000)return {value:(n/1000000).toFixed(2).replace(/\.?0+$/,''),unit:'万'};
 return {value:(n/100).toLocaleString('en-US',{maximumFractionDigits:2}),unit:'元'};
}
function metric(key,raw,role){
 const isMoney=moneyKeys.includes(key),known=raw&&Number.isSafeInteger(Number(raw.total))&&Number.isSafeInteger(Number(raw.cents));
 const out={key,label:labels[key],value:'—',unit:isMoney?'元':({newCustomers:'人',visits:'人',publications:'条',designs:'单',commissions:'单',followups:'人',materials:'单',opportunities:'项',budgets:'项',handover:'项',rectifications:'项',verify:'笔',incomplete:'笔'}[key]||'个'),known:!!known,caption:''};
 if(role==='owner'&&key==='customerNet')out.label='本月客户净回款';
 if(role==='owner'&&key==='newCustomers')out.label='新增客户';
 if(role==='owner'&&key==='building')out.label='当前在建';
 if(!known)return out;
 Object.assign(out,isMoney?money(Number(raw.cents)):{value:String(raw.total),unit:out.unit});
 if(key==='signed')out.caption=raw.total+' 单合同';
 if(key==='customerNet')out.caption='财务已核实';
 if(key==='overdueNodes'&&Number(raw.total)>0&&raw.first_date){const date=typeof raw.first_date==='number'?new Date(raw.first_date+8*3600000).toISOString().slice(0,10):Array.isArray(raw.first_date)?raw.first_date.map((n,i)=>i?String(n).padStart(2,'0'):n).join('-'):String(raw.first_date).slice(0,10);const days=Math.floor((Date.parse(require('./account').localDate())-Date.parse(date))/86400000);if(Number.isFinite(days))out.caption='最长'+Math.max(0,days)+'天';}
 if(key==='projectDue')out.caption='待收 '+money(Number(raw.cents)).value+money(Number(raw.cents)).unit;
 out.overdueCount=Number(raw.overdue_count||0);
 out.amountText=money(Number(raw.cents),false).value;
 return out;
}
function view(role,result){const raw=result&&result.metrics||{},mapped={};Object.keys(labels).forEach(key=>mapped[key]=metric(key,raw[key],role));
 if(raw.receivable&&raw.dueReceipt)mapped.receivable.caption='到期 '+mapped.dueReceipt.value+mapped.dueReceipt.unit+' · 逾期 '+money(Number(raw.dueReceipt.overdue_cents)).value+money(Number(raw.dueReceipt.overdue_cents)).unit;
 if(raw.soonPayable)mapped.payable.caption='近期需付 '+mapped.soonPayable.value+mapped.soonPayable.unit;
 if(raw.dueReceipt)mapped.dueReceipt.caption='其中逾期 '+money(Number(raw.dueReceipt.overdue_cents)).value+money(Number(raw.dueReceipt.overdue_cents)).unit;
 mapped.soonPayable.caption='已逾期及未来7天';
 const out={metrics:mapped};Object.entries(groups[role]||{}).forEach(([k,keys])=>out[k]=keys.map(key=>mapped[key]));return out;
}
function detailUrl(role,key,period){return '/pages/home-metrics/index?role='+encodeURIComponent(role)+'&metric='+encodeURIComponent(key)+'&period='+encodeURIComponent(period);}
module.exports={labels,groups,money,metric,view,currentPeriod,detailUrl,load:(role,period)=>http.get('/franchise/home',{role,period}),details:(role,period,key,page)=>http.get('/franchise/home/metrics/'+encodeURIComponent(key),{role,period,page})};
