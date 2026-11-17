const assert=require('assert/strict');
(async()=>{const r=await require('./finance-fullflow-runtime.cjs')();try{const {m,x}=r,home='/pages/admin/finance-desk/index',book=home+'?view=book&bookScope=project%3A'+x.project.id,sales='/pages/workflow/index?view=contract&id='+x.customerId;
 if(process.argv[2]!=='navigation'){
 await r.auth('sales');await r.nav(sales);
 if(!x.uiDeclaration){await r.tap('button[data-stage-id="'+x.stages[0].stageId+'"]');await r.input('amount','10','data-key');await r.input('transactionNo','FULL-UI-OWNER-10-V2','data-key');await r.click('确认办理回款');assert.equal((await r.data()).error,'');const cs=await r.api('GET','/franchise/sales/contracts?customerId='+x.customerId);x.uiDeclaration=String(cs[0].declarations.find(d=>d.transaction_no==='FULL-UI-OWNER-10-V2').id);r.save();}
 await r.shot('23-sales-pending');await r.auth('finance');await r.nav(home+'?view=flows&tab=receivables');
 if(!x.uiReturned){await r.tap('.journey-progress[data-id="'+x.uiDeclaration+'"]');await r.modal('退回补充资料','验收：请补充银行流水号');x.uiReturned=true;r.save();}
 await r.auth('sales');await r.nav(sales);
 if(!x.uiResubmitted){await r.shot('24-sales-return-fixed');const rows=await m.evaluate(()=>getCurrentPages().at(-1).data.salesContracts.flatMap(c=>c.declarations));assert.ok(rows.some(d=>d.reason==='验收：请补充银行流水号'));await r.tap('button[data-declaration-id="'+x.uiDeclaration+'"]');const f=await m.evaluate(()=>getCurrentPages().at(-1).data.collectForm);assert.equal(Number(f.amount),10);await r.input('transactionNo','FULL-UI-CORRECTED-10-V2','data-key');await r.click('确认办理回款');const cs=await r.api('GET','/franchise/sales/contracts?customerId='+x.customerId);x.uiResubmitted=String(cs[0].declarations.find(d=>d.transaction_no==='FULL-UI-CORRECTED-10-V2').id);r.save();}
 await r.auth('finance');await r.nav(home+'?view=flows&tab=receivables');
 if(!x.uiReceipt){await r.tap('.journey-progress[data-id="'+x.uiResubmitted+'"]');await r.submit('确认实际到账','uiReceipt');}
 r.record('修复复测：业务原生页面申报→财务退回→合同页显示原因→预填原金额重新提交→财务确认',{screenshot:'24-sales-return-fixed.png'});
 await r.auth('finance-lead');await r.nav(book);
 if(!x.refundRetestId){await r.tap('.book-transaction[data-key="REFUND:'+x.correctedRefund+'"]');await r.modal('作废重登');await r.until(async()=>(await r.data()).registerType==='refund','retest correction');assert.equal(await m.evaluate(()=>!!getCurrentPages().at(-1).data.bookEntryDetail),false);assert.equal(await m.evaluate(()=>getCurrentPages().at(-1).data.journeyReturnLabel),'返回项目账本');await r.shot('25-correction-fixed');await r.submit('确认登记退款','refundRetestId');await r.click('返回项目账本');}
 r.record('修复复测：退款更正不再被旧详情遮挡，成功后返回原项目账本',{screenshot:'25-correction-fixed.png'});
 await r.auth('finance');await r.nav(home+'?view=flows&tab=payables');
 if(!x.mistakeClosed){const payments=await r.api('GET','/franchise/finance/payments');const q=String(payments.find(p=>String(p.id)===x.mistakePayment).payableId);await r.tap('.journey-two-actions button[data-id="'+q+'"]');await r.input('amount','0');await r.input('reason','验收：误录费用一并冲减');await r.click('保存登记');await r.until(async()=>!(await r.data()).wbForm,'mistake closed');x.mistakeClosed=true;r.save();}
 r.record('误录付款撤销后将原误录费用调整为零，无残留待付');
 }
 await r.auth('finance');await r.nav(home);let p=await m.currentPage(),component;
 const openMonth=async()=>{p=await m.currentPage();component=await p.$('#finance-month');await(await component.$('.month-trigger')).tap();await r.pause(300);};
 const chooseMonth=async value=>{await(await component.$('.month-cell[data-value="'+value+'"]')).tap();await r.pause(200);};
 await openMonth();await r.shot('26-month-picker');await chooseMonth('2026-10');await(await component.$('.month-cancel')).tap();assert.equal(await m.evaluate(()=>getCurrentPages().at(-1).data.deskHome.period),'2026-11');
 const initial=await m.evaluate(()=>getCurrentPages().at(-1).data.deskHome.taskCount);await openMonth();await chooseMonth('2026-10');await(await component.$('.month-confirm')).tap();await r.pause(2000);assert.equal(await m.evaluate(()=>getCurrentPages().at(-1).data.deskHome.period),'2026-10');assert.equal(await m.evaluate(()=>getCurrentPages().at(-1).data.deskHome.taskCount),initial);await openMonth();await chooseMonth('2026-11');await(await component.$('.month-confirm')).tap();await r.pause(1700);r.record('月份弹层取消与确认、YYYY-MM显示、历史月零值与待办独立',{screenshot:'26-month-picker.png'});

 await r.nav(home+'?view=projects');await(await r.el('input[placeholder="搜索项目或账本"]')).input('李先生');await r.pause(300);assert.equal(await m.evaluate(()=>getCurrentPages().at(-1).data.financeProjects.length),1);await r.click('清空');
 await r.nav(home+'?view=flows&tab=ledger');await(await r.el('input[placeholder="搜索项目、对方或用途"]')).input('全流程验收');await r.pause(300);assert.ok(await m.evaluate(()=>getCurrentPages().at(-1).data.journeyCashRows.length>0));await r.click('清空');r.record('账本搜索与流水搜索、清空筛选可用');
 await r.nav(home+'?view=flows&tab=reconcile');await r.shot('27-reconciliation');await r.modal('完成对账并留痕');assert.equal((await r.data()).ledgerError,'');await r.click('在电脑端核对');r.record('月末对账可核对净实收并留痕；电脑入口给出配置提示');
 await r.nav(home+'?view=profile');await r.shot('28-finance-profile');r.record('财务我的页面与工作台导航可达');
 }catch(e){r.record('修复及导航复测异常',{message:e.message},false);await r.shot('error-retest');throw e;}finally{r.close();}})().then(()=>process.exit(0),e=>{console.error(e.stack);process.exit(1)});
