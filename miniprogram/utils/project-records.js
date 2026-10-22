// Shared, version-bound project data. Missing values remain missing.
const last=rows=>rows?.[rows.length-1]||null;
const copy=x=>JSON.parse(JSON.stringify(x));
const positive=v=>Number.isFinite(Number(v))&&Number(v)>0?Number(v):null;
function basis(c){
 if(c.projectBasis)return copy(c.projectBasis);
 const quote=c.contract&&(c.quotes||[]).find(q=>q.version===c.contract.quoteVersion);
 const budget=(c.budgets||[]).find(b=>b.version===(quote?.budgetVersion||c.request?.budgetVersion))||last(c.budgets);
 const p=budget?.parameters||{},r=c.request?.basis||{},signed=c.contract?.basis||{};
 const legacy=!budget&&!c.request?.basis&&c.request?.flow!=='design-first'?last(c.measurements):null;
 return {area:positive(signed.area)||positive(quote?.area)||positive(r.area)||positive(budget?.area)||positive(legacy?.area),width:positive(signed.width)||positive(r.width)||positive(p.width)||positive(legacy?.width),depth:positive(signed.depth)||positive(r.depth)||positive(p.depth)||positive(legacy?.depth),floors:positive(signed.floors)||positive(r.floors)||positive(p.floors)||positive(c.floors),budgetVersion:quote?.budgetVersion||c.request?.budgetVersion||budget?.version||null,designVersion:c.contract?.designVersion||c.confirmation?.version||null};
}
module.exports={basis};
