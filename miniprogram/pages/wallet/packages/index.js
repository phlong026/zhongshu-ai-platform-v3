// Existing deep links keep their destination; no simulated order is replayed or created.
const destination='/pages/wallet/company-recharge/index';
Page({data:{},onShow(){wx.redirectTo({url:destination});},choose(){},confirmPending(){}});
