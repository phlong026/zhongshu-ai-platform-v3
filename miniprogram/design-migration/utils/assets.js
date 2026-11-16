const api = require('./api');
const http = require('./request');
const design = require('../../api/franchise/design');
function fetchCaseAssetDataUrl(caseId,id) {
  const expected=http.captureSession();
  if(!caseId||!id)return Promise.reject({msg:'缺少案例或图片编号'});
  return design.cases.downloadUrl(String(caseId),String(id)).then(ticket=>{
    if(!http.isSameSession(expected))throw {msg:'工作身份已更新'};
    if(!ticket||!/^https:\/\//.test(ticket.downloadUrl))throw {msg:'图片地址暂不可用，请重试'};
    return ticket.downloadUrl;
  });
}
function fetchAssetDataUrl(id) {
  const expected = http.captureSession();
  if (!id) return Promise.reject({ msg: '缺少图片编号' });
  return api.createDownloadTicket(String(id)).then(ticket => {
    if (!http.isSameSession(expected)) throw { msg: '工作身份已更新' };
    if (!ticket || !/^https:\/\//.test(ticket.downloadUrl)) throw { msg: '图片地址暂不可用，请重试' };
    return ticket.downloadUrl;
  });
}
function previewImages(urls, current) {
  const list = (urls || []).filter(url => /^https:\/\//.test(url));
  if (list.length) wx.previewImage({ urls: list, current: current || list[0] });
}
module.exports = { fetchCaseAssetDataUrl, fetchAssetDataUrl, fetchProfileAvatar: fetchAssetDataUrl, previewImages, toPreviewable: value => Promise.resolve(value), cachedAssetDataUrl: () => '' };
