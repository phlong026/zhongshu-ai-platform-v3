const api = require('../../../api/franchise/content')
const types = {mp4:'video/mp4', mov:'video/mov', mp3:'audio/mpeg', m4a:'audio/m4a', wav:'audio/wav'}
const aliases = {mp4:['video/mp4'], mov:['video/mov','video/quicktime'], mp3:['audio/mp3','audio/mpeg'], m4a:['audio/m4a','audio/mp4','audio/x-m4a'], wav:['audio/wav','audio/x-wav','audio/wave']}

async function upload(kind, persona, alive) {
  const options = kind === 'AVATAR' ? ['mp4','mov'] : ['mp3','m4a','wav']
  const extension = await new Promise((resolve, reject) => wx.showActionSheet({
    itemList:options.map(item => item.toUpperCase() + ' 文件'),
    success:r => resolve(options[r.tapIndex]), fail:() => reject({cancelled:true})
  }))
  if (!extension || !alive()) throw {cancelled:true}
  const ticket = await api.clones.fileTicket(persona, {kind, mimeType:types[extension]})
  if (!alive()) throw {cancelled:true}
  if (!ticket || !/^\d+$/.test(ticket.uploadId) || !/^https:\/\/upload\.hifly\.cc\//.test(ticket.uploadUrl) ||
      !aliases[extension].includes(ticket.contentType) || !(Number(ticket.expiresAt) > Date.now())) throw Error('素材直传通道不可用')
  const record = await new Promise((resolve, reject) => wx.navigateTo({
    url:'/pages/studio/characters/transfer',
    events:{ transferred:resolve, transferFailed:() => reject(Error('上传网页未能打开，请检查微信业务域名配置')),
      transferCancelled:() => reject({cancelled:true}) },
    success:r => r.eventChannel.emit('grant', {uploadId:String(ticket.uploadId), uploadUrl:ticket.uploadUrl,
      expiresAt:ticket.expiresAt, contentType:ticket.contentType, extension}),
    fail:() => reject(Error('无法打开素材上传页'))
  }))
  if (!alive()) throw {cancelled:true}
  if (record.uploadId !== String(ticket.uploadId) || !/^[0-9a-f]{64}$/.test(record.sha256) ||
      !Number.isSafeInteger(record.sizeBytes) || record.sizeBytes <= 0 || record.sizeBytes > 20 * 1024 * 1024) throw Error('上传回执无效')
  const confirm = () => api.clones.fileReceipt(ticket.uploadId,{sizeBytes:record.sizeBytes,sha256:record.sha256})
  let result
  try { result = await confirm() } catch (e) {
    if (!alive() || e.silent || !(e.code === -1 || (e.code >= 500 && e.code < 600))) throw e
    result = await confirm()
  }
  if (!alive()) throw {cancelled:true}
  if (result?.status !== 'UPLOADED' || String(result.uploadId) !== record.uploadId) throw Error('上传回执尚未确认')
  const preview = {uploadId:record.uploadId, name:record.name, extension,
    sizeBytes:record.sizeBytes, sha256:record.sha256,
    previewKey:/^[a-f0-9]{32}$/.test(record.previewKey) ? record.previewKey : ''}
  return {id:record.uploadId, name:record.name, size:record.sizeBytes, preview}
}
module.exports = upload
