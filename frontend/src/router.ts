import { createRouter, createWebHistory } from 'vue-router'
import { loadMe, user } from './api'
import LoginView from './views/LoginView.vue'
import HomeView from './views/HomeView.vue'
import PasswordView from './views/PasswordView.vue'
import AccountView from './views/AccountView.vue'
import AdminView from './views/AdminView.vue'
import ReviewView from './views/ReviewView.vue'
import MessagesView from './modules/messaging/MessagesView.vue'
import PoiMapView from './views/PoiMapView.vue'
import PoiCollectView from './views/PoiCollectView.vue'
import PoiDetailView from './views/PoiDetailView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/password', component: PasswordView },
    { path: '/account', component: AccountView },
    { path: '/admin/users', component: AdminView, meta: { role: 'ADMIN' } },
    { path: '/poi', component: PoiMapView, meta: { roles: ['COLLECTOR', 'REVIEWER', 'FIELD_WORKER', 'ADMIN'] } },
    { path: '/poi/new', component: PoiCollectView, meta: { role: 'COLLECTOR' } },
    { path: '/poi/:id/revisions/new', component: PoiCollectView, meta: { role: 'COLLECTOR' } },
    { path: '/poi/:id', component: PoiDetailView, meta: { roles: ['COLLECTOR', 'REVIEWER', 'FIELD_WORKER', 'ADMIN'] } },
    { path: '/review', component: ReviewView, meta: { roles: ['REVIEWER', 'FIELD_WORKER', 'ADMIN', 'COLLECTOR'] } },
    { path: '/messages', component: MessagesView },
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
