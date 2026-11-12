<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login, user, type User } from '../api'
import MiniWechatLogin from '../components/MiniWechatLogin.vue'

const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const error = ref(route.query.error ? '微信授权未完成，请重新授权或使用账号密码登录' : '')
const busy = ref(false)
const pairingActive = ref(false)

async function completeWechat(account: User) {
  user.value = account
  await router.push(account.mustChangePassword ? '/password' : (typeof route.query.next === 'string' ? route.query.next : '/'))
}

async function submit() {
  if (busy.value || pairingActive.value) return
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
      <p class="muted">使用团队账号，或通过微信小程序确认后进入网站。</p>
      <form @submit.prevent="submit">
        <label>团队账号<input v-model="username" name="username" autocomplete="username" required /></label>
        <label>密码<input v-model="password" name="password" type="password" autocomplete="current-password" required /></label>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <button class="primary-button full" :disabled="busy || pairingActive">{{ busy ? '正在登录…' : '登录' }}</button>
      </form>
      <MiniWechatLogin :disabled="busy" @active="pairingActive = $event" @complete="completeWechat" />
      <p class="small-note">首次使用微信，请在小程序中绑定团队账号或注册采集者账号。</p>
    </section>
  </div>
</template>
