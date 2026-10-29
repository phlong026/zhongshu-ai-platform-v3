const assert=require('assert/strict');

(async()=>{
 await require('./role-page-runtime.cjs').connect();
 const rows=require('../qa/business-module-simplification-20261021/completed-fixture.json');
 for(const[k,v]of rows)wx.setStorageSync(k,JSON.parse(JSON.stringify(v)));
 const A=require('../miniprogram/utils/account'),M=require('../miniprogram/pages/admin/model'),V=require('../miniprogram/pages/admin/finance-views'),D=require('../miniprogram/utils/finance-desk'),X=require('../miniprogram/utils/access');
 A.mutate(s=>A.loginDemo(s,'hq-finance'));
 const s=M.visible(M.load()),receipt=s.receipts.find(r=>r.source?.module==='intercompany'&&r.source.kind==='design');
 assert(receipt,'fixture must include headquarters design receipt');

 const incoming=V.config('incoming-register',s,{},receipt.id),source=incoming.sections.find(x=>x.title==='付款来源'),organization=source.rows.find(x=>x.label==='付款组织'),notice=incoming.sections.flatMap(x=>x.rows).find(x=>x.type==='notice'&&/下发待办/.test(x.value));
 assert.equal(organization.value,D.companyName(receipt.source.fromCompanyId));
 assert.notEqual(organization.value,receipt.source.fromCompanyId);
 assert.match(notice.value,/设计完成/);
 assert.match(notice.value,/当前首尾款均足额到账/);

 const detail=V.config('receipt',s,{},receipt.id),actions=detail.sections.flatMap(x=>x.rows).filter(x=>['refund-request','correction-request'].includes(x.view));
 assert.equal(X.admin('refund-request',receipt),false);
 assert.equal(X.admin('correction-request',receipt),false);
 assert.deepEqual(actions,[]);
 assert(!detail.sections.some(x=>x.title==='原收款后续处理'),'无合法操作时不应显示空分组');
 console.log('PASS 到账页显示组织名、完整自动条件，并隐藏已禁用后续入口');

 for(const kind of ['refund','material-advance','design']){
  const legacy={...receipt,id:'legacy-'+kind,status:'待登记',source:{...receipt.source,kind,protocol:undefined}};
  const config=V.config('incoming-register',{...s,receipts:[legacy]}, {},legacy.id),rows=config.sections.flatMap(section=>section.rows);
  assert.equal(config.title,'收款登记','历史往来不能套用新设计协议');
  assert.equal(config.primary,'登记实际到账');
  assert(!rows.some(row=>row.label==='本次最多可确认'),'历史往来允许保留未分配实收，不套用本轮金额上限');
  assert(!rows.some(row=>row.type==='notice'&&/总部实际到账|设计行政|首尾款/.test(row.value)),'历史回款须描述本组织到账，不伪造行政放行');
 }
 console.log('PASS 历史设计、退款和材料往来沿用本组织收款语义');
})().catch(error=>{console.error(error.stack);process.exitCode=1;});
