const a=require('miniprogram-automator'),fs=require('fs');
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const out='docs/finance-ux-audit-20261117';
fs.mkdirSync(out,{recursive:true});
(async()=>{const m=await a.connect({wsEndpoint:'ws://127.0.0.1:9973'});try{
 const root='C:/Users/Administrator/.codex/artifacts/finance-joint-20261117/run1/';
 const actor=JSON.parse(fs.readFileSync(root+'actors.json','utf8')).finance;
 const handles=JSON.parse(fs.readFileSync(root+'handles.json','utf8'));
 await m.evaluate(x=>{wx.setStorageSync('zs-api-base','http://127.0.0.1:18091/admin-api');wx.setStorageSync('zs-api-token-v1',x.token);wx.setStorageSync('zs-api-post-v1',String(x.postId));wx.setStorageSync('zs-api-posts-v1',x.posts);wx.setStorageSync('zs-api-tenant-v1','1')},actor);
 let p;
 async function nav(url='/pages/admin/finance-desk/index'){p=await m.reLaunch(url);await wait(1300);await scroll(0);}
 async function scroll(n){await m.evaluate(n=>wx.pageScrollTo({scrollTop:n,duration:0}),n);await wait(160);}
 async function capture(name){await m.screenshot({path:out+'/'+name+'.png'});console.log('captured',name);}
 async function click(text){p=await m.currentPage();for(const el of await p.$$('button'))if((await el.text()).trim()===text){await el.tap();await wait(450);p=await m.currentPage();return;}throw Error('Button missing: '+text);}
 const phase=process.argv[2]||'home';
 if(phase==='month-check'){
  await nav();const before=await m.evaluate(()=>{const d=getCurrentPages().slice(-1)[0].data;return {count:d.deskHome.taskCount,preview:d.deskHome.previewTasks.length,focus:d.deskHome.focus.length}});
  const c=await p.$('#finance-month');await(await c.$('.month-trigger')).tap();await wait(160);
  for(const el of await c.$$('.month-cell'))if((await el.text()).trim()==='10月'){await el.tap();break;}
  await wait(160);await(await c.$('.month-confirm')).tap();await wait(3500);
  const after=await m.evaluate(()=>{const d=getCurrentPages().slice(-1)[0].data;return {month:d.selectedMonth,count:d.deskHome.taskCount,preview:d.deskHome.previewTasks.length,focus:d.deskHome.focus.length,todoStatus:d.platformTodos.status,todoRows:d.platformTodos.rows.length}});
  await capture('27-month-pending-stable');fs.writeFileSync(out+'/month-result.json',JSON.stringify({before,after},null,2));console.log(JSON.stringify({before,after}));await nav();
 } else if(phase==='home'){
  await nav();await capture('01-home');await scroll(460);await capture('02-home-lower');
  await click('收款登记');await scroll(0);await capture('03-receipt');
  await scroll(580);await capture('04-receipt-lower');
  await click('保存登记');await capture('05-receipt-error');
  await nav();await click('付款登记');await scroll(0);await capture('06-payment');
  await scroll(530);await capture('07-payment-lower');
 } else if(phase==='reimburse'){
  await nav();await click('报销办理');await scroll(0);await capture('08-reimbursements');
  await click('办理报销');await scroll(0);await capture('09-reimbursement-form');
  await scroll(500);await capture('10-reimbursement-form-lower');
  await nav();await scroll(450);await click('收支流水');await scroll(0);await capture('11-flows-top');
  await scroll(690);await capture('12-flows-middle');await scroll(1450);await capture('13-flows-bottom');
 } else if(phase==='routes'){
  await nav();await click('收支流水');await wait(1800);await scroll(0);await capture('20-flows-top-stable');
  await click('退款登记');await scroll(0);await capture('21-refund-form');
  await nav();await click('付款登记');await click('没有原业务单？补记零星付款');await scroll(0);await capture('22-manual-fallback');await scroll(600);await capture('23-manual-fallback-lower');
  await nav();await click('查看全部');await wait(1800);await scroll(0);await capture('24-progress-all');
  await nav();const c=await p.$('#finance-month');await(await c.$('.month-trigger')).tap();await wait(160);
  for(const el of await c.$$('.month-cell'))if((await el.text()).trim()==='10月'){await el.tap();break;}
  await wait(160);await(await c.$('.month-confirm')).tap();await wait(1000);await capture('25-october-home');
  const before=await m.evaluate(()=>getCurrentPages().slice(-1)[0].data.selectedMonth);
  await(await p.$('.fin-home-metric')).tap();await wait(2000);p=await m.currentPage();await scroll(0);await capture('26-month-drilldown');
  const after=await m.evaluate(()=>{const d=getCurrentPages().slice(-1)[0].data;return {selectedMonth:d.selectedMonth,period:d.period,flowTab:d.flowTab,view:d.view}});
  fs.writeFileSync(out+'/route-result.json',JSON.stringify({before,after},null,2));console.log(JSON.stringify({before,after}));
  await nav();
 } else if(phase==='books'){
  await nav('/pages/admin/finance-desk/index?view=projects');await capture('14-books');
  await nav('/pages/admin/finance-desk/index?view=book&bookScope=project%3A'+handles.project.id);await capture('15-project');
  await click('登记付款');await scroll(0);await capture('16-project-payment');await scroll(600);await capture('17-project-payment-lower');
  await nav('/pages/admin/finance-desk/index?view=book&bookScope=project%3A'+handles.project.id);await click('员工往来');await scroll(390);await capture('18-employee');
  await nav('/pages/admin/finance-desk/index?view=book&bookScope=company');await capture('19-company');
  await nav();
 }
}finally{m.disconnect();}})().then(()=>process.exit(0),e=>{console.error(e.stack);process.exit(1)});
