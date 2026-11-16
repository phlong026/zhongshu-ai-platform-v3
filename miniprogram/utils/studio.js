require('./runtime-compat');
const Job=require('./organization');
const Access=require('./access');
const KEY = require('./storage-keys').studio,AUTH=Symbol('studio-command');
const accounts = [{ id: 'douyin-adviser', platform: 'douyin', name: '众墅之家 · 建房顾问' }, { id: 'channels-design', platform: 'channels', name: '众墅之家 · 乡墅设计' }];
// 首发后端只登记人工发布事实，不提供组织账号授权目录；线上页面只展示平台并由办理人填写实际账号。
const publicationPlatforms = [{ platform: 'douyin', name: '抖音' }, { platform: 'channels', name: '视频号' }];
let sequence = 0;
const clone = require('./clone');
const uid = prefix => prefix + '-' + Date.now() + '-' + (++sequence);
function imageFor(item) { return item.image || (item.kind === 'ai' ? '/assets/creation-house.jpg' : '/assets/studio-portrait-wide.jpg'); }
function kindLabel(kind) { return kind === 'ai' ? 'AI视频' : kind === 'digital' ? '数字人口播' : '参考作品'; }
function formatTime(value) {
if (!value) return '时间未记录';
const date = new Date(value);
if (!Number.isFinite(date.getTime())) return '时间未记录';
const pad = n => String(n).padStart(2, '0');
return (date.getMonth() + 1) + '月' + date.getDate() + '日 ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
}
const title = '';
const original = '';
const script = '';
function initial() {
return { version: 3, title: '', original: '', script: '', tasks: [], records: [], works: [], publishDrafts: {} };
}
function storageKey(){const post=apiSalesPost(),local=Access.context();if(post&&!(local.active&&Job.has(local.role,'sales')))return KEY+':api:'+String(post.postId);const a=wx.getStorageSync(require('./storage-keys').account),s=a&&a.session;if(!s)return KEY+':guest';const raw=wx.getStorageSync(require('./storage-keys').management),book=raw&&(raw.companyId===s.companyId?raw:(raw.books||{})[s.companyId]),person=book&&(book.staff||[]).find(p=>p.id===(s.staffId||String(s.userId||'').replace(/^demo-/,'')));if(!Access.context().active||!Job.has(Access.context().role,'sales')||person&&person.active===false)return KEY+':disabled:'+s.companyId+':'+s.userId;return s.loggedIn&&s.userId==='demo-zhang'&&s.companyId==='quanzhou'?KEY:KEY+':'+(s.loggedIn?s.companyId+':'+s.userId:'guest');}
function scopedKey(base){return base+storageKey().slice(KEY.length);}
function read() {
const raw = wx.getStorageSync(storageKey());
const state = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : initial();
state.tasks = Array.isArray(state.tasks) ? state.tasks.filter(Boolean) : [];
state.works = Array.isArray(state.works) ? state.works.filter(Boolean) : [];
state.records = Array.isArray(state.records) ? state.records.filter(Boolean) : [];
// Discard only explicitly marked demo records and the old built-in task identities.
const demoIds=['task-progress','task-complete','task-retry','record-success','record-processing','record-verify','record-failed'];
const isDemo=row=>row.demo===true||row.mock===true||demoIds.includes(String(row.id));
const demoRows=state.tasks.concat(state.works).filter(isDemo);
const demoTaskIds=state.tasks.filter(isDemo).map(row=>row.id);
const demoScripts=demoRows.map(row=>row.script).filter(Boolean);
state.tasks=state.tasks.filter(row=>!isDemo(row));
state.works=state.works.filter(row=>!isDemo(row)&&!demoTaskIds.includes(row.taskId));
state.records=state.records.filter(row=>!isDemo(row)&&!demoTaskIds.includes(row.taskId));

state.title = typeof state.title === 'string' ? state.title : title;
state.original = typeof state.original === 'string' ? state.original : original;
state.script = typeof state.script === 'string' ? state.script : '';
state.rewriteHistory = Array.isArray(state.rewriteHistory) ? state.rewriteHistory.filter(Boolean) : [];
if(!state.scriptEdited&&!state.rewriteHistory.some(row=>row.online===true)&&demoScripts.includes(state.script))state.script='';
if(!state.scriptEdited&&!state.rewriteHistory?.length&&(state.script===state.original||raw?.version<3&&state.script===script))state.script='';
state.publishDrafts = state.publishDrafts && typeof state.publishDrafts === 'object' ? state.publishDrafts : {};
if (state.publishDraft && state.publishDraft.workId && !state.publishDrafts[state.publishDraft.workId]) state.publishDrafts[state.publishDraft.workId] = clone(state.publishDraft);
delete state.publishDraft;
state.version = 3;state._scope=storageKey();
state.tasks = state.tasks.map(task => Object.assign({}, task, { kind: task.kind || 'digital', script: typeof task.script === 'string' ? task.script : '', configuration: task.configuration || {} }));
state.works = state.works.map(work => {
const task = state.tasks.find(item => item.id === work.taskId);
return Object.assign({}, work, { kind: work.kind || (task && task.kind) || '', image: work.image || imageFor(task || {}) });
});
return state;
}
function apiSalesPost(){try{const http=require('../api/http');if(!http.token())return null;const post=require('../api/auth').currentApiPost();if(post&&['sales','sales-lead','boss','owner'].includes(post.roleCode))return post;}catch(e){}return null;}
function allowed(){const local=Access.context();if(local.active&&Job.has(local.role,'sales'))return true;return !!apiSalesPost();}
function guard(){if(allowed())return true;let token=false;try{token=!!require('../api/http').token();}catch(e){}wx.redirectTo({url:'/pages/account/index?view='+(Access.context().active||token?'access':'login')});return false;}
function write(state,token) { const old=read();if(token!==AUTH&&['tasks','works','records'].some(k=>JSON.stringify(state[k])!==JSON.stringify(old[k])))throw Error('作品和发布状态只能通过对应办理动作更新'); if(!allowed())throw Error('当前人员未获创作授权');if(state._scope&&state._scope!==storageKey())throw Error('工作身份已变化，请重新打开本人创作');wx.setStorageSync(storageKey(), state); return state; }
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
const content=require('./growth').prepare(workId,form,account);require('./growth').assertPublish(content,Access.context());
const existing = state.records.find(record => record.workId === workId && record.platform === account.platform && record.account === account.name && record.status !== 'failed');
if (existing) return { record: existing, duplicate: true };
const now = Date.now();
const a=Access.context();
const record = { id: uid('demo-record'), workId, taskId: work.taskId, kind: work.kind, title: form.title.trim(), publishText: form.publishText.trim(), tags: String(form.tags || '').trim(), cover: form.cover || work.image, platform: account.platform, accountId: account.id, account: account.name, status: form.scheduled ? 'scheduled' : 'publishing', demo: true, scheduledAt: form.scheduled || '', createdAt: now, submittedAt: now, publishedAt: null, workSnapshot: clone(work),contentId:content.id,contentSignature:content.signature,publicationFlow:'direct-v1',companyId:a.companyId,teamId:a.teamId,authorId:a.staffId,authorPersonId:a.personId,authorName:a.name,publishedUrl:'',collection:{status:'awaiting-link',metrics:null} };
state.records.unshift(record); write(state,AUTH);require('./growth').publishing(content.id,record.id);return { record, duplicate: false };
}
function normalizePublishedUrl(value){const url=String(value||'').trim();if(!url)throw Error('请填写发布后的作品链接');if(url.length>2000||!/^(https?):\/\/(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}(?::\d{1,5})?(?:[/?#][^\s<>]*)?$/i.test(url))throw Error('请输入有效的 http 或 https 作品链接');return url;}
function recentItems(state) {
const list = [];
const draft = wx.getStorageSync(scopedKey(require('./storage-keys').creationDraft));
if (draft && (draft.workingText || draft.text)) list.push({ id: 'draft', title: draft.title||(draft.workingText || draft.text).split('\n')[0].slice(0, 28), badge: '文案草稿', image: '/assets/creation-house.jpg', action: '继续编辑', updatedAt: draft.updatedAt || 0 });
const video = wx.getStorageSync(scopedKey(require('./storage-keys').videoDraft));
if (video && video.description) list.push({ id: 'video-draft', title: video.description.slice(0, 28), badge: 'AI视频草稿', image: video.firstFrame || '/assets/creation-house.jpg', action: '继续编辑', updatedAt: video.updatedAt || 0 });
Object.keys(state.videoTaskDrafts || {}).forEach(taskId => {
const draft = state.videoTaskDrafts[taskId];
if (draft.description && state.tasks.some(task => task.id === taskId)) list.push({ id: 'video-edit-' + taskId, editTask: taskId, title: draft.description.slice(0, 28), badge: '历史任务草稿', image: draft.firstFrame || '/assets/creation-house.jpg', action: '继续编辑', updatedAt: draft.updatedAt || 0 });
});
state.works.forEach(work => list.push({ id: work.id, taskId: work.taskId, title: work.title, badge: '已保存', image: work.image, action: '查看结果', updatedAt: work.savedAt || 0 }));
list.sort((a, b) => b.updatedAt - a.updatedAt);
return list;
}
function restoreManualDraft() {
const draft = wx.getStorageSync(scopedKey(require('./storage-keys').creationDraft));
if (!draft || !draft.text || (typeof draft.workingText === 'string' && draft.workingText !== draft.text)) return false;
const state = read(); delete state.extraction;state.rewriteHistory=[];state.original = draft.text; state.script = draft.rewrittenText && draft.rewrittenText !== draft.text ? draft.rewrittenText : ''; state.title = draft.text.split('\n')[0].slice(0, 28); delete state.editorBuffer; write(state); return true;
}
module.exports = { KEY, title, original, script, initial,scopedKey,scope:storageKey,guard,allowed, read, write, completeTask, saveWork, createPublication,  normalizePublishedUrl, scheduleError, imageFor, kindLabel, formatTime, recentItems, restoreManualDraft, accounts, publicationPlatforms };
