const { createDashboard } = require('../../utils/dashboard');
const PTPage = require('../../utils/platform-todos-page');
Page(createDashboard(2, {
data: { panel: null, deskTitle: '工程工作台', roleLabel: '', homeName: '', active: false, projectCount: 0, activeCount: 0, pendingCount: 0, deliveryCount: 0, todoCount: 0, platformTodos: null, tools: [{ title: '我的项目', icon: 'folder', action: 'projects' }, { title: '施工计划', icon: 'calendar', action: 'schedule' }, { title: '材料计划', icon: 'material', action: 'materials' }, { title: '节点款项', icon: 'tasks', action: 'payments' }] },
onLoad() { PTPage.init(this); },
// 工程 tab：工程岗重定向到工程首页；其余岗位（老板等）走平台 API 态——项目计数 + 平台待办，无本地演示数据。
onShow() {
const a = require('../../utils/access').context();
const X = require('../../utils/access'), O = require('../../utils/organization');
const http = require('../../api/http'), auth = require('../../api/auth');
const post = (() => { try { return http.token() ? auth.currentApiPost() : null; } catch (e) { return null; } })();
if (post ? O.has(post.roleCode, 'engineer') : a.active && O.has(a.role, 'engineer')) { wx.reLaunch({ url: '/pages/build/home/index' }); return; }
const nav = require('../../utils/workbench-nav')();
this.setData({ ...nav, panel: null, canWork: nav.canEngineering, active: !nav.guest, deskTitle: '工程工作台', homeName:post&&post.name||'同事', roleLabel: post ? (O.roles[post.roleCode] || post.roleCode) : (X.roles[a.role] || '访客'), projectCount: '—', activeCount: '—', pendingCount: '—', deliveryCount: '—', todoCount: '—' });
if (this.getTabBar()) { this.getTabBar().refresh?.(); this.getTabBar().setData({ selected: 2, hidden: false }); }
PTPage.refresh(this, false);
this.refreshProjectCounts();
},
refreshProjectCounts() {
const P = require('../../utils/platform-engineering');
if (!P.online()) return;
const http=require('../../api/http'),alive=http.requestGuard('engineeringOverview');
http.get('/platform/profile/mine').then(profile=>{if(alive()&&profile&&profile.nickname)this.setData({homeName:profile.nickname});}).catch(()=>{});
P.api.projects.page().then(rows => {
if(!alive())return;
const count = rows.length, active = rows.filter(r => r.status === 'IN_PROGRESS').length;
this.setData({ projectCount: count, activeCount: active, pendingCount:rows.filter(r=>r.status==='PENDING_START').length, deliveryCount:rows.filter(r=>r.status==='READY_DELIVER').length, todoCount: count });
}).catch(() => {});
},
openAction(e) {
const key = e.currentTarget.dataset.action;
if (key === 'login') { wx.navigateTo({ url: '/pages/account/index?view=login' }); return; }
const routes = { active:'/pages/build/projects/index?filter='+encodeURIComponent('在建'), pending:'/pages/build/projects/index?filter='+encodeURIComponent('待开工'), delivery:'/pages/build/projects/index?filter='+encodeURIComponent('待交付'), prepare:'/pages/build/projects/index?destination=prepare', projects: '/pages/build/projects/index', schedule: '/pages/build/projects/index?destination=schedule', materials: '/pages/build/projects/index?destination=materials', payments: '/pages/build/projects/index?destination=payments', todos: '/pages/build/projects/index?mode=tasks' };
if (routes[key]) wx.navigateTo({ url: routes[key] });
},
retryPlatformTodos() { PTPage.retry(this); },
openPlatformTodo(e) { PTPage.open(this, e); }
}));
