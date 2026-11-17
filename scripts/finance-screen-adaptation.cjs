const fs=require('fs'),assert=require('assert/strict');
const out='docs/finance-screen-adaptation-20261117/',label=process.argv[2];
assert.ok(['small','large'].includes(label));
(async()=>{const r=await require('./finance-fullflow-runtime.cjs')(),checks=[],screens=[],geometry={};
 const {m,x}=r,home='/pages/admin/finance-desk/index',scope='project:'+x.project.id,book=home+'?view=book&bookScope='+encodeURIComponent(scope);
 const save=()=>fs.writeFileSync(out+label+'-result.json',JSON.stringify({passed:false,checks,screens,geometry},null,2));
 const pass=(name,detail={})=>{checks.push({name,passed:true,...detail});console.log('PASS '+name);save();};
 const shot=async name=>{const file=label+'-'+name+'.png';await m.screenshot({path:out+file});screens.push(file);};
 const boxes=async selectors=>m.evaluate(selectors=>new Promise(resolve=>{const q=wx.createSelectorQuery(),res={};for(const sel of selectors)q.selectAll(sel).boundingClientRect(v=>res[sel]=v);q.exec(()=>resolve(res));}),selectors);
 const within=(b,w)=>assert.ok(b.left>=-1&&b.right<=w+1,JSON.stringify(b));
 const footer=async name=>{await r.scroll(99999);await r.pause(250);const b=await boxes(['.journey-form .fin-primary','.journey-voucher','#finance-desk-nav']);geometry[name]=b;const button=b['.journey-form .fin-primary'][0];assert.ok(button,'submit button exists');within(button,system.windowWidth);const nav=b['#finance-desk-nav'][0];assert.ok(button.top>=0&&button.bottom<=(nav?nav.top:system.windowHeight)+1,JSON.stringify(b));await shot(name);};
 const back=async()=>{await r.click('返回项目账本');await r.until(async()=>!(await r.data()).bookLoading,'return loaded');const d=await r.data();assert.equal(d.view,'book');assert.equal(d.bookScope,scope);assert.equal(d.bookFlowTab,'cost');assert.equal(d.bookNet,'21,560.00');assert.equal(d.bookCost,'4,450.00');};
 let system;
 try{
  await r.auth('finance');await r.nav(home);system=await m.systemInfo();assert.equal(system.platform,'devtools');assert.equal(system.screenWidth,label==='small'?320:430);assert.equal(system.screenHeight,label==='small'?568:932);
  geometry.home=await boxes(['.fin-home-metric','.fin-home-value']);for(const rows of Object.values(geometry.home))for(const b of rows)within(b,system.windowWidth);
  const metrics=await m.evaluate(()=>getCurrentPages().at(-1).data.deskHome.metrics);assert.equal(metrics[2].value,'110,885.00');await shot('home');pass('实际模拟器机型、首页六位金额与指标卡边界',{model:system.model,width:system.screenWidth,height:system.screenHeight});
  await r.nav(book);assert.equal((await r.data()).bookNet,'21,560.00');await shot('book');await r.click('费用明细');
  await r.click('登记收款');await shot('receipt-top');await r.input('amount','1234567.89');await footer('receipt-bottom');await r.click('返回项目账本');await r.until(async()=>!(await r.data()).bookLoading,'receipt returned');assert.equal((await r.data()).bookFlowTab,'cost');assert.equal((await r.data()).bookScope,scope);pass('收款金额输入、滚动至提交按钮、返回原项目费用标签');
  await r.click('登记付款');await shot('payment-top');await r.click('没有原单，补记其他支出 ›');await r.input('amount','1234567.89');await footer('expense-bottom');await back();pass('付款与补记支出入口、提交按钮完整显示、返回原项目费用标签');
  await r.nav(home+'?view=reimbursements');const reim=await m.evaluate(()=>getCurrentPages().at(-1).data.journeyReimbursements.find(t=>t.remaining>0));assert.ok(reim,'pending reimbursement');await r.tap('button.fin-primary[data-id="'+reim.id+'"]');await shot('reimbursement-top');await footer('reimbursement-bottom');await r.click('返回报销列表');assert.equal((await r.data()).view,'reimbursements');pass('设计费报销原单、提交按钮、返回报销列表');
  await r.nav(home+'?view=book&bookScope='+encodeURIComponent(x.mobileBook));await r.tap('.book-transaction[data-key="RECEIPT:'+x.mobileReceipt+'"]');await r.click('登记退款');await shot('refund-top');await footer('refund-bottom');await r.click('返回项目账本');await r.until(async()=>!(await r.data()).bookLoading,'refund returned');assert.equal((await r.data()).bookScope,x.mobileBook);assert.equal((await r.data()).bookNet,'7.00');pass('退款沿原收款进入、提交按钮、返回原账本');
  await r.nav(book);await r.click('费用明细');await r.click('登记收款');await m.evaluate(()=>getCurrentPages().at(-1).__financeKeyboardHandler({height:280}));assert.equal(await(await m.currentPage()).$('#finance-desk-nav'),null);await back();assert.equal(await m.evaluate(()=>getCurrentPages().at(-1).data.financeKeyboardOpen),false);assert.ok(await(await m.currentPage()).$('#finance-desk-nav'));await shot('project-return');pass('键盘高度事件中返回账本，底部导航恢复，项目与标签保留',{method:'模拟器键盘事件回调检查，不代表手机系统键盘'});
  await r.auth('boss');await r.nav(home+'?view=project&id='+x.project.id);assert.equal(await m.evaluate(()=>getCurrentPages().at(-1).data.projectBookNet),'21,560.00');await shot('boss-book');pass('老板只读账本金额与财务一致');
  await r.auth('finance');await r.nav(home);await shot('home');
  fs.writeFileSync(out+label+'-result.json',JSON.stringify({passed:true,system,checks,screens:[...new Set(screens)],geometry,unchangedAmounts:{company:110885,project:21560,projectCost:4450,mobileBook:7},scope:'真实开发者工具机型切换；仅读取与取消表单，没有新增财务登记'},null,2));
 }catch(e){save();await shot('error');throw e;}finally{r.close();}
})().then(()=>process.exit(0),e=>{console.error(e.stack);process.exit(1)});
