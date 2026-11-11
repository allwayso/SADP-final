Page({
  data: { ticket: '', pairing: '', busy: false, done: false, message: '' },
  onLoad(options) {
    let ticket = ''
    try { ticket = decodeURIComponent(options.scene || options.ticket || '') } catch (_) {}
    if (/^[a-f0-9]{32}$/.test(ticket)) {
      this.setData({ ticket })
      return
    }
    // 生码受限时改用网页上的十二位配对码，静态预览码也能完成确认
    this.setData({ message: '请填写网页上显示的十二位配对码。' })
  },
  onPairingInput(event) {
    this.setData({ pairing: (event.detail.value || '').trim().toUpperCase() })
  },
  confirm() {
    const ticket = this.data.ticket
    const pairing = this.data.pairing
    if (this.data.busy || this.data.done) return
    if (!ticket && !/^[a-f0-9]{12}$/i.test(pairing)) {
      this.setData({ message: '请填写十二位配对码。' })
      return
    }
    this.setData({ busy: true, message: '' })
    wx.login({
      success: ({ code }) => {
        if (!code) { this.fail('无法取得微信登录凭证，请重试。'); return }
        wx.request({
          url: getApp().globalData.apiBase + '/api/auth/wechat/mini/confirm',
          method: 'POST',
          data: { ticket: ticket || null, pairing: ticket ? null : pairing, code },
          success: res => {
            if (res.statusCode === 200) {
              this.setData({ done: true, busy: false, message: '微信身份已确认，请回到原网站完成操作。' })
            } else {
              const detail = res.data && res.data.message ? '：' + res.data.message : ''
              this.fail('确认失败或请求已过期' + detail + '，请回网站重新发起。')
            }
          },
          fail: error => this.fail('无法连接 ' + getApp().globalData.apiBase + '：' + (error.errMsg || '请检查网络与服务器域名配置'))
        })
      },
      fail: () => this.fail('微信登录失败，请重试。')
    })
  },
  fail(message) { this.setData({ busy: false, message }) }
})
