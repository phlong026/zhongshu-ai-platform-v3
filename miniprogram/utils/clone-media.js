// 只读取本地音频长度，不播放、不上传；未知长度不能视作已通过。
function checkVoice(path, size, alive = () => true) {
  if (Number(size) > 20 * 1024 * 1024) return Promise.reject(Error('声音样本须在20MB以内'));
  return new Promise((resolve, reject) => {
    const audio = wx.createInnerAudioContext(); let done = false, retry;
    const finish = error => { if (done) return; done = true; clearTimeout(timeout); clearTimeout(retry); audio.destroy(); error ? reject(error) : resolve(); };
    const check = () => {
      if (done) return;
      if (!alive()) return finish({ cancelled: true });
      const seconds = Number(audio.duration);
      if (seconds > 0) return finish(seconds < 5 || seconds > 180 ? Error('声音样本须为5秒至3分钟') : null);
      retry = setTimeout(check, 150);
    };
    const timeout = setTimeout(() => finish(Error('无法读取声音时长，请更换MP3文件')), 8000);
    audio.onError(() => finish(Error('无法读取声音样本，请更换MP3文件')));
    audio.onCanplay(check); audio.autoplay = false; audio.src = path;
  });
}
module.exports = { checkVoice };
