<script setup lang="ts">
import { onBeforeUnmount, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { logout, user } from './api'
import { refreshUnreadCount, unreadCount } from './modules/messaging/api'

const router = useRouter()
async function signOut() {
  await logout()
  await router.push('/login')
}

let badgeTimer: number | undefined
function refreshBadge() {
  if (user.value && !user.value.mustChangePassword) refreshUnreadCount()
}
watch(user, refreshBadge, { immediate: true })
onMounted(() => { badgeTimer = window.setInterval(refreshBadge, 30_000) })
onBeforeUnmount(() => window.clearInterval(badgeTimer))
</script>

<template>
  <div class="app-shell">
    <header class="masthead">
      <RouterLink to="/" class="brand"><span class="brand-mark">◎</span><span>check<span class="brand-accent">@</span>collect</span></RouterLink>
      <nav v-if="user && !user.mustChangePassword" class="top-nav" aria-label="主导航">
        <RouterLink to="/">工作台</RouterLink>
        <RouterLink to="/review">核验与异议</RouterLink>
        <RouterLink to="/messages">消息<span v-if="unreadCount" class="nav-badge">{{ unreadCount > 99 ? '99+' : unreadCount }}</span></RouterLink>
        <RouterLink to="/account">我的账号</RouterLink>
        <RouterLink v-if="user.roles.includes('ADMIN')" to="/admin/users">用户管理</RouterLink>
      </nav>
      <div v-if="user" class="session">
        <span class="session-name">{{ user.displayName }}</span>
        <button class="text-button" @click="signOut">退出</button>
      </div>
    </header>
    <main class="page-wrap"><RouterView /></main>
    <footer class="footer"><span>POI 数据采集与核验</span><span>check@collect</span></footer>
  </div>
</template>

<style scoped>
.nav-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  margin-left: 5px;
  border-radius: 9px;
  background: #d56f48;
  color: #fff;
  font-size: 11px;
  line-height: 1;
  vertical-align: 1px;
}
</style>
