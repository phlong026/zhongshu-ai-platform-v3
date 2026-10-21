const fs=require('fs');
const clone=x=>JSON.parse(JSON.stringify(x)),steps=[];
function rows(){return wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')).sort().map(k=>[k,wx.getStorageSync(k)]);}
function run(meta,fn){if(!process.env.DEMO_CAPTURE||global.__DEMO_CAPTURE_FILTER===false)return fn();const before=rows(),p=getCurrentPages().at(-1),account=wx.getStorageSync('zhongshu-account-v1');const context={route:p?.route,view:p?.data?.view,title:p?.data?.title||p?.data?.pageTitle,form:clone(p?.data?.form||{}),actor:clone(account.session),actorName:account.profile.name,...meta};const result=fn();steps.push({...context,before,after:rows(),resultRoute:getCurrentPages().at(-1)?.route,resultView:getCurrentPages().at(-1)?.data?.view,resultId:getCurrentPages().at(-1)?.id,resultService:getCurrentPages().at(-1)?.options?.service});return result;}
process.on('exit',code=>{if(process.env.DEMO_CAPTURE&&!code)fs.writeFileSync(process.env.DEMO_CAPTURE,JSON.stringify({steps},null,2));});
module.exports={run};
