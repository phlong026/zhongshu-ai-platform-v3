// 官方开发者工具真机调试；仅保存扫码图片和脱敏设备证据，不保存登录凭据。
const fs=require('node:fs'),path=require('node:path'),automator=require('miniprogram-automator');
const out=path.resolve(__dirname,'../docs/creation-fixes-20261116/live');fs.mkdirSync(out,{recursive:true});
(async()=>{
  const mp=await automator.connect({wsEndpoint:process.env.CREATION_LIVE_WS||'ws://127.0.0.1:9976'});
  const liveSource=path.resolve(process.env.CREATION_LIVE_ROOT||'C:/Users/Administrator/.codex/artifacts/creation-flow-20261116/live-native/miniprogram/app.js');
  if(fs.readFileSync(liveSource,'utf8').includes('__creationFixture'))throw Error('Live project must not contain a request fixture');
  const send=mp.send.bind(mp);mp.send=async(method,...args)=>{const result=await send(method,...args);if(method==='Tool.enableRemoteDebug'&&result.qrCode){fs.writeFileSync(path.join(out,'phone-debug-qr.png'),Buffer.from(result.qrCode,'base64'));console.log('QR_READY');}return result;};
  require('miniprogram-automator/out/util').printQrCode=async()=>{};
  console.log('STARTING_OFFICIAL_PHONE_DEBUG');await mp.remote(false);
  const info=await mp.callWxMethod('getSystemInfo');
  const proof={connectedAt:new Date().toISOString(),platform:info.platform,model:info.model,system:info.system,SDKVersion:info.SDKVersion,windowWidth:info.windowWidth,windowHeight:info.windowHeight,pixelRatio:info.pixelRatio};
  fs.writeFileSync(path.join(out,'phone-connection.json'),JSON.stringify(proof,null,2));console.log('PHONE_CONNECTED '+JSON.stringify(proof));
  await mp.disconnect();
})().catch(e=>{console.error(e.message);process.exit(1);});
