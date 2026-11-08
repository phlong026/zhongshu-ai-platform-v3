// One "我的" implementation for every role. The three route shells (profile tab, sales, boss) differ only in the
// bottom navigation component they mount; content, handlers and styles all come from here and profile.wxml.
const A = require('../../utils/account');
const O = require('../../utils/organization');
const M = require('../../utils/platform-messages');
const http = require('../../api/http');

const TEAM = { 'sales-lead': ['业务团队', 'business-team'], 'project-manager': ['工程团队', 'engineering-team'], 'finance-lead': ['财务管理', 'finance-team'], owner: ['门店团队与交接', 'staff'] };
const account = (view, extra = '') => '/pages/account/index?view=' + view + extra;
const ACTIONS = {
  login: () => account('login'), profile: () => account('profile'), messages: () => account('messages'),
  performance: (engineering, id, owner) => '/pages/admin/tasks/index?view=' + (owner ? 'team' : 'mine'),
  identity: () => account('identity'), help: () => account('help'), privacy: () => account('privacy'), policy: () => account('policy'), agreement: () => account('agreement'),
  services: () => account('services'), selection: () => account('selection'),
  learning: eng => account('learning', eng ? '&category=' + encodeURIComponent('工程') : ''),
  resources: eng => account('learning', '&tab=' + encodeURIComponent('资料') + (eng ? '&category=' + encodeURIComponent('工程') : '')),
  course: (eng, id) => account('course', id ? '&id=' + encodeURIComponent(id) : '')
};
// actions that need a signed-in account
const LOGIN_ONLY = ['profile', 'messages', 'performance'];

// Keep one entry per company setting; task access stays in the profile quick card.
function managementEntries(a) {
  if (!a.active) return [];
  const X = require('../../utils/access');
  if (X.isBoss(a)) {
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
  if (require('../../utils/access').isBoss(a)) return [{ text: '我的团队', url: '/pages/admin/detail/index?view=team' }];
  if (a.role === 'hq-design-admin' && a.orgType === 'headquarters') return [{ text: '设计费计价设置', url: '/pages/workflow/commission/index?view=pricing' }];
  const team = TEAM[a.role];
  if (['finance','finance-lead'].includes(a.role)) return (team ? [{ text: team[0], url: '/pages/admin/index?view=' + team[1] }] : []).concat([{ text: '项目回款提成', url: '/pages/admin/commission/index' }, { text: '公司费用与推广登记', url: '/pages/admin/operations/index?view=expenses&id=' + encodeURIComponent('company:' + a.companyId) + '&purpose=' + encodeURIComponent('推广获客') }]);
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
      const apiPost = (() => { try { const http = require('../../api/http'); return http.token() ? require('../../api/auth').currentApiPost() : null; } catch (e) { return null; } })();
      const apiLoggedIn = !!apiPost;
      const engineeringProfile = !!(apiLoggedIn && ['engineer', 'project-manager'].includes(apiPost.roleCode)) || !!(a.active && O.has(a.role, 'engineer') && !apiLoggedIn);
      const ownerProfile = !!(apiLoggedIn && ['boss', 'owner'].includes(apiPost.roleCode)) || !!(a.active && require('../../utils/access').isBoss(a) && !apiLoggedIn);
      this.setData({
        engineeringProfile, courseId: engineeringProfile ? 'engineering' : 'ai-video', courseTitle: engineeringProfile ? '乡墅开工与现场交底' : 'AI短视频获客实战',
        courseImage: engineeringProfile ? '/pages/account/assets/course-engineering.jpg' : A.courseImage,
        loggedIn: s.session.loggedIn || apiLoggedIn, showStoreServices: !apiLoggedIn && (!s.session.loggedIn || a.orgType === 'store'),
        entries: entries(a),
        ownerProfile, managementEntries: managementEntries(a),
        name: apiLoggedIn ? (apiPost.name || s.profile.name) : s.profile.name, avatar: s.profile.avatar,
        role: apiLoggedIn ? (O.roles[apiPost.roleCode] || apiPost.roleCode) : A.roles[s.session.role],
        company: c ? require('../../utils/access').rawBook(a.companyId)?.managementSettings?.company?.displayName || c.name : '未选择公司',
        unread: M.totalUnread(A.scoped(s, s.messages).filter(x => !x.read).length, this.apiUnread || 0), progress: { completed: 0, total: (A.courseChapters[engineeringProfile ? 'engineering' : 'ai-video'] || A.chapters).length, percent: 0 }
      });
      // API 未读数（基-2）：有会话才拉，回来合计刷新（11 文档 §十）
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
        http.get('/platform/messages/unread-count').then(r => {
          this.apiUnread = (r && r.count) || 0;
          const fresh = A.load();
          this.setData({ unread: M.totalUnread(A.scoped(fresh, fresh.messages).filter(x => !x.read).length, this.apiUnread) });
        }).catch(() => {});
        // 课程进度 API 真源（/franchise/content/course-progress 全量拉取，不读本地 learning 存储）
        require('../../api/franchise/content').drafts.courseProgress().then(rows => {
          if (courseGen !== this.__courseGen) return;
          const map = {}; (rows || []).forEach(r => { (map[r.courseId] = map[r.courseId] || []).push(Number(r.chapterIndex)); });
          const cid = this.data.courseId, total = (A.courseChapters[cid] || A.chapters).length;
          const done = (map[cid] || []).filter(i => Number.isInteger(i) && i >= 0 && i < total);
          this.setData({ progress: { completed: done.length, total, percent: total ? done.length / total * 100 : 0 } });
        }).catch(() => {});
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
        if (!this.data.loggedIn && (LOGIN_ONLY.includes(action) || (d.view && ['profile', 'messages', 'orders', 'personal-performance'].includes(d.view)))) target = account('login', '&return=' + encodeURIComponent(target));
      }
      if (target) wx.navigateTo({ url: target });
    }
  };
};
