const api = require('./api');
const M = require('./model');
const LABELS = {
  requests: { entrustedOn: '委托日期', providerName: '外部设计方', providerReference: '外部单号', text: '设计需求', scope: '设计范围', area: '建筑面积（平方米）', floors: '层数', reason: '变更说明' },
  results: { deliveredOn: '交付日期', drawingVersion: '图纸版本', requestVersion: '对应委托版本', note: '成果说明' }
};
const TRIGGERS = { ON_SIGN: '合同签约', BEFORE_START: '开工前', NODE_COMPLETED: '工程节点完成', ON_FINISH: '工程完工', DATE: '指定日期', MANUAL: '手动确认', LEGACY_BASE_DONE: '基础完工（原约定）', LEGACY_TOPPING: '主体封顶（原约定）' };

function versions(rows, customerId, kind, register) {
  if (!LABELS[kind] || !Array.isArray(rows)) throw Error('委托版本数据不完整');
  return rows.map(row => {
    if (!Number.isInteger(row.version) || row.version < 1 || row.version > 2147483647 || !row.data || typeof row.data !== 'object') throw Error('委托版本无效，请刷新重试');
    const data = row.data, attachments = row.attachments || data.attachments || [];
    if (!Array.isArray(attachments)) throw Error('委托附件格式不正确');
    const plan = row.paymentPlan;
    if (plan && !Array.isArray(plan.stages)) throw Error('委托付款约定格式不正确');
    return {
      key: kind + ':' + row.version, version: row.version,
      title: (kind === 'requests' ? '委托需求' : '外部成果') + ' V' + row.version,
      date: M.text(row.createdAt).replace('T', ' '), actor: M.text(row.actorName),
      fields: Object.entries(LABELS[kind]).filter(([key]) => data[key] != null && data[key] !== '').map(([key, label]) => ({ label, value: M.text(data[key]) })),
      files: attachments.map(file => register({ type: 'external', id: api.id(customerId), kind, version: row.version, assetId: api.id(file.assetId) }, file.title || '委托附件')),
      payment: plan ? {
        source: plan.source === 'CONTRACT' ? '合同约定' : '客户付款草案', version: M.text(plan.source === 'CONTRACT' ? plan.contractPlanVersion : plan.revision), total: M.money(plan.totalCents),
        stages: plan.stages.map((stage, i) => ({ key: String(i), label: M.text(stage.label), amount: M.money(stage.amountCents),
          ratio: stage.ratioBps == null ? '' : Number(stage.ratioBps) / 100 + '%', trigger: TRIGGERS[stage.trigger] || M.text(stage.trigger),
          date: stage.dueDate || '', node: stage.trigger === 'NODE_COMPLETED' ? M.text(stage.nodeIndex) : '' }))
      } : null
    };
  });
}
module.exports = { versions };
