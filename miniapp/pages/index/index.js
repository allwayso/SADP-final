Page({
  data: {
    ready: true,
    busy: false,
    user: null,
    step: 'idle',
    action: '',
    username: '',
    password: '',
    displayName: '',
    message: ''
  },
  onShow() {
    this.refresh()
  },
  refresh() {
    // 先在界面上给出登录按钮，再在后台确认是否已有会话，避免网络不畅时一直停在“检查中”。
    wx.request({
      url: getApp().globalData.apiBase + '/api/me',
      method: 'GET',
      timeout: 6000,
      success: res => {
        if (res.statusCode === 200 && res.data && res.data.username) {
          this.setData({ user: res.data, step: 'idle', message: '' })
        } else {
          this.setData({ user: null })
        }
      },
      fail: error => {
        this.setData({
          user: null,
          message: '无法连接服务 ' + getApp().globalData.apiBase + '，请确认手机与开发机在同一 Wi-Fi'
        })
        console.log('me request failed', error)
      }
    })
  },
  login() {
    if (this.data.busy) return
    this.setData({ busy: true, message: '' })
    wx.login({
      success: ({ code }) => {
        if (!code) { this.fail('无法取得微信登录凭证，请重试'); return }
        wx.request({
          url: getApp().globalData.apiBase + '/api/auth/wechat/mini/session',
          method: 'POST',
          data: { code },
          success: res => this.handle(res, 'login'),
          fail: error => this.fail('无法连接服务：' + (error.errMsg || '请检查网络'))
        })
      },
      fail: () => this.fail('微信登录失败，请重试')
    })
  },
  handle(res, action) {
    const body = res.data || {}
    if (res.statusCode === 200 && body.status === 'need-binding') {
      this.setData({ busy: false, step: 'choice', message: '该微信还没有绑定账号。' })
      return
    }
    if (res.statusCode === 200 && body.user) {
      this.setData({ busy: false, user: body.user, step: 'idle', message: '' })
      wx.showToast({ title: action === 'login' ? '已登录' : '已完成', icon: 'success' })
      return
    }
    const detail = body.message ? '：' + body.message : '（HTTP ' + res.statusCode + '）'
    this.fail('操作失败' + detail)
  },
  chooseBind() { this.setData({ step: 'form', action: 'bind', username: '', password: '', displayName: '', message: '' }) },
  chooseRegister() { this.setData({ step: 'form', action: 'register', username: '', password: '', displayName: '', message: '' }) },
  back() { this.setData({ step: 'choice', message: '' }) },
  onUsername(event) { this.setData({ username: (event.detail.value || '').trim() }) },
  onPassword(event) { this.setData({ password: event.detail.value || '' }) },
  onName(event) { this.setData({ displayName: (event.detail.value || '').trim() }) },
  submit() {
    if (this.data.busy) return
    const { action, username, password, displayName } = this.data
    if (!/^[A-Za-z0-9._-]{3,64}$/.test(username)) {
      this.fail('账号名需为 3-64 位字母、数字、点、下划线或连字符')
      return
    }
    if (!password || password.length < 10) {
      this.fail('密码至少 10 位')
      return
    }
    this.setData({ busy: true, message: '' })
    wx.login({
      success: ({ code }) => {
        if (!code) { this.fail('无法取得微信登录凭证，请重试'); return }
        wx.request({
          url: getApp().globalData.apiBase + '/api/auth/wechat/mini/session',
          method: 'POST',
          data: { code, action, username, password, displayName: displayName || username },
          success: res => this.handle(res, action),
          fail: error => this.fail('无法连接服务：' + (error.errMsg || '请检查网络'))
        })
      },
      fail: () => this.fail('微信登录失败，请重试')
    })
  },
  logout() {
    wx.request({
      url: getApp().globalData.apiBase + '/api/auth/wechat/mini/logout',
      method: 'POST',
      success: () => this.setData({ user: null, step: 'idle', message: '' }),
      fail: () => this.setData({ message: '退出失败，请重试' })
    })
  },
  fail(message) {
    this.setData({ busy: false, message })
    wx.showToast({ title: message, icon: 'none' })
  }
})
