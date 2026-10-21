const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const root=path.resolve('qa/business-flow-optimization-20261020'),dir=path.join(root,'final-visual');
process.env.FRANCHISE_NATIVE_OUT=dir;process.env.FRANCHISE_WS=process.env.FRANCHISE_WS||'ws://127.0.0.1:9430';
(async()=>{const r=await require('./franchise-native-runtime.cjs').connect('final-visual'),trace=JSON.parse(fs.readFileSync(path.join(root,'independent-trace.json'))).steps,screens=[];
 const norm=v=>JSON.stringify(v.slice().sort((a,b)=>a[0].localeCompare(b[0])));
 try{
  for(const [view,file]of [['assign','01-assign'],['deliverable','02-deliverable'],['documents','03-documents']]){
   const s=trace.filter(s=>s.area==='workflow'&&new URL(s.url,'http://local').searchParams.get('view')===view).at(-1);await r.replace(s.before);await r.open(s.url);if(view!=='assign')await r.mp.evaluate(f=>{const p=getCurrentPages().at(-1);p.setData({form:{...p.data.form,...f}});return true;},s.form);const d=await r.data();assert(!d.blocked);if(view==='assign')assert.equal(d.title,'安排设计师');if(view==='documents')assert(d.sections.find(s=>s.title==='签约附件').items.some(i=>i.value?.includes('已归档')));screens.push(await r.capture(file));console.log('PASS '+file);
  }
  const returned=JSON.parse(fs.readFileSync(path.join(root,'returned-quote-fixture.json'))),id=returned.find(x=>x[0]==='zhongshu-business-v3')[1].customers.find(c=>c.id.includes('from')).id;await r.replace(returned);await r.open('/pages/workflow/index?view=quote&id='+id);screens.push(await r.capture('05-returned-quote'));
  const accepted=JSON.parse(fs.readFileSync(path.join(root,'accepted-fixture.json')));await r.replace(accepted);await r.open('/pages/build/home/index');await r.sleep(200);screens.push(await r.capture('04-engineering-workbench'));assert.equal(r.errors.length,0,JSON.stringify(r.errors));fs.writeFileSync(path.join(dir,'results.json'),JSON.stringify({result:'passed',screens,exceptions:r.errors,scope:'最终文字、文件状态及工作台品牌页头的原生截图复核；使用明确模拟前置状态，不计入连续业务操作。'},null,2));
 }finally{try{await r.replace(r.before);await r.open('/pages/business/index').catch(()=>{});await r.replace(r.before);const restored=norm(await r.snapshot())===norm(r.before);assert(restored);fs.writeFileSync(path.join(dir,'restored.json'),JSON.stringify({restored,keys:r.before.length}));}finally{await r.mp.disconnect();}}
})().then(()=>process.exit(0)).catch(e=>{console.error(e.stack);process.exit(1);});
