const { day } = require('../../utils/finance-date');
const id = value => typeof value === 'number' && !Number.isSafeInteger(value) ? '' : /^[1-9]\d{0,18}$/.test(String(value || '')) ? String(value) : '';
const canWrite = post => !!post && post.orgType === 'store' && ['sales', 'sales-lead', 'boss'].includes(post.roleCode)
  && (!Array.isArray(post.permissions) || post.permissions.includes('franchise:sale:write'));
function detail(value, contractId, companyId, post) {
  const contract = value?.contract;
  if (!contract || id(contract.id) !== contractId || id(contract.companyId) !== companyId || !id(contract.customerId)
      || !Number.isSafeInteger(contract.revision) || contract.revision < 0
      || !Number.isSafeInteger(Number(contract.totalCents)) || Number(contract.totalCents) < 0) throw Error('合同响应不匹配，请重新加载');
  const status = String(contract.status || ''), signedDate = day(contract.signedDate), assetId = id(contract.assetId);
  return { contractId, customerId: id(contract.customerId), customerName: String(value.customerName || ''),
    revision: contract.revision, status, statusLabel: { WAIT_SIGN: '待签订', SIGNED: '已签订', VOIDED: '已作废' }[status] || '待核对',
    totalText: (Number(contract.totalCents) / 100).toFixed(2), signedDate, assetId,
    canWrite: canWrite(post) && ['WAIT_SIGN', 'SIGNED'].includes(status),
    hasEvidence: !!signedDate && !!assetId };
}
const form = context => ({ signedDate: context.signedDate, assetId: context.assetId, fileName: context.assetId ? '当前合同文件' : '', reason: '' });
function payload(value, context, today) {
  if (!context?.canWrite || !['WAIT_SIGN', 'SIGNED'].includes(context.status)) throw Error('当前合同不可办理签约或补证');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value.signedDate) || day(value.signedDate) !== value.signedDate) throw Error('请选择实际签约日期');
  if (!day(today) || value.signedDate > today) throw Error('签约日期不能晚于今天');
  if (!id(value.assetId)) throw Error('请上传已签合同文件');
  const body = { revision: context.revision, signedDate: value.signedDate, assetId: id(value.assetId) };
  if (context.status === 'SIGNED') {
    const reason = String(value.reason || '').trim();
    if (!reason || reason.length > 500) throw Error('请填写补录或更正原因，不超过500字');
    body.reason = reason;
  }
  return body;
}
module.exports = { id, canWrite, detail, form, payload };
