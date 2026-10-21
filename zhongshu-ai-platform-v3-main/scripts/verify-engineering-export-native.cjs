const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const directory=path.resolve('qa/engineering-finance-audit-20261021/native-export-pair');
process.env.FRANCHISE_NATIVE_OUT=directory;process.env.FRANCHISE_WS=process.env.FRANCHISE_WS||'ws://127.0.0.1:9795';
(async()=>{
 const r=await require('./franchise-native-runtime.cjs').connect('engineering-export-pair'),f=JSON.parse(fs.readFileSync('qa/engineering-finance-audit-20261021/engineering-native-fixtures.json')),fingerprint=require('./engineering-audit-fingerprint.cjs')(),files=[],mocked=new Set();
 const mock=async(method,result)=>{await r.mp.mockWxMethod(method,result);mocked.add(method);},unmock=async method=>{if(mocked.has(method)){await r.mp.restoreWxMethod(method);mocked.delete(method);}};
 try{
  assert(f.exportPair,'Prepare the explicit two-project fixture first');await r.replace(f.exportPair.rows);
  for(const [index,id]of f.exportPair.projects.entries()){
   await r.open('/pages/build/index?view=schedule&id='+encodeURIComponent(id));assert(!(await r.data()).error,(await r.data()).error);
   await mock('showActionSheet',{tapIndex:0});await mock('showModal',{confirm:true});r.mp.pageMap.clear();const buttons=await(await r.mp.currentPage()).$$('.plan-actions button');assert(buttons[0]);await buttons[0].tap();
   await r.until(async()=>!!(await r.data()).exportedPath,'实际生成项目计划文件');await unmock('showActionSheet');await unmock('showModal');
   const p=await r.data(),csv=await r.mp.evaluate(file=>wx.getFileSystemManager().readFileSync(file,'utf8'),p.exportedPath);
   assert(p.exportedPath.includes(encodeURIComponent(id)),'文件路径须包含原项目ID');assert(csv.includes(p.p.name));assert(csv.includes('施工节点'));
   fs.writeFileSync(path.join(directory,'project-'+(index?'B':'A')+'.csv'),csv);files.push({projectId:id,name:p.p.name,version:p.p.plan.version,path:p.exportedPath,csv});console.log('PASS native export '+p.p.name);
  }
  assert.equal(files[0].version,files[1].version);assert.notEqual(files[0].path,files[1].path);
  assert.equal(await r.mp.evaluate(file=>wx.getFileSystemManager().readFileSync(file,'utf8'),files[0].path),files[0].csv,'第二项目导出后第一项目原文件仍保持原内容');
  assert.equal(r.errors.length,0,JSON.stringify(r.errors));await r.capture('two-project-exports');
  fs.writeFileSync(path.join(directory,'results.json'),JSON.stringify({result:'passed',scope:'同一微信模拟器两个明确模拟项目的相同V1计划，真实Element.tap导出；系统弹窗选择查看，未分享文件',files,fingerprint,exceptions:r.errors},null,2));
 }catch(error){fs.writeFileSync(path.join(directory,'results.json'),JSON.stringify({result:'failed',error:error.stack,files,fingerprint},null,2));throw error;}
 finally{try{for(const method of [...mocked])await unmock(method);}finally{await r.restore();}}
})().then(()=>process.exit(0)).catch(e=>{console.error(e.stack);process.exit(1);});
