<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import TaskProgress from '@/components/common/TaskProgress.vue'
import ResultPreview from '@/components/studio/ResultPreview.vue'
import { tasksApi } from '@/api/endpoints'
import type { TaskStatus, TaskView } from '@/api/types'
import { isTerminal, useTasksStore } from '@/stores/tasks'
import { useToast } from '@/composables/useToast'
import { downloadFile } from '@/utils/download'
import { elapsed } from '@/utils/format'
import { ERROR_HINT, STATUS_LABEL, TASK_LABEL, stageLabel } from '@/utils/labels'

const store = useTasksStore()
const toast = useToast()

const FILTERS: { k: string; l: string; s?: TaskStatus[] }[] = [
  { k: 'all', l: '全部' },
  { k: 'active', l: '进行中', s: ['QUEUED', 'RUNNING'] },
  { k: 'failed', l: '失败', s: ['FAILED'] },
  { k: 'done', l: '已完成', s: ['SUCCEEDED'] },
  { k: 'cancelled', l: '已取消', s: ['CANCELLED'] },
]
const PAGE = 20
const filter = ref('all')
const page = ref(0)
const ids = ref<string[]>([])
const fetched = ref<Record<string, TaskView>>({})
const total = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)
const previewing = ref<TaskView | null>(null)
const acting = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    const p = await tasksApi.list(page.value, PAGE, FILTERS.find((f) => f.k === filter.value)?.s)
    fetched.value = Object.fromEntries(p.content.map((t) => [t.taskId, t]))
    ids.value = p.content.map((t) => t.taskId)
    total.value = p.totalElements
    totalPages.value = p.totalPages
    error.value = null
    // live progress for unfinished rows comes from the shared store's SSE subscriptions
    for (const t of p.content) if (!isTerminal(t)) void store.track(t.taskId)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(filter, () => {
  page.value = 0
  void load()
})
watch(page, load)

/** Prefer the live copy held by the store (updated over SSE) over the snapshot from the list request. */
const rows = computed(() => ids.value.map((id) => store.byId[id] ?? fetched.value[id]).filter(Boolean))

function inputNames(t: TaskView) {
  if (!t.inputs?.length) return '—'
  const names = t.inputs.map((i) => i.filename ?? '（已过期的文件）')
  return names.length > 2 ? `${names.slice(0, 2).join('、')} 等 ${names.length} 个` : names.join('、')
}
function startTime(t: TaskView) {
  return new Date(t.startedAt ?? t.createdAt).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

async function act(id: string, fn: () => Promise<unknown>, ok?: string) {
  acting.value = id
  try {
    await fn()
    if (ok) toast.ok(ok)
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    acting.value = null
  }
}

async function copyTrace(id: string) {
  try {
    await navigator.clipboard.writeText(id)
    toast.ok('追踪号已复制')
  } catch {
    toast.error('无法访问剪贴板')
  }
}
</script>

<template>
  <main id="main" class="tc-page">
    <header class="head">
      <div>
        <h1>任务中心</h1>
        <p>专业模式与智能模式提交的全部任务。进度实时更新，刷新页面不会丢失。</p>
      </div>
      <div class="filters" role="tablist" aria-label="按状态筛选">
        <button v-for="f in FILTERS" :key="f.k" type="button" role="tab" :aria-selected="filter === f.k"
                :class="{ on: filter === f.k }" @click="filter = f.k">{{ f.l }}</button>
      </div>
    </header>

    <p v-if="error" class="err" role="alert">
      任务列表加载失败：{{ error }}
      <button type="button" class="btn btn--sm btn--ghost" @click="load">重试</button>
    </p>

    <section class="list" :aria-busy="loading" aria-live="polite">
      <article v-for="t in rows" :key="t.taskId" class="row card" :class="`s-${t.status.toLowerCase()}`">
        <div class="main">
          <header class="top">
            <h2>{{ TASK_LABEL[t.type] ?? t.type }}</h2>
            <span class="chip" :class="{ 'chip--mint': t.status === 'SUCCEEDED', 'chip--danger': t.status === 'FAILED', 'chip--fog': !isTerminal(t) }">
              {{ STATUS_LABEL[t.status] }}
            </span>
            <span v-if="t.origin === 'AI'" class="chip chip--mint">智能模式</span>
            <span v-if="t.attempt > 1" class="chip">第 {{ t.attempt }} 次</span>
          </header>
          <dl class="meta">
            <div><dt>输入</dt><dd :title="inputNames(t)">{{ inputNames(t) }}</dd></div>
            <div><dt>{{ t.startedAt ? '开始' : '提交' }}</dt><dd class="mono">{{ startTime(t) }}</dd></div>
            <div v-if="t.startedAt"><dt>耗时</dt><dd class="mono">{{ elapsed(t.startedAt, t.finishedAt) }}</dd></div>
            <div v-if="!isTerminal(t) && t.stage"><dt>阶段</dt><dd>{{ stageLabel(t.stage) }}</dd></div>
            <div v-if="t.output"><dt>输出</dt><dd :title="t.output.filename">{{ t.output.filename }}</dd></div>
          </dl>
          <TaskProgress v-if="!isTerminal(t)" :task="t" />
          <div v-if="t.status === 'FAILED' || t.status === 'CANCELLED'" class="why" :class="{ bad: t.status === 'FAILED' }">
            <p><strong>原因：</strong>{{ t.message }} <span v-if="t.errorCode" class="mono code">{{ t.errorCode }}</span></p>
            <p v-if="t.errorCode && ERROR_HINT[t.errorCode]"><strong>下一步：</strong>{{ ERROR_HINT[t.errorCode] }}</p>
          </div>
        </div>
        <footer class="acts">
          <button v-if="t.status === 'SUCCEEDED' && (t.output || t.result?.items?.length)" type="button" class="btn btn--sm btn--ghost" @click="previewing = t">查看结果</button>
          <button v-if="t.status === 'SUCCEEDED' && t.output" type="button" class="btn btn--sm" :disabled="acting === t.taskId"
                  @click="act(t.taskId, () => downloadFile(t.output!.fileId, t.output!.filename))">下载</button>
          <button v-if="!isTerminal(t)" type="button" class="btn btn--sm btn--danger" :disabled="acting === t.taskId"
                  @click="act(t.taskId, () => store.cancel(t.taskId), '已请求取消')">取消</button>
          <button v-if="t.status === 'FAILED' || t.status === 'CANCELLED'" type="button" class="btn btn--sm btn--ghost"
                  :disabled="acting === t.taskId || t.errorCode === 'TARGET_UNREACHABLE'"
                  @click="act(t.taskId, () => store.retry(t.taskId), '已重新排队')">重试</button>
          <button type="button" class="trace mono" :title="`追踪号 ${t.taskId}（点击复制）`" @click="copyTrace(t.taskId)">#{{ t.taskId.slice(0, 8) }}</button>
        </footer>
      </article>
      <p v-if="!loading && !rows.length && !error" class="empty">没有符合条件的任务。</p>
    </section>

    <nav v-if="totalPages > 1" class="pager" aria-label="分页">
      <button type="button" class="btn btn--sm btn--ghost" :disabled="page === 0" @click="page--">上一页</button>
      <span class="mono">{{ page + 1 }} / {{ totalPages }} · 共 {{ total }} 个</span>
      <button type="button" class="btn btn--sm btn--ghost" :disabled="page + 1 >= totalPages" @click="page++">下一页</button>
    </nav>

    <ResultPreview :task="previewing" @close="previewing = null" />
  </main>
</template>

<style scoped>
.tc-page {
  display: grid;
  gap: var(--sp-4);
  max-width: 1100px;
  margin: 0 auto;
  padding: var(--sp-6) var(--gutter) var(--sp-8);
}
.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.head h1 {
  font-size: var(--fs-2xl);
}
.head p {
  margin-top: 6px;
  color: var(--text-2);
  font-size: var(--fs-sm);
}
.filters {
  display: flex;
  gap: 4px;
  padding: 3px;
  border-radius: var(--r-pill);
  background: var(--surface-sunk);
  max-width: 100%;
  overflow-x: auto;
}
.filters button {
  min-height: 34px;
  padding: 0 14px;
  border: 0;
  border-radius: var(--r-pill);
  background: transparent;
  font-size: var(--fs-sm);
  color: var(--text-2);
  white-space: nowrap;
}
.filters button.on {
  background: var(--surface);
  color: var(--text);
  box-shadow: var(--shadow-1);
}
.list {
  display: grid;
  gap: var(--sp-3);
}
.row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--sp-4);
  padding: var(--sp-4) var(--sp-5);
}
.s-running,
.s-queued {
  border-color: rgba(70, 103, 125, 0.35);
}
.main {
  display: grid;
  gap: 10px;
  min-width: 0;
}
.top {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.top h2 {
  font-size: var(--fs-md);
  font-weight: 600;
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 20px;
  margin: 0;
  font-size: var(--fs-xs);
}
.meta div {
  display: flex;
  gap: 6px;
  min-width: 0;
  max-width: 100%;
}
.meta dt {
  color: var(--text-3);
}
.meta dd {
  margin: 0;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.why {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--surface-sunk);
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--text-2);
}
.why.bad {
  background: var(--danger-soft);
}
.why.bad p:first-child {
  color: var(--danger);
}
.code {
  margin-left: 6px;
  opacity: 0.7;
}
.acts {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}
.trace {
  margin-top: auto;
  min-height: 32px;
  padding: 0 6px;
  border: 0;
  background: none;
  font-size: 11px;
  color: var(--text-3);
}
.trace:hover {
  color: var(--text);
}
.empty {
  padding: var(--sp-7) 0;
  text-align: center;
  color: var(--text-3);
  font-size: var(--fs-sm);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-3);
  font-size: var(--fs-sm);
}
.err {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: var(--r-sm);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: var(--fs-sm);
}
@media (max-width: 700px) {
  .row {
    grid-template-columns: 1fr;
  }
  .acts {
    flex-direction: row;
    flex-wrap: wrap;
    align-items: center;
  }
  .trace {
    margin: 0 0 0 auto;
  }
}
</style>
