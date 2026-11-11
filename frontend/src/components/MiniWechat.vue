<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, user } from '../api'
import type { User } from '../api'
const props = defineProps<{ purpose: 'LOGIN' | 'BIND' }>()
const router = useRouter()
const ready = ref(false), busy = ref(false), image = ref(''), ticket = ref(''), warning = ref(''), error = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0
onMounted(async () => { try { ready.value = (await api<{configured:boolean}>('/auth/wechat/mini/status')).configured } catch { ready.value = false } })
onUnmounted(() => { generation++; clearTimeout(timer) })
async function start() {
  const current = ++generation
  clearTimeout(timer); busy.value = true; image.value = ''; ticket.value = ''; warning.value = ''; error.value = ''
  try {
    const qr = await api<{ticket:string;image:string;warning?:string}>('/auth/wechat/mini/create', {method:'POST',body:JSON.stringify({purpose:props.purpose})})
    if(current !== generation) return
    image.value = qr.image
    ticket.value = qr.ticket
    warning.value = qr.warning ?? ''
    const poll = async () => {
      if(current !== generation) return
      try {
        const result = await api<{status:string;user?:User}>('/auth/wechat/mini/consume',{method:'POST',body:JSON.stringify({ticket:qr.ticket})})
        if(current !== generation) return
        if(result.status === 'complete' && result.user) {
          user.value = result.user; image.value = ''; ticket.value = ''; warning.value = ''; busy.value = false
          if(props.purpose === 'LOGIN') await router.push(result.user.mustChangePassword ? '/password' : '/')
        } else timer = setTimeout(poll,1500)
      } catch(cause) { if(current === generation) { error.value = cause instanceof Error ? cause.message : '扫码请求失败'; image.value=''; ticket.value=''; warning.value=''; busy.value=false } }
    }
    timer = setTimeout(poll,1500)
  } catch(cause) { if(current === generation) { error.value=cause instanceof Error ? cause.message : '无法获取小程序码';busy.value=false } }
}
</script>
<template>
  <section v-if="ready">
    <button type="button" class="secondary-button full" :disabled="busy && !image && !ticket" @click="start">{{ busy && !image && !ticket ? '正在获取小程序码…' : image || ticket ? '重新获取小程序码' : purpose === 'BIND' ? '用微信小程序扫码绑定' : '用微信小程序扫码登录' }}</button>
    <template v-if="image">
      <img :src="image" alt="微信小程序身份确认码" style="display:block;width:240px;max-width:100%;margin:16px auto" />
      <p class="small-note">用微信扫一扫并确认，二维码五分钟内有效。开发/体验版仅限已获授权的成员。</p>
    </template>
    <template v-else-if="ticket">
      <p class="error" role="alert">微信未返回小程序码：{{ warning || '接口不可用' }}。本次仍可使用开发者工具预览码完成确认。</p>
      <dl class="details">
        <div><dt>编译模式启动参数</dt><dd class="mono">ticket={{ ticket }}</dd></div>
        <div><dt>确认页路径</dt><dd class="mono">pages/confirm/confirm</dd></div>
      </dl>
      <p class="small-note">在微信开发者工具中用上面的启动参数编译并“预览”，用手机扫码确认；本页面会继续等待结果，五分钟内有效。预览码只对已获授权的开发者/体验成员有效，不等同于正式发布。</p>
    </template>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>
