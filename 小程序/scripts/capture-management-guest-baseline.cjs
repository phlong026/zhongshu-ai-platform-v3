const fs=require('fs'),path=require('path'),auto=require('miniprogram-automator');
const out=path.resolve('qa/management-guest-20261019'),wait=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{const mp=await auto.connect({wsEndpoint:'ws://127.0.0.1:9820'});let before;
try{before=await mp.evaluate(()=>wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')).map(k=>[k,wx.getStorageSync(k)]));
await mp.evaluate(()=>{const key='zhongshu-account-v1',s=wx.getStorageSync(key)||{version:1,session:{},profile:{},companies:[]};s.session.loggedIn=false;wx.setStorageSync(key,s);return true;});
await mp.callWxMethod('reLaunch',{url:'/pages/management/index'});await wait(1800);await mp.screenshot({path:path.join(out,'before.png')});
fs.writeFileSync(path.join(out,'before-page.json'),JSON.stringify(await mp.evaluate(()=>getCurrentPages().at(-1).data),null,2));console.log('Native guest baseline captured.');
}finally{if(before)await mp.evaluate(rows=>{wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')).forEach(k=>wx.removeStorageSync(k));for(const[k,v]of rows)wx.setStorageSync(k,v);return true;},before);await mp.disconnect();}})().catch(e=>{console.error(e);process.exitCode=1;});
