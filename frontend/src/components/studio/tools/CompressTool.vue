<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import ToolShell from '../ToolShell.vue'
import MediaPicker from '../MediaPicker.vue'
import Segmented from '../Segmented.vue'
import { mediaApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { CompressionPlan } from '@/api/types'
import { useTaskSubmit } from '@/composables/useTaskSubmit'
import { useLibraryStore } from '@/stores/library'
import { bytes, duration, kbps } from '@/utils/format'

const lib = useLibraryStore()
const selected = ref<string[]>([])
const file = computed(() => lib.files.find((f) => f.fileId === selected.value[0]))

const form = reactive({
  mode: 'size' as 'crf' | 'size' | 'kbps',
  crf: 23,
  targetSizeMB: 20,
  videoBitrateKbps: 2500,
  resolution: 'original',
  codec: 'h264' as 'h264' | 'h265',
  fps: '' as '' | number,
  audioBitrateKbps: 128,
  keepAudio: true,
  preset: 'medium',
})

const resolutions = [
  { value: 'original', label: '保持原始' },
  { value: '1080p', label: '1080p' },
  { value: '720p', label: '720p' },
  { value: '540p', label: '540p' },
  { value: '480p', label: '480p' },
  { value: '360p', label: '360p' },
]

// presets above the source height are a no-op on the server; hide them so the UI never implies upscaling
const resOptions = computed(() => {
  const h = file.value?.info?.height ?? 0
  return resolutions.filter((r) => r.value === 'original' || !h || parseInt(r.value) < h)
})

// sensible default for a newly picked video: ~40% of its size, rounded to 0.5 MiB
watch(
  () => file.value?.fileId,
  () => {
    const s = file.value?.size
    if (s) form.targetSizeMB = Math.max(0.5, Math.round((s * 0.4) / 1048576 * 2) / 2)
  },
)

const { busy, error, fields, run } = useTaskSubmit()

function body() {
  const b: Record<string, unknown> = {
    uniqueFilename: selected.value[0],
    codec: form.codec,
    resolution: form.resolution,
    preset: form.preset,
    audioBitrateKbps: form.keepAudio ? form.audioBitrateKbps : 0,
  }
  if (form.fps !== '') b.fps = Number(form.fps)
  if (form.mode === 'crf') Object.assign(b, { mode: 'crf', crfValue: form.crf })
  if (form.mode === 'size') Object.assign(b, { mode: 'bitrate', targetSizeMB: form.targetSizeMB })
  if (form.mode === 'kbps') Object.assign(b, { mode: 'kbps', videoBitrateKbps: form.videoBitrateKbps })
  return b
}

// ---- feasibility estimate (size mode) ----
const plan = ref<CompressionPlan | null>(null)
const estimating = ref(false)
const estimateError = ref<string | null>(null)
let estimateTimer: number | undefined

watch(
  () => [selected.value[0], form.mode, form.targetSizeMB, form.resolution, form.codec, form.keepAudio, form.audioBitrateKbps, form.fps],
  () => {
    plan.value = null
    estimateError.value = null
    window.clearTimeout(estimateTimer)
    if (form.mode !== 'size' || !selected.value[0] || !(form.targetSizeMB > 0)) return
    estimateTimer = window.setTimeout(estimate, 450)
  },
)

async function estimate() {
  estimating.value = true
  try {
    plan.value = await mediaApi.estimateCompression(body())
  } catch (e) {
    estimateError.value = e instanceof ApiError ? e.message : '评估失败'
  } finally {
    estimating.value = false
  }
}

function applySuggestion(height: number) {
  form.resolution = `${height}p`
}

const sizeHint = computed(() => {
  const s = file.value?.size
  if (!s) return ''
  const ratio = (form.targetSizeMB * 1024 * 1024) / s
  const rel = ratio >= 1 ? '目标不小于原文件，压缩意义不大' : `约为原文件的 ${Math.round(ratio * 100)}%`
  return `${rel} · 1 MiB = 1,048,576 字节，完成后按实际字节数核验`
})

// size mode: only a completed estimate that says "feasible" unlocks submission (pending or failed estimates do not)
const canSubmit = computed(() => !!selected.value[0] && (form.mode !== 'size' || (plan.value?.feasible === true && !estimating.value)))

function submit() {
  void run(() => mediaApi.compressVideo(body()), '压缩任务已提交')
}
</script>

<template>
  <ToolShell icon="compress" kicker="Video · Compress"
    title="视频压缩"
    subtitle="按目标体积（两遍编码，精确命中）、恒定质量（CRF）或指定码率压缩，可同时调整分辨率、编码器、帧率与音频。"
    feature="video.compress"
    tools="FFmpeg"
    :busy="busy"
    :can-submit="canSubmit"
    :error="error"
    submit-label="开始压缩"
    @submit="submit"
  >
    <MediaPicker v-model="selected" :kinds="['VIDEO']" label="源视频" accept="video/*" />

    <div v-if="file?.info" class="facts" aria-label="源视频信息（服务端 ffprobe）">
      <div><span>分辨率</span><b class="mono">{{ file.info.width }}×{{ file.info.height }}</b></div>
      <div><span>时长</span><b class="mono">{{ duration(file.info.duration) }}</b></div>
      <div><span>体积</span><b class="mono">{{ bytes(file.size) }}</b></div>
      <div><span>码率</span><b class="mono">{{ kbps(file.info.bitrate) }}</b></div>
      <div><span>编码</span><b class="mono">{{ file.info.videoCodec }}{{ file.info.audioCodec ? ' / ' + file.info.audioCodec : '' }}</b></div>
      <div><span>帧率</span><b class="mono">{{ file.info.fps }} fps</b></div>
    </div>

    <Segmented
      v-model="form.mode"
      name="cmode"
      label="压缩方式"
      :options="[
        { value: 'size', label: '目标体积', hint: '两遍编码，尽量精确命中体积' },
        { value: 'crf', label: '恒定质量 CRF', hint: '画质优先，体积不固定' },
        { value: 'kbps', label: '指定码率', hint: '直接设定视频码率' },
      ]"
    />

    <div class="grid">
      <div v-if="form.mode === 'size'" class="field">
        <label for="tsize">目标体积（MiB）</label>
        <input id="tsize" v-model.number="form.targetSizeMB" class="input mono" type="number" min="0.1" step="0.5"
               :aria-invalid="!!fields.targetSizeMB" />
        <span class="hint">{{ sizeHint }}</span>
      </div>
      <div v-if="form.mode === 'crf'" class="field">
        <label for="crf">CRF <span class="mono">{{ form.crf }}</span></label>
        <input id="crf" v-model.number="form.crf" type="range" min="16" max="36" step="1" />
        <span class="hint">数值越小画质越好、体积越大；常用 20–28</span>
      </div>
      <div v-if="form.mode === 'kbps'" class="field">
        <label for="vkbps">视频码率（kbps）</label>
        <input id="vkbps" v-model.number="form.videoBitrateKbps" class="input mono" type="number" min="50" max="200000" step="100" />
      </div>
      <div class="field">
        <label for="codec">编码器</label>
        <select id="codec" v-model="form.codec" class="select">
          <option value="h264">H.264（兼容性最好）</option>
          <option value="h265">H.265 / HEVC（同画质更小）</option>
        </select>
      </div>
      <div class="field">
        <label for="res">分辨率</label>
        <select id="res" v-model="form.resolution" class="select">
          <option v-for="r in resOptions" :key="r.value" :value="r.value">{{ r.label }}</option>
        </select>
        <span class="hint">只缩小、不放大</span>
      </div>
      <div class="field">
        <label for="fps">帧率</label>
        <select id="fps" v-model="form.fps" class="select">
          <option value="">保持原始</option>
          <option :value="60">60 fps</option>
          <option :value="30">30 fps</option>
          <option :value="25">25 fps</option>
          <option :value="24">24 fps</option>
        </select>
      </div>
      <div class="field">
        <label for="abr">音频</label>
        <select id="abr" v-model.number="form.audioBitrateKbps" class="select" :disabled="!form.keepAudio">
          <option :value="64">AAC 64 kbps</option>
          <option :value="96">AAC 96 kbps</option>
          <option :value="128">AAC 128 kbps</option>
          <option :value="192">AAC 192 kbps</option>
        </select>
        <label class="check"><input v-model="form.keepAudio" type="checkbox" /> 保留音频</label>
      </div>
      <div class="field">
        <label for="preset">编码速度</label>
        <select id="preset" v-model="form.preset" class="select">
          <option value="veryfast">更快（体积略大）</option>
          <option value="medium">均衡</option>
          <option value="slow">更慢（同码率画质更好）</option>
        </select>
      </div>
    </div>

    <div v-if="form.mode === 'size' && selected[0]" class="plan" :class="{ bad: (plan && !plan.feasible) || !!estimateError }" aria-live="polite">
      <template v-if="estimating">正在评估目标是否可实现…</template>
      <template v-else-if="estimateError">
        <strong>评估失败，暂不能提交</strong>
        <span>{{ estimateError }}</span>
        <button type="button" class="btn btn--sm btn--soft" @click="estimate">重新评估</button>
      </template>
      <template v-else-if="plan?.feasible">
        <strong>可以实现</strong>
        <span>预计视频码率约 <b class="mono">{{ plan.videoKbps }} kbps</b>，输出高度 {{ plan.height }}p。</span>
      </template>
      <template v-else-if="plan">
        <strong>这个目标无法以可接受的画质实现</strong>
        <span>{{ plan.reason }}</span>
        <ul class="sugg">
          <li v-for="s in plan.suggestions" :key="s.note">
            {{ s.note }}
            <button v-if="s.height < (file?.info?.height ?? 0)" type="button" class="btn btn--sm btn--soft" @click="applySuggestion(s.height)">
              改为 {{ s.height }}p
            </button>
          </li>
        </ul>
      </template>
    </div>
  </ToolShell>
</template>

<style scoped>
.facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr)); /* 6 facts always fill complete rows */
  gap: 1px;
  border-radius: var(--r-md);
  overflow: hidden;
  border: 1px solid var(--line);
  background: var(--line);
}
.facts div {
  display: grid;
  gap: 2px;
  padding: 10px 12px;
  background: var(--surface);
}
.facts span {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.facts b {
  font-weight: 400;
  font-size: var(--fs-sm);
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--sp-4);
}
@media (max-width: 520px) {
  .facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--text-2);
}
input[type='range'] {
  accent-color: var(--mint-ink);
}
.plan {
  display: grid;
  gap: 6px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--mint);
  color: var(--ink);
  font-size: var(--fs-sm);
}
.plan.bad {
  background: var(--apricot);
}
.plan b {
  font-weight: 500;
}
.sugg {
  margin: 4px 0 0;
  padding-left: 18px;
  display: grid;
  gap: 6px;
}
.sugg li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
</style>
