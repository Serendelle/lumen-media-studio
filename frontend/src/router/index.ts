import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('@/views/LandingView.vue'), meta: { title: 'Lumen · 流明智媒' } },
    { path: '/login', name: 'login', component: () => import('@/views/AuthView.vue'), meta: { title: '登录 · Lumen', guestOnly: true } },
    { path: '/register', name: 'register', component: () => import('@/views/AuthView.vue'), meta: { title: '注册 · Lumen', guestOnly: true } },
    {
      path: '/studio',
      name: 'studio',
      component: () => import('@/views/StudioView.vue'),
      meta: { title: '工作台 · Lumen', requiresAuth: true },
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue'), meta: { title: '未找到 · Lumen' } },
  ],
  scrollBehavior(to, _from, saved) {
    if (saved) return saved
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    return { top: 0 }
  },
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.restore()
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.guestOnly && auth.isAuthenticated) return { name: 'studio' }
})

router.afterEach((to) => {
  document.title = (to.meta.title as string) ?? 'Lumen · 流明智媒'
})

export default router
