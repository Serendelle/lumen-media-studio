<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Plan } from '@/api/ai'
import { useAiStore } from '@/stores/ai'
import { useLibraryStore } from '@/stores/library'
import { useToast } from '@/composables/useToast'
import { TASK_LABEL } from '@/utils/labels'
import type { TaskType } from '@/api/types'

const props = defineProps<{ plan: Plan }>()
const ai = useAiStore()
const lib = useLibraryStore()
const toast = useToast()
const acting = ref(false)

// only the latest version, while the server is waiting for it, can be confirmed
const actionable = computed(() => ai.state === 'AWAITING_CONFIRMATION' && ai.current?.planVersion === props.plan.version)
const impactLabel = { none: '不影响画质', low: '轻微影响', medium: '有损压缩', high: '明显影响' } as const

const PARAM_LABEL: Record<string, string> = {
  mode: '方式', targetSizeMB: '目标体积 MB', crf: 'CRF', videoBitrateKbps: '视频码率 kbps', codec: '编码', resolution: '分辨率',
  fps: '帧率', audioBitrateKbps: '音频码率 kbps', preset: '速度', format: '格式', bitrateKbps: '码率 kbps', sampleRate: '采样率',
  channels: '声道', targetFormat: '目标格式', tool: '操作', quality: '质量', targetSizeKB: '目标 KB', width: '宽', height: '高',
  maxWidth: '最大宽度', position: '位置', opacity: '透明度', margin: '边距', density: 'DPI', title: '标题', artist: '作者',
  comment: '描述', copyright: '版权', make: '厂商', model: '型号', dateTimeOriginal: '拍摄时间', gpsLatitude: '纬度',
  gpsLongitude: '经度', clearGps: '清除 GPS',
}
const VALUE_LABEL: Record<string, string> = { size: '按体积', crf: '恒定质量', bitrate: '指定码率', location: '仅位置信息', all: '全部元数据', original: '保持原始' }

function inputLabel(ref: string) {
  if (ref.startsWith('step:')) return `第 ${ref.slice(5)} 步的结果`
  return lib.files.find((f) => f.fileId === ref)?.originalFilename ?? '原文件'
}
function fmt(v: unknown) {
  if (typeof v === 'boolean') return v ? '是' : '否'
  return VALUE_LABEL[String(v)] ?? String(v)
}

async function decide(approve: boolean) {
  acting.value = true
  try {
    await ai.confirm(approve)
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    acting.value = false
  }
}
</script>

<template>
  <article class="plan card" :class="{ live: actionable }" aria-label="执行计划">
    <header class="head">
      <span class="eyebrow">Plan · v{{ plan.version }}</span>
      <h3>执行计划</h3>
      <p class="summary">{{ plan.summary }}</p>
    </header>
    <ol class="steps">
      <li v-for="s in plan.steps" :key="s.id" class="step">
        <span class="n mono">{{ String(s.id).padStart(2, '0') }}</span>
        <div class="body">
          <div class="row">
            <strong>{{ s.title }}</strong>
            <span class="chip" :class="{ 'chip--apricot': s.qualityImpact === 'medium' || s.qualityImpact === 'high', 'chip--mint': s.qualityImpact === 'none' }">
              {{ impactLabel[s.qualityImpact] }}
            </span>
          </div>
          <p class="meta">{{ TASK_LABEL[s.tool as TaskType] ?? s.tool }} · 输入：{{ s.inputs.map(inputLabel).join('、') }}</p>
          <dl v-if="Object.keys(s.params).length" class="params">
            <div v-for="(v, k) in s.params" :key="k"><dt>{{ PARAM_LABEL[k] ?? k }}</dt><dd class="mono">{{ fmt(v) }}</dd></div>
          </dl>
          <p v-if="s.estimate" class="est mono">评估：约 {{ s.estimate.videoKbps }} kbps · {{ s.estimate.height }}p · {{ s.estimate.feasible ? '可实现' : '不可实现' }}</p>
          <p v-if="s.rationale" class="why">{{ s.rationale }}</p>
        </div>
      </li>
    </ol>
    <p v-if="plan.constraintsText" class="cons">
      <strong>你的硬性要求</strong>{{ plan.constraintsText }}<span class="hint">完成后会逐条核验最终交给你的文件</span>
    </p>
    <ul v-if="plan.warnings?.length" class="warnings">
      <li v-for="w in plan.warnings" :key="w">{{ w }}</li>
    </ul>
    <footer class="acts">
      <template v-if="actionable">
        <p class="note">确认后才会开始处理。原文件不会被修改，所有结果都是新文件。</p>
        <button type="button" class="btn btn--ghost btn--sm" :disabled="acting" @click="decide(false)">不用了</button>
        <button type="button" class="btn" :disabled="acting" @click="decide(true)">确认并开始处理</button>
      </template>
      <p v-else class="note">{{ ai.current && ai.current.planVersion > plan.version ? '已被新版本计划取代' : '此计划已处理' }}</p>
    </footer>
  </article>
</template>

<style scoped>
.plan {
  display: grid;
  gap: var(--sp-4);
  padding: var(--sp-5);
  border-radius: var(--r-lg);
}
.plan.live {
  border-color: var(--line-strong);
  box-shadow: 0 0 0 4px var(--accent-soft), var(--shadow-2);
}
.head h3 {
  margin-top: 4px;
  font-size: var(--fs-lg);
}
.summary {
  margin-top: 4px;
  color: var(--text-2);
  font-size: var(--fs-sm);
}
.steps {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 10px;
}
.step {
  display: grid;
  grid-template-columns: 30px 1fr;
  gap: 10px;
  padding: 12px;
  border-radius: var(--r-md);
  background: var(--surface-sunk);
}
.n {
  font-size: var(--fs-sm);
  color: var(--text-3);
  padding-top: 1px;
}
.body {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  justify-content: space-between;
}
.row strong {
  font-weight: 500;
  font-size: var(--fs-sm);
}
.meta,
.why,
.est {
  font-size: var(--fs-xs);
  color: var(--text-2);
}
.why {
  color: var(--text-3);
}
.params {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.params div {
  display: inline-flex;
  gap: 6px;
  padding: 2px 8px;
  border-radius: 6px;
  background: var(--surface);
  font-size: 11px;
}
.params dt {
  color: var(--text-3);
}
.params dd {
  margin: 0;
}
.cons {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--fog);
  color: var(--fog-ink);
  font-size: var(--fs-xs);
  line-height: 1.6;
}
.cons .hint {
  opacity: 0.8;
}
.warnings {
  margin: 0;
  padding: 10px 14px 10px 28px;
  border-radius: var(--r-md);
  background: var(--apricot);
  color: var(--apricot-ink);
  font-size: var(--fs-xs);
  display: grid;
  gap: 4px;
}
.acts {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}
.note {
  margin-right: auto;
  font-size: var(--fs-xs);
  color: var(--text-3);
}
</style>
