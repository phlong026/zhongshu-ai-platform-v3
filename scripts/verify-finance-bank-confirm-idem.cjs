const assert = require('node:assert/strict');
const http = require('../miniprogram/api/http');
const auth = require('../miniprogram/api/auth');
const PF = require('../miniprogram/utils/platform-finance');
const attach = require('../miniprogram/pages/admin/finance-desk/platform-actions');

const flush = () => new Promise(resolve => setImmediate(resolve));

function loadFinancePageSpec() {
  const file = require.resolve('../miniprogram/pages/admin/finance-desk/index');
  const previousPage = global.Page;
  let spec;
  global.Page = value => { spec = value; };
  delete require.cache[file];
  try { require(file); }
  finally { global.Page = previousPage; }
  return spec;
}

async function main() {
  const originalSessionGeneration = http.sessionGeneration;
  const originalCurrentPostId = http.currentPostId;
  const originalCurrentPost = auth.currentApiPost;
  let session = 7;
  let post = { postId: 'post-A', orgId: 'org-A' };
  http.sessionGeneration = () => session;
  http.currentPostId = () => post.postId;
  auth.currentApiPost = () => post;
  global.wx = { toasts: [], showToast(options) { this.toasts.push(options); } };

  let keySeq = 0;
  const calls = [];
  let deferred;
  let todosPage;
  let originalTodosRefresh;
  const financeApi = {
    idemKey(action) { return 'fin-' + action + '-key-' + (++keySeq); },
    confirmBank(flowId, receiptId, idem) {
      calls.push({ flowId, receiptId, idem });
      return new Promise((resolve, reject) => { deferred = { resolve, reject }; });
    }
  };
  const page = {
    data: { busy: false, platformRegister: { pendingReceipts: [{ id: 'r-1' }, { id: 'r-2' }] } },
    loads: 0,
    setData(patch) { Object.assign(this.data, patch); },
    loadPlatformFinance() { this.loads += 1; }
  };
  attach(page, financeApi, PF);

  try {
    page.bankPick({ currentTarget: { dataset: { flowId: 'f-1' } }, detail: { value: '0' } });
    page.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-1' } } });
    await flush();
    const firstKey = calls[0].idem;
    assert.match(firstKey, /^fin-confirm-key-/, '到账确认必须显式传稳定幂等键');
    deferred.reject({ message: '网络异常' });
    await flush();

    page.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-1' } } });
    await flush();
    assert.equal(calls[1].idem, firstKey, '同组织/岗位/流水/应收的失败重试必须复用同键');
    deferred.resolve({ confirmId: 'c-1' });
    await flush();
    assert.equal(page.bankConfirmIdem, null, '成功后必须释放粘性键');

    page.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-1' } } });
    await flush();
    assert.notEqual(calls[2].idem, firstKey, '成功后的下一次确认必须生成新键');
    deferred.reject({ message: '网络异常' });
    await flush();

    page.bankPick({ currentTarget: { dataset: { flowId: 'f-1' } }, detail: { value: '1' } });
    page.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-1' } } });
    await flush();
    assert.notEqual(calls[3].idem, calls[2].idem, '更换应收选择必须生成新键');
    deferred.reject({ message: '网络异常' });
    await flush();

    post = { postId: 'post-A', orgId: 'org-B' };
    page.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-1' } } });
    await flush();
    assert.notEqual(calls[4].idem, calls[3].idem, '组织变化不得复用原组织幂等键');
    deferred.reject({ message: '网络异常' });
    await flush();

    session += 1;
    post = { postId: 'post-B', orgId: 'org-B' };
    page.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-1' } } });
    await flush();
    assert.notEqual(calls[5].idem, calls[4].idem, '会话或岗位变化必须生成新键');
    session += 1;
    post = { postId: 'post-C', orgId: 'org-C' };
    page.data.busy = 'new-scope-busy';
    deferred.reject({ message: '网络异常' });
    await flush();
    assert.equal(page.data.busy, 'new-scope-busy', '旧会话的 finally 不得清除新会话忙状态');

    const requests = [];
    const concurrentApi = {
      idemKey(action) { return 'concurrent-' + action + '-' + (++keySeq); },
      confirmBank(flowId, receiptId, idem) {
        let resolve; let reject;
        const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
        requests.push({ flowId, receiptId, idem, resolve, reject });
        return promise;
      }
    };
    todosPage = require('../miniprogram/utils/platform-todos-page');
    originalTodosRefresh = todosPage.refresh;
    todosPage.refresh = () => {};
    const spec = loadFinancePageSpec();
    const switched = {
      ...spec,
      data: { busy: false, platformRegister: { pendingReceipts: [{ id: 'r-1' }] } },
      loads: 0,
      __designFinance: true,
      __platformActions: true,
      __ptSyncHook: true,
      setData(patch) { Object.assign(this.data, patch); },
      loadPlatformFinance() { this.loads += 1; },
      loadDesignFees() {},
      loadProjectLedger() {},
      loadFinanceProjects() {},
      loadFinanceSourceRows() {},
      refresh() {},
      syncPlatformTasks() {}
    };
    attach(switched, concurrentApi, PF);

    session = 20; post = { postId: 'post-A', orgId: 'org-A' };
    switched.onShow();
    switched.loads = 0;
    switched.data.platformRegister = { pendingReceipts: [{ id: 'r-1' }] };
    switched.bankPick({ currentTarget: { dataset: { flowId: 'f-switch' } }, detail: { value: '0' } });
    switched.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-switch' } } });
    await flush();
    assert.equal(requests.length, 1, 'A身份先发出挂起确认');

    session = 21; post = { postId: 'post-B', orgId: 'org-B' };
    switched.onShow();
    switched.loads = 0;
    switched.data.platformRegister = { pendingReceipts: [{ id: 'r-1' }] };
    assert.equal(switched.data.busy, false, '真实身份刷新必须解除旧身份的全页锁');
    switched.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-switch' } } });
    await flush();
    assert.equal(requests.length, 2, 'B身份必须能在A请求挂起时发出第二个确认');
    assert.notEqual(requests[1].idem, requests[0].idem, 'B身份必须生成独立幂等键');
    const bKey = switched.bankConfirmIdem;
    const toastCount = wx.toasts.length;
    requests[0].resolve({ confirmId: 'late-A-success' });
    await flush();
    assert.equal(switched.data.busy, true, 'A迟到成功与finally不得清除B请求的忙状态');
    assert.equal(switched.bankConfirmIdem, bKey, 'A迟到成功不得释放B幂等键');
    assert.equal(switched.loads, 0, 'A迟到成功不得以B身份刷新账簿');
    assert.equal(wx.toasts.length, toastCount, 'A迟到成功不得显示B现场提示');
    requests[1].resolve({ confirmId: 'current-B-success' });
    await flush();
    assert.equal(switched.data.busy, false, 'B成功后释放自己的忙状态');
    assert.equal(switched.loads, 1, '仅B成功刷新一次账簿');

    session = 22; post = { postId: 'post-A2', orgId: 'org-A2' };
    switched.onShow();
    switched.loads = 0;
    switched.data.platformRegister = { pendingReceipts: [{ id: 'r-1' }] };
    switched.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-switch' } } });
    await flush();
    session = 23; post = { postId: 'post-C', orgId: 'org-C' };
    switched.onShow();
    switched.loads = 0;
    switched.data.platformRegister = { pendingReceipts: [{ id: 'r-1' }] };
    switched.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-switch' } } });
    await flush();
    assert.equal(requests.length, 4, '失败交叉时序也应允许新身份发请求');
    const cKey = switched.bankConfirmIdem;
    const beforeLateFailureToasts = wx.toasts.length;
    requests[2].reject({ message: 'late-A2-failure' });
    await flush();
    assert.equal(switched.data.busy, true, 'A迟到失败与finally不得清除C请求的忙状态');
    assert.equal(switched.bankConfirmIdem, cKey, 'A迟到失败不得替换C幂等键');
    assert.equal(wx.toasts.length, beforeLateFailureToasts, 'A迟到失败不得弹旧错误');
    requests[3].reject({ message: 'current-C-failure' });
    await flush();
    assert.equal(switched.data.busy, false, 'C当前失败后释放自己的忙状态');
    assert.equal(wx.toasts.length, beforeLateFailureToasts + 1, '仅C当前失败展示一次错误');

    assert.equal(page.loads, 1, '仅成功确认刷新一次账簿');
    console.log('verify-finance-bank-confirm-idem ✔ 失败重试同键，成功/选择/组织/岗位变化换键');
  } finally {
    http.sessionGeneration = originalSessionGeneration;
    http.currentPostId = originalCurrentPostId;
    auth.currentApiPost = originalCurrentPost;
    if (todosPage && originalTodosRefresh) todosPage.refresh = originalTodosRefresh;
    delete global.wx;
  }
}

main().catch(error => { console.error(error); process.exit(1); });
