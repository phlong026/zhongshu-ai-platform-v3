const assert=require('assert/strict');
(async()=>{const r=await require('./finance-fullflow-runtime.cjs')();try{
 const {m,x}=r,home='/pages/admin/finance-desk/index',book=home+'?view=book&bookScope=project%3A'+x.project.id;
 await r.auth('finance-lead');await r.nav(book);
 if(!x.finalRefundId){
  await r.tap('.book-transaction[data-key="REFUND:'+x.refundRetestId+'"]');
  await r.modal('作废重登');
  await r.until(async()=>(await r.data()).registerType==='refund','correction form');
  const f=(await r.data()).registerForm;
  assert.equal(f.date,'2026-11-17');
  assert.ok(f.receiptIdLabel&&!f.receiptIdLabel.includes('#'));
  assert.equal(await m.evaluate(()=>!!getCurrentPages().at(-1).data.bookEntryDetail),false);
  await r.shot('25-correction-fixed');
  await r.submit('确认登记退款','finalRefundId');
  x.previousRefundRetestId=x.refundRetestId;x.refundRetestId=x.finalRefundId;r.save();
  await r.click('返回项目账本');
 }
 r.record('更正表单日期统一YYYY-MM-DD，原收款展示项目与用途而非长编号',{screenshot:'25-correction-fixed.png'});
 await r.auth('finance');await r.nav(home);await r.shot('29-finance-home-final');
 }finally{r.close();}})().then(()=>process.exit(0),e=>{console.error(e.stack);process.exit(1)});
