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
  global.wx = {
    getStorageSync() { return undefined; },
    toasts: [],
    showToast(options) { this.toasts.push(options); },
    showModal(options) { options.success({ confirm: true, content: '跨岗位撤销原因足够长' }); }
  };

  let keySeq = 0;
  const calls = [];
  let deferred;
  let todosPage;
  let originalTodosRefresh;
  let performanceApi;
  let originalReconcile;
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
    page.__financeBusyOwner = {};
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
        requests.push({ kind: 'bank', flowId, receiptId, idem, resolve, reject });
        return promise;
      },
      voidPayment(paymentId, reason, idem) {
        let resolve; let reject;
        const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
        requests.push({ kind: 'void', paymentId, reason, idem, resolve, reject });
        return promise;
      },
      voidRefund(refundId, reason, idem) {
        let resolve; let reject;
        const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
        requests.push({ kind: 'correction', refundId, reason, idem, resolve, reject });
        return promise;
      },
      registerReceipt(body, idem) {
        let resolve; let reject;
        const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
        requests.push({ kind: 'register', body, idem, resolve, reject });
        return promise;
      }
    };
    todosPage = require('../miniprogram/utils/platform-todos-page');
    originalTodosRefresh = todosPage.refresh;
    todosPage.refresh = () => {};
    const spec = loadFinancePageSpec();
    performanceApi = require('../miniprogram/api/franchise/performance');
    originalReconcile = performanceApi.operating.reconcile;
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

    session = 30; post = { postId: 'void-A', orgId: 'org-A' };
    switched.onShow();
    switched.loads = 0;
    switched.data.platformLedger = [{ id: 'p-9', amountCents: 123400, canVoid: true }];
    switched.openPaymentVoid({ currentTarget: { dataset: { id: 'p-9' } } });
    await flush();
    assert.equal(requests[4].kind, 'void', 'A身份付款撤销已挂起');

    session = 31; post = { postId: 'bank-B', orgId: 'org-B' };
    switched.onShow();
    switched.loads = 0;
    switched.data.platformRegister = { pendingReceipts: [{ id: 'r-1' }] };
    switched.bankPick({ currentTarget: { dataset: { flowId: 'f-cross' } }, detail: { value: '0' } });
    switched.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-cross' } } });
    await flush();
    assert.equal(requests[5].kind, 'bank', 'B身份银行确认在A撤销挂起时已发出');
    const crossBankKey = switched.bankConfirmIdem;
    requests[4].resolve({});
    await flush();
    assert.equal(switched.data.busy, true, 'A撤销迟到成功不得清除B银行确认的共享锁');
    assert.equal(switched.bankConfirmIdem, crossBankKey, 'A撤销迟到成功不得释放B银行幂等键');
    requests[5].resolve({});
    await flush();

    session = 32; post = { postId: 'void-A-fail', orgId: 'org-A' };
    switched.onShow(); switched.loads = 0;
    switched.data.platformLedger = [{ id: 'p-10', amountCents: 223400, canVoid: true }];
    switched.openPaymentVoid({ currentTarget: { dataset: { id: 'p-10' } } });
    await flush();
    session = 33; post = { postId: 'bank-B2', orgId: 'org-B' };
    switched.onShow(); switched.loads = 0;
    switched.data.platformRegister = { pendingReceipts: [{ id: 'r-1' }] };
    switched.bankPick({ currentTarget: { dataset: { flowId: 'f-cross-fail' } }, detail: { value: '0' } });
    switched.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-cross-fail' } } });
    await flush();
    const crossFailKey = switched.bankConfirmIdem;
    const beforeVoidFailureToasts = wx.toasts.length;
    requests[6].reject({ code: 1102000037 });
    await flush();
    assert.equal(switched.data.busy, true, 'A撤销迟到失败不得清除B银行确认的共享锁');
    assert.equal(switched.bankConfirmIdem, crossFailKey, 'A撤销迟到失败不得释放B银行幂等键');
    assert.equal(wx.toasts.length, beforeVoidFailureToasts, 'A撤销迟到失败不得弹旧错误');
    assert.equal(switched.data.ledgerError, '', 'A撤销迟到失败不得写入B错误态');
    requests[7].resolve({});
    await flush();

    session = 40; post = { postId: 'correction-A', orgId: 'org-A' };
    switched.onShow(); switched.loads = 0;
    switched._refundRows = [{ id: 'refund-1', status: '已核实', refundDirection: 'client', receiptId: 'receipt-1', amountCents: 8800 }];
    switched.openCorrection({ currentTarget: { dataset: { id: 'f-refund-1' } } });
    await flush();
    assert.equal(requests[8].kind, 'correction', 'A身份退款更正已挂起');
    session = 41; post = { postId: 'bank-B3', orgId: 'org-B' };
    switched.onShow(); switched.loads = 0;
    switched.data.platformRegister = { pendingReceipts: [{ id: 'r-1' }] };
    switched.bankPick({ currentTarget: { dataset: { flowId: 'f-correction' } }, detail: { value: '0' } });
    switched.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-correction' } } });
    await flush();
    const correctionBankKey = switched.bankConfirmIdem;
    requests[8].resolve({});
    await flush();
    assert.equal(switched.data.busy, true, 'A更正迟到成功不得清除B银行确认的共享锁');
    assert.equal(switched.bankConfirmIdem, correctionBankKey, 'A更正迟到成功不得释放B银行幂等键');
    assert.notEqual(switched.data.registerType, 'refund', 'A更正迟到成功不得打开B身份表单');
    assert.equal(switched.loads, 0, 'A更正迟到成功不得刷新B账簿');
    requests[9].resolve({});
    await flush();

    session = 42; post = { postId: 'correction-A-fail', orgId: 'org-A' };
    switched.onShow(); switched.loads = 0;
    switched._refundRows = [{ id: 'refund-2', status: '已核实', refundDirection: 'client', receiptId: 'receipt-2', amountCents: 9900 }];
    switched.openCorrection({ currentTarget: { dataset: { id: 'f-refund-2' } } });
    await flush();
    session = 43; post = { postId: 'bank-B4', orgId: 'org-B' };
    switched.onShow(); switched.loads = 0;
    switched.data.platformRegister = { pendingReceipts: [{ id: 'r-1' }] };
    switched.bankPick({ currentTarget: { dataset: { flowId: 'f-correction-fail' } }, detail: { value: '0' } });
    switched.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-correction-fail' } } });
    await flush();
    const beforeCorrectionFailureToasts = wx.toasts.length;
    requests[10].reject({ message: 'late-correction-failure' });
    await flush();
    assert.equal(switched.data.busy, true, 'A更正迟到失败不得清除B银行确认的共享锁');
    assert.equal(switched.data.registerError || '', '', 'A更正迟到失败不得写入B表单错误');
    assert.equal(wx.toasts.length, beforeCorrectionFailureToasts, 'A更正迟到失败不得弹旧提示');
    requests[11].resolve({});
    await flush();

    session = 50; post = { postId: 'register-A', orgId: 'org-A' };
    switched.onShow(); switched.loads = 0;
    switched.data.registerType = 'receipt';
    switched.data.registerForm = { name: '测试收款', amount: '88.00', method: 'cash' };
    switched.submitRegister();
    await flush();
    assert.equal(requests[12].kind, 'register', 'A身份登记已挂起');
    post = { postId: 'bank-B5', orgId: 'org-B' }; // 同session切岗，必须依赖financeIdentity代次而非仅sessionGuard
    switched.onShow(); switched.loads = 0;
    switched.data.platformRegister = { pendingReceipts: [{ id: 'r-1' }] };
    switched.bankPick({ currentTarget: { dataset: { flowId: 'f-register' } }, detail: { value: '0' } });
    switched.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-register' } } });
    await flush();
    const registerBankKey = switched.bankConfirmIdem;
    requests[12].resolve({});
    await flush(); await flush();
    assert.equal(switched.data.busy, true, 'A登记迟到成功不得清除B银行确认的共享锁');
    assert.equal(switched.bankConfirmIdem, registerBankKey, 'A登记迟到成功不得释放B银行幂等键');
    assert.equal(switched.data.registerType || '', '', 'A登记迟到成功不得清理或重建B表单');
    assert.equal(switched.loads, 0, 'A登记迟到成功不得刷新B账簿');
    requests[13].resolve({});
    await flush();

    session = 51; post = { postId: 'register-A-fail', orgId: 'org-A' };
    switched.onShow(); switched.loads = 0;
    switched.data.registerType = 'receipt';
    switched.data.registerForm = { name: '测试收款', amount: '99.00', method: 'cash' };
    switched.submitRegister();
    await flush();
    post = { postId: 'bank-B6', orgId: 'org-B' };
    switched.onShow(); switched.loads = 0;
    switched.data.platformRegister = { pendingReceipts: [{ id: 'r-1' }] };
    switched.bankPick({ currentTarget: { dataset: { flowId: 'f-register-fail' } }, detail: { value: '0' } });
    switched.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-register-fail' } } });
    await flush();
    requests[14].reject({ message: 'late-register-failure' });
    await flush(); await flush();
    assert.equal(switched.data.busy, true, 'A登记迟到失败不得清除B银行确认的共享锁');
    assert.equal(switched.data.registerError || '', '', 'A登记迟到失败不得写入B表单错误');
    requests[15].resolve({});
    await flush();

    let reconcileRequest;
    performanceApi.operating.reconcile = () => new Promise((resolve, reject) => { reconcileRequest = { resolve, reject }; });
    session = 60; post = { postId: 'reconcile-A', orgId: 'org-A' };
    switched.onShow(); switched.loads = 0;
    switched.data.monthClosing = { status: 'ready', period: '2026-11', current: { sourceSignature: 'reviewed-version' } };
    switched.reconcileBook();
    assert.equal(switched.data.busy, true, 'A身份对账已挂起');
    session = 61; post = { postId: 'bank-B7', orgId: 'org-B' };
    switched.onShow(); switched.loads = 0;
    switched.data.platformRegister = { pendingReceipts: [{ id: 'r-1' }] };
    switched.bankPick({ currentTarget: { dataset: { flowId: 'f-reconcile' } }, detail: { value: '0' } });
    switched.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-reconcile' } } });
    await flush();
    const beforeReconcileSuccessToasts = wx.toasts.length;
    reconcileRequest.resolve({});
    await flush();
    assert.equal(switched.data.busy, true, 'A对账迟到成功不得清除B银行确认的共享锁');
    assert.equal(wx.toasts.length, beforeReconcileSuccessToasts, 'A对账迟到成功不得弹旧提示');
    assert.equal(switched.loads, 0, 'A对账迟到成功不得刷新B账簿');
    requests[16].resolve({});
    await flush();

    session = 62; post = { postId: 'reconcile-A-fail', orgId: 'org-A' };
    switched.onShow(); switched.loads = 0;
    switched.data.monthClosing = { status: 'ready', period: '2026-11', current: { sourceSignature: 'reviewed-version' } };
    switched.reconcileBook();
    session = 63; post = { postId: 'bank-B8', orgId: 'org-B' };
    switched.onShow(); switched.loads = 0;
    switched.data.platformRegister = { pendingReceipts: [{ id: 'r-1' }] };
    switched.bankPick({ currentTarget: { dataset: { flowId: 'f-reconcile-fail' } }, detail: { value: '0' } });
    switched.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 'f-reconcile-fail' } } });
    await flush();
    const beforeReconcileFailureToasts = wx.toasts.length;
    reconcileRequest.reject({ message: 'late-reconcile-failure' });
    await flush();
    assert.equal(switched.data.busy, true, 'A对账迟到失败不得清除B银行确认的共享锁');
    assert.equal(wx.toasts.length, beforeReconcileFailureToasts, 'A对账迟到失败不得弹旧错误');
    requests[17].resolve({});
    await flush();

    assert.equal(page.loads, 1, '仅成功确认刷新一次账簿');
    console.log('verify-finance-bank-confirm-idem ✔ 失败重试同键，成功/选择/组织/岗位变化换键');
  } finally {
    http.sessionGeneration = originalSessionGeneration;
    http.currentPostId = originalCurrentPostId;
    auth.currentApiPost = originalCurrentPost;
    if (todosPage && originalTodosRefresh) todosPage.refresh = originalTodosRefresh;
    if (performanceApi && originalReconcile) performanceApi.operating.reconcile = originalReconcile;
    delete global.wx;
  }
}

main().catch(error => { console.error(error); process.exit(1); });
