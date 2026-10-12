<script setup lang="ts">
import { reactive, ref } from 'vue'
import ToolShell from '../ToolShell.vue'
import MediaPicker from '../MediaPicker.vue'
import { mediaApi } from '@/api/endpoints'
import { useTaskSubmit } from '@/composables/useTaskSubmit'

const video = ref<string[]>([])
const mark = ref<string[]>([])
const form = reactive({ position: 'bottom-right', opacity: 0.75, margin: 24 })
const grid = [
  ['top-left', 'top-center', 'top-right'],
  ['center-left', 'center', 'center-right'],
  ['bottom-left', 'bottom-center', 'bottom-right'],
]
const posLabel: Record<string, string> = {
  'top-left': '左上', 'top-center': '上中', 'top-right': '右上',
  'center-left': '左中', center: '居中', 'center-right': '右中',
  'bottom-left': '左下', 'bottom-center': '下中', 'bottom-right': '右下',
}
const { busy, error, run } = useTaskSubmit()

function submit() {
  void run(() => mediaApi.watermark({
    videoUniqueFilename: video.value[0],
    watermarkUniqueFilename: mark.value[0],
    ...form,
  }))
}
</script>

<template>
  <ToolShell icon="watermark" kicker="Video · Watermark" title="视频水印" subtitle="把一张图片（推荐透明 PNG）叠加到视频上，可选九宫格位置、透明度与边距。"
             feature="video.watermark" tools="FFmpeg" :busy="busy" :error="error" :can-submit="!!video[0] && !!mark[0]" @submit="submit">
    <div class="two">
      <MediaPicker v-model="video" :kinds="['VIDEO']" label="视频" accept="video/*" />
      <MediaPicker v-model="mark" :kinds="['IMAGE']" label="水印图片" accept="image/*" />
    </div>
    <div class="row">
      <div class="field">
        <span class="label" id="pos-l">位置</span>
        <div class="pos" role="radiogroup" aria-labelledby="pos-l">
          <template v-for="row in grid" :key="row[0]">
            <label v-for="p in row" :key="p" class="cell" :class="{ on: form.position === p }" :title="posLabel[p]">
              <input v-model="form.position" class="sr-only" type="radio" name="pos" :value="p" />
              <span class="sr-only">{{ posLabel[p] }}</span>
            </label>
          </template>
        </div>
      </div>
      <div class="field">
        <label for="op">透明度 <span class="mono">{{ Math.round(form.opacity * 100) }}%</span></label>
        <input id="op" v-model.number="form.opacity" type="range" min="0.05" max="1" step="0.05" />
      </div>
      <div class="field">
        <label for="mg">边距（像素）</label>
        <input id="mg" v-model.number="form.margin" class="input mono" type="number" min="0" max="500" />
      </div>
    </div>
  </ToolShell>
</template>

<style scoped>
.two {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: var(--sp-5);
}
.row {
  display: grid;
  grid-template-columns: auto 1fr 160px;
  gap: var(--sp-5);
  align-items: end;
}
.label {
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.pos {
  display: grid;
  grid-template-columns: repeat(3, 28px);
  gap: 4px;
  padding: 6px;
  border-radius: var(--r-sm);
  background: var(--surface-sunk);
  width: max-content;
}
.cell {
  height: 20px;
  border-radius: 4px;
  background: var(--surface);
  border: 1px solid var(--line-strong);
  cursor: pointer;
}
.cell.on {
  background: var(--mint-ink);
  border-color: var(--mint-ink);
}
.cell:has(input:focus-visible) {
  outline: 2px solid var(--focus);
}
input[type='range'] {
  accent-color: var(--mint-ink);
}
@media (max-width: 640px) {
  .row {
    grid-template-columns: 1fr;
  }
}
</style>
