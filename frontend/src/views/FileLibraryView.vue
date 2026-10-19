<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import Icon from '@/components/common/Icon.vue'
import { filesApi } from '@/api/endpoints'
import type { FileView, MediaKind } from '@/api/types'
import { useLibraryStore } from '@/stores/library'
import { useToast } from '@/composables/useToast'
import { downloadFile, previewUrl } from '@/utils/download'
import { bytes, duration, relativeTime } from '@/utils/format'

const lib = useLibraryStore()
const toast = useToast()

const PAGE = 50
const role = ref<'INPUT' | 'OUTPUT'>('INPUT')
const kind = ref<MediaKind | 'ALL'>('ALL')
const page = ref(0)
const rows = ref<FileView[]>([])
const total = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)

const KIND_LABEL: Record<MediaKind, string> = { VIDEO: '视频', AUDIO: '音频', IMAGE: '图片', PDF: 'PDF', ARCHIVE: '压缩包' }

async function load() {
  loading.value = true
  try {
    const p = await filesApi.list(role.value, page.value, PAGE)
    rows.value = p.content
    total.value = p.totalElements
    totalPages.value = p.totalPages
    error.value = null
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(role, () => {
  page.value = 0
  kind.value = 'ALL'
  void load()
})
watch(page, load)

const shown = computed(() => (kind.value === 'ALL' ? rows.value : rows.value.filter((f) => f.mediaType === kind.value)))
const kinds = computed(() => [...new Set(rows.value.map((f) => f.mediaType))])

function expiresIn(ts: number): { text: string; soon: boolean } {
  const ms = ts - Date.now()
  if (ms <= 0) return { text: '即将清理', soon: true }
  const h = Math.floor(ms / 3_600_000)
  const m = Math.floor((ms % 3_600_000) / 60_000)
  return { text: h > 0 ? `${h} 小时 ${m} 分后清理` : `${m} 分钟后清理`, soon: ms < 3_600_000 }
}

// ---- preview ----
const dlg = ref<HTMLDialogElement | null>(null)
const previewing = ref<FileView | null>(null)
const previewSrc = ref<string | null>(null)
const previewError = ref<string | null>(null)

async function openPreview(f: FileView) {
  previewing.value = f
  previewSrc.value = null
  previewError.value = null
  dlg.value?.showModal()
  try {
    previewSrc.value = await previewUrl(f.fileId)
  } catch (e) {
    previewError.value = (e as Error).message
  }
}
function closePreview() {
  previewSrc.value = null
  previewing.value = null
}

// ---- delete (irreversible: confirm and say so) ----
const confirmDlg = ref<HTMLDialogElement | null>(null)
const deleting = ref<FileView | null>(null)
const busy = ref(false)

function askDelete(f: FileView) {
  deleting.value = f
  confirmDlg.value?.showModal()
}
async function doDelete() {
  const f = deleting.value
  if (!f) return
  busy.value = true
  try {
    await filesApi.remove(f.fileId)
    rows.value = rows.value.filter((x) => x.fileId !== f.fileId)
    total.value = Math.max(0, total.value - 1)
    if (f.role === 'INPUT') lib.files = lib.files.filter((x) => x.fileId !== f.fileId)
    toast.ok('文件已永久删除')
    confirmDlg.value?.close()
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    busy.value = false
  }
}

async function download(f: FileView) {
  try {
    await downloadFile(f.fileId, f.originalFilename)
  } catch (e) {
    toast.error((e as Error).message)
  }
}
</script>

<template>
  <main id="main" class="lib">
    <header class="head">
      <div>
        <h1>我的文件</h1>
        <p>上传的原始文件与处理结果分开存放。原始文件不会被任何处理覆盖。</p>
      </div>
      <div class="tabs" role="tablist" aria-label="文件类别">
        <button type="button" role="tab" :aria-selected="role === 'INPUT'" :class="{ on: role === 'INPUT' }" @click="role = 'INPUT'">上传的文件</button>
        <button type="button" role="tab" :aria-selected="role === 'OUTPUT'" :class="{ on: role === 'OUTPUT' }" @click="role = 'OUTPUT'">处理结果</button>
      </div>
    </header>

    <div v-if="kinds.length > 1" class="kinds" role="group" aria-label="按类型筛选">
      <button type="button" :class="{ on: kind === 'ALL' }" :aria-pressed="kind === 'ALL'" @click="kind = 'ALL'">全部</button>
      <button v-for="k in kinds" :key="k" type="button" :class="{ on: kind === k }" :aria-pressed="kind === k" @click="kind = k">
        {{ KIND_LABEL[k] }}
      </button>
    </div>

    <p v-if="error" class="err" role="alert">
      文件列表加载失败：{{ error }}
      <button type="button" class="btn btn--sm btn--ghost" @click="load">重试</button>
    </p>

    <section class="card table" :aria-busy="loading">
      <div class="tr th" role="row">
        <span>文件名</span><span>类型</span><span>大小</span><span>信息</span><span>上传时间</span><span>保留</span><span class="sr">操作</span>
      </div>
      <div v-for="f in shown" :key="f.fileId" class="tr" role="row">
        <span class="name" :title="f.originalFilename">{{ f.originalFilename }}</span>
        <span><span class="chip">{{ KIND_LABEL[f.mediaType] ?? f.mediaType }}</span></span>
        <span class="mono">{{ bytes(f.size) }}</span>
        <span class="info">
          <template v-if="f.info?.width">{{ f.info.width }}×{{ f.info.height }}</template>
          <template v-if="f.info?.duration"> · {{ duration(f.info.duration) }}</template>
          <template v-if="!f.info?.width && !f.info?.duration">—</template>
        </span>
        <span class="when">{{ relativeTime(f.createdAt) }}</span>
        <span class="exp" :class="{ soon: expiresIn(f.expiresAt).soon }">{{ expiresIn(f.expiresAt).text }}</span>
        <span class="acts">
          <button v-if="f.mediaType !== 'ARCHIVE' && f.mediaType !== 'PDF'" type="button" class="ib" :aria-label="`预览 ${f.originalFilename}`" @click="openPreview(f)"><Icon name="eye" :size="16" /></button>
          <button type="button" class="ib" :aria-label="`下载 ${f.originalFilename}`" @click="download(f)"><Icon name="download" :size="16" /></button>
          <button type="button" class="ib danger" :aria-label="`删除 ${f.originalFilename}`" @click="askDelete(f)"><Icon name="trash" :size="16" /></button>
        </span>
      </div>
      <p v-if="!loading && !shown.length && !error" class="empty">
        {{ role === 'INPUT' ? '还没有上传文件。在媒体工作台上传后会出现在这里。' : '还没有处理结果。' }}
      </p>
    </section>

    <nav v-if="totalPages > 1" class="pager" aria-label="分页">
      <button type="button" class="btn btn--sm btn--ghost" :disabled="page === 0" @click="page--">上一页</button>
      <span class="mono">{{ page + 1 }} / {{ totalPages }} · 共 {{ total }} 个</span>
      <button type="button" class="btn btn--sm btn--ghost" :disabled="page + 1 >= totalPages" @click="page++">下一页</button>
    </nav>

    <dialog ref="dlg" class="dlg" aria-labelledby="pv-t" @close="closePreview" @click.self="dlg?.close()">
      <div v-if="previewing" class="inner">
        <header class="dh">
          <h2 id="pv-t">{{ previewing.originalFilename }}</h2>
          <button type="button" class="x" aria-label="关闭" @click="dlg?.close()">×</button>
        </header>
        <p v-if="previewError" class="err">{{ previewError }}</p>
        <template v-else-if="previewSrc">
          <video v-if="previewing.mediaType === 'VIDEO'" :src="previewSrc" controls playsinline class="media" />
          <audio v-else-if="previewing.mediaType === 'AUDIO'" :src="previewSrc" controls class="audio" />
          <img v-else :src="previewSrc" :alt="previewing.originalFilename" class="media" />
        </template>
      </div>
    </dialog>

    <dialog ref="confirmDlg" class="dlg small" aria-labelledby="del-t" @click.self="confirmDlg?.close()">
      <div v-if="deleting" class="inner">
        <h2 id="del-t">永久删除这个文件？</h2>
        <p class="warn">「{{ deleting.originalFilename }}」将从服务器上删除，<strong>无法恢复</strong>。引用它的任务仍保留记录，但无法再重试或下载。</p>
        <div class="row">
          <button type="button" class="btn btn--ghost" @click="confirmDlg?.close()">取消</button>
          <button type="button" class="btn btn--danger" :disabled="busy" @click="doDelete">{{ busy ? '删除中…' : '永久删除' }}</button>
        </div>
      </div>
    </dialog>
  </main>
</template>

<style scoped>
.lib {
  display: grid;
  gap: var(--sp-4);
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--sp-6) var(--gutter) var(--sp-8);
}
.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.head h1 {
  font-size: var(--fs-2xl);
}
.head p {
  margin-top: 6px;
  color: var(--text-2);
  font-size: var(--fs-sm);
}
.tabs,
.kinds {
  display: flex;
  gap: 4px;
  padding: 3px;
  border-radius: var(--r-pill);
  background: var(--surface-sunk);
  width: max-content;
  max-width: 100%;
  overflow-x: auto;
}
.tabs button,
.kinds button {
  min-height: 34px;
  padding: 0 14px;
  border: 0;
  border-radius: var(--r-pill);
  background: transparent;
  font-size: var(--fs-sm);
  color: var(--text-2);
  white-space: nowrap;
}
.tabs button.on,
.kinds button.on {
  background: var(--surface);
  color: var(--text);
  box-shadow: var(--shadow-1);
}
.table {
  padding: 4px 0;
  overflow: hidden;
}
.tr {
  display: grid;
  grid-template-columns: minmax(0, 2.2fr) 70px 90px minmax(0, 1fr) 100px 130px 120px;
  align-items: center;
  gap: 12px;
  min-height: 52px;
  padding: 0 var(--sp-5);
  border-top: 1px solid var(--line);
  font-size: var(--fs-sm);
}
.th {
  min-height: 40px;
  border-top: 0;
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.info,
.when {
  color: var(--text-2);
  font-size: var(--fs-xs);
}
.exp {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.exp.soon {
  color: var(--warn);
}
.acts {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
}
.ib {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  color: var(--text-2);
}
.ib:hover {
  background: var(--surface-sunk);
  color: var(--text);
}
.ib.danger:hover {
  background: var(--danger-soft);
  color: var(--danger);
}
.sr {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
.empty {
  padding: var(--sp-6) var(--sp-5);
  text-align: center;
  color: var(--text-3);
  font-size: var(--fs-sm);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-3);
  font-size: var(--fs-sm);
}
.err {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: var(--r-sm);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: var(--fs-sm);
}
.dlg {
  width: min(880px, calc(100vw - 32px));
  padding: 0;
  border: 0;
  border-radius: var(--r-lg, 16px);
  background: var(--surface);
  box-shadow: 0 30px 80px rgba(0, 0, 0, 0.25);
}
.dlg.small {
  width: min(460px, calc(100vw - 32px));
}
.dlg::backdrop {
  background: rgba(30, 36, 34, 0.45);
}
.inner {
  display: grid;
  gap: var(--sp-4);
  padding: var(--sp-5);
}
.dh {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
}
.dh h2,
.inner > h2 {
  font-size: var(--fs-lg);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.x {
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  font-size: 22px;
  color: var(--text-2);
}
.media {
  width: 100%;
  max-height: 70vh;
  object-fit: contain;
  border-radius: var(--r-sm);
  background: #111;
}
.audio {
  width: 100%;
}
.warn {
  font-size: var(--fs-sm);
  color: var(--text-2);
  line-height: 1.6;
}
.row {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-2);
}
@media (max-width: 900px) {
  .th {
    display: none;
  }
  .tr {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas: 'name acts' 'meta acts';
    row-gap: 2px;
    padding: 10px var(--sp-4);
  }
  .tr > span:not(.name):not(.acts) {
    display: none;
  }
  .name {
    grid-area: name;
  }
  .acts {
    grid-area: acts;
  }
}
</style>
