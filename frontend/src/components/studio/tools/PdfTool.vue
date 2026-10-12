<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import ToolShell from '../ToolShell.vue'
import MediaPicker from '../MediaPicker.vue'
import Segmented from '../Segmented.vue'
import { mediaApi } from '@/api/endpoints'
import { useTaskSubmit } from '@/composables/useTaskSubmit'
import { useLibraryStore } from '@/stores/library'

const lib = useLibraryStore()
const mode = ref<'images-to-pdf' | 'pdf-to-image'>('images-to-pdf')
const selected = ref<string[]>([])
const form = reactive({ density: 150, format: 'png', quality: 90 })
watch(mode, () => (selected.value = []))

const ordered = computed(() => selected.value.map((id) => lib.files.find((f) => f.fileId === id)).filter(Boolean))
function move(i: number, d: number) {
  const next = [...selected.value]
  const j = i + d
  if (j < 0 || j >= next.length) return
  ;[next[i], next[j]] = [next[j], next[i]]
  selected.value = next
}

const { busy, error, run } = useTaskSubmit()
function submit() {
  const settings = mode.value === 'pdf-to-image' ? { ...form } : {}
  void run(() => mediaApi.pdf(selected.value, mode.value, settings))
}
</script>

<template>
  <ToolShell icon="pdf" kicker="Document · PDF" title="PDF 转换" subtitle="把多张图片按顺序合并为一个 PDF，或把 PDF 的每一页渲染成图片（打包为 zip）。"
             feature="pdf.process" tools="ImageMagick 与 Ghostscript" :busy="busy" :error="error" :can-submit="selected.length > 0" @submit="submit">
    <Segmented v-model="mode" name="pmode" label="方向"
               :options="[{ value: 'images-to-pdf', label: '图片 → PDF' }, { value: 'pdf-to-image', label: 'PDF → 图片' }]" />
    <MediaPicker v-if="mode === 'images-to-pdf'" v-model="selected" :kinds="['IMAGE']" multiple :max="200" label="图片（按选择顺序排列页码）" accept="image/*" />
    <MediaPicker v-else v-model="selected" :kinds="['PDF']" label="PDF 文件" accept="application/pdf" />

    <ol v-if="mode === 'images-to-pdf' && ordered.length > 1" class="order" aria-label="页面顺序">
      <li v-for="(f, i) in ordered" :key="f!.fileId">
        <span class="mono n">{{ i + 1 }}</span>
        <span class="name">{{ f!.originalFilename }}</span>
        <button type="button" class="btn btn--sm btn--ghost" :disabled="i === 0" :aria-label="`上移 ${f!.originalFilename}`" @click="move(i, -1)">↑</button>
        <button type="button" class="btn btn--sm btn--ghost" :disabled="i === ordered.length - 1" :aria-label="`下移 ${f!.originalFilename}`" @click="move(i, 1)">↓</button>
      </li>
    </ol>

    <div v-if="mode === 'pdf-to-image'" class="grid">
      <div class="field">
        <label for="pd">渲染精度（DPI）</label>
        <select id="pd" v-model.number="form.density" class="select">
          <option :value="96">96（屏幕浏览）</option>
          <option :value="150">150（推荐）</option>
          <option :value="300">300（打印）</option>
        </select>
      </div>
      <div class="field">
        <label for="pf">图片格式</label>
        <select id="pf" v-model="form.format" class="select">
          <option value="png">PNG</option>
          <option value="jpg">JPG</option>
        </select>
      </div>
    </div>
  </ToolShell>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--sp-4);
}
.order {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 6px;
}
.order li {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-sm);
}
.n {
  color: var(--text-3);
}
.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
