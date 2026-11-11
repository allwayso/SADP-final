<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '../api'
import MiniWechat from '../components/MiniWechat.vue'

const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const error = ref(route.query.error ? '微信授权未完成，请重新扫码或使用账号密码登录' : '')
const busy = ref(false)
const wechatReady = ref(false)
onMounted(async () => {
  try {
    const response = await fetch('/api/auth/wechat/status')
    wechatReady.value = response.ok && (await response.json() as { configured: boolean }).configured
  } catch { wechatReady.value = false }
})

async function submit() {
  busy.value = true
  error.value = ''
  try {
    const account = await login(username.value, password.value)
    await router.push(account.mustChangePassword ? '/password' : (typeof route.query.next === 'string' ? route.query.next : '/'))
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败'
  } finally { busy.value = false }
}
</script>

<template>
  <div class="login-layout">
    <section class="login-intro">
      <div class="eyebrow">FIELD DATA / POI</div>
      <h1>把现场的点位<br />变成可信的数据。</h1>
      <p>采集、核验和处理进展在同一处完成。每位成员使用自己的团队账号，记录会归属到本人。</p>
      <div class="map-lines" aria-hidden="true"><span></span><span></span><span></span><i>◎</i></div>
    </section>
    <section class="card login-card">
      <div class="eyebrow">账号登录</div>
      <h2>开始工作</h2>
      <p class="muted">手机现场作业使用团队账号。电脑也可以使用已绑定的微信扫码。</p>
      <form @submit.prevent="submit">
        <label>团队账号<input v-model="username" name="username" autocomplete="username" required /></label>
        <label>密码<input v-model="password" name="password" type="password" autocomplete="current-password" required /></label>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <button class="primary-button full" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
      </form>
      <MiniWechat purpose="LOGIN" />
      <template v-if="wechatReady"><div class="divider"><span>或</span></div><a class="secondary-button full" href="/api/auth/wechat/start">用微信扫码登录</a><p class="small-note">首次使用微信，请先登录团队账号并在“我的账号”绑定。</p></template>
    </section>
  </div>
</template>
