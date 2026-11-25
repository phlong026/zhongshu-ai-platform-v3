// Compatibility guards for stale pages. These handlers never create or pay an order.
const retired = function() {
  this.action = '';
  this.setData({busy:false,simulationPhase:'',simulationUncertain:false,primary:'',
    simulationError:'',paymentNotice:'实体费用请在外部付款后，由财务登记流水和凭证'});
};
module.exports = () => ({
  openSimulationPayment:retired, confirmSimulationPayment:retired,
  retrySimulationPayment:retired, checkSimulationPayment:retired,
  cancelSimulationPayment(){this.setData({simulationPhase:'',simulationError:''});},
  paymentSheetNoop(){}
});
