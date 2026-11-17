const fs=require('node:fs'),path=require('node:path'),automator=require('miniprogram-automator');
const out=path.resolve('docs/business-audit-20261116');
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const routes=[
 ['23-profile','/pages/profile/index'],['24-messages','/pages/account/index?view=messages'],
 ['25-performance','/pages/admin/tasks/index?view=mine'],['26-learning','/pages/account/index?view=learning'],
 ['27-privacy','/pages/account/index?view=privacy'],['28-help','/pages/account/index?view=help'],
 ['29-characters','/pages/studio/characters/index'],['30-remix','/pages/studio/index?view=remix'],
 ['31-digital','/pages/studio/index?view=digital'],['32-materials','/pages/studio/index?view=library'],
 ['33-works','/pages/studio/index?view=works'],['34-tasks','/pages/studio/index?view=tasks'],
 ['35-records','/pages/studio/index?view=records'],['36-ai-video','/pages/ai-video/index'],
 ['37-budget','/design-migration/pages/budget/quick'],
 ['38-budget-history','/design-migration/pages/budget/history'],
 ['39-ai-style','/design-migration/pages/ai-design/index?line=ELEVATION']
];
(async()=>{const mp=await automator.connect({wsEndpoint:'ws://127.0.0.1:9961'});const result={at:new Date().toISOString(),mode:'live navigation; no submit/save/generate/publish/payment buttons',pages:[],console:[],exceptions:[]};
const persist=()=>fs.writeFileSync(path.join(out,'extended-run.json'),JSON.stringify(result,null,2));
mp.on('exception',e=>{result.exceptions.push(e);persist();});mp.on('console',e=>{result.console.push(e);persist();});
try{for(const[name,url]of routes){try{await mp.reLaunch(url);await wait(1800);const state=await mp.evaluate(()=>{const p=getCurrentPages().slice(-1)[0],d=p.data;return {route:p.route,view:d.view,line:d.line,errors:Object.fromEntries(Object.entries(d).filter(([k,v])=>/error/i.test(k)&&typeof v==='string'&&v)),status:d.platformStatus,loading:d.loading,busy:d.busy,budgetDraftPresent:!!d.draft};});await mp.screenshot({path:path.join(out,'screenshots',name+'.png')});result.pages.push({name,url,...state});console.log(name,JSON.stringify(state));}catch(e){result.pages.push({name,url,toolError:e.message});console.log(name,e.message);}persist();}}
finally{await mp.switchTab('/pages/business/index').catch(()=>{});await mp.disconnect();persist();}})().catch(e=>{console.error(e);process.exitCode=1;});
