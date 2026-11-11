<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, user } from '../api'
import type { User } from '../api'
const props = defineProps<{ purpose: 'LOGIN' | 'BIND' }>()
const router = useRouter()
const ready = ref(false), busy = ref(false), image = ref(''), error = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0
onMounted(async () => { try { ready.value = (await api<{configured:boolean}>('/auth/wechat/mini/status')).configured } catch { ready.value = false } })
onUnmounted(() => { generation++; clearTimeout(timer) })
async function start() {
  const current = ++generation
  clearTimeout(timer); busy.value = true; image.value = ''; error.value = ''
  try {
    const qr = await api<{ticket:string;image:string}>('/auth/wechat/mini/create', {method:'POST',body:JSON.stringify({purpose:props.purpose})})
    if(current !== generation) return
    image.value = qr.image
    const poll = async () => {
      if(current !== generation) return
      try {
        const result = await api<{status:string;user?:User}>('/auth/wechat/mini/consume',{method:'POST',body:JSON.stringify({ticket:qr.ticket})})
        if(current !== generation) return
        if(result.status === 'complete' && result.user) {
          user.value = result.user; image.value = ''; busy.value = false
          if(props.purpose === 'LOGIN') await router.push(result.user.mustChangePassword ? '/password' : '/')
        } else timer = setTimeout(poll,1500)
      } catch(cause) { if(current === generation) { error.value = cause instanceof Error ? cause.message : '扫码请求失败'; image.value=''; busy.value=false } }
    }
    timer = setTimeout(poll,1500)
  } catch(cause) { if(current === generation) { error.value=cause instanceof Error ? cause.message : '无法获取小程序码';busy.value=false } }
}
</script>
<template>
  <section v-if="ready">
    <button type="button" class="secondary-button full" :disabled="busy && !image" @click="start">{{ busy && !image ? '正在获取小程序码…' : image ? '重新获取小程序码' : purpose === 'BIND' ? '用微信小程序扫码绑定' : '用微信小程序扫码登录' }}</button>
    <template v-if="image"><img :src="image" alt="微信小程序身份确认码" style="display:block;width:240px;max-width:100%;margin:16px auto" /><p class="small-note">用微信扫一扫并确认，二维码五分钟内有效。开发/体验版仅限已获授权的成员。</p></template>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>
