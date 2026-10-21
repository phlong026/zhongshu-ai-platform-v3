const fs=require('fs'),path=require('path'),assert=require('assert/strict');
process.env.FRANCHISE_WS=process.env.FRANCHISE_WS||'ws://127.0.0.1:9450';process.env.FRANCHISE_REAL_EVENTS='1';process.env.FRANCHISE_NATIVE_OUT=path.resolve('qa/business-flow-priority-20261021/legacy-intake-native');
const trace=JSON.parse(fs.readFileSync('qa/business-flow-priority-20261021/final-trace.json','utf8')).steps,scene=trace.find(s=>s.area==='workflow'&&new URL(s.url,'http://local').searchParams.get('view')==='assign'),id=new URL(scene.url,'http://local').searchParams.get('id'),clone=x=>JSON.parse(JSON.stringify(x));
(async()=>{const r=await require('./franchise-native-runtime.cjs').connect('legacy-intake'),checks=[];
 const customer=async()=>Object.fromEntries(await r.snapshot())['zhongshu-business-v3'].customers.find(c=>c.id===id);
 try{
  for(const [name,intake]of [['旧版缺核对标记',{date:scene.form.dueDate,requestVersion:1}],['未核对受理',{date:scene.form.dueDate,requestVersion:1,checked:false}],['旧需求版本受理',{date:scene.form.dueDate,requestVersion:0,checked:true}]]){
   await r.open('/pages/account/index?view=policy','.account');const rows=clone(scene.before),c=rows.find(r=>r[0]==='zhongshu-business-v3')[1].customers.find(c=>c.id===id);c.intake=intake;await r.replace(rows);await r.open(scene.url,'.primary-action');
   assert((await r.data()).sections.some(s=>s.items.some(i=>i.key==='intakeChecked')),'重新核对控件');await r.mp.evaluate(f=>{const p=getCurrentPages().at(-1);p.setData({form:{...p.data.form,...f,intakeChecked:false}});},scene.form);
   await r.event('.primary-action','submit');assert((await r.data()).error.includes('核对'));assert(!(await customer()).assignment);await r.event({selector:'.check-row',dataset:{key:'intakeChecked'}},'toggle');await r.event('.primary-action','submit');await r.until(async()=>!!(await customer()).assignment,'旧受理补核对后指派');const after=await customer();assert.equal(after.intake.checked,true);assert.equal(after.intake.requestVersion,after.request.version);assert.equal(after.intake.checkedById,'hq-design-lead');assert.equal(after.assignment.designerId,'hq-design-dual');await r.until(async()=>(await r.data()).view==='formal-progress','指派后正式设计结果页');checks.push({name,result:'passed',via:'Element.tap'});console.log('PASS '+name);
  }
  assert.equal(r.errors.length,0);fs.writeFileSync(path.join(r.directory,'results.json'),JSON.stringify({result:'passed',checks,interactions:r.interactions,exceptions:r.errors},null,2));
 }finally{await r.restore();}
})().then(()=>process.exit(0)).catch(e=>{console.error(e.stack);process.exit(1);});
