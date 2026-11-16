const api = require('../api/franchise/content');
const finance = require('../api/finance');

/** 后端只领取凭证；素材字节直接PUT到飞影，不经过平台文件接口或云存储。 */
module.exports = async function upload(path, kind, personaId, alive = () => true) {
  const ensure = () => { if (!alive()) throw { cancelled: true }; };
  const video = kind === 'AVATAR';
  if (!video && kind !== 'VOICE') throw Error('请选择分身或声音');
  const bytes = await new Promise((resolve, reject) => wx.getFileSystemManager().readFile({filePath:path,success:r=>resolve(r.data),fail:reject}));
  ensure();
  if (!bytes.byteLength || bytes.byteLength > (video ? 50 : 20) * 1024 * 1024) throw Error('素材大小须在' + (video ? 50 : 20) + 'MB以内');
  const ticket = await api.clones.uploadTicket(personaId,{kind,mimeType:video?'video/mp4':'audio/mpeg',sizeBytes:bytes.byteLength,sha256:finance.sha256Hex(bytes)});
  ensure();
  if (!ticket || !/^https:\/\/upload\.hifly\.cc\//i.test(String(ticket.uploadUrl||'')) || !ticket.uploadId
      || !(video ? ticket.contentType === 'video/mp4' : ['audio/mpeg','audio/mp3'].includes(ticket.contentType))) throw Error('素材直传通道不可用');
  await new Promise((resolve,reject) => wx.request({url:ticket.uploadUrl,method:'PUT',data:bytes,header:{'Content-Type':ticket.contentType},timeout:120000,
    success:r=>r.statusCode>=200&&r.statusCode<300?resolve():reject(Error('素材上传失败，请重新上传')),fail:reject}));
  ensure();
  const result = await api.clones.completeUpload(ticket.uploadId);
  ensure();
  if (!result || result.status !== 'UPLOADED') throw Error('素材上传尚未完成，请重试');
  return String(ticket.uploadId);
};
