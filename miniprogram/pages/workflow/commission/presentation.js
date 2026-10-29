const Files = require('../../../utils/sample-files');
const Checkout = require('../../../utils/design-checkout');
const Access = require('../../../utils/access');
const imageRoot = '/pages/workflow/assets/';
const money = require('../../../utils/money').yuan;
const specs = [
  { key: 'resultFile', label: '设计成果', name: '正式设计成果.pdf', kind: 'result', image: imageRoot + 'biz-plan-result.jpg' },
  { key: 'drawingFile', label: '施工图纸', name: '施工图纸.pdf', kind: 'drawing', image: imageRoot + 'biz-plan-1.jpg' },
  { key: 'reviewFile', label: '专业审核', name: '专业审核记录.pdf', kind: 'review', image: '' }
];
function document(customer, key, version, date) {
  const actor = Access.context();
  const spec = specs.find(row => row.key === key) || { key, label: key === 'confirmationFile' ? '客户确认' : '需求资料', name: key === 'confirmationFile' ? '客户成果确认书.pdf' : '设计需求资料.pdf', kind: key === 'confirmationFile' ? 'confirmation' : 'brief' };
  return { ...Files.reference({ name: spec.name, customer: customer.name, customerId: customer.id, projectId: customer.projectId, project: customer.name + '乡墅', version, sourceParty: key === 'confirmationFile' ? customer.owner : actor.name, confirmationParty: key === 'confirmationFile' ? customer.name : '', purpose: spec.label, details: ['图纸版本：' + version, '登记日期：' + (date || require('../../../utils/account').localDate()), '建筑面积：' + (customer.request?.basis?.area || customer.budgets?.at(-1)?.area || '') + '㎡'] }), previewKind: spec.kind, previewCustomer: customer.name, previewArea: customer.request?.basis?.area || customer.budgets?.at(-1)?.area || 0 };
}
function bundle(customer, version) {
  return Object.fromEntries(specs.map(spec => [spec.key, document(customer, spec.key, version)]).concat([['drawingVersion', version]]));
}
function cards(customer, form) {
  const source = { ...customer.businessFlow?.completed?.files, ...customer.businessFlow?.dispatch, ...customer.businessFlow?.packet, ...form };
  const currentVersion = source.drawingVersion || 'V' + (customer.request?.version || 1);
  return specs.map(spec => ({ ...spec, version: currentVersion, file: source[spec.key] || null, ready: !!source[spec.key] && (!source[spec.key].text || String(source[spec.key].text).includes('版本：' + currentVersion)) }));
}
function preview(file, customer, key) {
  const kind = file.previewKind || specs.find(row => row.key === key)?.kind || (key === 'confirmationFile' ? 'confirmation' : 'brief');
  const spec = specs.find(row => row.kind === kind);
  return { ...file, kind, image: spec?.image || '', customer: customer.name, city: customer.city, projectId: customer.projectId, version: file.meta?.version || 'V' + (customer.request?.version || 1), area: customer.request?.basis?.area || customer.budgets?.at(-1)?.area || '', scope: customer.request?.scope || '', sourceParty: file.meta?.sourceParty || customer.owner || '', confirmationParty: file.meta?.confirmationParty || customer.name };
}
function build(customer, view, form, quote, next, actor) {
  const flow = customer.businessFlow || {}, payment = Checkout.order(customer), paid = Checkout.paid(customer);
  const basis = customer.request?.basis || require('../../../utils/design-coordination').basis(customer) || quote || {};
  const step = !customer.request ? 0 : !paid ? 1 : !flow.dispatch ? 2 : !customer.acceptance ? 3 : 4;
  const files = cards(customer, form), documentCount = files.filter(row => row.ready).length;
  let engineering = null;
  if (customer.acceptance && ['engineer', 'project-manager'].includes(actor.role)) engineering = (wx.getStorageSync('zhongshu-engineering-v1')?.projects || []).find(row => row.sourceCustomerId === customer.id && Access.project(row, actor));
  const state = customer.acceptance ? ['项目已创建', '工程已接收本版资料', '工程负责人'] : customer.handover ? ['工程待接收', '完整资料包已提交', customer.handover.manager] : flow.dispatch ? ['设计成果已下发', '待确认成果并移交工程', customer.owner] : flow.completed ? ['设计已完成', '待下发本版成果', '总部设计'] : paid ? ['设计进行中', '总部设计已收到任务', '总部设计'] : ['订单待支付', '支付后开始设计', customer.owner];
  return {
    step, steps: ['设计委托', '支付费用', '设计成果', '移交工程'].map((label, index) => ({ label, index: index + 1, done: index < step, active: index === Math.min(step, 3) })),
    name: customer.name, city: customer.city, owner: customer.owner || '', projectId: customer.projectId,
    area: basis.area || 0, floors: basis.floors || customer.request?.basis?.floors || '', width: customer.request?.basis?.width || '', depth: customer.request?.basis?.depth || '',
    amount: money((payment?.amountCents || quote?.amountCents || 0) / 100), rate: money((payment?.rateCents || quote?.rateCents || 0) / 100),
    scope: customer.request?.scope || quote?.scope || '', recipient: payment?.recipient || quote?.recipient || '众墅之家总部',
    demand: customer.request?.text || customer.summary || '', version: form.drawingVersion || flow.dispatch?.drawingVersion || flow.completed?.files?.drawingVersion || 'V' + (customer.request?.version || 1),
    orderNumber: payment ? 'DS' + String(payment.createdAt || '').replace(/\D/g, '').slice(0, 14) + customer.id.slice(-4).toUpperCase() : '',
    paymentMethod: payment?.paymentMethod || (payment?.channel==='alipay'?'余额':'零钱'), brief: customer.handover?.note || flow.packet?.note || form.note || '', channel: payment?.channel || form.channel || 'wechat', channelName: payment?.channel === 'alipay' ? '支付宝' : '微信支付', paid, paidAt: payment?.paidAt ? require('../../../utils/account').displayTime(payment.paidAt) : '',
    files, documentCount, allFilesReady: documentCount === 3, state: state[0], subtitle: state[1], nextOwner: state[2],
    resultImage: imageRoot + 'biz-plan-result.jpg', attachment: form.attachment || customer.request?.attachment || null,
    confirmation: form.confirmationFile || flow.packet?.confirmationFile || null,
    engineeringId: engineering?.id || '', nextView: next.view, readOnly: !['submit', 'pay', 'complete', 'dispatch', 'handover', 'receive'].includes(view)
  };
}
module.exports = { build, bundle, document, preview };
