/**
 * 平台待办跳转：页面级 appPath → 小程序导航。
 * 原生 tabBar 页（app.json tabBar.list）用 switchTab，其余 navigateTo；失败/不支持时 toast 兜底。
 */
const TAB_PAGES = [ // 与 app.json tabBar.list 保持一致（verify-platform-todos 断言同步）
  'pages/home/index',
  'pages/business/index',
  'pages/engineering/index',
  'pages/management/index',
  'pages/profile/index'
];

function open(path) {
  if (!path) return fail();
  const normalized = String(path).replace(/^\//, '');
  if (TAB_PAGES.includes(normalized)) {
    return wx.switchTab({ url: '/' + normalized, fail: fail });
  }
  return wx.navigateTo({ url: path, fail: fail });
}

function fail() {
  wx.showToast({ title: '暂不支持小程序内办理，请到工作台查看', icon: 'none' });
}

module.exports = { open, TAB_PAGES };
