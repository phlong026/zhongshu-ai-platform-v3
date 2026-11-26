function apply(view, bundle) {
  if (!view.plan) return;
  const sources = bundle.materials?.plans?.find(plan => String(plan.materialPlanId) === view.plan.id)?.lines || [];
  const indexed = new Map(sources.map(line => [String(line.materialId), line]));
  view.plan = { ...view.plan, lines: view.plan.lines.map(line => {
    const source = indexed.get(line.id);
    const available = source?.availableQuantity != null && source.consumedQuantity != null
      && Array.isArray(source.movements) && Array.isArray(source.pendingReships);
    return { ...line, flowUrl: available ? '/pages/build/material-flow/index?project=' + encodeURIComponent(bundle.detail.id)
      + '&plan=' + encodeURIComponent(view.plan.id) + '&material=' + encodeURIComponent(line.id) : '' };
  }) };
  const index = view.fields.findIndex(field => field.key === 'actual');
  if (index >= 0) {
    view.fields.splice(index, 0, { key: 'batchExpected', label: '本批应到数量（短货时填写）', type: 'digit' });
    view.fields.splice(index + 2, 0, { key: 'quality', label: '质检结论', type: 'quality', options: ['合格','不合格'] });
  }
}

function expected(raw) {
  const text = String(raw ?? '').trim();
  if (!text) return undefined;
  const value = Number(text);
  if (!/^\d+(\.\d{1,3})?$/.test(text) || value <= 0 || value > 10000000) {
    throw { code: 0, msg: '本批应到数量须大于0、最多三位小数，且不超过一千万' };
  }
  return value;
}
module.exports = { apply, expected };
