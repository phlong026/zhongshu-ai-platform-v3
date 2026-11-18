const http=require('../../api/http');
const Home=require('../../utils/home-metrics');
Page({
  data:{row:null,error:'',title:'资金原单'},
  onLoad(options){this.options=options;},
  async onShow(){
    const {role,period,metric,type,id}=this.options;
    const alive=http.requestGuard('home-financial-record');
    this.setData({row:null,error:''});
    try{
      const row=await http.get('/franchise/home/metrics/'+encodeURIComponent(metric)+'/record',{role,period,type,id});
      if(alive())this.setData({row:{...row,status:({SIGNED:'已签署',SIGN_AFTER:'签约后收款',BASE_DONE:'基础完成款',CAP_DONE:'封顶款'})[row.status]||row.status,original:Home.money(Number(row.original_cents),false).value,metricAmount:Home.money(Number(row.amount),false).value},title:({contract:'施工合同原单',receivable:'合同应收期次',receipt:'收款原单',payment:'付款原单',refund:'退款原单',payable:'应付原单',reimbursement:'报销原单'})[type]});
    }catch(e){if(alive())this.setData({error:e.msg||e.message||'原单加载失败'});}
  }
});
