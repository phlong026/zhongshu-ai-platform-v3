Component({properties:{title:String},data:{statusHeight:54},lifetimes:{attached(){const info=getApp().globalData.windowInfo||wx.getSystemInfoSync();this.setData({statusHeight:info.statusBarHeight||20});}},methods:{back(){return require('../../../utils/page-navigation').back();},workbench(){return require('../../../utils/page-navigation').home();}}});

