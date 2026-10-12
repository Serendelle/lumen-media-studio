import { defineStore } from 'pinia'
import { ref } from 'vue'
import { systemApi } from '@/api/endpoints'
import type { SystemCapabilities } from '@/api/types'

/** What this installation can really do — features whose tools are missing are shown as unavailable. */
export const useSystemStore = defineStore('system', () => {
  const caps = ref<SystemCapabilities | null>(null)
  const error = ref<string | null>(null)
  let inflight: Promise<void> | null = null

  function load(force = false) {
    if (caps.value && !force) return Promise.resolve()
    if (!inflight) {
      inflight = systemApi
        .capabilities()
        .then((c) => {
          caps.value = c
          error.value = null
        })
        .catch((e) => {
          error.value = e?.message ?? '无法获取服务能力'
        })
        .finally(() => {
          inflight = null
        })
    }
    return inflight
  }

  function feature(key: string): boolean {
    return caps.value?.features?.[key] ?? false
  }

  return { caps, error, load, feature }
})
