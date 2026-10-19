// The same eligible task categories power home shortcuts and the handling list.
const groups={
 incoming:['incoming-register'],pay:['pay'],refund:['refund-execute'],
 supplement:['receipt-supplement','pay-supplement','payment-supplement','refund-supplement','cross-evidence','refund-request','correction-request'],
 reconcile:['reconcile'],review:['payment-review','verify'],refundReview:['refund-review','correction-approval'],transfer:['finance-transfer']
};
function filter(tasks,type){return groups[type]?tasks.filter(t=>groups[type].includes(t.view)):tasks;}
const titles={incoming:'收款登记',pay:'付款登记',refund:'退款办理',supplement:'原单补证',reconcile:'收款核算',review:'付款复核',refundReview:'退款及更正复核',transfer:'经办交接'};
function entries(role,tasks=[]){
 const operations=role==='finance-lead'?
 [['reconcile','收款核算','独立核对到账','chart'],['review','付款复核','核对原笔实付','money'],['refundReview','退款及更正复核','核对原单依据','folder'],['transfer','经办交接','原笔责任转交','team']]:
 [['incoming','收款登记','核对实际到账','money'],['pay','付款登记','按已批原单办理','money'],['refund','退款办理','办理已批准退款','folder'],['supplement','原单补证','补齐退回资料','folder']];
 return operations.map(([taskType,label,description,icon])=>({key:taskType,taskType,label,description,icon,view:'todos',count:filter(tasks,taskType).length})).concat([
  {key:'todos',label:'全部待办',description:'查看本人办理事项',icon:'chart',view:'todos',count:tasks.length},
  {key:'finance',label:'收支明细',description:'查有效收款与付款',icon:'money',view:'finance'},
  {key:'projects',label:'项目收支',description:'按项目核对收支',icon:'folder',view:'projects'},
  {key:'plans',label:'收付款计划',description:'跟进近期款项安排',icon:'calendar',view:'plans'}
 ]);
}
module.exports={filter,entries,titles};
