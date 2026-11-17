// A completed extraction or rewrite is not a generated video.
const KINDS = { copy: ['文案改写', 'text'], asr: ['语音转写', 'text'], link_parse: ['链接解析', 'source'], digital: ['数字人口播', 'video'], ai_video: ['AI视频', 'video'] };
function describe(task) {
  const info = KINDS[task && task.kind];
  if (!info) return null;
  const result = task.result || {};
  const httpUrl = value => /^https?:\/\//i.test(String(value || '')) ? String(value) : '';
  return { taskKind: task.kind, kindLabel: info[0], outputType: info[1],
    resultText: String(result.text || result.script || ''),
    // Source-media URLs must never stand in for a generated video.
    resultUrl: info[1] === 'video' ? httpUrl(task.resultUrl) : '',
    sourceVideoUrl: info[1] === 'source' ? httpUrl(result.videoUrl) : '',
    sourceTitle: String(result.title || ''),
    videoReady: info[1] === 'video' && task.status === 'SUCCEEDED' && !!httpUrl(task.resultUrl) };
}
module.exports = { describe };
