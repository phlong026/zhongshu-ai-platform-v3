const fs=require('fs'),assert=require('assert/strict');
(async()=>{
 await require('./role-page-runtime.cjs').connect();
 const A=require('../miniprogram/utils/account');A.mutate(s=>A.loginDemo(s,'finance-dual'));
 fs.mkdirSync('qa/finance-implementation-20261021',{recursive:true});
 const D=require('../miniprogram/utils/finance-desk'),N=require('../miniprogram/utils/page-navigation');
 wx.reLaunch({url:D.url('flows','',{tab:'payables'})});let page=getCurrentPages().at(-1);
 const target=page.data.rows.find(r=>r.available>0);assert(target);page.setData({keyword:target.name.split(' · ')[0],statusFilter:target.status,category:target.category,sort:'金额优先',due:''});page.refresh();
 const expected={flowTab:page.data.flowTab,keyword:page.data.keyword,statusFilter:page.data.statusFilter,category:page.data.category,sort:page.data.sort,rows:page.data.rows.map(r=>r.id)};
 // The real navigation code receives success callbacks; the deterministic
 // Page harness needs to provide that callback after creating the real page.
 const redirect=wx.redirectTo;wx.redirectTo=o=>{const result=redirect(o);o.success?.({errMsg:'redirectTo:ok'});return result;};
 const stack=getCurrentPages();for(let i=0;i<7;i++)stack.unshift({route:'pages/account/index',options:{view:'policy',id:'dummy-'+i},id:'dummy-'+i,data:{view:'policy'}});
 N.open(target.url);assert(getCurrentPages().at(-1).__navigationTrail?.length);N.back();page=getCurrentPages().at(-1);assert.equal(page.route,'pages/admin/finance-desk/index');assert(page.data.ready,page.data.error);
 const actual={flowTab:page.data.flowTab,keyword:page.data.keyword,statusFilter:page.data.statusFilter,category:page.data.category,sort:page.data.sort,rows:page.data.rows.map(r=>r.id)};assert.deepEqual(actual,expected);
 fs.writeFileSync('qa/finance-implementation-20261021/navigation-restore-results.json',JSON.stringify({result:'passed',scope:'实际财务Page与共享导航的深层替换返回；检验保留分段、搜索、状态、类别、排序并重新派生原单列表。Node集成，非新增原生截图。',expected,actual},null,2));console.log('PASS 财务深层返回恢复筛选并重新派生原单列表');
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
