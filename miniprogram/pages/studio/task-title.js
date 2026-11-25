function makeTaskTitle({ kind, title, description, text, sourceTitle } = {}) {
  const labels={ai_video:'AI视频',ai:'AI视频',digital:'数字人口播',copy:'文案二创',remix:'文案二创',image:'AI图片'};
  const prefix=(labels[kind]||'AI创作')+'｜';
  let topic=String(title||sourceTitle||description||text||'').trim();
  if(topic.startsWith(prefix))topic=topic.slice(prefix.length);
  topic=topic.split(/\r?\n/)[0].replace(/\s+/g,' ').trim();
  topic=topic.replace(/^(?:(?:请帮我|帮我|请)\s*)?(?:生成|制作|创作)(?:一个|一段|一条|一份)?\s*/,'').trim();
  const chars=Array.from(topic);
  return prefix+(chars.length>24?chars.slice(0,23).join('')+'…':topic||'未命名创作');
}
module.exports = makeTaskTitle;
