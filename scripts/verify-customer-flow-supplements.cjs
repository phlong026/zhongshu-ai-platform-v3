const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const model = require('../miniprogram/pages/customer-flow/model');
const { render } = require('./role-page-runtime.cjs');
const pageRoot = path.join(__dirname, '../miniprogram/pages/customer-flow');
const copy = value => JSON.parse(JSON.stringify(value));
const tick = () => new Promise(resolve => setImmediate(resolve));
const flush = async () => { for (let i = 0; i < 6; i++) await tick(); };
const later = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const CUSTOMER = '9007199254740993123', ASSET = '9007199254740993124';
const record = (step, values = {}) => ({ id: '9007199254740993125', customerId: CUSTOMER, stepCode: step,
  note: '签约后补照片', attachments: [], actorName: '李工', actorUserId: '9007199254740993126', createdAt: '2026-11-25T17:04:00Z', ...values });
function harness(step = 'CONTRACT') {
  const h = { calls: [], write: true, rows: [], post: '9', uploads: [], previews: [] };
  let definition, seq = 0;
  const api = {
    async listSupplements(id,code,page) { h.calls.push(['list',id,code,page]); return h.list ? h.list(id,code,page) : { list: h.rows, total: h.rows.length, canWrite: h.write }; },
    async addSupplement(id,code,body,key) { h.calls.push(['add',id,code,copy(body),key]); if (h.add) return h.add(id,code,body,key); const r=record(code,body); h.rows=[r,...h.rows]; return r; },
    async download(id,code,asset) { h.calls.push(['download',id,code,asset]); return { downloadUrl: '/original' }; }
  };
  const wx = { chooseMedia: options => {h.picker=options;}, chooseMessageFile: options => {h.picker=options;},
    showToast: () => {}, previewImage: options => h.previews.push(options), openDocument: options => h.previews.push(options) };
  const modules = {
    '../api': api, '../model': model, '../../../utils/api-scope': () => h.post ? 'orgA/post'+h.post : '',
    '../../../api/http': { token: () => h.post ? 'session' : '', currentPostId: () => h.post },
    '../../../api/auth': { currentApiPost: () => h.post ? { postId: h.post, orgId:'1', userId:'2' } : null },
    '../../../utils/platform-crm': { commandKey: () => 'supplement-'+ ++seq },
    '../upload': { run: async (job,alive) => {h.uploads.push(job); if(h.upload) return h.upload(job,alive); return {assetId:ASSET,title:job.file.name || '现场照片'};} },
    '../../../utils/b-asset-download': { downloadTicketFile: async opts => h.download ? h.download(opts) : {tempFilePath:'/local-file',contentType:'application/pdf'} }
  };
  vm.runInNewContext(fs.readFileSync(path.join(pageRoot,'supplements/index.js'),'utf8'), {
    Component: value => {definition=value;}, require: id => {assert.ok(id in modules,id);return modules[id];}, wx,console
  });
  h.mount = code => {
    const c={...definition.methods,data:{...copy(definition.data),customerId:CUSTOMER,stepCode:code},
      setData(values){Object.assign(this.data,copy(values));}};
    c.show=()=>definition.pageLifetimes.show.call(c); c.hide=()=>definition.pageLifetimes.hide.call(c);
    c.detach=()=>definition.lifetimes.detached.call(c);
    c.switchStep=code=>{c.data.stepCode=code;return definition.observers['customerId, stepCode'].call(c);};
    c.start=()=>definition.lifetimes.attached.call(c);
    return c;
  };
  h.c=h.mount(step); h.input=value=>h.c.inputNote({detail:{value}});
  h.call=name=>h.calls.filter(r=>r[0]===name);
  return h;
}

test('八个阶段各自显示一处选填区，合同、工程与快速预算原入口仍在', () => {
  const file=path.join(pageRoot,'index.wxml');
  const flatten = nodes => nodes.flatMap(n=>[n,...flatten(n.children||[])]);
  const cfg=JSON.parse(fs.readFileSync(path.join(pageRoot,'index.json'),'utf8'));
  assert.equal(cfg.usingComponents['step-supplements'],'./supplements/index');
  for (const s of model.STEPS) {
    const step={code:s.code,name:s.name,fields:{},completionErrors:[],canWrite:false,canReadEngineering:false};
    const nodes=flatten(render(file,{workflow:{customerId:CUSTOMER,steps:[step]},step,form:{attachments:[],fields:{}},fields:[],facts:[]}));
    const areas=nodes.filter(n=>n.tag==='step-supplements');
    assert.equal(areas.length,1,s.code);assert.equal(areas[0].attrs['step-code'],s.code);assert.equal(areas[0].attrs['customer-id'],CUSTOMER);
    if(['CONTRACT','START'].includes(s.code))assert.ok(nodes.some(n=>n.attrs?.bindtap==='openSource'));
    if(s.code==='QUOTATION')assert.ok(nodes.some(n=>n.attrs?.bindtap==='openBudget'));
  }
});

test('完成后的阶段可单独补文字或附件，当前岗和北京时间可读，不请求办理与完成', async () => {
  const h=harness('START'),c=h.c;await c.start();
  h.input('  完工后补记  ');await c.saveSupplement();
  assert.equal(h.call('add').length,1);
  assert.deepEqual(h.call('add')[0].slice(1,4),[CUSTOMER,'START',{note:'完工后补记',attachments:[]}]);
  assert.equal(c.data.rows[0].actorName,'李工');assert.equal(c.data.rows[0].createdText,'2026-11-26 01:04');
  c.chooseAttachment({currentTarget:{dataset:{kind:'photo'}}});assert.deepEqual(Array.from(h.picker.sourceType),['album','camera']);
  await h.picker.success({tempFiles:[{name:'核对照片.jpg',tempFilePath:'/p.jpg'}]});await flush();await c.saveSupplement();
  assert.deepEqual(h.call('add')[1][3],{note:'',attachments:[{assetId:ASSET,title:'核对照片.jpg'}]});
  c.chooseAttachment({currentTarget:{dataset:{kind:'file'}}});assert.ok(h.picker.extension.includes('pdf'));
  assert.ok(!h.calls.some(row=>['complete','save','reopen'].includes(row[0])));
});

test('附件核对失败后沿用同一上传，未知保存回执换页仍按原键正文重试', async () => {
  const h=harness(),c=h.c;await c.start();let fail=true;
  h.upload=async job=>{if(fail){fail=false;throw Error('网络中断');}return {assetId:ASSET,title:job.file.name};};
  c.chooseAttachment({currentTarget:{dataset:{kind:'file'}}});
  await h.picker.success({tempFiles:[{name:'凭据.pdf',path:'/file.pdf'}]});await flush();
  assert.ok(c.data.uploadError);await c.retryUpload();assert.equal(h.uploads[0],h.uploads[1]);
  h.input('合同后补');h.add=async()=>{throw {code:-1,message:'回执丢失'};};
  await c.saveSupplement();assert.ok(c.data.uncertain);const old=h.call('add')[0];
  c.detach();const resumed=h.mount('CONTRACT');await resumed.start();assert.ok(resumed.data.uncertain);
  resumed.inputNote({detail:{value:'继续改写'}});h.add=async()=>record('CONTRACT',old[3]);
  await resumed.saveSupplement();const again=h.call('add')[1];
  assert.equal(again[4],old[4]);assert.deepEqual(again[3],old[3]);assert.equal(resumed.data.uncertain,false);
});

test('分页按编号去重，未知经办与时间保留，原件按当前客户和步骤重新授权', async () => {
  const h=harness('MEASUREMENT'),c=h.c;
  const first=record('MEASUREMENT',{attachments:[{assetId:ASSET,title:'测量原件.pdf'}]});
  const old=record('MEASUREMENT',{id:'27',actorName:'',actorUserId:null,createdAt:null});
  h.list=async (_id,_code,p)=>({list:p===1?[first]:[first,old],total:2,canWrite:false});
  await c.start();assert.equal(c.data.canWrite,false);await c.moreSupplements();assert.equal(c.data.rows.length,2);
  assert.equal(c.data.rows[1].actorName,'经办人未留存');assert.equal(c.data.rows[1].createdText,'时间未留存');
  const flatten=nodes=>nodes.flatMap(n=>[n,...flatten(n.children||[])]);
  const shown=flatten(render(path.join(pageRoot,'supplements/index.wxml'),c.data));
  assert.ok(shown.some(n=>n.text==='李工'));assert.ok(shown.some(n=>n.text==='2026-11-26 01:04'));
  assert.ok(shown.some(n=>n.attrs?.bindtap==='openAttachment' && n.attrs['data-asset']===ASSET));
  assert.ok(!shown.some(n=>n.attrs?.bindtap==='saveSupplement'));
  await c.openAttachment({currentTarget:{dataset:{id:first.id,asset:ASSET}}});
  assert.deepEqual(h.call('download')[0].slice(1),[CUSTOMER,'MEASUREMENT',ASSET]);
  assert.equal(h.previews[0].filePath,'/local-file');h.input('越权尝试');await c.saveSupplement();assert.equal(h.call('add').length,0);
});

test('切步、切岗和撤权隔离旧读取、已发保存回执和原件预览', async () => {
  const h=harness('HOUSE_SELECTION'),c=h.c;const held=later();
  h.list=(_id,code)=>code==='HOUSE_SELECTION'?held.promise:Promise.resolve({list:[],total:0,canWrite:true});
  const first=c.start();await c.switchStep('SITE_VISIT');held.resolve({list:[record('HOUSE_SELECTION')],total:1,canWrite:true});await first;
  assert.equal(c.data.rows.length,0);h.input('现场');const write=later();h.add=()=>write.promise;
  const saving=c.saveSupplement();await flush();c.hide();h.post='99';
  h.list=async()=>({list:[],total:0,canWrite:false});await c.show();write.resolve(record('SITE_VISIT'));await saving;
  assert.equal(c.data.canWrite,false);assert.equal(c.data.rows.length,0);assert.equal(c.data.note,'');
  h.list=async()=>({list:[record('SITE_VISIT',{attachments:[{assetId:ASSET,title:'原件.pdf'}]})],total:1,canWrite:true});await c.show();
  const transfer=later();h.download=()=>transfer.promise;const opening=c.openAttachment({currentTarget:{dataset:{id:c.data.rows[0].id,asset:ASSET}}});await flush();
  h.list=async()=>{throw {code:403,message:'权限已撤回'};};await c.show();transfer.resolve({tempFilePath:'/old',contentType:'image/png'});await opening;
  assert.equal(h.previews.length,0);assert.equal(c.data.rows.length,0);assert.equal(c.data.canWrite,false);
});

test('在途登记返回原步骤时清除已入账草稿，其余步骤的文字保留', async()=>{
  const h=harness('INVITATION'),c=h.c,held=later();
  h.list=async()=>({list:[],total:0,canWrite:true});
  await c.start();h.input('邀约阶段补记');h.add=()=>held.promise;
  const saving=c.saveSupplement();await c.switchStep('INTRODUCTION');h.input('店面阶段未保存');
  held.resolve(record('INVITATION'));await saving;
  assert.equal(c.data.note,'店面阶段未保存');
  await c.switchStep('INVITATION');assert.equal(c.data.note,'');await c.saveSupplement();assert.equal(h.call('add').length,1);
  await c.switchStep('INTRODUCTION');assert.equal(c.data.note,'店面阶段未保存');
});

test('读取原件或保存时撤权会清空旧内容，并可从当前步骤重新核对授权', async()=>{
  const h=harness(),c=h.c;
  h.rows=[record('CONTRACT',{attachments:[{assetId:ASSET,title:'原件.pdf'}]})];
  await c.start();
  h.download=async()=>{throw {code:403,message:'该资料权限已收回'};};
  await c.openAttachment({currentTarget:{dataset:{id:c.data.rows[0].id,asset:ASSET}}});
  assert.match(c.data.loadError,/权限已收回/);assert.equal(c.data.rows.length,0);
  await c.refresh();assert.equal(c.data.rows.length,1);
  h.add=async()=>{throw {code:403,message:'工作岗位已调整'};};
  h.input('试记');await c.saveSupplement();assert.match(c.data.loadError,/工作岗位已调整/);assert.equal(c.data.canWrite,false);
  h.write=false;await c.refresh();assert.equal(c.data.rows.length,1);assert.equal(c.data.note,'');
});
