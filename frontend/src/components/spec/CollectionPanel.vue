<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { specsApi } from '@/api/endpoints'
import type { CollectionLink, SpecProject } from '@/api/types'
import { useToast } from '@/composables/useToast'
import { bytes } from '@/utils/format'

const props = defineProps<{ project: SpecProject }>()
const toast = useToast()
const links = ref<CollectionLink[]>([])
const days = ref(7)
const url = ref('')
const busy = ref(false)
const error = ref('')
const localOnly = ['localhost', '127.0.0.1', '[::1]'].includes(window.location.hostname)
async function load() {
  try { links.value = await specsApi.collections(props.project.id); error.value = '' }
  catch (e) { error.value = (e as Error).message }
}
onMounted(load)
async function create() {
  busy.value = true
  try {
    const link = await specsApi.createCollection(props.project.id, days.value)
    url.value = new URL(link.path!, window.location.origin).href
    await load()
    toast.ok('收集链接已生成，请复制保存')
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function stop(link: CollectionLink) {
  busy.value = true
  try { await specsApi.revokeCollection(props.project.id, link.id); url.value = ''; await load(); toast.ok('已停止收集') }
  catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function copy() {
  try { await navigator.clipboard.writeText(url.value); toast.ok('链接已复制') }
  catch { toast.error('请选中下方链接手动复制') }
}
</script>

<template>
  <section class="card collection">
    <div class="intro"><span class="eyebrow">一起收集，集中整理</span><h2>把收材料这件事，交给一个链接</h2>
      <p>参与者免登录填写姓名、编号并上传，彼此看不到资料。你可以在「材料」中查看全部提交、核对进度，再用 AI 一键整理。</p></div>
    <div class="settings">
      <label for="collection-days">收集有效期</label>
      <select id="collection-days" v-model.number="days" class="select"><option :value="1">1 天</option><option :value="7">7 天</option><option :value="14">14 天</option><option :value="30">30 天</option></select>
      <button class="btn" :disabled="busy || !project.spec.slots.length" @click="create">{{ busy ? '处理中…' : links.length ? '重新生成链接' : '生成收集链接' }}</button>
    </div>
    <p v-if="!project.spec.slots.length" class="note">先在「规范」中保存需要收集的材料项。</p>
    <p class="note">没有现成名单也可以开始，提交姓名将自动登记。导入名单后，重名或无法核实身份的提交会留待你确认。</p>
    <div v-if="url" class="share"><label for="collection-url">收集链接</label><div class="url-row"><input id="collection-url" class="input" :value="url" readonly @focus="($event.target as HTMLInputElement).select()" /><button class="btn" @click="copy">复制链接</button><a class="btn btn--ghost" :href="url" target="_blank" rel="noreferrer">预览</a></div></div>
    <p v-else-if="links.some(l => l.active)" class="note">已有收集链接正在使用。链接仅在生成时显示，请使用已保存的链接；重新生成后旧链接立即失效。</p>
    <p v-if="localOnly" class="local-note">当前使用本机地址，链接仅能在这台电脑打开。多人提交时，请从可访问的局域网或部署地址进入后生成链接。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="history" v-if="links.length"><h3>收集记录</h3><article v-for="link in links" :key="link.id">
      <div><strong>{{ link.active ? '收集中' : '已结束' }}</strong><span>{{ new Date(link.expiresAt).toLocaleString() }} 到期</span></div>
      <span>{{ link.files }} / {{ link.maxFiles }} 个文件 · {{ bytes(link.bytes) }} / {{ bytes(link.maxBytes) }}</span>
      <button v-if="link.active" class="btn btn--sm btn--ghost" :disabled="busy" @click="stop(link)">停止收集</button>
    </article></div>
  </section>
</template>

<style scoped>
.collection { padding:clamp(20px,4vw,40px); display:grid; gap:24px; }
.intro { max-width:680px; }.eyebrow { color:var(--mint-ink); font-size:12px; letter-spacing:.12em; }.intro h2 { margin:12px 0;font-size:clamp(22px,3vw,30px); }.intro p,.note { color:var(--text-2);line-height:1.8;font-size:14px; }.settings,.url-row { display:flex;gap:12px;align-items:center;flex-wrap:wrap; }.settings .select { width:140px; }.share { display:grid;gap:10px; }.url-row input { flex:1;min-width:180px; }.local-note { padding:14px 18px;border-radius:12px;background:var(--surface-sunk);color:var(--text-2);font-size:13px;line-height:1.7; }.error { color:var(--danger); }.history { display:grid;gap:12px; }.history h3 { font-size:16px; }.history article { display:flex;gap:16px;align-items:center;flex-wrap:wrap;padding:16px 0;border-top:1px solid var(--line);font-size:13px;color:var(--text-2); }.history article div { display:grid;gap:6px;flex:1; }.history strong { color:var(--text); }
</style>
