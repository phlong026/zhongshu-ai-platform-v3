const fs=require('fs'),assert=require('assert/strict');
(async()=>{const r=await require('./finance-fullflow-runtime.cjs')();try{const {m,x}=r,out='docs/finance-device-tools-20261117/',home='/pages/admin/finance-desk/index',book=home+'?view=book&bookScope='+encodeURIComponent(x.mobileBook);
 await r.auth('finance');await r.nav(book);
 if(!x.mobilePdfSupplement){
  await r.tap('.book-transaction[data-key="RECEIPT:'+x.mobileReceipt+'"]');await r.click('补充说明或凭据');await r.input('reason','界面验收：无扩展名临时PDF按原文件名上传');
  const bytes=fs.readFileSync(r.root+'mobile-test-voucher.pdf');
  const path=await m.evaluate(b64=>{const p=wx.env.USER_DATA_PATH+'/finance-pdf-opaque';wx.getFileSystemManager().writeFileSync(p,b64,'base64');return p;},bytes.toString('base64'));
  await m.mockWxMethod('showActionSheet',{tapIndex:1});await m.mockWxMethod('chooseMessageFile',{tempFiles:[{path,name:'验收凭证.pdf',size:bytes.length,type:'file'}]});
  try{await r.click('选择补充凭据');}finally{await m.restoreWxMethod('chooseMessageFile');await m.restoreWxMethod('showActionSheet');}
  assert.equal((await r.data()).wbForm.voucherName,'验收凭证.pdf');await m.screenshot({path:out+'08-pdf-supplement.png'});await r.click('保存登记');await r.until(async()=>!(await r.data()).wbForm,'PDF supplement saved');x.mobilePdfSupplement=true;r.save();
 }
 await r.nav(book);await r.tap('.book-transaction[data-key="RECEIPT:'+x.mobileReceipt+'"]');const entry=await m.evaluate(()=>getCurrentPages().at(-1).data.bookEntryDetail);assert.ok(entry.voucher_file_id);
 const ticket=await r.api('POST','/franchise/assets/'+entry.voucher_file_id+'/download-tickets',{});const actor=r.actors.finance;
 const url=ticket.downloadUrl.startsWith('/')?'http://127.0.0.1:18091'+ticket.downloadUrl:ticket.downloadUrl;
 const response=await fetch(url,{headers:{Authorization:'Bearer '+actor.token,'tenant-id':'1','X-Post-Id':String(actor.postId)}});assert.equal(response.status,200);assert.match(response.headers.get('content-type'),/application\/pdf/);const b=Buffer.from(await response.arrayBuffer());assert.equal(b.subarray(0,5).toString(),'%PDF-');
 const report=JSON.parse(fs.readFileSync(out+'result.json','utf8'));report.pdf={passed:true,contentType:response.headers.get('content-type'),bytes:b.length,originalFilename:'验收凭证.pdf',temporaryPathHasExtension:false};report.checks.push({name:'临时文件无扩展名的PDF补充上传，经真实票据、上传、完成、下载接口校验PDF字节和格式',passed:true});fs.writeFileSync(out+'result.json',JSON.stringify(report,null,2));
 await m.screenshot({path:out+'09-pdf-linked.png'});await r.nav(home);await m.screenshot({path:out+'07-home-final.png'});console.log('PASS extensionless PDF real upload/download and original record association');
 }finally{r.close();}})().then(()=>process.exit(0),e=>{console.error(e.stack);process.exit(1)});
