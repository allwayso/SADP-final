<script setup lang="ts">
import { useRoute } from 'vue-router'
import { user } from '../api'
const route = useRoute()
</script>
<template>
  <section class="page-heading"><div class="eyebrow">我的账号</div><h1>{{ user?.displayName }}</h1><p>你的采集、核验与交流记录使用下面的用户 ID 关联。</p></section>
  <div class="card account-card"><dl class="details"><div><dt>团队账号</dt><dd>{{ user?.username }}</dd></div><div><dt>用户 ID</dt><dd class="mono">{{ user?.id }}</dd></div><div><dt>角色</dt><dd>{{ user?.roles.join('、') }}</dd></div><div><dt>微信</dt><dd>{{ user?.wechatBound ? '已绑定' : '尚未绑定' }}</dd></div></dl>
    <p v-if="route.query.notice" class="success">微信绑定成功。</p>
    <p v-if="!user?.wechatBound" class="small-note">微信登录与绑定在「check@collect」微信小程序内完成：打开小程序用微信登录，选择“用已有账号绑定微信”，输入这里的团队账号和密码即可。</p>
  </div>
</template>
