import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { tasksApi } from '@/api/endpoints'
import { openSse, type SseHandle } from '@/api/sse'
import type { TaskView } from '@/api/types'

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

  const list = computed(() => order.value.map((id) => byId.value[id]).filter(Boolean))
  const active = computed(() => list.value.filter((t) => !isTerminal(t)))

  function upsert(t: TaskView) {
    const prev = byId.value[t.taskId]
    // never let a late/duplicate progress value move a running bar backwards
    if (prev && prev.status === t.status && t.status === 'RUNNING' && t.progress < prev.progress && t.attempt === prev.attempt) {
      t = { ...t, progress: prev.progress }
    }
    byId.value[t.taskId] = t
    if (!order.value.includes(t.taskId)) order.value = [t.taskId, ...order.value]
  }

  function onProgress(taskId: string, progress: number, stage: string) {
    const t = byId.value[taskId]
    if (!t || isTerminal(t)) return
    if (progress < t.progress) return // duplicate or out-of-order event
    byId.value[taskId] = { ...t, status: 'RUNNING', progress, stage: stage || t.stage }
  }

  function watch(taskId: string) {
    if (streams.has(taskId)) return
    const handle = openSse(
      `/api/tasks/${encodeURIComponent(taskId)}/events`,
      (msg) => {
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
          streams.delete(taskId)
          // the stream ends at a terminal state; read once to be sure we hold the final result
          void refreshOne(taskId)
        },
        onFatal: () => streams.delete(taskId),
      },
    )
    streams.set(taskId, handle)
  }

  async function refreshOne(taskId: string) {
    try {
      upsert(await tasksApi.get(taskId))
    } catch {
      /* not visible anymore */
    }
  }

  /** Track a task the UI just created. */
  async function track(taskId: string) {
    await refreshOne(taskId)
    watch(taskId)
  }

  async function restore() {
    const page = await tasksApi.list(0, 30)
    for (const t of [...page.content].reverse()) upsert(t)
    order.value = page.content.map((t) => t.taskId).concat(order.value.filter((id) => !page.content.some((t) => t.taskId === id)))
    for (const t of page.content) if (!isTerminal(t)) watch(t.taskId)
  }

  async function cancel(taskId: string) {
    upsert(await tasksApi.cancel(taskId))
  }

  async function retry(taskId: string) {
    upsert(await tasksApi.retry(taskId))
    streams.get(taskId)?.close()
    streams.delete(taskId)
    watch(taskId)
  }

  function stopAll() {
    streams.forEach((s) => s.close())
    streams.clear()
  }

  function reset() {
    stopAll()
    byId.value = {}
    order.value = []
  }

  return { byId, list, active, track, restore, cancel, retry, refreshOne, stopAll, reset }
})
