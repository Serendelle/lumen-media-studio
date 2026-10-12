<script setup lang="ts">
import { ref } from 'vue'
import ToolShell from '../ToolShell.vue'
import MediaPicker from '../MediaPicker.vue'
import Segmented from '../Segmented.vue'
import { mediaApi } from '@/api/endpoints'
import { useTaskSubmit } from '@/composables/useTaskSubmit'

const selected = ref<string[]>([])
const target = ref<'mp4' | 'mov' | 'webm' | 'mkv' | 'gif'>('mp4')
const gifFps = ref(12)
const { busy, error, run } = useTaskSubmit()

function submit() {
  void run(() =>
    mediaApi.convertVideo({
      uniqueFilename: selected.value[0],
      targetFormat: target.value,
      ...(target.value === 'gif' ? { fps: gifFps.value } : {}),
    }),
  )
}
</script>

<template>
  <ToolShell title="格式转换" subtitle="在常见封装格式之间转码。MP4/MOV/MKV 使用 H.264 + AAC，WebM 使用 VP9 + Opus，GIF 自动生成调色板。"
             feature="video.convert" tools="FFmpeg" :busy="busy" :error="error" :can-submit="!!selected[0]" @submit="submit">
    <MediaPicker v-model="selected" :kinds="['VIDEO']" label="源视频" accept="video/*" />
    <Segmented v-model="target" name="fmt" label="目标格式"
               :options="[{ value: 'mp4', label: 'MP4' }, { value: 'mov', label: 'MOV' }, { value: 'webm', label: 'WebM' }, { value: 'mkv', label: 'MKV' }, { value: 'gif', label: 'GIF' }]" />
    <div v-if="target === 'gif'" class="field" style="max-width: 280px">
      <label for="gfps">GIF 帧率 <span class="mono">{{ gifFps }} fps</span></label>
      <input id="gfps" v-model.number="gifFps" type="range" min="5" max="30" />
      <span class="hint">宽度固定为 480px；时长较长的视频会生成很大的 GIF</span>
    </div>
  </ToolShell>
</template>
