const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const r=await require('./role-page-runtime.cjs').connect(),A=require('../miniprogram/utils/account'),B=require('../miniprogram/utils/business'),D=require('../miniprogram/utils/sales-demo'),W=require('../miniprogram/utils/sales-workspace'),checks=[];
 const open=url=>r.callWxMethod('reLaunch',{url}),p=()=>getCurrentPages().at(-1),pass=name=>checks.push({name,result:'passed'});
 for(const [index,id]of ['zhang','sales-lead'].entries()){
  A.mutate(a=>A.loginDemo(a,id));assert(D.ensure());const before=W.customerRows().length;assert(!D.ensure());assert.equal(W.customerRows().length,before);pass(id+'：一次性模拟客户、草稿和作品，不重复注入');
  await open('/pages/business/index');assert(p().data.isSalesHome);assert.equal(p().data.salesCounts.all,before);assert(p().data.salesTodos.length);assert(p().data.recentCreation);pass(id+'：首页统计与可见记录一致，待办与最近创作非空');
  const page=await r.currentPage();await(await page.$('[data-action="customer"]')).tap();assert.equal(p().data.modal,'register');
  const phone='1399900100'+index;p().setData({'form.name':'界面联动测试'+id,'form.phone':phone,'form.source':'视频号咨询','form.summary':'两层乡墅，老人房及预算沟通'});await(await(await r.currentPage()).$('.sheet-save')).tap();
  assert.equal(p().data.view,'customer');const customer=p().data.customer;assert(customer&&customer.phone===phone);assert.equal(customer.ownerId,A.access.context().staffId);pass(id+'：首页登记入口通过原WXML表单创建归本人客户');
  const follow=await(await r.currentPage()).$('[data-modal="follow"]');assert(follow);await follow.tap();p().setData({'form.method':'到店','form.followContent':'已记录到店沟通及后续方案需求','form.appointment':A.localDate()+' 16:30','form.next':'电话联系'});await(await(await r.currentPage()).$('.sheet-save')).tap();assert(!p().data.error,p().data.error);assert.equal(B.customer(customer.id).status,'已到店');
  await open('/pages/customers/index');assert.equal(p().data.counts.all,before+1);p().setFilter({currentTarget:{dataset:{value:'已到店'}}});assert(p().data.filteredCustomers.some(c=>c.id===customer.id));p().search({detail:{value:'9900100'}});assert(p().data.filteredCustomers.some(c=>c.id===customer.id));assert(p().data.filteredCustomers.every(c=>c.phone.includes('****')));pass(id+'：到店状态、筛选、手机号搜索与脱敏联动');
 }
 A.mutate(a=>A.loginDemo(a,'zhang'));await open('/pages/customers/index');assert(p().data.customers.every(c=>!c.name.includes('sales-lead')));assert(!p().data.isLead);pass('业务员看不到负责人私人客户及管理入口');
 A.mutate(a=>A.loginDemo(a,'manager-li'));await open('/pages/customers/index');assert.equal(p().route,'pages/engineering/index');pass('非业务岗位不能进入客户模块');
 const out=path.resolve('qa/business-module-redesign-20261019');fs.mkdirSync(out,{recursive:true});fs.writeFileSync(path.join(out,'customers-results.json'),JSON.stringify({result:'passed',kind:'real Page/WXML handlers and existing business models',checks},null,2));console.log(JSON.stringify({result:'passed',checks:checks.length}));
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
