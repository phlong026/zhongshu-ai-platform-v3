require('./utils/runtime-compat');
App({
globalData: { windowInfo: null },
onLaunch() {
try { require('./utils/legacy-storage-cleanup').run(); } catch(error) { console.error('本地残留数据清理失败', error); }
this.globalData.windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
}
});
