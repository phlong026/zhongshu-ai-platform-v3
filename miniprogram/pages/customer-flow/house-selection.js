const { day } = require('../../utils/finance-date');

const CUSTOM = 'EXTERNAL_CUSTOM';
const options = [{ value: '', label: '标准户型或外部资料' }, { value: CUSTOM, label: '外部定制' }];
const isCustom = form => form?.fields?.selectionMode === CUSTOM;

function fields(definition, form, writing = false) {
  if (definition?.code !== 'HOUSE_SELECTION') return definition?.fields || [];
  if (isCustom(form)) {
    return definition.fields.filter(field => ['selectionMode', 'customDirection', 'confirmed'].includes(field.key))
      .map(field => field.key === 'confirmed' ? { ...field, label: '客户已确认定制方向' } : field);
  }
  return definition.fields.filter(field => field.key !== 'customDirection' && (!writing || field.key !== 'selectionMode' || form.explicitStandardSelection === true));
}

function update(previous, next) {
  if (previous.fields.selectionMode !== next.fields.selectionMode) {
    // Only a deliberate switch may clear an external custom direction already stored by the server.
    next.explicitStandardSelection = !isCustom(next);
    Object.assign(next.fields, { referenceCaseId: '', referenceCaseName: '', selectionResult: '', versionLabel: '', customDirection: '', confirmed: false });
    if (previous.fields.confirmed) next.occurredOn = '';
    return;
  }
  if (!isCustom(next)) return;
  if (previous.fields.confirmed && previous.fields.customDirection.trim() !== next.fields.customDirection.trim()) {
    next.fields.confirmed = false;
    next.occurredOn = '';
  } else if (!previous.fields.confirmed && next.fields.confirmed && !next.occurredOn) {
    next.occurredOn = day(Date.now());
  }
}

module.exports = { options, isCustom, fields, update };
