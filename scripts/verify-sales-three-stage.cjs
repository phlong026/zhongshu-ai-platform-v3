#!/usr/bin/env node
/**
 * M06-T02 三期分笔收款 v3 接线验证（role-page-runtime 真实驱动 workflow 页）。
 * 覆盖：
 *  1. 三期创建表单：Σ校验失败提示 / 合法提交 body={customerId,totalCents,signAfterCents,baseDoneCents,capDoneCents}
 *  2. 合同卡渲染：stageSummary 行映射（stageText=签约后/基础完成后/封顶后，部分已收/已收满，累计/余额）
 *  3. 分笔 collect：amountCents 入 body；金额必填校验；到账日期必填校验
 *  4. 可再收判定：PENDING/OPEN（余额>0）显示办理按钮，SETTLED 隐藏
 * 用法：node scripts/verify-sales-three-stage.cjs
 */
const assert = require('node:assert/strict');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  let pass = 0, fail = 0;
  const ok = (cond, name) => { if (cond) { pass++; console.log('  ok - ' + name); } else { fail++; console.log('  FAIL - ' + name); } };
  const wait = ms => new Promise(r => setTimeout(r, ms || 15));

  const sent = [];
  let responder = o => ({ statusCode: 200, data: { code: 0, data: [] } });
  global.wx.request = options => {
    sent.push(options);
    const r = responder(options);
    if (r) options.success(r);
    else options.fail({ errMsg: 'request:fail no-responder ' + options.url });
  };
  const toasts = [];
  global.wx.showToast = t => { toasts.push(t && t.title); };

  const http = require('../miniprogram/api/http');
  const auth = require('../miniprogram/api/auth');
  const PF = require('../miniprogram/utils/platform-crm');
  const Account = require('../miniprogram/utils/account');

  Account.mutate(s => Account.loginDemo(s, 'sales-dual'));
  auth.saveLogin({ accessToken: 'tk-3stage', posts: [] });
  auth.savePosts([{ postId: 701, userId: 9101, roleCode: 'sales', orgId: 9, orgType: 'store' }]);
  http.saveSession({ postId: 701 });

  const data = () => { const pgs = getCurrentPages(); return pgs.length ? pgs[pgs.length - 1] : null; };

  // ========== 1. 纯函数层：collectError 三态 ==========
  console.log('[unit] collectError 三期分笔校验');
  ok(PF.collectError({ method: 'bank', transactionNo: '', amount: 100, receivedDate: '2026-11-01' }) !== '', '银行无流水号拒绝');
  ok(PF.collectError({ method: 'bank', transactionNo: 'TX1', amount: 0, receivedDate: '2026-11-01' }) !== '', '金额 0 拒绝');
  ok(PF.collectError({ method: 'bank', transactionNo: 'TX1', amount: 100, receivedDate: '' }) !== '', '到账日期空拒绝');
  ok(PF.collectError({ method: 'bank', transactionNo: 'TX1', amount: 100, receivedDate: '2099-01-01' }) !== '', '到账日期晚于今天拒绝');
  ok(PF.collectError({ method: 'cash', transactionNo: '', amount: 0.001, receivedDate: '2026-11-01' }) !== '', '超两位小数金额拒绝（toCents 一致性，R1）');
  ok(PF.collectError({ method: 'cash', transactionNo: '', amount: 0.01, receivedDate: '2026-11-01' }) === '', '合法现金分笔通过');

  // ========== 2. 纯函数层：contractRow 三期映射 ==========
  console.log('[unit] contractRow 三期 summary 行映射');
  const row = PF.contractRow({
    contract: { id: 900, status: 'SIGNED', totalCents: 68000000, revision: 1, signedDate: '2026-11-01' },
    stages: [
      { stageId: 1, stage: 'SIGN_AFTER', label: '签约后', amountCents: 40000000, collectedCents: 25000000, balanceCents: 15000000, status: 'OPEN' },
      { stageId: 2, stage: 'BASE_DONE', label: '基础完成后', amountCents: 18000000, collectedCents: 0, balanceCents: 18000000, status: 'PENDING' },
      { stageId: 3, stage: 'CAP_DONE', label: '封顶后', amountCents: 10000000, collectedCents: 10000000, balanceCents: 0, status: 'SETTLED' },
      { stageId: 4, stage: 'CAP_DONE', label: '未约定', amountCents: 0, collectedCents: 0, balanceCents: 0, status: 'PENDING' }
    ]
  });
  ok(row.stages[0].stageText === '签约后' && row.stages[1].stageText === '基础完成后' && row.stages[2].stageText === '封顶后', '三期中文文案');
  ok(row.stages[0].statusText === '部分已收' && row.stages[2].statusText === '已收满', 'OPEN/SETTLED 状态文案');
  ok(row.stages[0].owing === true && row.stages[1].owing === true && row.stages[2].owing === false, '可再收判定：余额>0 可收，SETTLED/零额不可收（R1 冻结 summary 依赖）');
  const legacyRow = PF.contractRow({ contract: { id: 1, status: 'SIGNED' }, stages: [{ stageId: 9, stage: 'SIGN_AFTER', amountCents: 100, status: 'PENDING' }] });
  ok(legacyRow.stages[0].owing === false, '缺 balanceCents（部署不同步旧结构）保守不可收');
  ok(row.stages[0].collectedText === '250000.00' && row.stages[0].balanceText === '150000.00', '累计/余额金额渲染');

  // ========== 3. 页面级：三期创建表单提交 body ==========
  console.log('[page] 三期创建表单');
  responder = o => {
    if (String(o.url).includes('/sales/contracts') && o.method === 'POST') {
      return { statusCode: 200, data: { code: 0, data: { contractId: 900, status: 'WAIT_SIGN' } } };
    }
    return { statusCode: 200, data: { code: 0, data: [] } };
  };
  await runtime.callWxMethod('reLaunch', { url: '/pages/workflow/index?view=contract' });
  await wait(50);
  const page = data();
  await runtime.evaluate(() => {
    const pg = getCurrentPages().at(-1);
    pg.id = 'cust-1';
    pg.setData({ latestRequirementId: 55, salesQuotes: [{ totalText: '680000' }], customerNameText: '陈先生' });
    pg.openContractForm();
  });
  await wait(20);
  const form0 = page.data.contractForm;
  ok(form0 && form0.signAfter === '680000' && form0.baseDone === '0' && form0.capDone === '0', '三期表单初始化（签约后预填报价，其余 0）');
  await runtime.evaluate(() => {
    const pg = getCurrentPages().at(-1);
    pg.setData({ contractForm: { total: '680000', signAfter: '400000', baseDone: '180000', capDone: '100000', signedDate: '' } });
    pg.platformSubmitContract();
  });
  await wait(50);
  const createReq = sent.filter(o => String(o.url).includes('/sales/contracts') && o.method === 'POST').pop();
  ok(!!createReq && createReq.data.signAfterCents === 40000000 && createReq.data.baseDoneCents === 18000000
    && createReq.data.capDoneCents === 10000000 && createReq.data.totalCents === 68000000
    && !('firstCents' in createReq.data), '创建 body 三期字段（无 firstCents 残留）');

  // Σ≠总额前端拦截
  sent.length = 0;
  await runtime.evaluate(() => {
    const pg = getCurrentPages().at(-1);
    pg.setData({ contractForm: { total: '700000', signAfter: '400000', baseDone: '180000', capDone: '100000', signedDate: '' }, busy: false });
    pg.platformSubmitContract();
  });
  await wait(20);
  ok((page.data.contractError || '').includes('三期金额之和'), 'Σ≠总额前端拦截文案');
  ok(!sent.some(o => o.method === 'POST'), 'Σ≠总额不发请求');

  // ========== 4. 页面级：分笔 collect ==========
  console.log('[page] 分笔办理回款');
  sent.length = 0;
  responder = o => {
    if (String(o.url).includes('/collect')) {
      return { statusCode: 200, data: { code: 0, data: { receiptId: 5001, stageStatus: 'OPEN', collectedCents: 25000000, replayed: false } } };
    }
    return { statusCode: 200, data: { code: 0, data: [] } };
  };
  // R1：注入真实渲染源 salesContracts（此前注入 progressContracts 掩盖了取错数据源的真 bug）
  await runtime.evaluate(() => {
    const pg = getCurrentPages().at(-1);
    pg.setData({
      canCollect: true,
      salesContracts: [{
        id: '900', status: 'SIGNED', statusText: '已签订', totalText: '680000.00',
        stages: [
          { id: '1', stage: 'SIGN_AFTER', stageText: '签约后', amountText: '400000.00', collectedText: '0.00', balanceText: '400000.00', status: 'PENDING', statusText: '待回款', owing: true },
          { id: '3', stage: 'CAP_DONE', stageText: '封顶后', amountText: '100000.00', collectedText: '100000.00', balanceText: '0.00', status: 'SETTLED', statusText: '已收满', owing: false }
        ]
      }]
    });
    pg.platCollect({ currentTarget: { dataset: { stageId: '1' } } });
  });
  await wait(20);
  ok((page.data.collectStageText || '').includes('签约后') && (page.data.collectStageText || '').includes('剩余可收'), 'collect modal 带期名+剩余可收额（salesContracts 渲染源）');
  ok((page.data.collectStageText || '').includes('400000'), '剩余可收额非 0（R1 取错源回归：¥0 假绿）');
  ok(Number((page.data.collectForm || {}).amount) === 400000, '分笔金额默认预填剩余额');
  await runtime.evaluate(() => {
    const pg = getCurrentPages().at(-1);
    pg.setData({ collectForm: { amount: '250000', method: 'bank', transactionNo: 'TX9001', receivedDate: '2026-11-03' }, busy: false });
    pg.platCollectSubmit();
  });
  await wait(50);
  const collectReq = sent.filter(o => String(o.url).includes('/collect')).pop();
  ok(!!collectReq && collectReq.data.amountCents === 25000000, 'collect body amountCents 分笔金额');
  ok(!!collectReq && collectReq.data.receivedDate === '2026-11-03', '到账日期必填传递');

  // R2：WXML 渲染复核——期行绑定余额文本（约定 400000/已收 250000/余 150000）
  await runtime.evaluate(() => {
    const pg = getCurrentPages().at(-1);
    pg.setData({ collectForm: { amount: '250000', method: 'bank', transactionNo: 'TX9002', receivedDate: '2026-11-03' }, busy: false, error: '' });
    pg.platCollectSubmit();
  });
  await wait(30);
  // 金额空校验不发请求
  sent.length = 0;
  await runtime.evaluate(() => {
    const pg = getCurrentPages().at(-1);
    pg.setData({ modal: 'collect', busy: false, collectForm: { amount: '', method: 'bank', transactionNo: 'TX1', receivedDate: '2026-11-03' } });
    pg.platCollectSubmit();
  });
  await wait(20);
  ok((page.data.error || '').includes('本次回款金额'), '金额空前端拦截');
  ok(!sent.some(o => String(o.url).includes('/collect')), '金额空不发请求');

  // ===== R2 渲染复核（放最后：reLaunch 换页，避免破坏前序页面引用）=====
  // 渲染复核需要真实 WXML——reLaunch 合同视图并注入 summary 数据源
  responder = o => {
    const u = String(o.url);
    if (u.includes('/sales/contracts') && o.method === 'GET') return { statusCode: 200, data: { code: 0, data: [{
      contract: { id: 900, customerId: 'cust-1', status: 'SIGNED', totalCents: 68000000, revision: 1 },
      stages: [{ stageId: 1, stage: 'SIGN_AFTER', label: '签约后', amountCents: 40000000, collectedCents: 25000000, balanceCents: 15000000, status: 'OPEN' }]
    }] } };
    return { statusCode: 200, data: { code: 0, data: [] } };
  };
  await runtime.callWxMethod('reLaunch', { url: '/pages/workflow/index?view=contract&id=cust-1' });
  await wait(50);
  // runtime.screenshot 落盘（无返回值）：从文件读 WXML 渲染文本
  const os = require('node:os');
  const fs = require('node:fs');
  const renderFile = os.tmpdir() + '/sales-3stage-render-' + Date.now() + '.json';
  // 合同分支外层有 role 等条件：渲染复核前补齐页面上下文（与 loadPlatformContract 后状态等价）
  await runtime.evaluate(() => {
    const pg = getCurrentPages().at(-1);
    // 顶层条件 !customer&&view!=='customers' → 需带客户上下文（与真实进入合同页等价）
    pg.setData({ role: '业务员', canCollect: true, salesQuotes: [], latestRequirementId: 55, busy: false,
      customer: { id: 'cust-1', name: '陈先生' }, customerNameText: '陈先生' });
  });
  await wait(20);
  await runtime.screenshot({ path: renderFile });
  const renderedText = JSON.parse(fs.readFileSync(renderFile, 'utf8')).renderedText || '';
  if (process.env.DBG_RENDER) {
    console.log('[DBG] view=' + data().data.view + ' len=' + renderedText.length);
    console.log('[DBG] lastGET=', JSON.stringify(sent.filter(o => o.method === 'GET' && String(o.url).includes('/sales/contracts')).slice(-1).map(o => o.url)));
    console.log('[DBG] salesContracts=', JSON.stringify((data().data.salesContracts || []).map(c => ({ id: c.id, n: (c.stages || []).length }))));
    console.log('[DBG] contractsError=', data().data.contractsError);
    const dd = data().data;
    console.log('[DBG] topFields=', JSON.stringify({ role: dd.role, pageMode: dd.pageMode, canRegister: dd.canRegister, platformMode: dd.platformMode, salesQuotes: (dd.salesQuotes || []).length }));
  }
  ok(renderedText.includes('已收 250000.00'), 'WXML 期行渲染累计（R2 渲染断言）');
  ok(renderedText.includes('余 150000.00'), 'WXML 期行渲染余额（R2 渲染断言）');

  console.log(`\n通过 ${pass}，失败 ${fail}`);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('脚本异常:', e && e.message, '\n', (e && e.stack || '').split('\n')[1]); process.exit(1); });
