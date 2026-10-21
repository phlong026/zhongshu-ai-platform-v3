const cents = value => Math.round(Number(value || 0) * 100);
function total(supply) { if(supply.sourceDepotId&&Number.isInteger(supply.depotCost))return supply.depotCost;return Math.round(Number(supply.quantity || 0) * cents(supply.unitPrice)) + cents(supply.freight); }
function freight(project, material, quantity) {
  const quote = material.purchaseQuote;
  if (!quote) return 0;
  const rows = project.supplies.filter(s => s.materialId === material.id);
  const arranged = rows.reduce((n,s) => n + Number(s.quantity), 0);
  const allocated = rows.reduce((n,s) => n + cents(s.freight), 0);
  const full = cents(quote.freight);
  return (arranged + quantity >= material.quantity ? full - allocated : Math.round(full * quantity / material.quantity)) / 100;
}
module.exports = { total, freight, cents };
