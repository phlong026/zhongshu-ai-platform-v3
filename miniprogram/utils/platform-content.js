const api = require('../api/franchise/content');

// Older servers expose only resultState. A local save failure must never become a new paid generation.
function saveFailure(task) {
  return !!task && (task.saveRetryable === true || (task.resultState === 'FAILED'
    && (task.generationStatus === 'SUCCEEDED' || (!task.generationStatus && task.status === 'FAILED'))));
}
function taskView(status) {
  switch (status) {
    case 'SUCCEEDED': return 'completed';
    case 'FAILED': return 'failed';
    case 'SUBMISSION_UNCERTAIN': return 'uncertain';
    case 'ARCHIVING': return 'generating';
    default: return 'generating'; // QUEUED/SUBMITTING/RUNNING 及未知一律生成中
  }
}

function progressOf(status) {
  const view = taskView(status);
  if (view === 'completed') return 100;
  if (view === 'failed' || view === 'uncertain') return 0;
  return status === 'QUEUED' ? 10 : status === 'SUBMITTING' ? 30 : 63;
}

function mapResolution(v3Label) {
  return v3Label === '2K' ? '2K' : '768P';
}

function mapDuration(v3Label) {
  const match = /^(\d+)\s*(?:秒)?$/.exec(String(v3Label || ''));
  const seconds = match ? Number(match[1]) : 8;
  return Math.min(15, Math.max(4, seconds));
}

function mapRatio(v3Label) {
  if(v3Label==='自动')return 'adaptive';
  return ['adaptive', '21:9', '16:9', '4:3', '1:1', '3:4', '9:16'].includes(v3Label) ? v3Label : '16:9';
}

function mapVideoParameters(parameters) {
  const pick = key => {
    const found = (parameters || []).find(item => item.key === key);
    return found ? (typeof found.value === 'number' ? (found.options || [])[found.value] : found.value) : '';
  };
  return {
    resolution: mapResolution(pick('resolution')),
    duration: mapDuration(pick('duration')),
    ratio: mapRatio(pick('ratio'))
  };
}

function restoreVideoParameters(raw, template) {
  return template.map(parameter=>{
    let value;
    if(Array.isArray(raw)){
      const saved=raw.find(item=>item&&item.key===parameter.key);
      if(saved)value=typeof saved.value==='number'&&Array.isArray(saved.options)?saved.options[saved.value]:saved.value;
    }else if(raw&&typeof raw==='object')value=raw[parameter.key];
    if(value==null||value==='')return {...parameter,value:parameter.options?0:parameter.value};
    if(parameter.key==='duration'){
      const match=/^(\d+)\s*(?:秒)?$/.exec(String(value));
      if(!match||Number(match[1])<4||Number(match[1])>15)throw Error('草稿时长须为 4–15 秒，请重新填写');
      value=Number(match[1])+' 秒';
      if(!parameter.options)return {...parameter,value};
    }
    if(parameter.key==='resolution'){
      if(value==='1080P'&&parameter.options.includes('2K'))value='2K';
      if(value==='2K'&&!parameter.options.includes('2K'))value='1080P';
      if(value==='720P')value='768P';
    }
    if(parameter.key==='ratio'&&value==='adaptive')value='自动';
    if(parameter.key==='count'&&value===1)value='1 个';
    const index=parameter.options.indexOf(value);
    if(index<0)throw Error('草稿'+parameter.label+'不受支持，请重新选择');
    return {...parameter,value:index};
  });
}

function mapReferences(references) {
  return (references || []).map((item, index) => ({
    role: { image: 'reference_image', video: 'reference_video', audio: 'reference_audio' }[item.type] || 'reference_image',
    name: item.name || '参考素材' + (index + 1),
    ...(item.assetId?{assetId:String(item.assetId)}:{}),
    ...(item.mimeType?{mimeType:item.mimeType}:{})
  }));
}

function shareLinkUrl(link) {
  const value = String(link || '').replace(/\\([:/])/g, '$1');
  const match = value.match(/https?:\/\/[^\s<>"'“”‘’]+/i);
  return match ? match[0].replace(/[.,;:!?，。；：！？、)）\]】}》]+$/, '') : '';
}

function validateShareLink(link) {
  const value = String(link || '').trim();
  if (!value) return '请先粘贴视频分享链接';
  if (!shareLinkUrl(value)) return '请输入完整的视频分享链接';
  return '';
}

function validatePublicationForm(form, now) {
  if (!String(form.title || '').trim()) return '请填写标题';
  if (!String(form.publishText || '').trim()) return '请填写发布文案';
  if (form.scheduled) {
    const time = new Date(String(form.scheduled).replace(' ', 'T') + ':00');
    if (!Number.isFinite(time.getTime())) return '请选择有效的定时时间';
    if (time.getTime() < (now || Date.now()) + 60000) return '定时发布需至少提前1分钟';
  }
  return '';
}

function validatePublishedUrl(value) {
  const url = String(value || '').trim();
  if (!url) return '请填写发布后的作品链接';
  if (url.length > 2000 || !/^(https?):\/\/(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}(?::\d{1,5})?(?:[/?#][^\s<>]*)?$/i.test(url)) {
    return '请输入有效的 http 或 https 作品链接';
  }
  return '';
}

function degradeMessage(error) {
  if (String(error && error.code) === '1072000010') return '公司尚未开通积分账户，请联系公司管理员或平台开通后使用创作功能';
  const message = String((error && (error.message || error.errMsg)) || '');
  if (/fail|timeout|abort/i.test(message)) return '网络暂不可用，请稍后重试';
  return message || '操作未完成，请稍后重试';
}

function rowsOf(rows) {
  if (Array.isArray(rows)) return rows;
  if (rows && Array.isArray(rows.items)) return rows.items;
  if (rows && Array.isArray(rows.list)) return rows.list;
  return [];
}

// Task titles are fixed at submission time from the user's input, never from generated output.
function makeTaskTitle({ kind, title, description, text, sourceTitle } = {}) {
  const labels = { ai_video: 'AI视频', ai: 'AI视频', digital: '数字人口播', copy: '文案二创', remix: '文案二创', image: 'AI图片' };
  const label = labels[kind] || 'AI创作';
  const prefix = label + '｜';
  const clean = value => String(value || '').replace(/^[#*\s【】《》]+|[#*\s]+$/g, '').trim();
  const explicit = clean(title);
  let topic = explicit || clean(sourceTitle) || String(description || text || '');
  topic = clean(topic.split(/\r?\n/)[0]).replace(/\s+/g, ' ');
  topic = topic.replace(/^(?:(?:请帮我|帮我|请)\s*)?(?:生成|制作|创作)(?:一个|一段|一条|一份)?\s*/, '');
  topic = clean(topic);
  if (topic.startsWith(prefix)) {
    const normalizedTopic = topic.slice(prefix.length).trim();
    const chars = Array.from(normalizedTopic);
    return prefix + (chars.length > 24 ? chars.slice(0, 23).join('') + '…' : normalizedTopic || '未命名创作');
  }
  const points = Array.from(topic);
  if (points.length > 24) topic = points.slice(0, 23).join('') + '…';
  if (!topic) topic = '未命名创作';
  return prefix + topic;
}

function pollTask(taskId, { intervalMs = 2000, timeoutMs = 120000, onTick, maxConsecutiveErrors = 2 } = {}) {
  let cancelled = false, rejectRef = null;
  const promise = new Promise((resolve, reject) => {
    rejectRef = reject;
    const startedAt = Date.now();
    let consecutiveErrors = 0;
    const stop = () => reject({ code: 0, cancelled: true, message: '轮询已取消' });
    const tick = () => {
      if (cancelled) return stop();
      api.tasks.get(taskId).then(task => {
        if (cancelled) return stop();
        consecutiveErrors = 0;
        if (onTick) onTick(task);
        const view = taskView(task.status);
        if (view === 'completed' || view === 'failed' || view === 'uncertain') return resolve(task);
        if (Date.now() - startedAt >= timeoutMs) return reject(Error('任务处理时间较长，请稍后在任务中心查看'));
        setTimeout(tick, intervalMs);
      }).catch(error => {
        if (cancelled) return stop();
        if (Date.now() - startedAt >= timeoutMs) return reject(Error('任务处理时间较长，请稍后在任务中心查看'));
        if (++consecutiveErrors > maxConsecutiveErrors) return reject(error);
        setTimeout(tick, intervalMs); // 网络抖动容忍：不终止，按既有间隔重试
      });
    };
    tick();
  });
  // 立即落定（含在途请求场景，防止调用方 await 挂起）；重复 cancel/已落定后为 no-op
  promise.cancel = () => { cancelled = true; rejectRef({ code: 0, cancelled: true, message: '轮询已取消' }); return promise; };
  return promise;
}

module.exports = {
  api,
  saveFailure, taskView, progressOf, mapResolution, mapDuration, mapRatio, mapVideoParameters, restoreVideoParameters,
  mapReferences, shareLinkUrl, validateShareLink, validatePublicationForm, validatePublishedUrl,
  degradeMessage, rowsOf, makeTaskTitle, pollTask
};
