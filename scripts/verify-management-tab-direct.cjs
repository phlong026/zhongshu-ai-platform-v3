// 批次A1验收：管理Tab按角色直达工作台，删除双人格中转渲染
const assert=require('assert/strict');
let store={},redirects=[];
global.wx={getStorageSync:k=>store[k],setStorageSync:(k,v)=>{store[k]=JSON.parse(JSON.stringify(v));},removeStorageSync:k=>{delete store[k];},getStorageInfoSync:()=>({keys:Object.keys(store)}),redirectTo:o=>redirects.push(o.url),switchTab:o=>redirects.push(o.url),navigateTo:()=>{},showToast:()=>{},showModal:()=>{}};
global.getApp=()=>({globalData:{}});
global.getCurrentPages=()=>[{route:'pages/management/index',switchWorkspaceTab:()=>false}];
const pages=[];global.Page=c=>{pages.push(c);return c;};
const A=require('../miniprogram/utils/account'),X=require('../miniprogram/utils/access'),O=require('../miniprogram/utils/organization');
const results=[],pass=n=>{results.push(n);console.log('PASS '+n);};
(function(role){const MKEY=require('../miniprogram/utils/storage-keys').management;A.mutate(s=>{Object.assign(s.session,{loggedIn:true,companyId:'quanzhou',staffId:role+'-x',userId:role+'-x',role:role});s.companies=[{id:'quanzhou',name:'门店',type:'store',roles:['sales','sales-lead','engineer','project-manager','finance','finance-lead','boss','owner']}];});wx.setStorageSync(MKEY,{version:1,companyId:'quanzhou',staff:[{id:role+'-x',personId:role+'-x',name:role,roles:[role],teamIds:{sales:'sales',engineering:'engineering',finance:'finance'}}]});})('finance')
const nav=O.navigation(X.context());
assert.equal(nav.tabs[0].url,'/pages/admin/finance-desk/index','财务首个Tab应直达财务工作台，实际 '+nav.tabs[0].url);
assert.equal(nav.tabs[0].native,false);
assert.equal(nav.tabs[0].text,'首页');
pass('财务身份底部首个Tab直达财务工作台页面');
require('../miniprogram/pages/management/index');
require('../miniprogram/pages/admin/finance-desk/index');
assert.equal(pages.length,2);
const inst=cfg=>({data:JSON.parse(JSON.stringify(cfg.data)),setData(d){Object.assign(this.data,d);},getTabBar:()=>null});
const mgmt=inst(pages[0]);
redirects=[];pages[0].onShow.call(mgmt);
assert.ok(redirects.includes('/pages/admin/finance-desk/index'),'财务进入管理Tab应直达财务工作台，实际 '+JSON.stringify(redirects));
assert.equal(mgmt.data.financeDesk,undefined,'中转页不应再内嵌财务桌面数据');
pass('财务访问管理Tab页面时直达财务工作台，不再原地渲染');
(function(role){const MKEY=require('../miniprogram/utils/storage-keys').management;A.mutate(s=>{Object.assign(s.session,{loggedIn:true,companyId:'quanzhou',staffId:role+'-x',userId:role+'-x',role:role});s.companies=[{id:'quanzhou',name:'门店',type:'store',roles:['sales','sales-lead','engineer','project-manager','finance','finance-lead','boss','owner']}];});wx.setStorageSync(MKEY,{version:1,companyId:'quanzhou',staff:[{id:role+'-x',personId:role+'-x',name:role,roles:[role],teamIds:{sales:'sales',engineering:'engineering',finance:'finance'}}]});})('boss')
redirects=[];pages[0].onShow.call(inst(pages[0]));
assert.ok(redirects.includes('/pages/admin/home/index'),'老板应直达老板首页');
pass('老板访问管理Tab仍直达老板首页');
(function(role){const MKEY=require('../miniprogram/utils/storage-keys').management;A.mutate(s=>{Object.assign(s.session,{loggedIn:true,companyId:'quanzhou',staffId:role+'-x',userId:role+'-x',role:role});s.companies=[{id:'quanzhou',name:'门店',type:'store',roles:['sales','sales-lead','engineer','project-manager','finance','finance-lead','boss','owner']}];});wx.setStorageSync(MKEY,{version:1,companyId:'quanzhou',staff:[{id:role+'-x',personId:role+'-x',name:role,roles:[role],teamIds:{sales:'sales',engineering:'engineering',finance:'finance'}}]});})('finance')
const desk=inst(pages[1]);
pages[1].onLoad.call(desk,{});
assert.equal(desk.data.view,'desk','财务打开财务桌面默认应渲染工作台视图，实际 '+desk.data.view);
pass('财务桌面默认视图为工作台首页');
const desk2=inst(pages[1]);
pages[1].onLoad.call(desk2,{view:'flows'});
assert.equal(desk2.data.view,'flows');
pass('显式视图参数仍然生效，收支与项目入口不受影响');
console.log(results.length+' 项通过');
