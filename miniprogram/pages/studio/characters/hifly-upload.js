const api=require('../../../api/franchise/content');
const media=require('./clone-media');
const stream=require('../../../utils/sha256-stream');
/** 仅向飞影签名地址 PUT；平台只接收元数据。 */
module.exports=async function upload(path,kind,personaId,alive=()=>true,file={}){
const ensure=()=>{if(!alive())throw {cancelled:true};};ensure();
if(!['AVATAR','VOICE'].includes(kind))throw Error('请选择分身或声音');
const limit=20*1024*1024;
const fs=wx.getFileSystemManager();
const size=fs.stat
  ? await new Promise((resolve,reject)=>fs.stat({path,success:r=>resolve(r.stats.size),fail:()=>reject(Error('无法读取素材信息，请重新选择'))}))
  : Number(file.size);
ensure();if(!Number.isSafeInteger(size)||size<=0||size>limit)throw Error('素材大小须在20MB以内，请压缩后重试');
const format=await media.resolveFormat(fs,path,kind,file.name);ensure();
if(file.onProgress)file.onProgress('正在校验素材…');
const sha=await stream.hashFile(fs,path,size,alive);ensure();
if(file.onProgress)file.onProgress('正在准备上传…');
const ticket=await api.clones.uploadTicket(personaId,{kind,mimeType:format.mimeType,sizeBytes:size,sha256:sha});ensure();
const types={mp4:['video/mp4'],mov:['video/mov','video/quicktime'],mp3:['audio/mpeg','audio/mp3'],m4a:['audio/m4a','audio/mp4','audio/x-m4a'],wav:['audio/wav','audio/x-wav','audio/wave']};
if(!ticket||!/^https:\/\/upload\.hifly\.cc\//i.test(String(ticket.uploadUrl||''))||!ticket.uploadId||!types[format.extension].includes(ticket.contentType))throw Error('素材直传通道不可用');
// 普通签名PUT仍需完整二进制；摘要按块计算。
let bytes=await new Promise((resolve,reject)=>fs.readFile({
  filePath:path,position:0,length:size,success:r=>resolve(r.data),
  fail:()=>reject(Error('当前设备无法读取该素材，请压缩文件后重试'))
}));
ensure();if(bytes.byteLength!==size)throw Error('素材已变化，请重新选择');
if(ArrayBuffer.isView(bytes))bytes=bytes.byteOffset===0&&bytes.byteLength===bytes.buffer.byteLength?bytes.buffer:bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength);else {try{ArrayBuffer.prototype.slice.call(bytes,0,0);}catch(e){throw Error('无法读取素材二进制数据，请重新选择');}}
if(file.onProgress)file.onProgress('正在上传素材…');
const put=()=>new Promise((resolve,reject)=>wx.request({url:ticket.uploadUrl,method:'PUT',data:bytes,header:{'Content-Type':ticket.contentType},timeout:120000,
  success:r=>{
    if(r.statusCode>=200&&r.statusCode<300)return resolve();
    const error=Error(r.statusCode===403?'上传凭证已失效，请重新上传':'素材上传失败，请重新上传');
    error.retryable=r.statusCode>=500;reject(error);
  },
  fail:()=>reject(Object.assign(Error('上传中断或超时，请检查网络后重试'),{retryable:true}))
}));
try { await put(); }
catch(error) {
  ensure();
  if(!error.retryable || (ticket.expiresAt && Date.now()>=ticket.expiresAt))throw error;
  await put();
}
bytes=null;ensure();
if(file.onProgress)file.onProgress('正在确认客户端上传…');
const confirm=()=>api.clones.completeUpload(ticket.uploadId);
let result;
try { result=await confirm(); }
catch(error) {
  ensure();
  if(error?.silent || (Number(error?.code)>0 && Number(error.code)<500) || Number(error?.code)>=1000)throw error;
  result=await confirm();
}
ensure();if(!result||result.status!=='UPLOADED')throw Error('素材上传尚未完成，请重试');return String(ticket.uploadId);
};
