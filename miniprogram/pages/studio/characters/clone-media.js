// 只读取本地音频长度，不播放、不上传；未知长度不能视作已通过。
function checkVoice(path, size, alive = () => true) {
if (Number(size) > 20 * 1024 * 1024) return Promise.reject(Error('声音样本须在20MB以内'));
return new Promise((resolve, reject) => {
const audio = wx.createInnerAudioContext(); let done = false, retry;
const finish = error => { if (done) return; done = true; clearTimeout(timeout); clearTimeout(retry); const duration=Number(audio.duration)||0;audio.destroy(); error ? reject(error) : resolve({duration}); };
const check = () => {
if (done) return;
if (!alive()) return finish({ cancelled: true });
const seconds = Number(audio.duration);
if (seconds > 0) return finish(seconds < 5 || seconds > 180 ? Error('声音样本须为5秒至3分钟') : null);
retry = setTimeout(check, 150);
};
const timeout = setTimeout(() => finish(Error('无法读取声音时长，请更换MP3、M4A或WAV文件')), 8000);
audio.onError(() => finish(Error('无法读取声音样本，请更换MP3、M4A或WAV文件')));
audio.onCanplay(check); audio.autoplay = false; audio.src = path;
});
}
function formatOf(path,kind,name='') {
const ext=String(name||path).split(/[?#]/)[0].toLowerCase().match(/\.([a-z0-9]+)$/)?.[1];
const mime={mp4:'video/mp4',mov:'video/mov',mp3:'audio/mpeg',m4a:'audio/m4a',wav:'audio/wav'};
if(!(kind==='AVATAR'?['mp4','mov']:['mp3','m4a','wav']).includes(ext))throw Error(kind==='AVATAR'?'请选择MP4或MOV视频':'请选择MP3、M4A或WAV声音样本');
return {extension:ext,mimeType:mime[ext]};
}
function validateVideo(info){
if(/^(h265|hevc|hvc1|hev1)$/i.test(String(info.type||'')))throw Error('人物视频须为H.264编码，请转换后上传');
const seconds=Number(info.duration),width=Number(info.width),height=Number(info.height);
if(!Number.isFinite(seconds)||seconds<=0)throw Error('无法读取视频时长，请更换视频');
if(seconds<5||seconds>1800)throw Error('人物视频须为5秒至30分钟');
if(!width||!height)throw Error('无法读取视频分辨率，请更换视频');
if(Math.min(width,height)<360||Math.max(width,height)>4096||Math.min(width,height)>2160)throw Error('人物视频分辨率须为360p至4K');
}
async function checkVideo(path,size,alive=()=>true){
if(Number(size)>20*1024*1024)throw Error('人物视频须在20MB以内，请压缩后重试');
if(typeof wx.getVideoInfo!=='function')throw Error('当前微信版本无法读取视频信息，请更新微信后重试');
const info=await new Promise((resolve,reject)=>wx.getVideoInfo({src:path,success:resolve,fail:()=>reject(Error('无法读取视频，请选择MP4或MOV文件'))}));
if(!alive())throw {cancelled:true};validateVideo(info);return info;
}
async function resolveFormat(fs,path,kind,name){
try{return formatOf(path,kind,name);}catch(error){
// 临时路径无扩展名时读取容器头。
if(name||/\.[a-z0-9]+$/i.test(String(path).split(/[?#]/)[0]))throw error;
const data=await new Promise((resolve,reject)=>fs.readFile({filePath:path,position:0,length:32,success:r=>resolve(r.data),fail:()=>reject(error)}));
const bytes=new Uint8Array(data),ascii=(start,end)=>Array.from(bytes.subarray(start,end)).map(n=>String.fromCharCode(n)).join('');
let ext;if(ascii(4,8)==='ftyp')ext=kind==='AVATAR'?(ascii(8,12)==='qt  '?'mov':'mp4'):'m4a';
else if(kind==='VOICE'&&ascii(0,4)==='RIFF'&&ascii(8,12)==='WAVE')ext='wav';
else if(kind==='VOICE'&&(ascii(0,3)==='ID3'||bytes[0]===255&&(bytes[1]&224)===224))ext='mp3';
if(!ext)throw error;return formatOf('file.'+ext,kind);
}
}
module.exports = { checkVoice,checkVideo,validateVideo,formatOf,resolveFormat };
