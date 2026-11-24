// A completed extraction or rewrite is not a generated video.
const KINDS = { copy: ['文案改写', 'text'], asr: ['语音转写', 'text'], link_parse: ['链接解析', 'source'], digital: ['数字人口播', 'video'], ai_video: ['AI视频', 'video'] };
function kindLabel(kind) {
  return KINDS[kind]?.[0] || (kind === 'remix' ? '文案二创' : 'AI创作');
}

function kindCaption(kind, title) {
  const label = kindLabel(kind);
  const labels = kind === 'remix' ? [label, KINDS.copy[0], KINDS.asr[0], KINDS.link_parse[0]] : [label];
  return labels.some(value => title.startsWith(value + '｜')) ? '' : label;
}

function serverTitle(task) {
  return typeof task?.title === 'string' ? task.title.trim() : '';
}

function displayTitle(task, legacyTopic) {
  const title = serverTitle(task);
  if (title) return title;
  // Older servers omitted the input title, so only the first available line is shown as a topic.
  const line = String(legacyTopic || '').trim().split('\n', 1)[0].trim();
  const characters = Array.from(line);
  const topic = characters.length > 24 ? characters.slice(0, 23).join('') + '…' : line;
  return kindLabel(task.kind) + '｜' + (topic || '未命名创作');
}

function describe(task) {
  const info = KINDS[task && task.kind];
  if (!info) return null;
  const result = task.result || {};
  const httpUrl = value => /^https?:\/\//i.test(String(value || '')) ? String(value) : '';
  const resultText = String(result.text || result.script || '');
  const sourceTitle = String(result.title || '');
  const legacyTopic = info[1] === 'source' ? sourceTitle || resultText : resultText;
  return { taskKind: task.kind, kindLabel: info[0], outputType: info[1],
    title: displayTitle(task, legacyTopic), serverTitle: serverTitle(task), resultText,
    // Source-media URLs must never stand in for a generated video.
    resultUrl: info[1] === 'video' ? httpUrl(task.resultUrl) : '',
    sourceVideoUrl: info[1] === 'source' ? httpUrl(result.videoUrl) : '',
    sourceTitle,
    videoReady: info[1] === 'video' && task.status === 'SUCCEEDED' && !!httpUrl(task.resultUrl) };
}
module.exports = { describe, displayTitle, kindCaption, serverTitle };
