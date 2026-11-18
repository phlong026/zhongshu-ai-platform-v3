const assert=require('assert/strict'),fs=require('fs');
const out='docs/finance-device-tools-20261117/';
(async()=>{const r=await require('./finance-fullflow-runtime.cjs')();const checks=[];const pass=(name,detail={})=>{checks.push({name,passed:true,...detail});console.log('PASS '+name);fs.writeFileSync(out+'checks.json',JSON.stringify(checks,null,2));};
try{const {m,x}=r,home='/pages/admin/finance-desk/index';
 const shot=async name=>m.screenshot({path:out+name+'.png'});
 const voucher=async(mode='photo')=>{
  const bytes=fs.readFileSync('miniprogram/assets/book.png');
  const path=await m.evaluate(b64=>{const p=wx.env.USER_DATA_PATH+'/finance-mobile-voucher.png';wx.getFileSystemManager().writeFileSync(p,b64,'base64');return p;},bytes.toString('base64'));
  await m.mockWxMethod('showActionSheet',{tapIndex:mode==='photo'?0:1});
  const method=mode==='photo'?'chooseMedia':'chooseMessageFile';
  await m.mockWxMethod(method,{tempFiles:[mode==='photo'?{tempFilePath:path,size:bytes.length,fileType:'image'}:{path,name:'界面验收凭证.png',size:bytes.length,type:'image'}]});
  try{await r.click('添加凭据（选填） ›');}finally{await m.restoreWxMethod(method);await m.restoreWxMethod('showActionSheet');}
 };
 await r.auth('finance');await r.nav(home);const system=await m.systemInfo();assert.equal(system.platform,'devtools');
 fs.writeFileSync(out+'environment.json',JSON.stringify({platform:system.platform,model:system.model,screenWidth:system.screenWidth,screenHeight:system.screenHeight,SDKVersion:system.SDKVersion,scope:'按用户要求在微信开发者工具中验收页面与真实隔离后端；非物理手机'},null,2));
 if(!x.mobileBook){await r.nav(home+'?view=projects');await r.click('新建账本');await r.input('name','开发者工具·移动界面验收');await r.click('保存登记');await r.until(async()=>!(await r.data()).wbForm,'book saved');const books=await r.api('GET','/franchise/finance/workbench/books');x.mobileBook=books.find(b=>b.name==='开发者工具·移动界面验收').scope;r.save();}
 const book=home+'?view=book&bookScope='+encodeURIComponent(x.mobileBook);
 await r.nav(book);
 if(!x.mobileReceipt){await r.click('登记收款');await r.input('name','界面验收收款');await r.input('amount','20');await r.input('counterparty','验收付款人');await r.input('accountName','公司银行');
  await voucher();assert.ok((await r.data()).wbForm.voucherPath);await shot('01-receipt-photo');
  await m.evaluate(()=>getCurrentPages().at(-1).__financeKeyboardHandler({height:300}));assert.equal(await m.evaluate(()=>getCurrentPages().at(-1).data.financeKeyboardOpen),true);assert.equal(await(await m.currentPage()).$('#finance-desk-nav'),null);await shot('02-keyboard-event');
  await m.evaluate(()=>getCurrentPages().at(-1).__financeKeyboardHandler({height:0}));assert.equal((await r.data()).wbForm.amount,'20');
  await r.submit('确认登记收款','mobileReceipt');await r.click('返回项目账本');assert.equal((await r.data()).bookScope,x.mobileBook);
 }
 pass('收款选择相册/拍照来源，经真实资产接口上传并登记20元，成功返回原账本');
 pass('键盘高度事件300→0：底部导航隐藏恢复，金额与账本保留',{method:'调用真实页面键盘回调，模拟器不呈现手机系统键盘'});
 for(const [key,name,amount,employee] of [['mobilePayment','界面验收付款','5',false],['mobileAdvance','界面验收员工垫付','6',true]])if(!x[key]){
  await r.nav(book);await r.click('登记付款');await r.click('没有原单，补记其他支出 ›');await r.input('name',name);await r.input('amount',amount);await r.input('counterparty','验收服务商');await r.input('accountName',employee?'员工个人账户':'公司银行');
  if(employee){await r.pick('fundingType',1);const i=await m.evaluate(id=>getCurrentPages().at(-1).data.workbenchStaff.findIndex(s=>String(s.id)===String(id)),r.actors.sales.userId);assert.ok(i>=0);await r.pick('payerUserId',i);}
  await voucher('chat');await r.submit('登记付款',key);await r.click('返回项目账本');assert.equal((await r.data()).bookScope,x.mobileBook);
 }
 pass('公司付款5元与员工垫付6元分别登记，聊天凭证上传，返回原账本');
 const reimbursements=await r.api('GET','/franchise/finance/workbench/reimbursements'),reim=reimbursements.find(t=>String(t.origin_payment_id)===x.mobileAdvance);assert.ok(reim);x.mobileReimbursement=String(reim.payable_id);r.save();
 if(!x.mobileReimbursed){await r.nav(home+'?view=reimbursements');await r.tap('button[data-id="'+x.mobileReimbursement+'"]');await r.input('accountName','公司银行');await voucher();await shot('03-reimbursement');await r.submit('确认登记报销','mobileReimbursed');await r.click('返回报销列表');}
 pass('员工垫付生成报销，原应付登记6元报销、上传凭证，返回报销列表');
 if(!x.mobileRefund){await r.nav(book);await r.tap('.book-transaction[data-key="RECEIPT:'+x.mobileReceipt+'"]');await r.click('登记退款');await r.input('amount','2');await r.input('reason','界面验收退款');await voucher('chat');await shot('04-refund');await r.submit('登记退款','mobileRefund');await r.click('返回项目账本');assert.equal((await r.data()).bookScope,x.mobileBook);}
 pass('沿原收款登记2元退款并上传凭证，返回原账本');
 await r.nav(book);assert.equal((await r.data()).bookNet,'7.00');assert.equal((await r.data()).bookCost,'11.00');await shot('05-book-final');
 const ledger=await r.api('GET','/franchise/finance/workbench/ledger?scope='+encodeURIComponent(x.mobileBook));
 for(const id of [x.mobileReceipt,x.mobilePayment,x.mobileAdvance,x.mobileReimbursed,x.mobileRefund])assert.ok(ledger.entries.some(e=>String(e.id)===id&&e.voucher_file_id),'voucher linked '+id);
 pass('5笔实际登记均关联服务端凭证；新账本结余7元、费用11元，员工垫付与报销不重复计成本');
 fs.writeFileSync(out+'ledger-result.json',JSON.stringify(ledger,null,2));
 const originalBook=home+'?view=book&bookScope=project%3A'+x.project.id;await r.nav(originalBook);await r.click('费用明细');
 for(const label of ['登记收款','登记付款']){await r.click(label);await m.evaluate(()=>getCurrentPages().at(-1).__financeKeyboardHandler({height:280}));await r.click('返回项目账本');await r.until(async()=>!(await r.data()).bookLoading,'return loaded');assert.equal((await r.data()).bookScope,'project:'+x.project.id);assert.equal((await r.data()).bookFlowTab,'cost');assert.equal(await m.evaluate(()=>getCurrentPages().at(-1).data.financeKeyboardOpen),false);}
 await shot('06-project-return');pass('原项目收付款表单在键盘状态下返回，保持项目、费用标签和原金额');
 await r.nav(home);await shot('07-home-final');
 const metrics=await m.evaluate(()=>getCurrentPages().at(-1).data.deskHome.metrics);assert.equal(metrics[2].value,'110,885.00');
 fs.writeFileSync(out+'result.json',JSON.stringify({passed:true,checks,netYuan:7,costYuan:11,companyNetYuan:110885,originalProjectNetYuan:21560,originalProjectCostYuan:4450,sourceChooser:'自动化提供真实PNG文件；系统相册/相机选择回调模拟；上传票据、字节上传、完成、关联和财务登记均真实执行',metrics},null,2));
}catch(error){fs.writeFileSync(out+'last-error.json',JSON.stringify({error:error.message},null,2));await r.m.screenshot({path:out+'error.png'});throw error;}finally{r.close();}})().then(()=>process.exit(0),e=>{console.error(e.stack);process.exit(1)});
