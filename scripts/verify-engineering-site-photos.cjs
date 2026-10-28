// 现场拍照与多图留证：工程人员可在施工节点直接拍照或从相册一次多选，
// 现场记录与节点完成记录保存全部照片；历史单凭据记录按原样归一展示。
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const runtime=await require('./role-page-runtime.cjs').connect(),out=path.resolve('qa/engineering-site-photos-20261028');fs.mkdirSync(out,{recursive:true});
 const A=require('../miniprogram/utils/account'),E=require('../miniprogram/pages/build/model'),R=require('../miniprogram/utils/engineering-records');
 const scenes=JSON.parse(fs.readFileSync('qa/engineering-records-20261021/page-scenes.json','utf8'));const seed=scenes.find(s=>s.action==='site').before;for(const[k,v]of seed)wx.setStorageSync(k,v);
 const checks=[],pass=n=>{checks.push(n);console.log('PASS '+n);};
 const photos=[{tempFilePath:'wxfile://tmp/photo-a.jpg'},{tempFilePath:'wxfile://tmp/photo-b.jpg'}],mediaCalls=[];
 Object.assign(global.wx,{showActionSheet:o=>o.success({tapIndex:0}),chooseMedia:o=>{mediaCalls.push(o);o.success({tempFiles:photos.slice(0,o.count)});},saveFile:o=>o.success({savedFilePath:o.tempFilePath.replace('wxfile://tmp','saved')}),chooseMessageFile:o=>o.success({tempFiles:[{path:'wxfile://tmp/legacy.pdf',name:'legacy.pdf'}]}),previewImage(o){this.previewCalls=(this.previewCalls||0)+1;}});
 A.mutate(s=>A.loginDemo(s,'manager-li'));A.mutate(s=>A.switchIdentity(s,'quanzhou','engineer'));
 let p=E.load().projects.find(p=>p.recordFlow);assert(p,'快照应包含施工中工程');const id=p.id,node=p.plan.nodes.find(n=>n.status!=='已完成');
 wx.reLaunch({url:R.link(p,'node',{nodeId:node.id})});let page=getCurrentPages().at(-1);assert(!page.data.error,page.data.error);
 const tap=async selector=>{const el=await(await runtime.currentPage()).$(selector);assert(el,'缺少控件 '+selector);await el.tap();const current=getCurrentPages().at(-1);assert(!current.data.error,current.data.error);return current;};
 const raw=()=>wx.getStorageSync(E.KEY).projects.find(x=>x.id===id);
 await tap('.record-fields button[data-key="file"]');
 assert.equal(mediaCalls.length,1,'上传入口应直接调起拍照或相册');const opt=mediaCalls[0];
 assert(opt.sourceType.includes('camera')&&opt.sourceType.includes('album'),'拍照与相册均可作为来源');assert(opt.mediaType.includes('image'),'只选图片');
 assert.equal(opt.count,9,'施工节点现场凭据支持一次多选');assert.equal(page.data.form.files.length,2,'多图写入 form.files');assert.equal(page.data.form.file.name,'现场照片-1.jpg','form.file 保留第一张');assert.equal(page.data.fileNames.file,'已选2张照片');
 pass('现场凭据入口直接拍照或从相册一次多选，表单保留第一张兼容单凭据校验');
 page.setData({['form.note']:'梁板浇筑完成，现场拍照留证'});await tap('[data-action="site"]');
 let rec=raw().siteRecords.at(-1);assert.equal(rec.files.length,2,'现场记录保存全部照片');assert.equal(rec.file.name,'现场照片-1.jpg');assert(rec.files.every(f=>f.path&&f.path.startsWith('saved')),'照片持久化到本地文件');
 pass('现场记录保存多张照片，单凭据字段保留第一张');
 await tap('.record-fields button[data-key="file"]');
 page=getCurrentPages().at(-1);page.setData({['form.note']:'基础施工及现场验收完成'});await tap('[data-action="node"]');
 assert.equal(raw().plan.nodes[0].status,'已完成','节点顺序完成后进入下一节点');const done=raw().siteRecords.filter(r=>r.completion).at(-1);assert.equal(done.files.length,2,'节点完成记录保存多图');assert.equal(raw().inspections.filter(i=>i.nodeId===node.id).at(-1).file.name,'现场照片-1.jpg','验收凭据沿用第一张照片');
 pass('节点完成记录同样保存多图，验收凭据不丢现场照片');
 const state=wx.getStorageSync(E.KEY);state.projects.find(x=>x.id===id).siteRecords.find(r=>r.id===done.id).files=undefined;wx.setStorageSync(E.KEY,state);
 wx.reLaunch({url:R.link(R.get(id),'node',{nodeId:node.id})});page=getCurrentPages().at(-1);assert(!page.data.error,page.data.error);
 const shown=page.data.siteRecords.find(r=>r.id===done.id);assert.equal(shown.files.length,1,'历史单凭据记录归一为一图');assert.equal(shown.images.length,1,'缩略图取实际图片文件');
 assert(await(await runtime.currentPage()).$('.node-site-records image[data-file]'),'现场记录渲染可点缩略图');
 pass('历史单凭据记录按原样归一展示，现场记录渲染照片缩略图');
 wx.reLaunch({url:R.link(R.get(id),'prepare')});page=getCurrentPages().at(-1);await tap('.record-fields button[data-key="file"]');
 assert.equal(mediaCalls.at(-1).count,1,'合同等单凭据场景仍一次一张');
 pass('非现场凭据保持单文件选择口径');
 fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({result:'passed',checks,projectId:id,nodeId:node.id,evidence:'Node Page/WXML integration with chooseMedia upload flow'},null,2));console.log('COMPLETE',checks.length,'checks');
})().catch(e=>{console.error(e.stack);fs.writeFileSync(path.resolve('qa/engineering-site-photos-20261028/results.json'),JSON.stringify({result:'failed',error:e.message},null,2));process.exitCode=1;});
