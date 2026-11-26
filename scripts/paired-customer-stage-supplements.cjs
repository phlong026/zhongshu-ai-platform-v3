// Called by the backend CustomerWorkflowAssetPgIntegrationTest with a disposable PostgreSQL and asset store.
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
const readline = require('node:readline');
const fixture = JSON.parse(process.argv[2]);
const { connect, render } = require('./role-page-runtime.cjs');
const auth = require('../miniprogram/api/auth');
const http = require('../miniprogram/api/http');
const api = require('../miniprogram/pages/customer-flow/api');
const upload = require('../miniprogram/pages/customer-flow/upload');
const steps = require('../miniprogram/pages/customer-flow/model').STEPS.map(s=>s.code);
const drawer = path.resolve(__dirname,'../miniprogram/pages/customer-flow');
const stream = readline.createInterface({input:process.stdin});
const queue = [];
const flat = nodes => nodes.flatMap(n=>[n,...flat(n.children||[])]);
const wait = async predicate => {
  for(let i=0;i<900;i++) { if(predicate())return; await new Promise(r=>setTimeout(r,15)); }
  throw Error('Timed out waiting for the paired customer workflow');
};
stream.on('line',line=>{const next=queue.shift();if(!next)throw Error('Unexpected backend response');next(JSON.parse(line));});
function bridge(command) {
  return new Promise(resolve=>{queue.push(resolve);process.stdout.write(JSON.stringify(command)+'\n');});
}
function login(role) {
  const post=fixture[role];
  auth.saveLogin({accessToken:'fixture-'+role,userId:String(post.userId)});
  auth.savePosts([post]);auth.switchPost(String(post.postId));
}
function actor() {
  const post=String(http.currentPostId());
  const role=['sales','readonly','otherPost'].find(key=>String(fixture[key].postId)===post);
  assert.ok(role,'known fixture post');return role;
}
let component;
function mount(page) {
  const section=flat(render(path.join(drawer,'index.wxml'),page.data)).find(n=>n.tag==='step-supplements');
  assert.ok(section,'supplements are mounted for '+page.data.step.code);
  const view={...component.methods,data:{...structuredClone(component.data),customerId:section.attrs['customer-id'],stepCode:section.attrs['step-code']},
    setData(values){Object.assign(this.data,structuredClone(values));}};
  view.ready=component.lifetimes.attached.call(view);view.detach=()=>component.lifetimes.detached.call(view);
  return view;
}
(async()=>{
  const runtime=await connect({lifecycles:true});
  global.Component=value=>{component=value;};
  require(path.join(drawer,'supplements/index.js'));delete global.Component;
  const writes=[],downloads=[],files=new Map();let loseReceipt=true;
  const customer=String(fixture.customerId);
  let signedInProfile, customerArchive;
  wx.request = request => {
    const url=new URL(request.url);const endpoint=url.pathname.replace(/^\/admin-api/,'');
    if(endpoint==='/platform/posts/mine') {setImmediate(()=>request.success({statusCode:200,data:{code:0,data:[fixture[actor()]]}}));return {};}
    const ownProfile=request.method==='GET' && endpoint==='/platform/profile/mine';
    assert.ok(ownProfile || endpoint.startsWith('/franchise/customers/'),endpoint);
    if(request.method==='GET')for(const [name,value] of Object.entries(request.data||{}))url.searchParams.set(name,String(value));
    const role=actor(),cmd={actor:role,method:request.method,path:endpoint+url.search};
    if(request.method!=='GET')cmd.body=request.data||{};
    if(request.header['Idempotency-Key'])cmd.key=request.header['Idempotency-Key'];
    const creating=request.method==='POST'&&endpoint.endsWith('/supplements');
    if(creating)writes.push(structuredClone(cmd));
    bridge(cmd).then(body=>{
      if(role==='sales' && request.method==='GET' && body.code===0) {
        if(ownProfile) signedInProfile=body.data;
        if(endpoint==='/franchise/customers/'+customer) customerArchive=body.data.customer;
      }
      if(creating && loseReceipt && body.code===0) {loseReceipt=false;request.fail({errMsg:'fixture: response lost after commit'});}
      else request.success({statusCode:200,data:body});
    });
    return {};
  };
  wx.getFileSystemManager=()=>({statSync:file=>({size:files.get(file).length}),
    readFileSync:(file,encoding,offset,length)=>files.get(file).subarray(offset,offset+length).toString(encoding)});
  wx.downloadFile=options=>{
    const role=actor(),u=new URL(options.url),id=u.pathname.match(/\/assets\/(\d+)\/content$/)?.[1];
    assert.equal(options.header['X-Post-Id'],String(fixture[role].postId));assert.ok(id);
    let header=()=>{};
    bridge({actor:role,method:'GET',path:u.pathname+u.search}).then(body=>{
      if(body.code!==0){options.success({statusCode:403});return;}
      const bytes=Buffer.from(body.data.bytesBase64,'base64'),asset=fixture.assets.find(f=>String(f.assetId)===id);
      const hash=crypto.createHash('sha256').update(bytes).digest('hex');
      assert.equal(hash,asset.sha256);const local='/paired/'+id;files.set(local,bytes);
      header({header:{'content-type':body.data.contentType}});downloads.push({assetId:id,role,hash});
      options.success({statusCode:200,tempFilePath:local});
    });
    return {onHeadersReceived(fn){header=fn;}};
  };
  const opened=[];wx.showToast=()=>{};wx.openDocument=o=>opened.push(o.filePath);wx.previewImage=o=>opened.push(o.urls[0]);
  let picker;
  wx.chooseMedia=wx.chooseMessageFile=opts=>{picker=opts;};
  // The fixture has already accepted these exact bytes through the production upload service.
  // Return its receipt at the upload boundary, then use real component commands and authorized downloads.
  upload.run=async (job,alive)=>{assert.ok(alive());const asset=fixture.assets.find(f=>f.title===job.file.name);assert.ok(asset);return {assetId:String(asset.assetId),title:asset.title};};
  async function open(role,step) {
    login(role);
    await runtime.callWxMethod('reLaunch',{url:'/pages/customer-flow/index?customerId='+customer+'&step='+step});
    await wait(()=>getCurrentPages().at(-1)?.data.step?.code===step);
    return getCurrentPages().at(-1);
  }
  async function addFile(view,asset,kind) {
    view.chooseAttachment({currentTarget:{dataset:{kind}}});assert.ok(picker);
    await picker.success({tempFiles:[{name:asset.title,path:'/chosen/'+asset.assetId,tempFilePath:'/chosen/'+asset.assetId}]});
    assert.ok(view.data.files.some(f=>f.assetId===String(asset.assetId)));
  }
  const photo=fixture.assets.find(f=>String(f.contentType||f.mimeType||f.title).match(/image|\.png$|\.jpe?g$/i));
  const pdf=fixture.assets.find(f=>String(f.contentType||f.mimeType||f.title).match(/pdf/i));assert.ok(photo&&pdf);
  const page=await open('sales','INVITATION');
  const before={revision:page.data.workflow.revision,completedCount:page.data.workflow.completedCount,progressPercent:page.data.workflow.progressPercent};
  let view=mount(page);await view.ready;assert.ok(view.data.canWrite);
  view.inputNote({detail:{value:'  与客户沟通时补充图纸  '}});await addFile(view,pdf,'file');await view.saveSupplement();assert.ok(view.data.uncertain);
  await view.saveSupplement();assert.equal(writes.length,2);assert.equal(writes[0].key,writes[1].key);assert.deepEqual(writes[0].body,writes[1].body);
  assert.equal(view.data.rows.filter(r=>r.note==='与客户沟通时补充图纸').length,1);
  const first=view.data.rows.find(r=>r.note==='与客户沟通时补充图纸');assert.ok(first.actorName && first.createdText);
  await view.openAttachment({currentTarget:{dataset:{id:first.id,asset:String(pdf.assetId)}}});
  assert.equal(opened.at(-1),'/paired/'+pdf.assetId);view.detach();
  for(const code of steps.slice(1)) {
    page.selectStep({currentTarget:{dataset:{code}}});assert.equal(page.data.step.code,code);
    view=mount(page);await view.ready;assert.ok(view.data.canWrite,code);
    if(code==='INTRODUCTION') {
      await wait(()=>signedInProfile && customerArchive && page.data.form.fields.receptionist && page.data.form.fields.requirements);
      assert.equal(String(signedInProfile.id),String(fixture.sales.userId));
      assert.equal(String(customerArchive.id),customer);
      assert.equal(page.data.form.fields.receptionist,signedInProfile.nickname);
      assert.equal(page.data.form.fields.requirements,customerArchive.summary);
    }
    if(['CONTRACT','START'].includes(code)) {
      assert.equal(page.data.step.status,'COMPLETED',code);
      assert.ok(flat(render(path.join(drawer,'index.wxml'),page.data)).some(n=>n.attrs?.bindtap==='openSource'));
    }
    if(code==='START') {
      await addFile(view,photo,'photo');await view.saveSupplement();assert.equal(view.data.uncertain,false);
      const field=view.data.rows.find(r=>r.attachments.some(a=>a.assetId===String(photo.assetId)));assert.ok(field);
      await view.openAttachment({currentTarget:{dataset:{id:field.id,asset:String(photo.assetId)}}});
      assert.equal(opened.at(-1),'/paired/'+photo.assetId);
    }
    view.detach();
  }
  const latest=await api.get(customer);
  for(const [key,val] of Object.entries(before))assert.equal(latest[key],val,key);
  assert.equal(writes.length,3);
  const roPage=await open('readonly','INVITATION');view=mount(roPage);await view.ready;assert.equal(view.data.canWrite,false);
  assert.ok(view.data.rows.some(row=>row.id===first.id));view.inputNote({detail:{value:'只读岗位'}});await view.saveSupplement();assert.equal(writes.length,3);
  await view.openAttachment({currentTarget:{dataset:{id:first.id,asset:String(pdf.assetId)}}});assert.equal(downloads.at(-1).role,'readonly');view.detach();
  await assert.rejects(api.download(customer,'START',String(pdf.assetId)));
  login('otherPost');await assert.rejects(api.listSupplements(customer,'INVITATION',1));
  assert.deepEqual(downloads.map(d=>d.assetId),[String(pdf.assetId),String(photo.assetId),String(pdf.assetId)]);
  process.stdout.write(JSON.stringify({done:true,stages:steps.length,writes:writes.length,records:2,authorizedDownloads:downloads.length,progress:before})+'\n');
  stream.close();process.stdin.unref();
})().catch(error=>{console.error(error);process.exit(1);});
