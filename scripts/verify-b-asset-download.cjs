const {test} = require('node:test');
const assert = require('node:assert/strict');
const {downloadTicketFile} = require('../miniprogram/utils/b-asset-download');

const http = {base:()=> 'https://platform.example/admin-api', token:()=> 'session', currentPostId:()=> 'post', tenantId:()=> 1};
const ticket = '/admin-api/franchise/assets/123/content?token=opaque';
function wxDownload({statusCode=200, body='%PDF-1.4', contentType='application/pdf', beforeSuccess}={}) {
  let request;
  global.wx = {
    getFileSystemManager:()=>({
      statSync:()=>({size:Buffer.byteLength(body)}),
      readFileSync:(_path,_encoding,_position,length)=>body.slice(0,length)
    }),
    downloadFile(options) {
      request = options;
      queueMicrotask(()=>{
        if (beforeSuccess) beforeSuccess();
        options.success({statusCode,tempFilePath:'wxfile://download.pdf'});
      });
      return {onHeadersReceived(listener){listener({header:{'Content-Type':contentType}});}};
    }
  };
  return ()=>request;
}

test('B proxy resolves against configured origin and carries current identity', async()=>{
  const request=wxDownload();
  const result=await downloadTicketFile({http,downloadUrl:ticket});
  assert.equal(request().url,'https://platform.example'+ticket);
  assert.deepEqual(request().header,{Authorization:'Bearer session','X-Post-Id':'post','tenant-id':1});
  assert.equal(result.tempFilePath,'wxfile://download.pdf');
  assert.equal(result.contentType,'application/pdf');
});
test('absolute B proxy has the same authenticated behavior', async()=>{
  const request=wxDownload();
  await downloadTicketFile({http,downloadUrl:'https://platform.example'+ticket});
  assert.equal(request().header.Authorization,'Bearer session');
});
test('external signed HTTPS download receives no platform credentials', async()=>{
  const request=wxDownload();
  await downloadTicketFile({http,downloadUrl:'https://bucket.cos.example/file.pdf?sign=opaque'});
  assert.deepEqual(request().header,{});
});
test('arbitrary relative, legacy private and non-HTTPS routes are rejected before download',async()=>{
  const request=wxDownload();
  for(const downloadUrl of ['/admin-api/system/user','/app-api/design/v1/assets/1/content','https://old.example/platform/dev/design/assets/1','http://bucket.example/file.pdf','file:///tmp/a']) {
    await assert.rejects(downloadTicketFile({http,downloadUrl}),/下载通道/);
  }
  assert.equal(request(),undefined);
});
test('HTTP errors are not presented as files',async()=>{
  wxDownload({statusCode:403});
  await assert.rejects(downloadTicketFile({http,downloadUrl:ticket}),/403/);
});
test('HTTP 200 JSON business rejection is not opened as a document',async()=>{
  wxDownload({body:'{"code":403,"msg":"无权查看资料"}',contentType:'application/json; charset=utf-8'});
  await assert.rejects(downloadTicketFile({http,downloadUrl:ticket}),/无权查看资料/);
});
test('missing content-type still detects a JSON response',async()=>{
  wxDownload({body:'{"code":401,"msg":"登录已过期"}',contentType:''});
  await assert.rejects(downloadTicketFile({http,downloadUrl:ticket}),/登录已过期/);
});
test('session changes reject before request and after file arrival',async()=>{
  let alive=false;
  const request=wxDownload();
  await assert.rejects(downloadTicketFile({http,downloadUrl:ticket,alive:()=>alive}),/身份已切换/);
  assert.equal(request(),undefined);
  alive=true;
  wxDownload({beforeSuccess:()=>{alive=false;}});
  await assert.rejects(downloadTicketFile({http,downloadUrl:ticket,alive:()=>alive}),/身份已切换/);
});
