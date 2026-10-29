const assert=require('assert/strict');

let store={};
global.wx={
 getStorageSync:key=>store[key],
 setStorageSync:(key,value)=>{store[key]=JSON.parse(JSON.stringify(value));},
 removeStorageSync:key=>{delete store[key];}
};

const D=require('../miniprogram/utils/demo-catalog');
const Business=require('../miniprogram/utils/business');
const Engineering=require('../miniprogram/utils/engineering-seed');
const Management=require('../miniprogram/utils/management-seed');
const Account=require('../miniprogram/utils/account');
const SalesDemo=require('../miniprogram/utils/sales-demo');

const checks=[];
const test=(name,fn)=>{fn();checks.push(name);console.log('PASS '+name);};
const flow=D.acceptance;

test('六类验收账号使用固定人员、人员ID和组织',()=>{
 assert.deepEqual(flow.actors,{
  sales:{loginId:'sales-dual',staffId:'sales-dual',name:'李业务',companyId:'quanzhou'},
  engineering:{loginId:'engineering-dual',staffId:'engineering-dual',name:'张工程',companyId:'quanzhou'},
  finance:{loginId:'finance-dual',staffId:'finance-dual',name:'王财务',companyId:'quanzhou'},
  boss:{loginId:'boss',staffId:'zhao-boss',name:'张老板',companyId:'quanzhou'},
  design:{loginId:'hq-design-admin',staffId:'hq-design-admin',name:'林设计',companyId:'headquarters'},
  hqFinance:{loginId:'hq-finance-dual',staffId:'hq-finance-dual',name:'罗财务',companyId:'headquarters'}
 });
});

test('陈先生三套阶段样例明确独立，不伪装成连续闭环',()=>{
 assert.equal(flow.kind,'staged-fixtures');
 assert.equal(flow.continuous,false);
 assert.deepEqual(flow.stages.business,{projectId:'project-chen',customerId:'customer-chen',label:'陈先生（业务前段样例）',asOf:D.businessSnapshot});
 assert.deepEqual(flow.stages.engineering,{projectId:'snapshot-engineering-chen',engineeringId:'demo-chen',label:'陈先生（工程阶段样例）',asOf:D.engineeringSnapshot});
 assert.deepEqual(flow.stages.finance,{projectId:'snapshot-finance-chen',label:'陈先生（财务阶段样例）',asOf:D.financialSnapshot});
 assert.equal(new Set(Object.values(flow.stages).map(stage=>stage.projectId)).size,3);
 assert.equal(D.projects.chen.engineeringId,undefined);
 assert.equal(flow.contractCents,flow.contractYuan*100);
});

test('工程快照使用独立项目、空来源客户和历史经办人',()=>{
 const p=Engineering().projects.find(row=>row.id===flow.engineeringId);
 assert.equal(p.projectId,flow.stages.engineering.projectId);
 assert.equal(p.sourceCustomerId,'');
 assert.equal(p.customer,flow.stages.engineering.label);
 assert(p.name.includes(flow.stages.engineering.label));
 assert.equal(p.manager,'李经理');
 assert.equal(p.managerId,undefined);
 assert.equal(p.acceptedById,undefined);
 assert.equal(p.accepted,flow.timeline.engineeringAccepted);
 assert.equal(p.asOf,flow.stages.engineering.asOf);
 assert.equal(p.drawingVersion,flow.drawingVersion);
 assert(p.documents.some(row=>row.name==='签约资料'&&row.date===flow.timeline.contractSigned));
 assert(p.documents.some(row=>row.name==='交底记录'&&row.date===flow.timeline.handoverSubmitted));
 assert(flow.timeline.contractSigned<=flow.timeline.handoverSubmitted);
 assert(flow.timeline.handoverSubmitted<flow.timeline.engineeringAccepted);
 assert(flow.timeline.engineeringAccepted<=p.plan.compiledAt);
});

test('财务快照使用独立项目、阶段标签、历史经办人和分单位',()=>{
 const book=Management(),rows=book.receipts.filter(row=>row.projectId===flow.stages.finance.projectId);
 const advance=rows.find(row=>row.id===flow.receipts.advance.id);
 const first=rows.find(row=>row.id===flow.receipts.first.id);
 assert.equal(advance.amount,flow.receipts.advance.cents);
 assert.equal(first.amount,flow.receipts.first.cents);
 assert.equal(advance.registrar,'张业务');
 assert.equal(advance.registrarId,undefined);
 assert.equal(advance.receiver,'王店长');
 assert.equal(advance.financialRegistrarId,undefined);
 assert.equal(first.registrar,'李经理');
 assert.equal(first.financialRegistrarId,undefined);
 assert.equal(first.receiver,'王店长');
 assert(rows.every(row=>row.customer===flow.stages.finance.label));
 assert(rows.every(row=>row.receivedAt<=row.registeredAt));
 assert(rows.every(row=>Number.isInteger(row.amount)));
 const correction=rows.find(row=>row.id==='receipt-design-5000').revisions[0];
 assert.equal(correction.by,'李财务');
 assert.equal(correction.actorId,undefined);
 assert.equal(correction.amount,flow.designFeeCents);
});

test('退款保留原收款ID、财务快照项目及分单位',()=>{
 const book=Management(),refund=book.refunds.find(row=>row.id===D.refund.id),receipt=book.receipts.find(row=>row.id===refund.receiptId);
 assert.equal(refund.projectId,flow.stages.finance.projectId);
 assert.equal(receipt.projectId,flow.stages.finance.projectId);
 assert.equal(refund.amount,D.refund.amount*100);
 assert(refund.amount<receipt.amount);
});

test('全新默认案例归李业务，演示初始化可重复执行',()=>{
 Account.save(Account.seed());
 Account.mutate(state=>Account.loginDemo(state,flow.actors.sales.loginId));
 assert.equal(SalesDemo.ensure(),true);
 assert.equal(SalesDemo.ensure(),false);
 const customer=Business.load().customers.find(row=>row.id===flow.customerId);
 assert.equal(customer.ownerId,flow.actors.sales.staffId);
 assert.equal(customer.owner,flow.actors.sales.name);
 const work=Management().work.find(row=>row.customerId===flow.customerId&&row.projectId===flow.projectId);
 assert.equal(work.owner,flow.actors.sales.staffId);
 assert.equal(work.author,flow.actors.sales.staffId);
});

test('其他业务账号首次登录不会抢走默认陈先生案例',()=>{
 store={};
 Account.save(Account.seed());
 Account.mutate(state=>Account.loginDemo(state,'zhang'));
 assert.equal(SalesDemo.ensure(),true);
 const customer=Business.load().customers.find(row=>row.id===flow.customerId);
 assert.equal(customer,undefined,'普通业务员不应看见或接管李业务的客户');
 const raw=store[Business.KEY].customers.find(row=>row.id===flow.customerId);
 assert.equal(raw.ownerId,flow.actors.sales.staffId);
 assert.equal(raw.owner,flow.actors.sales.name);
});

test('已有人工转交不会被李业务登录后的演示初始化抢回',()=>{
 store={};
 Account.save(Account.seed());
 const existing=Business.initial();
 Object.assign(existing.customers.find(row=>row.id===flow.customerId),{ownerId:'liu',owner:'刘业务'});
 store[Business.KEY]=JSON.parse(JSON.stringify(existing));
 Account.mutate(state=>Account.loginDemo(state,flow.actors.sales.loginId));
 assert.equal(SalesDemo.ensure(),true);
 const raw=store[Business.KEY].customers.find(row=>row.id===flow.customerId);
 assert.equal(raw.ownerId,'liu');
 assert.equal(raw.owner,'刘业务');
});

test('非演示登录不会初始化或改写既有真实缓存',()=>{
 store={};
 Account.save(Account.seed());
 const existing=Business.initial();
 Object.assign(existing.customers.find(row=>row.id===flow.customerId),{ownerId:'liu',owner:'刘业务',summary:'真实客户人工录入摘要'});
 store[Business.KEY]=JSON.parse(JSON.stringify(existing));
 Account.mutate(state=>Account.loginDemo(state,flow.actors.sales.loginId));
 store[Account.KEY].session.demo=false;
 const before=JSON.stringify(store[Business.KEY]);
 assert.equal(SalesDemo.ensure(),false);
 assert.equal(JSON.stringify(store[Business.KEY]),before);
});

test('六类角色的核心流程都有明确模拟数据来源',()=>{
 assert.deepEqual(flow.coverage.map(row=>row.loginId),Object.values(flow.actors).map(actor=>actor.loginId));
 assert(flow.coverage.every(row=>row.flows.length&&row.source==='scripts/verify-six-role-flow.cjs'));
 assert(flow.coverage.every(row=>row.fixture==='周建国家连续验收快照'));
});

console.log(checks.length+' passed');
