const api = require('../../../api/franchise/content');
const http = require('../../../api/http');
const media = require('../../../utils/content-media');

const stages = {
  reading: [0, '正在读取素材…'],
  uploading: [1, '正在上传素材…'],
  processing: [2, '正在处理素材格式…']
};

function ensure(alive) { if (!alive()) throw {cancelled:true}; }
function pickerError(error) {
  if (/cancel/i.test(String(error?.errMsg || ''))) return {cancelled:true};
  return Error('无法选择素材，请重试或从微信聊天文件中选择');
}

async function choose(kind, alive) {
  let fromChat = kind === 'VOICE';
  if (!fromChat) {
    const choice = await new Promise((resolve, reject) => wx.showActionSheet({
      itemList:['相册或拍摄', '微信聊天文件'],
      success:result => resolve(result.tapIndex), fail:error => reject(pickerError(error))
    }));
    ensure(alive);
    if (![0, 1].includes(choice)) throw {cancelled:true};
    fromChat = choice === 1;
  }
  const result = await new Promise((resolve, reject) => {
    const options = {count:1, success:resolve, fail:error => reject(pickerError(error))};
    if (fromChat) wx.chooseMessageFile({...options, type:'all'});
    else wx.chooseMedia({...options, mediaType:['video'], sourceType:['album', 'camera']});
  });
  ensure(alive);
  const file = result.tempFiles?.[0];
  const path = file?.tempFilePath || file?.path;
  if (!path) throw Error('未能读取所选素材，请重新选择');
  if (Number(file.size) > 50 * 1024 * 1024) throw Error('原文件须在50MB以内');
  return {path, name:file.name || String(path).split('/').pop() || (kind === 'AVATAR' ? '人物视频' : '声音样本'),
    mimeType:file.mimeType || '', sizeBytes:Number(file.size) || 0};
}

async function upload(kind, personaId, alive, progress = () => {}, remember = () => {}) {
  if (!['AVATAR','VOICE'].includes(kind)) throw Error('请选择分身或声音');
  ensure(alive);
  const file = await choose(kind, alive);
  const onStage = stage => {
    if (alive() && stages[stage]) progress(...stages[stage], {selected:true});
  };
  onStage('reading');
  // Upload original bytes; the B service validates and converts the real codec.
  // Neither the filename nor a phone's ability to preview a codec decides validity.
  const assetId = await media.upload(file.path, alive, {name:file.name, mimeType:file.mimeType,
    type:kind === 'AVATAR' ? 'video' : 'audio', onStage});
  ensure(alive);
  if (!/^\d+$/.test(String(assetId))) throw Error('素材处理结果无效，请重试');
  const source = {assetId:String(assetId), personaId:String(personaId), kind, name:file.name, sizeBytes:file.sizeBytes};
  remember(source);
  return confirmReceipt(source, alive, progress);
}

async function confirmReceipt(source, alive, progress = () => {}) {
  // Keep recovery for an upload completed by older app versions.
  if (!source.assetId) return require('./hifly-upload').confirmReceipt(source, alive, progress);
  ensure(alive);
  if (!/^\d+$/.test(source.assetId) || !/^\d+$/.test(source.personaId) || !['AVATAR','VOICE'].includes(source.kind)) {
    throw Object.assign(Error('上传记录无效，请重新选择素材'), {code:1114000001});
  }
  progress(3, '正在确认上传素材…', {selected:true});
  const confirm = () => api.clones.assetUpload(source.personaId, {kind:source.kind, assetId:source.assetId});
  let result;
  try { result = await confirm(); } catch (error) {
    const code = Number(error.code);
    if (!alive() || error.silent || !(code === -1 || code >= 500 && code < 600)) throw error;
    progress(3, '正在重新确认同一份素材…', {selected:true});
    result = await confirm();
  }
  ensure(alive);
  if (result?.status !== 'UPLOADED' || !/^\d+$/.test(String(result.uploadId)) || String(result.assetId) !== source.assetId) {
    throw Error('素材上传结果尚未确认，请重试确认');
  }
  const size = Number(result.sizeBytes) || source.sizeBytes;
  return {id:String(result.uploadId), name:source.name, size,
    preview:{...source, uploadId:String(result.uploadId), sizeBytes:size}};
}

async function previewSource(source, alive) {
  ensure(alive);
  const ticket = await api.assets.downloadUrl(source.assetId);
  ensure(alive);
  const url = String(ticket?.downloadUrl || '');
  const origin = String(http.base()).replace(/\/admin-api\/?$/, '');
  if (/^https:\/\//i.test(url) && !url.startsWith(origin + '/')) return url;
  const file = await require('../../../utils/b-asset-download').downloadTicketFile({
    http, downloadUrl:url, alive, label:'素材'
  });
  ensure(alive);
  return file.tempFilePath;
}

module.exports = upload;
module.exports.confirmReceipt = confirmReceipt;
module.exports.previewSource = previewSource;
