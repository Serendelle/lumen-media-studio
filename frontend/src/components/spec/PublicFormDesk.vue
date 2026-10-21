<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { formApi, type FormConfig, type FormTarget } from '@/api/forms'
import type { PublicCollection } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import FormBoard from './FormBoard.vue'

const props = defineProps<{ info: PublicCollection; token: string }>()
const auth = useAuthStore(), route = useRoute()
const name = ref(''), joined = ref(false), busy = ref(false), error = ref(''), secret = ref('')
const config = ref<FormConfig>(props.info.form!)
const target = computed<FormTarget>(() => ({ token: props.token, secret: secret.value }))
const loginUrl = computed(() => `/login?redirect=${encodeURIComponent(route.fullPath)}`)
onMounted(async () => {
  await auth.restore()
  const key = `lumen.form.participant:${props.info.formKey}`
  try { secret.value = localStorage.getItem(key) ?? '' } catch { /* session-only identity */ }
  if (secret.value) {
    try { await formApi(target.value).board(); joined.value = true; return } catch { /* show identity entry instead */ }
  } else {
    const bytes = crypto.getRandomValues(new Uint8Array(32))
    secret.value = btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
    try { localStorage.setItem(key, secret.value) } catch { /* identity survives for this page only */ }
  }
  if (auth.isAuthenticated) await join()
})
async function join() {
  busy.value = true; error.value = ''
  try { await formApi(target.value).join(auth.user?.displayName ?? auth.user?.username ?? name.value, secret.value); joined.value = true }
  catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
</script>

<template>
  <div class="public-desk">
    <header class="form-welcome"><span class="eyebrow">A SHARED PLACE TO COLLECT</span><h1>{{ info.name }}</h1><p>填写信息、提交附件，让收集和汇总一次完成。</p><div class="policy"><span>{{ config.viewMode === 'ALL' ? '参与者可查看全部记录' : '普通参与者仅看自己的记录' }}</span><span>指定成员按账号授权</span><span>{{ config.accepting ? '收集中' : '已停止填写' }}</span></div><small>收集链接有效至 {{ new Date(info.expiresAt).toLocaleString() }}</small></header>
    <section v-if="!joined" class="card join-card"><h2>{{ auth.isAuthenticated ? '以当前账号进入' : '先登记你的姓名' }}</h2><p>姓名用于显示，记录归属依据你的个人凭证确认。同名参与者也不会看到彼此的私有记录。</p>
      <form @submit.prevent="join"><label v-if="!auth.isAuthenticated">姓名<input v-model="name" class="input" required maxlength="80" autocomplete="name" placeholder="填写你的姓名" /></label><p v-else>{{ auth.user?.displayName ?? auth.user?.username }}</p><button class="btn" :disabled="busy || !secret || (!auth.isAuthenticated && !name.trim())">{{ busy ? '进入中…' : '进入收集表' }}</button></form>
      <p class="identity-note">免登录填写的个人凭证保存在当前浏览器，请使用原浏览器返回查看或修改。指定成员请先 <a :href="loginUrl">登录本站账号</a>；登录后可跨设备查看自己的记录。</p><p v-if="error" class="error" role="alert">{{ error }}</p>
    </section>
    <FormBoard v-else :target="target" :slots="info.slots" @config="c => config = c" />
  </div>
</template>

<style scoped>
.public-desk { display:grid;gap:32px; }.form-welcome h1 { font-size:clamp(28px,4vw,40px);letter-spacing:-.03em;line-height:1.4;margin:14px 0;overflow-wrap:anywhere; }.form-welcome p { font-size:14px;color:#6b7a70;line-height:1.8; }.eyebrow { color:#63836f;font-size:10px;letter-spacing:.16em; }.policy { display:flex;gap:10px;flex-wrap:wrap;margin:22px 0 16px; }.policy span { font-size:11px;padding:6px 10px;border:1px solid #dce6d9;border-radius:20px;color:#55745f; }.form-welcome small { font-size:11px;color:#8b988f; }.join-card { padding:30px;display:grid;gap:18px; }.join-card h2 { font-size:20px; }.join-card p { color:#6b7a70;font-size:13px;line-height:1.8; }.join-card form { display:flex;gap:16px;align-items:flex-end;flex-wrap:wrap; }.join-card label { display:grid;gap:10px;font-size:13px;flex:1;min-width:180px; }.input { border:1px solid #dce5d9;border-radius:9px;padding:12px;background:#fff;color:#233d2c; }.btn { background:#285039;color:white;border:0;border-radius:9px;padding:12px 20px; }.btn:disabled { opacity:.5; }.identity-note { border-top:1px solid #edf0e9;padding-top:18px; }.identity-note a { color:#36744d; }.join-card .error { color:#b54742; }
</style>
