const Keys = require('./storage-keys');
const TaskOrder = require('./task-order');

const value = (...values) => values.find(item => item !== undefined && item !== null && item !== '') || '';
const eventTime = row => value(row.completedRecordedAt, row.at, row.reviewDate, row.completedAt, row.receivedAt, row.date && /^\d{2}:\d{2}$/.test(row.time || '') ? row.date + 'T' + row.time + ':00+08:00' : '', row.date, row.receivedDate, row.time);
const displayDate = row => String(value(row.date, row.reviewDate, row.completedAt, row.receivedDate, row.at, row.time)).replace('T', ' ').replace(/\.\d+Z$/, '').slice(0, 16);
const person = row => String(value(row.by, row.person, row.engineer, row.actorName, row.recipient));
const fileImage = file => {
  const src = file && value(file.path, file.url, file.fileID);
  return src && (/\.(png|jpe?g|webp|gif)$/i.test(src) || file.fileID) ? src : '';
};
const record = (source, kind, row, text, extra = {}) => ({
  source,
  kind,
  date: displayDate(row),
  eventId: String(value(row.id, row.eventId)),
  eventAt: String(eventTime(row)),
  sortTime: TaskOrder.time(eventTime(row)),
  ordinal: extra.ordinal || 0,
  by: person(row),
  text,
  image: extra.image || '',
  badge: source === 'engineering' ? '施工' : '业务'
});

function businessRecords(customer) {
  const genuine = (customer.follows || []).filter(row => row.kind !== 'capture' && !String(row.note || '').startsWith('登记客资'));
  const events = (customer.events || [])
    .filter(row => !genuine.length || !['记录客户跟进', '预置客户跟进记录'].includes(row.title))
    .map((row, ordinal) => record('business', 'event', row, row.title || '项目记录', { ordinal }));
  const follows = (customer.follows || [])
    .filter(row => row.kind !== 'capture' && !String(row.note || '').startsWith('登记客资'))
    .map((row, ordinal) => record('business', 'follow', row, row.content || row.note || '已记录客户沟通', { ordinal }));
  return events.concat(follows);
}

function relatedProject(customer, engineering, actor) {
  if (!actor?.active || actor.companyId !== customer.companyId) return null;
  return (engineering.projects || []).find(project => {
    if ((project.companyId || 'quanzhou') !== customer.companyId) return false;
    const sourceCustomerId = String(project.sourceCustomerId || '');
    const customerId = String(customer.id || '');
    const projectId = String(project.projectId || '');
    const customerProjectId = String(customer.projectId || '');
    if (sourceCustomerId) {
      if (!customerId || sourceCustomerId !== customerId) return false;
      return !(projectId && customerProjectId && projectId !== customerProjectId);
    }
    return !!projectId && !!customerProjectId && projectId === customerProjectId;
  }) || null;
}

const SAFE_EVENT = /施工|开工|准备|现场|节点|验收|巡检|整改|材料|到货|取消|交付|结算|归档|移交|接收|图纸|计划/;
const PRIVATE_EVENT = /收款|付款|金额|财务|合同款|流水|报销|成本|应付|实收|实付|垫付|催收/;

function coveredEngineeringEvent(project, title) {
  return title === '记录开工准备及合同' && !!project.recordFlow?.prepared
    || title === '记录实际开工' && !!project.actualStart
    || title === '记录节点完成' && ((project.siteRecords || []).some(row => row.completion) || (project.plan?.nodes || []).some(node => node.status === '已完成'))
    || title === '记录现场情况' && !!(project.siteRecords || []).length
    || title === '确认材料到货' && !!(project.receipts || []).length
    || title === '提交材料计划' && !!(project.recordFlow?.materialPlans || []).length
    || title === '记录工程交付' && !!project.delivery
    || title === '确认项目结算' && !!project.settlement;
}

function engineeringRecords(project) {
  if (!project) return [];
  const nodes = project.plan?.nodes || [];
  const nodeName = id => nodes.find(node => node.id === id)?.name || '施工现场';
  const materials = project.materials || [];
  const supplies = project.supplies || [];
  const rows = [];
  if (project.acceptedAt || project.accepted) rows.push(record('engineering', 'accepted', { at: project.acceptedAt, date: project.accepted }, '工程资料已接收'));
  if (project.recordFlow?.prepared) rows.push(record('engineering', 'prepared', project.recordFlow.prepared, '开工准备完成'));
  if (project.actualStart) rows.push(record('engineering', 'start', project.actualStart, '工程实际开工', { image: fileImage(project.actualStart.photo) }));
  for (const [ordinal, row] of (project.siteRecords || []).entries()) rows.push(record('engineering', 'site', row, row.note || nodeName(row.nodeId) + '现场记录', { image: fileImage(row.file || row.files?.[0]), ordinal }));
  for (const [ordinal, node] of nodes.entries()) if (node.status === '已完成' && node.completedAt) rows.push(record('engineering', 'node', node, node.name + '已完成', { ordinal }));
  for (const [ordinal, row] of (project.inspections || []).entries()) rows.push(record('engineering', 'inspection', row, nodeName(row.nodeId) + '验收' + value(row.result, row.reviewStatus, '已记录'), { ordinal }));
  for (const [ordinal, row] of (project.rectifications || []).entries()) {
    const inspection = (project.inspections || []).find(item => item.id === row.inspectionId);
    rows.push(record('engineering', 'rectification', row, nodeName(inspection?.nodeId || row.nodeId) + '整改' + value(row.result, row.reviewStatus, '已记录'), { ordinal }));
  }
  for (const [ordinal, plan] of (project.recordFlow?.materialPlans || []).entries()) {
    const names = (plan.materialIds || []).map(id => materials.find(item => item.id === id)?.name).filter(Boolean);
    rows.push(record('engineering', 'material-plan', plan, '材料计划已提交' + (names.length ? ' · ' + names.join('、') : ''), { ordinal }));
  }
  for (const [ordinal, row] of (project.receipts || []).entries()) {
    const supply = supplies.find(item => item.id === row.supplyId);
    const material = materials.find(item => item.id === supply?.materialId);
    rows.push(record('engineering', 'material', row, (material?.name || '项目材料') + '到场', { ordinal }));
  }
  if (project.delivery) rows.push(record('engineering', 'delivery', project.delivery, '工程已交付' + (project.delivery.recipient ? ' · ' + project.delivery.recipient + '签收' : ''), { image: fileImage(project.delivery.file || project.delivery.ownerFile) }));
  if (project.settlement) rows.push(record('engineering', 'settlement', project.settlement, '项目结算已确认'));
  for (const [ordinal, row] of (project.events || []).entries()) {
    const title = String(row.title || '');
    if (SAFE_EVENT.test(title) && !PRIVATE_EVENT.test(title) && !coveredEngineeringEvent(project, title)) rows.push(record('engineering', 'event', row, title, { ordinal }));
  }
  return rows;
}

function sortRecords(rows) {
  const seen = new Set();
  return rows.slice().sort((a, b) => b.sortTime - a.sortTime || b.ordinal - a.ordinal || Number(b.source === 'engineering') - Number(a.source === 'engineering')).filter(row => {
    if (!row.text) return false;
    const key = row.eventId
      ? [row.source, row.kind, row.eventId].join(':')
      : row.eventAt
        ? [row.source, row.kind, row.text.replace(/\s+/g, ''), row.eventAt].join(':')
        : [row.source, row.kind, row.ordinal].join(':');
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  }).map((row, index) => ({
    ...row,
    key: [row.source, row.kind, row.sortTime, index].join(':')
  }));
}

function businessSummary(customer, business) {
  const genuine = (customer.follows || []).filter(row => row.kind !== 'capture' && !String(row.note || '').startsWith('登记客资'));
  let journeyIndex = genuine.length ? 1 : 0;
  const SF = require('./sales-flow');
  if (customer.acceptance) journeyIndex = 5;
  else if (SF.is(customer)) journeyIndex = customer.businessFlow?.completed && customer.businessFlow.feedbacks?.at(-1)?.status !== 'pending' ? 4 : 3;
  else if (customer.request || customer.contract) journeyIndex = 3;
  else if ((customer.plans || []).length || (customer.facades || []).length || (customer.budgets || []).length || customer.salesIntent?.ready) journeyIndex = 2;
  const latest = sortRecords(business)[0] || null;
  if (journeyIndex >= 3) {
    const next = require('./design-coordination').next(customer);
    return { journeyIndex, title: next.state, owner: next.owner, next: '', latest };
  }
  return {
    journeyIndex,
    title: ['待首次沟通', '需求沟通中', '方案预算已出'][journeyIndex],
    owner: customer.owner || '',
    next: journeyIndex < 2 ? '出方案与预算' : '发起设计委托',
    latest
  };
}

function build(customer, engineering = wx.getStorageSync(Keys.engineering) || {}, actor = require('./access').context()) {
  const project = relatedProject(customer, engineering, actor);
  const business = businessRecords(customer);
  const fallback = businessSummary(customer, business);
  if (!project) return { engineering: false, delivered: false, records: sortRecords(business), ...fallback };
  const construction = engineeringRecords(project);
  const records = sortRecords(business.concat(construction));
  const constructionRecords = sortRecords(construction);
  const nodes = project.plan?.nodes || [];
  const current = nodes.find(node => node.status !== '已完成');
  const delivered = !!(project.delivery || project.archived);
  return {
    engineering: true,
    delivered,
    journeyIndex: delivered ? 7 : 5,
    title: delivered ? '工程已交付' : project.actualStart ? (current ? current.name + ' · 进行中' : '主体施工已完成') : '工程待开工',
    owner: project.assignedEngineerName || project.manager || project.actualStart?.engineer || '',
    next: delivered ? '' : project.actualStart ? (current ? current.name + '验收' : '工程交付') : '登记实际开工',
    latest: constructionRecords[0] || fallback.latest,
    records
  };
}

module.exports = { build };
