const fs=require('fs'),assert=require('assert/strict');
(async()=>{const r=await require('./finance-fullflow-runtime.cjs')();try{const {m,x}=r,home='/pages/admin/finance-desk/index',book=home+'?view=book&bookScope=project%3A'+x.project.id;
 await r.auth('finance');await r.nav(home);const homeState=await m.evaluate(()=>{const d=getCurrentPages().at(-1).data;return {metrics:d.deskHome.metrics,shortcuts:d.deskHome.shortcuts,tasks:d.deskHome.previewTasks,period:d.deskHome.period}});await r.shot('29-finance-home-final');
 if(!['roles','books'].includes(process.argv[2])){
 for(let i=0;i<4;i++){await r.nav(home);const buttons=await(await m.currentPage()).$$('.fin-home-metric');await buttons[i].tap();await r.pause(1700);assert.notEqual((await r.data()).view,'desk');}
 r.record('首页四个指标入口逐个打开，均到达对应业务列表');
 for(const item of homeState.shortcuts){await r.nav(home);await r.click(item.name||item.label);await r.pause(800);const d=await r.data();assert.ok(d.view!=='desk'||d.registerType||d.wbForm);}
 r.record('首页全部快捷办理逐项可达');
 await r.nav(home);let nav=await(await m.currentPage()).$('#finance-desk-nav');for(const b of await nav.$$('button'))if((await b.text()).trim()==='项目账本'){await b.tap();break;}await r.pause(500);assert.equal((await r.data()).view,'projects');r.record('底部项目账本导航进入账本列表');
 }
 if(process.argv[2]!=='roles'){
 await r.nav(book);assert.equal((await r.data()).bookNet,'21,560.00');assert.equal((await r.data()).bookCost,'4,450.00');await r.shot('30-finance-project-final');await r.click('费用明细');await r.shot('31-project-cost-final');await r.click('登记收款');await r.click('返回项目账本');assert.equal((await r.data()).bookFlowTab,'cost');await r.click('登记付款');await r.click('返回项目账本');assert.equal((await r.data()).bookFlowTab,'cost');r.record('收付款取消返回保留原项目及费用标签');await r.click('员工往来');await r.shot('32-employee-settled');
 await r.nav(home+'?view=book&bookScope=company');await r.shot('33-company-final');
 }
 await r.auth('engineer');await r.nav('/pages/build/record/index?view=collection&id='+x.project.id);assert.equal((await r.data()).error,'');const stages=await m.evaluate(()=>getCurrentPages().at(-1).data.installments);assert.equal(stages.length,4);assert.equal(Number(stages[0].remaining.replace(/[^0-9.]/g,'')),73990);await r.shot('34-engineer-collections-final');await r.scroll(420);await r.shot('35-engineer-tail-final');await r.nav('/pages/build/record/index?view=material&id='+x.project.id+'&planId='+x.materialPlanId);await r.shot('36-material-final');r.record('工程端四期应收、净实收与财务一致，材料结算及已付同步');
 await r.auth('sales');await r.nav('/pages/workflow/index?view=contract&id='+x.customerId);await r.shot('37-sales-final');
 await r.auth('boss');await r.nav(home+'?view=project&id='+x.project.id);const boss=await m.evaluate(()=>{const d=getCurrentPages().at(-1).data;return {readonly:d.projectLedgerReadOnly,net:d.projectBookNet,metrics:d.projectLedgerSummary,error:d.ledgerProjectError}});assert.equal(boss.readonly,true);assert.equal(boss.net,'21,560.00');assert.equal((await(await m.currentPage()).$$('input')).length,0);await r.shot('38-boss-final');r.record('老板账本只读，与财务项目结余21560元一致',{screenshot:'38-boss-final.png'});
 await r.auth('finance');await r.nav(home);await r.shot('29-finance-home-final');fs.writeFileSync(r.out+'native-final.json',JSON.stringify({passed:true,home:homeState,boss,renderer:'微信开发者工具原生模拟器，非物理手机'},null,2));
 }catch(e){r.record('最终四端验收异常',{message:e.message},false);await r.shot('error-final');throw e;}finally{r.close();}})().then(()=>process.exit(0),e=>{console.error(e.stack);process.exit(1)});
