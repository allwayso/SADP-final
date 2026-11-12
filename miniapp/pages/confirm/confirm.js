Page({
  data: {
    ticket: '', pairing: '', busy: false, done: false, message: '',
    needRegister: false, username: '', password: '', displayName: ''
  },
  onLoad(options) {
    let ticket = ''
    try { ticket = decodeURIComponent(options.scene || options.ticket || getApp().globalData.previewTicket || '') } catch (_) {}
    if (/^[a-f0-9]{32}$/.test(ticket)) {
      this.setData({ ticket })
      return
    }
    // 生码受限时改用网页上的十二位配对码，静态预览码也能完成确认
    this.setData({ message: '请填写网页上显示的十二位配对码。' })
  },
  onPairingInput(event) {
    this.setData({ pairing: (event.detail.value || '').trim().toUpperCase(), done: false, message: '' })
  },
  onUsernameInput(event) { this.setData({ username: (event.detail.value || '').trim() }) },
  onPasswordInput(event) { this.setData({ password: event.detail.value || '' }) },
  onNameInput(event) { this.setData({ displayName: (event.detail.value || '').trim() }) },
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
            if (res.statusCode !== 200) {
              const detail = res.data && res.data.message ? '：' + res.data.message : ''
              this.fail('确认失败或请求已过期' + detail + '，请回网站重新发起。')
              return
            }
            if (res.data && res.data.registered === false) {
              this.setData({ busy: false, needRegister: true, message: '该微信还没有账号，可直接注册采集者账号。' })
              return
            }
            this.setData({ done: true, busy: false, message: '微信身份已确认，请回到原网站完成操作。' })
          },
          fail: error => this.fail('无法连接 ' + getApp().globalData.apiBase + '：' + (error.errMsg || '请检查网络与服务器域名配置'))
        })
      },
      fail: () => this.fail('微信登录失败，请重试。')
    })
  },
  register() {
    if (this.data.busy || this.data.done) return
    const { ticket, username, password, displayName } = this.data
    if (!/^[A-Za-z0-9._-]{3,64}$/.test(username)) {
      this.setData({ message: '账号名需为 3 到 64 位字母、数字、点、下划线或连字符。' })
      wx.showToast({ title: '账号名需为字母数字', icon: 'none' })
      return
    }
    if (!password || password.length < 10) {
      this.setData({ message: '密码至少 10 位。' })
      wx.showToast({ title: '密码至少 10 位', icon: 'none' })
      return
    }
    this.setData({ busy: true, message: '' })
    wx.request({
      url: getApp().globalData.apiBase + '/api/auth/wechat/mini/register',
      method: 'POST',
      data: { ticket, username, password, displayName: displayName || username },
      success: res => {
        if (res.statusCode === 200) {
          this.setData({ done: true, busy: false, needRegister: false, message: '注册成功，请回到原网站完成登录。' })
        } else {
          const detail = res.data && res.data.message ? '：' + res.data.message : '（HTTP ' + res.statusCode + '）'
          this.setData({ busy: false, message: '注册失败' + detail })
          wx.showToast({ title: '注册失败' + detail, icon: 'none' })
        }
      },
      fail: error => this.setData({ busy: false, message: '无法连接网站：' + (error.errMsg || '请检查网络') })
    })
  },
  fail(message) { this.setData({ busy: false, message }) }
})
