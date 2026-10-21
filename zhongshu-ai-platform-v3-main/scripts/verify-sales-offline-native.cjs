const fs=require('fs'),path=require('path'),assert=require('assert/strict'),auto=require('miniprogram-automator');
const root=path.resolve(__dirname,'..'),out=path.join(root,'qa/business-module-simplification-20261021'),project=path.join(root,'.tools/business-module-simplification-20261021/native-project');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 fs.mkdirSync(project,{recursive:true});fs.cpSync(path.join(root,'miniprogram'),path.join(project,'miniprogram'),{recursive:true});
 const cfg=JSON.parse(fs.readFileSync(path.join(root,'project.config.json')));cfg.appid='touristappid';cfg.projectname='business-offline-isolated-qa';fs.writeFileSync(path.join(project,'project.config.json'),JSON.stringify(cfg,null,2));
 const scenes=JSON.parse(fs.readFileSync(path.join(out,'page-scenes.json'))),result={result:'running',stage:'connecting',storageChanged:false,project,checks:[],screenshots:[],evidence:'Official WeChat automator; isolated tourist project; no upload or external payment'};let mp,before;
 const persist=()=>fs.writeFileSync(path.join(out,'native-results.json'),JSON.stringify(result,null,2));
 try{
  mp=process.env.SALES_NATIVE_CONNECT==='1'?await auto.connect({wsEndpoint:'ws://127.0.0.1:9891'}):await auto.launch({projectPath:project,cliPath:process.env.SALES_NATIVE_CLI,port:9891,trustProject:true,timeout:45000});
  result.stage='reading-isolated-runtime';persist();
  before=await mp.evaluate(()=>wx.getStorageInfoSync().keys.map(k=>[k,wx.getStorageSync(k)]));
  for(const [i,s]of scenes.entries()){
   result.stage=s.action;result.storageChanged=true;persist();
   await mp.evaluate(rows=>{wx.getStorageInfoSync().keys.forEach(k=>wx.removeStorageSync(k));rows.forEach(([k,v])=>wx.setStorageSync(k,v));},s.before);
   const view=({'commission-submit':'submit','design-complete':'complete','design-dispatch':'dispatch','packet-submit':'handover','packet-return':'receive','packet-receive':'receive'})[s.action];
   const page=await mp.reLaunch('/pages/workflow/commission/index?view='+view+'&id='+s.id);await sleep(800);const data=await page.data();assert(!data.error,data.error);assert(data.primary,s.action+' primary');
   const screenshot=path.join(out,'native-'+i+'-'+s.action+'.png');await mp.screenshot({path:screenshot});result.screenshots.push(screenshot);
   await page.setData({form:{...data.form,...s.input}});const button=await page.$(s.action==='packet-return'?'button[bindtap="returnPacket"]':'.commission-actions button[bindtap="submit"]');assert(button);await button.tap();await sleep(500);assert(!(await page.data()).error,(await page.data()).error);result.checks.push(s.action);persist();
  }
  result.result='passed';result.stage='complete';
 }catch(e){result.result='blocked';result.error=e.message;process.exitCode=1;console.log('Native verification unavailable: '+e.message);}
 finally{
  if(mp&&before){await mp.evaluate(rows=>{wx.getStorageInfoSync().keys.forEach(k=>wx.removeStorageSync(k));rows.forEach(([k,v])=>wx.setStorageSync(k,v));},before).then(()=>{result.storageRestored=true;result.storageChanged=false;},e=>{result.restoreError=e.message;process.exitCode=1;});}
  if(mp)await mp.disconnect();persist();
 }
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
