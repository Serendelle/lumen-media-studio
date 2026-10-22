<script setup lang="ts">
import { ref, watch } from 'vue'
import { collaborationApi as api, dateTime, isAccessError, reviewLabel, type AssistantResult, type CheckReport } from '@/api/collaboration'
import { bytes } from '@/utils/format'

const props = defineProps<{ projectId: string; revision: number }>()
const emit = defineEmits<{ denied: [] }>()
const report = ref<CheckReport>(), assistant = ref<AssistantResult>(), loading = ref(false), thinking = ref(false), error = ref(''), consent = ref(false)
let request = 0
async function checks() {
  const seq = ++request; loading.value = true; error.value = ''
  try { const result = await api.checks(props.projectId); if (seq === request) report.value = result }
  catch (e) { if (seq === request) { error.value = (e as Error).message; if (isAccessError(e)) { report.value = undefined; assistant.value = undefined; emit('denied') } } }
  finally { if (seq === request) loading.value = false }
}
watch(() => [props.projectId, props.revision], () => { assistant.value = undefined; void checks() }, { immediate: true })
async function ask() {
  if (!consent.value) return
  thinking.value = true; error.value = ''
  try { assistant.value = await api.assistant(props.projectId) }
  catch (e) { error.value = (e as Error).message; if (isAccessError(e)) emit('denied') }
  finally { thinking.value = false }
}
function fact(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}
function checkText(check: unknown): string {
  if (typeof check === 'string') return check
  if (check && typeof check === 'object') { const record = check as Record<string, unknown>; return [record.label || record.rule || record.key || record.code, record.message || record.status].filter(Boolean).map(fact).join(' · ') || JSON.stringify(record) }
  return String(check)
}
</script>

<template>
  <section class="team-card">
    <header class="team-section-head"><div><h2>事实检查与资料整理</h2><p>依据实际上传版本生成检查报告，AI 可协助解释和列出后续工作。</p></div><button class="btn btn--ghost" :disabled="loading" @click="checks">{{ loading ? '检查中…' : '重新检查' }}</button></header>
    <p v-if="error" class="team-error" role="alert">{{ error }}</p>
    <p v-if="loading && !report" class="team-empty" role="status">正在读取真实文件与检查结果…</p>
    <template v-if="report">
      <p class="team-callout">{{ report.scope }}。腾讯文档必须先手动导入，才会包含在文件检查中。</p>
      <div class="team-metrics"><div><strong>{{ report.summary.files }}</strong><span>可见文件汇总</span></div><div><strong>{{ report.summary.approved }}</strong><span>审核通过</span></div><div><strong>{{ report.summary.changesRequested }}</strong><span>需修改</span></div><div><strong>{{ report.summary.missing }}</strong><span>缺少的材料类型</span></div></div>
      <p v-if="report.truncated || report.notice" class="team-note">{{ report.notice || '详细解析仅展示最新 100 份可见文件，汇总使用全部可见文件。' }}</p>
      <p v-if="report.missing.length" class="team-note">缺少材料：{{ report.missing.map(m => m.label).join('、') }}</p>
      <p v-if="!report.files.length" class="team-empty">还没有可检查的导入文件。</p>
      <details v-for="file in report.files" :key="file.versionId" class="team-check-detail"><summary>{{ file.filename }} <span class="team-tag">{{ reviewLabel[file.reviewStatus] }}</span><span class="team-muted">{{ bytes(file.sizeBytes) }} · {{ file.mediaType }}</span></summary><div class="team-form"><dl class="team-facts"><template v-for="(value, key) in file.facts" :key="key"><dt>{{ key }}</dt><dd>{{ fact(value) }}</dd></template></dl><p v-if="file.fields?.length" class="team-muted">实际解析字段：{{ file.fields.join('、') }}</p><ul v-if="file.checks.length" class="team-check-results"><li v-for="(check, index) in file.checks" :key="index">{{ checkText(check) }}</li></ul><code class="team-hash">SHA-256 {{ file.sha256 }}</code></div></details>
      <p class="team-footnote">报告生成于 {{ dateTime(report.generatedAt) }}。文件事实来自服务器检测；AI 文字建议不会修改原件或自动通过审核。</p>
    </template>
  </section>
  <section class="team-card team-form">
    <header class="team-section-head"><div><h2>AI 项目助手</h2><p>根据当前可见文件元数据和检查结果，整理摘要、缺漏与待办建议。</p></div></header>
    <label class="team-consent"><input v-model="consent" type="checkbox" :disabled="thinking" /><span>我同意将当前可见文件的名称、大小、类型、实测指标和检查结果发送给服务器配置的模型服务。文件名可能含个人信息，请确认已获授权。本操作不发送文件正文、解析字段值和腾讯文档正文。</span></label>
    <div class="team-actions"><button class="btn" :disabled="!consent || thinking || loading" @click="ask">{{ thinking ? '正在整理…' : '生成整理建议' }}</button><span class="team-muted">使用已配置模型，可能产生模型调用费用。</span></div>
    <article v-if="assistant" class="team-inset"><p class="team-muted">{{ assistant.model }} · {{ dateTime(assistant.generatedAt) }}</p><p class="team-ai-content">{{ assistant.content }}</p></article>
  </section>
</template>
