// UI-only smoke test. API responses are controlled; this does not test real WeChat.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const assert = require('node:assert/strict')
const base = process.env.CHECK_BASE_URL || 'http://127.0.0.1:4178'
const account = { id: 'browser-check-user', username: 'collector', displayName: '测试采集者', enabled: true, mustChangePassword: false, roles: ['COLLECTOR'], wechatBound: true }

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  try {
    for (const width of [1280, 390]) {
      const context = await browser.newContext({ viewport: { width, height: 900 } })
      let approved = false
      let consumed = 0
      let cancelled = 0
      let signedIn = false
      await context.route('**/api/**', async route => {
        const request = route.request()
        const url = new URL(request.url())
        const path = url.pathname
        if (path === '/api/me') return route.fulfill({ status: signedIn ? 200 : 401, json: signedIn ? account : {} })
        if (path.endsWith('/mini/status')) return route.fulfill({ json: { configured: true } })
        if (path.endsWith('/auth/csrf')) return route.fulfill({ json: { token: 'test-token', headerName: 'X-XSRF-TOKEN' } })
        if (path.endsWith('/pairings')) return route.fulfill({ json: { id: 'test-request', code: '01234567', expiresAt: new Date(Date.now() + 300000).toISOString() } })
        if (path.endsWith('/test-request/consume')) {
          assert.equal(request.headers()['x-xsrf-token'], 'test-token')
          consumed++
          signedIn = true
          return route.fulfill({ json: account })
        }
        if (path.endsWith('/test-request')) {
          if (request.method() === 'DELETE') { cancelled++; return route.fulfill({ status: 200, body: '' }) }
          return route.fulfill({ json: { status: approved ? 'approved' : 'pending' } })
        }
        return route.fulfill({ json: { items: [], unreadCount: 0, total: 0 } })
      })
      const page = await context.newPage()
      const errors = []
      page.on('pageerror', error => errors.push(error.message))
      await page.goto(base + '/login')
      const elements = await page.locator('button').allTextContents()
      assert.ok(elements.includes('用微信登录'))
      await page.getByRole('button', { name: '用微信登录', exact: true }).click()
      await page.getByLabel('配对码', { exact: true }).waitFor()
      assert.equal(await page.getByLabel('配对码', { exact: true }).textContent(), '01234567')
      assert.equal(await page.getByRole('button', { name: '登录', exact: true }).isDisabled(), true)
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
      if (process.env.CHECK_SCREENSHOT_DIR) await page.screenshot({ path: process.env.CHECK_SCREENSHOT_DIR + `/wechat-${width}.png`, fullPage: true })
      await page.getByRole('button', { name: '取消微信登录' }).click()
      await page.getByRole('button', { name: '用微信登录', exact: true }).waitFor()
      assert.equal(cancelled, 1)
      await page.getByRole('button', { name: '用微信登录', exact: true }).click()
      await page.getByLabel('配对码', { exact: true }).waitFor()
      approved = true
      await page.waitForURL(base + '/', { timeout: 10000 })
      assert.equal(consumed, 1)
      await page.getByText('测试采集者', { exact: true }).first().waitFor()
      assert.deepEqual(errors, [])
      console.log(`PASS ${width}px: code, cancel, single consume, account handoff and no overflow`)
      await context.close()
    }
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
