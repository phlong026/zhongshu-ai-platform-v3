// Shown on workbench tab pages when no one is signed in: the platform is for
// internal staff only, so guests get a login entry instead of sample data.
Component({
  properties: {
    title: { type: String, value: '' },
    description: { type: String, value: '' }
  },
  methods: {
    login() { wx.navigateTo({ url: '/pages/account/index?view=login' }); },
    goHome() { wx.switchTab({ url: '/pages/home/index' }); }
  }
});
