require('./utils/runtime-compat');
App({
globalData: { windowInfo: null },
onLaunch() {
try { require('./utils/legacy-storage-cleanup').run(); } catch(error) { console.error('本地残留数据清理失败', error); }
this.globalData.windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
this.watchUpdate();
},
// 线上版本修复要能及时到达用户：监听微信后台下发的新版本包，就绪即提示重启
watchUpdate() {
if (!wx.getUpdateManager) return;
try {
  const manager = wx.getUpdateManager();
  manager.onUpdateReady(() => {
    wx.showModal({ title: '更新提示', content: '新版本已准备好，是否重启应用？', success: (res) => { if (res.confirm) manager.applyUpdate(); } });
  });
  manager.onUpdateFailed(() => {
    wx.showToast({ title: '新版本下载失败，可删除小程序后重新打开', icon: 'none' });
  });
} catch(error) { console.error('版本更新监听失败', error); }
}
});
