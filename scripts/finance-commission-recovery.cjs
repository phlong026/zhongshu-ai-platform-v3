const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const runtime = require('./finance-fullflow-runtime.cjs');

async function run() {
  const r = await runtime();
  const { m } = r;
  const original = JSON.parse(fs.readFileSync(path.join(r.root, 'commission-flow-handles.json')));
  const project = String(original.project.id);
  const home = '/pages/admin/finance-desk/index';
  const book = home + '?view=book&bookScope=project%3A' + project;
  const commission = '/pages/admin/commission/index?id=' + project;
  const contract = '/pages/workflow/index?view=contract&id=' + original.customerId;
  const out = path.resolve('docs/finance-fullflow-20261117/commission-recovery');
  fs.mkdirSync(out, { recursive: true });
  const resultPath = path.join(out, 'result.json');
  const result = fs.existsSync(resultPath) ? JSON.parse(fs.readFileSync(resultPath)) : { project, checks: {}, snapshots: {} };
  const save = () => fs.writeFileSync(resultPath, JSON.stringify(result, null, 2));
  const pass = name => { result.checks[name] = true; save(); console.log('PASS', name); };
  const shot = async name => { await r.pause(700); await m.screenshot({ path: path.join(out, name + '.png') }); };
  const row = async () => (await r.api('GET', '/franchise/performance/commission/rows')).find(s => String(s.projectId) === project);
  const salesSummary = () => r.api('GET', '/franchise/sales/contracts/' + original.contractId + '/stage-summary');
  const getLedger = () => r.api('GET', '/franchise/finance/workbench/ledger?scope=project:' + project);
  const uiStages = async () => m.evaluate(id => getCurrentPages().slice(-1)[0].data.commissionRows.find(q => q.projectId === id).stages, project);
  const snapshot = async name => {
    const ledger = await getLedger();
    assert.equal(ledger.truncated, false);
    const entries = ledger.entries.map(e => ({ id: String(e.id), type: e.type, cents: e.amount_cents }));
    const stages = (await row()).stages.map(s => ({
      stage: s.stage, payableId: s.payableId, paid: s.paidCents, remaining: s.outstandingCents,
      eligible: s.eligible, reviewDue: s.reviewDue, reviews: s.reconciliations?.length || 0
    }));
    const state = { summary: ledger.summary, entries, stages };
    result.snapshots[name] = state;
    save();
    return state;
  };
  const focusProject = async () => {
    const cards = await (await m.currentPage()).$$('.commission-card');
    const index = await m.evaluate(id => getCurrentPages().slice(-1)[0].data.commissionRows.findIndex(q => q.projectId === id),project);
    assert.ok(cards[index], '找不到验收项目卡片');
    await r.scroll(Math.max(0, (await cards[index].offset()).top - 105));
  };
  const selector = (action, stage) => 'button[data-action="' + action + '"][data-id="' + project + '"][data-stage="' + stage + '"]';
  const submit = async label => {
    await r.click(label);
    await r.until(async () => {
      const d = await r.data();
      if (d.registerError || d.wbError) throw Error(d.registerError || d.wbError);
      return !!d.result?.id;
    }, label);
  };
  const stageKeys = ['start', 'base', 'topping'];
  const oldPayables = [original.release0, original.release1].map(String);
  try {
    assert.equal(await m.evaluate(() => wx.getStorageSync('zs-api-base')), 'http://127.0.0.1:18091/admin-api');
    result.device = (await m.systemInfo()).model;
    await r.auth('finance');
    if (!result.snapshots.before) {
      const initial = await snapshot('before');
      assert.equal(initial.summary.outstanding_cents, 15000);
      assert.equal(initial.summary.receivable_cents, 30000);
      assert.deepEqual(initial.stages.slice(0, 2).map(s => String(s.payableId)), oldPayables);
      pass('延续原项目、两笔原应付及首笔已付款');
    }
    for (let i = 0; i < 3; i++) {
      await r.auth('sales');
      const stages = await salesSummary();
      const stage = stages.find(s => s.stageId === original.stages[i].stageId);
      if (stage.balanceCents === 0) continue;
      assert.equal(stage.balanceCents, 10000);
      await r.nav(contract);
      const number = 'RECOVER-1118-' + stage.stage;
      const pending = async () => (await r.api('GET', '/franchise/sales/contracts?customerId=' + original.customerId))
        .find(c => String(c.contract.id) === String(original.contractId)).declarations.find(d => d.transaction_no === number);
      let declaration = await pending();
      if (!declaration) {
        await r.tap('button[data-stage-id="' + stage.stageId + '"]');
        await r.input('amount', '100', 'data-key');
        await r.input('transactionNo', number, 'data-key');
        await r.click('确认办理回款');
        assert.equal((await r.data()).error, '');
        declaration = await pending();
      }
      assert.ok(declaration?.id);
      assert.equal((await salesSummary()).find(s => s.stageId === stage.stageId).balanceCents, 10000);
      await r.auth('finance');
      await r.nav(home + '?view=flows&tab=receivables');
      await r.until(async () => !!await (await m.currentPage()).$('.journey-progress[data-id="' + declaration.id + '"]'), '载入业务收款申报', 60000);
      await r.tap('.journey-progress[data-id="' + declaration.id + '"]');
      await submit('确认实际到账');
      await r.click('返回上一页');
    }
    await r.auth('sales');
    const stages = await salesSummary();
    assert.equal(stages.length, 3);
    for (const s of stages) assert.deepEqual([s.grossCents,s.refundedCents,s.collectedCents,s.balanceCents], [110000,10000,100000,0]);
    result.sales = stages;
    if (!result.checks['三期原合同各补收100元，财务逐笔核实后业务净额恢复']) {
      await r.nav(contract);
      await shot('01-sales');
      pass('三期原合同各补收100元，财务逐笔核实后业务净额恢复');
    }
    await r.auth('finance');
    await r.nav(commission);
    if (process.argv.includes('--observe')) {
      const observed = await snapshot('beforeFix');
      console.log('原实现补齐后的待核对状态', observed.stages.map(s => s.reviewDue));
      await focusProject(); await shot('02-before-fix');
      return;
    }
    if (!result.checks['补齐后两笔原应付均等待老板核对，封顶期可首次生成']) {
      const view = await uiStages();
      assert.deepEqual(view.map(s => s.canPay), [false,false,false]);
      assert.deepEqual(view.map(s => s.canRelease), [false,false,true]);
      const waiting = await snapshot('awaitingReview');
      assert.deepEqual(waiting.stages.map(s => s.reviewDue), [true,true,false]);
      assert.equal(waiting.summary.committed_cents, 25000);
      await focusProject(); await shot('03-awaiting-review');
      pass('补齐后两笔原应付均等待老板核对，封顶期可首次生成');
    }
    for (let i = 0; i < 2; i++) {
      const check = ['签约期核对仅恢复原单50元', '基础期核对恢复原单100元'][i];
      if (result.checks[check]) continue;
      await r.auth('boss');
      await r.nav(commission);
      if ((await row()).stages[i].reviewDue) {
        await r.tap(selector('commission-reconcile', stageKeys[i]));
        await (await r.el('textarea[data-key="note"]')).input('业主本期已补回100元，净回款1000元，按原应付余额继续办理。');
        const img = fs.readFileSync('miniprogram/assets/book.png').toString('base64');
        const file = await m.evaluate(b => { const p = wx.env.USER_DATA_PATH + '/collection-review.png'; wx.getFileSystemManager().writeFileSync(p,b,'base64'); return p; }, img);
        try {
          await m.mockWxMethod('showActionSheet', { tapIndex: 0 });
          await m.mockWxMethod('chooseMessageFile', { tempFiles: [{ path: file, name: '补收核对依据.png' }] });
          await m.mockWxMethod('saveFile', { savedFilePath: file });
          await r.click('上传核对依据');
        } finally {
          for (const method of ['showActionSheet','chooseMessageFile','saveFile']) await m.restoreWxMethod(method);
        }
        if (i === 0) await shot('04-boss-review');
        await r.click('保存核对记录');
        assert.equal((await r.data()).error, '');
      }
      await r.auth('finance'); await r.nav(commission);
      const view = await uiStages();
      assert.equal(view[i].canPay, true);
      assert.equal(view[i].payableId, oldPayables[i]);
      if (i === 0) assert.equal(view[1].canPay, false);
      const verified = await snapshot('review' + i);
      assert.deepEqual(verified.entries, result.snapshots.awaitingReview.entries);
      assert.equal(verified.summary.committed_cents, 25000);
      if (i === 1) { await focusProject(); await shot('05-restored'); }
      pass(check);
    }
    await r.auth('finance'); await r.nav(commission);
    if (!(await row()).stages[2].payableId) {
      await r.tap(selector('commission-release','topping'));
      await r.until(async () => !!(await row()).stages[2].payableId, '封顶应付生成');
    }
    const release = result.snapshots.released || await snapshot('released');
    assert.equal(release.summary.committed_cents, 30000);
    const ids = release.stages.map(s => String(s.payableId));
    assert.deepEqual(ids.slice(0,2), oldPayables);
    assert.equal(new Set(ids).size, 3);
    if (!result.snapshots.retried) {
      for (const stage of stages) {
        await r.api('POST','/franchise/performance/commission/release',{ projectId: project, stage: stage.stage },'recover-retry-' + project + '-' + stage.stage);
      }
    }
    const retried = result.snapshots.retried || await snapshot('retried');
    assert.deepEqual(retried.entries, release.entries);
    assert.deepEqual(retried.summary, release.summary);
    pass('重复生成三期应付，原单号、流水和费用均保持一致');
    for (let i = 0; i < 3; i++) {
      const s = (await row()).stages[i];
      if (s.outstandingCents === 0) continue;
      assert.equal(s.outstandingCents, [5000,10000,5000][i]);
      await r.nav(commission);
      await r.tap('button[data-payable="' + ids[i] + '"]');
      await r.until(async () => (await r.data()).registerForm?.payableId === ids[i], '打开原应付');
      await r.input('amount', String(s.outstandingCents/100));
      await r.input('accountName', '公司银行账户');
      await r.input('transactionNo', 'RECOVERY-COMMISSION-' + stageKeys[i]);
      if (i === 0) await shot('06-payment');
      await submit('登记付款');
      await r.click('返回项目提成');
      assert.equal((await m.currentPage()).path, 'pages/admin/commission/index');
      await r.until(async () => !(await uiStages())[i].canPay, '结清后付款入口关闭');
    }
    await focusProject(); await shot('07-settled');
    const final = await snapshot('finance');
    assert.deepEqual(final.stages.map(s => s.remaining), [0,0,0]);
    assert.deepEqual(final.stages.map(s => s.paid), [15000,10000,5000]);
    assert.deepEqual(final.entries.filter(e=>e.type==='PAYABLE').map(e=>e.id).sort(), ids.slice().sort());
    assert.equal(final.entries.filter(e=>e.type==='PAYMENT').length, 4);
    assert.equal(final.entries.filter(e=>e.type==='RECEIPT').length, 6);
    assert.equal(final.entries.filter(e=>e.type==='REFUND').length, 3);
    for (const [k,v] of Object.entries({ received_cents:330000, refunded_cents:30000, paid_cents:30000, committed_cents:30000, outstanding_cents:0, receivable_cents:0 })) assert.equal(final.summary[k],v);
    pass('分三笔付清剩余200元，每笔返回项目提成，四笔累计付款300元');
    await r.nav(book);
    assert.equal((await r.data()).bookNet,'2,700.00');
    assert.equal((await r.data()).bookCost,'300.00');
    await shot('08-finance-book');
    await r.auth('boss');
    await r.nav(home + '?view=project&id=' + project);
    assert.equal(await m.evaluate(()=>getCurrentPages().slice(-1)[0].data.projectBookNet),'2,700.00');
    await shot('09-boss-book');
    const boss = await getLedger();
    assert.deepEqual(boss.summary,final.summary);
    assert.deepEqual(boss.entries.map(e=>[String(e.id),e.type,e.amount_cents]),final.entries.map(e=>[e.id,e.type,e.cents]));
    assert.equal(stages.reduce((n,s)=>n+s.collectedCents,0),boss.summary.received_cents-boss.summary.refunded_cents);
    const ownerBook = await r.api('GET','/franchise/finance/projects/' + project + '/ledger?pageNo=1&pageSize=100');
    assert.equal(ownerBook.entries.total,ownerBook.entries.list.length);
    const obligations = ownerBook.entries.list.filter(e=>e.type==='RECEIVABLE');
    assert.deepEqual(obligations.map(e=>e.amountCents), [100000,100000,100000]);
    const keys = entries => entries.map(e=>[e.type,String(e.id),e.cents].join(':')).sort();
    const common = ownerBook.entries.list.filter(e=>e.type!=='RECEIVABLE').map(e=>({...e,cents:e.amountCents}));
    assert.deepEqual(keys(common),keys(final.entries));
    const balances = ownerBook.summary;
    assert.deepEqual([balances.receivedCents,balances.refundedCents,balances.paidCents,balances.payableCents],
      [final.summary.received_cents,final.summary.refunded_cents,final.summary.paid_cents,final.summary.committed_cents]);
    const refundLabel = await m.evaluate(()=>getCurrentPages().slice(-1)[0].data.projectLedgerSummary.find(s=>s.key==='refunded')?.value);
    assert.equal(refundLabel,'300.00');
    result.ownerBook = {summary:balances, entries:ownerBook.entries.total, contractStages:obligations.length, matched:common.length};
    pass('老板专用账本显示退款300元，额外三笔为合同应收，其他16条与财务原单逐条相符');
    pass('业务合同净收3000元，财务与老板逐笔一致，现金结余2700元、费用300元');
    result.passed = true;
    save();
  } finally { r.close(); }
}
run().then(()=>process.exit(0),error=>{console.error(error.stack);process.exit(1);});
