const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const path = require('node:path')
const flush = () => new Promise(resolve => setImmediate(resolve))

function requestHarness() {
  const calls = []
  const storage = new Map()
  const context = {
    module: { exports: {} }, getApp: () => ({ globalData: { apiBase: 'https://example.test' } }),
    wx: {
      getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value),
      removeStorageSync: key => storage.delete(key), request: options => calls.push(options)
    }
  }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../utils/request.js'), 'utf8'), context)
  return { client: context.module.exports, calls, storage }
}

test('login cookie is stored and explicitly sent on following requests', async () => {
  const h = requestHarness()
  const login = h.client.request('/auth/wechat/mini/session', { method: 'POST', data: { code: 'once' } })
  h.calls.shift().success({ statusCode: 200, data: { status: 'logged-in' }, cookies: ['JSESSIONID=rotated; Path=/; HttpOnly'] })
  await login
  const me = h.client.request('/me')
  const call = h.calls.shift()
  assert.equal(call.header.Cookie, 'JSESSIONID=rotated')
  call.success({ statusCode: 200, data: { username: 'collector' } })
  assert.equal((await me).username, 'collector')
  assert.equal(h.storage.get('checkcollect-session:https://example.test'), 'JSESSIONID=rotated')
})

test('write requests obtain CSRF token and preserve cookies from response headers', async () => {
  const h = requestHarness()
  const logout = h.client.request('/auth/wechat/mini/logout', { method: 'POST' })
  const csrf = h.calls.shift()
  assert.match(csrf.url, /\/auth\/csrf$/)
  csrf.success({ statusCode: 200, data: { token: 'masked', headerName: 'X-XSRF-TOKEN' }, header: { 'Set-Cookie': 'JSESSIONID=one; Path=/' } })
  await flush()
  const write = h.calls.shift()
  assert.equal(write.header.Cookie, 'JSESSIONID=one')
  assert.equal(write.header['X-XSRF-TOKEN'], 'masked')
  write.success({ statusCode: 403, data: { message: 'denied' } })
  await assert.rejects(logout, error => error.status === 403 && error.message === 'denied')
  assert.equal(h.storage.get('checkcollect-session:https://example.test'), 'JSESSIONID=one')
})

test('old anonymous responses cannot erase or replace a newly authenticated cookie', async () => {
  const h = requestHarness()
  const old = h.client.request('/me').catch(error => error)
  const oldCall = h.calls.shift()
  h.client.beginIdentityChange()
  const login = h.client.request('/auth/wechat/mini/session', { method: 'POST' })
  h.calls.shift().success({ statusCode: 200, data: {}, cookies: ['JSESSIONID=new; Path=/'] })
  await login
  oldCall.success({ statusCode: 401, data: {}, cookies: ['JSESSIONID=stale; Path=/'] })
  assert.equal((await old).status, 401)
  assert.equal(h.storage.get('checkcollect-session:https://example.test'), 'JSESSIONID=new')
})

function pageHarness() {
  let definition
  let clears = 0
  const calls = []
  const context = {
    Page: value => { definition = value },
    getApp: () => ({ globalData: { apiBase: 'https://example.test' } }),
    require: () => ({
      beginIdentityChange() {}, clearSession() { clears++ },
      request: (url, options) => new Promise((resolve, reject) => calls.push({ url, options, resolve, reject }))
    }),
    wx: { login: options => options.success({ code: 'new' }), showToast() {}, showModal: options => options.success({ confirm: false }) }
  }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/index/index.js'), 'utf8'), context)
  const page = { ...definition, data: structuredClone(definition.data), setData(update) { Object.assign(this.data, update) } }
  return { page, calls, clears: () => clears }
}

test('late pre-login /me result cannot clear the new account card', async () => {
  const h = pageHarness()
  const refresh = h.page.refresh()
  const old = h.calls.shift()
  const login = h.page.login()
  await flush()
  h.calls.shift().resolve({ status: 'logged-in', user: { id: 'same-user' } })
  await login
  old.reject(Object.assign(new Error('anonymous'), { status: 401 }))
  await refresh
  assert.equal(h.page.data.user.id, 'same-user')
})

test('failed logout preserves account and successful logout clears it', async () => {
  const h = pageHarness()
  h.page.data.user = { id: 'same-user' }
  const failed = h.page.logout()
  h.calls.shift().reject(Object.assign(new Error('denied'), { status: 403 }))
  await failed
  assert.equal(h.page.data.user.id, 'same-user')
  assert.match(h.page.data.message, /退出失败/)
  assert.equal(h.clears(), 0)
  const success = h.page.logout()
  h.calls.shift().resolve('')
  await success
  assert.equal(h.page.data.user, null)
  assert.equal(h.clears(), 1)
})

test('cancelling the explicit pairing dialog does not authorize a browser', async () => {
  const h = pageHarness()
  h.page.data.user = { username: 'collector' }
  h.page.data.pairingCode = '12345678'
  await h.page.confirmPairing()
  assert.equal(h.calls.length, 0)
  assert.equal(h.page.data.busy, false)
})
