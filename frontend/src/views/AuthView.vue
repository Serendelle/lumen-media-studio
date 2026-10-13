<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import BrandMark from '@/components/common/BrandMark.vue'
import '@fontsource-variable/inter'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const isRegister = computed(() => route.name === 'register')
const form = reactive({ username: '', password: '', displayName: '' })
const fieldErrors = ref<Record<string, string>>({})
const error = ref<string | null>(route.query.expired ? '登录已过期，请重新登录' : null)
const busy = ref(false)
const toast = useToast()

// there is no password-reset endpoint on the backend, so say so instead of pretending
function forgotPassword() {
  toast.info('暂不支持在线找回密码，请联系管理员重置。')
}

watch(isRegister, () => {
  error.value = null
  fieldErrors.value = {}
})

function localCheck(): boolean {
  const f: Record<string, string> = {}
  if (form.username.trim().length < 3) f.username = '账号至少 3 个字符'
  if (isRegister.value && !/^[A-Za-z0-9_\-一-龥]+$/.test(form.username.trim()))
    f.username = '只能包含字母、数字、下划线、短横线或中文'
  if (form.password.length < 8) f.password = '密码至少 8 位'
  fieldErrors.value = f
  return Object.keys(f).length === 0
}

async function submit() {
  error.value = null
  if (!localCheck()) return
  busy.value = true
  try {
    if (isRegister.value) await auth.register(form.username.trim(), form.password, form.displayName.trim() || undefined)
    else await auth.login(form.username.trim(), form.password)
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/studio'
    await router.replace(redirect)
  } catch (e) {
    if (e instanceof ApiError) {
      error.value = e.message
      fieldErrors.value = e.fields
    } else {
      error.value = '出现了意外错误，请重试'
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="auth">
    <div class="glow" aria-hidden="true" />
    <main id="main" class="wrap">
      <RouterLink to="/" class="brand" aria-label="返回首页"><BrandMark /></RouterLink>
      <h1 class="title">{{ isRegister ? '创建账号' : '欢迎回来' }}</h1>
      <p class="sub">{{ isRegister ? '注册后即可使用全部处理能力。' : '登录以继续你的创作。' }}</p>

      <form class="card" novalidate @submit.prevent="submit">
        <div class="field">
          <label for="u">账号</label>
          <div class="box" :class="{ bad: fieldErrors.username }">
            <svg class="ic" viewBox="0 0 24 24" aria-hidden="true"><path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" /><circle cx="12" cy="7" r="4" /></svg>
            <input id="u" v-model="form.username" placeholder="请输入账号" autocomplete="username" maxlength="32"
                   :aria-invalid="!!fieldErrors.username" aria-describedby="u-err" />
          </div>
          <span v-if="fieldErrors.username" id="u-err" class="err">{{ fieldErrors.username }}</span>
        </div>

        <div class="field">
          <label for="p">密码</label>
          <div class="box" :class="{ bad: fieldErrors.password }">
            <svg class="ic" viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="11" width="18" height="11" rx="2" /><path d="M7 11V7a5 5 0 0 1 10 0v4" /></svg>
            <input id="p" v-model="form.password" type="password" :placeholder="isRegister ? '至少 8 位' : '请输入密码'" maxlength="72"
                   :autocomplete="isRegister ? 'new-password' : 'current-password'"
                   :aria-invalid="!!fieldErrors.password" aria-describedby="p-err" />
          </div>
          <span v-if="fieldErrors.password" id="p-err" class="err">{{ fieldErrors.password }}</span>
        </div>

        <div v-if="!isRegister" class="row">
          <button type="button" class="link" @click="forgotPassword">忘记密码？</button>
        </div>

        <p v-if="error" class="alert" role="alert">{{ error }}</p>

        <button class="submit" type="submit" :disabled="busy">
          {{ busy ? '请稍候…' : isRegister ? '注册' : '登录' }}
        </button>

        <p class="switch">
          <template v-if="isRegister">已有账号？<RouterLink :to="{ name: 'login', query: route.query }">去登录</RouterLink></template>
          <template v-else>还没有账号？<RouterLink :to="{ name: 'register', query: route.query }">去注册</RouterLink></template>
        </p>
      </form>
    </main>
  </div>
</template>

<style scoped>
/* Dark sign-in card in the landing page's language (black, white accents, Inter). */
.auth {
  --fg: #fafafa;
  --muted: #a1a1a1;
  --line: rgba(255, 255, 255, 0.12);
  --danger-dark: #ff6b6b;
  position: relative;
  min-height: 100svh;
  display: grid;
  place-items: center;
  padding: 32px 16px;
  background: #000;
  color: var(--fg);
  font-family: 'Inter Variable', ui-sans-serif, system-ui, sans-serif;
  isolation: isolate;
  overflow: hidden;
}
/* soft light from the top + a faint dot grid, echoing the hero */
.glow {
  position: absolute;
  inset: 0;
  z-index: -1;
  background:
    radial-gradient(ellipse 60% 45% at 50% 0%, rgba(255, 255, 255, 0.12), transparent 70%),
    radial-gradient(circle, rgba(255, 255, 255, 0.12) 1px, transparent 1.4px) 0 0 / 26px 26px;
  mask-image: radial-gradient(ellipse 80% 70% at 50% 40%, #000 30%, transparent 85%);
}
.wrap {
  width: min(420px, 100%);
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  animation: rise 0.6s cubic-bezier(0.22, 1, 0.36, 1) both;
}
@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(14px);
    filter: blur(6px);
  }
}
.brand {
  text-decoration: none;
}
.brand :deep(.en) {
  color: var(--fg);
  font-family: inherit;
  font-weight: 600;
  letter-spacing: -0.02em;
}
.brand :deep(.zh) {
  color: var(--muted);
}
.title {
  margin-top: 28px;
  font-size: 1.75rem;
  font-weight: 600;
  letter-spacing: -0.02em;
}
.sub {
  margin-top: 8px;
  font-size: 0.875rem;
  color: var(--muted);
}
.card {
  width: 100%;
  margin-top: 28px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 24px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #0a0a0a;
  box-shadow: 0 40px 120px -40px rgba(255, 255, 255, 0.15);
  text-align: left;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.field label {
  font-size: 0.875rem;
  font-weight: 500;
}
.box {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 48px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.box:focus-within {
  border-color: rgba(255, 255, 255, 0.45);
  box-shadow: 0 0 0 3px rgba(255, 255, 255, 0.12);
}
.box.bad {
  border-color: var(--danger-dark);
}
.ic {
  width: 20px;
  height: 20px;
  flex: none;
  fill: none;
  stroke: var(--muted);
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.box:focus-within .ic {
  stroke: var(--fg);
}
.box input {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--fg);
  font-size: 0.9375rem;
}
.box input::placeholder {
  color: #6b6b6b;
}
/* keep browser autofill from painting the field yellow/white */
.box input:-webkit-autofill {
  -webkit-text-fill-color: var(--fg);
  transition: background-color 9999s;
}
.err {
  font-size: 0.75rem;
  color: var(--danger-dark);
}
.row {
  display: flex;
  justify-content: flex-end;
  margin-top: -8px;
}
.link {
  border: 0;
  padding: 0;
  background: none;
  color: var(--fg);
  font-size: 0.875rem;
  cursor: pointer;
}
.link:hover {
  text-decoration: underline;
}
.alert {
  padding: 10px 12px;
  border: 1px solid rgba(255, 107, 107, 0.3);
  border-radius: 10px;
  background: rgba(255, 107, 107, 0.08);
  color: var(--danger-dark);
  font-size: 0.875rem;
}
.submit {
  height: 48px;
  border: 0;
  border-radius: 10px;
  background: var(--fg);
  color: #0a0a0a;
  font-size: 1rem;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.2s, transform 0.15s;
}
.submit:hover {
  opacity: 0.9;
}
.submit:active {
  transform: scale(0.98);
}
.submit:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.switch {
  text-align: center;
  font-size: 0.875rem;
  color: var(--muted);
}
.switch a {
  color: var(--fg);
  font-weight: 500;
  text-decoration: none;
}
.switch a:hover {
  text-decoration: underline;
}
@media (prefers-reduced-motion: reduce) {
  .wrap {
    animation: none;
  }
}
</style>
