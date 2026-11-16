Component({
properties: { title: String, interceptBack: Boolean },
data: { statusHeight: 47, navHeight: 44 },
lifetimes: {
attached() {
const info = getApp().globalData.windowInfo || wx.getSystemInfoSync();
this.setData({ statusHeight: info.statusBarHeight || 20, navHeight: Math.max(44, (info.windowWidth || 375) * 88 / 750) });
}
},
methods: {
back(){if(this.properties.interceptBack){this.triggerEvent('back');return;}return require('../../utils/page-navigation').back();},workbench(){return require('../../utils/page-navigation').home();}
}
});
