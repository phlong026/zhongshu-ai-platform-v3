const Job=require('./organization');
const Access=require('./access');
const KEY = 'zhongshu-studio',AUTH=Symbol('studio-command');
const accounts = [{ id: 'douyin-adviser', platform: 'douyin', name: '众墅之家 · 建房顾问' }, { id: 'channels-design', platform: 'channels', name: '众墅之家 · 乡墅设计' }];
let sequence = 0;
const clone = value => JSON.parse(JSON.stringify(value));
const uid = prefix => prefix + '-' + Date.now() + '-' + (++sequence);
function imageFor(item) { return item.image || (item.kind === 'ai' ? '/assets/creation-house.jpg' : '/assets/studio-portrait-wide.jpg'); }
function kindLabel(kind) { return kind === 'ai' ? 'AI视频' : kind === 'digital' ? '数字人口播' : '示例作品'; }
function formatTime(value) {
if (!value) return '时间未记录';
const date = new Date(value);
if (!Number.isFinite(date.getTime())) return '时间未记录';
const pad = n => String(n).padStart(2, '0');
return (date.getMonth() + 1) + '月' + date.getDate() + '日 ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
}
const title = '二层乡墅，怎样住得舒适';
const original = '盖一栋二层乡墅，先想清楚家人怎样生活。\n客厅的采光、厨房的位置，都影响日常。\n别只看外观，住起来舒服才重要。';
const script = '想把二层乡墅住得舒适，先从家人的日常出发。\n客厅留足采光，餐厨动线顺畅，卧室兼顾安静通风。\n庭院大小适合打理，比一味求大更重要。\n把居住人数和生活习惯告诉我，一起梳理适合的建房思路。';
function initial() {
const now = Date.parse('2026-10-17T10:45:00+08:00');
return {
version: 2, title, original, script,
tasks: [
{ id: 'task-progress', title: '乡墅厨房怎么布局', status: 'generating', saved: false, progress: 63 },
{ id: 'task-complete', title, status: 'completed', saved: false, progress: 100 },
{ id: 'task-retry', title: '庭院设计的三个细节', status: 'save-failed', saved: false, progress: 100 }
].map((task, i) => Object.assign({ kind: 'digital', script, demo: true, example: true, createdAt: now - (i + 1) * 60000, configuration: { actor: '李顾问', voice: '李顾问原声' } }, task)),
records: [
{ id: 'record-progress', title, platform: 'channels', account: '众墅之家 · 乡墅设计', time: '2026-10-17 10:40', status: 'publishing' },
{ id: 'record-success', title, platform: 'douyin', account: '众墅之家 · 建房顾问', time: '2026-10-17 10:32', status: 'published' },
{ id: 'record-verify', title, platform: 'channels', account: '众墅之家 · 乡墅设计', time: '2026-10-16 16:20', status: 'verify' },
{ id: 'record-error', title, platform: 'douyin', account: '众墅之家 · 建房顾问', time: '2026-10-16 15:10', status: 'failed' }
].map((record, i) => Object.assign({}, record, { taskId: 'task-complete', kind: 'digital', cover: '/assets/studio-portrait-wide.jpg', publishText: script, tags: '#乡墅设计 #农村建房', demo: true, example: true, snapshotDate:'2026-10-17',createdAt:Date.parse(record.time.replace(' ','T')+':00+08:00')-60000, submittedAt:Date.parse(record.time.replace(' ','T')+':00+08:00')-60000, publishedAt:record.status==='published'?Date.parse(record.time.replace(' ','T')+':00+08:00'):null })),
works: [], publishDrafts: {}
};
}
function storageKey(){const a=wx.getStorageSync('zhongshu-account-v1'),s=a&&a.session;if(!s)return KEY+':guest';const raw=wx.getStorageSync('zhongshu-management-v1'),book=raw&&(raw.companyId===s.companyId?raw:(raw.books||{})[s.companyId]),person=book&&(book.staff||[]).find(p=>p.id===(s.staffId||String(s.userId||'').replace(/^demo-/,'')));if(!Access.context().active||!Job.has(Access.context().role,'sales')||person&&person.active===false)return KEY+':disabled:'+s.companyId+':'+s.userId;return s.loggedIn&&s.userId==='demo-zhang'&&s.companyId==='quanzhou'?KEY:KEY+':'+(s.loggedIn?s.companyId+':'+s.userId:'guest');}
function scopedKey(base){return base+storageKey().slice(KEY.length);}
function read() {
const raw = wx.getStorageSync(storageKey());
const state = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : initial();
state.tasks = Array.isArray(state.tasks) ? state.tasks.filter(Boolean) : [];
state.works = Array.isArray(state.works) ? state.works.filter(Boolean) : [];
state.records = Array.isArray(state.records) ? state.records.filter(Boolean) : [];
state.title = typeof state.title === 'string' ? state.title : title;
state.original = typeof state.original === 'string' ? state.original : original;
state.script = typeof state.script === 'string' ? state.script : script;
state.publishDrafts = state.publishDrafts && typeof state.publishDrafts === 'object' ? state.publishDrafts : {};
if (state.publishDraft && state.publishDraft.workId && !state.publishDrafts[state.publishDraft.workId]) state.publishDrafts[state.publishDraft.workId] = clone(state.publishDraft);
delete state.publishDraft;
state.version = 2;state._scope=storageKey();
state.tasks = state.tasks.map(task => Object.assign({}, task, { kind: task.kind || 'digital', script: typeof task.script === 'string' ? task.script : (['task-progress', 'task-complete', 'task-retry'].includes(task.id) ? script : ''), configuration: task.configuration || {}, demo: true }));
state.works = state.works.map(work => {
const task = state.tasks.find(item => item.id === work.taskId);
return Object.assign({}, work, { kind: work.kind || (task && task.kind) || '', image: work.image || imageFor(task || {}), demo: true });
});
return state;
}
function allowed(){return Access.context().active&&Job.has(Access.context().role,'sales');}
function guard(){if(allowed())return true;wx.redirectTo({url:'/pages/account/index?view='+(Access.context().active?'access':'login')});return false;}
function write(state,token) { const old=read();if(token!==AUTH&&['tasks','works','records'].some(k=>JSON.stringify(state[k])!==JSON.stringify(old[k])))throw Error('作品和发布状态只能通过对应办理动作更新'); if(!allowed())throw Error('当前人员未获创作授权');if(state._scope&&state._scope!==storageKey())throw Error('工作身份已变化，请重新打开本人创作');wx.setStorageSync(storageKey(), state); return state; }
function createDemoTask(kind, description, configuration) {
const state = read();
const config = clone(configuration || {});if(!['ai','digital'].includes(kind)||!String(kind==='ai'?description:state.script).trim())throw Error('请填写生成内容');
const count = (config.parameters || []).find(item => item.key === 'count');
if (count && count.value !== '1 个') throw new Error('演示模式每次生成1个结果');
const task = { id: uid('demo'), title: kind === 'ai' ? description.trim().slice(0, 28) : state.title, script: kind === 'ai' ? description : state.script, configuration: config, kind, status: 'generating', saved: false, progress: 0, demo: true, createdAt: Date.now() };
task.contentSource=clone({extraction:state.extraction||null,rewrite:state.rewriteHistory?.at(-1)||null});
task.image = imageFor(task);
state.tasks.unshift(task); write(state,AUTH); return task;
}
function completeTask(id) {
const state = read(); const task = state.tasks.find(task => task.id === id);
if (!task) throw new Error('任务不存在或已清理');
if(task.status==='completed')return task;task.status = 'completed'; task.progress = 100; task.completedAt = Date.now(); write(state,AUTH);
return task;
}
function saveWork(id) {
const state = read(); const task = state.tasks.find(task => task.id === id);
if (!task) throw new Error('任务不存在或已清理');
if (!['completed', 'save-failed'].includes(task.status)) throw new Error('任务尚未完成，请先查看进度');
const existing = state.works.find(work => work.taskId === task.id);
if (existing) return existing;
const work = { id: uid('work'), taskId: task.id, title: task.title, script: task.script || '', kind: task.kind, contentSource:clone(task.contentSource||{}),configuration: clone(task.configuration || {}), image: imageFor(task), demo: true, savedAt: Date.now() };
task.saved = true; task.status = 'completed'; state.works.unshift(work); write(state,AUTH); return work;
}
function scheduleError(value, now) {
if (!value) return '';
const match = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})$/.exec(value);
if (!match) return '请选择有效的日期和时间';
const p = match.slice(1).map(Number);
const date = new Date(p[0], p[1] - 1, p[2], p[3], p[4]);
if (date.getFullYear() !== p[0] || date.getMonth() !== p[1] - 1 || date.getDate() !== p[2] || date.getHours() !== p[3] || date.getMinutes() !== p[4]) return '请选择有效的日期和时间';
return date.getTime() < (now === undefined ? Date.now() : now) + 60000 ? '定时发布需至少提前1分钟，请重新选择' : '';
}
function createPublication(workId, form) {
const state = read(); const work = state.works.find(work => work.id === workId);
if (!work) throw new Error('作品不存在或已清理，请重新选择');
const account = accounts[form.account];
if (!account) throw new Error('请选择有效的发布账号');
if (!String(form.title || '').trim() || !String(form.publishText || '').trim()) throw new Error('请填写标题和发布文案');
const invalidSchedule = scheduleError(form.scheduled);
if (invalidSchedule) throw new Error(invalidSchedule);
const content=require('./growth').prepare(workId,form,account);if(!['审核通过','发布中','演示已发布'].includes(content.status))return {reviewRequired:true,contentId:content.id};require('./growth').assertPublish(content,Access.context());if(content.review?.autoReview&&(!form.publishConfirmed||form.confirmationSignature!==content.signature))return {confirmationRequired:true,contentId:content.id};
const existing = state.records.find(record => record.workId === workId && record.platform === account.platform && record.account === account.name && record.status !== 'failed');
if (existing) return { record: existing, duplicate: true };
const now = Date.now();
const record = { id: uid('demo-record'), workId, taskId: work.taskId, kind: work.kind, title: form.title.trim(), publishText: form.publishText.trim(), tags: String(form.tags || '').trim(), cover: form.cover || work.image, platform: account.platform, accountId: account.id, account: account.name, status: form.scheduled ? 'scheduled' : 'publishing', demo: true, scheduledAt: form.scheduled || '', createdAt: now, submittedAt: now, publishedAt: null, workSnapshot: clone(work),contentId:content.id,contentSignature:content.signature };
state.records.unshift(record); write(state,AUTH);require('./growth').publishing(content.id,record.id);return { record, duplicate: false };
}
function confirmDemoPublication(id){const state=read(),r=state.records.find(r=>r.id===id);if(!r||!r.demo||!['publishing','verify','scheduled','published'].includes(r.status))throw Error('请选择可核实的原演示发布');if(r.contentId){const c=require('./growth').load().contents.find(c=>c.id===r.contentId);require('./growth').assertPublish(c,Access.context());}r.status='published';r.publishedAt=r.publishedAt||Date.now();write(state,AUTH);if(r.contentId)require('./growth').published(id);return r;}
function recentItems(state) {
const list = [];
const draft = wx.getStorageSync(scopedKey('zhongshu-creation-draft'));
if (draft && (draft.workingText || draft.text)) list.push({ id: 'draft', title: (draft.workingText || draft.text).split('\n')[0].slice(0, 28), badge: '文案草稿', image: '/assets/creation-house.jpg', action: '继续编辑', updatedAt: draft.updatedAt || 0 });
const video = wx.getStorageSync(scopedKey('zhongshu-video-draft'));
if (video && video.description) list.push({ id: 'video-draft', title: video.description.slice(0, 28), badge: 'AI视频草稿', image: video.firstFrame || '/assets/creation-house.jpg', action: '继续编辑', updatedAt: video.updatedAt || 0 });
Object.keys(state.videoTaskDrafts || {}).forEach(taskId => {
const draft = state.videoTaskDrafts[taskId];
if (draft.description && state.tasks.some(task => task.id === taskId)) list.push({ id: 'video-edit-' + taskId, editTask: taskId, title: draft.description.slice(0, 28), badge: '历史任务草稿', image: draft.firstFrame || '/assets/creation-house.jpg', action: '继续编辑', updatedAt: draft.updatedAt || 0 });
});
state.works.forEach(work => list.push({ id: work.id, taskId: work.taskId, title: work.title, badge: '已保存', image: work.image, action: '查看结果', updatedAt: work.savedAt || 0 }));
list.sort((a, b) => b.updatedAt - a.updatedAt);
if (!list.length) {
const example = state.tasks.find(task => task.id === 'task-complete');
if (example) list.push({ id: 'example-result', taskId: example.id, title: example.title, badge: '示例作品', image: imageFor(example), action: '查看示例' });
}
return list;
}
function restoreManualDraft() {
const draft = wx.getStorageSync(scopedKey('zhongshu-creation-draft'));
if (!draft || !draft.text || (typeof draft.workingText === 'string' && draft.workingText !== draft.text)) return false;
const state = read(); delete state.extraction;state.rewriteHistory=[];state.original = draft.text; state.script = draft.rewrittenText || draft.text; state.title = draft.text.split('\n')[0].slice(0, 28); delete state.editorBuffer; write(state); return true;
}
module.exports = { KEY, title, original, script, initial,scopedKey,scope:storageKey,guard,allowed, read, write, createDemoTask, completeTask, saveWork, createPublication, confirmDemoPublication, scheduleError, imageFor, kindLabel, formatTime, recentItems, restoreManualDraft, accounts };
