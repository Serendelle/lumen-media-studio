<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import ToolShell from '../ToolShell.vue'
import MediaPicker from '../MediaPicker.vue'
import Segmented from '../Segmented.vue'
import { mediaApi } from '@/api/endpoints'
import { useTaskSubmit } from '@/composables/useTaskSubmit'
import { useLibraryStore } from '@/stores/library'

const lib = useLibraryStore()
const selected = ref<string[]>([])
const file = computed(() => lib.files.find((f) => f.fileId === selected.value[0]))
const form = reactive({
  format: 'mp3' as 'mp3' | 'aac' | 'm4a' | 'wav' | 'flac',
  bitrateKbps: 192,
  sampleRate: '' as '' | number,
  channels: '' as '' | number,
})
const lossy = computed(() => ['mp3', 'aac', 'm4a'].includes(form.format))
const noAudio = computed(() => file.value?.info && file.value.info.hasAudio === false)
const { busy, error, run } = useTaskSubmit()

function submit() {
  const body: Record<string, unknown> = { uniqueFilename: selected.value[0], targetFormat: form.format }
  if (lossy.value) body.bitrateKbps = form.bitrateKbps
  if (form.sampleRate !== '') body.sampleRate = Number(form.sampleRate)
  if (form.channels !== '') body.channels = Number(form.channels)
  void run(() => mediaApi.extractAudio(body), '音频提取已提交')
}
</script>

<template>
  <ToolShell title="音频提取" subtitle="从视频（或音频）中取出音轨并转为所需格式。无损格式（WAV / FLAC）不需要设置码率。"
             feature="audio.extract" tools="FFmpeg" :busy="busy" :error="error" :can-submit="!!selected[0] && !noAudio" @submit="submit">
    <MediaPicker v-model="selected" :kinds="['VIDEO', 'AUDIO']" label="源文件" accept="video/*,audio/*" />
    <p v-if="noAudio" class="warn" role="note">该文件没有音轨，无法提取音频。</p>
    <Segmented v-model="form.format" name="afmt" label="输出格式"
               :options="[{ value: 'mp3', label: 'MP3' }, { value: 'aac', label: 'AAC' }, { value: 'm4a', label: 'M4A' }, { value: 'wav', label: 'WAV' }, { value: 'flac', label: 'FLAC' }]" />
    <div class="grid">
      <div class="field">
        <label for="abk">码率</label>
        <select id="abk" v-model.number="form.bitrateKbps" class="select" :disabled="!lossy">
          <option v-for="b in [96, 128, 192, 256, 320]" :key="b" :value="b">{{ b }} kbps</option>
        </select>
        <span v-if="!lossy" class="hint">无损格式无需码率</span>
      </div>
      <div class="field">
        <label for="asr">采样率</label>
        <select id="asr" v-model="form.sampleRate" class="select">
          <option value="">保持原始</option>
          <option v-for="r in [22050, 32000, 44100, 48000, 96000]" :key="r" :value="r">{{ r / 1000 }} kHz</option>
        </select>
      </div>
      <div class="field">
        <label for="ach">声道</label>
        <select id="ach" v-model="form.channels" class="select">
          <option value="">保持原始</option>
          <option :value="2">立体声</option>
          <option :value="1">单声道</option>
        </select>
      </div>
    </div>
  </ToolShell>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--sp-4);
}
.warn {
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--apricot);
  color: var(--apricot-ink);
  font-size: var(--fs-sm);
}
</style>
