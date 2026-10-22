<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { collaborationApi as api, dateTime, downloadBlob, isAccessError, reviewLabel, type FileVersion, type SharedFile } from '@/api/collaboration'
import { bytes } from '@/utils/format'

const props = defineProps<{ projectId: string; canEdit: boolean; canReview: boolean; revision: number }>()
const emit = defineEmits<{ changed: []; denied: []; openTasks: [] }>()
const files = ref<SharedFile[]>([]), total = ref(0), page = ref(0), kind = ref(''), archived = ref(false)
const loading = ref(true), busy = ref(false), error = ref(''), uploadFile = ref<File>(), replacing = ref<FileVersion>()
const uploadPercent = ref<number | null>(null), versions = ref<FileVersion[]>([]), selected = ref<FileVersion>(), versionsLoading = ref(false)
const preview = ref<{ url: string; name: string; type: string; assetId: string }>(), reviewNote = ref(''), submittedTask = ref(false)
const processingKeys = new Map<string, string>()
let request = 0, versionRequest = 0
async function load() {
  const seq = ++request; loading.value = true; error.value = ''
  try { const result = await api.files(props.projectId, page.value, kind.value, archived.value); if (seq === request) { files.value = result.items; total.value = result.total } }
  catch (e) { if (seq === request) { error.value = (e as Error).message; if (isAccessError(e)) { files.value = []; closePreview(); emit('denied') } } }
  finally { if (seq === request) loading.value = false }
}
watch(() => [props.projectId, props.revision, page.value], () => { void load(); if (selected.value) void showVersions(selected.value, false) }, { immediate: true })
watch(() => props.revision, async () => {
  const current = preview.value
  if (!current) return
  try { await api.fileVersions(props.projectId, current.assetId) }
  catch (e) { if (isAccessError(e) && current === preview.value) { closePreview(); error.value = '此预览的访问权限已变化，请重新加载资料。' } }
})
watch(() => [kind.value, archived.value], () => { if (page.value !== 0) page.value = 0; else void load() })
watch(() => props.canEdit, value => { if (!value) { replacing.value = undefined; uploadFile.value = undefined } })
async function upload() {
  if (!uploadFile.value) return
  busy.value = true; uploadPercent.value = 0; error.value = ''
  try {
    const result = await api.upload(props.projectId, uploadFile.value, p => uploadPercent.value = p, replacing.value?.assetId)
    uploadFile.value = undefined; replacing.value = undefined; await load(); if (selected.value?.assetId === result.assetId) await showVersions(result)
    emit('changed')
  } catch (e) { error.value = (e as Error).message; if (isAccessError(e)) emit('denied') }
  finally { busy.value = false; uploadPercent.value = null }
}
async function showVersions(file: FileVersion, clear = true) {
  const seq = ++versionRequest; selected.value = file; if (clear) { versions.value = []; reviewNote.value = '' }; versionsLoading.value = true
  try { const result = await api.fileVersions(props.projectId, file.assetId); if (seq === versionRequest) versions.value = result }
  catch (e) { if (seq === versionRequest) { error.value = (e as Error).message; if (isAccessError(e)) { selected.value = undefined; emit('denied') } } }
  finally { if (seq === versionRequest) versionsLoading.value = false }
}
async function archive(file: FileVersion) {
  busy.value = true; error.value = ''
  try { await api.archive(props.projectId, file.assetId, !file.archived); await load(); emit('changed') }
  catch (e) { error.value = (e as Error).message; if (isAccessError(e)) emit('denied') }
  finally { busy.value = false }
}
async function content(file: FileVersion, inline = false) {
  busy.value = true; error.value = ''
  try {
    const blob = await api.content(props.projectId, file.id, inline)
    if (!inline) { downloadBlob(blob, file.filename); return }
    const type = blob.type || file.mimeType || ''
    if (!/^(image\/(?:png|jpeg|webp|gif|bmp)|video\/|audio\/|application\/pdf)/.test(type)) { downloadBlob(blob, file.filename); return }
    closePreview(); preview.value = { url: URL.createObjectURL(blob), name: file.filename, type, assetId: file.assetId }
  } catch (e) {
    error.value = (e as Error).message
    if (isAccessError(e)) emit('denied')
  } finally { busy.value = false }
}
async function review(file: FileVersion, status: 'APPROVED' | 'CHANGES_REQUESTED') {
  busy.value = true; error.value = ''
  try { await api.review(props.projectId, file.id, status, reviewNote.value.trim()); if (selected.value) await showVersions(selected.value, false); await load(); emit('changed') }
  catch (e) { error.value = (e as Error).message; if (isAccessError(e)) emit('denied') }
  finally { busy.value = false }
}
function closePreview() { if (preview.value) URL.revokeObjectURL(preview.value.url); preview.value = undefined }
function selectFile(event: Event) { uploadFile.value = (event.target as HTMLInputElement).files?.[0] }
async function extractAudio(file: FileVersion) {
  busy.value = true; error.value = ''; submittedTask.value = false
  const key = processingKeys.get(file.id) ?? crypto.randomUUID(); processingKeys.set(file.id, key)
  try { await api.process(props.projectId, file.id, 'audio.extract', { format: 'mp3' }, key); processingKeys.delete(file.id); submittedTask.value = true; emit('changed') }
  catch (e) { error.value = (e as Error).message; if (isAccessError(e)) emit('denied') }
  finally { busy.value = false }
}
onBeforeUnmount(() => { request++; versionRequest++; closePreview() })
</script>

<template>
  <section class="team-card">
    <header class="team-section-head"><div><h2>共享文件库</h2><p>上传真实文件，保留版本、校验摘要与审核记录。每次下载都校验当前项目权限。</p></div><span class="team-tag">{{ total }} 个{{ archived ? '已归档' : '当前' }}文件</span></header>
    <form v-if="canEdit && !archived" class="team-inset team-form" @submit.prevent="upload">
      <div class="team-inline"><label class="team-grow">{{ replacing ? `为“${replacing.filename}”上传新版本` : '上传到项目' }}<input type="file" aria-label="选择项目共享文件" :disabled="busy" @change="selectFile" /></label><button class="btn" :disabled="busy || !uploadFile">{{ busy ? '上传中…' : replacing ? '上传新版本' : '上传文件' }}</button><button v-if="replacing" type="button" class="btn btn--ghost" :disabled="busy" @click="replacing = undefined">取消更新版本</button></div>
      <p v-if="uploadFile" class="team-muted">{{ uploadFile.name }} · {{ bytes(uploadFile.size) }}</p>
      <p class="team-footnote">上传新版本会保留旧版本，不会覆盖原件。归档只整理列表，不删除文件，也不会释放项目存储配额。</p>
      <div v-if="uploadPercent !== null" role="status"><progress :value="uploadPercent" max="100" /> {{ uploadPercent }}%<span v-if="uploadPercent === 100"> · 正在解析并保存版本</span></div>
    </form>
    <div class="team-inline team-filters"><label>文件类型<select v-model="kind" class="select"><option value="">全部类型</option><option value="VIDEO">视频</option><option value="AUDIO">音频</option><option value="IMAGE">图片</option><option value="PDF">PDF</option><option value="DOCUMENT">文档 / 表格</option><option value="ARCHIVE">压缩包</option></select></label><label class="team-check"><input v-model="archived" type="checkbox" /> 查看已归档文件</label><button class="team-text-button" :disabled="loading" @click="load">刷新</button></div>
    <p v-if="error" class="team-error" role="alert">{{ error }}</p>
    <p v-if="submittedTask" class="team-callout" role="status">音轨提取任务已提交，将保留原视频并生成独立音频文件。<button class="team-text-button" @click="emit('openTasks')">查看处理任务 →</button></p>
    <p v-if="loading && !files.length" class="team-empty" role="status">正在加载文件…</p><p v-else-if="!files.length && !error" class="team-empty">{{ archived ? '没有符合筛选条件的归档文件。' : '还没有可见文件。可上传文件，或从腾讯文档导出后手动导入。' }}</p>
    <div v-else class="team-table-wrap"><table class="team-table"><thead><tr><th>文件与版本</th><th>大小 / 上传人</th><th>审核</th><th>操作</th></tr></thead><tbody><tr v-for="file in files" :key="file.id">
      <td><strong class="team-filename">{{ file.filename }}</strong><small class="team-muted">v{{ file.versionNumber }} · {{ file.mediaType }} · {{ dateTime(file.createdAt) }}<template v-if="file.documentId"> · 腾讯文档导入</template></small></td>
      <td>{{ bytes(file.sizeBytes) }}<small class="team-muted">{{ file.uploaderName || file.uploadedBy }}</small></td>
      <td><span class="team-tag" :class="{ success: file.reviewStatus === 'APPROVED', warning: file.reviewStatus === 'CHANGES_REQUESTED' }">{{ reviewLabel[file.reviewStatus] }}</span></td>
      <td><div class="team-actions"><button class="team-text-button" :disabled="busy" @click="content(file, true)">预览 / 打开</button><button class="team-text-button" :disabled="busy" @click="content(file)">下载</button><button class="team-text-button" @click="showVersions(file)">版本 / 审核</button><button v-if="canEdit && !archived" class="team-text-button" :disabled="busy" @click="replacing = file; uploadFile = undefined">上传新版本</button><button v-if="canEdit && !archived && file.mediaType === 'VIDEO'" class="team-text-button" :disabled="busy" @click="extractAudio(file)">提取 MP3 音轨</button><button v-if="canEdit" class="team-text-button" :disabled="busy" @click="archive(file)">{{ archived ? '恢复归档' : '归档' }}</button></div></td>
    </tr></tbody></table></div>
    <nav v-if="total > 20" class="team-pagination" aria-label="文件分页"><button class="btn btn--ghost" :disabled="page === 0 || loading" @click="page--">上一页</button><span>第 {{ page + 1 }} 页 · {{ total }} 个文件</span><button class="btn btn--ghost" :disabled="(page + 1) * 20 >= total || loading" @click="page++">下一页</button></nav>
    <section v-if="selected" class="team-inset team-form">
      <header class="team-section-head"><div><h3>{{ selected.filename }} · 版本与审核</h3><p>审核绑定具体版本；新增版本需重新审核。</p></div><button class="team-text-button" @click="selected = undefined; versionRequest++">关闭</button></header>
      <label v-if="canReview">审核意见<textarea v-model="reviewNote" class="input" rows="2" maxlength="1000" :disabled="busy" placeholder="填写通过理由或需要修改的地方" /></label>
      <p v-if="versionsLoading" class="team-muted" role="status">正在读取版本…</p>
      <ul class="team-version-list"><li v-for="version in versions" :key="version.id"><div class="team-grow"><strong>v{{ version.versionNumber }} · {{ version.filename }}</strong><p class="team-muted">{{ bytes(version.sizeBytes) }} · {{ version.uploaderName || version.uploadedBy }} · {{ dateTime(version.createdAt) }}</p><code class="team-hash" :title="version.sha256">SHA-256 {{ version.sha256 }}</code><p v-if="version.reviewNote" class="team-note">审核意见：{{ version.reviewNote }}</p></div><div class="team-actions"><span class="team-tag" :class="{ success: version.reviewStatus === 'APPROVED', warning: version.reviewStatus === 'CHANGES_REQUESTED' }">{{ reviewLabel[version.reviewStatus] }}</span><button class="btn btn--ghost" :disabled="busy" @click="content(version)">下载</button><template v-if="canReview"><button class="team-text-button" :disabled="busy" @click="review(version, 'APPROVED')">审核通过</button><button class="team-text-button danger" :disabled="busy" @click="review(version, 'CHANGES_REQUESTED')">退回修改</button></template></div></li></ul>
    </section>
    <section v-if="preview" class="team-preview" role="dialog" aria-modal="true" :aria-label="preview.name"><header><h3>{{ preview.name }}</h3><button class="btn btn--ghost" @click="closePreview">关闭预览</button></header><img v-if="preview.type.startsWith('image/')" :src="preview.url" :alt="preview.name" /><video v-else-if="preview.type.startsWith('video/')" :src="preview.url" controls /><audio v-else-if="preview.type.startsWith('audio/')" :src="preview.url" controls /><iframe v-else-if="preview.type === 'application/pdf'" :src="preview.url" :title="preview.name" /></section>
  </section>
</template>
