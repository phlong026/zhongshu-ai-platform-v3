/**
 * 内容创作获客纯函数层（阶段 1.5，16 文档 §七 v3 接入）：
 * 七态映射 / v3 参数→H3 值域映射（F11）/ 表单校验 / 降级文案。零 wx 依赖，可被 verify 脚本静态断言。
 */
const api = require('../api/franchise/content');

/** 后端任务七态 → 页面展示态（utils/studio.js 三态语义对齐 + uncertain 专用展示） */
function taskView(status) {
  switch (status) {
    case 'SUCCEEDED': return 'completed';
    case 'FAILED': return 'failed';
    case 'SUBMISSION_UNCERTAIN': return 'uncertain';
    case 'ARCHIVING': return 'generating';
    default: return 'generating'; // QUEUED/SUBMITTING/RUNNING 及未知一律生成中
  }
}

/** 生成中/未知的进度展示值（后端无百分比，用区间进度示意） */
function progressOf(status) {
  const view = taskView(status);
  if (view === 'completed') return 100;
  if (view === 'failed' || view === 'uncertain') return 0;
  return status === 'QUEUED' ? 10 : status === 'SUBMITTING' ? 30 : 63;
}

/** F11：v3 分辨率选项 → H3 值域（H3 仅 768P/2K 两档；1080P→2K、720P→768P） */
function mapResolution(v3Label) {
  if (v3Label === '1080P') return '2K';
  return '768P'; // 768P 与 720P 都归 768P
}

/** F11：v3 时长选项（'8 秒'）→ 4-15 整数秒 */
function mapDuration(v3Label) {
  const match = /^(\d+)\s*秒$/.exec(String(v3Label || ''));
  const seconds = match ? Number(match[1]) : 8;
  return Math.min(15, Math.max(4, seconds));
}

/** F11：比例 9:16/16:9/1:1 原样透传（含首尾帧时后端归一 adaptive，前端不感知） */
function mapRatio(v3Label) {
  return ['9:16', '16:9', '1:1'].includes(v3Label) ? v3Label : '16:9';
}

/** v3 参数组 [{key,value}] → H3 请求参数 {resolution,duration,ratio}（数量恒 1 不上送） */
function mapVideoParameters(parameters) {
  const pick = key => {
    const found = (parameters || []).find(item => item.key === key);
    return found ? found.value : '';
  };
  return {
    resolution: mapResolution(pick('resolution')),
    duration: mapDuration(pick('duration')),
    ratio: mapRatio(pick('ratio'))
  };
}

/** 本地参考素材 {type:image|video|audio,...} → 后端 references [{role,name}]（image 需用途名） */
function mapReferences(references) {
  return (references || []).map((item, index) => ({
    role: { image: 'reference_image', video: 'reference_video', audio: 'reference_audio' }[item.type] || 'reference_image',
    name: item.name || '参考素材' + (index + 1)
  }));
}

/** 分享链接校验（creation 页入口）：抖音/小红书 http(s) 链接 */
function validateShareLink(link) {
  const value = String(link || '').trim();
  if (!value) return '请先粘贴视频分享链接';
  if (!/https?:\/\/[^\s]+/i.test(value)) return '请输入完整的视频分享链接';
  return '';
}

/** 发布表单校验（对齐后端 INPUT_INVALID 语义；定时≥1 分钟由后端终判，前端同款预判） */
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

/** 回执链接校验（对齐 studio.normalizePublishedUrl 语义） */
function validatePublishedUrl(value) {
  const url = String(value || '').trim();
  if (!url) return '请填写发布后的作品链接';
  if (url.length > 2000 || !/^(https?):\/\/(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}(?::\d{1,5})?(?:[/?#][^\s<>]*)?$/i.test(url)) {
    return '请输入有效的 http 或 https 作品链接';
  }
  return '';
}

/** 错误降级：后端不可达/未登录时保留本机演示兜底（demo 语义不破坏），仅提示网络态 */
function degradeMessage(error) {
  const message = String((error && (error.message || error.errMsg)) || '');
  if (/fail|timeout|abort/i.test(message)) return '网络暂不可用，已保留本机内容';
  return message || '操作未完成，请稍后重试';
}

/** 列表响应形状归一：数组 | {items:[]} | {list:[]} → 数组（分页包装差异容忍，非法形状回空） */
function rowsOf(rows) {
  if (Array.isArray(rows)) return rows;
  if (rows && Array.isArray(rows.items)) return rows.items;
  if (rows && Array.isArray(rows.list)) return rows.list;
  return [];
}

/** 任务轮询（页面通用）：intervalMs 间隔拉取直至终态或超时；onTick 每次回调。
 * 连续网络错容忍 maxConsecutiveErrors 次（默认 2，第 3 次整体 reject），错误路径同样受 timeoutMs 约束；
 * 返回 promise 挂 cancel() 取消钩子（页面 onUnload 终止轮询，防 UI 长时间占锁）——
 * 取消以 {cancelled:true} reject，调用方 catch 按 error.cancelled 静默。既有调用（只传 taskId/选项对象）兼容。 */
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
  taskView, progressOf, mapResolution, mapDuration, mapRatio, mapVideoParameters,
  mapReferences, validateShareLink, validatePublicationForm, validatePublishedUrl,
  degradeMessage, rowsOf, pollTask
};
