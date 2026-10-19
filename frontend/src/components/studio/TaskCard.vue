<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TaskView } from '@/api/types'
import TaskProgress from '@/components/common/TaskProgress.vue'
import { useTasksStore, isTerminal } from '@/stores/tasks'
import { useToast } from '@/composables/useToast'
import { downloadFile } from '@/utils/download'
import { bytes, elapsed, relativeTime } from '@/utils/format'
import { TASK_LABEL } from '@/utils/labels'

const props = defineProps<{ task: TaskView }>()
const emit = defineEmits<{ preview: [task: TaskView] }>()
const tasks = useTasksStore()
const toast = useToast()
const acting = ref(false)

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
  if (res.overall === 'PARTIAL_SUCCESS') out.push({ k: '结果', v: `部分成功（${res.succeeded}/${res.count}）`, tone: 'warn' })
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
  return out
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
</script>

<template>
  <article class="tc" :class="`s-${task.status.toLowerCase()}`" :aria-label="`${TASK_LABEL[task.type]}，${task.status}`">
    <header class="top">
      <span class="type">{{ TASK_LABEL[task.type] ?? task.type }}</span>
      <span v-if="task.origin === 'AI'" class="chip chip--mint">AI</span>
      <span v-if="task.attempt > 1" class="chip">第 {{ task.attempt }} 次</span>
      <time class="when">{{ relativeTime(task.createdAt) }}</time>
    </header>

    <p v-if="task.output" class="name" :title="task.output.filename">{{ task.output.filename }}</p>
    <TaskProgress :task="task" />

    <p v-if="task.status === 'FAILED' || task.status === 'CANCELLED'" class="msg" :class="{ bad: task.status === 'FAILED' }">
      {{ task.message }}<span v-if="task.errorCode" class="mono code">{{ task.errorCode }}</span>
    </p>

    <dl v-if="task.status === 'SUCCEEDED' && facts.length" class="facts">
      <div v-for="f in facts" :key="f.k" :class="f.tone">
        <dt>{{ f.k }}</dt>
        <dd>{{ f.v }}</dd>
      </div>
      <div v-if="task.startedAt"><dt>耗时</dt><dd class="mono">{{ elapsed(task.startedAt, task.finishedAt) }}</dd></div>
    </dl>

    <footer class="acts">
      <button v-if="canPreview || hasItems" type="button" class="btn btn--sm btn--ghost" @click="emit('preview', task)">
        {{ hasItems ? '查看对比' : '预览' }}
      </button>
      <button v-if="task.status === 'SUCCEEDED' && task.output" type="button" class="btn btn--sm"
              :disabled="acting" @click="act(() => downloadFile(task.output!.fileId, task.output!.filename))">下载</button>
      <button v-if="!isTerminal(task)" type="button" class="btn btn--sm btn--danger" :disabled="acting"
              @click="act(() => tasks.cancel(task.taskId), '已请求取消')">取消</button>
      <button v-if="task.status === 'FAILED' || task.status === 'CANCELLED'" type="button" class="btn btn--sm btn--ghost"
              :disabled="acting || task.errorCode === 'TARGET_UNREACHABLE'"
              :title="task.errorCode === 'TARGET_UNREACHABLE' ? '参数本身无法实现，请调整后重新提交' : ''"
              @click="act(() => tasks.retry(task.taskId), '已重新排队')">重试</button>
    </footer>
  </article>
</template>

<style scoped>
.tc {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--surface);
}
.s-running,
.s-queued {
  border-color: rgba(70, 103, 125, 0.35);
}
.top {
  display: flex;
  align-items: center;
  gap: 6px;
}
.type {
  font-size: var(--fs-sm);
  font-weight: 500;
}
.when {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.name {
  font-size: var(--fs-xs);
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.msg {
  font-size: var(--fs-xs);
  color: var(--text-2);
  overflow-wrap: anywhere;
}
.msg.bad {
  color: var(--danger);
}
.code {
  margin-left: 6px;
  opacity: 0.7;
}
.facts {
  margin: 0;
  display: grid;
  gap: 3px;
  font-size: var(--fs-xs);
}
.facts div {
  display: flex;
  gap: 8px;
}
.facts dt {
  flex: none;
  width: 56px;
  color: var(--text-3);
}
.facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.facts .ok dd {
  color: var(--ok);
}
.facts .warn dd {
  color: var(--warn);
}
.acts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.acts:empty {
  display: none;
}
</style>
