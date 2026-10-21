require('./utils/runtime-compat');
App({
globalData: { windowInfo: null },
onLaunch() {
try { require('./utils/region-migration').run(); } catch(error) { console.error('地域信息更新失败', error); }
this.globalData.windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
wx.loadFontFace({
family: 'ZhongshuSong', source: require('./utils/heading-font'), global: true,
success: () => { this.globalData.fontLoaded = true; },
fail: (error) => { this.globalData.fontError = error.errMsg; }
});
}
});
