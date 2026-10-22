// One "我的" implementation for every role. The three route shells (profile tab, sales, boss) differ only in the
// bottom navigation component they mount; content, handlers and styles all come from here and profile.wxml.
const A = require('../../utils/account');
const O = require('../../utils/organization');
require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'), 'dashboard');

const TEAM = { 'sales-lead': ['业务团队', 'business-team'], 'project-manager': ['工程团队', 'engineering-team'], 'finance-lead': ['财务管理', 'finance-team'], owner: ['门店团队与交接', 'staff'] };
const account = (view, extra = '') => '/pages/account/index?view=' + view + extra;
const ACTIONS = {
  login: () => account('login'), profile: () => account('profile'), messages: () => account('messages'),
  performance: (eng, id, owner) => owner ? '/pages/admin/detail/index?view=targets' : account('personal-performance'),
  identity: () => account('identity'), help: () => account('help'), privacy: () => account('privacy'), policy: () => account('policy'), agreement: () => account('agreement'),
  services: () => account('services'), selection: () => account('selection'),
  learning: eng => account('learning', eng ? '&category=' + encodeURIComponent('工程') : ''),
  resources: eng => account('learning', '&tab=' + encodeURIComponent('资料') + (eng ? '&category=' + encodeURIComponent('工程') : '')),
  course: (eng, id) => account('course', id ? '&id=' + encodeURIComponent(id) : '')
};
// actions that need a signed-in account
const LOGIN_ONLY = ['profile', 'messages', 'performance'];

// Keep existing company settings, including fee authorization, reachable from the shared profile.
function managementEntries(a) {
  if (!a.active) return [];
  const X = require('../../utils/access');
  if (X.isBoss(a)) {
    // Same urls the owner pages build; the admin pages live in a subpackage, so they are spelled out here.
    const admin = view => '/pages/admin/index?view=' + view, detail = view => '/pages/admin/detail/index?view=' + view;
    return [
      { text: '成员与权限', url: admin('staff') }, { text: '审批设置', url: detail('settings') },
      { text: '项目经理交接', url: detail('handover') }, { text: '门店资料', url: detail('company') }, { text: '操作记录', url: detail('activity') }
    ].concat(a.role === 'boss' && a.orgType === 'store' ? [{ text: '标准设计费用与授权', url: '/pages/workflow/commission/index?view=policy' }] : []);
  }
  return [];
}

function entries(a) {
  if (!a.active) return [];
  if (require('../../utils/access').isBoss(a)) return [{ text: '我的团队', url: '/pages/admin/detail/index?view=team' }];
  const team = TEAM[a.role];
  // the business lead also hands customers over between salespeople (single or batch) from here
  return team ? [{ text: team[0], url: '/pages/admin/index?view=' + team[1] }].concat(a.role === 'sales-lead' ? [{ text: '责任转交', url: '/pages/workflow/index?view=transfer' }] : []) : [];
}

module.exports = function (tabIndex) {
  return {
    data: { loggedIn: true, name: '', role: '', company: '', avatar: '/assets/account-avatar.jpg', unread: 0, progress: { completed: 0, total: 8, percent: 0 }, courseImage: A.courseImage, courseTitle: 'AI短视频获客实战', courseId: 'ai-video', engineeringProfile: false, ownerProfile: false, showStoreServices: false, entries: [], managementEntries: [] },
    logout() { return require('../../utils/session-actions').logout(); },
    onShow() {
      const s = A.load(), c = A.company(s), a = A.access.context(s);
      const engineeringProfile = !!(a.active && O.has(a.role, 'engineer'));
      const ownerProfile = !!(a.active && require('../../utils/access').isBoss(a));
      this.setData({
        engineeringProfile, courseId: engineeringProfile ? 'engineering' : 'ai-video', courseTitle: engineeringProfile ? '乡墅开工与现场交底' : 'AI短视频获客实战',
        courseImage: engineeringProfile ? '/pages/account/assets/course-engineering.jpg' : A.courseImage,
        loggedIn: s.session.loggedIn, showStoreServices: !s.session.loggedIn || a.orgType === 'store',
        entries: entries(a).concat(a.active && a.demo ? [{ text: '跨设备模拟联调', url: '/pages/collaboration/index' }] : []),
        ownerProfile, managementEntries: managementEntries(a),
        name: s.profile.name, avatar: s.profile.avatar, role: A.roles[s.session.role], company: c ? c.name : '未选择公司',
        unread: A.scoped(s, s.messages).filter(x => !x.read).length, progress: A.progress(s, engineeringProfile ? 'engineering' : 'ai-video')
      });
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
        if (!this.data.loggedIn && (LOGIN_ONLY.includes(action) || (d.view && ['profile', 'messages', 'orders', 'personal-performance'].includes(d.view)))) target = account('login', '&return=' + encodeURIComponent(target));
      }
      if (target) wx.navigateTo({ url: target });
    }
  };
};
