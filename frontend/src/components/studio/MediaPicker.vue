<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import Icon from '@/components/common/Icon.vue'
import { filesApi } from '@/api/endpoints'
import type { FileView, MediaKind } from '@/api/types'
import { useLibraryStore } from '@/stores/library'
import { useToast } from '@/composables/useToast'
import { bytes, duration } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    kinds: MediaKind[]
    multiple?: boolean
    max?: number
    label?: string
    accept?: string
    /** also offer processing results (e.g. extract audio from the compressed video, not only the upload) */
    withOutputs?: boolean
  }>(),
  { multiple: false, max: 50, label: '选择文件', withOutputs: false },
)
const model = defineModel<string[]>({ default: () => [] })

const lib = useLibraryStore()
const toast = useToast()
const dragging = ref(false)
const input = ref<HTMLInputElement | null>(null)

const outputs = ref<FileView[]>([])
onMounted(async () => {
  if (!props.withOutputs) return
  try {
    outputs.value = (await filesApi.list('OUTPUT', 0, 50)).content
  } catch {
    /* results are optional here; uploads still work */
  }
})
const pool = computed(() => [...lib.files, ...outputs.value])
const candidates = computed(() => pool.value.filter((f) => props.kinds.includes(f.mediaType)))
const selected = computed(() => model.value.map((id) => pool.value.find((f) => f.fileId === id)).filter(Boolean) as FileView[])

function toggle(f: FileView) {
  if (!props.multiple) {
    model.value = model.value[0] === f.fileId ? [] : [f.fileId]
    return
  }
  if (model.value.includes(f.fileId)) model.value = model.value.filter((id) => id !== f.fileId)
  else if (model.value.length < props.max) model.value = [...model.value, f.fileId]
  else toast.info(`最多选择 ${props.max} 个文件`)
}

async function addFiles(list: FileList | File[]) {
  const files = Array.from(list).slice(0, props.multiple ? props.max : 1)
  for (const file of files) {
    const fv = await lib.upload(file)
    if (!fv) continue
    if (!props.kinds.includes(fv.mediaType)) {
      toast.info(`「${fv.originalFilename}」识别为 ${kindLabel(fv.mediaType)}，不适用于当前工具`)
      continue
    }
    if (props.multiple) {
      if (model.value.length < props.max) model.value = [...model.value, fv.fileId]
    } else {
      model.value = [fv.fileId]
    }
  }
}

function onDrop(e: DragEvent) {
  dragging.value = false
  if (e.dataTransfer?.files?.length) void addFiles(e.dataTransfer.files)
}

function onPick(e: Event) {
  const el = e.target as HTMLInputElement
  if (el.files?.length) void addFiles(el.files)
  el.value = ''
}

function kindLabel(k: MediaKind) {
  return { VIDEO: '视频', AUDIO: '音频', IMAGE: '图片', PDF: 'PDF', ARCHIVE: '压缩包' }[k]
}

function describe(f: FileView) {
  const i = f.info
  const parts = [bytes(f.size)]
  if (i?.width && i?.height) parts.push(`${i.width}×${i.height}`)
  if (i?.duration) parts.push(duration(i.duration))
  return parts.join(' · ')
}
</script>

<template>
  <div class="picker">
    <div class="head">
      <span class="label">{{ label }}</span>
      <span v-if="multiple && model.length" class="count mono">{{ model.length }} / {{ max }}</span>
    </div>

    <div
      class="drop"
      :class="{ dragging }"
      role="button"
      tabindex="0"
      :aria-label="`上传${kinds.map(kindLabel).join('或')}文件`"
      @click="input?.click()"
      @keydown.enter.prevent="input?.click()"
      @keydown.space.prevent="input?.click()"
      @dragover.prevent="dragging = true"
      @dragleave="dragging = false"
      @drop.prevent="onDrop"
    >
      <span class="plus" aria-hidden="true"><Icon name="upload" :size="20" /></span>
      <span>拖入文件，或<u>点击上传</u></span>
      <span class="hint">支持 {{ kinds.map(kindLabel).join(' / ') }} · 类型以文件内容为准</span>
      <input ref="input" type="file" class="sr-only" :multiple="multiple" :accept="accept" tabindex="-1" @change="onPick" />
    </div>

    <ul v-if="lib.uploads.length" class="uploads">
      <li v-for="u in lib.uploads" :key="u.id" :class="{ failed: u.error }">
        <span class="name">{{ u.name }}</span>
        <template v-if="u.error">
          <span class="err">{{ u.error }}</span>
          <button class="x" type="button" aria-label="移除" @click="lib.dismissUpload(u.id)">×</button>
        </template>
        <template v-else>
          <span class="bar"><span :style="{ transform: `scaleX(${u.progress})` }" /></span>
          <span class="mono pct">{{ u.progress < 1 ? Math.round(u.progress * 100) + '%' : '分析中' }}</span>
        </template>
      </li>
    </ul>

    <ul v-if="candidates.length" class="list" :aria-label="`已上传的${kinds.map(kindLabel).join('/')}`">
      <li v-for="f in candidates" :key="f.fileId">
        <button
          type="button"
          class="item"
          :class="{ on: model.includes(f.fileId) }"
          :aria-pressed="model.includes(f.fileId)"
          @click="toggle(f)"
        >
          <span class="kind mono">{{ f.mediaType.slice(0, 3) }}</span>
          <span class="txt">
            <span class="name">{{ f.originalFilename }}</span>
            <span class="desc"><span v-if="f.role === 'OUTPUT'" class="tag">处理结果</span>{{ describe(f) }}</span>
          </span>
          <span class="check" aria-hidden="true">{{ model.includes(f.fileId) ? (multiple ? model.indexOf(f.fileId) + 1 : '✓') : '' }}</span>
        </button>
      </li>
    </ul>
    <p v-else-if="!lib.loading && !lib.uploads.length" class="empty">还没有可用的文件，先上传一个吧。</p>
    <p v-if="lib.loadError" class="err">文件列表加载失败：{{ lib.loadError }}</p>

    <slot name="selected" :files="selected" />
  </div>
</template>

<style scoped>
.tag {
  margin-right: 6px;
  padding: 0 6px;
  border-radius: var(--r-pill);
  background: var(--fog);
  color: var(--fog-ink);
  font-size: 10px;
}
.picker {
  display: grid;
  gap: 10px;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.label {
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.count {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.drop {
  display: grid;
  justify-items: center;
  gap: 4px;
  padding: 18px;
  border: 1.5px dashed var(--line-strong);
  border-radius: var(--r-md);
  background: var(--surface);
  font-size: var(--fs-sm);
  color: var(--text-2);
  cursor: pointer;
  text-align: center;
  transition: border-color var(--t-fast), background var(--t-fast);
}
.drop:hover,
.drop.dragging {
  border-color: var(--mint-ink);
  background: rgba(217, 237, 228, 0.35);
}
.plus {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  margin-bottom: 4px;
  border-radius: 12px;
  background: var(--mint);
  color: var(--mint-ink);
  transition: transform var(--t-med) var(--ease-out);
}
.drop:hover .plus,
.drop.dragging .plus {
  transform: translateY(-2px);
}
.hint {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.uploads,
.list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 6px;
}
.uploads li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 90px 52px;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: var(--r-sm);
  background: var(--surface-sunk);
  font-size: var(--fs-xs);
}
.uploads li.failed {
  grid-template-columns: minmax(0, 1fr) auto 20px;
  background: var(--danger-soft);
}
.uploads .bar {
  height: 4px;
  border-radius: 999px;
  background: var(--line);
  overflow: hidden;
}
.uploads .bar span {
  display: block;
  height: 100%;
  background: var(--mint-ink);
  transform-origin: left;
  transition: transform 0.2s;
}
.pct {
  text-align: right;
  color: var(--text-3);
}
.list {
  max-height: 248px;
  overflow: auto;
  padding-right: 2px;
}
.item {
  width: 100%;
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) 24px;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
  text-align: left;
  transition: border-color var(--t-fast), background var(--t-fast);
}
.item:hover {
  border-color: var(--line-strong);
}
.item.on {
  border-color: var(--mint-ink);
  background: rgba(217, 237, 228, 0.4);
}
.kind {
  font-size: 10px;
  letter-spacing: 0.08em;
  padding: 4px 0;
  border-radius: 6px;
  background: var(--fog);
  color: var(--fog-ink);
  text-align: center;
}
.txt {
  display: grid;
  min-width: 0;
}
.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-sm);
}
.desc {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.check {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  font-size: 11px;
  background: var(--mint-ink);
  color: var(--paper);
}
.item:not(.on) .check {
  background: transparent;
  border: 1px solid var(--line-strong);
}
.empty {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.err {
  color: var(--danger);
  font-size: var(--fs-xs);
}
.x {
  border: 0;
  background: none;
  color: var(--danger);
  font-size: 16px;
}
</style>
