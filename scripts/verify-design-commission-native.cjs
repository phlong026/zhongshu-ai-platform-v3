const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),automator=require('miniprogram-automator');
const out=path.resolve('docs/creation-repair-20261117'),sleep=ms=>new Promise(r=>setTimeout(r,ms)),timer=setTimeout(()=>process.exit(2),55000);
(async()=>{
 const mp=await automator.connect({wsEndpoint:'ws://127.0.0.1:9989'}),errors=[];mp.on('exception',e=>errors.push(e.message||String(e)));
 assert.ok(await mp.evaluate(()=>!!globalThis.__creationFixture&&wx.getStorageSync('zs-api-token-v1')==='isolated-native-test'),'requires isolated fixture');
 await mp.evaluate(()=>{
  const state=globalThis.__creationFixture;state.post.roleCode='sales';wx.setStorageSync('zs-api-posts-v1',[state.post]);state.people[0].canManage=true;
  const request=wx.request;wx.request=o=>{
   let data;const url=o.url,body=o.data||{};
   if(url.endsWith('/franchise/design/flow')){
    const rows=[{projectId:'501',name:'张先生',stage:'FLAT',jobId:'601'},{projectId:'502',name:'李先生',stage:'ELEVATION',jobId:'602',resultVersionId:'702'},{projectId:'503',name:'独立方案',stage:'FLAT',jobId:'603'}];
    data=body.path==='/design-projects'?{list:rows}:rows.find(r=>body.path.endsWith('/'+r.projectId));
   }else if(url.includes('/franchise/design/flow-ai-jobs/')){const id=url.split('/').pop();data={jobId:id,status:id==='601'?'RUNNING':id==='602'?'SUCCEEDED':'FAILED',requestedCount:2,acceptedCount:id==='601'?1:id==='602'?2:0,createdAt:'2026-11-17T14:32:00+08:00',imageOptions:{resolution:'2K'}};}
   else if(url.includes('/franchise/design/cases'))data={list:Array.from({length:12},(_,i)=>({caseId:String(i+1),title:['田园一层4室2厅','新中式二层三室两厅','现代雅居','庭院别墅'][i%4],faceWidth:12,depth:15,floorCount:2})),nextCursor:'more'};
   else return request(o);
   setTimeout(()=>o.success({statusCode:200,data:{code:0,data}}),10);return{abort(){}};
  };setTimeout(()=>wx.switchTab({url:'/pages/engineering/index'}),40);return true;
 });
 await sleep(1800);let page=await mp.currentPage();assert.equal((await page.data()).recentCount,3);assert.equal((await page.data()).recentRunning,1);assert.equal((await page.data()).catalogCases.length,4);
 await mp.evaluate(()=>{const p=getCurrentPages().slice(-1)[0];p.setData({catalogCases:p.data.catalogCases.map(r=>({...r,coverUrl:'/pages/design/assets/biz-case-1.jpg'})),recentTasks:p.data.recentTasks.map((r,i)=>({...r,coverUrl:i===1?'/pages/design/assets/biz-case-1.jpg':''}))});wx.pageScrollTo({scrollTop:260,duration:0});return true;});await sleep(400);
 await mp.screenshot({path:path.join(out,'18-最近任务与户型尺寸.png')});
 await mp.evaluate(()=>{wx.pageScrollTo({scrollTop:610,duration:0});return true;});await sleep(300);await mp.screenshot({path:path.join(out,'19-精选户型铺满卡片.png')});
 await mp.evaluate(()=>{setTimeout(()=>wx.reLaunch({url:'/pages/studio/characters/index'}),40);return true;});await sleep(1300);
 await mp.evaluate(()=>{const p=getCurrentPages().slice(-1)[0];p.detail({currentTarget:{dataset:{id:'90071992547409930'}}});p.resources();return true;});await sleep(300);page=await mp.currentPage();
 const texts=await Promise.all((await page.$$('.resource-actions button')).map(el=>el.text()));
 assert((await (await page.$('.resource-create-card')).size()).height>85,'creation card must show icon, title and description');
 assert.equal(texts.length,2);assert(texts[0].includes('创建口播分身'));assert(texts[1].includes('克隆声音'));assert((await page.data()).canCreate);await mp.screenshot({path:path.join(out,'20-创建口播分身入口.png')});
 await mp.evaluate(()=>{setTimeout(()=>wx.reLaunch({url:'/pages/workflow/commission/index?view=submit&customerId=701'}),40);return true;});await sleep(1400);
 await mp.evaluate(()=>{
  const p=getCurrentPages().slice(-1)[0];p.__commission={commissionId:'501',status:'DESIGNING',allowedActions:[],feedbacks:[],request:{version:1,scope:'建筑平面、立面及施工图'}};
  p.setData({view:'submit',platformStatus:'ready',error:'',submitPreview:true,designBriefEnabled:true,showCustomerPhone:true,customer:{id:'701',name:'张先生'},orderCustomer:{name:'张先生',phone:'138****6288',address:'武汉市黄陂区 · 隔离验收示例'},designBrief:{schemaVersion:1,projectName:'张先生 · 武汉自建房',orientation:'南',entranceAligned:'一致',householdMembers:'5',householdTags:['有老人','有小孩'],rooms:['卧室','客厅','餐厅','厨房'],bedrooms:'5',kitchenTypes:['封闭式'],specialRooms:['储藏室','独立洗衣间'],specialNeeds:'一层设老人房；客餐厅朝南；保留二层露台。',styleRefs:['新中式']},budgetReady:true,budgetBasis:{version:2,width:12,depth:10,floors:2,area:240,totalCents:42870000},pricingQuote:{amountText:'2400.00',area:240,rateText:'10.00'},form:{},fields:[],rows:[],primary:'提交设计委托',canPrepareBudget:false,submitBlockedReason:''});p.refreshOrderSheet();
  wx.saveImageToPhotosAlbum=o=>{globalThis.__exportedOrder=o.filePath;o.success({});};return true;
 });await sleep(400);await mp.screenshot({path:path.join(out,'21-新版设计委托单.png')});
 await mp.evaluate(async()=>{await getCurrentPages().slice(-1)[0].exportOrderImage();return true;});await sleep(300);
 const png=await mp.evaluate(()=>{if(!globalThis.__exportedOrder)return null;return wx.getFileSystemManager().readFileSync(globalThis.__exportedOrder,'base64');});assert(png,'real canvas export produced an image');
 fs.writeFileSync(path.join(out,'22-设计委托单实际导出.png'),Buffer.from(png,'base64'));
 fs.writeFileSync(path.join(out,'design-commission-native-result.json'),JSON.stringify({passed:!errors.length,errors,recentTasks:3,featuredCases:4,exported:true,scope:'隔离接口夹具；微信原生 Canvas 实际生成 PNG；相册写入由夹具代收，未改写真实业务'},null,2));
 assert.equal(errors.length,0,errors.join('\n'));await mp.disconnect();clearTimeout(timer);console.log('passed: recent tasks, cover layout, clone entry, commission native PNG export');
})().catch(e=>{clearTimeout(timer);console.error(e.stack);process.exit(1);});
