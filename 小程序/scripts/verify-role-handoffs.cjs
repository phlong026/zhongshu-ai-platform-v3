// Runs against the real WeChat simulator. All fixtures and writes are local and restored.
const fs=require('fs'),path=require('path'),assert=require('assert/strict'),auto=require('miniprogram-automator');
global.wx={getStorageSync:()=>undefined};
const A=require('../miniprogram/utils/account'),B=require('../miniprogram/utils/business'),M=require('../miniprogram/pages/admin/model');
const {stateAt,demoFile:file}=require('./business-fixtures.cjs');
const out=path.resolve('qa/role-handoff-20261018'),shots=path.join(out,'screenshots');fs.mkdirSync(shots,{recursive:true});
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const people=[
 {id:'sales-zhang',name:'调试张业务',staff:'zhang',role:'sales'},
 {id:'sales-liu',name:'调试刘业务',staff:'liu',role:'sales'},
 {id:'manager-li',name:'调试李经理',staff:'li',role:'manager'},
 {id:'manager-wang',name:'调试王经理',staff:'wang-manager',role:'manager'},
 {id:'finance-wang',name:'调试王财务',staff:'wang-finance',role:'finance'},
 {id:'finance-li',name:'调试李财务',staff:'qa-finance-li',role:'finance'},
 {id:'owner-zhao',name:'调试赵负责人',staff:'qa-owner-zhao',role:'owner'}
];
const persona=p=>{const s=A.seed();s.session={loggedIn:false,userId:'demo-'+p.staff,staffId:p.staff,companyId:'quanzhou',role:p.role,demo:true};s.profile.name=p.name;s.companies[0].roles=[p.role];s.messages=[];s.orders=[];return s;};
fs.writeFileSync(path.join(out,'personas.json'),JSON.stringify({note:'仅供本机模拟器使用的身份夹具，不是真实账号或凭据。',people:people.map(p=>({...p,state:persona(p)})),missing:['业务负责人','财务负责人','老板（只有门店负责人近似入口）']},null,2));
(async()=>{const mp=await auto.connect({wsEndpoint:process.env.ROLE_WS||'ws://127.0.0.1:9720'}),checks=[],captures=[],blockers=[],exceptions=[];let n=0,active='baseline',id='',projectId='',engineeringId='';
 mp.on('exception',e=>exceptions.push(e));
 const before=await mp.evaluate(()=>wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')).map(k=>[k,wx.getStorageSync(k)]));
 const initialPage=await mp.currentPage();fs.writeFileSync(path.join(out,'before-run.json'),JSON.stringify({at:new Date().toISOString(),page:initialPage.path,storage:before},null,2));
 const write=()=>fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({at:new Date().toISOString(),checks,captures,blockers,exceptions},null,2));
 const stored=k=>mp.evaluate(k=>wx.getStorageSync(k),k);
 const setStore=(k,v)=>mp.evaluate(x=>wx.setStorageSync(x.k,x.v),{k,v});
 const data=k=>mp.evaluate(k=>{const p=getCurrentPages().slice(-1)[0];return p.data[k]===undefined?null:p.data[k];},k);
 const invoke=(method,...args)=>mp.evaluate(x=>getCurrentPages().slice(-1)[0][x.method](...x.args),{method,args});
 const open=async url=>{await mp.callWxMethod('reLaunch',{url});await wait(650);return mp.currentPage();};
 const wf=v=>open('/pages/workflow/index?view='+v+'&id='+encodeURIComponent(id||'customer-chen'));
 const admin=(v,r='')=>open('/pages/admin/index?view='+v+(r?'&id='+encodeURIComponent(r):''));
 const tap=async selector=>{console.log('TAP '+selector);const p=await mp.currentPage();let e=await p.$(selector);for(let i=0;!e&&i<12;i++){await wait(200);e=await(await mp.currentPage()).$(selector);}assert(e,'Missing UI control '+selector);try{await e.tap();}catch(error){if(!/timeout waiting/.test(error.message)||(await mp.currentPage()).path===p.path)throw error;console.log('RPC navigation timeout; destination page observed. Mutation was not repeated.');}await wait(650);};
 const form=values=>mp.evaluate(values=>{const p=getCurrentPages().slice(-1)[0];for(const[k,v]of Object.entries(values))p.setData({['form.'+k]:v});},values);
 const input=async(k,v)=>{const e=await(await mp.currentPage()).$('[data-key="'+k+'"]');assert(e,'Input '+k);await e.input(v);await wait(60);};
 const customer=async()=>((await stored(B.KEY)).customers||[]).find(c=>c.id===id);
 const capture=async name=>{console.log('SCREEN '+name);await wait(1200);const filename=String(++n).padStart(2,'0')+'-'+name+'.png';await mp.screenshot({path:path.join(shots,filename)});const p=await mp.currentPage(),d=await data('view');captures.push({step:n,name,persona:active,file:'screenshots/'+filename,page:p.path,view:d});write();console.log('CAPTURE '+filename);return filename;};
 const check=(name,ok,actual={},severity='P1')=>{checks.push({name,result:ok?'passed':'failed',severity:ok?null:severity,persona:active,evidence:captures.length?captures[captures.length-1].file:null,actual});write();console.log((ok?'PASS ':'FAIL ')+name);};
 const phase=async(name,fn)=>{console.log('PHASE '+name);try{await fn();}catch(e){blockers.push({phase:name,error:e.stack});console.error('BLOCKER '+name+' '+e.message);try{await capture('blocked-'+name);}catch(_){}write();}};
 const login=async pid=>{const p=people.find(p=>p.id===pid);assert(p,pid);active=pid;await setStore(A.KEY,persona(p));await open('/pages/account/index?view=login');await tap('[data-handler="toggle"]');await tap('[data-handler="login"]');assert.equal((await data('modal')).type,'login-demo');await tap('.sheet .solid');assert.equal((await stored(A.KEY)).session.loggedIn,true);await open('/pages/account/index?view=identity');check(p.name+'本地演示登录和已授权身份',await data('role')===A.roles[p.role],{userId:(await stored(A.KEY)).session.userId,options:await data('roleOptions')});await capture(pid+'-identity');await open('/pages/'+(p.role==='manager'?'engineering':['owner','finance'].includes(p.role)?'management':'business')+'/index');await capture(pid+'-workbench');};
 try{
  const book=M.seed();book.staff.push({id:'qa-finance-li',name:'调试李财务',role:'财务专员',active:true},{id:'qa-owner-zhao',name:'调试赵负责人',role:'门店负责人',active:true});await setStore(M.KEY,book);await setStore(B.KEY,B.initial());await setStore('zhongshu-engineering-v1',{version:1,projects:[]});
  await phase('login-boundaries',async()=>{
   active='logged-out';const s=persona(people[0]);await setStore(A.KEY,s);await open('/pages/account/index?view=login');await tap('[data-handler="login"]');await capture('consent-validation');check('登录前须同意协议',!!await data('error'),{error:await data('error')});
   await tap('[data-key="sms"]');await capture('sms-status');check('短信登录明确提示尚未接入',String((await data('modal')).text).includes('尚未接入'));
   await wf('customer');await capture('logged-out-customer');check('退出后不可读客户详情',!await data('customer'));
   const all=A.seed();await setStore(A.KEY,all);await open('/pages/account/index?view=identity');await capture('all-available-roles');const roles=await data('roleOptions');check('业务负责人有独立角色',roles.some(r=>r.id==='sales-lead'),{available:roles});check('财务负责人有独立角色',roles.some(r=>r.id==='finance-lead'),{available:roles});check('老板有独立授权模型',roles.some(r=>r.id==='boss'),{available:roles});
  });
  await phase('sales-origination',async()=>{
   await login('sales-zhang');await wf('customers');await tap('[data-modal="register"]');await input('name','角色流转调试客户');await input('phone','13800009918');await input('summary','仅用于本轮跨岗位调试');await tap('.sheet-save');id=(await data('customer')).id;projectId=(await customer()).projectId;await capture('sales-created-customer');check('业务员新建客户生成稳定项目编号',!!id&&!!projectId,{id,projectId});
   await tap('[data-modal="follow"]');await form({followContent:'张业务确认需求，待财务核对收款',appointment:'2026-10-19 15:00'});await tap('.sheet-save');check('业务员跟进持久保存',(await customer()).follows.length===1);
   // Supply the pre-existing signed contract and measurement as an explicit fixture milestone.
   const b=await stored(B.KEY),c=b.customers.find(c=>c.id===id),f=stateAt(5).customers[0];for(const k of ['measurements','measurementFile','quotes','contract','signedFile','plans','budgets'])c[k]=f[k];c.contract.date='2026-10-18';c.owner='张业务';c.serviceOrders=[{version:1,total:12000,first:6000,tail:6000,file:file('测试双方设计服务约定.pdf'),date:'2026-10-18'}];await setStore(B.KEY,b);
   await wf('funds');await tap('.footer .primary-action');await form({receiptPurpose:'工程预收款',receiptAmount:'1000',receiptDate:'2026-10-18',receiptReceiver:'调试王财务',receiptTransaction:'QA-ROLE-IN-001',receiptFile:file('测试收款凭证.pdf')});await tap('.sheet-save');await capture('sales-receipt-registered');check('业务员备案收款关联原客户项目',(await customer()).receipts.length===1,{receipt:(await customer()).receipts[0],projectId});
   await wf('request');await form({requestText:'按已签合同和实测资料设计，测试首款与跨岗流转。'});await tap('.footer .primary-action');await capture('sales-request-submitted');check('业务需求提交产生首款待办',!!(await customer()).request&&!!(await customer()).paymentTask,{request:(await customer()).request,paymentTask:(await customer()).paymentTask});
   await admin('reconcile','business-receipt:'+id+':0');await capture('sales-finance-denied');check('业务员不能直接进入管理财务核算',await data('view')==='access');
  });
  await phase('peer-sales-isolation',async()=>{
   await login('sales-liu');await wf('customer');await capture('peer-sales-private-customer');check('未指派业务员不能读取张业务客户',!await data('customer'),{exposedCustomer:await data('customer')?{id:(await data('customer')).id,name:(await data('customer')).name,owner:(await data('customer')).owner}:null});
   if(await data('customer')){const count=(await customer()).follows.length;await tap('[data-modal="follow"]');await form({followContent:'刘业务越权测试写入，应被阻断',appointment:'2026-10-19 16:00'});await tap('.sheet-save');await capture('peer-sales-write');check('未指派业务员不能修改张业务客户',(await customer()).follows.length===count,{follows:(await customer()).follows});}
  });
  await phase('finance-receipt-handoff',async()=>{
   assert(id,'sales origination required');await login('finance-wang');await open('/pages/management/index');const rid='business-receipt:'+id+':0',payid='business-design:'+id+':1:first';await capture('finance-received-sales-todos');check('财务收到业务员备案收款及设计首款待办',(await data('financeTodos')).some(x=>x.id===rid)&&(await data('financeTodos')).some(x=>x.id===payid),{todos:await data('financeTodos')});
   await admin('reconcile',rid);await tap('.footer .outline');await form({note:'凭证缺银行回单，请原业务员补充'});await tap('.sheet .solid');await capture('finance-returned-original-receipt');check('退回保留来源收款及原因',(await customer()).receipts[0].status==='需补充',{receipt:(await customer()).receipts[0]});
   await login('sales-zhang');await wf('funds');await capture('sales-sees-returned-receipt');const c=await customer();check('原业务员能读到财务退回状态',c.receipts[0].status==='需补充');await admin('receipt-supplement',rid);await capture('sales-cannot-supplement-own-receipt');check('原业务员能进入退回原单补证',await data('view')==='receipt-supplement',{actualView:await data('view')});
   await login('finance-wang');await admin('receipt-supplement',rid);await form({note:'财务代补用于继续验证，不代表原业务员补证链通过',file:file('补充测试回单.pdf')});await tap('.footer .solid');check('补证保留同一来源且返回待核算',(await customer()).receipts[0].status==='待核算');await tap('.check-row');await tap('.footer .solid');await tap('.sheet .solid');await capture('finance-reconciled-receipt');check('核算结果回写业务原单且金额保持1000元',(await customer()).receipts[0].status==='已核算'&&(await customer()).receipts[0].amount===1000,{receipt:(await customer()).receipts[0]});
   await admin('pay',payid);await form({amount:'6000',date:'2026-10-18',transaction:'QA-ROLE-OUT-001',file:file('首款线下测试凭证.pdf'),checked:true});await tap('.footer .solid');await tap('.sheet .solid');await capture('finance-paid-design-first');const first=(await customer()).firstPayment;check('设计首款回写原客户及服务版本',first&&first.amount===6000&&first.serviceVersion===1,{first});
   const records=(await stored(M.KEY)).payments;check('付款登记后等待独立财务负责人核对',!first,{sourceFirstPayment:first,managementPayments:records.filter(x=>x.transaction==='QA-ROLE-OUT-001')});
   await login('finance-li');await wf('payment-first');await capture('second-finance-sees-same-payment');check('另一财务读取同一来源付款无丢失',(await data('customer')).firstPayment.transaction==='QA-ROLE-OUT-001');await admin('pay',payid);const paymentsBefore=JSON.stringify((await customer()).firstPayment);await form({amount:'6000',date:'2026-10-18',transaction:'QA-ROLE-OUT-002',file:file('重复测试凭证.pdf'),checked:true});await invoke('perform','pay');await capture('second-finance-duplicate-blocked');check('已付清原单不能由另一财务重复登记',JSON.stringify((await customer()).firstPayment)===paymentsBefore&&!!await data('error'),{error:await data('error')});
  });
  await phase('role-action-boundaries',async()=>{
   await login('sales-zhang');await wf('payment-first');check('业务员重开后读取财务办理回执',(await data('customer')).firstPayment.transaction==='QA-ROLE-OUT-001');
   await wf('intake');await form({intakeChecked:true});await tap('.footer .primary-action');await capture('sales-performed-headquarters-intake');check('供应商业务员不能执行总部受理',!(await customer()).intake,{intake:(await customer()).intake});
   await login('finance-wang');await admin('staff');await capture('finance-staff-directory');check('普通财务无法读取未另授的人员管理详情',await data('view')==='access',{view:await data('view'),sections:await data('sections')});
   await admin('transfer','work-chen');await form({assignee:'liu',note:'普通财务试图转交工作',checked:true});await invoke('perform','transfer');check('普通财务不能执行负责人转交',!!await data('error'),{error:await data('error')});
  });
  await phase('sales-to-engineering-handoff',async()=>{
   // Headquarters and real-customer services do not exist. Seed only their prerequisite records.
   const b=await stored(B.KEY),c=b.customers.find(c=>c.id===id),f=stateAt(13).customers[0];for(const k of ['intake','assignment','deliverables','tailApplication','tailPayment','release','confirmation','drawings','drawingFile','copies'])c[k]=f[k];c.reviews=[{id:'qa-review-v1',drawingVersion:'v1',status:'passed',person:'测试专业审核人',file:file('测试v1审图记录.pdf'),date:'2026-10-18'}];await setStore(B.KEY,b);
   await login('sales-zhang');await wf('handover');await form({manager:'李经理',handoverNote:'合同、测量、已确认v1图纸与电子交底连续移交'});await tap('.footer .primary-action');await capture('sales-submitted-handover');check('业务提交工程移交绑定当前图纸及成果版本',!!(await customer()).handover,{handover:(await customer()).handover});
   await login('manager-li');await wf('receive');await capture('manager-received-original-handover');check('指定项目经理读到原客户及版本',(await data('customer')).id===id&&(await data('customer')).handover.drawingVersion==='v1');await form({receiveContract:true,receiveDrawing:true,receiveBrief:true,receiveDate:'2026-10-18'});await tap('.footer .primary-action');await capture('manager-accepted-handover');check('工程接收只进入待开工',(await customer()).acceptance.stage==='待开工',{acceptance:(await customer()).acceptance});
   await open('/pages/build/index?view=projects');const e=await stored('zhongshu-engineering-v1'),p=e.projects.find(p=>p.sourceCustomerId===id);assert(p,'engineering sync');engineeringId=p.id;check('工程项目沿用原项目编号、客户与当前图纸',p.projectId===projectId&&p.drawingVersion==='v1'&&!p.actualStart,{id:p.id,projectId:p.projectId,sourceCustomerId:p.sourceCustomerId,manager:p.manager,drawingVersion:p.drawingVersion,actualStart:p.actualStart});await capture('engineering-synced-project');
   await login('manager-wang');await open('/pages/build/index?view=overview&id='+engineeringId);await capture('other-manager-project-access');check('未指派项目经理不能读李经理项目',!await data('p'),{exposed:await data('p')?{id:(await data('p')).id,manager:(await data('p')).manager}:null});
   await login('sales-zhang');await open('/pages/build/index?view=overview&id='+engineeringId);await capture('sales-engineering-access');check('业务员不能取得项目经理现场办理权限',!await data('p'),{view:await data('view'),action:await data('action'),project:await data('p')?{id:(await data('p')).id,manager:(await data('p')).manager}:null});
   check('跨人移交事件保存实际操作人ID',(await customer()).events.filter(e=>/移交|接收/.test(e.title)).every(e=>e.actorId||e.userId),{events:(await customer()).events.filter(e=>/移交|接收/.test(e.title))});
  });
  await phase('owner-transfer-business-consistency',async()=>{
   await login('owner-zhao');await admin('transfer','work-chen');await form({assignee:'liu',note:'张业务调岗，客户及待办应随责任流转',checked:true});await tap('.footer .solid');await tap('.sheet .solid');await capture('owner-transferred-work');const m=await stored(M.KEY),work=m.work.find(w=>w.id==='work-chen'),business=(await stored(B.KEY)).customers.find(c=>c.id==='customer-chen');check('负责人工作转交保留原经办人',work.owner==='liu'&&work.author==='zhang',{work,transfers:m.transfers});check('工作转交同步原业务客户责任',business.owner==='刘业务'||business.owner==='刘芳'||business.ownerId==='liu',{work,businessOwner:business.owner,businessOwnerId:business.ownerId||null});
   await open('/pages/management/index');await capture('owner-company-summary');check('负责人工作台可读取本公司汇总',await data('allowed')===true&&await data('mode')==='owner');
  });
  await phase('company-disabled-and-empty-session',async()=>{
   const s=persona(people[0]);s.session.loggedIn=true;s.session.companyId='qa-other-company';s.companies=[{id:'qa-other-company',name:'调试另一公司',roles:['sales']}];await setStore(A.KEY,s);active='other-company';await wf('customer');await capture('other-company-blocked');check('跨公司不能读取原客户',!await data('customer'));
   s.session.companyId='quanzhou';s.companies=[{id:'quanzhou',name:'泉州加盟商',roles:['sales']}];await setStore(A.KEY,s);const b=await stored(M.KEY);b.staff.find(p=>p.id==='zhang').active=false;await setStore(M.KEY,b);active='disabled-sales';await wf('customer');await capture('disabled-staff-blocked');check('停用后旧会话无法读取企业客户',!await data('customer'));
   await mp.evaluate(()=>wx.removeStorageSync('zhongshu-account-v1'));active='missing-session';await wf('customer');await capture('missing-session-customer-access');check('无会话存储时不能直接读取客户',!await data('customer'),{exposed:await data('customer')?{id:(await data('customer')).id,name:(await data('customer')).name}:null});
  });
  fs.writeFileSync(path.join(out,'final-fixture-state.json'),JSON.stringify({customerId:id,projectId,engineeringId,business:await stored(B.KEY),engineering:await stored('zhongshu-engineering-v1'),management:await stored(M.KEY)},null,2));
 }finally{
  await mp.evaluate(rows=>{const keep=new Set(rows.map(x=>x[0]));wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')&&!keep.has(k)).forEach(k=>wx.removeStorageSync(k));for(const[k,v]of rows)wx.setStorageSync(k,v);},before);
  const after=await mp.evaluate(()=>wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')).sort().map(k=>[k,wx.getStorageSync(k)]));const normalized=rows=>JSON.stringify([...rows].sort((a,b)=>a[0].localeCompare(b[0])));
  check('测试后本地全部众墅数据恢复',normalized(before)===normalized(after));fs.writeFileSync(path.join(out,'restore-check.json'),JSON.stringify({restored:normalized(before)===normalized(after),keys:before.map(x=>x[0])},null,2));
  await mp.callWxMethod('reLaunch',{url:'/pages/home/index'});await mp.disconnect();write();console.log(JSON.stringify({passed:checks.filter(c=>c.result==='passed').length,failed:checks.filter(c=>c.result==='failed').length,blockers:blockers.length,screenshots:captures.length}));
 }
})().catch(e=>{console.error(e);process.exitCode=1;});
