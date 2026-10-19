Component({properties:{title:String},data:{statusHeight:54},lifetimes:{attached(){const info=getApp().globalData.windowInfo||wx.getSystemInfoSync();this.setData({statusHeight:info.statusBarHeight||20});}},methods:{back(){if(getCurrentPages().length>1)wx.navigateBack();else wx.switchTab({url:'/pages/engineering/index'});}}});

