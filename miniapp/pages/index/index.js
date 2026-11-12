const client = require('../../utils/request')

Page({
  data: {
    busy: false, user: null, step: 'idle', action: '',
    username: '', password: '', displayName: '', message: '', pairingCode: ''
  },
  onShow() { this.refresh() },
  onUnload() { this.revision = (this.revision || 0) + 1 },
  async refresh() {
    if (this.data.busy) return
    const revision = this.revision = (this.revision || 0) + 1
    try {
      const user = await client.request('/me', { timeout: 6000 })
      if (revision !== this.revision) return
      this.setData({ user, message: '' })
    } catch (error) {
      if (revision !== this.revision) return
      if (error.status === 401) this.setData({ user: null, step: 'idle' })
      else this.setData({ message: error.message })
    }
  },
  login() { return this.authenticate({}) },
  async authenticate(fields) {
    if (this.data.busy) return
    const revision = this.revision = (this.revision || 0) + 1
    client.beginIdentityChange()
    this.setData({ busy: true, message: '' })
    try {
      const code = await new Promise((resolve, reject) => wx.login({
        success: result => result.code ? resolve(result.code) : reject(new Error('无法取得微信登录凭证，请重试')),
        fail: () => reject(new Error('微信登录失败，请重试'))
      }))
      const body = await client.request('/auth/wechat/mini/session', { method: 'POST', data: { code, ...fields } })
      if (revision !== this.revision) return
      if (body.status === 'need-binding') {
        this.setData({ step: 'choice', message: '该微信还没有绑定账号。' })
      } else if (body.user) {
        this.setData({ user: body.user, step: 'idle', password: '', message: '' })
        wx.showToast({ title: '已登录', icon: 'success' })
      } else throw new Error('登录响应异常，请重试')
    } catch (error) {
      if (revision === this.revision) this.fail(error.message)
    } finally {
      if (revision === this.revision) this.setData({ busy: false })
    }
  },
  chooseBind() { this.setData({ step: 'form', action: 'bind', username: '', password: '', displayName: '', message: '' }) },
  chooseRegister() { this.setData({ step: 'form', action: 'register', username: '', password: '', displayName: '', message: '' }) },
  back() { if (!this.data.busy) this.setData({ step: 'choice', password: '', message: '' }) },
  onUsername(event) { this.setData({ username: (event.detail.value || '').trim() }) },
  onPassword(event) { this.setData({ password: event.detail.value || '' }) },
  onName(event) { this.setData({ displayName: (event.detail.value || '').trim() }) },
  submit() {
    const { action, username, password, displayName } = this.data
    if (!/^[A-Za-z0-9._-]{3,64}$/.test(username)) { this.fail('账号名需为 3-64 位字母、数字或 . _ -'); return }
    if (!password || password.length < 10 || password.length > 128) { this.fail('密码需为 10-128 位'); return }
    return this.authenticate({ action, username, password, displayName: displayName || username })
  },
  async logout() {
    if (this.data.busy) return
    this.revision = (this.revision || 0) + 1
    client.beginIdentityChange()
    this.setData({ busy: true, message: '' })
    try {
      await client.request('/auth/wechat/mini/logout', { method: 'POST' })
      client.clearSession()
      this.setData({ user: null, step: 'idle', password: '', pairingCode: '', message: '' })
    } catch (error) { this.fail('退出失败：' + error.message) }
    finally { this.setData({ busy: false }) }
  },
  openPairing() { this.setData({ step: 'pairing', pairingCode: '', message: '' }) },
  closePairing() { if (!this.data.busy) this.setData({ step: 'idle', pairingCode: '', message: '' }) },
  onPairingCode(event) { this.setData({ pairingCode: (event.detail.value || '').replace(/\D/g, '').slice(0, 8) }) },
  async confirmPairing() {
    if (this.data.busy || !this.data.user) return
    if (!/^\d{8}$/.test(this.data.pairingCode)) { this.fail('请输入网页上的 8 位配对码'); return }
    this.setData({ busy: true, message: '' })
    try {
      const decision = await new Promise(resolve => wx.showModal({
        title: '确认登录网站',
        content: '将账号 ' + this.data.user.username + ' 登录到 ' + getApp().globalData.apiBase + ' 的网页？请仅确认本人网页显示的配对码。',
        confirmText: '确认登录', success: resolve, fail: () => resolve({ confirm: false })
      }))
      if (!decision.confirm) return
      await client.request('/auth/wechat/mini/pairings/confirm', { method: 'POST', data: { code: this.data.pairingCode } })
      this.setData({ step: 'idle', pairingCode: '', message: '已确认，请回到刚才的网页。' })
    } catch (error) { this.fail(error.message) }
    finally { this.setData({ busy: false }) }
  },
  fail(message) {
    this.setData({ message })
    wx.showToast({ title: message, icon: 'none' })
  }
})
