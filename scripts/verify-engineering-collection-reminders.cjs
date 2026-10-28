// 收款催款提醒：开工款和封顶款对照约定收款日，逾期进入待办催收，
// 临期在等待区提示，远离到期日维持原提示。
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const out=path.resolve('qa/engineering-collection-reminders-20261028');fs.mkdirSync(out,{recursive:true});
 await require('./role-page-runtime.cjs').connect();
 const A=require('../miniprogram/utils/account'),E=require('../miniprogram/pages/build/model'),R=require('../miniprogram/utils/engineering-records');
 const scenes=JSON.parse(fs.readFileSync('qa/engineering-records-20261021/page-scenes.json','utf8'));
 const checks=[],pass=n=>{checks.push(n);console.log('PASS '+n);};
 const shift=n=>{const t=new Date();t.setDate(t.getDate()+n);return new Date(t.getTime()-t.getTimezoneOffset()*60000).toISOString().slice(0,10);};
 const seed=scenes.find(s=>s.action==='collect').before;for(const[k,v]of seed)wx.setStorageSync(k,v);
 A.mutate(s=>A.loginDemo(s,'manager-li'));A.mutate(s=>A.switchIdentity(s,'quanzhou','engineer'));
 const pid=E.load().projects.find(p=>p.recordFlow).id;
 const setDue=(stage,due)=>{const book=JSON.parse(JSON.stringify(wx.getStorageSync('zhongshu-management-v1')));const plan=book.operations.plans.filter(p=>p.projectId===R.get(pid).projectId).at(-1);plan.installments.find(i=>i.stage===stage).due=due;wx.setStorageSync('zhongshu-management-v1',book);};
 const tasks=()=>R.tasks(R.get(pid));
 setDue('start',shift(-2));
 let t=tasks();assert(t.pending.some(x=>x.id.endsWith('collect-start')&&x.title.includes('已过约定收款日2天')),'逾期开工款进入待催收待办');assert(!t.waiting.some(x=>x.id.endsWith('collect-start')));
 pass('开工款过约定收款日进入待办催收');
 setDue('start',shift(3));
 t=tasks();assert(t.waiting.some(x=>x.id.endsWith('collect-start')&&x.title.includes('约定收款日临近')),'临期在等待区提示');assert(!t.pending.some(x=>x.id.endsWith('collect-start')));
 pass('开工款临期提示收款日临近');
 setDue('start',shift(30));
 t=tasks();assert(t.waiting.some(x=>x.id.endsWith('collect-start')&&/开工款待财务登记收款/.test(x.title)),'远期维持原提示');assert(!t.waiting.some(x=>x.title.includes('临近')));
 pass('未到期的开工款维持原有等待提示');
 const topping=scenes.find(s=>s.action==='collect'&&s.extra&&s.extra.stage==='topping').before;for(const[k,v]of topping)wx.setStorageSync(k,v);
 A.mutate(s=>A.loginDemo(s,'manager-li'));A.mutate(s=>A.switchIdentity(s,'quanzhou','engineer'));
 setDue('topping',shift(-1));
 t=tasks();assert(t.pending.some(x=>x.id.endsWith('collect-topping')&&x.title.includes('封顶款')&&x.title.includes('已过约定收款日1天')),'封顶后封顶款逾期催收');
 pass('登记封顶后封顶款逾期进入催收待办');
 fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({result:'passed',checks,projectId:pid,evidence:'Model-level task derivation for collection reminders'},null,2));console.log('COMPLETE',checks.length,'checks');
})().catch(e=>{console.error(e.stack);fs.writeFileSync(path.resolve('qa/engineering-collection-reminders-20261028/results.json'),JSON.stringify({result:'failed',error:e.message},null,2));process.exitCode=1;});
