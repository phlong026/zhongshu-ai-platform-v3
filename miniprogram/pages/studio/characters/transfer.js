const http = require('../../../api/http')
const origin = require('../../../config/env').API_BASE.replace(/\/admin-api\/?$/, '')
Page({
  data:{url:''},
  onLoad(options = {}) {
    this.alive = http.sessionGuard()
    this.channel = this.getOpenerEventChannel()
    if (options.mode === 'replay') {
      this.replay = true
      wx.setNavigationBarTitle({title:'回放上传素材'})
      this.channel.on('preview', source => {
        if (this.alive()) this.setData({url:origin + '/admin/clone-upload.html#replay=' + encodeURIComponent(JSON.stringify(source))})
      })
      return
    }
    this.channel.on('grant', grant => {
      if (!this.alive()) return this.finish('transferCancelled')
      this.id = grant.uploadId
      this.setData({url:origin + '/admin/clone-upload.html?keepPreview=1#' + encodeURIComponent(JSON.stringify(grant))})
    })
  },
  onShow() {
    if (this.alive && !this.alive()) { this.setData({url:''}); wx.navigateBack() }
  },
  finish(event, record) {
    if (this.finished) return
    this.finished = true
    this.channel.emit(event, record)
  },
  message(e) {
    const record = e.detail.data?.find(r => r?.uploadId === this.id)
    if (record && this.alive()) this.finish('transferred', record)
  },
  failed() {
    if (this.replay) wx.showToast({title:'回放页未能打开',icon:'none'})
    else this.finish('transferFailed')
    wx.navigateBack()
  },
  onUnload() {
    // WeChat delivers web-view messages when navigating back; accept that event before cancellation.
    if (!this.replay) setTimeout(() => this.finish('transferCancelled'), 0)
  }
})
