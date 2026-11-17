const a=require('miniprogram-automator'),assert=require('assert/strict'),fs=require('fs');
(async()=>{const m=await a.connect({wsEndpoint:'ws://127.0.0.1:9973'});try{
 const p=await m.currentPage();let c=await p.$('#finance-month');
 const state=()=>m.evaluate(()=>{const p=getCurrentPages().slice(-1)[0],c=p.selectComponent('#finance-month');return {period:p.data.deskHome.period,selected:p.data.selectedMonth,visible:c.data.visible,year:c.data.year,draft:c.data.draft,tasks:p.data.deskHome.taskCount};});
 if((await state()).visible)await(await c.$('.month-cancel')).tap();
 await m.evaluate(()=>{wx.pageScrollTo({scrollTop:0,duration:0})});await p.waitFor(250);
 if((await state()).period!=='2026-11'){await(await c.$('.month-trigger')).tap();await p.waitFor(180);await(await c.$$('.month-cell'))[10].tap();await p.waitFor(180);await(await c.$('.month-confirm')).tap();await p.waitFor(800);c=await p.$('#finance-month');}const initial=await state();assert.equal(initial.period,'2026-11');
 const open=async()=>{c=await p.$('#finance-month');await(await c.$('.month-trigger')).tap();await p.waitFor(250);};
 const pick=async i=>{const cells=await c.$$('.month-cell');await cells[i-1].tap();await p.waitFor(160);};
 await open();await pick(10);assert.equal((await state()).period,'2026-11');await(await c.$('.month-cancel')).tap();assert.equal((await state()).period,'2026-11');
 await open();assert.equal((await state()).draft,'2026-11');await pick(10);await(await c.$('.month-confirm')).tap();await p.waitFor(800);assert.equal((await state()).period,'2026-10');assert.equal((await state()).tasks,initial.tasks);
 await open();const arrows=await c.$$('.month-year-arrow');await arrows[1].tap();await p.waitFor(160);await pick(12);await(await c.$('.month-confirm')).tap();await p.waitFor(800);assert.equal((await state()).period,'2027-12');
 await open();await(await c.$$('.month-year-arrow'))[0].tap();await p.waitFor(160);await pick(11);await(await c.$('.month-confirm')).tap();await p.waitFor(800);assert.equal((await state()).period,'2026-11');
 await m.screenshot({path:'docs/finance-live-20261117/04-finance-month-updated.png'});await open();await m.screenshot({path:'docs/finance-live-20261117/05-month-panel-updated.png'});await(await c.$('.month-cancel')).tap();
 fs.writeFileSync('docs/finance-live-20261117/month-native-result.json',JSON.stringify({passed:true,checks:['native button opens sheet','draft does not reload','cancel preserves period','reopen resets draft','confirm reloads selected month','year navigation','task count preserved','restored 2026-11']},null,2));console.log('8 native month checks passed');
 }finally{m.disconnect();}})().catch(e=>{console.error(e.stack);process.exit(1)});
