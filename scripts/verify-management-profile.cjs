// Cases that assumed the pre-v2 single seeded session, or features the product has since switched off (refund / correction / unknown-payment
// verification, designer dispatch, service orders), were retired on 2026-10-22; see docs/旧验收脚本核对_20261022.md.
const assert=require('node:assert/strict'),fs=require('fs');
const storage={};global.wx={getStorageSync:k=>storage[k],setStorageSync:(k,v)=>{storage[k]=JSON.parse(JSON.stringify(v));}};const Account=require('../miniprogram/utils/account');Account.mutate(s=>Account.loginDemo(s,'sales-dual'));
const A=require('../miniprogram/utils/account'),M=require('../miniprogram/pages/admin/model'),V=require('../miniprogram/pages/admin/views'),checks=[];
const test=(name,fn)=>{fn();checks.push({name,result:'passed'});console.log('PASS '+name);};
const role=r=>{const s=A.load();s.session.role=r;A.save(s);};A.save(A.seed());role('owner');
const sample=M.file('本地示例凭据.pdf'),valid={verifiedAmount:'6000',amount:'1000',date:'2026-10-18',file:sample,checked:true,transaction:'TEST-FK-001',note:'已按原笔凭据核实',reason:'双方协商确认'};
test('管理汇总含同一快照的2000部分付款与5000退款',()=>{const s=M.seed(),sum=M.summaries(s);assert.equal(sum.income,8500000);assert.equal(sum.paid,2680000);assert.equal(V.ledger(s).length,7);assert.equal(sum.net,5820000);});
test('金额使用分，拒绝负数、三位小数、无效日期与缺凭据',()=>{for(const x of ['0','-1','1.001','NaN'])assert.throws(()=>M.cents(x));assert.equal(M.cents('0.01'),1);for(const f of [{...valid,file:null},{...valid,date:'2026-02-31'}])assert.throws(()=>M.apply(M.seed(),'pay','payable-chen-design',f));});
test('陈先生68万与张先生80至83万变更分开项目',()=>{const d=M.D;assert.equal(d.projects.chen.contract,680000);assert.equal(M.seed().change.projectId,d.projects.zhang.id);assert.equal(M.seed().change.original+M.seed().change.delta,83000000);});
test('财务角色不能停用人员，业务角色不能执行管理变更',()=>{role('finance');assert.throws(()=>M.apply(M.seed(),'disable','zhang',valid));role('sales');assert.throws(()=>M.apply(M.seed(),'reconcile','receipt-pending-100000',valid));role('owner');});
test('订单按公司和账号隔离，失效ID不借用其他订单',()=>{const s=A.seed();assert.equal(A.order(s,'missing'),null);s.session.companyId='other';assert.equal(A.order(s,'service-store-v2'),null);});
test('核实金额不一致不能把原笔6000直接计为已付',()=>{assert.throws(()=>M.apply(M.seed(),'verify','payment-lin-unknown',{...valid,verifiedAmount:'2000',result:'原笔已付款'}));});
fs.mkdirSync('qa/management-profile-20261018',{recursive:true});fs.writeFileSync('qa/management-profile-20261018/logic-results.json',JSON.stringify({result:'passed',checks},null,2));console.log(checks.length+' checks passed');
