const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'miniprogram/pages/growth/index.js'), 'utf8');
let definition;
let captureResponse;
let captureError;
let customerReadError;
let captureImpl;
let customerReadImpl;
let summaryImpl=async()=>({perPublication:[]});
let pendingImpl=async()=>[], linkImpl=async()=>({}), invalidateImpl=async()=>({});
let sessionGeneration = 1;
let currentPost = { postId: 'post-sales-a', orgType: 'store', roleCode: 'sales' };
const navigations = [];
const customerReads = [];
const captureKeys = [];
const toasts = [];

const crm = {
  leads: {
    capture: async (data, key) => {
      captureKeys.push(key);
      if (captureImpl) return captureImpl(data, key);
      if (captureError) throw captureError;
      return captureResponse;
    },
    pending: () => pendingImpl(),
    link: (...args) => linkImpl(...args),
    invalidate: (...args) => invalidateImpl(...args)
  },
  customers: {
    get: async id => {
      customerReads.push(String(id));
      if (customerReadImpl) return customerReadImpl(id);
      if (customerReadError) throw customerReadError;
      return { id };
    }
  }
};

function requirePage(name) {
  if (name === '../../utils/organization') return { has: () => true };
  if (name === '../../utils/growth') return { load: () => ({ contents: [], leads: [] }) };
  if (name === '../../utils/platform-content') return { degradeMessage: error => error.message || String(error) };
  if (name === '../../api/franchise/content') return { growth: { summary: () => summaryImpl() } };
  if (name === '../../api/franchise/crm') return crm;
  if (name === '../../api/http') return {
    token: () => 'test-token', sessionGeneration: () => sessionGeneration,
    requestGuard: () => {
      const generation=sessionGeneration, postId=currentPost.postId;
      return () => generation===sessionGeneration&&postId===currentPost.postId;
    }
  };
  if (name === '../../utils/view-container') return { makeFormHandlers: () => ({}) };
  if (name === '../../api/auth') return { currentApiPost: () => currentPost };
  throw new Error('Unexpected module: ' + name);
}

vm.runInNewContext(source, {
  Page: value => { definition = value; },
  require: requirePage,
  wx: {
    navigateTo: options => navigations.push(options.url),
    redirectTo() {},
    showToast: options => toasts.push(options.title),
    switchTab() {}
  },
  Date,
  Math,
  Promise,
  encodeURIComponent
}, { filename: 'miniprogram/pages/growth/index.js' });

function page() {
  const instance = {
    ...definition,
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) {
      for(const [key,value] of Object.entries(patch)){
        if(key.startsWith('form.'))this.data.form[key.slice(5)]=value;
        else this.data[key]=value;
      }
    },
    refresh() {}
  };
  instance.data.published = [];
  instance.data.directSourceChosen = true; // Existing non-publication scenarios start after a deliberate direct selection.
  return instance;
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

async function settle() {
  await Promise.resolve();
  await new Promise(resolve => setImmediate(resolve));
}

async function submit(response, readError, requestError) {
  navigations.length = 0;
  customerReads.length = 0;
  captureKeys.length = 0;
  toasts.length = 0;
  captureResponse = response;
  customerReadError = readError;
  captureError = requestError;
  captureImpl = null;
  customerReadImpl = null;
  sessionGeneration = 1;
  currentPost = { postId: 'post-sales-a', orgType: 'store', roleCode: 'sales' };
  const instance = page();
  instance.captureOnline({ name: '李女士', phone: '13812345678', need: '二层自建房' });
  await settle();
  return instance;
}

(async () => {
  let instance = await submit({ leadId: 'lead-1', customerId: '9007199254740993', result: 'CREATED' });
  assert.deepEqual(navigations, ['/pages/workflow/index?view=customer&id=9007199254740993']);
  assert.equal(instance.data.error, '');

  instance = await submit({ leadId: 'lead-2', customerId: 'customer-linked', result: 'LINKED' });
  assert.deepEqual(customerReads, ['customer-linked'], 'LINKED 客户须先按 customerId 校验当前岗位可读');
  assert.deepEqual(navigations, ['/pages/workflow/index?view=customer&id=customer-linked']);

  instance = await submit(
    { leadId: 'lead-3', customerId: 'customer-other-owner', result: 'LINKED' },
    { code: 403, message: 'Forbidden' }
  );
  assert.deepEqual(navigations, [], '不可读客户不得伪造归属或强行进入工作区');
  assert.match(instance.data.error, /同事负责/);
  assert.equal(instance.__captureKey, '');
  assert.equal(instance.__linkedCustomerRetry, null);
  assert.equal(instance.data.form.source, '直接来访');
  assert.equal(instance.data.form.name, undefined);

  instance = await submit({ leadId: 'lead-4', result: 'LINK_REQUIRED', revision: 2 });
  assert.deepEqual(navigations, []);
  assert.equal(instance.data.pendingLead.leadId, 'lead-4');
  assert.equal(instance.data.pendingLead.revision, 2);

  captureKeys.length = 0;
  captureError = { code: -1, message: '网络中断' };
  instance = page();
  instance.__captureKey = 'retry-same-capture-key';
  instance.captureOnline({ name: '李女士', phone: '13812345678', need: '二层自建房' });
  await settle();
  assert.equal(instance.__captureKey, 'retry-same-capture-key');
  captureError = null;
  captureResponse = { leadId: 'lead-5', customerId: 'customer-after-retry', result: 'CREATED' };
  instance.captureOnline({ name: '李女士', phone: '13812345678', need: '二层自建房' });
  await settle();
  assert.deepEqual(captureKeys, ['retry-same-capture-key', 'retry-same-capture-key']);

  captureKeys.length = 0;
  instance = page();
  instance.captureOnline({ name: '李女士', phone: '123', need: '二层自建房' });
  await settle();
  assert.deepEqual(captureKeys, []);
  assert.match(instance.data.error, /有效手机号/);

  const lateCapture = deferred();
  captureImpl = () => lateCapture.promise;
  captureKeys.length = 0;
  navigations.length = 0;
  toasts.length = 0;
  instance = page();
  instance.captureOnline({ name: '原岗位客户', phone: '13812345678', need: '旧请求' });
  const originalKey = instance.__captureKey;
  currentPost = { postId: 'post-sales-b', orgType: 'store', roleCode: 'sales' };
  lateCapture.resolve({ leadId: 'lead-late-post', customerId: 'customer-late-post', result: 'CREATED' });
  await settle();
  assert.equal(instance.__captureKey, originalKey, '切岗后迟到响应不得清理旧请求状态');
  assert.equal(instance.data.form.source, '直接来访');
  assert.deepEqual(navigations, []);
  assert.deepEqual(toasts, []);

  const lateSession = deferred();
  captureImpl = () => lateSession.promise;
  currentPost = { postId: 'post-sales-a', orgType: 'store', roleCode: 'sales' };
  sessionGeneration = 2;
  navigations.length = 0;
  instance = page();
  instance.captureOnline({ name: '新会话客户', phone: '13812345678', need: '会话测试' });
  sessionGeneration = 3;
  lateSession.resolve({ leadId: 'lead-late-session', customerId: 'customer-late-session', result: 'CREATED' });
  await settle();
  assert.deepEqual(navigations, []);

  const lateUnload = deferred();
  captureImpl = () => lateUnload.promise;
  sessionGeneration = 4;
  instance = page();
  instance.captureOnline({ name: '卸载客户', phone: '13812345678', need: '页面卸载测试' });
  instance.onUnload();
  lateUnload.resolve({ leadId: 'lead-late-unload', customerId: 'customer-late-unload', result: 'CREATED' });
  await settle();
  assert.deepEqual(navigations, []);

  const lateLinkedRead = deferred();
  captureImpl = null;
  captureResponse = { leadId: 'lead-linked-late', customerId: 'customer-linked-late', result: 'LINKED' };
  customerReadImpl = () => lateLinkedRead.promise;
  currentPost = { postId: 'post-sales-a', orgType: 'store', roleCode: 'sales' };
  sessionGeneration = 5;
  navigations.length = 0;
  toasts.length = 0;
  instance = page();
  instance.data.form = { source: '直接来访', name: '迟到关联客户', phone: '13812345678', need: '保护旧岗位现场' };
  instance.captureOnline(instance.data.form);
  await settle();
  const lateLinkedKey = instance.__captureKey;
  currentPost = { postId: 'post-sales-b', orgType: 'store', roleCode: 'sales' };
  lateLinkedRead.resolve({ id: 'customer-linked-late' });
  await settle();
  assert.equal(instance.__captureKey, lateLinkedKey);
  assert.equal(instance.data.form.name, '迟到关联客户');
  assert.deepEqual(navigations, []);
  assert.deepEqual(toasts, []);

  const lateLinkedError = deferred();
  customerReadImpl = () => lateLinkedError.promise;
  currentPost = { postId: 'post-sales-a', orgType: 'store', roleCode: 'sales' };
  sessionGeneration = 6;
  instance = page();
  instance.data.form = { source: '直接来访', name: '迟到错误客户', phone: '13812345678', need: '不要清理现场' };
  instance.captureOnline(instance.data.form);
  await settle();
  const lateErrorKey = instance.__captureKey;
  sessionGeneration = 7;
  lateLinkedError.reject({ code: 403, message: 'Forbidden from old session' });
  await settle();
  assert.equal(instance.__captureKey, lateErrorKey);
  assert.equal(instance.data.form.name, '迟到错误客户');
  assert.equal(instance.data.error, '');

  captureImpl = null;
  captureResponse = { leadId: 'lead-linked-retry', customerId: 'customer-after-retry', result: 'LINKED' };
  currentPost = { postId: 'post-sales-a', orgType: 'store', roleCode: 'sales' };
  sessionGeneration = 8;
  const transientRead = deferred();
  customerReadImpl = () => transientRead.promise;
  navigations.length = 0;
  customerReads.length = 0;
  captureKeys.length = 0;
  instance = page();
  instance.data.form = { source: '直接来访', name: '关联客户', phone: '13812345678', need: '保留表单' };
  instance.captureOnline(instance.data.form);
  await settle();
  transientRead.reject({ code: -1, message: '查看客户网络失败' });
  await settle();
  const linkedKey = instance.__captureKey;
  assert.ok(linkedKey);
  assert.equal(instance.__linkedCustomerRetry.customerId, 'customer-after-retry');
  assert.equal(instance.data.form.name, '关联客户');
  assert.equal(captureKeys.length, 1);
  assert.match(instance.data.error, /重试查看/);
  customerReadImpl = async id => ({ id });
  instance.captureOnline(instance.data.form);
  await settle();
  assert.equal(captureKeys.length, 1, '重试查看不得重发 capture');
  assert.equal(instance.__captureKey, '');
  assert.equal(instance.data.form.source, '直接来访');
  assert.equal(instance.data.form.name, undefined);
  assert.deepEqual(navigations, ['/pages/workflow/index?view=customer&id=customer-after-retry']);

  instance = page();
  instance.data.view = 'capture';
  instance.refresh = definition.refresh;
  instance.refresh();
  assert.equal(instance.data.content, null, 'capture 页没有内容对象时必须传 null，不向渲染层传 undefined');

  const pendingSummary=deferred();
  const publicationId='90071992547409931';
  summaryImpl=()=>pendingSummary.promise;
  instance=page();
  instance.refresh=definition.refresh;
  instance.onLoad({view:'capture',publicationId});
  instance.onShow();
  let sentSource;
  captureImpl=async body=>{sentSource=body.source;return {result:'CREATED',customerId:'from-publication'};};
  const form={...instance.data.form,name:'作品客资',phone:'13812345678',need:'真实来访'};
  instance.captureOnline(form);
  assert.equal(sentSource,undefined,'待核对的作品不能静默记为直接来访');
  pendingSummary.resolve({perPublication:[{id:publicationId,title:'正式发布'}]});
  await settle();
  assert.equal(instance.data.publishedIndex,1);
  instance.captureOnline(form);
  await settle();
  assert.equal(sentSource,'内容发布:'+publicationId);

  const staleSummary=deferred();
  summaryImpl=()=>staleSummary.promise;
  instance=page();
  instance.refresh=definition.refresh;
  instance.onLoad({view:'capture',publicationId});
  instance.onShow();
  sessionGeneration++;
  currentPost={postId:'post-sales-c',orgType:'store',roleCode:'sales'};
  summaryImpl=async()=>({perPublication:[]});
  instance.onShow();
  staleSummary.resolve({perPublication:[{id:publicationId,title:'旧岗位作品'}]});
  await settle();
  assert.equal(instance.data.published.length,0);
  assert.equal(instance.data.form.contentId,'');

  summaryImpl=async()=>{throw Error('作品接口弱网');};
  instance=page();instance.refresh=definition.refresh;
  instance.onLoad({view:'capture'});instance.onShow();await settle();
  captureKeys.length=0;sentSource=undefined;
  const offlineFields={name:'明示来源测试',phone:'13812345678',need:'现场咨询'};
  const directForm=()=>({...offlineFields,...instance.data.form});
  assert.equal(instance.data.directSourceChosen,false);
  assert.match(instance.data.publicationError,/暂时无法读取/);
  instance.captureOnline(directForm());await settle();
  assert.equal(captureKeys.length,0,'作品接口失败不能用默认值登记为直接来访');
  instance.chooseDirectSource();
  instance.captureOnline(directForm());await settle();
  assert.equal(sentSource,'直接来访');
  assert.equal(captureKeys.length,1,'明确选择非作品来源不必等待无关的作品接口');
  console.log('PASS 来源接口失败不默认来访，用户点选后仍能登记');

  summaryImpl=async()=>({perPublication:[{id:publicationId,title:'旧显示顺序作品'}]});
  instance=page();instance.refresh=definition.refresh;
  instance.onLoad({view:'capture',publicationId});instance.onShow();await settle();
  assert.equal(instance.data.publishedIndex,1);
  summaryImpl=async()=>{throw Error('作品接口弱网');};
  await instance.syncGrowth();
  assert.equal(instance.data.form.contentId,publicationId,'失败保留原作品选择');
  assert.equal(instance.data.published.length,0,'失败不把旧名单当作有效候选');
  sentSource=undefined;
  instance.captureOnline(directForm());await settle();
  assert.equal(sentSource,undefined);
  summaryImpl=async()=>({perPublication:[{id:'90071992547409935',title:'另一作品'},{id:publicationId,title:'原作品'}]});
  await instance.syncGrowth();
  assert.equal(instance.data.publishedIndex,2);
  instance.captureOnline(directForm());await settle();
  assert.equal(sentSource,'内容发布:'+publicationId,'作品排序变化后仍用原 ID');
  console.log('PASS 弱网保留固定作品编号，重新排列不改来源');

  const pendingRow=(id,name)=>({id,name,phone:'13812345678',status:'LINK_REQUIRED',revision:1});
  const oldList=deferred();
  currentPost={postId:'lead-manager-old',orgType:'store',roleCode:'sales-lead'};
  pendingImpl=()=>oldList.promise;
  instance=page();instance.refresh=definition.refresh;instance.onLoad({view:'home'});instance.onShow();
  currentPost={postId:'lead-manager-new',orgType:'store',roleCode:'boss'};sessionGeneration++;
  pendingImpl=async()=>[pendingRow('new-lead','新岗位客资')];
  instance.onShow();await settle();
  oldList.resolve([pendingRow('old-lead','旧岗位客资')]);await settle();
  assert.deepEqual(Array.from(instance.data.pendingLeads, r=>r.leadId),['new-lead']);
  const oldMutation=deferred();
  linkImpl=()=>oldMutation.promise;
  toasts.length=0;
  instance.onlineLinkLead({currentTarget:{dataset:{leadId:'new-lead'}}});
  currentPost={postId:'outside',orgType:'other',roleCode:'boss'};sessionGeneration++;
  instance.onShow();
  assert.equal(instance.data.pendingLeads.length,0,'撤权跳转之前必须清掉姓名和手机号');
  oldMutation.resolve({});await settle();
  assert.equal(instance.data.pendingLeads.length,0);
  assert.deepEqual(toasts,[],'旧岗位操作回调不能在新岗位跳成功提示');
  console.log('PASS 待关联名单及办理回调不能跨岗位；撤权先清空再跳转');

  const pendingRead=deferred(), pendingWrite=deferred(), newRead=deferred(), actions=[];
  currentPost={postId:'lead-manager',orgType:'store',roleCode:'sales-lead'};sessionGeneration++;
  pendingImpl=async()=>[pendingRow('other','同事另一个线索'),pendingRow('to-link','应关联')];
  instance=page();instance.refresh=definition.refresh;instance.onLoad({view:'home'});instance.onShow();await settle();
  pendingImpl=()=>pendingRead.promise;
  const oldRequest=instance.loadPendingLeads();
  linkImpl=(...args)=>{actions.push(['link',...args]);return pendingWrite.promise;};
  invalidateImpl=(...args)=>{actions.push(['invalidate',...args]);return Promise.resolve({});};
  const actionEvent={currentTarget:{dataset:{leadId:'to-link',idx:0}}};
  const saving=instance.onlineLinkLead(actionEvent);
  instance.onlineInvalidateLead(actionEvent);
  assert.equal(actions.length,1,'同一条在处理时不能另起作废');
  assert.equal(actions[0][1],'to-link','按稳定编号而不是旧列表位置操作');
  pendingImpl=()=>newRead.promise;
  pendingWrite.resolve({});await settle();
  assert.deepEqual(Array.from(instance.data.pendingLeads, r=>r.leadId),['other']);
  pendingRead.resolve([pendingRow('to-link','过期列表不应重现')]);await oldRequest;
  assert.deepEqual(Array.from(instance.data.pendingLeads, r=>r.leadId),['other']);
  newRead.resolve([pendingRow('other','另一条')]);await saving;
  assert.deepEqual(Array.from(instance.data.pendingLeads, r=>r.leadId),['other']);
  console.log('PASS 处理成功立即从名单移除，早先的名单不会恢复；不会按行号操作错人');

  invalidateImpl=async()=>{throw {code:403,message:'当前身份已不能办理'};};
  await instance.onlineInvalidateLead({currentTarget:{dataset:{leadId:'other'}}});
  assert.equal(instance.data.pendingLeads.length,0,'写入返回无权时也立即清理此前的名单');
  console.log('PASS 办理返回撤权时立即清空旧名单');

  console.log('PASS CREATED 登记后进入 customerId 对应客户工作区');
  console.log('PASS LINKED 仅在当前岗位可读时进入客户工作区');
  console.log('PASS LINK_REQUIRED 保留负责人查重归属处理');
  console.log('PASS 失败重试复用幂等键，非法手机号不发请求');
  console.log('PASS 切岗、会话变更和页面卸载后丢弃迟到回调');
  console.log('PASS LINKED 临时查看失败保留表单，重试不重发 capture');
  console.log('PASS capture 空内容展示值统一为 null');
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
