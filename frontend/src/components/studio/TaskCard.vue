<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TaskView } from '@/api/types'
import Icon from '@/components/common/Icon.vue'
import { useTasksStore, isTerminal } from '@/stores/tasks'
import { useToast } from '@/composables/useToast'
import { downloadFile } from '@/utils/download'
import { bytes, elapsed, relativeTime } from '@/utils/format'
import { STATUS_LABEL, TASK_LABEL, stageLabel } from '@/utils/labels'

/** One compact task row in the tray; details and actions open on click. */
const props = defineProps<{ task: TaskView }>()
const emit = defineEmits<{ preview: [task: TaskView] }>()
const tasks = useTasksStore()
const toast = useToast()
const acting = ref(false)
const open = ref(false)

const r = computed(() => props.task.result ?? {})
/** Human summary built only from fields the backend measured. */
const facts = computed(() => {
  const out: { k: string; v: string; tone?: string }[] = []
  const res = r.value
  const before = res.originalSize ?? res.inputSize
  const after = res.outputSize
  if (before && after) {
    const saved = Math.round((1 - after / before) * 1000) / 10
    out.push({ k: '体积', v: `${bytes(before)} → ${bytes(after)}${saved > 0 ? `（-${saved}%）` : ''}` })
  } else if (after) {
    out.push({ k: '输出', v: bytes(after) })
  }
  if (res.targetSizeMB) out.push({ k: '目标', v: `${res.targetSizeMB} MiB` })
  if (res.overall === 'PARTIAL_SUCCESS') {
    // batch tasks report succeeded/count; the spec delivery package reports complete/people + exceptions
    const detail = res.count != null ? `${res.succeeded}/${res.count}` : res.people != null ? `${res.complete}/${res.people} 人齐全，${res.exceptionCount} 项例外` : ''
    out.push({ k: '结果', v: detail ? `部分成功（${detail}）` : '部分成功', tone: 'warn' })
  }
  if (res.verification) {
    const v = res.verification
    const bad = (v.checks ?? []).filter((c: any) => !c.ok).length
    out.push(v.status === 'VERIFIED'
      ? { k: '核验', v: `${v.checks.length} 项全部通过`, tone: 'ok' }
      : { k: '核验', v: `${bad} 项未通过`, tone: 'warn' })
  }
  if (Array.isArray(res.warnings) && res.warnings.length) out.push({ k: '提示', v: `${res.warnings.length} 条（查看结果）`, tone: 'warn' })
  if (res.retry) out.push({ k: '自动调整', v: `码率 ${res.retry.fromKbps} → ${res.retry.toKbps} kbps` })
  if (res.codec) out.push({ k: '编码', v: String(res.codec).toUpperCase() })
  if (res.count) out.push({ k: '数量', v: `${res.count} 个文件` })
  if (res.pages) out.push({ k: '页数', v: `${res.pages} 页` })
  if (res.allTargetsMet === false) out.push({ k: '目标体积', v: '部分文件未达到', tone: 'warn' })
  if (res.allTargetsMet === true) out.push({ k: '目标体积', v: '全部达到', tone: 'ok' })
  if (res.verified === true) out.push({ k: '核验', v: '输出中无位置标签', tone: 'ok' })
  if (res.verified === false) out.push({ k: '核验', v: `仍有 ${res.remainingLocationTags?.length ?? '?'} 个位置标签`, tone: 'warn' })
  if (Array.isArray(res.writtenFields) && res.writtenFields.length) out.push({ k: '写入', v: res.writtenFields.join('、') })
  if (props.task.startedAt && isTerminal(props.task)) out.push({ k: '耗时', v: elapsed(props.task.startedAt, props.task.finishedAt) })
  return out
})

const tone = computed(() => ({ SUCCEEDED: 'ok', FAILED: 'bad', CANCELLED: 'muted', QUEUED: 'wait' } as Record<string, string>)[props.task.status] ?? 'run')
const running = computed(() => !isTerminal(props.task))
// queued tasks have no real progress yet: show an indeterminate bar rather than a fake number
const indeterminate = computed(() => props.task.status === 'QUEUED' || (props.task.status === 'RUNNING' && props.task.progress === 0))
/** second line: what is happening now, or what came out */
const sub = computed(() => {
  const t = props.task
  if (t.status === 'RUNNING') return t.stage ? stageLabel(t.stage) : STATUS_LABEL[t.status]
  if (t.status === 'QUEUED') return '排队中'
  if (t.status === 'FAILED' || t.status === 'CANCELLED') return t.message || STATUS_LABEL[t.status]
  return t.output?.filename ?? t.inputs?.[0]?.filename ?? STATUS_LABEL[t.status]
})
/** right-hand figure, like the size column of a file list */
const figure = computed(() => {
  const t = props.task
  if (t.status === 'RUNNING' && !indeterminate.value) return `${t.progress}%`
  if (t.status === 'SUCCEEDED' && r.value.outputSize) return bytes(r.value.outputSize)
  return ''
})

const canPreview = computed(() => props.task.status === 'SUCCEEDED' && !!props.task.output && props.task.output.mediaType !== 'ARCHIVE')
const hasItems = computed(() => Array.isArray(r.value.items) && r.value.items.length > 0)

async function act(fn: () => Promise<unknown>, okText?: string) {
  acting.value = true
  try {
    await fn()
    if (okText) toast.ok(okText)
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    acting.value = false
  }
}

function removeHistory() {
  if (!isTerminal(props.task) || acting.value) return
  if (!window.confirm('删除这条历史记录？原文件和处理结果仍保留在文件库，进行中的任务不受影响。')) return
  void act(() => tasks.remove(props.task.taskId), '历史记录已删除')
}
</script>

<template>
  <li class="tc" :class="[`t-${tone}`, { open }]">
    <button type="button" class="row" :aria-expanded="open" :aria-label="`${TASK_LABEL[task.type] ?? task.type}，${STATUS_LABEL[task.status]}`" @click="open = !open">
      <span class="si" aria-hidden="true">
        <span v-if="running" class="spin" />
        <Icon v-else-if="task.status === 'SUCCEEDED'" name="check" :size="14" />
        <Icon v-else-if="task.status === 'FAILED'" name="x" :size="14" />
        <span v-else class="dash" />
      </span>
      <span class="l1">
        <span class="type">{{ TASK_LABEL[task.type] ?? task.type }}</span>
        <span v-if="task.origin === 'AI'" class="tag">AI</span>
        <span v-if="task.attempt > 1" class="tag">第 {{ task.attempt }} 次</span>
        <span class="fig mono">{{ figure || relativeTime(task.createdAt) }}</span>
      </span>
      <span class="sub" :title="sub">{{ sub }}</span>
    </button>

    <div v-if="running" class="bar" role="progressbar" :aria-valuenow="indeterminate ? undefined : task.progress" aria-valuemin="0" aria-valuemax="100" :aria-label="STATUS_LABEL[task.status]">
      <span v-if="indeterminate" class="indet" />
      <span v-else class="fill" :style="{ transform: `scaleX(${task.progress / 100})` }" />
    </div>

    <div v-if="open" class="more">
      <dl v-if="facts.length" class="facts">
        <div v-for="f in facts" :key="f.k" :class="f.tone">
          <dt>{{ f.k }}</dt>
          <dd>{{ f.v }}</dd>
        </div>
      </dl>
      <p v-if="task.errorCode" class="code mono">{{ task.errorCode }}</p>
      <p class="when">{{ relativeTime(task.createdAt) }}创建</p>
      <div class="acts">
        <button v-if="canPreview || hasItems" type="button" class="act" @click="emit('preview', task)">
          <Icon name="eye" :size="13" />{{ hasItems ? '查看对比' : '预览' }}
        </button>
        <button v-if="task.status === 'SUCCEEDED' && task.output" type="button" class="act act--primary" :disabled="acting"
                @click="act(() => downloadFile(task.output!.fileId, task.output!.filename))">
          <Icon name="download" :size="13" />下载
        </button>
        <button v-if="running" type="button" class="act act--danger" :disabled="acting"
                @click="act(() => tasks.cancel(task.taskId), '已请求取消')">
          <Icon name="stop" :size="13" />取消
        </button>
        <button v-if="task.status === 'FAILED' || task.status === 'CANCELLED'" type="button" class="act"
                :disabled="acting || task.errorCode === 'TARGET_UNREACHABLE'"
                :title="task.errorCode === 'TARGET_UNREACHABLE' ? '参数本身无法实现，请调整后重新提交' : ''"
                @click="act(() => tasks.retry(task.taskId), '已重新排队')">
          <Icon name="retry" :size="13" />重试
        </button>
        <button v-if="isTerminal(task)" type="button" class="act act--danger" :disabled="acting" @click="removeHistory">
          <Icon name="trash" :size="13" />删除记录
        </button>
      </div>
    </div>
  </li>
</template>

<style scoped>
.tc {
  display: grid;
  border-radius: 6px;
}
.row {
  display: grid;
  grid-template-columns: 16px minmax(0, 1fr);
  align-items: center;
  column-gap: 8px;
  row-gap: 2px;
  width: 100%;
  padding: 7px 8px;
  border: 0;
  border-radius: 6px;
  background: none;
  color: var(--text);
  text-align: left;
  cursor: pointer;
  transition: background 120ms;
}
.row:hover,
.open .row {
  background: rgb(255 255 255 / 0.04);
}
.si {
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
}
.t-ok .si { color: var(--ok); }
.t-bad .si { color: var(--danger); }
.t-muted .si { color: var(--text-3); }
.spin {
  width: 12px;
  height: 12px;
  border: 1.5px solid var(--line-strong);
  border-top-color: var(--fog-ink);
  border-radius: 50%;
  animation: spin 0.9s linear infinite;
}
.t-wait .spin { border-top-color: var(--text-3); }
.dash { width: 8px; height: 1.5px; border-radius: 1px; background: currentColor; }
.l1 {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.type {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
  white-space: nowrap;
}
.tag {
  padding: 0 5px;
  border: 1px solid var(--line-strong);
  border-radius: 4px;
  font-size: 10px;
  line-height: 16px;
  color: var(--text-2);
}
.fig {
  margin-left: auto;
  padding-left: 8px;
  font-size: 11px;
  color: var(--text-3);
  white-space: nowrap;
}
.sub {
  grid-column: 2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--text-3);
}
.t-bad .sub { color: var(--danger); }
.bar {
  position: relative;
  height: 3px;
  margin: 0 8px 6px 32px;
  border-radius: 999px;
  overflow: hidden;
  background: rgb(255 255 255 / 0.08);
}
.fill {
  position: absolute;
  inset: 0;
  transform-origin: left;
  background: var(--fog-ink);
  transition: transform 300ms ease-out;
}
.indet {
  position: absolute;
  inset: 0 auto 0 0;
  width: 35%;
  border-radius: 999px;
  background: var(--fog-ink);
  animation: indet 1.3s ease-in-out infinite;
}
.more {
  display: grid;
  gap: 8px;
  padding: 4px 8px 10px 32px;
}
.facts {
  display: grid;
  gap: 4px;
  margin: 0;
  font-size: 12px;
}
.facts div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.facts dt {
  flex: none;
  color: var(--text-3);
}
.facts dd {
  margin: 0;
  text-align: right;
  overflow-wrap: anywhere;
  color: var(--text-2);
}
.facts .ok dd { color: var(--ok); }
.facts .warn dd { color: var(--warn); }
.code {
  font-size: 11px;
  color: var(--text-3);
}
.when {
  font-size: 11px;
  color: var(--text-3);
}
.acts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.acts:empty {
  display: none;
}
.act {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 9px;
  border: 1px solid var(--line-strong);
  border-radius: 6px;
  background: transparent;
  font-size: 12px;
  color: var(--text);
  cursor: pointer;
  transition: background 120ms;
}
.act:hover:not(:disabled) { background: rgb(255 255 255 / 0.06); }
.act:disabled { opacity: 0.45; cursor: not-allowed; }
.act--primary { border-color: transparent; background: var(--ink); color: var(--cream); }
.act--primary:hover:not(:disabled) { background: #e5e5e5; }
.act--danger { color: var(--danger); }
@keyframes spin { to { transform: rotate(360deg); } }
@keyframes indet {
  from { transform: translateX(-100%); }
  to { transform: translateX(300%); }
}
@media (prefers-reduced-motion: reduce) {
  .spin, .indet { animation: none; }
}
</style>
