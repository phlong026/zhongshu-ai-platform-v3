// The cashier simulates the interaction. Business payment status is always read
// back from the existing simulation API; no merchant SDK or payment password.
module.exports = function({ http, commissions, paymentView, delay = () => new Promise(resolve => setTimeout(resolve, 650)) }) {
  function guard(page) {
    const session = http.sessionGuard(), id = String(page.id), number = page.data.paymentOrder.number;
    return () => session() && !page.disposed && page.view === 'pay' && String(page.id) === id && page.data.paymentOrder?.number === number;
  }
  function validate(page, order) {
    if (!order || String(order.commissionId) !== String(page.id) || order.number !== page.data.paymentOrder.number || !['PENDING','PAID'].includes(order.status)) throw Error('登记结果暂不可用，请稍后查询');
    return order;
  }
  function paid(page, order) {
    page.dropCmdKey('simulate-pay');page.action = '';page.initialized = false;
    if(page.__commission)page.__commission.simulatedPayment = order;
    page.setData({paymentOrder:paymentView(order),primary:'',busy:false,simulationPhase:'',simulationUncertain:false,simulationError:'',paymentNotice:''});
  }
  return {
    openSimulationPayment() {
      if(this.data.busy || this.view !== 'pay' || this.data.paymentOrder?.status !== 'PENDING' || this.action !== 'commission-simulate-pay')return;
      if(this.data.simulationUncertain)return this.checkSimulationPayment();
      this.setData({simulationPhase:'confirm',simulationError:'',paymentNotice:'',paymentMerchant:this.__commission?.fee?.recipient || '众墅之家设计服务',error:''});
    },
    cancelSimulationPayment() {
      if(this.data.busy)return;
      const uncertain=this.data.simulationUncertain;
      this.setData({simulationPhase:'',simulationError:'',paymentNotice:uncertain?'登记结果待确认，请先查询':'已取消，可继续登记原订单'});
    },
    paymentSheetNoop() {},
    async confirmSimulationPayment() {
      if(this.data.busy || this.data.simulationPhase !== 'confirm' || this.data.paymentOrder?.status !== 'PENDING')return;
      const alive=guard(this), id=this.id, channel=this.data.paymentChannel, key=this.cmdKey('simulate-pay');
      this.setData({busy:true,simulationPhase:'processing',simulationChecking:false,simulationError:'',error:''});
      const visible=delay();let failure;
      try { await commissions.simulatePay(id,{channel,confirmed:true},key); } catch(error) { failure=error; }
      if(!alive())return;
      try {
        const order=validate(this,await commissions.simulatedReceipt(id));await visible;if(!alive())return;
        if(order.status==='PAID')return paid(this,order);
        this.setData({busy:false,simulationPhase:'failed',simulationUncertain:false,simulationError:failure?.message || failure?.msg || '付款登记未完成，请重试'});
      } catch(error) {
        await visible;if(!alive())return;
        this.setData({busy:false,simulationPhase:'unknown',simulationUncertain:true,simulationError:'暂时无法确认登记结果，请先查询订单'});
      }
    },
    retrySimulationPayment() {
      if(this.data.busy)return;
      if(this.data.simulationUncertain)return this.checkSimulationPayment();
      return this.openSimulationPayment();
    },
    async checkSimulationPayment() {
      if(this.data.busy || !this.data.paymentOrder || this.view !== 'pay')return;
      const alive=guard(this),id=this.id;
      this.setData({busy:true,simulationPhase:'processing',simulationChecking:true,simulationError:''});
      try {
        const order=validate(this,await commissions.simulatedReceipt(id));if(!alive())return;
        if(order.status==='PAID')return paid(this,order);
        this.setData({paymentOrder:paymentView(order),busy:false,simulationPhase:'confirm',simulationUncertain:false,simulationError:'',paymentNotice:'订单仍待登记'});
      } catch(error) {
        if(alive())this.setData({busy:false,simulationPhase:'unknown',simulationUncertain:true,simulationError:'暂时无法确认登记结果，请稍后查询'});
      }
    }
  };
};
