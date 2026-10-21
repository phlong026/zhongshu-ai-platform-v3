const X=require('./access'),T=require('./intercompany');
function receipt(record){
 const source=record?.source,a=X.context();
 if(source?.module!=='intercompany'||source.kind!=='design'||!['finance','finance-lead'].includes(a.role))return null;
 const c=(wx.getStorageSync('zhongshu-business-v3')?.customers||[]).find(c=>c.id===source.customerId&&c.designOrganizationId===a.companyId);
 if(!c)return null;
 const ready=T.condition(c,source.stage).ready;
 if(require('./sales-flow').is(c)){
  const next=require('./sales-flow').next(c);
  return {title:'设计业务衔接',rows:[{type:'row',label:'本期核对结果',value:ready?'本期款项已足额核对':'本期款项尚未足额核对'},{type:'row',label:'当前办理岗位',value:next.owner},{type:'notice',value:next.state},{type:'notice',value:'待办随有效到账和线下办理结果自动更新；线下设计由设计行政安排。'}]};
 }
 const state=!ready?'本期款项尚未足额核对，设计待办继续等待':source.stage==='first'?c.assignment?'已指定对接设计师，进入设计办理':'正式设计已就绪，待总部设计负责人指定设计师':c.release?'本版正式成果已下发':'尾款条件已满足，待授权设计人员正式下发';
 return {title:'设计业务衔接',rows:[{type:'row',label:'本期核对结果',value:ready?'本期款项已足额核对':'尚未满足本期设计条件'},{type:'row',label:'后续办理岗位',value:source.stage==='first'?'总部设计负责人':'获授权设计人员'},{type:'notice',value:state},{type:'notice',value:'后续设计待办随本期核对结果更新；由设计岗位在业务模块继续办理。'}]};
}
module.exports={receipt};
