const fs=require('fs'),path=require('path'),assert=require('assert/strict');
process.env.FRANCHISE_WS=process.env.FRANCHISE_WS||'ws://127.0.0.1:9430';
process.env.FRANCHISE_NATIVE_OUT=path.resolve('qa/business-module-redesign-20261019/native');
const {connect}=require('./franchise-native-runtime.cjs');
(async()=>{const r=await connect('capture-'+(process.argv[2]||'home'));try{
 const area=process.argv[2]||'home',route={home:'business',customers:'customers',creation:'creation',design:'design'}[area];
 await r.mp.evaluate(()=>{wx.removeStorageSync('zhongshu-live-demo-v1');wx.removeStorageSync('zhongshu-demo-session-v1');});
 await r.login('zhang','sales');await r.open('/pages/'+route+'/index','.sales-page');
 await r.sleep(600);const data=await r.data();if(area==='home')assert(data.isSalesHome,'Source project must render the revised home');
 await r.capture(area+'-sales');const layout=await r.rects(['.sales-page','.workbench-header','.content']);
 fs.writeFileSync(path.join(r.directory,area+'-layout.json'),JSON.stringify({route,data,layout,exceptions:r.errors},null,2));
 assert.equal(r.errors.length,0,'Native exceptions');console.log(JSON.stringify({result:'passed',area,route,layout}));
}finally{await r.restore();}})().catch(e=>{console.error(e.stack);process.exitCode=1;});
