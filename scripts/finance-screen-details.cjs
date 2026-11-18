const fs=require('fs'),assert=require('assert/strict'),label=process.argv[2],out='docs/finance-screen-adaptation-20261117/';
(async()=>{const r=await require('./finance-fullflow-runtime.cjs')(),result=JSON.parse(fs.readFileSync(out+label+'-result.json','utf8'));try{
 const {m,x}=r,home='/pages/admin/finance-desk/index',system=await m.systemInfo();assert.equal(system.screenWidth,label==='small'?320:430);
 const shot=async name=>{const file=label+'-'+name+'.png';await m.screenshot({path:out+file});if(!result.screens.includes(file))result.screens.push(file);};
 const boxes=async sel=>m.evaluate(sel=>new Promise(resolve=>wx.createSelectorQuery().selectAll(sel).boundingClientRect(resolve).exec()),sel);
 const pass=name=>{if(!result.checks.some(c=>c.name===name))result.checks.push({name,passed:true});console.log('PASS '+name);};
 await r.auth('finance');await r.nav(home);
 const metrics=[];for(const card of await(await m.currentPage()).$$('.fin-home-metric'))for(const text of await card.$$('text')){const pos=await text.offset(),size=await text.size();metrics.push({...pos,...size,right:pos.left+size.width});}assert.equal(metrics.length,8);result.geometry.homeText=metrics;for(const b of metrics){assert.ok(b.left>=0&&b.right<=system.windowWidth);}
 const task=await m.evaluate(()=>getCurrentPages().at(-1).data.deskHome.previewTasks.find(t=>t.name.includes('材料采购')));assert.ok(task);await r.tap('button.fin-task[data-key="'+task.key+'"]');assert.equal((await r.data()).registerType,'payment');assert.equal((await r.data()).registerForm.amount,'350.00');await shot('purchase-top');
 await r.scroll(99999);const submit=(await boxes('.journey-form .fin-primary'))[0],nav=(await boxes('#finance-desk-nav'))[0];assert.ok(submit.top>=0&&submit.bottom<=nav.top+1);result.geometry.purchaseButton=submit;await shot('purchase-bottom');
 const returnLabel=await m.evaluate(()=>getCurrentPages().at(-1).data.journeyReturnLabel);await r.click(returnLabel);assert.equal((await r.data()).view,'desk');pass('工程采购待办直达原应付350元、提交按钮无遮挡、返回财务首页');
 await r.nav(home+'?view=book&bookScope=project%3A'+x.project.id);const money=await boxes('.book-metric-value');assert.equal(money.length,4);for(const b of money)assert.ok(b.left>=0&&b.right<=system.windowWidth);result.geometry.bookMoney=money;
 await r.scroll(99999);const rows=await boxes('.book-transaction'),bottom=(await boxes('#finance-desk-nav'))[0];assert.ok(rows.length);assert.ok(rows.at(-1).bottom<=bottom.top+1);
 const header=await m.evaluate(()=>new Promise(resolve=>getCurrentPages().at(-1).selectComponent('#finance-page-header').createSelectorQuery().select('.header').boundingClientRect(resolve).exec()));assert.ok(Math.abs(header.top)<1,'header remains fixed at viewport top');assert.ok(header.bottom>system.statusBarHeight);result.geometry.scrolledHeader=header;await shot('book-bottom');pass('项目账本金额与最后一条流水可见，不被底部导航遮挡');pass('账本滚动后顶部导航固定，流水不再穿过微信胶囊区域');
 await r.nav(home);await r.scroll(99999);await shot('home-bottom');pass('首页快捷办理与正在办理可滚动到达');
 await r.scroll(0);fs.writeFileSync(out+label+'-result.json',JSON.stringify(result,null,2));
 }finally{r.close();}})().then(()=>process.exit(0),e=>{console.error(e.stack);process.exit(1)});
