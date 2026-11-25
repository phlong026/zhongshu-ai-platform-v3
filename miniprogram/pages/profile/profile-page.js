// One "我的" implementation for every role. The three route shells (profile tab, sales, boss) differ only in the
// bottom navigation component they mount; content, handlers and styles all come from here and profile.wxml.
const A = require('../../utils/account');
const O = require('../../utils/organization');
const M = require('../../utils/platform-messages');
const http = require('../../api/http');
const Learning = require('../../utils/learning-catalog');
const Profile = require('../../utils/platform-profile');
const apiScope = require('../../utils/api-scope');
const { canViewAllStores } = require('../../utils/store-map-access');

const TEAM = { 'sales-lead': ['业务团队', 'business-team'], 'project-manager': ['工程团队', 'engineering-team'], 'finance-lead': ['财务管理', 'finance-team'], owner: ['门店团队与交接', 'staff'] };
const account = (view, extra = '') => '/pages/account/index?view=' + view + extra;
const ACTIONS = {
  login: () => account('login'), profile: () => account('profile'), messages: () => account('messages'),
  announcements: () => '/pages/account/announcements/index',
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
const LOGIN_ONLY = ['profile', 'messages', 'announcements', 'performance', 'storeMap', 'academy'];
const emptyCourse = () => ({ learningLabel: '', courseId: '', courseTitle: '', courseImage: '', progress: { completed: 0, total: 0, percent: 0 } });
const denied = error => error && (error.postInvalid || [401,403].includes(Number(error.code)));
const emptyPrivate = () => ({ ...emptyCourse(), name: '', avatar: Profile.emptyProfile().avatar, unread: 0, role: '', company: '',
  identityText: '', identityOrganization: '', identityRole: '', entries: [], managementEntries: [], profileLoading: false, learningLoading: false });

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
    data: { loggedIn: true, name: '', role: '', company: '', avatar: '/assets/account-avatar.jpg', profileLoading: false, profileError: '', unread: 0, ...emptyCourse(), learningAvailable: false, learningLoading: false, learningError: '', engineeringProfile: false, ownerProfile: false, showStoreServices: false, entries: [], managementEntries: [] },
    logout() { return require('../../utils/session-actions').logout(); },
    onHide() { this.__profileHidden = true; this.__profileFlight = null; },
    onUnload() { this.onHide(); },
    onShow() {
      const s = A.load(), c = A.company(s), a = A.access.context(s);
      const apiPost = http.token() ? require('../../api/auth').currentApiPost() : null;
      const key = apiScope(), sameScope = !!key && key === this.__profileKey;
      this.__profileHidden = false;
      if (!sameScope) { this.__profileFlight = null; this.__courseCover = ''; this.apiUnread = 0; this.__profileDenied = false; }
      this.__profileKey = key;
      const bar = this.getTabBar && this.getTabBar();
      if (bar) { bar.refresh && bar.refresh(); bar.setData({ selected: tabIndex, hidden: false }); }
      if (this.__profileFlight) return this.__profileFlight.promise;
      const apiLoggedIn = !!apiPost, loggedIn = s.session.loggedIn || apiLoggedIn;
      const actor = apiLoggedIn ? { ...O.apiContext(apiPost), companyId: apiPost.orgId } : a;
      const engineeringProfile = apiLoggedIn ? ['engineer','project-manager'].includes(apiPost.roleCode) : !!(a.active && O.has(a.role,'engineer'));
      const ownerProfile = apiLoggedIn ? ['boss','owner'].includes(apiPost.roleCode) : !!(a.active && require('../../utils/access').isBoss(a));
      const courses = Learning.coursesFor(actor.active ? actor.role : '');
      const course = apiLoggedIn ? null : loggedIn
        ? (ownerProfile && courses.find(item => item.id === 'management')) || courses[0] || null
        : Learning.courses.find(item => item.id === 'ai-video') || null;
      const company = apiLoggedIn ? (apiPost.orgName || '') : (c ? c.name : '');
      const role = apiLoggedIn ? (O.roles[apiPost.roleCode] || apiPost.roleCode) : (A.roles[s.session.role] || '');
      this.setData({
        identityText: Profile.compactIdentity(company,role), identityOrganization: Profile.compactIdentity(company,''), identityRole: Profile.compactIdentity('',role),
        ...(sameScope ? {} : { ...emptyCourse(), learningLabel: course ? course.category+'培训' : '', courseId: course ? course.id : '',
          courseTitle: course ? course.name : '', courseImage: course ? course.image : '', progress: { completed: 0, total: course ? course.lessons : 0, percent: 0 } }),
        learningAvailable: apiLoggedIn || !!course, learningLoading: apiLoggedIn, learningError: '',
        engineeringProfile, ownerProfile, loggedIn, showStoreServices: !loggedIn || ['store','headquarters'].includes(actor.orgType),
        showAdminMap: canViewAllStores(apiPost), entries: entries(actor), managementEntries: managementEntries(actor),
        name: this.__profileDenied ? '' : s.profile.name, avatar: this.__profileDenied ? Profile.emptyProfile().avatar : s.profile.avatar, profileLoading: !!http.token(), profileError: '', role,
        company: apiLoggedIn ? (apiPost.orgName || '当前组织') : (c ? require('../../utils/access').rawBook(a.companyId)?.managementSettings?.company?.displayName || c.name : '未选择公司'),
        unread: apiLoggedIn ? (this.apiUnread || 0) : M.totalUnread(A.scoped(s,s.messages).filter(x => !x.read).length,0)
      });
      if (!http.token()) return;
      const flight = this.__profileFlight = {};
      const alive = () => {
        if (this.__profileHidden || this.__profileFlight !== flight) return false;
        if (apiScope() !== key) { this.__profileFlight = null; this.setData({ ...emptyPrivate(), loggedIn: !!http.token() }); return false; }
        return true;
      };
      const profileUserId = http.currentUserId();
      const clearProfile = () => { this.__profileDenied = true; A.mutate(s => { s.profile = Profile.emptyProfile(profileUserId); }); };
      const failed = (error, patch, reset) => {
        if (!alive()) return;
        if (error && error.postInvalid) { clearProfile(); this.__profileFlight = null; this.apiUnread = 0; this.setData(emptyPrivate()); return; }
        this.setData({ ...patch, ...(denied(error) ? reset : {}) });
      };
      const profileRead = http.get('/platform/profile/mine').then(remote => {
        if (!alive()) return;
        const profile = Profile.mergeProfile(A.load().profile,remote,profileUserId);
        A.mutate(s => { s.profile = profile; });
        this.__profileDenied = false;
        this.setData({ name: profile.name, avatar: profile.avatar, profileLoading: false, profileError: '' });
      }).catch(error => {
        if (alive() && denied(error)) clearProfile();
        failed(error, { profileLoading: false, profileError: '资料暂未加载，请进入个人资料重试' }, { name: '', avatar: Profile.emptyProfile().avatar });
      });
      const unreadRead = http.get('/platform/messages/center/summary').then(r => {
        if (!alive()) return;
        this.apiUnread = (r && r.count) || 0;
        this.setData({ unread: this.apiUnread, unreadError: '' });
      }).catch(error => {
        if (alive() && denied(error)) this.apiUnread = 0;
        failed(error, { unreadError: '消息未能加载，请进入消息中心重试' }, { unread: '—' });
      });
      const training = require('../../api/training');
      const courseRead = apiPost ? training.courses('').then(rows => {
        if (!alive()) return;
        const available = Array.isArray(rows) ? rows : Array.isArray(rows && rows.list) ? rows.list : [];
        const next = available.find(c => c.completed > 0 && c.percent < 100) || available.find(c => ['boss','owner'].includes(apiPost.roleCode) && c.category === '经营管理') || available[0];
        const cover = next ? JSON.stringify([next.id,next.coverVersionId]) : '';
        const image = cover && cover === this.__courseCover ? this.data.courseImage : A.courseImage;
        this.__courseCover = cover;
        this.setData({ learningLoading: false, learningLabel: next ? next.category+'培训' : '', courseId: next ? next.id : '',
          courseTitle: next ? next.title : '', courseImage: next ? image : '',
          progress: { completed: next ? next.completed : 0, total: next ? next.lessons : 0, percent: next ? next.percent : 0 } });
        if (next && next.coverVersionId) return training.ticket(next.id,next.coverVersionId,'poster').then(async t => {
          if (!alive()) return;
          const image = await new Promise((resolve,reject) => wx.getImageInfo({ src: training.url(t), success: resolve, fail: reject }));
          if (alive() && image.path) this.setData({ courseImage: image.path });
        }).catch(error => failed(error, {}, { courseImage: '' }));
      }).catch(error => failed(error, { learningLoading: false, learningError: '课程未能加载，请进入课程中心重试' }, emptyCourse())) : Promise.resolve();
      return flight.promise = Promise.all([profileRead,unreadRead,courseRead]).finally(() => { if (this.__profileFlight === flight) this.__profileFlight = null; });
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
      if (target) require('../../utils/page-navigation').open(target);
    }
  };
};
