const assert = require('node:assert/strict');
const test = require('node:test');
const cover = require('../miniprogram/pages/studio/publication-cover');
const pending = require('../miniprogram/pages/studio/publication-pending');
const flush = () => new Promise(resolve => setImmediate(resolve));

function fixture() {
  const calls = [], storage = new Map(), drafts = [];
  let live = true, picker;
  const page = { actorScope:'post-30',data:{view:'publish',workId:'80',title:'标题',publishText:'文案',tags:'',selectedAccount:0,
    publisherAccountId:'200',publisherAccountsLoaded:true,publisherAccounts:[{id:'200',platform:'douyin',enabled:true}],
    cover:'',coverAssetId:'',coverReady:false,coverUploading:false,coverLoading:false,coverError:''},
    setData(patch) {
      for (const [key,value] of Object.entries(patch)) {
        const match=key.match(/^(records\[(\d+)\]|detail)\.(.+)$/);
        if(match){const row=match[2]===undefined?this.data.detail:this.data.records[Number(match[2])];row[match[3]]=value;}
        else this.data[key]=value;
      }
    },persistEditor(){drafts.push(structuredClone(this.data));},go(){} };
  const env={post:{orgId:'20',postId:'30'},
    http:{token:()=>live?'token':'',currentUserId:()=> '10',tenantId:()=> '1',base:()=> 'https://api.example.com/admin-api',sessionGuard:()=>()=>live},
    studio:{scope:()=> 'post-30',publicationPlatforms:[{platform:'douyin'}]},
    wx:{chooseMedia:options=>{picker=options;},getImageInfo:options=>options.success({type:'jpeg'}),showToast(){},
      getStorageSync:key=>storage.get(key),setStorageSync:(key,value)=>storage.set(key,value),removeStorageSync:key=>storage.delete(key)},
    message:error=>error.message||error.errMsg,validate:()=>'',
    uploadContent:async(path,mime)=>{calls.push(['upload',path,mime]);return '301';},
    api:{assets:{downloadUrl:async assetId=>{calls.push(['preview',assetId]);return {downloadUrl:'https://cdn.example.com/'+assetId+'.jpg'};}},
      publications:{create:async body=>{calls.push(['publish',body]);return {id:'91'};}}}
  };
  return {page,env,calls,drafts,choose:path=>picker.success({tempFiles:[{tempFilePath:path}]}),cancel:()=>picker.fail({errMsg:'chooseMedia:fail cancel'}),revoke:()=>{live=false;}};
}

test('upload starts only after selection and stores the accepted asset ID in the publication payload',async()=>{
  const f=fixture();let accept;
  f.env.uploadContent=async(path,mime)=>{f.calls.push(['upload',path,mime]);return new Promise(resolve=>{accept=resolve;});};
  const flight=cover.upload(f.page,f.env);
  assert.equal(f.page.data.coverUploading,false);
  f.choose('wxfile://chosen.jpg');await flush();
  assert.equal(f.page.data.coverUploading,true);
  assert.equal(f.page.data.coverAssetId,'');
  await pending.submit(f.page,f.env);
  assert.equal(f.calls.some(row=>row[0]==='publish'),false,'an unfinished upload cannot be submitted');
  accept('301');await flight;
  assert.equal(f.page.data.coverAssetId,'301');
  assert.equal(f.page.data.coverReady,true);
  await pending.submit(f.page,f.env);
  assert.equal(f.calls.find(row=>row[0]==='publish')[1].coverAssetId,'301');
});

test('cancelling the picker leaves the saved cover and error state untouched',async()=>{
  const f=fixture();f.page.data.coverAssetId='300';f.page.data.cover='https://cdn.example.com/old.jpg';
  const flight=cover.upload(f.page,f.env);f.cancel();await flight;
  assert.equal(f.page.data.coverAssetId,'300');assert.equal(f.page.data.coverError,'');assert.equal(f.calls.length,0);
});

test('restoring a cover obtains a fresh authorized preview instead of reusing a stale local file',async()=>{
  const f=fixture();f.page.data.coverAssetId='301';f.page.data.cover='wxfile://expired.jpg';
  await cover.restore(f.page,f.env);
  assert.deepEqual(f.calls,[['preview','301']]);
  assert.equal(f.page.data.cover,'https://cdn.example.com/301.jpg');assert.equal(f.page.data.coverReady,true);
});

test('a private preview is downloaded with the shared authenticated downloader',async()=>{
  const f=fixture();f.env.api.assets.downloadUrl=async()=>({downloadUrl:'/admin-api/franchise/assets/301/content?token=grant'});
  f.env.downloadAsset=async options=>{assert.equal(options.http,f.env.http);assert.match(options.downloadUrl,/^\/admin-api/);return{tempFilePath:'wxfile://private.jpg'};};
  assert.equal(await cover.preview(f.env,'301'),'wxfile://private.jpg');
});

test('clearing a cover invalidates its delayed restore so it cannot reappear',async()=>{
  const f=fixture();let resolve;f.page.data.coverAssetId='301';f.env.api.assets.downloadUrl=()=>new Promise(done=>{resolve=done;});
  const flight=cover.restore(f.page,f.env);cover.clear(f.page);resolve({downloadUrl:'https://cdn.example.com/301.jpg'});await flight;
  assert.equal(f.page.data.coverAssetId,'');assert.equal(f.page.data.cover,'');assert.equal(f.page.data.coverReady,false);
});

test('a revoked session cannot apply a completed upload or write its draft',async()=>{
  const f=fixture();let resolve;f.env.uploadContent=()=>new Promise(done=>{resolve=done;});
  const flight=cover.upload(f.page,f.env);f.choose('wxfile://chosen.jpg');await flush();
  f.revoke();resolve('301');await flight;
  assert.equal(f.page.data.coverAssetId,'');assert.equal(f.drafts.length,0);
});

test('an upload for a previous work cannot overwrite the current editor',async()=>{
  const f=fixture();let resolve;f.env.uploadContent=()=>new Promise(done=>{resolve=done;});
  const flight=cover.upload(f.page,f.env);f.choose('wxfile://chosen.jpg');await flush();
  f.page.data.workId='81';f.page.data.coverAssetId='302';resolve('301');await flight;
  assert.equal(f.page.data.coverAssetId,'302');assert.equal(f.drafts.length,0);
});

test('published records and receipts use their stored cover asset rather than a fixed image',async()=>{
  const f=fixture();f.page.data.view='records';f.page.data.records=[{id:'91',coverAssetId:'301',cover:''},{id:'92',coverAssetId:'',cover:''}];
  await cover.hydrate(f.page,f.env);
  assert.equal(f.page.data.records[0].cover,'https://cdn.example.com/301.jpg');
  assert.equal(f.page.data.records[1].cover,'');
  f.page.data.view='receipt';f.page.data.detail={id:'91',coverAssetId:'301',cover:''};await cover.hydrate(f.page,f.env);
  assert.equal(f.page.data.detail.cover,'https://cdn.example.com/301.jpg');
});
