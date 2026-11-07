const { createDashboard } = require('../../utils/dashboard');
const PTPage = require('../../utils/platform-todos-page');
Page(createDashboard(2, {
data: { panel: null, deskTitle: '工程工作台', roleLabel: '', active: false, projectCount: 0, activeCount: 0, todoCount: 0, platformTodos: null, tools: [{ title: '我的项目', icon: 'folder', action: 'projects' }, { title: '施工计划', icon: 'calendar', action: 'schedule' }, { title: '材料计划', icon: 'material', action: 'materials' }, { title: '节点款项', icon: 'tasks', action: 'payments' }] },
onLoad() { PTPage.init(this); },
// 工程 tab：工程岗重定向到工程首页；其余岗位（老板等）走平台 API 态——项目计数 + 平台待办，无本地演示数据。
onShow() {
const http = require('../../api/http'), auth = require('../../api/auth');
const post = (() => { try { return http.token() ? auth.currentApiPost() : null; } catch (e) { return null; } })();
if (post && require('../../utils/organization').has(post.roleCode, 'engineer')) { wx.redirectTo({ url: '/pages/build/home/index' }); return; }
const a = require('../../utils/access').context();
const X = require('../../utils/access'), O = require('../../utils/organization');
const nav = require('../../utils/workbench-nav')();
this.setData({ ...nav, panel: null, canWork: nav.canEngineering, active: !nav.guest, deskTitle: '工程工作台', roleLabel: post ? (O.roles[post.roleCode] || post.roleCode) : (X.roles[a.role] || '访客'), projectCount: 0, activeCount: 0, todoCount: 0 });
if (this.getTabBar()) { this.getTabBar().refresh?.(); this.getTabBar().setData({ selected: 2, hidden: false }); }
PTPage.refresh(this, false);
this.refreshProjectCounts();
},
refreshProjectCounts() {
const P = require('../../utils/platform-engineering');
if (!P.online()) return;
P.api.projects.page().then(rows => {
const count = rows.length, active = rows.filter(r => r.status === 'IN_PROGRESS' || r.status === 'READY_DELIVER').length;
this.setData({ projectCount: count, activeCount: active, todoCount: count });
}).catch(() => {});
},
openAction(e) {
const key = e.currentTarget.dataset.action;
if (key === 'login') { wx.navigateTo({ url: '/pages/account/index?view=login' }); return; }
const routes = { projects: '/pages/build/projects/index', schedule: '/pages/build/projects/index?destination=schedule', materials: '/pages/build/materials/index', payments: '/pages/build/projects/index?destination=payments', todos: '/pages/build/projects/index?mode=tasks' };
if (routes[key]) wx.navigateTo({ url: routes[key] });
},
retryPlatformTodos() { PTPage.retry(this); },
openPlatformTodo(e) { PTPage.open(this, e); }
}));
