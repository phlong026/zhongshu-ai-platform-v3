function sourceUrl(row,role,key,period,roleCode){
 const id=encodeURIComponent(row.source_id||''),customer=encodeURIComponent(row.customer_id||''),project=encodeURIComponent(row.project_id||''),base='/pages/build/record/index?id='+project;
 switch(row.source_type){
 case 'customer':return '/pages/workflow/index?view=customer&id='+customer;
 case 'budget':return '/pages/workflow/index?view=budget&id='+customer;
 case 'receivable':if(role==='owner')return '/pages/home-metrics/record?role=owner&metric='+key+'&period='+period+'&type=receivable&id='+encodeURIComponent(row.id);
 case 'contract':if(roleCode==='owner'&&row.source_type==='contract')return '/pages/home-metrics/record?role='+role+'&metric='+key+'&period='+period+'&type=contract&id='+id;
  return role==='finance'?'/pages/admin/finance-desk/index?view=flows&tab=receivables&homeStage='+encodeURIComponent(row.id):'/pages/workflow/index?view=contract&id='+customer+'&contractId='+id;
 case 'commission':return '/pages/workflow/commission/index?commissionId='+id+'&customerId='+customer+'&view=progress';
 case 'handover':return '/pages/workflow/commission/index?commissionId='+id+'&customerId='+customer+'&view='+(role==='engineering'?'receive':'progress');
 case 'publication':return '/pages/studio/index?view=detail&record='+encodeURIComponent(row.id);
 case 'project':return base+'&view=overview';
 case 'node':return row.milestone==='START'||(!row.milestone&&row.subtitle==='正式开工')?base+'&view=start':base+'&view=node&nodeId='+id;
 case 'material':return base+'&view=material&homeSupply='+id;
 case 'quality':return '/pages/build/issues/index?project='+project+'&supply='+id+'&receipt='+encodeURIComponent(row.id);
 case 'issue':return '/pages/build/issues/index?project='+project+'&issue='+id;
 case 'project-receivable': {
  const stage=({SIGN_AFTER:'start',BASE_DONE:'base',CAP_DONE:'topping',tail:'tail',签约后收款:'start',基础完成款:'base',封顶款:'topping',交付尾款:'tail'})[row.stage_code||row.subtitle];
  if(!stage)return base+'&view=payments';
  if(stage==='tail'&&['finance','finance-lead'].includes(roleCode)&&row.stage_id)return '/pages/admin/finance-desk/index?view=flows&tab=receivables&homeStage='+encodeURIComponent(row.stage_id);
  return base+'&view=collection&stage='+stage;
 }
 case 'declaration':return '/pages/admin/finance-desk/index?view=flows&tab=receivables&messageType=sale_collection_declaration&messageObject='+id;
 case 'receipt':case 'payment':case 'refund':case 'payable':case 'reimbursement':
  if(role==='business'||role==='owner')return '/pages/home-metrics/record?role='+role+'&metric='+encodeURIComponent(key)+'&period='+period+'&type='+row.source_type+'&id='+id;
  return '/pages/admin/finance-desk/index?view=flows&tab=ledger&scope=all&messageType=fin_'+(row.source_type==='reimbursement'?'payable':row.source_type)+'&messageObject='+id;
 default:return '';
 }
}
module.exports=sourceUrl;
