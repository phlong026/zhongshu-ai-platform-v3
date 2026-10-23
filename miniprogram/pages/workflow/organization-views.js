const X=require('../../utils/access'),M=require('../../utils/commercial-approval');
const section=(title,items)=>({title,items}),notice=value=>({type:'notice',value}),kv=(label,value)=>({type:'kv',label,value}),area=key=>({type:'textarea',key}),check=(label,key)=>({type:'check',label,key});
function decorate(view,c,f,out,record){const a=X.context(),person=(id,org=c.companyId||'quanzhou')=>X.rawBook(org)?.staff.find(p=>p.id===id)?.name||'历史登记人';require('./task-views').decorate(view,c,f,out,record);
 if(view==='money-review'){
  const kind=record||'quote',r=M.latest(c,kind),design=kind==='service',money=value=>'¥ '+Number(value).toLocaleString('zh-CN');
  const amounts=design&&r?[kv('设计总金额',money(r.total)),kv('首付款金额',money(r.snapshot.first)),kv('设计尾款金额',money(r.snapshot.tail)),kv('交付范围',r.snapshot.basis||'—'),kv('实际收款方',r.snapshot.recipient||'—'),notice('本次一并审批总金额、首付款及尾款约定。批准后按约定登记线下付款，尾款不再重复申请金额审批。')]:[kv('申请金额',r?money(r.total):'—')];
  out.title='老板金额审批';out.sections=[section(r?.name||'金额事项',[kv('客户',c.name),kv('版本',r?'V'+r.version:'尚未提交'),...amounts,kv('申请人',r?person(r.submittedById,r.snapshot.companyId||c.companyId):'—'),kv('状态',({pending:'待老板审批',approved:'老板已批准',returned:'老板已退回'})[r?.status]||'无当前申请'),notice(r?.note||'审批结论绑定本版金额及依据。金额或依据变化后须重新审批。'),...(r?.snapshot.file?[{type:'file',name:r.snapshot.file.name,file:r.snapshot.file}]:[])]),...(r?.status==='pending'?[section('老板审批',[{type:'choice',label:'结论',key:'moneyDecision',values:['批准','退回']},area('moneyNote'),check(design?'已一并核对设计总金额、首付款、尾款及本版约定':'已核对本版金额、约定及依据','moneyChecked')])]:[])];out.primary=r?.status==='pending'&&a.role==='boss'?'保存老板审批':'';out.secondary='';return;
 }
}
module.exports={decorate};
