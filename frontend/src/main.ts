import { createApp } from 'vue'
import { createPinia } from 'pinia'
import '@fontsource-variable/fraunces/opsz.css'
import '@fontsource/jetbrains-mono/latin-400.css'
import './styles/tokens.css'
import './styles/base.css'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/http'
import { useAuthStore } from './stores/auth'
import { useTasksStore } from './stores/tasks'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)

// an expired/invalid session anywhere sends the user back to login, keeping where they were
setUnauthorizedHandler(() => {
  const auth = useAuthStore(pinia)
  if (!auth.isAuthenticated) return
  useTasksStore(pinia).reset()
  auth.logout()
  const current = router.currentRoute.value
  if (current.meta.requiresAuth) void router.replace({ name: 'login', query: { redirect: current.fullPath, expired: '1' } })
})

app.mount('#app')
