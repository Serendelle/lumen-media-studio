import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('@/views/ReactLandingView.vue'), meta: { title: 'Lumen · 流明智媒' } },
    // previous Vue landing page, kept as a fallback
    { path: '/classic', name: 'home-classic', component: () => import('@/views/LandingView.vue'), meta: { title: 'Lumen · 流明智媒' } },
    { path: '/collect/:token', name: 'collection-submit', component: () => import('@/views/CollectionSubmitView.vue'), meta: { title: '提交材料 · Lumen', publicCollection: true } },
    { path: '/login', name: 'login', component: () => import('@/views/AuthView.vue'), meta: { title: '登录 · Lumen', guestOnly: true } },
    { path: '/register', name: 'register', component: () => import('@/views/AuthView.vue'), meta: { title: '注册 · Lumen', guestOnly: true } },
    {
      path: '/workspace',
      component: () => import('@/layouts/WorkspaceLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        { path: '', name: 'workspace', component: () => import('@/views/WorkspaceHomeView.vue'), meta: { title: '概览 · Lumen' } },
        {
          path: 'media', name: 'media', component: () => import('@/views/MediaWorkspaceView.vue'), meta: { title: '媒体工作台 · Lumen' },
          // smart mode moved to its own page; old ?mode=ai links keep working
          beforeEnter: (to) => (to.query.mode === 'ai' ? { path: '/workspace/ai', query: to.query.conv ? { conv: to.query.conv } : {} } : true),
        },
        { path: 'ai', name: 'ai', component: () => import('@/views/AiWorkspaceView.vue'), meta: { title: '智能模式 · Lumen' } },
        { path: 'documents', name: 'documents', component: () => import('@/views/DocumentWorkspaceView.vue'), meta: { title: '文档工作台 · Lumen' } },
        { path: 'specs', name: 'specs', component: () => import('@/views/SpecListView.vue'), meta: { title: '材料规范官 · Lumen' } },
        { path: 'specs/:id', name: 'spec', component: () => import('@/views/SpecDeskView.vue'), meta: { title: '材料规范官 · Lumen' } },
        { path: 'learn', name: 'learn', component: () => import('@/views/LessonListView.vue'), meta: { title: '学习播放器 · Lumen' } },
        { path: 'learn/:id', name: 'lesson', component: () => import('@/views/LessonPlayerView.vue'), meta: { title: '学习播放器 · Lumen' } },
        { path: 'files', name: 'files', component: () => import('@/views/FileLibraryView.vue'), meta: { title: '我的文件 · Lumen' } },
        { path: 'tasks', name: 'tasks', component: () => import('@/views/TaskCenterView.vue'), meta: { title: '任务中心 · Lumen' } },
      ],
    },
    // legacy entry: old links (/studio?mode=ai&tool=audio) keep working and keep their intent
    { path: '/studio', name: 'studio', redirect: (to) => ({ path: '/workspace/media', query: to.query, hash: to.hash }) },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue'), meta: { title: '未找到 · Lumen' } },
  ],
  scrollBehavior(to, _from, saved) {
    if (saved) return saved
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    return { top: 0 }
  },
})

router.beforeEach(async (to) => {
  if (to.meta.publicCollection) return
  const auth = useAuthStore()
  await auth.restore()
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.guestOnly && auth.isAuthenticated) return { name: 'workspace' }
})

router.afterEach((to) => {
  document.title = (to.meta.title as string) ?? 'Lumen · 流明智媒'
})

export default router
