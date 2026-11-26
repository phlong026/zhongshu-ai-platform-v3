Component({
properties: {
accountFont: { type: Boolean, value: false }, divider: { type: Boolean, value: false }, title: String, subtitle: String, home: Boolean, back:Boolean, hideHome:Boolean, branded:{type:Boolean,value:false}, fixed:{type:Boolean,value:false}, action:{type:Boolean,value:false}, returnLabel:{type:String,value:'返回'}, busy:{type:Boolean,value:false} },
data: { statusHeight: 36, showNavigation:false },
lifetimes: {
attached() {
const info = getApp().globalData.windowInfo || wx.getSystemInfoSync();
const route=getCurrentPages().slice(-1)[0]?.route||'';
const roots=['home','business','engineering','management','profile','customers','design','creation'].map(v=>'pages/'+v+'/index').concat(['pages/account/sales/index','pages/build/home/index','pages/build/projects/index','pages/build/materials/index']);
this.setData({ statusHeight: info.statusBarHeight || 20,showNavigation:this.data.back||(!this.data.branded&&!roots.includes(route)) });
}
},
methods:{returnToOrigin(){if(!this.data.busy)this.triggerEvent('return');},back(){return require('../../utils/page-navigation').back();},workbench(){return require('../../utils/page-navigation').home();}}
});
