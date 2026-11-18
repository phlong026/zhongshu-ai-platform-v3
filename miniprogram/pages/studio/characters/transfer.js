const http = require('../../../api/http')
const origin = require('../../../config/env').API_BASE.replace(/\/admin-api\/?$/, '')
Page({
  data:{url:''},
  onLoad() {
    this.alive = http.sessionGuard()
    this.channel = this.getOpenerEventChannel()
    this.channel.on('grant', grant => {
      if (!this.alive()) return this.finish('transferCancelled')
      this.id = grant.uploadId
      this.setData({url:origin + '/admin/clone-upload.html#' + encodeURIComponent(JSON.stringify(grant))})
    })
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
  failed() { this.finish('transferFailed'); wx.navigateBack() },
  onUnload() {
    // WeChat delivers web-view messages when navigating back; accept that event before cancellation.
    setTimeout(() => this.finish('transferCancelled'), 0)
  }
})
