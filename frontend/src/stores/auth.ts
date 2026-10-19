import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/endpoints'
import { ApiError, tokenStore } from '@/api/http'
import type { UserView } from '@/api/types'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(tokenStore.get())
  const user = ref<UserView | null>(null)
  const ready = ref(false)

  const isAuthenticated = computed(() => !!token.value)

  function apply(t: string, u: UserView) {
    token.value = t
    user.value = u
    tokenStore.set(t)
  }

  async function login(username: string, password: string) {
    const r = await authApi.login(username, password)
    apply(r.token, r.user)
  }

  async function register(username: string, password: string, displayName?: string) {
    const r = await authApi.register(username, password, displayName)
    apply(r.token, r.user)
  }

  /** Validates a stored token once per page load. */
  /**
   * Only a 401 means the token is bad. A network error or 5xx (e.g. the backend is restarting) keeps the session
   * and leaves ready=false, so the next navigation simply tries again instead of silently logging the user out.
   */
  async function restore() {
    if (ready.value) return
    if (!token.value) {
      ready.value = true
      return
    }
    try {
      user.value = await authApi.me()
      ready.value = true
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        logout()
        ready.value = true
      }
    }
  }

  function logout() {
    token.value = null
    user.value = null
    tokenStore.set(null)
  }

  return { token, user, ready, isAuthenticated, login, register, restore, logout }
})
