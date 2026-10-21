<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { collectionApi } from '@/api/endpoints'
import type { PublicCollection, SpecSlot } from '@/api/types'
import { bytes } from '@/utils/format'
import PublicFormDesk from '@/components/spec/PublicFormDesk.vue'

const route = useRoute()
const token = String(route.params.token)
const info = ref<PublicCollection | null>(null)
const error = ref('')
const name = ref('')
const identifier = ref('')
const busy = ref(false)
const identityLocked = ref(false)
type Entry = { file: File; slot: string; key: string; state: 'ready' | 'uploading' | 'done' | 'error'; progress: number; error?: string; receipt?: string }
const entries = ref<Entry[]>([])
const done = computed(() => entries.value.filter(e => e.state === 'done').length)
const pending = computed(() => entries.value.filter(e => e.state !== 'done'))
const KINDS = { VIDEO: '视频', AUDIO: '音频', IMAGE: '图片', DOCUMENT: '文档' }
onMounted(async () => {
  try { info.value = await collectionApi.get(token) }
  catch (e) { error.value = (e as Error).message }
})
function pick(event: Event, slot: SpecSlot) {
  const input = event.target as HTMLInputElement
  for (const file of Array.from(input.files ?? [])) {
    if (entries.value.length >= 30) { error.value = '每次最多选择 30 个文件'; break }
    if (file.size > (info.value?.maxFileBytes ?? 0)) { error.value = `${file.name} 超过单个文件 200MB 的限制`; continue }
    const key = Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('')
    entries.value.push({ file, slot: slot.key, key, state: 'ready', progress: 0 })
  }
  input.value = ''
}
async function submit() {
  if (!name.value.trim() || !pending.value.length || busy.value) return
  busy.value = true; identityLocked.value = true; error.value = ''
  // Freeze this attempt's identity; retries keep the same request keys and never duplicate successful submissions.
  const submittedName = name.value.trim(), submittedId = identifier.value.trim()
  for (const entry of pending.value) {
    entry.state = 'uploading'; entry.error = ''
    try {
      const receipt = await collectionApi.submit(token, entry.file, submittedName, submittedId, entry.slot, entry.key, p => entry.progress = p)
      entry.state = 'done'; entry.progress = 100; entry.receipt = receipt.receipt
    } catch (e) { entry.state = 'error'; entry.error = (e as Error).message }
  }
  busy.value = false
}
function accept(slot: SpecSlot) {
  // Requirements may allow conversions, so the chooser suggests broad kinds instead of hiding fixable formats.
  return slot.kind === 'VIDEO' ? 'video/*' : slot.kind === 'AUDIO' ? 'audio/*,video/*' : slot.kind === 'IMAGE' ? 'image/*' : '.pdf,.docx,.xlsx,.pptx,.doc,.xls,.ppt,.txt,.csv'
}
</script>

<template>
  <div class="collect-page">
    <header class="brand"><a href="/">L<span>umen</span></a><span>材料收集</span><span class="private">{{ info?.form?.fields.length ? '按负责人设置的权限协作' : '仅负责人可见' }}</span></header>
    <main class="collect-main" :class="{ 'collect-main--form': info?.form?.fields.length }" id="main">
      <PublicFormDesk v-if="info?.form?.fields.length" :info="info" :token="token" />
      <template v-else-if="info">
        <div class="welcome"><span class="eyebrow">INVITED TO CONTRIBUTE</span><h1>{{ info.name }}</h1><p>按要求提交你的材料，剩下的交给负责人整理。</p><span class="deadline">截止 {{ new Date(info.expiresAt).toLocaleString() }}</span></div>
        <form class="collect-form" @submit.prevent="submit">
          <section class="card identity"><h2><span>01</span> 你是谁</h2><div class="fields"><label>姓名 <input v-model="name" class="input" required maxlength="80" autocomplete="name" :disabled="busy || identityLocked" placeholder="填写真实姓名，方便核对" /></label><label>学号 / 编号（选填）<input v-model="identifier" class="input" maxlength="80" :disabled="busy || identityLocked" placeholder="重名时建议填写" /></label></div><p>无需注册。姓名和资料仅提供给本次收集的负责人；请勿冒用他人身份。</p></section>
          <section class="card materials"><h2><span>02</span> 选择材料</h2><article v-for="slot in info.slots" :key="slot.key" class="slot"><div><h3>{{ slot.label }} <small>{{ slot.required ? '必交' : '选交' }}</small></h3><p>{{ KINDS[slot.kind] }}<template v-if="slot.rules.extensions?.length"> · {{ slot.rules.extensions.join(' / ').toUpperCase() }}</template><template v-if="slot.rules.maxBytes"> · 要求不超过 {{ bytes(slot.rules.maxBytes) }}</template><template v-if="slot.rules.minWidth || slot.rules.minHeight"> · 最小 {{ slot.rules.minWidth ?? '不限' }} × {{ slot.rules.minHeight ?? '不限' }}</template><template v-if="slot.rules.maxWidth || slot.rules.maxHeight"> · 最大 {{ slot.rules.maxWidth ?? '不限' }} × {{ slot.rules.maxHeight ?? '不限' }}</template></p></div><label class="pick" :class="{ disabled: busy }">选择文件<input type="file" multiple :aria-label="`选择${slot.label}文件`" :accept="accept(slot)" :disabled="busy" @change="pick($event, slot)" /></label></article><p class="hint">每个文件最多 200MB。负责人会检查格式和完整性；上传成功后，本页保留提交回执。</p></section>
          <section v-if="entries.length" class="card queue" aria-live="polite"><h2>本次提交 <small>{{ done }} / {{ entries.length }} 已收到</small></h2><article v-for="(entry, index) in entries" :key="entry.key"><div class="file"><strong>{{ entry.file.name }}</strong><span>{{ info.slots.find(s => s.key === entry.slot)?.label }} · {{ bytes(entry.file.size) }}</span><span v-if="entry.receipt" class="receipt">回执 {{ entry.receipt }}</span><span v-if="entry.error" class="error">{{ entry.error }}</span></div><span class="state" :class="{ success: entry.state === 'done' }">{{ entry.state === 'done' ? '✓ 已收到' : entry.state === 'uploading' ? `${entry.progress}%` : entry.state === 'error' ? '待重试' : '待提交' }}</span><button v-if="entry.state !== 'done' && !busy" type="button" class="remove" aria-label="移除文件" @click="entries.splice(index, 1)">×</button></article></section>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <div v-if="done && !pending.length" class="success-message" role="status"><strong>材料已送达，谢谢参与。</strong><span>负责人核对后完成整理。请保存本页回执，便于联系确认。</span></div>
          <div class="submit-row"><p>你无法查看其他参与者的资料。</p><button class="btn submit" :disabled="busy || !name.trim() || !pending.length">{{ busy ? '正在提交，请保持页面打开…' : entries.some(e => e.state === 'error') ? '重试未成功的文件' : '提交材料 →' }}</button></div>
        </form>
      </template>
      <section v-else-if="error" class="card unavailable"><span class="eyebrow">COLLECTION CLOSED</span><h1>暂时无法提交</h1><p role="alert">{{ error }}</p><p>请联系负责人获取新的收集链接。</p></section>
      <p v-else class="loading">正在打开收集页面…</p>
    </main><footer>由 Lumen 流明智媒提供材料收集支持</footer>
  </div>
</template>

<style scoped>
.collect-main--form { max-width:1240px !important; }
.collect-page { min-height:100vh;background:#f6f7f3;color:#182821;--text:#182821;--text-2:#5a6960;--surface:#fff;--line:#e0e7df;--mint-ink:#28694d;--danger:#ad3b36;--ink:#182821;--cream:#fff; }.brand { display:flex;align-items:center;gap:24px;padding:24px clamp(20px,5vw,72px);border-bottom:1px solid #e0e7df;font-size:13px;color:#69776f; }.brand a { color:#254b38;text-decoration:none;font-size:26px;font-weight:800; }.brand a span { font-size:21px; }.private { margin-left:auto;border:1px solid #dce6dc;border-radius:20px;padding:6px 12px;font-size:12px; }.collect-main { max-width:800px;margin:0 auto;padding:56px 20px; }.welcome { margin-bottom:36px; }.eyebrow { font-size:11px;letter-spacing:.17em;color:#59826c; }.welcome h1 { font-size:clamp(28px,5vw,42px);letter-spacing:-.04em;line-height:1.3;margin:14px 0;overflow-wrap:anywhere; }.welcome p { color:#69776f;font-size:15px;line-height:1.7; }.deadline { display:inline-block;margin-top:18px;font-size:12px;color:#69776f; }.collect-form { display:grid;gap:20px; }.collect-page .card { background:white;border:1px solid #e1e7e0;border-radius:20px;padding:28px;box-shadow:0 8px 24px #21352504; }.collect-page h2 { font-size:17px;margin-bottom:22px;display:flex;gap:12px;align-items:center; }.collect-page h2>span { font-size:12px;color:#8a9c8e; }.fields { display:grid;grid-template-columns:1fr 1fr;gap:20px; }.fields label { display:grid;gap:10px;font-size:13px; }.input { border:1px solid #d8e1d7;border-radius:10px;padding:12px;background:white;color:#182821;min-width:0; }.identity p,.hint { font-size:12px;line-height:1.8;color:#718075;margin-top:18px; }.slot { display:flex;align-items:center;justify-content:space-between;gap:16px;padding:20px 0;border-top:1px solid #edf0eb; }.slot h3 { font-size:15px;margin-bottom:7px; }.slot small { font-size:10px;font-weight:400;margin-left:5px;color:#698373; }.slot p { font-size:12px;color:#69776f;line-height:1.8; }.pick { position:relative;display:inline-flex;align-items:center; border:1px solid #dae3d8;border-radius:9px;font-size:12px;padding:10px 14px;white-space:nowrap;cursor:pointer; }.pick:focus-within { outline:2px solid #438862;outline-offset:3px; }.pick.disabled { opacity:.5;pointer-events:none; }.pick input { position:absolute;inset:0;width:100%;height:100%;opacity:0;cursor:pointer; }.queue h2 small { font-size:12px;color:#718075;margin-left:auto;font-weight:400; }.queue article { display:flex;align-items:center;gap:12px;border-top:1px solid #edf0eb;padding:16px 0; }.file { flex:1;min-width:0;display:grid;gap:7px; }.file strong { font-size:13px;overflow-wrap:anywhere; }.file span { font-size:11px;color:#718075; }.file .receipt { font-size:10px;overflow-wrap:anywhere; }.state { font-size:12px;color:#718075;white-space:nowrap; }.success { color:#287a4d; }.remove { background:none;border:0;font-size:20px;color:#87968c; }.error,.file .error { color:#ad3b36;font-size:13px;line-height:1.8; }.submit-row { display:flex;align-items:center;justify-content:space-between;gap:18px;flex-wrap:wrap; }.submit-row p { color:#7c8a80;font-size:12px; }.submit { background:#244d38;color:#fff;border:0;border-radius:10px;padding:14px 24px;min-height:48px; }.submit:disabled { opacity:.5;cursor:not-allowed; }.success-message { display:grid;gap:10px;background:#e9f4e9;border:1px solid #cfe2cf;border-radius:16px;padding:24px;color:#286944; }.success-message span { font-size:13px;line-height:1.7; }.unavailable { margin:40px 0;text-align:center; }.unavailable h1 { margin:20px 0;font-size:28px; }.unavailable p { color:#69776f;line-height:2;font-size:14px; }footer { text-align:center;color:#98a39a;font-size:11px;padding:0 20px 36px; }.loading { text-align:center;color:#718075; }@media(max-width:560px) { .fields { grid-template-columns:1fr; }.collect-page .card { padding:22px; }.brand { gap:14px; }.brand>span:not(.private) { display:none; }.collect-main { padding-top:36px; }.submit { width:100%; } }
</style>
