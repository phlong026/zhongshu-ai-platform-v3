function switchTab(options) {
  if (options.url.startsWith('/design-migration/')) return wx.redirectTo(options);
  return wx.switchTab(options);
}
module.exports = { switchTab };
