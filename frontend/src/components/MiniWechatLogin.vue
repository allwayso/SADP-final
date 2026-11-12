<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api, type User } from '../api'

defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ complete: [user: User]; active: [active: boolean] }>()
const configured = ref(false)
const pairing = ref<{ id: string; code: string; expiresAt: string } | null>(null)
const busy = ref(false)
const consuming = ref(false)
const remaining = ref(0)
const message = ref('')
let revision = 0
let timer: ReturnType<typeof setTimeout> | undefined
let countdown: ReturnType<typeof setInterval> | undefined

onMounted(async () => {
  try { configured.value = (await api<{ configured: boolean }>('/auth/wechat/mini/status')).configured }
  catch { configured.value = false }
})

function stop() {
  revision++
  clearTimeout(timer)
  clearInterval(countdown)
}

async function cancel() {
  if (consuming.value) return
  stop()
  const current = pairing.value
  pairing.value = null
  busy.value = true
  try { if (current) await api(`/auth/wechat/mini/pairings/${current.id}`, { method: 'DELETE' }) }
  catch { message.value = '请求已在本页关闭；服务端最迟在五分钟后失效。' }
  finally { busy.value = false; emit('active', false) }
}

async function start() {
  stop()
  const current = revision
  busy.value = true
  message.value = ''
  emit('active', true)
  try {
    const created = await api<{ id: string; code: string; expiresAt: string }>('/auth/wechat/mini/pairings', { method: 'POST' })
    if (current !== revision) return
    pairing.value = created
    const tick = () => { remaining.value = Math.max(0, Math.ceil((Date.parse(created.expiresAt) - Date.now()) / 1000)) }
    tick()
    countdown = setInterval(tick, 1000)
    timer = setTimeout(() => poll(current), 2000)
  } catch (error) {
    if (current !== revision) return
    message.value = error instanceof Error ? error.message : '无法发起微信登录'
    emit('active', false)
  } finally { if (current === revision) busy.value = false }
}

async function poll(current: number) {
  const request = pairing.value
  if (!request || current !== revision) return
  try {
    const state = await api<{ status: string }>(`/auth/wechat/mini/pairings/${request.id}`)
    if (current !== revision) return
    if (state.status === 'approved') {
      consuming.value = true
      const account = await api<User>(`/auth/wechat/mini/pairings/${request.id}/consume`, { method: 'POST' })
      if (current !== revision) return
      pairing.value = null
      stop()
      emit('complete', account)
      return
    }
    if (state.status !== 'pending') throw new Error(state.status === 'expired' ? '配对码已过期，请重新发起。' : '该请求已取消或已使用，请重新发起。')
    timer = setTimeout(() => poll(current), 2000)
  } catch (error) {
    if (current !== revision) return
    stop()
    pairing.value = null
    message.value = error instanceof Error ? error.message : '登录状态检查失败，请重新发起'
    emit('active', false)
  } finally { consuming.value = false }
}

onUnmounted(() => {
  stop()
  if (pairing.value) void api(`/auth/wechat/mini/pairings/${pairing.value.id}`, { method: 'DELETE' }).catch(() => {})
})
</script>

<template>
  <section v-if="configured" class="wechat-login" aria-label="微信登录">
    <div class="divider"><span>或</span></div>
    <template v-if="pairing">
      <p>打开「check@collect」微信小程序，登录后点“登录网站”，输入下面的配对码并确认。</p>
      <div class="pairing-code" aria-label="配对码">{{ pairing.code }}</div>
      <p class="small-note" aria-live="polite">{{ consuming ? '正在进入网站…' : `剩余 ${remaining} 秒，确认后本网页自动登录。` }}</p>
      <button type="button" class="secondary-button full" :disabled="consuming" @click="cancel">取消微信登录</button>
    </template>
    <button v-else type="button" class="secondary-button full" :disabled="disabled || busy" @click="start">{{ busy ? '正在生成配对码…' : '用微信登录' }}</button>
    <p v-if="message" class="error" role="alert">{{ message }}</p>
  </section>
</template>
