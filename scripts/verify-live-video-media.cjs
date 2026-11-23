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

function uploader(ticketUrl, response = { statusCode: 200, data: '' }, options = {}) {
  const requests = [], completed = [], tickets = [];
  let upload;
  const http = { base: () => 'https://platform.example/admin-api', token: () => 'private-token',
    currentPostId: () => 'post', tenantId: () => 'tenant' };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/content-media.js'), 'utf8'), {
    module: { set exports(value) { upload = value.upload; } },
    require: id => id === '../api/http' ? http : id === '../api/finance' ? require('../miniprogram/api/finance') : id === './media-formats' ? require('../miniprogram/utils/media-formats') : { assets: {
      uploadTicket: async body => { tickets.push(body); return { assetId: '9007199254740993', uploadUrl: ticketUrl }; },
      complete: async id => { completed.push(id); return options.complete ? options.complete(completed.length) : { status: 'ACCEPTED' }; }
    } },
    wx: { getFileSystemManager: () => ({ readFile: o => o.success({ data: options.bytes || new ArrayBuffer(4) }) }),
      request: o => { requests.push(o); o.success(response); } }, ArrayBuffer, Promise, setTimeout: callback => callback()
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
  }, { showToast() {}, navigateTo() {}, showModal:options=>options.success({confirm:true}) });
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
  await assert.rejects(u.upload('/tmp/a.zip', () => true), /无法识别/);
  await assert.rejects(u.upload('/tmp/a.png', () => false), /工作身份/);
  assert.equal(u.requests.length, 0);
});

test('common phone media retain their actual input format for server normalization', async () => {
  for (const [name, type, mimeType] of [
    ['IMG_1234.MOV','video','video/quicktime'], ['clip.mkv','video','video/x-matroska'],
    ['camera.3gp','video','video/3gpp'], ['clip.webm','video','video/webm'],
    ['recording.M4A','audio','audio/mp4'], ['voice.aac','audio','audio/aac'],
    ['voice.WAV','audio','audio/wav'], ['voice.amr','audio','audio/amr'],
    ['voice.flac','audio','audio/flac'], ['voice.opus','audio','audio/ogg'],
    ['IMG_1234.HEIC','image','image/heic'], ['photo.heif','image','image/heif'],
    ['photo.webp','image','image/webp'], ['photo.avif','image','image/avif'],
    ['photo.gif','image','image/gif'], ['photo.tiff','image','image/tiff']
  ]) {
    const u = uploader('https://bucket.example/object');
    await u.upload('/saved/opaque-file', () => true, {name,type});
    assert.equal(u.tickets[0].mimeType, mimeType, name);
    assert.equal(u.requests[0].header['content-type'], mimeType, name);
    assert.equal(u.completed.length, 1);
  }
});

test('extensionless WeChat temporary files are detected from bytes and wrong media roles are rejected', async () => {
  const formats = require('../miniprogram/utils/media-formats');
  const ftyp = brand => Buffer.concat([Buffer.from([0,0,0,24]), Buffer.from('ftyp'+brand), Buffer.alloc(12)]);
  assert.equal(formats.mimeType('/saved/tmp', ftyp('heic'), {type:'image'}), 'image/heic');
  assert.equal(formats.mimeType('/saved/tmp', ftyp('qt  '), {type:'video'}), 'video/quicktime');
  assert.equal(formats.mimeType('/saved/tmp', ftyp('M4A '), {type:'audio'}), 'audio/mp4');
  assert.equal(formats.mimeType('/saved/tmp.mp4', Buffer.from('RIFF1234WAVE'), {type:'audio', name:'voice.wav'}), 'audio/wav');
  assert.equal(formats.mimeType('/saved/tmp', Buffer.from('RIFF1234WEBP'), {type:'image'}), 'image/webp');
  const u = uploader('https://bucket.example/object', undefined, {bytes:ftyp('heic')});
  await assert.rejects(u.upload('/saved/opaque', () => true, {type:'video'}), /文件类型不符/);
  assert.equal(u.tickets.length, 0);
  assert.equal(u.requests.length, 0);
});

test('normalization retries the same accepted upload after response loss without reuploading or claiming a false completion', async () => {
  const stages = [];
  const u = uploader('https://bucket.example/object', undefined, {complete:count => {
    if (count === 1) throw {code:-1, message:'timeout'};
    return {status:count === 2 ? 'VALIDATING' : 'ACCEPTED'};
  }});
  assert.equal(await u.upload('/tmp/sample.mov', () => true, {type:'video',onStage:stage=>stages.push(stage)}), '9007199254740993');
  assert.equal(u.requests.length, 1);
  assert.equal(u.tickets.length, 1);
  assert.deepEqual(u.completed, ['9007199254740993','9007199254740993','9007199254740993']);
  assert.deepEqual(stages, ['reading','uploading','processing','complete']);
  const rejected = uploader('https://bucket.example/object', undefined, {complete:()=>({status:'REJECTED',message:'文件已损坏'})});
  const rejectedStages = [];
  await assert.rejects(rejected.upload('/tmp/sample.mp4',()=>true,{onStage:stage=>rejectedStages.push(stage)}), /文件已损坏/);
  assert.equal(rejectedStages.includes('complete'),false);
});

test('phone audio selection accepts recorder formats and carries original metadata across a renamed saved path', async () => {
  const h=libraryVideo({persist:async()=>'/saved/opaque'});
  let picker;
  h.wx.showActionSheet=o=>o.success({tapIndex:2});
  h.wx.chooseMessageFile=o=>{picker=o;};
  h.page.uploadReferences();
  assert.equal(picker.extension, undefined);
  await picker.success({tempFiles:[{path:'/tmp/voice',name:'华为录音.M4A',mimeType:'audio/mp4'}]});
  assert.equal(h.page.data.references[0].name,'华为录音.M4A');
  assert.equal(h.page.data.references[0].mimeType,'audio/mp4');
  assert.equal(h.page.data.references[0].path,'/saved/opaque');
  h.invalidate();
  await picker.success({tempFiles:[{path:'/tmp/old',name:'不应串入.wav'}]});
  assert.equal(h.page.data.references.length,1);
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

test('asset previews use accepted canonical bytes even when an undecodable original file still exists locally',async()=>{
  const missing=mediaSource();assert.equal(await missing.source('901',{type:'image',localPath:'/expired/901.png'}),'/renewed/901.png');
  assert.deepEqual(missing.calls,[['ticket','901'],['download','/admin-api/franchise/assets/901/content?token=t']]);
  const live=mediaSource({localExists:true});assert.equal(await live.source('901',{type:'image',localPath:'/saved/opaque-heic'}),'/renewed/901.png');
  assert.deepEqual(live.calls,[['ticket','901'],['download','/admin-api/franchise/assets/901/content?token=t']]);
  assert.equal(await missing.source('902',{type:'video'}),'');assert.equal(missing.calls.filter(c=>c[0]==='download').length,1);
});

test('signed preview addresses avoid platform byte headers and denied access cannot fall back to a cached private file',async()=>{
  const signed=mediaSource({ticket:async()=>({downloadUrl:'https://cos.example/frame.png?signed=t'})});
  assert.equal(await signed.source('901',{type:'image'}),'https://cos.example/frame.png?signed=t');assert.equal(signed.calls.length,1);
  const denied=mediaSource({localExists:true,ticket:async()=>{throw {code:403,message:'素材已移除'};}});
  await assert.rejects(denied.source('901',{type:'image',localPath:'/saved/private.png'}),e=>e.code===403);
});

function referencePreviewPage(media, wx) {
  const page = load({
    '../../utils/creation': {},
    '../../utils/studio': { scope: () => 'actor', allowed: () => true },
    '../../utils/platform-content': require('../miniprogram/utils/platform-content'),
    '../../api/http': { token: () => 'session', sessionGuard: () => () => true },
    '../../api/franchise/content': {},
    '../../utils/content-media': media,
    '../../utils/audio-preview': require('../miniprogram/utils/audio-preview')
  }, wx);
  page.data.mode = 'reference';
  return page;
}

const referenceEvent = id => ({ currentTarget: { dataset: { id } } });

test('reference rows count each media type independently in selection and submission order', () => {
  const page = referencePreviewPage({}, {});
  page.addReferences([
    { id: 'a', type: 'image', path: '/phone/kitchen.jpg' },
    { id: 'b', type: 'video', path: '/phone/motion.mp4' },
    { id: 'c', type: 'image', assetId: '901' },
    { id: 'd', type: 'audio', path: '/phone/voice.mp3' },
    { id: 'e', type: 'video', path: '/phone/detail.mp4' }
  ]);
  assert.deepEqual(Array.from(page.data.references, item => item.referenceLabel), ['图1', '视频1', '图2', '音频1', '视频2']);
  page.restoreMediaPreviews = () => {};
  page.removeReference(referenceEvent('a'));
  assert.deepEqual(Array.from(page.data.references, item => [item.id, item.referenceLabel]), [
    ['b', '视频1'], ['c', '图1'], ['d', '音频1'], ['e', '视频2']
  ]);
  page.applyVideoForm({
    mode: 'reference',
    parameters: { resolution: '768P', duration: 8, ratio: '9:16' },
    references: [
      { role: 'reference_audio', assetId: '901' },
      { role: 'reference_video', assetId: '902' },
      { role: 'reference_audio', assetId: '903' }
    ]
  });
  assert.deepEqual(Array.from(page.data.references, item => item.referenceLabel), ['音频1', '视频1', '音频2']);
  assert.ok(page.videoForm().references.every(item => !('referenceLabel' in item)));
});

test('images open immediately and library video opens inline using the normalized authorized file', async () => {
  const displayed = [], requested = [];
  const page = referencePreviewPage({ async source(id, alive, options) {
    assert.ok(alive());
    requested.push([id, options.type, options.playback]);
    return '/normalized/motion.mp4';
  } }, {
    previewImage: ({ current }) => displayed.push(['image', current]),
    previewMedia: ({ sources }) => displayed.push([sources[0].type, sources[0].url])
  });
  page.addReferences([
    { id: 'photo', type: 'image', path: '/phone/photo.png' },
    { id: 'video', type: 'video', assetId: '902' }
  ]);
  await page.previewReference(referenceEvent('photo'));
  await page.previewReference(referenceEvent('video'));
  assert.deepEqual(requested, [['902', 'video', true]]);
  assert.deepEqual(displayed, [['image', '/phone/photo.png']]);
  assert.equal(page.data.referenceVideoId, 'video');
  assert.equal(page.data.referenceVideoUrl, '/normalized/motion.mp4');
  await page.previewReference(referenceEvent('video'));
  assert.equal(page.data.referenceVideoUrl, '');
});

test('phone and library audio can play and pause, then stop when the selected row is removed', async () => {
  for (const item of [
    { id: 'voice', type: 'audio', path: '/phone/narration.m4a' },
    { id: 'voice', type: 'audio', assetId: '903', path: '' }
  ]) {
    const controls = [], events = {}, tickets = [];
    const wx = {
      createInnerAudioContext() {
        return {
          onCanplay: fn => events.canPlay = fn,
          onPlay: fn => events.play = fn,
          onPause: fn => events.pause = fn,
          onEnded() {}, onTimeUpdate() {}, onError() {},
          play() { controls.push(['play', this.src]); events.play(); },
          pause() { controls.push(['pause']); events.pause(); },
          destroy() { controls.push(['destroy']); }
        };
      }
    };
    const normalized = '/normalized/narration.mp3';
    const page = referencePreviewPage({
      async source(id, alive, options) {
        assert.ok(alive());
        tickets.push([id, options.type, options.playback]);
        return normalized;
      }
    }, wx);
    page.addReferences([item]);
    await page.previewReference(referenceEvent('voice'));
    assert.equal(page.data.previewAudioPlaying, true);
    await page.previewReference(referenceEvent('voice'));
    assert.equal(page.data.previewAudioPlaying, false);
    page.removeReference(referenceEvent('voice'));
    assert.deepEqual(controls, [['play', item.path || normalized], ['pause'], ['destroy']]);
    assert.deepEqual(tickets, item.assetId ? [['903', 'audio', true]] : []);
    assert.equal(page.data.previewAudioId, '');
  }
});

test('changing page while loading a preview does not open the player late', async () => {
  let resolveFile;
  const displayed = [];
  const page = referencePreviewPage({
    source: () => new Promise(resolve => { resolveFile = resolve; })
  }, { previewMedia: args => displayed.push(args) });
  page.addReferences([{ id: 'video', type: 'video', assetId: '902' }]);
  const pending = page.previewReference(referenceEvent('video'));
  page.onHide();
  resolveFile('/normalized/motion.mp4');
  await pending;
  assert.equal(displayed.length, 0);
  assert.equal(page.data.referencePreviewLoadingId, '');
  assert.equal(page.data.referenceVideoUrl, '');
});

test('saved audio and video previews open fresh signed media without replacing the default restore behavior', async () => {
  const signed = mediaSource({ ticket: async id => ({ downloadUrl: 'https://cos.example/' + id + '?signed=t' }) });
  assert.equal(await signed.source('902', { type: 'video' }), '');
  assert.equal(await signed.source('902', { type: 'video', playback: true }), 'https://cos.example/902?signed=t');
  assert.equal(await signed.source('903', { type: 'audio', playback: true }), 'https://cos.example/903?signed=t');
  assert.equal(signed.calls.filter(([kind]) => kind === 'download').length, 0);
  const proxy = mediaSource();
  await proxy.source('903', { type: 'audio', playback: true });
  assert.deepEqual(proxy.calls.map(([kind]) => kind), ['ticket', 'download']);
});
