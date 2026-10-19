Component({
properties: {
accountFont: { type: Boolean, value: false }, divider: { type: Boolean, value: false }, title: String, subtitle: String, home: Boolean },
data: { statusHeight: 36 },
lifetimes: {
attached() {
const info = getApp().globalData.windowInfo || wx.getSystemInfoSync();
this.setData({ statusHeight: info.statusBarHeight || 20 });
}
}
});
