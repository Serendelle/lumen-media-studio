<script setup lang="ts">
import { reactive, ref } from 'vue'
import ToolShell from '../ToolShell.vue'
import MediaPicker from '../MediaPicker.vue'
import Segmented from '../Segmented.vue'
import { mediaApi } from '@/api/endpoints'
import { useTaskSubmit } from '@/composables/useTaskSubmit'

const selected = ref<string[]>([])
const tool = ref<'compress' | 'resize' | 'convert'>('compress')
const form = reactive({
  format: 'webp',
  quality: 82,
  useTarget: false,
  targetSizeKB: 300,
  maxWidth: '' as '' | number,
  width: '' as '' | number,
  height: '' as '' | number,
  keepAspectRatio: true,
  targetFormat: 'webp',
})
const { busy, error, run } = useTaskSubmit()

function settings(): Record<string, unknown> {
  if (tool.value === 'compress') {
    const s: Record<string, unknown> = { format: form.format, quality: form.quality }
    if (form.useTarget) s.targetSizeKB = form.targetSizeKB
    if (form.maxWidth !== '') s.maxWidth = Number(form.maxWidth)
    return s
  }
  if (tool.value === 'resize') {
    const s: Record<string, unknown> = { keepAspectRatio: form.keepAspectRatio }
    if (form.width !== '') s.width = Number(form.width)
    if (form.height !== '') s.height = Number(form.height)
    return s
  }
  return { targetFormat: form.targetFormat, quality: form.quality }
}

const canSubmit = () =>
  selected.value.length > 0 && (tool.value !== 'resize' || form.width !== '' || form.height !== '')

function submit() {
  void run(() => mediaApi.processImages(selected.value, tool.value, settings()), `已提交 ${selected.value.length} 张图片`)
}
</script>

<template>
  <ToolShell title="图片处理" subtitle="批量压缩、改尺寸或转格式（单次最多 50 张）。设定目标体积时会按质量搜索；无法达到会如实标注并给出建议。"
             feature="image.process" tools="ImageMagick" :busy="busy" :error="error" :can-submit="canSubmit()" @submit="submit">
    <MediaPicker v-model="selected" :kinds="['IMAGE']" multiple :max="50" label="图片（可多选）" accept="image/*" />
    <Segmented v-model="tool" name="itool" label="操作"
               :options="[{ value: 'compress', label: '压缩' }, { value: 'resize', label: '改尺寸' }, { value: 'convert', label: '转格式' }]" />

    <div v-if="tool === 'compress'" class="grid">
      <div class="field">
        <label for="ifmt">输出格式</label>
        <select id="ifmt" v-model="form.format" class="select">
          <option value="keep">保持原格式</option>
          <option value="webp">WebP（推荐）</option>
          <option value="avif">AVIF（更小，编码较慢）</option>
          <option value="jpg">JPG</option>
          <option value="png">PNG（无损）</option>
        </select>
      </div>
      <div class="field">
        <label for="iq">质量 <span class="mono">{{ form.quality }}</span></label>
        <input id="iq" v-model.number="form.quality" type="range" min="30" max="100" :disabled="form.useTarget" />
        <span class="hint">{{ form.useTarget ? '使用目标体积时自动搜索质量' : '越高越清晰' }}</span>
      </div>
      <div class="field">
        <label class="inline"><input v-model="form.useTarget" type="checkbox" /> 按目标体积压缩</label>
        <div class="with-unit">
          <input v-model.number="form.targetSizeKB" class="input mono" type="number" min="5" step="10" :disabled="!form.useTarget" aria-label="目标体积 KB" />
          <span>KB / 张</span>
        </div>
      </div>
      <div class="field">
        <label for="imw">最大宽度</label>
        <input id="imw" v-model="form.maxWidth" class="input mono" type="number" min="1" placeholder="不限制" />
        <span class="hint">只缩小不放大</span>
      </div>
    </div>

    <div v-else-if="tool === 'resize'" class="grid">
      <div class="field">
        <label for="iw">宽度（px）</label>
        <input id="iw" v-model="form.width" class="input mono" type="number" min="1" max="16000" placeholder="自动" />
      </div>
      <div class="field">
        <label for="ih">高度（px）</label>
        <input id="ih" v-model="form.height" class="input mono" type="number" min="1" max="16000" placeholder="自动" />
      </div>
      <div class="field">
        <span class="label">比例</span>
        <label class="inline"><input v-model="form.keepAspectRatio" type="checkbox" /> 保持原始宽高比</label>
      </div>
    </div>

    <div v-else class="grid">
      <div class="field">
        <label for="itf">目标格式</label>
        <select id="itf" v-model="form.targetFormat" class="select">
          <option v-for="f in ['webp', 'avif', 'jpg', 'png', 'gif', 'bmp', 'tiff']" :key="f" :value="f">{{ f.toUpperCase() }}</option>
        </select>
      </div>
      <div class="field">
        <label for="iq2">质量 <span class="mono">{{ form.quality }}</span></label>
        <input id="iq2" v-model.number="form.quality" type="range" min="30" max="100" />
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
.inline {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.label {
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.with-unit {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-xs);
  color: var(--text-3);
}
input[type='range'] {
  accent-color: var(--mint-ink);
}
</style>
