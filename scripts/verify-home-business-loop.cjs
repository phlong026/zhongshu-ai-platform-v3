// 仅操作本机专用测试数据库，不涉及外部设计/支付服务。
const fs=require('fs'),assert=require('assert/strict');
const actors=JSON.parse(fs.readFileSync('C:/Users/Administrator/.codex/artifacts/home-20261118/actors.json'));
const out='docs/home-20261118',prefix='home-loop-'+Date.now();
async function call(role,method,path,body,key,fail=false){const p=actors[role],res=await fetch('http://127.0.0.1:18097/admin-api'+path,{method,headers:{Authorization:'Bearer '+p.token,'X-Post-Id':String(p.postId),'tenant-id':'1','Content-Type':'application/json',...(key?{'Idempotency-Key':prefix+'-'+key}:{})},...(body?{body:JSON.stringify(body)}:{})}),j=await res.json();if(fail){assert.notEqual(j.code,0,path);return j.code;}assert.equal(j.code,0,path+': '+j.msg);return j.data;}
const metrics=async()=> (await call('sales','GET','/franchise/home?role=business&period=2026-11')).metrics;
(async()=>{const cases=[],baseline=await metrics();
 const customer=await call('sales','POST','/franchise/leads',{name:'林女士 · 接待验收',phone:'199'+String(Date.now()).slice(-8),need:'乡墅接待及预算确认闭环验收',source:'到店咨询'},'customer');const id=String(customer.customerId),base='/franchise/customers/'+id;
 const future=[2026,11,25],occurred=[2026,11,18];
 await call('sales','POST',base+'/follows',{note:'电话预约，不应算作实际接待',nextVisitDate:future,contactKind:'FOLLOW',occurredOn:occurred},'follow');assert.equal((await metrics()).visits.total,baseline.visits.total);cases.push('电话跟进和预约不增加实际接待');
 const visit={note:'客户到店核对宅基地及建房需求',nextVisitDate:future,contactKind:'VISIT',occurredOn:occurred};await call('sales','POST',base+'/follows',visit,'visit');await call('sales','POST',base+'/follows',visit,'visit');assert.equal((await metrics()).visits.total,baseline.visits.total+1);cases.push('真实接待增加1人，同键重试不重复写入');
 await call('sales','POST',base+'/follows',{...visit,note:'第二次到店沟通'},'visit-2');assert.equal((await metrics()).visits.total,baseline.visits.total+1);cases.push('同月同客户多次接待去重');
 await call('sales','POST',base+'/follows',{...visit,occurredOn:[2099,1,1]},'future',true);cases.push('未来实际接待日期被拒绝');
 const scheme={kind:'BUDGET',title:'乡墅施工参考预算',payload:'{}',width:10,depth:10,floors:2,area:200,totalCents:48000000};
 const first=await call('sales','POST',base+'/schemes',scheme,'budget1');const latest=await call('sales','POST',base+'/schemes',{...scheme,title:'乡墅施工预算修订',totalCents:49800000},'budget2');
 let now=await metrics();assert.equal(now.budgets.total,baseline.budgets.total+1);cases.push('预算按客户最新版本计1项');
 await call('sales','POST',base+'/budget-confirmation',{schemeId:first.id,note:'旧版'},'old-confirm',true);await call('boss','POST',base+'/budget-confirmation',{schemeId:latest.id,note:'越权'},'boss-confirm',true);cases.push('旧版本与非业务岗位确认被拒绝');
 const confirm={schemeId:latest.id,note:'2026年11月18日到店核对，客户确认本版预算及施工范围'};await call('sales','POST',base+'/budget-confirmation',confirm,'confirm');await call('sales','POST',base+'/budget-confirmation',confirm,'confirm');assert.equal((await metrics()).budgets.total,baseline.budgets.total);cases.push('预算确认成功后退出待确认；重试幂等');
 const detail=await call('sales','GET',base);assert.ok(detail.schemes.find(s=>String(s.id)===String(latest.id)).confirmedAt);assert.equal(detail.follows.filter(f=>f.contactKind==='VISIT').length,2);cases.push('接待与预算确认原始事实可追溯');
 const facts=await Promise.all(['receipts','payments','refunds'].map(k=>call('finance','GET','/franchise/finance/'+k)));const fm=(await call('finance','GET','/franchise/home?role=finance&period=2026-11')).metrics;
 const day=v=>Array.isArray(v)?v[0]+'-'+String(v[1]).padStart(2,'0')+'-'+String(v[2]).padStart(2,'0'):String(v||'');
 const received=facts[0].filter(r=>r.status==='已登记'&&day(r.receivedDate).startsWith('2026-11')).reduce((s,r)=>s+Number(r.amountCents),0);
 const paid=facts[1].filter(r=>r.status==='已核实'&&r.fundingType==='COMPANY'&&day(r.payDate).startsWith('2026-11')).reduce((s,r)=>s+Number(r.amountCents),0);
 const refund=facts[2].filter(r=>r.status==='已核实'&&!r.replacedById&&day(r.date).startsWith('2026-11'));
 const outgoing=refund.filter(r=>r.refundDirection==='client').reduce((s,r)=>s+Number(r.amountCents),0),incoming=refund.filter(r=>r.refundDirection==='expense'&&r.fundingType==='COMPANY').reduce((s,r)=>s+Number(r.amountCents),0);
 assert.equal(Number(fm.received.cents),received);assert.equal(Number(fm.paid.cents),paid);assert.equal(Number(fm.net.cents),received-paid-outgoing+incoming);cases.push('净收支与财务原始三类接口独立交叉核对一致');
 fs.mkdirSync(out,{recursive:true});fs.writeFileSync(out+'/business-loop.json',JSON.stringify({customerId:id,latestBudgetId:latest.id,cases,passed:true},null,2));console.log(cases.length+' closed-loop and independent financial checks passed');
})().catch(e=>{console.error(e.message);process.exitCode=1;});
