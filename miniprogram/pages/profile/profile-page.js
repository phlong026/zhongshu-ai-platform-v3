// One "我的" implementation for every role. The three route shells (profile tab, sales, boss) differ only in the
// bottom navigation component they mount; content, handlers and styles all come from here and profile.wxml.
const A = require('../../utils/account');
const O = require('../../utils/organization');
const M = require('../../utils/platform-messages');
const http = require('../../api/http');
const Learning = require('../../utils/learning-catalog');
const Profile = require('../../utils/platform-profile');
const { canViewAllStores } = require('../../utils/store-map-access');

const TEAM = { 'sales-lead': ['业务团队', 'business-team'], 'project-manager': ['工程团队', 'engineering-team'], 'finance-lead': ['财务管理', 'finance-team'], owner: ['门店团队与交接', 'staff'] };
const account = (view, extra = '') => '/pages/account/index?view=' + view + extra;
const ACTIONS = {
  login: () => account('login'), profile: () => account('profile'), messages: () => account('messages'),
  performance: (engineering, id, owner) => '/pages/admin/tasks/index?view=' + (owner ? 'team' : 'mine'),
  identity: () => account('identity'), help: () => account('help'), privacy: () => account('privacy'), policy: () => account('policy'), agreement: () => account('agreement'),
  services: () => account('services'), selection: () => account('selection'),
  storeMap: () => '/pages/account/store-map/index',
  academy: () => account('learning', '&entry=academy'),
  learning: eng => account('learning', eng ? '&category=' + encodeURIComponent('工程') : ''),
  resources: eng => account('learning', '&tab=' + encodeURIComponent('资料') + (eng ? '&category=' + encodeURIComponent('工程') : '')),
  course: (eng, id) => account('course', id ? '&id=' + encodeURIComponent(id) : '')
};
// actions that need a signed-in account
const LOGIN_ONLY = ['profile', 'messages', 'performance', 'storeMap', 'academy'];

// Keep one entry per company setting; task access stays in the profile quick card.
function managementEntries(a) {
  if (!a.active) return [];
  const X = require('../../utils/access');
  if (X.isBoss(a)) {
    if (a.api) {
      const items = [{ text: '成员与权限', url: account('api-members') }];
      if (a.orgType === 'store' && a.role === 'boss') {
        items.unshift({ text: '加盟商资料', url: '/pages/account/store-profile/index' });
      }
      return items;
    }
    // Same urls the owner pages build; the admin pages live in a subpackage, so they are spelled out here.
    const admin = view => '/pages/admin/index?view=' + view, detail = view => '/pages/admin/detail/index?view=' + view;
    return [
      { text: '成员与权限', url: admin('staff') }, { text: '审批设置', url: detail('settings') }, { text: '我的规矩', url: detail('rules') },
      { text: '门店资料', url: detail('company') }, { text: '操作记录', url: detail('activity') }
    ];
  }
  return [];
}

function entries(a) {
  if (!a.active) return [];
  if (a.api) {
    if (require('../../utils/access').isBoss(a)) return [{ text: '团队绩效', url: '/pages/admin/tasks/index?view=team' }];
    if (a.role === 'sales-lead') return [{ text: '客户与责任转交', url: '/pages/customers/index' }];
    if (a.role === 'project-manager') return [{ text: '工程项目', url: '/pages/build/projects/index' }];
  }
  if (require('../../utils/access').isBoss(a)) return [{ text: '我的团队', url: '/pages/admin/detail/index?view=team' }];
  if (a.role === 'hq-design-admin' && a.orgType === 'headquarters') return [{ text: '设计费计价设置', url: '/pages/workflow/commission/index?view=pricing' }];
  const team = a.api ? null : TEAM[a.role];
  if (['finance','finance-lead'].includes(a.role)) return (team ? [{ text: team[0], url: '/pages/admin/index?view=' + team[1] }] : []).concat([{ text: '项目回款提成', url: '/pages/admin/commission/index?from=finance-profile' }]);
  // the business lead also hands customers over between salespeople (single or batch) from here
  return team ? [{ text: team[0], url: '/pages/admin/index?view=' + team[1] }].concat(a.role === 'sales-lead' ? [{ text: '责任转交', url: '/pages/workflow/index?view=transfer' }] : []) : [];
}

module.exports = function (tabIndex) {
  return {
    data: { loggedIn: true, name: '', role: '', company: '', avatar: '/assets/account-avatar.jpg', unread: 0, progress: { completed: 0, total: 8, percent: 0 }, courseImage: A.courseImage, courseTitle: 'AI短视频获客实战', courseId: 'ai-video', engineeringProfile: false, ownerProfile: false, showStoreServices: false, entries: [], managementEntries: [] },
    logout() { return require('../../utils/session-actions').logout(); },
    onShow() {
      const s = A.load(), c = A.company(s), a = A.access.context(s);
      // 联合调试修复（2026-11-08）：登录态必须含 API 会话——纯 API 登录（微信一键/短信）下
      // 本地 session.loggedIn 恒 false，「我的」页永远显示登录按钮。API 岗位/昵称为真源展示。
      const apiPost = (() => { try { return http.token() ? require('../../api/auth').currentApiPost() : null; } catch (e) { return null; } })();
      const apiLoggedIn = !!apiPost;
      const loggedIn = s.session.loggedIn || apiLoggedIn;
      const actor = apiLoggedIn ? { ...O.apiContext(apiPost), companyId: apiPost.orgId } : a;
      const engineeringProfile = !!(apiLoggedIn && ['engineer', 'project-manager'].includes(apiPost.roleCode)) || !!(a.active && O.has(a.role, 'engineer') && !apiLoggedIn);
      const ownerProfile = !!(apiLoggedIn && ['boss', 'owner'].includes(apiPost.roleCode)) || !!(a.active && require('../../utils/access').isBoss(a) && !apiLoggedIn);
      const courses = Learning.coursesFor(actor.active ? actor.role : '');
      const course = loggedIn
        ? (ownerProfile && courses.find(item => item.id === 'management')) || courses[0] || null
        : Learning.courses.find(item => item.id === 'ai-video') || null;
      const company=apiLoggedIn?(apiPost.orgName||''):(c?c.name:'');
      const role = apiLoggedIn ? (O.roles[apiPost.roleCode] || apiPost.roleCode) : (A.roles[s.session.role] || '');
      this.setData({
        identityText:Profile.compactIdentity(company,role),identityOrganization:Profile.compactIdentity(company,''),identityRole:Profile.compactIdentity('',role),learningAvailable:apiLoggedIn?true:!!course,learningLoading:apiLoggedIn,learningError:'',learningLabel:apiLoggedIn?'':course?course.category+'培训':'',
        engineeringProfile, courseId:course?course.id:'',courseTitle:course?course.name:'',courseImage:course?course.image:'',
        loggedIn, showStoreServices: !loggedIn || actor.orgType === 'store',
        showAdminMap: canViewAllStores(apiPost),
        entries: entries(actor),
        ownerProfile, managementEntries: managementEntries(actor),
        name: apiLoggedIn ? (apiPost.name || s.profile.name) : s.profile.name, avatar: s.profile.avatar,
        role,
        company: apiLoggedIn ? (apiPost.orgName || '当前组织') : (c ? require('../../utils/access').rawBook(a.companyId)?.managementSettings?.company?.displayName || c.name : '未选择公司'),
        unread: apiLoggedIn ? (this.apiUnread || 0) : M.totalUnread(A.scoped(s, s.messages).filter(x => !x.read).length, 0), progress: { completed: 0, total:course?course.lessons:0, percent: 0 }
      });
      // 课程进度代次守卫（Codex R2 P2）：递增移到 token 检查外——退出/换会话（无新请求）也使旧挂起响应失效
      this.__courseGen = (this.__courseGen || 0) + 1; const courseGen = this.__courseGen;
      if (http.token()) {
        // 姓名真源（2026-11-09）：posts/mine 不含 name——apiPost.name 恒空，此前只显示登录前旧缓存；
        // 经 profile/mine 同步后端最新 nickname（后台改名即生效，与 account 身份页同模式）
        const profileAlive = http.requestGuard('profile');
        http.get('/platform/profile/mine').then(remote => {
          if (!profileAlive()) return;
          const PP = require('../../utils/platform-profile');
          const profile = PP.mergeProfile(A.load().profile, remote);
          A.mutate(s => { s.profile = profile; });
          this.setData({ name: profile.name, avatar: profile.avatar });
        }).catch(() => {});
        const unreadAlive = http.requestGuard('unread');
        http.get('/platform/messages/center/summary').then(r => {
          if (!unreadAlive()) return;
          this.apiUnread = (r && r.count) || 0;
          this.setData({ unread: this.apiUnread });
        }).catch(() => {if(unreadAlive())this.setData({unread:'—',unreadError:'消息未能加载，请进入消息中心重试'});});
        const training=require('../../api/training'),identity=training.identity();
        training.courses('').then(rows => {
          if (courseGen !== this.__courseGen||identity!==training.identity()) return;
          const available=Array.isArray(rows)?rows:Array.isArray(rows&&rows.list)?rows.list:[],next=available.find(c=>c.completed>0&&c.percent<100)||available.find(c=>['boss','owner'].includes(apiPost.roleCode)&&c.category==='经营管理')||available[0];
          this.setData({learningAvailable:true,learningLoading:false,learningLabel:next?next.category+'培训':'',courseId:next?next.id:'',courseTitle:next?next.title:'',courseImage:next?A.courseImage:'',progress:{completed:next?next.completed:0,total:next?next.lessons:0,percent:next?next.percent:0}});
          if(next&&next.coverVersionId)training.ticket(next.id,next.coverVersionId,'poster').then(t=>{if(courseGen===this.__courseGen&&identity===training.identity())this.setData({courseImage:training.url(t)});}).catch(()=>{});
        }).catch(() => { if(courseGen===this.__courseGen&&identity===training.identity())this.setData({learningLoading:false,learningError:'课程未能加载，请进入课程中心重试'}); });
      }
      const bar = this.getTabBar && this.getTabBar();
      if (bar) { bar.refresh && bar.refresh(); bar.setData({ selected: tabIndex, hidden: false }); }
    },
    open(e) {
      const d = e.currentTarget.dataset;
      // entries carry a ready-made url; everything else is a named action (or, from older callers, a raw account view)
      let target = d.url;
      if (!target) {
        const action = d.action || (ACTIONS[d.view] ? d.view : ''), eng = this.data.engineeringProfile;
        target = ACTIONS[action] ? ACTIONS[action](eng, d.id || (action === 'course' ? this.data.courseId : ''), this.data.ownerProfile) : d.view ? account(d.view, d.id ? '&id=' + encodeURIComponent(d.id) : '') : '';
        if (!this.data.loggedIn && (['learning','resources','course'].includes(action)||LOGIN_ONLY.includes(action) || (d.view && ['profile', 'messages', 'orders', 'personal-performance'].includes(d.view)))) target = account('login', '&return=' + encodeURIComponent(target));
      }
      if (target) wx.navigateTo({ url: target });
    }
  };
};
