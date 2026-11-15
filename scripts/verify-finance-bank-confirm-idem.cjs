const assert = require('node:assert/strict');
const http = require('../miniprogram/api/http');
const auth = require('../miniprogram/api/auth');
const PF = require('../miniprogram/utils/platform-finance');
const attach = require('../miniprogram/pages/admin/finance-desk/platform-actions');

const flush = () => new Promise(resolve => setImmediate(resolve));

async function main() {
  const originalSessionGeneration = http.sessionGeneration;
  const originalCurrentPost = auth.currentApiPost;
  let session = 7;
  let post = { postId: 'post-A', orgId: 'org-A' };
  http.sessionGeneration = () => session;
  auth.currentApiPost = () => post;
  global.wx = { toasts: [], showToast(options) { this.toasts.push(options); } };

  let keySeq = 0;
  const calls = [];
  let deferred;
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

    assert.equal(page.loads, 1, '仅成功确认刷新一次账簿');
    console.log('verify-finance-bank-confirm-idem ✔ 失败重试同键，成功/选择/组织/岗位变化换键');
  } finally {
    http.sessionGeneration = originalSessionGeneration;
    auth.currentApiPost = originalCurrentPost;
    delete global.wx;
  }
}

main().catch(error => { console.error(error); process.exit(1); });
