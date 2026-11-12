// Cookie storage belongs to this API origin, never to an external browser.
let epoch = 0
let csrf = null
let csrfPending = null
const base = () => getApp().globalData.apiBase.replace(/\/$/, '')
const key = () => 'checkcollect-session:' + base()

function clearSession() {
  epoch++
  csrf = null
  csrfPending = null
  wx.removeStorageSync(key())
}

function beginIdentityChange() {
  epoch++
  csrf = null
  csrfPending = null
}

function raw(path, options = {}) {
  const generation = epoch
  const cookie = wx.getStorageSync(key())
  return new Promise((resolve, reject) => {
    wx.request({
      url: base() + '/api' + path,
      method: options.method || 'GET',
      data: options.data,
      timeout: options.timeout || 25000,
      header: { ...(cookie ? { Cookie: cookie } : {}), ...(options.header || {}) },
      success(res) {
        if (generation === epoch) {
          const headers = res.header || {}
          const cookies = res.cookies && res.cookies.length ? res.cookies : [headers['Set-Cookie'] || headers['set-cookie'] || '']
          // 同时保存会话和 CSRF Cookie：后端的 CookieCsrfTokenRepository 校验时会读取 XSRF-TOKEN，
          // 只传 JSESSIONID 会让确认类 POST 被 403 拒绝。
          const stored = new Map()
          for (const pair of String(wx.getStorageSync(key()) || '').split(';')) {
            const index = pair.indexOf('=')
            if (index > 0) stored.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim())
          }
          for (const item of cookies) {
            const text = String(item || '')
            const pattern = /(JSESSIONID|XSRF-TOKEN)=([^;,\s]*)/g
            let match
            while ((match = pattern.exec(text)) !== null) {
              if (!match[2] || /max-age=0/i.test(text)) stored.delete(match[1])
              else stored.set(match[1], match[2])
              csrf = null
            }
          }
          const joined = [...stored.entries()].map(([name, value]) => name + '=' + value).join('; ')
          if (joined) wx.setStorageSync(key(), joined)
          else wx.removeStorageSync(key())
        }
        if (res.statusCode >= 200 && res.statusCode < 300) { resolve(res.data); return }
        const error = new Error((res.data && res.data.message) || '请求失败（HTTP ' + res.statusCode + '）')
        error.status = res.statusCode
        if (res.statusCode === 401 && generation === epoch) clearSession()
        reject(error)
      },
      fail(error) { reject(new Error('无法连接服务，请检查网络和服务地址：' + (error.errMsg || '网络异常'))) }
    })
  })
}

async function request(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase()
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && path !== '/auth/wechat/mini/session') {
    if (!csrf) {
      if (!csrfPending) {
        const generation = epoch
        const pending = raw('/auth/csrf').then(value => {
          if (generation !== epoch) throw new Error('登录状态已变化，请重试')
          csrf = value
          return value
        })
        csrfPending = pending
        pending.finally(() => { if (csrfPending === pending) csrfPending = null }).catch(() => {})
      }
      await csrfPending
    }
    return raw(path, { ...options, method, header: { ...options.header, [csrf.headerName]: csrf.token } })
  }
  return raw(path, { ...options, method })
}

module.exports = { request, beginIdentityChange, clearSession }
