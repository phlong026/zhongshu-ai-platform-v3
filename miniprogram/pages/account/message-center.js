const M = require('../../utils/platform-messages');
const modules = [{id:'',name:'全部模块'},{id:'customer',name:'客户与合同'},{id:'design',name:'设计委托'},{id:'engineering',name:'工程项目'},{id:'material',name:'材料与供货'},{id:'finance',name:'收支与报销'},{id:'performance',name:'绩效任务'},{id:'creation',name:'内容创作'},{id:'learning',name:'学习培训'},{id:'system',name:'系统通知'}];
function money(cents) {
  const raw=String(cents==null?'':cents);if(!/^-?\d+$/.test(raw))return '';
  const negative=raw[0]==='-',digits=raw.replace(/^-/, '').padStart(3,'0');
  return (negative?'-':'')+'¥'+digits.slice(0,-2).replace(/\B(?=(\d{3})+(?!\d))/g,',')+'.'+digits.slice(-2);
}
function row(raw) {
  if(!raw||!(/^(\d+|todo:.+)$/.test(String(raw.id))))return null;
  const c=raw.context||{},fields=(Array.isArray(c.fields)?c.fields:[]).filter(f=>f&&f.label&&f.value!==''&&f.value!=null).map(f=>({label:String(f.label),value:String(f.value)}));
  const stageNames={FULL:'全额设计费',FIRST:'设计首款',TAIL:'设计尾款',SIGN_AFTER:'签约阶段款',BASE_DONE:'基础阶段款',CAP_DONE:'封顶阶段款'};
  fields.forEach(f=>{if(f.label==='款项阶段'){f.label='款项 / 用途';f.value=stageNames[f.value]||f.value;}});
  const module=modules.find(x=>x.id===(raw.module||c.module)),amount=money(c.amountCents);
  const detail=[...fields];
  if(c.businessNo)detail.unshift({label:'业务编号',value:String(c.businessNo)});
  if(c.projectName&&c.customerName&&!detail.some(f=>f.label==='关联项目'))detail.unshift({label:'关联项目',value:String(c.projectName)});
  if(amount)detail.unshift({label:c.amountLabel||(c.simulated?'模拟金额':'本次金额'),value:amount});
  if(c.actorName)detail.push({label:'经办人',value:String(c.actorName)});
  if(c.orgName)detail.push({label:'所属组织',value:String(c.orgName)});
  const title=String(raw.title||'业务通知').replace(/设计费(FULL|FIRST|TAIL)/g,(_,s)=>({FULL:'设计费全额',FIRST:'设计费首款',TAIL:'设计费尾款'}[s])),subject=String(c.customerName||c.projectName||c.taskName||'');
  return {id:String(raw.id),type:M.TYPE_LABELS[raw.type]||raw.type||'系统',route:raw.route||'',objectId:raw.objectId||'',title,subject,
    description:String(raw.description||''),date:M.formatTime(raw.createTime),read:!!raw.read,module:module?module.name:'业务通知',
    kind:raw.kind||'NOTICE',stateLabel:raw.stateLabel||(raw.actionable?'待处理':'仅知会'),actionable:!!raw.actionable,
    actionPath:raw.actionPath||'',actionLabel:raw.actionLabel||'',notice:String(c.notice||''),historyNote:String(c.historyNote||''),
    reason:String(c.recipientReason||''),amount,amountLabel:c.amountLabel||(c.simulated?'模拟金额':raw.actionable?'待处理金额':'金额'),simulated:!!c.simulated,
    businessNo:String(c.businessNo||''),summary:fields.slice(0,3),details:detail,orgName:String(c.orgName||''),context:c};
}
function rows(page){return require('../../api/http').token()?(page.apiMessages||[]):[];}
module.exports={row,money,modules,rows};
