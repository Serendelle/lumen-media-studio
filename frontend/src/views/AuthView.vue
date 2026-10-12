<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import BrandMark from '@/components/common/BrandMark.vue'
import LightField from '@/components/landing/LightField.vue'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const isRegister = computed(() => route.name === 'register')
const form = reactive({ username: '', password: '', displayName: '' })
const fieldErrors = ref<Record<string, string>>({})
const error = ref<string | null>(route.query.expired ? '登录已过期，请重新登录' : null)
const busy = ref(false)

watch(isRegister, () => {
  error.value = null
  fieldErrors.value = {}
})

function localCheck(): boolean {
  const f: Record<string, string> = {}
  if (form.username.trim().length < 3) f.username = '用户名至少 3 个字符'
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
    <div class="backdrop"><LightField /></div>
    <main id="main" class="panel card">
      <RouterLink to="/" class="brand" aria-label="返回首页"><BrandMark /></RouterLink>
      <h1 class="title">{{ isRegister ? '创建你的工作室' : '欢迎回来' }}</h1>
      <p class="sub">{{ isRegister ? '注册后即可上传媒体、使用全部处理能力。' : '登录以继续你的创作。' }}</p>

      <form class="form" novalidate @submit.prevent="submit">
        <div class="field">
          <label for="u">用户名</label>
          <input id="u" v-model="form.username" class="input" autocomplete="username" maxlength="32"
                 :aria-invalid="!!fieldErrors.username" aria-describedby="u-err" />
          <span v-if="fieldErrors.username" id="u-err" class="err">{{ fieldErrors.username }}</span>
        </div>
        <div v-if="isRegister" class="field">
          <label for="d">昵称 <span class="opt">（可选）</span></label>
          <input id="d" v-model="form.displayName" class="input" maxlength="32" autocomplete="nickname" />
        </div>
        <div class="field">
          <label for="p">密码</label>
          <input id="p" v-model="form.password" class="input" type="password" maxlength="72"
                 :autocomplete="isRegister ? 'new-password' : 'current-password'"
                 :aria-invalid="!!fieldErrors.password" aria-describedby="p-err" />
          <span v-if="fieldErrors.password" id="p-err" class="err">{{ fieldErrors.password }}</span>
          <span v-else-if="isRegister" class="hint">至少 8 位</span>
        </div>

        <p v-if="error" class="alert" role="alert">{{ error }}</p>

        <button class="btn submit" type="submit" :disabled="busy">
          {{ busy ? '请稍候…' : isRegister ? '注册并进入' : '登录' }}
        </button>
      </form>

      <p class="switch">
        <template v-if="isRegister">已有账号？<RouterLink :to="{ name: 'login', query: route.query }">登录</RouterLink></template>
        <template v-else>还没有账号？<RouterLink :to="{ name: 'register', query: route.query }">免费注册</RouterLink></template>
      </p>
    </main>
  </div>
</template>

<style scoped>
.auth {
  position: relative;
  min-height: 100svh;
  display: grid;
  place-items: center;
  padding: var(--sp-6) var(--gutter);
  isolation: isolate;
}
.backdrop {
  position: fixed;
  inset: 0;
  z-index: -1;
}
.panel {
  width: min(420px, 100%);
  padding: var(--sp-6);
  border-radius: var(--r-lg);
  background: rgba(252, 252, 249, 0.92);
  box-shadow: var(--shadow-3);
}
.brand {
  text-decoration: none;
}
.title {
  margin-top: var(--sp-6);
  font-size: var(--fs-xl);
}
.sub {
  margin-top: var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.form {
  display: grid;
  gap: var(--sp-4);
  margin-top: var(--sp-5);
}
.opt {
  color: var(--text-3);
}
.err {
  font-size: var(--fs-xs);
  color: var(--danger);
}
.alert {
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: var(--fs-sm);
}
.submit {
  width: 100%;
  margin-top: var(--sp-2);
}
.switch {
  margin-top: var(--sp-5);
  font-size: var(--fs-sm);
  color: var(--text-2);
  text-align: center;
}
.switch a {
  color: var(--mint-ink);
  font-weight: 500;
}
</style>
