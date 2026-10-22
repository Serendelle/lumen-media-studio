<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { collaborationApi as api, dateTime, downloadBlob, isAccessError, safeTencentUrl, type FileVersion, type ProjectMember, type SharedDocument } from '@/api/collaboration'
import { bytes } from '@/utils/format'

const props = defineProps<{ projectId: string; canEdit: boolean; owner: boolean; members: ProjectMember[]; revision: number }>()
const emit = defineEmits<{ changed: []; denied: [] }>()
const documents = ref<SharedDocument[]>([])
const page = ref(0), total = ref(0), loading = ref(true), busy = ref(false), error = ref('')
const editing = ref(false), editingId = ref<string>(), editingVersion = ref<number>()
const title = ref(''), externalUrl = ref(''), documentType = ref<SharedDocument['documentType']>('DOCUMENT')
const visibility = ref<'ALL' | 'SELECTED'>('ALL'), visibleUserIds = ref<number[]>([])
const versionDocument = ref<SharedDocument>(), versions = ref<FileVersion[]>([]), versionsLoading = ref(false)
const importFile = ref<File>(), uploadPercent = ref<number | null>(null)
const safeLink = computed(() => safeTencentUrl(externalUrl.value))
const importAccept = computed(() => versionDocument.value?.documentType === 'DOCUMENT' ? '.docx,.pdf' : versionDocument.value?.documentType === 'SPREADSHEET' ? '.xlsx,.pdf' : versionDocument.value?.documentType === 'SLIDE' ? '.pdf' : '.docx,.xlsx,.pdf')
let request = 0, versionRequest = 0
async function load() {
  const seq = ++request; loading.value = true; error.value = ''
  try {
    const result = await api.documents(props.projectId, page.value)
    if (seq === request) {
      documents.value = result.items; total.value = result.total
      if (versionDocument.value && !result.items.some(d => d.id === versionDocument.value?.id)) { versionDocument.value = undefined; versions.value = []; versionRequest++ }
      if (editingId.value && !result.items.some(d => d.id === editingId.value)) editing.value = false
    }
  }
  catch (e) { if (seq === request) { error.value = (e as Error).message; if (isAccessError(e)) { documents.value = []; emit('denied') } } }
  finally { if (seq === request) loading.value = false }
}
watch(() => [props.projectId, props.revision, page.value], () => { void load() }, { immediate: true })
watch(() => props.canEdit, value => { if (!value) editing.value = false })
function begin(document?: SharedDocument) {
  editingId.value = document?.id; editingVersion.value = document?.version
  title.value = document?.title ?? ''; externalUrl.value = document?.externalUrl ?? ''
  documentType.value = document?.documentType ?? 'DOCUMENT'; visibility.value = document?.visibility ?? 'ALL'
  visibleUserIds.value = [...document?.visibleUserIds ?? []]; editing.value = true; error.value = ''
}
async function save() {
  if (!safeLink.value) { error.value = '请填写 HTTPS 的腾讯文档官方链接。'; return }
  busy.value = true; error.value = ''
  try {
    await api.saveDocument(props.projectId, { title: title.value.trim(), externalUrl: safeLink.value, documentType: documentType.value, visibility: visibility.value, visibleUserIds: visibility.value === 'SELECTED' ? visibleUserIds.value : [], version: editingVersion.value }, editingId.value)
    editing.value = false; await load(); emit('changed')
  } catch (e) { error.value = (e as Error).message; if (isAccessError(e)) emit('denied') }
  finally { busy.value = false }
}
async function unlink(document: SharedDocument) {
  if (!window.confirm(`解除“${document.title}”的关联？腾讯文档本身仍由其平台管理。`)) return
  busy.value = true; error.value = ''
  try { await api.unlink(props.projectId, document.id); if (versionDocument.value?.id === document.id) versionDocument.value = undefined; await load(); emit('changed') }
  catch (e) { error.value = (e as Error).message; if (isAccessError(e)) emit('denied') }
  finally { busy.value = false }
}
async function showVersions(document: SharedDocument) {
  const seq = ++versionRequest; versionDocument.value = document; versions.value = []; versionsLoading.value = true; importFile.value = undefined; error.value = ''
  try { const result = await api.documentVersions(props.projectId, document.id); if (seq === versionRequest) versions.value = result }
  catch (e) { if (seq === versionRequest) { error.value = (e as Error).message; if (isAccessError(e)) { versionDocument.value = undefined; emit('denied') } } }
  finally { if (seq === versionRequest) versionsLoading.value = false }
}
async function importVersion() {
  if (!importFile.value || !versionDocument.value) return
  const document = versionDocument.value; busy.value = true; uploadPercent.value = 0; error.value = ''
  try { await api.importDocument(props.projectId, document.id, importFile.value, p => uploadPercent.value = p); await showVersions(document); emit('changed') }
  catch (e) { error.value = (e as Error).message; if (isAccessError(e)) emit('denied') }
  finally { busy.value = false; uploadPercent.value = null }
}
async function download(version: FileVersion) {
  busy.value = true; error.value = ''
  try { downloadBlob(await api.content(props.projectId, version.id), version.filename) }
  catch (e) { error.value = (e as Error).message; if (isAccessError(e)) emit('denied') }
  finally { busy.value = false }
}
function selectFile(event: Event) { importFile.value = (event.target as HTMLInputElement).files?.[0] }
</script>

<template>
  <section class="team-card">
    <header class="team-section-head"><div><h2>腾讯文档</h2><p>在腾讯文档中实时编辑，在 Lumen 关联项目并管理导入版本。</p></div><button v-if="canEdit" class="btn" :disabled="busy" @click="begin()">关联文档</button></header>
    <div class="team-callout">文档将在腾讯官方页面打开，需要按腾讯文档的账号与权限访问。Lumen 只管理链接记录；不会自动读取正文或同步外部修改。完成编辑后，请在腾讯文档导出文件，再导入项目。</div>
    <p v-if="error" class="team-error" role="alert">{{ error }}</p>
    <form v-if="editing && canEdit" class="team-inset team-form" @submit.prevent="save">
      <h3>{{ editingId ? '修改关联' : '关联腾讯文档' }}</h3>
      <div class="team-form-grid"><label>标题<input v-model="title" class="input" maxlength="120" required :disabled="busy" /></label><label>文档类型<select v-model="documentType" class="select" :disabled="busy"><option value="DOCUMENT">文档</option><option value="SPREADSHEET">表格</option><option value="SLIDE">演示文稿</option><option value="OTHER">其他</option></select></label></div>
      <label>腾讯文档链接<input v-model="externalUrl" class="input" type="url" required maxlength="2048" placeholder="https://docs.qq.com/…" :disabled="busy" /></label>
      <p v-if="externalUrl && !safeLink" class="team-error">仅支持 docs.qq.com 的 HTTPS 官方链接。</p>
      <label>谁可以看见这条关联<select v-model="visibility" class="select" :disabled="busy || !owner"><option value="ALL">所有项目成员</option><option value="SELECTED">指定成员及负责人</option></select></label>
      <fieldset v-if="visibility === 'SELECTED'" class="team-check-list" :disabled="busy || !owner"><legend>指定成员</legend><label v-for="member in members.filter(m => m.role !== 'OWNER')" :key="member.userId"><input v-model="visibleUserIds" type="checkbox" :value="member.userId" /> {{ member.displayName || member.username }}</label><p v-if="members.length < 2" class="team-muted">尚无其他成员，可先邀请成员。</p></fieldset>
      <p v-if="!owner" class="team-footnote">文档可见范围由负责人设置。</p>
      <p class="team-footnote">此范围控制 Lumen 中的关联和导入版本；腾讯文档页面的分享权限需在腾讯平台另行设置。</p>
      <div class="team-actions"><button class="btn" :disabled="busy || !safeLink || !title.trim()">{{ busy ? '保存中…' : '保存关联' }}</button><button type="button" class="btn btn--ghost" :disabled="busy" @click="editing = false">取消</button></div>
    </form>
    <p v-if="loading && !documents.length" class="team-empty" role="status">正在加载文档…</p>
    <p v-else-if="!documents.length && !error" class="team-empty">还没有可见的腾讯文档。{{ canEdit ? '可以关联已有文档或表格。' : '负责人关联文档后会显示在这里。' }}</p>
    <div v-else class="team-document-grid"><article v-for="document in documents" :key="document.id" class="team-document">
      <div class="team-inline"><span class="team-tag">{{ document.documentType === 'SPREADSHEET' ? '表格' : document.documentType === 'SLIDE' ? '演示' : '文档' }}</span><span class="team-muted">{{ document.visibility === 'SELECTED' ? '指定成员可见' : '项目成员可见' }}</span></div>
      <h3>{{ document.title }}</h3><p class="team-muted">更新于 {{ dateTime(document.updatedAt) }}</p>
      <div class="team-actions"><a v-if="safeTencentUrl(document.externalUrl)" :href="safeTencentUrl(document.externalUrl)!" target="_blank" rel="noopener noreferrer" class="btn">打开腾讯文档 ↗</a><span v-else class="team-error">链接不可用</span><button class="btn btn--ghost" @click="showVersions(document)">导入与版本</button></div>
      <div v-if="canEdit" class="team-actions"><button class="team-text-button" :disabled="busy" @click="begin(document)">编辑关联</button><button class="team-text-button danger" :disabled="busy" @click="unlink(document)">解除关联</button></div>
    </article></div>
    <nav v-if="total > 20" class="team-pagination" aria-label="文档分页"><button class="btn btn--ghost" :disabled="page === 0 || loading" @click="page--">上一页</button><span>第 {{ page + 1 }} 页 · {{ total }} 条</span><button class="btn btn--ghost" :disabled="(page + 1) * 20 >= total || loading" @click="page++">下一页</button></nav>
    <section v-if="versionDocument" class="team-inset team-form">
      <header class="team-section-head"><div><h3>{{ versionDocument.title }} · 已导入版本</h3><p>每次导入生成新版本，原文件保留。导入内容才会用于 Lumen 的检查与交付。</p></div><button class="team-text-button" :disabled="busy" @click="versionDocument = undefined; versionRequest++">关闭</button></header>
      <form v-if="canEdit" class="team-inline team-form" @submit.prevent="importVersion"><label class="team-grow">选择从腾讯文档导出的文件<input type="file" :accept="importAccept" aria-label="导入腾讯文档版本" :disabled="busy" @change="selectFile" /></label><button class="btn" :disabled="busy || !importFile">{{ busy ? '导入中…' : '导入新版本' }}</button></form>
      <p v-if="canEdit" class="team-footnote">{{ versionDocument.documentType === 'SLIDE' ? '演示文稿请导出为 PDF 后导入。' : versionDocument.documentType === 'SPREADSHEET' ? '表格支持真实 XLSX 或 PDF 导出文件。' : '支持 DOCX、XLSX 或 PDF，具体格式需与关联文档类型一致。' }}单次导入不超过 200 MB。</p>
      <div v-if="uploadPercent !== null" role="status"><progress :value="uploadPercent" max="100" /> {{ uploadPercent }}%<span v-if="uploadPercent === 100"> · 服务器正在校验文件</span></div>
      <p v-if="versionsLoading" class="team-muted" role="status">正在读取版本…</p><p v-else-if="!versions.length" class="team-muted">尚未导入文件。腾讯文档的在线内容尚未进入 Lumen。</p>
      <ul v-else class="team-version-list"><li v-for="version in versions" :key="version.id"><div><strong>v{{ version.versionNumber }} · {{ version.filename }}</strong><p class="team-muted">{{ bytes(version.sizeBytes) }} · {{ version.uploaderName || version.uploadedBy }} · {{ dateTime(version.createdAt) }}</p><code class="team-hash" :title="version.sha256">SHA-256 {{ version.sha256 }}</code></div><button class="btn btn--ghost" :disabled="busy" @click="download(version)">下载</button></li></ul>
    </section>
  </section>
</template>
