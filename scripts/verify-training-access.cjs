const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/account/training/index.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));
function pending() {
  let resolve, reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
}
function failure(code) { return Object.assign(new Error('fixture ' + code), {code}); }
function course(releaseId = 'v1') {
  return {id:'course', releaseId, title:'课程' + releaseId, attachments:[], lessons:[
    {id:'lesson', title:'课时' + releaseId, versionId:releaseId, attachments:[], completed:false}
  ]};
}
function fixture(detail = true) {
  let page;
  const state = {token:'session',post:{postId:'post',orgId:'store',orgType:'store',roleCode:'sales',userId:'person'}};
  const seen = {toasts:[], documents:[], pauses:0};
  const api = {
    identity:()=>state.token + ':' + state.post.postId, course:async()=>course(),
    courses:async()=>[course()], materials:async()=>[{courseId:'course',versionId:'material',name:'资料'}],
    read:async()=>({completed:true}), beat:async()=>({completed:true}), url:t=>t.path
  };
  const modules = {'../../../api/training':api,'../../../api/http':{token:()=>state.token},
    '../../../api/auth':{currentApiPost:()=>state.post},'../../../utils/organization':{roles:{sales:'业务员',finance:'财务'}}};
  const wx = {showToast(o){seen.toasts.push(o.title);},redirectTo(){},createVideoContext:()=>({pause(){seen.pauses++;}}),
    openDocument(o){seen.documents.push(o.filePath);o.success();}};
  vm.runInNewContext(source,{Page:value=>page=value,wx,require:key=>modules[key],getApp:()=>({globalData:{windowInfo:{statusBarHeight:20}}}),
    setTimeout,clearTimeout,console});
  page.data = structuredClone(page.data);
  page.setData = (patch,after) => { Object.assign(page.data,patch); after?.(); };
  page.onLoad(detail ? {id:'course'} : {});
  page.onShow();
  return {page,api,state,wx,seen};
}

for (const code of [401,403,404,1103000002]) {
  test('learning detail clears protected lessons and playback after server denial ' + code, async () => {
    const {page,api} = fixture(); await tick();
    assert.equal(page.data.course.releaseId,'v1');
    page.playId='old-play'; Object.assign(page.data,{mediaUrl:'ticket',document:true,readAllowed:true});
    api.course=async()=>{throw failure(code);}; await page.refresh();
    assert.ok(page.data.error);
    assert.equal(page.data.course,null);
    assert.equal(page.data.activeLesson,null);
    assert.equal(page.data.mediaUrl,'');
    assert.equal(page.playId,''); assert.equal(page.data.readAllowed,false);
  });
}

test('catalog keeps authorized results on a network error and forgets both lists when access changes', async () => {
  const {page,api} = fixture(false); await tick();
  api.courses=async()=>{throw failure(503);}; await page.refresh();
  assert.equal(page.data.visibleCourses.length,1); assert.equal(page.data.visibleResources.length,1);
  api.materials=async()=>{throw failure(403);}; api.courses=async()=>[course()]; await page.refresh();
  for(const key of ['courses','resources','visibleCourses','visibleResources']) assert.equal(page.data[key].length,0,key);
  assert.equal(page.data.resumeCourse,null);
});

test('same post with a new role discards the old course immediately and ignores a late denial', async () => {
  const {page,api,state} = fixture(); await tick();
  const stale=pending(), fresh=pending();
  api.course=()=>stale.promise; const old=page.refresh();
  state.post={...state.post,roleCode:'finance'}; api.course=()=>fresh.promise; page.onShow();
  assert.equal(page.data.course,null);
  fresh.resolve(course('v2')); await tick(); stale.reject(failure(403)); await old;
  assert.equal(page.data.course.releaseId,'v2'); assert.equal(page.data.error,'');
  state.token=''; page.onShow(); assert.equal(page.data.course,null);
});

test('reading progress must be confirmed for the same released lesson; late receipt cannot mark its replacement', async () => {
  const {page,api,seen} = fixture(); await tick();
  page.playId='play'; Object.assign(page.data,{readAllowed:true,document:true,mediaUrl:'old'});
  api.read=async()=>({completed:false}); await page.confirmRead();
  assert.equal(page.data.activeLesson.completed,false); assert.equal(seen.toasts.length,0);
  const old=pending(); api.read=()=>old.promise; const saving=page.confirmRead();
  api.course=async()=>course('v2'); await page.refresh();
  assert.equal(page.data.mediaUrl,''); old.resolve({completed:true}); await saving;
  assert.equal(page.data.course.releaseId,'v2'); assert.equal(page.data.activeLesson.completed,false);
  assert.equal(seen.toasts.length,0);
});

test('a revoked course clears playing media on the next heartbeat, including outstanding file previews', async () => {
  const {page,api,wx,seen}=fixture(); await tick();
  page.playId='play'; page.data.mediaUrl='old-video';
  let download;
  wx.downloadFile=o=>{download=o;}; api.ticket=async()=>({path:'/access'});
  const reading=page.resource({currentTarget:{dataset:{course:'course',id:'material',ext:'pdf'}}});
  await tick(); assert.ok(download);
  api.beat=async()=>{throw failure(403);}; api.course=async()=>{throw failure(403);};
  await page.flush(); await tick();
  download.success({statusCode:200,tempFilePath:'/fixture.pdf'}); await reading;
  assert.equal(page.data.course,null); assert.equal(page.data.mediaUrl,''); assert.equal(seen.documents.length,0);
});
