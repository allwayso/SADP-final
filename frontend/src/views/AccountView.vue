<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, user } from '../api'
import MiniWechat from '../components/MiniWechat.vue'
const route = useRoute()
const error = ref(route.query.error ? '微信绑定未完成，请核对微信账号后重试' : '')
async function bind() {
  error.value = ''
  try {
    const result = await api<{ url: string }>('/me/wechat/bind/start', { method: 'POST' })
    window.location.assign(result.url)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '无法发起绑定' }
}
</script>
<template>
  <section class="page-heading"><div class="eyebrow">我的账号</div><h1>{{ user?.displayName }}</h1><p>你的采集、核验与交流记录使用下面的用户 ID 关联。</p></section>
  <div class="card account-card"><dl class="details"><div><dt>团队账号</dt><dd>{{ user?.username }}</dd></div><div><dt>用户 ID</dt><dd class="mono">{{ user?.id }}</dd></div><div><dt>角色</dt><dd>{{ user?.roles.join('、') }}</dd></div><div><dt>微信</dt><dd>{{ user?.wechatBound ? '已绑定' : '尚未绑定' }}</dd></div></dl>
    <p v-if="route.query.notice" class="success">微信绑定成功。以后可以在电脑上扫码进入此账号。</p><p v-if="error" class="error" role="alert">{{ error }}</p>
    <MiniWechat v-if="!user?.wechatBound" purpose="BIND" />
    <button v-if="!user?.wechatBound" class="primary-button" @click="bind">绑定我的微信</button>
  </div>
</template>
