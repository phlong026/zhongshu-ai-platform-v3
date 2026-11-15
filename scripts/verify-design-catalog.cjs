const test=require('node:test');
const assert=require('node:assert/strict');
const PF=require('../miniprogram/utils/platform-design');
const attach=require('../miniprogram/pages/workflow/explore/platform-bridge');
const flush=async()=>{await new Promise(r=>setImmediate(r));await new Promise(r=>setImmediate(r));};
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
function harness(cases={}){
 let generation=1;const calls=[];
 const http={sessionGeneration:()=>generation,token:()=> 'test-session',clearSession:()=>{generation++;}};
 const page={__post:{postId:'post-1'},__postKey:'post-1',__http:http,data:{platCases:[],platFilter:{},platformMode:true},setData(patch){for(const [key,value]of Object.entries(patch)){const m=key.match(/^platCases\[(\d+)\]$/);if(m)this.data.platCases[Number(m[1])]=value;else this.data[key]=value;}}};
 attach(page,{PF,http,api:{cases:{styles:async()=>[],page:async()=>({list:[],nextCursor:null}),downloadUrl:async(c,a)=>{calls.push([c,a]);return {downloadUrl:'https://images.example/'+a};},...cases},assets:{downloadUrl:()=>{throw Error('Public case must not use private/generated asset channel');}}}});
 return {page,calls,changeSession(){generation++;}};
}
test('catalog initial rejection ends loading and exposes an error despite parallel style load',async()=>{
 const {page}=harness({page:async()=>{throw {code:503,message:'案例暂不可用'};}});page.platLoadCases(true);await flush();assert.equal(page.data.platLoading,false);assert.match(page.data.platformErr,/案例暂不可用/);
});
test('latest filter wins when older first page finishes last',async()=>{
 const old=deferred(),fresh=deferred();let count=0;const {page}=harness({page:()=>++count===1?old.promise:fresh.promise});
 page.platLoadCases(true);page.platLoadCases(true);fresh.resolve({list:[{caseId:'22',title:'新筛选'}]});await flush();old.resolve({list:[{caseId:'11',title:'旧筛选'}]});await flush();assert.deepEqual(page.data.platCases.map(x=>x.id),['22']);
});
test('first-page response cannot fill a changed account or post',async()=>{
 const request=deferred();const h=harness({page:()=>request.promise});h.page.platLoadCases(true);h.changeSession();request.resolve({list:[{caseId:'11',title:'旧账号'}]});await flush();assert.equal(h.page.data.platCases.length,0);
});
test('catalog cover uses public case membership channel and retains exact long IDs',async()=>{
 const h=harness({page:async()=>({list:[{caseId:'2112123885294055425',title:'真实案例',coverAssetId:'2112124039677997058'}]})});h.page.platLoadCases(true);await flush();assert.deepEqual(h.calls,[['2112123885294055425','2112124039677997058']]);assert.equal(h.page.data.platCases[0].coverUrl,'https://images.example/2112124039677997058');
});
test('direct case ID loads full metadata and images through public case endpoint',async()=>{
 const h=harness({detail:async id=>({caseId:id,title:'详情名称',coverAssetId:'901',floorPlanAssetIds:['902']})});
 h.page.platLoadCase('19');await flush();assert.equal(h.page.data.platCase.title,'详情名称');assert.deepEqual(h.calls,[['19','901'],['19','902']]);assert.deepEqual(h.page.data.platCaseImages,['https://images.example/901','https://images.example/902']);
});
test('late detail images cannot refill an unloaded page',async()=>{
 const image=deferred();const h=harness({detail:async id=>({caseId:id,coverAssetId:'901'}),downloadUrl:()=>image.promise});h.page.platLoadCase('19');await flush();h.page.platOnUnload();image.resolve({downloadUrl:'https://images.example/old'});await flush();assert.deepEqual(h.page.data.platCaseImages,[]);
});
test('cover loader bounds work, reports per-image errors, and keeps rows',async()=>{
 let active=0,max=0;const events=[];const rows=Array.from({length:7},(_,i)=>PF.caseRow({caseId:String(i+1),coverAssetId:String(i+101),title:'case '+i}));
 const result=await PF.loadCaseCovers(rows,async(c,a)=>{active++;max=Math.max(max,active);await flush();active--;if(a==='103')throw {code:503};return {downloadUrl:'https://images.example/'+a};},()=>true,(row,i)=>events.push(i));
 assert.equal(max,3);assert.equal(result.length,7);assert.equal(events.length,7);assert.equal(result[2].coverError,true);assert.equal(result[2].title,'case 2');assert.equal(result[0].coverUrl,'https://images.example/101');
});
test('cover loader stops queued work when page ownership changes',async()=>{
 let alive=true,count=0;const rows=Array.from({length:8},(_,i)=>PF.caseRow({caseId:String(i),coverAssetId:String(i+1)}));
 await PF.loadCaseCovers(rows,async()=>{count++;alive=false;return {downloadUrl:'https://images.example/cover'};},()=>alive);assert.ok(count<=3);
});
test('cover 401 is not converted into a missing-image success',async()=>{
 await assert.rejects(PF.loadCaseCovers([PF.caseRow({caseId:'1',coverAssetId:'2'})],async()=>{throw {code:401};},()=>true),e=>e.code===401);
});
test('public catalog style options include all and picker selection sends matching API code',async()=>{
 const queries=[];const h=harness({styles:async()=>[{code:'CN',label:'中式'},{code:'MODERN',label:'现代'}],page:async q=>{queries.push(q);return {list:[]};}});h.page.platLoadCases(true);await flush();assert.equal(h.page.data.platStyleOptions[0].code,'');h.page.platFilterPick({currentTarget:{dataset:{key:'styleCode'}},detail:{value:2}});await flush();assert.equal(queries.at(-1).styleCode,'MODERN');assert.equal(h.page.data.platStyleLabel,'现代');
});
test('pagination and case detail do not strand the existing catalog cover work',async()=>{
 const pending=[];let pageCount=0;const h=harness({page:async()=>({list:[{caseId:String(++pageCount),coverAssetId:String(100+pageCount)}],nextCursor:'next'}),detail:async id=>({caseId:id,title:'详情'}),downloadUrl:(id,asset)=>{const p=deferred();pending.push({id,asset,...p});return p.promise;}});
 h.page.platLoadCases(true);await flush();h.page.platLoadCases(false);await flush();h.page.platLoadCase('1');await flush();for(const p of pending)p.resolve({downloadUrl:'https://images.example/'+p.asset});await flush();assert.deepEqual(h.page.data.platCases.map(row=>({id:row.id,loading:row.coverLoading,url:row.coverUrl})),[{id:'1',loading:false,url:'https://images.example/101'},{id:'2',loading:false,url:'https://images.example/102'}]);
});
test('direct case detail can be selected as facade reference without list cache or event ID',async()=>{
 const h=harness({detail:async id=>({caseId:id,title:'详情'})});h.page.platLoadCase('19');await flush();h.page.platUseCase({currentTarget:{dataset:{kind:'facade'}}});assert.equal(h.page.data['form.referenceCaseId'],'19');assert.equal(h.page.data.view,'ai-facade');
});
test('opening detail while a next-page request is pending still settles list loading',async()=>{
 const next=deferred();let count=0;const h=harness({page:async()=>++count===1?{list:[{caseId:'1'}],nextCursor:'next'}:next.promise,detail:async id=>({caseId:id,title:'详情'})});h.page.platLoadCases(true);await flush();h.page.platLoadCases(false);h.page.platLoadCase('1');next.resolve({list:[{caseId:'2'}],nextCursor:null});await flush();assert.equal(h.page.data.platLoading,false);assert.deepEqual(h.page.data.platCases.map(r=>r.id),['1','2']);
});
test('case detail parameters keep published dimensions and version instead of blank metadata',()=>{
 const row=PF.caseRow({caseId:'19',caseVersionId:'29',title:'已发布户型',parameters:{faceWidth:17.8,depth:13,floorCount:1,buildingArea:231.4,styleCode:'NEW_CHINESE'}});assert.equal(row.faceWidth,17.8);assert.equal(row.depth,13);assert.equal(row.floorCount,1);assert.equal(row.buildingArea,231.4);assert.equal(row.versionId,'29');
});
test('dimension search retains width and depth API filters without turning them into keyword',async()=>{
 const queries=[];const h=harness({page:async q=>{queries.push(q);return {list:[]};}});h.page.platDimensionInput({currentTarget:{dataset:{key:'faceWidth'}},detail:{value:'17.8'}});h.page.platDimensionInput({currentTarget:{dataset:{key:'depth'}},detail:{value:'13'}});h.page.platSearch();await flush();assert.equal(queries[0].faceWidth,17.8);assert.equal(queries[0].depth,13);assert.equal(queries[0].keyword,undefined);
});
