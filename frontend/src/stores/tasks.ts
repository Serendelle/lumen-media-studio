import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { tasksApi } from '@/api/endpoints'
import { openSse, type SseHandle } from '@/api/sse'
import type { TaskHistoryDeletion, TaskView } from '@/api/types'

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])
export const isTerminal = (t: TaskView) => TERMINAL.has(t.status)

/**
 * Live task state. Every number shown comes from the backend (SSE snapshot + progress events, or a REST read);
 * nothing is simulated. After a page refresh, restore() reloads the list and re-subscribes to unfinished tasks.
 */
export const useTasksStore = defineStore('tasks', () => {
  const byId = ref<Record<string, TaskView>>({})
  const order = ref<string[]>([])
  const streams = new Map<string, SseHandle>()
  const deleted = ref(new Set<string>())
  const clearedBefore = ref(0)
  const confirmedVisible = ref(new Set<string>())
  const historyRevision = ref(0)
  const historySyncError = ref('')
  let sessionVersion = 0
  let restoreRequest = 0
  let writeSequence = 0
  const taskVersions = new Map<string, number>()

  const list = computed(() => order.value.map((id) => byId.value[id]).filter(Boolean))
  const active = computed(() => list.value.filter((t) => !isTerminal(t)))

  function isHistoryVisible(t: TaskView) {
    return !deleted.value.has(t.taskId)
      && (confirmedVisible.value.has(t.taskId) || !isTerminal(t) || !clearedBefore.value || (t.finishedAt ?? t.createdAt) > clearedBefore.value)
  }

  /** Only call for a REST response requested after the current history mutation. */
  function confirmHistory(tasks: TaskView[]) {
    for (const task of tasks) if (!deleted.value.has(task.taskId)) confirmedVisible.value.add(task.taskId)
  }

  function upsert(t: TaskView) {
    if (!isHistoryVisible(t)) return
    const prev = byId.value[t.taskId]
    // never let a late/duplicate progress value move a running bar backwards
    if (prev && prev.status === t.status && t.status === 'RUNNING' && t.progress < prev.progress && t.attempt === prev.attempt) {
      t = { ...t, progress: prev.progress }
    }
    byId.value[t.taskId] = t
    taskVersions.set(t.taskId, ++writeSequence)
    if (!order.value.includes(t.taskId)) order.value = [t.taskId, ...order.value]
  }

  function onProgress(taskId: string, progress: number, stage: string) {
    const t = byId.value[taskId]
    if (!t || isTerminal(t)) return
    if (progress < t.progress) return // duplicate or out-of-order event
    byId.value[taskId] = { ...t, status: 'RUNNING', progress, stage: stage || t.stage }
  }

  function watch(taskId: string) {
    if (streams.has(taskId) || deleted.value.has(taskId) || !byId.value[taskId] || isTerminal(byId.value[taskId])) return
    const session = sessionVersion
    const handle = openSse(
      `/api/tasks/${encodeURIComponent(taskId)}/events`,
      (msg) => {
        if (session !== sessionVersion || streams.get(taskId) !== handle) return
        try {
          const data = JSON.parse(msg.data)
          if (msg.event === 'status') upsert(data as TaskView)
          else if (msg.event === 'progress') onProgress(taskId, data.progress, data.stage)
        } catch {
          /* heartbeat or malformed event */
        }
      },
      {
        onClose: () => {
          if (session !== sessionVersion || streams.get(taskId) !== handle) return
          streams.delete(taskId)
          // the stream ends at a terminal state; read once to be sure we hold the final result
          void refreshOne(taskId)
        },
        onFatal: () => { if (streams.get(taskId) === handle) streams.delete(taskId) },
      },
    )
    streams.set(taskId, handle)
  }

  async function refreshOne(taskId: string) {
    if (deleted.value.has(taskId)) return
    const session = sessionVersion, revision = historyRevision.value
    try {
      const task = await tasksApi.get(taskId)
      if (session === sessionVersion && revision === historyRevision.value) { confirmHistory([task]); upsert(task) }
    } catch (e) {
      if (session === sessionVersion && revision === historyRevision.value && (e as { status?: number })?.status === 404) forget([taskId])
      /* not visible anymore */
    }
  }

  /** Track a task the UI just created. */
  async function track(taskId: string) {
    const session = sessionVersion
    await refreshOne(taskId)
    if (session === sessionVersion) watch(taskId)
  }

  async function restore() {
    const session = sessionVersion, revision = historyRevision.value, request = ++restoreRequest, startedAtWrite = writeSequence
    const [page, live] = await Promise.all([tasksApi.list(0, 30), tasksApi.list(0, 200, ['QUEUED', 'RUNNING'])])
    if (session !== sessionVersion || revision !== historyRevision.value || request !== restoreRequest) return false
    confirmHistory([...page.content, ...live.content])
    const incoming = [...new Map([...page.content, ...live.content].map(t => [t.taskId, t])).values()].filter(isHistoryVisible)
    const visibleIds = new Set(incoming.map(t => t.taskId))
    for (const id of Object.keys(byId.value)) if (!visibleIds.has(id) && (taskVersions.get(id) ?? 0) <= startedAtWrite) {
      closeStream(id)
      delete byId.value[id]
    }
    for (const t of [...incoming].reverse()) {
      if ((taskVersions.get(t.taskId) ?? 0) <= startedAtWrite) upsert(t)
    }
    order.value = incoming.map(t => t.taskId).concat(order.value.filter(id => !visibleIds.has(id) && !!byId.value[id]))
    for (const t of incoming) if (!isTerminal(t)) watch(t.taskId)
    historySyncError.value = ''
    return true
  }

  async function cancel(taskId: string) {
    const session = sessionVersion, revision = historyRevision.value
    const task = await tasksApi.cancel(taskId)
    if (session !== sessionVersion) return
    if (revision !== historyRevision.value) { await refreshOne(taskId); return }
    confirmHistory([task]); upsert(task)
  }

  async function retry(taskId: string) {
    const session = sessionVersion, revision = historyRevision.value
    const task = await tasksApi.retry(taskId)
    if (session !== sessionVersion) return
    if (revision !== historyRevision.value) await refreshOne(taskId)
    else { confirmHistory([task]); upsert(task) }
    closeStream(taskId)
    watch(taskId)
  }

  function closeStream(taskId: string) {
    const stream = streams.get(taskId)
    streams.delete(taskId)
    stream?.close()
  }

  function forget(taskIds: string[]) {
    for (const id of taskIds) {
      deleted.value.add(id)
      closeStream(id)
      delete byId.value[id]
    }
    order.value = order.value.filter(id => !deleted.value.has(id))
    historyRevision.value++
  }

  async function deleteHistory(operation: () => Promise<TaskHistoryDeletion>) {
    const session = sessionVersion
    const result = await operation()
    if (session !== sessionVersion) return result
    if (result.clearedBefore != null) {
      clearedBefore.value = Math.max(clearedBefore.value, result.clearedBefore)
      confirmedVisible.value = new Set()
      stopAll()
      // A concurrent retry can make a formerly terminal task active. Only the old terminal snapshot is hidden.
      for (const task of list.value) if (!isHistoryVisible(task)) {
        closeStream(task.taskId)
        delete byId.value[task.taskId]
      }
      order.value = order.value.filter(id => !!byId.value[id])
      historyRevision.value++
      try {
        for (let attempt = 0; attempt < 3; attempt++) {
          if (await restore() || session !== sessionVersion) break
          if (attempt === 2) historySyncError.value = '历史记录已清空，任务状态仍在变化，请重新同步任务列表。'
        }
      }
      catch { if (session === sessionVersion) historySyncError.value = '历史记录已清空，但任务状态暂时无法同步，请刷新任务列表。' }
    } else forget(result.taskIds)
    return result
  }

  const remove = (taskId: string) => deleteHistory(() => tasksApi.remove(taskId))
  const removeMany = (taskIds: string[]) => deleteHistory(() => tasksApi.removeMany(taskIds))
  const clearHistory = () => deleteHistory(() => tasksApi.clearHistory())

  function stopAll() {
    const pending = [...streams.values()]
    streams.clear()
    pending.forEach(s => s.close())
  }

  function reset() {
    sessionVersion++
    stopAll()
    byId.value = {}
    order.value = []
    deleted.value = new Set()
    clearedBefore.value = 0
    confirmedVisible.value = new Set()
    taskVersions.clear()
    historySyncError.value = ''
    historyRevision.value++
  }

  return { byId, list, active, historyRevision, historySyncError, isHistoryVisible, confirmHistory, track, restore, cancel, retry, refreshOne, remove, removeMany, clearHistory, stopAll, reset }
})
