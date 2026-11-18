const api = require('./api');
const http = require('./request');
function quote(stage, count, options) {
  const resolution = options && options.resolution || '2K';
  return api.getGenerationQuote(stage, count, resolution).then(value => {
    const price = value && value.companyPrice;
    if (!price || price.subject !== (stage === 'ELEVATION' ? 'ai_elevation' : 'ai_flat') || Number(price.quantity) !== count
        || !Number.isSafeInteger(Number(price.credits)) || Number(price.credits) < 1 || !price.priceVersion) throw { msg: '公司积分报价暂不可用' };
    return value;
  });
}
function lineSettings(data, stage) {
  return stage === 'ELEVATION' ? { count: data.elevCount, resolution: data.elevResolution } : { count: data.count, resolution: data.resolution };
}
function refresh(page, stage) {
  const seq = page._quoteSeq = (page._quoteSeq || 0) + 1, session = http.captureSession();
  const settings = lineSettings(page.data, stage);
  page.setData({ priceText: '正在获取报价…', quoteLoading: true, quoteReady: false, quoteError: '' });
  const requests = {};
  ['2K', '4K'].forEach(resolution => {
    requests[resolution] = quote(stage, settings.count, { resolution });
    requests[resolution].then(value => {
    if (page._quoteSeq === seq && http.isSameSession(session)) page.setData({ ['quoteByResolution.' + resolution]: value.totalPointCost + ' 公司积分' });
  }).catch(() => {}); });
  return (requests[settings.resolution] || quote(stage, settings.count, settings)).then(value => {
    if (page._quoteSeq === seq && http.isSameSession(session)) page.setData({ priceText: '本次共 ' + value.totalPointCost + ' 公司积分', quoteLoading: false, quoteReady: true, quoteError: '',
      quoteUnit: value.totalPointCost % settings.count === 0 ? value.totalPointCost / settings.count : null });
  }).catch(error => { if (page._quoteSeq === seq && http.isSameSession(session)) page.setData({ priceText: '报价暂不可用', quoteLoading: false, quoteReady: false, quoteError: error.msg || error.message || '报价读取失败，请重试' }); });
}
function confirm(stage, count, description, options) {
  const expected = http.captureSession();
  return quote(stage, count, options).then(value => new Promise((resolve, reject) => wx.showModal({ title: '确认生成',
    content: '生成 ' + count + ' 张方案，预扣 ' + value.totalPointCost + ' 公司积分，未完成部分按实际结算退回。' + (description || ''),
    success: result => !result.confirm ? reject({ cancelled: true }) : !http.isSameSession(expected) ? reject({ msg: '工作身份已更新，请重新进入' }) : resolve({ companyPrice: value.companyPrice }), fail: reject })));
}
module.exports = { quote, refresh, confirm };