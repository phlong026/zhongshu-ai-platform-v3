const assert=require('assert/strict');
(async()=>{const r=await require('./finance-fullflow-runtime.cjs')();try{const {m,x}=r;await r.auth('finance');const home='/pages/admin/finance-desk/index',project='project:'+x.project.id;
 await r.nav(home);await r.until(async()=>!!await m.evaluate(()=>getCurrentPages().slice(-1)[0].data.journeyDeclarations?.length),'declarations ready');
 await r.shot('09-pending-home');
 if(!x.declarationReturned){await r.tap('.fin-task[data-key="fr-sale-finance:'+x.declarationId+'"]');await r.until(async()=>!!await m.evaluate(()=>getCurrentPages().slice(-1)[0].data.journeyDeclaration),'open pending declaration');await r.shot('10-owner-confirm');await r.modal('退回补充资料','');assert.ok((await r.data()).workbenchError);await r.modal('退回补充资料','请补充实际到账流水号');await r.until(async()=>!await m.evaluate(()=>getCurrentPages().slice(-1)[0].data.journeyDeclaration),'declaration returned');x.declarationReturned=true;r.save();}
 r.record('首页到账待办直达原申报，退回必须说明原因，完成后返回首页',{screenshot:'10-owner-confirm.png'});
 await r.auth('sales');await r.nav('/pages/workflow/index?view=contract&id='+x.customerId);await r.shot('11-sales-after-return');
 if(!x.resubmittedDeclarationId){const res=await r.api('POST','/franchise/sales/stages/'+x.stages[0].stageId+'/collect',{amountCents:100000,method:'bank',transactionNo:'FULL-OWNER-CORRECTED',receivedDate:'2026-11-17',accountName:'公司银行'},'fullflow-resubmit-1117');x.resubmittedDeclarationId=String(res.declarationId);r.save();}
 await r.auth('finance');await r.nav(home);
 if(!x.ownerReceiptId){await r.tap('.fin-task[data-key="fr-sale-finance:'+x.resubmittedDeclarationId+'"]');await r.until(async()=>!!await m.evaluate(()=>getCurrentPages().slice(-1)[0].data.journeyDeclaration),'resubmitted declaration');await r.submit('确认实际到账','ownerReceiptId');await r.shot('12-owner-confirmed');await r.click('返回财务首页');}
 r.record('业务补充后财务确认到账，仅生成一笔实收',{screenshot:'12-owner-confirmed.png'});
 await r.nav(home+'?view=flows&tab=receivables');
 if(!x.depositAllocated){await r.tap('button[data-id="'+x.stages[0].stageId+'"].fin-text');await r.until(async()=>(await r.data()).wbForm?.type==='allocation','allocation form');const idx=await m.evaluate(id=>getCurrentPages().slice(-1)[0].data.allocationReceipts.findIndex(q=>String(q.id)===id),x.depositReceiptId);assert.ok(idx>=0,'deposit is selectable');await r.pick('receiptId',idx);await r.click('保存登记');await r.until(async()=>!(await r.data()).wbForm,'allocation saved');x.depositAllocated=true;r.save();}
 r.record('前期定金抵扣合同款，不重复增加实收');
 if(!x.contractChanged){await r.tap('button[data-id="'+x.contractId+'"].fin-text');await r.until(async()=>(await r.data()).wbForm?.type==='contractchange','contract change');await r.input('tail','5000');await r.click('保存登记');assert.ok((await r.data()).wbError);await r.input('reason','业主确认增加院墙项目5000元');await r.click('保存登记');await r.until(async()=>!(await r.data()).wbForm,'contract changed');x.contractChanged=true;r.save();}
 r.record('合同增项及尾款同步，变更依据必填');
 await r.nav(home+'?view=book&bookScope='+encodeURIComponent(project));
 if(!x.directReceiptId){await r.click('登记收款');const i=await m.evaluate(id=>getCurrentPages().slice(-1)[0].data.journeyStages.findIndex(s=>String(s.id)===String(id)),x.stages[0].stageId);await r.pick('stageId',i);await r.input('amount','20000');await r.input('accountName','公司银行');await r.pick('date','2026-11-18');await r.click('确认登记收款');assert.ok((await r.data()).wbError);await r.pick('date','2026-11-17');await r.input('transactionNo','FULL-DIRECT-20000');await r.submit('确认登记收款','directReceiptId');await r.click('返回项目账本');}
 r.record('项目合同回款登记，项目与期次预选，未来日期拦截');
 await r.nav(home+'?view=book&bookScope='+encodeURIComponent(project));
 if(!x.clientRefundId){await r.tap('.book-transaction[data-key="RECEIPT:'+x.directReceiptId+'"]');await r.click('登记退给客户');await r.input('amount','20001');await r.input('reason','验收退款');await r.click('确认登记退款');assert.ok((await r.data()).registerError);await r.input('amount','200');await r.input('transactionNo','FULL-CLIENT-REFUND-200');await r.submit('确认登记退款','clientRefundId');await r.shot('13-client-refund');await r.click('返回项目账本');}
 r.record('客户退款沿原收款登记，超过余额拦截，返回原账本',{screenshot:'13-client-refund.png'});
 await r.auth('sales');await r.nav('/pages/workflow/index?view=contract&id='+x.customerId);await r.shot('14-sales-contract-final');await r.auth('finance');
 const stages=await r.api('GET','/franchise/sales/contracts/'+x.contractId+'/stage-summary');assert.equal(stages[0].collectedCents,2600000);assert.equal(stages.reduce((n,s)=>n+s.amountCents,0),30500000);r.record('业务合同与财务净收26000元一致，合同总额305000元');
 }catch(e){r.record('收款全流程异常',{message:e.message},false);await r.shot('error-receipts');throw e;}finally{r.close();}})().then(()=>process.exit(0),e=>{console.error(e.stack);process.exit(1)});
