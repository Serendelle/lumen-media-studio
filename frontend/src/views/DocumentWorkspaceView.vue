<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import MediaPicker from '@/components/studio/MediaPicker.vue'
import TaskTray from '@/components/studio/TaskTray.vue'
import TaskProgress from '@/components/common/TaskProgress.vue'
import DocumentPreview from '@/components/document/DocumentPreview.vue'
import VerificationCard from '@/components/media/VerificationCard.vue'
import { documentsApi } from '@/api/endpoints'
import type { ConversionMatrix, DocumentInspection, FileView, TaskView, Verification } from '@/api/types'
import { useTaskSubmit } from '@/composables/useTaskSubmit'
import { useLibraryStore } from '@/stores/library'
import { isTerminal, useTasksStore } from '@/stores/tasks'
import { downloadFile } from '@/utils/download'
import { bytes } from '@/utils/format'
import { ERROR_HINT } from '@/utils/labels'

const lib = useLibraryStore()
const tasks = useTasksStore()
const { busy, error, fields, run } = useTaskSubmit()

const matrix = ref<ConversionMatrix | null>(null)
const matrixError = ref<string | null>(null)
onMounted(async () => {
  try {
    matrix.value = await documentsApi.matrix()
  } catch (e) {
    matrixError.value = (e as Error).message
  }
})
const routeFor = (from: string) => matrix.value?.routes.find((r) => r.from === from && r.to === 'pdf')

// ---- selection + inspection ----
const selected = ref<string[]>([])
const file = ref<FileView | null>(null)
const info = ref<DocumentInspection | null>(null)
const infoError = ref<string | null>(null)
const inspecting = ref(false)
let inspectReq = 0

watch(selected, async (ids) => {
  const id = ids[0]
  info.value = null
  infoError.value = null
  file.value = id ? (lib.files.find((f) => f.fileId === id) ?? null) : null
  if (!id) return
  const req = ++inspectReq
  inspecting.value = true
  try {
    const r = await documentsApi.inspect(id)
    if (req !== inspectReq) return
    info.value = r
    opts.sheetNames = (r.sheets ?? []).filter((s) => !s.hidden).map((s) => s.name)
  } catch (e) {
    if (req === inspectReq) infoError.value = (e as Error).message
  } finally {
    if (req === inspectReq) inspecting.value = false
  }
})

const fmt = computed(() => info.value?.format)
const FORMAT_LABEL: Record<string, string> = { docx: 'Word 文档', xlsx: 'Excel 工作簿', html: '静态网页', pdf: 'PDF' }
const route = computed(() => (fmt.value && fmt.value !== 'pdf' ? routeFor(fmt.value) : undefined))

// ---- options (only those the server accepts for the format) ----
const opts = reactive({
  pageSize: 'A4' as 'A4' | 'ORIGINAL',
  orientation: 'PORTRAIT' as 'PORTRAIT' | 'LANDSCAPE',
  marginMm: 10,
  sheetNames: [] as string[],
})
const visibleSheets = computed(() => (info.value?.sheets ?? []).filter((s) => !s.hidden))
const allSheetsChosen = computed(() => visibleSheets.value.length > 0 && visibleSheets.value.every((s) => opts.sheetNames.includes(s.name)))

function body(): Record<string, unknown> {
  if (fmt.value === 'html') return { pageSize: opts.pageSize, orientation: opts.orientation, marginMm: opts.marginMm }
  // all visible sheets is the server default: only send a choice when the user narrowed it
  if (fmt.value === 'xlsx' && !allSheetsChosen.value) return { sheetNames: opts.sheetNames }
  return {}
}
const canSubmit = computed(() =>
  !!info.value && !!route.value?.available && !busy.value
  && (fmt.value !== 'xlsx' || opts.sheetNames.length > 0)
  && (fmt.value !== 'html' || (opts.marginMm >= 0 && opts.marginMm <= 30)))

function submit() {
  if (!selected.value[0]) return
  void run(() => documentsApi.convert(selected.value[0], body()), '转换任务已提交')
}

// ---- latest conversion of the selected document ----
const latest = computed<TaskView | undefined>(() =>
  tasks.list.find((t) => t.type === 'document.convert' && t.inputs?.[0]?.fileId === selected.value[0]))
const latestVerification = computed(() => latest.value?.result?.verification as Verification | undefined)
const latestWarnings = computed<string[]>(() => latest.value?.result?.warnings ?? [])
</script>

<template>
  <main id="main" class="docs">
    <header class="head">
      <h1>文档工作台</h1>
      <p>Word / Excel / 网页 转 PDF。转换在服务器本地完成，原文件不会被修改，也不会上传到第三方预览服务。</p>
    </header>

    <div class="layout">
      <aside class="left">
        <section class="card pad">
          <MediaPicker v-model="selected" :kinds="['DOCUMENT', 'PDF']" label="选择文档"
                       accept=".docx,.xlsx,.html,.htm,.pdf" with-outputs />
          <p class="limits">.docx / .xlsx ≤ 30 MB · 单个 UTF-8 .html ≤ 5 MB · PDF。不接受启用宏的文档与旧版 .doc / .xls。</p>
        </section>

        <section class="card pad matrix" aria-labelledby="mx">
          <h2 id="mx">支持的转换</h2>
          <p v-if="matrixError" class="err">{{ matrixError }}</p>
          <ul v-else-if="matrix">
            <li v-for="r in matrix.routes" :key="r.from + r.to" :class="{ off: !r.available }">
              <span class="mono">{{ r.from.toUpperCase() }} → {{ r.to.toUpperCase() }}</span>
              <span class="eng">{{ r.engine }}</span>
              <span class="chip" :class="r.available ? 'chip--mint' : 'chip--apricot'">{{ r.available ? '可用' : '未安装' }}</span>
            </li>
          </ul>
          <p v-if="matrix" class="ns">暂不支持：{{ matrix.notSupported.join('；') }}</p>
        </section>
      </aside>

      <section class="mid">
        <div v-if="!selected[0]" class="card pad empty">
          <p>从左侧上传或选择一个文档。系统会先检查它的结构、页面设置和字体，再决定能怎样转换。</p>
        </div>

        <template v-else>
          <section class="card pad" aria-labelledby="ins" :aria-busy="inspecting">
            <h2 id="ins">{{ file?.originalFilename ?? info?.filename ?? '文档' }}</h2>
            <p v-if="infoError" class="err" role="alert">
              {{ infoError }}
            </p>
            <p v-else-if="inspecting" class="muted">正在检查文档结构…</p>
            <template v-else-if="info">
              <dl class="facts">
                <div><dt>类型</dt><dd>{{ FORMAT_LABEL[info.format] }}</dd></div>
                <div><dt>大小</dt><dd class="mono">{{ bytes(info.sizeBytes) }}</dd></div>
                <div v-if="info.pageSize"><dt>页面</dt><dd>{{ info.pageSize }}</dd></div>
                <div v-if="info.pageCount != null"><dt>页数</dt><dd class="mono">{{ info.pageCount }}</dd></div>
                <div v-if="info.paragraphs != null"><dt>段落</dt><dd class="mono">{{ info.paragraphs }}</dd></div>
                <div v-if="info.tables"><dt>表格</dt><dd>{{ info.tables.length ? info.tables.map((t) => `${t.rows}×${t.cols}`).join('、') : '无' }}</dd></div>
                <div v-if="info.sheets"><dt>工作表</dt><dd>{{ info.sheets.length }} 个</dd></div>
                <div v-if="info.title"><dt>标题</dt><dd>{{ info.title }}</dd></div>
                <div v-if="info.fonts?.length"><dt>字体</dt><dd>{{ info.fonts.join('、') }}</dd></div>
              </dl>
              <ul v-if="info.warnings.length" class="warns" aria-label="提示">
                <li v-for="w in info.warnings" :key="w">{{ w }}</li>
              </ul>
            </template>
          </section>

          <section v-if="info && info.format !== 'pdf'" class="card pad" aria-labelledby="cv">
            <h2 id="cv">转换为 PDF</h2>
            <p v-if="route && !route.available" class="err">服务器未安装 {{ route.engine }}，暂时无法转换这种文档。</p>

            <template v-if="info.format === 'html'">
              <div class="grid">
                <div class="field">
                  <label for="ps">纸张</label>
                  <select id="ps" v-model="opts.pageSize" class="select">
                    <option value="A4">A4</option>
                    <option value="ORIGINAL">沿用网页设置</option>
                  </select>
                </div>
                <div class="field">
                  <label for="or">方向</label>
                  <select id="or" v-model="opts.orientation" class="select" :disabled="opts.pageSize !== 'A4'">
                    <option value="PORTRAIT">纵向</option>
                    <option value="LANDSCAPE">横向</option>
                  </select>
                </div>
                <div class="field">
                  <label for="mg">页边距（mm）</label>
                  <input id="mg" v-model.number="opts.marginMm" class="input mono" type="number" min="0" max="30" step="1"
                         :aria-invalid="!!fields.marginMm || opts.marginMm < 0 || opts.marginMm > 30" />
                  <span class="hint">0–30</span>
                </div>
              </div>
              <p class="note">转换前会移除脚本、内嵌框架与所有外部链接，并在无网络、禁止脚本的浏览器中渲染。</p>
            </template>

            <template v-else-if="info.format === 'xlsx'">
              <fieldset class="sheets">
                <legend>要输出的工作表</legend>
                <label v-for="s in visibleSheets" :key="s.name" class="check">
                  <input v-model="opts.sheetNames" type="checkbox" :value="s.name" />
                  {{ s.name }} <span class="muted">{{ s.usedRows }} 行 × {{ s.usedCols }} 列{{ s.printArea ? ' · 打印区域 ' + s.printArea.split('!').pop() : '' }}</span>
                </label>
                <p v-if="!opts.sheetNames.length" class="err">至少选择一个工作表</p>
                <p v-if="fields.sheetNames" class="err">{{ fields.sheetNames }}</p>
              </fieldset>
              <p class="note">沿用工作簿自身的页面设置；隐藏的工作表不会输出。</p>
            </template>

            <p v-else class="note">沿用 Word 文档自身的纸张、方向与页边距。</p>

            <p v-if="error" class="err" role="alert">{{ error }}</p>
            <button type="button" class="btn" :disabled="!canSubmit" @click="submit">{{ busy ? '提交中…' : '开始转换' }}</button>
          </section>

          <section v-if="info?.format === 'pdf'" class="card pad" aria-labelledby="pv">
            <h2 id="pv">预览</h2>
            <DocumentPreview :file-id="selected[0]" />
            <p class="note">需要把 PDF 转成图片？使用媒体工作台的
              <RouterLink :to="{ path: '/workspace/media', query: { mode: 'pro', tool: 'pdf' } }">PDF 转换</RouterLink>。</p>
          </section>

          <section v-if="latest" class="card pad" aria-labelledby="rs">
            <h2 id="rs">转换结果</h2>
            <TaskProgress v-if="!isTerminal(latest)" :task="latest" />
            <template v-else-if="latest.status === 'SUCCEEDED' && latest.output">
              <p class="meta">
                <span class="mono">{{ latest.output.filename }}</span> · {{ bytes(latest.output.sizeBytes) }}
                <template v-if="latest.result?.pages"> · {{ latest.result.pages }} 页 · {{ latest.result.pageSize }}</template>
                <button type="button" class="btn btn--sm" @click="downloadFile(latest.output!.fileId, latest.output!.filename)">下载 PDF</button>
              </p>
              <VerificationCard v-if="latestVerification" :verification="latestVerification" compact />
              <ul v-if="latestWarnings.length" class="warns">
                <li v-for="w in latestWarnings" :key="w">{{ w }}</li>
              </ul>
              <DocumentPreview :file-id="latest.output.fileId" />
            </template>
            <div v-else class="why">
              <p><strong>{{ latest.status === 'CANCELLED' ? '已取消' : '转换失败' }}：</strong>{{ latest.message }}</p>
              <p v-if="latest.errorCode && ERROR_HINT[latest.errorCode]">{{ ERROR_HINT[latest.errorCode] }}</p>
            </div>
          </section>
        </template>
      </section>

      <TaskTray class="tray" />
    </div>
  </main>
</template>

<style scoped>
.docs {
  max-width: 1480px;
  margin: 0 auto;
  padding: var(--sp-5) var(--gutter) var(--sp-8);
}
.head h1 {
  font-size: var(--fs-xl);
}
.head p {
  margin-top: 4px;
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.layout {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr) 320px;
  gap: var(--sp-5);
  align-items: start;
  margin-top: var(--sp-5);
}
.left,
.mid {
  display: grid;
  gap: var(--sp-4);
  min-width: 0;
}
.pad {
  display: grid;
  gap: var(--sp-3);
  padding: var(--sp-5);
}
h2 {
  font-size: var(--fs-lg);
  overflow-wrap: anywhere;
}
.limits,
.note,
.muted,
.ns {
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--text-3);
}
.matrix ul {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.matrix li {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-xs);
}
.matrix li.off {
  opacity: 0.65;
}
.eng {
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.empty p {
  color: var(--text-2);
  font-size: var(--fs-sm);
}
.facts {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 8px 16px;
  margin: 0;
}
.facts div {
  display: grid;
  gap: 2px;
  min-width: 0;
}
.facts dt {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.facts dd {
  margin: 0;
  font-size: var(--fs-sm);
  overflow-wrap: anywhere;
}
.warns {
  display: grid;
  gap: 4px;
  margin: 0;
  padding: 10px 12px 10px 28px;
  border-radius: var(--r-sm);
  background: var(--apricot);
  color: var(--apricot-ink);
  font-size: var(--fs-xs);
  line-height: 1.6;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--sp-3);
}
.sheets {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  border: 0;
}
.sheets legend {
  margin-bottom: 6px;
  font-size: var(--fs-sm);
}
.check {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  font-size: var(--fs-sm);
}
.meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.meta .btn {
  margin-left: auto;
}
.why {
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--danger-soft);
  font-size: var(--fs-sm);
  line-height: 1.6;
}
.why p:first-child {
  color: var(--danger);
}
.err {
  color: var(--danger);
  font-size: var(--fs-sm);
}
.btn {
  justify-self: start;
}
.tray {
  position: sticky;
  top: 76px;
  max-height: calc(100vh - 92px);
  overflow: auto;
}
@media (max-width: 1199px) {
  .layout {
    grid-template-columns: 280px minmax(0, 1fr);
  }
  .tray {
    grid-column: 1 / -1;
    position: static;
    max-height: none;
  }
}
@media (max-width: 767px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
