// Leave the simulator ready for a human walkthrough. This intentionally keeps
// demo records, unlike the verification scripts which restore their snapshots.
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
process.env.FRANCHISE_WS=process.env.FRANCHISE_WS||'ws://127.0.0.1:9430';
process.env.FRANCHISE_NATIVE_OUT=path.resolve('qa/business-module-redesign-20261019/preview');
const {connect}=require('./franchise-native-runtime.cjs');
(async()=>{const r=await connect('prepare-preview');try{
 const flags=await r.mp.evaluate(()=>({live:!!wx.getStorageSync('zhongshu-live-demo-v1'),stage:!!wx.getStorageSync('zhongshu-demo-session-v1')}));
 assert(!flags.stage,'End the active stage walkthrough using its restore action first');
 if(flags.live){await r.open('/pages/demo/live','.restore-live');await r.event('.restore-live','restore');await r.until(async()=>(await r.current()).route==='pages/profile/index','original records restored');}
 const accounts=[];
 for(const[id,role]of [['sales-lead','sales-lead'],['zhang','sales']]){
  await r.login(id,role);await r.open('/pages/customers/index','.sales-page');const d=await r.data();assert(d.customers.length>0);accounts.push({id,role,customers:d.customers.length});await r.capture(id+'-customers');
 }
 await r.open('/pages/business/index','.sales-page');await r.capture('ready-home');assert.equal(r.errors.length,0);
 fs.writeFileSync(path.join(r.directory,'ready.json'),JSON.stringify({result:'passed',accounts,route:(await r.current()).route,previousContinuousDemoRestored:flags.live,beforeStorage:'prepare-preview-before-storage.json',intent:'Keep local mock clients and videos ready for manual review'},null,2));console.log(JSON.stringify({result:'passed',accounts,route:(await r.current()).route}));
 }finally{await r.mp.disconnect();}
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
