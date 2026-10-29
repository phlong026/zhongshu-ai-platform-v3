const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const out='qa/design-journey-20261024/visual';
 const r=await require('./franchise-native-runtime.cjs').connect('journey-visual',{directory:out,wsEndpoint:process.env.FRANCHISE_WS||'ws://127.0.0.1:10039',realEvents:true});
 const scenes=JSON.parse(fs.readFileSync('qa/design-journey-20261024/page-scenes.json'));
 const shot=async name=>{await r.mp.evaluate(()=>{wx.hideToast();return true;});await r.sleep(650);await r.capture(name);};
 try{
  const submit=scenes.find(s=>s.action==='commission-submit');await r.replace(submit.before);await r.open('/pages/workflow/commission/index?view=submit&id='+submit.id,'.commission-page',{relaunch:true});await shot('01-submit');
  await r.replace(submit.after);await r.open('/pages/workflow/commission/index?view=pay&id='+submit.id,'.commission-page',{relaunch:true});await r.event('.journey-pay','openCashier');await shot('02-wechat-cashier');await r.event('.cashier-close','closeCashier');
  await r.event({selector:'button',dataset:{channel:'alipay'}},'selectPaymentChannel');await r.event('.journey-pay','openCashier');await shot('03-alipay-cashier');await r.event('.cashier-close','closeCashier');
  const complete=scenes.find(s=>s.action==='design-complete');assert(complete);await r.replace(complete.after);await r.open('/pages/workflow/commission/index?view=dispatch&id='+complete.id,'.commission-page',{relaunch:true});await shot('04-design-results');
  await r.event({selector:'button',dataset:{previewKey:'drawingFile'}},'previewJourneyFile');await shot('05-drawing-preview');
  fs.writeFileSync(path.join(out,'scope.json'),JSON.stringify({scope:'只读视觉预检：使用已由Page事件生成的模拟状态，不作为跨角色连续办理证据。',screenshots:5,exceptions:r.errors},null,2));
 }finally{await r.restore();const b=path.join(out,'journey-visual-before-storage.json');if(fs.existsSync(b))fs.unlinkSync(b);}
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
