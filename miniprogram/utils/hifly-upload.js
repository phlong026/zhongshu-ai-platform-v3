const api=require('../api/franchise/content');
const finance=require('../api/finance');
const media=require('./clone-media');
const stream=require('./sha256-stream');
/** 仅向飞影签名地址 PUT；平台只接收元数据。 */
module.exports=async function upload(path,kind,personaId,alive=()=>true,file={}){
const ensure=()=>{if(!alive())throw {cancelled:true};};ensure();
if(!['AVATAR','VOICE'].includes(kind))throw Error('请选择分身或声音');
const video=kind==='AVATAR',limit=(video?500:20)*1024*1024;
const fs=wx.getFileSystemManager(),format=await media.resolveFormat(fs,path,kind,file.name);ensure();let size=Number(file.size)||0,bytes;
if(fs.stat)size=await new Promise((resolve,reject)=>fs.stat({path,success:r=>resolve(r.stats.size),fail:()=>reject(Error('无法读取素材信息，请重新选择'))}));
const read=()=>new Promise((resolve,reject)=>fs.readFile({filePath:path,success:r=>resolve(r.data),fail:()=>reject(Error('当前设备无法读取该素材，请压缩文件后重试'))}));
if(!size){bytes=await read();size=bytes.byteLength;}
ensure();if(!Number.isSafeInteger(size)||size<=0||size>limit)throw Error('素材大小须在'+(video?500:20)+'MB以内');
if(file.onProgress)file.onProgress('正在校验素材…');
const sha=bytes?finance.sha256Hex(bytes):await stream.hashFile(fs,path,size,alive);ensure();
const ticket=await api.clones.uploadTicket(personaId,{kind,mimeType:format.mimeType,sizeBytes:size,sha256:sha});ensure();
const types={mp4:['video/mp4'],mov:['video/mov','video/quicktime'],mp3:['audio/mpeg','audio/mp3'],m4a:['audio/m4a','audio/mp4','audio/x-m4a'],wav:['audio/wav','audio/x-wav','audio/wave']};
if(!ticket||!/^https:\/\/upload\.hifly\.cc\//i.test(String(ticket.uploadUrl||''))||!ticket.uploadId||!types[format.extension].includes(ticket.contentType))throw Error('素材直传通道不可用');
// 普通签名PUT仍需完整二进制；摘要按块计算。
if(!bytes)bytes=await read();ensure();if(bytes.byteLength!==size)throw Error('素材已变化，请重新选择');
if(ArrayBuffer.isView(bytes))bytes=bytes.byteOffset===0&&bytes.byteLength===bytes.buffer.byteLength?bytes.buffer:bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength);else {try{ArrayBuffer.prototype.slice.call(bytes,0,0);}catch(e){throw Error('无法读取素材二进制数据，请重新选择');}}
if(file.onProgress)file.onProgress('正在上传素材…');
await new Promise((resolve,reject)=>wx.request({url:ticket.uploadUrl,method:'PUT',data:bytes,header:{'Content-Type':ticket.contentType},timeout:600000,
success:r=>r.statusCode>=200&&r.statusCode<300?resolve():reject(Error(r.statusCode===403?'上传凭证已失效，请重新上传':'素材上传失败，请重新上传')),fail:()=>reject(Error('上传中断或超时，请检查网络后重试'))}));bytes=null;ensure();
const result=await api.clones.completeUpload(ticket.uploadId);ensure();if(!result||result.status!=='UPLOADED')throw Error('素材上传尚未完成，请重试');return String(ticket.uploadId);
};
