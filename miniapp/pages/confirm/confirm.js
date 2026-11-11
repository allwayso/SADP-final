Page({
  data: { ticket: '', busy: false, done: false, message: '' },
  onLoad(options) {
    let ticket = ''
    try { ticket = decodeURIComponent(options.scene || options.ticket || '') } catch (_) {}
    if (!/^[a-f0-9]{32}$/.test(ticket)) {
      this.setData({ message: '请求无效，请回到网站重新发起扫码。' })
      return
    }
    this.setData({ ticket })
  },
  confirm() {
    if (!this.data.ticket || this.data.busy || this.data.done) return
    this.setData({ busy: true, message: '' })
    wx.login({
      success: ({ code }) => {
        if (!code) { this.fail('无法取得微信登录凭证，请重试。'); return }
        wx.request({
          url: getApp().globalData.apiBase + '/api/auth/wechat/mini/confirm',
          method: 'POST',
          data: { ticket: this.data.ticket, code },
          success: res => {
            if (res.statusCode === 200) {
              this.setData({ done: true, busy: false, message: '微信身份已确认，请回到原网站完成操作。' })
            } else { this.fail('确认失败或请求已过期，请回网站重新扫码。') }
          },
          fail: () => this.fail('无法连接网站，请核对网络与服务器域名配置。')
        })
      },
      fail: () => this.fail('微信登录失败，请重试。')
    })
  },
  fail(message) { this.setData({ busy: false, message }) }
})
