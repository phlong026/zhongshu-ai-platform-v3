const http = require('../../api/http');
const api = require('../../api/franchise/engineering');
const { day } = require('../../utils/finance-date');
const { startPlanReady } = require('../../utils/platform-engineering');
const id = value => typeof value === 'number' && !Number.isSafeInteger(value) ? '' : /^[1-9]\d{0,18}$/.test(String(value || '')) ? String(value) : '';
const amount = value => value != null && Number.isSafeInteger(Number(value)) && Number(value) >= 0 ? Number(value) : null;
const money = value => amount(value) == null ? '—' : (Number(value) / 100).toFixed(2);
const label = { ON_SIGN: '签约时', BEFORE_START: '开工前', NODE_COMPLETED: '工程节点完成', ON_FINISH: '工程完工', DATE: '指定日期', MANUAL: '手动确认', LEGACY_BASE_DONE: '基础完工（原约定）', LEGACY_TOPPING: '主体封顶（原约定）' };
const field = (key, name, type = 'text') => ({ key, label: name, type, ...(type === 'textarea' ? { maxLength: 1000 } : {}) });
const revoked = error => error?.postInvalid || [401, 403, 404, 1115000000].includes(Number(error?.code));
const errorText = error => String(error?.msg || error?.message || '操作未完成，请重试');
function read(projectId, view) {
  return ['overview', 'prepare', 'start'].includes(view)
    ? http.get('/franchise/engineering/' + encodeURIComponent(projectId) + '/payment-plan') : Promise.resolve(undefined);
}
function parse(response) {
  if (!response || !Object.prototype.hasOwnProperty.call(response, 'paymentPlan') || typeof response.needsRefresh !== 'boolean'
      || typeof response.contractChanged !== 'boolean') throw Error('工程付款计划尚未确认，请重新加载');
  if (response.paymentPlan == null) return null;
  const plan = response.paymentPlan;
  if (!id(plan.contractId) || !Number.isSafeInteger(plan.version) || plan.version < 1 || !amount(plan.totalCents)
      || !Array.isArray(plan.stages) || !plan.stages.length || plan.stages.length > 20) throw Error('正式付款计划数据不完整，请重新加载');
  const rows = plan.stages.map(row => {
    if (!id(row.id) || !String(row.key || '') || amount(row.amountCents) == null) throw Error('正式付款节点数据不完整，请重新加载');
    return { ...row, id: id(row.id), key: String(row.key), label: String(row.label || row.key),
      amountText: money(row.amountCents), ratioText: row.ratioBps == null ? '' : (Number(row.ratioBps) / 100).toFixed(2) + '%',
      condition: (label[row.trigger] || '原合同约定') + (row.trigger === 'NODE_COMPLETED' ? ' · 第' + row.nodeIndex + '节点' : ''), dueDate: day(row.dueDate) };
  }).sort((a, b) => a.sequence - b.sequence);
  if (new Set(rows.map(r => r.id)).size !== rows.length || new Set(rows.map(r => r.key)).size !== rows.length
      || rows.reduce((sum, r) => sum + Number(r.amountCents), 0) !== Number(plan.totalCents)) throw Error('正式付款计划汇总不一致，请重新加载');
  return { contractId: id(plan.contractId), version: plan.version, totalText: money(plan.totalCents), rows,
    preparedVersion: response.preparedVersion, needsRefresh: response.needsRefresh, contractChanged: response.contractChanged };
}
function apply(data, bundle, response, options = {}) {
  data.paymentPlan = null; data.paymentNotice = ''; data.paymentConflict = false; data.paymentUncertain = false;
  if (options.view === 'collection') {
    const part = data.installments.find(r => r.key === data.form.stage);
    data.partLabel = part?.name || '付款节点不存在，请返回项目刷新';
    data.dunning = data.dunning.filter(r => r.stage === part?.key);
    const statuses = { PENDING: '待财务核实', RETURNED: '已退回', SUPERSEDED: '已重新提交', CONFIRMED: '已核实到账' };
    data.collectionDeclarations = (bundle.collections?.declarations || []).filter(row => row.stage === part?.key).map(row => ({
      id: id(row.id), amountText: money(row.amountCents), date: day(row.receivedDate), transactionNo: row.transactionNo,
      status: statuses[row.status] || '待核对', reason: row.returnReason, receiptId: id(row.receiptId)
    }));
    const term = bundle.collections?.stages?.find(row => row.stage === part?.key);
    if (data.own && part?.stageId && Array.isArray(bundle.collections?.declarations) && Number.isSafeInteger(term?.remainingCents)
        && Number.isSafeInteger(term.planVersion) && data.actions.some(a => a.action === 'dunning')) {
      part.remainingCents = term.remainingCents; part.planVersion = term.planVersion;
      data.fields.push(field('amount','本次到账金额（元）','digit'),field('date','实际到账日期','date'),
        field('transaction','本笔流水号'),field('file','本笔到账凭据（图片或 PDF）','file'));
      data.actions.push({ action: 'declare', label: '提交到账凭据给财务' });
    }
    if (!part) { data.fields = []; data.actions = []; }
  }
  if (response === undefined) return;
  const plan = parse(response), detail = bundle.detail, base = '/pages/build/record/index?id=' + encodeURIComponent(detail.id) + '&view=';
  data.paymentPlan = plan;
  if (response.contractChanged) {
    data.paymentNotice = '已关联的合同已变更或作废，请先核对合同。'; data.paymentConflict = true;
    if (['prepare', 'start'].includes(options.view)) { data.fields = []; data.actions = []; }
    if (!data.p.actualStart) data.next = { title: '核对工程关联的合同', url: base + 'prepare' };
  } else if (plan) {
    const balances = bundle.collections?.stages || [], early = plan.rows.filter(r => ['ON_SIGN', 'BEFORE_START'].includes(r.trigger) || !r.trigger && ['SIGN_AFTER', 'START'].includes(r.key));
    const matches = row => balances.find(b => String(b.stage) === row.key);
    const mixed = plan.rows.some(r => { const b = matches(r); return !b || b.planVersion != null && Number(b.planVersion) !== plan.version || b.expectedCents != null && Number(b.expectedCents) !== Number(r.amountCents); });
    const unpaid = early.find(r => amount(matches(r)?.confirmedCents) == null || Number(matches(r).confirmedCents) < Number(r.amountCents));
    const ready = !!detail.preparedAt && !response.needsRefresh && !mixed && !unpaid && startPlanReady(data.p.plan.nodes);
    data.paymentConflict = mixed;
    data.paymentNotice = mixed ? '付款约定与收款汇总正在更新，请重新加载。'
      : !data.p.actualStart && response.needsRefresh && detail.preparedAt ? '付款计划已有变更，请重新确认开工准备。'
      : unpaid ? '开工前待确认：' + unpaid.label : '';
    if (options.view === 'prepare') {
      if (options.reset) { data.form.note = detail.preparedNote || ''; data.form.workers = detail.preparedWorkers || ''; }
      for (const key of ['total', 'first', 'signAfter', 'baseDone', 'capDone', 'file']) delete data.form[key];
      data.form.paymentPlanVersion = plan.version;
      if (data.actions.some(a => a.action === 'prepare') && !mixed) {
        data.fields = [field('workers', '施工班组（选填）'), field('note', '现场、班组和开工准备记录', 'textarea')];
        data.actions = [{ action: 'prepare', label: '确认继承 V' + plan.version + ' 并保存准备' }];
      } else { data.fields = []; data.actions = []; }
    }
    if (options.view === 'start') {
      data.fields = []; data.actions = [];
      if (data.own && !data.p.archived && !data.p.actualStart && ready) {
        data.fields = [field('date', '实际开工日期', 'date'), field('note', '现场情况', 'textarea'), field('file', '开工凭据（jpg/png/pdf）', 'file')];
        data.actions = [{ action: 'start', label: '记录实际开工' }];
      }
    }
    if (!data.p.actualStart && !data.p.archived) {
      data.next = !detail.preparedAt || response.needsRefresh || mixed ? { title: '确认合同付款计划与开工准备', url: base + 'prepare' }
        : !startPlanReady(data.p.plan.nodes) ? { title: '填写计划开工日期', url: base + 'project' }
        : unpaid ? { title: '跟进' + unpaid.label, url: base + 'collection&stage=' + encodeURIComponent(unpaid.key) }
        : { title: '记录实际开工', url: base + 'start' };
    }
  } else {
    delete data.form.paymentPlanVersion;
    if (options.view === 'prepare') data.paymentNotice = '尚无已签正式付款计划；手工项目按原合同记录约定。';
  }
  if (options.view === 'start' && (plan || response.contractChanged)) {
    data.startBlocked = data.actions.some(a => a.action === 'start') ? '' : data.next?.title || data.paymentNotice;
    data.startPrerequisiteUrl = data.next?.url || base + 'prepare';
  }
  data.pending = (data.pending || []).map(row => row.id === 'next' && data.next ? { ...row, title: data.next.title, url: data.next.url } : row);
}
function payload(page, action, form) {
  if (action === 'declare') {
    const stage = page.data.installments.find(row => row.key === form.stage);
    const value = String(form.amount || '').trim(), transactionNo = String(form.transaction || '').trim();
    if (!stage?.collectible || !stage.stageId || stage.waitingForWork) throw Error('请核对当前收款节点');
    if (!/^\d{1,10}(?:\.\d{1,2})?$/.test(value) || Number(value) <= 0 || Math.round(Number(value) * 100) > stage.remainingCents) throw Error('本次金额须大于零且不超过该期余款');
    if (!transactionNo || !day(form.date) || !form.file?.path) throw Error('请填写到账日期、流水号并选择本笔到账凭据');
    return { stage: stage.key, stageId: stage.stageId, planVersion: stage.planVersion, amount: value, occurredAt: day(form.date), transactionNo };
  }
  if (action === 'dunning') {
    const part = page.data.installments.find(r => r.key === form.stage);
    if (!part || !part.collectible || part.waitingForWork) throw Error('本节点暂不满足催收条件，请刷新核对');
    if (form.promiseDate && !day(form.promiseDate)) throw Error('请选择有效承诺付款日期');
    return { stage: part.key, promiseDate: form.promiseDate || null, note: String(form.note || '').trim() };
  }
  const plan = page.data.paymentPlan;
  if (!plan || plan.contractChanged || page.data.paymentConflict || form.paymentPlanVersion !== plan.version) throw Error('付款计划已变化，请刷新后核对');
  const note = String(form.note || '').trim();
  if (!note || note.length > 1000) throw Error('请填写开工准备记录，不超过1000字');
  const nodes = (form.nodes || []).map(n => ({ nodeIndex: Number(n.nodeIndex ?? n.index), name: String(n.name || '').trim(), startDate: day(n.start) || null, endDate: day(n.end) || null }));
  if (!nodes.length || nodes.some((n, index) => !Number.isSafeInteger(n.nodeIndex) || n.nodeIndex < 0
      || n.nodeIndex === 0 && form.nodes[index].milestone !== 'start' || !n.name
      || form.nodes[index].start && !n.startDate || form.nodes[index].end && !n.endDate
      || !!n.startDate !== !!n.endDate || n.startDate && n.endDate < n.startDate
      || form.nodes[index].milestone === 'start' && n.startDate !== n.endDate)) throw Error('请保留原施工节点；起止日期须成对填写且结束不早于开始，计划开工只填同一天');
  if (plan.rows.some(r => r.trigger === 'NODE_COMPLETED' && !nodes.some(n => n.nodeIndex === r.nodeIndex))) throw Error('合同付款节点对应的施工节点不存在，请核对合同');
  return { paymentPlanVersion: plan.version, note, workers: String(form.workers || '').trim(), nodes };
}
function submit(page, action, form) {
  if (page.data.busy || page.__paymentPending || !page.data.actions.some(a => a.action === action)) return;
  if (page.__identity !== String(http.currentPostId()) + ':' + http.sessionGeneration() || String(page.data.p?.id) !== String(page.id)) return page.setData({ error: '工作身份或项目已变化，请重新加载' });
  try {
    const body = payload(page, action, form), guard = http.sessionGuard(), post = http.currentPostId(), projectId = String(page.id);
    page.__paymentPending = { action, body, projectId, filePath: action === 'declare' ? form.file.path : null, key: 'eng-plan-' + action + '-' + Date.now() + '-' + Math.random().toString(36).slice(2),
      alive: () => guard() && !page.__disposed && String(page.id) === projectId && String(http.currentPostId()) === String(post) };
    return retry(page);
  } catch (error) { page.setData({ error: errorText(error) }); }
}
async function retry(page) {
  const command = page.__paymentPending;
  if (!command || page.data.busy || !command.alive()) return;
  page.__paymentRead = (page.__paymentRead || 0) + 1;
  page.setData({ busy: true, paymentUncertain: false, error: '' });
  try {
    let result;
    if (command.action === 'declare') {
      if (!command.body.fileAssetId) {
        const assetId = await api.uploadVoucher(command.filePath, 'FIN_VOUCHER');
        if (!command.alive() || page.__paymentPending !== command) return;
        if (!id(assetId)) throw Error('上传凭据待核对，请重试');
        command.body = { ...command.body, fileAssetId: id(assetId) };
      }
      if (page.__inactive) throw Error('请返回工程收款继续提交已上传的凭据');
      result = await api.collections.register(command.projectId, command.body, command.key);
      if (result?.stageStatus !== 'PENDING_CONFIRMATION' || !id(result.declarationId)) throw Error('申报回执待核对，请重试');
    } else if (command.action === 'prepare') {
      result = await api.plan.prepare(command.projectId, command.body, command.key);
    } else {
      result = await api.collections.dunning(command.projectId, command.body.stage, command.body.promiseDate, command.body.note, command.key);
    }
    if (!command.alive() || page.__paymentPending !== command) return;
    if (command.action === 'prepare' && id(result?.id) !== command.projectId) throw Error('保存结果尚未确认，请重试核对');
    page.__paymentPending = null;
    page.setData({ busy: false, paymentUncertain: false, paymentConflict: false });
    wx.showToast({ title: command.action === 'declare' ? '已提交财务核实' : command.action === 'prepare' ? '开工准备已保存' : '催收已登记', icon: 'none' });
    if (!page.__inactive) return page.refreshOnline(true);
    page.initialized = false;
  } catch (error) {
    if (!command.alive() || page.__paymentPending !== command) return;
    const code = Number(error?.code), uncertain = !Number.isFinite(code) || code === -1 || code >= 500 && code < 600 || code === 1099000000;
    if (!uncertain) page.__paymentPending = null;
    if (revoked(error)) {
      page.__paymentPending = null;
      page.__paymentRead += 1;
      page.setData({ p: null, form: {}, fields: [], actions: [], docs: [], fileNames: {}, paymentPlan: null, installments: [], dunning: [], collections: [], busy: false, paymentUncertain: false, error: errorText(error) });
      return;
    }
    page.setData({ busy: false, paymentUncertain: uncertain, paymentConflict: !uncertain && /付款计划|合同已变更|合同.*作废/.test(errorText(error)),
      error: uncertain ? '保存结果尚未确认，请用原请求重试核对。' : errorText(error) });
  }
}
module.exports = { read, parse, apply, payload, submit, retry };
