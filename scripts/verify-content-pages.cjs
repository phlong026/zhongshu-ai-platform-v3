/**
 * 阶段 1.5 内容创作获客接入 verify：断言契约层导出面 + platform-content 纯函数行为 + 四页接线（静态扫描）。
 * 契约：monorepo docs/backend-parity/16-阶段1.5内容创作获客-需求与验收.md（/franchise/content/**）。
 * platform-content 顶层零 wx 依赖（可直接 require）；pollTask 用 wx.request 桩验证终态收敛与超时。
 * 用法：node scripts/verify-content-pages.cjs
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const pageSource = page => fs.readFileSync(path.join(root,'miniprogram',...(page==='creation'?['utils','sales-creation-page.js']:['pages',page,'index.js'])),'utf8');
const api = require('../miniprogram/api/franchise/content');
const P = require('../miniprogram/utils/platform-content');
assert.equal(typeof global.wx, 'undefined', '契约层顶层不得触碰 wx（纯函数可直 require）');

// ===== 导出面：api/franchise/content.js =====
const API_FACE = {
  links: ['parse'],
  scripts: ['extract', 'optimize', 'page', 'get'],
  tasks: ['create', 'page', 'get', 'saveWork', 'resultUrlStatus'],
  drafts: ['works', 'updateStatus', 'videoDraft', 'saveVideoDraft', 'completeChapter', 'courseProgress'],
  assets: ['uploadTicket', 'complete', 'downloadUrl'],
  materials: ['page', 'upload', 'complete', 'download', 'update', 'remove'],
  personas: ['page', 'create', 'update', 'remove'],
  clones: ['quote', 'list', 'create', 'refresh', 'setDefault'],
  publications: ['create', 'page', 'verify', 'link', 'fail'],
  growth: ['summary']
};
for (const [group, methods] of Object.entries(API_FACE)) {
  assert.ok(api[group] && typeof api[group] === 'object', 'api 缺分组：' + group);
  for (const method of methods) assert.equal(typeof api[group][method], 'function', `api.${group}.${method} 缺失`);
}
assert.deepEqual(Object.keys(api).sort(), Object.keys(API_FACE).sort(), 'api 分组集合一致');

// ===== 导出面：utils/platform-content.js（api 透传 + 纯函数集）=====
for (const name of ['taskView', 'progressOf', 'mapResolution', 'mapDuration', 'mapRatio', 'mapVideoParameters',
  'mapReferences', 'validateShareLink', 'validatePublicationForm', 'validatePublishedUrl', 'degradeMessage', 'rowsOf', 'pollTask']) {
  assert.equal(typeof P[name], 'function', 'platform-content 缺导出：' + name);
}
assert.equal(P.api, api, 'platform-content 透传同一 api 模块');

// ===== rowsOf：列表响应形状归一（数组 | {items} | {list}；非法回空）=====
assert.deepEqual(P.rowsOf([{ id: 1 }]), [{ id: 1 }]);
assert.deepEqual(P.rowsOf({ items: [{ id: 2 }] }), [{ id: 2 }]);
assert.deepEqual(P.rowsOf({ list: [{ id: 3 }] }), [{ id: 3 }]);
assert.deepEqual(P.rowsOf(null), []);
assert.deepEqual(P.rowsOf(undefined), []);
assert.deepEqual(P.rowsOf({ items: 'no' }), []);
assert.deepEqual(P.rowsOf({ total: 5 }), []);

// ===== taskView：后端七态 → 页面四态 =====
assert.equal(P.taskView('SUCCEEDED'), 'completed');
assert.equal(P.taskView('FAILED'), 'failed');
assert.equal(P.taskView('SUBMISSION_UNCERTAIN'), 'uncertain');
assert.equal(P.taskView('ARCHIVING'), 'generating', 'ARCHIVING 归生成中');
for (const status of ['QUEUED', 'SUBMITTING', 'RUNNING']) assert.equal(P.taskView(status), 'generating', status + ' 归生成中');
assert.equal(P.taskView(''), 'generating', '未知状态归生成中');
assert.equal(P.taskView(undefined), 'generating', 'undefined 归生成中');

// ===== progressOf：区间进度示意 =====
assert.equal(P.progressOf('SUCCEEDED'), 100);
assert.equal(P.progressOf('FAILED'), 0);
assert.equal(P.progressOf('SUBMISSION_UNCERTAIN'), 0);
assert.equal(P.progressOf('QUEUED'), 10);
assert.equal(P.progressOf('SUBMITTING'), 30);
assert.equal(P.progressOf('RUNNING'), 63);
assert.equal(P.progressOf('ARCHIVING'), 63);

// ===== F11 mapResolution：v3 三档 → H3 两档（1080P→2K、720P→768P）=====
assert.equal(P.mapResolution('2K'), '2K');
assert.equal(P.mapResolution('768P'), '768P');
assert.equal(P.mapResolution('720P'), '768P');
assert.equal(P.mapResolution(''), '768P', '空值兜底 768P');
assert.equal(P.mapResolution('4K'), '768P', '未知值兜底 768P');

// ===== F11 mapDuration：'8 秒' → 8；4-15 钳制 =====
assert.equal(P.mapDuration('8 秒'), 8);
assert.equal(P.mapDuration('5 秒'), 5);
assert.equal(P.mapDuration('10 秒'), 10);
assert.equal(P.mapDuration('8秒'), 8, '无空格容忍');
assert.equal(P.mapDuration('2 秒'), 4, '低于下限钳到 4');
assert.equal(P.mapDuration('20 秒'), 15, '高于上限钳到 15');
assert.equal(P.mapDuration(''), 8, '缺省 8 秒');
assert.equal(P.mapDuration('abc'), 8, '非法输入缺省 8 秒');

// ===== mapRatio：三档透传，非法回 16:9 =====
assert.equal(P.mapRatio('9:16'), '9:16');
assert.equal(P.mapRatio('16:9'), '16:9');
assert.equal(P.mapRatio('1:1'), '1:1');
assert.equal(P.mapRatio('4:3'), '4:3');
assert.equal(P.mapRatio(''), '16:9');

// ===== mapVideoParameters：v3 参数组 → H3 请求参数；数量恒 1 不上送 =====
assert.deepEqual(P.mapVideoParameters([
  { key: 'resolution', value: '2K' }, { key: 'duration', value: '8 秒' }, { key: 'ratio', value: '9:16' }, { key: 'count', value: '1 个' }
]), { resolution: '2K', duration: 8, ratio: '9:16' }, 'F11 映射且无 count 键');
assert.deepEqual(P.mapVideoParameters([]), { resolution: '768P', duration: 8, ratio: '16:9' }, '空参数组缺省值');
assert.deepEqual(P.mapVideoParameters(null), { resolution: '768P', duration: 8, ratio: '16:9' }, 'null 安全');
assert.deepEqual(P.mapVideoParameters(undefined), { resolution: '768P', duration: 8, ratio: '16:9' }, 'undefined 安全');

// ===== mapReferences：本地素材 → references[{role,name}] =====
assert.deepEqual(P.mapReferences([
  { type: 'image', name: '首帧参考' }, { type: 'video' }, { type: 'audio', name: 'BGM' }, { type: 'doc' }
]), [
  { role: 'reference_image', name: '首帧参考' },
  { role: 'reference_video', name: '参考素材2' },
  { role: 'reference_audio', name: 'BGM' },
  { role: 'reference_image', name: '参考素材4' }
], 'role 映射 + 缺名补位 + 未知类型兜底图片');
assert.deepEqual(P.mapReferences([]), []);
assert.deepEqual(P.mapReferences(null), []);
assert.deepEqual(P.mapReferences([{type:'image',name:'已上传图片',assetId:'9007199254740993',mimeType:'image/png',path:'/local/preview.png'}]),
  [{role:'reference_image',name:'已上传图片',assetId:'9007199254740993',mimeType:'image/png'}], '云草稿保留稳定ID与媒体类型，不保存设备预览路径');

// ===== validateShareLink =====
assert.equal(P.validateShareLink(''), '请先粘贴视频分享链接');
assert.equal(P.validateShareLink('   '), '请先粘贴视频分享链接');
assert.equal(P.validateShareLink('不是链接'), '请输入完整的视频分享链接');
assert.equal(P.validateShareLink('https://v.douyin.com/abc123/'), '');
assert.equal(P.validateShareLink('http://xhslink.com/x'), '');

// ===== validatePublicationForm：对齐后端 INPUT_INVALID；定时≥1 分钟 =====
const now = new Date(2026, 10, 2, 10, 0, 0).getTime(); // 与页面同一套本地时间解析，不绑定运行机时区
assert.equal(P.validatePublicationForm({ title: '', publishText: 'x' }, now), '请填写标题');
assert.equal(P.validatePublicationForm({ title: 't', publishText: ' ' }, now), '请填写发布文案');
assert.equal(P.validatePublicationForm({ title: 't', publishText: 'x', scheduled: '2026-13-99 99:99' }, now), '请选择有效的定时时间');
assert.equal(P.validatePublicationForm({ title: 't', publishText: 'x', scheduled: '2026-11-02 10:00' }, now), '定时发布需至少提前1分钟', '定时早于 1 分钟拒绝');
assert.equal(P.validatePublicationForm({ title: 't', publishText: 'x', scheduled: '2026-11-02 10:30' }, now), '', '提前 30 分钟通过');
assert.equal(P.validatePublicationForm({ title: 't', publishText: 'x' }, now), '', '立即发布通过');

// ===== validatePublishedUrl：对齐 studio.normalizePublishedUrl 语义 =====
assert.equal(P.validatePublishedUrl(''), '请填写发布后的作品链接');
assert.equal(P.validatePublishedUrl('ftp://example.com/x'), '请输入有效的 http 或 https 作品链接');
assert.equal(P.validatePublishedUrl('example.com/x'), '请输入有效的 http 或 https 作品链接');
assert.equal(P.validatePublishedUrl('https://'), '请输入有效的 http 或 https 作品链接');
assert.equal(P.validatePublishedUrl('https://example.com/p/1'), '');
assert.equal(P.validatePublishedUrl('http://example.com:8080/p?q=1#f'), '');
assert.equal(P.validatePublishedUrl('https://' + 'a'.repeat(2000) + '.com/x'), '请输入有效的 http 或 https 作品链接', '超 2000 长度拒绝');

// ===== degradeMessage：网络态降级文案 / 业务错误透传 =====
assert.equal(P.degradeMessage({ message: 'request:fail timeout' }), '网络暂不可用，请稍后重试');
assert.equal(P.degradeMessage({ errMsg: 'request:fail abort' }), '网络暂不可用，请稍后重试');
assert.equal(P.degradeMessage({ message: '链接格式不正确' }), '链接格式不正确');
assert.equal(P.degradeMessage(null), '操作未完成，请稍后重试');

// ===== pollTask：wx.request 桩下终态收敛 / 失败 / 不确定 / 超时 =====
(async () => {
  const queue = [];
  global.wx = { getStorageSync: key => key === 'zs-api-base' ? 'https://api.test' : '', setStorageSync() {}, removeStorageSync() {}, request: options => queue.push(options) };
  const respond = payload => { const options = queue.shift(); assert.ok(options, '请求已入队'); options.success({ statusCode: 200, data: { code: 0, data: payload } }); };
  const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

  // RUNNING → SUCCEEDED 收敛（result.text 带回）
  const done = P.pollTask('t1', { intervalMs: 1 }).then(task => {
    assert.equal(task.status, 'SUCCEEDED');
    assert.equal(task.result.text, '改写后的文案');
  });
  respond({ id: 't1', status: 'RUNNING' }); // 首个请求同步入队，立即回 RUNNING
  await wait(5); // 等 tick 重排下一轮
  respond({ id: 't1', status: 'SUCCEEDED', result: { text: '改写后的文案' } });
  await done;

  // FAILED / SUBMISSION_UNCERTAIN 直接终态（不等待 interval）
  const failedPromise = P.pollTask('t2', { intervalMs: 1000 });
  respond({ id: 't2', status: 'FAILED', failedReason: '引擎超时' });
  assert.equal(P.taskView((await failedPromise).status), 'failed');
  const uncertainPromise = P.pollTask('t3', { intervalMs: 1000 });
  respond({ id: 't3', status: 'SUBMISSION_UNCERTAIN' });
  assert.equal(P.taskView((await uncertainPromise).status), 'uncertain');

  // 连续网络错容忍：连续 2 次失败不终止（重试复用间隔），第 3 次失败整体 reject
  const tolerant = P.pollTask('t6', { intervalMs: 1 }).then(task => {
    assert.equal(task.status, 'SUCCEEDED', '连续 2 次失败后恢复仍收敛');
  });
  respond({ id: 't6', status: 'RUNNING' });
  await wait(5);
  for (let i = 0; i < 2; i++) { queue.shift().success({ statusCode: 500, data: { code: 500, msg: '网络异常，请稍后重试' } }); await wait(5); }
  respond({ id: 't6', status: 'SUCCEEDED', result: { text: 'x' } });
  await tolerant;
  const exhausted = P.pollTask('t7', { intervalMs: 1 }).then(() => { throw Error('应 reject'); }, error => assert.equal(error.message, '网络异常，请稍后重试'));
  respond({ id: 't7', status: 'RUNNING' });
  await wait(5);
  for (let i = 0; i < 3; i++) { queue.shift().success({ statusCode: 500, data: { code: 500, msg: '网络异常，请稍后重试' } }); await wait(5); }
  await exhausted;

  // 取消钩子：cancel() 终止轮询并以 {cancelled:true} reject（页面 onUnload 调用）
  const cancellable = P.pollTask('t8', { intervalMs: 1 });
  assert.equal(typeof cancellable.cancel, 'function', 'pollTask 应挂 cancel 取消钩子');
  respond({ id: 't8', status: 'RUNNING' });
  await wait(5);
  const outcome = await cancellable.cancel().then(() => 'resolved', error => (error && error.cancelled) ? 'cancelled' : 'rejected');
  assert.equal(outcome, 'cancelled', 'cancel 后 promise 以 cancelled reject');
  const queuedAfterCancel = queue.length;
  await wait(5);
  assert.equal(queue.length, queuedAfterCancel, '取消后不再排队新请求');
  queue.length = 0; // 丢弃取消时在途的桩请求，防污染后续用例（真实场景该请求响应后被 cancelled 守卫吞掉）

  // 请求层错误透传 reject（超容忍上限）
  const rejected = P.pollTask('t4', { intervalMs: 1, maxConsecutiveErrors: 0 }).then(() => { throw Error('应 reject'); }, error => assert.equal(error.message, '网络异常，请稍后重试'));
  const options = queue.shift(); assert.ok(options, 't4 请求已入队');
  options.success({ statusCode: 500, data: { code: 500, msg: '网络异常，请稍后重试' } });
  await rejected;

  // 超时：timeoutMs=0 时首个非终态响应后即拒绝并提示任务中心
  const timedOut = P.pollTask('t5', { intervalMs: 1, timeoutMs: 0 }).then(() => { throw Error('应超时拒绝'); }, error => assert.match(error.message, /任务中心/));
  respond({ id: 't5', status: 'RUNNING' });
  await timedOut;

  delete global.wx;

  // ===== B2/B1 防回归：写命令幂等键 + showLoading 缩短 + 轮询取消接线（静态扫描） =====
  const apiSource = fs.readFileSync(path.join(root, 'miniprogram/api/franchise/content.js'), 'utf8');
  for (const [name, action] of [['saveWork', 'save-work'], ['updateStatus', 'work-status'], ['completeChapter', 'chapter'], ['verify', 'pub-verify'], ['link', 'pub-link'], ['fail', 'pub-fail']]) {
    assert.match(apiSource, new RegExp(name + ':\\s*\\([^)]*key\\)[\\s\\S]{0,160}?idem\\(key,\\s*\'' + action + '\''), 'content.' + name + ' 未带 Idempotency-Key');
  }
  const studioSrc = fs.readFileSync(path.join(root, 'miniprogram/pages/studio/index.js'), 'utf8');
  assert.match(studioSrc, /contentKey\(/, 'studio 页缺内容域幂等键 helper');
  assert.match(studioSrc, /key:this\.contentKey\('task'\)/, 'studio 任务创建未冻结幂等键');
  assert.match(studioSrc, /tasks\.create\(attempt\.payload,attempt\.key\)/, 'studio 任务未使用已冻结请求与幂等键');
  assert.match(studioSrc, /saveWork\(taskId,[^\n]*this\.contentKey\('save-work:'/, 'studio 生成链 saveWork 未带幂等键');
  assert.match(studioSrc, /saveWork\(this\.data\.taskId,[^\n]*this\.contentKey\('save-work:'/, 'studio 结果页 saveWork 未带幂等键');
  assert.match(studioSrc, /publications\.verify\(recordId,this\.contentKey\('verify:/, 'studio verify 未带幂等键');
  assert.match(studioSrc, /publications\.link\(recordId,url,this\.contentKey\('link:/, 'studio link 未带幂等键');
  assert.doesNotMatch(studioSrc, /showLoading\(\{title:'生成口播中'/, 'studio showLoading 不得覆盖全程轮询');
  assert.match(studioSrc, /showLoading\(\{title:'提交生成任务'/, 'studio showLoading 应缩短为提交阶段');
  assert.match(studioSrc, /onUnload\(\)[\s\S]{0,200}__digitalPoll[\s\S]{0,80}\.cancel\(\)/, 'studio onUnload 未终止任务轮询');
  assert.match(studioSrc, /patchTaskProgress/, 'studio 缺任务中心轮询进度回填');
  const creationSrc2 = pageSource('creation');
  assert.doesNotMatch(creationSrc2, /showLoading\(\{title:'解析链接中'/, 'creation showLoading 不得覆盖全程轮询');
  assert.match(creationSrc2, /this\.setData\(\{extracting:true/, 'creation 提交开始即进入按钮忙碌态');
  assert.match(creationSrc2, /onUnload\(\)[\s\S]{0,120}__parsePoll[\s\S]{0,80}\.cancel\(\)/, 'creation onUnload 未终止解析轮询');
  assert.match(creationSrc2, /extracting: true|extracting:true/, 'creation 缺轮询按钮态');
  const growthSrc2 = fs.readFileSync(path.join(root, 'miniprogram/pages/growth/index.js'), 'utf8');
  assert.match(growthSrc2, /__captureKey/, 'growth 客资登记缺一次性幂等键');
  assert.match(growthSrc2, /leads\.capture\(\{name,phone,need,source\},this\.__captureKey\)/, 'growth capture 未传幂等键');
  const creationWxml = fs.readFileSync(path.join(root, 'miniprogram/pages/creation/index.wxml'), 'utf8');
  assert.match(creationWxml, /disabled="\{\{extracting\}\}"/, 'creation 提取按钮缺轮询禁用态');

  // ===== 四页接线静态扫描：require platform-content 或 content api =====
  const PAGES = ['creation', 'studio', 'ai-video', 'growth'];
  for (const page of PAGES) {
    const source = pageSource(page);
    assert.match(source, /require\((['"])[.\/]*(?:utils\/)?platform-content\1\)|require\((['"])[.\/]*api\/franchise\/content\2\)/,
      page + ' 页未接线内容域契约层');
    // 在线判定与降级兜底同在：http.token() 分流 + degradeMessage 兜底
    assert.match(source, /http\.token\(\)/, page + ' 页缺在线判定 http.token()');
  }
  const studioSource = fs.readFileSync(path.join(root, 'miniprogram/pages/studio/index.js'), 'utf8');
  assert.match(studioSource, /payload:\{kind:'digital'/, 'studio 页 digital 生成缺少数字人口播参数');
  assert.match(studioSource, /tasks\.create\(attempt\.payload,attempt\.key\)/, 'studio 页 digital 生成未接 tasks.create');
  assert.match(studioSource, /publications\.create\(/, 'studio 页发布创建未接 publications.create');
  assert.match(studioSource, /publications\.link\(/, 'studio 页发布链接未接 publications.link');
  assert.match(studioSource, /1_114_000_003/, '重复链接错误码未处理');
  assert.match(studioSource, /tasks\.page\(\)/, '任务中心未接 tasks.page');
  const creationSource = pageSource('creation');
  assert.match(creationSource, /links\.parse\(/, 'creation 页未接 links.parse');
  const videoSource = fs.readFileSync(path.join(root, 'miniprogram/pages/ai-video/index.js'), 'utf8');
  assert.match(videoSource, /kind:\s*'ai_video'/, 'ai-video 页未接 ai_video 任务创建');
  assert.match(videoSource, /mapVideoParameters/, 'ai-video 页未用 F11 参数映射');
  assert.match(videoSource, /saveVideoDraft/, 'ai-video 页未同步视频草稿');
  const growthSource = fs.readFileSync(path.join(root, 'miniprogram/pages/growth/index.js'), 'utf8');
  assert.match(growthSource, /growth\.summary\(\)/, 'growth 页未接 growth.summary');
  assert.match(growthSource, /leads\.capture\(/, 'growth 页未接 crm leads.capture');
  assert.match(growthSource, /内容发布:/, 'growth 页客资来源缺发布前缀');

  // 红线守卫：studio.js / growth.js 导出面未被破坏（其他页面在用）
  global.wx = { getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {} };
  const studioExports = require('../miniprogram/utils/studio');
  for (const name of ['KEY', 'title', 'original', 'script', 'initial', 'scopedKey', 'scope', 'guard', 'allowed', 'read', 'write',
    'completeTask', 'saveWork', 'createPublication',
    'normalizePublishedUrl', 'scheduleError', 'imageFor', 'kindLabel', 'formatTime', 'recentItems', 'restoreManualDraft', 'accounts']) {
    assert.ok(name in studioExports, 'studio.js 导出缺失：' + name);
  }
  delete global.wx;

  console.log('verify-content-pages ✔  全部断言通过');
})().catch(error => { console.error('verify-content-pages ✘ ' + (error && error.message)); process.exit(1); });
