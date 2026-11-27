const http=require('../../api/http');
const Home=require('../../utils/home-metrics');
const apiScope=require('../../utils/api-scope');
const {day}=require('../../utils/finance-date');
const amount=value=>value!=null&&value!==''&&Number.isSafeInteger(Number(value))&&Number(value)>=0?'¥'+Home.money(Number(value),false).value:'未记录';
function budgetRecord(detail,customerId,id,version){
  const scheme=(detail?.schemes||[]).find(s=>String(s.id)===id);
  if(String(detail?.customer?.id)!==customerId||!scheme||scheme.kind!=='BUDGET'||String(scheme.customerId)!==customerId||String(scheme.version)!==version)throw Error('预算原单或版本不匹配，请从客户资料重新进入');
  let payload={};try{payload=JSON.parse(scheme.payload||'{}')||{};}catch(e){}
  const snapshot=payload.itemizedBudget?.model==='ITEMIZED_V1'?payload.itemizedBudget:null;
  const fields=snapshot?.inputSummary||{};
  const basis=[['面宽',scheme.width,'m'],['进深',scheme.depth,'m'],['层数',scheme.floors,'层'],['建筑面积',scheme.area,'㎡'],['占地面积',fields.footprintArea,'㎡'],['屋顶面积',fields.roofArea,'㎡'],['建造地区',snapshot?.regionName,'']].map(([label,value,unit])=>({label,value:value==null||value===''?'未记录':String(value)+unit}));
  const status={PRICED:'已计价',MISSING_PRICE:'待补价',MISSING_QUANTITY:'待补量',MISSING_BOTH:'待补配置/量价',EXCLUDED:'本次未包含'};
  const groups=(Array.isArray(snapshot?.items)?snapshot.items:[]).filter(item=>item&&typeof item==='object').map(item=>({
    name:item.publicName||'预算分项',category:({BODY:'主体类',EXTERIOR:'外装类'})[item.category]||'',amount:amount(item.amountCents),subtotal:item.amountCents==null&&item.pricedSubtotalCents!=null?amount(item.pricedSubtotalCents):'',
    lines:(Array.isArray(item.lines)?item.lines:[]).filter(line=>line&&typeof line==='object').map(line=>({name:line.optionLabel||'已归档子项',status:status[line.status]||'',quantity:line.quantity==null?'':String(line.quantity)+(line.unit||''),amount:amount(line.amountCents)}))
  }));
  return {id:String(scheme.id),version:scheme.version,title:scheme.title,customerName:detail.customer.name,total:amount(scheme.totalCents),date:day(scheme.createTime),basis,groups,
    confirmation:scheme.confirmedAt?'已记录参考预算确认':'未记录参考预算确认',confirmationNote:scheme.confirmationNote||'',
    incomplete:scheme.totalCents==null,subtotal:scheme.totalCents==null&&snapshot?.pricedSubtotalCents!=null?amount(snapshot.pricedSubtotalCents):'',missing:(Array.isArray(snapshot?.missingItems)?snapshot.missingItems:[]).filter(v=>typeof v==='string').join('、')};
}
Page({
  data:{row:null,budget:null,error:'',title:'资金原单'},
  onLoad(options){this.options=options;},
  onHide(){this.__sequence=(this.__sequence||0)+1;},
  onUnload(){this.__sequence=(this.__sequence||0)+1;},
  async onShow(){
    const {role,period,metric,type,id}=this.options;
    const guard=http.requestGuard('home-financial-record'),scope=apiScope(),sequence=this.__sequence=(this.__sequence||0)+1;
    const alive=()=>guard()&&scope===apiScope()&&sequence===this.__sequence;
    this.setData({row:null,budget:null,error:'',...(type==='budget'?{title:'参考预算原单'}:{})});
    try{
      if(type==='budget'){
        const {customerId,version}=this.options;
        if(![customerId,id,version].every(v=>typeof v==='string'&&/^[1-9]\d*$/.test(v)))throw Error('预算原单编号或版本无效');
        const detail=await require('../../api/franchise/crm').customers.get(customerId);
        if(alive())this.setData({budget:budgetRecord(detail,customerId,id,version)});
        return;
      }
      const row=await http.get('/franchise/home/metrics/'+encodeURIComponent(metric)+'/record',{role,period,type,id});
      if(alive())this.setData({row:{...row,status:({SIGNED:'已签署',SIGN_AFTER:'签约后收款',BASE_DONE:'基础完成款',CAP_DONE:'封顶款'})[row.status]||row.status,original:Home.money(Number(row.original_cents),false).value,metricAmount:Home.money(Number(row.amount),false).value},title:({contract:'施工合同原单',receivable:'合同应收期次',receipt:'收款原单',payment:'付款原单',refund:'退款原单',payable:'应付原单',reimbursement:'报销原单'})[type]});
    }catch(e){if(alive())this.setData({error:e.msg||e.message||'原单加载失败'});}
  }
});
