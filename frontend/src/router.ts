import { createRouter, createWebHistory } from 'vue-router'
import { loadMe, user } from './api'
import LoginView from './views/LoginView.vue'
import HomeView from './views/HomeView.vue'
import PasswordView from './views/PasswordView.vue'
import AccountView from './views/AccountView.vue'
import AdminView from './views/AdminView.vue'
import ModuleView from './views/ModuleView.vue'
import ReviewView from './views/ReviewView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/password', component: PasswordView },
    { path: '/account', component: AccountView },
    { path: '/admin/users', component: AdminView, meta: { role: 'ADMIN' } },
    { path: '/poi', component: ModuleView, props: { title: 'POI 采集与地图', owner: '成员 B' }, meta: { role: 'COLLECTOR' } },
    { path: '/review', component: ReviewView, meta: { roles: ['REVIEWER', 'FIELD_WORKER', 'ADMIN', 'COLLECTOR'] } },
    { path: '/messages', component: ModuleView, props: { title: '消息与交流', owner: '成员 D' } },
    { path: '/', component: HomeView },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async to => {
  if (to.meta.public) {
    if (!user.value) await loadMe()
    return user.value ? (user.value.mustChangePassword ? '/password' : '/') : true
  }
  if (!user.value) await loadMe()
  if (!user.value) return { path: '/login', query: { next: to.fullPath } }
  if (user.value.mustChangePassword && to.path !== '/password') return '/password'
  if (!user.value.mustChangePassword && to.path === '/password') return '/'
  if (to.meta.role && !user.value.roles.includes(to.meta.role as typeof user.value.roles[number])) return '/'
  if (to.meta.roles && !(to.meta.roles as string[]).some(role => user.value?.roles.includes(role as typeof user.value.roles[number]))) return '/'
  return true
})

export default router
