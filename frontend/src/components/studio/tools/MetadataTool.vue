<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import ToolShell from '../ToolShell.vue'
import MediaPicker from '../MediaPicker.vue'
import { mediaApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { MetadataCapability, MetadataView } from '@/api/types'
import { useTaskSubmit } from '@/composables/useTaskSubmit'
import { useToast } from '@/composables/useToast'
import { useLibraryStore } from '@/stores/library'
import { useSystemStore } from '@/stores/system'
import { useTasksStore } from '@/stores/tasks'

const lib = useLibraryStore()
const system = useSystemStore()
const tasks = useTasksStore()
const toast = useToast()

const selected = ref<string[]>([])
const single = computed(() => (selected.value.length === 1 ? lib.files.find((f) => f.fileId === selected.value[0]) : undefined))

const MIME_EXT: Record<string, string> = {
  'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/heic': 'heic', 'image/heif': 'heic',
  'image/avif': 'avif', 'image/tiff': 'tiff', 'image/gif': 'gif', 'image/bmp': 'bmp', 'image/svg+xml': 'svg',
  'video/mp4': 'mp4', 'video/quicktime': 'mov', 'video/x-m4v': 'mp4', 'audio/mp4': 'm4a', 'video/x-matroska': 'mkv',
  'video/webm': 'webm', 'audio/mpeg': 'mp3', 'audio/flac': 'flac', 'audio/x-flac': 'flac', 'audio/wav': 'wav',
  'audio/x-wav': 'wav', 'audio/vnd.wave': 'wav', 'video/x-msvideo': 'avi',
}
const capability = computed<MetadataCapability | null>(() => {
  if (!single.value) return null
  const ext = MIME_EXT[single.value.mimeType]
  return (ext && system.caps?.metadataCapabilities?.[ext]) || null
})
const can = (field: string) => !!capability.value?.editableFields.includes(field)

// ---- read ----
const view = ref<MetadataView | null>(null)
const viewError = ref<string | null>(null)
const loadingView = ref(false)
const showAll = ref(false)

watch(single, async (f) => {
  view.value = null
  viewError.value = null
  showAll.value = false
  Object.assign(form, empty())
  if (!f) return
  loadingView.value = true
  try {
    view.value = await mediaApi.viewMetadata(f.fileId)
  } catch (e) {
    viewError.value = e instanceof ApiError ? e.message : '读取元数据失败'
  } finally {
    loadingView.value = false
  }
})

const sensitive = computed(() => new Set(view.value?.sensitiveTags ?? []))
const groups = computed(() => {
  const g = view.value?.groups ?? {}
  return Object.entries(g).filter(([name]) => showAll.value || !['System', 'File', 'ExifTool'].includes(name))
})

// ---- edit ----
const empty = () => ({ title: '', artist: '', comment: '', copyright: '', make: '', model: '', dateTimeOriginal: '', gpsLatitude: '' as '' | number, gpsLongitude: '' as '' | number, clearGps: false })
const form = reactive(empty())
const { busy, error, fields, run } = useTaskSubmit()

function toExifDate(local: string) {
  // <input type="datetime-local"> gives "2024-04-06T18:30" → "2024:04:06 18:30:00"
  if (!local) return ''
  const [d, t] = local.split('T')
  return `${d.replace(/-/g, ':')} ${(t ?? '00:00').padEnd(8, ':00').slice(0, 8)}`
}

const dirty = computed(() =>
  Object.entries(form).some(([k, v]) => (k === 'clearGps' ? v === true : v !== '' && v != null)),
)

function submitEdit() {
  if (!single.value) return
  const body: Record<string, unknown> = { uniqueFilename: single.value.fileId }
  for (const k of ['title', 'artist', 'comment', 'copyright', 'make', 'model'] as const) if (form[k].trim()) body[k] = form[k].trim()
  if (form.dateTimeOriginal) body.dateTimeOriginal = toExifDate(form.dateTimeOriginal)
  if (form.clearGps) body.clearGps = true
  else if (form.gpsLatitude !== '' && form.gpsLongitude !== '') {
    body.gpsLatitude = Number(form.gpsLatitude)
    body.gpsLongitude = Number(form.gpsLongitude)
  }
  void run(() => mediaApi.modifyMetadata(body), '元数据已写入新文件')
}

// ---- batch privacy scrub ----
const scrubbing = ref<{ done: number; total: number } | null>(null)
async function scrub(mode: 'all' | 'location') {
  const ids = [...selected.value]
  scrubbing.value = { done: 0, total: ids.length }
  let failed = 0
  for (const id of ids) {
    try {
      const r = await mediaApi.scrubMetadata(id, mode)
      await tasks.track(r.taskId)
    } catch (e) {
      failed++
      const name = lib.files.find((f) => f.fileId === id)?.originalFilename ?? id
      toast.error(`${name}：${e instanceof ApiError ? e.message : '清理失败'}`)
    }
    scrubbing.value = { done: scrubbing.value.done + 1, total: ids.length }
  }
  scrubbing.value = null
  if (failed < ids.length) toast.ok(`已清理 ${ids.length - failed} 个文件，结果在右侧任务列表中`)
}

function fmt(v: unknown) {
  const s = typeof v === 'string' ? v : JSON.stringify(v)
  return s.length > 140 ? s.slice(0, 140) + '…' : s
}
</script>

<template>
  <ToolShell title="元数据与隐私" subtitle="查看并编辑标题、作者、拍摄时间与 GPS；一键清理隐私信息（支持批量）。可编辑字段以该格式的实际能力为准，不承诺工具做不到的修改。"
             feature="metadata.view" tools="ExifTool 与 FFmpeg" :busy="busy" :error="error" :can-submit="!!single && dirty"
             submit-label="写入元数据（生成新文件）" @submit="submitEdit">
    <MediaPicker v-model="selected" :kinds="['IMAGE', 'VIDEO', 'AUDIO']" multiple :max="20" label="文件（单选查看与编辑，多选批量清理）" accept="image/*,video/*,audio/*" />

    <div v-if="selected.length" class="privacy">
      <div>
        <strong>隐私清理</strong>
        <span class="hint">输出为新文件，原文件不会被修改；完成后会重新读取输出文件核验结果</span>
      </div>
      <div class="acts">
        <button type="button" class="btn btn--sm btn--soft" :disabled="!!scrubbing" @click="scrub('location')">仅清除位置信息</button>
        <button type="button" class="btn btn--sm btn--ghost" :disabled="!!scrubbing" @click="scrub('all')">清除全部元数据</button>
      </div>
      <p v-if="scrubbing" class="hint mono">处理中 {{ scrubbing.done }} / {{ scrubbing.total }}</p>
    </div>

    <template v-if="single">
      <div v-if="loadingView" class="hint">正在读取元数据…</div>
      <p v-else-if="viewError" class="err">{{ viewError }}</p>
      <div v-else-if="view" class="view">
        <div class="summary">
          <span v-if="view.hasPrivacyData" class="chip chip--apricot">含 {{ view.sensitiveTags.length }} 项可能泄露隐私的字段</span>
          <span v-else class="chip chip--mint">未发现隐私敏感字段</span>
          <a v-if="view.gps" class="chip chip--fog" :href="view.gps.mapUrl" target="_blank" rel="noopener noreferrer">
            📍 {{ view.gps.latitude.toFixed(4) }}, {{ view.gps.longitude.toFixed(4) }} · 在地图中查看
          </a>
          <label class="inline"><input v-model="showAll" type="checkbox" /> 显示系统字段</label>
        </div>
        <details v-for="[name, tags] in groups" :key="name" class="group" :open="groups.length <= 3">
          <summary>{{ name }} <span class="mono n">{{ Object.keys(tags).length }}</span></summary>
          <dl>
            <div v-for="(v, k) in tags" :key="k" :class="{ sens: sensitive.has(`${name}:${k}`) }">
              <dt>{{ k }}</dt>
              <dd class="mono">{{ fmt(v) }}</dd>
            </div>
          </dl>
        </details>
      </div>

      <div class="edit">
        <p class="cap">
          <template v-if="capability && capability.writer !== 'NONE'">
            {{ capability.format.toUpperCase() }} 可编辑：{{ capability.editableFields.length }} 项（使用 {{ capability.writer === 'EXIFTOOL' ? 'ExifTool' : 'FFmpeg' }}）
          </template>
          <template v-else>该格式不支持写入元数据</template>
        </p>
        <div class="grid">
          <div class="field"><label for="m-title">标题</label><input id="m-title" v-model="form.title" class="input" maxlength="256" :disabled="!can('title')" /></div>
          <div class="field"><label for="m-artist">作者</label><input id="m-artist" v-model="form.artist" class="input" maxlength="256" :disabled="!can('artist')" /></div>
          <div class="field"><label for="m-copy">版权</label><input id="m-copy" v-model="form.copyright" class="input" maxlength="256" :disabled="!can('copyright')" /></div>
          <div class="field"><label for="m-cmt">描述 / 备注</label><input id="m-cmt" v-model="form.comment" class="input" maxlength="256" :disabled="!can('comment')" /></div>
          <div class="field"><label for="m-make">设备厂商</label><input id="m-make" v-model="form.make" class="input" maxlength="256" :disabled="!can('make')" /></div>
          <div class="field"><label for="m-model">设备型号</label><input id="m-model" v-model="form.model" class="input" maxlength="256" :disabled="!can('model')" /></div>
          <div class="field">
            <label for="m-dt">拍摄时间</label>
            <input id="m-dt" v-model="form.dateTimeOriginal" class="input" type="datetime-local" step="1" :disabled="!can('dateTimeOriginal')"
                   :aria-invalid="!!fields.dateTimeOriginal" />
          </div>
          <div class="field">
            <span class="label">GPS（纬度 / 经度）</span>
            <div class="gps">
              <input v-model="form.gpsLatitude" class="input mono" type="number" step="0.000001" min="-90" max="90" placeholder="纬度" aria-label="纬度"
                     :disabled="!can('gps') || form.clearGps" />
              <input v-model="form.gpsLongitude" class="input mono" type="number" step="0.000001" min="-180" max="180" placeholder="经度" aria-label="经度"
                     :disabled="!can('gps') || form.clearGps" />
            </div>
            <label class="inline"><input v-model="form.clearGps" type="checkbox" :disabled="!can('gps')" /> 清除 GPS</label>
            <span v-if="!can('gps')" class="hint">该格式没有可写入的位置字段</span>
          </div>
        </div>
      </div>
    </template>
  </ToolShell>
</template>

<style scoped>
.privacy {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: 14px 16px;
  border-radius: var(--r-md);
  background: linear-gradient(120deg, var(--mint), var(--fog));
}
.privacy > div:first-child {
  display: grid;
  gap: 2px;
}
.acts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.hint {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.err {
  color: var(--danger);
  font-size: var(--fs-sm);
}
.view {
  display: grid;
  gap: 8px;
}
.summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.summary a {
  text-decoration: none;
}
.inline {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--text-2);
}
.group {
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
}
.group summary {
  padding: 8px 12px;
  font-size: var(--fs-sm);
  cursor: pointer;
}
.n {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.group dl {
  margin: 0;
  padding: 0 12px 10px;
  display: grid;
  gap: 2px;
  max-height: 240px;
  overflow: auto;
}
.group dl div {
  display: grid;
  grid-template-columns: minmax(120px, 34%) 1fr;
  gap: 12px;
  padding: 3px 6px;
  border-radius: 4px;
  font-size: var(--fs-xs);
}
.group dt {
  color: var(--text-2);
  overflow-wrap: anywhere;
}
.group dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.sens {
  background: var(--apricot);
}
.edit {
  display: grid;
  gap: var(--sp-3);
}
.cap {
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--sp-4);
}
.label {
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.gps {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}
input:disabled {
  background: var(--surface-sunk);
  color: var(--text-3);
}
</style>
