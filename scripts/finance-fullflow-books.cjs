const assert=require('assert/strict');
(async()=>{const r=await require('./finance-fullflow-runtime.cjs')();try{const {m,x}=r;await r.auth('finance');
 const home='/pages/admin/finance-desk/index',list=home+'?view=projects';
 await r.nav(home);await r.click('收支流水');await r.until(async()=>(await r.data()).view==='flows','home shortcut route');assert.equal((await r.data()).view,'flows');r.record('首页快捷流水进入实际收支');
 await r.tap('.journey-flow-tabs .book-tab[data-tab="receivables"]');assert.equal(await m.evaluate(()=>getCurrentPages().slice(-1)[0].data.flowTab),'receivables');await r.tap('.journey-flow-tabs .book-tab[data-tab="payables"]');await r.tap('.journey-flow-tabs .book-tab[data-tab="reconcile"]');await r.tap('.journey-flow-tabs .book-tab[data-tab="ledger"]');r.record('流水、业主收款、待付事项、对账四标签切换');
 await r.nav(list);for(const tab of ['项目','前期客户','公司日常','全部'])await r.click(tab);r.record('账本四种分类可切换');
 if(!x.earlyBookId){await r.click('新建账本');await r.click('保存登记');assert.ok((await r.data()).wbError);await r.input('name','全流程验收·前期定金');await r.click('保存登记');await r.until(async()=>!(await r.data()).wbForm,'create book');const books=await r.api('GET','/franchise/finance/workbench/books');x.earlyBookId=books.find(b=>b.name==='全流程验收·前期定金').scope.slice(5);r.save();}
 r.record('前期客户账本新建、空名称拦截');
 const early='book:'+x.earlyBookId,project='project:'+x.project.id;
 await r.nav(home+'?view=book&bookScope='+encodeURIComponent(early));await r.modal('口径');r.record('账目口径可打开关闭');
 if(!x.depositReceiptId){await r.click('登记收款');assert.equal((await r.data()).wbForm.receiptMode,'other');await r.click('确认登记收款');assert.ok((await r.data()).wbError);await r.input('name','全流程验收·前期定金');await r.input('amount','5000');await r.input('counterparty','李先生');await r.input('accountName','公司银行');await r.input('transactionNo','FULL-DEPOSIT-5000');await r.voucher('添加凭据（选填） ›');await r.submit('确认登记收款','depositReceiptId');await r.scroll(0);await r.shot('03-deposit-success');await r.click('查看本笔记录');await r.until(async()=>!!await m.evaluate(()=>getCurrentPages().slice(-1)[0].data.bookEntryDetail),'entry result');assert.equal(await m.evaluate(()=>getCurrentPages().slice(-1)[0].data.bookEntryDetail.id),x.depositReceiptId);await r.click('关闭');await r.click('返回项目账本');}
 r.record('前期定金登记、必填拦截、真实附件上传、成功查看原记录',{screenshot:'03-deposit-success.png'});
 await r.nav(home+'?view=book&bookScope='+encodeURIComponent(early));
 await r.tap('.book-transaction[data-key="RECEIPT:'+x.depositReceiptId+'"]');await r.pause(200);await r.shot('04-deposit-detail');
 const detail=await m.evaluate(()=>getCurrentPages().slice(-1)[0].data.bookEntryDetail);assert.ok(detail.voucher_file_id,'uploaded voucher ID');x.voucherId=String(detail.voucher_file_id);r.save();
 await r.modal('关闭'); // No modal on this button; restoring the mock keeps later native prompts normal.
 if(!x.depositRevised){await r.tap('.book-transaction[data-key="RECEIPT:'+x.depositReceiptId+'"]');await r.click('修订金额');await r.input('amount','0');await r.click('提交修订');assert.ok((await r.data()).registerError);await r.input('amount','5200');await r.input('note','验收：据实际银行到账更正为5200元');await r.click('提交修订');await r.until(async()=>!(await r.data()).registerType,'receipt revision');x.depositRevised=true;r.save();}
 r.record('账本收款修订金额及原因、零金额拦截、返回原账本');
 await r.nav(home+'?view=book&bookScope='+encodeURIComponent(early));
 if(!x.bookLinked){await r.click('关联工程');const idx=await m.evaluate(s=>getCurrentPages().slice(-1)[0].data.workbenchBooks.findIndex(b=>b.scope===s),project);await r.pick('scope',idx);await r.click('保存登记');await r.until(async()=>!(await r.data()).wbForm,'link book');x.bookLinked=true;r.save();}
 assert.equal((await r.data()).bookScope,project);r.record('前期账本关联工程，已登记资金随项目归集');await r.shot('05-linked-project');
 await r.click('合同回款');await r.pause(600);await r.shot('06-contract-link');r.record('项目账本合同回款入口',{screenshot:'06-contract-link.png',state:await r.data()});
 await r.nav(home+'?view=book&bookScope='+encodeURIComponent(project));await r.click('材料采购');await r.pause(700);await r.shot('07-material-link');r.record('项目账本材料采购入口',{screenshot:'07-material-link.png',state:await r.data()});
 await r.nav(home+'?view=book&bookScope='+encodeURIComponent(project));await r.click('工程费用');await r.pause(700);await r.shot('08-expense-link');r.record('项目账本工程费用入口',{screenshot:'08-expense-link.png',state:await r.data()});
 }catch(e){r.record('前期账本流程异常',{message:e.message},false);await r.shot('error-books');throw e;}finally{r.close();}})().then(()=>process.exit(0),e=>{console.error(e.stack);process.exit(1)});
