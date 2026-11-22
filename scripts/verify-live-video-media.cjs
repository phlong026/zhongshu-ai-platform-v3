const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
function load(modules, wx) {
  let definition;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/ai-video/index.js'), 'utf8'), {
    Page: value => { definition = value; }, require: id => {
      if(id.endsWith("creation-keyboard"))return require("../miniprogram/utils/creation-keyboard");
      if(id.endsWith("creation-text-resize"))return require("../miniprogram/utils/creation-text-resize");
      if (!(id in modules)) throw Error('Unexpected module ' + id);
      return modules[id];
    }, wx, getCurrentPages: () => [], console, Date, Promise
  });
  return { ...definition, actorScope: 'actor', data: structuredClone(definition.data),
    setData(patch) { Object.assign(this.data, patch); } };
}
test('selected frames are uploaded and included in the paid task; upload failure creates no task', async () => {
  const sent = [], uploaded = [];
  let fail = false;
  const p = load({
    '../../utils/creation': {}, '../../utils/studio': { scope: () => 'actor' },
    '../../utils/platform-content': { mapVideoParameters: () => ({}), mapReferences: () => [], degradeMessage: e => e.message },
    '../../api/http': { token: () => 'session', sessionGuard: () => () => true },
    '../../utils/content-media': { upload: async file => {
      uploaded.push(file); if (fail) throw Error('upload failed'); return '9007199254740993';
    } },
    '../../api/franchise/content': { assets:{downloadUrl:async()=>({downloadUrl:'https://signed.example/media'})},tasks: { create: async body => { sent.push(body); return { id: 'task' }; } } }
  }, { showToast() {}, navigateTo() {}, redirectTo() {} });
  Object.assign(p.data, { description: '庭院', firstFrame: '/tmp/first.jpg', lastFrame: '/tmp/last.jpg' });
  await p.generateOnline();
  assert.equal(uploaded.length, 2);
  assert.deepEqual(JSON.parse(JSON.stringify(sent[0].references)), [
    { role: 'first_frame', assetId: '9007199254740993' }, { role: 'last_frame', assetId: '9007199254740993' }
  ]);
  sent.length = 0; p.__videoSubmission = null; fail = true;p.setData({firstFrame:'/tmp/replacement.jpg',firstFrameAssetId:''});
  await p.generateOnline();
  assert.equal(sent.length, 0);
});
test('reference media keeps asset ids and type roles; stale uploads never submit', async () => {
  let alive = true, sent = [], resolveUpload;
  const p = load({
    '../../utils/creation': {}, '../../utils/studio': { scope: () => 'actor' },
    '../../utils/platform-content': { mapVideoParameters: () => ({}), mapReferences: () => [], degradeMessage: e => e.message },
    '../../api/http': { token: () => 'session', sessionGuard: () => () => alive },
    '../../utils/content-media': { upload: () => new Promise(resolve => { resolveUpload = resolve; }) },
    '../../api/franchise/content': { tasks: { create: async body => { sent.push(body); return { id: 'task' }; } } }
  }, { showToast() {}, navigateTo() {} });
  Object.assign(p.data, { description: '庭院', mode: 'reference', references: [{ type: 'video', path: '/tmp/r.mp4', name: '参考视频' }] });
  const pending = p.generateOnline();
  await Promise.resolve(); alive = false; resolveUpload('a'); await pending;
  assert.equal(sent.length, 0);
});

function uploader(ticketUrl, response = { statusCode: 200, data: '' }) {
  const requests = [], completed = [], tickets = [];
  let upload;
  const http = { base: () => 'https://platform.example/admin-api', token: () => 'private-token',
    currentPostId: () => 'post', tenantId: () => 'tenant' };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/content-media.js'), 'utf8'), {
    module: { set exports(value) { upload = value.upload; } },
    require: id => id === '../api/http' ? http : id === '../api/finance' ? require('../miniprogram/api/finance') : { assets: {
      uploadTicket: async body => { tickets.push(body); return { assetId: '9007199254740993', uploadUrl: ticketUrl }; },
      complete: async id => { completed.push(id); return { status: 'ACCEPTED' }; }
    } },
    wx: { getFileSystemManager: () => ({ readFile: o => o.success({ data: new ArrayBuffer(4) }) }),
      request: o => { requests.push(o); o.success(response); } }, ArrayBuffer, Promise
  });
  return { upload, requests, completed, tickets };
}

test('COS raw PUT sends bytes without platform credentials; B proxy keeps its single admin-api prefix', async () => {
  const cos = uploader('https://bucket.example/object?signed=true');
  assert.equal(await cos.upload('/tmp/a.png', () => true), '9007199254740993');
  assert.equal(cos.requests[0].method, 'PUT');
  assert.equal(cos.requests[0].data.byteLength, 4);
  assert.deepEqual(Object.keys(cos.requests[0].header), ['content-type']);
  assert.equal(cos.tickets[0].sha256, require('node:crypto').createHash('sha256').update(Buffer.alloc(4)).digest('hex'));
  assert.equal(cos.tickets[0].sizeBytes, 4);
  const proxy = uploader('/admin-api/franchise/assets/9007199254740993/content?token=ticket', { statusCode: 200, data: { code: 0 } });
  await proxy.upload('/tmp/a.jpg', () => true);
  assert.equal(proxy.requests[0].url, 'https://platform.example/admin-api/franchise/assets/9007199254740993/content?token=ticket');
  assert.equal(proxy.requests[0].header.Authorization, 'Bearer private-token');
  assert.equal(proxy.requests[0].header['X-Post-Id'], 'post');
  assert.equal(proxy.requests[0].header['tenant-id'], 'tenant');
});

test('task response loss retries the same payload and idempotency key without uploading again', async () => {
  const calls = [], uploads = [];
  const p = load({
    '../../utils/creation': {}, '../../utils/studio': { scope: () => 'actor' },
    '../../utils/platform-content': { mapVideoParameters: () => ({}), degradeMessage: e => e.message },
    '../../api/http': { token: () => 'session', sessionGuard: () => () => true },
    '../../utils/content-media': { upload: async file => { uploads.push(file); return 'asset'; } },
    '../../api/franchise/content': { assets:{downloadUrl:async()=>({downloadUrl:'https://signed.example/media'})},tasks: { create: async (body, key) => {
      calls.push({ body, key }); if (calls.length === 1) throw Error('timeout'); return { id: 'task' };
    } } }
  }, { showToast() {}, navigateTo() {} });
  Object.assign(p.data, { description: '庭院', firstFrame: '/tmp/first.jpg' });
  await p.generateOnline(); await p.generateOnline();
  assert.equal(calls.length, 2);
  assert.equal(calls[0].key, calls[1].key);
  assert.equal(calls[0].body, calls[1].body);
  assert.equal(uploads.length, 1);
});

test('upload HTTP/business failures and unsupported files never complete an asset', async () => {
  for (const [url, response] of [
    ['https://bucket.example/object', { statusCode: 403 }],
    ['/admin-api/franchise/assets/9007199254740993/content?token=t', { statusCode: 200, data: { code: 401 } }],
    ['local://unsupported', { statusCode: 200 }]
  ]) {
    const u = uploader(url, response);
    await assert.rejects(u.upload('/tmp/a.png', () => true));
    assert.equal(u.completed.length, 0);
  }
  const u = uploader('https://bucket.example/object');
  await assert.rejects(u.upload('/tmp/a.wav', () => true), /暂仅支持/);
  await assert.rejects(u.upload('/tmp/a.png', () => false), /工作身份/);
  assert.equal(u.requests.length, 0);
});

test('video picker indices resolve to selected parameter labels', () => {
  let content;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/platform-content.js'), 'utf8'), {
    require: () => ({}), module: { set exports(value) { content = value; } }
  });
  const value = content.mapVideoParameters([
    { key: 'resolution', options: ['768P', '2K'], value: 1 },
    { key: 'duration', options: ['8 秒', '10 秒'], value: 1 },
    { key: 'ratio', options: ['9:16', '16:9'], value: 1 }
  ]);
  assert.deepEqual(JSON.parse(JSON.stringify(value)), { resolution: '2K', duration: 10, ratio: '16:9' });
});

function libraryVideo({download=async()=>({downloadUrl:'https://cos.example/media'}),persist=async path=>path}={}) {
  let valid=true;const navigations=[],uploads=[],tickets=[],tasks=[],toasts=[];
  const wx={showToast:o=>toasts.push(o.title),navigateTo:o=>navigations.push(o),redirectTo() {}};
  const page=load({
    '../../utils/creation':{persistMedia:persist},'../../utils/studio':{scope:()=> 'actor',allowed:()=>true},
    '../../utils/platform-content':require('../miniprogram/utils/platform-content'),
    '../../api/http':{token:()=>true,sessionGuard:()=>()=>valid},
    '../../utils/content-media':{upload:async path=>{uploads.push(path);return 'uploaded';}},
    '../../api/franchise/content':{assets:{downloadUrl:async id=>{tickets.push(id);return download(id);}},tasks:{create:async body=>{tasks.push(JSON.parse(JSON.stringify(body)));return{id:'task'};}}}
  },wx);
  page.data.description='庭院镜头';
  return{page,wx,navigations,uploads,tickets,tasks,toasts,invalidate:()=>valid=false};
}

test('video frame picker opens the real library and submits its large asset ID without reupload',async()=>{
  const h=libraryVideo(),id='9007199254740993';h.page.openLibrary('firstFrame');
  assert.equal(h.navigations[0].url,'/pages/studio/index?view=library&select=IMAGE');
  h.navigations[0].events.materialSelected({assetId:id,type:'image',mimeType:'image/png',name:'公司外观图',visibility:'COMPANY',canManage:false,path:'https://cos.example/frame.png'});
  assert.equal(h.page.data.firstFrameAssetId,id);assert.equal(h.page.data.firstFrame,'https://cos.example/frame.png');
  assert.equal(h.page.data.assets,undefined);
  await h.page.generateOnline();
  assert.deepEqual(h.uploads,[]);assert.deepEqual(h.tickets,[id]);
  assert.deepEqual(h.tasks[0].references,[{role:'first_frame',assetId:id}]);
});

test('library references keep media roles and de-duplicate by stable ID, including files without preview paths',async()=>{
  const h=libraryVideo();h.page.data.mode='reference';
  for(const [id,type,mimeType]of [['11','image','image/jpeg'],['12','video','video/mp4'],['13','audio','audio/mpeg'],['12','video','video/mp4']]){
    h.page.openLibraryButton();h.navigations.at(-1).events.materialSelected({assetId:id,type,mimeType,name:type,visibility:'COMPANY',canManage:false,path:''});
  }
  assert.equal(h.page.data.references.length,3);
  await h.page.generateOnline();
  assert.deepEqual(h.uploads,[]);assert.deepEqual(h.tickets,['11','12','13']);
  assert.deepEqual(h.tasks[0].references,[{role:'reference_image',assetId:'11',name:'image'},{role:'reference_video',assetId:'12',name:'video'},{role:'reference_audio',assetId:'13',name:'audio'}]);
});

test('revoked library access blocks task creation and preserves the selected reference for correction',async()=>{
  const h=libraryVideo({download:async()=>{throw{code:403,message:'素材已删除或无权访问'};}});
  h.page.data.mode='reference';h.page.data.references=[{id:'material-1',assetId:'1',type:'video',mimeType:'video/mp4',name:'共享视频',path:''}];
  await h.page.generateOnline();assert.equal(h.tasks.length,0);assert.equal(h.uploads.length,0);
  assert.equal(h.page.data.references[0].assetId,'1');assert.match(h.toasts.at(-1),/删除|无权/);
});

test('picker rejects a wrong media type and discards selection after session change or unload',async()=>{
  const h=libraryVideo();h.page.openLibrary('lastFrame');
  const receive=h.navigations[0].events.materialSelected;
  receive({assetId:'2',type:'video',mimeType:'video/mp4',name:'视频'});
  assert.equal(h.page.data.lastFrameAssetId,'');assert.match(h.toasts.at(-1),/图片/);
  h.invalidate();receive({assetId:'3',type:'image',mimeType:'image/png',path:'https://cos.example/p.png'});
  assert.equal(h.page.data.lastFrameAssetId,'');
  const gone=libraryVideo();gone.page.openLibrary('firstFrame');gone.page.onUnload();
  gone.navigations[0].events.materialSelected({assetId:'3',type:'image',mimeType:'image/png',path:'https://cos.example/p.png'});
  assert.equal(gone.page.data.firstFrameAssetId,'');
});

test('removing or replacing a library frame clears its asset ID so generation uses the chosen phone image',async()=>{
  const h=libraryVideo({persist:async()=>'/saved/new.jpg'});
  Object.assign(h.page.data,{firstFrame:'https://cos.example/old.png',firstFrameAssetId:'old'});
  h.wx.showActionSheet=o=>o.success({tapIndex:2});h.page.chooseFrame({currentTarget:{dataset:{target:'firstFrame'}}});
  assert.equal(h.page.data.firstFrameAssetId,'');assert.equal(h.page.data.firstFrame,'');
  Object.assign(h.page.data,{firstFrame:'https://cos.example/old.png',firstFrameAssetId:'old'});
  h.wx.showActionSheet=o=>o.success({tapIndex:0});h.wx.chooseMedia=o=>o.success({tempFiles:[{tempFilePath:'/tmp/new.jpg'}]});
  h.page.chooseFrame({currentTarget:{dataset:{target:'firstFrame'}}});await new Promise(resolve=>setImmediate(resolve));
  assert.equal(h.page.data.firstFrameAssetId,'');await h.page.generateOnline();
  assert.deepEqual(h.uploads,['/saved/new.jpg']);assert.deepEqual(h.tickets,['uploaded']);
});

function mediaSource({ticket=async()=>({downloadUrl:'/admin-api/franchise/assets/901/content?token=t'}),localExists=false}={}){
  const mod={exports:{}},calls=[];let valid=true;
  const http={base:()=> 'https://platform.example/admin-api'};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/utils/content-media'),'utf8'),{module:mod,
    wx:{getFileSystemManager:()=>({accessSync:path=>{if(!localExists)throw Error('file missing');}})},
    require:id=>{
      if(id==='../api/http')return http;
      if(id==='../api/franchise/content')return {assets:{downloadUrl:async id=>{calls.push(['ticket',id]);return ticket(id);}}};
      if(id==='./b-asset-download')return {downloadTicketFile:async options=>{calls.push(['download',options.downloadUrl]);return{tempFilePath:'/renewed/901.png'};}};
      throw Error(id);
    }
  });return{source:(id,options)=>mod.exports.source(id,()=>valid,options),calls,invalidate:()=>valid=false};
}

test('asset previews renew missing local files through the authorized B downloader and keep accessible local files',async()=>{
  const missing=mediaSource();assert.equal(await missing.source('901',{type:'image',localPath:'/expired/901.png'}),'/renewed/901.png');
  assert.deepEqual(missing.calls,[['ticket','901'],['download','/admin-api/franchise/assets/901/content?token=t']]);
  const live=mediaSource({localExists:true});assert.equal(await live.source('901',{type:'image',localPath:'/saved/901.png'}),'/saved/901.png');
  assert.deepEqual(live.calls,[['ticket','901']]);
  assert.equal(await missing.source('902',{type:'video'}),'');assert.equal(missing.calls.filter(c=>c[0]==='download').length,1);
});

test('signed preview addresses avoid platform byte headers and denied access cannot fall back to a cached private file',async()=>{
  const signed=mediaSource({ticket:async()=>({downloadUrl:'https://cos.example/frame.png?signed=t'})});
  assert.equal(await signed.source('901',{type:'image'}),'https://cos.example/frame.png?signed=t');assert.equal(signed.calls.length,1);
  const denied=mediaSource({localExists:true,ticket:async()=>{throw {code:403,message:'素材已移除'};}});
  await assert.rejects(denied.source('901',{type:'image',localPath:'/saved/private.png'}),e=>e.code===403);
});
