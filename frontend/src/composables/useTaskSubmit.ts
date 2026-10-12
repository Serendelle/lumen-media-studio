import { ref } from 'vue'
import { ApiError } from '@/api/http'
import { useTasksStore } from '@/stores/tasks'
import { useToast } from './useToast'

/** Shared submit flow for every tool: one in-flight request, backend field errors surfaced, task tracked live. */
export function useTaskSubmit() {
  const tasks = useTasksStore()
  const toast = useToast()
  const busy = ref(false)
  const error = ref<string | null>(null)
  const fields = ref<Record<string, string>>({})
  const details = ref<Record<string, any> | null>(null)

  async function run(create: () => Promise<{ taskId: string }>, okText = '任务已提交') {
    if (busy.value) return null
    busy.value = true
    error.value = null
    fields.value = {}
    details.value = null
    try {
      const r = await create()
      await tasks.track(r.taskId)
      toast.ok(okText)
      return r.taskId
    } catch (e) {
      if (e instanceof ApiError) {
        error.value = e.message
        fields.value = e.fields
        details.value = e.details ?? null
      } else {
        error.value = '提交失败，请重试'
      }
      return null
    } finally {
      busy.value = false
    }
  }

  return { busy, error, fields, details, run }
}
