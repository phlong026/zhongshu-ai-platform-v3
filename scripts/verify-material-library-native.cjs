const automator=require('miniprogram-automator'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const output=path.resolve(process.env.MATERIAL_QA_OUTPUT||'docs/material-library-20261118');fs.mkdirSync(output,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const mp=await automator.connect({wsEndpoint:'ws://127.0.0.1:9989'}),errors=[];mp.on('exception',e=>errors.push(String(e.message||e)));
 assert.ok(await mp.evaluate(()=>!!globalThis.__creationFixture&&wx.getStorageSync('zs-api-token-v1')==='isolated-native-test'),'isolated fixture required');
 const fixturePhotos={};for(const key of ['house','garden','site','presenter'])fixturePhotos[key]=fs.readFileSync(path.join(output,'fixtures',key+'.jpg')).toString('base64');
 await mp.evaluate(photos=>{const f=wx.getFileSystemManager();for(const key of Object.keys(photos))f.writeFileSync(wx.env.USER_DATA_PATH+'/material-'+key+'.jpg',photos[key],'base64');},fixturePhotos);
 await mp.evaluate(()=>{
   const state=globalThis.__materialFixture={calls:[],items:[
     {id:'701',name:'新中式别墅外观',mimeType:'image/jpeg',sizeBytes:2726298,createdAt:'2026-11-16',visibility:'PERSONAL',canManage:true,file:wx.env.USER_DATA_PATH+'/material-house.jpg'},
     {id:'702',name:'庭院生活',mimeType:'image/jpeg',sizeBytes:3250585,createdAt:'2026-11-15',visibility:'PERSONAL',canManage:true,file:wx.env.USER_DATA_PATH+'/material-garden.jpg'},
     {id:'703',name:'工地施工记录',mimeType:'video/mp4',sizeBytes:19608371,createdAt:'2026-11-14',visibility:'PERSONAL',canManage:true,coverId:'713',durationSec:12,file:wx.env.USER_DATA_PATH+'/preview-fixture.mp4'},
     {id:'704',name:'客户案例口播',mimeType:'video/mp4',sizeBytes:12478054,createdAt:'2026-11-13',visibility:'PERSONAL',canManage:true,coverId:'714',durationSec:8,file:wx.env.USER_DATA_PATH+'/preview-fixture.mp4'},
     {id:'705',name:'张工口播录音',mimeType:'audio/mpeg',sizeBytes:2097152,createdAt:'2026-11-12',visibility:'PERSONAL',canManage:true,durationSec:3,file:wx.env.USER_DATA_PATH+'/audio-fixture.mp3'}]};
   state.audio=state.items.pop();
   const request=wx.request,download=wx.downloadFile;state.original={request,download};
   wx.request=o=>{
     const route=String(o.url).split('/admin-api')[1]?.split('?')[0]||'',method=String(o.method||'GET').toUpperCase(),body=o.data||{};
     if(!route.startsWith('/franchise/content/material-library'))return request(o);
     state.calls.push({route,method,body});let data;
     if(route.endsWith('/download-tickets'))data={downloadUrl:'/admin-api/franchise/assets/'+route.split('/')[4]+'/content?token=fixture'};
     else if(route.endsWith('/upload-tickets')){state.uploadBody=body;data={assetId:'799',uploadUrl:'https://fixture.example/upload'};}
     else if(route.endsWith('/complete')){data={status:'READY'};if(!state.items.some(x=>x.id==='799')&&state.uploadBody)state.items.unshift({id:'799',...state.uploadBody,createdAt:'2026-11-18',canManage:true,file:wx.env.USER_DATA_PATH+'/material-house.jpg'});}
     else if(method==='PUT'){const row=state.items.find(x=>x.id===route.split('/').pop());if(row)Object.assign(row,body);data=true;}
     else if(method==='DELETE'){state.items=state.items.filter(x=>x.id!==route.split('/').pop());data=true;}
     else{const filtered=state.items.filter(x=>(body.scope!=='COMPANY'||x.visibility==='COMPANY')&&(body.type==='ALL'||x.mimeType.startsWith(body.type.toLowerCase()+'/'))&&x.name.includes(body.keyword||''));data={items:filtered,total:filtered.length,page:1,pageSize:20};}
     setTimeout(()=>o.success({statusCode:200,data:{code:0,data}}),30);return {abort(){}};
   };
   wx.downloadFile=o=>{
     if(!String(o.url).includes('/franchise/assets/'))return download(o);
     const id=String(o.url).match(/assets\/(\d+)/)[1],row=state.items.find(x=>x.id===id);
     let file=row?.file||(wx.env.USER_DATA_PATH+(id==='713'?'/material-site.jpg':'/material-presenter.jpg'));
     if(!file.startsWith(wx.env.USER_DATA_PATH)){const copy=wx.env.USER_DATA_PATH+'/material-qa-'+id+'.jpg';wx.getFileSystemManager().copyFileSync(file,copy);file=copy;}
     setTimeout(()=>o.success({statusCode:200,tempFilePath:file}),10);return {onHeadersReceived(){}};
   };
 });
 const invoke=(method,arg)=>mp.evaluate((method,arg)=>{getCurrentPages().slice(-1)[0][method](arg);},method,arg);
 const page=await mp.reLaunch('/pages/studio/index?view=library');
 async function until(fn,label){for(let i=0;i<60;i++){if(await fn())return;await sleep(100);}throw Error('timeout: '+label);}
 await until(async()=>(await page.data()).materialItems?.length===4,'list');await sleep(700);
 await mp.screenshot({path:path.join(output,'01-library-grid.png')});
 const rects=await mp.evaluate(()=>new Promise(resolve=>wx.createSelectorQuery().select('.material-footer').boundingClientRect().selectAll('.material-card').boundingClientRect().exec(resolve)));
 assert.equal(rects[1].length,4);assert.ok(Math.abs(rects[1][0].top-rects[1][1].top)<2);assert.ok(rects[0].left>=0);
 await invoke('openMaterial',{currentTarget:{dataset:{id:'703'}}});await until(async()=>!!(await page.data()).materialPreviewUrl,'video loaded');await sleep(1200);
 await mp.screenshot({path:path.join(output,'02-video-preview.png')});await invoke('closeMaterialPreview');assert.equal((await page.data()).materialPreview,null);assert.equal((await page.data()).materialItems.length,4);
 await mp.evaluate(()=>globalThis.__materialFixture.items.push(globalThis.__materialFixture.audio));
 await invoke('materialTypeChange',{currentTarget:{dataset:{type:'AUDIO'}}});await until(async()=>(await page.data()).materialItems?.length===1,'audio filter');
 await invoke('openMaterial',{currentTarget:{dataset:{id:'705'}}});await until(async()=>!!(await page.data()).materialPreviewUrl,'audio loaded');await sleep(650);await mp.screenshot({path:path.join(output,'03-audio-preview.png')});
 await invoke('closeMaterialPreview');await invoke('materialScopeChange',{currentTarget:{dataset:{scope:'COMPANY'}}});await until(async()=>!(await page.data()).materialLoading,'company empty');assert.equal((await page.data()).materialItems.length,0);await mp.screenshot({path:path.join(output,'04-company-empty.png')});
 await invoke('materialScopeChange',{currentTarget:{dataset:{scope:'PERSONAL'}}});await invoke('materialTypeChange',{currentTarget:{dataset:{type:'ALL'}}});await until(async()=>(await page.data()).materialItems?.length===5,'all restored');
 await page.setData({materialKeyword:'庭院'});await invoke('materialSearch');await until(async()=>(await page.data()).materialItems?.length===1,'search');assert.equal((await page.data()).materialItems[0].id,'702');
 await invoke('materialClearSearch');await until(async()=>(await page.data()).materialItems?.length===5,'clear search');await invoke('materialManageToggle');await mp.screenshot({path:path.join(output,'05-manage.png')});await invoke('materialManageToggle');
 await mp.evaluate(()=>{wx.showActionSheet=o=>o.success({tapIndex:0});wx.chooseMedia=o=>o.success({tempFiles:[{tempFilePath:wx.env.USER_DATA_PATH+'/material-house.jpg'}]});});
 await invoke('materialChooseUpload');await until(async()=>(await page.data()).materialItems?.[0]?.id==='799','upload completed');
 assert.equal((await page.data()).materialUploadPhase,'上传完成');assert.equal((await page.data()).materialPending,false);await mp.screenshot({path:path.join(output,'06-upload-complete.png')});
 await mp.evaluate(()=>{
   const f=globalThis.__materialFixture;f.pending=[];f.previewCount=0;f.playerCount=0;f.savedRequest=wx.request;f.savedPreview=wx.previewImage;f.savedAudio=wx.createInnerAudioContext;
   wx.request=o=>{if(String(o.url).includes('/material-library/')&&String(o.url).endsWith('/download-tickets')){f.pending.push(()=>f.savedRequest(o));return {abort(){}};}return f.savedRequest(o);};
   wx.previewImage=o=>{f.previewCount++;return f.savedPreview(o);};wx.createInnerAudioContext=()=>{f.playerCount++;return f.savedAudio();};
 });
 for(const id of ['705','701']) {
   await invoke('openMaterial',{currentTarget:{dataset:{id}}});await invoke('onHide');
   await mp.evaluate(()=>{globalThis.__materialFixture.pending.splice(0).forEach(f=>f());});await sleep(120);
   assert.equal((await page.data()).materialPreview,null);
 }
 assert.deepEqual(await mp.evaluate(()=>({previews:globalThis.__materialFixture.previewCount,players:globalThis.__materialFixture.playerCount})),{previews:0,players:0});
 await mp.evaluate(()=>{const f=globalThis.__materialFixture;wx.request=f.savedRequest;wx.previewImage=f.savedPreview;wx.createInnerAudioContext=f.savedAudio;});
 const result={route:page.path,checks:['two-column layout','real API list contract','category filter','company scope empty','search and clear','video explicit close returns to list','audio inline player','manage mode','file upload and acknowledged save','hidden page rejects delayed audio and image previews'],errors,rects};
 fs.writeFileSync(path.join(output,'native-result.json'),JSON.stringify(result,null,2));assert.equal(errors.length,0,errors.join('\n'));await mp.disconnect();console.log(JSON.stringify({passed:result.checks.length,output}));
})().catch(e=>{console.error(e);process.exit(1)});
