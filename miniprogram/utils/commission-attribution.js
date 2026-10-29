// Responsibility is captured when the commission occurs, separately from its current custodian.
function evidence(customer) {
  const order = customer.serviceOrders?.at(-1);
  const stamped = order?.responsibleId || customer.businessFlow?.responsibleId;
  if (stamped) return { person: stamped, source: '委托发生时责任记录' };
  const submitted = Date.parse(customer.businessFlow?.submittedAt || '');
  const transfer = (customer.responsibilityHistory || [])
    .filter(row => row.from && Number.isFinite(submitted) && Date.parse(row.effectiveAt || row.at || '') >= submitted)
    .sort((left, right) => Date.parse(left.effectiveAt || left.at) - Date.parse(right.effectiveAt || right.at))[0];
  return transfer ? { person: transfer.from, source: '委托后首次责任转交记录' } : { person: '', source: '责任证据待核对' };
}

const owner = customer => evidence(customer).person;
module.exports = { evidence, owner };
