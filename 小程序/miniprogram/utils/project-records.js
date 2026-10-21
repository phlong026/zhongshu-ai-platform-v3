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
function quoteBudget(c){return c.request?.flow==='design-first'?(c.budgets||[]).find(b=>b.version===c.request.budgetVersion):last(c.budgets);}
function quoteDefaults(c){
 const q=last(c.quotes),b=quoteBudget(c),returned=q&&require('./commercial-approval').latest(c,'quote')?.status==='returned';
 const source=returned?q:b||{};
 return {quoteMain:String(source.main??''),quoteExterior:String(source.exterior??''),quoteOther:String(returned?source.other||0:0),quoteReason:returned?source.reason||'':'',quoteScope:source.scope||'主体工程及已选外装项目，不含室内装修',__quoteBasis:JSON.stringify([b?.version,q?.version,c.release?.signature])};
}
function cents(v){if(!/^\d+(\.\d{1,2})?$/.test(String(v))||Number(v)>100000000)throw Error('请填写有效金额，最多两位小数且不超过一亿元。');return Math.round(Number(v)*100);}
function quoteTotal(f){return ['quoteMain','quoteExterior','quoteOther'].reduce((n,key)=>n+cents(f[key]),0)/100;}
function quotation(c,f,date){
 const b=quoteBudget(c);if(!b||!positive(b.area))throw Error('请先保存并核对本次设计委托关联的有效参考预算。');
 if(!require('./design-coordination').confirmed(c))throw Error('请先完成正式设计下发、收图及客户签认。');
 const main=cents(f.quoteMain),exterior=cents(f.quoteExterior),other=cents(f.quoteOther),total=main+exterior+other;
 if(!total||total>10000000000)throw Error('建造报价总额须大于零且不超过一亿元。');
 if(!String(f.quoteScope||'').trim())throw Error('请填写本次报价包含和不包含的范围。');
 if(total!==Math.round(b.total*100)&&!String(f.quoteReason||'').trim())throw Error('报价与参考预算不同，请说明分项或计价范围调整。');
 return {version:(c.quotes||[]).length+1,budgetVersion:b.version,planVersion:last(c.plans)?.version||null,facadeVersion:last(c.facades)?.version||null,designVersion:c.release?.version||null,designSignature:c.release?.signature||null,total:total/100,main:main/100,exterior:exterior/100,other:other/100,area:Number(b.area),unitPrice:Math.round(main/Number(b.area))/100,scope:f.quoteScope.trim(),reason:String(f.quoteReason||'').trim(),basis:copy(basis(c)),priceVersion:b.priceVersion||'',sample:!!b.sample||String(b.priceVersion||'').startsWith('demo'),date};
}
function leaders(companyId){return (require('./access').rawBook(companyId)?.staff||[]).filter(p=>p.active!==false&&(p.roles||[p.roleId]).includes('project-manager'));}
function drawingReview(c){const d=last(c.drawings);return d&&last((c.reviews||[]).filter(r=>r.drawingVersion===d.version));}
function packageRows(c){
 if(require('./sales-flow').is(c))return require('./sales-flow').packageRows(c);
 const q=c.contract&&(c.quotes||[]).find(q=>q.version===c.contract.quoteVersion),d=last(c.drawings),r=drawingReview(c),confirm=c.confirmationEvidence||c.confirmation;
 const copied=d&&(c.copies||[]).find(x=>x.version===d.version);
 return [
  {key:'quote',label:'已批准建造报价',version:q?'V'+q.version:'',ready:!!q&&require('./commercial-approval').approved(c,'quote'),file:q?{name:'建造报价 V'+q.version+' 摘要',text:'计价面积 '+q.area+'㎡\n主体工程 '+q.main+'元\n外装工程 '+q.exterior+'元\n其他约定 '+(q.other||0)+'元\n合计 '+q.total+'元\n'+(q.scope||'详见原报价约定')}:null,view:'quote',summary:q?'￥'+q.total.toLocaleString('zh-CN'):''},
  {key:'signed',label:'双方已签建造合同',version:c.contract?'V'+c.contract.version:'',ready:!!c.contract&&!!(c.contract.file||c.signedFile),file:c.contract?.file||c.signedFile,view:'contract'},
  {key:'design',label:'正式设计成果',version:c.release?'V'+c.release.version:'',ready:!!c.release?.files,file:c.release?.files,view:'confirmation'},
  {key:'confirmation',label:'客户成果签认',version:c.confirmation?'V'+c.confirmation.version:'',ready:require('./design-coordination').confirmed(c)&&!!confirm?.file,file:confirm?.file,view:'confirmation'},
  {key:'drawing',label:'当前施工图',version:d?.version||'',ready:!!d?.file,file:d?.file,view:'documents'},
  {key:'review',label:'施工图专业审核',version:d?.version||'',ready:r?.status==='passed'&&!!r.report,file:r?.report,view:'drawing-review',summary:r?.status==='returned'?'退回修改':r?.status==='pending'?'审核中':''},
  {key:'copies',label:'本版施工图抄送记录',version:d?.version||'',ready:!!copied,file:copied?{name:'施工图 '+d.version+' 抄送记录',text:'图纸版本 '+d.version+'\n抄送日期 '+copied.date+'\n接收客户 '+(copied.recipient||c.name)+'\n'+(copied.status==='simulated'?'本地演示记录，不代表客户已读':'按原发送记录留存')}:null,view:'copies',summary:copied?.status==='simulated'?'本地演示记录':copied?.date||''}
 ];
}
function handoverReady(c){const rows=packageRows(c);return rows.length>0&&rows.every(r=>r.ready);}
function handoverSnapshot(c,note){return {basis:basis(c),documents:packageRows(c).map(r=>copy(r)),brief:{name:'电子交底记录',text:note},designVersion:c.release?.version,drawingVersion:last(c.drawings)?.version,reviewId:drawingReview(c)?.id};}
module.exports={basis,quoteBudget,quoteDefaults,quoteTotal,quotation,leaders,drawingReview,packageRows,handoverReady,handoverSnapshot};
