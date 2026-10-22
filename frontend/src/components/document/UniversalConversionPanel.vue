<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import MediaPicker from '@/components/studio/MediaPicker.vue'
import TaskProgress from '@/components/common/TaskProgress.vue'
import DocumentPreview from '@/components/document/DocumentPreview.vue'
import VerificationCard from '@/components/media/VerificationCard.vue'
import { documentsApi, filesApi } from '@/api/endpoints'
import type { ConversionMatrix, ConversionMode, DocumentFormat, DocumentInspection, FileView, TaskView, Verification } from '@/api/types'
import { useTaskSubmit } from '@/composables/useTaskSubmit'
import { useLibraryStore } from '@/stores/library'
import { isTerminal, useTasksStore } from '@/stores/tasks'
import { downloadFile, previewUrl } from '@/utils/download'
import { bytes } from '@/utils/format'
import { ERROR_HINT } from '@/utils/labels'
import { availableModes, conversionRoutes, DOCUMENT_FORMATS, formatLabel, routeSupports } from './conversionOptions'

withDefaults(defineProps<{ title?: string }>(), { title: 'PDF 与文档转换' })
const lib = useLibraryStore()
const tasks = useTasksStore()
const { busy, error, fields, run } = useTaskSubmit()
const matrix = ref<ConversionMatrix | null>(null)
const matrixError = ref('')
const matrixLoading = ref(false)
const selected = ref<string[]>([])
const selectedFiles = ref<FileView[]>([])
const pickerKey = ref(0)
const info = ref<DocumentInspection | null>(null)
const infoError = ref('')
const inspecting = ref(false)
const target = ref<DocumentFormat>('pdf')
const mode = ref<ConversionMode>('layout')
const opts = reactive({ density: 150, pageSize: 'A4', orientation: 'PORTRAIT', marginMm: 10, sheetNames: [] as string[] })
const submittedTaskId = ref('')
const actionError = ref('')
let inspectRequest = 0
let previewRequest = 0

async function loadMatrix() {
  matrixLoading.value = true
  matrixError.value = ''
  try { matrix.value = await documentsApi.matrix() }
  catch (e) { matrixError.value = (e as Error).message }
  finally { matrixLoading.value = false }
}
onMounted(loadMatrix)
onUnmounted(() => { inspectRequest++; previewRequest++ })

function fileFormat(file: FileView): DocumentFormat | undefined {
  if (file.mediaType === 'PDF') return 'pdf'
  if (file.mimeType === 'image/png') return 'png'
  if (file.mimeType === 'image/jpeg') return 'jpg'
  if (info.value?.fileId === file.fileId) return info.value.format
  const detected = (file.info as { format?: string } | undefined)?.format
  return DOCUMENT_FORMATS.find(f => f.value === detected)?.value
}
const sourceFormats = computed(() => selectedFiles.value.map(fileFormat))
const knownSources = computed(() => sourceFormats.value.filter((f): f is DocumentFormat => !!f))
const allImages = computed(() => sourceFormats.value.length > 0 && sourceFormats.value.every(f => f === 'png' || f === 'jpg'))
const selectionProblem = computed(() => {
  if (!selected.value.length || inspecting.value) return ''
  if (selectedFiles.value.length !== selected.value.length) return '部分文件无法读取，请重新选择。'
  if (sourceFormats.value.some(f => !f)) return '所选文件的实际格式不受支持。请选择 DOCX、XLSX、HTML、OFD、PDF、PNG 或 JPG。'
  if (selected.value.length > 1 && !allImages.value) return '一次请选择一个文档；只有 PNG/JPG 图片可以多选并按顺序合并。'
  return ''
})
const currentRoutes = computed(() => conversionRoutes(matrix.value?.routes ?? [], knownSources.value, target.value))
const modes = computed(() => availableModes(currentRoutes.value))
const supported = computed(() => !selectionProblem.value && routeSupports(matrix.value?.routes ?? [], knownSources.value, target.value))
const routeReason = computed(() => {
  if (!selected.value.length) return '选择源文件后，查看当前服务器支持的转换方式。'
  if (selectionProblem.value) return selectionProblem.value
  if (knownSources.value.includes(target.value)) return '源文件与目标格式相同，请选择其他目标格式。'
  if (currentRoutes.value.length !== new Set(knownSources.value).size) return '当前服务器没有提供这条转换路线。'
  return currentRoutes.value.filter(r => !r.available).map(r => r.reason || `服务器尚未准备好 ${r.engine}`).join('；')
})
const usesPages = computed(() => ['pdf', 'ofd', 'png', 'jpg'].includes(target.value) || mode.value === 'layout')
const needsDensity = computed(() => !allImages.value && (['png', 'jpg', 'ofd'].includes(target.value)
  || (mode.value === 'layout' && ['docx', 'xlsx', 'html'].includes(target.value))))
const visibleSheets = computed(() => (info.value?.sheets ?? []).filter(s => !s.hidden))
const allSheetsChosen = computed(() => visibleSheets.value.every(s => opts.sheetNames.includes(s.name)))
const pendingUploads = computed(() => lib.uploads.filter(u => !u.error))
const canSubmit = computed(() => selected.value.length > 0 && !inspecting.value && !infoError.value && !busy.value
  && supported.value && modes.value.includes(mode.value) && pendingUploads.value.length === 0
  && (info.value?.format !== 'xlsx' || !usesPages.value || opts.sheetNames.length > 0)
  && (info.value?.format !== 'html' || !usesPages.value || (Number.isFinite(opts.marginMm) && opts.marginMm >= 0 && opts.marginMm <= 30)))

function chooseDefaultTarget() {
  if (!knownSources.value.length) return
  const routes = matrix.value?.routes ?? []
  if (routeSupports(routes, knownSources.value, target.value)) return
  const preferred: DocumentFormat[] = knownSources.value.includes('pdf') ? ['docx', 'png', 'html', 'xlsx', 'ofd', 'jpg'] : ['pdf', 'docx', 'xlsx', 'html', 'ofd', 'png', 'jpg']
  const next = preferred.find(format => routeSupports(routes, knownSources.value, format))
  if (next) target.value = next
}
watch(matrix, chooseDefaultTarget)
watch(currentRoutes, routes => {
  const nextModes = availableModes(routes)
  const serverDefault = routes.map(r => r.defaultMode).find((value): value is ConversionMode => !!value && nextModes.includes(value))
  mode.value = serverDefault ?? nextModes[0] ?? 'layout'
})

watch(selected, async ids => {
  const request = ++inspectRequest
  selectedFiles.value = []
  info.value = null
  infoError.value = ''
  submittedTaskId.value = ''
  actionError.value = ''
  inspecting.value = ids.length > 0
  if (!ids.length) return
  try {
    const files = await Promise.all(ids.map(id => lib.files.find(f => f.fileId === id) ?? filesApi.get(id)))
    if (request !== inspectRequest) return
    selectedFiles.value = files
    if (files.length === 1 && files[0].mediaType !== 'IMAGE') {
      const inspection = await documentsApi.inspect(files[0].fileId)
      if (request !== inspectRequest) return
      info.value = inspection
      opts.sheetNames = (inspection.sheets ?? []).filter(s => !s.hidden).map(s => s.name)
    }
    chooseDefaultTarget()
  } catch (e) {
    if (request === inspectRequest) infoError.value = (e as Error).message
  } finally {
    if (request === inspectRequest) inspecting.value = false
  }
})

function move(index: number, step: number) {
  const ids = [...selected.value]
  const other = index + step
  if (other < 0 || other >= ids.length) return
  ;[ids[index], ids[other]] = [ids[other], ids[index]]
  selected.value = ids
}
async function submit() {
  if (!canSubmit.value) return
  const ids = [...selected.value]
  const format = target.value
  const options: Record<string, unknown> = { mode: mode.value }
  if (needsDensity.value) options.density = opts.density
  if (info.value?.format === 'html' && usesPages.value) Object.assign(options, { pageSize: opts.pageSize, orientation: opts.orientation, marginMm: opts.marginMm })
  if (info.value?.format === 'xlsx' && usesPages.value && !allSheetsChosen.value) options.sheetNames = [...opts.sheetNames]
  const taskId = await run(() => documentsApi.convertTo(ids, format, options), '转换任务已提交')
  if (taskId && ids.join('|') === selected.value.join('|')) submittedTaskId.value = taskId
}

const latest = computed<TaskView | undefined>(() => submittedTaskId.value ? tasks.byId[submittedTaskId.value]
  : selected.value.length ? tasks.list.find(t => t.type === 'document.convert' && t.inputs?.length === selected.value.length && t.inputs.every((input, i) => input.fileId === selected.value[i])) : undefined)
const latestVerification = computed(() => latest.value?.result?.verification as Verification | undefined)
const latestWarnings = computed<string[]>(() => latest.value?.result?.warnings ?? [])
const resultImage = ref('')
watch(() => latest.value?.output?.fileId, async id => {
  const request = ++previewRequest
  resultImage.value = ''
  if (!id || latest.value?.output?.mediaType !== 'IMAGE') return
  try {
    const url = await previewUrl(id)
    if (request === previewRequest) resultImage.value = url
  } catch { /* Download remains available if an inline ticket cannot be issued. */ }
})
async function cancelTask() {
  if (!latest.value) return
  actionError.value = ''
  try { await tasks.cancel(latest.value.taskId) }
  catch (e) { actionError.value = (e as Error).message }
}
async function download() {
  const output = latest.value?.output
  if (!output) return
  actionError.value = ''
  try { await downloadFile(output.fileId, output.filename) }
  catch (e) { actionError.value = (e as Error).message }
}
function useResult() {
  if (!latest.value?.output || latest.value.output.mediaType === 'ARCHIVE') return
  selected.value = [latest.value.output.fileId]
  pickerKey.value++
}
const displayedRoutes = computed(() => (matrix.value?.routes ?? []).filter(route => DOCUMENT_FORMATS.some(f => f.value === route.from) && DOCUMENT_FORMATS.some(f => f.value === route.to)))
</script>

<template>
  <div class="conversion">
    <header class="head">
      <p class="eyebrow">DOCUMENT CONVERSION</p>
      <h1>{{ title }}</h1>
      <p>Word、Excel、HTML、OFD、PDF 与 PNG/JPG 图片互转。转换在服务器本地完成，结果单独保存，原文件保留。</p>
    </header>

    <div class="conversion-grid">
      <section class="card pad source" aria-label="源文件">
        <h2>1. 选择源文件</h2>
        <MediaPicker :key="pickerKey" v-model="selected" :kinds="['DOCUMENT', 'PDF', 'IMAGE']" multiple :max="100" with-outputs
                     accept=".docx,.xlsx,.html,.htm,.ofd,.pdf,.png,.jpg,.jpeg" label="一个文档，或多张 PNG/JPG 图片" />
        <p class="note">Word 使用 .docx，Excel 使用 .xlsx；暂不支持旧版 .doc / .xls 或启用宏的文档。多张图片按下方顺序转换。</p>
        <p v-if="matrix?.maxTotalBytes" class="note">单次输入总大小 ≤ {{ bytes(matrix.maxTotalBytes) }}<template v-if="matrix.maxRenderPages">，最多渲染 {{ matrix.maxRenderPages }} 页</template>。</p>
        <button v-if="selected.length" type="button" class="btn btn--sm btn--ghost start" @click="selected = []">清除选择</button>
        <div v-if="pendingUploads.length" class="upload-actions">
          <button v-for="upload in pendingUploads" :key="upload.id" type="button" class="btn btn--sm btn--ghost" @click="lib.dismissUpload(upload.id)">取消上传 {{ upload.name }}</button>
        </div>
        <p v-if="inspecting" class="note" role="status">正在读取文件并检查文档结构…</p>
        <p v-if="infoError || selectionProblem" class="err" role="alert">{{ infoError || selectionProblem }}</p>
        <ol v-if="allImages && selectedFiles.length > 1" class="order" aria-label="图片页面顺序">
          <li v-for="(file, index) in selectedFiles" :key="file.fileId">
            <span class="mono">{{ index + 1 }}</span><span class="filename">{{ file.originalFilename }}</span>
            <button type="button" class="btn btn--sm btn--ghost" :disabled="index === 0" :aria-label="`上移 ${file.originalFilename}`" @click="move(index, -1)">↑</button>
            <button type="button" class="btn btn--sm btn--ghost" :disabled="index === selectedFiles.length - 1" :aria-label="`下移 ${file.originalFilename}`" @click="move(index, 1)">↓</button>
          </li>
        </ol>
        <template v-if="info">
          <h3 class="filename">{{ info.filename }}</h3>
          <dl class="facts">
            <div><dt>实际格式</dt><dd>{{ formatLabel(info.format) }}</dd></div>
            <div><dt>大小</dt><dd>{{ bytes(info.sizeBytes) }}</dd></div>
            <div v-if="info.pageCount != null"><dt>页数</dt><dd>{{ info.pageCount }}</dd></div>
            <div v-if="info.pageSize"><dt>页面</dt><dd>{{ info.pageSize }}</dd></div>
            <div v-if="info.paragraphs != null"><dt>段落</dt><dd>{{ info.paragraphs }}</dd></div>
            <div v-if="info.tables"><dt>表格</dt><dd>{{ info.tables.length }} 个</dd></div>
            <div v-if="info.sheets"><dt>工作表</dt><dd>{{ info.sheets.length }} 个</dd></div>
            <div v-if="info.title"><dt>标题</dt><dd>{{ info.title }}</dd></div>
            <div v-if="info.fonts?.length"><dt>字体</dt><dd>{{ info.fonts.join('、') }}</dd></div>
          </dl>
          <ul v-if="info.warnings.length" class="warns"><li v-for="warning in info.warnings" :key="warning">{{ warning }}</li></ul>
        </template>
      </section>

      <section class="card pad settings" aria-label="转换设置">
        <h2>2. 选择目标格式</h2>
        <p v-if="matrixLoading" class="note" role="status">正在检查服务器转换能力…</p>
        <div v-if="matrixError" class="error-box" role="alert">
          <p>{{ matrixError }}</p><button type="button" class="btn btn--sm" @click="loadMatrix">重新检查</button>
        </div>
        <fieldset class="targets">
          <legend class="sr-only">目标格式</legend>
          <label v-for="format in DOCUMENT_FORMATS" :key="format.value" class="target" :class="{ chosen: target === format.value }">
            <input v-model="target" type="radio" name="conversion-target" :value="format.value" />
            <strong>{{ format.short }}</strong><span>{{ format.value.toUpperCase() }}</span>
            <small v-if="knownSources.length">{{ routeSupports(matrix?.routes ?? [], knownSources, format.value) ? '可转换' : '不可用' }}</small>
          </label>
        </fieldset>
        <p v-if="!supported" class="note" :class="{ err: selected.length && !inspecting }">{{ routeReason }}</p>
        <template v-if="supported">
          <p v-for="route in currentRoutes" :key="route.from + route.to" class="note">{{ route.description || `${formatLabel(route.from)} → ${formatLabel(route.to)}` }}</p>
          <fieldset v-if="modes.length > 1" class="modes">
            <legend>转换方式</legend>
            <label v-for="choice in modes" :key="choice" class="check"><input v-model="mode" type="radio" name="conversion-mode" :value="choice" />{{ choice === 'layout' ? '保留页面外观' : '提取可编辑内容' }}</label>
          </fieldset>
          <p v-if="mode === 'layout' && ['docx', 'xlsx'].includes(target)" class="notice">按页面嵌入图像，尽量保留原始外观；生成的 Word / Excel 中，页面文字不能直接编辑。</p>
          <p v-else-if="mode === 'editable'" class="notice">提取实际存在的文字、段落与表格，复杂排版可能变化。扫描件或纯图片没有文字层时，不能直接提取为可编辑文字；本功能不包含 OCR。</p>
          <p v-if="target === 'html' && mode === 'layout'" class="note">导出包含页面图像的独立 HTML 文件。</p>
          <p v-if="allImages && ['png', 'jpg'].includes(target)" class="note">图片互转保留原始像素尺寸，不应用文档渲染 DPI。</p>
          <p v-else-if="allImages" class="note">按原图长宽比保留页面外观；文档页面中的大图会自动缩小至安全尺寸。渲染 DPI 不用于图片源文件。</p>
          <p v-if="['png', 'jpg'].includes(target)" class="note">文档的各页图片会连同清单打包为 ZIP；单张图片互转直接输出图片，多张图片输出 ZIP。</p>
          <p v-if="target === 'pdf' && allImages && selected.length > 1" class="note">按选择顺序把全部图片合并为一个多页 PDF。</p>
          <p v-if="knownSources.includes('ofd') || target === 'ofd'" class="notice">转换不会保留原 OFD 数字签名的有效性；请保留原件用于签名验证。</p>
          <div v-if="needsDensity" class="field">
            <label for="conversion-density">渲染精度（DPI）</label>
            <select id="conversion-density" v-model.number="opts.density" class="select"><option :value="96">96 · 屏幕浏览</option><option :value="150">150 · 推荐</option><option :value="300">300 · 打印</option></select>
          </div>
          <template v-if="info?.format === 'html' && usesPages">
            <div class="options-grid">
              <div class="field"><label for="conversion-paper">纸张</label><select id="conversion-paper" v-model="opts.pageSize" class="select"><option value="A4">A4</option><option value="ORIGINAL">沿用网页设置</option></select></div>
              <div class="field"><label for="conversion-direction">方向</label><select id="conversion-direction" v-model="opts.orientation" class="select" :disabled="opts.pageSize !== 'A4'"><option value="PORTRAIT">纵向</option><option value="LANDSCAPE">横向</option></select></div>
              <div class="field"><label for="conversion-margin">页边距（mm）</label><input id="conversion-margin" v-model.number="opts.marginMm" type="number" min="0" max="30" class="input" /><span v-if="fields.marginMm" class="err">{{ fields.marginMm }}</span></div>
            </div>
            <p class="note">网页转换会移除脚本、内嵌框架与外部资源，沿用静态内容。</p>
          </template>
          <fieldset v-if="info?.format === 'xlsx' && usesPages" class="sheets">
            <legend>输出工作表</legend>
            <label v-for="sheet in visibleSheets" :key="sheet.name" class="check"><input v-model="opts.sheetNames" type="checkbox" :value="sheet.name" />{{ sheet.name }} <span class="note">{{ sheet.usedRows }} 行 × {{ sheet.usedCols }} 列</span></label>
            <p v-if="!opts.sheetNames.length" class="err">至少选择一个工作表。</p>
            <p v-if="fields.sheetNames" class="err">{{ fields.sheetNames }}</p>
            <p class="note">沿用工作簿页面设置，隐藏工作表不输出。</p>
          </fieldset>
        </template>
        <p v-if="error" class="err" role="alert">{{ error }}</p>
        <button type="button" class="btn start" :disabled="!canSubmit" @click="submit">{{ busy ? '提交中…' : `开始转换为 ${target.toUpperCase()}` }}</button>
      </section>
    </div>

    <section v-if="latest" class="card pad result" aria-label="转换结果" aria-live="polite">
      <h2>转换结果</h2>
      <template v-if="!isTerminal(latest)"><TaskProgress :task="latest" /><button type="button" class="btn btn--sm btn--ghost" @click="cancelTask">取消转换</button></template>
      <template v-else-if="latest.status === 'SUCCEEDED' && latest.output">
        <div class="result-meta"><strong class="filename">{{ latest.output.filename }}</strong><span>{{ bytes(latest.output.sizeBytes) }}</span><button type="button" class="btn btn--sm" @click="download">下载 {{ latest.output.filename.split('.').pop()?.toUpperCase() }}</button><button v-if="latest.output.mediaType !== 'ARCHIVE'" type="button" class="btn btn--sm btn--ghost" @click="useResult">用结果继续转换</button></div>
        <VerificationCard v-if="latestVerification" :verification="latestVerification" compact />
        <ul v-if="latestWarnings.length" class="warns"><li v-for="warning in latestWarnings" :key="warning">{{ warning }}</li></ul>
        <DocumentPreview v-if="latest.output.mediaType === 'PDF'" :file-id="latest.output.fileId" />
        <img v-else-if="resultImage" :src="resultImage" class="result-image" alt="转换后的图片预览" />
        <p v-else class="note">{{ latest.output.mediaType === 'ARCHIVE' ? '下载 ZIP 后可查看所有页面图片与清单。' : '请下载文件并使用对应应用打开。' }}</p>
      </template>
      <div v-else class="error-box"><p>{{ latest.status === 'CANCELLED' ? '转换已取消' : '转换失败' }}：{{ latest.message }}</p><p v-if="latest.errorCode && ERROR_HINT[latest.errorCode]">{{ ERROR_HINT[latest.errorCode] }}</p><p>原文件仍然保留，可调整选项后重新转换。</p></div>
      <p v-if="actionError" class="err" role="alert">{{ actionError }}</p>
    </section>

    <details v-if="info?.format === 'pdf'" class="card pad"><summary>预览原始 PDF</summary><DocumentPreview :file-id="info.fileId" /></details>
    <details v-if="matrix" class="card pad matrix"><summary>查看服务器支持的全部转换路线</summary><ul><li v-for="route in displayedRoutes" :key="route.from + route.to"><span>{{ route.from.toUpperCase() }} → {{ route.to.toUpperCase() }}</span><span :class="route.available ? 'available' : 'err'">{{ route.available ? '可用' : (route.reason || '暂不可用') }}</span></li></ul><p v-if="matrix.notSupported.length" class="note">{{ matrix.notSupported.join('；') }}</p></details>
  </div>
</template>

<style scoped>
.conversion { display: grid; gap: var(--sp-4); min-width: 0; }
.head { display: grid; gap: 6px; }
.head h1 { font-size: var(--fs-xl); }
.head > p:last-child { color: var(--text-2); font-size: var(--fs-sm); line-height: 1.7; }
.eyebrow { color: var(--text-3); font: 11px var(--font-mono); letter-spacing: .12em; }
.conversion-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); align-items: start; gap: var(--sp-4); }
.pad { padding: var(--sp-5); display: grid; align-content: start; gap: var(--sp-3); min-width: 0; }
h2 { font-size: var(--fs-lg); } h3 { font-size: var(--fs-sm); }
.note { font-size: var(--fs-xs); line-height: 1.7; color: var(--text-3); }
.err { font-size: var(--fs-sm); color: var(--danger); overflow-wrap: anywhere; }
.notice, .warns { background: var(--apricot); color: var(--apricot-ink); border-radius: var(--r-sm); padding: 10px 12px; font-size: var(--fs-xs); line-height: 1.7; }
.warns { margin: 0; padding-left: 28px; }
.targets { margin: 0; padding: 0; border: 0; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.target { position: relative; display: grid; gap: 4px; padding: 12px; border: 1px solid var(--line); border-radius: var(--r-sm); cursor: pointer; }
.target.chosen { border-color: var(--mint-ink); background: var(--mint); }
.target input { position: absolute; top: 12px; right: 10px; accent-color: var(--mint-ink); }
.target strong { padding-right: 16px; font-size: var(--fs-sm); }
.target span, .target small { color: var(--text-3); font-size: 10px; }
.modes, .sheets { border: 0; margin: 0; padding: 0; }
.modes legend, .sheets legend { margin-bottom: 6px; font-size: var(--fs-sm); }
.check { display: flex; align-items: center; gap: 8px; min-height: 32px; font-size: var(--fs-sm); flex-wrap: wrap; }
.options-grid, .facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 10px; }
.facts { margin: 0; } .facts div { min-width: 0; }
.facts dt { color: var(--text-3); font-size: var(--fs-xs); } .facts dd { margin: 3px 0 0; font-size: var(--fs-sm); overflow-wrap: anywhere; }
.order { display: grid; gap: 5px; list-style: none; margin: 0; padding: 0; }
.order li { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto auto; gap: 6px; align-items: center; font-size: var(--fs-xs); }
.filename { overflow-wrap: anywhere; min-width: 0; }
.start { justify-self: start; margin-top: 4px; }
.result-meta, .upload-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: var(--fs-sm); }
.result-image { max-width: 100%; max-height: 600px; object-fit: contain; justify-self: start; border: 1px solid var(--line); border-radius: var(--r-sm); }
.error-box { display: grid; gap: 8px; padding: 12px; background: var(--danger-soft); border-radius: var(--r-sm); font-size: var(--fs-sm); line-height: 1.6; }
summary { cursor: pointer; font-size: var(--fs-sm); font-weight: 600; }
details[open] > summary { margin-bottom: var(--sp-3); }
.matrix ul { display: grid; gap: 7px; padding: 0; margin: 0; list-style: none; }
.matrix li { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 8px; font-size: var(--fs-xs); }
.available { color: var(--mint-ink); }
@media (max-width: 1050px) { .conversion-grid { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 520px) { .pad { padding: var(--sp-3); } .targets { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
