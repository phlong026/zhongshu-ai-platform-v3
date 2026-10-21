const fs=require('fs'),assert=require('assert/strict'),auto=require('miniprogram-automator');
const out='qa/workbench-hubei-20261019/native',wait=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const mp=await auto.connect({wsEndpoint:'ws://127.0.0.1:9865'}),checks=[],errors=[];mp.on('exception',e=>errors.push(e));
 const before=await mp.evaluate(()=>wx.getStorageInfoSync().keys.map(k=>[k,wx.getStorageSync(k)]));
 async function open(url){await mp.callWxMethod('reLaunch',{url});await wait(800);return mp.currentPage();}
 try{
  const migrated=await mp.evaluate(()=>{wx.setStorageSync('zhongshu-id-path-proof',{recordId:'泉州-001',path:'C:/泉州/合同.pdf',name:'泉州项目'});wx.removeStorageSync('zhongshu-region-hubei-v1');getApp().onLaunch();return wx.getStorageSync('zhongshu-id-path-proof');});assert.equal(migrated.recordId,'泉州-001');assert.equal(migrated.path,'C:/泉州/合同.pdf');assert.equal(migrated.name,'湖北项目');checks.push({name:'地域迁移保留中文记录ID和本机附件路径',result:'passed'});
  await open('/pages/account/index?view=login');await mp.evaluate(()=>{const p=getCurrentPages().at(-1);p.setData({selectedDemoUser:'manager-li'});setTimeout(()=>p.loginDemo(),80);return true;});await wait(1000);
  let p=await open('/pages/engineering/index');
  const fixture={id:'delivery-proof',projectId:'delivery-proof',companyId:'quanzhou',managerId:'li',manager:'李工程师',customer:'客户',name:'交付统计核对',city:'武汉市',floors:2,area:400,actualStart:{date:'2026-10-01'},plan:{version:1,nodes:[{id:'node-proof',name:'基础工程',status:'已完成'}]},delivery:{status:'已核验'},archived:false,image:'/pages/engineering/assets/project-chen.jpg'};
  await mp.evaluate(p=>{wx.setStorageSync('zhongshu-engineering-v1',{version:1,projects:[p]});getCurrentPages().at(-1).onShow();},fixture);await wait(100);assert.equal(await mp.evaluate(()=>getCurrentPages().at(-1).data.deskStats[1].value),0);
  fixture.delivery.status='待核验';await mp.evaluate(p=>{wx.setStorageSync('zhongshu-engineering-v1',{version:1,projects:[p]});getCurrentPages().at(-1).onShow();},fixture);await wait(100);assert.equal(await mp.evaluate(()=>getCurrentPages().at(-1).data.deskStats[1].value),1);checks.push({name:'实际页面待交付计数排除已核验，计入待核验',result:'passed'});
  for(const guest of [false,true]){
   if(guest)await mp.evaluate(()=>{const s=wx.getStorageSync('zhongshu-account-v1');s.session.loggedIn=false;wx.setStorageSync('zhongshu-account-v1',s);});
   p=await open('/pages/profile/index');assert.equal((await p.$$('.demo-entry')).length,0);assert(!(await(await p.$('.profile-page')).text()).includes('全流程模拟演示'));assert(await p.$('.academy-section'));assert(await p.$('[data-view="help"]'));
   await mp.pageScrollTo(100000);await wait(140);await mp.screenshot({path:out+'/profile-'+(guest?'guest':'signed-in')+'-final.png'});checks.push({name:(guest?'未登录':'已登录')+'我的页删除演示入口，学习培训及帮助入口保留',result:'passed'});
  }
  assert.equal(errors.length,0);fs.writeFileSync(out+'/final-results.json',JSON.stringify({result:'passed',checks,errors},null,2));console.log(JSON.stringify({result:'passed',checks:checks.length}));
 }finally{await mp.evaluate(rows=>{for(const k of wx.getStorageInfoSync().keys)wx.removeStorageSync(k);for(const[k,v]of rows)wx.setStorageSync(k,v);},before);await open('/pages/profile/index');await mp.disconnect();}
})().catch(e=>{console.error(e);process.exit(1);});
